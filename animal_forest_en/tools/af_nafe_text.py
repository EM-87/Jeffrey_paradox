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
    town the game always has at hand (GLOBAL; af_text.steering, the rule
    make rom-en holds every message laid over the bank to). The talk
    orders (DEMONPC0 and the rest) count, in every slot: slot 0 is the
    villagers' talk animation (ac_npc2 func_80998740_jp), but the gyroid
    acts on it (ac_haniwa func_8096B7AC_jp: set, it reads the choice made),
    and the shops', the post office's and the events' actors act on slot
    9; which actor says a message the bank does not tell;
  * it prints no string the game fills for one message (a free string, an
    item, STR_DETERMINATION, STR_RNDNUM, STR_MAIL) the cartridge's message
    does not print: the game would not fill it.

Where the codes differ only in the talking animal's orders in slot 0 (the
patch moved them to the beats of its English, added some, dropped some,
changed a few), the message is taken with its orders dropped and the
cartridge's put back (recover): the same values in the same order, each
between the same codes that steer the game as on the cartridge, so the
game does what the cartridge's message makes it do; where in the text
each goes is the patch's: at one of its own order's places when the
orders align (an order-preserving match by their places in the text,
segment by segment between the steering codes), else at the start of the
segment, a page, a wait for A, a line or a word, as the cartridge's
follows, nearest its place in the text.

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
COSMETIC, GLOBAL, FILLED = af_text.COSMETIC, af_text.GLOBAL, af_text.FILLED     # the rule is af_text's (steering)
KANA = set(c for c in msgbank.N64_CHARS if c and "\u3040" <= c <= "\u30ff")   # hiragana and katakana
DEMONPC0, MSGCLEAR, BTN = NAMES["DEMONPC0"], NAMES["MSGCLEAR"], NAMES["BTN"]
GAP, OTHER_VALUE = 0.35, 0.10             # recover's costs: an order placed on its own; one at an order of another value


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


steering, filled = af_text.steering, af_text.filled


def talk_order(it):
    """The talking animal's order in slot 0 (DEMONPC0 0000xx), a code or an item."""
    return it[0] == "c" and it[1] == DEMONPC0 and it[2][:1] == b"\0"


def any_order(it):
    """An order of the talking animal in any slot (DEMONPC0, DEMONPCQST): what af_text.mend puts back."""
    return it[0] == "c" and it[1] in (DEMONPC0, NAMES["DEMONPCQST"])


def steers_item(it, is_order=talk_order):
    return it[0] == "c" and it[1] not in af_text.NOT_STEERING and not is_order(it)


def _segments(items, is_order=talk_order):
    """The items between the codes that steer the game, and those codes."""
    segs, cur, cuts = [], [], []
    for it in items:
        if steers_item(it, is_order):
            segs.append(cur)
            cuts.append(it)
            cur = []
        else:
            cur.append(it)
    segs.append(cur)
    return segs, cuts


def _place(items, i):
    """How far into the segment's characters the place before items[i] is, 0 to 1."""
    total = sum(1 for it in items if it[0] == "ch")
    return sum(1 for it in items[:i] if it[0] == "ch") / total if total else 0.0


def _context(items, i):
    """What the order at items[i] follows: the segment's start, a page, a wait for A, a line's end or text."""
    for it in reversed(items[:i]):
        if it[0] == "c":
            if it[1] == MSGCLEAR:
                return "page"
            if it[1] == BTN:
                return "btn"
            continue                             # a pause, a colour, another order: further back
        return "line" if it[1] == "\n" else "text"
    return "start"


def _beats(items, kind):
    """The places an order of that context can go in items."""
    out = []
    for i in range(len(items) + 1):
        prev = items[i - 1] if i else None
        if kind == "start":
            ok = i == 0
        elif kind == "page":
            ok = prev is not None and prev[0] == "c" and prev[1] == MSGCLEAR
        elif kind == "btn":
            ok = prev is not None and prev[0] == "c" and prev[1] == BTN
        elif kind == "line":
            ok = prev == ("ch", "\n")
        else:                                    # before a word, or after the segment's last
            ok = i == 0 or (prev[0] == "ch" and prev[1] in " \n" and i < len(items) and items[i][0] == "ch") or \
                (i == len(items) and prev[0] == "ch" and prev[1] not in " \n")
        if ok:
            out.append(i)
    return out


