#!/usr/bin/env python3
"""Lay `code` out so that the translation can grow it without moving what
the cartridge's code assumes is in place.

    tools/af_relink.py linker_scripts/jp/animalforest.ld --ref REF_MAP
                       [--map PASS1_MAP] [--moved-out FILE] [--next-rom 0x73F4D0]
                       [--keep-hole OBJECT ...]
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
   is new, leaves its slot (kept as a hole of its old size); one that
   shrank stays, padded to its old size (as does one of the same size
   whose symbols moved inside it: sizes are rounded to 16 bytes). A text
   section that left its slot goes into the slots other text sections
   left, where it fits (pack_holes: as many bytes as can be, a slot of an
   object named with --keep-hole left empty: the checks' hook lives in
   one), and the rest go to a `code_en` region after buffers. Every byte
   of code_en comes off the heap, and so off the arena play gives its
   actors (reference/NOTES.md, "The second review"): what goes into a
   hole is given back. An overlay's sections are held the same
   way: one that shrank is padded in place (its relocation table is made
   anew from its objects, tools/af_ovlreloc.py), one that grew is refused.
   tools/af_shiftcheck.py proves it after the link.

3. code_en is one more piece of the code segment: its ROM image runs on
   from code's (bss and buffers are zeros there), so the boot's one DMA of
   code loads it and its bss arrives zeroed; buffers_VRAM_END follows it,
   so the system heap starts after it. Each moved section is kept inside
   one 64 KB %hi window, since IDO shares `lui`s within an object; the
   kinds (text, data, rodata, bss) and the sections of each are put in the
   order that loses the least to those windows. The
   moved and padded sections are listed in --moved-out (`object section`,
   or `object section shrunk`, per line), for af_shiftcheck.

4. Each --segment NAME=OBJECT becomes a plain (uncompressed, never loaded
   whole) ROM segment after code: NAME_ROM_START/END for a dmadata entry
   (include/tables/dmatables/dmadata_table_jp.h names it), 4 KB aligned
   like the cartridge's own files. The translation's text banks live
   there (tools/af_text.py writes the objects' .bin files).
"""

import bisect
import itertools
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


def code_symbols(linkmap, outs=BLOCK):
    """(address, name) of the symbols of the output sections outs (code's by
    default), in address order."""
    return sorted((a, name) for name, (a, out) in linkmap.symbols.items() if out in outs)


def inside(symbols, addr, size):
    """name -> offset of the symbols in [addr, addr + size)."""
    i = bisect.bisect_left(symbols, (addr, ""))
    out = {}
    while i < len(symbols) and symbols[i][0] < addr + size:
        out[symbols[i][1]] = symbols[i][0] - addr
        i += 1
    return out


def changed_sections(ref, new):
    """(grown, shrunk): the (kind, object, new size, old size) of code's input
    sections that grew or are new (old size 0), in the new map's order, and of
    those that shrank (new size 0 when they are gone). A section of the same
    size whose symbols sit elsewhere inside it counts as shrunk: its size is
    rounded to 16 bytes, so a function 12 bytes shorter can leave it as long
    while every function after it moves (the en build's m_font.o)."""
    old, cur = code_sections(ref), code_sections(new)
    ref_at = {(sec, obj): addr for out, sec, obj, addr, size in ref.inputs if out in BLOCK and size}
    ref_syms, new_syms = code_symbols(ref), code_symbols(new)
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
        else:
            before, after = inside(ref_syms, ref_at[(sec, obj)], was), inside(new_syms, addr, size)
            if any(before[name] != after[name] for name in before.keys() & after.keys()):
                shrunk.append((sec, obj, size, was))
    for (sec, obj), was in old.items():
        if (sec, obj) not in cur:
            shrunk.append((sec, obj, 0, was))
    return grown, shrunk


def changed_overlay_sections(ref, new):
    """(kind, object, new size, old size) of the overlays' input sections that
    shrank, or kept their size with their symbols moved: padded in place like
    code's (their relocation tables are made anew, tools/af_ovlreloc.py). One
    that grew is refused: the ROM after it would move."""
    skip = BLOCK + (".buffers_bss",)
    old = {(sec, obj): (out, addr, size) for out, sec, obj, addr, size in ref.inputs
           if out not in skip and sec in KINDS and size}
    shrunk, seen = [], set()
    for out, sec, obj, addr, size in new.inputs:
        if (sec, obj) not in old or (sec, obj) in seen or not size:
            continue
        seen.add((sec, obj))
        ref_out, ref_addr, was = old[(sec, obj)]
        if size > was:
            raise SystemExit("af_relink: %s(%s) grew from %#x to %#x: an overlay that grows would move the ROM after "
                             "it" % (obj, sec, was, size))
        if size < was:
            shrunk.append((sec, obj, size, was))
            continue
        before = inside(code_symbols(ref, (ref_out,)), ref_addr, was)
        after = inside(code_symbols(new, (out,)), addr, size)
        if any(before[name] != after[name] for name in before.keys() & after.keys()):
            shrunk.append((sec, obj, size, was))
    return shrunk


