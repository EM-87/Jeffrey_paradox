#!/usr/bin/env python3
"""Boot a ROM in the headless emulator and check it gets where it should.

    tests/emu_check.py EMU_DIR ROM OUT_DIR

Checks, on a fresh cartridge (no save) with the clock at the release day:

  1. frames keep coming and the game reads the controller (it booted);
  2. by frame 400 the title screen is drawn (a picture, not a blank);
  3. for a ROM identical to the original, that picture is exactly the one
     the original draws (an md5 of the pixels, not the pixels: nothing from
     the cartridge is kept in this repository);
  4. START leaves the title for the dark stage of the intro;
  5. the emulator itself: a state saved and loaded replays the very same
     frames, which is what bug hunts from a savestate rely on, and a save
     costs one frame and changes nothing, so a route that saves states on
     its way is the same run on any host.

Screenshots of each step go to OUT_DIR.
"""

import hashlib
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, START  # noqa: E402
import rom as romtool  # noqa: E402

# Frame 400 of the original cartridge: angrylion's filtered VI output with
# emu/build.sh's pinned versions, fresh cartridge, clock at the N64 class's
# default (2001-04-14 12:00:00). Measured, and the same on repeated runs.
TITLE_FRAME = 400
EXPECTED_TITLE = {romtool.BASEROM_MD5: "5eda39514e69467957ec900c2e4355fd"}

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def distinct_colours(rgb):
    return len({rgb[i:i + 3] for i in range(0, len(rgb), 3 * 7)})


def dark_fraction(rgb):
    dark = sum(1 for i in range(0, len(rgb), 3) if rgb[i] + rgb[i + 1] + rgb[i + 2] < 60)
    return dark / (len(rgb) // 3)


def main(argv):
    emu_dir, rom_path, out = argv
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    with open(rom_path, "rb") as f:
        rom = f.read()
    rom_md5 = hashlib.md5(rom).hexdigest()
    print("emu-check: %s (md5 %s)" % (rom_path, rom_md5))

    with N64(emu_dir, rom, os.path.join(out, "run")) as n64:
        n64.frames(TITLE_FRAME)
        check(n64.polls() > 0, "boots: %d frames, controller read %d times" % (n64.frame, n64.polls()))

        n64.screenshot(os.path.join(out, "1-title.png"))
        w, h, rgb = n64.screen()
        check(w > 0 and distinct_colours(rgb) > 200, "title screen drawn (%dx%d, %d colours sampled)"
              % (w, h, distinct_colours(rgb)))

        got = hashlib.md5(rgb).hexdigest()
        if rom_md5 in EXPECTED_TITLE:
            check(got == EXPECTED_TITLE[rom_md5], "title identical to the original cartridge's (%s)" % got)
        else:
            print("  --    title md5 %s (ROM differs from the original; nothing to compare)" % got)

        n64.press(START, 3, 120)
        n64.screenshot(os.path.join(out, "2-after-start.png"))
        dark = dark_fraction(n64.screen()[2])
        check(dark > 0.5, "START leads to the intro's dark stage (%.0f%% dark)" % (dark * 100))

        state = os.path.join(out, "run", "check.st")
        saved = n64.save_state(state)
        n64.to_frame(saved + 30)
        first = hashlib.md5(n64.screen()[2] + n64.read(0x80000000, 0x400000)).hexdigest()
        n64.load_state(state)
        check(n64.frame == saved + 1, "a state saved at frame %d loads back there (frame %d after it)"
              % (saved, n64.frame))
        n64.to_frame(saved + 30)
        again = hashlib.md5(n64.screen()[2] + n64.read(0x80000000, 0x400000)).hexdigest()
        check(first == again, "a loaded state replays the same frames, pixels and RAM")

        # A save costs exactly one frame and changes nothing: the same inputs, counted in frames
        # from here, give the same run with or without a save in the middle (the core writes the
        # file on a thread of its own; frames that ran while it wrote made routes host-dependent).
        def play(save_at=None):
            n64.load_state(state)
            for k in range(30):
                if k == save_at:
                    at = n64.save_state(os.path.join(out, "run", "mid.st"))
                    check(n64.frame == at + 1, "a save takes one frame (saved at %d, now %d)" % (at, n64.frame))
                    continue
                n64.frames(1, START if k % 7 == 3 else 0)
            n64.frames(1, 0)
            return n64.frame, hashlib.md5(n64.screen()[2] + n64.read(0x80000000, 0x400000)).hexdigest()
        plain, with_save = play(), play(save_at=12)
        check(plain == with_save, "a run with a save in it is the run without (frame %d / %d)"
              % (plain[0], with_save[0]))

    print("emu-check: %s" % ("FAILED: " + "; ".join(failures) if failures else "all passed"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
