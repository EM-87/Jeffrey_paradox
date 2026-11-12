#!/usr/bin/env python3
"""Show messages of the en build's bank in the message window and measure their lines.

    tests/emu_messages.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR [--nafe NAFE_MESSAGES.txt] [--own OURS.txt ...]
                          [--gc-font GC_FONT.bin]

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). For each
message, from that state, a hook asks for it as the game does
(mMsg_request_main_appear on the base window, white, no speaker); once it
has typed out (the window waiting for A, its mainIndex 2, for 10 frames;
at most 300), its first page's lines are measured on the frame: each
line's first and last text pixel against the model, the en font's widths
and glyphs (tests/emu_board.py's Board) over the message as the en build's
bank holds it (AF_EN_DIR/assets/jp/en/msg_text.bin and msg_index.bin, what
the ROM's message segments are made of), within 2 frame pixels; an empty
line has none, and nothing below the page's lines is ink.

Which messages: those the en bank took from the AF Project's 2010
translation (--nafe, build/gc/nafe_messages.txt: tools/af_nafe_text.py)
and our own (--own, script/message/*.txt) whose first page needs no
speaker (its text, pauses, colours and talk animations only, and the
town's name: STR_COUNTRYNAME, measured as the name the state's save holds,
common_data + 0x2F60, since the en build puts nothing after it, string 484
empty) and waits for the player (<BTN> or <MSGEND>: not a page that clears itself after a pause
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

Then the names longer than 6, tokens (tools/af_items.py; NOTES, "Names
longer than 6"): Tom Nook (a special character, his actor found among the
NPCs by its fgName) as the speaker of the first message whose first page
puts in the speaker's name, with his token and with it off (the special
names' table emptied in RAM: his name cut to 6, as the cartridge had it):
the page's lines measured both ways with his name put in, and the plate's
ink (the name centred in 72 pixels from x 61, func_8009D308_jp, at the
screen's pitch: emu_board.BOARD_X), placed by the cut name's; the animals'
names whose fields hold tokens, loaded as the game loads them
(mNpc_GetNpcWorldNameTableNo), each its token, and the first put in a
message as free string 0, its lines measured. And the catchphrases longer
than 4, tokens in the string bank (af_items' string_tokens.txt): each
loaded into 4 bytes as the game loads one, its token; the widest put in a
page by STR_TAIL, Tom Nook speaking with the town's first animal behind
him (Npc.npcInfo.animal set to the save's first, its catchphrase loaded
so), the page's lines measured with the whole catchphrase and the
animal's name.

Where: the window's text from the frame's row 153 (lines 16 rows apart)
and x 127.18, 2.005 frame pixels to the font's pixel (fitted on 316 line
ends of 51 messages, residuals within 1.9; the letter board's text is
drawn about 2% narrower: tests/emu_board.py), the ink a pixel with a
colour under 150 (the window's white over the field is 216 and up in each;
its text, dark or coloured, has one well under). Writes each screenshot,
and a sheet of them, to OUT_DIR.
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
from n64emu import A, N64, write_png  # noqa: E402
import af_font  # noqa: E402
import af_text  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_save  # noqa: E402
import msgbank  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef unsigned short u16;
typedef int s32;
void* mMsg_Get_base_window_p(void);
s32 mMsg_request_main_appear(void* msg, void* actor, s32 showName, u8* color, s32 msgNo, s32 priority);
void* Actor_info_fgName_search(void* actorInfo, u16 fgName, s32 part);
void mNpc_GetNpcWorldNameTableNo(char* name, u16 npcId);
void mMsg_Set_free_str(void* msg, s32 freeStrNo, char* str, s32 strLen);
void mString_Load_StringFromRom(char* dst, s32 dstLen, s32 strIdx);
extern void* gamePT;
extern u8 common_data[];
/* the control, then the speakers' fgNames (a zero last), the animals' numbers and the names loaded */
typedef struct HookCtl { s32 cmd; s32 result; s32 arg[4]; u16 speakers[16]; u16 ids[64]; char names[6 * 64]; } HookCtl;
HookCtl hook_ctl;
u8 hook_color[4] = { 255, 255, 255, 255 };

void hook_main(void) {
    HookCtl* h = &hook_ctl;
    void* actor = 0;
    s32 i;

    if (h->cmd == 1) {
        /* a message asked for as an actor asks for one, on the base window, without a speaker */
        h->arg[1] = (s32)mMsg_Get_base_window_p();
        h->result = mMsg_request_main_appear((void*)h->arg[1], 0, 0, hook_color, h->arg[0], 5);
        h->cmd = 0;
    } else if (h->cmd == 2) {
        /* the same with the first actor of hook_speakers found among the NPCs as its speaker, its name shown */
        for (i = 0; h->speakers[i] != 0 && actor == 0; i++) {
            actor = Actor_info_fgName_search((u8*)gamePT + PLAY_ACTOR_INFO, h->speakers[i], ACTOR_PART_NPC);
        }
        h->arg[1] = (s32)mMsg_Get_base_window_p();
        h->arg[2] = (s32)actor;
        h->result = actor ? mMsg_request_main_appear((void*)h->arg[1], actor, 1, hook_color, h->arg[0], 5) : 0;
        h->cmd = 0;
    } else if (h->cmd == 3) {
        /* the names of the animals in ids (arg[1] of them), as the game loads one */
        for (i = 0; i < h->arg[1]; i++) {
            mNpc_GetNpcWorldNameTableNo(&h->names[6 * i], 0xE000 | h->ids[i]);
        }
        h->cmd = 0;
    } else if (h->cmd == 4) {
        /* a message without a speaker, its free string 0 the name of the animal arg[2] */
        mNpc_GetNpcWorldNameTableNo(h->names, 0xE000 | h->arg[2]);
        h->arg[1] = (s32)mMsg_Get_base_window_p();
        mMsg_Set_free_str((void*)h->arg[1], 0, h->names, 6);
        h->result = mMsg_request_main_appear((void*)h->arg[1], 0, 0, hook_color, h->arg[0], 5);
        h->cmd = 0;
    } else if (h->cmd == 5) {
        /* the strings in ids (arg[1] of them) loaded into 4 bytes each, as the game loads a catchphrase */
        for (i = 0; i < h->arg[1]; i++) {
            mString_Load_StringFromRom(&h->names[4 * i], 4, h->ids[i]);
        }
        h->cmd = 0;
    } else if (h->cmd == 6) {
        /* cmd 2's message, the town's first animal (the save's) behind the speaker, its catchphrase loaded from
           string arg[2] as the game loads one (mNpc_SetDefAnimalInfo) */
        u8* animal = common_data + SAVE_ANIMALS;

        for (i = 0; h->speakers[i] != 0 && actor == 0; i++) {
            actor = Actor_info_fgName_search((u8*)gamePT + PLAY_ACTOR_INFO, h->speakers[i], ACTOR_PART_NPC);
        }
        h->arg[1] = (s32)mMsg_Get_base_window_p();
        h->arg[3] = (s32)actor;
        if (actor != 0) {
            mString_Load_StringFromRom((char*)animal + ANIMAL_CATCHPHRASE, 4, h->arg[2]);
            *(u8**)((u8*)actor + NPC_ANIMAL) = animal;
            h->result = mMsg_request_main_appear((void*)h->arg[1], actor, 1, hook_color, h->arg[0], 5);
        }
        h->cmd = 0;
    }
}
""".replace("PLAY_ACTOR_INFO", "0x1C78").replace("ACTOR_PART_NPC", "3").replace("SAVE_ANIMALS", "0x9F18").replace(
    "ANIMAL_CATCHPHRASE", "0x4E5").replace("NPC_ANIMAL", "0x174")
