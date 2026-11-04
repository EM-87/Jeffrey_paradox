#!/usr/bin/env python3
"""The cartridge's own data that a file we decompiled includes, written from
the user's dump at extract time.

    tools/af_dumpdata.py DECOMP_DIR

A file whose data we write in C (so that one object holds its .data, as the
cartridge's did: IDO and the assembler both pad a section to 16 bytes, so
splitting the data between objects moves it) still needs bytes this
repository must not hold: text (CLAUDE.md, rule 2). Those come from the
decompressed dump the decomp's setup leaves in DECOMP_DIR
(baseroms/jp/baserom-decompressed.z64, the yaml's offsets) and are written
as C initializers under DECOMP_DIR/assets/jp, which splat's extract
regenerates and git ignores; the Makefile runs this after each extract.
"""

import os
import sys

ROM = "baseroms/jp/baserom-decompressed.z64"

# include file -> [(symbol, ROM offset, size)], in the order the C file defines them
FILES = {
    # the name entry's titles and the destination's suffix (ledit_ovl's .data: m_ledit_ovl.c)
    "assets/jp/overlays/submenu/ledit_ovl/m_ledit_ovl_titles.inc.c": [
        ("D_80884D20_jp", 0x78C990, 8),
        ("D_80884D28_jp", 0x78C998, 12),
        ("D_80884D34_jp", 0x78C9A4, 12),
        ("D_80884D40_jp", 0x78C9B0, 12),
        ("D_80884D4C_jp", 0x78C9BC, 12),
    ],
    "assets/jp/overlays/submenu/ledit_ovl/m_ledit_ovl_suffix.inc.c": [
        ("D_80884E34_jp", 0x78CAA4, 4),
    ],
}


def initializers(rom, entries):
    lines = ["/* Written by tools/af_dumpdata.py from the user's dump; not part of the repository */"]
    for name, at, size in entries:
        data = rom[at:at + size]
        lines.append("u8 %s[%d] = { %s };" % (name, size, ", ".join("0x%02X" % b for b in data)))
    return "\n".join(lines) + "\n"


def main(argv):
    decomp = argv[0]
    rom = open(os.path.join(decomp, ROM), "rb").read()
    for path, entries in FILES.items():
        out = os.path.join(decomp, path)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        open(out, "w").write(initializers(rom, entries))
    print("af_dumpdata: %d files of the dump's data in %s" % (len(FILES), os.path.join(decomp, "assets", "jp")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
