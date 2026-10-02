"""A headless Nintendo 64, driven one frame at a time from Python.

mupen64plus-core runs the game on its own thread; this module holds that
thread at every finished frame (the core's frame callback) until told to
go on, so a script can press a button on an exact frame, read RDRAM, take a
screenshot, or save a state, all with the machine stopped. The pieces are
built by emu/build.sh: the core with its debugger, the cxd4 RSP, angrylion's
software RDP with emu/headless_output.c instead of a window, and
emu/input_headless.c.

    with N64("build/emu", rom_bytes, "build/run") as n64:
        n64.frames(300)
        n64.press(START)
        n64.screenshot("title.png")
        print(hex(n64.u32(0x80000300)))

A frame here is one rendered frame (a graphics task finished by the RSP),
not one VI interrupt: Animal Forest renders at a fraction of the VI rate,
and a frame is what the player sees change.
"""

import calendar
import ctypes
import os
import struct
import threading
import time
import zlib

# BUTTONS word (m64p_plugin.h), low 16 bits.
R_DPAD, L_DPAD, D_DPAD, U_DPAD = 1 << 0, 1 << 1, 1 << 2, 1 << 3
START, Z, B, A = 1 << 4, 1 << 5, 1 << 6, 1 << 7
R_C, L_C, D_C, U_C = 1 << 8, 1 << 9, 1 << 10, 1 << 11
R, L = 1 << 12, 1 << 13


def stick(x, y):
    """The analog stick as BUTTONS bits: x, y in -128..127, up is +y."""
    return ((x & 0xFF) << 16) | ((y & 0xFF) << 24)


# m64p_types.h
M64CMD_ROM_OPEN, M64CMD_ROM_CLOSE = 1, 2
M64CMD_EXECUTE, M64CMD_STOP = 5, 6
M64CMD_STATE_LOAD, M64CMD_STATE_SAVE = 10, 11
M64CMD_SET_FRAME_CALLBACK = 15
M64CMD_CORE_STATE_SET = 17
M64PLUGIN_RSP, M64PLUGIN_GFX, M64PLUGIN_INPUT = 1, 2, 4
M64TYPE_INT, M64TYPE_BOOL, M64TYPE_STRING = 1, 3, 4
M64CORE_EMU_STATE, M64CORE_SPEED_LIMITER = 1, 5
M64CORE_STATE_LOADCOMPLETE, M64CORE_STATE_SAVECOMPLETE = 10, 11
M64EMU_STOPPED = 1
M64P_DBG_RUNSTATE_RUNNING = 2
M64P_DBG_PTR_RDRAM = 1
M64P_CPU_PC, M64P_CPU_REG_REG = 1, 2
M64P_BKP_CMD_ADD_STRUCT, M64P_BKP_CMD_REMOVE_IDX = 2, 5
M64P_BKP_FLAG_ENABLED, M64P_BKP_FLAG_READ, M64P_BKP_FLAG_WRITE, M64P_BKP_FLAG_EXEC = 1, 2, 4, 8

FRONTEND_API_VERSION = 0x020106
RDRAM_SIZE = 8 * 1024 * 1024

EMU_MODES = {"interpreter": 0, "cached": 1, "dynarec": 2}

_DebugCallback = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p)
_StateCallback = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_int, ctypes.c_int)
_FrameCallback = ctypes.CFUNCTYPE(None, ctypes.c_uint)
_DbgInit = ctypes.CFUNCTYPE(None)
_DbgUpdate = ctypes.CFUNCTYPE(None, ctypes.c_uint)
_DbgVi = ctypes.CFUNCTYPE(None)


class Breakpoint(ctypes.Structure):
    _fields_ = [("address", ctypes.c_uint32), ("endaddr", ctypes.c_uint32), ("flags", ctypes.c_uint)]


class Hang(Exception):
    """No frame came out within the timeout. .pcs holds where the CPU was."""

    def __init__(self, message, pcs):
        super().__init__(message)
        self.pcs = pcs


def to_physical(addr):
    """KSEG0/KSEG1 address (0x80xxxxxx, 0xA0xxxxxx) or physical, to physical."""
    return addr & 0x1FFFFFFF


def write_png(path, width, height, rgb):
    """rgb: bytes, 3 per pixel, top row first."""
    raw = b"".join(b"\0" + rgb[y * width * 3:(y + 1) * width * 3] for y in range(height))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


