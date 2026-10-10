#!/usr/bin/env python3
"""lv2data's LZSS on hand-made streams: no dump needed (`make check`)."""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lv2data  # noqa: E402


def stream(length, body):
    return bytes([length & 255, length >> 8]) + bytes(body)


def ref(pos, n):
    w = (pos & 0xFFF) | ((n - 3) << 12)
    return [w & 255, w >> 8]


def test_literals():
    data, used = lv2data.decompress(stream(3, [0b111, 1, 2, 3]), 0)
    assert data == b"\x01\x02\x03" and used == 6, (data, used)


def test_reference_overlaps_its_own_output():
    # "AB" then a reference to window position 0 for 6 bytes: ABABABAB.
    s = stream(8, [0b011, 0x41, 0x42] + ref(0, 6))
    data, used = lv2data.decompress(s, 0)
    assert data == b"ABABABAB", data
    assert used == len(s)


def test_window_starts_zeroed():
    # A reference into the part of the window nothing has written yet.
    data, _ = lv2data.decompress(stream(4, [0b0] + ref(0xFF0, 4)), 0)
    assert data == b"\0\0\0\0", data


def test_length_is_exact_and_stops_mid_reference():
    # The length word is the exact length (the DOS game stores length-1),
    # and a reference longer than what is left is cut short.
    data, _ = lv2data.decompress(stream(5, [0b01, 0x7A] + ref(0, 18)), 0)
    assert data == b"zzzzz", data


def test_window_position_is_output_index_mod_4096():
    # 4096 literals fill the window; the 4097th lands on position 0 again.
    # A 3-byte reference to position 0 then reads that byte back, and each
    # byte it copies is written at positions 1 and 2 before they are read,
    # so all three are the 4097th literal (not literals 0, 1, 2).
    lits = [(i * 7 + i // 256 + 1) & 255 for i in range(4097)]
    body = []
    for i in range(0, 4096, 8):
        body += [0xFF] + lits[i:i + 8]
    body += [0b01, lits[4096]] + ref(0, 3)
    data, _ = lv2data.decompress(stream(4097 + 3, body), 0)
    assert data[:4097] == bytes(lits)
    assert data[4097:] == bytes([lits[4096]] * 3), data[4097:]
    assert lits[4096] != lits[0]


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
    print("lv2data: %d tests passed" % len(tests))


if __name__ == "__main__":
    main()
