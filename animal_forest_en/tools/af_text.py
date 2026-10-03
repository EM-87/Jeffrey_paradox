#!/usr/bin/env python3
"""The translation's message bank for the N64: drafted from the official
script, checked against what the cartridge's message system can take,
compiled to the files the ROM reads.

    tools/af_text.py draft  N64_DUMP GC_DUMP ALIGN.tsv OUT.txt [--overrides FILE ...]
    tools/af_text.py draft  N64_DUMP GC_DUMP - OUT.txt --bank choice|string [--overrides FILE ...]
    tools/af_text.py check  BANK.txt [--bank message] [--cols 32] [--lines 4] [--max-bytes N] [--reference N64_DUMP]
    tools/af_text.py compile BANK.txt TEXT.bin INDEX.bin [the same options]

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
  * a line has at most --cols characters (a warning: the width is the
    font's, which phase 5 decides; 32 is the GameCube's practical English
    line in the N64's window).
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
    items = []
    for t in tokens:
        if t[0] == "t":
            items += [("ch", c) for c in t[1]]
        else:
            items.append(t)
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
    merged = []
    for it in out:
        if it[0] == "ch":
            if merged and merged[-1][0] == "t":
                merged[-1] = ("t", merged[-1][1] + it[1])
            else:
                merged.append(("t", it[1]))
        else:
            merged.append(it)
    return merged


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


def check_message(number, tokens, cols, lines, max_bytes, allowed_lines=0, single=False, original=None, letter=False):
    """[(level, text)] for one message; allowed_lines: what its original showed;
    single: a choice or free string (no codes, no terminator, one line);
    original: the cartridge's message, for the free strings the game fills;
    letter: a letter's part (lines and free strings, no terminator)."""
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
            if not COMMANDS[t[1]].startswith("STR_") or t[1] > N64_CODE_MAX:
                problems.append(("error", "code %s in a letter (only the N64's strings)" % COMMANDS[t[1]]))
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
                if page[-1] > cols:
                    problems.append(("warning", "a line of %d characters (--cols %d)" % (page[-1], cols)))
        elif t[1] in PAGE_BREAKS or t[1] in TERMINATORS:
            if shown() > lines:
                if shown() <= allowed_lines:
                    problems.append(("warning", "a page of %d lines (--lines %d), as the original" % (shown(), lines)))
                else:
                    problems.append(("error", "a page of %d lines: the window draws %d" % (shown(), lines)))
            if t[1] == NAMES["MSGCLEAR"]:
                page = [0]
    return problems


def load_bank(path):
    return Bank.parse_dump(open(path, encoding="utf-8").read())


def run_checks(messages, cols, lines, max_bytes, reference=None, single=False, letter=False):
    errors = warnings = 0
    for number in sorted(messages):
        allowed = page_lines(reference[number]) if reference and number in reference else 0
        original = reference.get(number) if reference and (letter or not single) else None
        for level, text in check_message(number, messages[number], cols, lines, max_bytes, allowed, single and not letter,
                                         original, letter):
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
    args, overrides, reference, bank, count, i = [], [], None, "message", None, 0
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
        else:
            args.append(argv[i])
            i += 1
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
    errors = run_checks(messages, opts["--cols"], opts["--lines"], opts["--max-bytes"], reference, bank != "message",
                        bank in LETTER_CAPS)
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
