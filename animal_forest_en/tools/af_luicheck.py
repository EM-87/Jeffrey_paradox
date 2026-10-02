#!/usr/bin/env python3
"""Find the `lui` instructions the asm shares between two symbols, and tell
whether a new layout keeps them working.

    tools/af_luicheck.py REF_MAP NEW_MAP [--asm asm/jp] [--all]

MIPS loads an address in two halves: `lui $r, %hi(A)` then `%lo(B)($r)`.
The halves agree only while A and B sit in the same 64 KB window (the
window is [X8000, X+1 7FFF): %hi rounds). IDO and hand-written asm share
one lui between neighbouring symbols, and the disassembly keeps that as a
pair whose two names differ, so the linker cannot fix one without the
other. Such a pair is a hazard whenever symbols move by different amounts
(it survives a shift of the whole window by a multiple of 0x10000).

This scans the asm (text files only), tracks which `lui` last wrote each
register (through addu/or/move), and reports every mixed pair whose halves
agree in REF_MAP's layout and disagree in NEW_MAP's. The register tracking
is linear, so a few pairs it reports are not real (the lui came from
another path); those already disagree in the reference and are not
counted. --all lists every mixed pair with its symbols' sections.
Exit status 1 when a pair is broken.
"""

import glob
import os
import re
import sys

RELOC = re.compile(r"%(hi|lo)\(([A-Za-z_][A-Za-z0-9_]*)(?:\s*([+-])\s*(0x[0-9A-Fa-f]+|\d+))?\)")
REG = re.compile(r"\$([a-z0-9]+)")
# instructions that write no general register (first operand is not a destination)
NO_WRITE = {"sw", "sh", "sb", "sd", "swl", "swr", "sdl", "sdr", "sc", "scd", "swc1", "sdc1", "lwc1", "ldc1",
            "beq", "bne", "beql", "bnel", "blez", "bgtz", "bltz", "bgez", "bltzl", "bgezl", "blezl", "bgtzl",
            "bc1f", "bc1t", "bc1fl", "bc1tl", "j", "jal", "jr", "jalr", "mtc1", "ctc1", "mult", "multu",
            "div", "divu", "mthi", "mtlo", "cache", "sync", "nop", "break", "teq", "tne", "tge", "tlt"}
# the address in a register survives these (the sum of a lui and an index)
PROPAGATE = {"addu", "daddu", "add", "dadd", "or", "move", "addiu", "daddiu"}


def hi16(x):
    return ((x + 0x8000) >> 16) & 0xFFFF


def load_map(path):
    syms = {}
    for line in open(path):
        m = re.match(r"\s+0x([0-9a-f]+)\s+([A-Za-z_][A-Za-z0-9_.$]*)\s*$", line)
        if m:
            syms.setdefault(m.group(2), int(m.group(1), 16))
    return syms


def addend(m):
    if not m.group(4):
        return 0
    return int(m.group(4), 0) * (-1 if m.group(3) == "-" else 1)


def mixed_pairs(asm_dir):
    """(file, lui line, lo line, hi symbol, hi addend, lo symbol, lo addend) for every
    %lo whose register was last loaded by a lui of another symbol."""
    pairs = []
    for path in sorted(glob.glob(os.path.join(asm_dir, "**", "*.s"), recursive=True)):
        if os.sep + "data" + os.sep in path:
            continue
        last = {}
        for ln, line in enumerate(open(path), 1):
            s = line.split("#")[0].strip()
            if s.startswith("/*"):
                s = s.split("*/", 1)[1].strip()
            if not s or s[0] == "." or s.endswith(":") or s.split(" ")[0] in ("glabel", "jlabel", "dlabel"):
                continue
            op = s.replace(",", " ").split()[0]
            regs = REG.findall(s)
            m = RELOC.search(s)
            if op == "lui" and m and m.group(1) == "hi":
                last[regs[0]] = (m.group(2), addend(m), ln)
                continue
            if m and m.group(1) == "lo":
                base = regs[-1]
                if base in last:
                    hs, ha, hl = last[base]
                    if hs != m.group(2):
                        pairs.append((path, hl, ln, hs, ha, m.group(2), addend(m)))
                if op in ("addiu", "daddiu", "ori") and regs[0] != base:
                    last.pop(regs[0], None)
                continue
            if op in PROPAGATE and len(regs) >= 2:
                src = [r for r in regs[1:] if r in last]
                if src:
                    last[regs[0]] = last[src[0]]
                    continue
            if op not in NO_WRITE and regs:
                last.pop(regs[0], None)
    return pairs


def main(argv):
    asm_dir, show_all = "asm/jp", False
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == "--asm":
            asm_dir = argv[i + 1]
            i += 2
        elif argv[i] == "--all":
            show_all = True
            i += 1
        else:
            args.append(argv[i])
            i += 1
    ref, new = load_map(args[0]), load_map(args[1])
    pairs = mixed_pairs(asm_dir)
    broken = unknown = untracked = agreed = 0
    for path, hl, ln, hs, ha, ls, la in pairs:
        if hs not in ref or ls not in ref or hs not in new or ls not in new:
            unknown += 1
            continue
        if hi16(ref[hs] + ha) != hi16(ref[ls] + la):
            untracked += 1          # not a real pair: the halves already disagree where the game works
            continue
        agreed += 1
        ok = hi16(new[hs] + ha) == hi16(new[ls] + la)
        if not ok or show_all:
            print("%s %s:%d-%d  lui %%hi(%s%+#x) %#x->%#x  %%lo(%s%+#x) %#x->%#x"
                  % ("BROKEN" if not ok else "ok    ", path, hl, ln, hs, ha, ref[hs], new[hs], ls, la, ref[ls], new[ls]))
        broken += not ok
    print("af_luicheck: %d shared lui pairs (%d unknown symbols, %d tracking errors), %d broken in %s"
          % (agreed, unknown, untracked, broken, args[1]))
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
