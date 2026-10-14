#!/usr/bin/env python3
"""The DOS Lost Vikings' floppy, unpacked: DATA.DAT, VIKINGS.EXE, SETUP.EXE.

    lv1dos_unpack.py lost_vikings.img OUTDIR

The disk (US, 1993, one 3.5" HD floppy; archive.org item
lostvikings_us_kryoflux, whose KryoFlux streams decode to the same 2880
sectors as its lost_vikings.img) holds only INSTALL.EXE and VIKINGS.I00.
VIKINGS.I00 is a text banner ("PC Vikings install data file version
1.01"), a few binary fields, and from offset 0x3E an ordinary LHA archive
(-lh5-) of the three files the game runs from. This script reads the FAT12
image itself and hands the archive to `lha` (lhasa), which checks each
member's CRC as it extracts.
"""

import os
import struct
import subprocess
import sys
import tempfile

BANNER = b"\r\nPC Vikings install data file version 1.01\r\n\x1a"
LHA_START = 0x3E


def fat12_files(img):
    """-> {NAME.EXT: bytes} for the root directory of a FAT12 image."""
    bps, spc, reserved, nfats, nroot = struct.unpack_from("<HBHBH", img, 11)
    spf = struct.unpack_from("<H", img, 22)[0]
    root = (reserved + nfats * spf) * bps
    data = root + nroot * 32
    fat = img[reserved * bps:(reserved + spf) * bps]

    def next_cluster(c):
        v = struct.unpack_from("<H", fat, c * 3 // 2)[0]
        return v >> 4 if c & 1 else v & 0xFFF

    files = {}
    for i in range(nroot):
        e = img[root + 32 * i:root + 32 * i + 32]
        if e[0] in (0, 0xE5) or e[11] & 0x18:  # free, deleted, label, dir
            continue
        name = e[0:8].decode("ascii").rstrip()
        ext = e[8:11].decode("ascii").rstrip()
        cluster, size = struct.unpack_from("<HI", e, 26)
        out = bytearray()
        while 2 <= cluster < 0xFF8 and len(out) < size:
            o = data + (cluster - 2) * spc * bps
            out += img[o:o + spc * bps]
            cluster = next_cluster(cluster)
        files[name + ("." + ext if ext else "")] = bytes(out[:size])
    return files


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    img = open(argv[1], "rb").read()
    files = fat12_files(img)
    archive = files.get("VIKINGS.I00")
    if archive is None or not archive.startswith(BANNER):
        print("no VIKINGS.I00 version 1.01 on this disk: %s" % sorted(files))
        return 1
    os.makedirs(argv[2], exist_ok=True)
    out = os.path.abspath(argv[2])
    with tempfile.TemporaryDirectory() as tmp:
        lzh = os.path.join(tmp, "vikings.lzh")
        open(lzh, "wb").write(archive[LHA_START:])
        subprocess.run(["lha", "-xfw=" + out, lzh], check=True,
                       stdout=subprocess.DEVNULL)
    for name in sorted(os.listdir(out)):
        print(name, os.path.getsize(os.path.join(out, name)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
