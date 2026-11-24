#!/usr/bin/env python3
"""One Controller Pak between the en build and the cartridge itself, played both ways.

    tests/emu_pakjp.py EMU_DIR EN_ROM AF_EN_DIR EN_ROUTE_DIR JP_ROM AF_DIR JP_ROUTE_DIR OUT_DIR

EN_ROUTE_DIR is make route-en's, JP_ROUTE_DIR the same route played on the matching ROM (tools/route_newgame.py:
4-houses.st, a new game with Nook showing the houses); AF_DIR is the matching tree, whose symbols the
cartridge's sessions are read with.

A Controller Pak is the cartridge's own thing: a player saved onto one by the en build can be taken to a town
of the untranslated game, and a player of that game to a town of the en build. Nothing of the en build's is on
the Pak that the cartridge reads (the player's record is the cartridge's, byte for byte; the rests of the long
letters lie in bytes of the Pak's record that the game never reads: tests/emu_trip.py), so both must go as a
trip between two cartridges goes. tests/emu_trip.py plays the trip between two towns of the en build; here
the other town is the cartridge's, made by the game from its own route, and one Pak goes through six
power-ons, its file handed from one console to the other between them:

  the en build's player: at home an animal met and a letter written, longer than the cartridge keeps; the
  station's master, the train. In the cartridge's town the player arrives as a visitor, by name, with the
  pockets and the letter as the Pak holds them (the cartridge's 96 bytes of it); an animal there is spoken
  to, and the station's master saves the player onto the Pak again: the bytes the en build packed the
  letter's rest into are as they were. Home again in the en build, the letter is whole and the gyroid saves;

  the cartridge's player: its town's station's master and the train; in the en build's town the player
  arrives as a visitor, the name's kana drawn among the English (the font keeps the cartridge's glyphs),
  every page otherwise clean and the arena never refusing; an animal spoken to, the station's master, the
  train; and back in the cartridge's town the player is a resident again and the gyroid saves.

The cartridge's sessions are played without the allocator's hook and their pages, which are Japanese, are
counted and not judged (emu_trip's session, plain). A choice is answered by its place, never by its words.
"""

import json
import os
import re
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "emu"))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, HERE)

import emu_psel  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_trip  # noqa: E402
import emu_year  # noqa: E402
from n64emu import N64  # noqa: E402

HOLE, HOLE_SIZE = 0x80700000, 0x1000    # the hook's place on the cartridge: osMemSize is 4 MB, the emulator has 8
DAY = (2001, 4, 15)
CLOCKS = {"home": DAY + (10, 0, 0), "visit-jp": DAY + (11, 30, 0), "back": DAY + (13, 0, 0),
          "home-jp": DAY + (14, 0, 0), "visit-en": DAY + (15, 0, 0), "back-jp": DAY + (16, 0, 0)}
KANA = re.compile(r"Japanese \{([0-9a-f]{2})\}")       # emu_year's word for a kana in a page, and its code

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def make_jp_town(emu_dir, rom_path, af, state, out):
    """A saved town of the cartridge itself from its route's houses' state: the first house chosen by the
    game's talk, the actors saved as the game saves them before a save (tests/emu_town.py's hook, placed in
    RAM the game does not use). {town, id, sound, houses}."""
    af = os.path.abspath(af)
    rom = open(rom_path, "rb").read()
    elf = os.path.join(af, "build", "animalforest-jp.elf")
    syms = emu_save.elf_symbols(elf)
    syms["mMsg_window"] = syms[emu_trip.JP_WINDOW]
    os.makedirs(out, exist_ok=True)
    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=90) as n64:
        n64.load_state(state)
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mMsg_window"], emu_year.actor_names(af), syms)
        chosen = emu_town.choose_house(t)
        mttime = syms["mTM_time"]
        words = struct.unpack(">II", n64.read(mttime, 8))
        blob, stub, ctl = emu_save.build_hook(af, elf, HOLE, HOLE_SIZE, words, mttime, emu_town.HOOK_C)
        emu_save.write_code(n64, HOLE, blob)
        emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
        n64.write(ctl, struct.pack(">ii", 1, 0))
        for _ in range(30):
            n64.frames(1, 0)
            if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                break
        save = n64.read(syms["common_data"], 0x10000)
        items = emu_town.block_items(save, emu_town.PLAYERS_BLOCK)
        common = n64.read(syms["common_data"], emu_psel.RTC_TIME + 8)
    stamped = emu_psel.stamp(common)
    path = os.path.join(out, "jp.town")
    open(path, "wb").write(stamped)
    return {"town": path, "chosen": bool(chosen), "id": bytes(stamped[emu_trip.PRIVATE:emu_trip.PRIVATE + 0x10]).hex(),
            "sound": emu_save.checksum_ok(stamped),
            "houses": [sum(1 for i in items if i in kind) for kind in (emu_town.HOUSES, emu_town.MAILBOXES,
                                                                      emu_town.GYROIDS)]}


