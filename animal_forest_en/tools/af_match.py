#!/usr/bin/env python3
"""Does our C compile to the cartridge's code? One answer per function.

    tools/af_match.py [--diff] SRC_C FUNC [FUNC...]
    tools/af_match.py --all SRC_C

Run from the decomp's root (build/af). Builds SRC_C's object once, then
compare each function with expected/ (made by `make diff-init`): the
symbol's words and relocations, read with objdump, wherever it sits in the
object. 0 differing instructions is a match. --diff prints the function
side by side, the cartridge's on the left. --all checks every function the file defines
in C (those not behind a GLOBAL_ASM pragma).
"""

import os
import re
import subprocess
import sys

def c_functions(src):
    """(name, wrapped) for every function defined in C; wrapped means it is
    behind #ifdef NON_MATCHING (tools/af_wrap.py), the ROM using its asm."""
    text = open(src).read()
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for m in re.finditer(r"^[A-Za-z_][\w\s\*]*?\b(\w+)\s*\([^;{]*\)\s*\{", text, flags=re.M):
        if m.group(1) in ("if", "while", "for", "switch"):
            continue
        out.append((m.group(1), text[:m.start()].rstrip().endswith("#ifdef NON_MATCHING")))
    return out


def build(src, non_matching=False):
    obj = "build/" + os.path.splitext(src)[0] + ".o"
    # The object's timestamp says nothing about which way it was built:
    # always rebuild, or a NON_MATCHING object could end up in the ROM.
    if os.path.exists(obj):
        os.remove(obj)
    args = ["make", "-s", obj, "WERROR=0"] + (["NON_MATCHING=1"] if non_matching else [])
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit("build failed")
    return obj


def disasm(obj, func):
    """[(raw word, text, [relocations])] for one symbol, offsets dropped."""
    r = subprocess.run(["mips-linux-gnu-objdump", "-drz", "-M", "reg-names=32", "--disassemble=" + func, obj],
                       capture_output=True, text=True)
    out, started = [], False
    for line in r.stdout.splitlines():
        if line.endswith("<%s>:" % func):
            started = True
            continue
        if not started:
            continue
        m = re.match(r"\s+[0-9a-f]+:\t([0-9a-f]{8}) \t(.*)", line)
        if m:
            text = re.sub(r"\b[0-9a-f]+ <", "<", m.group(2)).replace("\t", " ")
            out.append([m.group(1), text, []])
            continue
        m = re.match(r"\s+[0-9a-f]+: (R_MIPS_\w+)\s+(\S+)", line)
        if m and out:
            out[-1][2].append("%s %s" % (m.group(1), m.group(2)))
    return out


DATA_PREFIXES = ("D_", "B_", "RO_", "jtbl_", "FLT_", "DBL_", ".rodata", ".data", ".bss", ".late_rodata", "$")


def comparable(row):
    """A row as compared: a %hi/%lo reference to data (not code) is the same
    reference whatever its name and addend here, because an object built
    from asm names data D_/RO_ where one built from C has .rodata+offset.
    Such rows are masked to the opcode and registers; `make verify` checks
    the final addresses."""
    word, _, relocs = row
    out = []
    masked = False
    for r in relocs:
        kind, sym = r.split(" ", 1)
        if kind in ("R_MIPS_HI16", "R_MIPS_LO16") and sym.startswith(DATA_PREFIXES):
            out.append(kind + " <data>")
            masked = True
        else:
            out.append(r)
    if masked:
        word = word[:4] + "????"
    return word, out


def score(func, obj):
    """Number of differing instructions (0 = match), and the rows."""
    mine = disasm(obj, func)
    want = disasm("expected/" + obj, func)
    if not want:
        return None, ["%s not in expected/%s" % (func, obj)]
    if not mine:
        return None, ["%s not in %s" % (func, obj)]
    rows, bad = [], 0
    for i in range(max(len(mine), len(want))):
        a = want[i] if i < len(want) else None
        b = mine[i] if i < len(mine) else None
        same = a and b and comparable(a) == comparable(b)
        if not same:
            bad += 1
        ta = ("%s %s" % (a[1], " ".join(a[2]))) if a else ""
        tb = ("%s %s" % (b[1], " ".join(b[2]))) if b else ""
        rows.append("%s %3x  %-44s | %s" % (" " if same else "*", i * 4, ta[:44], tb))
    return bad, rows


def main(argv):
    show = "--diff" in argv
    argv = [a for a in argv if a != "--diff"]
    if argv and argv[0] == "--all":
        src = argv[1]
        funcs = c_functions(src)
    else:
        src = argv[0]
        known = dict(c_functions(src))
        funcs = [(f, known.get(f, False)) for f in argv[1:]]
    results = {}
    # Matching C first, from the normal build; then the NON_MATCHING drafts,
    # from a build with them compiled in; then the normal object back.
    for wrapped in (False, True):
        group = [f for f, w in funcs if w == wrapped]
        if not group:
            continue
        obj = build(src, non_matching=wrapped)
        for f in group:
            results[f] = score(f, obj)
    if any(w for _, w in funcs):
        build(src)
    bad = 0
    for f, wrapped in funcs:
        s, rows = results[f]
        tag = " (NON_MATCHING)" if wrapped else ""
        if s == 0:
            print("  match  %s%s" % (f, tag))
            continue
        bad += 1
        print("  %5s  %s%s" % ("?" if s is None else s, f, tag))
        if show:
            for r in rows[:200]:
                print("         " + r)
    print("%d/%d match" % (len(funcs) - bad, len(funcs)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
