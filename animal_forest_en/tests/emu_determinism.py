#!/usr/bin/env python3
"""Check that two runs of the same inputs are the same machine, frame by frame.

    tests/emu_determinism.py EMU_DIR ROM OUT_DIR [--parallel]

Plays the opening of tools/route_newgame.py (power-on, START, A until the
name dial: about 3,300 frames) in two processes at the same time and
compares all of RDRAM after every frame from FIRST on, then saves a state
at the dial and compares the two states' contents. Any difference is a bug
in the emulator or its frontend (CLAUDE.md, rule 5): the first frame that
differs and where in RAM are printed.

What it has caught (reference/NOTES.md, "The emulator is deterministic"):
angrylion's render workers racing on texture loads (two runs first differed
at frame 3256, in the framebuffer the game copies before the name dial),
and save_state() letting frames run while the core wrote the file.
--parallel renders with angrylion's workers, to see the first again.
"""

import gzip
import hashlib
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))

FIRST = 380
FB, FB_SIZE = 0x3DA800, 0x25800          # Animal Forest's framebuffer, below the 4 MB mark
RDRAM_SIZE = 0x800000


def one(emu_dir, rom_path, out, parallel):
    """One run: a hash of RDRAM per frame, RDRAM at the end, a state."""
    import ctypes
    import n64emu
    from n64emu import N64, START
    from afplay import name_dial_open, until

    if parallel:
        set_param = N64._set

        def _set(self, section, key, value, kind=n64emu.M64TYPE_INT):
            if key == b"Parallel":
                value = True
            if key == b"NumWorkers":
                value = 0
            return set_param(self, section, key, value, kind)
        N64._set = _set

    rom = open(rom_path, "rb").read()
    hashes = {}
    with N64(emu_dir, rom, os.path.join(out, "cart")) as n64:
        step = n64.frames

        def frames(n=1, buttons=None):
            if buttons is not None:
                n64.hold(buttons)
            for _ in range(n):
                step(1)
                if n64.frame >= FIRST:
                    hashes[n64.frame] = hashlib.md5(ctypes.string_at(n64._rdram_ptr(), RDRAM_SIZE)).hexdigest()
        n64.frames = frames
        n64.frames(400)
        n64.press(START, 3, 60)
        until(n64, name_dial_open, 200, "name dial")
        n64.frames = step
        open(os.path.join(out, "ram.bin"), "wb").write(ctypes.string_at(n64._rdram_ptr(), RDRAM_SIZE))
        n64.save_state(os.path.join(out, "dial.st"))
    json.dump(hashes, open(os.path.join(out, "hashes.json"), "w"))


def main(argv):
    if argv and argv[0] == "--one":
        one(argv[1], argv[2], argv[3], argv[4] == "1")
        return 0
    parallel = "--parallel" in argv
    emu_dir, rom_path, out = [a for a in argv if a != "--parallel"]
    shutil.rmtree(out, ignore_errors=True)
    runs = [os.path.join(out, name) for name in ("a", "b")]
    procs = []
    for run in runs:
        os.makedirs(run)
        procs.append(subprocess.Popen([sys.executable, os.path.abspath(__file__), "--one", emu_dir, rom_path, run,
                                       "1" if parallel else "0"], stdout=subprocess.DEVNULL))
    if any(p.wait() for p in procs):
        print("emu-determinism: a run failed")
        return 1
    a, b = (json.load(open(os.path.join(run, "hashes.json"))) for run in runs)
    frames = sorted(set(a) | set(b), key=int)
    differ = [f for f in frames if a.get(f) != b.get(f)]
    failures = []
    print("emu-determinism: %s, %d frames from %d compared%s" % (rom_path, len(frames), FIRST,
                                                                 " (angrylion's workers)" if parallel else ""))
    if differ:
        ram = [open(os.path.join(run, "ram.bin"), "rb").read() for run in runs]
        words = [i for i in range(0, RDRAM_SIZE, 4) if ram[0][i:i + 4] != ram[1][i:i + 4]]
        in_fb = sum(1 for i in words if FB <= i < FB + FB_SIZE)
        print("  FAIL  the runs part at frame %s; at the dial %d words of RAM differ, %d in the framebuffer"
              % (differ[0], len(words), in_fb))
        failures.append("RAM")
    else:
        print("  ok    RAM the same after every frame (%d frames, to the name dial)" % len(frames))
    states = [hashlib.md5(gzip.open(os.path.join(run, "dial.st")).read()).hexdigest() for run in runs]
    if states[0] == states[1]:
        print("  ok    the states saved at the dial are the same")
    else:
        print("  FAIL  the states saved at the dial differ")
        failures.append("state")
    print("emu-determinism: %s" % ("all passed" if not failures else "FAILED: " + ", ".join(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
