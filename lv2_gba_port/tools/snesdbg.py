"""The cartridge, run: an instrumented snes9x libretro core driven from Python.

    from snesdbg import Snes
    s = Snes("lv2.sfc")
    s.run(120)                    # two seconds, no buttons
    s.run(4, "START")             # START held for four frames
    s.screenshot("title.png")
    s.wram[0x0100]                # bytes, as the console has them now
    s.cdl                         # the code/data log over the ROM

The core is upstream snes9x plus tools/snes9x/{dbg.cpp,hooks.patch}, built by
tools/snes9x/build.sh. It is looked for at $LV2_SNES_CORE, then at
build/snes9x/libretro/snes9x_libretro.so.

This is a measuring instrument for the cartridge, the way the Tetris port's
nes_cpu.py was: nothing in the port runs on it.
"""

import ctypes as C
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

BUTTONS = ["B", "Y", "SELECT", "START", "UP", "DOWN", "LEFT", "RIGHT",
           "A", "X", "L", "R"]

# Bits of the code/data log (tools/snes9x/dbg.h).
CODE, OPERAND, DATA, DMA, M8, X8, EMU = 1, 2, 4, 8, 0x10, 0x20, 0x40

_ENV_GET_CAN_DUPE = 3
_ENV_GET_SYSTEM_DIRECTORY = 9
_ENV_SET_PIXEL_FORMAT = 10
_ENV_GET_VARIABLE = 15
_ENV_GET_LOG_INTERFACE = 27
_ENV_GET_SAVE_DIRECTORY = 31
_ENV_GET_INPUT_BITMASKS = 51 | 0x10000
_MEMORY_SYSTEM_RAM = 2

_env_t = C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p)
_video_t = C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t)
_audio_t = C.CFUNCTYPE(None, C.c_int16, C.c_int16)
_audio_batch_t = C.CFUNCTYPE(C.c_size_t, C.c_void_p, C.c_size_t)
_poll_t = C.CFUNCTYPE(None)
_state_t = C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint)
_bp_t = C.CFUNCTYPE(None, C.c_uint32)


class _GameInfo(C.Structure):
    _fields_ = [("path", C.c_char_p), ("data", C.c_void_p),
                ("size", C.c_size_t), ("meta", C.c_char_p)]


def _core_path():
    p = os.environ.get("LV2_SNES_CORE")
    if p:
        return p
    root = os.path.dirname(HERE)
    return os.path.join(root, "build", "snes9x", "libretro",
                        "snes9x_libretro.so")


def mask_of(buttons):
    """'A+RIGHT', ['A', 'RIGHT'] or an int -> the libretro joypad mask."""
    if buttons is None:
        return 0
    if isinstance(buttons, int):
        return buttons
    if isinstance(buttons, str):
        buttons = [b for b in buttons.replace(",", "+").split("+") if b]
    m = 0
    for b in buttons:
        m |= 1 << BUTTONS.index(b.strip().upper())
    return m


