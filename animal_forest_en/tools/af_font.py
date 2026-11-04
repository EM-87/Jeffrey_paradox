#!/usr/bin/env python3
"""The en build's font: the cartridge's own glyphs, Latin letters drawn at
their own width.

    tools/af_font.py ROM OUT_SEGMENT.bin OUT_CUT.inc [--gap 1] [--space 4] [--gc GC_FONT.bin]
    tools/af_font.py gc ISO OUT_GC_FONT.bin

The font is the file at vrom 0xBCD000 (0x62E0 bytes, stored uncompressed):
at 0x128 a 192x256 I4 sheet, 256 characters of 12x16 pixels, sixteen to a
row, character c at (c % 16 * 12, c // 16 * 16) (src/code/m_font.c;
func_80090188_jp loads the 0x6000 bytes into B_8013A680_jp). The game draws
a character from the left edge of its cell, 12 pixels wide less the
character's entry in a table of columns cut from the right
(func_8009028C_jp, when the caller asks for it); the cartridge's table is
all zeros, and its Latin letters sit near the middle of their cells, one to
five empty columns on the left (measured: reference/NOTES.md, "The font").

This moves each Latin character (the N64 charset's printable ASCII but the
space, tools/msgbank.py N64_CHARS) to the left edge of its cell and gives it
the cut that leaves its inked columns and --gap empty ones; the space gets
--space pixels. Kana, kanji and the marks (♥ ♪ ～ 💧 ✚) keep their cells and
no cut, so Japanese text draws as on the cartridge. Writes the segment (same
size, nothing outside the Latin cells touched) for the en build's copy of
the .bin, and the table as a C initializer that the en build's m_font.c
includes (assets/jp/en/font_cut.inc). Both come from the user's dump at
build time; neither is committed.

With --gc, the Latin characters are the GameCube's instead: its glyphs
and its own table of columns cut (Animal Crossing draws a character the
same way, from the left of a 12x16 cell, 12 pixels less its cut:
mFont_GetCodeWidth in ac-decomp's m_font.c, a map; the table, ours to
read from the user's disc, never to copy). The GameCube's English text
was laid out for that font, on a letter board as wide as ours (192
pixels, six lines), so it fits as it did there. The GameCube's charset
has the N64's Latin characters at the same codes (tools/msgbank.py
GC_CHARS, N64_CHARS); each is looked up by character all the same.

`gc` reads that font from the user's disc image (make script): the sheet
(FONT_nes_tex_font1, 192x256 I4 in the GameCube's 8x8 tiles) and the cut
table (m_font.o's offset_data) are found by name in the disc's own
foresta.map, in the .data section of foresta.rel (a REL module, Yaz0
compressed), whose sections the map lists in order; the map's .data must
end where the module's section is that long. Writes the sheet untiled (the N64
sheet's layout) and the 256 cuts after it, in build/gc, never committed.
"""

import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gciso  # noqa: E402
from af_names import segment  # noqa: E402
from msgbank import GC_CHARS, N64_CHARS  # noqa: E402

VROM, SIZE = 0xBCD000, 0x62E0
SHEET, SHEET_W = 0x128, 192
CELL_W, CELL_H, PER_ROW = 12, 16, 16


SHEET_SIZE = SHEET_W * CELL_H * 16 // 2                # 0x6000
GC_SHEET, GC_CUT = ("FONT_nes_tex_font1", None), ("offset_data$396", "m_font.o")
GC_SECTIONS = (".text", ".ctors", ".dtors", ".rodata", ".data", ".bss")   # the REL's sections 1-6
GC_FONT_SIZE = SHEET_SIZE + 256
# The marks the GameCube's English text uses that the en text keeps at the
# same code (tools/af_text.py compiles "~" and "😃" as the N64's wave dash
# and face): the GameCube's glyphs too, so its text looks as it did.
GC_MARKS = {"♥": "♥", "♪": "♪", "ー": "ー", "～": "~", "☺": "😃"}


def latin(c):
    ch = N64_CHARS[c]
    return len(ch) == 1 and "!" <= ch <= "~"


