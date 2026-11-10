#!/usr/bin/env python3
"""Put posts up on the en build's bulletin board and check what it keeps and draws.

    tests/emu_notice.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR

STATE is a state of EN_ROM in play (make route-en's 4-houses.st), whose
board holds the new game's four posts (code/m_notice.c, func_800A5BC4_jp:
the shop letters 30 to 33). A hook (tests/emu_save.py's way) puts the
game's notices up as func_800A5F08_jp and func_800A65C4_jp do (a shop
letter loaded into a post of its own, dated, then func_800A5D30_jp), saves
and loads as tests/emu_letters.py does, and opens the board (submenu
program 6) as mSM_submenu_ctrl opens a program. The twenty free strings
are set first, to known text.

The en build keeps a post's first 96 bytes in the save, as the cartridge
does, and the rest of the GameCube's 192 in the save's extension, under a
key made of the post's 0x68 bytes (m_handbill.c, "The bulletin board's
posts"); the board draws a post whole, laid out as a letter's body
(m_handbill.c, mHandbill_en_NoticeText). The models: tests/emu_letters.py's
of a shop letter's body and of the extension, tests/emu_board.py's of the
lines (192 pixels, broken after the last space that fits, at the en font's
widths) and tests/emu_write.py's of the editor's place in them. Checks:

  1. the new game's four posts hold their letters' first 96 bytes, and
     the extension the rest under each post's key;
  2. the game's notices put up are filed the same way, and one with
     nothing past 96 bytes is not;
  3. the board, opened, turned page by page from the newest to the
     oldest post, draws each post whole with the model's lines (each
     line's first and last text pixel, tests/emu_board.py's way, at the
     board's place: its text from the screen's x 63 and y 63, in lines of
     16, the paper near white and the text's ink red over green and
     blue);
  4. the board full, a post put up moves the others up, the oldest
     going, and each keeps its rest;
  5. a notice typed on the editor's dial (A on the board) is written in
     the letters' 192-byte body, 32 characters a line, laid out as the
     model lays it out, the cursor and end mark at their places; past
     the cartridge's 96 bytes it is kept whole;
  6. finished (START, the edit-end check's first answer), it goes up: the
     post holds its first 96 bytes and its time, the extension the rest
     under the post's key; read on the board, it is drawn whole;
  7. the save keeps the rests of the save's letters and posts, in both
     slots, and drops the others (the letters the new game's posts were
     loaded from); the load brings them back, and the typed post is read
     whole again.
Writes the screenshots, and a sheet of them, to OUT_DIR.
"""

import os
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, A, START, Z, write_png  # noqa: E402
import aflz  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_letters  # noqa: E402
import emu_save  # noqa: E402
import emu_write  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
void mHandbill_Load_HandbillFromRom(u8* header, s32* header_back_start, u8* footer, u8* body, s32 mail_no);
void func_800A5D30_jp(u8* post);
void mSM_open_submenu_new(void* submenu, s32 programId, s32 arg2, s32 arg3, s32 arg4);
void SetGameFrame(s32 divisor);
s32 func_80090044_jp(void);
void func_8008F938_jp(void);
extern void* gamePT;

typedef struct HookCtl {
    s32 cmd;
    s32 result;
    s32 arg[4];
    u8 post[0x68];
} HookCtl;

HookCtl hook_ctl;

