#!/usr/bin/env python3
"""The animals' names in English, in the cartridge's name segment.

    tools/af_names.py ROM GC_NAME_TABLE OUT.bin [--max-len 6]

The N64 keeps each animal's name in 6 bytes (PLAYER_NAME_LEN, also
ANIMAL_NAME_LEN) in the file at vrom 0xE04000: an 8-byte header, then
name n at 8 + 6 n (`mNpc_LoadNpcNameString`, src/code/m_npc.c). The
GameCube's `forest_2nd.arc/npc_name_str_table.bin` is 236 names of 8
bytes, the first four placeholders: its name n + 4 is the N64's name n
(Bob is ニコバン, Olivia オリビア, Kabuki かぶきち...; measured). This
copies the segment from the dump and writes the English names into it,
in the N64 charset, cut to --max-len (a name longer than that is
reported: the 6-byte field is the N64's own limit, and widening it means
changing the save data's layout). The result goes to the en build as the
segment's .bin: same size, same place, nothing else in the file touched.
"""

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from af_text import ascii_fallback  # noqa: E402
from msgbank import GC_CHARS, N64_BYTES  # noqa: E402

VROM, HEADER, GC_SKIP, GC_LEN = 0xE04000, 8, 4, 8


def segment(rom):
    off = 0x19D40
    while True:
        vs, ve, ps, pe = struct.unpack(">IIII", rom[off:off + 16])
        if (vs, ve, ps, pe) == (0, 0, 0, 0):
            raise ValueError("vrom %#x is not in dmadata" % VROM)
        if vs == VROM:
            if pe:
                raise ValueError("the name segment is compressed in this ROM")
            return rom[ps:ps + (ve - vs)]
        off += 16


def english_names(table):
    names = []
    for i in range(GC_SKIP * GC_LEN, len(table), GC_LEN):
        names.append("".join(GC_CHARS[b] for b in table[i:i + GC_LEN]).rstrip(" "))
    return names


def rename(seg, names, max_len=6):
    out, cut = bytearray(seg), []
    for n, name in enumerate(names):
        at = HEADER + max_len * n
        if at + max_len > len(out):
            break
        plain = ascii_fallback(name)
        if len(plain) > max_len:
            cut.append((n, plain))
            plain = plain[:max_len]
        field = plain.ljust(max_len)
        out[at:at + max_len] = bytes(N64_BYTES[c] for c in field)
    return bytes(out), cut


def main(argv):
    max_len = 6
    if "--max-len" in argv:
        max_len = int(argv[argv.index("--max-len") + 1])
        argv = [a for a in argv if a not in ("--max-len", str(max_len))]
    rom, table, out_path = open(argv[0], "rb").read(), open(argv[1], "rb").read(), argv[2]
    names = english_names(table)
    seg, cut = rename(segment(rom), names, max_len)
    open(out_path, "wb").write(seg)
    for n, name in cut:
        print("  name %d %r cut to %r" % (n, name, name[:max_len]))
    print("%s: %d names written, %d longer than %d" % (out_path, len(names), len(cut), max_len))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
