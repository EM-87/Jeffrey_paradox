#!/usr/bin/env python3
"""The translation's message bank for the N64: drafted from the official
script, checked against what the cartridge's message system can take,
compiled to the files the ROM reads.

    tools/af_text.py draft  N64_DUMP GC_DUMP ALIGN.tsv OUT.txt [--overrides FILE ...]
    tools/af_text.py draft  N64_DUMP GC_DUMP - OUT.txt --bank choice|string [--overrides FILE ...]
    tools/af_text.py check  BANK.txt [--bank message] [--cols 32] [--lines 4] [--max-bytes N] [--reference N64_DUMP]
                            [--font ROM [--gc-font GC_FONT.bin]]
    tools/af_text.py compile BANK.txt TEXT.bin INDEX.bin [the same options] [--overrides FILE ...]
    tools/af_text.py compile-letters TEXT.bin INDEX.bin MAIL_HEADER.txt ... VMAIL_FOOTER.txt

--bank names which of the cartridge's banks the text is (tools/msgbank.py
N64_BANKS): message (11,752 entries), choice (460: the answers a choice
window offers, single lines), string (1,562: catchphrases, names of
animals, fish, insects..., single lines), or a letter bank: mail_header,
mail_body, mail_footer (544 each: the shop's and the game's letters, the
GameCube's super_data, mail_data and ps_data by number) and vmail_header,
vmail_a, vmail_b, vmail_c, vmail_footer (384 each: the villagers' letters,
the GameCube's superz_data, maila/b/c_data and psz_data). With `-` for
the GameCube dump the draft writes the sender's name, STR_FREE1, as our
own text (the Japanese footers add their town, FREE14). A letter is lines
of text and free strings, no terminator; its default --max-bytes is the
en build's (LETTER_CAPS, from the GameCube's longest entries). The count and the default
--max-bytes come from it; the choice and string banks keep the same
numbering on the GameCube, so their draft is simply the GameCube's entry
by number where it is not empty (no alignment file: pass `-`); so are the
letters', but for the event notices the GameCube moved (LETTER_REMAP: the
GameCube's letter is taken from its own number). --count
overrides the count: the en string bank goes on to 0x679 entries, through
the GameCube's day ordinals (0x64E) and month names (0x66D), which the en
build's date builders read.

A bank text is tools/msgbank.py's dump form: `## number`, the message with
its codes as `<NAME hex>`, `#:` notes. compile needs every number 0..11751
(the cartridge's count: the game addresses messages by number) and writes
the text file and the u32 table of ends, 16-byte padded, that the message
loader (`func_8009E388_jp`) reads through dmadata.

check and compile lay the --overrides files' messages over the bank by
number first, as draft does (make rom-en: the messages
tools/af_nafe_text.py takes from the AF Project's 2010 translation, which
never reach the repository or the draft, then ours, script/message/*.txt,
which the draft has as well). With --reference a message laid over is an
error unless its codes that steer the game are the cartridge's, the same
ones in the same order with the same arguments (steering(): all but how the
text shows, the strings the game always has at hand and the en build's
capital and article), and it prints no string the game fills for one
message (filled(): a free string, an item...) that the cartridge's does not.

compile-letters takes the eight letter banks in LETTER_BANKS' order and
writes them as one text file and one table of ends (the en build's two
letter segments, read by its m_handbill.c): bank b's entry n is entry
LETTER_FIRST[b] + n of the table, from the end before it (0 for the very
first) to its own, so the cartridge's way of finding an entry works on
the whole table.

The checks are the measured limits (reference/NOTES.md, "The message
system", "The GameCube script"):
  * every character has a byte in the N64 charset (an accented letter does
    not, until phase 5 gives it a glyph: it is an error);
  * every code is one the N64 has (0..0x60) with its argument size;
  * a message is at most --max-bytes (0x400: the loader refuses longer
    ones) and ends with MSGEND, MSGCONTINUE or MSGTIMEEND;
  * a page (up to MSGCLEAR) has at most --lines lines (4 on both games:
    the window draws that many from the page's start and never a fifth,
    measured), an error unless the cartridge's own message already had
    more (--reference, its dump: 14 messages do, their extra lines never
    shown); draft splits such pages with BTN and MSGCLEAR;
  * a line has at most --cols characters (a warning), or with --font (the
    user's dump, and --gc-font the GameCube's font from their disc, for
    the en build's widths: tools/af_font.py) at most the
    window's 192 pixels: the message bank is first laid out to that width
    (layout(): a page whose lines fit is left as it is; else a long line's
    last words go on to the next line, or the page's text is filled anew,
    or it is split into pages after the last sentence that fits, with
    <BTN> and <MSGCLEAR>, as draft splits a fifth line), and a line still
    wider (one word, or free strings) is a warning;
  * a letter's only codes are the free strings (STR_FREE0..19): the
    letters' expander has a handler for nothing else and stops on it;
  * a free string (STR_FREE0..19) the cartridge's own message does not
    use is a warning (--reference): the game fills free strings for the
    message it shows, and an unfilled one prints the slot's leftovers (a
    run of あ where the GameCube's text names a memory card slot or
    another villager: reference/NOTES.md, "The English bank in the ROM").

draft takes, for each N64 number, the GameCube text when af_align says it
is the same message (same, plain, edited: edited ones get a note), else
the Japanese with a note; the GameCube-only codes become what the N64 can
do: SETCURSORJUST, CLRCUSRORJUST and SPACE are dropped (counted),
STR_AMPM, CUTARTICLE and CAPTIALIZE are kept (compiled as codes 71 to 73,
which the en build reads as them), MALEFEMALECHK keeps its first alternative,
SELNOBCLOSE becomes the N64's SELNOB, anything else is kept and reported
for the check to refuse. Characters the N64 charset lacks are given
their plain ASCII (é -> e, the GameCube's marks their nearest; each
replacement is counted). An official message takes the cartridge's codes
that steer the game (mend: the GameCube's are its own game's), or where
it cannot, the draft has the cartridge's Japanese with a `#: TODO
translate` note; MESSAGE_REMAP takes five from a twin of the same
Japanese. An official message naming free strings the Japanese does not
gets a `#: TODO adapt` note. --overrides files (our own translations, in
the repository) replace messages by number.
"""