class N64:
    def __init__(self, emu_dir, rom, workdir, emumode="dynarec", watch=False,
                 vi_filtered=True, timeout=30.0, verbose=False,
                 clock=(2001, 4, 14, 12, 0, 0)):
        """emu_dir: emu/build.sh's output. rom: the cartridge, big-endian
        (.z64) bytes. workdir: where the flash save, the Controller Pak and
        states go (created; delete it for a fresh cartridge).

        watch=True turns the debugger on so watch() works; memory
        watchpoints only fire under the interpreters, so it forces
        emumode="cached" unless "interpreter" was asked for.
        timeout: seconds of wall-clock time without a frame before
        frames() gives up with Hang.
        clock: what the cartridge's real-time clock reads at power-on
        (year, month, day, hour, minute, second); it then advances with
        emulated time, so a run sees the same dates every time. The
        default is the game's release day. None: the host's clock."""
        self.emu_dir = os.path.abspath(emu_dir)
        self.workdir = os.path.abspath(workdir)
        os.makedirs(self.workdir, exist_ok=True)
        self.rom = bytes(rom)
        self.watch_enabled = watch
        if watch and emumode == "dynarec":
            emumode = "cached"
        self.emumode = emumode
        self.vi_filtered = vi_filtered
        self.timeout = timeout
        self.verbose = verbose
        self.clock = clock

        self.frame = 0          # rendered frames since power-on
        self.hits = []          # (pc, accessed address, flags) per watchpoint hit
        self.on_hit = None      # optional callable(n64, pc, addr, flags) -> None
        self._at_frame = threading.Event()
        self._go = threading.Event()
        self._stopped = threading.Event()
        self._state_done = threading.Event()
        self._state_ok = False
        self._thread = None
        self._rdram = None

    # -- lifecycle -------------------------------------------------------

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.stop()

    def _lib(self, name):
        return ctypes.CDLL(os.path.join(self.emu_dir, name), mode=ctypes.RTLD_GLOBAL)

    def start(self):
        core = self.core = self._lib("libmupen64plus.so.2")
        self.gfx = self._lib("mupen64plus-video-angrylion-headless.so")
        self.rsp = self._lib("mupen64plus-rsp-cxd4.so")
        self.inp = self._lib("mupen64plus-input-headless.so")

        core.CoreDoCommand.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        core.ConfigOpenSection.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
        core.ConfigSetParameter.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p]
        core.CoreAttachPlugin.argtypes = [ctypes.c_int, ctypes.c_void_p]
        core.DebugMemGetPointer.restype = ctypes.c_void_p
        core.DebugGetCPUDataPtr.restype = ctypes.c_void_p
        core.DebugBreakpointCommand.argtypes = [ctypes.c_int, ctypes.c_uint, ctypes.POINTER(Breakpoint)]
        core.DebugBreakpointTriggeredBy.argtypes = [ctypes.POINTER(ctypes.c_uint32), ctypes.POINTER(ctypes.c_uint32)]
        core.DebugSetCallbacks.argtypes = [_DbgInit, _DbgUpdate, _DbgVi]
        self.gfx.headless_frame.restype = ctypes.c_uint32
        self.gfx.headless_frame.argtypes = [ctypes.POINTER(ctypes.POINTER(ctypes.c_uint8))] + [ctypes.POINTER(ctypes.c_uint32)] * 3
        self.inp.headless_input_set.argtypes = [ctypes.c_int, ctypes.c_uint]
        self.inp.headless_input_polls.restype = ctypes.c_uint

        # Callbacks must outlive the core: keep references on self.
        self._debug_cb = _DebugCallback(self._on_debug)
        self._state_cb = _StateCallback(self._on_state)
        self._frame_cb = _FrameCallback(self._on_frame)
        self._dbg_init = _DbgInit(lambda: None)
        self._dbg_update = _DbgUpdate(self._on_dbg_update)
        self._dbg_vi = _DbgVi(lambda: None)

        # The RTC goes through localtime(): make that UTC, so `clock` is
        # what the game reads whatever the host's zone.
        os.environ["TZ"] = "UTC"
        time.tzset()
        if self.clock is not None:
            core.HeadlessFixClock.argtypes = [ctypes.c_longlong, ctypes.c_uint]
            self._clock_base = calendar.timegm(tuple(self.clock) + (0, 0, 0))
            core.HeadlessFixClock(self._clock_base, 0)

        cfg = self.workdir.encode()
        data = os.path.join(self.emu_dir, "data").encode()
        self._check(core.CoreStartup(FRONTEND_API_VERSION, cfg, data, None, self._debug_cb, None, self._state_cb), "CoreStartup")

        section = ctypes.c_void_p()
        core.ConfigOpenSection(b"Core", ctypes.byref(section))
        self._set(section, b"R4300Emulator", EMU_MODES[self.emumode])
        self._set(section, b"EnableDebugger", self.watch_enabled, M64TYPE_BOOL)
        self._set(section, b"SaveSRAMPath", self.workdir.encode(), M64TYPE_STRING)
        self._set(section, b"SaveStatePath", self.workdir.encode(), M64TYPE_STRING)
        self._set(section, b"ScreenshotPath", self.workdir.encode(), M64TYPE_STRING)
        self._set(section, b"OnScreenDisplay", False, M64TYPE_BOOL)
        # Same inputs, same run: no jitter on PI/SI interrupts.
        self._set(section, b"RandomizeInterrupt", False, M64TYPE_BOOL)

        if self.watch_enabled:
            core.DebugSetCallbacks(self._dbg_init, self._dbg_update, self._dbg_vi)

        rom = ctypes.create_string_buffer(self.rom, len(self.rom))
        self._check(core.CoreDoCommand(M64CMD_ROM_OPEN, len(self.rom), rom), "ROM_OPEN")

        handle = ctypes.c_void_p(core._handle)
        for lib, kind in ((self.gfx, M64PLUGIN_GFX), (self.inp, M64PLUGIN_INPUT), (self.rsp, M64PLUGIN_RSP)):
            lib.PluginStartup.argtypes = [ctypes.c_void_p, ctypes.c_void_p, _DebugCallback]
            self._check(lib.PluginStartup(handle, None, self._debug_cb), "PluginStartup")
            if kind == M64PLUGIN_GFX:
                video = ctypes.c_void_p()
                core.ConfigOpenSection(b"Video-AngrylionPlus", ctypes.byref(video))
                self._set(video, b"Parallel", True, M64TYPE_BOOL)
                self._set(video, b"NumWorkers", 0)
                self._set(video, b"ViMode", 0 if self.vi_filtered else 1)
            self._check(core.CoreAttachPlugin(kind, ctypes.c_void_p(lib._handle)), "CoreAttachPlugin")

        # As fast as the host goes, not at 60 VIs a second.
        self._check(core.CoreDoCommand(M64CMD_CORE_STATE_SET, M64CORE_SPEED_LIMITER, ctypes.byref(ctypes.c_int(0))), "SPEED_LIMITER")
        self._check(core.CoreDoCommand(M64CMD_SET_FRAME_CALLBACK, 0, ctypes.cast(self._frame_cb, ctypes.c_void_p)), "SET_FRAME_CALLBACK")

        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        self._wait_frame()

    def _run(self):
        self.core.CoreDoCommand(M64CMD_EXECUTE, 0, None)
        self._stopped.set()
        self._at_frame.set()

    def stop(self):
        if not self._thread:
            return
        self.core.CoreDoCommand(M64CMD_STOP, 0, None)
        self._go.set()
        self._thread.join(timeout=10)
        self.core.CoreDoCommand(M64CMD_ROM_CLOSE, 0, None)
        self.core.CoreShutdown()
        self._thread = None

    # -- callbacks (emulation thread) -----------------------------------

    def _on_debug(self, context, level, message):
        if self.verbose or level <= 1:  # M64MSG_ERROR
            print("[m64p %d] %s" % (level, message.decode(errors="replace")))

    def _on_state(self, context, param, value):
        if param in (M64CORE_STATE_SAVECOMPLETE, M64CORE_STATE_LOADCOMPLETE):
            self._state_ok = bool(value)
            self._state_done.set()

    def _on_frame(self, index):
        # The core's own count, which our patch keeps in savestates.
        self.frame = index + 1
        self._at_frame.set()
        self._go.wait()
        self._go.clear()

    def _on_dbg_update(self, pc):
        # Also called once at power-on (the debugger starts paused), with
        # no breakpoint behind it: flags 0.
        flags, addr = ctypes.c_uint32(), ctypes.c_uint32()
        self.core.DebugBreakpointTriggeredBy(ctypes.byref(flags), ctypes.byref(addr))
        if flags.value:
            self.hits.append((pc, addr.value, flags.value))
            if self.on_hit:
                self.on_hit(self, pc, addr.value, flags.value)
        self.core.DebugSetRunState(M64P_DBG_RUNSTATE_RUNNING)
        self.core.DebugStep()

    # -- stepping --------------------------------------------------------

    def _wait_frame(self):
        deadline = time.monotonic() + self.timeout
        while not self._at_frame.wait(0.05):
            if time.monotonic() > deadline:
                raise Hang("no frame in %.0f s after frame %d" % (self.timeout, self.frame), self.sample_pc())
        if self._stopped.is_set():
            raise RuntimeError("the emulator stopped")
        self._at_frame.clear()

    def frames(self, n=1, buttons=None):
        """Run n frames. buttons: held for all of them (None = leave as is)."""
        if buttons is not None:
            self.hold(buttons)
        for _ in range(n):
            self._go.set()
            self._wait_frame()

    def hold(self, buttons, control=0):
        self.inp.headless_input_set(control, buttons)

    def press(self, buttons, frames=2, release=2):
        """Press for `frames` frames, then let go for `release` frames."""
        self.frames(frames, buttons)
        self.frames(release, 0)

    def polls(self, control=0):
        return self.inp.headless_input_polls(control)

    def to_frame(self, frame, buttons=None):
        """Run until self.frame == frame."""
        if frame < self.frame:
            raise ValueError("frame %d is behind us (%d)" % (frame, self.frame))
        self.frames(frame - self.frame, buttons)

    def run_until(self, predicate, limit, buttons=None):
        """Run frames until predicate(self) is true; returns the frames it
        took, or raises TimeoutError after `limit`."""
        for i in range(limit):
            if predicate(self):
                return i
            self.frames(1, buttons)
        if predicate(self):
            return limit
        raise TimeoutError("condition not met in %d frames" % limit)

    # -- memory ----------------------------------------------------------

    def _rdram_ptr(self):
        if self._rdram is None:
            self._rdram = self.core.DebugMemGetPointer(M64P_DBG_PTR_RDRAM)
        return self._rdram

    def read(self, addr, size):
        """size bytes of RDRAM at addr, in the N64's (big-endian) order.
        mupen64plus keeps RDRAM as host-endian 32-bit words."""
        phys = to_physical(addr)
        start = phys & ~3
        end = (phys + size + 3) & ~3
        if end > RDRAM_SIZE:
            raise ValueError("outside RDRAM: %08x" % addr)
        words = ctypes.string_at(self._rdram_ptr() + start, end - start)
        be = b"".join(words[i:i + 4][::-1] for i in range(0, len(words), 4))
        return be[phys - start:phys - start + size]

    def write(self, addr, data):
        phys = to_physical(addr)
        start = phys & ~3
        end = (phys + len(data) + 3) & ~3
        be = bytearray(self.read(start | 0x80000000, end - start))
        be[phys - start:phys - start + len(data)] = data
        host = b"".join(bytes(be[i:i + 4])[::-1] for i in range(0, len(be), 4))
        ctypes.memmove(self._rdram_ptr() + start, host, len(host))

    def u8(self, addr):
        return self.read(addr, 1)[0]

    def u16(self, addr):
        return struct.unpack(">H", self.read(addr, 2))[0]

    def u32(self, addr):
        return struct.unpack(">I", self.read(addr, 4))[0]

    def clock_vis(self):
        """VIs since power-on, as the fixed clock counts them."""
        return self.core.HeadlessClockVis()

    def pc(self):
        return ctypes.c_uint32.from_address(self.core.DebugGetCPUDataPtr(M64P_CPU_PC)).value

    def gpr(self, n):
        regs = ctypes.cast(self.core.DebugGetCPUDataPtr(M64P_CPU_REG_REG), ctypes.POINTER(ctypes.c_int64))
        return regs[n] & 0xFFFFFFFF

    def sample_pc(self, count=64, interval=0.002):
        """Where the CPU spends its time while frames are not coming out:
        a game stuck in a loop shows the same few addresses."""
        pcs = []
        for _ in range(count):
            pcs.append(self.pc())
            time.sleep(interval)
        return pcs

    # -- watchpoints (watch=True) ----------------------------------------

    def watch(self, start, end=None, write=True, read=False, execute=False):
        """Record every CPU access to [start, end] (inclusive) in self.hits
        as (pc, physical address, flags). Only the CPU's: what the RSP and
        RDP write (framebuffers, DMA) goes past it."""
        if not self.watch_enabled:
            raise RuntimeError("start the N64 with watch=True")
        flags = M64P_BKP_FLAG_ENABLED
        flags |= M64P_BKP_FLAG_WRITE if write else 0
        flags |= M64P_BKP_FLAG_READ if read else 0
        flags |= M64P_BKP_FLAG_EXEC if execute else 0
        # The core checks physical addresses (r4300_core.c masks with
        # 0x1ffffffc before the memory handlers); hits come back physical.
        end = start if end is None else end
        bp = Breakpoint(to_physical(start), to_physical(end), flags)
        return self.core.DebugBreakpointCommand(M64P_BKP_CMD_ADD_STRUCT, 0, ctypes.byref(bp))

    def unwatch(self, index):
        self.core.DebugBreakpointCommand(M64P_BKP_CMD_REMOVE_IDX, index, None)

    # -- screen ----------------------------------------------------------

    def screen(self):
        """(width, height, rgb bytes) of the last frame angrylion finished."""
        pix = ctypes.POINTER(ctypes.c_uint8)()
        w, h, hout = ctypes.c_uint32(), ctypes.c_uint32(), ctypes.c_uint32()
        self.gfx.headless_frame(ctypes.byref(pix), ctypes.byref(w), ctypes.byref(h), ctypes.byref(hout))
        if not pix or not w.value:
            return 0, 0, b""
        rgba = ctypes.string_at(pix, w.value * h.value * 4)
        rgb = bytearray(w.value * h.value * 3)
        rgb[0::3] = rgba[0::4]
        rgb[1::3] = rgba[1::4]
        rgb[2::3] = rgba[2::4]
        return w.value, h.value, bytes(rgb)

    def screenshot(self, path):
        w, h, rgb = self.screen()
        if not w:
            raise RuntimeError("no frame has been drawn yet")
        write_png(path, w, h, rgb)
        return w, h

    # -- states ----------------------------------------------------------

    def _state(self, command, path):
        self._state_done.clear()
        self._check(self.core.CoreDoCommand(command, 1, ctypes.c_char_p(os.path.abspath(path).encode())), "state")
        # The core does it at its next interrupt, so let frames go by.
        for _ in range(60):
            if self._state_done.is_set():
                break
            self.frames(1)
        if not (self._state_done.is_set() and self._state_ok):
            raise RuntimeError("state %s failed: %s" % ("save" if command == M64CMD_STATE_SAVE else "load", path))

    def save_state(self, path):
        """Snapshot the machine as it is at this frame; returns its number.

        The core takes the snapshot at its next interrupt, before the next
        frame is done, and writes the file on another thread: this returns
        once the file is written, a few frames later (self.frame has moved
        on). The frame number and the fixed clock go in the state
        (emu/patches), so load_state() puts the machine back at the frame
        returned here and the same inputs replay the same run."""
        frame = self.frame
        self._state(M64CMD_STATE_SAVE, path)
        return frame

    def load_state(self, path):
        """Back to the snapshot of save_state(), run on to the end of that
        frame: self.frame is then the number it returned plus one, the same
        machine, pixel and byte, as the first run was at that frame."""
        self._state(M64CMD_STATE_LOAD, path)

    # -- helpers ---------------------------------------------------------

    def _set(self, section, key, value, kind=M64TYPE_INT):
        if kind == M64TYPE_STRING:
            arg = ctypes.c_char_p(value)
        else:
            arg = ctypes.byref(ctypes.c_int(int(value)))
        self._check(self.core.ConfigSetParameter(section, key, kind, ctypes.cast(arg, ctypes.c_void_p) if kind == M64TYPE_STRING else arg), "ConfigSetParameter %s" % key.decode())

    @staticmethod
    def _check(err, what):
        if err:
            raise RuntimeError("%s failed: m64p error %d" % (what, err))
