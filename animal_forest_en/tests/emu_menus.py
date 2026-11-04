#!/usr/bin/env python3
"""The menus' own words in the en build, on screen.

    tests/emu_menus.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR [GC_UI]

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
     differ by INK at least: the VI filter leaves a faint fringe a pixel
     wide on each side);
  3. the destination's window draws nothing after the text typed (the
     overlay's three characters there are spaces, as the GameCube has
     nothing).
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
from n64emu import N64, write_png  # noqa: E402
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
FRAME_X = 2.0                                        # frame pixels a font pixel, wide (640 for 320)
TOLERANCE = 2.0                                      # frame pixels (the scale's rounding, the VI filter)
INK = 60                                             # the colours' difference, summed, that is the title's
# ink: below it the VI filter's fringe, a frame pixel on each side
SETTLE = 60
HOOK_BUF = 0x14

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def expected(af_en, gc_path):
    """{name: bytes} the en build's menus hold: the GameCube's, or the cartridge's."""
    gc = af_ui.read_text(gc_path) if gc_path else {}
    rom = open(os.path.join(af_en, af_ui.ROM), "rb").read()
    return {name: af_ui.n64_bytes(name, gc[name]) if name in gc else rom[at:at + size]
            for name, at, size, _, _ in af_ui.STRINGS}


def main(argv):
    emu_dir, rom_path, af_en, state, out = argv[:5]
    gc_path = argv[5] if len(argv) > 5 and os.path.exists(argv[5]) else None
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
    want = expected(af_en, gc_path)
    print("emu_menus: the %s strings" % ("GameCube's" if gc_path else "cartridge's"))
    shots = []

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
            model = (ext[1] - ext[0] + 1) * TITLE_SCALE * FRAME_X if ext else 0
            got = (max(ink) - min(ink) + 1) if ink else 0
            check(ink and abs(got - model) <= TOLERANCE,
                  "%s: the title's ink is %s frame pixels wide (x %s..%s, rows %s..%s), the model's %.1f" % (
                      what, got, min(ink) if ink else None, max(ink) if ink else None,
                      min(rows) if rows else None, max(rows) if rows else None, model))
    contact.main([os.path.join(out, "sheet.png"), "2"] + shots)
    print("emu_menus: %d failures; screenshots in %s" % (len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
