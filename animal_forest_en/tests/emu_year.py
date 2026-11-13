#!/usr/bin/env python3
"""The year's events in the en build: a saved town powered on at each event's date and time, every block of the
town visited, and the event's actors, its villagers and the special characters talked to.

    tests/emu_year.py EMU_DIR EN_ROM AF_EN_DIR TOWN OUT_DIR [--only LABEL ...] [--answers first|last] [--jobs N]

TOWN is emu_psel's town of two players (make psel-check-en's OUT_DIR/2-two.town: the houses' state stamped as a
save, its player copied into a second slot). Its first player still has the new player's part-time job and the
arrival talk pending (event flags 2 and 6 in common_data + 0xEE3C, the save's copy of the type-1 event flags:
func_8007D25C_jp sets them, mEv_CheckFirstJob and mEv_CheckFirstIntro read them); both are cleared here, so the
player walks the town as one who has done the job.

The events are the cartridge's, read from its notices on the board (the en bank's mail_body 420-458, the
dates and times as they give them): New Year's Day, the countdown, the cherry blossom festival, the spring and
fall sports fairs at their events, Children's Day, the June and November fishing tourneys, morning aerobics,
the August fireworks, the two moon viewings, Halloween and Toy Day; and two weekly visitors (K.K. on Saturday
nights, the turnip seller on Sunday mornings).

For each, in a process of its own (mupen64plus starts once per process; --jobs runs that many at once): the
town in both slots of a fresh flash, the cartridge's clock at the event's date and time (n64emu's fixed clock);
START, the greeter's first answer and the first player (the choice window in RAM, as emu_psel reads it), the
greeter's pages read and screened on the way; A while the window shows, until the player waits (its main index,
player + 0xCF0, 7: mPlib_get_player_actor_main_index) with the window hidden. Then every block of the town's
5 x 6, walked into over its edges (the game makes a block's actors when the player crosses into it, main index
15; a jump by position keeps the old block's), each block's actors listed (the actor lists,
Game_Play.actorInfo). Each actor an event brings (an Ev_ overlay), each villager it sets up (an actor named
*_Npc or *_Npc<n>: the party under the cherry trees, the fairs' teams...), the special characters' (K.K., the
police) and the first two ordinary villagers (Normal_Npc, by their fg) are then talked to from the state on
arrival: the player taken to the block the tour saw it in (or one beside it: a villager walks), put before it
facing it (its front first, then around it), A pressed; the talk played through (A on each page the window
waits on; at a choice the first answer, or the last with --answers last, and the last for a question asked
twice before), every page's text read from the window's message data and checked: no kana or kanji (the N64
charset's, the en bank's two marks ー and ～ aside) and no item or name token left in it (0x80 and a digit,
m_item_name.c); a choice's answers the same, from mChoice_en_strings. A hang (no frame for 60 s) fails. Screens
of each page go to OUT_DIR/LABEL, the pages' text to OUT_DIR/report.json (reference/NOTES.md, "The year's
events").
"""

import json
import math
import re
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
sys.path.insert(0, HERE)
import aflz  # noqa: E402
import emu_psel  # noqa: E402
import emu_save  # noqa: E402
import msgbank  # noqa: E402
from n64emu import N64, A, B, START, Hang  # noqa: E402
from afplay import DIRECTIONS  # noqa: E402

GAME_PT = 0x8010EF90                   # gamePT: the game state, Game_Play in the town
ACTOR_INFO = 0x1C78                    # Game_Play.actorInfo: a count and the first actor for each of 8 parts
PART_PLAYER, PART_NPC = 2, 3
A_NAME, A_FG, A_POS, A_PREV, A_ROT_Y, A_SHAPE_ROT_Y, A_NEXT = 0x0, 0x6, 0x28, 0x3C, 0x36, 0xDE, 0x158
PLAYER_MAIN, PLAYER_WAIT = 0xCF0, 7    # mPlib_get_player_actor_main_index; 7 the player waiting, free
BLOCK_KIND_P = 0x80106818              # g_block_kind_p: a u32 of mRF_BLOCKKIND_ flags for each of 7 x 8 blocks
BLOCK = 640.0                          # FI_BK_WORLDSIZE_X: 16 units of 40
EVENT_FLAGS = 0xEE3C                   # the save's type-1 event flags (func_8007D25C_jp)
JOB_BITS = (1 << 2) | (1 << 6)         # the first player's first job and first intro
# the message window (en: mMsg_window): its message and page, the choice window in it
W_MSG_DATA, W_MAIN, W_START, W_END = 0x00C, 0x2B4, 0x29C, 0x2A0
WAITING, HIDDEN = 2, 0
CHOICE = 0x1B0
C_MAIN, C_VISIBLE, C_LENS, C_NUM = 0x9C, 0xA4, 0x34 + 0x28, 0x34 + 0x48
EN_LEN = 24
D_MSGNO, D_LEN, D_TEXT = 0x4, 0x8, 0x10
KINDS = ("player", "shop", "shrine", "police", "post", "station", "cliff", "river", "waterfall", "bridge",
         "rail", "beach", "border", "tunnel", "slope")
