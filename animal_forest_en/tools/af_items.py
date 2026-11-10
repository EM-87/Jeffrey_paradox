#!/usr/bin/env python3
"""Item names in the en build: the GameCube's, read from the user's disc.

    tools/af_items.py gc-files REL MAP OUT      the GameCube's name and article for each of the cartridge's
                                                items, from build/gc's copies of the disc's module and map
                                                (make script; never committed)
    tools/af_items.py compile ROM OUT_BIN OUT_INC [--gc FILE] [--own FILE] [--font ROM] [--gc-font FILE]
                                                the en build's table: a ROM segment of records and its include
    tools/af_items.py pairs ROM REL MAP KIND [FIRST LAST]
                                                the cartridge's names beside the GameCube's (to check the
                                                mapping by eye; prints only, writes nothing)

The cartridge copies an item's name (10 characters, mIN_copy_name_str, from a file of its own at vrom
0x10F4000) into a buffer of 10 bytes, which the menus draw and the message and letter code copy into
their text. The GameCube's names have up to 16 characters (739 of its 1,024 furniture names are longer
than 10), and those buffers stay 10 bytes in some 27 overlays. So the en build writes a token there
(TOKEN_*: a mark, the name's number in the en table, filler to as many cells as the English takes): the
en font draws a token as its name, and the message and letter code copy the name, with its article,
where the token is inserted (reference/NOTES.md, "Item names"). The table is a ROM segment
(item_en_names) of RECORD_SIZE-byte records, read by DMA as the cartridge reads its own names: the
name's length, its article, its cells, and its characters in the N64 font's codes.

The cartridge numbers its names by kind (KINDS: 16 kinds of item, then the furniture, each piece's name
once for each way it faces), and the en table follows that order, densely. The GameCube's tables
(itemName_* and ftrName_table in dataobject.obj, itemArt_* and ftrArt in m_item_name.o) hold the same
kinds in the same order with items of its own added, so a name is the GameCube's at the same index,
except for the plants (a sapling the GameCube added second: PLANT_SHIFT) and the furniture after the
clothes (blocks the GameCube grew: FTR_BLOCKS, and the cartridge's Famicoms, which all have one name:
FTR_SAME). Where the GameCube has no name for the cartridge's item (a placeholder in its table, or an
item of the cartridge's own), ours from script/items.txt (`kind index<TAB>name`, article in OWN_ARTICLE
form `a|an|the|some|-`), else the cartridge's own name and a warning.
"""

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_font  # noqa: E402
from af_font import gc_find, gc_rel_data, map_layout  # noqa: E402
from af_text import ascii_fallback  # noqa: E402
from msgbank import GC_CHARS, N64_BYTES, N64_CHARS  # noqa: E402

NAME_FILE = 0x10F4000                       # vrom (and ROM: not compressed) of the cartridge's names
NAME_LEN = 10                               # the cartridge's names, and the buffers they are copied into
GC_NAME_LEN = 16
# (kind, the cartridge's count, its names' offset in the file, the GameCube's names, its articles); the
# offsets are D_801076BC_jp's (segment 6), the counts what lies between them (each kind's names start on 4)
KINDS = [("paper", 64, 0x008, "itemName_paper", "itemArt_Paper"),
         ("money", 4, 0x288, "itemName_money", "itemArt_Money"),
         ("tool", 36, 0x2B0, "itemName_tool", "itemArt_Tool"),
         ("fish", 32, 0x418, "itemName_fish", "itemArt_Fish"),
         ("cloth", 255, 0x558, "itemName_cloth", "itemArt_Cloth"),
         ("etc", 30, 0xF50, "itemName_etc", "itemArt_Etc"),
         ("carpet", 64, 0x107C, "itemName_carpet", "itemArt_Carpet"),
         ("wall", 64, 0x12FC, "itemName_wall", "itemArt_Wall"),
         ("fruit", 7, 0x157C, "itemName_fruit", "itemArt_Fruit"),
         ("plant", 10, 0x15C4, "itemName_plant", "itemArt_Plant"),
         ("minidisk", 55, 0x1628, "itemName_minidisk", "itemArt_MiniDisk"),
         ("dummy", 1, 0x1850, "itemName_dummy", "itemArt_Diary"),
         ("ticket", 96, 0x185C, "itemName_ticket", "itemArt_Ticket"),
         ("insect", 32, 0x1C1C, "itemName_insect", "itemArt_Insect"),
         ("hukubukuro", 2, 0x1D5C, "itemName_hukubukuro", "itemArt_Hukubukuro"),
         ("kabu", 4, 0x1D70, "itemName_kabu", "itemArt_Kabu"),
         ("ftr", 947, 0x1D98, "ftrName_table", "ftrArt")]
