#!/usr/bin/env python3
"""A TELEVISION BORDER OUT OF THE TITLE'S OWN COLUMNS: the gold ingots and
the green and red jewels that stand either side of the title screen, run
round a 4:3 frame with the GBA's screen in the middle.

    python3 tools/make_border.py title.png [-o border.png] [--preview p.png]

`title.png` is a 240x160 capture of the port's title screen (run_rom or any
emulator, lossless). The border comes out at 640x480 with the GBA's
240x160 doubled to 480x320 in the middle, that window transparent, for an
emulator's overlay or bezel; --preview puts the title itself in it. It is
made of the cartridge's art, so like the rest it is generated, never
committed.
"""
import argparse
import sys

from PIL import Image

T = 16                       # one ingot or jewel


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("title")
    ap.add_argument("-o", "--out", default="border.png")
    ap.add_argument("--preview")
    ap.add_argument("--inside", help="another 240x160 capture for the preview")
    args = ap.parse_args()

    title = Image.open(args.title).convert("RGB")
    if title.size != (240, 160):
        sys.exit("a 240x160 capture of the title screen, please")
    # The left column, measured on the port's title: ingots at 0, 16, 48,
    # 80, 112..., a green jewel at 32 and 96, a red one at 64.
    ingot = title.crop((0, 0, T, T))
    green = title.crop((0, 32, T, 32 + T))
    red = title.crop((0, 64, T, 64 + T))
    jewels = [green, red]

    W, H = 320, 240          # 4:3, at the GBA's own scale
    X0, Y0 = 40, 40          # the screen's corner
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 255))

    # The ring: one column of the title's art all the way round, half a
    # piece clear of the screen (whose own title has the columns at its
    # sides), a jewel every third place and on every corner.
    GAP = 8
    ring_x0, ring_y0 = X0 - T - GAP, Y0 - T - GAP
    ring_x1, ring_y1 = X0 + 240 + GAP, Y0 + 160 + GAP
    n = 0

    def place(img, x, y, rotate=False):
        canvas.paste(img.rotate(90, expand=True) if rotate else img, (x, y))

    xs = list(range(ring_x0, ring_x1, T)) + [ring_x1]
    for x in xs:
        corner = x in (ring_x0, ring_x1)
        for y in (ring_y0, ring_y1):
            if corner:
                place(jewels[(x // T) % 2], x, y)
            else:
                k = (x - ring_x0) // T
                place(jewels[k // 3 % 2] if k % 3 == 0 else ingot, x, y,
                      rotate=True)
    for y in list(range(ring_y0 + T, ring_y1 - T + 1, T)):
        k = (y - ring_y0) // T
        for x in (ring_x0, ring_x1):
            place(jewels[k // 3 % 2] if k % 3 == 0 else ingot, x, y)

    # The window the GBA's picture shows through.
    hole = Image.new("RGBA", (240, 160), (0, 0, 0, 0))
    canvas.paste(hole, (X0, Y0))
    big = canvas.resize((W * 2, H * 2), Image.NEAREST)
    big.save(args.out)
    print(f"{args.out}: {big.size[0]}x{big.size[1]}, window 480x320 at (80,80)")
    if args.preview:
        prev = canvas.copy()
        inside = Image.open(args.inside).convert("RGBA") if args.inside \
            else title.convert("RGBA")
        prev.paste(inside, (X0, Y0))
        prev.resize((W * 2, H * 2), Image.NEAREST).convert("RGB").save(args.preview)
        print(f"{args.preview}: the title in it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