# Game_Play.actorInfo (m_play.h), ACTOR_PART_NPC, common_data.save.animals (m_common_data.h), Animal_c.catchphrase
# (m_npc.h), Npc.npcInfo.animal (ac_npc.h)

CTL = struct.Struct(">ii4I")
SPEAKERS_AT, IDS_AT, NAMES_AT = CTL.size, CTL.size + 32, CTL.size + 32 + 128   # HookCtl's tables
COUNT = msgbank.N64_BANKS["message"][2]
NAMES = msgbank.NAMES
PAGE_END = {NAMES[n] for n in ("BTN", "MSGCLEAR", "MSGEND", "MSGCONTINUE", "MSGTIMEEND")}
NO_SPEAKER = {NAMES[n] for n in ("PAUSE", "COLORCHARS", "TEXTCOLOR", "DEMONPC0", "VOICETRUE", "VOICEFALSE")}
WAITS = {NAMES["BTN"], NAMES["MSGEND"]}      # the window waits for A: the next page, or closing
COUNTRYNAME, TOWN_AT = NAMES["STR_COUNTRYNAME"], 0x2F60   # the town's name: common_data.save.landInfo.name, 6 bytes
TOP, PITCH, LINES = 153, 16, 4              # the window's first line in the frame, its lines
WINDOW_X = (127.18, 2.005)                  # the frame's x of the text's left edge, frame pixels to the pixel
TEXT_X = range(118, 514)                    # the window's text area in the frame, the next page's arrow (516 on) left out
INK = 150                                   # a pixel with a colour under this is ink (the window's are 216 up)
TOLERANCE = 2.0
SHOWN, SETTLED = 300, 10                    # at most this long for a page to appear and type out; the window
                                            # waiting for A this long before the screenshot
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
# the names longer than 6 (tokens: tools/af_items.py, m_item_name.c)
TALKNAME, FREE0 = NAMES["STR_TALKNAME"], NAMES["STR_FREE0"]
NOOKS = (0xD008, 0xD01E, 0xD03A, 0xD03B, 0xD03C, 0xD009, 0xD00A, 0xD00B, 0xD014)   # Tom Nook's actors: their
NOOK_STRING = 0x205                         # name is string 0x205 (m_npc.c, l_sp_actor_name)
PLATE = (61.0, 72.0)                        # the plate's name: centred from x 61 in 72 pixels (func_8009D308_jp)
PLATE_INK = 160                             # the plate's text: a pixel with every colour under this (its green
                                            # has one over 240)
