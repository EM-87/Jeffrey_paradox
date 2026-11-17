#!/usr/bin/env python3
"""Mr. Resetti by the game's own road in the en build: a saved town powered on again and again, saved or not.

    tests/emu_reset.py EMU_DIR EN_ROM AF_EN_DIR TOWN OUT_DIR

TOWN is emu_psel's town of two players (make psel-check-en's OUT_DIR/2-two.town), as tests/emu_year.py takes
it. The game knows a reset by a mark in the player's data (PrivateInfo + 0xAE0): the start of play sets it
(func_800952D0_jp) and writes the flash; a save that ends the play clears it and one that goes on playing
keeps it (func_800952A4_jp and func_800952D0_jp, from func_80095FE0_jp); a start that finds it set
(func_800953B0_jp) counts a reset (PrivateInfo.resetCount) and raises a flag (common_data + 0x10A68) that
brings Mr. Resetti to the player's door. Nothing here calls any of that or writes any of it: the cartridge's
flash is left as the game wrote it and the console is powered on again, each power-on in a process of its
own (mupen64plus starts once per process), as tests/emu_year.py arrives: START, the greeter's first answer,
the first player, A on every page. The saves are the player's: the gyroid by the house is spoken to and
asked to save, the player is taken into the house, and the one who keeps the saves there (ovl_Npc_Restart)
is told to save and stop, or to save and go on.

The power-ons, in order, and what each is to find:

  0. the town as the checks make it (tests/emu_town.py): no reset, he does not come. Then the gyroid: save
     and go on; the power is cut after it;
  1. a reset is counted (a save that goes on playing keeps the mark), he comes and talks. Then the gyroid:
     save and stop, and the game leaves the town;
  2. no reset, he does not come, the count is what it was: the save that ended the play cleared the mark.
     The power is cut with nothing saved, and so after each of the next;
  3 to 6. one more reset each, he comes and talks; where he waits (he calls the player over, or stands by
     the door until the player walks off and stops them), the player walks off, or goes to him and speaks.
     Which of his actors comes is the count's to say (ovl_Npc's func_8053aab0: its table of four names), and
     the count does not grow for ever: the same function writes a count of 5 or more back as 3 or 4, so
     power-on 6, the fifth reset, finds the count at 3 again and the one who asks for a phrase comes a
     second time. From there the third and the fourth visit take turns.

Checks:

  1. each power-on finds what it is to find: the count, the flag up while he is there and down when he is
     done, one of his actors there or none, the player free at the end;
  2. every page on the way (the greeter's, his, the gyroid's, the save's) is read from the window's message
     data: no kana or kanji and no token left in it;
  3. the saves: the gyroid's four answers, the player taken in, the two answers of the one who keeps the
     saves; after saving to stop the game is back at its title, after saving to go on the player is free;
  4. where he asks for a phrase (ovl_Npc_Majin3: the name entry opens on his actor's 10 bytes): the phrase on
     his page (the window's item string 0) is the bank's string of the number his actor holds; first a try
     one slip away is typed on the dial (the phrase without its last character) and his page is the wrong
     try's, which asks again; then one of his list's words, and his page is the insult's; then the phrase,
     and his page is the right one's: he lets the player go;
  5. the room the game has left while he is at the door (the arena's largest free block at its least, over
     tests/emu_year.py's floor), and where he is made: his actor and his overlay lie inside ovl_Npc's own
     overlay, the room it keeps for the characters it makes, so his visit takes nothing out of the arena
     (tests/emu_memory.py counts on that for every character).
"""

import json
import math
import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
sys.path.insert(0, HERE)
import af_dial  # noqa: E402
import emu_letters  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_write  # noqa: E402
import emu_year  # noqa: E402
import msgbank  # noqa: E402
from n64emu import N64, A, START, Z, Hang  # noqa: E402
from afplay import DIRECTIONS  # noqa: E402

