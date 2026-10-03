#!/usr/bin/env python3
"""Drive the en build's save code in the emulator and check the flash.

    tests/emu_save.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). The
game's own save functions are called from a hook: a few lines of C,
compiled by the decomp's IDO and linked against the en ELF's symbols into
the hole m_choice_main's text left in code when it moved to code_en,
called each frame from mTM_time (its first two instructions moved into
the hook's stub). Python sets a command word; the hook runs it a step a
frame, as the game's callers do. The flash file is then read back and
unpacked with tools/aflz.py. Checks (reference/NOTES.md, "The compressed
save"):

  1. the normal save (func_80090044_jp, the borrowed framebuffer, both
     slots written and read back) leaves both slots in the compressed
     format, holding the save as stamped and the letters' extension;
  2. the gyroid's save (the slot writer func_8008F1BC_jp, from a buffer
     the caller fills and stamps) does the same, with another extension;
  3. the save menu's synchronous write (func_8008F7C8_jp) writes the
     compressed format where the game state's heap has room (called on
     the trademark screen of a fresh boot) and the cartridge's format in
     play, where it has none;
  4. the load (func_8008F968_jp) brings back the save and the extension
     from a compressed slot;
  5. a fresh boot with a compressed slot 0 and an erased slot 1 repairs
     slot 1 with slot 0's image, and the game loads the save and the
     extension from it.
"""

import hashlib
import os
import random
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, START, A  # noqa: E402
import aflz  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef unsigned short u16;
typedef int s32;
typedef unsigned int u32;

extern u8 common_data[];
s32 func_80090044_jp(void);
s32 func_8008F1BC_jp(void* data, void* slot);
void func_8008F210_jp(void);
void* func_800D97A0_jp(u32 size);
void func_800D986C_jp(s32 buf);
void func_8008EFDC_jp(void* chk);
u16 func_8008EEB4_jp(void* data, s32 size, u16 checksum);
s32 func_8008F7C8_jp(void);
s32 func_8008F968_jp(void);
void bcopy(const void* src, void* dst, int size);

typedef struct HookCtl {
    s32 cmd;
    s32 state;
    s32 result;
    s32 frames;
    u8* buf;
    s32 slot;
} HookCtl;

HookCtl hook_ctl;

void hook_main(void) {
    HookCtl* h = &hook_ctl;
    s32 r;

    switch (h->cmd) {
        case 1: /* the normal save, a step a frame */
            h->frames++;
            r = func_80090044_jp();
            if (r != 0) {
                h->result = r;
                h->cmd = 0;
            }
            break;
        case 2: /* the gyroid's: a borrowed framebuffer, the save stamped in it, both slots */
            h->frames++;
            if (h->state == 0) {
                h->buf = func_800D97A0_jp(0xF980);
                if (h->buf != 0) {
                    bcopy(common_data, h->buf, 0xF980);
                    func_8008EFDC_jp(h->buf);
                    *(u16*)(h->buf + 0x12) = func_8008EEB4_jp(h->buf, 0xF980, *(u16*)(h->buf + 0x12));
                    h->slot = 0;
                    h->state = 1;
                }
            } else if (h->state == 1) {
                r = func_8008F1BC_jp(h->buf, (void*)h->slot);
                if (r == 1) {
                    func_8008F210_jp();
                    if (h->slot == 0) {
                        h->slot = 1;
                    } else {
                        h->state = 2;
                    }
                } else if (r == -1) {
                    func_8008F210_jp();
                    h->result = -1;
                    h->state = 2;
                }
            } else {
                func_800D986C_jp((s32)h->buf);
                if (h->result == 0) {
                    h->result = 1;
                }
                h->cmd = 0;
            }
            break;
        case 3:
            h->result = func_8008F7C8_jp();
            h->cmd = 0;
            break;
        case 4:
            h->result = func_8008F968_jp();
            h->cmd = 0;
            break;
    }
}
"""

STUB_S = """
.set noreorder
.set noat
.text
.globl hook_stub
hook_stub:
    addiu $sp, $sp, -0x18
    sw    $ra, 0x14($sp)
    jal   hook_main
    nop
    lw    $ra, 0x14($sp)
    addiu $sp, $sp, 0x18
    .word 0x%08X
    .word 0x%08X
    j     0x%08X
    nop
