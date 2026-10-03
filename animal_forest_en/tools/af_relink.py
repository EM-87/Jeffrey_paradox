#!/usr/bin/env python3
"""Lay `code` out so that the translation can grow it without moving what
the cartridge's code assumes is in place.

    tools/af_relink.py linker_scripts/jp/animalforest.ld --ref REF_MAP
                       [--map PASS1_MAP] [--moved-out FILE] [--next-rom 0x73F4D0]
                       [--segment NAME=build/assets/jp/en/file.o ...] [--pristine FILE]

Run in a decomp checkout after `make extract`, on the linker script splat
generated; a pristine copy is kept under build/ (`--pristine` names
another place) and every run starts from that copy, so the result never
depends on the previous run.
Four things are done (reference/NOTES.md, "Shiftability"):

1. The `.code` block (and the NOLOAD `.buffers_bss` that follows it in
   RAM) is cut from its place in the ROM and appended after the last
   segment, with `__romPos` pinned to what the next segment's vrom was.
   Every other segment's vrom follows the previous one, and about 4,400
   vrom addresses sit as plain numbers in asm data tables, so nothing
   before code may move; at the end of the ROM code is free to grow.

2. Nothing of code moves in RAM either: not its text, not its data block
   (.data, .rodata, .bss and buffers). The cartridge's own files hold
   addresses the linker never sees: 2,348 texture loads in 204 field
   models point at code's bss buffers by their RAM address (NOTES), and
   the asm shares `lui` halves between symbols. So with --map (the map of
   a first link), an input section of code that grew against REF_MAP, or
   is new, leaves its slot (kept as a hole of its old size) for a
   `code_en` region after buffers; one that shrank stays, padded to its
   old size. tools/af_shiftcheck.py proves it after the link.

3. code_en is one more piece of the code segment: its ROM image runs on
   from code's (bss and buffers are zeros there), so the boot's one DMA of
   code loads it and its bss arrives zeroed; buffers_VRAM_END follows it,
   so the system heap starts after it. Each moved section is kept inside
   one 64 KB %hi window, since IDO shares `lui`s within an object. The
   moved and padded sections are listed in --moved-out (`object section`,
   or `object section shrunk`, per line), for af_shiftcheck.

4. Each --segment NAME=OBJECT becomes a plain (uncompressed, never loaded
   whole) ROM segment after code: NAME_ROM_START/END for a dmadata entry
   (include/tables/dmatables/dmadata_table_jp.h names it), 4 KB aligned
   like the cartridge's own files. The translation's text banks live
   there (tools/af_text.py writes the objects' .bin files).
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from af_shiftcheck import LinkMap  # noqa: E402

MARK = "/* code and buffers moved to the end of the ROM by tools/af_relink.py */"
BLOCK = (".code", ".code_bss")
KINDS = (".text", ".data", ".rodata", ".bss")


def hi16(x):
    return ((x + 0x8000) >> 16) & 0xFFFF


def cut_block(lines, head):
    """[start, end) of the segment block that begins with `head`: through
    its trailing `. = ALIGN(., 16);` line."""
    start = lines.index(head)
    end = start
    while lines[end].strip() != ". = ALIGN(., 16);":
        end += 1
    return start, end + 1


def relocate(lines, next_rom):
    a, b = cut_block(lines, "    code_ROM_START = __romPos;")
    c, d = cut_block(lines, "    buffers_ROM_START = __romPos;")
    assert c == b + 1, "buffers does not follow code in the script (%d, %d)" % (b, c)
    block, after = lines[a:d], lines[d:]
    m = re.match(r"\s+(\w+)_ROM_START = __romPos;", after[1])
    assert m, after[1]
    if next_rom is None:
        next_rom = ask_next_rom(m.group(1))
    hole = ["    " + MARK,
            "    __romPos = 0x%X; /* %s keeps the cartridge's vrom */" % (next_rom, m.group(1)),
            "    . = ALIGN(., 16);"]
    lines = lines[:a] + hole + after
    tail = lines.index("    /DISCARD/ :")
    return lines[:tail] + ["    __romPos = ALIGN(__romPos, 16);", "    . = ALIGN(., 16);", ""] + block + [""] + lines[tail:], m.group(1), next_rom


def code_sections(linkmap):
    """(kind, object) -> size for code's input sections, text and data block."""
    return {(sec, obj): size for out, sec, obj, addr, size in linkmap.inputs
            if out in BLOCK and sec in KINDS and size}


