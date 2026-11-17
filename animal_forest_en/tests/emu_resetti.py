#!/usr/bin/env python3
"""Mr. Resetti's phrases in the en build: typed on the dial as a player types them, judged by his own code.

    tests/emu_resetti.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). Mr. Resetti (ovl_Npc_Majin3) picks one of 16
phrases (func_809B4FC4_jp: string 0x484 and a number under 16, loaded into 10 bytes and set as item string 0
of his page), opens the name entry on 10 bytes of his own (func_809B4DB8_jp: mSM_open_submenu_new, program 4
in its mode 3, his actor's 0x94C) and, the window closed, judges them (func_809B4D08_jp): 0 when they are the
phrase (func_809B4BB8_jp: the string loaded into 10 bytes again, mem_cmp of 10), else 1 when one of 32 words
is anywhere in them (func_809B4C18_jp: strings 0x4C0 on, of each as many bytes as its place in the list says,
tools/af_dial.py's insult_len), else 2; his next page is chosen by that (D_809B5744_jp). A phrase the dial
cannot type, or one the window keeps another way than the string's loader gives it, can never be said: the
player would be held at his page for good (reference/NOTES.md, "The second review").

A hook (tests/emu_save.py's way) loads his overlay as the game loads an actor's (m_actor.c: ovlmgr_Load into
room from the arena), keeps a stand-in for his actor (the bytes his check reads: the 10 at 0x94C, the
phrase's number at 0x956), opens the window as he opens it and calls his check where the overlay was loaded.
The dial is typed as a player types it (tests/emu_write.py's way: Z to the mode, the stick down for the
ring's page, A on the place; START closes the window). Checks:

  1. the window, opened as he opens it, fills his 10 bytes with spaces, is one line of 10 and opens on the
     small letters;
  2. each of the 16 phrases, typed: the window leaves in his 10 bytes what the string's loader gives for the
     phrase (hook: mString_Load_StringFromRom into 10), and his check says 0 with that phrase's number and 2
     with the next one's;
  3. each of the 32 words, as the loader gives it, put at the start of his 10 bytes, at their end and after
     one letter: his check says 1; three are typed on the dial (the first, the last, with its space, and one
     between two letters); nothing typed (the window's spaces), letters with none of the words and a phrase
     in the wrong case say 2;
  4. his check against the model of it (tools/af_dial.py's lengths; the reading of the asm the build's check
     of the bank stands on): random texts of the phrases' and the words' characters, half of them with a
     word or the start of one planted, each with a random phrase's number, say what the model says;
  5. his page (the message the wrong try's page goes back to: it prints item string 0 between quotes) with
     each phrase set as his code sets it: the phrase whole on the page, its line where the model has it.

The phrases and the words are read from the bank the en build's ROM holds; none is written here.
"""

import os
import random
import shutil
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, A, START, Z, write_png  # noqa: E402
import af_dial  # noqa: E402
import contact  # noqa: E402
import emu_board  # noqa: E402
import emu_messages  # noqa: E402
import emu_save  # noqa: E402
import emu_write  # noqa: E402
import msgbank  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef int s32;
typedef unsigned int u32;
void mSM_open_submenu_new(void* submenu, s32 programId, s32 arg2, s32 arg3, s32 arg4);
void SetGameFrame(s32 divisor);
void* zelda_malloc(u32 size);
void ovlmgr_Load(u32 vromStart, u32 vromEnd, void* vramStart, void* vramEnd, void* allocatedRamAddr);
void mString_Load_StringFromRom(char* dst, s32 dstLen, s32 strIdx);
void* mMsg_Get_base_window_p(void);
s32 mMsg_request_main_appear(void* msg, void* actor, s32 showName, u8* color, s32 msgNo, s32 priority);
void mMsg_Set_item_str(void* msg, s32 itemStrNo, char* str, s32 strLen);
extern void* gamePT;
typedef struct HookCtl { s32 cmd; s32 arg[5]; s32 result; char buf[12]; } HookCtl;
HookCtl hook_ctl = { 0 };
u8 hook_color[4] = { 255, 255, 255, 255 };

