#!/usr/bin/env python3
"""Messages the GameCube's script has no counterpart for, in the English of
the AF Project's 2010 translation, for the en build.

    tools/af_nafe_text.py NAFE_ROM EN_BANK.txt N64_DUMP.txt OUT.txt

The 2010 patch (NAFE-WIP-2_12_2010.ups by Zoinkity and the AF Project; make
nafe builds its ROM from the user's copy) translated the cartridge's
message bank in place: its index is still the cartridge's file (vrom
0xCF9000), its text a new file at 0x1914000, and its message n is the
cartridge's message n where its codes are the cartridge's (of the
cartridge's 11,650 messages with a string or an end code, 10,166 have the
same ones in the same order in the patch).
The en bank (af_text.py draft, from the user's disc) still has the
cartridge's Japanese for about 900 messages, those the GameCube's script
has no counterpart for and we have not translated. This takes the 2010
patch's English for them, where it can do so safely; a message is taken
when

  * the en bank's text for it is the cartridge's own (EN_BANK against
    N64_DUMP, tools/msgbank.py dump-n64 of the user's dump);
  * the patch's text is English (Latin letters, no kana);
  * its codes that steer the game are the cartridge's, the same ones in the
    same order with the same arguments: all but the page breaks, pauses,
    colours, scales and voice switches (COSMETIC) and the names, dates and
    town the game always has at hand (GLOBAL). The talk orders (DEMONPC0
    and the rest) count, in every slot: slot 0 is the villagers' talk
    animation (ac_npc2 func_80998740_jp), but the gyroid acts on it (ac_haniwa
    func_8096B7AC_jp: set, it reads the choice made), and the shops', the
    post office's and the events' actors act on slot 9; which actor says a
    message the bank does not tell;
  * it prints no string the game fills for one message (a free string, an
    item, STR_DETERMINATION, STR_RNDNUM, STR_MAIL) the cartridge's message
    does not print: the game would not fill it.

A page of more than four lines is split as the draft splits the bank's
(af_text.split_pages): the window draws four. OUT.txt is the messages
taken in the bank's dump form, which make rom-en lays over the en bank
(af_text.py compile --overrides); it is written under build/gc, never
committed, and nothing of the patch's text enters the repository.
"""

import collections
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_text  # noqa: E402
import msgbank  # noqa: E402
import nafe  # noqa: E402
import nafe_diff  # noqa: E402

INDEX_VROM = 0xCF9000                     # the cartridge's message index, kept by the patch (msgbank.N64_BANKS)
TEXT_VROM = 0x1914000                     # the patch's message text (the cartridge's is at 0xBD4000)
COUNT = msgbank.N64_BANKS["message"][2]

NAMES = msgbank.NAMES
COSMETIC = {NAMES[n] for n in ("PAUSE", "BTN", "BTN2", "MSGCLEAR", "TEXTCOLOR", "COLORCHARS", "LINEOFS", "LINETYPE",
                                "CHARSCALE", "LINESCALE", "VOICETRUE", "VOICEFALSE")}
GLOBAL = {NAMES[n] for n in ("STR_PLAYERNAME", "STR_TALKNAME", "STR_TAIL", "STR_YEAR", "STR_MONTH", "STR_WEEK",
                              "STR_DAY", "STR_HOUR", "STR_MIN", "STR_SEC", "STR_COUNTRYNAME")}
FILLED = ({NAMES[n] for n in NAMES if n.startswith("STR_FREE") or n.startswith("STR_ITEM")} |
          {NAMES[n] for n in ("STR_DETERMINATION", "STR_RNDNUM", "STR_MAIL")})
KANA = set(c for c in msgbank.N64_CHARS if c and "\u3040" <= c <= "\u30ff")   # hiragana and katakana


def messages(rom):
    """{number: tokens} of the patch's message bank."""
    if hashlib.md5(rom).hexdigest() != nafe.NAFE_MD5:
        raise ValueError("not the 2010 patch's ROM (md5 %s)" % nafe.NAFE_MD5)
    files = {e[0]: e for e in nafe_diff.dma_entries(rom)}
    index = nafe_diff.file_data(rom, files[INDEX_VROM])
    text = nafe_diff.file_data(rom, files[TEXT_VROM])
    bank = msgbank.Bank.from_table(text, index[:4 * COUNT], msgbank.N64_CHARS)
    return {n: bank.tokens(n) for n in range(len(bank))}


def text_of(tokens):
    return "".join(t[1] for t in tokens if t[0] == "t")


def english(tokens):
    text = text_of(tokens)
    return any(c.isascii() and c.isalpha() for c in text) and not any(c in KANA for c in text)


def steering(tokens):
    """The codes that steer the game, with their arguments, in order."""
    return [(t[1], t[2]) for t in tokens if t[0] == "c" and t[1] not in COSMETIC | GLOBAL | FILLED]


def filled(tokens):
    return set(t[1] for t in tokens if t[0] == "c" and t[1] in FILLED)


def take(ours, cart, theirs):
    """({number: tokens} taken from theirs, Counter of why each candidate was or was not)."""
    out, why = {}, collections.Counter()
    for n in sorted(cart):
        if not text_of(cart[n]) or text_of(ours.get(n, [])) != text_of(cart[n]):
            continue                                 # empty, or the en bank has its English
        tokens = theirs.get(n, [])
        if not english(tokens):
            why["not in English in the 2010 patch"] += 1
        elif steering(tokens) != steering(cart[n]):
            why["codes that steer the game differ"] += 1
        elif not filled(tokens) <= filled(cart[n]):
            why["prints a string the game does not fill there"] += 1
        else:
            out[n] = af_text.split_pages(tokens, 4)
            why["taken"] += 1
    return out, why


def dump(taken):
    return "".join("## %d\n#: the AF Project's 2010 translation\n%s\n\n" % (n, msgbank.render(taken[n]).rstrip("\n"))
                   for n in sorted(taken))


def main(argv):
    if len(argv) != 4:
        raise SystemExit(__doc__)
    theirs = messages(open(argv[0], "rb").read())
    ours, cart = af_text.load_bank(argv[1]), af_text.load_bank(argv[2])
    taken, why = take(ours, cart, theirs)
    open(argv[3], "w", encoding="utf-8").write(dump(taken))
    untranslated = sum(why.values())
    print("af_nafe_text: %d of the en bank's %d messages still in Japanese taken from the AF Project's 2010 "
          "translation (%s)" % (len(taken), untranslated,
                                ", ".join("%d %s" % (why[k], k) for k in sorted(why) if k != "taken")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