ANIMAL_NAME_LEN = 6
TAIL, CATCHPHRASE_LEN = NAMES["STR_TAIL"], 4
SAVE_ANIMALS = 0x9F18                       # common_data.save.animals: the town's (Animal_c, 0x528 bytes each,
                                            # its AnmPersonalID_c first: npcId)

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


def first_page(tokens, town=b"\x41" * 6, fill=None):
    """The first page's lines (the font's codes), or None if it needs a speaker (any other code) or does not wait
    for the player (a page that clears itself after a pause, a message that closes after a time, or goes on to
    another). The town's name (STR_COUNTRYNAME) is town, its bytes; fill: {code: the bytes the window puts in}."""
    text = ""
    fill = dict(fill or {}, **{str(COUNTRYNAME): town})
    for t in tokens:
        if t[0] == "c":
            if t[1] in PAGE_END:
                if t[1] not in WAITS:
                    return None
                break
            if str(t[1]) in fill:
                text += "".join(msgbank.N64_CHARS[b] for b in fill[str(t[1])])
                continue
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


def page_problems(rgb, w, page, board):
    """Each of the window's lines where the model puts page's (the font's codes, a line each), or nothing where
    it has none: [what is wrong]."""
    bad = []
    for k in range(LINES):
        e = board.extent(page[k]) if k < len(page) and page[k] else None
        got = measure(rgb, w, TOP + k * PITCH)
        want = e and (frame_x(e[0]), frame_x(e[1] + 1))
        ok = (got is None) if e is None else (
            got is not None and all(abs(g - x) <= TOLERANCE for g, x in zip(got, want)))
        if not ok:
            bad.append("line %d drawn at %s, the model's %s" % (k + 1, got, want and "(%.1f, %.1f)" % want))
    return bad


