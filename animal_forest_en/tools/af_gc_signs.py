#!/usr/bin/env python3
"""The signs the GameCube's English redrew, for the en build.

    tools/af_gc_signs.py GC_MAP GC_REL_SZS BASEROM EN_DIR

The GameCube's Animal Crossing keeps the structures' textures it shares with
the cartridge under the decomp's names, at the same sizes, in its own
format: CI4 in 8x8 tiles (32 bytes a tile, two texels a byte, the high
nibble first), its palettes RGB5A3. Where its English redrew a sign with
words on it (SIGNS: the four shops' names, the first one's door sign and
emblem and the fourth one's sale banner, the dump's sign, the black market's, the countdown board's units,
the police box's plate, the fishing tourney's stall, the houses' name
plates), this takes its texels from the user's disc (build/gc/foresta.rel.szs,
which make script reads, its map naming them), lays them out as the N64's
linear CI4 and writes them over the en tree's copy of the structures' object
(object_00D5E000: the PNG splat extracts for a named texture, the
unextracted part's .bin for the rest), every copy the cartridge has of the
texture (its summer and winter ones, where they are the same); and the
colours the GameCube changed in a palette with them (PALETTES, converted
to RGBA5551: the black market's), into the en tree's palettes' data
(object_00D5B000.data.s: its .palette.inc.c files are not compiled). Each
is checked first: the cartridge's lists draw a CI4 texture of that size
there; the GameCube's texels and the cartridge's are the same texture
(they agree on most of it) redrawn (not all); every other palette the
textures use is the cartridge's, as the GameCube has it (within a step of
rounding on a transparent entry). Run at make rom-en when the disc's
files are there;
nothing it reads or writes enters the repository (reference/NOTES.md, "The
GameCube's signs").
"""

import os
import re
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_font  # noqa: E402
import nafe_diff  # noqa: E402

