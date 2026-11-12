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
where the model's threshold puts it or out to a faint column, and past
the ruler's own length out to what a pixel of its width carries (a menu
that draws two characters gives a ruler of two). A string the menu
builds round its own part (BUILT) is put in the buffer it types into in
place of another's; one the menu copies for each use (EVERY) gets the
ruler in every copy; the map's labels show in the acre it opens on, its
record given the label (MAP_LABEL: the town differs with each ROM, so no
cursor is moved); the tag's price window shows on the money's tag, given
the price's words in the tag's table (TAG_LIST). The bulletin board's
number and date (BOARD) the menu writes into their templates every frame,
so no ruler stays there: the date's place comes from the menu's own date
drawn with its swap off, at the call's scale, and the number's box is the
call's 109 pixels before it; the date's digits each in the cartridge's
cell, the number's English centred in its box.

Then the labels the 2010 patch drew (tools/af_nafe_gfx.py): the hook opens
each menu whose segment holds some (LABEL_MENUS), and each label is in RAM
where the segment was loaded (found by a stretch of the en tree's copy away
from the labels) with the texels the en build drew it with: the 2010
patch's (NAFE_ROM, make nafe's, when given) or the cartridge's own.
gameplay_keep's are checked in the first menu's RAM (segment 4 stays
loaded all through play). Program 11 (ovl__00789B60, its mailbox label)
is opened by the inventory from inside it and does not open on its own:
not checked here.

Then, in a process of its own (the emulator's plugins start once per
process), the title's warning that no controller is in the first port
(the en build draws its three lines through the font's line, which swaps
them: TL_NOPAD1 to 3): booted to the title, common_data's pad_connected
cleared, and from that state the warning opened three times as the
menus' swaps are (as it is; the three lines' English made spaces; their
swaps off and the cartridge's strings in the logo actor's data made
rulers), each line's ink, in its band of rows, where the layout puts it
in the box its ruler shows.

And strings of the en build's string bank, loaded by the hook as the game
loads them (mString_Load_StringFromRom, mString_Load_NumberStringAddUnitFromRom):
the staff credits' lines at the 15 bytes the orange box loads
(ovl_Mikanbox), as script/string/credits.txt has them; what the message
code puts after the town's name (string 484), all spaces; and a fish's
size as the fishing tourney writes it, its number and string 670.
Writes the screenshots to OUT_DIR.
"""

import os
import re
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, A, R, stick, write_png  # noqa: E402
import af_font  # noqa: E402
import af_items  # noqa: E402
import af_nafe_gfx  # noqa: E402
import af_text  # noqa: E402
import af_ui  # noqa: E402
import msgbank  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_save  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
void mSM_open_submenu_new(void* submenu, s32 programId, s32 arg2, s32 arg3, s32 arg4);
void SetGameFrame(s32 divisor);
void mPr_GetMotherMail(void* mail, void* pid, unsigned short present, s32 stationery, s32 mailNumber);
void mIN_copy_name_str(char* dst, unsigned short item);
u8* mIN_en_TokenName(u8* str, s32* len, s32* article);
void* mMsg_Get_base_window_p(void);
void mMsg_Set_item_str(void* msg, s32 no, char* str, s32 len);
void mMsg_Set_free_str(void* msg, s32 no, char* str, s32 len);
s32 mMsg_CopyItem(void* msg, s32 no, char* data, s32 start, s32 len);
s32 func_800A1AA4_jp(void* msg, s32* idx);          /* code 72: the GameCube's CUTARTICLE */
s32 func_800A1AC4_jp(void* msg, s32* idx);          /* code 73: its CAPITALIZE */
s32 func_800A134C_jp(void* msg, s32 idx, s32 no);   /* a free string */
s32 func_800A17FC_jp(void* msg, s32 idx, s32 no);   /* an item string */
void mHandbill_Set_free_str(s32 no, char* str, s32 len);
void func_80093478_jp(u8* buf, s32 size, s32 len, s32 fill);   /* a letter's part, its codes expanded */
s32 mMsg_request_main_appear(void* msg, void* actor, s32 showName, u8* color, s32 msgNo, s32 priority);
void mString_Load_StringFromRom(char* dst, s32 dstLen, s32 strIdx);
s32 mString_Load_NumberStringAddUnitFromRom(char* dst, unsigned short num, s32 idx);
u8 hook_color[4] = { 255, 255, 255, 255 };
extern void* gamePT;
typedef struct HookCtl { s32 cmd; s32 arg[4]; u8 buf[32]; } HookCtl;
HookCtl hook_ctl;
typedef struct HookText { s32 loaded; s32 no; s32 len; s32 cut; u8 text[64]; } HookText;   /* an mMsg_Data */
HookText hook_text;
u8 hook_letter[40];

/* 1: a submenu program opened as mSM_submenu_ctrl opens one; 2: a shop letter written into a Mail_c (the
   letter board check's way: tests/emu_board.py); 3: an item's name (arg 0) copied into buf as the game copies
   it, and the name its token stands for at buf + 12 (its length, its article and whether it is a token in
   args 1 to 3); 4: buf's 10 bytes set as the message's item string 0, and the text at buf + 12 (arg 0 bytes,
   the code that inserts it) given it, its new length in arg 1; 5: buf's 10 bytes set as the message's item
   string 0 and free string 0, and the text at buf + 12 (arg 0 bytes) run through the message window's
   handlers as its cursor runs them (codes 72 and 73, the article's; 36, free string 0; 49, item string 0;
   any other passed over), in a text of the hook's in place of the window's own: the text back at buf + 12,
   its length in arg 1; 6: buf's 10 bytes set as a letter's free string 0, and the text at buf + 12 (arg 0
   bytes) expanded as a letter's body is, in 40 bytes filled with spaces: the 20 first back at buf + 12; 7: buf's
   first arg 1 bytes set as the message's item string 0, and message arg 0 asked of the window as the demo's
   event messages ask for theirs (no speaker, its priority: m_demo.c, wait_emsg_start), whether it took the
   request in arg 2; 8: string arg 0 loaded into buf as the game loads one (arg 1 bytes, at most 32); 9: the
   number arg 0 with string arg 1 after it into buf as the fishing tourney writes a size, its length in arg 2 */
void hook_main(void) {
    HookCtl* h = &hook_ctl;
    s32* submenu = (s32*)((u8*)gamePT + 0x1CBC);

    if (h->cmd == 1 && submenu[3] == 0) {
        mSM_open_submenu_new(submenu, h->arg[0], h->arg[1], h->arg[2], h->arg[3]);
        submenu[3] = 1;
        submenu[0] = 1;
        SetGameFrame(2);
        h->cmd = 0;
    } else if (h->cmd == 2) {
        mPr_GetMotherMail((void*)h->arg[0], (void*)h->arg[1], 0, 0, h->arg[2]);
        h->cmd = 0;
    } else if (h->cmd == 3) {
        s32 len = 0;
        s32 art = 0;
        s32 i;
        u8* name;

        mIN_copy_name_str((char*)h->buf, (unsigned short)h->arg[0]);
        name = mIN_en_TokenName(h->buf, &len, &art);
        for (i = 0; name != 0 && i < len && i < 16; i++) {
            h->buf[12 + i] = name[i];
        }
        h->arg[1] = len;
        h->arg[2] = art;
        h->arg[3] = name != 0;
        h->cmd = 0;
    } else if (h->cmd == 4) {
        void* msg = mMsg_Get_base_window_p();

        mMsg_Set_item_str(msg, 0, (char*)h->buf, 10);
        h->arg[1] = mMsg_CopyItem(msg, 0, (char*)h->buf + 12, 0, h->arg[0]);
        h->cmd = 0;
    } else if (h->cmd == 5) {
        void* msg = mMsg_Get_base_window_p();
        void** data = (void**)((u8*)msg + 0xC);     /* the window's msgData */
        void* own = *data;
        HookText* t = &hook_text;
        s32 idx = 0;
        s32 i;

        mMsg_Set_item_str(msg, 0, (char*)h->buf, 10);
        mMsg_Set_free_str(msg, 0, (char*)h->buf, 10);
        for (i = 0; i < h->arg[0]; i++) {
            t->text[i] = h->buf[12 + i];
        }
        t->len = h->arg[0];
        *data = t;
        while (idx < t->len) {
            if (t->text[idx] != 0x7F) {
                idx++;
            } else if (t->text[idx + 1] == 72) {
                func_800A1AA4_jp(msg, &idx);
            } else if (t->text[idx + 1] == 73) {
                func_800A1AC4_jp(msg, &idx);
            } else if (t->text[idx + 1] == 36) {
                func_800A134C_jp(msg, idx, 0);
            } else if (t->text[idx + 1] == 49) {
                func_800A17FC_jp(msg, idx, 0);
            } else {
                idx += 2;
            }
        }
        *data = own;
        for (i = 0; i < t->len && i < 20; i++) {
            h->buf[12 + i] = t->text[i];
        }
        h->arg[1] = t->len;
        h->cmd = 0;
    } else if (h->cmd == 6) {
        s32 i;

        mHandbill_Set_free_str(0, (char*)h->buf, 10);
        for (i = 0; i < 40; i++) {
            hook_letter[i] = (i < h->arg[0]) ? h->buf[12 + i] : ' ';
        }
        func_80093478_jp(hook_letter, 40, h->arg[0], 2);
        for (i = 0; i < 20; i++) {
            h->buf[12 + i] = hook_letter[i];
        }
        h->cmd = 0;
    } else if (h->cmd == 7) {
        void* msg = mMsg_Get_base_window_p();

        mMsg_Set_item_str(msg, 0, (char*)h->buf, h->arg[1]);
        h->arg[2] = mMsg_request_main_appear(msg, 0, 0, hook_color, h->arg[0], 5);
        h->cmd = 0;
    } else if (h->cmd == 8) {
        mString_Load_StringFromRom((char*)h->buf, h->arg[1], h->arg[0]);
        h->cmd = 0;
    } else if (h->cmd == 9) {
        h->arg[2] = mString_Load_NumberStringAddUnitFromRom((char*)h->buf, (unsigned short)h->arg[0], h->arg[1]);
        h->cmd = 0;
    }
}
"""

