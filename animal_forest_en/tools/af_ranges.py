#!/usr/bin/env python3
"""Which dmadata entries compress.py must compress, in its own order.

    tools/af_ranges.py MAP > ranges.txt

Run in a decomp checkout. The decomp's compress_ranges.py numbers the
segments in yaml order; compress.py numbers the dmadata entries sorted by
vrom. The two agree only while the yaml order is the ROM order, which
tools/af_relink.py breaks (code goes last). This reads the dmadata table
(include/tables/dmadata_table.h, the names in dmadata order), the yamls
(which names are `compress: True`) and the link map (each name's
`_ROM_START`), sorts by vrom and prints compress.py's `--compress` ranges.
"""

import glob
import itertools
import re
import sys


def dma_names():
    # include/tables/dmadata_table.h selects the version's table
    text = open("include/tables/dmadata_table.h").read()
    inc = re.search(r'#include\s+"([^"]+)"', text).group(1)
    for base in ("include/tables/", "include/"):
        try:
            return re.findall(r"DEFINE_DMA_ENTRY\((\w+),", open(base + inc).read())
        except FileNotFoundError:
            pass
    raise SystemExit("dmadata table %s not found" % inc)


def compressed_names():
    names, name = set(), None
    for y in glob.glob("yamls/*/*.yaml"):
        for line in open(y):
            m = re.match(r"\s*-\s*name:\s*(\S+)", line)
            if m:
                name = m.group(1)
            elif name and re.match(r"\s*compress:\s*True", line):
                names.add(name)
    return names


def rom_starts(map_path):
    starts = {}
    for line in open(map_path):
        m = re.match(r"\s+0x([0-9a-f]+)\s+(\w+)_ROM_START = __romPos", line)
        if m:
            starts[m.group(2)] = int(m.group(1), 16)
    return starts


def main(argv):
    starts = rom_starts(argv[0])
    compressed = compressed_names()
    entries = sorted(dma_names(), key=lambda n: starts[n])
    idx = [i for i, n in enumerate(entries) if n in compressed]
    out = []
    for _, run in itertools.groupby(enumerate(idx), lambda p: p[1] - p[0]):
        run = [k for _, k in run]
        out.append("%d-%d" % (run[0], run[-1]) if len(run) > 1 else "%d" % run[0])
    print(",".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
