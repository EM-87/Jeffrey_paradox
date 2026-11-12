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
"""

import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import af_menu_tex  # noqa: E402
import msgbank  # noqa: E402

TOKEN_MARK, TOKEN_DIGIT0, TOKEN_BASE = 0x80, 0x81, 64          # the en m_choice_main.h's Choice_EN_TOKEN_*

# (the overlay's data, its vrom and vram, the word's vram, the bank's answer, what it is)
ANSWERS = [
    ("asm/jp/data/overlays/actors/ovl_Npc_P_Sel2/ac_npc_p_sel2.data.s", 0x8A1F10, 0x809BE720, 0x809C0C38, 121,
     "the player-select actor's answer for a new player (func_809BF244_jp)"),
]

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


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__)
    rom_path, en = argv
    rom = open(rom_path, "rb").read()
    for data_path, vrom, vram0, vram, number, what in ANSWERS:
        cart = af_menu_tex.file_at(rom, vrom)
        cart_word = struct.unpack(">I", cart[vram - vram0:vram - vram0 + 4])[0]
        check_answer(rom, cart_word, number, what)
        path = os.path.join(en, data_path)
        data_s = open(path).read()
        new_s = rewrite(data_s, vram, cart_word, token(number), what)
        if new_s != data_s:
            open(path, "w").write(new_s)
    print("af_answers: %d answer(s) of the overlays pointed at the choice bank" % len(ANSWERS))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
