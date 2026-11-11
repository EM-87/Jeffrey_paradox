#!/usr/bin/env python3
"""The title's logo the AF Project's 2010 patch drew in English, for the en build.

    tools/af_nafe_logo.py BASEROM NAFE_ROM|- EN_DIR

The title screen's logo is six pieces that the logo actor (ovl_Animal_Logo)
draws in two layers each (func_80AA0C98_jp), from a segment it loads for
itself (segment_01136000, vrom 0x1136000, which aAL_actor_ct reads into the
zelda arena and its draw makes segment 6). The segment holds the title's
other textures first (the line that asks for START, the copyright line,
the trademark mark: what the actor's code names in segment 6) and the
pieces after them, their vertices, display lists and textures, to its end.
The 2010 patch (NAFE-WIP-2_12_2010.ups by Zoinkity and the AF Project; make
nafe builds its ROM from the user's copy) redrew the pieces as the game's
English name in fewer bytes, and changed the actor to draw them: its two
tables of the pieces' display lists, their places (x of each, y of all),
the colour tables, the trademark mark's x, one word of a display list of
its own, and four values of the drawing's code (which of its own display
lists comes first, and three alphas).

This takes them from the patch's ROM and writes them into the en tree
(EN_DIR, which splat rewrites at every extract): the segment, the
cartridge's up to where its pieces start and the patch's from there,
padded with zeros to the cartridge's size so that nothing after it moves;
the data's words, over the lines of the actor's data (.data.s) of the
symbols in TAKEN; and the four values as the include the en build's
ac_animal_logo.c uses. It first checks that the patch's pieces start where
the cartridge's did and that the cartridge's run to the segment's end,
that what the actor's code names in segment 6 lies below them, and that
below them the patch changed no texel that shows (only the intensity of
texels with no alpha, which the cartridge's keep); that the four
instructions are the cartridge's and the patch's differ only in their
values. Nothing else of the patch is taken: its warning without a
controller (code and text), the title's options text, and the file name it
wrote its text over stay out (listed). Without the patch's ROM (NAFE_ROM
"-") the cartridge's own are written: its segment, its words and its
values. Run at make rom-en; nothing it reads or writes enters the
repository.
"""

import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nafe_diff  # noqa: E402

SEGMENT_VROM = 0x1136000
SEGMENT = "assets/jp/segment_01136000.bin"
OVERLAY_VROM, OVERLAY_VRAM = 0x95FEC0, 0x80A9FC70
ACTOR = "overlays/actors/ovl_Animal_Logo"
DATA = "asm/jp/data/%s/ac_animal_logo.data.s" % ACTOR
CODE_ASM = "asm/jp/nonmatchings/%s" % ACTOR
INCLUDE = "assets/jp/en/animal_logo.inc"

# the two tables of the six pieces' display lists (segment 6 addresses): the pieces' layers
PIECES = ("D_80AA2188_jp", "D_80AA21A0_jp")
# the actor's data the patch changed for its logo, taken whole: the display list drawn between the two layers,
# the pieces' x and the logo's y, the pieces' tables, the colour tables, the trademark mark's x
TAKEN = ("D_80AA20E8_jp", "D_FLT_80AA216C_jp", "D_FLT_80AA2184_jp") + PIECES + (
    "D_80AA21B8_jp", "D_80AA21CC_jp", "D_80AA21E0_jp", "D_80AA21F4_jp", "D_80AA2208_jp", "D_80AA221C_jp",
    "D_80AA2230_jp", "D_80AA2244_jp", "D_80AA2258_jp", "D_80AA227C_jp")
# the values of func_80AA0C98_jp's code the patch changed: (vram, the cartridge's instruction, the include's
# name, what the instruction's immediate is): the %lo of the display list the first layer begins with, the
# first layer's primitive alpha, its environment alpha, the second layer's environment alpha
CODE = ((0x80AA0DDC, 0x254A20C0, "aAL_EN_FIRST_DL", "dl"),
        (0x80AA0E48, 0x358D0078, "aAL_EN_PRIM_ALPHA", "alpha"),
        (0x80AA0EA8, 0x35F800FF, "aAL_EN_ENV_ALPHA", "alpha"),
        (0x80AA0F5C, 0x358800FF, "aAL_EN_ENV2_ALPHA", "alpha"))
