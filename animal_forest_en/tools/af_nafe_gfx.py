#!/usr/bin/env python3
"""The labels the AF Project's 2010 patch drew in English, for the en build.

    tools/af_nafe_gfx.py BASEROM NAFE_ROM EN_DIR

The 2010 patch (NAFE-WIP-2_12_2010.ups by Zoinkity and the AF Project; make
nafe builds its ROM from the user's copy) redrew some of the menus' word
textures in English, each in its place, size and format: the dial's modes
and buttons, the inventory's and the map's labels, the bulletin board's,
the bank's, the Controller Pak's, the catalog's (reference/NOTES.md, "The
2010 patch's labels"). GameCube has no counterpart for most of these
screens. LABELS names each by its segment, offset, format and size; this
takes the patch's texels from its ROM's file and writes them over the en
tree's extracted copy of the segment (EN_DIR/assets/jp/..., which splat
rewrites at every extract), after checking that the cartridge's display
lists in that segment and the patch's both draw a texture there in that
format and size, and that the patch redrew it. Nothing else of the patch is taken: its code,
text, font and display-list changes stay out. Run at make rom-en when the
patch's ROM is there; nothing it reads or writes enters the repository.
"""

import collections
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nafe_diff  # noqa: E402

FORMATS = {"I": 4, "IA": 3}
SIZES = {4: 0, 8: 1}

# segment (vrom, the en tree's file) -> [(offset, format, bits, width, height, what it labels)]
LABELS = {
    (0xA40000, "assets/jp/segment_00A40000.bin"): [   # the text editor's dial (ovl__0078CB80)
        (0x1B48, "I", 4, 48, 16, "the symbols' mode"),
        (0x1CC8, "I", 4, 48, 16, "the letters' mode"),
        (0x1E48, "I", 4, 48, 16, "the digits' mode"),
        (0x1FC8, "IA", 8, 64, 16, "the button that decides"),
        (0x5448, "IA", 8, 64, 16, "the cursor's buttons"),
        (0x5848, "IA", 8, 32, 16, "the button that erases"),
        (0x5A48, "IA", 8, 64, 16, "the button that ends"),
        (0x15048, "IA", 8, 64, 16, "the button that converts"),
    ],
    (0xA30000, "assets/jp/segment_00A30000.bin"): [   # the inventory (inventory_ovl)
        (0x00B8, "I", 4, 32, 16, "the town"),
        (0x1D28, "I", 4, 128, 32, "the insects caught"),
        (0xAA00, "I", 4, 32, 16, "the bells"),
        (0xAD00, "I", 4, 16, 64, "the things carried, upright"),
        (0xAF00, "I", 4, 64, 16, "the letters"),
        (0xB100, "I", 4, 64, 16, "the money"),
        (0xE438, "I", 4, 160, 32, "the fish caught"),
    ],
    (0xAAD000, "assets/jp/segment_00AAD000.bin"): [   # the map (map_ovl)
        (0x11C8, "I", 4, 32, 16, "the word after the town's name, drawn blank"),
        (0x7B60, "I", 4, 64, 16, "the guide"),
        (0xB260, "I", 4, 64, 16, "the block"),
        (0xB460, "I", 4, 64, 16, "the number"),
    ],
    (0xA7C000, "assets/jp/segment_00A7C000.bin"): [   # ovl__00789B60
        (0x35B0, "I", 4, 64, 16, "the mailbox"),
    ],
    (0xABA000, "assets/jp/segment_00ABA000.bin"): [   # the bulletin board (ovl__00797A50)
        (0x0858, "IA", 8, 64, 16, "the newest"),
        (0x0C58, "IA", 8, 64, 16, "the first entry"),
        (0x1058, "IA", 8, 64, 16, "quitting"),
        (0x1458, "IA", 8, 64, 16, "writing"),
    ],
    (0xACC000, "assets/jp/segment_00ACC000.bin"): [   # an amount to enter (ovl__0079B120)
        (0x1288, "I", 4, 96, 16, "the money carried"),
        (0x1588, "I", 4, 96, 16, "the money to transfer"),
        (0x2088, "I", 4, 32, 16, "the bells"),
        (0x2A08, "I", 4, 48, 16, "what is left"),
    ],
    (0xAD1000, "assets/jp/segment_00AD1000.bin"): [   # ovl__0079C020
        (0x43C8, "I", 4, 64, 16, "ending"),
        (0x67C8, "IA", 8, 64, 16, "playing"),
        (0x6C48, "IA", 8, 64, 16, "throwing out"),
    ],
    (0xB1A000, "assets/jp/segment_00B1A000.bin"): [   # the Controller Pak's notes (ovl__007A10E0)
        (0x7560, "I", 4, 32, 16, "ending"),
        (0x76E0, "I", 4, 48, 16, "the notes"),
        (0xB860, "I", 4, 32, 16, "the number"),
        (0xB960, "I", 4, 48, 16, "the pages"),
        (0xBAE0, "I", 4, 48, 16, "what is left"),
    ],
    (0xB2A000, "assets/jp/segment_00B2A000.bin"): [   # the catalog (catalog_ovl)
        (0x09B8, "IA", 8, 64, 16, "the first page"),
        (0x0DB8, "IA", 8, 64, 16, "the last page"),
        (0x4888, "I", 4, 32, 16, "the bells"),
    ],
    (0xA22000, "assets/jp/objects/gameplay_keep.bin"): [   # gameplay_keep (segment 4)
        (0x08F8, "IA", 8, 64, 16, "the camera"),
        (0xB4D0, "I", 4, 96, 16, "the money carried"),
        (0xB7D0, "I", 4, 32, 16, "the bells"),
    ],
}