JAPANESE = {i for i, c in enumerate(msgbank.N64_CHARS)
            if c and c not in ("ー", "～") and any("　" <= ch <= "ヿ" or "一" <= ch <= "鿿"
                                                for ch in c)}
TOKEN_MARK = 0x80
SPECIAL = ("Npc_Totakeke", "Npc_Police", "Npc_Police2")
VILLAGERS = 2                          # the ordinary villagers (Normal_Npc) talked to on each date, the first found


def talked_to(name):
    """An event's actor (an Ev_ overlay, or a villager the event sets up: Hanami_Npc0, Halloween_Npc...), or a
    special character's."""
    return (name.startswith("Ev_") or name in SPECIAL or
            (re.search(r"_Npc\d*$", name) is not None and name not in ("Normal_Npc", "Set_Npc_Manager")))

# (label, date, time): the cartridge's events as its notices give them
EVENTS = [
    ("new-years-day", (2002, 1, 1), (10, 0)),
    ("kk-saturday", (2002, 2, 2), (20, 30)),
    ("turnips-sunday", (2002, 2, 3), (7, 0)),
    ("cherry-blossoms", (2002, 4, 6), (12, 0)),
    ("cherry-blossoms-night", (2002, 4, 6), (19, 30)),
    ("spring-fair-aerobics", (2002, 4, 20), (9, 30)),
    ("spring-fair-footrace", (2002, 4, 20), (11, 30)),
    ("spring-fair-ball-toss", (2002, 4, 20), (13, 30)),
    ("spring-fair-tug-of-war", (2002, 4, 20), (15, 30)),
    ("childrens-day", (2002, 5, 5), (12, 0)),
    ("fishing-tourney-june", (2002, 6, 2), (10, 0)),
    ("morning-aerobics", (2002, 7, 25), (6, 10)),
    ("fireworks", (2002, 8, 3), (19, 30)),
    ("harvest-moon", (2002, 9, 21), (18, 30)),
    ("thirteenth-night-moon", (2002, 10, 19), (18, 30)),
    ("fall-fair-aerobics", (2002, 10, 14), (9, 30)),
    ("fall-fair-tug-of-war", (2002, 10, 14), (15, 30)),
    ("halloween", (2002, 10, 31), (19, 0)),
    ("fishing-tourney-november", (2002, 11, 3), (10, 0)),
    ("toy-day", (2002, 12, 24), (19, 0)),
    ("countdown", (2002, 12, 31), (23, 10)),
]

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def actor_names(af_en):
    names = {}
    for line in open(os.path.join(af_en, "include", "tables", "actor_table.h")):
        line = line.strip()
        if line.startswith("/* 0x") and "DEFINE_ACTOR" in line:
            num = int(line[3:line.index("*/")].strip(), 16)
            names[num] = line[line.index("(") + 1:line.rindex(")")].split(",")[0].strip()
    return names


def town_image(town):
    """Both slots of a fresh flash: the town, its first player's job and arrival talk done."""
    save = bytearray(open(town, "rb").read())
    flags = struct.unpack(">I", save[EVENT_FLAGS:EVENT_FLAGS + 4])[0] & ~JOB_BITS
    save[EVENT_FLAGS:EVENT_FLAGS + 4] = struct.pack(">I", flags)
    image, compressed = aflz.pack(emu_psel.fix(bytes(save)), b"")
    if not compressed:
        raise SystemExit("emu_year: the town does not fit a compressed slot")
    return image + image


