#!/usr/bin/env python3
"""Drive the en build's letter loaders in the emulator and check what they make.

    tests/emu_letters.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). A hook
(tests/emu_save.py's way: IDO-compiled, in the hole m_choice_main's text
left in code, called each frame from mTM_time) calls the game's own
loaders: mHandbill_Load_HandbillFromRom for every shop letter, as the
mother's letter and the shops' callers do, and mHandbillz_load for a set
of villagers' letters, as mNpc_GetHandbillz does (header and footer
buffers of 20 and 26 bytes, the body straight into a 96-byte buffer).

What they should make comes from the en build's own letter files
(AF_EN_DIR/assets/jp/en/mail_text.bin, mail_index.bin) through a Python
model of the cartridge's expander (m_handbill.c: the header's newline
taken out, the free strings put in, padding) at the en build's lengths
(reference/NOTES.md, "The letters in the en build"). The free strings are
written first, twenty known ones, some filling the 10 bytes. Checks:

  1. each shop letter's header (10 bytes), name position, footer (16) and
     body (96) are the whole letter's first bytes;
  2. the whole letter the loaders keep (mHandbill_en_letter: header 24,
     body 192, footer 32) is the model's;
  3. the same for the villagers' letters, and a body whose three parts
     pass 192 bytes fails as the GameCube's does (when the banks have
     one: the GameCube's longest parts make 184).
"""

import os
import random
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
from n64emu import N64  # noqa: E402
import emu_save  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
typedef unsigned int u32;

typedef struct HandbillzInfo {
    u8* superBuf;
    u32 superBufSize;
    u8* mailBuf;
    u32 mailBufSize;
    u8* psBuf;
    u32 psBufSize;
    s32 superNo;
    s32 mailANo;
    s32 mailBNo;
    s32 mailCNo;
    s32 psNo;
    s32 headerBackStart;
} HandbillzInfo;

void mHandbill_Load_HandbillFromRom(u8* header, s32* header_back_start, u8* footer, u8* body, s32 mail_no);
s32 mHandbillz_load(HandbillzInfo* info);

typedef struct HookCtl {
    s32 cmd;
    s32 result;
    s32 arg[5];
    s32 back;
    u8 header[32];
    u8 footer[32];
    u8 body[96];
} HookCtl;

HookCtl hook_ctl;

