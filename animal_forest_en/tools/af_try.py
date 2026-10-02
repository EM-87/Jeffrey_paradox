#!/usr/bin/env python3
"""Try several ways of writing one function; keep the best.

    tools/af_try.py SRC_C FUNC VARIANTS_FILE

Run from the decomp's root (build/af). VARIANTS_FILE holds whole function
definitions separated by lines of "----". Each replaces FUNC's current
definition in SRC_C (from its first line to the next "}" in column 0), is
compiled, and scored with af_match.py's comparison. The best one stays in
SRC_C (the first that matches, or the lowest score; the original if none
beats it).
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_match  # noqa: E402


def find(text, func):
    m = re.search(r"^[A-Za-z_][^\n;{]*?\b%s\s*\([^;{]*\)\s*\{" % re.escape(func), text, flags=re.M)
    if not m:
        raise SystemExit("%s is not defined in C here" % func)
    end = text.index("\n}", m.start()) + 2
    return m.start(), end


def main(argv):
    src, func, variants_file = argv
    text = open(src).read()
    start, end = find(text, func)
    original = text[start:end]
    variants = [v.strip("\n") for v in open(variants_file).read().split("\n----\n") if v.strip()]

    wrapped = text[:start].rstrip().endswith("#ifdef NON_MATCHING")

    def attempt(body):
        open(src, "w").write(text[:start] + body + text[end:])
        try:
            obj = af_match.build(src, non_matching=wrapped)
        except SystemExit:
            return None
        return af_match.score(func, obj)[0]

    best = (attempt(original), -1)
    print("  original: %s" % best[0])
    for i, v in enumerate(variants):
        s = attempt(v)
        print("  variant %d: %s" % (i, s))
        if s is not None and (best[0] is None or s < best[0]):
            best = (s, i)
        if s == 0:
            break
    body = original if best[1] < 0 else variants[best[1]]
    open(src, "w").write(text[:start] + body + text[end:])
    if wrapped:
        af_match.build(src)
    print("kept %s (%s)" % ("original" if best[1] < 0 else "variant %d" % best[1], best[0]))
    return 0 if best[0] == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
