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
Three things are done (reference/NOTES.md, "Shiftability"):

1. The `.code` block (and the NOLOAD `.buffers_bss` that follows it in
   RAM) is cut from its place in the ROM and appended after the last
   segment, with `__romPos` pinned to what the next segment's vrom was.
   Every other segment's vrom follows the previous one, and about 4,400
   vrom addresses sit as plain numbers in asm data tables, so nothing
   before code may move; at the end of the ROM code is free to grow.

2. The cartridge's data block (.data, .rodata, .bss of code, and buffers)
   is kept at its original address modulo 0x10000: text may grow, and the
   block then moves as one piece by a multiple of 64 KB, which keeps every
   %hi/%lo pair the asm shares between two symbols and every distance
   inside it (tools/af_shiftcheck.py proves it after the link).

3. With --map (the map of a first link), an object whose .data, .rodata or
   .bss changed size against REF_MAP, or is new, is taken out of the block
   (its old slot padded to its old size) and placed in a `code_en` region
   between the text and the block; each such section is kept inside one
   64 KB %hi window, since IDO shares `lui`s within an object. code_en is
   loaded from the ROM like the rest of code, so its bss arrives zeroed.
   The moved sections are listed in --moved-out (`object section` per
   line), for af_shiftcheck; the object's other sections stay in place.

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
KINDS = (".data", ".rodata", ".bss")


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


def block_sections(linkmap):
    """(kind, object) -> size for the data block's input sections."""
    lo, hi = linkmap.marks["code_DATA_START"], linkmap.marks["buffers_VRAM_END"]
    return {(sec, obj): size for out, sec, obj, addr, size in linkmap.inputs
            if out in BLOCK and sec in KINDS and lo <= addr < hi and size}


def moved_sections(ref, new):
    """The (kind, object, new size, old size) that changed size or are new,
    in the new map's order; old size 0 when the object is new."""
    old = block_sections(ref)
    out = []
    for o, sec, obj, addr, size in new.inputs:
        if o in BLOCK and sec in KINDS and size and old.get((sec, obj)) != size:
            out.append((sec, obj, size, old.get((sec, obj), 0)))
    for (sec, obj), size in old.items():          # shrunk to nothing: the slot still needs its hole
        if not any(s == sec and ob == obj for s, ob, _, _ in out) and \
                not any(o in BLOCK and s == sec and ob == obj and n for o, s, ob, _, n in new.inputs):
            out.append((sec, obj, 0, size))
    return out


def rigid_block(lines, data_off, moved, text_end):
    """Insert the code_en region and the pad that keeps the block's address modulo 0x10000.
    data_off is the block's original offset from the start of .code: inside an output
    section `.` is that offset, not the address (ld refuses ADDR(.code) there and
    ABSOLUTE(.) wraps the section around the address space)."""
    begin = lines.index("        code_TEXT_END = .;")
    # take the moved objects' lines out of the block, leaving holes of their old size
    for sec, obj, size, old in moved:
        key = "        %s(%s);" % (obj, sec)
        if key in lines:
            i = lines.index(key)
            hole = ["        . += 0x%X; /* af_relink: %s(%s) is in code_en now, its size changed */" % (old, obj, sec)] if old else []
            lines[i:i + 1] = hole
    region = ["        /* af_relink: the translation's data: objects whose data changed size, and new ones */"]
    addr = (text_end + 15) & ~15
    for kind in KINDS:
        name = "code_en_%s_START = .;" % kind[1:].upper()
        region.append("        " + name)
        for sec, obj, size, old in moved:
            if sec != kind or not size:
                continue
            addr = (addr + 15) & ~15
            if hi16(addr) != hi16(addr + size - 1):
                pad = (0x8000 - (addr & 0x7FFF)) & 0x7FFF
                region.append("        . += 0x%X; /* af_relink: keeps %s(%s) inside one 64 KB %%hi window */" % (pad, obj, sec))
                addr += pad
            region.append("        %s(%s);" % (obj, sec))
            addr += size
        region.append("        code_en_%s_END = .;" % kind[1:].upper())
    region += ["        /* af_relink: the cartridge's data block keeps its address modulo 0x10000: every",
               "           address pair it shares and every distance inside it survive (NOTES, Shiftability) */",
               "        . += (0x10000 - ((. - 0x%X) & 0xFFFF)) & 0xFFFF;" % data_off]
    begin = lines.index("        code_TEXT_END = .;")
    return lines[:begin + 1] + region + lines[begin + 1:]


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
    moved = []
    if ref_path:
        ref = LinkMap(ref_path)
        if map_path:
            new = LinkMap(map_path)
            moved = moved_sections(ref, new)
            text_end = new.marks["code_TEXT_END"]
        else:
            text_end = ref.marks["code_TEXT_END"]
        lines = rigid_block(lines, ref.marks["code_DATA_START"] - ref.marks["code_TEXT_START"], moved, text_end)
    if segs:
        lines = segments(lines, segs)
    if moved_out:
        open(moved_out, "w").write("".join(sorted(set("%s %s\n" % (obj, sec) for sec, obj, _, _ in moved))))
    open(path, "w").write("\n".join(lines))
    print("af_relink: code and buffers moved to the end (%s stays at 0x%X); data block kept modulo 0x10000; "
          "%d object section%s in code_en; %d plain segment%s after code"
          % (segment, next_rom, len(moved), "" if len(moved) == 1 else "s", len(segs), "" if len(segs) == 1 else "s"))
    for sec, obj, size, old in moved:
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