import collections
import os
import re
import struct
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from msgbank import COMMANDS, N64_BANKS, N64_BYTES, NAMES, Bank, encode, parse, render  # noqa: E402

N64_COUNT = 0x2DE8                       # the message bank; main() sets it from --bank
N64_CODE_MAX = 0x60
TERMINATORS = {NAMES["MSGEND"], NAMES["MSGCONTINUE"], NAMES["MSGTIMEEND"]}
PAGE_BREAKS = {NAMES["BTN"], NAMES["MSGCLEAR"]}
DROP = {NAMES[n] for n in ("SETCURSORJUST", "CLRCUSRORJUST", "SPACE")}
# GameCube codes the translation's N64 code carries under another number: the
# bank text keeps the GameCube's name, compile writes the N64's (changes.patch,
# src/code/m_msg_main.c: codes 71 to 73, LUCK_6 to LUCK_8 on the cartridge and
# in neither bank, are STR_AMPM, CUTARTICLE and CAPTIALIZE in the en build; the
# letters' expander in m_handbill.c takes the last two as well: an item's name
# goes in with its article unless cut, a string with a capital if asked).
N64_CARRIED = {NAMES["STR_AMPM"]: NAMES["LUCK_6"], NAMES["CUTARTICLE"]: NAMES["LUCK_7"],
               NAMES["CAPTIALIZE"]: NAMES["LUCK_8"]}
LETTER_CODES = {NAMES["LUCK_7"], NAMES["LUCK_8"]}          # a letter's codes besides the free strings
# The codes that steer the game are all but how the text shows (pages, pauses,
# colours, scales, voice: COSMETIC), the strings the game always has at hand
# (the names, the catchphrase, the date and time, the town: GLOBAL) and the en
# build's capital and article for the next string (SHAPING). A string the game
# fills for one message (FILLED: a free string, an item, STR_DETERMINATION,
# STR_RNDNUM, STR_MAIL) is filled only for the messages that print it. A message
# laid over the bank (--overrides: the 2010 patch's, tools/af_nafe_text.py, and
# ours, script/message/) keeps the cartridge's codes that steer the game and
# prints none of those strings the cartridge's does not.
COSMETIC = {NAMES[n] for n in ("PAUSE", "BTN", "BTN2", "MSGCLEAR", "TEXTCOLOR", "COLORCHARS", "LINEOFS", "LINETYPE",
                                "CHARSCALE", "LINESCALE", "VOICETRUE", "VOICEFALSE")}
GLOBAL = {NAMES[n] for n in ("STR_PLAYERNAME", "STR_TALKNAME", "STR_TAIL", "STR_YEAR", "STR_MONTH", "STR_WEEK",
                              "STR_DAY", "STR_HOUR", "STR_MIN", "STR_SEC", "STR_AMPM", "STR_COUNTRYNAME")}
SHAPING = {NAMES["CAPTIALIZE"], NAMES["CUTARTICLE"]}
FILLED = ({NAMES[n] for n in NAMES if n.startswith("STR_FREE") or n.startswith("STR_ITEM")} |
          {NAMES[n] for n in ("STR_DETERMINATION", "STR_RNDNUM", "STR_MAIL")})
NOT_STEERING = COSMETIC | GLOBAL | SHAPING | FILLED
# The en build's letter banks: room for the GameCube's longest entry (header
# 23 bytes, body 177, footer 30, the villagers' parts 20/62/90/32), free strings
# expanded later by the loader.
LETTER_CAPS = {"mail_header": 32, "mail_body": 192, "mail_footer": 32, "vmail_header": 32,
               "vmail_a": 96, "vmail_b": 96, "vmail_c": 48, "vmail_footer": 32}


LETTER_BANKS = ("mail_header", "mail_body", "mail_footer",
                "vmail_header", "vmail_a", "vmail_b", "vmail_c", "vmail_footer")


def letter_count(bank):
    return 544 if bank.startswith("mail") else 384


# The letters the GameCube keeps at another number, {bank: {cartridge's number: the GameCube's}}: its event
# notices (mail_body 420-462, which the bulletin board posts), where it dropped events of the cartridge's
# and added its own, moving the rest (reference/NOTES.md, "The letter banks, both games, by number").
# Numbers only; the draft takes the GameCube's letter from there.
LETTER_REMAP = {"mail_body": {422: 423, 423: 424, 427: 429, 428: 426, 430: 428, 432: 431, 433: 432, 434: 433,
                              438: 439, 440: 437, 446: 445, 447: 446, 448: 447, 449: 448, 450: 449, 451: 450,
                              452: 451}}
# Messages whose Japanese, codes and all, is another message's (a villager meeting the player late at night,
# 11198-11211 and 11724-11737 the same set), where the GameCube wrote the first for a meeting of its own (a
# villager moved in from another town, free string 10, which the cartridge does not fill): the GameCube's
# text of the other number. Numbers only.
MESSAGE_REMAP = {11198: 11724, 11203: 11729, 11205: 11731, 11207: 11733, 11209: 11735}


LETTER_FIRST = {}
for _bank in LETTER_BANKS:
    LETTER_FIRST[_bank] = sum(letter_count(b) for b in LETTER_BANKS[:LETTER_BANKS.index(_bank)])


def carried(tokens):
    return [("c", N64_CARRIED[t[1]], t[2]) if t[0] == "c" and t[1] in N64_CARRIED else t for t in tokens]


