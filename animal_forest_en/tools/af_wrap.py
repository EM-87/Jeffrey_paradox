#!/usr/bin/env python3
"""Put a function's C behind NON_MATCHING, or take it out again.

    tools/af_wrap.py SRC_C FUNC [FUNC...]          wrap
    tools/af_wrap.py --unwrap SRC_C FUNC [FUNC...]  unwrap (it matches now)

Run from the decomp's root (build/af). The decomp's convention for C that
does not match yet:

    #ifdef NON_MATCHING
    <the C>
    #else
    #pragma GLOBAL_ASM("asm/jp/nonmatchings/<dir>/<file>/FUNC.s")
    #endif

so the ROM keeps the cartridge's own code until the C is proven, while the
C stays in the file as the best attempt so far.
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from af_try import find  # noqa: E402


def asm_path(src, func):
    rel = os.path.splitext(os.path.relpath(src, "src"))[0]
    return "asm/jp/nonmatchings/%s/%s.s" % (rel, func)


def wrap(text, src, func):
    start, end = find(text, func)
    before = text[:start]
    if before.rstrip().endswith("#ifdef NON_MATCHING"):
        return text
    block = "#ifdef NON_MATCHING\n%s\n#else\n#pragma GLOBAL_ASM(\"%s\")\n#endif" % (text[start:end], asm_path(src, func))
    return before + block + text[end:]


def unwrap(text, src, func):
    start, end = find(text, func)
    head = "#ifdef NON_MATCHING\n"
    if not text[:start].endswith(head):
        return text
    tail = "\n#else\n#pragma GLOBAL_ASM(\"%s\")\n#endif" % asm_path(src, func)
    if not text[end:].startswith(tail):
        raise SystemExit("%s: unexpected NON_MATCHING block" % func)
    return text[:start - len(head)] + text[start:end] + text[end + len(tail):]


def main(argv):
    undo = argv[0] == "--unwrap"
    if undo:
        argv = argv[1:]
    src, funcs = argv[0], argv[1:]
    text = open(src).read()
    for f in funcs:
        text = (unwrap if undo else wrap)(text, src, f)
    open(src, "w").write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
