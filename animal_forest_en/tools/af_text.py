#!/usr/bin/env python3
"""The translation's message bank for the N64: drafted from the official
script, checked against what the cartridge's message system can take,
compiled to the files the ROM reads.

    tools/af_text.py draft  N64_DUMP GC_DUMP ALIGN.tsv OUT.txt [--overrides FILE ...]
    tools/af_text.py draft  N64_DUMP GC_DUMP - OUT.txt --bank choice|string [--overrides FILE ...]
    tools/af_text.py check  BANK.txt [--bank message] [--cols 32] [--lines 4] [--max-bytes N] [--reference N64_DUMP]
                            [--font ROM]
    tools/af_text.py compile BANK.txt TEXT.bin INDEX.bin [the same options]
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
by number where it is not empty (no alignment file: pass `-`). --count
overrides the count: the en string bank goes on to 0x679 entries, through
the GameCube's day ordinals (0x64E) and month names (0x66D), which the en
build's date builders read.

A bank text is tools/msgbank.py's dump form: `## number`, the message with
its codes as `<NAME hex>`, `#:` notes. compile needs every number 0..11751
(the cartridge's count: the game addresses messages by number) and writes
the text file and the u32 table of ends, 16-byte padded, that the message
loader (`func_8009E388_jp`) reads through dmadata.

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
    user's dump, for the en build's widths: tools/af_font.py) at most the
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
do: CUTARTICLE, CAPITALIZE, SETCURSORJUST, CLRCUSRORJUST and SPACE are
dropped (counted), STR_AMPM is kept (compiled as code 71, which the en
build reads as it), MALEFEMALECHK keeps its first alternative,
SELNOBCLOSE becomes the N64's SELNOB, anything else is kept and reported
for the check to refuse. Characters the N64 charset lacks are given
their plain ASCII (é -> e, the GameCube's marks their nearest; each
replacement is counted). An official message naming free strings the
Japanese does not gets a `#: TODO adapt` note. --overrides files (our own
translations, in the repository) replace messages by number.
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
DROP = {NAMES[n] for n in ("CUTARTICLE", "CAPTIALIZE", "SETCURSORJUST", "CLRCUSRORJUST", "SPACE")}
# GameCube codes the translation's N64 code carries under another number: the
# bank text keeps the GameCube's name, compile writes the N64's (changes.patch,
# src/code/m_msg_main.c: code 71, LUCK_6 on the cartridge and in neither bank,
# is STR_AMPM in the en build).
N64_CARRIED = {NAMES["STR_AMPM"]: NAMES["LUCK_6"]}
# The en build's letter banks: room for the GameCube's longest entry (header
# 23 bytes, body 177, footer 30, the villagers' parts 20/62/90/32), free strings
# expanded later by the loader.
LETTER_CAPS = {"mail_header": 32, "mail_body": 192, "mail_footer": 32, "vmail_header": 32,
               "vmail_a": 96, "vmail_b": 96, "vmail_c": 48, "vmail_footer": 32}


LETTER_BANKS = ("mail_header", "mail_body", "mail_footer",
                "vmail_header", "vmail_a", "vmail_b", "vmail_c", "vmail_footer")


def letter_count(bank):
    return 544 if bank.startswith("mail") else 384


LETTER_FIRST = {}
for _bank in LETTER_BANKS:
    LETTER_FIRST[_bank] = sum(letter_count(b) for b in LETTER_BANKS[:LETTER_BANKS.index(_bank)])


def carried(tokens):
    return [("c", N64_CARRIED[t[1]], t[2]) if t[0] == "c" and t[1] in N64_CARRIED else t for t in tokens]
FALLBACK = {"…": "...", "“": '"', "”": '"', "‘": "'", "’": "'", "–": "-", "—": "-",
            "ー": "-", "¡": "!", "¿": "?", "ß": "ss", "Æ": "AE", "æ": "ae", "Ø": "O", "ø": "o", "Ð": "D", "ð": "d",
            "Þ": "Th", "þ": "th", "·": ".", "•": "*", "×": "x", "°": "o",
            "~": "～", "😃": "☺", "😄": "☺"}               # the N64 font's wave dash and face


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
# letters of the font's average lowercase width: the names (the player's,
# the animal's, the town's) 6 and the catchphrase 4, their fields' sizes
# (PLAYER_NAME_LEN, ANIMAL_CATCHPHRASE_LEN); the date's and time's parts as
# the en build writes them (m_string.c: "Wednesday", "September", "14th",
# "12", "p.m."); any other (free strings, items: numbers, names, things) 8,
# TODO(verify) against what the game fills.
CODE_LETTERS = {"STR_PLAYERNAME": 6, "STR_TALKNAME": 6, "STR_COUNTRYNAME": 6, "STR_ISLANDNAME": 6,
                "STR_TAIL": 4, "STR_YEAR": 4, "STR_MONTH": 9, "STR_WEEK": 9, "STR_DAY": 4,
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
            if not COMMANDS[t[1]].startswith("STR_FREE"):
                # the letters' expander (func_80093478_jp) has a handler for the twenty free strings
                # only (D_80107020_jp) and does not move past any other code: the game would hang
                problems.append(("error", "code %s in a letter (only the free strings)" % COMMANDS[t[1]]))
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


def run_checks(messages, cols, lines, max_bytes, reference=None, single=False, letter=False, measure=None):
    errors = warnings = 0
    for number in sorted(messages):
        allowed = page_lines(reference[number]) if reference and number in reference else 0
        original = reference.get(number) if reference and (letter or not single) else None
        for level, text in check_message(number, messages[number], cols, lines, max_bytes, allowed, single and not letter,
                                         original, letter, measure):
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
    open(index_path, "wb").write(struct.pack(">%dI" % len(ends), *ends))
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


def draft(n64_path, gc_path, align_path, out_path, overrides):
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
            classes[n] = "same" if gc.get(n) else "removed"
    else:
        for line in open(align_path, encoding="utf-8").read().splitlines()[1:]:
            number, cls = line.split("\t")[:2]
            classes[int(number)] = cls
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
        elif cls in ("same", "plain", "edited") and n in gc:
            tokens = adapt(gc[n], counts)
            counts["official " + cls] += 1
            if cls == "edited":
                note = "#: the GameCube edited this message; the Japanese had: %s\n" % render(n64[n]).replace("\n", "¶")
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
    args, overrides, reference, bank, count, font, i = [], [], None, "message", None, None, 0
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
        draft(args[1], args[2], args[3], args[4], overrides)
        return 0
    messages = load_bank(args[1])
    measure = None
    if font is not None and bank == "message":
        import af_font
        from af_names import segment
        widths = af_font.widths(af_font.align(segment(open(font, "rb").read(), af_font.VROM))[1])
        measure, counts = Measure(widths), collections.Counter()
        messages = {n: layout(t, widths, WINDOW_WIDTH, opts["--lines"], counts) for n, t in messages.items()}
        print("af_text: laid out at %d pixels: %s" % (WINDOW_WIDTH, ", ".join("%d %s" % (counts[k], k)
                                                                             for k in sorted(counts)) or "nothing to do"))
    errors = run_checks(messages, opts["--cols"], opts["--lines"], opts["--max-bytes"], reference, bank != "message",
                        bank in LETTER_CAPS, measure)
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