def steering(tokens):
    """The codes that steer the game, with their arguments, in order."""
    return [(t[1], t[2]) for t in tokens if t[0] == "c" and t[1] not in NOT_STEERING]


# The official text's codes that steer the game (reference/NOTES.md, "The official text's codes"). The
# GameCube's game reads its own: talk orders moved to the beats of its English, given in other slots or as
# quest orders (DEMONPCQST), a choice of its own (an answer past the cartridge's 460 choices, a next message
# its bank has), a luck or sound code more or less. The en build runs the cartridge's code, so the draft
# gives each official message the cartridge's codes (mend), or the cartridge's Japanese where it cannot.
SETSELSTR = {NAMES["SETSELSTR2"], NAMES["SETSELSTR3"], NAMES["SETSELSTR4"]}
RANDOM_NEXT = {NAMES["SETNEXTMSGRND2"], NAMES["SETNEXTMSGRND3"], NAMES["SETNEXTMSGRND4"]}


SOUNDS = {NAMES["SNDTRGSYS"], NAMES["SNDNOPAGE"]}


def _text_end(tokens):
    """Where the codes after the message's last text begin (a full stop after them, which the window never
    reaches, is not text)."""
    i = len(tokens)
    while i and not (tokens[i - 1][0] == "t" and tokens[i - 1][1].strip("\n 。")):
        i -= 1
    return i


def _sounds_aligned(theirs, end, codes, plain):
    """theirs[:end] with its codes (plain ones) made codes where the two differ only by sounds the one has
    and the other not (a sound trigger more or less in a run of them): the other codes matched in order, a
    sound of theirs alone dropped, one of the cartridge's alone put after the code before it (or before the
    one after it). None when other codes differ."""
    mine = [i for i in range(end) if plain(theirs[i])]
    a, b = [theirs[i][1:] for i in mine], [t[1:] for t in codes]
    # an order-preserving match of equal codes (longest common subsequence)
    lcs = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) - 1, -1, -1):
        for j in range(len(b) - 1, -1, -1):
            lcs[i][j] = lcs[i + 1][j + 1] + 1 if a[i] == b[j] else max(lcs[i + 1][j], lcs[i][j + 1])
    pairs, i, j = [], 0, 0
    while i < len(a) and j < len(b):
        if a[i] == b[j]:
            pairs.append((i, j))
            i, j = i + 1, j + 1
        elif lcs[i + 1][j] >= lcs[i][j + 1]:
            i += 1
        else:
            j += 1
    matched_a, matched_b = set(p[0] for p in pairs), set(p[1] for p in pairs)
    if any(a[k][0] not in SOUNDS for k in range(len(a)) if k not in matched_a) or \
            any(b[k][0] not in SOUNDS for k in range(len(b)) if k not in matched_b):
        return None
    after = {}                                   # the cartridge's lone sounds, by the token they follow
    for k in range(len(b)):
        if k in matched_b:
            continue
        before = [p for p in pairs if p[1] < k]
        at = mine[before[-1][0]] + 1 if before else (mine[next(p for p in pairs if p[1] > k)[0]] if pairs else end)
        after.setdefault(at, []).append(("c",) + b[k])
    out = []
    drop = set(mine[k] for k in range(len(a)) if k not in matched_a)
    for i in range(end + 1):
        out += after.get(i, [])
        if i < end and i not in drop:
            out.append(theirs[i])
    return out


# What the cartridge's codes after its last text may still print: dropped from them where the English takes
# them (_bare_tail)
PRINTED_TAIL = GLOBAL | FILLED | SHAPING


def _bare_tail(tokens):
    """The cartridge's codes after its last text as the English takes them, without what they still print.
    _text_end counts no full stop, newline or space as text, so a tail can end the cartridge's sentence: a
    pause, the catchphrase and a full stop after its last words (3884, 10089), which the English, its own
    sentence said, must not print again. The full stops and the strings go; the pauses, newlines, page breaks
    and the codes that steer the game stay."""
    out = []
    for t in tokens:
        if t[0] == "t":
            kept = t[1].replace("。", "")
            if kept:
                out.append(("t", kept))
        elif t[1] not in PRINTED_TAIL:
            out.append(t)
    return out


def _flow(codes):
    """What the codes after a message's text do: open a choice, force a next message, pick one at random,
    and how the message ends."""
    names = set(t[1] for t in codes)
    return (NAMES["OPENCHOICE"] in names, NAMES["SETFORCEMSG"] in names, bool(names & RANDOM_NEXT),
            next((t[1] for t in reversed(codes) if t[1] in TERMINATORS), None))


def mend(cart, theirs):
    """(theirs with the cartridge's codes that steer the game, how) or (None, why not): in its text the
    cartridge's arguments where the codes are the same ones in the same order (a talk order aside; a sound
    trigger more or less in a run of them, _sounds_aligned); after its text, where the two do the same
    (both or neither open a choice, force a next message, pick one at random; the same ending), the
    cartridge's codes, its choice's strings among them (the GameCube's choices are its own: a quiz's
    answers for its own events); then the talking animal's orders in every slot dropped and the
    cartridge's put back at the beats of the English (af_nafe_text.recover with any_order)."""
    import af_nafe_text                                  # recover's place-finding; it imports this module
    if steering(theirs) == steering(cart):
        return theirs, ""
    is_order = af_nafe_text.any_order

    def plain(t):
        return t[0] == "c" and t[1] not in NOT_STEERING and not is_order(t)
    ct, tt = _text_end(cart), _text_end(theirs)
    c_mid, t_mid = [t for t in cart[:ct] if plain(t)], [t for t in theirs[:tt] if plain(t)]
    how = []
    if [t[1] for t in c_mid] != [t[1] for t in t_mid]:
        head = _sounds_aligned(theirs, tt, c_mid, plain)
        if head is None:
            return None, "codes in its text"
        theirs = head + list(theirs[tt:])
        tt = len(head)
        t_mid = [t for t in theirs[:tt] if plain(t)]
        how.append("sounds")
    out = list(theirs)
    for i, t in zip([i for i in range(tt) if plain(theirs[i])], c_mid):
        out[i] = t
    if c_mid != t_mid:
        how.append("arguments")
    c_tail, t_tail = [t for t in cart[ct:] if plain(t)], [t for t in theirs[tt:] if plain(t)]
    if c_tail != t_tail:
        if _flow(c_tail) != _flow(t_tail):
            return None, "what it does after its text"
        out = out[:tt] + _bare_tail(cart[ct:])
        how.append("codes after the text")
    if steering(out) != steering(cart):
        out = af_nafe_text.recover(cart, out, is_order)
        if out is None or steering(out) != steering(cart):
            return None, "talk orders"
        how.append("talk orders")
    return out, ", ".join(how)


