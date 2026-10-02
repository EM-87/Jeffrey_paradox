#!/usr/bin/env python3
"""Find addresses the disassembly spelled against the wrong symbol.

    tools/af_anchors.py REF_MAP [--asm asm/jp] [--all]

IDO folds a constant index into an address: `table[name - 0x8000]` on an
s16 table becomes `lui/lh` of `table - 0x10000`, an address 64 KB below the
table, usually inside some unrelated function. The disassembler names such
an address after the nearest symbol below it (`Na_KishaStatusLevel + 0x7C`
for `move_obj_profile_table - 0x10000`, in ovl_Birth_Control), which links
to the same bytes as the cartridge but follows the wrong thing when the
layout changes: the table moves, the function does not, and the game reads
zeros (reference/NOTES.md, "Shiftability").

This scans the asm for every %hi/%lo and .word reference with an addend,
places the named symbol and the resulting address with the reference
(matching) map, and reports:

  * a load or store whose address is a text symbol plus an offset
    (an error: data is never inside a function);
  * a reference whose address falls in another object or section than the
    symbol it is spelled with, unless its addend is negative and a multiple
    of 0x1000, which is what a correctly spelled fold looks like (an error
    when either side is in `code`, whose layout the translation changes;
    otherwise listed with --all).

For each error it names the data symbols a whole number of 4 KB above the
address, which is where the folded table usually is. The fix is to spell
the reference with that symbol (`%lo(move_obj_profile_table - 0x10000)`):
the matching build keeps its bytes, and the address follows the table.
Exit status 1 when there is an error.
"""

import bisect
import glob
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from af_shiftcheck import LinkMap  # noqa: E402

RELOC = re.compile(r"%(hi|lo)\(([A-Za-z_][A-Za-z0-9_]*)(?:\s*([-+])\s*(0x[0-9A-Fa-f]+|\d+))?\)")
WORD = re.compile(r"^\s*\.word\s+([A-Za-z_][A-Za-z0-9_]*)\s*([-+])\s*(0x[0-9A-Fa-f]+|\d+)\s*$")
MEMORY = {"lb", "lbu", "lh", "lhu", "lw", "lwu", "lwl", "lwr", "ld", "lwc1", "ldc1",
          "sb", "sh", "sw", "swl", "swr", "sd", "swc1", "sdc1"}


class Layout:
    def __init__(self, path):
        m = LinkMap(path)
        self.secs = sorted((a, a + n, sec, obj, out) for out, sec, obj, a, n in m.inputs
                           if n and 0x80000000 <= a < 0x81000000 and not (out or "").startswith(".segment_"))
        self.starts = [s[0] for s in self.secs]
        self.labels = {name: addr for name, (addr, out) in m.symbols.items()
                       if not (out or "").startswith(".segment_")}
        self.by_addr = sorted((addr, name) for name, addr in self.labels.items()
                              if not name.endswith(".NON_MATCHING") and not name.startswith("code_"))
        self.code_lo = m.marks.get("code_TEXT_START", m.marks.get("code_VRAM", 0))
        self.code_hi = m.marks.get("buffers_VRAM_END", 0)

    def owner(self, addr):
        i = bisect.bisect_right(self.starts, addr) - 1
        if i >= 0 and self.secs[i][0] <= addr < self.secs[i][1]:
            return self.secs[i]
        return None

    def in_code(self, addr):
        return self.code_lo <= addr < self.code_hi

    def tables_above(self, addr, limit=0x40000):
        """Data labels a multiple of 0x1000 above addr: where a folded index points."""
        out = []
        i = bisect.bisect_right(self.by_addr, (addr, ""))
        while i < len(self.by_addr) and self.by_addr[i][0] - addr <= limit:
            a, name = self.by_addr[i]
            own = self.owner(a)
            if (a - addr) % 0x1000 == 0 and own and own[2] != ".text":
                out.append("%s (%+#x)" % (name, addr - a))
            i += 1
        return out


def references(asm_dir):
    """(file, line, op, symbol, addend) for every %hi/%lo and .word with a symbol."""
    for path in sorted(glob.glob(os.path.join(asm_dir, "**", "*.s"), recursive=True)):
        for ln, line in enumerate(open(path), 1):
            s = line.split("*/")[-1] if "*/" in line else line
            s = s.split("#")[0].strip()
            if not s:
                continue
            m = WORD.match(s)
            if m:
                yield path, ln, ".word", m.group(1), int(m.group(3), 0) * (1 if m.group(2) == "+" else -1)
                continue
            if s[0] == "." or s.endswith(":"):
                continue
            op = s.replace(",", " ").split()[0]
            for m in RELOC.finditer(s):
                add = (int(m.group(4), 0) * (1 if m.group(3) == "+" else -1)) if m.group(4) else 0
                yield path, ln, op, m.group(2), add


def check(layout, asm_dir):
    errors, notes = {}, {}
    for path, ln, op, sym, add in references(asm_dir):
        if sym not in layout.labels:
            continue
        anchor = layout.labels[sym]
        target = anchor + add
        oa, ot = layout.owner(anchor), layout.owner(target)
        if oa is None:
            continue
        key = (sym, add, target)
        where = "%s:%d %s" % (os.path.relpath(path, asm_dir), ln, op)
        if oa[2] == ".text" and op in MEMORY and add:
            errors.setdefault(key, ("%s is a function; this %s reads data at %#x" % (sym, op, target), []))[1].append(where)
        elif ot is not None and (ot[3], ot[2]) != (oa[3], oa[2]):
            text = "%s is in %s %s but the address is in %s %s" % (sym, oa[3], oa[2], ot[3], ot[2])
            if add < 0 and add % 0x1000 == 0:
                continue                    # a fold spelled with its table
            if layout.in_code(anchor) or layout.in_code(target):
                errors.setdefault(key, (text, []))[1].append(where)
            else:
                notes.setdefault(key, (text, []))[1].append(where)
    return errors, notes


def main(argv):
    asm_dir, show_all, args = "asm/jp", False, []
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
    layout = Layout(args[0])
    errors, notes = check(layout, asm_dir)
    for (sym, add, target), (text, uses) in sorted(errors.items(), key=lambda kv: kv[0][2]):
        print("ERROR %s%+#x = %#x: %s" % (sym, add, target, text))
        for u in uses:
            print("      %s" % u)
        tables = layout.tables_above(target)
        if tables:
            print("      spell it as: %s" % ", ".join(tables[:3]))
    if show_all:
        for (sym, add, target), (text, uses) in sorted(notes.items(), key=lambda kv: kv[0][2]):
            print("note  %s%+#x = %#x: %s (%d use%s)" % (sym, add, target, text, len(uses), "" if len(uses) == 1 else "s"))
    print("af_anchors: %d wrongly anchored reference%s in code, %d outside it"
          % (len(errors), "" if len(errors) == 1 else "s", len(notes)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
