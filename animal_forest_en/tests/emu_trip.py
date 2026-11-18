#!/usr/bin/env python3
"""A trip with the Controller Pak, played: out of the town by train, into another as a visitor, and home.

    tests/emu_trip.py EMU_DIR EN_ROM AF_EN_DIR ROUTE_DIR OUT_DIR

ROUTE_DIR is make route-en's: 4-houses.st (a new game, Nook showing the houses) and 1-name.st (the same new
game at its first name dial).

The game has four starts of play (m_start_data_init.c): a new game, a saved town, a new player in a town that
has players, and a player who comes with a Controller Pak (mSDI_StartInitPak). The other checks go the first
three; this one goes the fourth, and the road that leads to it. Two towns are made by the game: the route's
own, and another with other names typed at the dials (a player of the same name from a town of the same name
is taken for a resident). Then one Controller Pak goes through three power-ons, the flash changed between
them as two Game Paks would be:

  at home: an animal met, a letter written to it from a sheet of paper in the pockets, longer than the 96
  bytes the cartridge keeps of a letter's body (the en build files the rest in the save's extension, under a
  key made of the letter's own bytes: m_handbill.c); the letter stays in the pockets. The station's master,
  the first answer: he saves the player onto the Controller Pak, the town onto the Game Pak, and the train
  leaves. The game takes the traveller's things out of the save then, letters too, so no letter of the save
  holds the rest's key while the trip lasts: the extension must keep it all the same (the copy of the
  player's record for the Pak files a record of the trip, and all that was filed before it is kept:
  m_flashrom.c's mFRm_en_CopyPrivateInfo, m_handbill.c's mHandbill_en_CollectExt);

  in the other town: the greeter's one question, and the player arrives as a visitor (the player's number is
  4, the player's record is not the save's but g_foreigner_private), pockets and letter come with the Pak;
  an animal of that town is spoken to; the station's master again, and the train home. The letter's rest is
  not in that town's extension: the Controller Pak carries the cartridge's record, which is the limit of
  what a letter keeps in another town;

  at home again: the greeter copies the Pak's record into the save and the player is a resident again, the
  letter in the pockets, its rest in the extension as it was written; the gyroid saves, and the flash holds
  both.

Every page read on the way is judged as the other checks judge one (emu_year's Town.page_problems), and the
allocator's hook is in place from each power-on (tests/emu_memory.py's watch): the arena must never say no.
"""

import glob
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
import emu_job  # noqa: E402
import emu_letters  # noqa: E402
import emu_memory  # noqa: E402
import emu_psel  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_year  # noqa: E402
from afplay import DIRECTIONS, dialogue_open, name_dial_open, presses, type_on_dial, until, walk  # noqa: E402
from n64emu import N64, A, START, Hang  # noqa: E402

FG = emu_memory.FG_SCENE
PRIVATE, PRIVATE_SIZE = emu_psel.PRIVATE_AT, emu_psel.PRIVATE_SIZE
POCKETS, MAIL, MAIL_SIZE = emu_job.POCKETS, emu_job.MAIL, emu_job.MAIL_SIZE
EXISTS = 0xA74                          # PrivateInfo.exists: 0 while the player is away on a trip
PLAYER_NO, PRIVATE_PTR = 0x10003, 0x10138   # common_data: the player's number (4: a visitor), the player's record
VISITOR = 4                             # mPR_FOREIGNER_NUMBER
FOREIGNER = "g_foreigner_private"       # the record of a player who is not of the save (m_private.c)
PAPER = 0x2000                          # a sheet of paper, the first of the 64 kinds
OTHER_NAMES = ("dlru", "rdlu")          # the other town's player and town, on the dials
PUSHES = 180                            # letters typed on the dial: a body past the cartridge's 96 bytes
BODY_AT, BODY = 0x34, 96                # a Mail_c's body
MASTER = "Npc_Station_Master"
HOLD_KEY = 0x484F4C30                   # m_handbill.h: the record of a trip, + the traveller's number (0 to 3)
DAY = (2001, 4, 15)
CLOCKS = {"home": DAY + (10, 0, 0), "visit": DAY + (11, 30, 0), "back": DAY + (13, 0, 0)}

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def held(private):
    """The letters a player's record carries: [a Mail_c's bytes] (a place without one is blank)."""
    raw = private[MAIL:MAIL + 10 * MAIL_SIZE]
    return [bytes(raw[i * MAIL_SIZE:(i + 1) * MAIL_SIZE]) for i in range(10)
            if raw[i * MAIL_SIZE:i * MAIL_SIZE + 12].strip(b" ")]