PRIVATE_AT, MARK, RESET_COUNT = 0x10138, 0xAE0, 0x12   # common_data.privateInfo; PrivateInfo's mark, its count
RESET_FLAG = 0x10A68                                  # common_data: a reset was found at this start
TYPER, TYPED_AT, PHRASE_AT, TYPED_LEN = "Npc_Majin3", 0x94C, 0x956, 10   # his actor that asks; its bytes
ITEM_STR, ITEM_LEN = 0x570, 32                         # MessageWindow.itemStr[0] (en: 32 bytes)
SAY, RIGHT, INSULT, WRONG = 0x23EB, 0x23EC, 0x23EF, 0x23EE   # his page with the phrase; D_809B5744_jp's three
SUBMENU_OPEN = emu_write.SUBMENU + 12
GYROID, TITLE = "Haniwa", "Animal_Logo"                # the house's gyroid; the title's logo
GYROID_ASK, GYROID_SAVE = 0x925, 2                     # the gyroid's question, its answer to save (of four)
STOP, GO_ON = 0, 1                                     # the two answers of the one who keeps the saves
# each power-on: whether it is to find a reset, and the save made before the power is cut
PLAN = ((False, GO_ON), (True, STOP), (False, None), (True, None), (True, None), (True, None), (True, None))
STEPS, STALL = 1500, 12                                # a power-on's steps at most; steps he waits before the
                                                       # player goes to him
ACTOR_TABLE, ACTOR_ENTRY = "actor_dlftbls", 0x20      # the game's table of actors: an overlay's vram start and
                                                       # end and where it is loaded, at 8
HIS = {1: "Npc_Majin", 2: "Npc_Majin2", 3: "Npc_Majin3", 4: "Npc_Majin4"}   # the count's actor (D_809837AA_jp)

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


class Dial:
    """The name entry's dial, typed as a player types it (tests/emu_resetti.py's way)."""

    def __init__(self, n64, play, af_en):
        self.n64, self.play = n64, play
        self.capitals, self.marks = emu_write.dial_table(af_en), af_dial.symbol_codes()

    def editor(self):
        n64 = self.n64
        e = n64.read(n64.u32(n64.u32(self.play() + emu_write.SUBMENU + emu_write.SUBMENU_OVL) + emu_write.EDITOR_OVL),
                     0x30)
        return {"mode": e[4], "ring": e[5], "turning": e[3], "lower": e[emu_write.LOWER_FLAG],
                "length": struct.unpack(">h", e[0x1C:0x1E])[0]}

    def press(self, button):
        self.n64.frames(3, button)
        self.n64.frames(6, 0)

    def to_mode(self, mode, lower=None):
        for _ in range(6):
            e = self.editor()
            if e["mode"] == mode and lower in (None, e["lower"]):
                return
            self.press(Z)
        raise SystemExit("the dial has no mode %d (small letters: %s)" % (mode, lower))

    def type_code(self, code):
        ch = bytes([code])
        if b"a" <= ch <= b"z" or b"A" <= ch <= b"Z":
            self.to_mode(emu_write.LETTERS, int(ch.islower()))
            k = self.capitals.index(ch.upper())
        elif ch in self.marks:
            self.to_mode(emu_write.SYMBOLS)
            k = self.marks.index(ch)
        else:
            raise SystemExit("the dial has no place for %r" % ch)
        for _ in range(12):
            e = self.editor()
            if e["turning"] == 0 and e["ring"] == k // 5 * 5:
                break
            self.n64.frames(12, emu_write.DOWN)
            self.n64.frames(12, 0)
        v = emu_write.PLACES[k - self.editor()["ring"]]
        self.n64.frames(5, v)
        self.n64.frames(3, v | A)
        self.n64.frames(3, v)
        self.n64.frames(4, 0)


def show(codes):
    return "".join(msgbank.N64_CHARS[b] for b in codes)


def approach(t, actor):
    """The player put before the actor facing it and A, around it until the window opens (tests/emu_year.py's
    talk). True if it opened."""
    n64 = t.n64
    ax, ay, az = t.pos(actor["addr"])
    facing = struct.unpack(">h", n64.read(actor["addr"] + emu_year.A_SHAPE_ROT_Y, 2))[0] * math.pi / 0x8000
    for attempt in range(16):
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
            if t.window_main() != emu_year.HIDDEN or t.player_main() != emu_year.PLAYER_WAIT:
                break
            n64.frames(3, 0)
        if t.window_main() != emu_year.HIDDEN:
            return True
    return False