LEDIT = 4
# the letter's tag (tag_ovl's func_80877B0C_jp, drawn by the en m_submenu.c's mSM_en_tagMailName in the
# GameCube's order): a shop letter in each of the pockets' ten letters (its sender the player's mother: "from"
# and the word for home), the inventory opened and its cursor brought onto the letters (five pushes right, one
# up), at rest
TAG_LETTER = 205
TAG_MOVES = [(stick(100, 0), 6)] * 5 + [(stick(0, 100), 6)]
TAG_REST = 150
PRIVATE_INFO, POCKET_MAIL, MAIL_SIZE = 0x10138, 0x40A, 0xA4   # common_data.privateInfo, its mail[], a Mail_c
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
MESSAGE_SHOWN = 300                                   # frames for a one-page message to appear and type out

EDIT_END, ADDRESS = 15, 13
EE_WIN0 = struct.pack(">iII4f", 2, 0x0C0017C8, 0x0C001790, -45.0, 43.0, 65.0, -74.0)  # its first window
EE_ANSWERS_AT = 0xC8                                 # D_80896A18_jp after D_80896950_jp
EE_ANSWERS = [("EE_ANSWER0", "mSM_en_eeAnswer0"), ("EE_ANSWER1", "mSM_en_eeAnswer1"),
              ("EE_ANSWER2", "mSM_en_eeAnswer2")]
ANSWER_ROW = 16                                      # frame rows between answers (a row a line: 240 rows)
AD_TEXT_W = 144                                      # the address book's balloon: the cartridge's 12 characters

