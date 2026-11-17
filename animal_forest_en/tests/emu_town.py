#!/usr/bin/env python3
"""The saved town of the checks that power one on: the houses' state made a town the game could have saved.

The new game's route ends where Nook has shown the four houses (tools/route_newgame.py, 4-houses.st). Its
common_data stamped as a save (tests/emu_psel.py's stamp) is not a town the game ever saves:

  - the player owns no house yet. Here the first house is chosen as the game has a player choose one: A at
    its door, a look inside, out again, Nook's two questions answered yes. The game's own talk writes the
    home's owner;
  - while an actor that stands on the map is alive (a house, its mailbox, its gyroid), its place on the map
    holds a stand-in for it, and the actor's save function puts the real item back. The game calls every
    actor's (Actor_info_save_actor) before each save: the gyroid's (ovl_Npc_Restart), the station's, the
    clock's. A copy of common_data without that call keeps the stand-ins, and a town powered on from it has
    no players' houses at all: the first towns of these checks were such copies. Here a hook (tests/
    emu_save.py's way) calls it.

What stays made by hand: the stamp itself (the code, the town's id, the clock, the checksum: the game allows
no save before the player's first job for Nook is done) and, in tests/emu_year.py's town_image, the two event
flags of that job and of the arrival talk.

grow() brings the town to its 15 animals with the game's own mNpc_Grow (the check it asks first, of a day
gone by and of the player's friendships, made to say yes) and mNpc_InitNpcData, which gives each new animal
one of the map's reserved lots and builds its house there.
"""

import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
sys.path.insert(0, HERE)
import emu_save  # noqa: E402
import emu_year  # noqa: E402
from n64emu import A, stick  # noqa: E402
from afplay import DIRECTIONS  # noqa: E402

HOOK_C = r"""
typedef int s32;
void Actor_info_save_actor(void* play);
void mNpc_Grow(void);
void mNpc_InitNpcData(void);
extern void* gamePT;
typedef struct HookCtl { s32 cmd; s32 result; } HookCtl;
HookCtl hook_ctl = { 0 };

void hook_main(void) {
    HookCtl* h = &hook_ctl;

    if (h->cmd == 1) {
        /* every actor's save function, as the game calls them before a save */
        Actor_info_save_actor(gamePT);
        h->cmd = 0;
    } else if (h->cmd == 2) {
        /* one more animal moves in, if the town has room */
        mNpc_Grow();
        h->cmd = 0;
    } else if (h->cmd == 3) {
        /* a lot and a house for each animal that has none */
        mNpc_InitNpcData();
        h->cmd = 0;
    }
}
"""
MAT = (2128.0, 1488.0)                 # the first house's doormat: where its player stands on coming out
INTO_DOOR = stick(-57, 57)             # its door is up and to the left of the mat
PRIVATE_AT, HOMES_AT, NPC_MAX_AT = 0x20, 0x3588, 0x18      # Save: the players, their homes, how many animals
ANIMALS_AT, ANIMAL_SIZE, ANIMAL_HOME, ANIMAL_MAX = 0x9F18, 0x528, 0x4E0, 15
FG_AT, FG_BLOCK = 0x62A8, 0x200        # Save.foreground: 6 rows of 5 blocks, 16 x 16 items each
PLAYERS_BLOCK = (3, 2)                 # the block of the four houses
HOUSES, MAILBOXES, GYROIDS = range(0x5800, 0x5804), range(0xA000, 0xA004), range(0xA004, 0xA008)
INSIDE = "My_Room"                     # the actor of a house's room
GUIDE = "Npc_Rcn_Guide"                # Nook while he shows the houses


def follow(t, answers=(), limit=600, quiet=30, pages=None, choices=None):
    """Play a talk through: A on every page the window waits on, each choice answered by answers (the first
    answer past them), until the window has been hidden for `quiet` steps. The answers given. pages: a list
    the pages read go to ({msg, at, text, problems}); choices: one for each choice's answers as text."""
    n64 = t.n64
    given, still, seen = 0, 0, set()
    for _ in range(limit):
        if pages is not None and t.window_main() == emu_year.WAITING:
            page = t.page()
            if page and (page[0], page[1]) not in seen:
                seen.add((page[0], page[1]))
                pages.append({"msg": page[0], "at": page[1], "text": emu_year.text(page[2])[:700],
                              "problems": t.page_problems(page[2])})
        if t.choice_waiting():
            n64.frames(20, 0)
            if choices is not None:
                choices.append([emu_year.text(a) for a in t.answers()])
            for _ in range(answers[given] if given < len(answers) else 0):
                n64.frames(4, DIRECTIONS["d"])
                n64.frames(8, 0)
            n64.press(A, 3, 30)
            given, still = given + 1, 0
        elif t.window_main() != emu_year.HIDDEN:
            n64.press(A, 3, 22)
            still = 0
        else:
            n64.frames(15, 0)
            still += 1
            if still >= quiet:
                break
    return given


def names(t):
    return {a["name"] for a in t.actors()}


def choose_house(t):
    """The first house chosen as the game has a player choose one. True if the talk went as it goes."""
    n64 = t.n64
    t.drop(MAT[0], MAT[1], 40)
    n64.frames(15, INTO_DOOR)
    n64.frames(3, INTO_DOOR | A)
    n64.frames(30, 0)
    follow(t, quiet=20)                           # Nook's word at the door; the player goes in
    for _ in range(40):
        if INSIDE in names(t) and t.player_main() == emu_year.PLAYER_WAIT:
            break
        n64.frames(15, 0)
    if INSIDE not in names(t):
        return False
    n64.frames(60, DIRECTIONS["d"])               # out again
    for _ in range(60):
        if INSIDE not in names(t):
            break
        n64.frames(15, 0)
    given = follow(t)                             # how was it? this one? ... no need to hear it again
    return INSIDE not in names(t) and GUIDE not in names(t) and given >= 2


