#!/usr/bin/env python3
"""Our English for the words on the furniture, for the en build.

    tools/af_own_ftr.py BASEROM EN_DIR

Seventeen pieces of furniture carry words one can read in Japanese, one
texture each (reference/NOTES.md, "Our own furniture words"): a road works
set (a safety barricade in four colours, its two signs and a detour map), a
fire warning on a drum, a bus stop's sign, a public bath's price board, a
red telephone's coin label, a drinks label, a milk box, two shop signs, a
traffic sign's line, a sticker and a stone plaque. Ours, in each texture's
own inks: the barricade's SAFETY FIRST; SORRY! ROAD WORK AHEAD on the sign
where a worker bows, DETOUR over the map; the distance sign keeps its 50m
and an arrow points ahead where its kanji was; NO FIRE; BUS and STOP; the
bath's prices with an adult and a child for the two kanji pairs, BATH
over them; the coin label's 10 without its currency; ROYAL A; MILK on the
box; CARDS and CASH on the two signs; GO SLOW; N64 on the sticker (whose
katakana are the console's nickname) and NINTENDO on the plaque (the
company's name, read right to left). The small print a few texels high
that no one can read is kept where it is not the words' (the faces'
captions, a map's dotted roads).

Each furniture object is a segment of its own (a CI4 texture block in
segment 6, its palette, display lists that set render tiles by their TMEM
address: the room code loads the block whole, ac_sum_blue_bureau01.c
aSumBlueBureau01_dw). Each texture is checked first: the object's lists
set a CI4 render tile of this size at its TMEM address; the texels are the
cartridge's (their md5: a check, not a copy) and so the palette its inks
were chosen in. The block is loaded with LoadBlock, so its odd rows are
kept with their 32-bit words swapped (splat's ci4swap): read and written
so. Written over the en tree's .bin of the object (splat leaves these
segments unextracted), at make rom-en.

The operations, in order, on the texture's texels: paint a box one index;
clean a box (each of its ink texels the nearest texel of its row that is
not ink, the left one where two are as near: tools/af_own_signs.py's way
for a cloth or a stone); restripe a box (each texel the one above and to
the right: the barricade's stripes run on under where its words were);
write a word in one of our two fonts (3x5, and 7 rows tall for the large
ones), placed at a point or centred in a box, with a shadow or an outline;
lay a small picture (an arrow, the bath's two figures). Nothing of the
cartridge's enters the repository: the fonts and pictures are ours, the
cleaned grounds are made from the user's dump at make rom-en.
"""

import hashlib
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_gc_signs  # noqa: E402

# Our fonts: each glyph its rows, '#' inked. FONT5: capitals and digits 5 rows tall; FONT7: the letters of
# the large words, 7 rows tall.
FONT5 = {
    "A": [".#.", "#.#", "###", "#.#", "#.#"], "B": ["##.", "#.#", "##.", "#.#", "##."],
    "C": [".##", "#..", "#..", "#..", ".##"], "D": ["##.", "#.#", "#.#", "#.#", "##."],
    "E": ["###", "#..", "##.", "#..", "###"], "F": ["###", "#..", "##.", "#..", "#.."],
    "G": [".##", "#..", "#.#", "#.#", ".##"], "H": ["#.#", "#.#", "###", "#.#", "#.#"],
    "I": ["###", ".#.", ".#.", ".#.", "###"], "K": ["#.#", "#.#", "##.", "#.#", "#.#"],
    "L": ["#..", "#..", "#..", "#..", "###"], "N": ["#..#", "##.#", "#.##", "#..#", "#..#"],
    "O": [".#.", "#.#", "#.#", "#.#", ".#."], "P": ["##.", "#.#", "##.", "#..", "#.."],
    "R": ["##.", "#.#", "##.", "#.#", "#.#"],
    "S": [".##", "#..", ".#.", "..#", "##."], "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "W": ["#...#", "#...#", "#.#.#", "#.#.#", ".#.#."], "Y": ["#.#", "#.#", ".#.", ".#.", ".#."],
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "3": ["##.", "..#", ".#.", "..#", "##."], "4": ["#.#", "#.#", "###", "..#", "..#"],
    "6": [".##", "#..", "##.", "#.#", ".#."], "!": ["#", "#", "#", ".", "#"],
}
FONT7 = {
    "B": ["###.", "#..#", "#..#", "###.", "#..#", "#..#", "###."],
    "D": ["###.", "#..#", "#..#", "#..#", "#..#", "#..#", "###."],
    "E": ["###", "#..", "#..", "##.", "#..", "#..", "###"],
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
    "K": ["#..#", "#.#.", "##..", "##..", "#.#.", "#..#", "#..#"],
    "L": ["#...", "#...", "#...", "#...", "#...", "#...", "####"],
    "M": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    "N": ["#...#", "##..#", "##..#", "#.#.#", "#..##", "#..##", "#...#"],
    "O": [".##.", "#..#", "#..#", "#..#", "#..#", "#..#", ".##."],
    "R": ["###.", "#..#", "#..#", "###.", "#.#.", "#..#", "#..#"],
    "S": [".###", "#...", "#...", ".##.", "...#", "...#", "###."],
    "T": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", ".#."],
    "U": ["#..#", "#..#", "#..#", "#..#", "#..#", "#..#", ".##."],
    "6": [".##.", "#...", "#...", "###.", "#..#", "#..#", ".##."],
    "4": ["..#.", ".##.", "#.#.", "#.#.", "####", "..#.", "..#."],
}
FONTS = {5: FONT5, 7: FONT7}
SPACE = {5: 2, 7: 3}            # a space's width; one column between letters

