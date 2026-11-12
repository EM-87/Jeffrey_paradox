#!/usr/bin/env python3
"""The player-select actor's choices in the en build: a saved town's players, from power-on.

    tests/emu_psel.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR [--ares ARES_DIR]

STATE is a state of EN_ROM in play (make route-en's 4-houses.st). Its common_data, stamped as the game
stamps a save (func_8008EFDC_jp: the code, the town's id, the clock; then the checksum), is the saved town;
its player is copied into the next slots for towns of two and four players (other names and ids, each
owning the next house), and each town is packed as the en build's slot image (tools/aflz.py) into both
slots of a fresh cartridge's flash. From power-on, A goes through the player-select actor's talk
(ovl_Npc_P_Sel2) and each choice is read in RAM as the window holds it (mChoice_en_strings and the lengths
in the Choice), its screen kept in OUT_DIR. Checks (reference/NOTES.md, "The player-select actor's
answers"):

  1. one player: which player plays? lists the player and the bank's answer 121 for a new player, the token
     tools/af_answers.py writes where the cartridge copies a word of the overlay's own;
  2. two players: the same with both; then the first menu's second answer, the next menu's second and
     its first, to remove a house: whose? both
     players and the bank's answer 41 (func_809BF244_jp with 0, the third answer loaded into the 10 bytes
     before the window's pointer on its stack: the en build's loader wrote 24 there, and the game hung);
  3. four players: whose house? two players and the bank's answers 441 and 41 (func_809BF3E4_jp: both
     loaded by func_809BF1E8_jp, the last into the last 10 bytes of its buffer); each answer whole, longer
     than the cartridge's 10 bytes.

With --ares (emu/ares/build.sh's output), the two players' towns are played again on ares: which player
plays, and whose house to remove.
"""

import json
import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
sys.path.insert(0, HERE)
import aflz  # noqa: E402
import ares64  # noqa: E402
import emu_save  # noqa: E402
import msgbank  # noqa: E402
from afplay import DIRECTIONS  # noqa: E402
from n64emu import N64, A, START, Hang  # noqa: E402

