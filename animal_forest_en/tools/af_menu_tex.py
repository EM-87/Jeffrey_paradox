#!/usr/bin/env python3
"""The menus' word textures the 2010 patch left in Japanese, for the en build.

    tools/af_menu_tex.py BASEROM EN_DIR EN_FONT [GC_MAP GC_REL_SZS]

Three menus draw Japanese words as textures (I4) that neither the 2010
patch's labels nor the font cover (reference/NOTES.md, "The menus' word
textures"):

- the town tune's notes (program 8, ovl__0079C020): a frog for each note,
  the note's name on it, kana (do to ti and the random note; the tie a
  dash and the rest z's: 16x16 textures in segment_00AD1000 from 0x2868,
  0x80 apart, which the overlay's note table names by segment 0x0C
  address). The GameCube's town tune puts letters on its notes (C to B, a
  question mark; the same dash and z's: onp_win_*, 16x16 I4, in 8x8 tiles
  as its font sheet), taken from the user's disc (build/gc/foresta.rel.szs,
  its map naming them) where its files are there, the cartridge's kana
  without them;
- the birthday's window (program 9, ovl__0079DA50): the words for month
  and day after the two numbers (segment_00ADD000 at 0x2DB8 and 0x2EB8,
  32x16). The GameCube's window spells the month out, a texture a month,
  and has none of these: ours, a dash after the month and nothing after
  the day, as the clock writes its date (month - day), in the en font's
  glyph;
- the text editor's catchphrase window (segment_00A94000 at 0x1318,
  128x16): Japanese corner quotes round the catchphrase, a wave dash for
  the sentence before it and a full stop after it. Ours: an opening
  double quote and an ellipsis where the corner quote and the wave dash
  were, a closing double quote where the full stop and the closing quote
  were: the en font's glyphs.

Each is checked first: the cartridge's lists draw an I4 texture of that
size there (the notes: the overlay's note table names each), and the
cartridge's texels there are not empty. Written over the en tree's copy
of the segment (its .bin, which splat rewrites at every extract); nothing
of the cartridge's or the GameCube's enters the repository: ours are
the en font's glyphs, put where the kana were.
"""

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_font  # noqa: E402
import af_gc_signs  # noqa: E402
import msgbank  # noqa: E402

TUNE_VROM, TUNE_OVERLAY, TUNE_SEGMENT = 0xAD1000, 0x79C020, 0x0C
# the cartridge's note textures, the GameCube's for each (its symbols in dataobject.obj)
TUNE_NOTES = [(0x2868, "onp_win_c_tex_rgb_i4", "do"), (0x28E8, "onp_win_d_tex_rgb_i4", "re"),
              (0x2968, "onp_win_e_tex_rgb_i4", "mi"), (0x29E8, "onp_win_f_tex_rgb_i4", "fa"),
              (0x2A68, "onp_win_g_tex_rgb_i4", "so"), (0x2AE8, "onp_win_a_tex_rgb_i4", "la"),
              (0x2B68, "onp_win_b_tex_rgb_i4", "ti"), (0x2C68, "onp_win_q_tex_rgb_i4", "the random note")]
# (the tie, 0x2BE8, a dash, and the rest, 0x2CE8, z's, are the same on both: the GameCube's onp_win_onpu8 and
# onp_win_z textures are the cartridge's)
NOTE_SIZE = (16, 16)
BIRTHDAY_VROM, MONTH_AT, DAY_AT, BIRTHDAY_SIZE = 0xADD000, 0x2DB8, 0x2EB8, (32, 16)
EDITOR_VROM, QUOTES_AT, QUOTES_SIZE = 0xA94000, 0x1318, (128, 16)
SEGMENT_BIN = "assets/jp/segment_%08X.bin"
FONT_CUT = "assets/jp/en/font_cut.inc"

# the letter papers' words (segment_00AE3000, CI4 on each paper's palette): the safety slogan's two halves and
# the New Year's card's greeting. The GameCube has no texture of these: ours, the en font's glyphs in the
# palette's own inks (the glyph's strong texels, its middling ones, its faint ones), on the paper's ground
PAPER_VROM = 0xAE3000
PAPERS = [(0x3620, (32, 16), "Safety", -1, (3, 13, 11, 0), "the safety slogan's first word"),
          (0x3720, (32, 16), "First", 0, (3, 13, 11, 0), "the safety slogan's second word"),
          (0x28DC8, (64, 16), "New Year", 0, (5, 8, 2, 1), "the New Year's card's greeting")]

