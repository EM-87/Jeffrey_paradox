#!/usr/bin/env python3
"""Our English for the words on the structures that no English redrew, for the en build.

    tools/af_own_signs.py BASEROM EN_DIR

Four structures of the town carry words in textures of the structures'
object (object_00D5E000) that the GameCube's English has no texture for
(reference/NOTES.md, "Our own signs"): its stall at the fireworks, its
police box and its fortune stall at the New Year's shrine are other
models (the fireworks stall's four textures, pinwheels and fans, share
none of the cartridge's texels and have no words; its police box's t2 has
its name over an arch), and it kept the fishing tourney headquarters'
sign as the cartridge has it. Ours, in the cartridge's own inks:

- the fireworks stall (ovl_Yatai, structure 44: its lists at 0x93348 and
  0x95440, its palette object_00D5B000's at 0x1088, which the structures'
  clip loads into segment 8). Its banner (0x93380, the left 64x23) names
  the stall in kana: ours names it REDD'S, the en bank's name for the fox
  who keeps it. The banner's cloth is cleaned of the kana (each of their
  texels the nearest of its row that is not ink: the folds run across)
  and our letters, with a shadow, laid over it; its scalloped hem is
  kept. Three cards hang at its side (0x94380, the right 64x32; each
  32x16, turned a quarter clockwise on the stall) and name its goods,
  the three things the cartridge's stall sells (messages 5976 to 5978):
  the initials of the music's medium with a word for in stock and a
  small picture of a disc, the fruit, the haniwa figures. Ours: K.K.
  with a note (the disc's picture kept), FRUIT and GYROID, the en bank's
  names for them, drawn upright here and turned onto the texture; each
  card is painted its ground first;
- the police box's notice board (the left 32x32 of the police box's t2:
  0x4D578, and 0x4F648 in winter; its palette at 0xF08): lines of small
  vertical script under four large characters in summer, a warning
  against pickpockets in winter. Ours: NOTICE over lines of small print,
  and BEWARE OF THIEVES. The board's face is painted white first; the
  dotted line at its foot, a wanted poster's grid of faces beside it and
  the box's own name plate (a strip of blots at the texture's right
  edge) carry no word one can read, and are kept;
- the fishing tourney headquarters' paper sign (the left 32x32 of
  0x8BF98, and its copy at 0x8E028; the tourney's palette at 0x1168, the
  same colours at 0x1188): two large characters for headquarters, one
  over the other. Ours: HQ, the same way, its strokes in the same greys,
  the paper cleaned of the characters as the banner's cloth is;
- the fortune stall at the New Year's shrine (its palette at 0x10C8):
  the plaque hung on it (0x22BA8, its right panel) names it in four red
  kana, one under the other; ours, LUCK the same way, the panel cleaned
  of them as the banner is. Two fortune slips (0x233A8, at its right)
  bear tiny green writing and a red mark: ours, GOOD over the slips'
  red band and LUCK under it on the front slip, the back one left
  blank; the band is kept.

Each is checked first: the cartridge's lists draw a CI4 128x32 texture at
its offset; the texture is the cartridge's (its md5: a check, not a copy)
and the object holds no other copy of it; the palette the structure draws
it with is the one our inks were chosen in (its md5). Written over the en
tree's copy of the object (the PNG splat extracts for a named texture,
the unextracted part's .bin for the rest), after tools/af_gc_signs.py,
which writes the same .bin.

Inside the police box (its room: the scene's file at vrom 0x12AC000,
segment_012AC000, whose own lists draw both in segment 6 with its palette
at 0x2A68) two posters hang on the wall, their titles in large kana over
a plain ground: a recruiting poster (CI4 32x48 at 0x3C68, an officer
saluting under a call for officers), ours JOIN US!, and a wanted poster
(CI4 48x32 at 0x3968, two faces under a line asking whoever sees them to
call the police), ours WANTED!. Each title's rows are painted the
poster's ground and ours laid there in its inks (the recruiting poster's
blue with the shadow under each stroke, as its kana have; the wanted
poster's brown); the small print under the officer and under the faces,
a few texels high, carries no word one can read, and is kept. Checked
as the structures' are, in the room's file; written over the en tree's
.bin of it (splat leaves the file unextracted).

Nothing of the cartridge's enters the repository: the art here is ours,
the cleaned cloth and paper are made from the user's dump at make
rom-en.
"""

