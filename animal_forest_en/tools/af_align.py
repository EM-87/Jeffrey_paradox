#!/usr/bin/env python3
"""Which N64 messages have the official English text, message by message.

    tools/af_align.py ROM GC_DATA GC_TABLE OUT.tsv

Animal Crossing (GameCube) kept Doubutsu no Mori's message numbering: the
follow-up message numbers inside SETNEXTMSG codes are the same in both
banks, and 9 of 10 N64 messages have the same structural codes as the
GameCube message with their number (measured, reference/NOTES.md, "The
GameCube script"). The GameCube bank goes on past the N64's 11,752 with
its own additions. So the official text for N64 message n is GameCube
message n, when it is the same message. This compares the two banks
number by number on the codes that carry meaning (expressions, sounds,
substitutions, follow-ups, choices; not pauses, buttons, line breaks or the
GameCube's article codes) and classes each N64 message:

    same       the structural codes and their arguments agree (at least one)
    plain      neither message has structural codes: same by numbering alone
    edited     they share codes but differ: the GameCube rewrote the message
    removed    the GameCube slot is empty or a bare end: nothing to take
    different  nothing in common: the slot was reused

OUT.tsv has number, class, both texts on one line each (newlines shown as
a pilcrow); the summary goes to stdout. The texts stay on the user's
machine.
"""

import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from msgbank import Bank, NAMES, render  # noqa: E402

STRUCTURAL = {NAMES[n] for n in NAMES if n.startswith(("DEMO", "SETNEXTMSG", "SETSELSTR", "STR_", "SETFORCEMSG",
                                                       "OPENCHOICE", "FORCENEXT", "BGM", "MSGTIMEEND", "SNDTRGSYS",
                                                       "LUCK_", "MSGCONTENTS_", "VOICE", "GIVE", "SELNOB", "COLORCHARS"))}
BARE = {NAMES["MSGEND"], NAMES["MSGCONTINUE"]}


def skeleton(tokens):
    return [(t[1], t[2]) for t in tokens if t[0] == "c" and t[1] in STRUCTURAL]


def classify(n64, gc):
    """n64, gc: token lists."""
    if not gc or all(t[0] == "c" and t[1] in BARE for t in gc) and not all(t[0] == "c" and t[1] in BARE for t in n64):
        return "removed"
    a, b = skeleton(n64), skeleton(gc)
    if a == b:
        return "same" if a else "plain"
    common = len(collections.Counter(a) & collections.Counter(b))
    return "edited" if common and common * 2 >= min(len(a), len(b)) else "different"


def main(argv):
    rom, data, table, out = argv
    n64 = Bank.from_n64(open(rom, "rb").read())
    gc = Bank.from_gc(open(data, "rb").read(), open(table, "rb").read())
    counts, rows = collections.Counter(), []
    for i in range(len(n64)):
        a = n64.tokens(i)
        b = gc.tokens(i) if i < len(gc) else []
        cls = classify(a, b)
        counts[cls] += 1
        rows.append("%d\t%s\t%s\t%s\n" % (i, cls, render(a).replace("\n", "¶"), render(b).replace("\n", "¶")))
    open(out, "w", encoding="utf-8").write("number\tclass\tn64\tgc\n" + "".join(rows))
    total = len(n64)
    print("af_align: %d N64 messages against GameCube messages 0..%d" % (total, len(gc) - 1))
    for cls in ("same", "plain", "edited", "removed", "different"):
        print("  %-9s %5d  %4.1f%%" % (cls, counts[cls], 100.0 * counts[cls] / total))
    print("  official text usable as is (same + plain): %d (%.1f%%)" % (counts["same"] + counts["plain"],
                                                                        100.0 * (counts["same"] + counts["plain"]) / total))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
