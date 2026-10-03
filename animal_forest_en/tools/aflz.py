#!/usr/bin/env python3
"""The save compressor of the translation build, in Python.

    tools/aflz.py pack SAVE_BIN [EXT_BIN] OUT     a slot image as the en build writes it
    tools/aflz.py unpack SLOT_BIN SAVE_OUT [EXT_OUT]

The twin of include/af_lz.h in the en checkout (changes.patch): the same
greedy LZSS in Yaz0's encoding with the same hash chains and tie-breaking,
so it produces the same bytes; tests check both ways, and the en build's
slot images can be read here (from an emulator's flash file) to look at a
save. The slot image (reference/NOTES.md, "The compressed save"): "AFZ1",
the save stream's compressed size, the extension's raw and compressed
sizes and the sum of its bytes (big-endian u32s), then the two streams,
then zeros to the slot's end. A slot without "AFZ1" is in the cartridge's
own format: the save as it is.
"""

import struct
import sys

WINDOW, MAX_LEN, HASH_SIZE, CHAIN = 0x1000, 0x111, 0x1000, 32
MAX_INPUT = 0xFFFF  # positions are 16-bit in the C
MAGIC = 0x41465A31
HEADER = 20
SAVE_SIZE, SLOT_SIZE = 0xF980, 0x10000


def _hash(src, p):
    return ((src[p] << 4) ^ (src[p + 1] << 2) ^ src[p + 2]) & (HASH_SIZE - 1)


def compress(src, cap=None):
    """Bytes, or None if it would pass cap (or src is too long)."""
    size = len(src)
    if size >= MAX_INPUT:
        return None
    head = [-1] * HASH_SIZE
    prev_dist = [0] * WINDOW
    out = bytearray()
    pos = 0

    def insert(p):
        if p + 3 > size:
            return
        h = _hash(src, p)
        prev = head[h]
        prev_dist[p & (WINDOW - 1)] = (p - prev) if (prev >= 0 and p - prev < WINDOW) else 0
        head[h] = p

    while pos < size:
        group_at = len(out)
        out.append(0)
        flags = 0
        bit = 0
        while bit < 8 and pos < size:
            best_len = best_dist = 0
            if pos + 3 <= size:
                cand = head[_hash(src, pos)]
                steps = 0
                while cand >= 0 and pos - cand <= WINDOW and steps < CHAIN:
                    length = 0
                    while length < MAX_LEN and pos + length < size and src[cand + length] == src[pos + length]:
                        length += 1
                    if length > best_len:
                        best_len, best_dist = length, pos - cand
                        if length == MAX_LEN:
                            break
                    d = prev_dist[cand & (WINDOW - 1)]
                    if d == 0:
                        break
                    cand -= d
                    steps += 1
            if best_len >= 3:
                dist = best_dist - 1
                if best_len < 0x12:
                    out += bytes((((best_len - 2) << 4) | (dist >> 8), dist & 0xFF))
                else:
                    out += bytes((dist >> 8, dist & 0xFF, best_len - 0x12))
                for k in range(best_len):
                    insert(pos + k)
                pos += best_len
            else:
                flags |= 0x80 >> bit
                out.append(src[pos])
                insert(pos)
                pos += 1
            bit += 1
        out[group_at] = flags
        if cap is not None and len(out) > cap:
            return None
    return bytes(out)


def decompress(src, size):
    """The size bytes, or None if src is not exactly a stream of them."""
    out = bytearray()
    i = 0
    while len(out) < size:
        if i >= len(src):
            return None
        flags = src[i]
        i += 1
        for bit in range(8):
            if len(out) >= size:
                break
            if flags & (0x80 >> bit):
                if i >= len(src):
                    return None
                out.append(src[i])
                i += 1
            else:
                if i + 2 > len(src):
                    return None
                b0, b1 = src[i], src[i + 1]
                i += 2
                dist = ((b0 & 0xF) << 8 | b1) + 1
                length = b0 >> 4
                if length == 0:
                    if i >= len(src):
                        return None
                    length = src[i] + 0x12
                    i += 1
                else:
                    length += 2
                start = len(out) - dist
                if start < 0 or len(out) + length > size:
                    return None
                for k in range(length):
                    out.append(out[start + k])
    return bytes(out) if i == len(src) else None


def pack(save, ext=b""):
    """The slot image (SLOT_SIZE bytes) and whether it is compressed."""
    save = bytes(save[:SAVE_SIZE])
    ext = bytes(ext)
    cs = compress(save, SLOT_SIZE - HEADER)
    ce = b""
    if cs is not None and ext:
        ce = compress(ext, SLOT_SIZE - HEADER - len(cs))
    if cs is None or ce is None:
        return save + bytes(SLOT_SIZE - SAVE_SIZE), False
    image = struct.pack(">IIIII", MAGIC, len(cs), len(ext), len(ce), sum(ext) & 0xFFFFFFFF) + cs + ce
    return image + bytes(SLOT_SIZE - len(image)), True


def unpack(slot):
    """(save, ext) from a slot image; ext is None when the slot is in the cartridge's format."""
    magic, save_comp, ext_size, ext_comp, ext_sum = struct.unpack(">IIIII", slot[:HEADER])
    if magic != MAGIC:
        return bytes(slot[:SAVE_SIZE]), None
    if HEADER + save_comp + ext_comp > len(slot):
        raise ValueError("slot sizes run past the slot")
    save = decompress(slot[HEADER:HEADER + save_comp], SAVE_SIZE)
    if save is None:
        raise ValueError("the save stream is damaged")
    ext = b""
    if ext_size:
        ext = decompress(slot[HEADER + save_comp:HEADER + save_comp + ext_comp], ext_size)
        if ext is None or sum(ext) & 0xFFFFFFFF != ext_sum:
            raise ValueError("the extension stream is damaged")
    return save, ext


def main(argv):
    if argv[0] == "pack":
        save = open(argv[1], "rb").read()
        ext = open(argv[2], "rb").read() if len(argv) > 3 else b""
        image, compressed = pack(save, ext)
        open(argv[-1], "wb").write(image)
        print("%s: %s" % (argv[-1], "compressed" if compressed else "the cartridge's format (did not fit)"))
    elif argv[0] == "unpack":
        save, ext = unpack(open(argv[1], "rb").read())
        open(argv[2], "wb").write(save)
        if len(argv) > 3 and ext is not None:
            open(argv[3], "wb").write(ext)
        print("save %d bytes, extension %s" % (len(save), "none (cartridge format)" if ext is None else "%d bytes" % len(ext)))
    else:
        raise SystemExit(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