# the actor's display lists the first layer can begin with, by the %lo of their address
OWN_DLS = {0x20C0: "D_80AA20C0_jp", 0x20E8: "D_80AA20E8_jp"}

WORD_LINE = re.compile(r"^(\s*/\* ([0-9A-F]+) ([0-9A-F]{8}) ([0-9A-F]{8}) \*/ )\.(word|float) .*$")
LABEL = re.compile(r"^\s*(?:glabel|dlabel) (\w+)")
VRAM_COMMENT = re.compile(r"/\* [0-9A-F]+ ([0-9A-F]{8})\b")
SEGMENT6 = re.compile(r"%lo\(D_6([0-9A-F]{6})\)")


def file_at(rom, vrom):
    for e in nafe_diff.dma_entries(rom):
        if e[0] == vrom:
            return nafe_diff.file_data(rom, e)
    raise SystemExit("af_nafe_logo: no file at vrom %#x" % vrom)


def word(data, vram):
    return struct.unpack(">I", data[vram - OVERLAY_VRAM:vram - OVERLAY_VRAM + 4])[0]


def pieces_used(seg, dls):
    """[(start, size)] of what the display lists at the segment's offsets dls use: themselves (to their
    end), the vertices they load and the textures they load by blocks; anything else they could load
    (another display list, a tile, a palette) is a layout this does not know."""
    used = []
    for at in dls:
        start, timg = at, None
        while True:
            if at + 8 > len(seg):
                raise SystemExit("af_nafe_logo: a piece's display list at %#x runs off the segment" % start)
            w0, w1 = struct.unpack(">II", seg[at:at + 8])
            op = w0 >> 24
            if op == 0x01:                  # G_VTX: n vertices of 16 bytes
                used.append((w1 & 0xFFFFFF, ((w0 >> 12) & 0xFF) * 16))
            elif op == 0xFD:                # G_SETTIMG: the address and the texel size of the next load
                timg = (w1 & 0xFFFFFF, (w0 >> 19) & 3)
            elif op == 0xF3:                # G_LOADBLOCK: lrs + 1 texels of that size
                if timg is None:
                    raise SystemExit("af_nafe_logo: a block loaded before its image at %#x" % at)
                used.append((timg[0], (((w1 >> 12) & 0xFFF) + 1) << timg[1] >> 1))
            elif op in (0xDE, 0xF4, 0xF0):
                raise SystemExit("af_nafe_logo: the piece's display list at %#x has %02X at %#x, which this "
                                 "does not follow" % (start, op, at))
            at += 8
            if op == 0xDF:                  # G_ENDDL
                used.append((start, at - start))
                break
    return sorted(used)


def span(used, length):
    """(first, end, gaps): where the pieces start, how far they reach, the holes between (alignment)."""
    first, end, gaps = used[0][0], used[0][0], []
    for start, size in used:
        if start > end:
            gaps.append((end, start))
        end = max(end, start + size)
    if end > length:
        raise SystemExit("af_nafe_logo: the pieces reach %#x, past the segment's %#x bytes" % (end, length))
    return first, end, gaps


def table(data, vram, count=6):
    return [w & 0xFFFFFF for w in struct.unpack(">%dI" % count, data[vram - OVERLAY_VRAM:vram - OVERLAY_VRAM + 4 * count])]


def symbols(data_s):
    """{name: [vram...]} of the words of each symbol of the data's .s, in order."""
    out, name = {}, None
    for line in data_s.splitlines():
        m = LABEL.match(line)
        if m:
            name = m.group(1)
            continue
        m = WORD_LINE.match(line)
        if m and name:
            out.setdefault(name, []).append(int(m.group(3), 16))
    return out