def facts(private, ext):
    """What a check asks of a player's record and an extension: the things, the letters' keys, the records."""
    records = emu_letters.Ext.parse(bytes(ext)).records if ext else []
    keys = [key for key, h, b, f in records]
    trips = [key for key in keys if HOLD_KEY <= key < HOLD_KEY + 4]
    line = max((keys.index(key) for key in trips), default=-1)     # what lies before the last of them is kept
    return {"name": bytes(private[:6]).hex(), "id": bytes(private[:0x10]).hex(), "exists": private[EXISTS],
            "pockets": [v for v in struct.unpack(">15H", bytes(private[POCKETS:POCKETS + 30])) if v],
            "letters": ["%08x" % emu_letters.mail_key(m) for m in held(private)],
            "mail": [m.hex() for m in held(private)],
            "rests": {"%08x" % key: b.hex() for key, h, b, f in records if key not in trips},
            "trips": [key - HOLD_KEY for key in trips],
            "held": ["%08x" % key for key in keys[:line + 1] if key not in trips]}


def flash_facts(work, rom):
    """The flash in work, as the game left it: its first player's facts."""
    save, ext = emu_memory.flash_save(work, rom)
    return facts(save[PRIVATE:PRIVATE + PRIVATE_SIZE], ext)


def pak_facts(work, me):
    """The Controller Pak's file in work: the facts of the record that begins with the player's id, or None."""
    for path in glob.glob(os.path.join(work, "*.mpk")):
        pak = open(path, "rb").read()
        at = pak.find(bytes.fromhex(me))
        if at >= 0 and at + PRIVATE_SIZE <= len(pak):
            return facts(pak[at:at + PRIVATE_SIZE], b"")
    return None