# ours: the en font's glyphs (its segment, which tools/af_font.py writes), each put with its first inked column
# at x
MONTH = [("-", 7)]                                       # after the month: between the numbers, where the kana were
QUOTES = [('"', 31), (".", 43), (".", 47), (".", 51),    # the corner quote's and the wave dash's places
          ('"', 113)]                                    # the closing corner quote's


def drawn_i4(data, fmt=(4, 0)):
    """{offset: (width, height)} of the textures of this format ((4, 0): I4; (2, 0): CI4) the segment's own lists
    draw."""
    out, timg, load, render = {}, None, None, None
    loads = {}
    for at in range(0, len(data) - 8, 8):
        if data[at] == 0xFD:
            seg = data[at + 4]
            loads[seg] = loads.get(seg, 0) + 1
    seg = max(loads, key=loads.get) if loads else None
    for at in range(0, len(data) - 8, 8):
        w0, w1 = struct.unpack(">II", data[at:at + 8])
        op = w0 >> 24
        if op == 0xFD:
            timg = w1
        elif op in (0xF3, 0xF4) and timg is not None and timg >> 24 == seg:
            load = timg & 0xFFFFFF
        elif op == 0xF5 and (w1 >> 24) & 7 == 0:
            render = ((w0 >> 21) & 7, (w0 >> 19) & 3)
        elif op == 0xF2 and (w1 >> 24) & 7 == 0 and load is not None and render is not None:
            if render == fmt:
                out[load] = ((((w1 >> 12) & 0xFFF) >> 2) + 1, ((w1 & 0xFFF) >> 2) + 1)
            load = None
    return out


def lettered(font, glyphs, w, h):
    """The I4 texture (w x h, linear, the high nibble first) with these glyphs of the en font (its segment) on it,
    each (character, x): its first inked column at x, its rows the texture's."""
    texels = [0] * (w * h)
    for ch, x in glyphs:
        code = msgbank.N64_BYTES[ch]
        rows = [af_font._row(font, code, y)[0] for y in range(af_font.CELL_H)]
        inked = [c for c in range(af_font.CELL_W) if any(r[c] for r in rows)]
        if not inked:
            raise ValueError("the en font's %r has no ink" % ch)
        for y, row in enumerate(rows[:h]):
            for c in inked:
                xx = x + c - inked[0]
                if not 0 <= xx < w:
                    raise ValueError("%r at %d runs out of the %d-pixel texture" % (ch, x, w))
                texels[y * w + xx] = max(texels[y * w + xx], row[c])
    return bytes(texels[i] << 4 | texels[i + 1] for i in range(0, len(texels), 2))


def font_widths(en):
    """The en font's advance for each character (its cut table, which tools/af_font.py writes beside it)."""
    text = open(os.path.join(en, FONT_CUT)).read().split("\n", 1)[1]
    cut = [int(n.rstrip(",")) for n in text.split()]
    if len(cut) != 256:
        raise SystemExit("af_menu_tex: %s holds %d cuts, not 256" % (FONT_CUT, len(cut)))
    return af_font.widths(cut)


def lettered_ci4(font, widths, word, x, w, h, spacing, inks):
    """The CI4 texture (w x h) with word in the en font from x, spacing pixels more between its glyphs: the
    glyph's texels of 12 and up the first ink, 7 to 11 the second, 3 to 6 the third, the rest the ground (inks:
    four palette indices)."""
    strong, middle, faint, ground = inks
    level = [0] * (w * h)
    pen = x
    for ch in word:
        code = msgbank.N64_BYTES[ch]
        rows = [af_font._row(font, code, y)[0] for y in range(af_font.CELL_H)]
        advance = widths[code]
        for y, row in enumerate(rows[:h]):
            for c in range(af_font.CELL_W):
                if row[c] and c < advance:
                    xx = pen + c
                    if not 0 <= xx < w:
                        raise ValueError("%r runs out of the %d-pixel texture" % (word, w))
                    level[y * w + xx] = max(level[y * w + xx], row[c])
        pen += advance + spacing
    idx = [strong if v >= 12 else middle if v >= 7 else faint if v >= 3 else ground for v in level]
    return bytes(idx[i] << 4 | idx[i + 1] for i in range(0, len(idx), 2))


def file_at(rom, vrom):
    return af_gc_signs.file_at(rom, vrom)


def write_over(en, vrom, at, new, cart, what):
    """new over the en tree's copy of the segment at vrom, where it holds the cartridge's texels or new."""
    path = os.path.join(en, SEGMENT_BIN % vrom)
    data = bytearray(open(path, "rb").read())
    if len(data) != len(cart):
        raise SystemExit("af_menu_tex: %s is %#x bytes, the cartridge's file %#x" % (path, len(data), len(cart)))
    held = bytes(data[at:at + len(new)])
    if held not in (cart[at:at + len(new)], new):
        raise SystemExit("af_menu_tex: %s at %#x (%s) holds neither the cartridge's texels nor ours" % (
            path, at, what))
    if held != new:
        data[at:at + len(new)] = new
        open(path, "wb").write(bytes(data))