TUNE_BUTTONS = (R,)                                  # the town tune's trash: its question
EVERY = "every"                                      # the ruler written into every copy of the string
# the map's label for the acre under its cursor, by its entry in D_8088FF20_jp (2 the shop, 3 the police, 4
# the post office, 5 the shrine, 6 the station, 7 the dump): set in the houses' acre's record, where the map
# opens (its label at +0x12, func_8088F110_jp; the record found by its residents' names, four pointers in a
# row to the copies of MP_VACANT: the route's save has four empty houses). The town differs with each ROM
# (its seed follows the run's timing), so the check moves no cursor
MAP_LABEL = "map label"
MAP_LABEL_AT = 0x12
# the tag's price window (kind 39: the price's word, then its digits a cell each and the word for bells), which a
# tag shows once the player sets a price: the check gives the money's tag (kind 8, from the inventory) kind 39's
# list of words in the tag's table (D_80878F08_jp: a list and its length for each kind, found in RAM by the
# lengths of kinds 1 to 8)
TAG_LIST = "tag list"
TAG_MONEY, TAG_PRICE = 8, 39
TAG_COUNTS = [3, 4, 4, 5, 4, 5, 3, 2]
# the bulletin board (program 6, ovl__00797A50), whose number and date the menu writes into their templates every
# frame (no ruler stays there): the date's place from the menu's own date drawn with its swap off (from the
# template's place, at func_80895328_jp's scale), the number's box the call's 109 pixels before it
BOARD = (6, 0)
BOARD_SCALE = 0.75
BOARD_DATE_AFTER = 109.0
# the menus that draw the strings the en build swaps: ((program, data0), buttons that bring them up (or a
# button and the frames it is held, or a map label to show), names; a name with the place in the dump of the
# copy the menu draws, where the overlay holds the same bytes twice, or with EVERY, where the menu copies the
# string for each use)
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
              # the inventory: the money's tag, each word where the tag's cursor is not on it (the word under
              # the cursor is drawn some 5% larger, from a place the cartridge's box does not give: measured,
              # the first word's English 2 frame pixels right of the box's left and its ruler 2 left)
              ((1, 0), (A,), ["TG_YAMERU"]),
              ((1, 0), (A, (stick(0, -100), 6)), ["TG_TUKAMU"]),
              ((5, 0), (), [("MP_VACANT", EVERY)]),                  # the map: the houses' acre, at rest
              ((5, 0), ((MAP_LABEL, 2),), ["MP_SHOP"]),
              ((5, 0), ((MAP_LABEL, 3),), ["MP_POLICE"]),
              ((5, 0), ((MAP_LABEL, 4),), ["MP_POST", "MP_OFFICE"]),
              ((5, 0), ((MAP_LABEL, 5),), ["MP_SHRINE"]),
              ((5, 0), ((MAP_LABEL, 6),), ["MP_STATION"]),
              ((5, 0), ((MAP_LABEL, 7),), ["MP_DUMP"]),
              ((1, 0), ((TAG_LIST, TAG_PRICE), A), ["TG_OKANE", "TG_BERU"])]   # the money's tag as the price's
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
TITLE_FRAME = 400                                    # the title drawn (tests/emu_check.py)
PAD_CONNECTED = 0x10AB0                              # common_data's pad_connected, set once at boot
# (second_game.c): 0 makes the title draw its warning
WARNING = ["TL_NOPAD1", "TL_NOPAD2", "TL_NOPAD3"]    # the warning's lines, top to bottom

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


def lever(edge, left, ruler):
    """What the ruler's error adds at an edge farther from the box's left than the ruler reaches: a frame pixel
    of its width (its scale, by 1 / ruler) carried to the edge's distance, beyond the pixel TOLERANCE allows at
    the ruler's own length. A menu that draws a string of two characters gives a ruler of two."""
    return max(0.0, abs(edge - left) - ruler) / ruler


def ink_matches(got, want, left=None, ruler=None):
    """got (left, right) within TOLERANCE of want's range: each edge where the model's threshold puts it, or
    out to where a faint column puts it; with the box's left and the ruler's width, what its scale's error
    carries to each edge too."""
    tl = TOLERANCE + (lever(want[0], left, ruler) if ruler else 0.0)
    tr = TOLERANCE + (lever(want[1], left, ruler) if ruler else 0.0)
    return want[2] - tl <= got[0] <= want[0] + tl and want[1] - tr <= got[1] <= want[3] + tr


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
    first = len(key) - len(key.lstrip(b" ")) if layout & af_ui.FIRST else 0      # from its first character
    left += first * cell
    own = (len(key) - first) * cell
    box = af_ui.box_of(layout) * cell if af_ui.box_of(layout) else own
    left += (own - box) / 2 if layout & af_ui.CENTER else own - box if layout & af_ui.RIGHT else 0
    width, s = even(board.width(en)) * scale * FRAME_X, scale
    if layout & af_ui.FIT and width > box:
        s, width = scale * box / width, box
    x = left + ((box - width) / 2 if layout & af_ui.CENTER else box - width if layout & af_ui.RIGHT else 0)
    return [ink(en, x, s)]


def found_once(ram, windows, skip):
    """The address of the first of windows (stretches of the cartridge's data from a string on, longest first)
    found in ram exactly once outside skip (the swap table's (start, end)), or None."""
    for win in windows:
        hits, at = [], ram.find(win)
        while at >= 0 and len(hits) < 2:
            if not skip[0] <= 0x80000000 + at < skip[1]:
                hits.append(at)
            at = ram.find(win, at + 1)
        if len(hits) == 1:
            return 0x80000000 + hits[0]
    return None


def bands(pixels):
    """pixels [(x, y)] split into bands of consecutive rows, top to bottom."""
    groups = []
    for y in sorted({y for _, y in pixels}):
        if groups and y == groups[-1][1] + 1:
            groups[-1][1] = y
        else:
            groups.append([y, y])
    return [[p for p in pixels if top <= p[1] <= bottom] for top, bottom in groups]