def make_town(emu_dir, rom_path, af_en, state, out, typed):
    """A saved town from a state of the route: the houses' state as it is, or (typed: the two names) the
    first dial's with other names typed and the route's own steps from there (tools/route_newgame.py). The
    first house is chosen by the game's talk and the actors saved as the game saves them (tests/emu_town.py)."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    os.makedirs(out, exist_ok=True)
    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=90) as n64:
        n64.load_state(state)
        if typed:
            type_on_dial(n64, typed[0])
            until(n64, name_dial_open, 120, "town dial")
            type_on_dial(n64, typed[1])
            presses(n64, 200)                           # the rest of the train, the arrival
            for _ in range(3):
                walk(n64, "r", 15)
            for _ in range(4):
                walk(n64, "d", 15)
            until(n64, dialogue_open, 40, "Nook")
            presses(n64, 110)                           # Nook's welcome, the walk to the houses
        done = emu_town.settle(n64, af_en, syms)
        common = n64.read(syms["common_data"], emu_psel.RTC_TIME + 8)
    save = emu_psel.stamp(common)
    path = os.path.join(out, "town.town")
    open(path, "wb").write(save)
    return {"town": path, "settled": emu_town.settled(done), "said": emu_town.describe(done),
            "id": bytes(save[PRIVATE:PRIVATE + 0x10]).hex(), "sound": emu_save.checksum_ok(save)}


def session(emu_dir, rom_path, af_en, plan):
    """One power-on of the flash in plan["work"], the Controller Pak's file there as the last power-on left
    it. The plan: label, clock, answers (the greeter's); town (a fresh flash made of it, a sheet of paper in
    its first player's first free pocket) or flash (a flash's file copied in); steps, of meet (an animal
    spoken to), write (the letter), talk (an animal spoken to, no more asked), depart (the station's master,
    the train), save (the gyroid, to stop)."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    work, label, steps = plan["work"], plan["label"], plan["steps"]
    os.makedirs(work, exist_ok=True)
    fla = emu_save.fla_path(work, rom)
    if plan.get("town"):
        save = bytearray(open(plan["town"], "rb").read())
        flags = struct.unpack(">I", save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4])[0] & ~emu_year.JOB_BITS
        save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4] = struct.pack(">I", flags)
        if "write" in steps:                            # bought at the shop, as a player would have
            at = PRIVATE + POCKETS
            slot = next(s for s in range(15) if not save[at + 2 * s:at + 2 * s + 2].strip(b"\0"))
            save[at + 2 * slot:at + 2 * slot + 2] = struct.pack(">H", PAPER)
        image, compressed = aflz.pack(emu_psel.fix(bytes(save)), b"")
        if not compressed:
            raise SystemExit("emu_trip: %s's save does not fit a compressed slot" % label)
        emu_save.write_fla(fla, image + image)
    elif plan.get("flash"):
        shutil.copyfile(plan["flash"], fla)
    result = {"label": label, "arrived": False, "hang": None, "pages": [], "now": None, "met": None,
              "written": None, "talked": None, "left": None, "saved": None, "watch": None, "made": []}
    cd = syms["common_data"]
    with N64(emu_dir, rom, work, timeout=60, clock=tuple(plan["clock"])) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names, syms)
        pages = result["pages"]
        p = emu_job.Player(n64, t, syms, pages)

        def now():
            """The player in play: the number, whose record it is, and its facts with RAM's extension."""
            at = n64.u32(cd + PRIVATE_PTR)
            size = n64.u32(syms["mFRm_en_extSize"])
            ext = n64.read(syms["mFRm_en_ext"], size) if 0 < size <= emu_letters.EXT_CAP else b""
            who = "the save's" if at == cd + PRIVATE else "a visitor's" if at == syms[FOREIGNER] else "%#x" % at
            return dict(facts(n64.read(at, PRIVATE_SIZE), ext), number=n64.read(cd + PLAYER_NO, 1)[0], record=who)

        def animals():
            return {fg: tuple(place[:2]) for fg, place in emu_town.animals(n64.read(cd, 0x10000))}

        try:
            low, used = emu_memory.watch_soon(n64, af_en, syms)
            n64.frames(400 - used, 0)
            n64.press(START, 3, 60)
            answers = iter(plan["answers"])             # the greeter's questions, then A until the town
            for _ in range(60):
                how = p.advance(400, 12, pick=lambda count: next(answers, 0))
                if how == "free" and p.scene() == FG:
                    result["arrived"] = True
                    break
                n64.frames(30, 0)
            result["asked"] = p.asked
            n64.screenshot(os.path.join(work, label + "-town.png"))
            result["now"] = now()
            if result["arrived"] and "meet" in steps:
                homes = animals()
                fg = next(iter(homes))
                how = p.animal(fg, homes)
                result["met"] = {"animal": fg, "ended": how,
                                 "known": fg in emu_job.acquainted(n64.read(cd, 0x10000))}
            if result["arrived"] and "write" in steps:
                before = result["now"]["letters"]
                slot = list(struct.unpack(">15H", p.private(POCKETS, 30))).index(PAPER)
                p.items()
                p.hand(slot)
                p.tag(1)                                # to write a letter: whom to
                n64.frames(60, 0)
                n64.press(A, 3, 40)                     # the address book,
                n64.press(A, 3, 60)                     # its first entry
                n64.frames(40, 0)
                for n in range(PUSHES):
                    v = DIRECTIONS["urld"[n % 4]]
                    n64.frames(8, v)
                    n64.frames(6, v | A)
                    n64.frames(4, v)
                    n64.frames(8, 0)
                n64.screenshot(os.path.join(work, label + "-letter.png"))
                n64.press(START, 6, 60)
                n64.press(A, 3, 40)                     # the end's question
                n64.press(A, 3, 60)
                n64.frames(60, 0)
                p.items(False)
                after = now()
                new = [k for k in after["letters"] if k not in before]
                mail = bytes.fromhex(after["mail"][after["letters"].index(new[0])]) if len(new) == 1 else b""
                result["written"] = {"key": new[0] if len(new) == 1 else None, "mail": mail.hex(),
                                     "rest": after["rests"].get(new[0]) if len(new) == 1 else None,
                                     "paper": PAPER in after["pockets"]}
            if result["arrived"] and "talk" in steps:
                homes = animals()
                fg = next(iter(homes))
                read = len(pages)
                how = p.animal(fg, homes)
                result["talked"] = {"animal": fg, "ended": how, "pages": len(pages) - read}
            if result["arrived"] and "depart" in steps:
                result["before"] = now()
                gone, spoken = False, False
                if p.outside() and t.go_block(p.block_of("station"), set()):
                    n64.frames(30, 0)
                    master = [a for a in t.actors() if a["name"] == MASTER]
                    if master:
                        spoken, _ = p.talk(master[0])   # the first answer; the Pak, the flash, and the train
                        for _ in range(300):
                            try:
                                gone = p.scene() != FG
                            except (ValueError, struct.error):
                                gone = True
                            if gone:
                                break
                            p.advance(20, 4)
                            n64.frames(15, 0)
                n64.frames(120, 0)
                result["left"] = {"spoken": spoken, "gone": gone}
            if result["arrived"] and "save" in steps:
                result["before"] = now()
                result["saved"] = emu_memory.save_and_stop(t, emu_town.MAT, pages)
                n64.frames(120, 0)
            result["watch"] = low()
            result["made"] = emu_memory.made(result["watch"], names)
        except Hang as e:
            result["hang"] = str(e)
            n64.screenshot(os.path.join(work, label + "-hang.png"))
    result["problems"] = ["%#x at %d: %s" % (pg["msg"], pg["at"], ", ".join(pg["problems"])) for pg in result["pages"]
                          if pg["problems"]]
    result["pages"] = len(result["pages"])
    return result


