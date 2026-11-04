#!/usr/bin/env python3
"""Open the en build's letter board on letters and measure what it draws.

    tests/emu_board.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). For each
letter, from that state, a hook (tests/emu_save.py's way) loads it into
the player's first pocket letter as the game's callers do (a shop letter
as mPr_GetMotherMail, a villager's as mNpc_GetHandbillz: tests/
emu_letters.py's model of both), then opens the board to read it, as
mSM_submenu_ctrl opens a submenu program (the board, program 12, with
menuInfo data0 1: read; m_board_ovl.c's init). The player's name and the
twenty free strings are set first, to known text.

What the board should draw comes from a model of the en board
(m_handbill.c, mHandbill_en_Board*) over tests/emu_letters.py's model of
the whole letter: the header with the name put in at its position (a
letter of mail type 2, 3 or 5 keeps its header as it is), the body broken
into lines at the board's 192 pixels after the last space that fits, its
own newlines kept, six lines, the footer ending at the body's right edge.
Widths and glyphs are the en build's own font file and cut table
(AF_EN_DIR/assets/jp/segment_00BCD000.bin, en/font_cut.inc: made from the
user's dump and disc, tools/af_font.py).

Measured on the frame (angrylion's 640x240), in bands of 16 lines at
the header's, the six body lines' and the footer's places (the board at
rest, the airmail paper): the text's pixels, told by their colour (the
text's brown-purple has red over blue over green; the paper is grey, the
ruled lines blue, the postmark brown-grey with blue and green equal: a
brightness threshold cannot tell a thin stroke from the postmark). Each
band's leftmost and rightmost text pixel must be where the model's first
and last characters' inked columns (a glyph's pixels at least half
strength) fall, within 2 frame pixels; an empty line has none, and no
text lies outside the bands. Where: the board draws its text about 2%
narrower than the message window does (the window's text is 2 frame
pixels to a pixel, measured on Nook's first lines; the board's is 1.954,
around the screen's centre, measured on these boards: BOARD_X; why is
TODO(verify): the game's viewport is the whole screen and the font's
projection 16 units to the pixel). Writes each board's screenshot, and a
sheet of them, to OUT_DIR, for looking at.
"""

import os
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64  # noqa: E402
import contact  # noqa: E402
import emu_letters  # noqa: E402
import emu_save  # noqa: E402
import af_font  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef unsigned short u16;
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

void mPr_GetMotherMail(void* mail, void* pid, u16 present, s32 stationery, s32 mailNumber);
s32 mHandbillz_load(HandbillzInfo* info);
void mSM_open_submenu_new(void* submenu, s32 programId, s32 arg2, s32 arg3, s32 arg4);
void SetGameFrame(s32 divisor);
void bcopy(const void* src, void* dst, int size);
extern void* gamePT;

typedef struct HookCtl {
    s32 cmd;
    s32 result;
    s32 arg[6];
    u8 header[20];
    u8 footer[26];
} HookCtl;

HookCtl hook_ctl;

/* Mail_c: content at 0x26 (font, headerBackStart 0x27, mailType 0x28, paperType 0x29, header 0x2A,
   body 0x34, footer 0x94); Game_Play: submenu at 0x1CBC (programId +4, moveProcIndex +0xC) */