def changed_sections(ref, new):
    """(grown, shrunk): the (kind, object, new size, old size) of code's input
    sections that grew or are new (old size 0), in the new map's order, and of
    those that shrank (new size 0 when they are gone)."""
    old, cur = code_sections(ref), code_sections(new)
    grown, shrunk, seen = [], [], set()
    for o, sec, obj, addr, size in new.inputs:
        if o not in BLOCK or sec not in KINDS or not size or (sec, obj) in seen:
            continue
        seen.add((sec, obj))
        was = old.get((sec, obj), 0)
        if size > was:
            grown.append((sec, obj, size, was))
        elif size < was:
            shrunk.append((sec, obj, size, was))
    for (sec, obj), was in old.items():
        if (sec, obj) not in cur:
            shrunk.append((sec, obj, 0, was))
    return grown, shrunk


def fixed_block(lines, grown, shrunk, buffers_end):
    """Leave everything of code where the cartridge has it: a grown or new
    section goes to code_en after buffers (its slot a hole of its old size), a
    shrunk one is padded to its old size; code's ROM image runs on to code_en's
    end. buffers_end: where buffers ends in RAM (the reference's, unmoved)."""
    lines = list(lines)
    for sec, obj, size, old in grown:
        key = "        %s(%s);" % (obj, sec)
        if key in lines:
            i = lines.index(key)
            lines[i:i + 1] = ["        . += 0x%X; /* af_relink: %s(%s) grew to %#x: it is in code_en, its slot stays */"
                              % (old, obj, sec, size)] if old else []
    for sec, obj, size, old in shrunk:
        key = "        %s(%s);" % (obj, sec)
        if key in lines:
            i = lines.index(key)
            lines[i + 1:i + 1] = ["        . += 0x%X; /* af_relink: %s(%s) shrank from %#x: padded to its old size */"
                                  % (old - size, obj, sec, old)]
    region = ["    /* af_relink: code_en, after buffers: the sections that grew and the new ones. Nothing the",
              "       cartridge placed moves (reference/NOTES.md, Shiftability). Loaded with code by its one",
              "       DMA (code's ROM image runs on: bss and buffers are zeros), so its bss arrives zeroed. */",
              "    code_en_VRAM = ALIGN(., 16);",
              "    .code_en code_en_VRAM : AT(code_ROM_START + (code_en_VRAM - code_VRAM)) SUBALIGN(16)",
              "    {",
              "        FILL(0x00000000);"]
    addr = (buffers_end + 15) & ~15
    for kind in KINDS:
        region.append("        code_en_%s_START = .;" % kind[1:].upper())
        for sec, obj, size, old in grown:
            if sec != kind:
                continue
            addr = (addr + 15) & ~15
            if hi16(addr) != hi16(addr + size - 1):
                pad = (0x8000 - (addr & 0x7FFF)) & 0x7FFF
                region.append("        . += 0x%X; /* af_relink: keeps %s(%s) inside one 64 KB %%hi window */" % (pad, obj, sec))
                addr += pad
            region.append("        %s(%s);" % (obj, sec))
            addr += size
        region.append("        code_en_%s_END = .;" % kind[1:].upper())
    region += ["    }",
               "    __romPos = code_ROM_START + (. - code_VRAM);",
               "    code_ROM_END = __romPos;"]
    lines.remove("    code_ROM_END = __romPos;")
    b = lines.index("    buffers_ROM_END = __romPos;")
    return lines[:b] + region + lines[b:]