void hook_main(void) {
    HookCtl* h = &hook_ctl;
    s32* submenu = (s32*)((u8*)gamePT + 0x1CBC);

    if (h->cmd == 1 && submenu[3] == 0) {
        /* a submenu program opened as mSM_submenu_ctrl opens one */
        mSM_open_submenu_new(submenu, h->arg[0], h->arg[1], h->arg[2], h->arg[3]);
        submenu[3] = 1;
        submenu[0] = 1;
        SetGameFrame(2);
        h->cmd = 0;
    } else if (h->cmd == 2) {
        /* room from the arena play gives its actors */
        h->result = (s32)zelda_malloc(h->arg[0]);
        h->cmd = 0;
    } else if (h->cmd == 3) {
        /* an overlay loaded as the game loads an actor's (m_actor.c) */
        ovlmgr_Load(h->arg[0], h->arg[1], (void*)h->arg[2], (void*)h->arg[3], (void*)h->arg[4]);
        h->cmd = 0;
    } else if (h->cmd == 4) {
        /* a function of one argument called where it was loaded */
        h->result = ((s32 (*)(void*))h->arg[0])((void*)h->arg[1]);
        h->cmd = 0;
    } else if (h->cmd == 5) {
        /* a string as his code loads one: into 10 bytes */
        mString_Load_StringFromRom(h->buf, 10, h->arg[0]);
        h->cmd = 0;
    } else if (h->cmd == 6) {
        /* a message on the base window, its item string 0 the string arg[1] as func_809B4FC4_jp sets the
           phrase: loaded into 10 bytes, set with a length of 10 */
        void* msg = mMsg_Get_base_window_p();

        mString_Load_StringFromRom(h->buf, 10, h->arg[1]);
        mMsg_Set_item_str(msg, 0, h->buf, 10);
        h->arg[2] = (s32)msg;
        h->result = mMsg_request_main_appear(msg, 0, 0, hook_color, h->arg[0], 5);
        h->cmd = 0;
    }
}
"""
CTL = struct.Struct(">i5Ii12s")
OVERLAY = "ovl_Npc_Majin3"
JUDGE = "func_809B4D08_jp"                           # his check of what was typed: 0 the phrase, 1 a word, 2 neither
PHRASE_CHECK, JUDGE_HEAD = "func_809B4BB8_jp", 0x14  # the phrase's compare, which his check calls first: its jal
ACTOR_SIZE, TYPED_AT, PHRASE_AT, TYPED_LEN = 0x960, 0x94C, 0x956, 10    # the bytes of his actor his check reads
LEDIT, LEDIT_RESET = 4, 3                            # the name entry, his mode of it (func_809B4DB8_jp)
SUBMENU, SUBMENU_OVL, EDITOR_OVL = emu_write.SUBMENU, emu_write.SUBMENU_OVL, emu_write.EDITOR_OVL
LOWER_FLAG, SYMBOLS, LETTERS = emu_write.LOWER_FLAG, emu_write.SYMBOLS, emu_write.LETTERS
PLACES, DOWN = emu_write.PLACES, emu_write.DOWN
PHRASES, WORDS = af_dial.PENANCE_COUNT, af_dial.INSULT_COUNT
SAY, WRONG = 0x23EB, 0x23EE                          # his page with the phrase; the wrong try's (D_809B5744_jp[2])
ITEM0, SETFORCEMSG = msgbank.NAMES["STR_ITEM0"], msgbank.NAMES["SETFORCEMSG"]
OPENED, CLOSED = 60, 200                             # frames for the window to open, at most to close
RANDOM_TEXTS, SEED = 240, 20261117
SPACE = msgbank.N64_BYTES[" "]

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def model(typed, phrase, words):
    """What his check says of the window's 10 bytes (func_809B4D08_jp, as its asm reads): 0 when they are the
    phrase's 10, else 1 when a word's compared bytes are at any place in them, else 2."""
    if typed == phrase:
        return 0
    for i, word in enumerate(words):
        n = af_dial.insult_len(i)
        if any(typed[k:k + n] == word[:n] for k in range(TYPED_LEN + 1 - n)):
            return 1
    return 2


def show(codes):
    """The font's codes as text, for a report."""
    return "".join(msgbank.N64_CHARS[b] for b in codes)


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
    mttime = syms["mTM_time"]
    seg = open(os.path.join(af_en, "assets", "jp", "segment_00BCD000.bin"), "rb").read()
    board = emu_board.Board(seg, emu_board.read_cut(af_en))
    bank = emu_messages.bank_of(af_en)
    dial = emu_write.dial_table(af_en)
    marks = af_dial.symbol_codes()
    vram, vram_end = syms[OVERLAY + "_VRAM"], syms[OVERLAY + "_VRAM_END"]
    shots = []

    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        n64.load_state(state)
        words2 = struct.unpack(">II", n64.read(mttime, 8))
        blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words2, mttime, HOOK_C)

        def call(cmd, *args):
            """A command run by the hook: (its result, its 10 bytes)."""
            n64.write(ctl, CTL.pack(cmd, *(list(args) + [0] * (5 - len(args)) + [0, b""])))
            for _ in range(120):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    got = CTL.unpack(n64.read(ctl, CTL.size))
                    return got[6], got[7][:TYPED_LEN]
            raise SystemExit("the hook did not take command %d" % cmd)

        def setup():
            """From the state: the hook, a stand-in for his actor and his overlay loaded: (actor, his check)."""
            n64.load_state(state)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            actor = call(2, ACTOR_SIZE)[0] & 0xFFFFFFFF
            ovl = call(2, vram_end - vram)[0] & 0xFFFFFFFF
            if not actor or not ovl:
                raise SystemExit("no room in the arena for his actor and his overlay")
            n64.write(actor, bytes(ACTOR_SIZE))
            call(3, syms[OVERLAY + "_ROM_START"], syms[OVERLAY + "_ROM_END"], vram, vram_end, ovl)
            return actor, ovl + syms[JUDGE] - vram

        def play():
            return n64.u32(syms["gamePT"])

        def editor():
            ed = n64.u32(n64.u32(play() + SUBMENU + SUBMENU_OVL) + EDITOR_OVL)
            e = n64.read(ed, 0x30)
            _, per_line, lines, length = struct.unpack(">hhhh", e[0x16:0x1E])
            return {"perLine": per_line, "lines": lines, "length": length, "mode": e[4], "ring": e[5],
                    "turning": e[3], "lower": e[LOWER_FLAG]}

        def press(button):
            n64.frames(3, button)
            n64.frames(6, 0)

        def to_mode(mode, lower=None):
            for _ in range(6):
                e = editor()
                if e["mode"] == mode and lower in (None, e["lower"]):
                    return
                press(Z)
            raise SystemExit("the dial has no mode %d (small letters: %s): %r" % (mode, lower, editor()))

        def type_code(code):
            """One of the font's codes typed on the dial: its mode, its ring's page, its place."""
            ch = bytes([code])
            if b"a" <= ch <= b"z" or b"A" <= ch <= b"Z":
                to_mode(LETTERS, int(ch.islower()))
                k = dial.index(ch.upper())
            elif ch in marks:
                to_mode(SYMBOLS)
                k = marks.index(ch)
            else:
                raise SystemExit("the dial has no place for %r" % ch)
            for _ in range(12):
                e = editor()
                if e["turning"] == 0 and e["ring"] == k // 5 * 5:
                    break
                n64.frames(12, DOWN)
                n64.frames(12, 0)
            v = PLACES[k - editor()["ring"]]
            n64.frames(5, v)
            n64.frames(3, v | A)
            n64.frames(3, v)
            n64.frames(4, 0)

        def open_window(actor):
            n64.write(actor + TYPED_AT, b"\xAA" * TYPED_LEN)
            call(1, LEDIT, LEDIT_RESET, 0, actor + TYPED_AT)
            n64.frames(OPENED, 0)

        def say(codes, name=None):
            """codes typed in his window, opened as he opens it, and START: (actor, his check, the window's 10
            bytes, whether it closed)."""
            actor, judge = setup()
            open_window(actor)
            for c in codes:
                type_code(c)
            n64.frames(10, 0)
            if name:
                path = os.path.join(out, name + ".png")
                n64.screenshot(path)
                shots.append(path)
            press(START)
            closed = False
            for _ in range(CLOSED):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(play() + SUBMENU + 12, 4))[0] == 0:
                    closed = True
                    break
            return actor, judge, n64.read(actor + TYPED_AT, TYPED_LEN), closed

        def judged(actor, judge, typed, number):
            n64.write(actor + TYPED_AT, typed)
            n64.write(actor + PHRASE_AT, bytes([number]))
            return call(4, judge, actor)[0]

        # the strings as his code loads them
        actor, judge = setup()
        image = open(os.path.join(af_en, "build", "animalforest-jp.z64"), "rb").read()
        at = syms[OVERLAY + "_ROM_START"] + syms[JUDGE] - vram
        loaded, linked = n64.read(judge, JUDGE_HEAD + 4), image[at:at + JUDGE_HEAD + 4]
        jal = [0x0C000000 | ((a >> 2) & 0x3FFFFFF) for a in (judge - syms[JUDGE] + syms[PHRASE_CHECK],
                                                             syms[PHRASE_CHECK])]
        check(loaded[:JUDGE_HEAD] == linked[:JUDGE_HEAD] and
              [struct.unpack(">I", x[JUDGE_HEAD:])[0] for x in (loaded, linked)] == jal,
              "his overlay loaded as the game loads an actor's: his check's first %#x bytes are the image's, and "
              "its call of the phrase's compare is relocated to where the overlay is (%08X, linked %08X)" % (
                  JUDGE_HEAD, jal[0], jal[1]))
        phrases = [call(5, af_dial.PENANCE_FIRST + i)[1] for i in range(PHRASES)]
        words = [call(5, af_dial.INSULT_FIRST + i)[1] for i in range(WORDS)]
        lens = [af_dial.insult_len(i) for i in range(WORDS)]
        print("    the phrases: %s" % ", ".join(repr(show(p).rstrip()) for p in phrases))
        print("    the words: %s" % ", ".join(repr(show(w).rstrip()) for w in words))
        check(all(len(p.rstrip(b" ")) >= 1 for p in phrases) and len(set(phrases)) == PHRASES,
              "the %d phrases, each loaded into 10 bytes: none empty, no two alike" % PHRASES)
        check(all(len(w.rstrip(b" ")) == n for w, n in zip(words, lens)),
              "the %d words, each as long as his check compares of it (%s)" % (
                  WORDS, " ".join(str(len(w.rstrip(b" "))) for w in words)))

        # 1. the window
        open_window(actor)
        e, filled = editor(), n64.read(actor + TYPED_AT, TYPED_LEN)
        check(filled == bytes([SPACE]) * TYPED_LEN and (e["perLine"], e["lines"], e["length"]) == (TYPED_LEN, 1, 0),
              "the window, opened as he opens it: his 10 bytes filled with spaces (%r), one line of %d, empty" % (
                  filled, e["perLine"]))
        check((e["mode"], e["lower"]) == (LETTERS, 1), "its dial opens on the small letters (mode %d, flag %d)" % (
            e["mode"], e["lower"]))
        path = os.path.join(out, "0-window.png")
        n64.screenshot(path)
        shots.append(path)

        # 2. the phrases, typed
        for i, phrase in enumerate(phrases):
            text = phrase.rstrip(b" ")
            actor, judge, typed, closed = say(text, "1-phrase-%02d" % i)
            right = judged(actor, judge, typed, i)
            other = judged(actor, judge, typed, (i + 1) % PHRASES)
            check(closed and typed == phrase and (right, other) == (0, 2),
                  "phrase %d (%d characters), typed on the dial: the window closes on START (%s) and leaves the "
                  "loader's 10 bytes (%r); his check says %d with its number, %d with the next one's" % (
                      i, len(text), closed, show(typed), right, other))

        # 3. the words
        actor, judge = setup()
        bad = []
        blank = bytes([SPACE]) * TYPED_LEN
        letter = bytes([msgbank.N64_BYTES["q"]])
        for i, word in enumerate(words):
            w = word[:lens[i]]
            for typed in (w + blank[lens[i]:], blank[lens[i]:] + w, letter + w + blank[lens[i] + 1:]):
                got = judged(actor, judge, typed, 0)
                if got != 1:
                    bad.append("%r: %d" % (show(typed), got))
        check(not bad, "each of the %d words at the start of his 10 bytes, at their end and after a letter: his "
              "check says 1%s" % (WORDS, "; " + "; ".join(bad) if bad else ""))
        nothing = judged(actor, judge, blank, 0)
        plain = letter * TYPED_LEN
        check(model(plain, phrases[0], words) == 2 and (nothing, judged(actor, judge, plain, 0)) == (2, 2),
              "nothing typed (the window's spaces), and letters with none of the words: 2 (%d, %d)" % (
                  nothing, judged(actor, judge, plain, 0)))
        tries = [(i, t) for i, p in enumerate(phrases) for t in (
            bytes(b ^ 0x20 if chr(b).isalpha() and b < 0x80 else b for b in p),       # the font's letters are ASCII
            bytes(b | 0x20 if b"A"[0] <= b <= b"Z"[0] else b for b in p)) if t != p]
        got = [judged(actor, judge, t, i) for i, t in tries]
        check(tries and set(got) == {2}, "a phrase typed in the wrong case (%d tries: every letter's case "
              "swapped, all small) is a wrong try, not an insult: %s" % (len(tries), sorted(set(got))))
        for name, codes in (("the first", words[0][:lens[0]]), ("the last", words[-1][:lens[-1]]),
                            ("one between two letters", letter + words[WORDS // 2][:lens[WORDS // 2]] + letter)):
            actor, judge, typed, closed = say(codes, "2-word-%d" % len(codes))
            got = judged(actor, judge, typed, 0)
            check(closed and typed == codes.ljust(TYPED_LEN, b" ") and got == 1,
                  "%s of the words typed on the dial (%r): the window leaves it (%r), his check says %d" % (
                      name, show(codes), show(typed), got))

        # 4. his check against the model
        actor, judge = setup()
        rnd = random.Random(SEED)
        alphabet = sorted(set(b"".join(phrases) + b"".join(w[:n] for w, n in zip(words, lens))))
        counts, differ = [0, 0, 0], []
        for k in range(RANDOM_TEXTS):
            typed = bytearray(rnd.choice(alphabet) for _ in range(TYPED_LEN))
            if k % 2:
                i = rnd.randrange(WORDS)
                piece = words[i][:rnd.choice((lens[i], lens[i], lens[i] - 1))]
                at = rnd.randrange(TYPED_LEN + 1 - len(piece))
                typed[at:at + len(piece)] = piece
            number = rnd.randrange(PHRASES)
            typed = phrases[number] if k % 16 == 0 else bytes(typed)
            want, got = model(typed, phrases[number], words), judged(actor, judge, typed, number)
            counts[want] += 1
            if got != want:
                differ.append("%r with phrase %d: %d, the model's %d" % (show(typed), number, got, want))
        check(not differ and all(counts), "his check and the model of it on %d random texts (the model's: %d the "
              "phrase, %d a word, %d neither): %d differ%s" % (
                  RANDOM_TEXTS, counts[0], counts[1], counts[2], len(differ),
                  "; " + "; ".join(differ[:4]) if differ else ""))

        # 5. his page
        tokens = bank[SAY]
        clears = emu_messages.clears_before(tokens, ITEM0)
        back = [t[2] for t in bank[WRONG] if t[0] == "c" and t[1] == SETFORCEMSG]
        quoted = any(a[0] == "t" and a[1].endswith('"') and b[:2] == ("c", ITEM0)
                     for a, b in zip([t for t in tokens if t[0] == "t" or t[1] == ITEM0],
                                     [t for t in tokens if t[0] == "t" or t[1] == ITEM0][1:]))
        check(clears is not None and quoted and back == [struct.pack(">H", SAY)],
              "message %#x, which the wrong try's page (%#x) goes back to, prints item string 0 between quotes "
              "(page %s)" % (SAY, WRONG, None if clears is None else clears + 1))
        if clears is not None:
            bad, widest = [], 0
            for i, phrase in enumerate(phrases):
                text = phrase.rstrip(b" ")
                page = emu_messages.page_with(tokens, clears, {str(ITEM0): text})
                widest = max(widest, max(board.width(line) for line in page))
                setup()
                took, _ = call(6, SAY, af_dial.PENANCE_FIRST + i)
                window = CTL.unpack(n64.read(ctl, CTL.size))[3]
                problems = ["not shown"]
                for _ in range(6):                     # the pages before it, and the waits inside a page
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
                path = os.path.join(out, "3-page-%02d.png" % i)
                write_png(path, w, h, rgb)
                shots.append(path)
                if not took or problems or not any(text in line for line in page):
                    bad.append("phrase %d: %s" % (i, "; ".join(problems) if took else "not taken by the window"))
            check(not bad, "his page with each of the %d phrases set as his code sets it: the phrase whole, its "
                  "line where the model has it (the widest line %d pixels of the window's 192)%s" % (
                      PHRASES, widest, "; " + "; ".join(bad) if bad else ""))

    contact.main([os.path.join(out, "sheet.png"), "4"] + shots)
    print("emu_resetti: %s; screenshots in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
