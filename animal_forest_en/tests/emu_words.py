#!/usr/bin/env python3
"""The words the cartridge loads into fewer bytes than their English takes, through the game's own code.

    tests/emu_words.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR EN_STRING_DRAFT

STATE is a state of EN_ROM in play (make route-en's 4-houses.st); EN_STRING_DRAFT the en string bank's text
before the tokens are laid over it (build/gc/en_string.txt: what each string says).

The cartridge's code loads a string of the bank into 10 bytes (or 4) and hands those on: the villagers' talk
fills a message's item strings with a word of a list (ovl__00815B70: fish, insects, sports, food, drinks,
colours, places, games, things to read, the year's animals, the star signs, and the shop's kind, 4 bytes
after a code that colours them), the fortune teller her reading's four (ovl_Ev_Gypsy), a villager's reply a
letter's free strings (m_npc.c). The cartridge's words fit; 234 of the English ones do not, and came out cut
at 10 letters (or 4). They take tokens (tools/af_items.py, SHORT_LOADED), which the message code expands
where it puts a string in (m_msg_main.c, mMsg_en_CopyString: after a colour code too, the code then
colouring the whole word): reference/NOTES.md, "Strings cut by their loaders". The letters' side is
tests/emu_letters.py's.

A hook (tests/emu_save.py's way) loads a string as its code does and sets it as an item string, asks the
message code to put the five item strings in (mMsg_CopyItem), loads an overlay as the game loads one
(ovlmgr_Load into room from the arena) and calls a function of it, and shows a message. Checks:

  1. every string of SHORT_LOADED, loaded into its loader's bytes and put in as a message puts an item
     string in, is the draft's whole word (the shop's kinds after their colour code, its count the word's
     length);
  2. the villagers' talk, its overlay loaded: its four fillers called (func_8092106C_jp, func_809211D8_jp
     for each size of the shop, func_809212A8_jp, func_80921324_jp for a birthday in each star sign): every
     word put in is one of its list's, whole; the shop's kind is its size's; the sign is the birthday's;
  3. the fortune teller, her overlay loaded: her filler (func_809DC590_jp) the same;
  4. on screen: her reading (message 0x973) with the widest word of each list, its page's lines where the
     font's model has them; and a villager's line about the shop (0x273A) filled by the talk's own filler
     for the largest shop: its lines where the model has them, the colour's ink over the whole word.
"""

import os
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, A, write_png  # noqa: E402
import af_items  # noqa: E402
import af_text  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_messages  # noqa: E402
import emu_save  # noqa: E402
import msgbank  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
typedef unsigned int u32;
void* zelda_malloc(u32 size);
void ovlmgr_Load(u32 vromStart, u32 vromEnd, void* vramStart, void* vramEnd, void* allocatedVRamAddr);
void mString_Load_StringFromRom(char* dst, s32 dstLen, s32 strIdx);
void* mMsg_Get_base_window_p(void);
s32 mMsg_request_main_appear(void* msg, void* actor, s32 showName, u8* color, s32 msgNo, s32 priority);
void mMsg_Set_item_str(void* msg, s32 itemStrNo, char* str, s32 strLen);
s32 mMsg_CopyItem(void* msg, s32 strNo, char* data, s32 startIdx, s32 len);
typedef struct HookCtl { s32 cmd; s32 arg[5]; s32 result; char buf[16]; char text[5][64]; s32 len[5]; } HookCtl;
HookCtl hook_ctl = { 0 };
u8 hook_color[4] = { 255, 255, 255, 255 };