def filled(tokens):
    """The strings the game fills for one message that the message prints."""
    return set(t[1] for t in tokens if t[0] == "c" and t[1] in FILLED)


def laid_problems(tokens, original):
    """[(level, text)] for a message laid over the bank, against the cartridge's."""
    problems = []
    if steering(tokens) != steering(original):
        problems.append(("error", "its codes that steer the game are not the cartridge's: %s, the cartridge's %s" % (
            render([("c",) + s for s in steering(tokens)]), render([("c",) + s for s in steering(original)]))))
    unfilled = filled(tokens) - filled(original)
    if unfilled:
        problems.append(("error", "prints %s, which the game does not fill for this message"
                         % ", ".join(sorted(COMMANDS[c] for c in unfilled))))
    return problems


FALLBACK = {"…": "...", "“": '"', "”": '"', "‘": "'", "’": "'", "–": "-", "—": "-",
            "ー": "-", "¡": "!", "¿": "?", "ß": "ss", "Æ": "AE", "æ": "ae", "Ø": "O", "ø": "o", "Ð": "D", "ð": "d",
            "Þ": "Th", "þ": "th", "·": ".", "•": "*", "×": "x", "°": "o",
            "~": "～", "😃": "☺", "😄": "☺",                # the N64 font's wave dash and face,
            "🌢": "💧", "💢": "✚",                          # its drop and its anger mark (the same letters have
            "☃": "～",                                     # them there), a sigh's wave for the GameCube's mark,
            ";": ",", "/": "-"}                           # and for the semicolon and slash it lacks


def ascii_fallback(text, counts=None):
    out = []
    for c in text:
        if c in N64_BYTES or c == "\n":
            out.append(c)
            continue
        if c in FALLBACK:
            rep = FALLBACK[c]
        else:
            base = "".join(ch for ch in unicodedata.normalize("NFKD", c) if not unicodedata.combining(ch))
            rep = base if base and all(ch in N64_BYTES for ch in base) else "?"
        if counts is not None:
            counts["%s -> %s" % (c, rep)] += 1
        out.append(rep)
    return "".join(out)


STR_CODES = {NAMES[n] for n in NAMES if n.startswith("STR_")}


def split_pages(tokens, lines=4, counts=None):
    """Insert <BTN>, newline, <MSGCLEAR> before the line that would be the
    (lines+1)th of a page: the window draws `lines` lines from the page's
    start and a further one is never shown (measured, NOTES)."""
    items = _items(tokens)
    breaks = PAGE_BREAKS | TERMINATORS

    def visible_ahead(i):
        for it in items[i:]:
            if it[0] == "ch":
                if it[1] not in " \n":
                    return True
            elif it[1] in breaks:
                return False
            elif it[1] in STR_CODES:
                return True
        return False

    out, line = [], 0
    for i, it in enumerate(items):
        if it[0] == "ch" and it[1] == "\n":
            if line == lines - 1 and visible_ahead(i + 1):
                out += [("c", NAMES["BTN"], b""), ("ch", "\n"), ("c", NAMES["MSGCLEAR"], b"")]
                line = 0
                if counts is not None:
                    counts["pages split"] += 1
                continue
            line += 1
        elif it[0] == "c" and it[1] == NAMES["MSGCLEAR"]:
            line = 0
        out.append(it)
    return _merge(out)


def _items(tokens):
    """A message as characters ("ch", c) and codes."""
    items = []
    for t in tokens:
        if t[0] == "t":
            items += [("ch", c) for c in t[1]]
        else:
            items.append(t)
    return items


def _merge(items):
    """_items' inverse."""
    merged = []
    for it in items:
        if it[0] == "ch":
            if merged and merged[-1][0] == "t":
                merged[-1] = ("t", merged[-1][1] + it[1])
            else:
                merged.append(("t", it[1]))
        else:
            merged.append(it)
    return merged


# -- lines in pixels -----------------------------------------------------------
# The message window's text is 192 pixels wide: 16 of the cartridge's 12-pixel
# characters, from centerX - 96 (m_msg_main.c), the page's continue mark at
# 257 (func_800A2C4C_jp) just past it. The en build draws each character at
# its own width (tools/af_font.py, "The font in the en build" in NOTES).
WINDOW_WIDTH = 192
# What a code prints is filled at run time, so layout assumes a length, in
# letters of the font's average lowercase width: the player's and the
# town's names 6, their fields' size (PLAYER_NAME_LEN); the speaker's name
# 9 and the catchphrase 11, the widest the en build puts in since the names
# longer than 6 and the catchphrases longer than 4 are tokens (56 and 70
# pixels at the GameCube's letters, 6.5 pixels on average: NOTES, "Names
# longer than 6", "Catchphrases longer than 4"); the date's and time's
# parts as the en build writes them (m_string.c: "Wednesday", "September",
# "14th", "12", "p.m."); any other (free strings, items: numbers, names,
# things) 8, TODO(verify) against what the game fills.
CODE_LETTERS = {"STR_PLAYERNAME": 6, "STR_TALKNAME": 9, "STR_COUNTRYNAME": 6, "STR_ISLANDNAME": 6,
                "STR_TAIL": 11, "STR_YEAR": 4, "STR_MONTH": 9, "STR_WEEK": 9, "STR_DAY": 4,
                "STR_HOUR": 2, "STR_MIN": 2, "STR_SEC": 2, "STR_AMPM": 4, "STR_RNDNUM": 3}
