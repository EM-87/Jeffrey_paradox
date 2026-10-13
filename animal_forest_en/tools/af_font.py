#!/usr/bin/env python3
"""The en build's font: the cartridge's own glyphs, Latin letters drawn at
their own width.

    tools/af_font.py ROM OUT_SEGMENT.bin OUT_CUT.inc [--gap 1] [--space 4]

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
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from af_names import segment  # noqa: E402
from msgbank import N64_CHARS  # noqa: E402

VROM, SIZE = 0xBCD000, 0x62E0
SHEET, SHEET_W = 0x128, 192
CELL_W, CELL_H, PER_ROW = 12, 16, 16


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


def widths(cut):
    """What each character advances the pen by (func_8009028C_jp)."""
    return [CELL_W - n for n in cut]


def initializer(cut):
    lines = ["/* the columns cut from each character's right: tools/af_font.py, from the user's dump */"]
    for i in range(0, 256, 16):
        lines.append(" ".join("%d," % n for n in cut[i:i + 16]))
    return "\n".join(lines) + "\n"


def main(argv):
    opts = {"--gap": 1, "--space": 4}
    args = []
    i = 0
    while i < len(argv):
        if argv[i] in opts:
            opts[argv[i]] = int(argv[i + 1])
            i += 2
        else:
            args.append(argv[i])
            i += 1
    rom_path, seg_path, inc_path = args
    seg, cut = align(segment(open(rom_path, "rb").read(), VROM), opts["--gap"], opts["--space"])
    open(seg_path, "wb").write(seg)
    open(inc_path, "w").write(initializer(cut))
    w = [12 - cut[c] for c in range(256) if latin(c)]
    print("%s: %d Latin characters at the left of their cells, %d to %d pixels wide (gap %d, space %d)"
          % (seg_path, len(w), min(w), max(w), opts["--gap"], opts["--space"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
