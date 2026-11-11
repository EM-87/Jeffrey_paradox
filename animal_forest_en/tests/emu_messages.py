#!/usr/bin/env python3
"""Show messages of the en build's bank in the message window and measure their lines.

    tests/emu_messages.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR [--nafe NAFE_MESSAGES.txt] [--own OURS.txt ...]
                          [--gc-font GC_FONT.bin]

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). For each
message, from that state, a hook asks for it as the game does
(mMsg_request_main_appear on the base window, white, no speaker); once it
has typed out, its first page's lines are measured on the frame: each
line's first and last text pixel against the model, the en font's widths
and glyphs (tests/emu_board.py's Board) over the message as the en build's
bank holds it (AF_EN_DIR/assets/jp/en/msg_text.bin and msg_index.bin, what
the ROM's message segments are made of), within 2 frame pixels; an empty
line has none, and nothing below the page's lines is ink.

Which messages: those the en bank took from the AF Project's 2010
translation (--nafe, build/gc/nafe_messages.txt: tools/af_nafe_text.py)
and our own (--own, script/message/*.txt) whose first page needs no
speaker (its text, pauses, colours and talk animations only) and waits for
the player (<BTN> or <MSGEND>: not a page that clears itself after a pause
or a message that closes after a time, which a screenshot after it typed
out would miss), each checked to be in the bank as its file's message
laid out for the en font (af_text.layout), ours also to keep the
cartridge's codes that steer the game (af_text.laid_problems, against the
dump's build/gc/n64_jp.txt); and, as a control, the first two of the
GameCube's own (in neither file) with three lines or more on such a first
page.

Then the talk orders: each message taken or ours whose run needs no
choice and goes nowhere else (it ends with <MSGEND>, and has no choice,
next message, luck or quest code) and has talk orders in slot 0 is asked
for the same way and played to its end, A pressed each time the window
waits for it (its mainIndex 2, mMsg_INDEX_NORMAL: the page typed out);
every frame the talking animal's order in slot 0 is read (demo_data's
orderData[4][0], m_demo.h: DEMO_ORDER_NPC0, the 16 bytes before the
table, 10 values a type). Its values, as they change, must be the
message's orders as the window meets them (orders with no character,
pause or page between them set in one frame: the last shows), and the
message's orders the cartridge's own, the same values in the same order
(af_nafe_text puts them back where the 2010 translation had other ones;
ours keep them).

Where: the window's text from the frame's row 153 (lines 16 rows apart)
and x 127.18, 2.005 frame pixels to the font's pixel (fitted on 316 line
ends of 51 messages, residuals within 1.9; the letter board's text is
drawn about 2% narrower: tests/emu_board.py), the ink a pixel with a
colour under 150 (the window's white over the field is 216 and up in each;
its text, dark or coloured, has one well under). Writes each screenshot,
and a sheet of them, to OUT_DIR.
"""

import os
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import A, N64  # noqa: E402
import af_font  # noqa: E402
import af_text  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_save  # noqa: E402
import msgbank  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
void* mMsg_Get_base_window_p(void);
s32 mMsg_request_main_appear(void* msg, void* actor, s32 showName, u8* color, s32 msgNo, s32 priority);
typedef struct HookCtl { s32 cmd; s32 result; s32 arg[4]; } HookCtl;
HookCtl hook_ctl;
u8 hook_color[4] = { 255, 255, 255, 255 };