void hook_main(void) {
    HookCtl* h = &hook_ctl;
    u8* mail = (u8*)h->arg[0];
    HandbillzInfo info;

    switch (h->cmd) {
        case 1: /* a shop letter, as mPr_GetMotherMail; then its mail type */
            mPr_GetMotherMail(mail, (void*)h->arg[1], 0, 0, h->arg[2]);
            mail[0x28] = h->arg[3];
            h->cmd = 0;
            break;
        case 2: /* a villager's letter, as mNpc_GetHandbillz, into a letter addressed as case 1's */
            mPr_GetMotherMail(mail, (void*)h->arg[1], 0, 0, 0);
            info.superBuf = h->header;
            info.superBufSize = 20;
            info.mailBuf = mail + 0x34;
            info.mailBufSize = 96;
            info.psBuf = h->footer;
            info.psBufSize = 26;
            info.superNo = h->arg[2] & 0xFFFF;
            info.mailANo = h->arg[2] >> 16;
            info.mailBNo = h->arg[3] & 0xFFFF;
            info.mailCNo = h->arg[3] >> 16;
            info.psNo = h->arg[4];
            h->result = mHandbillz_load(&info);
            mail[0x27] = info.headerBackStart;
            mail[0x28] = 0;
            bcopy(h->header, mail + 0x2A, 10);
            bcopy(h->footer, mail + 0x94, 16);
            h->cmd = 0;
            break;
        case 3: { /* the board on the letter, to read it */
            s32* submenu = (s32*)((u8*)gamePT + 0x1CBC);

            if (submenu[3] == 0) {
                mSM_open_submenu_new(submenu, 12, 1, 0, (s32)mail);
                submenu[3] = 1;
                submenu[0] = 1;
                SetGameFrame(2);
                h->cmd = 0;
            }
            break;
        }
    }
}
"""

CTL = struct.Struct(">ii6I20s26s")
PRIVATE_INFO = 0x10138                    # common_data.privateInfo (m_common_data.h)
POCKET_MAIL = 0x40A                       # PrivateInfo.mail[0] (m_private.h)
PLAYER = b"Jeff  "
FREES = [b"Tom Nook", b"Rover", b"Bob", b"Pelly", b"Tortimer", b"Wendell", b"Copper", b"Booker", b"K.K.", b"Blathers"]
FREES = [f.ljust(10, b" ") for f in FREES * 2]
SPACE, NEWLINE = 0x20, 0xCD
BOARD_WIDTH, BOARD_LINES = 192, 6
# The board at rest (measured on the airmail paper, frames 90 and 96 after
# it opens equal): where each band's 16 lines start; the frame's x of the
# text's left edge (x 64 = 160 - 96), and frame pixels to the pixel.
HEADER_Y, BODY_Y, FOOTER_Y, LINE_H = 37, 64, 172, 16
BOARD_X = (131.6, 1.954)
TOLERANCE = 2.0                            # frame pixels (the VI filter, the text's resampling)
TEXT_HUE = 6                               # blue over green and red over blue by this much: text
STRONG = 8                                 # a glyph's pixel at least this (of 15) inks the column
PAPER_X = range(96, 544)                   # the text's part of the paper, in the frame
SETTLE = 90
# Shop letters: (mail number, mail type); villagers' letters: (header, a, b, c, footer)
SHOP = [(205, 4), (215, 4), (51, 2), (51, 4), (102, 4), (528, 4), (430, 4), (4, 4), (503, 4)]
VILLAGERS = [(3, 40, 77, 101, 9), (200, 210, 230, 250, 20)]

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def read_cut(af_en):
    text = open(os.path.join(af_en, "assets", "jp", "en", "font_cut.inc")).read().split("\n", 1)[1]
    cut = [int(n.rstrip(",")) for n in text.split()]
    assert len(cut) == 256
    return cut


class Board:
    """The en board's model: lines of (text, left, right) in N64 pixels from the text's left edge."""

    def __init__(self, seg, cut):
        self.w = af_font.widths(cut)
        self.ink = []
        for c in range(256):
            rows = [af_font._row(seg, c, y)[0] for y in range(16)]
            self.ink.append([x for x in range(12) if max(r[x] for r in rows) >= STRONG])

    def width(self, s):
        return sum(self.w[c] for c in s)

    def extent(self, s, x=0):
        """(left, right) of the ink of s drawn at x, or None."""
        pos, left, right = x, None, None
        for c in s:
            cols = [k for k in self.ink[c] if k < self.w[c]]
            if cols:
                left = pos + cols[0] if left is None else left
                right = pos + cols[-1]
            pos += self.w[c]
        return None if left is None else (left, right)

    def line(self, s, rest):
        """mHandbill_en_BoardLine: (length drawn, length consumed)."""
        width = n = 0
        space = -1
        while n < rest and s[n] != NEWLINE:
            w = self.w[s[n]]
            if s[n] == SPACE:
                space = n
            if n > 0 and width + w > BOARD_WIDTH:
                return (space, space + 1) if space > 0 else (n, n)
            width += w
            n += 1
        return n, (n + 1 if n < rest else n)

    def body(self, body):
        n = len(body)
        while n and body[n - 1] in (NEWLINE, SPACE):
            n -= 1
        s, rest, out = body, n, []
        for _ in range(BOARD_LINES):
            drawn, used = self.line(s, rest) if rest > 0 else (0, 0)
            out.append(s[:drawn])
            s, rest = s[used:], rest - used
        return out, rest > 0

    def header(self, header, back, mail_type, name):
        back = min(back, 24)
        length = max(len(header[:24].rstrip(b" ")), back)
        if mail_type in (2, 3, 5):
            return header[:length]
        return header[:back] + name + header[back:length]

    def footer(self, footer):
        f = footer[:32].lstrip(b" ").rstrip(b" ")
        return f, BOARD_WIDTH - self.width(f)


def is_text(rgb, w, x, y):
    r, g, b = rgb[(y * w + x) * 3:(y * w + x) * 3 + 3]
    return b - g >= TEXT_HUE and r - b >= TEXT_HUE


def measure(rgb, w, y0):
    """(left, right) of the text in the band of 16 lines from y0, in frame pixels (right exclusive), or None."""
    xs = [x for y in range(y0, y0 + LINE_H) for x in PAPER_X if is_text(rgb, w, x, y)]
    return (min(xs), max(xs) + 1) if xs else None


def frame_x(x):
    """The frame's x of the board's x pixels right of its text's left edge."""
    return BOARD_X[0] + BOARD_X[1] * x


