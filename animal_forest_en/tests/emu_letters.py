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
     one: the GameCube's longest parts make 184);
  4. the extension (mFRm_en_ext): from what the game filed on its way to
     the houses, through all of that filing and over the cap, it holds
     what a Python model of its records says; then, from
     empty, letters loaded straight into the save's letters (pockets,
     mailbox) as the game's callers do are filed under their own bytes, a
     letter with nothing past them is not, a copy finds its rest
     (mHandbill_en_GetLetter gives the whole letter back), a letter the
     save does not hold goes at the next save, the save's slots hold the
     rest of the ones it does, a letter cleared from the save loses its
     rest at the save after, and the load brings the extension back.
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
import aflz  # noqa: E402

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
s32 mHandbill_en_GetLetter(void* content, u8* header, u8* body, u8* footer);
s32 func_80090044_jp(void);
s32 func_8008F968_jp(void);
void bcopy(const void* src, void* dst, int size);

typedef struct HookCtl {
    s32 cmd;
    s32 result;
    s32 arg[5];
    s32 back;
    u8 header[32];
    u8 footer[32];
    u8 body[96];
    u8 whole_header[24];
    u8 whole_body[192];
    u8 whole_footer[32];
    s32 frames;
} HookCtl;

/* Mail_c: content at 0x26 (headerBackStart 0x27, header 0x2A, body 0x34, footer 0x94) */

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
        case 3: { /* a shop letter into a Mail_c, as mPr_GetMotherMail */
            u8* mail = (u8*)h->arg[1];
            s32 back;

            mHandbill_Load_HandbillFromRom(mail + 0x2A, &back, mail + 0x94, mail + 0x34, h->arg[0]);
            mail[0x27] = back;
            h->cmd = 0;
            break;
        }
        case 4: { /* a villager's letter into a Mail_c, as mNpc_GetHandbillz */
            u8* mail = (u8*)h->arg[0];

            info.superBuf = h->header;
            info.superBufSize = 20;
            info.mailBuf = mail + 0x34;
            info.mailBufSize = 96;
            info.psBuf = h->footer;
            info.psBufSize = 26;
            info.superNo = h->arg[1];
            info.mailANo = h->arg[2];
            info.mailBNo = h->arg[3];
            info.mailCNo = h->arg[4];
            info.psNo = h->back;
            h->result = mHandbillz_load(&info);
            if (h->result) {
                mail[0x27] = info.headerBackStart;
                bcopy(h->header, mail + 0x2A, 10);
                bcopy(h->footer, mail + 0x94, 16);
            }
            h->cmd = 0;
            break;
        }
        case 5: /* a letter whole */
            h->result = mHandbill_en_GetLetter((u8*)h->arg[0] + 0x26, h->whole_header, h->whole_body, h->whole_footer);
            h->cmd = 0;
            break;
        case 6: /* the normal save, a step a frame */
            h->frames++;
            h->result = func_80090044_jp();
            if (h->result != 0) {
                h->cmd = 0;
            }
            break;
        case 7: /* the load */
            h->result = func_8008F968_jp();
            h->cmd = 0;
            break;
    }
}
"""

CTL = struct.Struct(">ii5Ii32s32s96s24s192s32si")
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


# -- the model of the extension (m_handbill.c, "The letters' extension") ----------

EXT_CAP = 0x2400
MAIL_SIZE = 0xA4
SAVE_MAIL = ([0x20 + p * 0xBD0 + 0x40A + k * MAIL_SIZE for p in range(4) for k in range(10)] +      # pockets
             [0x3588 + h * 0xB48 + 0x478 + k * MAIL_SIZE for h in range(4) for k in range(10)] +   # mailboxes
             [0xEF6C + k * MAIL_SIZE for k in range(5)] + [0xF2A0, 0xF344])                        # post office


def fnv(data):
    key = 0x811C9DC5
    for c in data:
        key = ((key ^ c) * 0x01000193) & 0xFFFFFFFF
    return key


def mail_key(mail):
    """A Mail_c's bytes: its header, body and footer."""
    return fnv(mail[0x2A:0x34] + mail[0x34:0x94] + mail[0x94:0xA4])


