#!/usr/bin/env python3
"""Apply a UPS patch (byuu's format), checking all three CRC32s.

Only here for the AF Project's patch (NAFE-WIP-2_12_2010.ups), which is
UPS because its text sits past 16 MiB, beyond IPS's reach. Its own notes
("BEFORE YOU EVEN THINK OF PATCHING!.txt") say to apply it to an
unswapped ROM extended from 0x1000000 to 0x2000000; the input CRC in the
patch says with what (tools/nafe.py tries the candidates).

    tools/ups.py PATCH IN OUT

Format: "UPS1", varint input size, varint output size, then hunks of
(varint bytes to skip, XOR bytes up to and including a 0x00), then CRC32
of input, of output, of the patch up to that point.
"""

import struct
import sys
import zlib


def _varint(buf, pos):
    value, shift = 0, 1
    while True:
        x = buf[pos]
        pos += 1
        value += (x & 0x7F) * shift
        if x & 0x80:
            return value, pos
        shift <<= 7
        value += shift


def header(patch):
    if patch[:4] != b"UPS1":
        raise ValueError("not a UPS patch")
    in_size, pos = _varint(patch, 4)
    out_size, pos = _varint(patch, pos)
    in_crc, out_crc, patch_crc = struct.unpack("<III", patch[-12:])
    return in_size, out_size, in_crc, out_crc, patch_crc, pos


def apply(patch, source):
    in_size, out_size, in_crc, out_crc, patch_crc, pos = header(patch)
    if zlib.crc32(patch[:-4]) & 0xFFFFFFFF != patch_crc:
        raise ValueError("the patch is damaged (CRC32)")
    if len(source) != in_size or zlib.crc32(source) & 0xFFFFFFFF != in_crc:
        raise ValueError("wrong input: size 0x%x crc %08x, the patch wants size 0x%x crc %08x"
                         % (len(source), zlib.crc32(source) & 0xFFFFFFFF, in_size, in_crc))
    out = bytearray(source[:out_size].ljust(out_size, b"\0"))
    at = 0
    end = len(patch) - 12
    while pos < end:
        skip, pos = _varint(patch, pos)
        at += skip
        while True:
            x = patch[pos]
            pos += 1
            if at < out_size:
                out[at] ^= x
            at += 1
            if x == 0:
                break
    if zlib.crc32(out) & 0xFFFFFFFF != out_crc:
        raise ValueError("patched output fails its CRC32")
    return bytes(out)


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    with open(argv[0], "rb") as f:
        patch = f.read()
    with open(argv[1], "rb") as f:
        source = f.read()
    out = apply(patch, source)
    with open(argv[2], "wb") as f:
        f.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
