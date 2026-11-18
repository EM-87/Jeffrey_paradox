#!/usr/bin/env python3
"""The GameCube's NES where the cartridge has its Famicoms, for the en build.

    tools/af_gc_nes.py BASEROM EN_DIR [GC_MAP GC_REL_SZS]

Eight of the cartridge's pieces of furniture are a Famicom: seven with a
game in it (pieces 842 to 848) and one on its disk drive (849). Each has
an overlay of its own whose move function hands the room's actor its
game's number, 1 to 7, or none for the drive's (the function at 0x54 of
the room's clip, func_8093BD60_jp, which func_8093A364_jp follows to
goto_emu_game), and a profile there that names the piece's object: a file
of 0xE80 bytes (0xE60 the drive's) the room reads into one of its places
of 0x1400 (func_80938D44_jp) and draws as segment 6 (func_80947024_jp): a
palette it loads as palette 15 (func_80946858_jp), a block of textures it
loads whole (func_80946A2C_jp) and two lists (func_80946CFC_jp). The
seven with a game differ in the colours of the cartridge that sticks out
of the Famicom, in nothing else.

The GameCube's Animal Crossing has the same seven at the head of its own
games (fFC_game_table: the numbers 1 to 7 first), under one profile whose
height, scale and flags are the cartridge's (iam_famicom_common), and
draws them as an NES: three lists (fFC_dw names them: the console, its
cord, the game's label), the label's texture and palette out of two
tables by the game (fFC_texture_table, fFC_palette_table). The drive's
piece it draws as the same NES with a label of its own
(aKobDisksystem8_dw names the three lists, a texture and a palette; its
profile, iam_kob_disksystem8, has the cartridge's height, scale and first
word of flags, and another second word, which stays the cartridge's
here). Its lists are the N64's but for what its own drawing code added
(emu64): a texture named by one order with its size (no load into TMEM),
a tile set by another with its way of wrapping, a palette by a third, and
triangles packed five bits a vertex, up to nine and then twelve of them
in one order. Its vertices are the N64's as they are. Its textures are
CI4 in 8x8 tiles and its palettes RGB5A3 (tools/af_gc_signs.py).

With the disc's files (build/gc's foresta.map and foresta.rel.szs, which
make script keeps), this makes eight objects for the N64 out of them, one
a piece: the console's palette, the label's, the five textures laid out
linear, the vertices, and one list of the N64's orders that says what the
three say (each texture loaded as a block when it is drawn; the label's
palette loaded over the console's before the label, as the GameCube's
list does; two triangles an order). The module's own relocations say what
each address in a list is (the map names it), so nothing is taken by its
place. The objects go, one after another, into a file of their own after
code (assets/jp/en/ftr_nes.bin: the segment ftr_en_nes, af_relink
--segment, a dmadata entry in changes.patch): an object is 0x11E0 bytes
and the cartridge's files lie 0x1000 apart. The eight profiles are
pointed at them in the en tree's copies of the overlays' data (their
.data.s, which splat rewrites at every extract): the file's place and
end, the list, no second list and no block of textures to load.

Checked first, on the cartridge (the user's dump): each overlay hands on
the number its piece has in the GameCube's table (the drive's none), its
profile is the one described and its object a file of the cartridge's; on
the disc: each drawing function names the three lists and its label, the
profiles' height, scale and flags are the cartridge's (the drive's second
word of flags aside), every order of the lists is one this knows, every
address is named by a relocation, every triangle is of vertices loaded,
an object is no larger than the room's place and passes the audit the
check runs again (audit), and the eight are the ones that were looked at
in the running game (their md5).

Without the disc's files the eight stay the cartridge's Famicoms: the
profiles' words are put back and the segment is 16 empty bytes.

Nothing of either game enters the repository: names, sizes, offsets and
the orders' numbers (reference/NOTES.md, "The NES").
"""

import bisect
import hashlib
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_font  # noqa: E402
import af_gc_signs  # noqa: E402
import gciso  # noqa: E402
import nafe_diff  # noqa: E402