def on_first_page(tokens, code):
    """Whether code is on the message's first page."""
    for t in tokens:
        if t[0] == "c":
            if t[1] == code:
                return True
            if t[1] in PAGE_END:
                return False
    return False


def plate_ink(rgb, w, h):
    """(left, right + 1) of the name's ink on the plate (the box of its green, less its rounded edges), or None."""
    green = [(x, y) for y in range(96, TOP) for x in range(w)
             if rgb[(y * w + x) * 3 + 1] > 200 and rgb[(y * w + x) * 3 + 2] < 80 and 140 < rgb[(y * w + x) * 3] < 230]
    if not green:
        return None
    x0, x1 = min(x for x, _ in green) + 8, max(x for x, _ in green) - 8
    y0, y1 = min(y for _, y in green) + 6, max(y for _, y in green) - 4
    xs = [x for y in range(y0, y1) for x in range(x0, x1) if max(rgb[(y * w + x) * 3:(y * w + x) * 3 + 3]) < PLATE_INK]
    return (min(xs), max(xs) + 1) if xs else None


def name_record(af_en, n):
    """The characters of the en table's name n (tools/af_items.py's records)."""
    data = open(os.path.join(af_en, "assets", "jp", "en", "item_names.bin"), "rb").read()
    rec = data[n * 20:(n + 1) * 20]
    return rec[4:4 + rec[0]]


def token_number(field):
    """The en table's number a token in field stands for, or None."""
    if field[0] != 0x80 or not (0x81 <= field[1] < 0x81 + 64 and 0x81 <= field[2] < 0x81 + 64):
        return None
    return (field[1] - 0x81) * 64 + field[2] - 0x81


def special_names(af_en):
    """{string: the en table's number} of the special characters' names longer than 6 (the include's table)."""
    inc = open(os.path.join(af_en, "assets", "jp", "en", "item_names.inc")).read()
    return {int(a, 16): int(b) for a, b in re.findall(r"\{ (0x[0-9A-F]+), (\d+) \}", inc) if int(a, 16)}


def string_of(af_en, number):
    """The en string bank's string number, its bytes (the ROM's string segments)."""
    at = os.path.join(af_en, "assets", "jp", "en")
    text = open(os.path.join(at, "string_text.bin"), "rb").read()
    ends = open(os.path.join(at, "string_index.bin"), "rb").read()
    end = struct.unpack(">I", ends[4 * number:4 * number + 4])[0]
    start = struct.unpack(">I", ends[4 * number - 4:4 * number])[0] if number else 0
    return text[start:end]