def _recover_segment(c_seg, n_seg, is_order=talk_order):
    orders = [(it, _place(c_seg, i), _context(c_seg, i)) for i, it in enumerate(c_seg) if is_order(it)]
    clean, points = [], []
    for it in n_seg:
        if is_order(it):
            points.append((len(clean), it))
        else:
            clean.append(it)
    points = [(at, _place(clean, at), it) for at, it in points]
    # an order-preserving match of the cartridge's orders to their order places, by the places in the text
    a, b = len(orders), len(points)
    cost = [[0.0] * (b + 1)] + [[float("inf")] * (b + 1) for _ in range(a)]
    step = [[None] * (b + 1) for _ in range(a + 1)]
    for i in range(1, a + 1):
        for j in range(b + 1):
            best = (cost[i - 1][j] + GAP, "own")
            if j:
                best = min(best, (cost[i][j - 1], "drop"))
                best = min(best, (cost[i - 1][j - 1] + abs(orders[i - 1][1] - points[j - 1][1]) +
                                  (OTHER_VALUE if orders[i - 1][0] != points[j - 1][2] else 0), "match"))
            cost[i][j], step[i][j] = best
    at, i, j = [None] * a, a, b
    while i:
        if step[i][j] == "match":
            at[i - 1] = points[j - 1][0]
            i, j = i - 1, j - 1
        elif step[i][j] == "drop":
            j -= 1
        else:
            i -= 1
    # the others at a place of their context, between their neighbours, nearest their place in the text
    for k in range(a):
        if at[k] is not None:
            continue
        lo = max([at[m] for m in range(k) if at[m] is not None] or [0])
        hi = min([at[m] for m in range(k + 1, a) if at[m] is not None] or [len(clean)])
        places = [x for x in _beats(clean, orders[k][2]) if lo <= x <= hi] or \
            [x for x in _beats(clean, "text") if lo <= x <= hi] or [lo]
        at[k] = min(places, key=lambda x: (abs(_place(clean, x) - orders[k][1]), x))
    out = list(clean)
    for k in sorted(range(a), key=lambda k: (at[k], k), reverse=True):
        out.insert(at[k], orders[k][0])
    return out


def recover(cart, theirs, is_order=talk_order):
    """Their message with its talk orders in slot 0 (is_order: any_order, every slot's) dropped and the
    cartridge's put back (module doc), or None when the codes that steer the game differ otherwise."""
    c_segs, c_cuts = _segments(af_text._items(cart), is_order)
    n_segs, n_cuts = _segments(af_text._items(theirs), is_order)
    if c_cuts != n_cuts:
        return None
    out = []
    for k, (c_seg, n_seg) in enumerate(zip(c_segs, n_segs)):
        out += _recover_segment(c_seg, n_seg, is_order)
        if k < len(n_cuts):
            out.append(n_cuts[k])
    out = af_text._merge(out)
    # what it must be: the cartridge's codes that steer the game, their text and their other codes
    if steering(out) != steering(cart) or \
            [it for it in af_text._items(out) if not is_order(it)] != \
            [it for it in af_text._items(theirs) if not is_order(it)]:
        raise AssertionError("talk orders not put back as the cartridge's")
    return out


def take(ours, cart, theirs):
    """({number: tokens} taken from theirs, Counter of why each candidate was or was not)."""
    out, why = {}, collections.Counter()
    for n in sorted(cart):
        if not text_of(cart[n]).strip() or text_of(ours.get(n, [])) != text_of(cart[n]):
            continue                                 # no text (none, or line breaks only), or the en bank has its English
        tokens = theirs.get(n, [])
        if not english(tokens):
            why["not in English in the 2010 patch"] += 1
        elif not filled(tokens) <= filled(cart[n]):
            why["prints a string the game does not fill there"] += 1
        elif steering(tokens) == steering(cart[n]):
            out[n] = af_text.split_pages(tokens, 4)
            why["taken"] += 1
        else:
            recovered = recover(cart[n], tokens)
            if recovered is None:
                why["codes that steer the game differ"] += 1
            else:
                out[n] = af_text.split_pages(recovered, 4)
                why["taken, the cartridge's talk orders put back"] += 1
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
          "translation (%s)" % (len(taken), untranslated, ", ".join("%d %s" % (why[k], k) for k in sorted(why))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
