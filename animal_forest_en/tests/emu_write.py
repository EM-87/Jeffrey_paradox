#!/usr/bin/env python3
"""Write a letter with the en build's editor and check what it lays out.

    tests/emu_write.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). A hook
(tests/emu_save.py's way) puts a sheet of paper in the first pocket, gives
the first villager a memory of the player (the address book lists the
villagers who remember the player: ovl__00792700, mNpc_GetAnimalMemoryIdx)
and opens the board to write (program 12, data0 0) as mSM_submenu_ctrl
opens a program; A twice picks the first address and the editor (program
10) opens on the body. Z three times gives the dial's capitals (mode 3).
Letters are typed on the dial as a player types them: the stick down turns
its ring a page of five (the editor's ring, byte 5 of its state), A with
the stick on one of the five places of the upper half types ring + place
(func_80885140_jp; its table, D_80888594_jp, read from the en tree's
extracted data). The C buttons move
the cursor, B deletes.

The model is tests/emu_board.py's: the en board's lines (m_handbill.c,
mHandbill_en_BoardLine: 192 pixels, broken after the last space that
fits) at the en font's widths, and the editor's place in them (m_handbill.c,
"Writing a letter": its line and column, the cursor's x, a line up or down
to the column nearest that x). Checks:

  1. a new letter's header is "To " before the name, its footer "from "
     and the player's name (the GameCube's, ac-decomp's m_board_ovl.c);
  2. a typed sentence is the letter's body, the editor's line and column
     at its end are the model's, the board draws the model's lines (each
     line's first and last text pixel, emu_board.py's way: the board at
     rest, the dial covering the lines below the third) and the end mark
     right after the text;
  3. the cursor moved left and up is at the model's place, its bar
     centred on the boundary between its characters;
  4. a letter typed in the middle of a line, and deleted, reflows the
     lines as the model does;
  5. typing on past the paper stops at the body's 96 bytes or six lines;
  6. the letter written, read on the board, is drawn with the model's
     lines, the lines it shared with the screen it was written on alike.
Writes the screenshots to OUT_DIR.
"""

import os
import re
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, A, B, Z, stick, write_png  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_save  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
void mSM_open_submenu_new(void* submenu, s32 programId, s32 arg2, s32 arg3, s32 arg4);
void SetGameFrame(s32 divisor);
extern void* gamePT;
typedef struct HookCtl { s32 cmd; s32 arg[4]; } HookCtl;
HookCtl hook_ctl;

/* a submenu program opened as mSM_submenu_ctrl opens one */
void hook_main(void) {
    HookCtl* h = &hook_ctl;
    s32* submenu = (s32*)((u8*)gamePT + 0x1CBC);

    if (h->cmd == 1 && submenu[3] == 0) {
        mSM_open_submenu_new(submenu, h->arg[0], h->arg[1], h->arg[2], h->arg[3]);
        submenu[3] = 1;
        submenu[0] = 1;
        SetGameFrame(2);
        h->cmd = 0;
    }
}
"""

PRIVATE_INFO, POCKETS, POCKET_MAIL, MAIL_SIZE = 0x10138, 0x14, 0x40A, 0xA4
ANIMALS, ANIMAL_MEMORY = 0x9F18, 0x10              # common_data.animals[0], its memories (the address book's offset)
SUBMENU, SUBMENU_OVL, EDITOR_OVL, BOARD_OVL = 0x1CBC, 0x2C, 0x106E0, 0x106E4
PAPER = 0x2000
PLAYER = b"Jeff  "
DIAL_DATA = "asm/jp/data/overlays/submenu/ovl__0078CB80/ovl__0078CB80.data.s"
DIAL_TABLE, DIAL_SIZE = "D_80888594_jp", 30      # the capitals' table (D_808885DC_jp[3] entries), from the dump
PLACES = [stick(-80, 0), stick(-57, 57), stick(0, 80), stick(57, 57), stick(80, 0)]
DOWN = stick(0, -80)
C_LEFT, C_UP = 0x200, 0x800                          # the pad's C buttons (m64p's BUTTONS)
SENTENCE = b"DEAR FRIEND, THIS IS A LETTER WRITTEN WITH THE NEW ENGLISH EDITOR."
MORE = b" IT GOES ON AND ON UNTIL THE PAPER IS FULL, AND THEN SOME MORE WORDS."
BODY_CAP, BODY_LINES, VISIBLE_LINES = 96, 6, 3
NL = 0xCD
GREEN = lambda r, g, b: g > 140 and r < 110 and b < 110   # noqa: E731 (the end mark)
RED = lambda r, g, b: r > 150 and g < 120 and b < 120     # noqa: E731 (the cursor's bar)
# The cursor's bar fades with the editor's step (its state's 0x0C): alpha (10 - v) * 255 / 10, v the
# step up to 10 and back (func_808881C0_jp, colour 195, 80, 80); faint over a ruled line's blue, it
# passes for the text's colour
TEXT_AREA = range(112, 528)                          # the frame's columns of the paper's text, its border left out

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


# -- the model of the editor's place (m_handbill.c, "Writing a letter") ------

def dial_table(af_en):
    """The dial's capitals, as splat extracted them from the user's dump into the en tree."""
    text = open(os.path.join(af_en, DIAL_DATA)).read()
    body = text[text.index("dlabel %s" % DIAL_TABLE):text.index("enddlabel %s" % DIAL_TABLE)]
    data = b"".join(int(w, 16).to_bytes(4, "big") for w in re.findall(r"\.word 0x([0-9A-Fa-f]{8})", body))
    return data[:DIAL_SIZE]


def edit_lines(board, s, n):
    starts, at = [0], 0
    while at < n and len(starts) < 8:
        _, used = board.line(s[at:], n - at)
        at += used
        if at < n or s[at - 1] == NL:
            starts.append(at)
    return starts


def place(board, s, length, i):
    """(line, col) of index i (mHandbill_en_EditColLine)."""
    starts = edit_lines(board, s, max(i, length))
    k = max(j for j, st in enumerate(starts) if st <= i)
    if i - starts[k] == 0 and k > 0 and k == BODY_LINES:
        return k - 1, i - starts[k - 1]
    return k, i - starts[k]


def cursor_x(board, s, length, line, col):
    """The cursor's x in its line (mHandbill_en_EditX)."""
    starts = edit_lines(board, s, length)
    line = min(line, len(starts) - 1)
    drawn = board.line(s[starts[line]:], length - starts[line])[0] if starts[line] < length else 0
    return board.width(s[starts[line]:starts[line] + min(col, drawn)])