class Town:
    """The game in the town: its actors, the player's place, the message window."""

    def __init__(self, n64, window, strings, names):
        self.n64, self.window, self.strings, self.names = n64, window, strings, names

    def i32(self, addr):
        return struct.unpack(">i", self.n64.read(addr, 4))[0]

    def play(self):
        return self.n64.u32(GAME_PT)

    def actors(self):
        out = []
        play = self.play()
        for part in range(8):
            a = self.n64.u32(play + ACTOR_INFO + 4 + part * 8 + 4)
            for _ in range(256):
                if not a:
                    break
                raw = self.n64.read(a, A_NEXT + 4)
                name = struct.unpack(">h", raw[A_NAME:A_NAME + 2])[0]
                fg = struct.unpack(">H", raw[A_FG:A_FG + 2])[0]
                x, y, z = struct.unpack(">3f", raw[A_POS:A_POS + 12])
                out.append({"part": part, "addr": a, "name": self.names.get(name, "?%x" % name), "fg": fg,
                            "pos": (x, y, z)})
                a = struct.unpack(">I", raw[A_NEXT:A_NEXT + 4])[0]
        return out

    def player(self):
        return self.n64.u32(self.play() + ACTOR_INFO + 4 + PART_PLAYER * 8 + 4)

    def player_main(self):
        p = self.player()
        return self.i32(p + PLAYER_MAIN) if p else -1

    def pos(self, actor):
        return struct.unpack(">3f", self.n64.read(actor + A_POS, 12))

    def put(self, x, y, z, yaw=None):
        p = self.player()
        self.n64.write(p + A_POS, struct.pack(">3f", x, y, z))
        self.n64.write(p + A_PREV, struct.pack(">3f", x, y, z))
        if yaw is not None:
            self.n64.write(p + A_ROT_Y, struct.pack(">h", yaw))
            self.n64.write(p + A_SHAPE_ROT_Y, struct.pack(">h", yaw))

    def block(self):
        x, _, z = self.pos(self.player())
        return int(x // BLOCK), int(z // BLOCK)

    def drop(self, x, z, frames=24):
        """The player put at (x, z) in its block, from above: it lands on the ground there (a cliff's top or
        foot, whichever is under it)."""
        for _ in range(3):
            self.put(x, 420.0, z)
            self.n64.frames(2, 0)
        self.n64.frames(frames, 0)

    def cross(self, nb):
        """Walk the player into the neighbouring block nb: the game changes blocks only when the player walks
        over the edge (its main index 15 while the screen scrolls, the new block's actors made then); a jump
        there by position leaves the old block's actors. Tries points along the edge; True if over."""
        bx, bz = self.block()
        dx, dz = nb[0] - bx, nb[1] - bz
        stick = DIRECTIONS[{(1, 0): "r", (-1, 0): "l", (0, 1): "d", (0, -1): "u"}[(dx, dz)]]
        for f in (0.5, 0.3, 0.7, 0.15, 0.85, 0.4, 0.6, 0.22, 0.78):
            if dx:
                x = (bx + (1 if dx > 0 else 0)) * BLOCK - dx * 45
                z = bz * BLOCK + f * BLOCK
            else:
                z = (bz + (1 if dz > 0 else 0)) * BLOCK - dz * 45
                x = bx * BLOCK + f * BLOCK
            self.drop(x, z)
            for _ in range(30):
                self.n64.frames(3, stick)
                if self.block() == nb:
                    for _ in range(40):
                        self.n64.frames(5, 0)
                        if self.player_main() == PLAYER_WAIT:
                            break
                    return True
        return False

    def go_block(self, target, blocked):
        """The player to the target block over walkable edges (breadth first; an edge found closed is kept in
        blocked). True if there."""
        for _ in range(40):
            here = self.block()
            if here == target:
                return True
            prev = {here: None}
            queue = [here]
            while queue:
                b = queue.pop(0)
                if b == target:
                    break
                for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nb = (b[0] + d[0], b[1] + d[1])
                    if 1 <= nb[0] <= 5 and 1 <= nb[1] <= 6 and nb not in prev and (b, nb) not in blocked:
                        prev[nb] = b
                        queue.append(nb)
            if target not in prev:
                return False
            step = target
            while prev[step] != here:
                step = prev[step]
            if not self.cross(step):
                blocked.add((here, step))
                blocked.add((step, here))
        return self.block() == target

    def go(self, tx, tz, blocked=None):
        """The player to (tx, tz): over the blocks' edges to its block, then put there."""
        blocked = set() if blocked is None else blocked
        target = (int(tx // BLOCK), int(tz // BLOCK))
        ok = self.go_block(target, blocked)
        if ok:
            self.drop(tx, tz, 30)
        return ok

    def blocks(self):
        p = self.n64.u32(BLOCK_KIND_P)
        raw = self.n64.read(p, 7 * 8 * 4)
        out = {}
        for bz in range(1, 7):
            for bx in range(1, 6):
                v = struct.unpack(">I", raw[(bz * 7 + bx) * 4:(bz * 7 + bx) * 4 + 4])[0]
                out[(bx, bz)] = [KINDS[b] for b in range(len(KINDS)) if v >> b & 1]
        return out

    # the message window
    def window_main(self):
        return self.i32(self.window + W_MAIN)

    def choice_waiting(self):
        return (self.i32(self.window + CHOICE + C_MAIN) == WAITING and
                self.i32(self.window + CHOICE + C_VISIBLE) != 0)

    def page(self):
        data = self.n64.u32(self.window + W_MSG_DATA)
        if not data:
            return None
        msg_no = self.i32(data + D_MSGNO)
        length = max(0, min(self.i32(data + D_LEN), 0x600))
        raw = self.n64.read(data + D_TEXT, length)
        return msg_no, self.i32(self.window + W_START), raw

    def answers(self):
        num = max(0, min(self.i32(self.window + CHOICE + C_NUM), 4))
        lens = struct.unpack(">4i", self.n64.read(self.window + CHOICE + C_LENS, 16))
        raw = self.n64.read(self.strings, 4 * EN_LEN)
        return [raw[k * EN_LEN:k * EN_LEN + max(0, min(lens[k], EN_LEN))] for k in range(num)]


def problems(raw):
    """Kana, kanji or a token left in a message's or an answer's bytes (its codes skipped)."""
    out = []
    i = 0
    while i < len(raw):
        b = raw[i]
        if b == 0x7F and i + 1 < len(raw):
            code = raw[i + 1]
            i += msgbank.SIZES[code] if code < len(msgbank.SIZES) else 2
            continue
        if b in JAPANESE:
            out.append("Japanese {%02x}%s at %d" % (b, msgbank.N64_CHARS[b], i))
        if b == TOKEN_MARK and i + 1 < len(raw) and 0x81 <= raw[i + 1] < 0x81 + 64:
            out.append("a token at %d" % i)
        i += 1
    return out


def text(raw):
    return msgbank.render(msgbank.decode(raw, msgbank.N64_CHARS))


def arrive(t, answers=(0, 0), pages=None, shots=None):
    """From power-on: START, the greeter's answers, A until the player waits in the town. pages: a list the
    greeter's pages go to ({msg, at, text, problems}); shots: a path's start for their screens."""
    n64 = t.n64
    n64.frames(400, 0)
    n64.press(START, 3, 60)
    given = 0
    free = 0
    seen = set()
    for _ in range(1500):
        page = t.page() if pages is not None and t.window_main() == WAITING else None
        if page and (page[0], page[1]) not in seen:
            seen.add((page[0], page[1]))
            pages.append({"msg": page[0], "at": page[1], "text": text(page[2])[:600], "problems": problems(page[2])})
            if shots:
                n64.screenshot("%s-greeter-%02d.png" % (shots, len(pages)))
        if t.choice_waiting() and given < len(answers):
            n64.frames(20, 0)
            for _ in range(answers[given]):
                n64.frames(4, DIRECTIONS["d"])
                n64.frames(8, 0)
            n64.press(A, 3, 30)
            given += 1
            continue
        if given >= len(answers) and t.window_main() == HIDDEN and t.player_main() == PLAYER_WAIT:
            free += 1
            if free >= 4:
                return True
            n64.frames(15, 0)
            continue
        free = 0
        if t.window_main() != HIDDEN:
            n64.press(A, 3, 22)
        else:
            n64.frames(15, 0)
    return False


def talk(t, actor, shots, label, which, last=False, limit=8000):
    """Put the player before the actor facing it, A, and play the talk through. Returns what it read."""
    n64 = t.n64
    ax, ay, az = t.pos(actor["addr"])
    facing = struct.unpack(">h", n64.read(actor["addr"] + A_SHAPE_ROT_Y, 2))[0] * math.pi / 0x8000
    out = {"actor": actor["name"], "fg": "%04x" % actor["fg"], "pages": [], "choices": [], "problems": [],
           "talked": False, "ended": False}
    for attempt in range(16):
        # its front first (a stall's keeper talks across its table), then around it, nearer and farther
        ang = facing + (attempt % 8) * math.pi / 4
        dist = 55 if attempt < 8 else 40
        x, z = ax + dist * math.sin(ang), az + dist * math.cos(ang)
        yaw = int(math.atan2(ax - x, az - z) * 0x8000 / math.pi) & 0xFFFF
        yaw = yaw - 0x10000 if yaw >= 0x8000 else yaw
        for _ in range(4):
            t.put(x, ay + 10, z, yaw)
            n64.frames(2, 0)
        n64.press(A, 3, 10)
        for _ in range(40):
            if t.window_main() != HIDDEN or t.player_main() != PLAYER_WAIT:
                break
            n64.frames(3, 0)
        if t.window_main() != HIDDEN:
            out["talked"] = True
            break
    if not out["talked"]:
        return out
    seen = set()
    asked = {}
    start = n64.frame
    quiet = 0
    stuck = 0
    while n64.frame - start < limit:
        if t.choice_waiting():
            n64.frames(10, 0)
            answers = t.answers()
            texts = [text(a) for a in answers]
            out["choices"].append(texts)
            for a in answers:
                for p in problems(a):
                    out["problems"].append("answer %r: %s" % (text(a), p))
            n64.screenshot(os.path.join(shots, "%s-%02d-choice-%d.png" % (label, which, len(out["choices"]))))
            again = asked.get(tuple(texts), 0)
            asked[tuple(texts)] = again + 1
            k = len(answers) - 1 if last or again >= 2 else 0     # a question asked twice before: its last answer
            for _ in range(k):
                n64.frames(4, DIRECTIONS["d"])
                n64.frames(8, 0)
            n64.press(A, 3, 30)
            quiet = stuck = 0
            continue
        main = t.window_main()
        if main == WAITING:
            page = t.page()
            if page:
                msg_no, at, raw = page
                key = (msg_no, at)
                if key not in seen:
                    seen.add(key)
                    out["pages"].append({"msg": msg_no, "at": at, "text": text(raw)[:600]})
                    for p in problems(raw):
                        out["problems"].append("message %d: %s" % (msg_no, p))
                    n64.screenshot(os.path.join(shots, "%s-%02d-page-%03d.png" % (label, which, len(out["pages"]))))
            n64.press(A, 3, 12)
            quiet = stuck = 0
            continue
        if main == HIDDEN and t.player_main() == PLAYER_WAIT:
            quiet += 1
            if quiet >= 6:
                out["ended"] = True
                break
            n64.frames(15, 0)
            continue
        quiet = 0
        stuck += 1
        if stuck % 40 == 39:            # a menu the talk opened (a shop's, the pockets): B leaves it
            n64.press(B, 3, 20)
        else:
            n64.frames(10, 0)
    out["frames"] = n64.frame - start
    return out


def find_again(t, name, fg, block, blocked):
    """The actor (by its name and fg) from the town as it was on arrival: in the block the tour saw it in, or
    one beside it (a villager walks); None if in none."""
    for b in [block] + [(block[0] + dx, block[1] + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]:
        if not (1 <= b[0] <= 5 and 1 <= b[1] <= 6) or not t.go_block(b, blocked):
            continue
        found = [a for a in t.actors() if a["name"] == name and a["fg"] == fg]
        if found:
            return found[0]
    return None


def run_event(emu_dir, rom, af_en, town, out, label, date, hm, last):
    """One event in this process: arrive, visit every block, talk to its actors. A dict for the report."""
    names = actor_names(af_en)
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    work = os.path.join(out, label)
    os.makedirs(work, exist_ok=True)
    emu_save.write_fla(emu_save.fla_path(work, rom), town_image(town))
    result = {"label": label, "date": "%04d-%02d-%02d %02d:%02d" % (date + hm), "actors": {}, "talks": [],
              "hang": None}
    with N64(emu_dir, rom, work, timeout=60, clock=date + hm + (0,)) as n64:
        t = Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names)
        try:
            result["arrival"] = []
            result["arrived"] = arrive(t, pages=result["arrival"], shots=os.path.join(work, label))
            n64.screenshot(os.path.join(work, "arrived.png"))
            n64.save_state(os.path.join(work, "free.st"))
            blocks = t.blocks()
            where = {}
            blocked = set()
            unreached = []
            # every block, snaking row by row from the player's
            order = [(bx if bz % 2 else 6 - bx, bz) for bz in range(1, 7) for bx in range(1, 6)]
            for b in order:
                if not t.go_block(b, blocked):
                    unreached.append("%d,%d" % b)
                    continue
                for a in t.actors():
                    if a["part"] in (0, 3, 4, 6) and a["name"] not in result["actors"]:
                        result["actors"][a["name"]] = "%d,%d" % b
                    key = (a["name"], a["fg"])
                    if a["part"] == PART_NPC and key not in where and (
                            talked_to(a["name"]) or
                            (a["name"] == "Normal_Npc" and sum(k[0] == "Normal_Npc" for k in where) < VILLAGERS)):
                        where[key] = b
            result["unreached"] = unreached
            result["blocks"] = {"%d,%d" % b: k for b, k in blocks.items()}
            n64.screenshot(os.path.join(work, "toured.png"))
            for which, (name, fg) in enumerate(sorted(where)):
                n64.load_state(os.path.join(work, "free.st"))
                found = find_again(t, name, fg, where[(name, fg)], set(blocked))
                if found is None:
                    result["talks"].append({"actor": name, "talked": False, "note": "not found again"})
                    continue
                result["talks"].append(talk(t, found, work, label, which, last=last))
        except Hang as e:
            result["hang"] = str(e)
            n64.screenshot(os.path.join(work, "hang.png"))
    return result


def main(argv):
    only = []
    while "--only" in argv:
        i = argv.index("--only")
        only.append(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    last = False
    if "--answers" in argv:
        i = argv.index("--answers")
        last = argv[i + 1] == "last"
        argv = argv[:i] + argv[i + 2:]
    jobs = 1
    if "--jobs" in argv:
        i = argv.index("--jobs")
        jobs = int(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    emu_dir, rom_path, af_en, town, out = argv
    os.makedirs(out, exist_ok=True)
    report = []
    labels = [label for label, _, _ in EVENTS if not only or label in only]

    def one(label):
        return subprocess.run([sys.executable, os.path.abspath(__file__), "--event", emu_dir, rom_path, af_en, town,
                               out, label, "1" if last else "0"], capture_output=True, text=True)

    import concurrent.futures
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:   # each event a process of its own
        runs = list(pool.map(one, labels))
    for label, r in zip(labels, runs):
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
        if r.returncode or not lines:
            print(r.stdout[-1500:], r.stderr[-1500:])
            check(False, "%s: the run ended without a report" % label)
            continue
        res = json.loads(lines[-1])
        report.append(res)
        evs = sorted(n for n in res["actors"] if talked_to(n))
        print("    %s %s: %s" % (label, res["date"], ", ".join(evs) if evs else "no event actors"))
        check(res.get("arrived") and not res["hang"], "%s: into the town and around it%s" % (
            label, "" if not res["hang"] else " (hung: %s)" % res["hang"]))
        greeter = [p for pg in res.get("arrival", []) for p in pg["problems"]]
        check(not greeter, "%s: the greeter, %d pages%s" % (label, len(res.get("arrival", [])),
                                                            "; " + "; ".join(greeter[:4]) if greeter else ""))
        for tk in res["talks"]:
            if not tk.get("talked"):
                print("    %s: %s not talked to (%s)" % (label, tk["actor"], tk.get("note", "no talk started")))
                continue
            check(tk["ended"] and not tk["problems"], "%s: %s, %d pages, %d choices%s" % (
                label, tk["actor"], len(tk["pages"]), len(tk["choices"]),
                "" if tk["ended"] else " (did not end)") + ("; " + "; ".join(tk["problems"][:4]) if tk["problems"] else ""))
    json.dump(report, open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_year: %s" % ("all passed" if not failures else "%d failed" % len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1] == "--event":
        emu_dir, rom_path, af_en, town, out, label, last = sys.argv[2:9]
        date, hm = next((d, h) for lb, d, h in EVENTS if lb == label)
        res = run_event(emu_dir, open(rom_path, "rb").read(), af_en, town, out, label, date, hm, last == "1")
        print(json.dumps(res))
        sys.exit(0)
    sys.exit(main(sys.argv[1:]))
