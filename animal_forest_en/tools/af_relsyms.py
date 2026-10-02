#!/usr/bin/env python3
"""Unpin the RAM addresses splat left absolute, so that code can move.

    tools/af_relsyms.py MAP AUTO_LD [--from 0x80051A80 --to 0x801948E0]

Run in a decomp checkout after a link with the cartridge's layout (MAP is
its map file) and before relinking with code grown or moved. splat writes
`linker_scripts/jp/auto/undefined_syms_auto.ld` with one `SYM = 0xADDR;`
per address it saw referenced but gave no label to: 22 of them point into
code's data (a byte inside a table, `D_80104509_jp`, or a label it also
emitted, `D_80107B75_jp`). A linker-script assignment wins over an object's
label, so after a shift those symbols would still name the old addresses.

For each assignment inside [--from, --to) (code and buffers by default):
  - if the map has a label at that very address, the line is dropped: the
    label (relative, from the object) defines the symbol;
  - otherwise it becomes `SYM = PARENT + off;`, PARENT the nearest label
    below it in the map.
Everything else in the file is kept. With the cartridge's layout the values
are the same, so the matching build does not change (make verify).
"""

import bisect
import re
import sys


def labels(map_path):
    out = []
    for line in open(map_path):
        # "  0x80104508   D_80104508_jp" — a label; assignments show "= ...".
        m = re.match(r"\s+0x([0-9a-f]{8,16})\s+([A-Za-z_]\w*)$", line)
        if m and not m.group(2).endswith(".NON_MATCHING"):
            out.append((int(m.group(1), 16), m.group(2)))
    out.sort()
    return out


def main(argv):
    map_path, ld_path = argv[0], argv[1]
    lo = int(argv[argv.index("--from") + 1], 0) if "--from" in argv else 0x80051A80
    hi = int(argv[argv.index("--to") + 1], 0) if "--to" in argv else 0x801948E0
    labs = labels(map_path)
    addrs = [a for a, _ in labs]
    at = {}
    for a, n in labs:
        at.setdefault(a, n)
    out, dropped, rel = [], 0, 0
    for line in open(ld_path):
        m = re.match(r"\s*(\w+)\s*=\s*0x([0-9A-Fa-f]+);\s*(//.*)?$", line)
        if m:
            name, addr = m.group(1), int(m.group(2), 16)
            if lo <= addr < hi:
                if addr in at and at[addr] != name:
                    # another label at the same address: alias it
                    out.append("%s = %s; /* af_relsyms: was 0x%X */\n" % (name, at[addr], addr))
                    rel += 1
                    continue
                if addr in at:
                    dropped += 1
                    continue
                i = bisect.bisect_right(addrs, addr) - 1
                if i < 0:
                    raise SystemExit("%s: no label below 0x%X" % (name, addr))
                pa, pn = labs[i]
                out.append("%s = %s + 0x%X; /* af_relsyms: was 0x%X */\n" % (name, pn, addr - pa, addr))
                rel += 1
                continue
        out.append(line)
    open(ld_path, "w").write("".join(out))
    print("af_relsyms: %d pins dropped (a label defines them), %d made relative" % (dropped, rel))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