FTR_FACES = 4                               # a piece of furniture's name, once for each way it faces
GC_NAMES_OBJ, GC_ARTS_OBJ = "dataobject.obj", "m_item_name.o"
PLANT_SHIFT = 1                             # the plants after the first: the GameCube's next index
# the furniture: (first, end, what the GameCube's index adds) after the clothes, where the GameCube put its
# mannequins (8), then more insects (8), fish (8) and umbrellas' own patterns (8), and after the cartridge's
# Famicoms (7, one name: FTR_SAME) and its disk drive (ours), more games
FTR_BLOCKS = [(0, 746, 0), (746, 778, 8), (778, 810, 16), (810, 842, 24), (850, 947, 44)]
FTR_SAME = {i: 893 for i in range(842, 849)}
# names the cartridge keeps under another kind: the clothes it shows as furniture (mRmTp_FtrItemNo2Item1ItemNo
# turns them into the clothes before a name is read, as it does the insects, fish and umbrellas: their
# furniture names are never read, and the GameCube's table has none for seven of the clothes'), and a bag of
# money among the other items
FTR_CLOTH = (491, 746)
REMAP = {("etc", 13): ("money", 0)}
ARTICLES = ["-", "a", "an", "the", "some"]  # mIN_ARTICLE_NONE, _A, _AN, _THE, _SOME
RECORD_SIZE = 4 + GC_NAME_LEN               # length, article, cells, 0, the characters
TOKEN_MARK = 0x80                           # the N64 font's unnamed code: no string the game draws has it
TOKEN_DIGIT0, TOKEN_BASE = 0x81, 64         # the name's number in two digits, 0x81-0xC0 (no space, no 0xCD)
TOKEN_LEN = 3
CELL = 12                                   # the cartridge's characters, a cell each
SMALL_ACCENTED = "\u00e0\u00e1\u00e2\u00e4\u00e7\u00e8\u00e9\u00ea\u00eb\u00ed\u00ee\u00ef\u00f1\u00f3\u00f4\u00f6\u00fa\u00fb\u00fc"


def slots():
    """[(kind, index)] of the cartridge's names, in the en table's order."""
    return [(k, i) for k, n, _, _, _ in KINDS for i in range(n)]


def kind_bases():
    """{kind: the en table's number of its first name}."""
    out, n = {}, 0
    for k, count, _, _, _ in KINDS:
        out[k] = n
        n += count
    return out


def gc_source(kind, i):
    """(the GameCube's kind, its index) holding the cartridge's name i of kind, or None (no counterpart)."""
    if (kind, i) in REMAP:
        return REMAP[(kind, i)]
    if kind == "plant":
        return kind, (i if i == 0 else i + PLANT_SHIFT)
    if kind == "ftr":
        if FTR_CLOTH[0] <= i < FTR_CLOTH[1]:
            return "cloth", i - FTR_CLOTH[0]
        if i in FTR_SAME:
            return kind, FTR_SAME[i]
        for first, end, add in FTR_BLOCKS:
            if first <= i < end:
                return kind, i + add
        return None
    return kind, i


def n64_name(rom, kind, i):
    """The cartridge's name i of kind (its 10 bytes)."""
    _, _, at, _, _ = next(k for k in KINDS if k[0] == kind)
    at += (i * FTR_FACES if kind == "ftr" else i) * NAME_LEN
    return rom[NAME_FILE + at:NAME_FILE + at + NAME_LEN]