# Our pictures: an arrow pointing ahead, the bath's adult and child (bottom-aligned with the digits)
ARROW = ["..#..", ".###.", "#####", ".###.", ".###.", ".###."]
ADULT = [".#.", "###", ".#.", ".#.", "#.#", "#.#"]
CHILD = [".#.", "###", ".#.", "#.#"]


def word(text, size):
    """The word's rows in the font of that size: letters one column apart, a space SPACE[size] wide."""
    font = FONTS[size]
    rows = [""] * size
    for k, ch in enumerate(text):
        if ch == " ":
            rows = [r + "." * SPACE[size] for r in rows]
            continue
        glyph = font[ch]
        gap = "." if k and text[k - 1] != " " else ""
        rows = [r + gap + g for r, g in zip(rows, glyph)]
    return rows


# Each texture: the object's vrom, the render tile's TMEM address, width, height, the texture block's offset
# in the object, the palette's offset, the texels' md5 and the palette's md5, what it is, and our operations:
#   ("paint", (x0, x1, y0, y1), index)            the box one index (x1, y1 past its end)
#   ("clean", (x0, x1, y0, y1), {ink indices})    each ink texel in the box the nearest of its row not ink
#   ("restripe", (x0, x1, y0, y1))                each texel in the box the one above and to the right
#   ("text", size, words, place, ink, edge)       place: (x, y), or ("centre", (x0, x1, y0, y1)) the box
#                                                 it is centred in; edge: None, ("shadow", index) under and
#                                                 right of each inked texel, or ("outline", index) round it
#   ("art", rows, (x, y), ink)                    a picture's '#' texels
FURNITURE = [
    (0x155A000, 0x00, 64, 32, 0x20, 0x00, "3cee606cfcd2c4a22b7eb02aa4d123d3", "456ddb24d4a8025c8725db5dd3ce3e47",
     "the safety barricade's board",
     [("restripe", (10, 54, 15, 23)), ("text", 5, "SAFETY FIRST", ("centre", (1, 63, 15, 22)), 0x5, ("outline", 0x6))]),
    (0x155B000, 0x00, 64, 32, 0x20, 0x00, "bbd2af3caa427ff8c23df07bdc438267", "5d7ac3b48a28ef85b38e56492b56f361",
     "the safety barricade's board (a second colour)",
     [("restripe", (10, 54, 11, 19)), ("text", 5, "SAFETY FIRST", ("centre", (1, 63, 11, 18)), 0x5, ("outline", 0x6))]),
    (0x155C000, 0x20, 64, 32, 0x20, 0x00, "bbd2af3caa427ff8c23df07bdc438267", "efc561199e2f8efce3d177e4e24fdde1",
     "the safety barricade's board (a third colour)",
     [("restripe", (10, 54, 11, 19)), ("text", 5, "SAFETY FIRST", ("centre", (1, 63, 11, 18)), 0x5, ("outline", 0x6))]),
    (0x155D000, 0x60, 64, 32, 0x20, 0x00, "bbd2af3caa427ff8c23df07bdc438267", "40421bf7b02d8d83433ccf57e558981c",
     "the safety barricade's board (a fourth colour, with a worker)",
     [("restripe", (10, 54, 11, 19)), ("text", 5, "SAFETY FIRST", ("centre", (1, 63, 11, 18)), 0x5, ("outline", 0x6))]),
    (0x1581000, 0x00, 32, 64, 0x20, 0x00, "e77340dfa060c4ffea5ac9e236d6bd9d", "9e36bd3c25dc1a2dd44bdb8f0c9ae661",
     "the road works sign where a worker bows",
     [("paint", (2, 30, 2, 28), 0x1), ("clean", (2, 13, 28, 56), {0x6}),
      ("text", 5, "SORRY!", ("centre", (2, 30, 3, 9)), 0x8, None),
      ("text", 5, "ROAD", ("centre", (2, 30, 10, 15)), 0x6, None),
      ("text", 5, "WORK", ("centre", (2, 30, 16, 21)), 0x6, None),
      ("text", 5, "AHEAD", ("centre", (2, 30, 22, 28)), 0x8, None)]),
    (0x157F000, 0x00, 32, 64, 0x20, 0x00, "637cb7b25d1d89198588b9eb4dd1bc51", "80921cfde951b6878966318a6611afac",
     "the road works detour map",
     [("clean", (2, 30, 2, 12), {0x8}), ("clean", (2, 13, 14, 21), {0x6}),
      ("text", 7, "DETOUR", ("centre", (2, 30, 3, 11)), 0x8, None)]),
    (0x1580000, 0x00, 32, 64, 0x20, 0x00, "094aa73c08d835d3e53360f22f9e660a", "76c6c42af2777c0f4ac94eba91591ab5",
     "the road works distance sign",
     [("paint", (23, 31, 8, 18), 0x6), ("art", ARROW, (25, 12), 0x1)]),
    (0x1566000, 0x00, 64, 48, 0x20, 0x00, "023d8d5b3fc1b25f9810504292759393", "8a5ff6ca64ab6b81dd7ef27d4aa1f38b",
     "the drum's fire warning",
     [("clean", (0, 32, 18, 28), {0xE}), ("text", 5, "NO FIRE", ("centre", (0, 32, 19, 28)), 0xE, None)]),
    (0x143A000, 0x00, 32, 32, 0x28, 0x08, "fc5d1113fbc75433565f4de7e516d5c3", "5d6d460b8b4b2aaf9a364a26dbbc9aa3",
     "the bus stop's sign",
     [("clean", (2, 29, 11, 21), {0x1, 0x2, 0x3}), ("text", 7, "BUS", ("centre", (2, 29, 11, 21)), 0x1, None),
      ("clean", (5, 26, 22, 28), {0x1, 0x3}), ("text", 5, "STOP", ("centre", (5, 26, 22, 28)), 0x1, None)]),
    (0x15B5000, 0x50, 32, 32, 0x20, 0x00, "f1732b2d4b3f01f1d62419ca5321cd54", "4cb3f463ca15129cac068a302cd37b64",
     "the public bath's price board",
     [("clean", (3, 29, 1, 2), {0x9, 0xA, 0xB, 0xE}), ("clean", (3, 30, 2, 29), {0x9, 0xA, 0xB, 0xE}),
      ("text", 5, "BATH", ("centre", (3, 29, 1, 7)), 0xA, None),
      ("art", ADULT, (8, 9), 0xB), ("text", 5, "340", (13, 10), 0xB, None),
      ("art", CHILD, (8, 17), 0xB), ("text", 5, "160", (13, 16), 0xB, None)]),
    (0x15AD000, 0x10, 16, 16, 0x20, 0x00, "4b32f391a202bf2c1cca9c957f36f68d", "cd3d8f7332a7f06252885ead6c715513",
     "the red telephone's coin label",
     [("paint", (1, 15, 4, 8), 0x8), ("art", ["##.###", ".#.#.#", ".#.#.#", ".#.###"], (5, 4), 0xD)]),
    (0x1567000, 0xD0, 32, 16, 0x20, 0x00, "ca1b5ab42911bfa021053092d577bf92", "e375bfa5de1a5c632350a588b6101508",
     "the drinks label",
     [("paint", (3, 29, 4, 10), 0xC), ("text", 5, "ROYAL A", ("centre", (3, 29, 4, 11)), 0x7, None)]),
    (0x15CE000, 0x50, 48, 16, 0x40, 0x00, "4a6f9ce16cd4ec4ac22cfbee67e3d4ef", "35b116685eabbccd7362244d7cf8c8f9",
     "the milk box's label",
     [("clean", (2, 41, 2, 14), {0x4, 0x5, 0x6}), ("text", 7, "MILK", ("centre", (2, 41, 2, 14)), 0x5, None)]),
    (0x15B9000, 0x90, 48, 16, 0x20, 0x00, "af1a5d380f915a43de691ccdb10d53d0", "b9c95632ef551a4772c5939bcbc3bbcd",
     "the two shop signs (playing cards, a lender)",
     [("paint", (3, 25, 3, 13), 0xC), ("text", 5, "CARDS", ("centre", (3, 25, 3, 13)), 0xB, None),
      ("clean", (28, 46, 3, 11), {0x6, 0xA}), ("text", 5, "CASH", ("centre", (28, 46, 3, 10)), 0xA, None)]),
    (0x157A000, 0x70, 32, 32, 0x20, 0x00, "8c894d487f5bbe367a31a3fca3a238e1", "09ffbf664a5ea2af046c947edf7c4188",
     "the traffic sign's line",
     [("paint", (2, 30, 26, 31), 0x5), ("text", 5, "GO SLOW", ("centre", (2, 30, 26, 31)), 0xB, None)]),
    (0x15A1000, 0x50, 32, 32, 0x20, 0x00, "a21df3b01965e379f038feeb1e86943e", "3a430edc37afb24e813120572953155b",
     "the sticker",
     [("clean", (3, 29, 9, 16), {0x6, 0x7}), ("text", 7, "N64", ("centre", (3, 29, 9, 16)), 0x6, None)]),
    (0x1582000, 0x30, 48, 32, 0x20, 0x00, "86e69b45e8875b3bd8b7a4391e3bf123", "1f384015551932200f7c94e3af6819ae",
     "the stone plaque",
     [("clean", (2, 46, 8, 21), {0x7, 0x8}),
      ("text", 7, "NINTENDO", ("centre", (2, 46, 8, 21)), 0x8, ("shadow", 0x7))]),
]