def clean(run):
    """Whether a session arrived, read its pages clean, never hung, and the arena never said no."""
    return (bool(run) and run["arrived"] and not run["hang"] and not run["problems"] and run["pages"] > 0 and
            bool(run["watch"]) and run["watch"]["fails"] == 0)


def arena(run):
    seen = run and run["watch"]
    return ("the arena never said no (%d allocations, the largest free block %s at its least)" % (
        seen["calls"], seen["least"]) if seen and not seen["fails"] else "THE ARENA SAID NO" if seen else "not watched")


def report(towns, home, away, pak, visit, other, back, kept):
    """The checks. towns: {home, other}: make_town's answers; home, visit, back: the three sessions; away,
    other, kept: the flash's facts after each (the home's with its player away, the other town's, the home's
    with its player back); pak: the Controller Pak's facts after the first."""
    check(bool(towns) and all(towns.get(k) and towns[k]["settled"] and towns[k]["sound"] for k in ("home", "other")) and
          towns["home"]["id"][:24] != towns["other"]["id"][:24],
          "two towns made by the game from the route's states, the first house chosen by its talk: the route's "
          "own and one with other names typed at the dials (the players' and the towns' names differ: %s)" % (
              bool(towns) and bool(towns.get("home")) and bool(towns.get("other")) and
              towns["home"]["id"][:24] != towns["other"]["id"][:24]))
    met, letter = (home or {}).get("met"), (home or {}).get("written")
    rest = bytes.fromhex(letter["rest"]) if letter and letter.get("rest") else b""
    check(clean(home) and bool(met) and met["ended"] == "free" and met["known"] and bool(letter) and
          bool(letter["key"]) and not letter["paper"] and len(rest) > 0,
          "at home: an animal met, and a letter written to it from the sheet of paper in the pockets (the "
          "paper's tag's second entry, the address book's first entry, %d letters on the dial, the end's "
          "question): %s" % (PUSHES, "in the pockets, %d bytes of body, the %d past the cartridge's %d filed in "
                             "the extension under the letter's key" % (BODY + len(rest), len(rest), BODY)
                             if letter and letter["key"] and rest else "NOT WRITTEN AS MEANT (%s)" % (letter,)))
    left = (home or {}).get("left")
    mine = bool(pak) and bool(letter) and letter["key"] in pak["letters"]
    check(clean(home) and bool(left) and left["spoken"] and left["gone"] and bool(away) and away["exists"] == 0 and
          not away["pockets"] and not away["letters"] and mine and
          pak["pockets"] == home["before"]["pockets"],
          "the station's master, the first answer: %s" % (
              "he saves the player onto the Controller Pak (the Pak's file holds the record, its pockets and the "
              "letter) and the town onto the Game Pak, and the train leaves; in the flash the player is away, "
              "pockets and letters taken out by the game" if left and left["gone"] and away and not away["exists"]
              and mine else "NOT GONE AS THE GAME GOES (%s; away %s; the Pak %s)" % (
                  left, away and {k: away[k] for k in ("exists", "pockets", "letters")},
                  pak and {k: pak[k] for k in ("pockets", "letters")})))
    listed = bool(away) and bool(letter) and away["trips"] == [0] and letter["key"] in away["held"]
    check(bool(away) and bool(letter) and bool(rest) and away["rests"].get(letter["key"]) == letter["rest"] and listed,
          "while the player is away no letter of the save holds the rest's key: %s" % (
              "the extension keeps it all the same, as it was written, before the record of the player's trip"
              if away and letter and away["rests"].get(letter["key"]) == letter["rest"] and listed
              else "THE REST IS GONE FROM THE FLASH (the extension's rests %s, the records of a trip %s, kept "
                   "by them %s)" % (away and sorted(away["rests"]), away and away["trips"], away and away["held"])))
    there = (visit or {}).get("now")
    check(clean(visit) and visit["asked"] == 1 and bool(there) and there["number"] == VISITOR and
          there["record"] == "a visitor's" and bool(home) and there["id"] == home["now"]["id"] and
          there["pockets"] == home["before"]["pockets"] and bool(letter) and there["mail"] == [letter["mail"]],
          "in the other town, the Controller Pak in the pad: %s; %s" % (
              "the greeter's one question, and the player arrives as a visitor (the player's number %d, the "
              "record not the save's), pockets and letter as the Pak holds them" % there["number"]
              if there and there["number"] == VISITOR and there["record"] == "a visitor's"
              else "NOT A VISITOR (%s)" % (there and {k: there[k] for k in ("number", "record", "pockets", "letters")},),
              arena(visit)))
    spoke = (visit or {}).get("talked")
    check(clean(visit) and bool(spoke) and spoke["ended"] == "free" and spoke["pages"] > 0,
          "an animal of the other town spoken to as a visitor: %s" % (
              "%d pages, clean" % spoke["pages"] if spoke and spoke["ended"] == "free" else "NOT SPOKEN TO (%s)" % (spoke,)))
    check(bool(there) and bool(letter) and letter["key"] not in there["rests"] and not there["trips"] and
          bool(other) and letter["key"] not in other["rests"] and not other["trips"],
          "the letter's rest is not in the other town's extension, in RAM or in its flash: the Controller Pak "
          "carries the cartridge's record, a letter's first %d bytes of body (the limit of a letter in another "
          "town)" % BODY)
    left = (visit or {}).get("left")
    check(clean(visit) and bool(left) and left["spoken"] and left["gone"] and bool(other) and bool(towns) and
          bool(towns.get("other")) and other["id"] == towns["other"]["id"] and other["exists"] == 1,
          "the other town's station's master, the first answer: %s" % (
              "the player is saved onto the Controller Pak again, the train leaves, and the town's own player is "
              "in its flash as before" if left and left["gone"] and other and other["exists"] == 1
              else "NOT GONE AS THE GAME GOES (%s)" % (left,)))
    here = (back or {}).get("now")
    check(clean(back) and back["asked"] == 1 and bool(here) and here["number"] == 0 and
          here["record"] == "the save's" and here["exists"] == 1 and bool(home) and
          here["pockets"] == home["before"]["pockets"] and bool(letter) and here["mail"] == [letter["mail"]],
          "at home again: %s; %s" % (
              "the greeter's one question, the Pak's record copied into the save, and the player is a resident "
              "again (the player's number 0), pockets and letter back" if here and here["number"] == 0 and
              here["record"] == "the save's" else "NOT HOME (%s)" % (
                  here and {k: here[k] for k in ("number", "record", "exists", "pockets", "letters")},),
              arena(back)))
    check(bool(here) and bool(letter) and bool(rest) and here["rests"].get(letter["key"]) == letter["rest"] and
          not here["trips"],
          "the letter is whole again: %s" % (
              "its %d bytes past the cartridge's are in the extension under its key, as they were written, and "
              "the record of the trip is gone" % len(rest)
              if here and letter and here["rests"].get(letter["key"]) == letter["rest"] and not here["trips"]
              else "IT IS NOT (the extension's rests %s, the records of a trip %s): THE LETTER ENDS AT ITS "
                   "%dTH BYTE" % (here and sorted(here["rests"]), here and here["trips"], BODY)))
    check(emu_memory.saved(back) and bool(kept) and bool(letter) and kept["letters"] == [letter["key"]] and
          kept["rests"].get(letter["key"]) == letter["rest"] and not kept["trips"] and kept["exists"] == 1,
          "the player's gyroid saves and the game leaves for its title: %s" % (
              "the flash holds the letter and its rest" if kept and letter and
              kept["rests"].get(letter["key"]) == letter["rest"] else "THE FLASH DOES NOT HOLD THE LETTER WHOLE (%s)" % (
                  kept and {"letters": kept["letters"], "rests": sorted(kept["rests"]), "trips": kept["trips"]},)))
    runs = [r for r in (home, visit, back) if r]
    check(len(runs) == 3 and all(clean(r) for r in runs),
          "on the way %d pages read, %s; no hang; %s" % (
              sum(r["pages"] for r in runs),
              "clean" if not any(r["problems"] for r in runs) else [r["problems"][:2] for r in runs if r["problems"]],
              "; ".join("%s: %s" % (r["label"], arena(r)) for r in runs)))


