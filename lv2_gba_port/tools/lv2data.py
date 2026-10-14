#!/usr/bin/env python3
"""The cartridge's data: the chunk table and its LZSS.

    lv2data.py FILE list                every chunk: offset, sizes
    lv2data.py FILE dump N [OUT]        chunk N, decompressed, to OUT
    lv2data.py FILE dumpall DIR         every chunk to DIR/chunk_NNN.bin

FILE is a SNES dump (LV2 or LV1, same table) or the DOS Lost Vikings'
DATA.DAT, told apart by their tables (below).

The game reaches all its packed data through one table of 4-byte entries at
$8B:8000 (routine $80:B80C): entry N is a 16-bit offset and a 16-bit bank
count from the start of bank $8B, so chunk N starts at file offset
(0x0B + hi) * 0x8000 + lo. The table's first entry points right past the
table, which makes its length (0x554 / 4 = 341 chunks on the USA cartridge).

Each chunk is a 16-bit decompressed length and then an LZSS stream, the
same scheme OpenVikings documents for the DOS Lost Vikings
(tools/lvtools/compression.py there), read off the two decompressors at
$80:B8DE (to any address in WRAM) and $80:BA4A (to VRAM, 4 KB at a time):

  - a flag byte, used LSB first: 1 = one literal byte, 0 = a reference;
  - a reference is a little-endian word: the low 12 bits are an absolute
    position in a 4 KB window, the high 4 bits the length minus 3;
  - the window starts zeroed, and output byte i is written at position
    i mod 4096 ($B8DE clears it with the loop at $B934; $BA4A writes the
    output straight into its window at $7E:2000,X).

One difference from the DOS game: the length word is the exact length here
(the loops count $16EF/$10 down to zero), not length minus one.

The DOS game's DATA.DAT (LV1, US 1.01) keeps the same chunks behind a table
of little-endian 32-bit file offsets at its start: the first offset / 4 is
the count (535), and chunk N ends where N+1 begins (the last at the end of
the file). With the length word read as length - 1, 527 of the 535 streams
end exactly where their chunk does; the other 8 are not streams.
"""

import os
import struct
import sys

TABLE = 0x0B * 0x8000  # $8B:8000


def chunk_offsets(rom):
    first = struct.unpack_from("<H", rom, TABLE)[0]
    n = first // 4
    out = []
    for i in range(n):
        lo, hi = struct.unpack_from("<HH", rom, TABLE + 4 * i)
        out.append((0x0B + hi) * 0x8000 + lo)
    return out


def dos_chunk_offsets(dat):
    """DATA.DAT's table, or None if `dat` does not start with one."""
    if len(dat) < 8:
        return None
    first = struct.unpack_from("<I", dat, 0)[0]
    if first % 4 or not 8 <= first <= len(dat):
        return None
    offs = list(struct.unpack_from("<%dI" % (first // 4), dat, 0))
    if offs[0] != first or any(b < a for a, b in zip(offs, offs[1:])) \
            or offs[-1] > len(dat):
        return None
    return offs


def container(data):
    """-> (kind, chunk start offsets, length bias) for a SNES dump or the
    DOS DATA.DAT. The bias is what the length word is short of the length."""
    offs = dos_chunk_offsets(data)
    if offs is not None:
        return "dos", offs, 1
    return "snes", chunk_offsets(data), 0


def decompress(rom, offset, length_bias=0):
    """-> (data, bytes of stream consumed including the length word).
    length_bias is 0 for the SNES games and 1 for DOS DATA.DAT."""
    size = struct.unpack_from("<H", rom, offset)[0] + length_bias
    src = offset + 2
    window = bytearray(0x1000)
    out = bytearray()
    while len(out) < size:
        flags = rom[src]
        src += 1
        for _ in range(8):
            if len(out) >= size:
                break
            if flags & 1:
                b = rom[src]
                src += 1
                window[len(out) & 0xFFF] = b
                out.append(b)
            else:
                w = rom[src] | (rom[src + 1] << 8)
                src += 2
                pos = w & 0xFFF
                for _ in range((w >> 12) + 3):
                    if len(out) >= size:
                        break
                    b = window[pos]
                    window[len(out) & 0xFFF] = b
                    out.append(b)
                    pos = (pos + 1) & 0xFFF
            flags >>= 1
    return bytes(out), src - offset


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    rom = open(argv[1], "rb").read()
    kind, offs, bias = container(rom)
    cmd = argv[2]
    if cmd == "list":
        print("# %s, %d chunks" % (kind, len(offs)))
        ends = offs[1:] + [len(rom)] if kind == "dos" else offs[1:] + [None]
        for i, o in enumerate(offs):
            try:
                data, used = decompress(rom, o, bias)
            except IndexError:
                print("%3d  %06X  not a stream" % (i, o))
                continue
            nxt = ends[i]
            gap = "" if nxt is None else " gap %d" % (nxt - o - used)
            print("%3d  %06X  packed %5d  size %5d%s" % (
                i, o, used, len(data), gap))
    elif cmd == "dump":
        n = int(argv[3], 0)
        data, _ = decompress(rom, offs[n], bias)
        out = argv[4] if len(argv) > 4 else "chunk_%03d.bin" % n
        open(out, "wb").write(data)
    elif cmd == "dumpall":
        os.makedirs(argv[3], exist_ok=True)
        for i, o in enumerate(offs):
            try:
                data, _ = decompress(rom, o, bias)
            except IndexError:
                continue
            open(os.path.join(argv[3], "chunk_%03d.bin" % i), "wb").write(data)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