void hook_main(void) {
    HookCtl* h = &hook_ctl;
    HandbillzInfo info;

    switch (h->cmd) {
        case 1: /* a shop letter, as mPr_GetMotherMail loads one */
            mHandbill_Load_HandbillFromRom(h->header, &h->back, h->footer, h->body, h->arg[0]);
            h->cmd = 0;
            break;
        case 2: /* a villager's letter, as mNpc_GetHandbillz loads one */
            info.superBuf = h->header;
            info.superBufSize = 20;
            info.mailBuf = h->body;
            info.mailBufSize = 96;
            info.psBuf = h->footer;
            info.psBufSize = 26;
            info.superNo = h->arg[0];
            info.mailANo = h->arg[1];
            info.mailBNo = h->arg[2];
            info.mailCNo = h->arg[3];
            info.psNo = h->arg[4];
            info.headerBackStart = -1;
            h->result = mHandbillz_load(&info);
            h->back = info.headerBackStart;
            h->cmd = 0;
            break;
    }
}
"""

CTL = struct.Struct(">ii5ii32s32s96s")
FILL_NONE, FILL_RETURN, FILL_SPACE = 0, 1, 2
FREE_CODES = dict([(36 + i, i) for i in range(10)] + [(54 + i, 10 + i) for i in range(10)])
SPACE, NEWLINE = 0x20, 0xCD
FIRST = {"mail_header": 0, "mail_body": 544, "mail_footer": 1088, "vmail_header": 1632,
         "vmail_a": 2016, "vmail_b": 2400, "vmail_c": 2784, "vmail_footer": 3168}

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


# -- the model: src/code/m_handbill.c's expander, byte for byte ---------------

def move_data_cut(data, buf_size, dst_idx, src_idx, data_len, fill):
    """func_80092E80_jp."""
    new_len = data_len
    if dst_idx < src_idx:
        while src_idx < data_len:
            data[dst_idx] = data[src_idx]
            dst_idx += 1
            src_idx += 1
        new_len -= src_idx - dst_idx
        if fill != FILL_NONE:
            ch = NEWLINE if fill == FILL_RETURN else SPACE
            while dst_idx < data_len:
                data[dst_idx] = ch
                dst_idx += 1
    elif dst_idx > src_idx:
        move_size = data_len - src_idx
        new_len += dst_idx - src_idx
        if new_len > buf_size:
            data_len -= new_len - buf_size
            move_size -= new_len - buf_size
            new_len = buf_size
        d, s = new_len - 1, data_len - 1
        for _ in range(move_size):
            data[d] = data[s]
            d -= 1
            s -= 1
    return new_len


def put_free(data, buf_size, start, str_len, free, fill):
    """func_80092FC4_jp (a free string's code is 2 bytes)."""
    s = free.rstrip(b" ")
    n = len(s)
    cut = move_data_cut(data, buf_size, start + n, start + 2, str_len, fill)
    if cut >= buf_size and n > buf_size - start:
        n = buf_size - start
    data[start:start + n] = s[:n]
    return cut


def expand(data, buf_size, str_len, frees, fill, back=None):
    """func_80093478_jp, or func_80093520_jp with the header's name position."""
    length, pos = str_len, 0
    while pos < length:
        if data[pos] == 0x7F:
            before = length
            length = put_free(data, buf_size, pos, length, frees[FREE_CODES[data[pos + 1]]], fill)
            if back is not None and pos < back:
                back += length - before
        else:
            pos += 1
    return back


def border(dst, dst_size, src):
    """func_800939B8_jp: the header without its newline, and where it was."""
    lines = back = dst_pos = 0
    for src_pos, c in enumerate(src):
        if c == NEWLINE:
            lines += 1
            back = src_pos
        elif dst_pos < dst_size:
            dst[dst_pos] = c
            dst_pos += 1
    return back if lines == 1 else len(src)


def header(raw, frees):
    """(the 43-byte header, its name position), or None for an empty entry."""
    if not raw:
        return None
    buf = bytearray(b" " * 43)
    back = border(buf, 43, raw)
    back = expand(buf, 43, len(raw) - 1, frees, FILL_SPACE, back)
    return bytes(buf), back


def footer(raw, frees):
    buf = bytearray(b" " * 32)
    buf[:len(raw)] = raw
    expand(buf, 32, len(raw), frees, FILL_SPACE)
    return bytes(buf)


def body(raw, frees):
    buf = bytearray(bytes([NEWLINE]) * 192)
    buf[:len(raw)] = raw
    expand(buf, 192, len(raw), frees, FILL_RETURN)
    return bytes(buf)


def entries(af_en):
    text = open(os.path.join(af_en, "assets", "jp", "en", "mail_text.bin"), "rb").read()
    index = open(os.path.join(af_en, "assets", "jp", "en", "mail_index.bin"), "rb").read()
    ends = struct.unpack(">3552I", index[:3552 * 4])
    return lambda bank, n: text[(ends[FIRST[bank] + n - 1] if FIRST[bank] + n else 0):ends[FIRST[bank] + n]]


def main(argv):
    emu_dir, rom_path, af_en, state, out = argv
    af_en = os.path.abspath(af_en)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    rom = open(rom_path, "rb").read()
    en_elf = os.path.join(af_en, "build", "animalforest-jp.elf")
    syms = emu_save.elf_symbols(en_elf)
    ref_map = os.path.join(os.path.dirname(af_en), "af", "build", "animalforest-jp.map")
    hole, hole_size = emu_save.section_of(ref_map, "build/src/code/m_choice_main.o", ".text")
    mttime, letter_at, free_at = syms["mTM_time"], syms["mHandbill_en_letter"], syms["B_80140680_jp"]
    entry = entries(af_en)
    rnd = random.Random(5)
    frees = []
    for i in range(20):                          # known free strings; some fill all ten bytes
        n = 10 if i % 3 == 0 else rnd.randrange(1, 10)
        frees.append(bytes(rnd.choice(b"ABCDEFGHIJKLMNOPQRSTUVWXYZ") for _ in range(n)).ljust(10, b" "))

    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        n64.load_state(state)
        words = struct.unpack(">II", n64.read(mttime, 8))
        check(n64.read(hole, hole_size) == bytes(hole_size), "the hole at %#x (%#x bytes) is empty" % (hole, hole_size))
        blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
        emu_save.write_code(n64, hole, blob)
        emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
        n64.write(free_at, b"".join(frees))

        def run(cmd, args):
            n64.write(ctl, CTL.pack(cmd, 0, *(list(args) + [0] * (5 - len(args))), -1,
                                    b"\xee" * 32, b"\xee" * 32, b"\xee" * 96))
            for _ in range(10):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    break
            got = CTL.unpack(n64.read(ctl, CTL.size))
            whole = n64.read(letter_at, 0xFC)
            return got, whole

        # 1-2. every shop letter
        bad = []
        for n in range(544):
            got, whole = run(1, [n])
            h = header(entry("mail_header", n), frees)
            f, b = footer(entry("mail_footer", n), frees), body(entry("mail_body", n), frees)
            want_header = h[0][:10] if h else b" " * 10
            want_back = h[1] if h else -1
            ok = (got[0] == 0 and got[8][:10] == want_header and got[7] == want_back and
                  got[9][:16] == f[:16] and got[10] == b[:96] and
                  whole[0x1C:0xDC] == b and whole[0xDC:0xFC] == f and
                  (h is None or (whole[:0x18] == h[0][:24] and struct.unpack(">i", whole[0x18:0x1C])[0] == h[1])))
            if not ok:
                bad.append(n)
        check(not bad, "shop letters: all 544 as the model makes them (%d differ: %s)" % (len(bad), bad[:8]))
        longer = sum(1 for n in range(544) if len(body(entry("mail_body", n), frees).rstrip(b"\xcd")) > 96)
        print("      %d of the 544 bodies run past the letter's 96 bytes (the rest is the extension's)" % longer)

        # 3. villagers' letters: sixty draws as mNpc_SetRemail's (a group of 32), and one that does not fit
        draws = []
        for k in range(60):
            base = rnd.randrange(0, 384 - 32) // 32 * 32
            draws.append([base + rnd.randrange(32) for _ in range(5)])
        longest = [max(range(384), key=lambda n: len(entry(bank, n))) for bank in ("vmail_a", "vmail_b", "vmail_c")]
        most = sum(len(entry(bank, n)) for bank, n in zip(("vmail_a", "vmail_b", "vmail_c"), longest))
        too_long = [0] + longest + [0] if most > 192 else None
        bad, fails = [], 0
        for args in draws + ([too_long] if too_long else []):
            got, whole = run(2, args)
            h = header(entry("vmail_header", args[0]), frees)
            parts = [entry(bank, args[i + 1]) for i, bank in enumerate(("vmail_a", "vmail_b", "vmail_c"))]
            f = footer(entry("vmail_footer", args[4]), frees)
            fits = sum(len(p) for p in parts) <= 192
            fails += not fits
            ok = got[1] == int(fits) and got[9][:26] == f[:26] and whole[0xDC:0xFC] == f
            if h:
                ok = ok and got[8][:20] == h[0][:20] and got[7] == h[1] and whole[:0x18] == h[0][:24]
            if fits:
                b = body(b"".join(parts), frees)
                ok = ok and got[10] == b[:96] and whole[0x1C:0xDC] == b
            else:
                ok = ok and got[10] == b"\xee" * 96          # the caller's body is not touched
            if not ok:
                bad.append(args)
        check(not bad, "villagers' letters: %d draws as the model makes them (%d differ: %s)" % (
            len(draws), len(bad), bad[:4]))
        if too_long:
            check(fails == 1, "a villager's body past 192 bytes fails the letter (%s)" % too_long)
        else:
            print("      no villager's body can pass 192 bytes with these banks (the longest parts make %d)" % most)

    print("emu-letters: %s" % ("all passed" if not failures else "%d failed" % len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