def main(argv):
    if argv[:1] == ["--town"]:
        emu_dir, rom_path, af_en, state, out = argv[1:6]
        print(json.dumps(make_town(emu_dir, rom_path, af_en, state, out, argv[6:8])))
        return 0
    if argv[:1] == ["--session"]:
        print(json.dumps(session(argv[1], argv[2], argv[3], json.load(open(argv[4])))))
        return 0
    emu_dir, rom_path, af_en, route, out = argv
    af_en, route = os.path.abspath(af_en), os.path.abspath(route)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    rom = open(rom_path, "rb").read()

    def child(*args):
        r = subprocess.run([sys.executable, os.path.abspath(__file__)] + list(args), capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
        if r.returncode or not lines:
            print(r.stdout[-1500:], r.stderr[-1500:])
            return None
        return json.loads(lines[-1])

    def run(label, **plan):
        path = os.path.join(out, "plan-%s.json" % label)
        json.dump(dict(plan, label=label, work=work, clock=CLOCKS[label]), open(path, "w"))
        return child("--session", emu_dir, rom_path, af_en, path)

    print("emu_trip: %s, a trip with the Controller Pak from %s" % (rom_path, route))
    towns = {"home": child("--town", emu_dir, rom_path, af_en, os.path.join(route, "4-houses.st"),
                           os.path.join(out, "home")),
             "other": child("--town", emu_dir, rom_path, af_en, os.path.join(route, "1-name.st"),
                            os.path.join(out, "other"), *OTHER_NAMES)}
    work = os.path.join(out, "pak")                     # one pad's Controller Pak through the three power-ons
    home = away = pak = visit = other = back = kept = None
    if towns["home"] and towns["other"]:
        home = run("home", town=towns["home"]["town"], answers=[0, 0], steps=["meet", "write", "depart"])
    if home and home["left"] and home["left"]["gone"]:
        away, pak = flash_facts(work, rom), pak_facts(work, home["now"]["id"])
        fla = emu_save.fla_path(work, rom)
        shutil.copyfile(fla, os.path.join(out, "home-away.fla"))
        os.remove(fla)                                  # the other Game Pak
        visit = run("visit", town=towns["other"]["town"], answers=[0], steps=["talk", "depart"])
    if visit and visit["left"] and visit["left"]["gone"]:
        other = flash_facts(work, rom)
        back = run("back", flash=os.path.join(out, "home-away.fla"), answers=[0], steps=["save"])
    if back and back["saved"]:
        kept = flash_facts(work, rom)
    report(towns, home, away, pak, visit, other, back, kept)
    json.dump({"towns": towns, "home": home, "away": away, "pak": pak, "visit": visit, "other": other,
               "back": back, "kept": kept}, open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_trip: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