"""

LINK_LD = """
SECTIONS
{
    . = 0x%08X;
    .text : { stub.o(.text) hook.o(.text) }
    .data : { hook.o(.data) hook.o(.rodata*) hook.o(.bss) hook.o(COMMON) }
    /DISCARD/ : { *(.reginfo) *(.mdebug*) *(.MIPS.*) *(.pdr) *(.gptab*) *(.options) *(.comment) }
}
"""

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def elf_symbols(elf):
    out = subprocess.run(["mips-linux-gnu-nm", elf], capture_output=True, text=True, check=True).stdout
    syms = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3:
            syms[parts[2]] = int(parts[0], 16)
    return syms


def section_of(map_path, obj, sec):
    """(address, size) of obj's input section in the matching build's map."""
    for line in open(map_path):
        p = line.split()
        if len(p) == 4 and p[0] == sec and p[3] == obj:
            return int(p[1], 16), int(p[2], 16)
    raise SystemExit("no %s %s in %s" % (sec, obj, map_path))


def build_hook(af_en, en_elf, hole, hole_size, mttime_words, mttime):
    work = tempfile.mkdtemp()
    with open(os.path.join(work, "hook.c"), "w") as f:
        f.write(HOOK_C)
    cc = os.path.join(af_en, "tools", "ido", "linux", "7.1", "cc")
    subprocess.run([cc, "-c", "-G", "0", "-non_shared", "-Xcpluscomm", "-nostdinc", "-Wab,-r4300_mul",
                    "-O2", "-mips2", "-o", "hook.o", "hook.c"], cwd=work, check=True)
    with open(os.path.join(work, "stub.s"), "w") as f:
        f.write(STUB_S % (mttime_words[0], mttime_words[1], mttime + 8))
    subprocess.run(["mips-linux-gnu-as", "-march=vr4300", "-32", "-G0", "-o", "stub.o", "stub.s"], cwd=work, check=True)
    with open(os.path.join(work, "hook.ld"), "w") as f:
        f.write(LINK_LD % hole)
    game = elf_symbols(en_elf)
    undefined = subprocess.run(["mips-linux-gnu-nm", "-u", "hook.o", "stub.o"], cwd=work, capture_output=True,
                               text=True, check=True).stdout.split()
    defs = ["--defsym=%s=0x%08X" % (n, game[n]) for n in sorted(set(undefined)) if n in game]
    subprocess.run(["mips-linux-gnu-ld", "-T", "hook.ld"] + defs + ["-o", "hook.elf"], cwd=work, check=True)
    subprocess.run(["mips-linux-gnu-objcopy", "-O", "binary", "hook.elf", "hook.bin"], cwd=work, check=True)
    blob = open(os.path.join(work, "hook.bin"), "rb").read()
    syms = elf_symbols(os.path.join(work, "hook.elf"))
    shutil.rmtree(work)
    if len(blob) > hole_size:
        raise SystemExit("the hook (%#x bytes) does not fit the hole (%#x)" % (len(blob), hole_size))
    return blob, syms["hook_stub"], syms["hook_ctl"]


def write_code(n64, addr, data):
    """Through the core's own write path, which drops the dynarec's blocks for it."""
    import ctypes
    n64.core.DebugMemWrite32.argtypes = [ctypes.c_uint, ctypes.c_uint]
    data = data + bytes(-len(data) % 4)
    for i in range(0, len(data), 4):
        n64.core.DebugMemWrite32(addr + i, struct.unpack(">I", data[i:i + 4])[0])


def fla_path(workdir, rom):
    return os.path.join(workdir, "DOUBUTSUNOMORI (rebuilt)-%s.fla" % hashlib.md5(rom).hexdigest()[:8].upper())


def read_fla(path):
    raw = open(path, "rb").read()
    return b"".join(raw[i:i + 4][::-1] for i in range(0, len(raw), 4))


def write_fla(path, image):
    open(path, "wb").write(b"".join(image[i:i + 4][::-1] for i in range(0, len(image), 4)))


def checksum_ok(save):
    return sum(struct.unpack(">%dH" % (len(save) // 2), save)) & 0xFFFF == 0


def text(seed, n):
    rnd = random.Random(seed)
    words = [b"Dear", b"urld,", b"the", b"letter", b"from", b"your", b"mother", b"arrived", b"today.", b"Love,"]
    return b" ".join(rnd.choice(words) for _ in range(n))[:n]


def main(argv):
    emu_dir, rom_path, af_en, state, out = argv
    af_en = os.path.abspath(af_en)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    rom = open(rom_path, "rb").read()
    en_elf = os.path.join(af_en, "build", "animalforest-jp.elf")
    syms = elf_symbols(en_elf)
    # the hole is where the cartridge has the object (the en map has it in code_en)
    ref_map = os.path.join(os.path.dirname(af_en), "af", "build", "animalforest-jp.map")
    hole, hole_size = section_of(ref_map, "build/src/code/m_choice_main.o", ".text")
    mttime = syms["mTM_time"]
    cd, ext_at, ext_size_at = syms["common_data"], syms["mFRm_en_ext"], syms["mFRm_en_extSize"]

    cart = os.path.join(out, "cart")
    with N64(emu_dir, rom, cart, timeout=120) as n64:
        def inject():
            words = struct.unpack(">II", n64.read(mttime, 8))
            check(n64.read(hole, hole_size) == bytes(hole_size), "the hole at %#x (%#x bytes) is empty" % (hole, hole_size))
            blob, stub, ctl = build_hook(af_en, en_elf, hole, hole_size, words, mttime)
            write_code(n64, hole, blob)
            write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            return ctl

        # 3a. the save menu's write where the game state's heap has room: the trademark screen
        # (frame 100: its state has its megabyte nearly free; play takes the whole heap by 200)
        n64.frames(100, 0)
        ctl = inject()

        def run(cmd, limit=600):
            n64.write(ctl, struct.pack(">iiii", cmd, 0, 0, 0))
            for _ in range(limit):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    break
            c, st, result, frames = struct.unpack(">iiii", n64.read(ctl, 16))
            return result, frames, c

        def set_ext(data):
            n64.write(ext_at, data)
            n64.write(ext_size_at, struct.pack(">i", len(data)))

        def slots():
            image = read_fla(fla_path(cart, rom))
            return image[:0x10000], image[0x10000:0x20000]

        def check_slot(slot, ext, what):
            save, got_ext = aflz.unpack(slot)
            ram = n64.read(cd, 0xF980)
            check(got_ext is not None, "%s: the compressed format" % what)
            check(got_ext == ext, "%s: the extension (%d bytes) comes back" % (what, len(ext)))
            check(save[0x14:] == ram[0x14:], "%s: the save is common_data's" % what)
            check(save[4:8] == b"NAFJ" and save[8:10] == ram[0x2F68:0x2F6A] and checksum_ok(save),
                  "%s: stamped (code, town id, checksum)" % what)
            return save

        e0 = text(0, 2000)
        set_ext(e0)
        result, frames, c = run(3)
        s0, s1 = slots()
        check(result == 0 and s0[:4] == b"AFZ1", "save menu's write, the trademark screen: compressed (result %d)" % result)
        got, got_ext = aflz.unpack(s0)
        check(got == n64.read(cd, 0xF980) and got_ext == e0, "save menu's write: the save and the extension come back")
        check(s1 == b"\xff" * 0x10000, "save menu's write: slot 1 erased with the chip")

        n64.load_state(state)                    # play, the houses (RAM restored: the hook again)
        ctl = inject()

        # 1. the normal save
        e1 = text(1, 3000)
        set_ext(e1)
        result, frames, c = run(1)
        check(result == 1, "normal save: done in %d frames (result %d)" % (frames, result))
        s0, s1 = slots()
        check_slot(s0, e1, "normal save, slot 0")
        check_slot(s1, e1, "normal save, slot 1")
        check(s0 == s1, "normal save: both slots alike")
        used = 20 + sum(struct.unpack(">II", s0[4:8] + s0[12:16]))
        print("      slot image: %d bytes of 0x10000 (save and extension compressed)" % used)

        # 2. the gyroid's save
        e2 = text(2, 5000)
        set_ext(e2)
        result, frames, c = run(2)
        check(result == 1, "gyroid's save: done in %d frames (result %d)" % (frames, result))
        s0, s1 = slots()
        saved = check_slot(s0, e2, "gyroid's save, slot 0")
        check_slot(s1, e2, "gyroid's save, slot 1")
        compressed_image = s0

        # 4. the load, from that compressed slot: clobber the extension and a byte of the save first
        set_ext(b"")
        n64.write(ext_at, bytes(64))
        poke = cd + 0x9F18
        before = n64.read(poke, 1)
        n64.write(poke, bytes([before[0] ^ 0xFF]))
        result, frames, c = run(4)
        check(result == 1, "load: done (result %d)" % result)
        size = struct.unpack(">i", n64.read(ext_size_at, 4))[0]
        check(size == len(e2) and n64.read(ext_at, size) == e2, "load: the extension comes back (%d bytes)" % size)
        check(n64.read(cd, 0xF980) == saved, "load: common_data is the saved save")

        # 3. the save menu's synchronous write, in play: no room in the game state's heap
        set_ext(text(3, 1000))
        result, frames, c = run(3)
        check(result == 0, "save menu's write: done (result %d)" % result)
        s0, s1 = slots()
        check(s0[:4] != b"AFZ1" and s0[:0xF980] == n64.read(cd, 0xF980) and checksum_ok(s0[:0xF980]),
              "save menu's write: the cartridge's format, common_data stamped")
        check(s1 == b"\xff" * 0x10000, "save menu's write: slot 1 erased with the chip")
        n64.screenshot(os.path.join(out, "after.png"))
    return compressed_image, saved, e2


def boot(emu_dir, rom_path, af_en, out):
    """5. A fresh boot: slot 0 compressed, slot 1 erased (its own process: the
    emulator's plugins start once per process)."""
    rom = open(rom_path, "rb").read()
    image = open(os.path.join(out, "slot0.bin"), "rb").read()
    saved = open(os.path.join(out, "save.bin"), "rb").read()
    ext = open(os.path.join(out, "ext.bin"), "rb").read()
    syms = elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    cart = os.path.join(out, "boot")
    shutil.rmtree(cart, ignore_errors=True)
    os.makedirs(cart)
    write_fla(fla_path(cart, rom), image + b"\xff" * 0x10000)
    with N64(emu_dir, rom, cart, timeout=120) as n64:
        n64.frames(400, 0)                       # the trademark screen checks and repairs the slots
        img = read_fla(fla_path(cart, rom))
        check(img[:0x10000] == image and img[0x10000:0x20000] == image,
              "boot: slot 1 repaired with slot 0's image as it is (frame %d)" % n64.frame)
        for i in range(40):
            n64.frames(100, A if i % 2 else START)
            n64.frames(20, 0)
        n64.screenshot(os.path.join(out, "boot.png"))
        size = struct.unpack(">i", n64.read(syms["mFRm_en_extSize"], 4))[0]
        check(size == len(ext) and n64.read(syms["mFRm_en_ext"], size) == ext,
              "boot: the extension is loaded (%d bytes)" % size)
        ram = n64.read(syms["common_data"], 0x20)
        check(ram[4:10] == saved[4:10], "boot: the save is loaded (code and town id)")
        img = read_fla(fla_path(cart, rom))
        if img[:0x10000] != image:               # the game saved on its own on the way (the arrival)
            got, got_ext = aflz.unpack(img[:0x10000])
            check(got_ext == ext and img[:0x10000] == img[0x10000:0x20000],
                  "boot: the game's own save on the way kept the extension, in both slots")


if __name__ == "__main__":
    if sys.argv[1] == "--boot":
        boot(*sys.argv[2:6])
        print("emu-save, the boot: %s" % ("passed" if not failures else "%d failed" % len(failures)))
        sys.exit(1 if failures else 0)
    else:
        image, saved, ext = main(sys.argv[1:])
        out = sys.argv[5]
        for name, data in (("slot0.bin", image), ("save.bin", saved), ("ext.bin", ext)):
            open(os.path.join(out, name), "wb").write(data)
        print("  --    (the boot, in a process of its own)")
        r = subprocess.run([sys.executable, os.path.abspath(__file__), "--boot", sys.argv[1], sys.argv[2], sys.argv[3], out])
        if r.returncode:
            failures.append("boot")
    print("emu-save: %s" % ("all passed" if not failures else "%d failed" % len(failures)))
    sys.exit(1 if failures else 0)
