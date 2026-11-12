#!/usr/bin/env python3
"""The en build's dial without its kana modes: the modes' panel and the symbols' ring.

    tools/af_dial.py BASEROM EN_DIR

The text editor's dial (ovl__0078CB80, submenu program 10) has five modes, which Z goes through: hiragana,
the symbols, katakana, the letters and the digits; its modes' panel lists their labels in that order, a row
each, each row drawn by a list of the dial's segment (reference/NOTES.md, "The dial's small letters"). The en
build has no kana modes: Z goes through the small letters, the capitals, the symbols and the digits
(ovl__0078CB80.c, which points the panel's entries at the rows), and the panel shows those four from the top,
in that order. This writes, over the en tree's copies of the dial's segment (its .bin) and of the editor's data
(its .data.s), both of which splat rewrites at every extract:

- our label for the small letters (I4 48x16, drawn by hand: LOWER_LABEL) where the hiragana's label was, so
  the first row's list draws it;
- the second, third and fourth rows' lists pointed at the capitals', the symbols' and the digits' labels
  (their texture words; the labels are the 2010 patch's where the user has it, the cartridge's without it);
  the fifth row's list is left as it is, the editor drawing none of it;
- the symbols' ring without the cartridge's Japanese marks (its comma, full stop, middle dot and corner
  brackets): its 25 other marks in our order (SYMBOLS: the marks a sentence ends with, a space and a newline
  first), its count and its ring's length 25, five pages.

Each is checked first against the cartridge's (the user's dump): the lists' words and the labels they draw,
the marks of its ring. Nothing of the cartridge's enters the repository: the label is ours, the rest offsets
and the font's codes of our marks (tools/msgbank.py's charset).
"""

import os
import re
import struct
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_menu_tex  # noqa: E402
import msgbank  # noqa: E402

DIAL_VROM = 0xA40000                         # segment_00A40000, segment 0x0C while the editor draws
EDITOR_VROM, EDITOR_VRAM = 0x78CB80, 0x80885140
SEGMENT_BIN = "assets/jp/segment_%08X.bin" % DIAL_VROM
EDITOR_DATA = "asm/jp/data/overlays/submenu/ovl__0078CB80/ovl__0078CB80.data.s"
LABEL_W, LABEL_H = 48, 16
SET_TIMG = 0xFD900000                        # a label's list's texture: gsDPSetTextureImage(G_IM_FMT_I, G_IM_SIZ_16b)

# the panel's rows from the top: the row's list, the label the cartridge's list draws, the en build's
ROWS = [(0x1440, 0x1848, 0x1848, "row 1, the small letters (ours, over the hiragana's label)"),
        (0x15A0, 0x1B48, 0x1CC8, "row 2, the capitals"),
        (0x1498, 0x19C8, 0x1B48, "row 3, the symbols"),
        (0x14F0, 0x1CC8, 0x1E48, "row 4, the digits")]
LOWER_AT = 0x1848

# the symbols' ring (D_80888568_jp), its count (D_808885DC_jp[1]) and its ring's length (D_808885B4_jp[1])
SYMBOLS_AT, SYMBOLS_COUNT_AT, SYMBOLS_RING_AT, CART_SYMBOLS = 0x80888568, 0x808885E0, 0x808885B8, 30
SYMBOLS = "?!' \n,.\"-:()&%@_<>～ー♥♪☺💧✚"
JAPANESE_MARKS = {"IDEOGRAPHIC COMMA", "IDEOGRAPHIC FULL STOP", "KATAKANA MIDDLE DOT", "LEFT CORNER BRACKET",
                  "RIGHT CORNER BRACKET"}

# our label for the small letters, drawn by hand at the height and weight of the 2010 patch's capitals: each
# row's texels by LEGEND (0 to 15)
LEGEND = " .:-=+*#%@ABCDEF"
LOWER_LABEL = [
    "                                                ",
    "                                                ",
    "                                                ",
    "            FFA                                 ",
    "            FFA                                 ",
    "            FFA                                 ",
    "   =BEFFE*  FFA*DFE+     +CEFFD*                ",
    "  =F%. -FF= FFFE+-CFD   BFE=..*F                ",
    "   =CEFEFFA FFC    BFF .FF*                     ",
    "  BFC:..FFA FFA    *FF :FF=                     ",
    "  FF-  .FFA FFC    BFF .FF*                     ",
    "  BFC:-DFFA FFFE+-CFD   BFE=..*F                ",
    "   *DFE+CFA FF*DFE+      +CEFFD*                ",
    "                                                ",
    "                                                ",
    "                                                ",
]

WORD_LINE = re.compile(r"^(\s*/\* ([0-9A-F]+) ([0-9A-F]{8}) ([0-9A-F]{8}) \*/ )\.word .*$")


def lower_label():
    """Our label's texels as I4 (48x16, the high nibble first)."""
    if len(LOWER_LABEL) != LABEL_H or any(len(row) != LABEL_W for row in LOWER_LABEL):
        raise SystemExit("af_dial: the label is not %dx%d" % (LABEL_W, LABEL_H))
    levels = [LEGEND.index(c) for row in LOWER_LABEL for c in row]
    return bytes(levels[i] << 4 | levels[i + 1] for i in range(0, len(levels), 2))


def symbol_codes():
    """Our marks as the font's codes."""
    return bytes(msgbank.N64_BYTES[c] for c in SYMBOLS)