def render_tiles(data):
    """{TMEM address: {(format, size, line, width, height)}} of the render tiles the object's lists set."""
    out, tile = {}, None
    for at in range(0, len(data) - 7, 8):
        w0, w1 = struct.unpack(">II", data[at:at + 8])
        if w0 >> 24 == 0xF5 and (w1 >> 24) & 7 == 0:
            tile = ((w0 >> 21) & 7, (w0 >> 19) & 3, (w0 >> 9) & 0x1FF, w0 & 0x1FF)
        elif w0 >> 24 == 0xF2 and (w1 >> 24) & 7 == 0 and tile is not None:
            w = ((((w1 >> 12) & 0xFFF) - ((w0 >> 12) & 0xFFF)) >> 2) + 1
            h = (((w1 & 0xFFF) - (w0 & 0xFFF)) >> 2) + 1
            out.setdefault(tile[3], set()).add((tile[0], tile[1], tile[2], w, h))
    return out


def unswap(raw, w, h):
    """The texels (rows of indices) of a CI4 texture kept for LoadBlock: odd rows' 32-bit words swapped."""
    stride = w // 2
    rows = []
    for y in range(h):
        r = raw[y * stride:(y + 1) * stride]
        if y % 2:
            r = b"".join(r[i + 4:i + 8] + r[i:i + 4] for i in range(0, len(r), 8))
        rows.append([v for b in r for v in (b >> 4, b & 15)])
    return rows


