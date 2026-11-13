#!/usr/bin/env python3
"""The answers an overlay copies from its own data, pointed at the choice bank: the en build's tokens.

    tools/af_answers.py BASEROM EN_DIR

A choice window's answers come from the choice bank (mChoice_Load_ChoseStringFromRom), which the en build
fills in English. A few the cartridge copies instead from a word of an overlay's own data, its Japanese
written there: the player-select actor (ovl_Npc_P_Sel2, the one who greets a saved town's player at power-on)
puts the answer for a new player as the 4 bytes of D_809C0C38_jp after the players' names
(func_809BF244_jp's mem_copy; reference/NOTES.md, "The player-select actor's answers"). This writes over that
word, in the en tree's copy of the overlay's data (its .data.s, which splat rewrites at every extract), the
token of the bank's answer that says the same (Choice_EN_TOKEN_MARK in the en m_choice_main.h: the mark
twice, then the answer's number in two digits), which the en build's mChoice_Add_choice_data takes as that
answer, whole. The word is checked first against the cartridge's (the user's dump), and the answer's number
against the cartridge's choice bank, the answer holding the overlay's word. Nothing of the cartridge's
enters the repository: offsets, numbers and the token.

The fireworks night's stall (ovl_Ev_Yomise, func_80A72DB8_jp) offers its goods three at a time and copies the
fourth answer from its data the same way: 4 bytes once every good has been offered (the start of the bank's
answer 45, which says the same), 8 while more remain (an answer that shows the next three). No answer of the
cartridge's bank says the second, and the GameCube's stall keeps both in its own data too (ac_ev_yomise's
new_player_str and new_player_str2): EXTRA puts the second past the end of the cartridge's 460 answers, the
GameCube's English read from build/gc's copies of its module and map (make script keeps them), or ours
(script/answers.txt) without them:

    tools/af_answers.py extra GC_DIR OWN OUT   the bank's answers past the cartridge's, in msgbank's dump
                                                form, for af_text.py compile --overrides (make rom-en)
"""

import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_menu_tex  # noqa: E402
import msgbank  # noqa: E402
from af_font import gc_find, gc_rel_data, map_layout  # noqa: E402

TOKEN_MARK, TOKEN_DIGIT0, TOKEN_BASE = 0x80, 0x81, 64          # the en m_choice_main.h's Choice_EN_TOKEN_*

CHOICE_COUNT = 460                     # the cartridge's choice bank (m_choice_main.c's reader)
SPACE_WORD = 0x20202020                # four spaces: the stall clears its answers with them (mem_clear's 0x20)
P_SEL2 = "asm/jp/data/overlays/actors/ovl_Npc_P_Sel2/ac_npc_p_sel2.data.s"
YOMISE = "asm/jp/data/overlays/actors/ovl_Ev_Yomise/ac_ev_yomise.data.s"

# (the overlay's data, its vrom and vram, the word's vram, the bank's answer, what it is)
ANSWERS = [
    (P_SEL2, 0x8A1F10, 0x809BE720, 0x809C0C38, 121,
     "the player-select actor's answer for a new player (func_809BF244_jp)"),
    (YOMISE, 0x932B60, 0x80A728C0, 0x80A73ABC, 45,
     "the fireworks stall's answer once every good has been offered (func_80A72DB8_jp)"),
]

# The answers no answer of the bank says, numbered on from the cartridge's 460: (the overlay's data, its vrom
# and vram, the vram of the bytes it copies, how many, the answer's number, the GameCube's symbol and object
# for it, what it is). The first word gets the token, the rest spaces.
EXTRA = [
    (YOMISE, 0x932B60, 0x80A728C0, 0x80A73AC0, 8, 460, ("new_player_str2$530", "ac_ev_yomise.o"),
     "the fireworks stall's answer while goods remain to offer (func_80A72DB8_jp)"),
]
EN_CHOICE_COUNT = CHOICE_COUNT + len(EXTRA)     # the en m_choice_main.h's Choice_EN_CHOICE_NUM

WORD_LINE = re.compile(r"^(\s*/\* ([0-9A-F]+) ([0-9A-F]{8}) ([0-9A-F]{8}) \*/ )\.word .*$")


def token(number):
    """The 4 bytes of the token for the bank's answer number, as a word."""
    if not 0 <= number < TOKEN_BASE * TOKEN_BASE:
        raise SystemExit("af_answers: answer %d has no token" % number)
    return struct.unpack(">I", bytes([TOKEN_MARK, TOKEN_MARK, TOKEN_DIGIT0 + number // TOKEN_BASE,
                                      TOKEN_DIGIT0 + number % TOKEN_BASE]))[0]


def check_answer(rom, cart_word, number, what):
    """The cartridge's answer number holds the overlay's word (its 4 characters begin it)."""
    bank = msgbank.Bank.from_n64(rom, "choice")
    answer = bank.messages[number]
    if not answer.startswith(struct.pack(">I", cart_word)):
        raise SystemExit("af_answers: %s: the cartridge's answer %d does not begin with the overlay's word" % (
            what, number))