def window_start(addr, size):
    """addr, or the start of the next 64 KB %hi window when size bytes from addr would cross into it."""
    if size and hi16(addr) != hi16(addr + size - 1):
        return addr + ((0x8000 - (addr & 0xFFFF)) & 0xFFFF)
    return addr


def lay(sections, addr):
    """[(address, section)] of sections (kind, object, size, old) laid one after another from addr, each on
    16 bytes and inside one %hi window, and the address after the last."""
    out = []
    for sec in sections:
        addr = window_start((addr + 15) & ~15, sec[2])
        out.append((addr, sec))
        addr += sec[2]
    return out, addr


def pack_holes(grown, ref_at, keep=()):
    """Where the text sections that left their slots go inside the slots other ones left: ([(start, end,
    [objects whose slots make the run])], {object: address}). The slots that touch are one run; a run of an
    object in keep is left empty. As many bytes as can be are placed (every choice of a run, or none, for
    each section: there are a handful), each section whole inside one %hi window."""
    text = [g for g in grown if g[0] == ".text"]
    slots = sorted((ref_at[(sec, obj)], old, obj) for sec, obj, size, old in text if old and (sec, obj) in ref_at)
    runs = []
    for addr, old, obj in slots:
        if runs and runs[-1][1] == addr:
            runs[-1][1] += old
            runs[-1][2].append(obj)
        else:
            runs.append([addr, addr + old, [obj]])
    usable = [k for k, run in enumerate(runs) if not any(obj in keep for obj in run[2])]
    order = sorted(text, key=lambda g: (-g[2], g[1]))
    best = [0, {}]

    def place(i, cursors, chosen, total):
        if total + sum(g[2] for g in order[i:]) <= best[0]:
            return                                       # cannot beat what is found
        if i == len(order):
            best[0], best[1] = total, dict(chosen)
            return
        sec = order[i]
        for k in usable:
            at = window_start((cursors[k] + 15) & ~15, sec[2])
            if at + sec[2] <= runs[k][1]:
                was = cursors[k]
                cursors[k], chosen[sec[1]] = at + sec[2], at
                place(i + 1, cursors, chosen, total + sec[2])
                cursors[k] = was
                del chosen[sec[1]]
        place(i + 1, cursors, chosen, total)

    place(0, {k: runs[k][0] for k in usable}, {}, 0)
    return [(a, b, objs) for a, b, objs in runs], best[1]