def swap(rows):
    """unswap's inverse: the rows as the cartridge keeps them."""
    out = b""
    for y, row in enumerate(rows):
        r = bytes(row[i] << 4 | row[i + 1] for i in range(0, len(row), 2))
        if y % 2:
            r = b"".join(r[i + 4:i + 8] + r[i:i + 4] for i in range(0, len(r), 8))
        out += r
    return out


def nearest(row, x, ink):
    """The nearest texel of the row not ink to x (the left one where two are as near)."""
    for d in range(1, len(row)):
        for xx in (x - d, x + d):
            if 0 <= xx < len(row) and row[xx] not in ink:
                return row[xx]
    raise ValueError("row of all ink")


def apply(rows, op):
    """The texels with one operation done (FURNITURE's)."""
    rows = [list(r) for r in rows]
    h, w = len(rows), len(rows[0])
    kind = op[0]
    if kind == "paint":
        (x0, x1, y0, y1), v = op[1], op[2]
        for y in range(y0, y1):
            for x in range(x0, x1):
                rows[y][x] = v
    elif kind == "clean":
        (x0, x1, y0, y1), ink = op[1], op[2]
        for y in range(y0, y1):
            before = list(rows[y])
            for x in range(x0, x1):
                if before[x] in ink:
                    rows[y][x] = nearest(before, x, ink)
    elif kind == "restripe":
        x0, x1, y0, y1 = op[1]
        for y in range(y0, y1):
            for x in range(x0, x1):
                rows[y][x] = rows[y - 1][x + 1]
    elif kind in ("text", "art"):
        if kind == "text":
            _, size, text, place, ink, edge = op
            art = word(text, size)
        else:
            _, art, place, ink = op
            edge = None
        aw, ah = len(art[0]), len(art)
        if place[0] == "centre":
            x0, x1, y0, y1 = place[1]
            if aw > x1 - x0 or ah > y1 - y0:
                raise ValueError("%r does not fit its box" % (op[2],))
            px, py = x0 + (x1 - x0 - aw) // 2, y0 + (y1 - y0 - ah) // 2
        else:
            px, py = place
        inked = {(px + dx, py + dy) for dy, r in enumerate(art) for dx, c in enumerate(r) if c == "#"}
        for x, y in inked:
            if not (0 <= x < w and 0 <= y < h):
                raise ValueError("%r goes off the texture" % (op[2],))
            rows[y][x] = ink
        if edge:
            how, v = edge
            around = ((1, 1),) if how == "shadow" else \
                ((-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1))
            for x, y in inked:
                for dx, dy in around:
                    xx, yy = x + dx, y + dy
                    if (xx, yy) not in inked and 0 <= xx < w and 0 <= yy < h:
                        rows[yy][xx] = v
    else:
        raise ValueError("no operation %r" % kind)
    return rows


