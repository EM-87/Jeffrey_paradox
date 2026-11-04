#!/usr/bin/env python3
"""The menus' own words in the en build, on screen.

    tests/emu_menus.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR [--gc GC_UI] [--own OWN_UI] [--nafe NAFE_ROM]

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). A hook
(tests/emu_save.py's way) opens the name entry (submenu program 4) in each
of its five modes as the game's callers do (mSM_open_submenu_new: the
player's name, the destination, a catchphrase, something to say, a song to
request). For each window:

  1. the table entry the overlay draws the title from (mLE_win_data, found
     in RAM by its first window's place) points at the en build's string
     in code, and holds it: the GameCube's (GC_UI, build/gc/gc_ui.txt, when
     given) or the cartridge's own;
  2. the title drawn is as wide as the font's model makes it at the
     title's scale (0.875, func_80884514_jp): the window opened twice from
     the same state, the second time with the title's length 0, the two
     frames of the same number differ only by the title's ink (the
     emulator is deterministic), whose extent is measured (pixels that
     differ by INK at least). A font pixel is FRAME_X frame pixels wide
     (the board's measurement: the VI shows the 320 pixels over about 625
     of the frame's 640), and INK still counts the VI filter's fringe, a
     frame pixel on each side (FRINGE);
  3. the destination's window draws nothing after the text typed (the
     overlay's three characters there are spaces, as the GameCube has
     nothing).

Then the edit-end check (program 15) and the address book (program 13),
opened as the letter board opens them (data0 0; the check's three-answer
window too, data0 1): the answers' table the check draws from (found in
RAM by its first window) points at the en build's strings in code and holds
them; the question and the book's balloon are as wide as the font's model
(the window opened twice, the second time with the string's bytes in RAM
made spaces: the frames differ by its ink only; the balloon's string
narrowed to the cartridge's 144 pixels when it is wider, as the en build
draws it); after A, each answer drawn is as wide as the model too, row by
row.

Then the strings the en build swaps where the font draws them
(tools/af_ui.py's SUBST): each menu opened three times, as it is, with
the English's bytes in the swap table made spaces, and with the swap off
(the table's key changed) and the cartridge's bytes, where the menu holds
them (the overlay's data, or the buffer a menu types a string into), made
a ruler (the widest glyph whose columns are whole ink or none, repeated
as far as the string's last character, so that a menu laid out by that
count, as the tag is, keeps its layout). The second against the first
gives the English's ink, against the third the ruler's, drawn by the
same call at the cartridge's place and scale; from the ruler's the box
the cartridge's string took (its
full-width cells: the cartridge's own glyphs have faint edges the font's
model places a column wrong, which over ten of them misjudged the scale
by 1.5%) and from that where the en m_font.c's layout puts the English,
which the English's ink must match (centred, aligned, narrowed to the box
when wider; a template's characters each centred in its cell), each edge
where the model's threshold puts it or out to a faint column. A string
the menu builds round its own part (BUILT) is put in the buffer it types
into in place of another's.

Then the labels the 2010 patch drew (tools/af_nafe_gfx.py): the hook opens
each menu whose segment holds some (LABEL_MENUS), and each label is in RAM
where the segment was loaded (found by a stretch of the en tree's copy away
from the labels) with the texels the en build drew it with: the 2010
patch's (NAFE_ROM, make nafe's, when given) or the cartridge's own.
gameplay_keep's are checked in the first menu's RAM (segment 4 stays
loaded all through play). Program 11 (ovl__00789B60, its mailbox label)
is opened by the inventory from inside it and does not open on its own:
not checked here.
Writes the screenshots to OUT_DIR.
"""

import os
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, A, R, write_png  # noqa: E402
import af_font  # noqa: E402
import af_nafe_gfx  # noqa: E402
import af_ui  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_save  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
void mSM_open_submenu_new(void* submenu, s32 programId, s32 arg2, s32 arg3, s32 arg4);
void SetGameFrame(s32 divisor);
extern void* gamePT;
typedef struct HookCtl { s32 cmd; s32 arg[4]; u8 buf[32]; } HookCtl;
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