# --- the cartridge ---------------------------------------------------------------------------------------------------
# the pieces: the game its overlay hands the room (0: none), its object's size, its two lists in the object
CART = {842 + k: (k + 1, 0xE80, 0xCC0, 0xE18) for k in range(7)}        # the Famicoms with a game
CART[849] = (0, 0xE60, 0xC80, 0xDF0)                                    # the Famicom on its disk drive
PIECES = sorted(CART)
MY_ROOM_VROM, MY_ROOM_VRAM = 0x82D7F0, 0x80936710
OVERLAY_TABLE = 0x80947638                  # D_80947638_jp: a piece's overlay, vrom start and end, vram start and end
OVERLAY_SIZE = 0xE0
GAME_AT, GAME_WORD = 0x30, 0x24070000       # addiu a3, zero, <game> before the call (func_80A440D0_jp in 842's)
NO_GAME_WORD = 0x00003825                   # or a3, zero, zero: the drive's
FUNCS_AT, PROFILE_AT, PROFILE_WORDS = 0x80, 0x94, 19
SEG = 0x06000000                            # the object, where the room draws it
PLACE = 0x1400                              # a place of the room's for an object (func_80938D44_jp)
SHAPE = [0x41900000, 0x3C23D70A, 0x04000000, 0x00000800]   # the height (18), the scale (0.01), two words of flags


def cart_profile(piece, rom_start, vram):
    """The profile of the cartridge's piece: the object's vrom start and end, its segment's start and end, two
    opaque lists, two translucent, the block of textures, the palette, a rig and a texture animation, the
    height, the scale, two words of flags, the functions, two spare."""
    _, size, first, second = CART[piece]
    return [rom_start, rom_start + size, SEG, SEG + size, SEG + first, SEG + second, 0, 0, SEG + 0x20, SEG, 0, 0] + \
        SHAPE + [vram + FUNCS_AT, 0, 0]


P_ROM, P_END, P_SEG_END, P_LIST, P_LIST2, P_TEXTURES, P_PALETTE = 0, 1, 3, 4, 5, 8, 9
DATA_S = "asm/jp/data/overlays/other_ovl/ovl__%08X/ovl__%08X.data.s"
WORD_LINE = re.compile(r"^(\s*/\* ([0-9A-F]+) ([0-9A-F]{8}) ([0-9A-F]{8}) \*/ )\.word .*$")
SEGMENT, BIN = "ftr_en_nes", "assets/jp/en/ftr_nes.bin"

# --- the disc --------------------------------------------------------------------------------------------------------
DATA_OBJ, CODE_OBJ = "dataobject.obj", "f_furniture.o"
VERTICES = "int_tak_nes01_v"
LISTS = ["int_tak_nes01_on_model", "int_tak_nes01_onT_model", "int_tak_nes01_game_on_model"]   # as fFC_dw draws them
PALETTE = "int_tak_nes01_pal"               # the console's: the lists load it as palette 15
DRAW, GAMES, TEX_TABLE, PAL_TABLE, GC_PROFILE = ("fFC_dw", "fFC_game_table", "fFC_texture_table",
                                                 "fFC_palette_table", "iam_famicom_common")
DRIVE_DRAW, DRIVE_PROFILE = "aKobDisksystem8_dw", "iam_kob_disksystem8"     # the drive's piece: an NES of its own
GC_PROFILE_SHAPE = 0x20                     # the height, the scale and the flags in the GameCube's profile
DRIVE_SAME = 3                              # of them, the drive's are the cartridge's: not its second word of flags
LABEL_SEG, LABEL_PAL_SEG = 8, 9             # fFC_dw gives the label's list its texture and palette in these
R_ADDR32, R_NOP, R_SECTION, R_END = 1, 201, 202, 203
SECTIONS = {name: k + 1 for k, name in enumerate(af_font.GC_SECTIONS)}

# --- the orders ------------------------------------------------------------------------------------------------------
G_VTX, G_TRI1, G_TRI2, G_TRIN = 0x01, 0x05, 0x06, 0x0A
G_TEXTURE, G_GEOMETRYMODE, G_ENDDL = 0xD7, 0xD9, 0xDF
G_SETOTHERMODE_L, G_SETOTHERMODE_H, G_RDPLOADSYNC, G_RDPPIPESYNC, G_RDPTILESYNC = 0xE2, 0xE3, 0xE6, 0xE7, 0xE8
G_LOADTLUT, G_SETTILESIZE, G_LOADBLOCK, G_SETTILE, G_SETPRIMCOLOR, G_SETCOMBINE, G_SETTIMG = (
    0xF0, 0xF2, 0xF3, 0xF5, 0xFA, 0xFC, 0xFD)
