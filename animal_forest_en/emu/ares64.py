"""The Nintendo 64 in ares, driven one game frame at a time from Python: the second emulator.

emu/ares/build.sh builds ares-headless (emu/ares/headless.cpp): ares's own N64 core, with its recompilers,
its cycle-counted RCP and its emulated CPU caches, and paraLLEl-RDP on Vulkan (lavapipe without a GPU). This
class gives it the interface of n64emu.N64, so emu/afplay.py's screen tests and tools/route_newgame.py's
route play on it too:

    with Ares64("build/ares", rom_bytes, "build/run-ares") as n64:
        n64.frames(300)
        n64.press(START)
        n64.screenshot("title.png")

A frame is one of the game's (gamePT->frameCounter steps, read through the CPU's data cache), as mupen64plus's
frame callback counts one finished graphics task: Animal Forest renders at 30 fps, two VI fields a frame.

The cartridge is set up by ares's mia from the ROM's header, as the ares app sets it up: Doubutsu no Mori
(id NAF) gets 128 KB of Flash RAM, the real-time clock and a Controller Pak; its saves sit in workdir as
game.flash, game.rtc and game.pak. The clock is ares's: it starts from the host's time and keeps its own
from the .rtc file, as a cartridge's battery would; a run is not repeatable to the frame, as mupen's is.

screen() gives the frame in afplay's coordinates (640x240: ares's 640x480 every other line), screenshot()
ares's whole frame.
"""

import os
import shutil
import struct
import subprocess

from n64emu import write_png

GAME_PT = 0x8010EF90         # Game* gamePT (src/code/game.c), the same in the cartridge and the en build
FRAME_COUNTER = 0xA0         # Game.frameCounter (include/game.h)


class Hang(Exception):
    pass