import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_gc_signs  # noqa: E402

W, H = 128, 32                      # every texture here: CI4, 128x32
SIZE = W * H // 2

# the palettes the structures draw them with: their offset in object_00D5B000, the md5 of the cartridge's
STALL_PALETTE = (0x1088, "cc28ee330f0896400cfbc36eaf435371")
POLICE_PALETTE = (0xF08, "d4775086910c01323342086fa661a04a")
TOURNEY_PALETTE = (0x1168, "22ed4949bf60cbe69f2a0addd33a24fb")
FORTUNE_PALETTE = (0x10C8, "78e16471da64f3efa69931c183921c82")
BANNER_AT, CARDS_AT, SUMMER_AT, WINTER_AT = 0x93380, 0x94380, 0x4D578, 0x4F648
HQ_AT, PLAQUE_AT, SLIPS_AT = (0x8BF98, 0x8E028), 0x22BA8, 0x233A8
# the textures: the offsets of every copy object_00D5E000 has, the md5 of the cartridge's, its palette, what it is
TEXTURES = [((BANNER_AT,), "28e3a9d9ce01d4b9b163e340b08849d2", STALL_PALETTE, "the fireworks stall's banner"),
            ((CARDS_AT,), "98bdc343a0ceec89f2fca45994d07352", STALL_PALETTE, "the fireworks stall's cards"),
            ((SUMMER_AT,), "19452b4802611635d28b4ebdcb5b46e5", POLICE_PALETTE, "the police box's notice board"),
            ((WINTER_AT,), "21758434b4065256aae3355c57471ad9", POLICE_PALETTE,
             "the police box's notice board, in winter"),
            (HQ_AT, "86252b2ffd3ebd9c09b86338391c9d0d", TOURNEY_PALETTE, "the fishing tourney headquarters' sign"),
            ((PLAQUE_AT,), "a7f5c8dbdce4e95ffdb40843ffb4a750", FORTUNE_PALETTE, "the fortune stall's plaque"),
            ((SLIPS_AT,), "b3c5b3236b1bd1dc836979238c9fdbd4", FORTUNE_PALETTE, "the fortune stall's slips")]

# The banner: the kana's box (x from, x to, y from, y to: the hem below it kept), their inks (the stall's
# palette: the cream and its two brown edges), our letters' place and inks ('#' the cream, '-' the shadow)
BANNER_BOX = (1, 63, 0, 18)
BANNER_INK = {0xF, 0x6, 0x4}
BANNER_PLACE = (5, 2)
BANNER_INKS = {"#": 0xF, "-": 0x6}
BANNER = """
#######-...########-.######-....######-...##-.######-.
########-..########-.#######-...#######-..##-########-
##----###-.##-------.##---###-..##---###-.-#-##----##-
##-...-##-.##-.......##-..-###-.##-..-###-#--##-...---
##-....##-.##-.......##-...-##-.##-...-##---.###-.....
##-...###-.#######-..##-....##-.##-....##-...-#####-..
########--.#######-..##-....##-.##-....##-....-######-
#######--..##------..##-....##-.##-....##-.....---###-
##--###-...##-.......##-....##-.##-....##-........-##-
##-.-###-..##-.......##-...###-.##-...###-.........##-
##-..-##-..##-.......##-..###--.##-..###--...##-...##-
##-...###-.########-.#######--..#######--....########-
##-...-##-.########-.######--...######--.....-######--
---....---.---------.-------....-------.......-------.
"""

