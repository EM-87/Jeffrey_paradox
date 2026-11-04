#!/usr/bin/env python3
"""The menus' own words: strings a menu draws with the font from its own data,
not from the text banks.

    tools/af_ui.py gc ISO OUT            the GameCube's, from the user's disc (make script)
    tools/af_ui.py gc-files REL MAP OUT  the same from the disc's foresta.rel.szs and foresta.map
                                         that make script keeps in build/gc
    tools/af_ui.py compile DECOMP OUT [--gc FILE] [--own FILE]
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

SUBST names the strings the en build swaps where the font draws them
(func_80090E98_jp, the font's line, in the en m_font.c), so that the menu
drawing them stays as the cartridge has it: the cartridge's string, read
from the dump (the key: never part of the repository), the English in its
place (the GameCube's, or our own from --own, script/ui.txt, which also
overrides the GameCube's) and how the English takes the cartridge's box
(its full-width characters, 12 pixels each, or a box of so many cells
around it: BOX): centred, narrowed to it when wider, or kept in its cells,
a character a cell (a template the menu draws numbers into). A key is the
cartridge's string, or pieces of its data with a HOLE where a string the
menu builds has its own part (a price's digits); that English has a hole
too, `{}` in our text, which the menu's part fills. TYPED(n): the menu types
the string into a buffer of n bytes, a character a frame, and draws as much
as it has typed (the key is the whole buffer's, without its trailing
spaces). `compile` writes them as UI_EN_SUBST, a sentinel last.
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

# how the English takes the cartridge's box: aligned left (0), centred or right; narrowed to the box when
# wider; or each character in its own 12-pixel cell (the en m_font.c: mFont_EN_SUBST_*). BOX(n): the box n
# cells wide around the cartridge's string (centred on it, or from its left or right edge as the English
# aligns), in place of its own length. TYPED(n): the string typed into a buffer of n bytes
LEFT, CENTER, RIGHT, FIT, CELLS = 0, 1, 2, 4, 8
HOLE = None                                           # in a key's pieces: the part the menu builds itself
EN_HOLE = "{}"                                        # in the English: where that part goes
NO_HOLE = 0xFF                                        # mFont_EN_SUBST_NO_HOLE
SUBST_KEY_MAX, SUBST_EN_MAX = 24, 32


def BOX(cells):
    if not 0 < cells <= 0xFF:                         # a u8 in mFont_EnSubst
        raise ValueError("a box of %d cells" % cells)
    return cells << 8


def TYPED(size):
    if not 0 < size <= 0xFF:
        raise ValueError("a buffer of %d bytes" % size)
    return size << 16


def box_of(layout):
    return (layout >> 8) & 0xFF


def typed_of(layout):
    return layout >> 16


GY = TYPED(12) | LEFT | FIT | BOX(16)                 # the gyroid's balloon
GY_THATS = (0x7951A0, 3)                              # D_8088D9F0_jp

TG_WORDS = 0x7812B0                                   # tag_ovl's words: D_80878AE0_jp and on, 12 bytes each


def TG_WORD(i):
    """The tag's i-th word: 8 characters, the rest of its 12 bytes the procedure it runs."""
    return (TG_WORDS + 12 * i, 8)


def GC_WORD(sym):
    """A GameCube tag word (mTG_tag_word_c): 16 characters, spaces after the word, then its procedures."""
    return (sym, 16)