def rewrite_data(data_s, cart, words):
    """The .s with the lines of the words in `words` ({vram: value}) as .word value, after checking that
    each line is the cartridge's (its comment's word is the cartridge's word there)."""
    out, done = [], set()
    for line in data_s.split("\n"):
        m = WORD_LINE.match(line)
        if m and int(m.group(3), 16) in words:
            vram = int(m.group(3), 16)
            if int(m.group(4), 16) != word(cart, vram):
                raise SystemExit("af_nafe_logo: %s: the line of %08X is not the cartridge's" % (DATA, vram))
            line = "%s.word 0x%08X" % (m.group(1), words[vram])
            done.add(vram)
        out.append(line)
    missing = set(words) - done
    if missing:
        raise SystemExit("af_nafe_logo: %s has no line for %s" % (DATA, ", ".join("%08X" % v for v in sorted(missing))))
    return "\n".join(out)


def labels(en):
    """[(vram, name)] of the actor's functions and data, from the en tree's asm, sorted."""
    out = {}
    for base in (os.path.join(en, CODE_ASM), os.path.dirname(os.path.join(en, DATA))):
        for root, _, files in os.walk(base):
            for f in files:
                if not f.endswith(".s"):
                    continue
                name = None
                for line in open(os.path.join(root, f)):
                    m = LABEL.match(line)
                    if m:
                        name = m.group(1)
                        continue
                    m = VRAM_COMMENT.search(line)
                    if m and name:
                        out.setdefault(int(m.group(1), 16), name)
                        name = None
    return sorted(out.items())


def named(vram, table_):
    best = "%08X" % vram
    for v, name in table_:
        if v > vram:
            break
        best = name
    return best


def include(values):
    lines = ["/* The title's logo, written by tools/af_nafe_logo.py at make rom-en: the 2010 patch's, or the",
             " * cartridge's without it. The display list the first layer begins with, its alphas, the second",
             " * layer's environment alpha (func_80AA0C98_jp). */"]
    for _, _, name, _ in CODE:
        lines.append("#define %s %s" % (name, values[name]))
    return "\n".join(lines) + "\n"