# The cards, upright (16 wide, 32 tall): on the texture each is 32x16 at (x, y), its upright column the
# texture's row and its upright row the texture's column from the right. '.' is the card's ground.
CARD_KK = """
................
..331...13331...
..331..13331....
..331.13331.....
..33113331......
..331.13331.....
..331..13331....
..331...13331...
................
......33........
......33........
................
..331...13331...
..331..13331....
..331.13331.....
..33113331......
..331.13331.....
..331..13331....
..331...13331...
................
......33........
......33........
................
...........cc...
...........c5c..
...........c.5c.
...........c..c.
...........c....
..........cc....
..........ccc...
...........c....
................
"""
CARD_FRUIT = """
................
...########+....
...##+..........
...######+......
...##+..........
...##+..........
................
...#######+.....
...##+...##+....
...#######+.....
...##+..##+.....
...##+...##+....
................
...##+...##+....
...##+...##+....
...##+...##+....
...##+...##+....
....######+.....
................
...########+....
......##+.......
......##+.......
......##+.......
...########+....
................
...########+....
......##+.......
......##+.......
......##+.......
......##+.......
................
................
"""
CARD_GYROID = """
................
....######+.....
...##+..........
...##+..###+....
....######+.....
................
...##+....##+...
....########+...
.......##+......
.......##+......
................
...#######+.....
...##+....##+...
...#######+.....
...##+....##+...
................
....######+.....
...##+....##+...
...##+....##+...
....######+.....
................
....########+...
.......##+......
.......##+......
....########+...
................
...#######+.....
...##+....##+...
...##+....##+...
...#######+.....
................
................
"""
# (x, y) on the texture, the ground, the inks (the stall's palette: the MD card's red and its outline, its
# brown; the fruit card's brown and its lighter edge; the haniwa card's green and its lighter edge), the
# art, the upright box kept as the cartridge's (the disc's picture), what
CARDS = [((64, 0), 0xF, {"3": 0x3, "1": 0x1, "c": 0xC, "5": 0x5}, CARD_KK, (0, 9, 22, 31), "the music's card"),
         ((96, 0), 0xD, {"#": 0x5, "+": 0x6}, CARD_FRUIT, None, "the fruit's card"),
         ((96, 16), 0xE, {"#": 0x8, "+": 0x7}, CARD_GYROID, None, "the haniwa's card")]

# The notice board: its face (x from, x to, y from, y to: the dotted line at its foot below it kept), painted
# white (the police box's palette's 6); ours on it: '#' the dark ink, 'a' and 'b' the small print's two greys
BOARD_BOX = (2, 30, 0, 22)
BOARD_GROUND = 0x6
BOARD_INKS = {"#": 0x5, "a": 0x4, "b": 0x3}
BOARD_SUMMER = """
............................
............................
...#..#..#..###.#..##.###...
...##.#.#.#..#..#.#...#.....
...#.##.#.#..#..#.#...##....
...#..#.#.#..#..#.#...#.....
...#..#..#...#..#..##.###...
............................
............................
.....abababa.ababa.abab.....
............................
.....aba.abababa.ababab.....
............................
.....ababa.aba.abababab.....
............................
......ababababab.abab.......
............................
......ababab.ababa.aba......
............................
........ababa.ababab........
............................
............................
"""
BOARD_WINTER = """
............................
............................
..##..###.#...#..#..##..###.
..#.#.#...#...#.#.#.#.#.#...
..##..##..#.#.#.###.##..##..
..#.#.#...#.#.#.#.#.#.#.#...
..##..###..#.#..#.#.#.#.###.
............................
............#..###..........
...........#.#.#............
...........#.#.##...........
...........#.#.#............
............#..#............
............................
..###.#.#.#.###.#.#.###..##.
...#..#.#.#.#...#.#.#...#...
...#..###.#.##..#.#.##...#..
...#..#.#.#.#...#.#.#.....#.
...#..#.#.#.###..#..###.##..
............................
......abab.ababab.abab......
............................
"""
BOARDS = [(SUMMER_AT, BOARD_SUMMER), (WINTER_AT, BOARD_WINTER)]

# The headquarters' sign: the characters' box on the paper (the pins in its top corners kept), their inks (the
# tourney's palette: the dark grey and its lighter edge), ours at (x, y) in the same inks
HQ_BOX = (4, 29, 2, 30)
HQ_INK = {0xF, 0xE}
HQ_PLACE = (9, 3)
HQ_INKS = {"f": 0xF, "e": 0xE}
HQ = """
fff........fff
fff........fff
fff........fff
fff........fff
fff........fff
ffffffffffffff
ffffffffffffff
fff........fff
fff........fff
fff........fff
fff........fff
fff........fff
..............
..............
..efffffffe...
.ffffffffffff.
fffe......efff
fff........fff
fff........fff
fff........fff
fff....fe..fff
fffe...efffffe
.ffffffffffff.
..efffffffefff
............ff
"""