def title_warning(emu_dir, rom, out, syms, subs, cart, board, faint, rule, shots):
    """The title's warning that no controller is in the first port: each line's English where the en
    m_font.c's layout puts it in the box the cartridge's string had (the module's docstring)."""
    table = next(a for n, a in syms.items() if n.endswith(":mFont_en_subst"))
    skip = (table, table + len(subs) * SUBST_SIZE)
    index = {sub[0]: i for i, sub in enumerate(subs)}
    if not all(name in index for name in WARNING):
        check(False, "the title's warning: its lines in the swap table")
        return
    with N64(emu_dir, rom, os.path.join(out, "title"), timeout=120) as n64:
        n64.frames(TITLE_FRAME, 0)
        n64.write(syms["common_data"] + PAD_CONNECTED, b"\x00")
        state = os.path.join(out, "title.st")
        n64.save_state(state)

        def open_title(pokes=(), rulers=()):
            """The title SETTLE frames on from the state, with pokes [(address, bytes)] written first and each
            ruler (windows, bytes) written where its windows are found in RAM. (w, h, rgb), or None."""
            n64.load_state(state)
            for at, data in pokes:
                n64.write(at, data)
            ram = n64.read(0x80000000, 0x800000)
            for windows, data in rulers:
                at = found_once(ram, windows, skip)
                if at is None:
                    return None
                n64.write(at, data)
            n64.frames(SETTLE, 0)
            return n64.screen()

        def entry_at(name):
            return table + index[name] * SUBST_SIZE

        lines = [subs[index[name]] for name in WARNING]
        w, h, seen = open_title()
        path = os.path.join(out, "title-warning.png")
        write_png(path, w, h, seen)
        shots.append(path)
        _, _, bare = open_title([(entry_at(name) + SUBST_HEAD + af_ui.SUBST_KEY_MAX, b" " * len(en))
                                 for name, (_, _, en, _, _, _) in zip(WARNING, lines)])
        rulers = []
        for name, (_, key, _, _, _, _) in zip(WARNING, lines):
            rom_at = af_ui.key_pieces(next(k for n, k, _, _, _ in af_ui.SUBST if n == name))[0][0]
            rulers.append(([cart[rom_at:rom_at + n] for n in (32, 24)] + [key],
                           (rule * len(key.rstrip(b" "))).ljust(len(key), b" ")))
        ruled = open_title([(entry_at(name) + SUBST_HEAD, b"\xFF") for name in WARNING], rulers)
        if ruled is None:
            check(False, "the title's warning: the cartridge's strings in the logo actor's data in RAM")
            return
        en_bands, jp_bands = bands(ink_pixels(seen, bare, w, h)), bands(ink_pixels(ruled[2], bare, w, h))
        if len(en_bands) != len(WARNING) or len(jp_bands) != len(WARNING):
            check(False, "the title's warning: %d lines of English and %d rulers drawn, not %d"
                  % (len(en_bands), len(jp_bands), len(WARNING)))
            return
        for name, (_, key, en, layout, _, _), en_px, jp_px in zip(WARNING, lines, en_bands, jp_bands):
            en_runs, jp_runs = runs(x for x, _ in en_px), runs(x for x, _ in jp_px)
            reach = len(key.rstrip(b" "))
            left, scale = swap_box(board, rule * reach, (jp_runs[0][0], jp_runs[-1][1]))
            span = jp_runs[-1][1] - jp_runs[0][0]
            want, got = swap_inks(board, faint, key, en, layout, left, scale)[0], (en_runs[0][0], en_runs[-1][1])
            far = max(lever(want[k], left, span) for k in (1, 3))
            check(ink_matches(got, want, left, span),
                  "the title's warning, %s (%r): its ink at %s (rows %d..%d), where the layout puts it in the "
                  "cartridge's box (from x %.1f at the scale %.3f): %s%s" % (
                      name, en.rstrip(b" "), got, min(y for _, y in en_px), max(y for _, y in en_px), left, scale,
                      "(%.1f, %.1f)" % want[:2] if want[:2] == want[2:] else
                      "(%.1f, %.1f; faint columns %.1f, %.1f)" % want,
                      "; the ruler's error allows %.1f at the far edge" % (TOLERANCE + far) if far else ""))


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

        # the letter's tag
        def open_tag(pokes=()):
            """The inventory on a letter's tag, pokes [(address, bytes)] written first. (w, h, rgb)."""
            ctl = setup()
            for at, data in pokes:
                n64.write(at, data)
            priv = struct.unpack(">I", n64.read(syms["common_data"] + PRIVATE_INFO, 4))[0]
            for k in range(10):
                n64.write(ctl, struct.pack(">i4I", 2, priv + POCKET_MAIL + MAIL_SIZE * k, priv, TAG_LETTER, 0))
                n64.frames(2, 0)
            n64.write(ctl, struct.pack(">i4I", 1, 1, 0, 0, 0))
            n64.frames(SETTLE * 2, 0)
            for move, hold in TAG_MOVES:
                n64.frames(hold, move)
                n64.frames(40, 0)
            n64.frames(TAG_REST, 0)
            return n64.screen()

        tag_strings = [("TG_LETTER_TO", "mSM_en_tgLetterTo"), ("TG_FROM", "mSM_en_tgFrom"), ("TG_HOME", "mSM_en_tgHome")]
        for name, sym in tag_strings:
            got = n64.read(syms[sym], len(want[name]))
            check(got == want[name], "the letter's tag: %s holds %r (%r)" % (sym, want[name], got))
        w, h, tag = open_tag()
        path = os.path.join(out, "letter-tag.png")
        write_png(path, w, h, tag)
        shots.append(path)
        def blank(name, sym):
            return (syms[sym], b" " * len(want[name]))

        # each piece's ink: the frame without it. The word for "from" is measured with the sender gone too: spaces
        # in its place are not its width, and would move the sender
        _, _, no_first = open_tag([blank(*tag_strings[0])])
        _, _, no_home = open_tag([blank(*tag_strings[2])])
        _, _, no_from = open_tag([blank(*tag_strings[1]), blank(*tag_strings[2])])
        tag_ink = {"TG_LETTER_TO": runs(x for x, _ in ink_pixels(tag, no_first, w, h)),
                   "TG_FROM": runs(x for x, _ in ink_pixels(no_home, no_from, w, h)),
                   "TG_HOME": runs(x for x, _ in ink_pixels(tag, no_home, w, h))}
        first = want["TG_LETTER_TO"]
        _, _, ruled = open_tag([(syms["mSM_en_tgLetterTo"], rule * len(first))])
        _, _, unruled = open_tag([(syms["mSM_en_tgLetterTo"], b" " * len(first))])
        ruler_runs = runs(x for x, _ in ink_pixels(ruled, unruled, w, h))
        if not ruler_runs or not all(tag_ink.values()):
            check(False, "the letter's tag: ink drawn (%s; the ruler %d runs)" % (
                ", ".join("%s %d runs" % (n, len(r)) for n, r in tag_ink.items()), len(ruler_runs)))
        else:
            left, scale = swap_box(board, rule * len(first), (ruler_runs[0][0], ruler_runs[-1][1]))
            span = ruler_runs[-1][1] - ruler_runs[0][0]
            frm = want["TG_FROM"]
            places = {"TG_LETTER_TO": left, "TG_FROM": left,
                      "TG_HOME": left + even(board.width(frm)) * scale * FRAME_X}   # after "from" (its even width)
            for name, x in places.items():
                ink = swap_inks(board, faint, want[name], want[name], af_ui.LEFT, x, scale)[0]
                got = (tag_ink[name][0][0], tag_ink[name][-1][1])
                check(ink_matches(got, ink, left, span),
                      "the letter's tag: %r's ink at %s, the model's %s at the scale %.3f" % (
                          want[name], got, "(%.1f, %.1f)" % ink[:2], scale))

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
            """The address of the first of windows found in ram exactly once outside the swap table, or None."""
            return found_once(ram, windows, (table, table + len(subs) * SUBST_SIZE))

        def copies(ram, key):
            """The addresses of every copy of key in ram outside the swap table."""
            out, at = [], ram.find(key)
            while at >= 0:
                if not table <= 0x80000000 + at < table + len(subs) * SUBST_SIZE:
                    out.append(0x80000000 + at)
                at = ram.find(key, at + 1)
            return out

        def houses_record(ram):
            """The map's record of the acre its cursor opens on: the first of four pointers in a row, each to a
            copy of MP_VACANT (its residents' names), or None."""
            names = set(copies(ram, entries["MP_VACANT"][1]))
            for n in sorted(names):
                p, a = struct.pack(">I", n), ram.find(struct.pack(">I", n))
                while a >= 0:
                    if a % 4 == 0 and all(struct.unpack(">I", ram[a + 4 * k:a + 4 * k + 4])[0] in names
                                          for k in range(4)):
                        return 0x80000000 + a
                    a = ram.find(p, a + 1)
            return None

        def tag_lists(ram):
            """The tag's table of words (kind 0 first: a list and its length each), or None."""
            pattern = b"".join(b"\x80[\x00-\xff]{3}" + struct.pack(">I", n) for n in TAG_COUNTS)
            hits = [m.start() for m in re.finditer(pattern, ram, re.DOTALL) if m.start() % 4 == 0]
            return 0x80000000 + hits[0] - 8 if len(hits) == 1 else None

        def open_swap(menu, buttons, pokes=(), rewrite=None):
            """The menu opened with pokes [(address, bytes)] written first; with rewrite (windows of what
            holds a string from its start: the overlay's data, or the buffer a menu types it into; the bytes;
            and whether every copy of the last window gets them, every frame), those bytes written there once
            it is in RAM. (w, h, rgb)."""
            ctl = setup()
            for at, data in pokes:
                n64.write(at, data)
            n64.write(ctl, struct.pack(">i4I", 1, menu[0], menu[1], 0, 0))
            todo = [rewrite] if rewrite else []

            def run(frames):                              # the rewrite once its place is in RAM (an overlay
                for _ in range(frames):                   # a button loads too)
                    n64.frames(1, 0)
                    if todo:
                        windows, data, every = todo[0]
                        ram = n64.read(0x80000000, 0x800000)
                        if every:
                            for at in copies(ram, windows[-1]):
                                n64.write(at, data)
                        else:
                            at = overlay_copy(ram, windows)
                            if at is not None:
                                n64.write(at, data)
                                todo.pop()
            run(SETTLE)
            for b in buttons:
                b, hold = b if isinstance(b, tuple) else (b, 4)
                run(2 * SETTLE)                           # the menu takes buttons once it is at rest (the town
                if b == MAP_LABEL:                        # tune's not 120 frames after it opens, at 160 it does)
                    record = houses_record(n64.read(0x80000000, 0x800000))
                    if record is None:
                        check(False, "the map: the record of the acre it opens on (four empty houses)")
                    else:
                        n64.write(record + MAP_LABEL_AT, struct.pack(">h", hold))
                elif b == TAG_LIST:
                    lists = tag_lists(n64.read(0x80000000, 0x800000))
                    if lists is None:
                        check(False, "the tag: its table of words in RAM (the lengths of kinds 1 to 8)")
                    else:
                        n64.write(lists + 8 * TAG_MONEY, n64.read(lists + 8 * hold, 4))
                else:
                    n64.press(b, frames=hold)
                run(SETTLE)
            return n64.screen()

        for i, (menu, buttons, names) in enumerate(SWAP_MENUS):
            w, h, normal = open_swap(menu, buttons)
            path = os.path.join(out, "swap-%02d-program-%d-%d.png" % ((i,) + menu))
            write_png(path, w, h, normal)
            shots.append(path)
            for name in names:
                name, rom_at = name if isinstance(name, tuple) else (name, None)
                every = rom_at == EVERY
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
                    built = (windows, key.ljust(typed, b" "), False)
                elif typed:                               # the menu's buffer, where it typed the string
                    windows = [key.ljust(typed, b" ")]
                elif every:                               # each copy the menu made of it
                    windows = [key]
                else:                                     # the overlay's data, from the string on
                    if rom_at is None:
                        rom_at = af_ui.key_pieces(next(k for n, k, _, _, _ in af_ui.SUBST if n == name))[0][0]
                    # the key alone only when it is as long as the shortest window: a short one turns up by chance
                    # before the overlay is in RAM (the map's shop label, 3 bytes, in the heap: the ruler went
                    # there and the map drew its own string)
                    windows = [cart[rom_at:rom_at + n] for n in (16, 12, 8) if n > len(key)] + (
                        [key] if len(key) >= 8 else [])
                # the ruler as far as the string's last character (a menu may lay itself out by that count,
                # mMl_strlen's: the tag sizes its window by it), the rest spaces
                reach = len(key.rstrip(b" "))
                ruled = (windows, (rule * reach).ljust(typed or len(key), b" "), every)
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
                span = None                               # the scale known: nothing carried
                if layout & af_ui.CELLS:
                    left = jp_runs[0][0] + FRINGE / 2 - board.extent(rule)[0] * CELLS_SCALE * FRAME_X
                    want, got = swap_inks(board, faint, key, en, layout, left, CELLS_SCALE), en_runs
                else:
                    left, scale = swap_box(board, rule * reach, (jp_runs[0][0], jp_runs[-1][1]))
                    span = jp_runs[-1][1] - jp_runs[0][0]  # the ruler's width
                    want, got = swap_inks(board, faint, key, en, layout, left, scale), [(en_runs[0][0], en_runs[-1][1])]
                far = max((lever(e[k], left, span) for e in want for k in (1, 3)), default=0.0) if span else 0.0
                check(len(want) == len(got) and all(ink_matches(g, e, left, span) for g, e in zip(got, want)),
                      "%s: its ink at %s, where the layout puts it in the cartridge's box: %s%s" % (
                          what, got, ", ".join("(%.1f, %.1f)" % e[:2] if e[:2] == e[2:] else
                                               "(%.1f, %.1f; faint columns %.1f, %.1f)" % e for e in want),
                          "; the ruler's error allows %.1f at the far edge" % (TOLERANCE + far) if far else ""))

        # the bulletin board's number and date (BOARD), which the menu writes into their templates every frame:
        # each swap's English blanked gives its ink; the date drawn the cartridge's way (its swap off) its place,
        # from where the menu draws it at its scale; the number's box the call's distance before it
        if all(n in entries for n in ("BB_NUMBER", "BB_DATE")):
            w, h, seen = open_swap(BOARD, ())
            path = os.path.join(out, "swap-board-program-%d-%d.png" % BOARD)
            write_png(path, w, h, seen)
            shots.append(path)
            (num_at, num_key, num_en, num_layout, _, num_hole), date_at = entries["BB_NUMBER"], entries["BB_DATE"][0]
            found = copies(n64.read(0x80000000, 0x800000), num_key + bytes(3))   # D_80895C08_jp's word, its padding
            if len(found) != 1:
                check(False, "the bulletin board: its templates in RAM (%d copies of the number's)" % len(found))
            else:
                number = n64.read(found[0] - 2, 2)        # the number's two cells, and the date after the padding
                date = n64.read(found[0] + 6, 10)
                _, _, date_bare = open_swap(BOARD, (), [(date_at + SUBST_HEAD + af_ui.SUBST_KEY_MAX, b" " * 10)])
                _, _, date_off = open_swap(BOARD, (), [(date_at + SUBST_HEAD, b"\xFF")])
                _, _, num_bare = open_swap(BOARD, (), [(num_at + SUBST_HEAD + af_ui.SUBST_KEY_MAX,
                                                        b" " * len(num_en))])
                cells = runs(x for x, _ in ink_pixels(seen, date_bare, w, h))
                plain = runs(x for x, _ in ink_pixels(date_off, date_bare, w, h))
                marks = runs(x for x, _ in ink_pixels(seen, num_bare, w, h))
                if not cells or not plain or not marks:
                    check(False, "the bulletin board: ink drawn (the date %d runs, its own way %d, the number %d)" % (
                        len(cells), len(plain), len(marks)))
                else:
                    left = plain[0][0] + FRINGE / 2 - board.extent(date)[0] * BOARD_SCALE * FRAME_X
                    own = swap_inks(board, faint, date, date, af_ui.LEFT, left, BOARD_SCALE)[0]
                    check(ink_matches((plain[0][0], plain[-1][1]), own),
                          "the bulletin board: the date %r drawn its own way at %s, the model's (%.1f, %.1f) at the "
                          "scale %.2f" % (date, (plain[0][0], plain[-1][1]), own[0], own[1], BOARD_SCALE))
                    want = swap_inks(board, faint, date, date, af_ui.CELLS, left, BOARD_SCALE)
                    check(len(want) == len(cells) and all(ink_matches(g, e) for g, e in zip(cells, want)),
                          "the bulletin board: BB_DATE's digits at %s, a cell each in the cartridge's cells: %s" % (
                              cells, ", ".join("(%.1f, %.1f)" % e[:2] for e in want)))
                    # the number's box, and its ink: the English with the menu's number, against the frame with
                    # the English blanked, where the number is drawn alone (both edges the outermost of the two)
                    key = number + num_key
                    built = num_en[:num_hole] + number + num_en[num_hole:]
                    alone = b" " * num_hole + number + b" " * (len(num_en) - num_hole)
                    x = left - BOARD_DATE_AFTER * FRAME_X
                    m1, m2 = (swap_inks(board, faint, key, t, num_layout, x, BOARD_SCALE)[0] for t in (built, alone))
                    want = (min(m1[0], m2[0]), max(m1[1], m2[1]), min(m1[2], m2[2]), max(m1[3], m2[3]))
                    got = (marks[0][0], marks[-1][1])
                    check(ink_matches(got, want), "the bulletin board: BB_NUMBER (%r) at %s, the layout's (%.1f, "
                          "%.1f) in its box %.1f pixels before the date's" % (built, got, want[0], want[1],
                                                                             BOARD_DATE_AFTER * FRAME_X))

        # the items' names (tools/af_items.py, the en m_item_name.c): an item's name copied as the game copies it
        # is a token for the table's record, which stands for the record's name and article; a message's item
        # string takes the name; the inventory draws the money's name, measured like the swaps (the ruler as
        # long as the token: the inventory centres the name by that length)
        inc_path = os.path.join(af_en, "assets", "jp", "en", "item_names.inc")
        if os.path.exists(inc_path) and "#define ITEM_EN_FROM_GC 1" in open(inc_path).read():
            item_records = open(os.path.join(af_en, "assets", "jp", "en", "item_names.bin"), "rb").read()
            bases = af_items.kind_bases()

            def record(n):
                r = item_records[n * af_items.RECORD_SIZE:(n + 1) * af_items.RECORD_SIZE]
                return r[1], r[2], r[4:4 + r[0]]

            def token(n):
                head = bytes([af_items.TOKEN_MARK, af_items.TOKEN_DIGIT0 + n // af_items.TOKEN_BASE,
                              af_items.TOKEN_DIGIT0 + n % af_items.TOKEN_BASE])
                return (head + bytes([af_items.TOKEN_MARK]) * (record(n)[1] - len(head))).ljust(af_items.NAME_LEN, b" ")

            samples = []
            for k, (kind, count, _, _, _) in enumerate(af_items.KINDS[:16]):
                for i in sorted({0, count - 1}):
                    samples.append(("%s %d" % (kind, i), 0x2000 | (k << 8) | i, bases[kind] + i))
            for f in (0, 1, 490, 850, 946):
                for face in (0, 3):
                    samples.append(("furniture %d facing %d" % (f, face), 0x1000 + f * af_items.FTR_FACES + face,
                                    bases["ftr"] + f))
            samples += [("the clothes' first as furniture", 0x17AC, bases["cloth"]),
                        ("the insects' first as furniture", 0x1BA8, bases["insect"])]
            ctl = setup()
            n64.frames(2, 0)
            for what, item, n in samples:
                n64.write(ctl, struct.pack(">i4I", 3, item, 0, 0, 0))
                n64.frames(2, 0)
                cmd, _, length, art, is_token = struct.unpack(">i4i", n64.read(ctl, HOOK_BUF))
                buf = n64.read(ctl + HOOK_BUF, 32)
                want_art, _, want_name = record(n)
                check(cmd == 0 and is_token and buf[:af_items.NAME_LEN] == token(n) and length == len(want_name)
                      and buf[12:12 + length] == want_name and art == want_art,
                      "the items' names: %s (%#06x) is the token %s for the table's %d, %r (%s)" % (
                          what, item, buf[:af_items.NAME_LEN].hex(), n, buf[12:12 + max(0, length)],
                          af_items.ARTICLES[art] if 0 <= art < len(af_items.ARTICLES) else art))
            n64.write(ctl, struct.pack(">i4I", 3, 0, 0, 0, 0))   # no item: the cartridge's blank
            n64.frames(2, 0)
            buf = n64.read(ctl + HOOK_BUF, af_items.NAME_LEN)
            check(buf == b" " * af_items.NAME_LEN, "the items' names: no item is a blank name (%r)" % buf)
            money = bases["money"]
            code = bytes([0x7F, msgbank.NAMES["STR_ITEM0"]])
            n64.write(ctl + HOOK_BUF, token(money) + bytes(2) + code)
            n64.write(ctl, struct.pack(">i4I", 4, len(code), 0, 0, 0))
            n64.frames(2, 0)
            new_len = struct.unpack(">i", n64.read(ctl + 8, 4))[0]
            got = n64.read(ctl + HOOK_BUF + 12, max(0, min(new_len, 20)))
            check(got == record(money)[2], "the items' names: a message's item string 0 takes the money's name "
                  "(%r, %d bytes)" % (got, new_len))

            # the articles (the GameCube's way: an item's name goes into a message, a choice or a letter after
            # its article, unless the text cut it, and with a capital if the text asked; the en m_msg_main.c and
            # m_handbill.c, the GameCube's CUTARTICLE and CAPTIALIZE carried as codes 72 and 73)
            def code(name):                           # the code as the compiled bank holds it
                n = msgbank.NAMES[name]
                return bytes([0x7F, af_text.N64_CARRIED.get(n, n)])
            cut, cap, item0, free0 = (code(n) for n in ("CUTARTICLE", "CAPTIALIZE", "STR_ITEM0", "STR_FREE0"))

            def said(n, capital=False, article=True):
                art, _, name = record(n)
                text = (af_items.ARTICLES[art].encode() + b" " if article and art else b"") + name
                return text[:1].upper() + text[1:] if capital else text

            tool, fruit = bases["tool"], bases["fruit"]
            cases = [("an item string", tool, item0, said(tool)),
                     ("its article cut", tool, cut + item0, said(tool, article=False)),
                     ("a capital", tool, cap + item0, said(tool, capital=True)),
                     ("cut and a capital", tool, cap + cut + item0, said(tool, True, False)),
                     ("a free string", fruit, free0, said(fruit)),
                     ("no article to cut", money, cap + cut + item0, said(money, True)),
                     ("the requests spent by the first string", tool, cap + cut + item0 + b" " + item0,
                      said(tool, True, False) + b" " + said(tool))]
            for what, n, text, want in cases:
                n64.write(ctl + HOOK_BUF, token(n) + bytes(2) + text)
                n64.write(ctl, struct.pack(">i4I", 5, len(text), 0, 0, 0))
                n64.frames(2, 0)
                new_len = struct.unpack(">i", n64.read(ctl + 8, 4))[0]
                got = n64.read(ctl + HOOK_BUF + 12, max(0, min(new_len, 20)))
                check(got.replace(cut, b"").replace(cap, b"") == want,
                      "the items' names: a message's %s: %r gives %r (%r)" % (what, text, want, got))
            plain = b"bob".ljust(af_items.NAME_LEN)
            n64.write(ctl + HOOK_BUF, plain + bytes(2) + cap + free0)
            n64.write(ctl, struct.pack(">i4I", 5, 4, 0, 0, 0))
            n64.frames(2, 0)
            got = n64.read(ctl + HOOK_BUF + 12, 5)
            check(got == cap + b"Bob", "the items' names: a message's capital on a string of no item (%r)" % got)
            n64.write(ctl + HOOK_BUF, token(tool) + bytes(2) + item0)
            n64.write(ctl, struct.pack(">i4I", 4, len(item0), 0, 0, 0))
            n64.frames(2, 0)
            new_len = struct.unpack(">i", n64.read(ctl + 8, 4))[0]
            got = n64.read(ctl + HOOK_BUF + 12, max(0, min(new_len, 20)))
            check(got == said(tool), "the items' names: a choice's item string takes the article (%r)" % got)
            for what, text, want in [("a free string", free0 + b"!", said(tool) + b"!"),
                                     ("its article cut", b"x " + cut + free0, b"x " + said(tool, article=False)),
                                     ("a capital", cap + free0, said(tool, capital=True)),
                                     ("cut and a capital", cap + cut + free0 + b".", said(tool, True, False) + b".")]:
                n64.write(ctl + HOOK_BUF, token(tool) + bytes(2) + text)
                n64.write(ctl, struct.pack(">i4I", 6, len(text), 0, 0, 0))
                n64.frames(2, 0)
                got = n64.read(ctl + HOOK_BUF + 12, 20).rstrip(b" ")
                check(got == want, "the items' names: a letter's %s: %r gives %r (%r)" % (what, text, want, got))

            # on screen: the window shows a message of the translation's bank (the first of each kind in the
            # GameCube's English that needs no speaker and is one page: the item string put in with its article,
            # cut, or capitalized) with the first tool's token in item string 0, and again with the English it should
            # give written as the string itself: the same pixels (and the article's codes, left in the text as
            # the cartridge leaves its own, draw nothing); with a wrong English (the article missing, or there
            # where the text cuts it) they differ, so the comparison sees an article
            bank_path = os.path.join(os.path.dirname(gc_path), "en_bank.txt") if gc_path else ""
            bank = af_text.load_bank(bank_path) if os.path.exists(bank_path) else {}
            plain = {"MSGEND", "MSGCONTINUE", "PAUSE", "TEXTCOLOR", "STR_ITEM0", "CUTARTICLE", "CAPTIALIZE"}
            kinds = {}
            for n in sorted(bank):
                names = [msgbank.COMMANDS[t[1]] for t in bank[n] if t[0] == "c"]
                english = all(t[1].isascii() for t in bank[n] if t[0] == "t")
                if english and set(names) <= plain and names.count("STR_ITEM0") == 1:
                    kind = ("cut" if "CUTARTICLE" in names else "capital" if "CAPTIALIZE" in names else "article")
                    kinds.setdefault(kind, n)

            def message(msg_no, string):
                ctl = setup()
                n64.write(ctl + HOOK_BUF, string)
                n64.write(ctl, struct.pack(">i4I", 7, msg_no, len(string), 0, 0))
                n64.frames(2, 0)
                took = struct.unpack(">i", n64.read(ctl + 12, 4))[0]
                n64.frames(MESSAGE_SHOWN, 0)
                return took, n64.screen()

            for kind in ("article", "cut", "capital"):
                if kind not in kinds:
                    check(False, "the items' names: a message of the bank's that puts in item string 0 (%s)" % kind)
                    continue
                msg_no = kinds[kind]
                english = said(tool, article=(kind != "cut"))  # (the capital: the window's own, on both)
                wrong = said(tool, article=(kind == "cut"))
                took, (w, h, seen) = message(msg_no, token(tool))
                _, (_, _, wrote) = message(msg_no, english)
                _, (_, _, other) = message(msg_no, wrong)
                path = os.path.join(out, "items-message-%d.png" % msg_no)
                write_png(path, w, h, seen)
                shots.append(path)
                differ = sum(1 for k in range(0, len(seen), 3) if seen[k:k + 3] != wrote[k:k + 3])
                check(took and seen == wrote and seen != other,
                      "the items' names: message %d (%s) shows the token as it shows %r written in (%d pixels "
                      "differ; with %r, %s)" % (msg_no, kind, english, differ, wrong,
                                               "different" if seen != other else "the same"))
            # on screen: the inventory's name of the money under its cursor
            art, cells, name = record(money)
            w, h, seen = open_swap((1, 0), ())
            path = os.path.join(out, "items-program-1.png")
            write_png(path, w, h, seen)
            shots.append(path)
            _, _, bare = open_swap((1, 0), (), rewrite=([token(money)], b" " * af_items.NAME_LEN, True))
            _, _, ruled = open_swap((1, 0), (), rewrite=([token(money)], (rule * cells).ljust(af_items.NAME_LEN, b" "),
                                                         True))
            name_runs = runs(x for x, _ in ink_pixels(seen, bare, w, h))
            ruler_runs = runs(x for x, _ in ink_pixels(ruled, bare, w, h))
            if not name_runs or not ruler_runs:
                check(False, "the items' names: the money's name drawn (%d runs, the ruler %d)" % (
                    len(name_runs), len(ruler_runs)))
            else:
                left, scale = swap_box(board, rule * cells, (ruler_runs[0][0], ruler_runs[-1][1]))
                span = ruler_runs[-1][1] - ruler_runs[0][0]
                want = swap_inks(board, faint, b" " * cells, name, af_ui.LEFT | af_ui.FIT, left, scale)[0]
                got = (name_runs[0][0], name_runs[-1][1])
                check(ink_matches(got, want, left, span),
                      "the items' names: the inventory draws the money's %r at %s, the model's (%.1f, %.1f) from "
                      "the place of its %d cells at the scale %.3f" % (name, got, want[0], want[1], cells, scale))

        # the string bank (tools/af_text.py, script/string/), loaded as the game loads it: the staff credits at
        # the orange box's 15 bytes a line (ovl_Mikanbox, func_80AA3D08_jp), the word after the town's name at
        # mLd_AddMuraString's 10, and a fish's size as the fishing tourney writes it (its number, string 670)
        ctl = setup()
        n64.frames(2, 0)

        def load_string(n, size):
            n64.write(ctl + HOOK_BUF, b"\xff" * 32)
            n64.write(ctl, struct.pack(">i4I", 8, n, size, 0, 0))
            n64.frames(2, 0)
            return struct.unpack(">i", n64.read(ctl, 4))[0], n64.read(ctl + HOOK_BUF, size)

        credits = af_text.load_bank(os.path.join(HERE, "..", "script", "string", "credits.txt"))
        differ = [n for n in sorted(credits) if load_string(n, 15) != (0, msgbank.encode(credits[n]).ljust(15, b" "))]
        check(credits and not differ, "the string bank: the staff credits' %d lines load at the orange box's 15 bytes "
              "as script/string/credits.txt has them (%d differ: %s)" % (len(credits), len(differ), differ[:5]))
        cmd, got = load_string(484, 10)
        check(cmd == 0 and got == b" " * 10, "the string bank: what goes after the town's name loads as 10 spaces, "
              "nothing for the message code to put in (%r)" % got)
        n64.write(ctl + HOOK_BUF, b"\xff" * 32)
        n64.write(ctl, struct.pack(">i4I", 9, 40, 0x29E, 0, 0))
        n64.frames(2, 0)
        cmd, _, _, length = struct.unpack(">4i", n64.read(ctl, 16))
        got = n64.read(ctl + HOOK_BUF, 10)
        check(cmd == 0 and length == 5 and got[:5] == b"40 cm", "the string bank: a fish of 40 written as the "
              "fishing tourney writes its size: %r (%d bytes)" % (got[:max(0, min(length, 10))], length))

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
    print("  --    (the title's warning, in a process of its own: the emulator's plugins start once per process)")
    r = subprocess.run([sys.executable, os.path.abspath(__file__), "--title", emu_dir, rom_path, af_en, out] + argv[5:])
    if r.returncode:
        failures.append("the title's warning")
    if os.path.exists(os.path.join(out, "title-warning.png")):
        shots.append(os.path.join(out, "title-warning.png"))
    contact.main([os.path.join(out, "sheet.png"), "2"] + shots)
    contact.main([os.path.join(out, "labels.png"), "2"] + label_shots)
    print("emu_menus: %d failures; screenshots in %s" % (len(failures), out))
    return 1 if failures else 0


def title_main(argv):
    """--title EMU_DIR EN_ROM AF_EN_DIR OUT_DIR [--gc GC_UI] [--own OWN_UI]: the title's warning alone (main
    runs it so, in a process of its own)."""
    emu_dir, rom_path, af_en, out = argv[:4]
    opts = dict(zip(argv[4::2], argv[5::2]))
    gc_path = opts.get("--gc") if os.path.exists(opts.get("--gc", "")) else None
    own_path = opts.get("--own") if os.path.exists(opts.get("--own", "")) else None
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    seg = open(os.path.join(af_en, "assets", "jp", "segment_00BCD000.bin"), "rb").read()
    board = emu_board.Board(seg, emu_board.read_cut(af_en))
    cart = open(os.path.join(af_en, af_ui.ROM), "rb").read()
    subs = af_ui.substitutions(cart, af_ui.read_text(gc_path) if gc_path else {},
                               af_ui.read_text(own_path) if own_path else {})
    title_warning(emu_dir, open(rom_path, "rb").read(), out, syms, subs, cart, board, faint_columns(seg),
                  ruler(seg, board), [])
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1] == "--title":
        sys.exit(title_main(sys.argv[2:]))
    sys.exit(main(sys.argv[1:]))
