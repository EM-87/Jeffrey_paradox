#!/usr/bin/env python3
"""Item names in the en build: the GameCube's, read from the user's disc.

    tools/af_items.py gc-files REL MAP OUT      the GameCube's name and article for each of the cartridge's
                                                items, from build/gc's copies of the disc's module and map
                                                (make script; never committed)
    tools/af_items.py compile ROM OUT_BIN OUT_INC [--gc FILE] [--own FILE] [--font ROM] [--gc-font FILE]
                          [--npc-names GC_TABLE --names-seg NAMES_BIN --strings EN_STRING]
                          [--string-tokens OUT_TXT]
                                                the en build's table: a ROM segment of records and its include;
                                                with the animals' names, theirs too, and the catchphrases'
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
item of the cartridge's own), or names another thing than the cartridge's item is (one it drew anew under
the same number), ours from script/items.txt (`kind index<TAB>name`, article in OWN_ARTICLE form
`a|an|the|some|-`), else the cartridge's own name and a warning.

Names longer than an animal's 6 bytes (ANIMAL_NAME_LEN, the N64's field) take the same tokens: the
animals' (the GameCube's table, as tools/af_names.py writes them into the en build's name segment: 38 of
the 220 the segment holds are longer) and the special characters' (their strings in the en string bank,
SPECIAL_NAME_STRINGS: m_npc.c's l_sp_actor_name loads them into 6 bytes). Their records follow the
items', with no article (reference/NOTES.md, "Names longer than 6"); each animal's token is written into
the name segment (NAMES_BIN, af_names.py's output) in its field, which mNpc_LoadNpcNameString copies as
it is, and the special names' are listed in the include (ITEM_EN_SPECIAL_NAMES) for the en m_string.c to
write when it loads one into 6 bytes. The catchphrases longer than an animal's 4 bytes
(ANIMAL_CATCHPHRASE_LEN: the strings the animals' default data names, DEFAULT_DATA, as the en string bank
has them: 198 of 215) take them too, their records after the names'; their tokens go into the string bank
itself (OUT_TXT, laid over the bank when it is compiled: those strings are only ever loaded as
catchphrases), so the game copies them as it copied the words (reference/NOTES.md, "Catchphrases longer
than 4"). So does every other string the cartridge's code loads into fewer bytes than its English takes
(SHORT_LOADED: the words of the villagers' talk, of the fortune teller's reading and of a villager's reply,
the New Year fortune slip's and the shop's name under a notice, 10 bytes each, and the shop's kind, 4): the
message and the letter code expand a token in a string they put in as they do an item's
(reference/NOTES.md, "The second review", "Strings cut by their loaders").

A record holds NAME_MAX characters: the GameCube's 16 and four more, for a name of ours that the
GameCube's length would cut (script/items.txt).

One special character has a name of its own on the GameCube and another's on the cartridge (NAMES_APART:
the policeman at the desk; the cartridge's table gives him the string of the one at the door). With the
disc's strings the en string bank keeps his name in a spare slot (af_text.STRING_REMAP) and the include
tells the en m_npc.c's table the slot (ITEM_EN_NAME_<number>); without them it tells it the cartridge's
string. `special-names EN_M_NPC_C FORESTA_REL_SZS FORESTA_MAP INCLUDE` holds the en table to the GameCube's
own (its m_npc.o's l_sp_actor_name, 12 bytes an entry as the cartridge's): every character of the en table
takes the string the GameCube's table gives it. It reads what the include says (INCLUDE, the one compile
wrote) and refuses one made without the disc's strings, whose table is the cartridge's.

A name of the GameCube's that takes a or an is given the one its first sound asks for (sound_article): the
GameCube's own table has a few the other way round.
"""

import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_font  # noqa: E402
import af_names  # noqa: E402
import af_text  # noqa: E402
import msgbank  # noqa: E402
from af_font import gc_find, gc_rel_data, map_layout  # noqa: E402
from af_text import ascii_fallback  # noqa: E402
from msgbank import GC_CHARS, N64_BYTES, N64_CHARS  # noqa: E402