# The fortune stall's plaque: its right panel's box, the kana's inks (the fortune stall's palette: the red,
# the dark red, the brown red), ours at (x, y): '8' the dark red, '7' its edge
PLAQUE_BOX = (114, 126, 4, 28)
PLAQUE_INK = {0x6, 0x7, 0x8}
PLAQUE_PLACE = (115, 5)
PLAQUE_INKS = {"8": 0x8, "7": 0x7}
PLAQUE = """
.88........
.88........
.88........
.88........
.8888887...
...........
.88....88..
.88....88..
.88....88..
.88....88..
.7888888...
...........
..788888...
.88........
.88........
.88........
..788888...
...........
.88...887..
.88..887...
.888887....
.88..887...
.88...887..
"""

# The fortune slips: their box; the writing's green anywhere in it and its red outside the rows of the slips'
# band (the band's own reds), painted the paper's white (the palette's f); ours on the front slip in the green
SLIPS_BOX = (98, 126, 1, 30)
SLIPS_GREEN, SLIPS_RED, SLIPS_BAND = {0xD}, {0x6, 0x8}, (16, 19)
SLIPS_PAPER = 0xF
SLIPS_INKS = {"#": 0xD}
SLIPS = [(99, 9, """
.##..#...#..##.
#...#.#.#.#.#.#
#.#.#.#.#.#.#.#
#.#.#.#.#.#.#.#
.##..#...#..##.
"""), (99, 19, """
#...#.#..##.#.#
#...#.#.#...#.#
#...#.#.#...##.
#...#.#.#...#.#
###.###..##.#.#
""")]

# The police box's room: the scene's file (vrom, the en tree's .bin of it), the palette its lists draw the
# posters with (offset, md5), and each poster: its offset, width, height, md5, the title's box (x from, x to,
# y from, y to) painted its ground, ours at (x, y) in the inks ('#' the ink, '-' the shadow), what it is
ROOM_VROM, ROOM_BIN = 0x12AC000, "assets/jp/segment_012AC000.bin"
ROOM_PALETTE = (0x2A68, "c85a3e21cf8f163976082788d3bb63ac")
POSTER_JOIN = """
..#..#..###.#..#...#.#..##.#
..#.#-#.-#-.##.#...#.#.#--.#
..#.#.#..#..##.#...#.#.-#..#
..#.#.#..#..#-##...#.#..-#.#
#.#.#.#..#..#.##...#.#...#.-
-#-.-#-.###.#.-#...###.##-.#
.-...-..---.-..-...---.--..-
"""
POSTER_WANTED = """
#...#..#..#..#.###.###.##..#
#...#.#.#.##.#..#..#...#.#.#
#.#.#.###.#.##..#..##..#.#.#
#.#.#.#.#.#..#..#..#...#.#..
.#.#..#.#.#..#..#..###.##..#
"""
POSTERS = [(0x3C68, 32, 48, "82ce72eda566267e1126e3698a8e2dcb", (1, 31, 2, 9), 0x3, POSTER_JOIN, (2, 2),
            {"#": 0x7, "-": 0x6}, "the police box's recruiting poster"),
           (0x3968, 48, 32, "282f2ea48881f98b55994da0bf7466e7", (1, 48, 1, 6), 0x2, POSTER_WANTED, (10, 1),
            {"#": 0x1}, "the police box's wanted poster")]


def grid(art):
    """The art's rows (its first and last newline dropped), each as long as the widest."""
    rows = art.strip("\n").split("\n")
    width = max(len(r) for r in rows)
    if any(len(r) != width for r in rows):
        raise ValueError("the art's rows are not all %d wide" % width)
    return rows


def card_cell(x0, y0, xu, yu):
    """The texel (its index in the 128-wide texture) of a card at (x0, y0) seen upright at (xu, yu)."""
    return (y0 + xu) * W + x0 + 31 - yu


def clean_rows(texels, box, ink):
    """Each ink texel in the box the nearest texel of its row that is not ink (the left one where two are as
    near); a row with no texel that is not ink is an error."""
    out = list(texels)
    x0, x1, y0, y1 = box
    for y in range(y0, y1):
        row = texels[y * W:(y + 1) * W]
        for x in range(x0, x1):
            if row[x] not in ink:
                continue
            for d in range(1, W):
                if x - d >= 0 and row[x - d] not in ink:
                    out[y * W + x] = row[x - d]
                    break
                if x + d < W and row[x + d] not in ink:
                    out[y * W + x] = row[x + d]
                    break
            else:
                raise ValueError("row %d of the banner is all ink" % y)
    return out