CODE_LETTERS_DEFAULT = 8
SPACE = ("ch", " ")
NEWLINE = ("ch", "\n")
BTN = ("c", NAMES["BTN"], b"")


class Measure:
    """Pixel widths of characters and codes, from the font's widths (256)."""

    def __init__(self, widths):
        self.widths = widths
        self.letter = sum(widths[N64_BYTES[c]] for c in "abcdefghijklmnopqrstuvwxyz") / 26

    def item(self, it):
        if it[0] == "ch":
            return self.widths[N64_BYTES.get(it[1], 0)]
        name = COMMANDS[it[1]] if it[1] < len(COMMANDS) else ""
        if name.startswith("STR_"):
            return round(CODE_LETTERS.get(name, CODE_LETTERS_DEFAULT) * self.letter)
        return 0

    def __call__(self, items):
        return sum(self.item(it) for it in items)


def _visible(line):
    return any((it[0] == "ch" and it[1] != " ") or (it[0] == "c" and it[1] in STR_CODES) for it in line)


def _latin(line):
    return any(it[0] == "ch" and it[1].isascii() and it[1].isalpha() for it in line)


def _words(line):
    words, word = [], []
    for it in line:
        if it == SPACE:
            words.append(word)
            word = []
        else:
            word.append(it)
    words.append(word)
    return words


def _join(words):
    line = []
    for k, word in enumerate(words):
        if k:
            line.append(SPACE)
        line += word
    return line


def _fill(words, measure, limit):
    """Words as lines, each as full as fits (a word wider than limit alone);
    returns the number of words of each line."""
    out, line = [], []
    for word in words:
        if line and measure(_join(line + [word])) > limit and _visible(_join(line)):
            out.append(len(line))
            line = [word]
        else:
            line.append(word)
    out.append(len(line))
    return out


def _lines(words, sizes):
    out, k = [], 0
    for n in sizes:
        out.append(_join(words[k:k + n]))
        k += n
    return out


def _cascade(lines, measure, limit):
    """Each line wider than limit gives its last words to the next line of
    text (or a new line when none follows), which may then give on in turn;
    the other lines stay as they are."""
    lines = [list(line) for line in lines]
    i = 0
    while i < len(lines):
        if measure(lines[i]) > limit:
            words = _words(lines[i])
            k = len(words) - 1
            while k > 0 and measure(_join(words[:k])) > limit:
                k -= 1
            if k > 0:
                lines[i], rest = _join(words[:k]), _join(words[k:])
                if i + 1 < len(lines) and _visible(lines[i + 1]):
                    lines[i + 1] = rest + [SPACE] + lines[i + 1]
                else:
                    lines.insert(i + 1, rest)
        i += 1
    return lines


def _sentence_end(word):
    text = "".join(it[1] for it in word if it[0] == "ch").rstrip("\"')")
    return text[-1:] in (".", "!", "?")


def _split(words, measure, limit, max_lines):
    """Pages of at most max_lines lines, broken after the last sentence that
    fits on each; at a line when no sentence end does, not leaving one line
    of one or two words alone on the last page."""
    sizes = _fill(words, measure, limit)
    if len(sizes) <= max_lines:
        return [_lines(words, sizes)]
    best = None
    for k in range(len(words) - 1):
        if _sentence_end(words[k]) and len(_fill(words[:k + 1], measure, limit)) <= max_lines:
            best = k + 1
    if best is None:
        keep = max_lines
        if len(sizes) == max_lines + 1 and sizes[-1] <= 2:
            keep = max_lines - 1
        best = sum(sizes[:keep])
    return [_lines(words[:best], _fill(words[:best], measure, limit))] + _split(words[best:], measure, limit, max_lines)


def _layout_page(lines, measure, limit, max_lines, counts):
    """The pages one page's lines (MSGCLEAR to MSGCLEAR) become: itself when
    every line fits; else its long lines' last words moved on; else the page
    filled anew (its lines of text joined); else split at sentence ends."""
    tail = []
    while len(lines) > 1 and not _visible(lines[-1]):
        tail.insert(0, lines.pop())
    if all(measure(line) <= limit for line in lines) or not any(_latin(line) for line in lines):
        return [lines + tail]                   # it fits, or it is Japanese: the cartridge's own lines
    moved = _cascade(lines, measure, limit)
    if len(moved) <= max_lines:
        counts["pages with lines moved on"] += 1
        return [moved + tail]
    words = []
    for k, line in enumerate(lines):
        words += _words(line)
    words = [w for w in words if w] or [[]]
    pages = _split(words, measure, limit, max_lines)
    counts["pages filled anew" if len(pages) == 1 else "pages split"] += 1
    pages[-1] = pages[-1] + tail
    return pages