def nearest_col(board, s, length, line, x, max_col):
    """mHandbill_en_EditSetCol."""
    start = edit_lines(board, s, length)[line]
    best, dist, w = 0, x, 0
    for col in range(1, max_col + 1):
        w += board.w[s[start + col - 1]]
        if abs(w - x) < dist:
            best, dist = col, abs(w - x)
    return best


def line_len(board, s, length, line):
    starts = edit_lines(board, s, length)
    return (starts[line + 1] if line + 1 < len(starts) else length) - starts[line]


def pixels(rgb, w, rows, test):
    return sorted(set(x for y in rows for x in TEXT_AREA if test(*rgb[(y * w + x) * 3:(y * w + x) * 3 + 3])))


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
    mttime, cd = syms["mTM_time"], syms["common_data"]
    seg = open(os.path.join(af_en, "assets", "jp", "segment_00BCD000.bin"), "rb").read()
    board = emu_board.Board(seg, emu_board.read_cut(af_en))
    dial = dial_table(af_en)
    shots = []

    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        def setup():
            n64.load_state(state)
            words = struct.unpack(">II", n64.read(mttime, 8))
            blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            priv = struct.unpack(">I", n64.read(cd + PRIVATE_INFO, 4))[0]
            n64.write(priv, PLAYER)
            return ctl, priv

        def open_program(ctl, *args):
            n64.write(ctl, struct.pack(">i4I", 1, *args))
            for _ in range(30):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    return
            raise SystemExit("the hook did not open program %d" % args[0])

        def ovl(at):
            play = struct.unpack(">I", n64.read(syms["gamePT"], 4))[0]
            sub = struct.unpack(">I", n64.read(play + SUBMENU + SUBMENU_OVL, 4))[0]
            return struct.unpack(">I", n64.read(sub + at, 4))[0]

        def editor():
            e = n64.read(ovl(EDITOR_OVL), 0x30)
            cursor, _, lines, length, _, col, line = struct.unpack(">hhhhhhh", e[0x16:0x24])
            return {"cursor": cursor, "lines": lines, "length": length, "col": col, "line": line,
                    "mode": e[4], "ring": e[5], "turning": e[3]}

        def letter():
            bd = ovl(BOARD_OVL)
            b = n64.read(bd, 0xC0)
            return {"field": b[0], "headerPos": b[2], "back": b[0x2F], "header": b[0x32:0x3C],
                    "body": b[0x3C:0x9C], "footer": b[0x9C:0xAC], "mail": b[8:8 + MAIL_SIZE]}

        def type_char(ch):
            ed = ovl(EDITOR_OVL)
            k = dial.index(ch)
            for _ in range(12):
                e = n64.read(ed, 8)
                if e[3] == 0 and e[5] == k // 5 * 5:
                    break
                n64.frames(12, DOWN)
                n64.frames(12, 0)
            e = n64.read(ed, 8)
            v = PLACES[k - e[5]]
            n64.frames(5, v)
            n64.frames(3, v | A)
            n64.frames(3, v)
            n64.frames(4, 0)

        def type_text(s):
            for c in s:
                type_char(bytes([c]))

        def press(button, times=1):
            for _ in range(times):
                n64.frames(3, button)
                n64.frames(6, 0)

        def shot(name, bar=False):
            """The frame of one fade cycle with the cursor's bar faintest in its line (to measure the text) or
            strongest. The frame shown trails the editor's step (the game builds the next frame while one is
            drawn), so it is chosen by its pixels: the bar's pink, red with green and blue alike."""
            n64.frames(8, 0)
            ed = editor()
            rows = range(emu_board.BODY_Y + ed["line"] * 16, emu_board.BODY_Y + ed["line"] * 16 + 16)
            best = None
            for _ in range(24):
                n64.frames(1, 0)
                w, h, rgb = n64.screen()
                score = len([1 for y in rows for x in TEXT_AREA for r, g, b in [rgb[(y * w + x) * 3:(y * w + x) * 3 + 3]]
                             if r - g > 12 and abs(g - b) <= 8])
                if best is None or (score > best[0] if bar else score < best[0]):
                    best = (score, w, h, rgb)
            path = os.path.join(out, name + ".png")
            write_png(path, best[1], best[2], best[3])
            shots.append(path)
            return best[1:]

        def check_lines(what, s, length, rgb, w, lines=VISIBLE_LINES):
            want, _ = board.body(s[:length])
            bad = []
            for k in range(lines):
                ext = board.extent(want[k]) if k < len(want) else None
                got = emu_board.measure(rgb, w, emu_board.BODY_Y + k * emu_board.LINE_H)
                wx = ext and (emu_board.frame_x(ext[0]), emu_board.frame_x(ext[1] + 1))
                ok = (got is None) if ext is None else (
                    got is not None and all(abs(g - e) <= emu_board.TOLERANCE for g, e in zip(got, wx)))
                if not ok:
                    bad.append("line %d drawn at %s, the model's %s" % (k + 1, got, wx and "(%.1f, %.1f)" % wx))
            check(not bad, "%s: the first %d lines where the model has them%s" % (
                what, lines, "; " + "; ".join(bad) if bad else ""))
            return [emu_board.measure(rgb, w, emu_board.BODY_Y + k * emu_board.LINE_H) for k in range(lines)]

        # -- writing ----------------------------------------------------------
        ctl, priv = setup()
        n64.write(priv + POCKETS, struct.pack(">H", PAPER))
        n64.write(cd + ANIMALS + ANIMAL_MEMORY, n64.read(priv, 0x10))
        open_program(ctl, 12, 0, 0, priv + POCKET_MAIL + 9 * MAIL_SIZE)
        n64.frames(40, 0)
        press(A)                                  # "first choose who it is to"
        n64.frames(30, 0)
        press(A)                                  # the first address
        n64.frames(40, 0)
        ed, lt = editor(), letter()
        check(ed["lines"] == BODY_LINES and lt["field"] == 1, "the editor opens on the letter's body (%d lines)"
              % ed["lines"])
        check(lt["header"] == b"To" + b" " * 8 and lt["back"] == 3 and lt["footer"] == b"from Jeff".ljust(16),
              "a new letter: %r, the name at %d, footer %r" % (lt["header"], lt["back"], lt["footer"]))
        press(Z, 3)
        check(editor()["mode"] == 3, "Z three times: the dial's capitals")

        # 2. a sentence
        type_text(SENTENCE)
        ed, lt = editor(), letter()
        n = len(SENTENCE)
        check(lt["body"][:n] == SENTENCE and ed["length"] == n, "typed: %r (%d bytes)" % (lt["body"][:n], ed["length"]))
        want = place(board, SENTENCE, n, n)
        check((ed["line"], ed["col"]) == want, "the cursor after it: line %d column %d (the model's %d, %d)"
              % (ed["line"], ed["col"], want[0], want[1]))
        w, h, rgb = shot("1-typed")
        written = check_lines("typed", SENTENCE, n, rgb, w)
        end_line, end_col = place(board, SENTENCE, n, n)
        green = pixels(rgb, w, range(emu_board.BODY_Y + end_line * 16, emu_board.BODY_Y + end_line * 16 + 16), GREEN)
        ink_end = emu_board.frame_x(board.extent(SENTENCE[edit_lines(board, SENTENCE, n)[end_line]:n])[1] + 1)
        check(green and 0 <= green[0] - ink_end <= 6, "the end mark right after the text (at frame x %s, the "
              "text's ink ends at %.1f)" % (green[0] if green else None, ink_end))

        # 3. the cursor left and up
        press(C_LEFT, 5)
        ed = editor()
        want = place(board, SENTENCE, n, n - 5)
        check((ed["cursor"], ed["line"], ed["col"]) == (n - 5,) + want, "five left: index %d, line %d column %d "
              "(the model's %d, %d, %d)" % (ed["cursor"], ed["line"], ed["col"], n - 5, want[0], want[1]))
        for step in range(2):
            x = cursor_x(board, SENTENCE, n, ed["line"], ed["col"])
            line = ed["line"] - 1
            col = nearest_col(board, SENTENCE, n, line, x, line_len(board, SENTENCE, n, line) - 1)
            press(C_UP)
            ed = editor()
            check((ed["line"], ed["col"]) == (line, col), "up: line %d column %d, the column nearest x %d (the "
                  "model's %d, %d)" % (ed["line"], ed["col"], x, line, col))
        w, h, rgb = shot("2-cursor", bar=True)
        bar = pixels(rgb, w, range(emu_board.BODY_Y + ed["line"] * 16, emu_board.BODY_Y + ed["line"] * 16 + 16), RED)
        boundary = emu_board.frame_x(cursor_x(board, SENTENCE, n, ed["line"], ed["col"]))
        check(bar and abs((bar[0] + bar[-1] + 1) / 2.0 - boundary) <= emu_board.TOLERANCE,
              "the cursor's bar (frame x %s..%s) centred on the boundary at %.1f" % (
                  bar[0] if bar else None, bar[-1] if bar else None, boundary))

        # 4. a letter in the middle, then deleted
        at = editor()["cursor"]
        type_text(b"W")
        edited = SENTENCE[:at] + b"W" + SENTENCE[at:]
        ed, lt = editor(), letter()
        check(lt["body"][:n + 1] == edited and ed["cursor"] == at + 1, "a W typed at %d: %r" % (at, lt["body"][:n + 1]))
        w, h, rgb = shot("3-inserted")
        check_lines("with the W", edited, n + 1, rgb, w)
        press(B)
        ed, lt = editor(), letter()
        check(lt["body"][:n] == SENTENCE and ed["length"] == n and ed["cursor"] == at,
              "B deletes it: %r, the cursor at %d" % (lt["body"][:n], ed["cursor"]))
        w, h, rgb = shot("4-deleted")
        check_lines("after B", SENTENCE, n, rgb, w)

        # 5. on past the paper (from the end of the text)
        for _ in range(n):
            press(0x100)                          # C right, to the text's end
            if editor()["cursor"] == n:
                break
        type_text(MORE)
        ed, lt = editor(), letter()
        text = lt["body"][:ed["length"]]
        lines = len(edit_lines(board, text, len(text)))
        check(ed["length"] <= BODY_CAP and lines <= BODY_LINES and text == (SENTENCE + MORE)[:len(text)]
              and len(text) < n + len(MORE),
              "typing on: %d of %d bytes kept, %d lines (at most %d bytes, %d lines)" % (
                  len(text), n + len(MORE), lines, BODY_CAP, BODY_LINES))
        shot("5-full")
        mail = letter()["mail"]

        # 6. the letter read
        ctl, priv = setup()
        n64.write(priv + POCKET_MAIL, mail)
        open_program(ctl, 12, 1, 0, priv + POCKET_MAIL)
        n64.frames(emu_board.SETTLE, 0)
        path = os.path.join(out, "6-read.png")
        n64.screenshot(path)
        shots.append(path)
        w, h, rgb = n64.screen()
        want, _ = board.body(text)
        read = [emu_board.measure(rgb, w, emu_board.BODY_Y + k * 16) for k in range(BODY_LINES)]
        bad = []
        for k in range(BODY_LINES):
            ext = board.extent(want[k]) if k < len(want) else None
            wx = ext and (emu_board.frame_x(ext[0]), emu_board.frame_x(ext[1] + 1))
            ok = (read[k] is None) if ext is None else (
                read[k] is not None and all(abs(g - e) <= emu_board.TOLERANCE for g, e in zip(read[k], wx)))
            if not ok:
                bad.append("line %d drawn at %s, the model's %s" % (k + 1, read[k], wx))
        check(not bad, "the letter read: its %d lines where the model has them%s" % (
            len([x for x in want if x]), "; " + "; ".join(bad) if bad else ""))
        # the text typed on changes the sentence's last line, not the two before it
        check(written[:2] == read[:2], "read and written, the sentence's first two lines are drawn alike (%s)"
              % (written[:2],))
    contact.main([os.path.join(out, "sheet.png"), "3"] + shots)
    print("emu_write: %d failures; screenshots in %s" % (len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