/* a message asked for as an actor asks for one, on the base window, without a speaker */
void hook_main(void) {
    HookCtl* h = &hook_ctl;

    if (h->cmd == 1) {
        h->arg[1] = (s32)mMsg_Get_base_window_p();
        h->result = mMsg_request_main_appear((void*)h->arg[1], 0, 0, hook_color, h->arg[0], 5);
        h->cmd = 0;
    }
}
"""

CTL = struct.Struct(">ii4I")
COUNT = msgbank.N64_BANKS["message"][2]
NAMES = msgbank.NAMES
PAGE_END = {NAMES[n] for n in ("BTN", "MSGCLEAR", "MSGEND", "MSGCONTINUE", "MSGTIMEEND")}
NO_SPEAKER = {NAMES[n] for n in ("PAUSE", "COLORCHARS", "TEXTCOLOR", "DEMONPC0", "VOICETRUE", "VOICEFALSE")}
WAITS = {NAMES["BTN"], NAMES["MSGEND"]}      # the window waits for A: the next page, or closing
TOP, PITCH, LINES = 153, 16, 4              # the window's first line in the frame, its lines
WINDOW_X = (127.18, 2.005)                  # the frame's x of the text's left edge, frame pixels to the pixel
TEXT_X = range(118, 514)                    # the window's text area in the frame, the next page's arrow (516 on) left out
INK = 150                                   # a pixel with a colour under this is ink (the window's are 216 up)
TOLERANCE = 2.0
SHOWN = 300                                 # frames for a page to appear and type out
CONTROLS = 2
# the talk orders
DEMONPC0 = NAMES["DEMONPC0"]
NPC0_SLOT0 = 0x10 + 4 * 2 * 10               # demo_data.orderData[DEMO_ORDER_NPC0][0] (m_demo.h DemoData)
MAIN_INDEX, WAITING, HIDDEN = 0x2B4, 2, 0     # MessageWindow.mainIndex: mMsg_INDEX_NORMAL, mMsg_INDEX_HIDE
WAIT_FRAMES, PLAY_FRAMES = 10, 4000           # a wait this long before A; at most this long a message
# codes 71 to 73 are LUCK_6 to LUCK_8 on the cartridge and the en build's STR_AMPM, CUTARTICLE and CAPTIALIZE
# (af_text.N64_CARRIED): strings, not luck
GOES_ON = ({NAMES[n] for n in NAMES if n.startswith(("SETNEXTMSG", "SETSELSTR", "LUCK_"))} |
           {NAMES[n] for n in ("OPENCHOICE", "SETFORCEMSG", "FORCENEXT", "MSGCONTINUE", "MSGTIMEEND", "SELNOB",
                               "GIVEOPEN", "GIVECLOSE", "DEMONPCQST")}) - set(af_text.N64_CARRIED.values())
TIMED = {NAMES["PAUSE"], NAMES["BTN"], NAMES["MSGCLEAR"]}

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def bank_of(af_en):
    """{number: tokens} of the bank the en build's ROM holds."""
    at = os.path.join(af_en, "assets", "jp", "en")
    text = open(os.path.join(at, "msg_text.bin"), "rb").read()
    ends = struct.unpack(">%dI" % COUNT, open(os.path.join(at, "msg_index.bin"), "rb").read()[:4 * COUNT])
    return {n: msgbank.decode(text[(ends[n - 1] if n else 0):ends[n]], msgbank.N64_CHARS) for n in range(COUNT)}


def first_page(tokens):
    """The first page's lines (the font's codes), or None if it needs a speaker (any other code) or does not wait
    for the player (a page that clears itself after a pause, a message that closes after a time, or goes on to
    another)."""
    text = ""
    for t in tokens:
        if t[0] == "c":
            if t[1] in PAGE_END:
                if t[1] not in WAITS:
                    return None
                break
            if t[1] not in NO_SPEAKER:
                return None
        else:
            text += t[1]
    lines = text.split("\n")
    return [bytes(msgbank.N64_BYTES[c] for c in line) for line in lines] if text.strip() else None


def is_talk_order(t):
    return t[0] == "c" and t[1] == DEMONPC0 and t[2][:1] == b"\0"


def talk_orders(tokens):
    """The values of a message's talk orders in slot 0, in order."""
    return [struct.unpack(">H", t[2][1:3])[0] for t in tokens if is_talk_order(t)]


def collapse(values):
    return [v for k, v in enumerate(values) if not k or v != values[k - 1]]


def seen_orders(tokens):
    """The values slot 0 shows as the window meets the orders: of orders with no character, pause or page
    between them, the last; a value the same as the one before not seen again."""
    out, pending = [], False
    for it in af_text._items(tokens):
        if is_talk_order(it):
            if pending:
                out[-1] = struct.unpack(">H", it[2][1:3])[0]
            else:
                out.append(struct.unpack(">H", it[2][1:3])[0])
            pending = True
        elif it[0] == "ch" or it[1] in TIMED:
            pending = False
    return collapse(out)


def plays_through(tokens):
    """A message the window plays to its end without a choice and without going on to another."""
    codes = [t[1] for t in tokens if t[0] == "c"]
    return bool(codes) and codes[-1] == NAMES["MSGEND"] and not GOES_ON & set(codes)


def measure(rgb, w, y0):
    xs = [x for y in range(y0, y0 + PITCH) for x in TEXT_X if min(rgb[(y * w + x) * 3:(y * w + x) * 3 + 3]) < INK]
    return (min(xs), max(xs) + 1) if xs else None


def frame_x(x):
    return WINDOW_X[0] + WINDOW_X[1] * x


