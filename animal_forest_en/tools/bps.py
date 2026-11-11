#!/usr/bin/env python3
"""The deliverable: a BPS patch (byuu's format) from the user's dump to the en build's ROM.

    tools/bps.py create SOURCE TARGET OUT.bps [--metadata TEXT]
    tools/bps.py apply  SOURCE PATCH.bps OUT

Format: "BPS1", varint source size, target size, metadata size, the
metadata; then actions until the last 12 bytes, each a varint of
(length - 1) << 2 | kind: 0 SourceRead (the source's bytes at the
output's own offset), 1 TargetRead (the bytes follow), 2 SourceCopy and 3
TargetCopy (a signed varint moves a cursor in the source, or in the
target written so far, by |d| << 1 | sign; the copy runs from there and the
cursor moves past it). Then CRC32 of the source, of the target, and of
the patch up to there, little-endian. apply checks all three.

create finds the target's runs in the source: the en build keeps most of
the cartridge's segments as they were, at other places (code moved to the
end, others after it shifted; reference/NOTES.md, "Shiftability"), so the
source is indexed by its 32 bytes at every 16th offset, the target looked
up at every offset not yet covered (a run moved by any amount meets the
index within 16 bytes) and each hit grown both ways as far as the bytes
agree. What no run covers is written as it is, a run of one byte as that
byte and a copy of the target's previous one. create applies the patch it
made before writing it, so a patch that does not give the target back is
never written. On the ROMs: 3.1 MB for the 24 MiB en ROM, most of it the
new text and code.
"""

import re
import struct
import sys
import zlib

BLOCK, STEP, MIN_RUN = 32, 16, 32
REPEAT = re.compile(rb"(.)\1{31,}", re.S)      # a byte 32 times or more: written once and copied on


def varint(value):
    out = bytearray()
    while True:
        x = value & 0x7F
        value >>= 7
        if value == 0:
            out.append(0x80 | x)
            return bytes(out)
        out.append(x)
        value -= 1


def read_varint(buf, pos):
    value, shift = 0, 1
    while True:
        x = buf[pos]
        pos += 1
        value += (x & 0x7F) * shift
        if x & 0x80:
            return value, pos
        shift <<= 7
        value += shift


def signed(delta):
    return varint(abs(delta) << 1 | (delta < 0))


def common(a, i, b, j, limit):
    """How many bytes a[i:] and b[j:] share, at most limit."""
    n = 0
    chunk = 4096
    while n < limit:
        size = min(chunk, limit - n)
        if a[i + n:i + n + size] == b[j + n:j + n + size]:
            n += size
            continue
        lo, hi = 0, size                            # the first difference in this chunk
        while lo < hi:
            mid = (lo + hi) // 2
            if a[i + n:i + n + mid + 1] == b[j + n:j + n + mid + 1]:
                lo = mid + 1
            else:
                hi = mid
        return n + lo
    return n


def common_back(a, i, b, j, limit):
    """How many bytes before a[i] and b[j] agree, at most limit."""
    n = 0
    while n < limit and a[i - n - 1] == b[j - n - 1]:
        n += 1
    return n


class Writer:
    def __init__(self, source, target, metadata):
        self.out = bytearray(b"BPS1" + varint(len(source)) + varint(len(target)) + varint(len(metadata)) + metadata)
        self.source, self.target = source, target
        self.at = 0                                  # the target written so far
        self.src_cursor = self.tgt_cursor = 0
        self.counts = {"source read": 0, "source copy": 0, "target read": 0, "target copy": 0}

    def action(self, kind, length):
        self.out += varint((length - 1) << 2 | kind)

    def source_run(self, s, length):
        if s == self.at:
            self.action(0, length)
            self.counts["source read"] += length
        else:
            self.action(2, length)
            self.out += signed(s - self.src_cursor)
            self.src_cursor = s + length
            self.counts["source copy"] += length
        self.at += length

    def literal(self, end):
        """The target from where it is written up to end, as it is (runs of one byte copied on)."""
        data = self.target[self.at:end]
        pos = 0
        for m in REPEAT.finditer(data):
            self._read(data[pos:m.start() + 1])      # up to and including the run's first byte
            run = m.end() - m.start() - 1            # the rest of the run: a copy of the byte before it
            self.action(3, run)
            self.out += signed(self.at - 1 - self.tgt_cursor)
            self.tgt_cursor = self.at - 1 + run
            self.at += run
            self.counts["target copy"] += run
            pos = m.end()
        self._read(data[pos:])

    def _read(self, data):
        if data:
            self.action(1, len(data))
            self.out += data
            self.at += len(data)
            self.counts["target read"] += len(data)

    def finish(self):
        self.out += struct.pack("<II", zlib.crc32(self.source) & 0xFFFFFFFF, zlib.crc32(self.target) & 0xFFFFFFFF)
        self.out += struct.pack("<I", zlib.crc32(self.out) & 0xFFFFFFFF)
        return bytes(self.out)