def layout(tokens, widths, limit=WINDOW_WIDTH, max_lines=4, counts=None):
    """The message with every line at most limit pixels wide where its words
    allow, its pages at most max_lines lines (a split page goes on after
    <BTN>, a newline and <MSGCLEAR>, as the cartridge's messages do).
    Pages whose lines all fit are left exactly as they are."""
    counts = collections.Counter() if counts is None else counts
    measure = Measure(widths)
    out, page = [], []

    def flush():
        lines = [[]]
        for it in page:
            if it == NEWLINE:
                lines.append([])
            else:
                lines[-1].append(it)
        for k, pg in enumerate(_layout_page(lines, measure, limit, max_lines, counts)):
            if k:
                # the page waits for A once: a <BTN> the text had where it is cut serves
                if pg[0][:1] == [BTN]:
                    pg[0] = pg[0][1:]
                if out[-1:] != [BTN]:
                    out.append(BTN)
                out.extend([NEWLINE, ("c", NAMES["MSGCLEAR"], b"")])
            for j, line in enumerate(pg):
                if j:
                    out.append(NEWLINE)
                out.extend(line)

    for it in _items(tokens):
        if it[0] == "c" and it[1] == NAMES["MSGCLEAR"]:
            flush()
            out.append(it)
            page = []
        else:
            page.append(it)
    flush()
    return _merge(out)


def adapt(tokens, counts):
    """GameCube tokens as N64 tokens."""
    out = []
    for t in tokens:
        if t[0] == "t":
            out.append(("t", ascii_fallback(t[1], counts)))
        elif t[1] in DROP:
            counts["dropped " + ["%d" % t[1], *[n for n in NAMES if NAMES[n] == t[1]]][-1]] += 1
        elif t[1] == NAMES["MALEFEMALECHK"]:
            counts["MALEFEMALECHK kept first"] += 1   # TODO(verify): its 4 argument bytes' meaning
        elif t[1] == NAMES["SELNOBCLOSE"]:
            out.append(("c", NAMES["SELNOB"], b""))    # the N64's choice without B; "close" is the GameCube's variant
            counts["SELNOBCLOSE as SELNOB"] += 1
        else:
            out.append(t)
    return out


def page_lines(tokens):
    """the most lines any page of the message shows"""
    most, page = 0, [0]

    def shown():
        n = len(page)
        while n > 1 and page[n - 1] == 0:
            n -= 1
        return n

    for t in tokens:
        if t[0] == "t":
            for k, part in enumerate(t[1].split("\n")):
                if k:
                    page.append(0)
                page[-1] += len(part)
        elif t[1] in PAGE_BREAKS or t[1] in TERMINATORS:
            most = max(most, shown())
            if t[1] == NAMES["MSGCLEAR"]:
                page = [0]
    return max(most, shown())


def free_strings(tokens):
    """The free strings (STR_FREE0..19) a message prints."""
    return set(re.findall(r"<(STR_FREE\d+)>", render(tokens)))


def wide_lines(tokens, measure, limit=WINDOW_WIDTH):
    """The widths of the message's English lines (any Latin letter) wider
    than limit pixels; Japanese ones are the cartridge's own."""
    out, line = [], []
    for it in _items(tokens) + [NEWLINE]:
        if it == NEWLINE or (it[0] == "c" and (it[1] in PAGE_BREAKS or it[1] in TERMINATORS)):
            if measure(line) > limit and _latin(line):
                out.append(measure(line))
            line = []
        else:
            line.append(it)
    return out


def check_message(number, tokens, cols, lines, max_bytes, allowed_lines=0, single=False, original=None, letter=False,
                  measure=None):
    """[(level, text)] for one message; allowed_lines: what its original showed;
    single: a choice or free string (no codes, no terminator, one line);
    original: the cartridge's message, for the free strings the game fills;
    letter: a letter's part (lines and free strings, no terminator);
    measure: the font's widths (Measure): lines are checked in pixels
    against the window's, not in characters against cols."""
    problems = []
    if original is not None:
        unfilled = free_strings(tokens) - free_strings(original)
        if unfilled:
            problems.append(("warning", "prints %s, which the cartridge's message does not: the game may not fill it"
                             % ", ".join(sorted(unfilled))))
    tokens = carried(tokens)
    try:
        raw = encode(tokens)
    except ValueError as e:
        return [("error", "%s" % e)]
    if len(raw) > max_bytes:
        problems.append(("error", "%d bytes, the loader takes %d" % (len(raw), max_bytes)))
    codes = [t for t in tokens if t[0] == "c"]
    if letter:
        for t in codes:
            if not COMMANDS[t[1]].startswith("STR_FREE") and t[1] not in LETTER_CODES:
                # the letters' expander (func_80093478_jp) has a handler for the twenty free strings
                # only (D_80107020_jp; the en build's for the article's two as well) and does not move
                # past any other code: the game would hang
                problems.append(("error", "code %s in a letter (only the free strings and the article's)"
                                 % COMMANDS[t[1]]))
        return problems
    if single:
        if codes or "\n" in "".join(t[1] for t in tokens if t[0] == "t"):
            problems.append(("error", "a choice or string is one line of text, no codes"))
        return problems
    for t in codes:
        if t[1] > N64_CODE_MAX:
            problems.append(("error", "code %d is the GameCube's, not the N64's" % t[1]))
    if not codes or codes[-1][1] not in TERMINATORS or tokens[-1] != codes[-1]:
        problems.append(("error", "does not end with MSGEND, MSGCONTINUE or MSGTIMEEND"))
    page = [0]                                  # the lines of the page so far, as lengths

    def shown():
        """lines the page shows: a newline right before a break opens no line"""
        n = len(page)
        while n > 1 and page[n - 1] == 0:
            n -= 1
        return n

    for t in tokens:
        if t[0] == "t":
            for k, part in enumerate(t[1].split("\n")):
                if k:
                    page.append(0)
                page[-1] += len(part)
                if page[-1] > cols and measure is None:
                    problems.append(("warning", "a line of %d characters (--cols %d)" % (page[-1], cols)))
        elif t[1] in PAGE_BREAKS or t[1] in TERMINATORS:
            if shown() > lines:
                if shown() <= allowed_lines:
                    problems.append(("warning", "a page of %d lines (--lines %d), as the original" % (shown(), lines)))
                else:
                    problems.append(("error", "a page of %d lines: the window draws %d" % (shown(), lines)))
            if t[1] == NAMES["MSGCLEAR"]:
                page = [0]
    if measure is not None:
        for width in wide_lines(tokens, measure):
            problems.append(("warning", "a line of %d pixels (the window's %d), one word or a free string too wide"
                             % (width, WINDOW_WIDTH)))
    return problems