def main(argv):
    nafe_path = gc_font = None
    own_paths = []
    while "--own" in argv:
        i = argv.index("--own")
        own_paths.append(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    if "--nafe" in argv:
        i = argv.index("--nafe")
        nafe_path = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if "--gc-font" in argv:
        i = argv.index("--gc-font")
        gc_font = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    emu_dir, rom_path, af_en, state, out = argv
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
    bank = bank_of(af_en)

    theirs = af_text.load_bank(nafe_path) if nafe_path and os.path.exists(nafe_path) else {}
    ours = {}
    for path in own_paths:
        ours.update(af_text.load_bank(path))
    cart = af_text.load_bank(os.path.join(os.path.dirname(af_en), "gc", "n64_jp.txt")) if theirs or ours else {}
    if theirs or ours:
        base = os.path.join(os.path.dirname(af_en), "baserom.z64")
        widths = af_font.widths(af_font.font(open(base, "rb").read(),
                                             open(gc_font, "rb").read() if gc_font else None)[1])
    for name, messages in (("taken from the 2010 translation", theirs), ("of ours", ours)):
        if messages:
            laid = [n for n in messages if msgbank.encode(af_text.carried(af_text.layout(messages[n], widths))) ==
                    msgbank.encode(bank[n])]
            check(len(laid) == len(messages), "the bank holds the %d messages %s, laid out for the en font (%d do)"
                  % (len(messages), name, len(laid)))
    if not theirs:
        print("  (no --nafe file: the 2010 translation's messages are not in this build)")
    if ours:
        kept = [n for n in ours if not af_text.laid_problems(af_text.layout(ours[n], widths), cart[n])]
        check(len(kept) == len(ours), "the %d messages of ours keep the cartridge's codes that steer the game and "
              "print no string it does not fill (%d do)" % (len(ours), len(kept)))
    shown = [n for n in sorted(set(theirs) | set(ours)) if first_page(bank[n])]
    controls = []
    for n in range(COUNT):
        page = first_page(bank[n])
        if n not in theirs and n not in ours and page and len([s for s in page if s]) >= 3 and \
                all(t[1].isascii() for t in bank[n] if t[0] == "t"):
            controls.append(n)
            if len(controls) == CONTROLS:
                break
    print("  %d of the %d messages taken and %d of our %d need no speaker on their first page; %d of the GameCube's "
          "as controls" % (len([n for n in shown if n in theirs]), len(theirs), len([n for n in shown if n in ours]),
                           len(ours), len(controls)))

    shots, bad_messages, lines_checked = [], [], 0
    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        for n in controls + shown:
            n64.load_state(state)
            words = struct.unpack(">II", n64.read(mttime, 8))
            blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            n64.write(ctl, CTL.pack(1, 0, n, 0, 0, 0))
            for _ in range(10):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    break
            took = struct.unpack(">i", n64.read(ctl + 4, 4))[0]
            n64.frames(SHOWN, 0)
            w, h, rgb = n64.screen()
            path = os.path.join(out, "message-%d.png" % n)
            n64.screenshot(path)
            shots.append(path)
            page = first_page(bank[n])
            bad = [] if took else ["not taken by the window"]
            for k in range(LINES):
                e = board.extent(page[k]) if k < len(page) and page[k] else None
                got = measure(rgb, w, TOP + k * PITCH)
                want = e and (frame_x(e[0]), frame_x(e[1] + 1))
                ok = (got is None) if e is None else (
                    got is not None and all(abs(g - x) <= TOLERANCE for g, x in zip(got, want)))
                lines_checked += k < len(page)
                if not ok:
                    bad.append("line %d drawn at %s, the model's %s" % (k + 1, got, want and "(%.1f, %.1f)" % want))
            if bad:
                bad_messages.append(n)
            check(not bad, "message %d (%s): its first page's %d lines where the model has them%s" % (
                n, "ours" if n in ours else "the 2010 translation's" if n in theirs else "the GameCube's",
                len([s for s in page if s]), "; " + "; ".join(bad) if bad else ""))
        # the talk orders, as the window plays the messages through
        played = [n for n in sorted(set(theirs) | set(ours)) if talk_orders(bank[n]) and plays_through(bank[n])]
        same = [n for n in theirs if talk_orders(bank[n]) == talk_orders(cart.get(n, []))]
        if theirs:
            check(len(same) == len(theirs), "the %d messages taken have the cartridge's talk orders in slot 0, the "
                  "same values in the same order (%d do)" % (len(theirs), len(same)))
        slot = syms["demo_data"] + NPC0_SLOT0
        bad_orders = []
        for n in played:
            n64.load_state(state)
            words = struct.unpack(">II", n64.read(mttime, 8))
            blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            n64.write(slot, bytes(2))
            n64.write(ctl, CTL.pack(1, 0, n, 0, 0, 0))
            for _ in range(10):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    break
            window = struct.unpack(">I", n64.read(ctl + 12, 4))[0]
            values, waited, shown_once = [0], 0, False
            for _ in range(PLAY_FRAMES):
                n64.frames(1, 0)
                value = struct.unpack(">H", n64.read(slot, 2))[0]
                if value != values[-1]:
                    values.append(value)
                index = struct.unpack(">i", n64.read(window + MAIN_INDEX, 4))[0]
                shown_once |= index != HIDDEN
                if shown_once and index == HIDDEN:
                    break
                waited = waited + 1 if index == WAITING else 0
                if waited == WAIT_FRAMES:
                    n64.frames(3, A)
                    n64.frames(2, 0)
                    waited = 0
            got, want = values[1:], seen_orders(bank[n])
            if got != want:
                bad_orders.append(n)
            check(got == want, "message %d: its talk orders as it plays, %s, the message's (%s)" % (
                n, " ".join("%x" % v for v in got), " ".join("%x" % v for v in want)))
        print("  %d messages played through (%d of ours), their talk orders as the cartridge's"
              % (len(played), len([n for n in played if n in ours])))
    contact.main([os.path.join(out, "sheet.png"), "3"] + shots)
    print("emu_messages: %d messages shown, %d lines measured, %d failures; screenshots in %s" % (
        len(controls + shown), lines_checked, len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