void hook_main(void) {
    HookCtl* h = &hook_ctl;
    s32* submenu = (s32*)((u8*)gamePT + 0x1CBC);
    u8 header[16];
    u8 footer[16];
    s32 back;

    switch (h->cmd) {
        case 1: /* a notice of the game's, as func_800A5F08_jp puts one up: a letter into a dated post, then up */
            mHandbill_Load_HandbillFromRom(header, &back, footer, h->post, h->arg[0]);
            func_800A5D30_jp(h->post);
            h->cmd = 0;
            break;
        case 2: /* a submenu program opened as mSM_submenu_ctrl opens one */
            if (submenu[3] == 0) {
                mSM_open_submenu_new(submenu, h->arg[0], h->arg[1], h->arg[2], h->arg[3]);
                submenu[3] = 1;
                submenu[0] = 1;
                SetGameFrame(2);
                h->cmd = 0;
            }
            break;
        case 3: /* the normal save, a step a frame */
            h->result = func_80090044_jp();
            if (h->result != 0) {
                h->cmd = 0;
            }
            break;
        case 4: /* the load (the title's direct one) */
            func_8008F938_jp();
            h->result = 1;
            h->cmd = 0;
            break;
    }
}
"""

CTL = struct.Struct(">ii4I104s")
POSTS, POST_SIZE, POST_COUNT, POST_TEXT = 0x2F6A, 0x68, 15, 96   # common_data.save's board (code/m_notice.c)
CLEAR_TIME = b"\xff" * 8                                           # mTM_rtcTime_clear_code: an empty post
SUBMENU, SUBMENU_OVL, EDITOR_OVL = 0x1CBC, 0x2C, 0x106E0
EDIT_BODY, BODY_CAP, BODY_LINES = "mHandbill_en_editBody", 192, 6
NOTICE_PROGRAM, INIT_MAILS = 6, (30, 31, 32, 33)
# The board's text from the screen's (63 + x, 63 - y), x and y its menu info's position (func_808958A0_jp
# passes them to func_8089562C_jp: 63 + x, 46 - y + 17), lines of 16: at rest x and y are 0; while a notice
# is written the board rises (y 16 with four lines) to keep the cursor's line above the dial. The frame's x
# of the text's left edge and frame pixels to the pixel, fitted on the 96 line ends of the nine posts of
# check 3 (residuals within 1.7; the letter board's, tests/emu_board.py BOARD_X, gives 1.954 and leaves
# 2.1)
TEXT_Y, LINE_H = 63, 16
NOTICE_X = (129.35, 1.960)
PAPER_X = range(100, 520)                  # the paper's text area in the frame (near white, 248), its curl left out
BOARD_MENU = 0x10088 + 6 * 0x48            # the board's menu info (the submenu's 7th), its position at 0x18
SETTLE = 90
C_LEFT = 0x200
# Notices of the game's (shop letters 0x1A4 to 0x21F: func_800A65C4_jp, func_800A5F08_jp): the longest
# English bodies, one with seven lines on the GameCube, one empty
NOTICES = [536, 430, 528, 511, 463]
SENTENCE = (b"FOR SALE, A LOVELY OLD CHAIR AND A BIG TABLE, BOTH IN GOOD SHAPE. ASK AT THE HOUSE BY THE RIVER, "
            b"ANY DAY.")
FREES = emu_board.FREES
GREEN = emu_write.GREEN                      # the end mark
TOLERANCE = emu_board.TOLERANCE

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def is_text(rgb, w, x, y):
    """The board's ink (30, 0, 0, blended over the near-white paper): red over green and blue, green and blue alike."""
    r, g, b = rgb[(y * w + x) * 3:(y * w + x) * 3 + 3]
    return r - g >= 10 and abs(g - b) <= 12 and r < 225


def measure(rgb, w, y0):
    """(left, right) of the text in the band of 16 lines from y0, in frame pixels (right exclusive), or None."""
    xs = [x for y in range(y0, y0 + LINE_H) for x in PAPER_X if is_text(rgb, w, x, y)]
    return (min(xs), max(xs) + 1) if xs else None


def frame_x(x):
    return NOTICE_X[0] + NOTICE_X[1] * x


