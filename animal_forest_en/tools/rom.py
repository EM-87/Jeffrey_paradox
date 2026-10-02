#!/usr/bin/env python3
"""The cartridge: byte order, identity, header checksum.

A Doubutsu no Mori dump comes in one of three byte orders, told apart by
the first word of the header:

    80 37 12 40   .z64, big-endian, the N64's own order (what everything
                  here uses, and what the decomp builds)
    37 80 40 12   .v64, every 16-bit half swapped (Doctor V64)
    40 12 37 80   .n64, every 32-bit word reversed

The file name says nothing: the dump this project started from is called
.n64 and is in .v64 order.

    tools/rom.py normalize IN OUT     any order -> .z64, checked against
                                      the one ROM the decomp builds
    tools/rom.py info ROM             order, md5, header name and CRCs
    tools/rom.py fixcrc ROM           recompute the header CRCs in place
"""

import hashlib
import struct
import sys

# zeldaret/af baseroms/jp/checksum-compressed.md5, and the [!] entry in
# mupen64plus.ini: どうぶつの森 (NUS-NAFJ-JPN), 16 MiB.
BASEROM_MD5 = "a4f7c57c180297b2e7ba5a5feb44fe0b"
BASEROM_SIZE = 0x1000000


def byte_order(data):
    head = bytes(data[:4])
    if head == b"\x80\x37\x12\x40":
        return "z64"
    if head == b"\x37\x80\x40\x12":
        return "v64"
    if head == b"\x40\x12\x37\x80":
        return "n64"
    raise ValueError("not an N64 ROM (first word %s)" % head.hex())


def to_z64(data):
    order = byte_order(data)
    data = bytearray(data)
    if order == "v64":
        data[0::2], data[1::2] = data[1::2], data[0::2]
    elif order == "n64":
        data[0::4], data[1::4], data[2::4], data[3::4] = data[3::4], data[2::4], data[1::4], data[0::4]
    return bytes(data)


def md5(data):
    return hashlib.md5(data).hexdigest()


def load_baserom(path):
    """The user's dump, in .z64 order, refused unless it is the right one."""
    with open(path, "rb") as f:
        data = to_z64(f.read())
    if md5(data) != BASEROM_MD5:
        raise ValueError("%s is not Doubutsu no Mori (J) [!]: md5 %s, want %s"
                         % (path, md5(data), BASEROM_MD5))
    return data


def cic_6102_crc(data):
    """The two header CRCs (0x10, 0x14) over 1 MiB from 0x1000, as the
    6102/7101 boot code checks them, which is the CIC Animal Forest uses.
    Same algorithm as the decomp's tools/compress.py (via ipl3checksum)."""
    seed = 0xF8CA4DDC
    t1 = t2 = t3 = t4 = t5 = t6 = seed
    mask = 0xFFFFFFFF
    words = struct.unpack(">262144I", data[0x1000:0x101000])
    for d in words:
        if (t6 + d) & mask < t6:
            t4 = (t4 + 1) & mask
        t6 = (t6 + d) & mask
        t3 ^= d
        r = ((d << (d & 0x1F)) | (d >> (32 - (d & 0x1F)))) & mask if d & 0x1F else d
        t5 = (t5 + r) & mask
        if t2 > d:
            t2 ^= r
        else:
            t2 ^= t6 ^ d
        t1 = (t1 + (t5 ^ d)) & mask
    return (t6 ^ t4 ^ t3) & mask, (t5 ^ t2 ^ t1) & mask


def fix_crc(data):
    data = bytearray(data)
    crc1, crc2 = cic_6102_crc(data)
    data[0x10:0x18] = struct.pack(">II", crc1, crc2)
    return bytes(data)


def main(argv):
    if len(argv) == 3 and argv[0] == "normalize":
        data = load_baserom(argv[1])
        with open(argv[2], "wb") as f:
            f.write(data)
        print("%s: Doubutsu no Mori (J) [!], md5 %s -> %s" % (argv[1], BASEROM_MD5, argv[2]))
        return 0
    if len(argv) == 2 and argv[0] == "info":
        with open(argv[1], "rb") as f:
            raw = f.read()
        order = byte_order(raw)
        data = to_z64(raw)
        crc1, crc2 = struct.unpack(">II", data[0x10:0x18])
        calc = cic_6102_crc(data)
        print("order %s, size 0x%x, md5 %s (as .z64)" % (order, len(data), md5(data)))
        print("name %r, CRC %08X %08X (%s)" % (data[0x20:0x34].decode("ascii", "replace"), crc1, crc2,
                                              "valid" if calc == (crc1, crc2) else "computed %08X %08X" % calc))
        return 0
    if len(argv) == 2 and argv[0] == "fixcrc":
        with open(argv[1], "rb") as f:
            data = f.read()
        byte_order(data) == "z64" or sys.exit("fixcrc wants a .z64")
        with open(argv[1], "wb") as f:
            f.write(fix_crc(data))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