def stray_ink(rgb, w, h):
    """Rows with ink outside the bands, between the header's and below the footer's."""
    bands = set(range(HEADER_Y, HEADER_Y + LINE_H)) | set(range(BODY_Y, BODY_Y + BOARD_LINES * LINE_H)) | \
        set(range(FOOTER_Y, FOOTER_Y + LINE_H))
    out = []
    for y in range(HEADER_Y, FOOTER_Y + LINE_H + 8):
        if y in bands:
            continue
        if any(is_text(rgb, w, x, y) for x in PAPER_X):
            out.append(y)
    return out


def letter(entry, kind, which):
    """(header, its name position, body, footer) of the whole letter, the models of tests/emu_letters.py."""
    if kind == "shop":
        h = emu_letters.header(entry("mail_header", which), FREES)
        body = emu_letters.body(entry("mail_body", which), FREES)
        foot = emu_letters.footer(entry("mail_footer", which), FREES)
    else:
        hd, a, b, c, ft = which
        h = emu_letters.header(entry("vmail_header", hd), FREES)
        body = emu_letters.body(entry("vmail_a", a) + entry("vmail_b", b) + entry("vmail_c", c), FREES)
        foot = emu_letters.footer(entry("vmail_footer", ft), FREES)
    return (h[0][:24], h[1]) if h else (b" " * 24, 24), body, foot


def expected(board, entry, kind, which, mail_type):
    """[(what, band's first line, (left, right) or None)] in N64 pixels from the text's left edge, and
    whether the body runs past six lines."""
    (head, back), body, foot = letter(entry, kind, which)
    lines, cut_off = board.body(body)
    want = [("header", HEADER_Y, board.extent(board.header(head, back, mail_type, PLAYER.rstrip(b" "))))]
    want += [("body line %d" % (k + 1), BODY_Y + k * LINE_H, board.extent(s)) for k, s in enumerate(lines)]
    f, fx = board.footer(foot)
    want.append(("footer", FOOTER_Y, board.extent(f, fx)))
    return want, cut_off, sum(1 for s in lines if s)


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
    mttime, cd, free_at = syms["mTM_time"], syms["common_data"], syms["B_80140680_jp"]
    seg = open(os.path.join(af_en, "assets", "jp", "segment_00BCD000.bin"), "rb").read()
    board = Board(seg, read_cut(af_en))
    entry = emu_letters.entries(af_en)
    shots = []

    letters = [("shop", n, t) for n, t in SHOP] + [("villager", v, 0) for v in VILLAGERS]
    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        for kind, which, mail_type in letters:
            n64.load_state(state)
            words = struct.unpack(">II", n64.read(mttime, 8))
            blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            n64.write(free_at, b"".join(FREES))
            priv = struct.unpack(">I", n64.read(cd + PRIVATE_INFO, 4))[0]
            n64.write(priv, PLAYER)
            mail = priv + POCKET_MAIL

            def run(cmd, args, limit=10):
                n64.write(ctl, CTL.pack(cmd, 0, *(list(args) + [0] * (6 - len(args))), bytes(20), bytes(26)))
                for _ in range(limit):
                    n64.frames(1, 0)
                    if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                        return CTL.unpack(n64.read(ctl, CTL.size))
                raise SystemExit("the hook did not run command %d" % cmd)

            if kind == "shop":
                label = "shop letter %d (type %d)" % (which, mail_type)
                run(1, [mail, priv, which, mail_type])
            else:
                hd, a, b, c, ft = which
                label = "villager's letter %s" % (which,)
                got = run(2, [mail, priv, hd | a << 16, b | c << 16, ft])
                check(got[1] == 1, "%s: mHandbillz_load takes it" % label)
            run(3, [mail], limit=30)
            n64.frames(SETTLE, 0)
            w, hgt, rgb = n64.screen()
            n64.frames(6, 0)
            check(n64.screen()[2] == rgb, "%s: the board is at rest %d frames after it opens" % (label, SETTLE))
            path = os.path.join(out, "%s-%s.png" % (kind, "-".join(str(x) for x in (which if kind != "shop" else (which, mail_type)))))
            n64.screenshot(path)
            shots.append(path)

            want, cut_off, drawn_lines = expected(board, entry, kind, which, mail_type)
            bad = []
            for what, y0, ext in want:
                got = measure(rgb, w, y0)
                want_x = ext and (frame_x(ext[0]), frame_x(ext[1] + 1))
                if ext is None:
                    ok = got is None
                else:
                    ok = got is not None and all(abs(g - e) <= TOLERANCE for g, e in zip(got, want_x))
                if not ok:
                    bad.append("%s: drawn at frame x %s, the model's %s" % (
                        what, got, want_x and "(%.1f, %.1f)" % want_x))
            stray = stray_ink(rgb, w, hgt)
            check(not bad and not stray, "%s: %d lines where the model has them%s%s%s" % (
                label, drawn_lines, " (the rest past six lines is not drawn)" if cut_off else "",
                "; " + "; ".join(bad) if bad else "", "; ink outside the bands at rows %s" % stray[:6] if stray else ""))
    contact.main([os.path.join(out, "sheet.png"), "3"] + shots)
    print("emu_board: %d boards, %d failures; screenshots in %s" % (len(letters), len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