NAME_FILE = 0x10F4000                       # vrom of the cartridge's names: their place in the decompressed image
NAME_LEN = 10                               # the cartridge's names, and the buffers they are copied into
GC_NAME_LEN = 16                            # the GameCube's names (its tables' entries)
NAME_MAX = 20                               # a record's characters: a name of ours may be a little longer (an
                                            # article and a name must fit mIN_EN_TEXT_MAX, m_item_name.h, and an
                                            # answer's 24 bytes: TEXT_MAX)
TEXT_MAX = 24
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
ARTICLE_WORDS = ["", "a ", "an ", "the ", "some "]   # what each puts before the name (m_item_name.c)
RECORD_SIZE = 4 + NAME_MAX                  # length, article, cells, 0, the characters
TOKEN_MARK = 0x80                           # the N64 font's unnamed code: no string the game draws has it
TOKEN_DIGIT0, TOKEN_BASE = 0x81, 64         # the name's number in two digits, 0x81-0xC0 (no space, no 0xCD)
TOKEN_LEN = 3
CELL = 12                                   # the cartridge's characters, a cell each
ANIMAL_NAME_LEN = 6                         # an animal's name (PLAYER_NAME_LEN, also ANIMAL_NAME_LEN)
CATCHPHRASE_LEN = 4                         # an animal's catchphrase (ANIMAL_CATCHPHRASE_LEN)
# The strings the cartridge's code loads into fewer bytes than their English may take: (the first, how many,
# the loader's bytes). Every call of mString_Load_StringFromRom in the cartridge's asm and C was looked up, 41
# of them (reference/NOTES.md, "Strings cut by their loaders"); the cartridge's own words all fit. One longer
# than its loader's bytes takes a token of that length (its record after the catchphrases'), which the code the
# bytes are handed to expands: a message's item or free string (m_msg_main.c), a letter's free string
# (m_handbill.c).
SHORT_LOADED = (
    # the fortune teller's reading (ovl_Ev_Gypsy, func_809DC590_jp: a word of each of the four lists of its
    # table D_809DCAB0_jp into item strings 0 to 3)
    (0x164, 4 * 32, 10),
    # the villagers' talk (ovl__00815B70, func_80920FFC_jp: a word of a list into an item string): the fish, the
    # insects, the sports, the food and the drinks (func_8092106C_jp, D_80921D2C_jp), the colours
    # (func_809211D8_jp), the places, the games and the things to read (func_809212A8_jp, D_80921D4C_jp), the
    # year's animals and the star signs (func_80921324_jp, D_80921D70_jp: 12 each, and the player's own sign)
    (0x1E5, 32, 10), (0x219, 32, 10), (0x334, 32, 10), (0x314, 32, 10), (0x414, 32, 10), (0x434, 32, 10),
    (0x464, 32, 10), (0x2F4, 32, 10), (0x4A0, 32, 10), (0x458, 12, 10), (0x494, 12, 10),
    # the shop's kind in the villagers' talk (func_809211D8_jp: 0x454 and the shop's size, 4 bytes after a
    # COLORCHARS code in the 10 it hands on: m_msg_main.c's mMsg_en_CopyString takes a token there)
    (0x454, 4, 4),
    # the kind of thing a villager wants (ovl__00815B70 at 0x809201C8: free string 11)
    (0x55D, 4, 10),
    # a villager's reply to a letter (m_npc.c, mNpc_SetRemailFreeString: a word of each of base_str_no's eleven
    # lists into a letter's free strings 3 to 13): the food, the sports, the games, the fish and the insects
    # above, and these six
    (0x354, 32, 10), (0x374, 32, 10), (0x394, 32, 10), (0x3B4, 32, 10), (0x3D4, 32, 10), (0x3F4, 32, 10),
    # the New Year fortune slip (ovl_Ev_Miko, func_809E5BC4_jp: four lists of 16 from its table D_809E62B8_jp,
    # at 673, 689, 714 and 730, and the luck's rank from 705) and the shop's name under three of the bulletin
    # board's notices (code/m_notice, func_800A63F8_jp: 1368 and the shop's size): a letter's free string
    (673, 73, 10), (1368, 4, 10),
    # what the shopkeepers count a thing in (ovl_Npc_Shop_Master and its four fellows, func_809CB044_jp: 0x566
    # or a list of D_809CE698_jp, 8 of 15: free string 8)
    (0x566, 8 * 15, 10),
)
# the animals' default data (NpcDefaultData, 6 bytes each: cloth, catchphraseIdx, umbrella), 218 of them
DEFAULT_DATA, DEFAULT_SIZE, DEFAULT_CATCHPHRASE = 0xE03000, 6, 2
# the special characters' names: the strings m_npc.c's l_sp_actor_name loads into 6 bytes (mNpc_GetNpcWorldNameP)
# a special character the GameCube's table names apart from the cartridge's: {the character's number: (the
# cartridge's string, the en bank's)}; the en bank's is a number of af_text.STRING_REMAP
NAMES_APART = {0xD010: (0x210, 749)}
SPECIAL_NAME_STRINGS = (list(range(0x205, 0x219)) + [0x4E0, 0x4E1, 0x4E2] +
                        sorted(en for _, en in NAMES_APART.values()))
