#!/usr/bin/env python3
"""Move `code` to the end of the ROM so that it can grow.

    tools/af_relink.py linker_scripts/jp/animalforest.ld [--next-rom 0x73F4D0]

Run in a decomp checkout after `make extract`, on the linker script splat
generated. Every segment's vrom follows the previous one (`__romPos`), so a
`code` segment one byte longer would move everything after it: dmadata and
the overlay tables would follow (they are linker symbols), but about 4,400
vrom addresses sit as plain numbers in asm data tables (the furniture
tables, the effect table...) and would not. So instead the `.code` block
(and the NOLOAD `.buffers_bss` that follows it in RAM) is cut from its
place and appended after the last segment, and `__romPos` is pinned to
what the next segment's vrom was: nothing else moves, code is free to
grow, and the compressed ROM has no hole, because compress.py lays out only
what dmadata lists. Boot finds code through SEGMENT_ROM_START(code).

Idempotent: a script already relinked is left alone.
"""

import re
import sys

MARK = "/* code and buffers moved to the end of the ROM by tools/af_relink.py */"


def cut_block(lines, head):
    """[start, end) of the segment block that begins with `head`: through
    its trailing `. = ALIGN(., 16);` line."""
    start = lines.index(head)
    end = start
    while lines[end].strip() != ". = ALIGN(., 16);":
        end += 1
    return start, end + 1


def main(argv):
    path = argv[0]
    next_rom = None
    if "--next-rom" in argv:
        next_rom = int(argv[argv.index("--next-rom") + 1], 0)
    text = open(path).read()
    if MARK in text:
        print("af_relink: already relinked")
        return 0
    lines = text.split("\n")

    a, b = cut_block(lines, "    code_ROM_START = __romPos;")
    c, d = cut_block(lines, "    buffers_ROM_START = __romPos;")
    assert c == b + 1, "buffers does not follow code in the script (%d, %d)" % (b, c)
    block = lines[a:d]
    after = lines[d:]
    # The vrom the segment after buffers had: its ROM_START is the first
    # assignment that follows (overlays.yaml's first segment, 0x73F4D0).
    m = re.match(r"\s+(\w+)_ROM_START = __romPos;", after[1])
    assert m, after[1]
    if next_rom is None:
        next_rom = ask_next_rom(m.group(1))
    hole = [
        "    " + MARK,
        "    __romPos = 0x%X; /* %s keeps the cartridge's vrom */" % (next_rom, m.group(1)),
        "    . = ALIGN(., 16);",
    ]
    lines = lines[:a] + hole + after

    tail = lines.index("    /DISCARD/ :")
    lines = lines[:tail] + ["    __romPos = ALIGN(__romPos, 16);", "    . = ALIGN(., 16);", ""] + block + [""] + lines[tail:]
    open(path, "w").write("\n".join(lines))
    print("af_relink: code and buffers moved to the end; %s stays at 0x%X" % (m.group(1), next_rom))
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