def check_names(n64, state, af_en, en_elf, hole, hole_size, mttime, syms, bank, board, town, out, shots):
    """The names longer than 6, tokens (NOTES, "Names longer than 6"): Tom Nook's (a special character's) on the
    plate and put in a page by STR_TALKNAME, with its token and with the token off (the en table's special
    names emptied: his name cut to 6), whose plate places the plate; the animals' as the game loads them, each
    its token, and one put in a page as a free string. The lines measured."""
    specials = special_names(af_en)
    if NOOK_STRING not in specials:
        print("  (Tom Nook's name is not longer than 6 in this build: no special name to check)")
        return 0
    measured = 0

    def hooked(cmd, args=(0, 0, 0, 0), pokes=()):
        n64.load_state(state)
        words = struct.unpack(">II", n64.read(mttime, 8))
        blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
        emu_save.write_code(n64, hole, blob)
        emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
        for at, data in pokes:
            n64.write(ctl + at if at < 0x1000 else at, data)
        n64.write(ctl, CTL.pack(cmd, 0, *args))
        for _ in range(10):
            n64.frames(1, 0)
            if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                break
        return ctl, CTL.unpack(n64.read(ctl, CTL.size))

    hooked.n64, hooked.syms = n64, syms

    def settled(window):
        waited = 0
        for _ in range(SHOWN):
            n64.frames(1, 0)
            waited = waited + 1 if struct.unpack(">i", n64.read(window + MAIN_INDEX, 4))[0] == WAITING else 0
            if waited == SETTLED:
                break
        return n64.screen()

    full = name_record(af_en, specials[NOOK_STRING])
    cut = string_of(af_en, NOOK_STRING)[:ANIMAL_NAME_LEN].rstrip(b" ")
    talk = next((n for n in sorted(bank) if on_first_page(bank[n], TALKNAME)
                 and first_page(bank[n], town, {str(TALKNAME): full})), None)
    # the en table's special names in RAM (m_item_name.c's mIN_en_specialNames: its strings and records as the
    # include lists them, a zero last), found by its bytes: the token off where it starts
    listed = re.findall(r"\{ (0x[0-9A-F]+), (\d+) \}", open(os.path.join(af_en, "assets", "jp", "en",
                                                                         "item_names.inc")).read())
    n64.load_state(state)
    ram = n64.read(0x80000000, 0x800000)
    found = ram.find(b"".join(struct.pack(">HH", int(a, 16), int(b)) for a, b in listed))
    if found < 0:
        check(False, "the en table's special names in RAM")
        return 0
    table = 0x80000000 + found
    speakers = struct.pack(">%dH" % (len(NOOKS) + 1), *(NOOKS + (0,)))
    plates = {}
    for what, name, pokes in (("cut to 6, the token off", cut, [(table, bytes(2))]), ("its token", full, [])):
        _, (_, took, _, window, actor, _) = hooked(2, (talk, 0, 0, 0), [(SPEAKERS_AT, speakers)] + pokes)
        w, h, rgb = settled(window)
        path = os.path.join(out, "names-nook-%s.png" % ("token" if name == full else "cut"))
        write_png(path, w, h, rgb)
        shots.append(path)
        page = first_page(bank[talk], town, {str(TALKNAME): name})
        bad = ([] if took and actor else ["no speaker found (actor %08x) or not taken" % actor]) + \
            page_problems(rgb, w, page, board)
        measured += min(len(page), LINES)
        check(not bad, "Tom Nook's name %s, put in message %d by STR_TALKNAME: its first page's %d lines where the "
              "model has them%s" % (what, talk, len([x for x in page if x]), "; " + "; ".join(bad) if bad else ""))
        plates[name] = plate_ink(rgb, w, h)
    # the plate: the name centred in 72 pixels from x 61 (func_8009D308_jp), a font pixel BOARD_X's frame pixels
    # (the screen's pitch: the window's text is drawn wider, WINDOW_X); where its x 0 is on the frame, from the
    # cut name's ink
    pitch = emu_board.BOARD_X[1]

    def left_of(name):
        return PLATE[0] + (PLATE[1] - (board.width(name) + (board.width(name) & 1))) / 2

    ec, ef = board.extent(cut), board.extent(full)
    if plates.get(cut) and plates.get(full):
        origin = plates[cut][0] - pitch * (left_of(cut) + ec[0])
        want_cut = origin + pitch * (left_of(cut) + ec[1] + 1)
        want = (origin + pitch * (left_of(full) + ef[0]), origin + pitch * (left_of(full) + ef[1] + 1))
        got = plates[full]
        check(abs(plates[cut][1] - want_cut) <= TOLERANCE and all(abs(g - x) <= TOLERANCE for g, x in zip(got, want)),
              "the plate: Tom Nook's whole name's ink at %s, centred where the model puts it, (%.1f, %.1f) (the plate "
              "placed by the cut name's ink at %s, its right edge the model's %.1f)" % (got, want[0], want[1],
                                                                                       plates[cut], want_cut))
    else:
        check(False, "the plate: its name's ink found (%s)" % plates)

    # the animals' names: the en name segment's fields that hold tokens, loaded as the game loads them
    names_path = os.path.join(af_en, "assets", "jp", "segment_00E04000.bin")
    seg = open(names_path, "rb").read()
    fields = [(n, seg[8 + ANIMAL_NAME_LEN * n:8 + ANIMAL_NAME_LEN * (n + 1)]) for n in range((len(seg) - 8) // 6)]
    long_ = [(n, f) for n, f in fields if token_number(f) is not None]
    if not long_:
        print("  (no animal's name is a token in this build)")
        return measured
    ctl, _ = hooked(3, (0, len(long_), 0, 0), [(IDS_AT, struct.pack(">%dH" % len(long_), *(n for n, _ in long_)))])
    got = n64.read(ctl + NAMES_AT, ANIMAL_NAME_LEN * len(long_))
    loaded = [got[ANIMAL_NAME_LEN * k:ANIMAL_NAME_LEN * (k + 1)] for k in range(len(long_))]
    names = [name_record(af_en, token_number(f)) for _, f in long_]
    check(loaded == [f for _, f in long_] and all(len(x) > ANIMAL_NAME_LEN for x in names),
          "the animals' names longer than 6: %d loaded as the game loads them (mNpc_GetNpcWorldNameTableNo), each "
          "its token, standing for a name of %s characters" % (len(long_), "-".join(
              str(k) for k in sorted({min(len(x) for x in names), max(len(x) for x in names)}))))
    free = next((n for n in sorted(bank) if on_first_page(bank[n], FREE0)
                 and first_page(bank[n], town, {str(FREE0): names[0]})), None)
    n, field = long_[0]
    _, (_, took, _, window, _, _) = hooked(4, (free, 0, n, 0))
    w, h, rgb = settled(window)
    path = os.path.join(out, "names-animal-%d.png" % n)
    write_png(path, w, h, rgb)
    shots.append(path)
    page = first_page(bank[free], town, {str(FREE0): names[0]})
    bad = ([] if took else ["not taken by the window"]) + page_problems(rgb, w, page, board)
    measured += min(len(page), LINES)
    check(not bad, "animal %d's name (%d characters), put in message %d as free string 0: its first page's %d lines "
          "where the model has them%s" % (n, len(names[0]), free, len([x for x in page if x]),
                                         "; " + "; ".join(bad) if bad else ""))
    return measured + check_catchphrases(hooked, settled, speakers, af_en, seg, bank, board, town, out, shots)


def string_tokens(af_en):
    """{string: its token} of the catchphrases longer than 4 (tools/af_items.py's string_tokens.txt)."""
    path = os.path.join(af_en, "assets", "jp", "en", "string_tokens.txt")
    text = open(path).read() if os.path.exists(path) else ""
    return {int(n): bytes(int(b, 16) for b in re.findall(r"\{([0-9a-f]{2})\}", t))
            for n, t in re.findall(r"^## (\d+)\n(\S+)", text, re.M)}


def check_catchphrases(hooked, settled, speakers, af_en, seg, bank, board, town, out, shots):
    """The catchphrases longer than 4, tokens in the string bank: each loaded as the game loads one, its
    token; the widest put in a page by STR_TAIL, said by Tom Nook with the town's first animal behind him
    (its catchphrase loaded so), the page's lines measured with the whole catchphrase and the animal's name."""
    tokens = string_tokens(af_en)
    if not tokens:
        print("  (no catchphrase is a token in this build)")
        return 0
    numbers = sorted(tokens)
    loaded = {}
    for at in range(0, len(numbers), 64):
        part = numbers[at:at + 64]
        ctl, _ = hooked(5, (0, len(part), 0, 0), [(IDS_AT, struct.pack(">%dH" % len(part), *part))])
        got = hooked.n64.read(ctl + NAMES_AT, CATCHPHRASE_LEN * len(part))
        loaded.update({n: got[CATCHPHRASE_LEN * k:CATCHPHRASE_LEN * (k + 1)] for k, n in enumerate(part)})
    phrases = {n: name_record(af_en, token_number(tokens[n])) for n in numbers}
    check(all(loaded[n] == tokens[n] for n in numbers) and all(len(p) > CATCHPHRASE_LEN for p in phrases.values()),
          "the catchphrases longer than 4: %d loaded as the game loads one (mString_Load_StringFromRom, 4 bytes), "
          "each its token, standing for %d to %d characters (%d differ)" % (
              len(numbers), min(len(p) for p in phrases.values()), max(len(p) for p in phrases.values()),
              sum(1 for n in numbers if loaded[n] != tokens[n])))
    widest = max(numbers, key=lambda n: (board.width(phrases[n]), -n))
    phrase = phrases[widest]
    npc = struct.unpack(">H", hooked.n64.read(hooked.syms["common_data"] + SAVE_ANIMALS, 2))[0] & 0xFF
    field = seg[8 + ANIMAL_NAME_LEN * npc:8 + ANIMAL_NAME_LEN * (npc + 1)]
    name = name_record(af_en, token_number(field)) if token_number(field) is not None else field.rstrip(b" ")
    fill = {str(TAIL): phrase, str(TALKNAME): name}
    talk = next((n for n in sorted(bank) if on_first_page(bank[n], TAIL) and first_page(bank[n], town, fill)), None)
    _, (_, took, _, window, _, actor) = hooked(6, (talk, 0, widest, 0), [(SPEAKERS_AT, speakers)])
    w, h, rgb = settled(window)
    path = os.path.join(out, "catchphrase-%d.png" % widest)
    write_png(path, w, h, rgb)
    shots.append(path)
    page = first_page(bank[talk], town, fill)
    bad = ([] if took and actor else ["not taken (actor %08x)" % actor]) + page_problems(rgb, w, page, board)
    check(not bad, "the widest catchphrase (string %d, %d characters, %d pixels), put in message %d by STR_TAIL, "
          "the town's first animal's (number %d) behind the speaker: its first page's %d lines where the model has "
          "them%s" % (widest, len(phrase), board.width(phrase), talk, npc, len([x for x in page if x]),
                      "; " + "; ".join(bad) if bad else ""))
    return min(len(page), LINES)


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
        n64.load_state(state)
        town = n64.read(syms["common_data"] + TOWN_AT, 6).rstrip(b" ")
        print("  the town's name in the state's save: %d characters" % len(town))
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
            window = struct.unpack(">I", n64.read(ctl + 12, 4))[0]
            waited = 0
            for _ in range(SHOWN):
                n64.frames(1, 0)
                waited = waited + 1 if struct.unpack(">i", n64.read(window + MAIN_INDEX, 4))[0] == WAITING else 0
                if waited == SETTLED:
                    break
            w, h, rgb = n64.screen()
            path = os.path.join(out, "message-%d.png" % n)
            n64.screenshot(path)
            shots.append(path)
            page = first_page(bank[n], town)
            bad = ([] if took else ["not taken by the window"]) + page_problems(rgb, w, page, board)
            lines_checked += min(len(page), LINES)
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
        lines_checked += check_names(n64, state, af_en, en_elf, hole, hole_size, mttime, syms, bank, board, town,
                                     out, shots)
    contact.main([os.path.join(out, "sheet.png"), "3"] + shots)
    print("emu_messages: %d messages shown, %d lines measured, %d failures; screenshots in %s" % (
        len(controls + shown), lines_checked, len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