def printable(text):
    """A real name: ASCII letters (some), digits, small accented letters and the marks names use, not a
    placeholder's bytes (the GameCube's accented capitals and signs, with no plain letter)."""
    return (any("a" <= c.lower() <= "z" for c in text)
            and all(("a" <= c.lower() <= "z") or c.isdigit() or c in SMALL_ACCENTED or c in " .,'-!&:/()+"
                    for c in text))


def gc_names(szs, map_text):
    """{(kind, index): (name, article)} of the GameCube's for the cartridge's names that have one."""
    layout = map_layout(map_text)
    places = {}
    for kind, _, _, names, arts in KINDS:
        places[names] = gc_find(layout, names, GC_NAMES_OBJ)
        places[arts] = gc_find(layout, arts, GC_ARTS_OBJ)
    data = gc_rel_data(szs, layout, max(at + size for at, size in places.values()))
    out = {}
    for kind, i in slots():
        src = gc_source(kind, i)
        if src is None:
            continue
        _, _, _, names, arts = next(k for k in KINDS if k[0] == src[0])
        j = src[1]
        (nat, nsize), (aat, asize) = places[names], places[arts]
        if (j + 1) * GC_NAME_LEN > nsize or j >= asize:
            continue
        text = "".join(GC_CHARS[b] for b in data[nat + j * GC_NAME_LEN:nat + (j + 1) * GC_NAME_LEN]).rstrip(" ")
        if printable(text):
            out[(kind, i)] = (text, data[aat + j])
    return out


def write_gc(names, out):
    with open(out, "w", encoding="utf-8") as f:
        f.write("# The GameCube's item names for the cartridge's (tools/af_items.py gc-files): kind, index, "
                "article, name\n")
        for (kind, i), (text, art) in sorted(names.items(), key=lambda kv: slots().index(kv[0])):
            f.write("%s\t%d\t%s\t%s\n" % (kind, i, ARTICLES[art], text))
    print("%s: %d of the cartridge's %d item names" % (out, len(names), len(slots())))


def read_names(path):
    """{(kind, index): (name, article)} of a file of names (the GameCube's or ours)."""
    out = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        kind, i, art, text = line.split("\t")
        if art not in ARTICLES:
            raise SystemExit("af_items: %s: %s %s: the article %r" % (path, kind, i, art))
        out[(kind, int(i))] = (text, ARTICLES.index(art))
    return out


def n64_bytes(what, text):
    out = ascii_fallback(text)
    if "?" in out and "?" not in text:
        raise SystemExit("af_items: %s: %r has characters the N64's font lacks" % (what, text))
    return bytes(N64_BYTES[c] for c in out)