def pak_of(path, me):
    """The rests packed after the Controller Pak's record of the player whose id is me, in the Pak's file at
    path (emu_trip.packed), or None when the file has no such record."""
    pak = open(path, "rb").read() if os.path.exists(path) else b""
    at = pak.find(bytes.fromhex(me)) if me else -1
    if at < emu_trip.PASSPORT_PRIVATE:
        return None
    record = at - emu_trip.PASSPORT_PRIVATE
    return emu_trip.packed(pak[record + emu_trip.PASSPORT_RESTS:record + emu_trip.PASSPORT_SIZE])


def played(run):
    """A session of the cartridge's: it arrived and read pages, and never hung (its pages are not judged)."""
    return bool(run) and run["arrived"] and not run["hang"] and run["pages"] > 0


def names_only(run, me):
    """Whether every fault of a session's pages is a kana of the visitor's own names (me: the id, whose first
    twelve bytes are the player's name and the town's)."""
    mine = {"%02x" % b for b in bytes.fromhex(me)[:12] if b != 0x20} if me else set()
    return all(KANA.search(p) and KANA.search(p).group(1) in mine for p in run["problems"])


def report(towns, home, visit_jp, packs, back, home_jp, visit_en, back_jp):
    """The checks. towns: {home, jp}; the six sessions; packs: the rests packed in the Pak's record when the
    en build's player left home and when the cartridge wrote the record again."""
    en_id, jp_id = (((towns or {}).get(k) or {}).get("id") for k in ("home", "jp"))
    check(bool(en_id) and bool(jp_id) and en_id[:24] != jp_id[:24] and towns["home"]["settled"] and
          towns["home"]["sound"] and towns["jp"]["chosen"] and towns["jp"]["sound"] and towns["jp"]["houses"] == [4, 4, 4],
          "two towns made by the game, each from its own route's houses: the en build's and the cartridge's (the "
          "players' and the towns' names differ: %s; the cartridge's saved with its 4 houses, mailboxes and "
          "gyroids: %s)" % (bool(en_id) and bool(jp_id) and en_id[:24] != jp_id[:24],
                            bool(towns) and bool(towns.get("jp")) and towns["jp"]["houses"]))
    letter, left = (home or {}).get("written"), (home or {}).get("left")
    rest = bytes.fromhex(letter["rest"]) if letter and letter.get("rest") else b""
    check(emu_trip.clean(home) and bool(letter) and bool(letter["key"]) and len(rest) > 0 and bool(left) and
          left["gone"],
          "the en build's player leaves home: %s" % (
              "a letter written there, %d bytes of body (%d past the cartridge's), and the station's master's "
              "train" % (emu_trip.BODY + len(rest), len(rest))
              if letter and rest and left and left["gone"] else "NOT GONE WITH A LONG LETTER (%s; %s)" % (letter, left)))
    there, spoke, left = ((visit_jp or {}).get(k) for k in ("now", "talked", "left"))
    name = bytes.fromhex(en_id)[:6].decode("latin-1").strip() if en_id else ""
    named = bool(name) and any(name in text for _, text, _ in (visit_jp or {}).get("said") or [])
    check(played(visit_jp) and bool(there) and there["number"] == emu_trip.VISITOR and
          there["record"] == "a visitor's" and there["id"] == en_id and bool(home) and
          there["pockets"] == home["before"]["pockets"] and bool(letter) and there["mail"] == [letter["mail"]] and
          named and bool(spoke) and spoke["ended"] == "free" and spoke["pages"] > 0 and bool(left) and left["gone"],
          "in the cartridge's town, the Controller Pak in its pad: %s" % (
              "the player arrives as a visitor (the player's number %d, the record not the save's) and is "
              "called by name; pockets and letter as the Pak holds them, the cartridge's bytes of the letter; "
              "an animal spoken to (%d pages); the station's master, the train (%d pages read, Japanese, not "
              "judged)" % (there["number"], spoke["pages"], visit_jp["pages"])
              if played(visit_jp) and there and there["number"] == emu_trip.VISITOR and named and spoke and left
              and left["gone"] else "NOT AS A VISITOR GOES (%s; named %s; %s; %s)" % (
                  there and {k: there[k] for k in ("number", "record", "pockets", "letters")}, named, spoke, left)))
    before, after = (packs or (None, None))[:2]
    check(bool(letter) and bool(before) and letter["key"] in before and before == after,
          "the bytes after the Pak's record's fields: %s" % (
              "the cartridge wrote the record anew when the visitor left, and they are as the en build packed "
              "them, the letter's rest in them" if letter and before and letter["key"] in before and before == after
              else "NOT KEPT (packed at home %s; after the cartridge's write %s)" % (before, after)))
    here = (back or {}).get("now")
    check(emu_trip.clean(back) and bool(here) and here["number"] == 0 and here["record"] == "the save's" and
          here["exists"] == 1 and bool(letter) and here["mail"] == [letter["mail"]] and bool(rest) and
          here["rests"].get(letter["key"]) == letter["rest"] and not here["trips"] and
          bool(back.get("saved")) and back["saved"]["left"],
          "home again in the en build: %s" % (
              "a resident again, the letter in the pockets and whole, its %d bytes past the cartridge's under "
              "its key as they were written; the gyroid saves" % len(rest)
              if here and letter and here["rests"].get(letter["key"]) == letter["rest"]
              else "NOT HOME WITH THE LETTER WHOLE (%s)" % (
                  here and {k: here[k] for k in ("number", "record", "exists", "letters", "trips")},)))
    mine, left = ((home_jp or {}).get(k) for k in ("now", "left"))
    check(played(home_jp) and bool(mine) and mine["id"] == jp_id and mine["number"] == 0 and bool(left) and
          left["spoken"] and left["gone"],
          "the cartridge's player leaves its town: %s" % (
              "the station's master, the first answer, the train (%d pages, Japanese)" % home_jp["pages"]
              if played(home_jp) and left and left["gone"] else "NOT GONE (%s; %s)" % (
                  mine and {k: mine[k] for k in ("id", "number", "record")}, left)))
    there, spoke, left = ((visit_en or {}).get(k) for k in ("now", "talked", "left"))
    faults = (visit_en or {}).get("problems") or []
    watched = bool(visit_en) and bool(visit_en["watch"]) and visit_en["watch"]["fails"] == 0
    check(bool(visit_en) and visit_en["arrived"] and not visit_en["hang"] and watched and bool(there) and
          there["number"] == emu_trip.VISITOR and there["record"] == "a visitor's" and there["id"] == jp_id and
          bool(faults) and names_only(visit_en, jp_id) and bool(spoke) and spoke["ended"] == "free" and
          spoke["pages"] > 0 and bool(left) and left["gone"],
          "in the en build's town, the same Pak: %s" % (
              "the cartridge's player arrives as a visitor (the player's number %d); %d pages read in English, "
              "the only kana in them the visitor's own names' (%d places); an animal spoken to (%d pages); the "
              "station's master, the train; %s" % (
                  there["number"], visit_en["pages"], len(faults), spoke["pages"], emu_trip.arena(visit_en))
              if visit_en and there and there["number"] == emu_trip.VISITOR and faults and
              names_only(visit_en, jp_id) and spoke and left and left["gone"] and watched
              else "NOT AS A VISITOR GOES, OR A PAGE WITH A FAULT (%s; %s; %s; %s)" % (
                  there and {k: there[k] for k in ("number", "record", "id")}, spoke, left, faults[:4])))
    here = (back_jp or {}).get("now")
    check(played(back_jp) and bool(here) and here["id"] == jp_id and here["number"] == 0 and
          here["record"] == "the save's" and here["exists"] == 1 and bool(back_jp.get("saved")) and
          back_jp["saved"]["left"],
          "back in the cartridge's town: %s" % (
              "the player is a resident again, and the gyroid saves (%d pages, Japanese)" % back_jp["pages"]
              if played(back_jp) and here and here["number"] == 0 and back_jp.get("saved") and
              back_jp["saved"]["left"] else "NOT HOME (%s; %s)" % (
                  here and {k: here[k] for k in ("id", "number", "record", "exists")},
                  back_jp and back_jp.get("saved"))))
    runs = [home, visit_jp, back, home_jp, visit_en, back_jp]
    all_played = all(runs) and all(r["arrived"] and not r["hang"] and r["pages"] > 0 for r in runs)
    check(all_played and emu_trip.clean(home) and emu_trip.clean(back),
          "the six power-ons: %s" % (
              "each arrived in its town and none hung; the en build's own two clean, %d pages; the cartridge's "
              "three read %d pages" % (
                  home["pages"] + back["pages"], sum(r["pages"] for r in (visit_jp, home_jp, back_jp)))
              if all_played else "NOT ALL PLAYED (%s)" % (
                  [r and {k: r[k] for k in ("label", "arrived", "hang")} for r in runs],)))