LEDIT = 4
MODES = [("the player's name", "LEDIT_PLAYER", "mSM_en_lePlayerTitle"),
         ("the destination", "LEDIT_COUNTRY", "mSM_en_leCountryTitle"),
         ("a catchphrase", "LEDIT_EPHRASE", "mSM_en_leEphraseTitle"),
         ("something to say", "LEDIT_RESET", "mSM_en_leResetTitle"),
         ("a song to request", "LEDIT_REQUEST", "mSM_en_leRequestTitle")]
WIN_SIZE, TITLE_AT, LEN_AT = 0x28, 0x10, 0x14
WIN0 = struct.pack(">4f", 122.0, 76.0, 136.0, 46.0)  # the first window's places (m_ledit_ovl.c)
SUFFIX_AT = 5 * WIN_SIZE + 5 * 4                     # after the table and the move procs: D_80884E34_jp
TITLE_SCALE = 0.875
FRAME_X = emu_board.BOARD_X[1]                       # frame pixels a font pixel, wide (measured on the board:
# the VI shows the 320 pixels over about 625 of the frame's 640)
TOLERANCE = 2.0                                      # frame pixels (the scale's rounding, the VI filter)
INK = 60                                             # the colours' difference, summed, that is ink
FRINGE = 2.0                                         # the VI filter's fringe, a frame pixel on each side of
# the ink, which INK still counts (the board's own thresholds leave it out)
SETTLE = 60
HOOK_BUF = 0x14

EDIT_END, ADDRESS = 15, 13
EE_WIN0 = struct.pack(">iII4f", 2, 0x0C0017C8, 0x0C001790, -45.0, 43.0, 65.0, -74.0)  # its first window
EE_ANSWERS_AT = 0xC8                                 # D_80896A18_jp after D_80896950_jp
EE_ANSWERS = [("EE_ANSWER0", "mSM_en_eeAnswer0"), ("EE_ANSWER1", "mSM_en_eeAnswer1"),
              ("EE_ANSWER2", "mSM_en_eeAnswer2")]
ANSWER_ROW = 16                                      # frame rows between answers (a row a line: 240 rows)
AD_TEXT_W = 144                                      # the address book's balloon: the cartridge's 12 characters

TUNE_BUTTONS = (R,)                                  # the town tune's trash: its question
# the menus that draw the strings the en build swaps: ((program, data0), buttons that bring them up, names; a
# name with the place in the dump of the copy the menu draws, where the overlay holds the same bytes twice)
SWAP_MENUS = [((7, 0), (), ["RP_TITLE", "OK"]),
              ((9, 0), (), ["BD_TITLE"]),
              ((3, 0), (), ["TI_TITLE", "TI_DATE", "TI_TIME"]),
              ((19, 0), (), ["PK_TITLE"]),
              ((8, 0), TUNE_BUTTONS, ["MS_QUESTION", "MS_YES", "MS_NO"]),
              ((14, 0), (), ["GY_IKAGA", "GY_PRICE"]),
              ((16, 0), (), ["WR_LETTERS1", "WR_LETTERS2", "WR_YES", "WR_NO"]),
              ((16, 1), (), ["WR_POCKETS1", "WR_PRESENT2"]),
              ((16, 2), (), ["WR_BELLS1", "WR_BELLS2"]),
              ((18, 2), (), ["PK_NOERASE", ("PK_FAILED", 0x7A0BBC)]),   # the erase message's own copy
              ((18, 3), (), ["PK_INSERT1", "PK_INSERT2", "PK_INSERT3"]),
              ((1, 0), (A,), ["TG_TUKAMU", "TG_YAMERU"])]           # the inventory: the money's tag
# a string a menu builds round its own part, which no menu of the route's save shows: put in the buffer it
# types into in place of another's (name: the other, the part)
BUILT = {"GY_PRICE": ("GY_IKAGA", "1000")}           # an item's price in the gyroid's shop
SUBST_HEAD = 7                                       # mFont_EnSubst: its lengths, layout, box, buffer, holes
SUBST_SIZE = SUBST_HEAD + af_ui.SUBST_KEY_MAX + af_ui.SUBST_EN_MAX   # (u8s only)
RULER_GLYPHS = "wesxzmnuo"                          # RULER: the widest of these whose columns are whole
CELLS_SCALE = 0.875                                  # the clock's templates (func_80883B40_jp: its numbers
# start in their cells, 10.5 pixels apart)

