#!/usr/bin/env python3
"""Try many ways of writing one function, fast.

    tools/af_bench.py SRC_C FUNC VARIANTS_FILE [--show]

Run from the decomp's root (build/af). Each variant (whole definitions of
FUNC, separated by lines of "----") is compiled alone with SRC_C's prelude
(everything before its first function) by IDO directly, without the
decomp's make, and compared with FUNC in expected/: one line per variant
with its stack frame and the number of differing instructions. --show
prints the differences side by side, the cartridge's on the left.

About 0.1 s a variant, so hundreds of declaration orders or expression
shapes can be swept in a minute (see reference/NOTES.md, "Matching notes").
Relocations are not compared, so a data reference written as base+offset
where the cartridge names the symbol counts as a difference here and not in
af_match.py; af_match.py and `make verify` have the last word.
"""

import os
import re
import subprocess
import sys
import tempfile

CC = "tools/ido/linux/7.1/cc"
FLAGS = ("-c -G 0 -non_shared -Xcpluscomm -Wab,-r4300_mul -mips2 -EB -O2 -g3 -nostdinc -DVERSION_JP=1 "
         "-Iinclude -Isrc -Iassets/jp -I. -Ibuild -Ilib/ultralib/include -Ilib/ultralib/include/PR "
         "-Ilib/ultralib/include/compiler/ido -DLANGUAGE_C -D_LANGUAGE_C -D_MIPS_SZLONG=32 -DF3DEX_GBI_2 "
         "-DNDEBUG -D_FINALROM -DBUILD_VERSION=VERSION_L").split()


def instructions(obj, func):
    out = subprocess.run(["mips-linux-gnu-objdump", "-dz", "-M", "reg-names=32", "--disassemble=" + func, obj],
                         capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines():
        m = re.match(r"\s+[0-9a-f]+:\t[0-9a-f]{8} \t(.*)", line)
        if m:
            rows.append(re.sub(r"[0-9a-f]+ <[^>]*>", "<>", m.group(1)).replace("\t", " "))
    return rows


DEFINITION = re.compile(r"^([A-Za-z_][^\n;{]*?(\w+)\([^;{]*\))\s*\{", flags=re.M)


def prototypes(text, func):
    """Declarations of the functions the file defines before FUNC, as the
    compiler sees them there. Without them a call to a file-local function
    is an implicit declaration, which IDO compiles differently (it once
    made 46 of func_80094400_jp's instructions differ). Definitions under
    NON_MATCHING are left out: the build does not see them either."""
    hidden = []
    for m in re.finditer(r"^#ifdef NON_MATCHING\n.*?^#else\n", text, flags=re.M | re.S):
        hidden.append((m.start(), m.end()))
    out = []
    for m in DEFINITION.finditer(text):
        if m.group(2) == func:
            break
        if any(a <= m.start() < b for a, b in hidden) or m.group(1).startswith("static"):
            continue
        out.append(m.group(1) + ";")
    return "\n".join(out) + "\n"


def main(argv):
    show = "--show" in argv
    argv = [a for a in argv if a != "--show"]
    src, func, variants_file = argv
    text = open(src).read()
    first = DEFINITION.search(text)
    prelude = text[:first.start()] + prototypes(text, func)
    want = instructions("expected/build/" + os.path.splitext(src)[0] + ".o", func)
    if not want:
        raise SystemExit("%s is not in expected/ (make diff-init)" % func)
    variants = [v for v in open(variants_file).read().split("\n----\n") if v.strip()]
    best = None
    with tempfile.TemporaryDirectory() as tmp:
        c, o = os.path.join(tmp, "v.c"), os.path.join(tmp, "v.o")
        for i, v in enumerate(variants):
            open(c, "w").write(prelude + "\n" + v + "\n")
            r = subprocess.run([CC] + FLAGS + ["-o", o, c], capture_output=True, text=True)
            got = instructions(o, func) if r.returncode == 0 else []
            if not got:
                errors = [l for l in r.stderr.splitlines() if "rror" in l]
                print("%3d  build failed: %s" % (i, " / ".join(errors)[:200]))
                continue
            frame = next((g.split(",")[-1] for g in got if g.startswith("addiu sp,sp,-")), "leaf")
            n = max(len(got), len(want))
            bad = [k for k in range(n) if k >= len(got) or k >= len(want) or got[k] != want[k]]
            print("%3d  frame %-5s %d differ" % (i, frame, len(bad)))
            if best is None or len(bad) < best[0]:
                best = (len(bad), i)
            if show and bad:
                for k in range(n):
                    a = want[k] if k < len(want) else ""
                    b = got[k] if k < len(got) else ""
                    print("      %s %-40s | %s" % ("*" if a != b else " ", a, b))
    if best:
        print("best: variant %d (%d differ)" % (best[1], best[0]))
    return 0 if best and best[0] == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