G_SETTILE_DOLPHIN = 0xD2
STATE = (G_TEXTURE, G_SETCOMBINE, G_SETOTHERMODE_L, G_SETPRIMCOLOR, G_GEOMETRYMODE)   # said once while the same
TEXTURE_ON = (0xD7000002, 0xFFFFFFFF)       # the N64's: texture coordinates as they are (the cartridge's lists')
TLUT_RGBA16 = (0xE3001001, 0x00008000)      # palettes are RGBA16 (the cartridge's lists say so; emu64 needs none)
GC_TLUT = 0xF08F4010                        # a palette of 16 as palette 15, the GameCube's way
GC_TILE = 0xD2F0F000                        # tile 0 with palette 15, its two ways of wrapping in 0xF00
WRAP = {0: 2, 1: 0, 2: 1}                   # the GameCube's clamp, repeat and mirror as the N64's
LOAD_TILE, PAL15_TMEM, CACHE = 7, 0x1F0, 32
# the eight objects the disc's files make, as they were looked at in the running game (a check, not a copy)
OBJECTS_MD5 = "d97f998ed95a51c5dd263a8b7435f8a9"


class Module:
    """The disc's module: its sections' bytes, the map's names in them, and what its relocations to itself
    point each word at."""

    def __init__(self, rel, map_text):
        self.rel = rel
        self.layout = af_font.map_layout(map_text)
        ident, _, _, count, table, _, _, _, _, _, imp_at, imp_size = struct.unpack(">12I", rel[:0x30])
        self.sections = [struct.unpack(">II", rel[table + 8 * i:table + 8 * i + 8]) for i in range(count)]
        for name in (".text", ".data"):
            extent = max(o + s for o, s, _, _ in self.layout[name])
            if extent != self.sections[SECTIONS[name]][1]:
                raise SystemExit("af_gc_nes: the map's %s ends at %#x, the module's section is %#x bytes" % (
                    name, extent, self.sections[SECTIONS[name]][1]))
        self.relocs = {}
        for i in range(imp_size // 8):
            module, at = struct.unpack(">II", rel[imp_at + 8 * i:imp_at + 8 * i + 8])
            if module != ident:
                continue
            section, pos = None, 0
            while True:
                delta, kind, target, addend = struct.unpack(">HBBI", rel[at:at + 8])
                at += 8
                pos += delta
                if kind == R_END:
                    break
                if kind == R_SECTION:
                    section, pos = target, 0
                elif kind != R_NOP:
                    self.relocs[(section, pos)] = (kind, target, addend)
        self.index = {}

    def find(self, section, name, obj):
        """(offset, size) of the object's symbol in the section, which must hold it once."""
        hits = [(o, s) for o, s, sym, ob in self.layout[section] if sym == name and ob == obj]
        if len(hits) != 1:
            raise SystemExit("af_gc_nes: %s of %s is in the map's %s %d times, not once" % (name, obj, section, len(hits)))
        return hits[0]

    def bytes(self, section, name, obj):
        at, size = self.find(section, name, obj)
        base = self.sections[SECTIONS[section]][0] & ~3
        return self.rel[base + at:base + at + size]

    def named(self, section_number, offset):
        """(name, object, offset in it) of what lies at the section's offset, or None."""
        name = af_font.GC_SECTIONS[section_number - 1] if 1 <= section_number <= len(af_font.GC_SECTIONS) else None
        if name not in self.layout:
            return None
        if name not in self.index:
            rows = sorted((o, s, sym, ob) for o, s, sym, ob in self.layout[name] if s and not sym.startswith("."))
            self.index[name] = (rows, [r[0] for r in rows])
        rows, starts = self.index[name]
        i = bisect.bisect_right(starts, offset) - 1
        for o, s, sym, ob in reversed(rows[max(0, i - 7):i + 1]):       # the nearest that holds it
            if o <= offset < o + s:
                return sym, ob, offset - o
        return None

    def target(self, section, name, obj, at):
        """What the word at `at` of the symbol points at: (name, object, offset), by the module's relocation
        there, or None."""
        start, size = self.find(section, name, obj)
        hit = self.relocs.get((SECTIONS[section], start + at))
        if hit is None or hit[0] != R_ADDR32 or not 0 <= at < size:
            return None
        return self.named(hit[1], hit[2])

    def named_in(self, section, name, obj):
        """The names the symbol's relocations point at (a function's: the data it names)."""
        start, size = self.find(section, name, obj)
        k = SECTIONS[section]
        out = set()
        for at in range(start, start + size):
            hit = self.relocs.get((k, at))
            if hit is not None:
                what = self.named(hit[1], hit[2])
                if what is not None:
                    out.add(what[0])
        return out


def triangles(orders):
    """([(a, b, c)], orders used) of the GameCube's packed triangles that begin at orders[0]: the count less one
    in seven bits, then five bits a vertex from the low end up, above four spare bits: nine in the first
    order's 49 bits, twelve in each one after it."""
    w0, w1 = orders[0]
    if w0 >> 24 != G_TRIN:
        raise ValueError("not the packed triangles' order")
    count = ((w0 >> 17) & 0x7F) + 1
    bits, room = ((w0 & 0x1FFFF) << 32) | w1, 9
    index, used = [], 1
    while True:
        if bits & 15:
            raise ValueError("packed triangles with their spare bits set")
        index += [(bits >> (4 + 5 * k)) & 31 for k in range(room)]
        if len(index) >= 3 * count:
            break
        if used >= len(orders):
            raise ValueError("packed triangles past the list's end")
        bits, room = (orders[used][0] << 32) | orders[used][1], 12
        used += 1
    if any(index[3 * count:]):
        raise ValueError("packed triangles with vertices past their count")
    return [tuple(index[3 * t:3 * t + 3]) for t in range(count)], used


def tri_orders(tris):
    """The N64's orders for the triangles: two an order, a last one alone."""
    out = []
    for i in range(0, len(tris) - 1, 2):
        (a, b, c), (d, e, f) = tris[i], tris[i + 1]
        out.append((G_TRI2 << 24 | a * 2 << 16 | b * 2 << 8 | c * 2, d * 2 << 16 | e * 2 << 8 | f * 2))
    if len(tris) % 2:
        a, b, c = tris[-1]
        out.append((G_TRI1 << 24 | a * 2 << 16 | b * 2 << 8 | c * 2, 0))
    return out


def log2(n):
    if n < 1 or n & (n - 1):
        raise ValueError("%d is not a power of two" % n)
    return n.bit_length() - 1


def load_block(address, w, h, wrap_s, wrap_t):
    """The N64's orders that load a CI4 texture of w by h at the address as a block and set tile 0 to draw it
    with palette 15 (gsDPLoadTextureBlock_4b's seven)."""
    words = max(1, w // 16)
    wrap = wrap_t << 18 | log2(h) << 14 | wrap_s << 8 | log2(w) << 4
    return [(G_SETTIMG << 24 | 2 << 21 | 2 << 19, address),
            (G_SETTILE << 24 | 2 << 21 | 2 << 19, LOAD_TILE << 24 | wrap),
            (G_RDPLOADSYNC << 24, 0),
            (G_LOADBLOCK << 24, LOAD_TILE << 24 | (((w * h + 3) >> 2) - 1) << 12 | ((1 << 11) + words - 1) // words),
            (G_RDPPIPESYNC << 24, 0),
            (G_SETTILE << 24 | 2 << 21 | (((w >> 1) + 7) >> 3) << 9, 15 << 20 | wrap),
            (G_SETTILESIZE << 24, (w - 1) << 14 | (h - 1) << 2)]


def load_palette(address):
    """The N64's orders that load 16 colours at the address as palette 15 (the room's own: func_80946858_jp)."""
    return [(G_RDPPIPESYNC << 24, 0), (G_SETTIMG << 24 | 2 << 19, address), (G_RDPTILESYNC << 24, 0),
            (G_SETTILE << 24 | PAL15_TMEM, LOAD_TILE << 24), (G_RDPLOADSYNC << 24, 0),
            (G_LOADTLUT << 24, LOAD_TILE << 24 | 15 << 14), (G_RDPPIPESYNC << 24, 0)]


def convert(lists, place, loaded):
    """One list of the N64's orders for the GameCube's lists, drawn one after another.

    lists: [(orders, targets)], a list's orders as (w0, w1) and {an order's number: what its address is}, a
    name or a segment's number. place(what, offset) gives an address in the object. loaded: what the room has
    loaded as palette 15 before the list. Returns (orders, {a texture's name: (w, h)}, the vertices drawn)."""
    out, state, textures, drawn = [], {}, {}, 0
    for orders, targets in lists:
        image, cached, i = None, 0, 0
        if orders[-1] != (G_ENDDL << 24, 0):
            raise SystemExit("af_gc_nes: a list does not end with its end")
        while i < len(orders) - 1:
            w0, w1 = orders[i]
            op = w0 >> 24
            if op in STATE:
                order = TEXTURE_ON if op == G_TEXTURE else (w0, w1)
                if op == G_TEXTURE and (w0, w1) != (TEXTURE_ON[0], 0):
                    raise SystemExit("af_gc_nes: order %d turns the texture on another way (%08X %08X)" % (i, w0, w1))
                if state.get(op) != order:
                    first = not state
                    state[op] = order
                    out.append(order)
                    if first:
                        out += [(G_RDPPIPESYNC << 24, 0), TLUT_RGBA16]
            elif op == G_LOADTLUT:
                if w0 != GC_TLUT or i not in targets:
                    raise SystemExit("af_gc_nes: order %d loads a palette another way (%08X)" % (i, w0))
                if targets[i] != loaded:
                    loaded = targets[i]
                    out += load_palette(place(loaded, 0))
            elif op == G_SETTIMG:
                if (w0 >> 18) & 0x3F != 0x11 or i not in targets:
                    raise SystemExit("af_gc_nes: order %d names a texture that is not CI4 by its size (%08X)" % (i, w0))
                image = (targets[i], (w0 & 0x3FF) + 1, (((w0 >> 10) & 0xFF) + 1) * 4)
            elif op == G_SETTILE_DOLPHIN:
                if w0 & ~0xF00 != GC_TILE or w1 or image is None:
                    raise SystemExit("af_gc_nes: order %d sets a tile another way (%08X %08X)" % (i, w0, w1))
                what, w, h = image
                if textures.setdefault(what, (w, h)) != (w, h):
                    raise SystemExit("af_gc_nes: %s is drawn at two sizes" % (what,))
                out += load_block(place(what, 0), w, h, WRAP[(w0 >> 10) & 3], WRAP[(w0 >> 8) & 3])
                image = None
            elif op == G_VTX:
                count, end = (w0 >> 12) & 0xFF, (w0 & 0xFF) >> 1
                if w0 & 0x00F00F01 or not 0 < count <= end <= CACHE or i not in targets:
                    raise SystemExit("af_gc_nes: order %d loads vertices another way (%08X)" % (i, w0))
                what, at = targets[i]
                if at % 16:
                    raise SystemExit("af_gc_nes: order %d loads vertices from the middle of one" % i)
                out.append((w0, place(what, at)))
                cached, drawn = max(cached, end), max(drawn, at // 16 + count)
            elif op == G_TRIN:
                try:
                    tris, used = triangles(orders[i:-1])
                except ValueError as e:
                    raise SystemExit("af_gc_nes: order %d: %s" % (i, e))
                if any(v >= cached for t in tris for v in t):
                    raise SystemExit("af_gc_nes: order %d draws a vertex that is not loaded" % i)
                out += tri_orders(tris)
                i += used - 1
            else:
                raise SystemExit("af_gc_nes: order %d is one this does not know (%08X %08X)" % (i, w0, w1))
            i += 1
    return out + [(G_ENDDL << 24, 0)], textures, drawn


def gc_lists(module):
    """[(orders, targets)] of LISTS for convert: an address a relocation names is that name (its offset with
    it for the vertices), one in the label's two segments is the segment's number."""
    out = []
    for name in LISTS:
        raw = module.bytes(".data", name, DATA_OBJ)
        orders = [struct.unpack(">II", raw[i:i + 8]) for i in range(0, len(raw), 8)]
        targets = {}
        for i, (w0, w1) in enumerate(orders):
            op = w0 >> 24
            if op not in (G_VTX, G_SETTIMG, G_LOADTLUT):
                continue
            what = module.target(".data", name, DATA_OBJ, 8 * i + 4)
            if what is not None and w1 == 0 and what[1] == DATA_OBJ:
                targets[i] = (what[0], what[2]) if op == G_VTX else what[0]
                if op != G_VTX and what[2]:
                    raise SystemExit("af_gc_nes: %s's order %d is not at the start of %s" % (name, i, what[0]))
            elif what is None and op != G_VTX and w1 in (LABEL_SEG << 24, LABEL_PAL_SEG << 24):
                targets[i] = w1 >> 24
            else:
                raise SystemExit("af_gc_nes: %s's order %d has an address nothing names (%08X)" % (name, i, w1))
        out.append((orders, targets))
    return out


def gc_labels(module):
    """{piece: (the label's texture, its palette)} by name: a game's out of the two tables, once the games'
    table numbers the cartridge's from 1 and the drawing function names the lists and the tables; the drive's
    the two its own drawing function names beside the lists, by their sizes."""
    games = sorted(game for game, _, _, _ in CART.values() if game)
    if list(module.bytes(".data", GAMES, CODE_OBJ)[:len(games)]) != games or games != list(range(1, len(games) + 1)):
        raise SystemExit("af_gc_nes: %s does not begin with the games 1 to %d" % (GAMES, len(games)))
    named = module.named_in(".text", DRAW, CODE_OBJ)
    if named != set(LISTS) | {TEX_TABLE, PAL_TABLE}:
        raise SystemExit("af_gc_nes: %s names %s, not the three lists and the two tables" % (DRAW, sorted(named)))
    out = {}
    for piece, (game, _, _, _) in sorted(CART.items()):
        if game:
            pair = [module.target(".data", table, CODE_OBJ, 4 * (game - 1)) for table in (TEX_TABLE, PAL_TABLE)]
            if any(p is None or p[1] != DATA_OBJ or p[2] for p in pair):
                raise SystemExit("af_gc_nes: the tables' entry %d is not a texture and a palette" % (game - 1))
            out[piece] = (pair[0][0], pair[1][0])
        else:
            named = module.named_in(".text", DRIVE_DRAW, CODE_OBJ)
            rest = sorted(named - set(LISTS), key=lambda name: module.find(".data", name, DATA_OBJ)[1])
            if not set(LISTS) <= named or [module.find(".data", name, DATA_OBJ)[1] for name in rest] != [0x20, 0x200]:
                raise SystemExit("af_gc_nes: %s names %s, not the three lists, a palette and a texture" % (
                    DRIVE_DRAW, sorted(named)))
            out[piece] = (rest[1], rest[0])
    return out


def build(lists, data, label):
    """(an object's bytes, the list's offset in it) with the label's texture and palette named `label`.

    data: {name: bytes} of the palettes (16 N64 colours each, packed), the textures (the N64's linear CI4) and
    the vertices. The object: the console's palette (the profile's), the label's, the textures in the order they
    are first drawn, the vertices, the list."""
    def source(what):
        return data[label[0] if what == LABEL_SEG else label[1] if what == LABEL_PAL_SEG else what]

    _, textures, drawn = convert(lists, lambda what, at: 0, PALETTE)        # what is drawn, in its order
    if len(data[VERTICES]) != 16 * drawn:
        raise SystemExit("af_gc_nes: the lists draw %d vertices of %d" % (drawn, len(data[VERTICES]) // 16))
    for what in (PALETTE, LABEL_PAL_SEG):
        if len(source(what)) != 32:
            raise SystemExit("af_gc_nes: a palette is not 16 colours")
    for what, (w, h) in textures.items():
        if len(source(what)) != w * h // 2:
            raise SystemExit("af_gc_nes: %s is not CI4 of %dx%d" % (what, w, h))
    offsets, body = {}, b""
    for what in [PALETTE, LABEL_PAL_SEG] + list(textures) + [VERTICES]:
        offsets[what] = len(body)
        body += source(what)
    if len(body) % 8:
        raise SystemExit("af_gc_nes: the object's data is not a whole number of orders long")
    orders, _, _ = convert(lists, lambda what, at: SEG + offsets[what] + at, PALETTE)
    return body + b"".join(struct.pack(">II", *o) for o in orders), len(body)


def audit(obj, list_at):
    """What is wrong with an object for the room: [] when its list, from list_at to its end, is the N64's
    orders that convert() writes, each address it names in the object and before the list, each block and
    palette it loads whole in the object, each vertex drawn loaded, and nothing after its end."""
    known = {G_TEXTURE, G_RDPPIPESYNC, G_SETOTHERMODE_H, G_SETCOMBINE, G_SETOTHERMODE_L, G_SETTIMG, G_SETTILE,
             G_RDPLOADSYNC, G_LOADBLOCK, G_SETTILESIZE, G_SETPRIMCOLOR, G_GEOMETRYMODE, G_VTX, G_TRI1, G_TRI2,
             G_RDPTILESYNC, G_LOADTLUT, G_ENDDL}
    bad, image, cached, ended = [], None, 0, False
    if list_at % 8 or not 0 < list_at < len(obj):
        return ["the list is not in the object"]
    for at in range(list_at, len(obj) - 7, 8):
        w0, w1 = struct.unpack(">II", obj[at:at + 8])
        op = w0 >> 24
        if ended:
            if w0 or w1:
                bad.append("%#x: an order after the end" % at)
            continue
        if op not in known:
            bad.append("%#x: an order of no one's (%08X)" % (at, w0))
        elif op in (G_SETTIMG, G_VTX):
            if w1 >> 24 != SEG >> 24 or not 0 <= w1 & 0xFFFFFF < list_at:
                bad.append("%#x: an address outside the object's data (%08X)" % (at, w1))
            elif op == G_SETTIMG:
                image = w1 & 0xFFFFFF
            else:
                count, end = (w0 >> 12) & 0xFF, (w0 & 0xFF) >> 1
                cached = max(cached, end)
                if not 0 < count <= end <= CACHE or (w1 & 0xFFFFFF) + 16 * count > list_at:
                    bad.append("%#x: vertices past the object's data or the cache" % at)
        elif op == G_LOADBLOCK:
            if image is None or image + (((w1 >> 12) & 0xFFF) + 1) * 2 > list_at:
                bad.append("%#x: a block past the object's data" % at)
        elif op == G_LOADTLUT:
            if image is None or image + (((w1 >> 14) & 0x3FF) + 1) * 2 > list_at:
                bad.append("%#x: a palette past the object's data" % at)
        elif op in (G_TRI1, G_TRI2):
            drawn = [(w0 >> 16) & 0xFF, (w0 >> 8) & 0xFF, w0 & 0xFF]
            drawn += [(w1 >> 16) & 0xFF, (w1 >> 8) & 0xFF, w1 & 0xFF] if op == G_TRI2 else []
            if any(v % 2 or v // 2 >= cached for v in drawn):
                bad.append("%#x: a vertex that is not loaded" % at)
        elif op == G_ENDDL:
            ended = True
    return bad if ended else bad + ["no end"]


def gc_objects(module):
    """([an object's bytes for each of PIECES], the list's offset in one), once the disc's two profiles have the
    cartridge's height, scale and flags (the drive's second word of flags aside)."""
    for name, same in ((GC_PROFILE, len(SHAPE)), (DRIVE_PROFILE, DRIVE_SAME)):
        raw = module.bytes(".data", name, CODE_OBJ)[GC_PROFILE_SHAPE:GC_PROFILE_SHAPE + 4 * len(SHAPE)]
        if list(struct.unpack(">%dI" % len(SHAPE), raw))[:same] != SHAPE[:same]:
            raise SystemExit("af_gc_nes: %s has another height, scale or flags than the cartridge's" % name)
    labels = gc_labels(module)
    lists = gc_lists(module)
    data = {VERTICES: module.bytes(".data", VERTICES, DATA_OBJ)}
    names = {PALETTE}
    for orders, targets in lists:
        names |= {t for t in targets.values() if isinstance(t, str)}
    for name in sorted(names | {n for pair in labels.values() for n in pair}):
        data[name] = module.bytes(".data", name, DATA_OBJ)
    sizes = {}
    for orders, targets in lists:
        for i, (w0, _) in enumerate(orders):
            if w0 >> 24 == G_SETTIMG and i in targets:
                sizes[targets[i]] = ((w0 & 0x3FF) + 1, (((w0 >> 10) & 0xFF) + 1) * 4)
    palettes, textures = {p for _, p in labels.values()}, {t for t, _ in labels.values()}
    for name in list(data):
        if name == VERTICES:
            continue
        if name == PALETTE or name in palettes:
            data[name] = struct.pack(">16H", *af_gc_signs.gc_palette(data[name], name))
        else:
            w, h = sizes[LABEL_SEG] if name in textures else sizes[name]
            try:
                data[name] = af_gc_signs.untile_ci4(data[name], w, h)
            except ValueError as e:
                raise SystemExit("af_gc_nes: %s: %s" % (name, e))
    objects, at = [], None
    for piece in PIECES:
        body, at = build(lists, data, labels[piece])
        objects.append(body + b"\0" * (-len(body) % 16))
    if len({len(o) for o in objects}) != 1 or not PLACE >= len(objects[0]) > 0:
        raise SystemExit("af_gc_nes: an object of %s bytes for the room's place of %#x" % (
            sorted({len(o) for o in objects}), PLACE))
    for piece, obj in zip(PIECES, objects):
        bad = audit(obj, at)
        if bad:
            raise SystemExit("af_gc_nes: the object of piece %d: %s" % (piece, "; ".join(bad[:4])))
    if hashlib.md5(b"".join(objects)).hexdigest() != OBJECTS_MD5:
        raise SystemExit("af_gc_nes: the disc's files make other objects than the ones looked at on the screen "
                         "(reference/NOTES.md, \"The NES\"): another disc, or a change to this to look at again")
    return objects, at


def cart_pieces(rom):
    """[(the overlay's vrom, its vram, the profile's words)] of PIECES, each checked: the overlay hands its
    game's number on (the drive's none), its profile is the cartridge's (cart_profile) and its object a file of
    the cartridge's."""
    room = af_gc_signs.file_at(rom, MY_ROOM_VROM)
    files = {e[0]: e[1] for e in nafe_diff.dma_entries(rom)}
    out = []
    for piece in PIECES:
        game = CART[piece][0]
        at = OVERLAY_TABLE - MY_ROOM_VRAM + 16 * piece
        vrom, vrom_end, vram, vram_end = struct.unpack(">4I", room[at:at + 16])
        if vrom_end - vrom != OVERLAY_SIZE or vram_end - vram != OVERLAY_SIZE:
            raise SystemExit("af_gc_nes: piece %d's overlay is not %#x bytes" % (piece, OVERLAY_SIZE))
        ovl = af_gc_signs.file_at(rom, vrom)
        if struct.unpack(">I", ovl[GAME_AT:GAME_AT + 4])[0] != (GAME_WORD | game if game else NO_GAME_WORD):
            raise SystemExit("af_gc_nes: piece %d's overlay does not hand on %s" % (
                piece, "the game %d" % game if game else "no game"))
        words = list(struct.unpack(">%dI" % PROFILE_WORDS, ovl[PROFILE_AT:PROFILE_AT + 4 * PROFILE_WORDS]))
        if words != cart_profile(piece, words[P_ROM], vram) or files.get(words[P_ROM]) != words[P_END]:
            raise SystemExit("af_gc_nes: piece %d's profile is not the cartridge's Famicom's" % piece)
        out.append((vrom, vram, words))
    return out


def set_words(data_s, words, what):
    """The .s with the .word of each vram of `words` rewritten: a text to write, or None for the cartridge's
    own word, which the line's comment keeps."""
    out, left = [], dict(words)
    for line in data_s.split("\n"):
        m = WORD_LINE.match(line)
        if m and int(m.group(3), 16) in left:
            text = left.pop(int(m.group(3), 16))
            line = "%s.word %s" % (m.group(1), "0x" + m.group(4) if text is None else text)
        out.append(line)
    if left:
        raise SystemExit("af_gc_nes: %s: no line for %s" % (what, ", ".join("%08X" % v for v in sorted(left))))
    return "\n".join(out)


def profile_words(vram, index, size, list_at):
    """{vram: text} of the profile's words for the object `index` of the segment, or the cartridge's (None)
    without one."""
    at = vram + PROFILE_AT
    if size is None:
        return {at + 4 * k: None for k in (P_ROM, P_END, P_SEG_END, P_LIST, P_LIST2, P_TEXTURES, P_PALETTE)}
    return {at + 4 * P_ROM: "%s_ROM_START + 0x%X" % (SEGMENT, index * size),
            at + 4 * P_END: "%s_ROM_START + 0x%X" % (SEGMENT, (index + 1) * size),
            at + 4 * P_SEG_END: "0x%08X" % (SEG + size),
            at + 4 * P_LIST: "0x%08X" % (SEG + list_at),
            at + 4 * P_LIST2: "0x00000000",
            at + 4 * P_TEXTURES: "0x00000000",
            at + 4 * P_PALETTE: "0x%08X" % SEG}


def write(path, data, mode="wb"):
    tmp = path + ".tmp"
    with open(tmp, mode) as f:
        f.write(data)
    os.replace(tmp, path)


def main(argv):
    if len(argv) not in (2, 4):
        raise SystemExit(__doc__)
    rom, en = open(argv[0], "rb").read(), argv[1]
    pieces = cart_pieces(rom)
    objects, list_at = None, None
    if len(argv) == 4:
        module = Module(gciso.yaz0(open(argv[3], "rb").read()), open(argv[2], errors="replace").read())
        objects, list_at = gc_objects(module)
    size = len(objects[0]) if objects else None
    for index, (vrom, vram, _) in enumerate(pieces):
        path = os.path.join(en, DATA_S % (vrom, vrom))
        text = open(path).read()
        write(path, set_words(text, profile_words(vram, index, size, list_at), "piece %d" % PIECES[index]), "w")
    os.makedirs(os.path.dirname(os.path.join(en, BIN)), exist_ok=True)
    write(os.path.join(en, BIN), b"".join(objects) if objects else b"\0" * 16)
    if objects:
        print("af_gc_nes: the GameCube's NES for the %d Famicoms (%d objects of %#x bytes, the list at %#x)" % (
            len(pieces), len(objects), size, list_at))
    else:
        print("af_gc_nes: the cartridge's Famicoms (no GameCube files)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