def main(argv):
    rom_path, en, font_path = argv[:3]
    gc_paths = argv[3:5] if len(argv) >= 5 else None
    rom = open(rom_path, "rb").read()
    font = open(font_path, "rb").read()
    count = 0

    # the town tune's notes: the GameCube's letters, where its files are there
    tune = file_at(rom, TUNE_VROM)
    overlay = file_at(rom, TUNE_OVERLAY)
    named = {struct.unpack(">I", overlay[k:k + 4])[0] for k in range(0, len(overlay) - 3, 4)}
    w, h = NOTE_SIZE
    for at, _, what in TUNE_NOTES:
        if (TUNE_SEGMENT << 24 | at) not in named:
            raise SystemExit("af_menu_tex: the town tune's note table does not name %#x (%s)" % (at, what))
        if not any(tune[at:at + w * h // 2]):
            raise SystemExit("af_menu_tex: the town tune's note at %#x (%s) is empty" % (at, what))
    if gc_paths and all(os.path.exists(p) for p in gc_paths):
        layout = af_font.map_layout(open(gc_paths[0], errors="replace").read())
        want = {name for _, name, _ in TUNE_NOTES}
        found = {sym: (o, s) for o, s, sym, obj in layout[".data"] if obj == af_gc_signs.GC_OBJECT and sym in want}
        missing = sorted(want - set(found))
        if missing or any(s != w * h // 2 for o, s in found.values()):
            raise SystemExit("af_menu_tex: the GameCube's notes not all in its map at 16x16 I4 (%s)" % missing)
        gc = af_font.gc_rel_data(open(gc_paths[1], "rb").read(), layout, max(o + s for o, s in found.values()))
        for at, name, what in TUNE_NOTES:
            o, s = found[name]
            new = af_gc_signs.untile_ci4(gc[o:o + s], w, h)        # I4 tiles as CI4's: 8x8, four bits a texel
            if new == tune[at:at + len(new)]:
                raise SystemExit("af_menu_tex: the GameCube's %s is the cartridge's" % name)
            write_over(en, TUNE_VROM, at, new, tune, what)
            count += 1
    else:
        print("af_menu_tex: the town tune's notes: the cartridge's (no GameCube files)")

    # the birthday's month and day, and the catchphrase window's quotes: ours
    for vrom, at, size, glyphs, what in ((BIRTHDAY_VROM, MONTH_AT, BIRTHDAY_SIZE, MONTH, "the birthday's month"),
                                         (BIRTHDAY_VROM, DAY_AT, BIRTHDAY_SIZE, [], "the birthday's day"),
                                         (EDITOR_VROM, QUOTES_AT, QUOTES_SIZE, QUOTES, "the catchphrase's quotes")):
        cart = file_at(rom, vrom)
        if drawn_i4(cart).get(at) != size:
            raise SystemExit("af_menu_tex: %s: the cartridge's lists draw %s at %#x, not I4 %dx%d" % (
                what, drawn_i4(cart).get(at), at, size[0], size[1]))
        if not any(cart[at:at + size[0] * size[1] // 2]):
            raise SystemExit("af_menu_tex: %s at %#x is empty in the cartridge" % (what, at))
        write_over(en, vrom, at, lettered(font, glyphs, *size), cart, what)
        count += 1
    # the letter papers' words: ours
    widths = font_widths(en)
    papers = file_at(rom, PAPER_VROM)
    drawn_ci4 = drawn_i4(papers, (2, 0))
    for at, (w, h), word, spacing, inks, what in PAPERS:
        if drawn_ci4.get(at) != (w, h):
            raise SystemExit("af_menu_tex: %s: the cartridge's lists draw %s at %#x, not CI4 %dx%d" % (
                what, drawn_ci4.get(at), at, w, h))
        old_idx = [n for b in papers[at:at + w * h // 2] for n in (b >> 4, b & 15)]
        if max(set(old_idx), key=old_idx.count) != inks[3]:
            raise SystemExit("af_menu_tex: %s: its ground is not index %d" % (what, inks[3]))
        width = sum(widths[msgbank.N64_BYTES[c]] for c in word) + spacing * (len(word) - 1)
        write_over(en, PAPER_VROM, at, lettered_ci4(font, widths, word, (w - width) // 2, w, h, spacing, inks),
                   papers, what)
        count += 1
    print("af_menu_tex: %d of the word textures in English" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