GC_SPECIAL = ("l_sp_actor_name", "m_npc.o")          # the GameCube's table of the special characters' names
SPECIAL_ENTRY = struct.Struct(">HHii")               # an entry: the character, its sex, its name's string, its voice
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
    """The cartridge's name i of kind (its 10 bytes), from the decompressed image, where a file lies at its
    vrom (the en tree's baseroms/jp/baserom-decompressed.z64: the dump keeps the file elsewhere, and a read
    of it there would come back empty)."""
    _, _, at, _, _ = next(k for k in KINDS if k[0] == kind)
    at += (i * FTR_FACES if kind == "ftr" else i) * NAME_LEN
    data = rom[NAME_FILE + at:NAME_FILE + at + NAME_LEN]
    if len(data) != NAME_LEN:
        raise SystemExit("af_items: no name at %#x of an image of %#x bytes: give the decompressed image, where the "
                         "names' file lies at its vrom" % (NAME_FILE + at, len(rom)))
    return data


AN_LETTERS = "AEFHILMNORSX"                 # the letters whose own names start with a vowel's sound


def sound_article(text):
    """a or an (their numbers in ARTICLES) by the first sound of the name's first word, or None for a name
    with no letter or digit: a number as it is read (8, 11, 18: an), a letter or initials read letter by
    letter, the words that start with a vowel and are read with a consonant (one, uni..., use..., eu...) or
    the other way round (hour, honest, heir), and otherwise the first letter."""
    word = re.search(r"[A-Za-z0-9]+", text)
    if not word:
        return None
    word = word.group(0)
    a, an = ARTICLES.index("a"), ARTICLES.index("an")
    if word[0].isdigit():
        digits = re.match(r"\d+", word).group(0)       # 18th, 80s: the number's own sound
        return an if digits[0] == "8" or digits in ("11", "18") else a
    if word.isupper():
        return an if word[0] in AN_LETTERS else a
    low = word.lower()
    if low.startswith(("one", "uni", "use", "usu", "uku", "eu", "ufo")):
        return a
    if low.startswith(("hour", "honest", "heir")):
        return an
    return an if low[0] in "aeiou" else a


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