class Snes:
    _instance = None

    def __init__(self, rom, core=None):
        if Snes._instance is not None:
            raise RuntimeError("one core per process: libretro keeps globals")
        Snes._instance = self
        self.lib = C.CDLL(core or _core_path())
        self.rom = open(rom, "rb").read() if isinstance(rom, str) else bytes(rom)
        self.pad = 0
        self.frame_rgb = None
        self._sysdir = C.create_string_buffer(
            os.path.join(os.path.dirname(HERE), "build").encode())
        self._keep = []
        self._bp_cb = None

        lib = self.lib
        lib.retro_set_environment(self._cb(_env_t, self._env))
        lib.retro_set_video_refresh(self._cb(_video_t, self._video))
        lib.retro_set_audio_sample(self._cb(_audio_t, lambda l, r: None))
        lib.retro_set_audio_sample_batch(
            self._cb(_audio_batch_t, lambda d, n: n))
        lib.retro_set_input_poll(self._cb(_poll_t, lambda: None))
        lib.retro_set_input_state(self._cb(_state_t, self._input))
        lib.retro_init()

        self._rombuf = C.create_string_buffer(self.rom, len(self.rom))
        info = _GameInfo(b"game.sfc", C.cast(self._rombuf, C.c_void_p),
                         len(self.rom), None)
        if not lib.retro_load_game(C.byref(info)):
            raise RuntimeError("the core refused the ROM")

        lib.dbg_cdl.restype = C.POINTER(C.c_uint8)
        lib.dbg_ptr.restype = C.POINTER(C.c_uint8)
        lib.dbg_frame.restype = C.c_uint32
        lib.dbg_read.restype = C.c_uint8
        lib.retro_get_memory_data.restype = C.c_void_p
        lib.retro_serialize_size.restype = C.c_size_t
        lib.dbg_enable(1)

        self.rom_size = len(self.rom)
        self.cdl = np.ctypeslib.as_array(lib.dbg_cdl(), shape=(0x800000,))[
            :self.rom_size]
        self.wram = np.ctypeslib.as_array(lib.dbg_ptr(0), shape=(0x20000,))
        self.vram = np.ctypeslib.as_array(lib.dbg_ptr(1), shape=(0x10000,))
        self.cgram = np.ctypeslib.as_array(
            C.cast(lib.dbg_ptr(2), C.POINTER(C.c_uint16)), shape=(256,))
        self.oam = np.ctypeslib.as_array(lib.dbg_ptr(3), shape=(544,))

    def _cb(self, kind, fn):
        f = kind(fn)
        self._keep.append(f)
        return f

    # -- libretro callbacks -------------------------------------------------

    def _env(self, cmd, data):
        cmd &= 0xFFFF  # drop RETRO_ENVIRONMENT_EXPERIMENTAL
        if cmd == _ENV_SET_PIXEL_FORMAT:
            self.pixfmt = C.cast(data, C.POINTER(C.c_int))[0]
            return True
        if cmd in (_ENV_GET_SYSTEM_DIRECTORY, _ENV_GET_SAVE_DIRECTORY):
            C.cast(data, C.POINTER(C.c_void_p))[0] = C.addressof(self._sysdir)
            return True
        if cmd == _ENV_GET_CAN_DUPE:
            C.cast(data, C.POINTER(C.c_bool))[0] = True
            return True
        if cmd == _ENV_GET_INPUT_BITMASKS & 0xFFFF:
            return True
        return False

    def _video(self, data, w, h, pitch):
        if not data:
            return
        fmt = getattr(self, "pixfmt", 2)
        if fmt == 1:  # XRGB8888
            a = np.ctypeslib.as_array(C.cast(data, C.POINTER(C.c_uint32)),
                                      shape=(h, pitch // 4))[:, :w]
            rgb = np.stack([(a >> 16) & 255, (a >> 8) & 255, a & 255], -1)
        else:  # RGB565
            a = np.ctypeslib.as_array(C.cast(data, C.POINTER(C.c_uint16)),
                                      shape=(h, pitch // 2))[:, :w]
            r = (a >> 11) & 31
            g = (a >> 5) & 63
            b = a & 31
            rgb = np.stack([(r << 3) | (r >> 2), (g << 2) | (g >> 4),
                            (b << 3) | (b >> 2)], -1)
        self.frame_rgb = rgb.astype(np.uint8)

    def _input(self, port, device, index, id_):
        if port != 0:
            return 0
        if id_ == 256:  # RETRO_DEVICE_ID_JOYPAD_MASK
            return self.pad
        return 1 if self.pad & (1 << id_) else 0

    # -- driving ------------------------------------------------------------

    def run(self, frames=1, buttons=None):
        self.pad = mask_of(buttons)
        for _ in range(frames):
            self.lib.retro_run()
        self.pad = 0

    def tap(self, buttons, hold=2, after=8):
        """Press and release: `hold` frames down, `after` frames up."""
        self.run(hold, buttons)
        self.run(after)

    @property
    def frame(self):
        return self.lib.dbg_frame()

    def screenshot(self, path):
        from PIL import Image
        Image.fromarray(self.frame_rgb).save(path)

    def save_state(self):
        n = self.lib.retro_serialize_size()
        buf = C.create_string_buffer(n)
        if not self.lib.retro_serialize(buf, C.c_size_t(n)):
            raise RuntimeError("serialize failed")
        return buf.raw

    def load_state(self, blob):
        buf = C.create_string_buffer(blob, len(blob))
        if not self.lib.retro_unserialize(buf, C.c_size_t(len(blob))):
            raise RuntimeError("unserialize failed")

    # -- looking ------------------------------------------------------------

    def regs(self):
        out = (C.c_uint32 * 9)()
        self.lib.dbg_regs(out)
        k = ["pc", "a", "x", "y", "s", "d", "db", "p", "e"]
        return dict(zip(k, list(out)))

    def ppu(self):
        out = (C.c_uint32 * 40)()
        self.lib.dbg_ppu(out)
        v = list(out)
        bgs = []
        for i in range(4):
            sc, scs, nb, ho, vo, sz = v[2 + 6 * i:8 + 6 * i]
            bgs.append(dict(sc_base=sc, sc_size=scs, name_base=nb, hofs=ho,
                            vofs=vo, tile16=sz))
        o = 26
        return dict(mode=v[0], bg3prio=v[1], bg=bgs, obj_base=v[o],
                    obj_select=v[o + 1], obj_size=v[o + 2], tm=v[o + 3],
                    ts=v[o + 4], cgwsel=v[o + 5], cgadsub=v[o + 6],
                    inidisp=v[o + 7])

    def read(self, address, n=1):
        """Bytes at a 24-bit CPU address, without side effects (ROM, WRAM,
        SRAM; registers read as 0)."""
        return bytes(self.lib.dbg_read(C.c_uint32(address + i))
                     for i in range(n))

    def dma_log(self, clear=True):
        """Every general-purpose DMA since the last call: dicts with frame,
        channel, src (24-bit), breg ($21xx low byte), size, to_cpu, dest
        (VRAM word address / CGRAM index / OAM address / WRAM address)."""
        out = (C.c_uint32 * (8 * 65536))()
        n = self.lib.dbg_dma_log(out, 65536)
        rows = []
        for i in range(n):
            e = out[8 * i:8 * i + 8]
            rows.append(dict(frame=e[0], channel=e[1], src=e[2], breg=e[3],
                             size=e[4], to_cpu=bool(e[5]), dest=e[6],
                             mode=e[7] & 15, fixed=bool(e[7] & 16)))
        if clear:
            self.lib.dbg_dma_clear()
        return rows

    def breakpoints(self, addrs, callback):
        """callback(pc) runs inside the frame, before the opcode at pc."""
        arr = (C.c_uint32 * len(addrs))(*addrs)
        self._bp_cb = _bp_t(callback)
        self._bp_arr = arr
        self.lib.dbg_set_breakpoints(arr, len(addrs), self._bp_cb)

    def clear_breakpoints(self):
        self.lib.dbg_set_breakpoints(None, 0, None)

    def trace(self, path, instructions):
        self.lib.dbg_trace_start(path.encode(), C.c_long(instructions))

    def trace_stop(self):
        self.lib.dbg_trace_stop()


def lorom_offset(address):
    """24-bit LoROM address -> file offset (None if not ROM)."""
    bank = (address >> 16) & 0x7F
    lo = address & 0xFFFF
    if lo < 0x8000 or bank >= 0x7E:
        return None
    return (bank << 15) | (lo & 0x7FFF)


def lorom_address(offset, bank_base=0x80):
    return ((bank_base + (offset >> 15)) << 16) | 0x8000 | (offset & 0x7FFF)