# (name, the cartridge's string: its ROM offset and length, or a list of such pieces and HOLE; layout; the
# GameCube's: symbol, or (symbol, the bytes of it that are the string, its trailing spaces dropped), and
# object, or None)
SUBST = [
    # an amount to enter (ovl__0079B120, func_80898128_jp): its title; the end of the entry, the same
    # bytes in the birthday's (ovl__0079DA50) and the clock's (ovl__0078AE30)
    ("RP_TITLE", (0x79BED4, 9), CENTER | FIT, "kingaku_str$537", "m_repay_ovl.o"),
    ("OK", (0x79BEE0, 3), CENTER, "kettei_str$538", "m_repay_ovl.o"),
    # the birthday (ovl__0079DA50, func_8089A684_jp): its title
    ("BD_TITLE", (0x79E34C, 10), CENTER | FIT, "chr_table$472", "m_birthday_ovl.o"),
    # the clock (ovl__0078AE30, func_80883B40_jp): its title; the date's and the time's words, which the
    # numbers are drawn between: ours, in the cartridge's cells
    ("TI_TITLE", (0x78BE70, 9), CENTER | FIT, "str_line_adjust$655", "m_timeIn_ovl.o"),
    ("TI_DATE", (0x78BE7C, 14), CELLS, None, None),
    ("TI_TIME", (0x78BE8C, 5), CELLS, None, None),
    # the town tune's question when it is finished, and its two answers (ovl__0079C020, func_80899088_jp)
    ("MS_QUESTION", (0x79D864, 5), CENTER | FIT, "mMS_str_title", "m_mscore_ovl.o"),
    ("MS_YES", (0x79D86C, 2), LEFT, "mMS_str_ok", "m_mscore_ovl.o"),
    ("MS_NO", (0x79D870, 6), LEFT | FIT, "mMS_str_cancel", "m_mscore_ovl.o"),
    # the Controller Pak's notes (ovl__007A10E0, func_808A5594_jp): its title, which has no GameCube
    # counterpart (a memory card): ours
    ("PK_TITLE", (0x7A27FC, 18), CENTER | FIT, None, None),
    # the gyroid's shop (ovl__00794380): its balloon's line, left at the balloon's edge, at most 16 cells.
    # func_8088CCF4_jp builds it in a buffer of 12 spaces from its table D_8088DA64_jp, the last four after
    # a word (GY_THATS) and the last after a price's digits too (func_8088CC04_jp); func_8088CFB0_jp types
    # it, a character a frame, and func_8088D5D0_jp draws as much as is typed
    ("GY_IKAGA", (0x7951A4, 9), GY, "mes_ikaga$433", "m_haniwa_ovl.o"),
    ("GY_DONO", (0x7951B0, 11), GY, "mes_dono$434", "m_haniwa_ovl.o"),
    ("GY_OIKURA", (0x7951BC, 11), GY, "mes_oikura$436", "m_haniwa_ovl.o"),
    ("GY_KASIKO", (0x7951C8, 8), GY, "mes_kasiko$437", "m_haniwa_ovl.o"),
    ("GY_DOREWO", (0x7951D0, 12), GY, "mes_dorewo$438", "m_haniwa_ovl.o"),
    ("GY_MOU", (0x7951DC, 8), GY, "mes_mou$439", "m_haniwa_ovl.o"),
    ("GY_OKANE", (0x7951E4, 10), GY, "mes_okane$440", "m_haniwa_ovl.o"),
    ("GY_MAIDO", (0x7951F0, 7), GY, "mes_maido$441", "m_haniwa_ovl.o"),
    ("GY_TADA", [GY_THATS, (0x7951F8, 4)], GY, "mes_tada$442", "m_haniwa_ovl.o"),
    ("GY_TADA2", [GY_THATS, (0x7951FC, 9)], GY, "mes_tada2$443", "m_haniwa_ovl.o"),
    ("GY_MISE", [GY_THATS, (0x795208, 7)], GY, "mes_mise$444", "m_haniwa_ovl.o"),
    ("GY_PRICE", [GY_THATS, HOLE, (0x795210, 4)], GY, None, None),
    # the item menu's warnings (ovl__0079A290, from tables of place, string and length: D_80897508_jp and
    # after): two lines each, each centred where the cartridge centred its own, at most 17 cells, the
    # window's width (the GameCube breaks the same warnings over two or three longer lines: ours); the
    # letters' answers
    ("WR_LETTERS1", (0x79AB44, 12), CENTER | FIT | BOX(17), None, None),
    ("WR_LETTERS2", (0x79AB50, 15), CENTER | FIT | BOX(17), None, None),
    ("WR_YES", (0x79AB3C, 3), LEFT | FIT | BOX(6), "wr_Yes", "m_warning_ovl.o"),
    ("WR_NO", (0x79AB40, 2), LEFT | FIT | BOX(6), "wr_No", "m_warning_ovl.o"),
    ("WR_POCKETS1", (0x79AB60, 12), CENTER | FIT | BOX(17), None, None),
    ("WR_PRESENT2", (0x79AB6C, 13), CENTER | FIT | BOX(17), None, None),
    ("WR_BELLS1", (0x79AB7C, 12), CENTER | FIT | BOX(17), None, None),
    ("WR_BELLS2", (0x79AB88, 11), CENTER | FIT | BOX(17), None, None),
    ("WR_TURNIPS1", (0x79ABA0, 9), CENTER | FIT | BOX(17), None, None),
    ("WR_NOGIFT2", (0x79ABBC, 11), CENTER | FIT | BOX(17), None, None),
    ("WR_HELD1", (0x79ABAC, 8), CENTER | FIT | BOX(17), None, None),
    ("WR_SPOIL1", (0x79AB94, 10), CENTER | FIT | BOX(17), None, None),
    ("WR_NOSTORE2", (0x79ABC8, 7), CENTER | FIT | BOX(17), None, None),
    ("WR_SUCH1", (0x79ABB4, 7), CENTER | FIT | BOX(17), None, None),
    ("WR_NOSELL2", (0x79ABD0, 6), CENTER | FIT | BOX(17), None, None),
    ("WR_FLOOR1", (0x79ABD8, 10), CENTER | FIT | BOX(17), None, None),
    ("WR_FLOOR2", (0x79ABE4, 10), CENTER | FIT | BOX(17), None, None),
    ("WR_THATWAY1", (0x79ABF0, 11), CENTER | FIT | BOX(17), None, None),
    ("WR_THATWAY2", (0x79ABFC, 11), CENTER | FIT | BOX(17), None, None),
    ("WR_HERE1", (0x79AC08, 10), CENTER | FIT | BOX(17), None, None),
    ("WR_HERE2", (0x79AC14, 9), CENTER | FIT | BOX(17), None, None),
    ("WR_NOPLANT2", (0x79AC20, 8), CENTER | FIT | BOX(17), None, None),
    ("WR_GRABBAG1", (0x79AC28, 13), CENTER | FIT | BOX(17), None, None),
    ("WR_GRABBAG2", (0x79AC38, 13), CENTER | FIT | BOX(17), None, None),
    ("WR_BOX1", (0x79AC48, 11), CENTER | FIT | BOX(17), None, None),
    ("WR_BOX2", (0x79AC54, 12), CENTER | FIT | BOX(17), None, None),
    ("WR_WRITE1", (0x79AC60, 11), CENTER | FIT | BOX(17), None, None),
    ("WR_WRITE2", (0x79AC6C, 11), CENTER | FIT | BOX(17), None, None),
    # the Controller Pak's messages (ovl__0079F810, from tables like the warnings'): ours, each line centred
    # where the cartridge centred its own, at most 17 cells, the window's width; the options left, where
    # the cursor points
    ("PK_READING1", (0x7A0B10, 14), CENTER | FIT | BOX(17), None, None),
    ("PK_READING2", (0x7A0B20, 8), CENTER | FIT | BOX(17), None, None),
    ("PK_WAIT", (0x7A0B28, 11), CENTER | FIT | BOX(17), None, None),
    ("PK_SAVING", (0x7A0B34, 10), CENTER | FIT | BOX(17), None, None),
    ("PK_ERASING", (0x7A0B40, 14), CENTER | FIT | BOX(17), None, None),
    ("PK_INSERT1", (0x7A0B5C, 8), CENTER | FIT | BOX(17), None, None),
    ("PK_INSERT2", (0x7A0B64, 10), CENTER | FIT | BOX(17), None, None),
    ("PK_INSERT3", (0x7A0B70, 9), CENTER | FIT | BOX(17), None, None),
    ("PK_NOWRITE", (0x7A0B7C, 7), CENTER | FIT | BOX(17), None, None),
    ("PK_FAILED", (0x7A0B84, 8), CENTER | FIT | BOX(17), None, None),
    ("PK_NOREAD", (0x7A0B8C, 7), CENTER | FIT | BOX(17), None, None),
    ("PK_OTHER1", (0x7A0B94, 13), CENTER | FIT | BOX(17), None, None),
    ("PK_OTHER2", (0x7A0BA4, 9), CENTER | FIT | BOX(17), None, None),
    ("PK_NOERASE", (0x7A0BB0, 9), CENTER | FIT | BOX(17), None, None),
    ("PK_PAKS", (0x7A0BC4, 10), CENTER | FIT | BOX(17), None, None),
    ("PK_DAMAGED", (0x7A0BD0, 11), CENTER | FIT | BOX(17), None, None),
    ("PK_RECONNECT1", (0x7A0BDC, 10), CENTER | FIT | BOX(17), None, None),
    ("PK_REPAIR1", (0x7A0BE8, 11), CENTER | FIT | BOX(17), None, None),
    ("PK_LOST1", (0x7A0C00, 12), CENTER | FIT | BOX(17), None, None),
    ("PK_LOST2", (0x7A0C0C, 11), CENTER | FIT | BOX(17), None, None),
    ("PK_LOST3", (0x7A0C18, 8), CENTER | FIT | BOX(17), None, None),
    ("PK_OPT_RECONNECT", (0x7A0C20, 8), LEFT | FIT | BOX(12), None, None),
    ("PK_OPT_REPAIR", (0x7A0C28, 7), LEFT | FIT | BOX(12), None, None),
    ("PK_OPT_QUIT", (0x7A0C30, 7), LEFT | FIT | BOX(12), None, None),
    ("PK_YES", (0x7A0C38, 2), LEFT | FIT | BOX(6), None, None),
    ("PK_NO", (0x7A0C3C, 3), LEFT | FIT | BOX(6), None, None),
    # the tag's words (tag_ovl, func_8087792C_jp: each drawn whole, 8 characters, spaces after the word),
    # left where the cursor points: the GameCube's, by its words' names; ours where it has none. The second
    # word for opening (D_80878C84_jp) has the first's bytes, and its English; the amounts are digits, and
    # the price's two labels (D_80878C24_jp, D_80878C30_jp) frame digits drawn apart: kept
    ("TG_AKERU", TG_WORD(0), LEFT | FIT, GC_WORD("mTG_tag_word_akeru"), "m_tag_ovl.o"),
    ("TG_AGERU", TG_WORD(1), LEFT | FIT, GC_WORD("mTG_tag_word_ageru"), "m_tag_ovl.o"),
    ("TG_ITADAKU", TG_WORD(2), LEFT | FIT, GC_WORD("mTG_tag_word_itadaku"), "m_tag_ovl.o"),
    ("TG_UN", TG_WORD(3), LEFT | FIT, GC_WORD("mTG_tag_word_dump_item"), "m_tag_ovl.o"),
    ("TG_URU", TG_WORD(4), LEFT | FIT, GC_WORD("mTG_tag_word_sell"), "m_tag_ovl.o"),
    ("TG_OKURU", TG_WORD(5), LEFT | FIT, GC_WORD("mTG_tag_word_okuru"), "m_tag_ovl.o"),
    ("TG_KAKINAOSU", TG_WORD(6), LEFT | FIT, GC_WORD("mTG_tag_word_kakinaosu"), "m_tag_ovl.o"),
    ("TG_KABENIHARU", TG_WORD(7), LEFT | FIT, GC_WORD("mTG_tag_word_kabeniharu"), "m_tag_ovl.o"),
    ("TG_IRERU", TG_WORD(8), LEFT | FIT, GC_WORD("mTG_tag_word_korewoireru"), "m_tag_ovl.o"),
    ("TG_UERU", TG_WORD(9), LEFT | FIT, GC_WORD("mTG_tag_word_zimenniueru"), "m_tag_ovl.o"),
    ("TG_OKU", TG_WORD(10), LEFT | FIT, GC_WORD("mTG_tag_word_zimennioku"), "m_tag_ovl.o"),
    ("TG_SUTERU", TG_WORD(11), LEFT | FIT, GC_WORD("mTG_tag_word_suteru"), "m_tag_ovl.o"),
    ("TG_TADA", TG_WORD(12), LEFT | FIT, GC_WORD("mTG_tag_word_tada"), "m_tag_ovl.o"),
    ("TG_TUKAMU", TG_WORD(13), LEFT | FIT, GC_WORD("mTG_tag_word_tukamu"), "m_tag_ovl.o"),
    ("TG_TEGAMI", TG_WORD(14), LEFT | FIT, GC_WORD("mTG_tag_word_tegamiwokaku"), "m_tag_ovl.o"),
    ("TG_NEDAN", TG_WORD(15), LEFT | FIT, GC_WORD("mTG_tag_word_nedanwotukeru"), "m_tag_ovl.o"),
    ("TG_PRESENT", TG_WORD(16), LEFT | FIT, GC_WORD("mTG_tag_word_present"), "m_tag_ovl.o"),
    ("TG_MISERU", TG_WORD(17), LEFT | FIT, GC_WORD("mTG_tag_word_miserudake"), "m_tag_ovl.o"),
    ("TG_YAMERU", TG_WORD(18), LEFT | FIT, GC_WORD("mTG_tag_word_yameru"), "m_tag_ovl.o"),
    ("TG_HEYA", TG_WORD(19), LEFT | FIT, GC_WORD("mTG_tag_word_heyanioku"), "m_tag_ovl.o"),
    ("TG_YUKA", TG_WORD(20), LEFT | FIT, GC_WORD("mTG_tag_word_yukanisiku"), "m_tag_ovl.o"),
    ("TG_YOMU", TG_WORD(21), LEFT | FIT, GC_WORD("mTG_tag_word_yomu"), "m_tag_ovl.o"),
    ("TG_WATASU", TG_WORD(22), LEFT | FIT, GC_WORD("mTG_tag_word_watasu"), "m_tag_ovl.o"),
    ("TG_OSAMERU", TG_WORD(29), LEFT | FIT, GC_WORD("mTG_tag_word_osameru"), "m_tag_ovl.o"),
    ("TG_ZENBU", TG_WORD(30), LEFT | FIT, GC_WORD("mTG_tag_word_zenbutukamu"), "m_tag_ovl.o"),
    ("TG_ICHIMAI", TG_WORD(31), LEFT | FIT, GC_WORD("mTG_tag_word_1maitukamu"), "m_tag_ovl.o"),
    ("TG_ORDER", TG_WORD(32), LEFT | FIT, GC_WORD("mTG_tag_word_order"), "m_tag_ovl.o"),
    ("TG_UMERU", TG_WORD(33), LEFT | FIT, GC_WORD("mTG_tag_word_zimenniumeru"), "m_tag_ovl.o"),
    ("TG_NIGASU", TG_WORD(34), LEFT | FIT, GC_WORD("mTG_tag_word_nigasu"), "m_tag_ovl.o"),
    ("TG_KESU", TG_WORD(36), LEFT | FIT, None, None),
    ("TG_HAI", TG_WORD(37), LEFT | FIT, GC_WORD("mTG_tag_word_hai"), "m_tag_ovl.o"),
    ("TG_IIE", TG_WORD(38), LEFT | FIT, GC_WORD("mTG_tag_word_iie"), "m_tag_ovl.o"),
    # the tag's question before something is thrown away (func_80877FF4_jp): one line, or two the GameCube
    # has only as placeholders (ours)
    ("TG_SUTERUNO", (0x781838, 5), LEFT | FIT, "mTG_tag_str_suteruno", "m_tag_ovl.o"),
    ("TG_HONTONI", (0x781840, 4), LEFT | FIT, None, None),
    ("TG_IIDESUKA", (0x781844, 6), LEFT | FIT, None, None),
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


def gc_sources():
    """[(name, symbol, object)] of every string read from the GameCube."""
    return ([(name, sym, obj) for name, _, _, sym, obj in STRINGS]
            + [(name, sym, obj) for name, _, _, sym, obj in SUBST if sym])


def key_pieces(key):
    """A SUBST key's pieces: (ROM offset, length) and HOLE."""
    return key if isinstance(key, list) else [key]


def key_bytes(rom, key):
    """(the key's bytes from the dump, where its hole is in them or None)."""
    out, hole = b"", None
    for piece in key_pieces(key):
        if piece is HOLE:
            hole = len(out)
        else:
            out += rom[piece[0]:piece[0] + piece[1]]
    return out, hole


def gc_strings(szs, map_text):
    """{name: text} of the GameCube's strings (of a symbol given with a length, that many bytes, its trailing
    spaces dropped)."""
    layout = map_layout(map_text)
    places, sized = {}, set()
    for name, sym, obj in gc_sources():
        if isinstance(sym, tuple):
            sym, size = sym
            places[name] = (gc_find(layout, sym, obj)[0], size)
            sized.add(name)
        else:
            places[name] = gc_find(layout, sym, obj)
    data = gc_rel_data(szs, layout, max(at + size for at, size in places.values()))
    out = {name: "".join(GC_CHARS[b] for b in data[at:at + size]) for name, (at, size) in places.items()}
    return {name: text.rstrip(" ") if name in sized else text for name, text in out.items()}


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


def substitutions(rom, gc, own):
    """[(name, the cartridge's bytes, the English bytes, layout, the key's hole, the English's)] of SUBST's
    strings that have English (ours over the GameCube's); a hole is where the menu's own part goes, or
    None."""
    out = []
    for name, spec, layout, _, _ in SUBST:
        text = own.get(name, gc.get(name))
        if text is None:
            continue
        key, hole = key_bytes(rom, spec)
        if (hole is None) != (text.count(EN_HOLE) == 0) or text.count(EN_HOLE) > 1:
            raise SystemExit("af_ui: %s: the key has %s hole, the English %d" % (
                name, "no" if hole is None else "a", text.count(EN_HOLE)))
        before, _, after = text.partition(EN_HOLE)
        en, en_hole = n64_bytes(name, before + after), None if hole is None else len(n64_bytes(name, before))
        if typed_of(layout) and len(key) > typed_of(layout):
            raise SystemExit("af_ui: %s: %d bytes typed into %d" % (name, len(key), typed_of(layout)))
        if layout & CELLS and (hole is not None or typed_of(layout)):
            raise SystemExit("af_ui: %s: a template in cells has no hole and is not typed" % name)
        if layout & CELLS:
            if len(en) > len(key):
                raise SystemExit("af_ui: %s: %d cells, the cartridge's string has %d" % (name, len(en), len(key)))
            en = en.ljust(len(key), b" ")                     # the cells after the text: blank
        if len(key) > SUBST_KEY_MAX or len(en) > SUBST_EN_MAX:
            raise SystemExit("af_ui: %s: %d and %d bytes, past %d and %d" % (name, len(key), len(en),
                                                                              SUBST_KEY_MAX, SUBST_EN_MAX))
        out.append((name, key, en, layout, hole, en_hole))
    return out


def subst_include(subs):
    def init(data):
        return "{ %s }" % ", ".join("0x%02X" % b for b in data) if data else "{ 0 }"
    lines = ["#define UI_EN_SUBST_KEY_MAX %d" % SUBST_KEY_MAX, "#define UI_EN_SUBST_EN_MAX %d" % SUBST_EN_MAX,
             "#define UI_EN_SUBST_COUNT %d" % len(subs), "#define UI_EN_SUBST \\"]
    for name, key, en, layout, hole, en_hole in subs:
        lines.append("    { %d, %d, %d, %d, %d, %d, %d, %s, %s }, /* %s */ \\" % (
            len(key), len(en), layout & 0xFF, box_of(layout), typed_of(layout),
            NO_HOLE if hole is None else hole, NO_HOLE if en_hole is None else en_hole, init(key), init(en), name))
    lines.append("    { 0, 0, 0, 0, 0, 0, 0, { 0 }, { 0 } }")
    return "\n".join(lines) + "\n"


def write_gc(strings, out):
    with open(out, "w", encoding="utf-8") as f:
        f.write("# The GameCube's strings for the menus (tools/af_ui.py gc): name, a tab, the text\n")
        for name, _, _ in gc_sources():
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
        own = read_text(argv[argv.index("--own") + 1]) if "--own" in argv else {}
        rom = open(os.path.join(decomp, ROM), "rb").read()
        strings = []
        for name, at, size, _, _ in STRINGS:
            strings.append((name, n64_bytes(name, gc[name]) if name in gc else rom[at:at + size]))
        subs = substitutions(rom, gc, own)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        open(out, "w").write(include(strings, bool(gc)) + subst_include(subs))
        print("%s: %d of the menus' strings, %s; %d swapped where the font draws them" % (
            out, len(strings), "the GameCube's" if gc else "the cartridge's", len(subs)))
        return 0
    raise SystemExit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