def lay(texels, art, x0, y0, inks):
    """The art's inked texels over the texture with its top left at (x0, y0)."""
    out = list(texels)
    for dy, row in enumerate(grid(art)):
        for dx, c in enumerate(row):
            if c in inks:
                if not (0 <= x0 + dx < W and 0 <= y0 + dy < H):
                    raise ValueError("the art goes off the texture at (%d, %d)" % (x0 + dx, y0 + dy))
                out[(y0 + dy) * W + x0 + dx] = inks[c]
    return out


def card(texels, x0, y0, ground, inks, art, keep):
    """A card painted its ground (but for the upright box keep) and the art drawn upright on it."""
    rows = grid(art)
    if (len(rows[0]), len(rows)) != (16, 32):
        raise ValueError("a card is 16x32 upright, not %dx%d" % (len(rows[0]), len(rows)))
    out = list(texels)
    for yu in range(32):
        for xu in range(16):
            if keep and keep[0] <= xu < keep[1] and keep[2] <= yu < keep[3]:
                continue
            c = rows[yu][xu]
            out[card_cell(x0, y0, xu, yu)] = inks[c] if c in inks else ground
    return out


def board(texels, art):
    """The notice board's face painted white and the art drawn on it."""
    x0, x1, y0, y1 = BOARD_BOX
    rows = grid(art)
    if (len(rows[0]), len(rows)) != (x1 - x0, y1 - y0):
        raise ValueError("the board's face is %dx%d, not %dx%d" % (x1 - x0, y1 - y0, len(rows[0]), len(rows)))
    out = list(texels)
    for y in range(y0, y1):
        for x in range(x0, x1):
            c = rows[y - y0][x - x0]
            out[y * W + x] = BOARD_INKS[c] if c in BOARD_INKS else BOARD_GROUND
    return out


def slips(texels):
    """The fortune slips without their writing: its green, and its red but in the band's rows, the paper's white."""
    out = list(texels)
    x0, x1, y0, y1 = SLIPS_BOX
    for y in range(y0, y1):
        for x in range(x0, x1):
            v = texels[y * W + x]
            if v in SLIPS_GREEN or (v in SLIPS_RED and not SLIPS_BAND[0] <= y < SLIPS_BAND[1]):
                out[y * W + x] = SLIPS_PAPER
    return out


def ci4(texels):
    return bytes(texels[i] << 4 | texels[i + 1] for i in range(0, len(texels), 2))


def compose(obj):
    """{offset: the en build's CI4 bytes} of our textures, from the cartridge's object."""
    out = {}
    t = af_gc_signs.texels(obj[BANNER_AT:BANNER_AT + SIZE])
    out[BANNER_AT] = ci4(lay(clean_rows(t, BANNER_BOX, BANNER_INK), BANNER, *BANNER_PLACE, BANNER_INKS))
    t = af_gc_signs.texels(obj[CARDS_AT:CARDS_AT + SIZE])
    for (x0, y0), ground, inks, art, keep, _ in CARDS:
        t = card(t, x0, y0, ground, inks, art, keep)
    out[CARDS_AT] = ci4(t)
    for at, art in BOARDS:
        out[at] = ci4(board(af_gc_signs.texels(obj[at:at + SIZE]), art))
    for at in HQ_AT:
        t = af_gc_signs.texels(obj[at:at + SIZE])
        out[at] = ci4(lay(clean_rows(t, HQ_BOX, HQ_INK), HQ, *HQ_PLACE, HQ_INKS))
    t = af_gc_signs.texels(obj[PLAQUE_AT:PLAQUE_AT + SIZE])
    out[PLAQUE_AT] = ci4(lay(clean_rows(t, PLAQUE_BOX, PLAQUE_INK), PLAQUE, *PLAQUE_PLACE, PLAQUE_INKS))
    t = slips(af_gc_signs.texels(obj[SLIPS_AT:SLIPS_AT + SIZE]))
    for x0, y0, art in SLIPS:
        t = lay(t, art, x0, y0, SLIPS_INKS)
    out[SLIPS_AT] = ci4(t)
    return out