void hook_main(void) {
    HookCtl* h = &hook_ctl;
    void* msg;
    s32 i;

    if (h->cmd == 1) {
        /* room from the arena play gives its actors */
        h->result = (s32)zelda_malloc(h->arg[0]);
        h->cmd = 0;
    } else if (h->cmd == 2) {
        /* an overlay loaded as the game loads an actor's (m_actor.c) */
        ovlmgr_Load(h->arg[0], h->arg[1], (void*)h->arg[2], (void*)h->arg[3], (void*)h->arg[4]);
        h->cmd = 0;
    } else if (h->cmd == 3) {
        /* a function called where its overlay was loaded */
        h->result = ((s32 (*)(s32, s32, s32, s32))h->arg[0])(h->arg[1], h->arg[2], h->arg[3], h->arg[4]);
        h->cmd = 0;
    } else if (h->cmd == 4) {
        /* a string as its code loads it (arg[1] bytes, at buf + arg[2]), handed on as an item string (arg[3]):
           10 bytes, as func_80920FFC_jp and func_809DC590_jp hand theirs on */
        msg = mMsg_Get_base_window_p();
        mString_Load_StringFromRom(h->buf + h->arg[2], h->arg[1], h->arg[0]);
        mMsg_Set_item_str(msg, h->arg[3], h->buf, 10);
        h->cmd = 0;
    } else if (h->cmd == 5) {
        /* the five item strings as a message puts each in for its code (49 on: STR_ITEM0) */
        msg = mMsg_Get_base_window_p();
        for (i = 0; i < 5; i++) {
            h->text[i][0] = 0x7F;
            h->text[i][1] = 49 + i;
            h->len[i] = mMsg_CopyItem(msg, i, h->text[i], 0, 2);
        }
        h->cmd = 0;
    } else if (h->cmd == 6) {
        /* a message asked for as an actor asks for one, on the base window, without a speaker */
        msg = mMsg_Get_base_window_p();
        h->arg[1] = (s32)msg;
        h->result = mMsg_request_main_appear(msg, 0, 0, hook_color, h->arg[0], 5);
        h->cmd = 0;
    }
}
"""
CTL = struct.Struct(">i5Ii16s")
BUF_AT, TEXT_AT, TEXT_LEN, LEN_AT = 28, CTL.size, 64, CTL.size + 5 * 64
ITEM0 = msgbank.NAMES["STR_ITEM0"]
TALK, GYPSY = "ovl__00815B70", "ovl_Ev_Gypsy"
# the talk's fillers and what each fills the item strings 0 on with (the lists' first strings, their counts)
FIVE = ("func_8092106C_jp", [(0x219, 32), (0x1E5, 32), (0x334, 32), (0x314, 32), (0x414, 32)])
THREE = ("func_809212A8_jp", [(0x464, 32), (0x2F4, 32), (0x4A0, 32)])
SHOP, SHOP_KINDS, SHOP_COLOURS = "func_809211D8_jp", 0x454, (0x434, 32)
SHOP_SIZE_AT = 0xED72                               # common_data: the shop's size in its top two bits (func_800C165C_jp)
COLOR_CODE = bytes([0x7F, 0x50, 0x46, 0xA0, 0x00])  # what the talk puts before the shop's kind, then the count
SIGNS, SIGN_LISTS, SIGN_FIRST = "func_80921324_jp", [(0x458, 12), (0x494, 12)], 0x494
PRIVATE_INFO, BIRTHDAY_AT = 0x10138, 0xA92          # common_data.privateInfo; its birthday's month and day
# a day in each star sign and the sign's place among the twelve from Aries (the dates are the sky's, not a text's)
BIRTHDAYS = [((1, 5), 9), ((2, 1), 10), ((3, 1), 11), ((4, 1), 0), ((5, 1), 1), ((6, 1), 2), ((7, 1), 3),
             ((8, 1), 4), ((9, 1), 5), ((10, 1), 6), ((11, 1), 7), ((12, 1), 8), ((12, 25), 9)]
READING, READING_FILLER, READING_LISTS = 0x973, "func_809DC590_jp", [(0x1A4, 32), (0x1C4, 32), (0x184, 32), (0x164, 32)]
SHOP_LINE = 0x273A                                  # a villager's line with the shop's kind on its first page
FILLS = 60                                          # how often each filler of random words is called
GREEN = lambda r, g, b: g > r + 50 and g > b + 50   # noqa: E731 (the shop's kind: 70, 160, 0)
PRESSES = 12

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def show(codes):
    return "".join(msgbank.N64_CHARS[b] or "?" for b in codes)


def main(argv):
    emu_dir, rom_path, af_en, state, out, draft_path = argv
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
    bank = emu_messages.bank_of(af_en)
    draft = af_text.load_bank(draft_path)
    sizes = {}
    for first, count, field in af_items.SHORT_LOADED:
        for n in range(first, first + count):
            sizes[n] = min(field, sizes.get(n, field))
    shots = []

    def word(n):
        return msgbank.encode(draft[n]).rstrip(b" ")

    def words(lists):
        return [{word(n) for n in range(first, first + count)} for first, count in lists]

    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        n64.load_state(state)
        words2 = struct.unpack(">II", n64.read(mttime, 8))
        blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words2, mttime, HOOK_C)

        def call(cmd, *args):
            n64.write(ctl, CTL.pack(cmd, *(list(args) + [0] * (5 - len(args)) + [0, n64.read(ctl + BUF_AT, 16)])))
            for _ in range(120):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    return struct.unpack(">i", n64.read(ctl + 24, 4))[0]
            raise SystemExit("the hook did not take command %d" % cmd)

        def setup():
            n64.load_state(state)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))

        def items():
            """The five item strings as a message puts them in."""
            call(5)
            lens = struct.unpack(">5i", n64.read(ctl + LEN_AT, 20))
            return [n64.read(ctl + TEXT_AT + TEXT_LEN * i, max(0, min(lens[i], TEXT_LEN))) for i in range(5)]

        def load(name):
            """The overlay loaded as the game loads one; where a function of it is."""
            at = call(1, syms[name + "_VRAM_END"] - syms[name + "_VRAM"]) & 0xFFFFFFFF
            if not at:
                raise SystemExit("no room in the arena for %s" % name)
            call(2, syms[name + "_ROM_START"], syms[name + "_ROM_END"], syms[name + "_VRAM"],
                 syms[name + "_VRAM_END"], at)
            return lambda f: at + syms[f] - syms[name + "_VRAM"]

        def set_item(k, n):
            """String n into item string k as its code loads it."""
            if sizes[n] == 10:
                call(4, n, 10, 0, k)
            else:
                n64.write(ctl + BUF_AT, COLOR_CODE + bytes([sizes[n]]) + b" " * sizes[n])
                call(4, n, sizes[n], len(COLOR_CODE) + 1, k)

        def as_put_in(n):
            return word(n) if sizes[n] == 10 else COLOR_CODE + bytes([len(word(n))]) + word(n)

        setup()
        # 1. every string
        bad = []
        for n in sorted(sizes):
            set_item(0, n)
            got = items()[0]
            if got != as_put_in(n):
                bad.append("%d: %r, the draft's %r" % (n, show(got), show(word(n))))
        longer = [n for n in sizes if len(word(n)) > sizes[n]]
        check(not bad and longer, "the %d strings the cartridge's code loads into 10 bytes or 4, each loaded so and "
              "put in as a message puts an item string in: the whole word, %d of them longer than their loader's "
              "bytes, up to %d characters (%d differ%s)" % (
                  len(sizes), len(longer), max(len(word(n)) for n in sizes), len(bad),
                  ": " + "; ".join(bad[:4]) if bad else ""))

        # 2. the villagers' talk
        talk = load(TALK)

        def fills(function, lists, at, times, args=()):
            """A filler called times: (what was outside its lists, the longest word seen of each list)."""
            want, outside, longest = words(lists), [], [0] * len(lists)
            for _ in range(times):
                for a in args or [()]:
                    call(3, at(function), *a)
                got = items()
                for k in range(len(lists)):
                    longest[k] = max(longest[k], len(got[k]))
                    if got[k] not in want[k]:
                        outside.append("item %d %r" % (k, show(got[k])))
            return outside, longest

        for function, lists in (FIVE, THREE):
            outside, longest = fills(function, lists, talk, FILLS)
            most = [max(len(w) for w in ws) for ws in words(lists)]
            check(not outside and all(m > 10 for m in longest),
                  "the villagers' talk, %s called %d times: every word put into item strings 0 to %d is one of its "
                  "list's, whole (the longest seen %s characters, the lists' longest %s)%s" % (
                      function, FILLS, len(lists) - 1, longest, most, "; " + "; ".join(outside[:4]) if outside else ""))
        old = n64.read(cd + SHOP_SIZE_AT, 2)
        bad, kinds = [], []
        for size in range(4):
            n64.write(cd + SHOP_SIZE_AT, struct.pack(">H", (struct.unpack(">H", old)[0] & 0x3FFF) | (size << 14)))
            call(3, talk(SHOP))
            got = items()
            kinds.append(show(got[3][len(COLOR_CODE) + 1:]))
            if (got[3] != as_put_in(SHOP_KINDS + size) or not all(g.isdigit() for g in got[:3])
                    or got[4] not in words([SHOP_COLOURS])[0]):
                bad.append("size %d: %r" % (size, [show(g) for g in got]))
        n64.write(cd + SHOP_SIZE_AT, old)
        check(not bad, "the shop's kind (%s for each size of the shop): item string 3 its colour code, counting the "
              "word's characters (%s), then the whole word (%s); three numbers before it, a colour's name after%s" % (
                  SHOP, ", ".join(str(len(word(SHOP_KINDS + k))) for k in range(4)), ", ".join(kinds),
                  "; " + "; ".join(bad) if bad else ""))
        priv = n64.u32(cd + PRIVATE_INFO)
        old = n64.read(priv + BIRTHDAY_AT, 2)
        bad, seen = [], []
        for (month, day), sign in BIRTHDAYS:
            n64.write(priv + BIRTHDAY_AT, bytes([month, day]))
            call(3, talk(SIGNS))
            got = items()
            seen.append(show(got[2]))
            if (got[2] != word(SIGN_FIRST + sign) or (got[3], got[4]) != (b"%d" % month, b"%d" % day)
                    or any(got[k] not in ws for k, ws in enumerate(words(SIGN_LISTS)))):
                bad.append("%d-%d: %r" % (month, day, [show(g) for g in got]))
        n64.write(priv + BIRTHDAY_AT, old)
        check(not bad and max(map(len, seen)) > 10, "the star sign (%s for %d birthdays, one in each sign): item "
              "string 2 the birthday's sign, whole (%s), the month and the day after it, a year's animal and a sign "
              "before%s" % (SIGNS, len(BIRTHDAYS), ", ".join(seen), "; " + "; ".join(bad[:4]) if bad else ""))

        # 3. the fortune teller
        gypsy = load(GYPSY)
        outside, longest = fills(READING_FILLER, READING_LISTS, gypsy, FILLS, [(k,) for k in range(4)])
        check(not outside and all(m > 10 for m in longest),
              "the fortune teller, %s called %d times for each of her four: every word is one of its list's, whole "
              "(the longest seen %s characters)%s" % (READING_FILLER, FILLS, longest,
                                                      "; " + "; ".join(outside[:4]) if outside else ""))

        # 4. on screen
        def shown(message, page, name):
            """The message asked for; A pressed until its lines are the page's: (what is wrong, the frame)."""
            took = call(6, message)
            window = struct.unpack(">I", n64.read(ctl + 8, 4))[0]
            problems = ["not taken by the window"] if not took else ["not shown"]
            for _ in range(PRESSES if took else 0):
                waited = 0
                for _ in range(emu_messages.SHOWN):
                    n64.frames(1, 0)
                    index = struct.unpack(">i", n64.read(window + emu_messages.MAIN_INDEX, 4))[0]
                    waited = waited + 1 if index == emu_messages.WAITING else 0
                    if waited == emu_messages.SETTLED:
                        break
                w, h, rgb = n64.screen()
                problems = emu_messages.page_problems(rgb, w, page, board)
                if not problems:
                    break
                n64.frames(3, A)
                n64.frames(2, 0)
            w, h, rgb = n64.screen()
            path = os.path.join(out, name + ".png")
            write_png(path, w, h, rgb)
            shots.append(path)
            return problems, (w, rgb)

        setup()
        widest = [max(range(first, first + count), key=lambda n: (board.width(word(n)), -n))
                  for first, count in READING_LISTS]
        for k, n in enumerate(widest):
            set_item(k, n)
        tokens = bank[READING]
        clears = emu_messages.clears_before(tokens, ITEM0 + 3)
        page = emu_messages.page_with(tokens, clears or 0, {str(ITEM0 + k): word(n) for k, n in enumerate(widest)})
        problems, _ = shown(READING, page, "reading") if clears else (["no page with the reading"], None)
        check(not problems and all(any(word(n) in line for line in page) for n in widest),
              "the fortune teller's reading (message %#x, page %s) with the widest word of each of her lists (%s): "
              "its %d lines where the model has them, the widest %d pixels of the window's 192%s" % (
                  READING, clears and clears + 1, ", ".join(repr(show(word(n))) for n in widest),
                  len([x for x in page if x]), max(board.width(line) for line in page),
                  "; " + "; ".join(problems) if problems else ""))

        setup()
        talk = load(TALK)
        old = n64.read(cd + SHOP_SIZE_AT, 2)
        n64.write(cd + SHOP_SIZE_AT, struct.pack(">H", struct.unpack(">H", old)[0] | 0xC000))
        call(3, talk(SHOP))
        kind = word(SHOP_KINDS + 3)
        tokens = bank[SHOP_LINE]
        page = emu_messages.page_with(tokens, 0, {str(ITEM0 + 3): kind})
        problems, frame = shown(SHOP_LINE, page, "shop")
        line = next((k for k, text in enumerate(page) if kind in text), None)
        check(not problems and line is not None,
              "a villager's line about the shop (message %#x) after the talk's own filler, the shop at its largest: "
              "its %d lines where the model has them (%r)%s" % (
                  SHOP_LINE, len([x for x in page if x]), show(page[line]) if line is not None else None,
                  "; " + "; ".join(problems) if problems else ""))
        if line is not None and frame is not None:
            w, rgb = frame
            rows = range(emu_messages.TOP + line * emu_messages.PITCH, emu_messages.TOP + (line + 1) * emu_messages.PITCH)
            xs = [x for y in rows for x in emu_messages.TEXT_X if GREEN(*rgb[(y * w + x) * 3:(y * w + x) * 3 + 3])]
            at = page[line].index(kind)
            ext = board.extent(kind, board.width(page[line][:at]))
            want = (emu_messages.frame_x(ext[0]), emu_messages.frame_x(ext[1] + 1))
            got = (min(xs), max(xs) + 1) if xs else None
            check(got is not None and all(abs(g - x) <= emu_messages.TOLERANCE + 1 for g, x in zip(got, want)),
                  "the colour the talk gives the shop's kind is over the whole word: its ink from x %s to %s, the "
                  "model's word %.1f to %.1f" % ((got and got[0], got and got[1]) + want))

    contact.main([os.path.join(out, "sheet.png"), "2"] + shots)
    print("emu_words: %s; screenshots in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) != 7:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