def file_at(rom, vrom):
    for e in nafe_diff.dma_entries(rom):
        if e[0] == vrom:
            return nafe_diff.file_data(rom, e)
    raise SystemExit("af_nafe_gfx: no file at vrom %#x" % vrom)


def drawn(data):
    """{offset: (format, bits, width, height)} of the textures the segment's own display lists draw
    (the load's address in the segment most of its loads name, the render tile's format, its size)."""
    loads = collections.Counter(struct.unpack(">I", data[at + 4:at + 8])[0] >> 24 for at in range(0, len(data) - 8, 8)
                                if data[at] == 0xFD and (struct.unpack(">I", data[at + 4:at + 8])[0] & 0xFFFFFF) < len(data))
    seg = loads.most_common(1)[0][0] if loads else None
    out, timg, load, render = {}, None, None, None
    names = {v: k for k, v in FORMATS.items()}
    for at in range(0, len(data) - 8, 8):
        w0, w1 = struct.unpack(">II", data[at:at + 8])
        op = w0 >> 24
        if op == 0xFD:
            timg = w1
        elif op in (0xF3, 0xF4) and timg is not None and (timg >> 24) == seg:
            load = timg & 0xFFFFFF
        elif op == 0xF5 and ((w1 >> 24) & 7) == 0:
            render = ((w0 >> 21) & 7, (w0 >> 19) & 3)
        elif op == 0xF2 and ((w1 >> 24) & 7) == 0 and load is not None and render is not None:
            w, h = (((w1 >> 12) & 0xFFF) >> 2) + 1, ((w1 & 0xFFF) >> 2) + 1
            if render[0] in names:
                out[load] = (names[render[0]], 4 << render[1], w, h)
            load = None
    return out


def main(argv):
    rom_path, nafe_path, en = argv
    rom, nafe = open(rom_path, "rb").read(), open(nafe_path, "rb").read()
    count = 0
    for (vrom, path), labels in LABELS.items():
        ours, theirs = file_at(rom, vrom), file_at(nafe, vrom)
        textures, their_textures = drawn(ours), drawn(theirs)
        target = os.path.join(en, path)
        data = bytearray(open(target, "rb").read())
        if len(data) != len(ours):
            raise SystemExit("af_nafe_gfx: %s is %#x bytes, the cartridge's file %#x" % (path, len(data), len(ours)))
        for at, fmt, bits, w, h, what in labels:
            for whose, found in (("the cartridge's", textures), ("the 2010 patch's", their_textures)):
                if found.get(at) != (fmt, bits, w, h):
                    raise SystemExit("af_nafe_gfx: %s %#x (%s): %s lists draw %s there, not %s%d %dx%d"
                                     % (path, at, what, whose, found.get(at), fmt, bits, w, h))
            size = w * h * bits // 8
            if theirs[at:at + size] == ours[at:at + size]:
                raise SystemExit("af_nafe_gfx: %s %#x (%s): the 2010 patch left it as it was" % (path, at, what))
            data[at:at + size] = theirs[at:at + size]
            count += 1
        open(target, "wb").write(bytes(data))
    print("af_nafe_gfx: %d labels the 2010 patch drew, in %d segments" % (count, len(LABELS)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