def load_bank(path):
    return Bank.parse_dump(open(path, encoding="utf-8").read())


JAPANESE_TEXT = re.compile(r"[\u3001-\u30fb\u30fd-\u30ff\u4e00-\u9fff]")   # kana, kanji, their marks; not ー


def japanese_text(messages):
    """The numbers of the messages whose text prints a Japanese character (the long vowel mark and the wave
    dash, which the English uses as a dash and a sigh, aside). With the 2010 patch's English and ours laid
    over the draft, none should; without the patch, those only it translates keep the cartridge's."""
    return sorted(n for n, tokens in messages.items()
                  if any(t[0] == "t" and JAPANESE_TEXT.search(t[1].replace("～", "")) for t in tokens))


def run_checks(messages, cols, lines, max_bytes, reference=None, single=False, letter=False, measure=None, laid=()):
    """laid: the numbers of the messages laid over the bank (--overrides), checked against reference."""
    errors = warnings = 0
    for number in sorted(messages):
        allowed = page_lines(reference[number]) if reference and number in reference else 0
        original = reference.get(number) if reference and (letter or not single) else None
        problems = check_message(number, messages[number], cols, lines, max_bytes, allowed, single and not letter,
                                 original, letter, measure)
        if number in laid and original is not None:
            problems += laid_problems(messages[number], original)
        for level, text in problems:
            print("%s %d: %s" % (level, number, text))
            if level == "error":
                errors += 1
            else:
                warnings += 1
    missing = [n for n in range(N64_COUNT) if n not in messages]
    if missing:
        print("error: %d numbers missing (first %s)" % (len(missing), missing[:5]))
        errors += 1
    extra = [n for n in messages if n >= N64_COUNT]
    if extra:
        print("error: numbers past %d: %s" % (N64_COUNT - 1, extra[:5]))
        errors += 1
    print("af_text: %d messages, %d errors, %d warnings" % (len(messages), errors, warnings))
    return errors


def compile_bank(messages, text_path, index_path):
    text, ends = bytearray(), []
    for n in range(N64_COUNT):
        text += encode(carried(messages[n]))
        ends.append(len(text))
    text += bytes(-len(text) % 16)
    open(text_path, "wb").write(text)
    # The readers take an even entry's ends 16 bytes at a time from two entries before it (m_string.c's
    # mString_Get_StringDataAddressAndSize, m_msg_main.c's func_8009E388_jp, m_choice_main.c, m_handbill.c): the
    # last entry of an odd count reads one end past the table, and the DMA manager stops the game on a read that
    # crosses its segment's end (m_std_dma.c, "Segment Alignment Error"). The string bank's 0x679 did: every
    # December month name hung the game. Padded as the letters' table is.
    index = struct.pack(">%dI" % len(ends), *ends)
    index += bytes(-len(index) % 16)
    open(index_path, "wb").write(index)
    print("%s: %#x bytes; %s: %d ends" % (text_path, len(text), index_path, len(ends)))


def compile_letters(paths, text_path, index_path, cols=32, lines=4):
    """The eight letter banks (LETTER_BANKS order) as one text and one table
    of ends; None if a bank does not pass its checks."""
    global N64_COUNT
    text, ends, errors = bytearray(), [], 0
    for bank, path in zip(LETTER_BANKS, paths):
        N64_COUNT = letter_count(bank)
        messages = load_bank(path)
        print("%s (%s):" % (bank, os.path.basename(path)))
        errors += run_checks(messages, cols, lines, LETTER_CAPS[bank], None, True, True)
        for n in range(N64_COUNT):
            text += encode(carried(messages.get(n, [])))
            ends.append(len(text))
    if errors:
        print("af_text: letters not compiled")
        return None
    text += bytes(-len(text) % 16)
    index = struct.pack(">%dI" % len(ends), *ends)
    index += bytes(-len(index) % 16)
    open(text_path, "wb").write(text)
    open(index_path, "wb").write(index)
    print("%s: %#x bytes; %s: %d ends" % (text_path, len(text), index_path, len(ends)))
    return text, index