OBJECT_VROM, OBJECT_DIR = 0xD5E000, "assets/jp/objects/object_00D5E000"
PALETTE_VROM = 0xD5B000
# the palettes' file is built from splat's data (its .palette.inc.c files are not compiled)
PALETTE_DATA = "asm/jp/data/objects/object_00D5B000/object_00D5B000.data.s"
GC_OBJECT = "dataobject.obj"
# the GameCube's texture, the offsets of the cartridge's copies in object_00D5E000, its size, at least how many
# of its texels the two have the same (the same texture: the rest is the redrawn sign), what it is
SIGNS = [
    ("obj_s_yamishop_t1_tex_txt", [0x010C8], (128, 32), 0.80, "the black market's front"),
    ("obj_e_count_t2_tex_txt", [0x07A58], (128, 32), 0.90, "the countdown board's minutes"),
    ("obj_e_count_t3_tex_txt", [0x08258], (128, 32), 0.90, "the countdown board's seconds"),
    ("obj_s_dump_t1_tex", [0x104D0], (128, 32), 0.85, "the dump's sign"),
    ("obj_w_dump_t1_tex", [0x11AC8], (128, 32), 0.85, "the dump's sign, in winter"),
    ("obj_s_house2_name_tex_txt", [0x28768], (32, 32), 0.85, "a house's name plate"),
    ("obj_s_house3_name_tex_txt", [0x2AE58], (32, 32), 0.85, "a house's name plate"),
    ("obj_s_house4_name_tex_txt", [0x2D488], (32, 32), 0.85, "a house's name plate"),
    ("obj_s_house5_name_tex_txt", [0x2FD90], (32, 32), 0.85, "a house's name plate"),
    ("obj_w_house2_name_tex_txt", [0x34CF0], (32, 32), 0.85, "a house's name plate, in winter"),
    ("obj_w_house3_name_tex_txt", [0x373E0], (32, 32), 0.85, "a house's name plate, in winter"),
    ("obj_w_house4_name_tex_txt", [0x39A50], (32, 32), 0.85, "a house's name plate, in winter"),
    ("obj_w_house5_name_tex_txt", [0x3C358], (32, 32), 0.85, "a house's name plate, in winter"),
    ("obj_s_kouban_t1_tex_txt", [0x4CCF8, 0x4EDC8], (128, 32), 0.90, "the police box's plate"),
    ("obj_s_shop1_roof_txt", [0x592D8], (128, 32), 0.85, "the first shop's name"),
    ("obj_w_shop1_roof_txt", [0x5B318], (128, 32), 0.85, "the first shop's name, in winter"),
    ("obj_s_shop1_front_txt", [0x58AD8, 0x5AB18], (128, 32), 0.90, "the first shop's door sign"),
    ("obj_s_shop1_side_txt", [0x59AD8, 0x5BB18], (128, 32), 0.90, "the first shop's emblem"),
    ("obj_s_shop2_t2_tex_txt", [0x5E018, 0x604F8], (128, 32), 0.90, "the second shop's name"),
    ("obj_s_shop3_t3_tex_txt", [0x62A90, 0x65040], (128, 32), 0.80, "the third shop's name"),
    ("obj_s_shop4_t3_tex_txt", [0x67618, 0x69BF8], (128, 32), 0.55, "the fourth shop's name"),
    ("obj_s_shop4_t2_tex_txt", [0x66578], (128, 32), 0.45, "the fourth shop's sale banner"),
    ("obj_w_shop4_t2_tex_txt", [0x68B58], (128, 32), 0.45, "the fourth shop's sale banner, in winter"),
    ("obj_e_turi_t1_tex", [0x8AF98, 0x8D028], (128, 32), 0.90, "the fishing tourney's stall"),
]
# the GameCube's palettes it changed, the cartridge's (in object_00D5B000) they replace
PALETTES = [("obj_s_yamishop_pal", "obj_s_yamishop_pal")]
# the palettes the other signs are drawn with, the GameCube's and where the cartridge's is (its file's vrom, the
# offset): the same colours
SAME_PALETTES = [("obj_e_count_pal", PALETTE_VROM, 0x10E8), ("obj_dump_pal", PALETTE_VROM, 0x11C8),
                 ("obj_s_house2_a_pal", PALETTE_VROM, 0x128), ("obj_police_box_pal", PALETTE_VROM, 0xF08),
                 ("obj_shop1_pal", PALETTE_VROM, 0x968), ("obj_shop2_pal", OBJECT_VROM, 0x5DFF8),
                 ("obj_shop3_pal", OBJECT_VROM, 0x62A70), ("obj_shop4_pal", OBJECT_VROM, 0x675F8),
                 ("obj_e_turi_pal", PALETTE_VROM, 0x1168)]