def time_of(k):
    """A distinct time for the k-th post put up (lbRTC_time_c: sec, min, hour, day, weekday, month, year)."""
    return bytes([k % 60, 30, 10, 1 + k % 28, k % 7, 7]) + struct.pack(">H", 2003)


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
    board = emu_board.Board(seg, emu_board.read_cut(af_en))
    dial = emu_write.dial_table(af_en)
    entry = emu_letters.entries(af_en)
    shots = []

    def body_of(n):
        """The model's whole body of shop letter n, as the loaders make it (192 bytes, newlines after it)."""
        return emu_letters.body(entry("mail_body", n), FREES)

    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        n64.load_state(state)
        words = struct.unpack(">II", n64.read(mttime, 8))
        blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
        emu_save.write_code(n64, hole, blob)
        emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
        n64.write(free_at, b"".join(FREES))

        def run(cmd, args=(), post=b"", limit=30):
            n64.write(ctl, CTL.pack(cmd, 0, *(list(args) + [0] * (4 - len(args))), post.ljust(104, b"\0")))
            for _ in range(limit):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    return CTL.unpack(n64.read(ctl, CTL.size))
            raise SystemExit("the hook did not run command %d" % cmd)

        def posts():
            data = n64.read(cd + POSTS, POST_COUNT * POST_SIZE)
            return [data[k * POST_SIZE:(k + 1) * POST_SIZE] for k in range(POST_COUNT)]

        def ext_in_ram():
            size = struct.unpack(">i", n64.read(syms["mFRm_en_extSize"], 4))[0]
            return n64.read(syms["mFRm_en_ext"], size) if size > 0 else b""

        def ext():
            return emu_letters.Ext.parse(ext_in_ram())

        def whole(post, model_ext=None):
            """A post's text whole as the board reads it: its 96 bytes, the extension's rest, spaces after."""
            rest = (model_ext or ext()).rest(emu_letters.post_key(post))
            return (post[:POST_TEXT] + (rest[1] if rest else b"")).ljust(BODY_CAP, b" ")

        def submenu_ovl():
            play = struct.unpack(">I", n64.read(syms["gamePT"], 4))[0]
            return struct.unpack(">I", n64.read(play + SUBMENU + SUBMENU_OVL, 4))[0]

        def editor():
            e = n64.read(struct.unpack(">I", n64.read(submenu_ovl() + EDITOR_OVL, 4))[0], 0x30)
            cursor, per_line, lines, length, _, col, line = struct.unpack(">hhhhhhh", e[0x16:0x24])
            return {"cursor": cursor, "perLine": per_line, "lines": lines, "length": length, "col": col,
                    "line": line, "mode": e[4], "ring": e[5], "turning": e[3],
                    "text": struct.unpack(">I", e[0x24:0x28])[0], "at": None}

        def press(button, times=1):
            for _ in range(times):
                n64.frames(3, button)
                n64.frames(6, 0)

        def at_rest(name):
            n64.frames(SETTLE, 0)
            w, h, rgb = n64.screen()
            n64.frames(6, 0)
            path = os.path.join(out, name + ".png")
            n64.screenshot(path)
            shots.append(path)
            return w, rgb, n64.screen()[2] == rgb

        def text_y():
            """The board's first line in the frame (its menu info's position y, a float, the board risen by it)."""
            y = struct.unpack(">f", n64.read(submenu_ovl() + BOARD_MENU + 0x1C, 4))[0]
            return int(round(TEXT_Y - y))

        def check_post(what, text, rgb, w, lines=BODY_LINES, y0=TEXT_Y):
            """The board's lines of text against the model's, read (newlines not drawn)."""
            want, cut_off = board.body(text)
            bad = []
            for k in range(lines):
                e = board.extent(want[k]) if k < len(want) else None
                got = measure(rgb, w, y0 + k * LINE_H)
                wx = e and (frame_x(e[0]), frame_x(e[1] + 1))
                ok = (got is None) if e is None else (
                    got is not None and all(abs(g - x) <= TOLERANCE for g, x in zip(got, wx)))
                if not ok:
                    bad.append("line %d drawn at %s, the model's %s" % (k + 1, got, wx and "(%.1f, %.1f)" % wx))
            check(not bad, "%s: %d lines where the model has them%s%s" % (
                what, sum(1 for s in want if s), " (the rest past six lines is not drawn)" if cut_off else "",
                "; " + "; ".join(bad) if bad else ""))

        # 1. the new game's posts
        start = posts()
        start_ext = ext()
        bad = []
        for k, n in enumerate(INIT_MAILS):
            b = body_of(n)
            rest = start_ext.rest(emu_letters.post_key(start[k]))
            if start[k][:POST_TEXT] != b[:POST_TEXT] or rest != (b"", b[POST_TEXT:].rstrip(b"\xcd"), b""):
                bad.append(k)
        check(not bad and all(p[POST_TEXT:] == CLEAR_TIME for p in start[4:]),
              "the new game's board: letters %s in its first four posts, their first %d bytes in the save and the "
              "rest (%s bytes) in the extension under each post's key; the other eleven empty (%s differ)" % (
                  INIT_MAILS, POST_TEXT, [len(body_of(n)[POST_TEXT:].rstrip(b"\xcd")) for n in INIT_MAILS], bad))

        # 2. the game's notices
        bad = []
        for k, n in enumerate(NOTICES):
            run(1, [n], b" " * POST_TEXT + time_of(k))
            p, b = posts()[4 + k], body_of(n)
            rest = ext().rest(emu_letters.post_key(p))
            want_rest = b[POST_TEXT:].rstrip(b"\xcd")
            if p != b[:POST_TEXT] + time_of(k) or rest != ((b"", want_rest, b"") if want_rest else None):
                bad.append(n)
        check(not bad, "the game's notices %s go up in posts 5 to %d, the rest of each in the extension (%s "
              "bytes), none for the empty one (%s differ)" % (
                  NOTICES, 4 + len(NOTICES), [len(body_of(n)[POST_TEXT:].rstrip(b"\xcd")) for n in NOTICES], bad))

        # 3. the board, from the newest post to the oldest
        run(2, [NOTICE_PROGRAM, 0, 0, 0])
        ps = posts()
        count = 4 + len(NOTICES)
        for k in range(count - 1, -1, -1):
            w, rgb, rest_ok = at_rest("post-%d" % (k + 1))
            check(rest_ok, "post %d: the board is at rest %d frames after the page turned" % (k + 1, SETTLE))
            check_post("post %d (%s)" % (k + 1, ("letter %d" % (INIT_MAILS + tuple(NOTICES))[k])), whole(ps[k]),
                       rgb, w)
            if k:
                press(C_LEFT)
        press(START)
        n64.frames(60, 0)

        # 4. the board full: each post keeps its rest as the posts move up, the oldest going
        for k in range(count, POST_COUNT + 1):
            run(1, [NOTICES[k % 2]], b" " * POST_TEXT + time_of(k))
        ps = posts()
        model = ext()
        firsts = [body_of(n)[:POST_TEXT] for n in INIT_MAILS[1:] + tuple(NOTICES)]
        check([p[:POST_TEXT] for p in ps[:len(firsts)]] == firsts and ps[-1][POST_TEXT:] == time_of(POST_COUNT),
              "the board full, one more post moves the others up and the oldest goes")
        check(all(whole(p, model).rstrip(b" ").rstrip(b"\xcd") == body_of(n).rstrip(b"\xcd")
                  for p, n in zip(ps, INIT_MAILS[1:] + tuple(NOTICES))),
              "each post moved up finds its rest")

        # 5. a notice typed: A on the board opens the editor on the letters' 192-byte body
        run(2, [NOTICE_PROGRAM, 0, 0, 0])
        n64.frames(SETTLE, 0)
        press(A)
        n64.frames(60, 0)
        ed = editor()
        check(ed["text"] == syms[EDIT_BODY] and ed["perLine"] == 32 and ed["lines"] == BODY_LINES and
              ed["length"] == 0, "A: the editor on the letters' body (%#x), %d characters a line, %d lines, empty" % (
                  ed["text"], ed["perLine"], ed["lines"]))
        press(Z, 3)
        check(editor()["mode"] == 3, "Z three times: the dial's capitals")
        ed_at = struct.unpack(">I", n64.read(submenu_ovl() + EDITOR_OVL, 4))[0]

        def type_char(ch):
            k = dial.index(ch)
            for _ in range(12):
                e = n64.read(ed_at, 8)
                if e[3] == 0 and e[5] == k // 5 * 5:
                    break
                n64.frames(12, emu_write.DOWN)
                n64.frames(12, 0)
            e = n64.read(ed_at, 8)
            v = emu_write.PLACES[k - e[5]]
            n64.frames(5, v)
            n64.frames(3, v | A)
            n64.frames(3, v)
            n64.frames(4, 0)

        for c in SENTENCE:
            type_char(bytes([c]))
        ed = editor()
        n = len(SENTENCE)
        text = n64.read(syms[EDIT_BODY], BODY_CAP)
        want = emu_write.typed(board, b"", SENTENCE)
        check(want == SENTENCE and text[:n] == SENTENCE and ed["length"] == n and
              text[n:] == b" " * (BODY_CAP - n),
              "typed: %d bytes kept whole past the cartridge's %d (%r)" % (ed["length"], POST_TEXT, text[:ed["length"]]))
        place = emu_write.place(board, SENTENCE, n, n)
        check((ed["line"], ed["col"]) == place, "the cursor after it: line %d column %d (the model's %d, %d)" % (
            ed["line"], ed["col"], place[0], place[1]))
        n64.frames(8, 0)
        y0 = text_y()
        written = len([s for s in board.body(SENTENCE)[0] if s])
        best = None
        for _ in range(24):                  # the frame of the cursor's fade where its bar is faintest
            n64.frames(1, 0)
            w, h, rgb = n64.screen()
            score = len([1 for y in range(y0, y0 + written * LINE_H) for x in PAPER_X
                         for r, g, b in [rgb[(y * w + x) * 3:(y * w + x) * 3 + 3]] if r - g > 40 and abs(g - b) <= 8])
            if best is None or score < best[0]:
                best = (score, w, h, rgb)
        _, w, h, rgb = best
        path = os.path.join(out, "typed.png")
        write_png(path, w, h, rgb)
        shots.append(path)
        check(y0 < TEXT_Y, "writing, the board rises to keep the cursor's line above the dial (its text from "
              "frame row %d)" % y0)
        check_post("the notice being written", SENTENCE, rgb, w, lines=written, y0=y0)
        end_line = place[0]
        green = sorted(set(x for y in range(y0 + end_line * LINE_H, y0 + end_line * LINE_H + LINE_H)
                           for x in PAPER_X if GREEN(*rgb[(y * w + x) * 3:(y * w + x) * 3 + 3])))
        starts = emu_write.edit_lines(board, SENTENCE, n)
        ink_end = frame_x(board.extent(SENTENCE[starts[end_line]:n])[1] + 1)
        check(green and 0 <= green[0] - ink_end <= 6, "the end mark right after the text (at frame x %s, the text's "
              "ink ends at %.1f)" % (green[0] if green else None, ink_end))

        # 6. finished: START, the edit-end check's answers, the first
        press(START)
        n64.frames(40, 0)
        press(A)
        n64.frames(40, 0)
        press(A)
        n64.frames(60, 0)
        ps = posts()
        mine = ps[-1]
        rest = ext().rest(emu_letters.post_key(mine))
        check(mine[:POST_TEXT] == SENTENCE[:POST_TEXT] and mine[POST_TEXT:] != CLEAR_TIME,
              "finished, the notice goes up: the post holds its first %d bytes and its time (%s)" % (
                  POST_TEXT, mine[POST_TEXT:].hex()))
        check(rest == (b"", SENTENCE[POST_TEXT:], b""), "the rest, %d bytes, in the extension under the post's key "
              "(%r)" % (n - POST_TEXT, rest and rest[1]))
        run(2, [NOTICE_PROGRAM, 0, 0, 0])
        w, rgb, rest_ok = at_rest("typed-read")
        check_post("the notice, read on the board", whole(mine), rgb, w)
        press(START)
        n64.frames(60, 0)

        # 7. the save and the load
        def slots():
            image = emu_save.read_fla(emu_save.fla_path(os.path.join(out, "cart"), rom))
            return [aflz.unpack(image[k * 0x10000:(k + 1) * 0x10000])[1] for k in (0, 1)]

        keys = (set(emu_letters.mail_key(n64.read(cd + at, emu_letters.MAIL_SIZE)) for at in emu_letters.SAVE_MAIL) |
                set(emu_letters.post_key(p) for p in posts()))
        model = ext()
        before = len(model.records)
        post_rests = dict((emu_letters.post_key(p), model.rest(emu_letters.post_key(p))) for p in posts()
                          if model.rest(emu_letters.post_key(p)))
        model.collect(keys)
        got = run(3, [], limit=600)
        saved = ext_in_ram()
        kept = emu_letters.Ext.parse(saved)
        check(got[1] == 1 and saved == model.bytes() and slots() == [saved, saved] and
              all(kept.rest(k) == r for k, r in post_rests.items()),
              "the save keeps the rests of the save's posts (%d) and letters in both slots (%d records of %d, %d "
              "bytes), the letters the posts were loaded from dropped" % (
                  len(post_rests), len(model.records), before, len(saved)))
        n64.write(syms["mFRm_en_extSize"], struct.pack(">i", 0))
        run(4)
        check(ext_in_ram() == saved, "the load brings the extension back")
        run(2, [NOTICE_PROGRAM, 0, 0, 0])
        w, rgb, rest_ok = at_rest("typed-loaded")
        check_post("after the load, the notice", whole(mine), rgb, w)

    contact.main([os.path.join(out, "sheet.png"), "3"] + shots)
    print("emu_notice: %d failures; screenshots in %s" % (len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