def main(argv):
    if len(argv) != 3:
        raise SystemExit(__doc__)
    rom_path, nafe_path, en = argv
    rom = open(rom_path, "rb").read()
    cart_seg, cart_ovl = file_at(rom, SEGMENT_VROM), file_at(rom, OVERLAY_VROM)
    seg_path, data_path = os.path.join(en, SEGMENT), os.path.join(en, DATA)
    data_s = open(data_path).read()
    syms = symbols(data_s)
    for name in TAKEN:
        if name not in syms:
            raise SystemExit("af_nafe_logo: %s has no %s" % (DATA, name))
    values = {}
    for vram, insn, name, kind in CODE:
        if word(cart_ovl, vram) != insn:
            raise SystemExit("af_nafe_logo: the cartridge's instruction at %08X is %08X, not %08X"
                             % (vram, word(cart_ovl, vram), insn))
        values[name] = OWN_DLS[insn & 0xFFFF] if kind == "dl" else str(insn & 0xFFFF)
    cart_used = pieces_used(cart_seg, [a for t in PIECES for a in table(cart_ovl, syms[t][0])])
    first, end, gaps = span(cart_used, len(cart_seg))
    if end != len(cart_seg) or any(b - a >= 16 for a, b in gaps):
        raise SystemExit("af_nafe_logo: the cartridge's pieces are not the segment's end from %#x" % first)
    names6 = set()
    for root, _, files in os.walk(os.path.join(en, CODE_ASM)):
        for f in files:
            names6.update(int(m, 16) for m in SEGMENT6.findall(open(os.path.join(root, f)).read()))
    if not names6 or max(names6) >= first:
        raise SystemExit("af_nafe_logo: the actor names %s in segment 6, not all below the pieces at %#x"
                         % (sorted("%#x" % n for n in names6), first))
    current = open(seg_path, "rb").read()

    if nafe_path == "-":
        seg, words, note = cart_seg, {}, "the cartridge's (no 2010 patch's ROM: make nafe NAFE_UPS=...)"
    else:
        nafe = open(nafe_path, "rb").read()
        nafe_seg, nafe_ovl = file_at(nafe, SEGMENT_VROM), file_at(nafe, OVERLAY_VROM)
        if len(nafe_ovl) != len(cart_ovl):
            raise SystemExit("af_nafe_logo: the patch's logo actor is %#x bytes, the cartridge's %#x"
                             % (len(nafe_ovl), len(cart_ovl)))
        used = pieces_used(nafe_seg, [a for t in PIECES for a in table(nafe_ovl, syms[t][0])])
        nafe_first, nafe_end, _ = span(used, len(nafe_seg))
        if nafe_first != first:
            raise SystemExit("af_nafe_logo: the patch's pieces start at %#x, the cartridge's at %#x" % (nafe_first, first))
        if len(nafe_seg) > len(cart_seg):
            raise SystemExit("af_nafe_logo: the patch's segment is %#x bytes, more than the cartridge's %#x"
                             % (len(nafe_seg), len(cart_seg)))
        shown = [at for at in range(first) if cart_seg[at] != nafe_seg[at] and (cart_seg[at] & 15 or nafe_seg[at] & 15)]
        if shown:
            raise SystemExit("af_nafe_logo: below the pieces the patch changed texels that show, from %#x" % shown[0])
        hidden = sum(1 for at in range(first) if cart_seg[at] != nafe_seg[at])
        seg = cart_seg[:first] + nafe_seg[first:] + bytes(len(cart_seg) - len(nafe_seg))
        words = {v: word(nafe_ovl, v) for name in TAKEN for v in syms[name]}
        for vram, insn, name, kind in CODE:
            theirs = word(nafe_ovl, vram)
            if theirs & 0xFFFF0000 != insn & 0xFFFF0000:
                raise SystemExit("af_nafe_logo: the patch's instruction at %08X is %08X, not the cartridge's "
                                 "%08X with another value" % (vram, theirs, insn))
            if kind == "dl" and theirs & 0xFFFF not in OWN_DLS:
                raise SystemExit("af_nafe_logo: the patch's first layer begins with %08X, not one of the "
                                 "actor's display lists" % (theirs & 0xFFFF))
            values[name] = OWN_DLS[theirs & 0xFFFF] if kind == "dl" else str(theirs & 0xFFFF)
        taken = set(words) | {v for v, _, _, _ in CODE}
        left = {}
        names = labels(en)
        for off in range(0, len(cart_ovl), 4):
            vram = OVERLAY_VRAM + off
            if cart_ovl[off:off + 4] != nafe_ovl[off:off + 4] and vram not in taken:
                where = named(vram, names)
                left[where] = left.get(where, 0) + 1
        note = ("the 2010 patch's: its pieces from %#x to %#x (%d bytes below them differ only in texels "
                "with no alpha: the cartridge's kept), %d words of the actor's data (%d changed), %d values "
                "of its code; left out: %s"
                % (first, nafe_end, hidden, len(words), sum(1 for v in words if words[v] != word(cart_ovl, v)),
                   len(CODE), ", ".join("%s (%d words)" % kv for kv in sorted(left.items())) or "nothing"))

    if len(current) != len(cart_seg):
        raise SystemExit("af_nafe_logo: %s is %#x bytes, the cartridge's segment %#x" % (SEGMENT, len(current), len(cart_seg)))
    if current != seg:
        open(seg_path, "wb").write(seg)
    all_words = {v: words.get(v, word(cart_ovl, v)) for name in TAKEN for v in syms[name]}
    new_s = rewrite_data(data_s, cart_ovl, all_words)
    if new_s != data_s:
        open(data_path, "w").write(new_s)
    inc_path = os.path.join(en, INCLUDE)
    os.makedirs(os.path.dirname(inc_path), exist_ok=True)
    text = include(values)
    if not os.path.exists(inc_path) or open(inc_path).read() != text:
        open(inc_path, "w").write(text)
    print("af_nafe_logo: the title's logo: %s" % note)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