def rewrite(data_s, vram, cart_word, new_word, what):
    """The .s with the line of vram as .word new_word, the line's comment holding the cartridge's word (or
    ours, already written)."""
    out, done = [], False
    for line in data_s.split("\n"):
        m = WORD_LINE.match(line)
        if m and int(m.group(3), 16) == vram:
            if int(m.group(4), 16) != cart_word:
                raise SystemExit("af_answers: %s: the line of %08X is not the cartridge's word" % (what, vram))
            line = "%s.word 0x%08X" % (m.group(1), new_word)
            done = True
        out.append(line)
    if not done:
        raise SystemExit("af_answers: %s: no line for %08X" % (what, vram))
    return "\n".join(out)


def check_extra(rom, cart_bytes, what):
    """No answer of the cartridge's bank begins with the overlay's bytes: the bank needs one more."""
    bank = msgbank.Bank.from_n64(rom, "choice")
    if len(bank.messages) != CHOICE_COUNT:
        raise SystemExit("af_answers: the cartridge's choice bank has %d answers, not %d" % (
            len(bank.messages), CHOICE_COUNT))
    if any(m.startswith(cart_bytes) for m in bank.messages):
        raise SystemExit("af_answers: %s: an answer of the cartridge's bank holds the overlay's bytes" % what)


def gc_answers(szs, map_text):
    """{number: text} of EXTRA's answers, the GameCube's, from its module (Yaz0) and map."""
    layout = map_layout(map_text)
    places = {number: gc_find(layout, sym, obj) for _, _, _, _, _, number, (sym, obj), _ in EXTRA}
    data = gc_rel_data(szs, layout, max(at + size for at, size in places.values()))
    return {number: "".join(msgbank.GC_CHARS[b] for b in data[at:at + size]).rstrip(" ")
            for number, (at, size) in places.items()}


def extra_dump(answers):
    """The answers in msgbank's dump form."""
    return "".join("## %d\n%s\n\n" % (number, answers[number]) for number in sorted(answers))


def write_extra(gc_dir, own_path, out):
    rel, mapf = os.path.join(gc_dir, "foresta.rel.szs"), os.path.join(gc_dir, "foresta.map")
    if os.path.exists(rel) and os.path.exists(mapf):
        answers = gc_answers(open(rel, "rb").read(), open(mapf, encoding="latin-1").read())
        source = "the GameCube's"
    else:
        answers = {number: msgbank.render(tokens).replace("<MSGEND>", "")
                   for number, tokens in msgbank.Bank.parse_dump(open(own_path, encoding="utf-8").read()).items()}
        source = "ours"
    wanted = {number for _, _, _, _, _, number, _, _ in EXTRA}
    if set(answers) != wanted:
        raise SystemExit("af_answers: the answers past the bank are %s, not %s" % (sorted(answers), sorted(wanted)))
    for number, text in answers.items():
        if not text or any(ch not in msgbank.N64_BYTES for ch in text):
            raise SystemExit("af_answers: answer %d (%s) is empty or has a character the N64 font lacks" % (
                number, source))
    open(out, "w", encoding="utf-8").write(extra_dump(answers))
    print("af_answers: %d answer(s) past the cartridge's bank (%s)" % (len(answers), source))


def main(argv):
    if argv and argv[0] == "extra":
        if len(argv) != 4:
            raise SystemExit(__doc__)
        write_extra(*argv[1:])
        return 0
    if len(argv) != 2:
        raise SystemExit(__doc__)
    rom_path, en = argv
    rom = open(rom_path, "rb").read()
    edits = []
    for data_path, vrom, vram0, vram, number, what in ANSWERS:
        cart = af_menu_tex.file_at(rom, vrom)
        cart_word = struct.unpack(">I", cart[vram - vram0:vram - vram0 + 4])[0]
        check_answer(rom, cart_word, number, what)
        edits.append((data_path, vram, cart_word, token(number), what))
    for data_path, vrom, vram0, vram, size, number, _, what in EXTRA:
        cart = af_menu_tex.file_at(rom, vrom)
        cart_bytes = cart[vram - vram0:vram - vram0 + size]
        check_extra(rom, cart_bytes, what)
        for k in range(0, size, 4):
            edits.append((data_path, vram + k, struct.unpack(">I", cart_bytes[k:k + 4])[0],
                          token(number) if k == 0 else SPACE_WORD, what))
    for data_path, vram, cart_word, new_word, what in edits:
        path = os.path.join(en, data_path)
        data_s = open(path).read()
        new_s = rewrite(data_s, vram, cart_word, new_word, what)
        if new_s != data_s:
            open(path, "w").write(new_s)
    print("af_answers: %d answer(s) of the overlays pointed at the choice bank" % (len(ANSWERS) + len(EXTRA)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