def _row(seg, c, y):
    """Character c's row y: its 12 pixels and the offset of their 6 bytes."""
    at = SHEET + ((c // PER_ROW * CELL_H + y) * SHEET_W + c % PER_ROW * CELL_W) // 2
    pixels = []
    for b in seg[at:at + CELL_W // 2]:
        pixels += [b >> 4, b & 15]
    return pixels, at


def inked(seg, c):
    """The columns of character c with any pixel set."""
    rows = [_row(seg, c, y)[0] for y in range(CELL_H)]
    return [x for x in range(CELL_W) if any(r[x] for r in rows)]


def align(seg, gap=1, space=4):
    """(segment, cut): the Latin characters at the left of their cells, and
    the columns to cut from each character's right."""
    if len(seg) != SIZE:
        raise ValueError("the font is %#x bytes, not %#x: not this cartridge's" % (len(seg), SIZE))
    out, cut = bytearray(seg), [0] * 256
    for c in range(256):
        if N64_CHARS[c] == " ":
            cut[c] = CELL_W - space
        elif latin(c):
            cols = inked(seg, c)
            if not cols:
                continue
            left = cols[0]
            for y in range(CELL_H):
                pixels, at = _row(seg, c, y)
                pixels = pixels[left:] + [0] * left
                out[at:at + CELL_W // 2] = bytes(pixels[i] << 4 | pixels[i + 1] for i in range(0, CELL_W, 2))
            cut[c] = max(0, CELL_W - (cols[-1] - left + 1 + gap))
    return bytes(out), cut


def map_layout(text):
    """{section: [(offset, size, symbol, object)]} of a CodeWarrior map."""
    out = {}
    parts = re.split(r"^(\.\w+) section layout\n", text.replace("\r\n", "\n"), flags=re.M)
    for name, body in zip(parts[1::2], parts[2::2]):
        out[name] = [(int(a, 16), int(b, 16), sym, obj.strip()) for a, b, sym, obj in
                     re.findall(r"^  ([0-9a-f]{8}) ([0-9a-f]{6}) [0-9a-f]{8}\s+\d+ (\S+) \t(.*)$", body, flags=re.M)]
    return out


def gc_find(layout, name, obj=None):
    """(offset, size) of the symbol name (of object obj) in the map's .data, which must hold it once."""
    hits = [(o, s) for o, s, sym, ob in layout[".data"] if sym == name and (obj is None or ob == obj)]
    if len(hits) != 1:
        raise SystemExit("%s is not in the map's .data once (%d)" % (name, len(hits)))
    return hits[0]


def gc_rel_data(szs, layout, upto):
    """foresta.rel's .data section (Yaz0 compressed: szs), decompressed as far as its first upto bytes,
    once the map's .data is found to end where the module's section does."""
    head = gciso.yaz0(szs, 0x100)
    count, table = struct.unpack(">II", head[0xC:0x14])
    entries = [struct.unpack(">II", head[table + 8 * i:table + 8 * i + 8]) for i in range(min(count, 7))]
    k = GC_SECTIONS.index(".data") + 1
    data_at, data_size = entries[k][0] & ~3, entries[k][1]
    extent = max(o + s for o, s, _, _ in layout[".data"])
    if extent != data_size:
        raise SystemExit("the map's .data ends at %#x, the module's section %d is %#x bytes" % (extent, k, data_size))
    return gciso.yaz0(szs, data_at + upto)[data_at:]


def gc_font(szs, map_text):
    """(sheet, cut): the GameCube's font sheet in the N64 sheet's layout, its 256 cuts."""
    layout = map_layout(map_text)
    (sheet_at, sheet_size), (cut_at, cut_size) = gc_find(layout, *GC_SHEET), gc_find(layout, *GC_CUT)
    if (sheet_size, cut_size) != (SHEET_SIZE, 256):
        raise SystemExit("af_font: the map's font is %#x bytes and its cut table %#x, not %#x and 0x100"
                         % (sheet_size, cut_size, SHEET_SIZE))
    data = gc_rel_data(szs, layout, max(sheet_at + sheet_size, cut_at + cut_size))
    tiled = data[sheet_at:sheet_at + SHEET_SIZE]
    sheet = bytearray(SHEET_SIZE)
    row_bytes, tiles_per_row = SHEET_W // 2, SHEET_W // 8
    for t in range(len(tiled) // 32):                  # 8x8 tiles of 4 bytes a row, left to right, then down
        tx, ty = t % tiles_per_row, t // tiles_per_row
        for y in range(8):
            at = (ty * 8 + y) * row_bytes + tx * 4
            sheet[at:at + 4] = tiled[t * 32 + y * 4:t * 32 + y * 4 + 4]
    return bytes(sheet), list(data[cut_at:cut_at + 256])


def gc_cell(sheet, g, y):
    """The GameCube character g's row y, as 6 bytes of the N64 sheet's layout."""
    at = (g // PER_ROW * CELL_H + y) * (SHEET_W // 2) + g % PER_ROW * CELL_W // 2
    return sheet[at:at + CELL_W // 2]


def gc_align(seg, gc):
    """(segment, cut): the Latin characters, the space and GC_MARKS the GameCube's, the rest the cartridge's."""
    if len(seg) != SIZE:
        raise ValueError("the font is %#x bytes, not %#x: not this cartridge's" % (len(seg), SIZE))
    if len(gc) != GC_FONT_SIZE:
        raise ValueError("the GameCube font is %#x bytes, not %#x" % (len(gc), GC_FONT_SIZE))
    sheet, gc_cut = gc[:SHEET_SIZE], gc[SHEET_SIZE:]
    codes = {}
    for g, ch in enumerate(GC_CHARS):
        codes.setdefault(ch, g)
    out, cut = bytearray(seg), [0] * 256
    for c in range(256):
        ch = N64_CHARS[c]
        if latin(c) or ch == " ":
            g = codes[ch]
        elif ch in GC_MARKS:
            g = codes[GC_MARKS[ch]]
        else:
            continue
        for y in range(CELL_H):
            at = _row(seg, c, y)[1]
            out[at:at + CELL_W // 2] = gc_cell(sheet, g, y)
        cut[c] = gc_cut[g]
    return bytes(out), cut


def font(rom, gc=None, gap=1, space=4):
    """(segment, cut) of the en build: the GameCube's Latin characters if
    given its font (gc_font's bytes), else the cartridge's realigned."""
    seg = segment(rom, VROM)
    return gc_align(seg, gc) if gc else align(seg, gap, space)


def widths(cut):
    """What each character advances the pen by (func_8009028C_jp)."""
    return [CELL_W - n for n in cut]


def initializer(cut):
    lines = ["/* the columns cut from each character's right: tools/af_font.py, from the user's dump */"]
    for i in range(0, 256, 16):
        lines.append(" ".join("%d," % n for n in cut[i:i + 16]))
    return "\n".join(lines) + "\n"


def main(argv):
    if argv and argv[0] == "gc":
        iso_path, out_path = argv[1:3]
        disc = gciso.Disc(open(iso_path, "rb").read())
        sheet, cut = gc_font(disc.read("foresta.rel.szs"), disc.read("foresta.map").decode("latin-1"))
        open(out_path, "wb").write(sheet + bytes(cut))
        w = [CELL_W - cut[g] for g, ch in enumerate(GC_CHARS) if len(ch) == 1 and "!" <= ch <= "~"]
        print("%s: the GameCube's font, its ASCII %d to %d pixels wide" % (out_path, min(w), max(w)))
        return 0
    opts = {"--gap": 1, "--space": 4, "--gc": None}
    args = []
    i = 0
    while i < len(argv):
        if argv[i] in opts:
            opts[argv[i]] = argv[i + 1] if argv[i] == "--gc" else int(argv[i + 1])
            i += 2
        else:
            args.append(argv[i])
            i += 1
    rom_path, seg_path, inc_path = args
    gc = open(opts["--gc"], "rb").read() if opts["--gc"] else None
    seg, cut = font(open(rom_path, "rb").read(), gc, opts["--gap"], opts["--space"])
    open(seg_path, "wb").write(seg)
    open(inc_path, "w").write(initializer(cut))
    w = [12 - cut[c] for c in range(256) if latin(c)]
    if gc:
        print("%s: %d Latin characters the GameCube's, %d to %d pixels wide, the space %d"
              % (seg_path, len(w), min(w), max(w), 12 - cut[N64_CHARS.index(" ")]))
    else:
        print("%s: %d Latin characters at the left of their cells, %d to %d pixels wide (gap %d, space %d)"
              % (seg_path, len(w), min(w), max(w), opts["--gap"], opts["--space"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