class Ares64:
    def __init__(self, ares_dir, rom, workdir, timeout=None, interpreter=False, hang_vis=900,
                 counter=(GAME_PT, FRAME_COUNTER), verbose=False):
        """ares_dir: emu/ares/build.sh's output. rom: big-endian (.z64) bytes. workdir: the cartridge's saves
        go there (game.flash, game.rtc, game.pak): delete it for a new cartridge, keep it to load the save.
        timeout is accepted for n64emu.N64's signature; a hang here is hang_vis VI fields without a frame."""
        self.ares_dir = os.path.abspath(ares_dir)
        self.workdir = os.path.abspath(workdir)
        os.makedirs(self.workdir, exist_ok=True)
        self.rom_path = os.path.join(self.workdir, "game.z64")
        with open(self.rom_path, "wb") as f:
            f.write(rom)
        self.args = [os.path.join(self.ares_dir, "ares-headless"), self.rom_path, "--hang", str(hang_vis)]
        if counter:
            self.args += ["--counter", "0x%08X:0x%X" % counter]
        if interpreter:
            self.args.append("--interpreter")
        self.verbose = verbose
        self.frame = 0
        self.vi = 0
        self.buttons = 0
        self.proc = None
        self.manifest = ""
        self._screen = None

    # -- lifecycle -------------------------------------------------------

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.stop()

    def start(self):
        log = open(os.path.join(self.workdir, "ares.log"), "ab")
        self.proc = subprocess.Popen(self.args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
                                     cwd=self.workdir)
        status, _, fields = self._answer()
        if status != "ok":
            raise RuntimeError("ares-headless did not start: %s" % status)
        with open(os.path.join(self.workdir, "ares.log"), "rb") as f:
            self.manifest = f.read().decode(errors="replace")

    def stop(self):
        if not self.proc:
            return
        try:
            self._command("flush")
            self._command("quit")
        except (BrokenPipeError, RuntimeError):
            pass
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.proc = None

    # -- protocol --------------------------------------------------------

    def _answer(self):
        line = self.proc.stdout.readline().decode()
        if not line:
            raise RuntimeError("ares-headless exited (see %s)" % os.path.join(self.workdir, "ares.log"))
        parts = line.split()
        status, length, fields = parts[0], int(parts[1]), parts[2:]
        payload = self.proc.stdout.read(length) if length else b""
        return status, payload, fields

    def _command(self, text):
        self.proc.stdin.write((text + "\n").encode())
        self.proc.stdin.flush()
        status, payload, fields = self._answer()
        if status == "err":
            raise RuntimeError("ares-headless: %s: %s" % (text, payload.decode(errors="replace")))
        return status, payload, fields

    # -- stepping --------------------------------------------------------

    def frames(self, n=1, buttons=None):
        """Run n game frames. buttons: held for all of them (None = leave as is)."""
        if buttons is not None:
            self.buttons = buttons & 0xFFFFFFFF
        if n <= 0:
            return
        self._screen = None
        status, _, fields = self._command("frames %d %d" % (n, self.buttons))
        self.frame, self.vi = int(fields[0]), int(fields[1])
        if status == "hang":
            raise Hang("no game frame in a while after frame %d (VI %d)" % (self.frame, self.vi))

    def vis(self, n, buttons=None):
        """Run n VI fields, whatever the game does (a hang, a black screen, the boot)."""
        if buttons is not None:
            self.buttons = buttons & 0xFFFFFFFF
        self._screen = None
        _, _, fields = self._command("vis %d %d" % (n, self.buttons))
        self.frame, self.vi = int(fields[0]), int(fields[1])

    def hold(self, buttons, control=0):
        self.buttons = buttons & 0xFFFFFFFF

    def press(self, buttons, frames=2, release=2):
        self.frames(frames, buttons)
        self.frames(release, 0)

    def to_frame(self, frame, buttons=None):
        if frame < self.frame:
            raise ValueError("frame %d is behind us (%d)" % (frame, self.frame))
        self.frames(frame - self.frame, buttons)

    def run_until(self, predicate, limit, buttons=None):
        for i in range(limit):
            if predicate(self):
                return i
            self.frames(1, buttons)
        if predicate(self):
            return limit
        raise TimeoutError("condition not met in %d frames" % limit)

    # -- memory ----------------------------------------------------------

    def read(self, addr, size):
        _, payload, _ = self._command("read %x %d" % (addr, size))
        return payload

    def write(self, addr, data):
        self._command("write %x %s" % (addr, bytes(data).hex()))

    def u8(self, addr):
        return self.read(addr, 1)[0]

    def u16(self, addr):
        return struct.unpack(">H", self.read(addr, 2))[0]

    def u32(self, addr):
        return struct.unpack(">I", self.read(addr, 4))[0]

    # -- the screen ------------------------------------------------------

    def native(self):
        """(width, height, rgb) of ares's last frame, as ares gives it."""
        _, payload, _ = self._command("screen")
        w, h = struct.unpack("<II", payload[:8])
        return w, h, payload[8:]

    def screen(self):
        """(640, 240, rgb): ares's frame in the coordinates of afplay's tests (angrylion's 640x240)."""
        if self._screen is None:
            w, h, rgb = self.native()
            if w == 640 and h == 480:
                rows = [rgb[y * 640 * 3:(y + 1) * 640 * 3] for y in range(0, 480, 2)]
                self._screen = (640, 240, b"".join(rows))
            else:
                self._screen = (w, h, rgb)
        return self._screen

    def screenshot(self, path):
        w, h, rgb = self.native()
        if not w:
            raise RuntimeError("no frame has been drawn yet")
        write_png(path, w, h, rgb)
        return w, h

    # -- states and the cartridge -----------------------------------------

    def save_state(self, path):
        """ares's state of the machine at this frame, and our frame and VI counts beside it."""
        self._command("save %s" % os.path.abspath(path))
        with open(path + ".frame", "w") as f:
            f.write("%d %d\n" % (self.frame, self.vi))
        return self.frame

    def load_state(self, path):
        self._command("load %s" % os.path.abspath(path))
        self._screen = None
        with open(path + ".frame") as f:
            self.frame, self.vi = (int(x) for x in f.read().split())

    def flush(self):
        """Write the cartridge's saves (Flash RAM, the clock, the Controller Pak) to workdir now."""
        self._command("flush")

    def save_files(self):
        return {name: os.path.getsize(os.path.join(self.workdir, name))
                for name in sorted(os.listdir(self.workdir)) if name.startswith("game.") and name != "game.z64"}


def fresh(workdir):
    """A new cartridge: no saves, no clock."""
    shutil.rmtree(workdir, ignore_errors=True)
    os.makedirs(workdir)
