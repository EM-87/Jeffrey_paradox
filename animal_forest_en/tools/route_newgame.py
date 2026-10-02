#!/usr/bin/env python3
"""Play a new game from power-on to the four houses, saving states.

    tools/route_newgame.py EMU_DIR ROM OUT_DIR

Works on the original cartridge and on the AF Project's ROM (and should on
ours): same screens, same pad. Leaves in OUT_DIR, each with a screenshot:

    1-name.st      the name dial on the train
    2-town.st      the town-name dial
    3-station.st   off the train, the player free on the platform
    4-houses.st    Nook has shown the four houses; the player is free

The emulator is deterministic, so the same ROM gives the same states.
The route was found by hand (reference/NOTES.md); every wait is a test of
what is on screen except the dialogue runs, which press A a generous number
of times (A with nobody to talk to does nothing).
"""

import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
from n64emu import N64, START  # noqa: E402
from afplay import (Shots, dialogue_open, name_dial_open, presses, type_on_dial,  # noqa: E402
                    until, walk)


def main(argv):
    emu_dir, rom_path, out = argv
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    with open(rom_path, "rb") as f:
        rom = f.read()

    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=90) as n64:
        shot = Shots(n64, os.path.join(out, "route"))

        def milestone(name):
            shot()
            frame = n64.save_state(os.path.join(out, name + ".st"))
            print("  %-12s frame %d" % (name, frame))

        n64.frames(400)
        n64.press(START, 3, 60)
        until(n64, name_dial_open, 200, "name dial")      # K.K., the train, Rover
        milestone("1-name")
        type_on_dial(n64, "urld")
        until(n64, name_dial_open, 120, "town dial")
        milestone("2-town")
        type_on_dial(n64, "urld")
        presses(n64, 200)                                  # Rover, Nook on the phone, arrival
        milestone("3-station")
        for _ in range(3):
            walk(n64, "r", 15)
        for _ in range(4):
            walk(n64, "d", 15)
        until(n64, dialogue_open, 40, "Nook")
        presses(n64, 110)                                  # Nook's welcome, the walk to the houses
        if dialogue_open(n64):
            raise RuntimeError("Nook is still talking at frame %d" % n64.frame)
        milestone("4-houses")
        shot.sheet(2)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
