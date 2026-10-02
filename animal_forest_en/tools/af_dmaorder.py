#!/usr/bin/env python3
"""Put a compressed ROM's dmadata back in the table's own order.

    tools/af_dmaorder.py COMPRESSED_ROM UNCOMPRESSED_ROM --dma-start 0x19D40

The decomp's compress.py sorts the dmadata entries by vrom before it lays
the files out, and writes the table back in that sorted order. On the
cartridge the table order is the vrom order, so that changes nothing;
once tools/af_relink.py has moved `code` to the end of the vrom space,
code's entry moves from the 7th slot to the last and every entry after it
slides up one. Nothing in the game indexes the table (the DMA manager's
index functions have no callers, and a ROM with the shuffled table
behaved exactly like one with it restored), but the table is a thing
other tools read (ours, nafe_diff.py, anyone's), so this rewrites the
compressed ROM's table in the order the linked (uncompressed) ROM has it,
matching entries by vrom. The header checksum is not touched: run
tools/rom.py fixcrc afterwards.
"""

import struct
import sys

ENTRY = struct.Struct(">IIII")


def entries(data, start):
    out, off = [], start
    while True:
        e = ENTRY.unpack_from(data, off)
        if e == (0, 0, 0, 0):
            return out
        out.append(e)
        off += ENTRY.size


def main(argv):
    rom_path, ref_path = argv[0], argv[1]
    start = int(argv[argv.index("--dma-start") + 1], 0)
    rom = bytearray(open(rom_path, "rb").read())
    ref = open(ref_path, "rb").read()
    table = entries(ref, start)
    mine = {e[0]: e for e in entries(rom, start)}
    if len(mine) != len(table):
        raise SystemExit("dmadata: %d entries in %s, %d in %s" % (len(mine), rom_path, len(table), ref_path))
    moved = 0
    for i, e in enumerate(table):
        new = mine.pop(e[0])
        if ENTRY.unpack_from(rom, start + i * ENTRY.size) != new:
            moved += 1
        ENTRY.pack_into(rom, start + i * ENTRY.size, *new)
    if mine:
        raise SystemExit("dmadata: vroms not in the table: %s" % ", ".join("%#x" % v for v in mine))
    open(rom_path, "wb").write(rom)
    print("af_dmaorder: %d entries, %d moved back into the table's order" % (len(table), moved))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