def poster(texels, w, box, ground, art, place, inks):
    """A poster's title painted its ground and ours laid there (w its width)."""
    out = list(texels)
    h = len(out) // w
    x0, x1, y0, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            out[y * w + x] = ground
    px, py = place
    for dy, row in enumerate(grid(art)):
        for dx, c in enumerate(row):
            if c in inks:
                if not (x0 <= px + dx < x1 and y0 <= py + dy < y1 and py + dy < h):
                    raise ValueError("the art goes off the title's box at (%d, %d)" % (px + dx, py + dy))
                out[(py + dy) * w + px + dx] = inks[c]
    return out


def compose_room(room):
    """{offset: the en build's CI4 bytes} of the police box's room's posters, from the cartridge's file."""
    out = {}
    for at, w, h, _, box, ground, art, place, inks, _ in POSTERS:
        t = af_gc_signs.texels(room[at:at + w * h // 2])
        out[at] = ci4(poster(t, w, box, ground, art, place, inks))
    return out


def check_room(room):
    """The room's posters and palette as our art expects them, or SystemExit."""
    drawn = af_gc_signs.drawn_ci(room)
    pal_at, pal_md5 = ROOM_PALETTE
    if hashlib.md5(room[pal_at:pal_at + 32]).hexdigest() != pal_md5:
        raise SystemExit("af_own_signs: the police box's room's palette at %#x is not the cartridge's" % pal_at)
    for at, w, h, md5, _, _, _, _, _, what in POSTERS:
        if drawn.get(at) != (4, w, h):
            raise SystemExit("af_own_signs: %s: the room's lists draw %s at %#x, not CI4 %dx%d" % (
                what, drawn.get(at), at, w, h))
        if hashlib.md5(room[at:at + w * h // 2]).hexdigest() != md5:
            raise SystemExit("af_own_signs: %s: the texture at %#x is not the cartridge's" % (what, at))
        if room.count(room[at:at + w * h // 2]) != 1:
            raise SystemExit("af_own_signs: %s: the room holds the texture other than at %#x" % (what, at))


def check(obj, pals):
    """The cartridge's textures and palettes as our art expects them, or SystemExit."""
    drawn = af_gc_signs.drawn_ci(obj)
    for offsets, md5, (pal_at, pal_md5), what in TEXTURES:
        for at in offsets:
            if drawn.get(at) != (4, W, H):
                raise SystemExit("af_own_signs: %s: the cartridge's lists draw %s at %#x, not CI4 %dx%d" % (
                    what, drawn.get(at), at, W, H))
            if hashlib.md5(obj[at:at + SIZE]).hexdigest() != md5:
                raise SystemExit("af_own_signs: %s: the texture at %#x is not the cartridge's" % (what, at))
        if obj.count(obj[offsets[0]:offsets[0] + SIZE]) != len(offsets):
            raise SystemExit("af_own_signs: %s: the object holds the texture other than at %s" % (
                what, ", ".join("%#x" % at for at in offsets)))
        if hashlib.md5(pals[pal_at:pal_at + 32]).hexdigest() != pal_md5:
            raise SystemExit("af_own_signs: %s: the palette at %#x is not the cartridge's" % (what, pal_at))


def main(argv):
    rom_path, en = argv
    rom = open(rom_path, "rb").read()
    obj = af_gc_signs.file_at(rom, af_gc_signs.OBJECT_VROM)
    check(obj, af_gc_signs.file_at(rom, af_gc_signs.PALETTE_VROM))
    parts = af_gc_signs.object_parts(open(os.path.join(en, "yamls", "jp", "assets.yaml")).read())
    for at, new in sorted(compose(obj).items()):
        af_gc_signs.put(en, parts, at, obj[at:at + SIZE], new, W, H, "af_own_signs")
    room = af_gc_signs.file_at(rom, ROOM_VROM)
    check_room(room)
    posters = compose_room(room)
    for at, w, h, _, _, _, _, _, _, _ in POSTERS:
        af_gc_signs.put(en, [(0, None, "bin", ROOM_BIN)], at, room[at:at + w * h // 2], posters[at], w, h,
                        "af_own_signs")
    print("af_own_signs: the fireworks stall's banner and cards, the police box's notice board, the fishing tourney "
          "headquarters' sign, the fortune stall's plaque and slips (%d textures), and the police box's two "
          "posters inside" % len(compose(obj)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
