#!/usr/bin/env python3
"""What the AF Project's 2010 patch changed, file by file and function by
function, without playing a minute of it.

    tools/nafe_diff.py BASEROM NAFE_ROM MAPFILE [--bytes]

Both ROMs are read through their dmadata (the file table at 0x19D40):
every file is decompressed (Yaz0) and compared with its counterpart, so a
one-byte change inside a compressed file shows as one byte, not as the
whole file recompressed. Changes in code are named with the decomp's linker
map (MAPFILE: build/af/build/animalforest-jp.map): segment, then function
or variable. Anything the patch added past the original's last file is
summarised by range.
"""

import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
from symbols import Symbols  # noqa: E402

DMADATA = 0x19D40  # zeldaret/af baseroms/jp/dmadata_start.txt


def yaz0(src):
    if src[:4] != b"Yaz0":
        raise ValueError("not Yaz0")
    size = struct.unpack(">I", src[4:8])[0]
    out = bytearray()
    i = 16
    while len(out) < size:
        code = src[i]
        i += 1
        for bit in range(8):
            if len(out) >= size:
                break
            if code & (0x80 >> bit):
                out.append(src[i])
                i += 1
            else:
                b1, b2 = src[i], src[i + 1]
                i += 2
                dist = ((b1 & 0xF) << 8 | b2) + 1
                count = b1 >> 4
                if count == 0:
                    count = src[i] + 0x12
                    i += 1
                else:
                    count += 2
                start = len(out) - dist
                for k in range(count):
                    out.append(out[start + k])
    return bytes(out)


def dma_entries(rom):
    out = []
    at = DMADATA
    while True:
        e = struct.unpack(">IIII", rom[at:at + 16])
        if e == (0, 0, 0, 0):
            return out
        out.append(e)
        at += 16


def file_data(rom, entry):
    vstart, vend, pstart, pend = entry
    if pstart == 0xFFFFFFFF:
        return None  # "syms": zeros, not used by the game
    if pend:
        return yaz0(rom[pstart:pend])
    return rom[pstart:pstart + vend - vstart]


class Segments:
    """vrom offset -> (segment, vram) from the map's X_ROM_START/X_VRAM."""

    def __init__(self, mapfile):
        rom, vram, end = {}, {}, {}
        pat = re.compile(r"\s+0x([0-9a-fA-F]+)\s+(\w+)_(ROM_START|ROM_END|VRAM)\s*=")
        with open(mapfile, errors="replace") as f:
            for line in f:
                m = pat.match(line)
                if m:
                    {"ROM_START": rom, "ROM_END": end, "VRAM": vram}[m.group(3)][m.group(2)] = int(m.group(1), 16)
        self.segs = sorted((rom[n], end.get(n, rom[n]), vram.get(n), n) for n in rom if n in end)

    def at(self, vrom):
        best = None
        for start, stop, vram, name in self.segs:
            if start <= vrom < stop and (best is None or stop - start < best[1] - best[0]):
                best = (start, stop, vram, name)
        if not best:
            return None, None
        start, _, vram, name = best
        return name, (vram + vrom - start) if vram else None


def runs(a, b):
    """[start, end) ranges where a and b differ (a, b same length)."""
    out, i, n = [], 0, min(len(a), len(b))
    while i < n:
        if a[i] != b[i]:
            j = i
            while j < n and (a[j] != b[j] or (j + 8 < n and a[j:j + 8] != b[j:j + 8])):
                j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    if len(a) != len(b):
        out.append((n, max(len(a), len(b))))
    return out


def main(argv):
    show_bytes = "--bytes" in argv
    argv = [a for a in argv if a != "--bytes"]
    base_path, nafe_path, mapfile = argv
    base = open(base_path, "rb").read()
    nafe = open(nafe_path, "rb").read()
    syms = Symbols(mapfile)
    segs = Segments(mapfile)

    eb, en = dma_entries(base), dma_entries(nafe)
    print("dmadata: original %d files, NAFE %d" % (len(eb), len(en)))
    # The game asks for files by vrom address (the AF Project's notes call
    # it the "codeword"), so files are paired by vrom start, not by index:
    # the patch reordered the table and reused the 16-byte placeholder
    # entries for its new text banks.
    by_vrom = {e[0]: e for e in en}
    total = 0
    for b in eb:
        n = by_vrom.pop(b[0], None)
        db = file_data(base, b)
        dn = file_data(nafe, n) if n else None
        if db is None and dn is None:
            continue
        if n is None:
            print("\nvrom %08x %s: gone from NAFE's table" % (b[0], segs.at(b[0])[0]))
            continue
        if db == dn:
            continue
        diff = runs(db or b"", dn or b"")
        size = sum(e - s for s, e in diff)
        total += size
        seg, _ = segs.at(b[0])
        print("\nvrom %08x %s: %d bytes in %d places, size %x -> %x"
              % (b[0], seg or "?", size, len(diff), len(db or b""), len(dn or b"")))
        for s, e in diff[:80]:
            name, vram = segs.at(b[0] + s)
            where = syms.at(vram) if vram else "vrom %08x" % (b[0] + s)
            line = "  +%06x..%06x (%5d)  %s" % (s, e, e - s, where)
            if show_bytes and e - s <= 32:
                line += "  %s -> %s" % ((db or b"")[s:e].hex(), (dn or b"")[s:e].hex())
            print(line)
        if len(diff) > 80:
            print("  ... %d more" % (len(diff) - 80))
    for v, n in sorted(by_vrom.items()):
        if n[2] == 0xFFFFFFFF:
            continue
        print("\nvrom %08x: NEW in NAFE, %x bytes at rom %08x" % (v, n[1] - n[0], n[2]))
    phys_end = max((e[3] if e[3] else e[2] + e[1] - e[0]) for e in eb if e[2] != 0xFFFFFFFF)
    print("\noriginal ROM data ends at %08x; NAFE has %d non-zero bytes after it"
          % (phys_end, sum(1 for x in nafe[phys_end:] if x)))
    print("changed inside original files: %d bytes" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