class Ext:
    def __init__(self):
        self.records = []                        # (key, header rest, body rest, footer rest), oldest first

    @classmethod
    def parse(cls, data):
        ext = cls()
        pos = 2
        while data[:2] == b"LX" and pos + 7 <= len(data):
            k, h, b, f = struct.unpack(">IBBB", data[pos:pos + 7])
            at = pos + 7
            ext.records.append((k, data[at:at + h], data[at + h:at + h + b], data[at + h + b:at + h + b + f]))
            pos = at + h + b + f
        return ext

    def size(self):
        return 2 + sum(7 + len(h) + len(b) + len(f) for _, h, b, f in self.records)

    def file(self, whole_header, whole_body, whole_footer, save_keys):
        key = fnv(whole_header[:10] + whole_body[:96] + whole_footer[:16])
        rest = (whole_header[10:24].rstrip(b" "), whole_body[96:192].rstrip(b"\xcd"), whole_footer[16:32].rstrip(b" "))
        self.records = [r for r in self.records if r[0] != key]
        if not any(rest):
            return
        size = 7 + sum(len(x) for x in rest)
        if self.size() + size > EXT_CAP:
            self.collect(save_keys)
        while self.size() + size > EXT_CAP and self.records:
            self.records.pop(0)
        self.records.append((key,) + rest)

    def collect(self, save_keys):
        self.records = [r for r in self.records if r[0] in save_keys]

    def rest(self, key):
        return next(((h, b, f) for k, h, b, f in self.records if k == key), None)

    def bytes(self):
        return b"LX" + b"".join(struct.pack(">IBBB", k, len(h), len(b), len(f)) + h + b + f
                                 for k, h, b, f in self.records)


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
    cd, ext_at, ext_size_at = syms["common_data"], syms["mFRm_en_ext"], syms["mFRm_en_extSize"]
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

        def run(cmd, args, back=-1, limit=10):
            n64.write(ctl, CTL.pack(cmd, 0, *(list(args) + [0] * (5 - len(args))), back,
                                    b"\xee" * 32, b"\xee" * 32, b"\xee" * 96, b"\xee" * 24, b"\xee" * 192,
                                    b"\xee" * 32, 0))
            for _ in range(limit):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    break
            got = CTL.unpack(n64.read(ctl, CTL.size))
            whole = n64.read(letter_at, 0xFC)
            return got, whole

        def ext_in_ram():
            size = struct.unpack(">i", n64.read(ext_size_at, 4))[0]
            return n64.read(ext_at, size) if size > 0 else b""

        def save_keys():
            return set(mail_key(n64.read(cd + at, MAIL_SIZE)) for at in SAVE_MAIL)

        keys = save_keys()
        ext = Ext.parse(ext_in_ram())             # what the game filed on its way to the houses
        held = sum(1 for r in ext.records if r[0] in keys)
        check(ext.bytes() == ext_in_ram() or not ext_in_ram(),
              "at the houses the game has filed %d letters' rests (%d bytes), %d of them for letters in the save"
              % (len(ext.records), len(ext_in_ram()), held))

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
            ext.file(whole[:0x18], whole[0x1C:0xDC], whole[0xDC:0xFC], keys)
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
            if fits:
                ext.file(whole[:0x18], whole[0x1C:0xDC], whole[0xDC:0xFC], keys)
        check(not bad, "villagers' letters: %d draws as the model makes them (%d differ: %s)" % (
            len(draws), len(bad), bad[:4]))
        if too_long:
            check(fails == 1, "a villager's body past 192 bytes fails the letter (%s)" % too_long)
        else:
            print("      no villager's body can pass 192 bytes with these banks (the longest parts make %d)" % most)

        # 4. the extension
        got = ext_in_ram()
        check(got == ext.bytes(), "after %d letters filed, the extension is the model's (%d bytes, %d records, cap %#x)"
              % (544 + len(draws), len(got), len(ext.records), EXT_CAP))

        def mail_at(i):
            return cd + SAVE_MAIL[i]

        def whole_of(i):
            """The model's whole letter for save letter i: its bytes, then its rest."""
            mail = n64.read(mail_at(i), MAIL_SIZE)
            rest = ext.rest(mail_key(mail)) or (b"", b"", b"")
            return ((mail[0x2A:0x34] + rest[0]).ljust(24, b" "), (mail[0x34:0x94] + rest[1]).ljust(192, b"\xcd"),
                    (mail[0x94:0xA4] + rest[2]).ljust(32, b" "), ext.rest(mail_key(mail)) is not None)

        def file_from_ram():
            whole = n64.read(letter_at, 0xFC)
            ext.file(whole[:0x18], whole[0x1C:0xDC], whole[0xDC:0xFC], save_keys())

        n64.write(ext_size_at, struct.pack(">i", 0))
        ext = Ext()
        long_ones = [n for n in range(544) if len(body(entry("mail_body", n), frees).rstrip(b"\xcd")) > 96][:3]
        no_rest = next(n for n in range(544) if entry("mail_body", n) and
                       len(body(entry("mail_body", n), frees).rstrip(b"\xcd")) <= 96 and
                       len(footer(entry("mail_footer", n), frees).rstrip(b" ")) <= 16 and
                       (header(entry("mail_header", n), frees) or (b" " * 24,))[0][10:24].strip(b" ") == b"")
        for i in range(87):                     # the save's letters start blank here
            n64.write(mail_at(i), bytes(MAIL_SIZE))
        run(3, [long_ones[0], mail_at(0)])       # pocket 0
        file_from_ram()
        run(3, [long_ones[1], mail_at(1)])       # pocket 1
        file_from_ram()
        run(4, [mail_at(2)] + draws[0][:4], back=draws[0][4])   # pocket 2, a villager's
        file_from_ram()
        run(3, [no_rest, mail_at(3)])            # pocket 3: nothing past the letter's own bytes
        file_from_ram()
        check(ext_in_ram() == ext.bytes() and len(ext.records) == 3,
              "letters loaded into the save are filed under their own bytes, one with no rest is not (%d records)"
              % len(ext.records))
        n64.write(mail_at(40), n64.read(mail_at(0), MAIL_SIZE))      # a copy in mailbox 0, as the game moves letters
        bad = []
        for i in (0, 1, 2, 3, 40):
            got, _ = run(5, [mail_at(i)])
            want = whole_of(i)
            if (got[11], got[12], got[13], got[1]) != (want[0], want[1], want[2], int(want[3])):
                bad.append(i)
        check(not bad, "the whole letters come back, the copy's too (%s differ)" % bad)
        run(1, [long_ones[2]])                    # a letter the save does not hold
        file_from_ram()
        check(ext_in_ram() == ext.bytes() and len(ext.records) == 4, "a letter outside the save is filed too")

        def slots():
            image = emu_save.read_fla(emu_save.fla_path(os.path.join(out, "cart"), rom))
            return [aflz.unpack(image[k * 0x10000:(k + 1) * 0x10000])[1] for k in (0, 1)]

        got, _ = run(6, [], limit=600)
        ext.collect(save_keys())
        flash = slots()
        check(got[1] == 1 and ext_in_ram() == ext.bytes() and len(ext.records) == 3 and
              flash == [ext.bytes(), ext.bytes()],
              "the save drops the letter it does not hold and keeps the rest of the others, in both slots "
              "(%d records, %d bytes)" % (len(ext.records), len(ext.bytes())))
        n64.write(mail_at(1), bytes(MAIL_SIZE))   # pocket 1's letter thrown away
        got, _ = run(6, [], limit=600)
        ext.collect(save_keys())
        check(got[1] == 1 and slots() == [ext.bytes()] * 2 and len(ext.records) == 2,
              "a letter cleared from the save loses its rest at the next save")
        n64.write(ext_size_at, struct.pack(">i", 0))
        got, _ = run(7, [])
        whole, _ = run(5, [mail_at(40)])
        check(got[1] == 1 and ext_in_ram() == ext.bytes() and whole[1] == 1 and whole[12] == whole_of(40)[1],
              "the load brings the extension back, and the copy's whole letter with it")

    print("emu-letters: %s" % ("all passed" if not failures else "%d failed" % len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