def segments(lines, specs):
    """Plain ROM segments NAME=OBJECT appended after code and buffers."""
    block = []
    for spec in specs:
        name, _, obj = spec.partition("=")
        block += ["    /* af_relink: %s, a plain segment of the translation (tools/af_text.py) */" % name,
                  "    __romPos = ALIGN(__romPos, 0x1000);",
                  "    %s_ROM_START = __romPos;" % name,
                  "    .%s : AT(%s_ROM_START) SUBALIGN(16)" % (name, name),
                  "    {",
                  "        FILL(0x00000000);",
                  "        %s_DATA_START = .;" % name,
                  "        %s(.data);" % obj,
                  "        %s_DATA_END = .;" % name,
                  "    }",
                  "    __romPos += SIZEOF(.%s);" % name,
                  "    %s_ROM_END = __romPos;" % name,
                  "    __romPos = ALIGN(__romPos, 16);",
                  "    . = ALIGN(., 16);",
                  ""]
    tail = lines.index("    /DISCARD/ :")
    return lines[:tail] + block + lines[tail:]


def main(argv):
    path, ref_path, map_path, moved_out, next_rom, segs, pristine = None, None, None, None, None, [], None
    i = 0
    while i < len(argv):
        if argv[i] == "--segment":
            segs.append(argv[i + 1])
            i += 2
        elif argv[i] == "--pristine":
            pristine = argv[i + 1]
            i += 2
        elif argv[i] == "--ref":
            ref_path = argv[i + 1]
            i += 2
        elif argv[i] == "--map":
            map_path = argv[i + 1]
            i += 2
        elif argv[i] == "--moved-out":
            moved_out = argv[i + 1]
            i += 2
        elif argv[i] == "--next-rom":
            next_rom = int(argv[i + 1], 0)
            i += 2
        else:
            path = argv[i]
            i += 1
    pristine = pristine or os.path.join("build", path.lstrip("/") + ".splat")
    if not os.path.exists(pristine):
        if MARK in open(path).read():
            raise SystemExit("af_relink: %s is already relinked and %s is missing: run make extract" % (path, pristine))
        os.makedirs(os.path.dirname(pristine) or ".", exist_ok=True)
        open(pristine, "w").write(open(path).read())
    lines = open(pristine).read().split("\n")

    lines, segment, next_rom = relocate(lines, next_rom)
    grown, shrunk = [], []
    if ref_path and map_path:
        ref = LinkMap(ref_path)
        grown, shrunk = changed_sections(ref, LinkMap(map_path))
        lines = fixed_block(lines, grown, shrunk, ref.marks["buffers_VRAM_END"])
    if segs:
        lines = segments(lines, segs)
    if moved_out:
        listed = ["%s %s\n" % (obj, sec) for sec, obj, _, _ in grown] + \
                 ["%s %s shrunk\n" % (obj, sec) for sec, obj, _, _ in shrunk]
        open(moved_out, "w").write("".join(sorted(set(listed))))
    open(path, "w").write("\n".join(lines))
    print("af_relink: code and buffers moved to the end of the ROM (%s stays at 0x%X); nothing moves in RAM; "
          "%d section%s in code_en, %d padded; %d plain segment%s after code"
          % (segment, next_rom, len(grown), "" if len(grown) == 1 else "s", len(shrunk),
             len(segs), "" if len(segs) == 1 else "s"))
    for sec, obj, size, old in grown + shrunk:
        print("  %s(%s): %#x -> %#x bytes" % (obj, sec, old, size))
    return 0


def ask_next_rom(segment):
    """The segment's start from the splat yamls (the cartridge's vrom)."""
    import glob
    for y in glob.glob("yamls/*/*.yaml"):
        found = False
        for line in open(y):
            if re.match(r"\s*-\s*name:\s*%s\s*$" % re.escape(segment), line):
                found = True
            elif found:
                m = re.match(r"\s*start:\s*(0x[0-9A-Fa-f]+)", line)
                if m:
                    return int(m.group(1), 16)
    raise SystemExit("no start for %s in the yamls; give --next-rom" % segment)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
