"""Playing Animal Forest from a script: what is on screen, and how to answer.

Pixel tests, measured on angrylion's filtered 640x240 output of both the
original cartridge and the AF Project's ROM (same layout: the 2010 patch
moved text, not windows). Coordinates are in that 640x240 frame.

How the game reads the pad, as found by trying (reference/NOTES.md):
- dialogue advances on A;
- a choice balloon (yellow, right of the dialogue) moves with the stick,
  not the D-pad;
- the name dial types the letter the stick points at when A is pressed
  with the stick held; with the stick centred A does nothing. START ends,
  Z changes the alphabet.
"""

import os

from n64emu import A, START, stick


def pix(n64, x, y):
    w, h, rgb = n64.screen()
    i = (y * w + x) * 3
    return rgb[i], rgb[i + 1], rgb[i + 2]


def dialogue_open(n64):
    """The pale dialogue balloon, low centre."""
    r, g, b = pix(n64, 320, 205)
    return r > 170 and g > 185 and b > 170 and abs(r - b) < 40


def menu_open(n64):
    """The yellow choice balloon at the right of the dialogue."""
    r, g, b = pix(n64, 520, 165)
    return r > 220 and g > 210 and b < 200


def name_dial_open(n64):
    """The name-entry dial: blue ring, yellow pointer below the knob."""
    r, g, b = pix(n64, 270, 195)
    r2, g2, b2 = pix(n64, 320, 205)
    return b > 140 and r < 80 and g < 90 and r2 > 220 and g2 > 220 and b2 < 80


def dark_fraction(n64):
    w, h, rgb = n64.screen()
    step = 3 * 9
    return sum(1 for i in range(0, len(rgb), step) if rgb[i] + rgb[i + 1] + rgb[i + 2] < 60) / (len(rgb) // step)


DIRECTIONS = {"u": stick(0, 80), "d": stick(0, -80), "l": stick(-80, 0), "r": stick(80, 0)}


def walk(n64, direction, frames):
    n64.frames(frames, DIRECTIONS[direction])
    n64.frames(2, 0)


def until(n64, predicate, limit=80, what="condition"):
    """Press A (3 frames, then 22 off) until predicate(n64)."""
    for _ in range(limit):
        if predicate(n64):
            return
        n64.press(A, 3, 22)
    if not predicate(n64):
        raise RuntimeError("no %s after %d presses of A (frame %d)" % (what, limit, n64.frame))


def choose(n64, index):
    """Pick entry `index` (0 = top) of an open choice balloon."""
    n64.frames(10, 0)
    for _ in range(index):
        n64.frames(4, DIRECTIONS["d"])
        n64.frames(8, 0)
    n64.press(A, 3, 20)


def type_on_dial(n64, directions):
    """One letter per direction ("u", "r", "d", "l"), then START."""
    for d in directions:
        v = DIRECTIONS[d]
        n64.frames(8, v)
        n64.frames(6, v | A)
        n64.frames(4, v)
        n64.frames(8, 0)
    n64.press(START, 6, 60)


def presses(n64, count):
    for _ in range(count):
        n64.press(A, 3, 22)


class Shots:
    """Numbered screenshots under a prefix, and a contact sheet of them."""

    def __init__(self, n64, prefix):
        self.n64, self.prefix, self.paths = n64, prefix, []

    def __call__(self):
        path = "%s_%02d.png" % (self.prefix, len(self.paths))
        self.n64.screenshot(path)
        self.paths.append(path)

    def sheet(self, cols=3):
        here = os.path.dirname(os.path.abspath(__file__))
        os.system("python3 %s %s_sheet.png %d %s" % (os.path.join(here, "contact.py"), self.prefix, cols,
                                                     " ".join(self.paths)))