def power_on(emu_dir, rom_path, af_en, town, work, k):
    """Power-on k of the cartridge in work, in this process: a dict of what it saw."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    string = emu_letters.bank_strings(af_en)
    cd = syms["common_data"]
    if k == 0:
        os.makedirs(work, exist_ok=True)
        emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(town))
    result = {"on": k, "events": [], "hang": None, "free": False, "save": None, "room": None, "place": None}
    number = {name: n for n, name in names.items()}
    events = result["events"]
    with N64(emu_dir, rom, work, timeout=60, clock=(2001, 5, 10, 10, 5 * k, 0)) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names)
        dial = Dial(n64, t.play, af_en)

        def state():
            priv = n64.u32(cd + PRIVATE_AT)
            return {"count": n64.read(priv + RESET_COUNT, 1)[0] if priv else -1,
                    "mark": n64.u32(priv + MARK) if priv else 0, "flag": n64.read(cd + RESET_FLAG, 1)[0]}

        def his():
            return [a for a in t.actors() if "Majin" in a["name"]]

        def place(actor):
            """[whether his instance lies inside ovl_Npc's own overlay, whether his overlay does]."""
            def entry(name):
                return struct.unpack(">3I", n64.read(syms[ACTOR_TABLE] + number[name] * ACTOR_ENTRY + 8, 12))
            start, end, at = entry("Npc")
            return [bool(at) and at <= a < at + end - start for a in (actor["addr"], entry(actor["name"])[2])]

        def typing(round_):
            """The name entry is open: what he asked for, a text typed by the round, START."""
            actor = next((a["addr"] for a in his() if a["name"] == TYPER), None)
            if actor is None:
                events.append({"typed": None, "note": "a window opened and no actor of his asks for a phrase"})
                n64.press(START, 3, 30)
                return
            number = n64.read(actor + PHRASE_AT, 1)[0]
            phrase = string(af_dial.PENANCE_FIRST + number).rstrip(b" ")
            shown = n64.read(t.window + ITEM_STR, ITEM_LEN).rstrip(b" ")
            word = string(af_dial.INSULT_FIRST).rstrip(b" ")
            text, want = ((phrase[:-1], WRONG), (word, INSULT), (phrase, RIGHT))[min(round_, 2)]
            before = n64.read(actor + TYPED_AT, TYPED_LEN)
            for c in text:
                dial.type_code(c)
            n64.frames(10, 0)
            n64.screenshot(os.path.join(work, "on-%d-typed-%d.png" % (k, round_)))
            dial.press(START)
            closed = False
            for _ in range(300):
                n64.frames(1, 0)
                if t.i32(t.play() + SUBMENU_OPEN) == 0:
                    closed = True
                    break
            events.append({"typed": show(text), "number": number, "phrase": show(phrase), "shown": show(shown),
                           "before": show(before), "left": show(n64.read(actor + TYPED_AT, TYPED_LEN)),
                           "closed": closed, "want": want})

        def save_at_the_gyroid(answer):
            """The gyroid asked to save, the keeper answered: what was said, and whether the game left play."""
            out = {"answer": answer, "pages": [], "choices": [], "spoken": False, "left": False, "free": False}
            mat = emu_town.MAT
            gyroids = [a for a in t.actors() if a["name"] == GYROID]
            if not gyroids:
                return out
            mine = min(gyroids, key=lambda a: (a["pos"][0] - mat[0]) ** 2 + (a["pos"][2] - mat[1]) ** 2)
            out["spoken"] = approach(t, mine)
            try:
                emu_town.follow(t, answers=(GYROID_SAVE, answer), limit=900, quiet=60, pages=out["pages"],
                                choices=out["choices"])
                there = emu_town.names(t)
                out["left"] = TITLE in there             # the title's own town, its logo over it
                out["free"] = (not out["left"] and GYROID in there and t.player_main() == emu_year.PLAYER_WAIT)
            except ValueError:                          # between two states: the pointers read are no one's
                out["left"] = True
            return out

        try:
            n64.frames(400, 0)
            n64.press(START, 3, 60)
            given, free, rounds, seen, last, still = 0, 0, 0, set(), None, 0
            result["start"] = None
            for step in range(STEPS):
                s = state()
                mine = his()
                now = sorted({a["name"] for a in mine})
                if mine:                             # the room the arena has while he is there, at its least
                    room = t.arena(syms["zelda_arena"])
                    if room and (result["room"] is None or room[0] < result["room"][0]):
                        result["room"] = list(room)
                    if result["place"] is None:
                        result["place"] = place(mine[0])
                if result["start"] is None and s["count"] >= 0 and given >= 2:
                    result["start"] = s
                key = (s["flag"], tuple(now), t.window_main(), t.player_main())
                if key != last:
                    events.append({"flag": s["flag"], "actors": now, "step": step})
                    if os.environ.get("EMU_RESET_TRACE"):
                        print("step %d frame %d: flag %d %s window %d player %d submenu %d" % (
                            step, n64.frame, s["flag"], now, t.window_main(), t.player_main(),
                            t.i32(t.play() + SUBMENU_OPEN)), file=sys.stderr, flush=True)
                    last, still = key, 0
                else:
                    still += 1
                if t.i32(t.play() + SUBMENU_OPEN) != 0:
                    n64.frames(60, 0)
                    typing(rounds)
                    rounds += 1
                    continue
                page = t.page() if t.window_main() == emu_year.WAITING else None
                if page and (page[0], page[1]) not in seen:
                    seen.add((page[0], page[1]))
                    events.append({"msg": page[0], "at": page[1], "text": emu_year.text(page[2])[:700],
                                   "problems": emu_year.problems(page[2]), "his": bool(now)})
                    if now:
                        n64.screenshot(os.path.join(work, "on-%d-page-%03d.png" % (k, len(seen))))
                if t.choice_waiting() and given < 2:
                    n64.frames(20, 0)
                    n64.press(A, 3, 30)
                    given += 1
                    continue
                waits = t.window_main() == emu_year.HIDDEN and t.player_main() == emu_year.PLAYER_WAIT
                if given >= 2 and waits and not s["flag"] and not now:
                    free += 1
                    if free >= 4:
                        result["free"] = True
                        break
                    n64.frames(15, 0)
                    continue
                free = 0
                if t.window_main() != emu_year.HIDDEN:
                    n64.press(A, 3, 22)
                elif waits and mine and still >= STALL:
                    # he waits: for the player to walk off (he stops them), or to come when called
                    n64.frames(40, DIRECTIONS["d"])
                    n64.frames(30, 0)
                    spoke = t.window_main() != emu_year.HIDDEN
                    events.append({"went": mine[0]["name"], "how": "walked off" if spoke else "went to him",
                                   "spoke": spoke or approach(t, mine[0]), "step": step})
                    still = 0
                else:
                    n64.frames(15, 0)
            result["end"] = state()
            n64.screenshot(os.path.join(work, "on-%d-end.png" % k))
            if result["free"] and PLAN[k][1] is not None:
                result["save"] = save_at_the_gyroid(PLAN[k][1])
                n64.screenshot(os.path.join(work, "on-%d-saved.png" % k))
            n64.frames(120, 0)
        except Hang as e:
            result["hang"] = str(e)
            n64.screenshot(os.path.join(work, "on-%d-hang.png" % k))
    return result


def main(argv):
    if argv[:1] == ["--power-on"]:
        k, emu_dir, rom_path, af_en, town, work = int(argv[1]), argv[2], argv[3], argv[4], argv[5], argv[6]
        result = power_on(emu_dir, rom_path, af_en, town, work, k)
        with open(os.path.join(work, "on-%d.json" % k), "w") as f:
            json.dump(result, f, indent=1)
        return 0
    emu_dir, rom_path, af_en, town, out = argv
    af_en = os.path.abspath(af_en)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    runs = []
    for k in range(len(PLAN)):
        done = subprocess.run([sys.executable, os.path.abspath(__file__), "--power-on", str(k), emu_dir, rom_path,
                               af_en, town, out], capture_output=True, text=True)
        path = os.path.join(out, "on-%d.json" % k)
        if done.returncode or not os.path.exists(path):
            check(False, "power-on %d ran (%s)" % (k, (done.stderr or done.stdout).strip()[-300:]))
            break
        runs.append(json.load(open(path)))
    report(runs)
    print("emu_reset: %s; screens and reports in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


def kept(count):
    """The count the game keeps once his actor is set: 5 or more goes back to 3 or 4 (func_8053aab0)."""
    return count if count < 5 else 3 + ((count - 5) & 1)


def report(runs):
    count, resets = 0, 0
    for run in runs:
        k = run["on"]
        reset, save = PLAN[k]
        if reset:
            count, resets = kept(count + 1), resets + 1
        pages = [e for e in run["events"] if "msg" in e]
        mine = [e for e in pages if e["his"]]
        actors = sorted({n for e in run["events"] if "actors" in e for n in e["actors"]})
        flags = {e["flag"] for e in run["events"] if "flag" in e}
        typed = [e for e in run["events"] if "typed" in e]
        went = [e for e in run["events"] if "went" in e]
        saved = run.get("save")
        every = pages + (saved["pages"] if saved else [])
        bad = ["%#x at %d: %s" % (e["msg"], e["at"], ", ".join(e["problems"])) for e in every if e["problems"]]
        end = run.get("end") or {"count": -1, "flag": -1, "mark": 0}
        print("  power-on %d: count %d, his actors %s, %d pages (%d of his: %s)%s%s" % (
            k, end["count"], actors, len(pages), len(mine), " ".join(sorted({"%#x" % e["msg"] for e in mine})),
            "; he waited and the player %s" % ", ".join(e.get("how", "?") for e in went) if went else "",
            "; typed %s" % [(e["typed"], e.get("left")) for e in typed] if typed else ""))
        check(run["hang"] is None, "power-on %d: no hang (%s)" % (k, run["hang"]))
        if not reset:
            check(run["free"] and end["count"] == count and not actors and flags == {0} and end["mark"] != 0,
                  "power-on %d, %s: no reset counted (the count is %d), he does not come, the player is free; the "
                  "start of play has set its mark" % (
                      k, "of the town as the checks make it" if k == 0 else "the play before saved and stopped",
                      end["count"]))
        else:
            check(run["free"] and end["count"] == count and 1 in flags and end["flag"] == 0 and
                  actors == [HIS[count]] and bool(mine) and all(e["spoke"] for e in went),
                  "power-on %d, the play before %s: reset %d, the count kept is %d (%s), the flag up, %s comes "
                  "(the count's is %s) and talks (%d pages%s), then the flag is down, he is gone and the player is "
                  "free" % (
                      k, "saved and went on" if PLAN[k - 1][1] == GO_ON else "never saved", resets, end["count"],
                      "the game's own way back from 5" if resets >= 5 else "one more",
                      "/".join(actors) or "nobody", HIS[count], len(mine),
                      "; he waits, and speaks when the player %s" % " and ".join(
                          sorted({e.get("how", "?") for e in went})) if went else ""))
        check(not bad, "power-on %d: its %d pages have no kana, kanji or token left (%s)" % (k, len(every), bad[:3]))
        if reset:
            room, where = run.get("room"), run.get("place")
            check(bool(room) and room[0] >= emu_year.ARENA_FLOOR and where == [True, True],
                  "power-on %d: while he is there the arena's largest free block is %s at its least (the floor "
                  "%d), and his actor and his overlay lie %s" % (
                      k, "%d bytes (%d in use)" % (room[0], room[2]) if room else "not read", emu_year.ARENA_FLOOR,
                      "inside ovl_Npc's own overlay: he takes nothing out of the arena" if where == [True, True]
                      else "not both inside ovl_Npc's own overlay (%s)" % where))
        if save is not None:
            said = [e["msg"] for e in (saved["pages"] if saved else [])]
            asked = saved["choices"] if saved else []
            check(bool(saved) and saved["spoken"] and GYROID_ASK in said and
                  [len(c) for c in asked] == [4, 2] and len(set(said)) >= 4 and
                  (saved["left"] if save == STOP else saved["free"]),
                  "power-on %d: the gyroid is asked to save (its answers: %s), the player is taken into the house "
                  "and the one who keeps the saves is told to %s (the answers: %s; %d pages: %s): %s" % (
                      k, " / ".join(asked[0]) if asked else "none",
                      "save and stop" if save == STOP else "save and go on",
                      " / ".join(asked[1]) if len(asked) > 1 else "none", len(said),
                      " ".join("%#x" % m for m in sorted(set(said))),
                      ("the game is back at its title" if saved and saved["left"] else "the game did not leave")
                      if save == STOP else
                      ("the player is free again" if saved and saved["free"] else "the player is not free")))
        if typed:
            # what followed each try: the first page of his after it
            order = [e for e in run["events"] if "typed" in e or ("msg" in e and e["his"])]
            for i, e in enumerate(order):
                if "typed" not in e:
                    continue
                after = next((x["msg"] for x in order[i + 1:] if "msg" in x), None)
                check(e["typed"] is not None and e["closed"] and e["shown"] == e["phrase"] and
                      e["left"].rstrip(" ") == e["typed"] and after == e["want"],
                      "power-on %d: he asks for phrase %d (%r on his page, the bank's %r); %r typed on the dial, "
                      "the window leaves %r; his next page is %#x (%s)" % (
                          k, e.get("number", -1), e.get("shown"), e.get("phrase"), e["typed"], e.get("left"),
                          after or 0, {WRONG: "the wrong try's", INSULT: "the insult's", RIGHT: "the right one's"}.get(
                              e.get("want"), "?")))
    asked = [r["on"] for r in runs if any("typed" in e for e in r["events"])]
    check(len(runs) == len(PLAN) and len(asked) == 2 and resets == 5 and count == 3,
          "he asks for a phrase at power-on %s: at the third reset and again at the fifth, where the game has put "
          "the count back to 3" % (", ".join(map(str, asked)) or "none"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