def create(source, target, metadata=b""):
    """A BPS patch that makes target from source; and what it does, in bytes of each kind."""
    index = {}
    for s in range(0, len(source) - BLOCK + 1, STEP):
        index.setdefault(source[s:s + BLOCK], s)
    w = Writer(source, target, metadata)
    t = 0
    while t + BLOCK <= len(target):
        s = index.get(target[t:t + BLOCK])
        if s is not None:
            if source[t:t + BLOCK] == target[t:t + BLOCK]:
                s = t                                # the same place: no offset to write
            back = common_back(source, s, target, t, min(s, t - w.at))
            length = back + common(source, s, target, t, min(len(source) - s, len(target) - t))
            if length >= MIN_RUN:
                w.literal(t - back)
                w.source_run(s - back, length)
                t = w.at
                continue
        t += 1                                       # any offset: a run moved by other than 16 is met too
    w.literal(len(target))
    return w.finish(), w.counts


def apply(source, patch):
    if patch[:4] != b"BPS1":
        raise ValueError("not a BPS patch")
    src_size, pos = read_varint(patch, 4)
    tgt_size, pos = read_varint(patch, pos)
    meta_size, pos = read_varint(patch, pos)
    pos += meta_size
    src_crc, tgt_crc, patch_crc = struct.unpack("<III", patch[-12:])
    if zlib.crc32(patch[:-4]) & 0xFFFFFFFF != patch_crc:
        raise ValueError("the patch's own CRC32 does not match: the file is damaged")
    if len(source) != src_size or zlib.crc32(source) & 0xFFFFFFFF != src_crc:
        raise ValueError("this is not the ROM the patch is for (size %d, CRC32 %08X; the patch wants %d, %08X)"
                         % (len(source), zlib.crc32(source) & 0xFFFFFFFF, src_size, src_crc))
    out = bytearray()
    src_cursor = tgt_cursor = 0
    end = len(patch) - 12
    while pos < end:
        data, pos = read_varint(patch, pos)
        kind, length = data & 3, (data >> 2) + 1
        if kind == 0:
            out += source[len(out):len(out) + length]
        elif kind == 1:
            out += patch[pos:pos + length]
            pos += length
        else:
            d, pos = read_varint(patch, pos)
            delta = -(d >> 1) if d & 1 else d >> 1
            if kind == 2:
                src_cursor += delta
                out += source[src_cursor:src_cursor + length]
                src_cursor += length
            else:
                tgt_cursor += delta
                for _ in range(length):              # may overlap what it writes: byte by byte
                    out.append(out[tgt_cursor])
                    tgt_cursor += 1
    if len(out) != tgt_size or zlib.crc32(out) & 0xFFFFFFFF != tgt_crc:
        raise ValueError("the result is not the patch's target")
    return bytes(out)


def main(argv):
    if len(argv) >= 4 and argv[0] == "create":
        metadata = b""
        if "--metadata" in argv:
            metadata = argv[argv.index("--metadata") + 1].encode("utf-8")
        source, target = open(argv[1], "rb").read(), open(argv[2], "rb").read()
        patch, counts = create(source, target, metadata)
        apply(source, patch)                          # never write a patch that does not give the target back
        open(argv[3], "wb").write(patch)
        print("%s: %d bytes (%s), from %s to %s" % (argv[3], len(patch), ", ".join(
            "%s %d" % (k, v) for k, v in counts.items()), argv[1], argv[2]))
        return 0
    if len(argv) == 4 and argv[0] == "apply":
        out = apply(open(argv[1], "rb").read(), open(argv[2], "rb").read())
        open(argv[3], "wb").write(out)
        print("%s: %d bytes, CRC32 %08X" % (argv[3], len(out), zlib.crc32(out) & 0xFFFFFFFF))
        return 0
    raise SystemExit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