def word_at(data, base, vram):
    return struct.unpack(">I", data[vram - base:vram - base + 4])[0]


def segment_edits(cart_seg):
    """{offset: bytes} of the dial's segment, after checking the cartridge's lists and labels."""
    drawn = af_menu_tex.drawn_i4(cart_seg)
    edits = {}
    for dl, cart_tex, tex, what in ROWS:
        if word_at(cart_seg, 0, dl + 8) != SET_TIMG or word_at(cart_seg, 0, dl + 12) != 0x0C000000 | cart_tex:
            raise SystemExit("af_dial: %s: the cartridge's list at %#x does not draw the label at %#x" % (
                what, dl, cart_tex))
        if drawn.get(tex) != (LABEL_W, LABEL_H):
            raise SystemExit("af_dial: %s: the cartridge's lists draw %s at %#x, not I4 %dx%d" % (
                what, drawn.get(tex), tex, LABEL_W, LABEL_H))
        edits[dl + 12] = struct.pack(">I", 0x0C000000 | tex)
    edits[LOWER_AT] = lower_label()
    return edits


def ring_edits(cart_ovl):
    """{vram: word} of the editor's data: the symbols' ring, its count and its ring's length, after checking
    that the cartridge's ring is our marks and five Japanese ones."""
    rev = {}
    for ch, code in msgbank.N64_BYTES.items():
        rev.setdefault(code, ch)
    cart = cart_ovl[SYMBOLS_AT - EDITOR_VRAM:SYMBOLS_AT - EDITOR_VRAM + CART_SYMBOLS]
    ours = symbol_codes()
    others = [rev.get(c, "?") for c in cart if c not in ours]
    names = sorted(unicodedata.name(c, "?") for c in others)
    if sorted(cart) != sorted(list(ours) + [msgbank.N64_BYTES[c] for c in others]) or set(names) != JAPANESE_MARKS:
        raise SystemExit("af_dial: the cartridge's symbols are not our marks and the five Japanese ones (%r)" % (
            "".join(rev.get(c, "?") for c in cart)))
    for at in (SYMBOLS_COUNT_AT, SYMBOLS_RING_AT):
        if word_at(cart_ovl, EDITOR_VRAM, at) != CART_SYMBOLS:
            raise SystemExit("af_dial: the cartridge's word at %08X is not the symbols' %d" % (at, CART_SYMBOLS))
    table = ours + b" " * (CART_SYMBOLS - len(ours))
    table += cart_ovl[SYMBOLS_AT + CART_SYMBOLS - EDITOR_VRAM:SYMBOLS_AT + 32 - EDITOR_VRAM]   # the padding
    words = {SYMBOLS_AT + i: struct.unpack(">I", table[i:i + 4])[0] for i in range(0, 32, 4)}
    words[SYMBOLS_COUNT_AT] = words[SYMBOLS_RING_AT] = len(ours)
    return words


def rewrite_data(data_s, cart_ovl, words):
    """The .s with the lines of `words` ({vram: value}) as .word value, each line checked to be the cartridge's
    (its comment's word is the cartridge's word there)."""
    out, done = [], set()
    for line in data_s.split("\n"):
        m = WORD_LINE.match(line)
        if m and int(m.group(3), 16) in words:
            vram = int(m.group(3), 16)
            if int(m.group(4), 16) != word_at(cart_ovl, EDITOR_VRAM, vram):
                raise SystemExit("af_dial: %s: the line of %08X is not the cartridge's" % (EDITOR_DATA, vram))
            line = "%s.word 0x%08X" % (m.group(1), words[vram])
            done.add(vram)
        out.append(line)
    missing = set(words) - done
    if missing:
        raise SystemExit("af_dial: %s has no line for %s" % (EDITOR_DATA, ", ".join("%08X" % v for v in sorted(missing))))
    return "\n".join(out)


def apply_segment(seg, cart_seg, edits):
    """seg with the edits, each place holding the cartridge's bytes or ours."""
    if len(seg) != len(cart_seg):
        raise SystemExit("af_dial: %s is %#x bytes, the cartridge's segment %#x" % (SEGMENT_BIN, len(seg), len(cart_seg)))
    out = bytearray(seg)
    for at, new in sorted(edits.items()):
        held = bytes(out[at:at + len(new)])
        if held not in (cart_seg[at:at + len(new)], new):
            raise SystemExit("af_dial: %s at %#x holds neither the cartridge's bytes nor ours" % (SEGMENT_BIN, at))
        out[at:at + len(new)] = new
    return bytes(out)


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__)
    rom_path, en = argv
    rom = open(rom_path, "rb").read()
    cart_seg, cart_ovl = af_menu_tex.file_at(rom, DIAL_VROM), af_menu_tex.file_at(rom, EDITOR_VROM)
    seg_path, data_path = os.path.join(en, SEGMENT_BIN), os.path.join(en, EDITOR_DATA)
    seg = open(seg_path, "rb").read()
    new_seg = apply_segment(seg, cart_seg, segment_edits(cart_seg))
    if new_seg != seg:
        open(seg_path, "wb").write(new_seg)
    data_s = open(data_path).read()
    new_s = rewrite_data(data_s, cart_ovl, ring_edits(cart_ovl))
    if new_s != data_s:
        open(data_path, "w").write(new_s)
    print("af_dial: the panel's four rows (our small letters' label, three lists pointed anew) and the symbols' "
          "ring without its Japanese marks (%d of %d)" % (len(SYMBOLS), CART_SYMBOLS))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