def compose(raw, w, h, ops):
    """The en build's bytes of a texture from the cartridge's (raw, kept for LoadBlock)."""
    rows = unswap(raw, w, h)
    for op in ops:
        rows = apply(rows, op)
    return swap(rows)


def check(data, tmem, w, h, base, pal, md5, pal_md5, what):
    """The cartridge's object as our art expects it, or SystemExit."""
    tiles = render_tiles(data).get(tmem, set())
    if (2, 0, w // 16, w, h) not in tiles:
        raise SystemExit("af_own_ftr: %s: the object's lists set no CI4 %dx%d render tile at TMEM %#x (%s)" % (
            what, w, h, tmem, sorted(tiles)))
    at = base + tmem * 8
    if hashlib.md5(data[at:at + w * h // 2]).hexdigest() != md5:
        raise SystemExit("af_own_ftr: %s: the texture at %#x is not the cartridge's" % (what, at))
    if hashlib.md5(data[pal:pal + 32]).hexdigest() != pal_md5:
        raise SystemExit("af_own_ftr: %s: the palette at %#x is not the cartridge's" % (what, pal))


def bin_path(vrom):
    return "assets/jp/segment_%08X.bin" % vrom


def main(argv):
    rom_path, en = argv
    rom = open(rom_path, "rb").read()
    for vrom, tmem, w, h, base, pal, md5, pal_md5, what, ops in FURNITURE:
        data = af_gc_signs.file_at(rom, vrom)
        check(data, tmem, w, h, base, pal, md5, pal_md5, what)
        at = base + tmem * 8
        old = data[at:at + w * h // 2]
        af_gc_signs.put(en, [(0, None, "bin", bin_path(vrom))], at, old, compose(old, w, h, ops), w, h, "af_own_ftr")
    print("af_own_ftr: the furniture's words (%d textures of %d objects)" % (
        len(FURNITURE), len({f[0] for f in FURNITURE})))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