def cells(data, widths):
    """The cells the name takes at the en font's widths (mFont_GetStringWidth's, rounded up to even), at
    least a token's and at most the cartridge's buffer."""
    w = sum(widths[b] for b in data)
    w += w & 1
    return max(TOKEN_LEN, min(NAME_LEN, -(-w // CELL)))


def records(rom, gc, own, widths):
    """(the segment's bytes, {kind: (from the GameCube, ours, the cartridge's)})."""
    out, counts = bytearray(), {}
    for kind, i in slots():
        if (kind, i) in own:
            (text, art), src = own[(kind, i)], 1
        elif (kind, i) in gc:
            (text, art), src = gc[(kind, i)], 0
        else:
            text, art, src = None, 0, 2
        if text is None:
            data = n64_name(rom, kind, i).rstrip(b" ")
        else:
            data = n64_bytes("%s %d" % (kind, i), text)
        if len(data) > GC_NAME_LEN:
            raise SystemExit("af_items: %s %d: %d characters, past %d" % (kind, i, len(data), GC_NAME_LEN))
        c = counts.setdefault(kind, [0, 0, 0])
        c[src] += 1
        out += bytes([len(data), art, cells(data, widths), 0]) + data.ljust(GC_NAME_LEN, b" ")
    return bytes(out), counts


def include(from_gc):
    bases = kind_bases()
    lines = ["/* The item names' table (tools/af_items.py): the GameCube's from the user's disc, in a ROM segment;"
             " not part of the repository */",
             "#define ITEM_EN_FROM_GC %d /* names as tokens, the table's */" % (1 if from_gc else 0),
             "#define ITEM_EN_COUNT %d" % len(slots()),
             "#define ITEM_EN_RECORD_SIZE %d" % RECORD_SIZE,
             "#define ITEM_EN_NAME_MAX %d" % GC_NAME_LEN,
             "#define ITEM_EN_TOKEN_MARK 0x%02X" % TOKEN_MARK,
             "#define ITEM_EN_TOKEN_DIGIT0 0x%02X" % TOKEN_DIGIT0,
             "#define ITEM_EN_TOKEN_BASE %d" % TOKEN_BASE,
             "#define ITEM_EN_TOKEN_LEN %d" % TOKEN_LEN,
             "#define ITEM_EN_FTR_BASE %d" % bases["ftr"],
             "#define ITEM_EN_FTR_COUNT %d" % next(n for k, n, _, _, _ in KINDS if k == "ftr"),
             "#define ITEM_EN_FTR_FACES %d" % FTR_FACES,
             "#define ITEM_EN_KIND_BASES { %s }" % ", ".join(str(bases[k]) for k, _, _, _, _ in KINDS[:16]),
             "#define ITEM_EN_KIND_COUNTS { %s }" % ", ".join(str(n) for _, n, _, _, _ in KINDS[:16])]
    return "\n".join(lines) + "\n"


def opts_of(argv, names):
    opts, args, i = {}, [], 0
    while i < len(argv):
        if argv[i] in names:
            opts[argv[i]] = argv[i + 1]
            i += 2
        else:
            args.append(argv[i])
            i += 1
    return opts, args


def main(argv):
    if argv[:1] == ["gc-files"]:
        rel, map_path, out = argv[1:4]
        write_gc(gc_names(open(rel, "rb").read(), open(map_path, encoding="latin-1").read()), out)
        return 0
    if argv[:1] == ["pairs"]:
        rom_path, rel, map_path, kind = argv[1:5]
        rom = open(rom_path, "rb").read()
        gc = gc_names(open(rel, "rb").read(), open(map_path, encoding="latin-1").read())
        count = next(n for k, n, _, _, _ in KINDS if k == kind)
        first, last = (int(argv[5]), int(argv[6])) if len(argv) > 6 else (0, count)
        for i in range(first, min(last, count)):
            jp = "".join(N64_CHARS[b] or "?" for b in n64_name(rom, kind, i)).rstrip()
            text, art = gc.get((kind, i), ("", 0))
            src = gc_source(kind, i)
            print("%s %4d  %-12s | %-10s %-5s %s" % (kind, i, jp, "%s %d" % src if src else "-", ARTICLES[art], text))
        return 0
    if argv[:1] == ["compile"]:
        opts, args = opts_of(argv[1:], ("--gc", "--own", "--font", "--gc-font"))
        rom_path, out_bin, out_inc = args
        rom = open(rom_path, "rb").read()
        gc = read_names(opts["--gc"]) if opts.get("--gc") and os.path.exists(opts["--gc"]) else {}
        own = read_names(opts["--own"]) if opts.get("--own") and gc else {}
        font_rom = open(opts.get("--font", rom_path), "rb").read()
        gc_font = open(opts["--gc-font"], "rb").read() if opts.get("--gc-font") else None
        widths = af_font.widths(af_font.font(font_rom, gc_font)[1])
        data, counts = records(rom, gc, own, widths)
        open(out_bin, "wb").write(data)
        open(out_inc, "w").write(include(bool(gc)))
        totals = [sum(c[k] for c in counts.values()) for k in range(3)]
        print("%s: %d names, %d the GameCube's, %d ours, %d the cartridge's%s" % (
            out_bin, len(slots()), totals[0], totals[1], totals[2],
            "" if gc else " (no disc: the en build keeps the cartridge's names)"))
        for kind, (g, o, c) in counts.items():
            if gc and c:
                print("  %s: %d with no English (the cartridge's)" % (kind, c))
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