# the menus that load the segments the 2010 patch drew labels in: (program, data0, segment's vrom, what)
LABEL_MENUS = [(1, 0, 0xA30000, "the inventory"),
               (LEDIT, 0, 0xA40000, "the text editor's dial (the name entry)"),
               (5, 0, 0xAAD000, "the map"),
               (6, 0, 0xABA000, "the bulletin board"),
               (7, 0, 0xACC000, "an amount to enter"),
               (8, 0, 0xAD1000, "the town tune"),
               (19, 0, 0xB1A000, "the Controller Pak's notes"),
               (20, 0, 0xB2A000, "the catalog")]
KEEP = 0xA22000                                      # gameplay_keep, segment 4
ANCHOR = 0x100                                       # bytes of a segment found in RAM to place it

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def labels_of(vrom):
    """(the en tree's copy of the segment, its labels) for the segment at vrom."""
    for (at, path), labels in af_nafe_gfx.LABELS.items():
        if at == vrom:
            return path, labels
    raise KeyError(vrom)


def segment_base(ram, data, labels):
    """Where the segment data is in ram: a stretch of ANCHOR bytes away from its labels and varied
    enough, found there once. None if no such stretch is found."""
    spans = [(at, at + w * h * bits // 8) for at, _, bits, w, h, _ in labels]
    for a in range(0, len(data) - ANCHOR, 0x40):
        win = data[a:a + ANCHOR]
        if any(s < a + ANCHOR and a < e for s, e in spans) or len(set(win)) < 32:
            continue
        at = ram.find(win)
        if at >= 0 and ram.find(win, at + 1) < 0:
            return at - a
    return None


def ink_pixels(rgb, bare, w, h):
    """[(x, y)] of the pixels the two frames differ in by INK at least."""
    return [(x, y) for y in range(h) for x in range(w)
            if sum(abs(a - b) for a, b in zip(rgb[(y * w + x) * 3:(y * w + x) * 3 + 3],
                                              bare[(y * w + x) * 3:(y * w + x) * 3 + 3])) >= INK]


def check_ink(diff, model, what):
    xs = [x for x, _ in diff]
    rows = [y for _, y in diff]
    got = (max(xs) - min(xs) + 1) if xs else 0
    check(xs and abs(got - model) <= TOLERANCE,
          "%s: its ink is %s frame pixels wide (x %s..%s, rows %s..%s), the model's %.1f" % (
              what, got, min(xs) if xs else None, max(xs) if xs else None,
              min(rows) if rows else None, max(rows) if rows else None, model))


def even(n):
    return n + (n & 1)                                # mFont_GetStringWidth rounds a width up to even


def runs(xs):
    """[(left, right + 1)] of the runs of consecutive frame columns in xs."""
    out = []
    for x in sorted(set(xs)):
        if out and x == out[-1][1]:
            out[-1][1] = x + 1
        else:
            out.append([x, x + 1])
    return [tuple(r) for r in out]


def ruler(seg, board):
    """The widest glyph of RULER_GLYPHS (in the en font's codes) whose columns are each whole ink or none: no
    faint edge for the threshold to count or not, and as long a ruler as the string's length allows (the
    English it places can be longer than the cartridge's string, which its scale's error grows with)."""
    whole = []
    for ch in RULER_GLYPHS:
        c = af_ui.n64_bytes("ruler", ch)[0]
        peaks = [max(af_font._row(seg, c, y)[0][x] for y in range(16)) for x in range(board.w[c])]
        if all(p in (0, 15) for p in peaks) and any(peaks):
            whole.append((board.w[c], -RULER_GLYPHS.index(ch), c))
    if not whole:
        raise SystemExit("emu_menus: no glyph of %r has whole columns only" % RULER_GLYPHS)
    return bytes([max(whole)[2]])


def faint_columns(seg):
    """Each glyph's columns with any ink (the font's model counts those of STRONG and more: whether the frame
    counts a fainter one depends on the colours behind it)."""
    return [[x for x in range(12) if any(af_font._row(seg, c, y)[0][x] for y in range(16))] for c in range(256)]


def faint_extent(board, faint, s):
    """(left, right) of the ink of s counting faint columns, or None (emu_board.Board.extent's way)."""
    pos, left, right = 0, None, None
    for c in s:
        cols = [k for k in faint[c] if k < board.w[c]]
        if cols:
            left = pos + cols[0] if left is None else left
            right = pos + cols[-1]
        pos += board.w[c]
    return None if left is None else (left, right)


def ink_matches(got, want):
    """got (left, right) within TOLERANCE of want's range: each edge where the model's threshold puts it, or
    out to where a faint column puts it."""
    return want[2] - TOLERANCE <= got[0] <= want[0] + TOLERANCE and want[1] - TOLERANCE <= got[1] <= want[3] + TOLERANCE


def swap_box(board, key, ink):
    """(the frame x the cartridge's box starts at, its scale) from the ink of key (the ruler) drawn in its
    place."""
    ext = board.extent(key)
    scale = (ink[1] - ink[0] - FRINGE) / ((ext[1] - ext[0] + 1) * FRAME_X)
    return ink[0] + FRINGE / 2 - ext[0] * scale * FRAME_X, scale


def swap_inks(board, faint, key, en, layout, left, scale):
    """[(left, right, left, right)] frame x of the English's ink where the en m_font.c places it in the
    cartridge's box starting at frame x left, by the model's threshold and counting faint columns: one for a
    line, one a character for a template in cells."""
    def ink(text, x, s):
        ext, wide = board.extent(text), faint_extent(board, faint, text)
        return (x + ext[0] * s * FRAME_X - FRINGE / 2, x + (ext[1] + 1) * s * FRAME_X + FRINGE / 2,
                x + wide[0] * s * FRAME_X - FRINGE / 2, x + (wide[1] + 1) * s * FRAME_X + FRINGE / 2)
    cell = 12 * scale * FRAME_X
    if layout & af_ui.CELLS:
        return [ink(en[i:i + 1], left + i * cell + (cell - even(board.width(en[i:i + 1])) * scale * FRAME_X) / 2, scale)
                for i in range(len(en)) if en[i] != 0x20]
    own, box = len(key) * cell, (af_ui.box_of(layout) or len(key)) * cell
    left += (own - box) / 2 if layout & af_ui.CENTER else own - box if layout & af_ui.RIGHT else 0
    width, s = even(board.width(en)) * scale * FRAME_X, scale
    if layout & af_ui.FIT and width > box:
        s, width = scale * box / width, box
    x = left + ((box - width) / 2 if layout & af_ui.CENTER else box - width if layout & af_ui.RIGHT else 0)
    return [ink(en, x, s)]


def check_labels(ram, vrom, af_en, cart, nafe, what):
    """Each label of the segment at vrom, in ram, holds the texels the en build drew it with."""
    path, labels = labels_of(vrom)
    data = open(os.path.join(af_en, path), "rb").read()
    base = segment_base(ram, data, labels)
    check(base is not None, "%s: %s is in RAM" % (what, os.path.basename(path)))
    if base is None:
        return
    ours = cart[vrom:vrom + len(data)]
    theirs = af_nafe_gfx.file_at(nafe, vrom) if nafe else None
    for at, fmt, bits, w, h, label in labels:
        size = w * h * bits // 8
        got = ram[base + at:base + at + size]
        want = theirs[at:at + size] if nafe else ours[at:at + size]
        check(got == want, "%s: %s (%#x, %s%d %dx%d) holds the %s texels%s" % (
            what, label, at, fmt, bits, w, h, "2010 patch's" if nafe else "cartridge's",
            "" if got == want else " (it holds the %s)" % (
                "cartridge's" if got == ours[at:at + size] else "neither's")))


def expected(af_en, gc_path):
    """{name: bytes} the en build's menus hold: the GameCube's, or the cartridge's."""
    gc = af_ui.read_text(gc_path) if gc_path else {}
    rom = open(os.path.join(af_en, af_ui.ROM), "rb").read()
    return {name: af_ui.n64_bytes(name, gc[name]) if name in gc else rom[at:at + size]
            for name, at, size, _, _ in af_ui.STRINGS}


def main(argv):
    emu_dir, rom_path, af_en, state, out = argv[:5]
    opts = dict(zip(argv[5::2], argv[6::2]))
    gc_path = opts.get("--gc") if os.path.exists(opts.get("--gc", "")) else None
    nafe_path = opts.get("--nafe") if os.path.exists(opts.get("--nafe", "")) else None
    own_path = opts.get("--own") if os.path.exists(opts.get("--own", "")) else None
    af_en = os.path.abspath(af_en)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    rom = open(rom_path, "rb").read()
    en_elf = os.path.join(af_en, "build", "animalforest-jp.elf")
    syms = emu_save.elf_symbols(en_elf)
    ref_map = os.path.join(os.path.dirname(af_en), "af", "build", "animalforest-jp.map")
    hole, hole_size = emu_save.section_of(ref_map, "build/src/code/m_choice_main.o", ".text")
    mttime = syms["mTM_time"]
    seg = open(os.path.join(af_en, "assets", "jp", "segment_00BCD000.bin"), "rb").read()
    board = emu_board.Board(seg, emu_board.read_cut(af_en))
    rule, faint = ruler(seg, board), faint_columns(seg)
    want = expected(af_en, gc_path)
    cart = open(os.path.join(af_en, af_ui.ROM), "rb").read()
    own = af_ui.read_text(own_path) if own_path else {}
    subs = af_ui.substitutions(cart, af_ui.read_text(gc_path) if gc_path else {}, own)
    nafe = open(nafe_path, "rb").read() if nafe_path else None
    print("emu_menus: the %s strings, the %s labels" % ("GameCube's" if gc_path else "cartridge's",
                                                       "2010 patch's" if nafe else "cartridge's"))
    shots, label_shots = [], []

    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        def setup():
            n64.load_state(state)
            words = struct.unpack(">II", n64.read(mttime, 8))
            blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            return ctl

        def find_table():
            ram = n64.read(0x80000000, 0x800000)
            at = ram.find(WIN0)
            return None if at < 0 else 0x80000000 + at

        def open_window(mode, untitled):
            """The window opened in mode, SETTLE frames after the hook opened it; with untitled, its
            title's length 0 from the frame its overlay is in RAM. (frame, table, rgb)."""
            ctl = setup()
            n64.write(ctl, struct.pack(">i4I", 1, LEDIT, mode, 0, ctl + HOOK_BUF))
            start = n64.frame
            table = None
            while n64.frame < start + SETTLE:
                n64.frames(1, 0)
                if table is None:
                    table = find_table()
                    if table is not None and untitled:
                        n64.write(table + mode * WIN_SIZE + LEN_AT, struct.pack(">i", 0))
            w, h, rgb = n64.screen()
            return table, w, h, rgb

        for mode, (what, name, sym) in enumerate(MODES):
            table, w, h, rgb = open_window(mode, False)
            if table is None:
                check(False, "%s: the window table is in RAM" % what)
                continue
            entry = n64.read(table + mode * WIN_SIZE, WIN_SIZE)
            ptr, length = struct.unpack(">Ii", entry[TITLE_AT:TITLE_AT + 8])
            text = n64.read(ptr, length) if 0x80000000 <= ptr < 0x80800000 else b""
            check(ptr == syms[sym] and text == want[name] and length == len(want[name]),
                  "%s: the title is the en build's %s, %d bytes (%r)" % (what, sym, length, text))
            path = os.path.join(out, "%d-%s.png" % (mode, name.lower()))
            write_png(path, w, h, rgb)
            shots.append(path)
            if mode == 1:
                suffix = n64.read(table + SUFFIX_AT, 4)
                check(suffix[:3] == b"   ", "%s: nothing after the text typed (%r)" % (what, suffix))
            _, w2, h2, bare = open_window(mode, True)
            diff = [(x, y) for y in range(h) for x in range(w)
                    if sum(abs(a - b) for a, b in zip(rgb[(y * w + x) * 3:(y * w + x) * 3 + 3],
                                                      bare[(y * w + x) * 3:(y * w + x) * 3 + 3])) >= INK]
            ink = [x for x, _ in diff]
            rows = [y for _, y in diff]
            ext = board.extent(want[name])
            model = (ext[1] - ext[0] + 1) * TITLE_SCALE * FRAME_X + FRINGE if ext else 0
            got = (max(ink) - min(ink) + 1) if ink else 0
            check(ink and abs(got - model) <= TOLERANCE,
                  "%s: the title's ink is %s frame pixels wide (x %s..%s, rows %s..%s), the model's %.1f" % (
                      what, got, min(ink) if ink else None, max(ink) if ink else None,
                      min(rows) if rows else None, max(rows) if rows else None, model))

        def open_menu(program, data0, blank=(), answers=False):
            """The menu opened by the hook, SETTLE frames on (and after A for the answers, SETTLE more);
            with blank, those (address, length) made spaces in RAM from the start. (w, h, rgb)."""
            ctl = setup()
            for at, size in blank:
                n64.write(at, b" " * size)
            n64.write(ctl, struct.pack(">i4I", 1, program, data0, 0, 0))
            n64.frames(SETTLE, 0)
            if answers:
                n64.press(A)
                n64.frames(SETTLE, 0)
            return n64.screen()

        def model_width(data, scale=1.0):
            ext = board.extent(data)
            return (ext[1] - ext[0] + 1) * scale * FRAME_X + FRINGE if ext else 0

        # the edit-end check: its answers' table, the question, the answers
        question = want["EE_QUESTION"]
        for data0 in (0, 1):
            what = "the edit-end check (window %d)" % data0
            w, h, rgb = open_menu(EDIT_END, data0)
            ram = n64.read(0x80000000, 0x800000)
            at = ram.find(EE_WIN0)
            check(at >= 0, "%s: its windows' table is in RAM" % what)
            if data0 == 0 and at >= 0:
                table = 0x80000000 + at + EE_ANSWERS_AT
                for i, (name, sym) in enumerate(EE_ANSWERS):
                    ptr, length = struct.unpack(">Ii", n64.read(table + 8 * i, 8))
                    text = n64.read(ptr, length) if 0x80000000 <= ptr < 0x80800000 else b""
                    check(ptr == syms[sym] and text == want[name] and length == len(want[name]),
                          "%s: answer %d is the en build's %s, %d bytes (%r)" % (what, i, sym, length, text))
                path = os.path.join(out, "edit-end-question.png")
                write_png(path, w, h, rgb)
                shots.append(path)
                _, _, bare = open_menu(EDIT_END, data0, [(syms["mSM_en_eeQuestion"], len(question))])
                check_ink(ink_pixels(rgb, bare, w, h), model_width(question), "%s: the question (%r)" % (what, question))
            names = [n for n, _ in EE_ANSWERS[:2 + data0]]
            w, h, rgb = open_menu(EDIT_END, data0, answers=True)
            path = os.path.join(out, "edit-end-answers-%d.png" % data0)
            write_png(path, w, h, rgb)
            shots.append(path)
            _, _, bare = open_menu(EDIT_END, data0, [(syms[s], len(want[n])) for n, s in EE_ANSWERS], answers=True)
            diff = ink_pixels(rgb, bare, w, h)
            top = min(y for _, y in diff) if diff else 0
            for i, name in enumerate(names):
                row = [(x, y) for x, y in diff if top + i * ANSWER_ROW <= y < top + (i + 1) * ANSWER_ROW]
                check_ink(row, model_width(want[name]), "%s: answer %d (%r)" % (what, i, want[name]))

        # the address book's first balloon: the book is empty in the houses' state
        what = "the address book's balloon"
        w, h, rgb = open_menu(ADDRESS, 0)
        path = os.path.join(out, "address-book.png")
        write_png(path, w, h, rgb)
        shots.append(path)
        for name, sym in (("AD_CHOOSE", "mSM_en_adChoose"), ("AD_EMPTY", "mSM_en_adEmpty")):
            text = n64.read(syms[sym], len(want[name]))
            check(text == want[name], "%s: %s holds the en build's string (%r)" % (what, sym, text))
        _, _, bare = open_menu(ADDRESS, 0, [(syms["mSM_en_adEmpty"], len(want["AD_EMPTY"]))])
        width = board.width(want["AD_EMPTY"])
        width += width & 1                                # mFont_GetStringWidth's even width
        scale = min(1.0, AD_TEXT_W / width)               # narrowed to the cartridge's 144 pixels
        check_ink(ink_pixels(rgb, bare, w, h), model_width(want["AD_EMPTY"], scale),
                  "%s: the book is empty (%r), at %.3f" % (what, want["AD_EMPTY"], scale))

        # the strings swapped where the font draws them
        table = next(a for n, a in syms.items() if n.endswith(":mFont_en_subst"))
        entries = {}
        for i, (name, key, en, layout, key_hole, en_hole) in enumerate(subs):   # (hole: the hook's code)
            entries[name] = (table + i * SUBST_SIZE, key, en, layout, key_hole, en_hole)
            got = n64.read(table + i * SUBST_SIZE, SUBST_SIZE)
            head = bytes([len(key), len(en), layout & 0xFF, af_ui.box_of(layout), af_ui.typed_of(layout),
                          af_ui.NO_HOLE if key_hole is None else key_hole,
                          af_ui.NO_HOLE if en_hole is None else en_hole])
            check(got[:SUBST_HEAD] == head and got[SUBST_HEAD:SUBST_HEAD + len(key)] == key
                  and got[SUBST_HEAD + af_ui.SUBST_KEY_MAX:SUBST_HEAD + af_ui.SUBST_KEY_MAX + len(en)] == en,
                  "the swap table: %s holds the cartridge's %d bytes and the English %r" % (name, len(key), en))

        def overlay_copy(ram, windows):
            """The address of the first of windows (stretches of the cartridge's data from a string on, longest
            first) found in ram exactly once outside the swap table, or None."""
            for win in windows:
                hits, at = [], ram.find(win)
                while at >= 0 and len(hits) < 2:
                    if not table <= 0x80000000 + at < table + len(subs) * SUBST_SIZE:
                        hits.append(at)
                    at = ram.find(win, at + 1)
                if len(hits) == 1:
                    return 0x80000000 + hits[0]
            return None

        def open_swap(menu, buttons, pokes=(), rewrite=None):
            """The menu opened with pokes [(address, bytes)] written first; with rewrite (windows of what
            holds a string from its start: the overlay's data, or the buffer a menu types it into; and the
            bytes), those bytes written there once it is in RAM. (w, h, rgb)."""
            ctl = setup()
            for at, data in pokes:
                n64.write(at, data)
            n64.write(ctl, struct.pack(">i4I", 1, menu[0], menu[1], 0, 0))
            todo = [rewrite] if rewrite else []

            def run(frames):                              # the rewrite once its place is in RAM (an overlay
                for _ in range(frames):                   # a button loads too)
                    n64.frames(1, 0)
                    if todo:
                        at = overlay_copy(n64.read(0x80000000, 0x800000), todo[0][0])
                        if at is not None:
                            n64.write(at, todo.pop()[1])
            run(SETTLE)
            for b in buttons:
                run(2 * SETTLE)                           # the menu takes buttons once it is at rest (the town
                n64.press(b, frames=4)                    # tune's not 120 frames after it opens, at 160 it does)
                run(SETTLE)
            return n64.screen()

        for menu, buttons, names in SWAP_MENUS:
            w, h, normal = open_swap(menu, buttons)
            path = os.path.join(out, "swap-program-%d.png" % menu[0])
            write_png(path, w, h, normal)
            shots.append(path)
            for name in names:
                name, rom_at = name if isinstance(name, tuple) else (name, None)
                at, key, en, layout, key_hole, en_hole = entries.get(name, (None,) * 6)
                if at is None:
                    check(False, "%s: in the swap table (no English for it)" % name)
                    continue
                typed, built = af_ui.typed_of(layout), None
                if name in BUILT:                         # the string built round its part, as the menu would
                    other, part = BUILT[name]
                    part = af_ui.n64_bytes(name, part)
                    key, en = key[:key_hole] + part + key[key_hole:], en[:en_hole] + part + en[en_hole:]
                    windows = [entries[other][1] + b" " * (typed - len(entries[other][1]))]
                    built = (windows, key.ljust(typed, b" "))
                elif typed:                               # the menu's buffer, where it typed the string
                    windows = [key.ljust(typed, b" ")]
                else:                                     # the overlay's data, from the string on
                    if rom_at is None:
                        rom_at = af_ui.key_pieces(next(k for n, k, _, _, _ in af_ui.SUBST if n == name))[0][0]
                    windows = [cart[rom_at:rom_at + n] for n in (16, 12, 8) if n > len(key)] + [key]
                # the ruler as far as the string's last character (a menu may lay itself out by that count,
                # mMl_strlen's: the tag sizes its window by it), the rest spaces
                reach = len(key.rstrip(b" "))
                ruled = (windows, (rule * reach).ljust(typed or len(key), b" "))
                what = "program %d's %s (%r)" % (menu[0], name, en.rstrip(b" "))
                en_at, off = at + SUBST_HEAD + af_ui.SUBST_KEY_MAX, [(at + SUBST_HEAD, b"\xFF")]
                seen = open_swap(menu, buttons, rewrite=built)[2] if built else normal
                _, _, bare = open_swap(menu, buttons, [(en_at, b" " * (len(en) - (len(part) if built else 0)))],
                                       rewrite=built)
                _, _, jp = open_swap(menu, buttons, off, rewrite=ruled)
                en_runs = runs(x for x, _ in ink_pixels(seen, bare, w, h))
                jp_runs = runs(x for x, _ in ink_pixels(jp, bare, w, h))    # bare: the menu's bytes as they are
                if not en_runs or not jp_runs:
                    check(False, "%s: ink drawn (English %d runs, the ruler %d)" % (what, len(en_runs), len(jp_runs)))
                    continue
                if layout & af_ui.CELLS:
                    left = jp_runs[0][0] + FRINGE / 2 - board.extent(rule)[0] * CELLS_SCALE * FRAME_X
                    want, got = swap_inks(board, faint, key, en, layout, left, CELLS_SCALE), en_runs
                else:
                    left, scale = swap_box(board, rule * reach, (jp_runs[0][0], jp_runs[-1][1]))
                    want, got = swap_inks(board, faint, key, en, layout, left, scale), [(en_runs[0][0], en_runs[-1][1])]
                check(len(want) == len(got) and all(ink_matches(g, e) for g, e in zip(got, want)),
                      "%s: its ink at %s, where the layout puts it in the cartridge's box: %s" % (
                          what, got, ", ".join("(%.1f, %.1f)" % e[:2] if e[:2] == e[2:] else
                                               "(%.1f, %.1f; faint columns %.1f, %.1f)" % e for e in want)))

        for i, (program, data0, vrom, what) in enumerate(LABEL_MENUS):
            ctl = setup()
            n64.write(ctl, struct.pack(">i4I", 1, program, data0, 0, ctl + HOOK_BUF if program == LEDIT else 0))
            n64.frames(SETTLE, 0)
            ram = n64.read(0x80000000, 0x800000)
            check_labels(ram, vrom, af_en, cart, nafe, what)
            if i == 0:
                check_labels(ram, KEEP, af_en, cart, nafe, "gameplay_keep")
            path = os.path.join(out, "labels-%d-program-%d.png" % (i, program))
            n64.screenshot(path)
            label_shots.append(path)
    contact.main([os.path.join(out, "sheet.png"), "2"] + shots)
    contact.main([os.path.join(out, "labels.png"), "2"] + label_shots)
    print("emu_menus: %d failures; screenshots in %s" % (len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
