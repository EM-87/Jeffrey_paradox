#!/usr/bin/env python3
"""The menus' own words: strings a menu draws with the font from its own data,
not from the text banks.

    tools/af_ui.py gc ISO OUT            the GameCube's, from the user's disc (make script)
    tools/af_ui.py gc-files REL MAP OUT  the same from the disc's foresta.rel.szs and foresta.map
                                         that make script keeps in build/gc
    tools/af_ui.py compile DECOMP OUT [--gc FILE]
                                         the include the en build's menus use (make rom-en)

Some of what the menus show is neither in the message banks nor a texture:
the name entry's titles, the address book's balloons, the edit-end check's
question are strings in the menu's own data (reference/NOTES.md, "The
menus' own words"). The GameCube keeps the same strings in its code, in
English: STRINGS names each by the cartridge's place (its ROM offset and
length in the decompressed dump) and the GameCube's symbol (in foresta.map's
.data, read from foresta.rel as tools/af_font.py reads the font).

`gc` writes them as text to OUT (build/gc, never committed). `compile`
writes, for each, a C initializer and its length (UI_EN_<name>,
UI_EN_<name>_LEN) in the N64 font's codes: the GameCube's when --gc gives
them, else the cartridge's own from DECOMP's decompressed dump, so that the
build without the disc keeps its menus as they were.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gciso  # noqa: E402
from af_font import gc_find, gc_rel_data, map_layout  # noqa: E402
from msgbank import GC_CHARS, N64_BYTES  # noqa: E402

ROM = "baseroms/jp/baserom-decompressed.z64"

# (name, the cartridge's string: ROM offset and length, the GameCube's: symbol and object)
STRINGS = [
    # the name entry's window titles (ledit_ovl, mLE_win_data): the player's name, the destination,
    # a catchphrase, something to say, a song to request
    ("LEDIT_PLAYER", 0x78C990, 8, "mLE_player_title", "m_ledit_ovl.o"),
    ("LEDIT_COUNTRY", 0x78C998, 9, "mLE_country_title", "m_ledit_ovl.o"),
    ("LEDIT_EPHRASE", 0x78C9A4, 9, "mLE_ephrase_title", "m_ledit_ovl.o"),
    ("LEDIT_RESET", 0x78C9B0, 9, "mLE_reset_title", "m_ledit_ovl.o"),
    ("LEDIT_REQUEST", 0x78C9BC, 9, "mLE_request_title", "m_ledit_ovl.o"),
    # the edit-end check's question and its three answers (ovl__00799580: D_808969F4_jp, the answers'
    # table D_80896A18_jp)
    ("EE_QUESTION", 0x79A174, 9, "mEE_str_table", "m_editEndChk_ovl.o"),
    ("EE_ANSWER0", 0x79A180, 2, "mEE_str_data0", "m_editEndChk_ovl.o"),
    ("EE_ANSWER1", 0x79A184, 5, "mEE_str_data1", "m_editEndChk_ovl.o"),
    ("EE_ANSWER2", 0x79A18C, 9, "mEE_str_data2", "m_editEndChk_ovl.o"),
    # the address book's first balloon (ovl__00792700, func_8088B9F4_jp): an addressee to choose,
    # the book is empty
    ("AD_CHOOSE", 0x7941D8, 12, "mAD_title_str", "m_address_ovl.o"),
    ("AD_EMPTY", 0x7941E4, 12, "mAD_title_str2", "m_address_ovl.o"),
]


def escape(text):
    return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")


def unescape(text):
    out, i = [], 0
    while i < len(text):
        if text[i] == "\\" and i + 1 < len(text):
            out.append({"n": "\n", "t": "\t", "\\": "\\"}[text[i + 1]])
            i += 2
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def gc_strings(szs, map_text):
    """{name: text} of the GameCube's strings."""
    layout = map_layout(map_text)
    places = {name: gc_find(layout, sym, obj) for name, _, _, sym, obj in STRINGS}
    data = gc_rel_data(szs, layout, max(at + size for at, size in places.values()))
    return {name: "".join(GC_CHARS[b] for b in data[at:at + size]) for name, (at, size) in places.items()}


def n64_bytes(name, text):
    """The text in the N64 font's codes; a character the cartridge's font lacks is an error."""
    out = bytearray()
    for ch in text:
        if ch not in N64_BYTES:
            raise SystemExit("af_ui: %s: %r has no code in the N64's font" % (name, ch))
        out.append(N64_BYTES[ch])
    return bytes(out)


def read_text(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line and not line.startswith("#"):
            name, _, text = line.partition("\t")
            out[name] = unescape(text)
    return out


def include(strings, from_gc=False):
    lines = ["/* The menus' strings (tools/af_ui.py): the GameCube's from the user's disc, or the"
             " cartridge's own; not part of the repository */",
             "#define UI_EN_FROM_GC %d /* the GameCube's: a menu may place them as the GameCube does */"
             % (1 if from_gc else 0)]
    for name, data in strings:
        lines.append("#define UI_EN_%s { %s }" % (name, ", ".join("0x%02X" % b for b in data)))
        lines.append("#define UI_EN_%s_LEN %d" % (name, len(data)))
    return "\n".join(lines) + "\n"


def write_gc(strings, out):
    with open(out, "w", encoding="utf-8") as f:
        f.write("# The GameCube's strings for the menus (tools/af_ui.py gc): name, a tab, the text\n")
        for name, _, _, _, _ in STRINGS:
            f.write("%s\t%s\n" % (name, escape(strings[name])))
    print("%s: %d of the menus' strings" % (out, len(strings)))


def main(argv):
    if argv[0] == "gc":
        disc = gciso.Disc(open(argv[1], "rb").read())
        write_gc(gc_strings(disc.read("foresta.rel.szs"), disc.read("foresta.map").decode("latin-1")), argv[2])
        return 0
    if argv[0] == "gc-files":
        write_gc(gc_strings(open(argv[1], "rb").read(), open(argv[2], "rb").read().decode("latin-1")), argv[3])
        return 0
    if argv[0] == "compile":
        decomp, out = argv[1], argv[2]
        gc = read_text(argv[argv.index("--gc") + 1]) if "--gc" in argv else {}
        rom = open(os.path.join(decomp, ROM), "rb").read()
        strings = []
        for name, at, size, _, _ in STRINGS:
            strings.append((name, n64_bytes(name, gc[name]) if name in gc else rom[at:at + size]))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        open(out, "w").write(include(strings, bool(gc)))
        print("%s: %d of the menus' strings, %s" % (out, len(strings), "the GameCube's" if gc else "the cartridge's"))
        return 0
    raise SystemExit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
