"""Several screenshots on one sheet, to look at a sequence at once.

    python3 emu/contact.py OUT.png COLUMNS IN1.png IN2.png ...

Each picture is halved horizontally (angrylion's VI output is 640 wide for
a 320-pixel line), so a sheet of four is still easy to read.
"""
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from n64emu import write_png  # noqa: E402


def read_png(path):
    with open(path, "rb") as f:
        d = f.read()
    i, idat = 8, b""
    while i < len(d):
        n = struct.unpack(">I", d[i:i + 4])[0]
        tag = d[i + 4:i + 8]
        if tag == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", d[i + 8:i + 18])
            if depth != 8 or ctype != 2:
                raise ValueError("only 8-bit RGB PNGs (what write_png makes)")
        elif tag == b"IDAT":
            idat += d[i + 8:i + 8 + n]
        i += 12 + n
    raw = zlib.decompress(idat)
    stride = w * 3 + 1
    rows = []
    for y in range(h):
        if raw[y * stride] != 0:
            raise ValueError("only unfiltered PNGs (what write_png makes)")
        rows.append(raw[y * stride + 1:(y + 1) * stride])
    return w, h, b"".join(rows)


def main(argv):
    out, cols, paths = argv[0], int(argv[1]), argv[2:]
    pics = [read_png(p) for p in paths]
    w = max(p[0] for p in pics) // 2
    h = max(p[1] for p in pics)
    rows = (len(pics) + cols - 1) // cols
    gap = 4
    W, H = cols * (w + gap), rows * (h + gap)
    sheet = bytearray(b"\x40\x40\x40" * W * H)
    for k, (pw, ph, rgb) in enumerate(pics):
        ox, oy = (k % cols) * (w + gap), (k // cols) * (h + gap)
        for y in range(ph):
            for x in range(pw // 2):
                s = (y * pw + x * 2) * 3
                d = ((oy + y) * W + ox + x) * 3
                sheet[d:d + 3] = rgb[s:s + 3]
    write_png(out, W, H, bytes(sheet))


if __name__ == "__main__":
    main(sys.argv[1:])