def untile_ci4(tex, w, h):
    """The GameCube's tiled CI4 (8x8 tiles, left to right, then down) as the N64's linear CI4 bytes."""
    if len(tex) != w * h // 2 or w % 8 or h % 8:
        raise ValueError("a %dx%d CI4 texture is %d bytes, not %d" % (w, h, w * h // 2, len(tex)))
    texels = bytearray(w * h)
    for t in range(len(tex) // 32):
        tx, ty = t % (w // 8), t // (w // 8)
        for r in range(8):
            for c in range(8):
                b = tex[t * 32 + r * 4 + c // 2]
                texels[(ty * 8 + r) * w + tx * 8 + c] = b >> 4 if c % 2 == 0 else b & 15
    return bytes((texels[i] << 4) | texels[i + 1] for i in range(0, len(texels), 2))


def rgb5a3_to_rgba5551(v):
    """A GameCube palette entry as the N64's: opaque RGB555 as it is; a translucent one's four bits a channel
    widened to five, transparent (the N64's alpha is one bit)."""
    if v & 0x8000:
        r, g, b, a = (v >> 10) & 31, (v >> 5) & 31, v & 31, 1
    else:
        r, g, b, a = [((v >> s) & 15) << 1 | ((v >> s) & 15) >> 3 for s in (8, 4, 0)] + [0]
    return r << 11 | g << 6 | b << 1 | a


def same_colour(x, y):
    """Two RGBA5551 entries the same, or both transparent within a step a channel (the GameCube's translucent
    entries keep four bits a channel)."""
    return x == y or (x & 1 == 0 == y & 1 and all(abs((x >> k & 31) - (y >> k & 31)) <= 1 for k in (11, 6, 1)))


def texels(ci4):
    return [n for b in ci4 for n in (b >> 4, b & 15)]


def drawn_ci(data):
    """{offset: (bits, width, height)} of the CI textures the object's own lists draw (the load's address in
    segment 6, the render tile's format and size)."""
    out, timg, load, render = {}, None, None, None
    for at in range(0, len(data) - 8, 8):
        w0, w1 = struct.unpack(">II", data[at:at + 8])
        op = w0 >> 24
        if op == 0xFD:
            timg = w1
        elif op in (0xF3, 0xF4) and timg is not None and timg >> 24 == 6:
            load = timg & 0xFFFFFF
        elif op == 0xF5 and (w1 >> 24) & 7 == 0:
            render = ((w0 >> 21) & 7, (w0 >> 19) & 3)
        elif op == 0xF2 and (w1 >> 24) & 7 == 0 and load is not None and render is not None:
            if render[0] == 2:
                out[load] = (4 << render[1], (((w1 >> 12) & 0xFFF) >> 2) + 1, ((w1 & 0xFFF) >> 2) + 1)
            load = None
    return out


def file_at(rom, vrom):
    for e in nafe_diff.dma_entries(rom):
        if e[0] == vrom:
            return nafe_diff.file_data(rom, e)
    raise SystemExit("af_gc_signs: no file at vrom %#x" % vrom)


def object_parts(yaml_text):
    """[(start, end, kind, path)] of object_00D5E000's parts in the en tree's splat config: its CI4 textures
    (a PNG each) and its unextracted rest (a .bin), the offsets the file's."""
    at = yaml_text.index("- name: object_00D5E000")
    end = yaml_text.find("\n  - name:", at + 1)
    body = yaml_text[at:end if end > 0 else len(yaml_text)]
    parts = []
    for m in re.finditer(r"\[(0x[0-9A-Fa-f]+), ci4, ([\w/]+), (\d+), (\d+)", body):
        start = int(m.group(1), 16) - OBJECT_VROM
        parts.append((start, start + int(m.group(3)) * int(m.group(4)) // 2, "png",
                      os.path.join(OBJECT_DIR, m.group(2) + ".ci4.png")))
    m = re.search(r"\[(0x[0-9A-Fa-f]+), bin, (\w+)\]", body)
    if m:
        parts.append((int(m.group(1), 16) - OBJECT_VROM, None, "bin", os.path.join(OBJECT_DIR, m.group(2) + ".bin")))
    return parts


def png_chunks(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    at, out = 8, []
    while at < len(data):
        n, kind = struct.unpack(">I4s", data[at:at + 8])
        out.append((kind, data[at + 8:at + 8 + n]))
        at += 12 + n
    return out


def png_chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def png_indices(path):
    """(width, height, depth, the texels' indices) of an indexed PNG of 4 or 8 bits a texel (unfiltered rows or
    the filters zlib's writers use)."""
    chunks = png_chunks(open(path, "rb").read())
    head = dict(chunks)[b"IHDR"]
    w, h, depth, kind = struct.unpack(">IIBB", head[:10])
    if kind != 3 or depth not in (4, 8) or head[12]:
        raise SystemExit("af_gc_signs: %s is not an indexed PNG of 4 or 8 bits, not interlaced" % path)
    raw = zlib.decompress(b"".join(body for k, body in chunks if k == b"IDAT"))
    stride, out, prev = (w * depth + 7) // 8, [], bytearray((w * depth + 7) // 8)
    for y in range(h):
        f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a, b, c = line[i - 1] if i else 0, prev[i], prev[i - 1] if i else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                line[i] = (line[i] + min((abs(p - a), 0, a), (abs(p - b), 1, b), (abs(p - c), 2, c))[2]) & 255
        out += list(line[:w]) if depth == 8 else [n for v in line for n in (v >> 4, v & 15)][:w]
        prev = line
    return w, h, depth, out


def read_ci4_png(path):
    """The N64's CI4 bytes of the PNG splat extracted (pigment64 takes its indices)."""
    _, _, _, idx = png_indices(path)
    return bytes(idx[i] << 4 | idx[i + 1] for i in range(0, len(idx), 2))


def write_ci4_png(path, ci4, w, h):
    """The PNG splat extracted with these texels, at its own depth, its palette and other chunks kept."""
    chunks = png_chunks(open(path, "rb").read())
    width, height, depth, _ = png_indices(path)
    if (width, height) != (w, h):
        raise SystemExit("af_gc_signs: %s is %dx%d, not %dx%d" % (path, width, height, w, h))
    idx = texels(ci4)
    if depth == 8:
        raw = b"".join(b"\0" + bytes(idx[y * w:(y + 1) * w]) for y in range(h))
    else:
        raw = b"".join(b"\0" + ci4[y * w // 2:(y + 1) * w // 2] for y in range(h))
    out = b"\x89PNG\r\n\x1a\n"
    for k, body in chunks:
        if k == b"IDAT":
            continue
        if k == b"IEND":
            out += png_chunk(b"IDAT", zlib.compress(raw, 9))
        out += png_chunk(k, body)
    open(path, "wb").write(out)


def palette_words(data_s, name):
    """(the 16 entries the data's label name holds, the span of its eight .word values in the text)."""
    m = re.search(r"dlabel %s\n((?:.*\.word 0x[0-9A-Fa-f]{8}\n){8})enddlabel %s\n" % (name, name), data_s)
    if m is None:
        raise SystemExit("af_gc_signs: no palette %s of eight words in the palettes' data" % name)
    words = [int(v, 16) for v in re.findall(r"\.word 0x([0-9A-Fa-f]{8})", m.group(1))]
    return [half for w in words for half in (w >> 16, w & 0xFFFF)], m.span(1)


def with_palette(data_s, name, entries):
    """The palettes' data with name's eight words these entries (splat's comments kept)."""
    _, (start, end) = palette_words(data_s, name)
    lines = data_s[start:end].splitlines(True)
    out = [re.sub(r"\.word 0x[0-9A-Fa-f]{8}", ".word 0x%04X%04X" % (entries[2 * k], entries[2 * k + 1]), line)
           for k, line in enumerate(lines)]
    return data_s[:start] + "".join(out) + data_s[end:]


def main(argv):
    map_path, szs_path, rom_path, en = argv
    layout = af_font.map_layout(open(map_path, errors="replace").read())
    want = [n for n, _, _, _, _ in SIGNS] + [g for g, _ in PALETTES] + [g for g, _, _ in SAME_PALETTES]
    places = {}
    for o, s, sym, obj in layout[".data"]:
        if obj == GC_OBJECT and sym in want:
            places.setdefault(sym, []).append((o, s))
    missing = [n for n in want if n not in places]
    if missing:
        raise SystemExit("af_gc_signs: not in the map's %s: %s" % (GC_OBJECT, ", ".join(missing)))
    gc = af_font.gc_rel_data(open(szs_path, "rb").read(), layout,
                             max(o + s for spots in places.values() for o, s in spots))
    found = {}
    for sym, spots in places.items():
        if len({gc[o:o + s] for o, s in spots}) != 1:
            raise SystemExit("af_gc_signs: %s is in the map's %s %d times, not all the same" % (
                sym, GC_OBJECT, len(spots)))
        found[sym] = spots[0]
    rom = open(rom_path, "rb").read()
    obj, pals = file_at(rom, OBJECT_VROM), file_at(rom, PALETTE_VROM)
    drawn = drawn_ci(obj)
    parts = object_parts(open(os.path.join(en, "yamls", "jp", "assets.yaml")).read())

    def gc_palette(name):
        o, s = found[name]
        if s != 32:
            raise SystemExit("af_gc_signs: %s is %d bytes, not a palette of 16" % (name, s))
        return [rgb5a3_to_rgba5551(struct.unpack(">H", gc[o + 2 * i:o + 2 * i + 2])[0]) for i in range(16)]

    files = {OBJECT_VROM: obj, PALETTE_VROM: pals}
    for theirs, vrom, at in SAME_PALETTES:
        a, b = gc_palette(theirs), struct.unpack(">16H", files[vrom][at:at + 32])
        if not all(same_colour(x, y) for x, y in zip(a, b)):
            raise SystemExit("af_gc_signs: the GameCube's %s is not the cartridge's palette at %#x + %#x" % (
                theirs, vrom, at))
    count, copies = 0, 0
    for name, offsets, (w, h), agree, what in SIGNS:
        o, s = found[name]
        new = untile_ci4(gc[o:o + s], w, h)
        for at in offsets:
            if drawn.get(at) != (4, w, h):
                raise SystemExit("af_gc_signs: %s (%s): the cartridge's lists draw %s at %#x, not CI4 %dx%d" % (
                    name, what, drawn.get(at), at, w, h))
            old = obj[at:at + len(new)]
            same = sum(a == b for a, b in zip(texels(old), texels(new)))
            if not agree * w * h <= same < w * h:
                raise SystemExit("af_gc_signs: %s (%s) and the cartridge's at %#x agree on %d of %d texels" % (
                    name, what, at, same, w * h))
            part = next((p for p in parts if p[0] <= at and (p[1] is None or at + len(new) <= p[1])), None)
            if part is None:
                raise SystemExit("af_gc_signs: no part of the en tree's object holds %#x" % at)
            start, _, kind, path = part
            path = os.path.join(en, path)
            if kind == "png":
                held = read_ci4_png(path) if start == at else None
                if held not in (old, new):
                    raise SystemExit("af_gc_signs: %s is not the cartridge's texture at %#x" % (path, at))
                if held != new:
                    write_ci4_png(path, new, w, h)
            else:
                data = bytearray(open(path, "rb").read())
                if data[at - start:at - start + len(new)] not in (old, new):
                    raise SystemExit("af_gc_signs: %s does not hold the cartridge's texture at %#x" % (path, at))
                if data[at - start:at - start + len(new)] != new:
                    data[at - start:at - start + len(new)] = new
                    open(path, "wb").write(bytes(data))
            copies += 1
        count += 1
    data_path = os.path.join(en, PALETTE_DATA)
    data_s = open(data_path).read()
    for theirs, ours in PALETTES:
        at = re.search(r"start: (0x[0-9A-Fa-f]+), type: af_palette, name: %s\b" % ours,
                       open(os.path.join(en, "yamls", "jp", "assets.yaml")).read())
        cart = list(struct.unpack(">16H", pals[int(at.group(1), 16) - PALETTE_VROM:][:32])) if at else None
        # the GameCube's colours where they differ from the cartridge's (a transparent entry's rounding kept)
        new = cart and [c if same_colour(c, g) else g for c, g in zip(cart, gc_palette(theirs))]
        if cart is None or cart == new:
            raise SystemExit("af_gc_signs: the cartridge's %s not found, or the GameCube's is the same" % ours)
        held, _ = palette_words(data_s, ours)
        if held not in (cart, new):
            raise SystemExit("af_gc_signs: the palettes' data does not hold the cartridge's %s" % ours)
        data_s = with_palette(data_s, ours, new)
    if data_s != open(data_path).read():
        open(data_path, "w").write(data_s)
    print("af_gc_signs: %d signs the GameCube redrew (%d copies) and %d palette%s, from the disc" % (
        count, copies, len(PALETTES), "" if len(PALETTES) == 1 else "s"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