def fixed_block(lines, grown, shrunk, buffers_end, ref_at=None, keep=()):
    """Leave everything of code where the cartridge has it: a grown or new section leaves its slot (a hole of
    its old size) for the slots other grown text sections left, where it fits (ref_at: {(kind, object): its
    address in the reference}; without it none does), or for code_en after buffers; a shrunk one is padded
    to its old size; code's ROM image runs on to code_en's end. buffers_end: where buffers ends in RAM (the
    reference's, unmoved)."""
    lines = list(lines)
    runs, placed = pack_holes(grown, ref_at, keep) if ref_at else ([], {})
    size_of = {obj: size for sec, obj, size, old in grown if sec == ".text"}
    run_of = {}
    for start, end, objs in runs:
        inside = sorted((placed[o], o) for o in placed if start <= placed[o] < end)
        for o in objs:
            run_of[o] = (start, end, objs[0], inside)
    # each section's own line first, as the script has it: a section placed in a run is written a second time
    # there, and its own slot's line must not be taken for that one
    replace = {}
    for sec, obj, size, old in grown:
        key = "        %s(%s);" % (obj, sec)
        if key not in lines:
            continue
        i = lines.index(key)
        where = "its slot stays" if obj not in placed or sec != ".text" else "its slot stays, it is in another's"
        if not old:
            replace[i] = []
        elif sec != ".text" or obj not in run_of or not run_of[obj][3]:
            replace[i] = ["        . += 0x%X; /* af_relink: %s(%s) grew to %#x: %s */" % (old, obj, sec, size, where)]
        else:
            start, end, first, inside = run_of[obj]
            if obj != first:        # the run's slots are filled at its first slot's line
                replace[i] = ["        /* af_relink: %s(%s) grew to %#x: its slot is part of the run filled above */"
                              % (obj, sec, size)]
                continue
            out, at = ["        /* af_relink: %s(%s) grew to %#x: a run of slots, %#x bytes from %#x, holds what "
                       "fits of the sections that grew */" % (obj, sec, size, end - start, start)], start
            for addr, o in inside:
                if addr > at:
                    out.append("        . += 0x%X;" % (addr - at))
                out.append("        %s(.text);" % o)
                at = addr + size_of[o]
            if end > at:
                out.append("        . += 0x%X; /* af_relink: the rest of the run */" % (end - at))
            replace[i] = out
    for sec, obj, size, old in shrunk:
        key = "        %s(%s);" % (obj, sec)
        if key in lines and old > size:
            replace[lines.index(key)] = [key, "        . += 0x%X; /* af_relink: %s(%s) shrank from %#x: padded to its old size */"
                                         % (old - size, obj, sec, old)]
    lines = [x for i, line in enumerate(lines) for x in replace.get(i, [line])]
    region = ["    /* af_relink: code_en, after buffers: the sections that grew and found no slot, and the new ones.",
              "       Nothing the cartridge placed moves (reference/NOTES.md, Shiftability). Loaded with code by",
              "       its one DMA (code's ROM image runs on: bss and buffers are zeros), so its bss arrives zeroed. */",
              "    code_en_VRAM = ALIGN(., 16);",
              "    .code_en code_en_VRAM : AT(code_ROM_START + (code_en_VRAM - code_VRAM)) SUBALIGN(16)",
              "    {",
              "        FILL(0x00000000);"]
    first = (buffers_end + 15) & ~15
    out_of = {kind: [g for g in grown if g[0] == kind and not (kind == ".text" and g[1] in placed)] for kind in KINDS}
    # the order of the kinds, and of each kind's sections (as they come, or by size), that ends lowest
    choice = None
    for kinds in itertools.permutations(KINDS):
        for within in (None, False, True):
            addr, laid = first, []
            for kind in kinds:
                secs = out_of[kind] if within is None else sorted(out_of[kind], key=lambda g: (g[2], g[1]), reverse=within)
                got, addr = lay(secs, addr)
                laid.append((kind, got))
            if choice is None or addr < choice[0]:
                choice = (addr, laid)
    addr = first
    for kind, got in choice[1]:
        region.append("        code_en_%s_START = .;" % kind[1:].upper())
        for at, (sec, obj, size, old) in got:
            start = (addr + 15) & ~15
            if at > start:
                region.append("        . += 0x%X; /* af_relink: keeps %s(%s) inside one 64 KB %%hi window */" % (at - start, obj, sec))
            region.append("        %s(%s);" % (obj, sec))
            addr = at + size
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
    keep = []
    i = 0
    while i < len(argv):
        if argv[i] == "--segment":
            segs.append(argv[i + 1])
            i += 2
        elif argv[i] == "--keep-hole":
            keep.append(argv[i + 1])
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
    grown, shrunk, placed = [], [], {}
    if ref_path and map_path:
        ref = LinkMap(ref_path)
        first = LinkMap(map_path)
        grown, shrunk = changed_sections(ref, first)
        shrunk += changed_overlay_sections(ref, first)
        ref_at = {(sec, obj): addr for out, sec, obj, addr, size in ref.inputs if out in BLOCK and size}
        lines = fixed_block(lines, grown, shrunk, ref.marks["buffers_VRAM_END"], ref_at, keep)
        placed = pack_holes(grown, ref_at, keep)[1]
    if segs:
        lines = segments(lines, segs)
    if moved_out:
        listed = ["%s %s\n" % (obj, sec) for sec, obj, _, _ in grown] + \
                 ["%s %s shrunk\n" % (obj, sec) for sec, obj, _, _ in shrunk]
        open(moved_out, "w").write("".join(sorted(set(listed))))
    open(path, "w").write("\n".join(lines))
    out = len(grown) - len(placed)
    print("af_relink: code and buffers moved to the end of the ROM (%s stays at 0x%X); nothing moves in RAM; "
          "%d section%s in code_en, %d in the slots others left, %d padded; %d plain segment%s after code"
          % (segment, next_rom, out, "" if out == 1 else "s", len(placed), len(shrunk),
             len(segs), "" if len(segs) == 1 else "s"))
    for sec, obj, size, old in grown + shrunk:
        print("  %s(%s): %#x -> %#x bytes%s" % (obj, sec, old, size, " (at %#x, in a slot)" % placed[obj]
                                             if sec == ".text" and obj in placed else ""))
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