def draft(n64_path, gc_path, align_path, out_path, overrides, remap=None):
    """remap: {number: the GameCube's number} (LETTER_REMAP for a bank drafted by number, MESSAGE_REMAP);
    an official message takes the cartridge's codes that steer the game (mend), or where it cannot, the
    message is the cartridge's Japanese, to translate."""
    remap = remap or {}
    n64 = load_bank(n64_path)
    if gc_path == "-":                      # no GameCube bank (the villagers' footers): the sender's name, ours
        out, sender = [], ("c", NAMES["STR_FREE1"], b"")
        for n in range(N64_COUNT):
            codes = [t for t in n64.get(n, []) if t[0] == "c"]
            tokens = [sender] if sender in codes else codes
            out.append("## %d\n#: ours: the sender's name, as the GameCube signs villagers' letters\n%s\n\n"
                       % (n, render(tokens)))
        open(out_path, "w", encoding="utf-8").write("".join(out))
        print("%s: %d messages, the sender's name" % (out_path, N64_COUNT))
        return
    gc = load_bank(gc_path)
    classes = {}
    if align_path == "-":                   # a single-line bank: the same numbering, entry by entry
        for n in range(N64_COUNT):
            classes[n] = "same" if gc.get(remap.get(n, n)) else "removed"
    else:
        for line in open(align_path, encoding="utf-8").read().splitlines()[1:]:
            number, cls = line.split("\t")[:2]
            classes[int(number)] = cls
        for n, theirs in remap.items():     # the other number's Japanese is this one's: its class
            classes[n] = classes.get(theirs, "different")
    ours = {}
    for path in overrides:
        ours.update(load_bank(path))
    counts, out = collections.Counter(), []
    for n in range(N64_COUNT):
        cls = classes.get(n, "different")
        note = ""
        if n in ours:
            tokens = ours[n]
            counts["ours"] += 1
        elif n not in n64:                   # past the cartridge's count (--count): the GameCube's, or nothing
            tokens = adapt(gc[n], counts) if gc.get(n) else []
            counts["beyond the cartridge"] += 1
        elif cls in ("same", "plain", "edited") and remap.get(n, n) in gc:
            tokens, why = mend(n64[n], adapt(gc[remap.get(n, n)], counts))
            if tokens is None:
                tokens = n64[n]
                counts["japanese, the GameCube's steers otherwise"] += 1
                note = "#: TODO translate (the GameCube's text steers the game otherwise: %s)\n" % why
            else:
                counts["official " + cls] += 1
                if n in remap:
                    note = "#: the GameCube's %s %d (%s)\n" % ("letter" if align_path == "-" else "message",
                                                               remap[n], "LETTER_REMAP" if align_path == "-"
                                                               else "MESSAGE_REMAP")
                    counts["official, from another number"] += 1
                if cls == "edited":
                    note += "#: the GameCube edited this message; the Japanese had: %s\n" % \
                        render(n64[n]).replace("\n", "¶")
                if why:
                    note += "#: the cartridge's codes that steer the game put back: %s\n" % why
                    counts["official, the cartridge's codes put back"] += 1
                unfilled = free_strings(tokens) - free_strings(n64[n])
                if unfilled:
                    note += "#: TODO adapt: the GameCube's text prints %s, which the cartridge does not fill here\n" \
                        % ", ".join(sorted(unfilled))
                    counts["official, free strings to adapt"] += 1
        else:
            tokens = n64[n]
            counts["japanese " + cls] += 1
            note = "#: TODO translate (%s: no official text)\n" % cls
        if N64_COUNT == N64_BANKS["message"][2]:
            tokens = split_pages(tokens, 4, counts)
        text = render(tokens)
        kept = text.rstrip("\n")               # trailing newlines (letters) as dump() writes them
        out.append("## %d\n%s%s%s\n\n" % (n, note, kept, "{cd}" * (len(text) - len(kept))))
    open(out_path, "w", encoding="utf-8").write("".join(out))
    for k in sorted(counts):
        print("  %-28s %d" % (k, counts[k]))
    print("%s: %d messages" % (out_path, N64_COUNT))


def main(argv):
    global N64_COUNT
    opts = {"--cols": 32, "--lines": 4, "--max-bytes": None}
    args, overrides, reference, bank, count, font, gc_font, i = [], [], None, "message", None, None, None, 0
    while i < len(argv):
        if argv[i] in opts:
            opts[argv[i]] = int(argv[i + 1], 0)
            i += 2
        elif argv[i] == "--bank":
            bank = argv[i + 1]
            i += 2
        elif argv[i] == "--count":
            count = int(argv[i + 1], 0)
            i += 2
        elif argv[i] == "--reference":
            reference = load_bank(argv[i + 1])
            i += 2
        elif argv[i] == "--overrides":
            overrides.append(argv[i + 1])
            i += 2
        elif argv[i] == "--font":
            font = argv[i + 1]
            i += 2
        elif argv[i] == "--gc-font":
            gc_font = argv[i + 1]
            i += 2
        else:
            args.append(argv[i])
            i += 1
    if args and args[0] == "compile-letters":
        if len(args) != 3 + len(LETTER_BANKS):
            raise SystemExit(__doc__)
        return 0 if compile_letters(args[3:], args[1], args[2], opts["--cols"], opts["--lines"]) else 1
    N64_COUNT = count if count is not None else N64_BANKS[bank][2]
    if bank in LETTER_CAPS:
        N64_COUNT = count if count is not None else (544 if bank.startswith("mail") else 384)
    if opts["--max-bytes"] is None:
        opts["--max-bytes"] = LETTER_CAPS.get(bank, N64_BANKS[bank][3])
    cmd = args[0]
    if cmd == "draft":
        draft(args[1], args[2], args[3], args[4], overrides,
              MESSAGE_REMAP if bank == "message" else LETTER_REMAP.get(bank))
        return 0
    messages, laid_numbers = load_bank(args[1]), set()
    for path in overrides:
        laid = load_bank(path)
        messages.update(laid)
        laid_numbers |= set(laid)
        print("af_text: %d messages laid over from %s" % (len(laid), os.path.basename(path)))
    measure = None
    if font is not None and bank == "message":
        import af_font
        gc = open(gc_font, "rb").read() if gc_font else None
        widths = af_font.widths(af_font.font(open(font, "rb").read(), gc)[1])
        measure, counts = Measure(widths), collections.Counter()
        messages = {n: layout(t, widths, WINDOW_WIDTH, opts["--lines"], counts) for n, t in messages.items()}
        print("af_text: laid out at %d pixels: %s" % (WINDOW_WIDTH, ", ".join("%d %s" % (counts[k], k)
                                                                             for k in sorted(counts)) or "nothing to do"))
    errors = run_checks(messages, opts["--cols"], opts["--lines"], opts["--max-bytes"], reference, bank != "message",
                        bank in LETTER_CAPS, measure, laid_numbers)
    if bank == "message":
        japanese = japanese_text(messages)
        if japanese:
            print("af_text: warning: %d messages print Japanese (%s)" % (
                len(japanese), ", ".join(map(str, japanese[:20])) + (", ..." if len(japanese) > 20 else "")))
    if cmd == "check":
        return 1 if errors else 0
    if cmd == "compile":
        if errors:
            print("af_text: not compiled")
            return 1
        compile_bank(messages, args[2], args[3])
        return 0
    raise SystemExit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