def cells(data, widths, field=NAME_LEN):
    """The cells the name takes at the en font's widths (mFont_GetStringWidth's, rounded up to even), at
    least a token's and at most the buffer it goes in (the cartridge's 10 bytes for an item)."""
    w = sum(widths[b] for b in data)
    w += w & 1
    return max(TOKEN_LEN, min(field, -(-w // CELL)))


def token(n, cells_, field):
    """The token for the en table's name n, in a buffer of field bytes (as the en mIN_copy_name_str
    writes one): the mark, the number in two digits, the mark again up to the name's cells, spaces."""
    if not 0 <= n < TOKEN_BASE * TOKEN_BASE or not TOKEN_LEN <= cells_ <= field:
        raise ValueError("no token for name %d in %d cells of %d" % (n, cells_, field))
    return (bytes([TOKEN_MARK, TOKEN_DIGIT0 + n // TOKEN_BASE, TOKEN_DIGIT0 + n % TOKEN_BASE])
            + bytes([TOKEN_MARK]) * (cells_ - TOKEN_LEN) + b" " * (field - cells_))


def long_names(npc_table, capacity, strings):
    """[(what, the name's bytes)] of the names longer than an animal's 6 bytes: the animals' that the
    name segment holds (capacity of them; af_names' English for each, by the N64's number), then the
    special characters' ({number: tokens} of the en string bank)."""
    out = []
    for n, name in enumerate(af_names.english_names(npc_table)[:capacity]):
        data = n64_bytes("animal %d" % n, name)
        if len(data) > ANIMAL_NAME_LEN:
            out.append((("animal", n), data))
    for number in SPECIAL_NAME_STRINGS:
        data = msgbank.encode(strings[number]).rstrip(b" ") if number in strings else b""
        if len(data) > ANIMAL_NAME_LEN:
            out.append((("string", number), data))
    return out


def long_catchphrases(segment_data, strings):
    """[(("catchphrase", number), its bytes)] of the catchphrases longer than an animal's 4 bytes: the strings
    the animals' default data names (segment_data, DEFAULT_DATA's file), as the en string bank has them."""
    numbers = sorted({struct.unpack(">H", segment_data[at + DEFAULT_CATCHPHRASE:at + DEFAULT_CATCHPHRASE + 2])[0]
                      for at in range(0, len(segment_data) - DEFAULT_SIZE + 1, DEFAULT_SIZE)})
    out = []
    for number in numbers:
        data = msgbank.encode(strings[number]).rstrip(b" ") if number in strings else b""
        if len(data) > CATCHPHRASE_LEN:
            out.append((("catchphrase", number), data))
    return out


def short_loaded(strings):
    """{loader's bytes: [(("loaded", number), its bytes)]} of SHORT_LOADED's strings longer than the bytes the
    cartridge's code loads them into, as the en string bank has them ({number: tokens}); a string two loaders
    load takes the fewer bytes."""
    size = {}
    for first, count, field in SHORT_LOADED:
        for number in range(first, first + count):
            size[number] = min(field, size.get(number, field))
    out = {}
    for number in sorted(size):
        data = msgbank.encode(strings[number]).rstrip(b" ") if number in strings else b""
        if len(data) > size[number]:
            out.setdefault(size[number], []).append((("loaded", number), data))
    return out


def name_records(names, base, widths, field=ANIMAL_NAME_LEN):
    """(the records of names [(what, bytes)], numbered from base, each at most field cells, and [(what,
    number, its token in field bytes)])."""
    out, tokens = bytearray(), []
    for k, (what, data) in enumerate(names):
        if len(data) > NAME_MAX:
            raise SystemExit("af_items: %s %d: %d characters, past %d" % (what + (len(data), NAME_MAX)))
        c = cells(data, widths, field)
        out += bytes([len(data), 0, c, 0]) + data.ljust(NAME_MAX, b" ")
        tokens.append((what, base + k, token(base + k, c, field)))
    return bytes(out), tokens


def string_tokens(tokens):
    """The string bank's text (tools/msgbank.py's dump form) that puts each string's token in its place:
    tokens [((what, number), _, token)], their bytes written {xx}."""
    lines = ["# The tokens of the catchphrases and of the strings loaded into fewer bytes than they take "
             "(tools/af_items.py), laid over the en string bank: not part of the repository"]
    for (_, number), _, tok in tokens:
        lines += ["", "## %d" % number, "".join("{%02x}" % b for b in tok)]
    return "\n".join(lines) + "\n"


def records(rom, gc, own, widths, mended=None):
    """(the segment's bytes, {kind: (from the GameCube, ours, the cartridge's)}). A name of ours for a piece
    of clothing is also its name as furniture (FTR_CLOTH: the GameCube's table names both). A name of the
    GameCube's that takes a or an takes the one its first sound asks for (sound_article: the GameCube's
    table has a few the other way round); mended, a list, collects the (kind, index) of those changed."""
    out, counts = bytearray(), {}
    indefinite = (ARTICLES.index("a"), ARTICLES.index("an"))
    for kind, i in slots():
        twin = ("cloth", i - FTR_CLOTH[0]) if kind == "ftr" and FTR_CLOTH[0] <= i < FTR_CLOTH[1] else None
        if (kind, i) in own:
            (text, art), src = own[(kind, i)], 1
        elif twin in own:
            (text, art), src = own[twin], 1
        elif (kind, i) in gc:
            (text, art), src = gc[(kind, i)], 0
            if art in indefinite and sound_article(text) not in (None, art):
                art = sound_article(text)
                if mended is not None:
                    mended.append((kind, i))
        else:
            text, art, src = None, 0, 2
        if text is None:
            data = n64_name(rom, kind, i).rstrip(b" ")
        else:
            data = n64_bytes("%s %d" % (kind, i), text)
        if len(data) > NAME_MAX:
            raise SystemExit("af_items: %s %d: %d characters, past %d" % (kind, i, len(data), NAME_MAX))
        if len(ARTICLE_WORDS[art]) + len(data) > TEXT_MAX:
            raise SystemExit("af_items: %s %d: %r with its article is %d characters, past the %d a sentence or an "
                             "answer takes" % (kind, i, text, len(ARTICLE_WORDS[art]) + len(data), TEXT_MAX))
        c = counts.setdefault(kind, [0, 0, 0])
        c[src] += 1
        out += bytes([len(data), art, cells(data, widths), 0]) + data.ljust(NAME_MAX, b" ")
    return bytes(out), counts


def include(from_gc, tokens=(), apart=False):
    """tokens: [(what, number, token)] of the names after the items'. apart: the en string bank holds the
    names of NAMES_APART (the disc's strings), so the en table names them; else the cartridge's strings."""
    bases = kind_bases()
    special = [(w[1], n) for w, n, _ in tokens if w[0] == "string"]
    names = ["#define ITEM_EN_NAME_%04X 0x%03X /* the string of this special character's name (the "
             "cartridge's table: 0x%03X) */" % (number, en if apart else cart, cart)
             for number, (cart, en) in sorted(NAMES_APART.items())]
    lines = ["/* The item names' table (tools/af_items.py): the GameCube's from the user's disc, in a ROM segment;"
             " not part of the repository */",
             "#define ITEM_EN_FROM_GC %d /* names as tokens, the table's */" % (1 if from_gc else 0),
             "#define ITEM_EN_COUNT %d" % len(slots()),
             "#define ITEM_EN_RECORDS %d /* the items' and the names longer than 6 after them */"
             % (len(slots()) + len(tokens)),
             "#define ITEM_EN_SPECIAL_NAMES { %s} /* a special character's name: its string, its record */"
             % "".join("{ 0x%03X, %d }, " % sn for sn in special + [(0, 0)]),
             "#define ITEM_EN_RECORD_SIZE %d" % RECORD_SIZE,
             "#define ITEM_EN_NAME_MAX %d" % NAME_MAX,
             "#define ITEM_EN_TOKEN_MARK 0x%02X" % TOKEN_MARK,
             "#define ITEM_EN_TOKEN_DIGIT0 0x%02X" % TOKEN_DIGIT0,
             "#define ITEM_EN_TOKEN_BASE %d" % TOKEN_BASE,
             "#define ITEM_EN_TOKEN_LEN %d" % TOKEN_LEN,
             "#define ITEM_EN_FTR_BASE %d" % bases["ftr"],
             "#define ITEM_EN_FTR_COUNT %d" % next(n for k, n, _, _, _ in KINDS if k == "ftr"),
             "#define ITEM_EN_FTR_FACES %d" % FTR_FACES,
             "#define ITEM_EN_KIND_BASES { %s }" % ", ".join(str(bases[k]) for k, _, _, _, _ in KINDS[:16]),
             "#define ITEM_EN_KIND_COUNTS { %s }" % ", ".join(str(n) for _, n, _, _, _ in KINDS[:16])]
    return "\n".join(lines + names) + "\n"


def include_apart(text):
    """Whether an include of include()'s (its text) names NAMES_APART's characters by the en bank's strings."""
    return all("#define ITEM_EN_NAME_%04X 0x%03X " % (number, en) in text
               for number, (_, en) in NAMES_APART.items())


def names_apart(strings, gc_strings):
    """Whether the en string bank (strings) holds the names of NAMES_APART: each is the GameCube's string
    af_text.STRING_REMAP names (gc_strings: the GameCube's bank). A bank drafted before the remap is refused."""
    for number, (_, en) in sorted(NAMES_APART.items()):
        theirs = af_text.STRING_REMAP.get(en)
        if theirs is None:
            raise SystemExit("af_items: string %d (character %#x) is not in af_text.STRING_REMAP" % (en, number))
        if not gc_strings.get(theirs) or strings.get(en) != gc_strings[theirs]:
            raise SystemExit("af_items: the en string bank's %d is not the GameCube's string %d (character %#x's "
                             "name): make drafts" % (en, theirs, number))
    return True


def gc_special_names(szs, map_text):
    """[(a special character's number, its name's string)] of the GameCube's table."""
    layout = map_layout(map_text)
    at, size = gc_find(layout, *GC_SPECIAL)
    data = gc_rel_data(szs, layout, at + size)
    return [SPECIAL_ENTRY.unpack_from(data, at + i)[::2] for i in range(0, size, SPECIAL_ENTRY.size)]


def en_special_names(source, apart=True):
    """[(a special character's number, its name's string)] of the en tree's table (m_npc.c's l_sp_actor_name:
    a number, or the include's ITEM_EN_NAME_<number>, which stands for NAMES_APART's string)."""
    start = source.index("SpecialNpcData l_sp_actor_name[] = {")
    block = source[start:source.index("};", start)]
    rows = re.findall(r"\{\s*(0x[0-9A-Fa-f]+),\s*\w+,\s*(\w+),\s*\d+\s*\}", block)
    if len(rows) != block.count("{") - 1:               # (the table's own brace)
        raise SystemExit("af_items: %d of the en table's %d entries read: one is written another way" % (
            len(rows), block.count("{") - 1))
    out = []
    for number, string in rows:
        number = int(number, 16)
        if string.startswith("ITEM_EN_NAME_"):
            if int(string[len("ITEM_EN_NAME_"):], 16) != number or number not in NAMES_APART:
                raise SystemExit("af_items: %s names character %#x in the en table" % (string, number))
            out.append((number, NAMES_APART[number][1 if apart else 0]))
        else:
            out.append((number, int(string, 0)))
    return out


def special_name_problems(en, gc):
    """What is wrong with the en table against the GameCube's: every character of the en table must take the
    string the GameCube's gives it (through af_text.STRING_REMAP where the en bank keeps it at another number),
    a string the tokens know (SPECIAL_NAME_STRINGS), and NAMES_APART must be the en table's."""
    theirs, out = dict(gc), []
    for number, string in en:
        want = af_text.STRING_REMAP.get(string, string)
        if number not in theirs:
            out.append("%#x: not in the GameCube's table" % number)
        elif theirs[number] != want:
            out.append("%#x: the en table gives it string %d (the GameCube's %d), the GameCube's table string %d"
                       % (number, string, want, theirs[number]))
        if string not in SPECIAL_NAME_STRINGS:
            out.append("%#x: its string %d is not in SPECIAL_NAME_STRINGS" % (number, string))
    for number, (_, apart) in sorted(NAMES_APART.items()):
        if (number, apart) not in en:
            out.append("%#x: NAMES_APART gives it string %d, the en table does not" % (number, apart))
    return out


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
        opts, args = opts_of(argv[1:], ("--gc", "--own", "--font", "--gc-font", "--npc-names", "--names-seg",
                                        "--strings", "--string-tokens", "--gc-strings"))
        rom_path, out_bin, out_inc = args
        rom = open(rom_path, "rb").read()
        gc = read_names(opts["--gc"]) if opts.get("--gc") and os.path.exists(opts["--gc"]) else {}
        own = read_names(opts["--own"]) if opts.get("--own") and gc else {}
        font_rom = open(opts.get("--font", rom_path), "rb").read()
        gc_font = open(opts["--gc-font"], "rb").read() if opts.get("--gc-font") else None
        widths = af_font.widths(af_font.font(font_rom, gc_font)[1])
        mended = []
        data, counts = records(rom, gc, own, widths, mended)
        tokens = []
        if gc and opts.get("--names-seg") and os.path.exists(opts.get("--npc-names", "")):
            # the names longer than 6: their records after the items', each animal's token in its field
            seg_path = opts["--names-seg"]
            seg = bytearray(open(seg_path, "rb").read())
            capacity = (len(seg) - af_names.HEADER) // ANIMAL_NAME_LEN
            strings = af_text.load_bank(opts["--strings"]) if os.path.exists(opts.get("--strings", "")) else {}
            more, tokens = name_records(long_names(open(opts["--npc-names"], "rb").read(), capacity, strings),
                                        len(slots()), widths)
            data += more
            for (kind, n), _, tok in tokens:
                if kind == "animal":
                    seg[af_names.HEADER + ANIMAL_NAME_LEN * n:af_names.HEADER + ANIMAL_NAME_LEN * (n + 1)] = tok
            if bytes(seg) != open(seg_path, "rb").read():
                open(seg_path, "wb").write(bytes(seg))
            print("%s: the names longer than %d as tokens, %d animals' and %d special characters'" % (
                seg_path, ANIMAL_NAME_LEN, sum(1 for w, _, _ in tokens if w[0] == "animal"),
                sum(1 for w, _, _ in tokens if w[0] == "string")))
            # the catchphrases longer than 4: their records after the names', their tokens in the string bank
            more, phrases = name_records(long_catchphrases(af_names.segment(rom, DEFAULT_DATA), strings),
                                         len(slots()) + len(tokens), widths, CATCHPHRASE_LEN)
            data += more
            tokens += phrases
            print("%s: the catchphrases longer than %d as tokens, %d" % (
                opts.get("--string-tokens", "(no --string-tokens)"), CATCHPHRASE_LEN, len(phrases)))
            # the strings loaded into fewer bytes than their English takes: the same, after them
            taken = {number for (kind, number), _, _ in tokens if kind in ("string", "catchphrase")}
            for field, found in sorted(short_loaded(strings).items()):
                twice = sorted(number for (_, number), _ in found if number in taken)
                if twice:
                    raise SystemExit("af_items: strings %s are a name's or a catchphrase's and a word loaded into "
                                     "%d bytes" % (twice, field))
                more, loaded = name_records(found, len(slots()) + len(tokens), widths, field)
                data += more
                tokens += loaded
                print("%s: the strings longer than the %d bytes their code loads as tokens, %d" % (
                    opts.get("--string-tokens", "(no --string-tokens)"), field, len(loaded)))
        if opts.get("--string-tokens"):
            text = string_tokens([t for t in tokens if t[0][0] in ("catchphrase", "loaded")])
            path = opts["--string-tokens"]
            if not os.path.exists(path) or open(path).read() != text:
                open(path, "w").write(text)
        open(out_bin, "wb").write(data)
        apart = False
        if opts.get("--gc-strings") and os.path.exists(opts["--gc-strings"]) and os.path.exists(opts.get("--strings", "")):
            apart = names_apart(af_text.load_bank(opts["--strings"]), af_text.load_bank(opts["--gc-strings"]))
        open(out_inc, "w").write(include(bool(gc), tokens, apart))
        totals = [sum(c[k] for c in counts.values()) for k in range(3)]
        print("%s: %d names, %d the GameCube's, %d ours, %d the cartridge's%s" % (
            out_bin, len(slots()), totals[0], totals[1], totals[2],
            "" if gc else " (no disc: the en build keeps the cartridge's names)"))
        for kind, (g, o, c) in counts.items():
            if gc and c:
                print("  %s: %d with no English (the cartridge's)" % (kind, c))
        if mended:
            print("  %d of the GameCube's names given the article their first sound asks for: %s" % (
                len(mended), ", ".join("%s %d" % m for m in mended)))
        print("  the special characters named apart from the cartridge's table: %s" % (
            ", ".join("%#x by string %d" % (n, en) for n, (_, en) in sorted(NAMES_APART.items())) if apart
            else "none (no disc's strings: the cartridge's)"))
        return 0
    if argv[:1] == ["special-names"]:
        source, rel, map_path, inc = argv[1:5]
        if not include_apart(open(inc, encoding="utf-8").read()):
            raise SystemExit("af_items: special names: %s was made without the disc's strings (the en table is "
                             "the cartridge's there): make script, then make rom-en" % inc)
        en = en_special_names(open(source, encoding="utf-8").read())
        gc = gc_special_names(open(rel, "rb").read(), open(map_path, encoding="latin-1").read())
        problems = special_name_problems(en, gc)
        for problem in problems:
            print("af_items: special names: %s" % problem)
        print("%s: %d special characters, %s (%d through STRING_REMAP; the GameCube's table has %d)" % (
            source, len(en), "%d NOT as the GameCube's table names them" % len(problems) if problems
            else "each with the string the GameCube's table gives it",
            sum(1 for _, string in en if string in af_text.STRING_REMAP), len(gc)))
        return 1 if problems else 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