SAVE_SIZE = 0xF980
LAND_ID = 0x2F68                       # Save.landInfo.id
RTC_TIME = 0x1010C + 0x10              # CommonData.time.rtcTime (lbRTC_time_c, 8 bytes)
PRIVATE_AT, PRIVATE_SIZE = 0x20, 0xBD0  # Save.saveFilePrivateInfo[4]
HOMES_AT, HOME_SIZE = 0x3588, 0xB48     # Save.homes[4], each its owner's PersonalID_c first
OTHERS = ["bob", "dot", "ivy"]
CHOICE = 0x1B0                         # MessageWindow.choiceWindow
MAIN_IDX, VISIBLE = 0x9C, 0xA4         # Choice.mainIdx (2: waiting for an answer), Choice.isWindowVisible
LENS, NUM = 0x34 + 0x28, 0x34 + 0x48   # Choice.data.stringLens[4], Choice.data.choiceNum
EN_LEN = 24                            # Choice_EN_STRING_LEN

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def bank_answers(af_en):
    """The en choice bank as the ROM holds it: answer number -> bytes."""
    idx = open(os.path.join(af_en, "assets/jp/en/choice_index.bin"), "rb").read()
    text = open(os.path.join(af_en, "assets/jp/en/choice_text.bin"), "rb").read()
    ends = struct.unpack(">%dI" % (len(idx) // 4), idx)
    return lambda n: text[(ends[n - 1] if n else 0):ends[n]]


def stamp(common):
    """common_data's save stamped as func_8008EFDC_jp stamps it, the checksum made good."""
    save = bytearray(common[:SAVE_SIZE])
    save[4:8] = b"NAFJ"
    save[8:10] = common[LAND_ID:LAND_ID + 2]
    save[0x0A:0x12] = common[RTC_TIME:RTC_TIME + 8]
    return fix(save)


def fix(save):
    save = bytearray(save)
    save[0x12:0x14] = b"\0\0"
    total = sum(struct.unpack(">%dH" % (len(save) // 2), bytes(save))) & 0xFFFF
    save[0x12:0x14] = struct.pack(">H", -total & 0xFFFF)
    return bytes(save)


def town(save, players):
    """The town with its player copied into the next slots: other names and ids, each owning the next house."""
    out = bytearray(save)
    p0 = save[PRIVATE_AT:PRIVATE_AT + PRIVATE_SIZE]
    h0 = save[HOMES_AT:HOMES_AT + HOME_SIZE]
    pad = p0[5:6]
    for i in range(1, players):
        p = bytearray(p0)
        p[0:6] = bytes(msgbank.N64_BYTES[c] for c in OTHERS[i - 1]).ljust(6, pad)
        p[0x0C:0x0E] = struct.pack(">H", (struct.unpack(">H", p0[0x0C:0x0E])[0] + i) & 0xFFFF)
        out[PRIVATE_AT + i * PRIVATE_SIZE:PRIVATE_AT + (i + 1) * PRIVATE_SIZE] = p
        h = bytearray(h0)
        h[0:0x10] = p[0:0x10]
        out[HOMES_AT + i * HOME_SIZE:HOMES_AT + (i + 1) * HOME_SIZE] = h
    return fix(out)


def name_of(save, i):
    return save[PRIVATE_AT + i * PRIVATE_SIZE:PRIVATE_AT + i * PRIVATE_SIZE + 6].rstrip(b" ")


def play(emu_dir, rom_path, af_en, save, out, answers):
    """play_here() in a process of its own (the emulator's plugins start once per process)."""
    os.makedirs(out, exist_ok=True)
    town_path = os.path.join(out, "..", os.path.basename(out) + ".town")
    open(town_path, "wb").write(save)
    r = subprocess.run([sys.executable, os.path.abspath(__file__), "--play", emu_dir, rom_path, af_en, town_path, out,
                        ",".join(str(a) for a in answers)], capture_output=True, text=True)
    lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
    if r.returncode or not lines:
        print(r.stdout[-2000:], r.stderr[-2000:])
        return [], False
    result = json.loads(lines[-1])
    return [([bytes.fromhex(a) for a in got], frame) for got, frame in result["seen"]], result["went_on"]


def play_here(emu_dir, rom, save, out, answers, limit=500):
    """Power-on with the town in both slots; A through the talk; at each choice its answers, then the answer
    given by answers (one number per choice; past them, stop). Returns [(answers read, frame)], and whether
    the game went on."""
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    image, compressed = aflz.pack(save, b"")
    if not compressed:
        raise SystemExit("emu_psel: the town does not fit a compressed slot")
    on_ares = os.path.exists(os.path.join(emu_dir, "ares-headless"))
    if on_ares:                                           # ares keeps the flash in the N64's byte order
        open(os.path.join(out, "game.flash"), "wb").write(image + image)
        machine = ares64.Ares64(emu_dir, rom, out)
    else:
        emu_save.write_fla(emu_save.fla_path(out, rom), image + image)
        machine = N64(emu_dir, rom, out, timeout=60)
    seen = []
    with machine as n64:
        window = play_here.syms["mMsg_window"] + CHOICE
        strings = play_here.syms["mChoice_en_strings"]
        n64.frames(400, 0)
        n64.press(START, 3, 60)
        try:
            for _ in range(limit):
                main_idx, = struct.unpack(">i", n64.read(window + MAIN_IDX, 4))
                visible, = struct.unpack(">i", n64.read(window + VISIBLE, 4))
                if main_idx != 2 or not visible:
                    n64.press(A, 3, 22)
                    continue
                n64.frames(20, 0)
                num, = struct.unpack(">i", n64.read(window + NUM, 4))
                lens = struct.unpack(">4i", n64.read(window + LENS, 16))
                raw = n64.read(strings, 4 * EN_LEN)
                got = [raw[k * EN_LEN:k * EN_LEN + lens[k]] for k in range(num)]
                n64.screenshot(os.path.join(out, "choice_%d.png" % len(seen)))
                seen.append((got, n64.frame))
                for _ in range(answers[len(seen) - 1]):
                    n64.frames(4, DIRECTIONS["d"])
                    n64.frames(8, 0)
                n64.press(A, 3, 30)
                if len(seen) == len(answers):
                    break
            n64.frames(300, 0)                            # the game goes on after the last answer
            went_on = True
        except (Hang, ares64.Hang):
            went_on = False
        n64.screenshot(os.path.join(out, "end.png"))
    return seen, went_on


def text(b):
    rev = {}
    for ch, code in msgbank.N64_BYTES.items():
        rev.setdefault(code, ch)
    return "".join(rev.get(c, "{%02x}" % c) for c in b)


def main(argv):
    ares_dir = None
    if "--ares" in argv:
        i = argv.index("--ares")
        ares_dir = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    emu_dir, rom_path, af_en, state, out = argv
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    answer = bank_answers(af_en)
    new_player, maybe_not, someone_else = answer(121), answer(41), answer(441)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)

    with N64(emu_dir, rom, os.path.join(out, "state"), timeout=60) as n64:
        n64.load_state(state)
        common = n64.read(syms["common_data"], RTC_TIME + 8)
    save = stamp(common)
    check(emu_save.checksum_ok(save) and save[4:8] == b"NAFJ", "the houses' common_data stamped as a save")
    one, two, four = town(save, 1), town(save, 2), town(save, 4)
    names = [name_of(four, i) for i in range(4)]
    print("      the players: %s" % ", ".join(text(n) for n in names))

    # 1. one player: start (the first answer); which player plays?
    seen, went_on = play(emu_dir, rom_path, af_en, one, os.path.join(out, "1-one"), [0, 0])
    got = seen[1][0] if len(seen) > 1 else None
    check(got == [names[0], new_player],
          "one player: which player plays? %s" % (" / ".join(text(g) for g in got) if got else "(no choice)"))
    check(went_on, "one player: the game goes on")

    # 2. two players: which player plays?
    seen, went_on = play(emu_dir, rom_path, af_en, two, os.path.join(out, "2-two"), [0, 0])
    got = seen[1][0] if len(seen) > 1 else None
    check(got == [names[0], names[1], new_player],
          "two players: which player plays? %s" % (" / ".join(text(g) for g in got) if got else "(no choice)"))
    # the first menu's second answer, the next menu's second, its first: whose house to remove? then cancel
    seen, went_on = play(emu_dir, rom_path, af_en, two, os.path.join(out, "2-two-remove-house"), [1, 1, 0, 2])
    got = seen[3][0] if len(seen) > 3 else None
    check(got == [names[0], names[1], maybe_not],
          "two players, whose house to remove? %s" % (" / ".join(text(g) for g in got) if got else "(no choice)"))
    check(went_on, "two players, whose house to remove: the game goes on (frame %d)" % (seen[-1][1] if seen else 0))

    # 3. four players: whose house to remove?
    seen, went_on = play(emu_dir, rom_path, af_en, four, os.path.join(out, "3-four-remove-house"), [1, 1, 0, 3])
    got = seen[3][0] if len(seen) > 3 else None
    check(got is not None and len(got) == 4 and got[0] in names and got[1] in names and
          got[2:] == [someone_else, maybe_not],
          "four players, whose house to remove? %s" % (" / ".join(text(g) for g in got) if got else "(no choice)"))
    check(went_on, "four players, whose house to remove: the game goes on")
    check(len(someone_else) > 10 and len(maybe_not) > 10 and len(new_player) > 10,
          "the answers are whole past the cartridge's 10 bytes (%d, %d, %d)" % (
              len(new_player), len(maybe_not), len(someone_else)))

    if ares_dir:
        # the same towns on ares (emu/ares64.py): its own CPU caches, its RDP, its flash
        for label, save, answers, want in (
                ("two players", two, [0, 0], [names[0], names[1], new_player]),
                ("two players, whose house to remove", two, [1, 1, 0, 2], [names[0], names[1], maybe_not])):
            sub = "ares-" + label.replace(", ", "-").replace(" ", "-")
            seen, went_on = play(ares_dir, rom_path, af_en, save, os.path.join(out, sub), answers)
            at = 1 if answers == [0, 0] else 3
            got = seen[at][0] if len(seen) > at else None
            check(got == want and went_on, "ares, %s: %s; the game goes on: %s" % (
                label, " / ".join(text(g) for g in got) if got else "(no choice)", went_on))

    print("emu_psel: %s" % ("all passed" if not failures else "%d failed" % len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1] == "--play":
        emu_dir, rom_path, af_en, town_path, out, answers = sys.argv[2:8]
        play_here.syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
        seen, went_on = play_here(emu_dir, open(rom_path, "rb").read(), open(town_path, "rb").read(), out,
                                  [int(a) for a in answers.split(",")])
        print(json.dumps({"seen": [([g.hex() for g in got], frame) for got, frame in seen], "went_on": went_on}))
        sys.exit(0)
    sys.exit(main(sys.argv[1:]))