def hook(n64, af_en, syms):
    """The hook in place: a function that runs one of its commands."""
    af_en = os.path.abspath(af_en)
    en_elf = os.path.join(af_en, "build", "animalforest-jp.elf")
    ref_map = os.path.join(os.path.dirname(af_en), "af", "build", "animalforest-jp.map")
    hole, hole_size = emu_save.section_of(ref_map, "build/src/code/m_choice_main.o", ".text")
    mttime = syms["mTM_time"]
    words = struct.unpack(">II", n64.read(mttime, 8))
    blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
    emu_save.write_code(n64, hole, blob)
    emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))

    def run(cmd):
        n64.write(ctl, struct.pack(">ii", cmd, 0))
        for _ in range(30):
            n64.frames(1, 0)
            if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                return
        raise SystemExit("emu_town: the hook did not take command %d" % cmd)
    return run


def grow(n64, af_en, syms, run):
    """The town's animals brought to 15 by the game's own code. How many there are."""
    en_elf = os.path.join(af_en, "build", "animalforest-jp.elf")
    out = subprocess.run(["mips-linux-gnu-nm", "-S", en_elf], capture_output=True, text=True, check=True).stdout
    size = next(int(p[1], 16) for p in (ln.split() for ln in out.splitlines()) if len(p) == 4 and p[3] == "mNpc_Grow")
    code = n64.read(syms["mNpc_Grow"], size)
    jal = 0x0C000000 | ((syms["mNpc_CheckGrow"] >> 2) & 0x3FFFFFF)
    calls = [at for at in range(0, len(code), 4) if struct.unpack(">I", code[at:at + 4])[0] == jal]
    if len(calls) != 1:
        raise SystemExit("emu_town: mNpc_Grow calls its check %d times" % len(calls))
    emu_save.write_code(n64, syms["mNpc_Grow"] + calls[0], struct.pack(">I", 0x24020001))    # addiu v0, zero, 1
    cd = syms["common_data"]
    for _ in range(ANIMAL_MAX):
        if n64.read(cd + NPC_MAX_AT, 1)[0] >= ANIMAL_MAX:
            break
        run(2)
    run(3)
    emu_save.write_code(n64, syms["mNpc_Grow"] + calls[0], struct.pack(">I", jal))
    return n64.read(cd + NPC_MAX_AT, 1)[0]


def block_items(save, block):
    at = FG_AT + ((block[1] - 1) * 5 + (block[0] - 1)) * FG_BLOCK
    return struct.unpack(">256H", save[at:at + FG_BLOCK])


def animals(save):
    """[(the animal's name on the map, its home's block and place)] of the town's animals."""
    out = []
    for i in range(ANIMAL_MAX):
        a = save[ANIMALS_AT + i * ANIMAL_SIZE:ANIMALS_AT + (i + 1) * ANIMAL_SIZE]
        npc = struct.unpack(">H", a[0:2])[0]
        if npc >> 12 == 0xE:
            out.append((npc, tuple(a[ANIMAL_HOME + 1:ANIMAL_HOME + 5])))
    return out


def settle(n64, af_en, syms, more=False):
    """From the houses' state loaded in n64: the first house chosen, the town grown to 15 animals if `more`,
    the actors saved. A dict of what was done, for the caller's checks; common_data is then the town."""
    t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)
    done = {"chosen": choose_house(t)}
    run = hook(n64, af_en, syms)
    if more:
        done["animals"] = grow(n64, af_en, syms, run)
    run(1)
    save = n64.read(syms["common_data"], 0x10000)
    items = block_items(save, PLAYERS_BLOCK)
    done["owner"] = save[HOMES_AT:HOMES_AT + 0x10] == save[PRIVATE_AT:PRIVATE_AT + 0x10]
    done["houses"] = sum(1 for i in items if i in HOUSES)
    done["mailboxes"] = sum(1 for i in items if i in MAILBOXES)
    done["gyroids"] = sum(1 for i in items if i in GYROIDS)
    done["homes"] = sum(1 for _, home in animals(save) if home[0] != 0xFF)
    done["animal_houses"] = sum(1 for b in range(30) for i in block_items(save, (b % 5 + 1, b // 5 + 1))
                                if 0x5000 <= i < 0x5800)
    return done


def settled(done, more=False):
    """Whether settle() made the town it is to make."""
    return (done["chosen"] and done["owner"] and done["houses"] == done["mailboxes"] == done["gyroids"] == 4 and
            (not more or done["animals"] == done["homes"] == done["animal_houses"] == ANIMAL_MAX))


def describe(done):
    text = ("the first house chosen by the game's own talk (%s; the home's owner is the player: %s), the actors "
            "saved as the game saves them before a save: %d houses, %d mailboxes and %d gyroids on the players' "
            "block" % ("it went as it goes" if done["chosen"] else "it did not", done["owner"], done["houses"],
                       done["mailboxes"], done["gyroids"]))
    if "animals" in done:
        text += "; the town grown to %d animals by the game's own code, %d with a lot, %d houses on the map" % (
            done["animals"], done["homes"], done["animal_houses"])
    return text