def main(argv):
    if argv[:1] == ["--town-jp"]:
        print(json.dumps(make_jp_town(*argv[1:6])))
        return 0
    emu_dir, en_rom, af_en, en_route, jp_rom, af, jp_route, out = argv
    af_en, af, en_route, jp_route = (os.path.abspath(p) for p in (af_en, af, en_route, jp_route))
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    sides = {"en": (en_rom, af_en), "jp": (jp_rom, af)}
    work = os.path.join(out, "pak")                     # both consoles' files, each cartridge's under its name

    def child(script, *args):
        r = subprocess.run([sys.executable, os.path.join(HERE, script)] + list(args), capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
        if r.returncode or not lines:
            print(r.stdout[-1500:], r.stderr[-1500:])
            return None
        return json.loads(lines[-1])

    def pak(side):
        return emu_trip.cart_file(work, open(sides[side][0], "rb").read(), ".mpk")

    def hand_over(src, dst):
        """The Pak taken out of one console's pad and put into the other's."""
        shutil.copyfile(pak(src), pak(dst))

    def run(label, side, **plan):
        path = os.path.join(out, "plan-%s.json" % label)
        json.dump(dict(plan, label=label, work=work, clock=CLOCKS[label], plain=side == "jp", said=True),
                  open(path, "w"))
        return child("emu_trip.py", "--session", emu_dir, sides[side][0], sides[side][1], path)

    print("emu_pakjp: %s and %s, one Controller Pak between them" % (en_rom, jp_rom))
    towns = {"home": child("emu_trip.py", "--town", emu_dir, en_rom, af_en, os.path.join(en_route, "4-houses.st"),
                           os.path.join(out, "home")),
             "jp": child("emu_pakjp.py", "--town-jp", emu_dir, jp_rom, af, os.path.join(jp_route, "4-houses.st"),
                         os.path.join(out, "jp"))}
    home = visit_jp = back = home_jp = visit_en = back_jp = None
    packs = []
    if towns["home"] and towns["jp"]:
        home = run("home", "en", town=towns["home"]["town"], answers=[0, 0], steps=["meet", "write", "depart"])
    if home and home["left"] and home["left"]["gone"]:
        packs.append(pak_of(pak("en"), towns["home"]["id"]))
        hand_over("en", "jp")
        visit_jp = run("visit-jp", "jp", town=towns["jp"]["town"], answers=[0], steps=["talk", "depart"])
    if visit_jp and visit_jp["left"] and visit_jp["left"]["gone"]:
        packs.append(pak_of(pak("jp"), towns["home"]["id"]))
        hand_over("jp", "en")
        back = run("back", "en", answers=[0], steps=["save"])
    if back and back["saved"]:
        hand_over("en", "jp")                           # the Pak, its traveller home, goes to the other console
        home_jp = run("home-jp", "jp", answers=[0, 0], steps=["depart"])
    if home_jp and home_jp["left"] and home_jp["left"]["gone"]:
        hand_over("jp", "en")
        visit_en = run("visit-en", "en", answers=[0], steps=["talk", "depart"])
    if visit_en and visit_en["left"] and visit_en["left"]["gone"]:
        hand_over("en", "jp")
        back_jp = run("back-jp", "jp", answers=[0], steps=["save"])
    report(towns, home, visit_jp, packs, back, home_jp, visit_en, back_jp)
    json.dump({"towns": towns, "home": home, "visit-jp": visit_jp, "packs": packs, "back": back, "home-jp": home_jp,
               "visit-en": visit_en, "back-jp": back_jp}, open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_pakjp: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
