#!/usr/bin/env python3
"""The room the game has left in the en build, in a town as full as a town gets, and indoors.

    tests/emu_memory.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR [--jobs N] [--only LABEL ...]

Play gives the zelda arena what its state's heap has left, and every byte the translation adds to code comes
off it (reference/NOTES.md, "The room the game has left"). An actor's overlay that finds no room there stops
the game (m_actor.c: "ovlmgr: Out of Memory", through the fault handler), on the cartridge as here; the
cartridge has `code_en`'s size more. tests/emu_year.py reads the arena on its tour of a young town (two
players, the six animals a town starts with). This check does it where the town is as full as it gets:

  1. STATE (make route-en's 4-houses.st) is made a saved town by tests/emu_town.py: the first house chosen by
     the game's talk, the town grown to its 15 animals by the game's own mNpc_Grow and mNpc_InitNpcData (each
     with a lot and a house), the actors saved as the game saves them; four players, each with a house;
  2. that town is powered on at each of the year's 21 dates and times (tests/emu_year.py's EVENTS), each in
     a process of its own, and every block of its 5 x 6 is walked into: in each the arena's nodes are walked
     and its largest free block noted, and which of the actors that come and go outdoors are there (WATCH:
     the snowballs of the winter, which a block has or has not from one day to the next, a balloon, the
     postman, the passing trader's tent, a fish let go, an umbrella). The least of each date must stay over
     FLOOR, and FLOOR is more than all of WATCH's overlays together (their sizes read from the game's own
     table of actors): whichever of them come at once into the fullest block, they fit;
  3. indoors, on a plain afternoon, each in a process of its own: the player's house as it is, and the same
     house with a different gyroid on each of its 16 places (put in the saved town's room before the power-on:
     the game gives a player no furniture to place here); the shop, the post office, the police box and the
     house of an animal that is in, each gone into at its door, where its own code looks for the player
     (BUILDINGS). The arena's largest free block in each must stay over INDOORS_FLOOR.

The furniture's code is loaded into the arena piece by piece (ovl_My_Room, func_8093678C_jp: zelda_malloc
and ovlmgr_Load for each kind's own little overlay, by a table of 947 at D_80947638_jp, 536 different ones;
the 127 gyroids share one). All of them together, each counted once, are summed from the build's table and
held under what a room has free: whatever is put in a room, its code fits.
"""

import concurrent.futures
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
import emu_psel  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_year  # noqa: E402
from n64emu import N64, A, Hang, stick  # noqa: E402

FLOOR = emu_year.ARENA_FLOOR    # a town block's largest free block at least: the year check's floor, held
                                # here to be more than WATCH's overlays together
INDOORS_FLOOR = 0x40000         # a room's: 256 KB
PLAYERS = 4
AFTERNOON = (2002, 5, 15, 14, 0, 0)
HOME_LAYER = emu_town.HOMES_AT + 0x38          # the first home's room: 16 x 16 items
WALL, FURNITURE = 0xFFFE, 0x1000                # a room's wall; the first piece of furniture (4 names each)
GYROIDS = range(364, 491)                       # the furniture that are gyroids (they share an overlay)
FTR_TABLE, FTR_COUNT = "D_80947638_jp", 947    # ovl_My_Room's table of the furniture's overlays
DOORS = (((48, 48), (-57, 57)), ((-48, 48), (57, 57)), ((0, 60), (0, 80)), ((0, 40), (0, 80)))
# A building gone into: its actor outside, an actor only its room has, where the player is put from the actor
# (x, z) and pushed (the stick), and whether the door wants A. The places are where each building's own code
# looks for the player: the post office's, within 19 of (-40, +50), turned between east and north, and A
# (func_80A08670_jp); the police box's, within 33 of (+24, +40), turned between north and west, no button
# (func_80A07C2C_jp); an animal's house, the unit in front of the player the house's own, and A
# (func_80A040B4_jp: the animal must be in, or the door only says so); the shop's found by trying
BUILDINGS = {
    "shop": ("Shop", "Shop_Indoor", DOORS, True),
    "post-office": ("Post_Office", "Room_Sunshine_Posthouse", (((-52, 62), (57, 57)),), True),
    "police-box": ("Police_Box", "Room_Sunshine_Police", (((44, 60), (-57, 57)),), False),
    "animal-house": ("House", "Arrange_Room", (((0, 70), (0, 80)),), True),
}
ROOMS = ("house", "house-gyroids") + tuple(BUILDINGS)
# the actors that come and go outdoors, whose overlays a block may have to take on top of what it holds
WATCH = ("Snowman", "Fuusen", "Npc_Post_Man", "Ev_Broker2", "Gyo_Release", "T_Umbrella")
ACTOR_TABLE, ACTOR_ENTRY = "actor_dlftbls", 0x20   # the game's table of actors: an overlay's vram start and end at 8

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def furniture_bytes(af_en):
    """(all the furniture's overlays, each counted once; the largest one) from the en build's image."""
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    rom = open(os.path.join(af_en, "build", "animalforest-jp.z64"), "rb").read()
    at = syms["ovl_My_Room_ROM_START"] + syms[FTR_TABLE] - syms["ovl_My_Room_VRAM"]
    sizes = {}
    for i in range(FTR_COUNT):
        _, _, start, end = struct.unpack(">4I", rom[at + i * 16:at + i * 16 + 16])
        sizes[start] = (end - start + 7) & ~7
    return sum(sizes.values()), max(sizes.values())


def room_with_gyroids(town):
    """The town with a different gyroid on each place inside the first home's walls. (the town, how many)"""
    save = bytearray(town)
    count = 0
    for z in range(16):
        row = struct.unpack(">16H", save[HOME_LAYER + z * 32:HOME_LAYER + z * 32 + 32])
        for x in range(16):
            if row[x] != WALL and WALL in row[:x] and WALL in row[x + 1:] and not 0x4000 <= row[x] < 0x5000 \
                    and row[x] != 0x1F3F and count < len(GYROIDS):
                at = HOME_LAYER + (z * 16 + x) * 2
                save[at:at + 2] = struct.pack(">H", FURNITURE + GYROIDS[count] * 4)
                count += 1
    return emu_psel.fix(bytes(save)), count


def make_town(emu_dir, rom_path, af_en, state, out):
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    with N64(emu_dir, rom, os.path.join(out, "state"), timeout=120) as n64:
        n64.load_state(state)
        done = emu_town.settle(n64, af_en, syms, more=True)
        common = n64.read(syms["common_data"], emu_psel.RTC_TIME + 8)
        done["watch"] = {}
        for number, name in names.items():           # the overlays of WATCH, by the game's table of actors
            if name in WATCH:
                start, end = struct.unpack(">2I", n64.read(syms[ACTOR_TABLE] + number * ACTOR_ENTRY + 8, 8))
                done["watch"][name] = end - start
    open(os.path.join(out, "grown.town"), "wb").write(emu_psel.town(emu_psel.stamp(common), PLAYERS))
    return done


def tour(emu_dir, rom_path, af_en, town, out, label):
    """The town powered on at the event's date, every block walked into: a dict for the report."""
    date, hm = next((d, h) for lb, d, h in emu_year.EVENTS if lb == label)
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    work = os.path.join(out, label)
    os.makedirs(work, exist_ok=True)
    emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(town))
    result = {"label": label, "date": "%04d-%02d-%02d %02d:%02d" % (date + hm), "arrived": False, "hang": None,
              "blocks": {}, "least": None, "unreached": [], "stops": []}
    with N64(emu_dir, rom, work, timeout=60, clock=date + hm + (0,)) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en))
        try:
            result["arrived"] = emu_year.arrive(t)
            blocked = set()
            order = [(bx if bz % 2 else 6 - bx, bz) for bz in range(1, 7) for bx in range(1, 6)]
            for b in order:
                if not t.go_block(b, blocked):
                    result["unreached"].append("%d,%d" % b)
                    continue
                room = t.arena(syms["zelda_arena"])
                here = [a["name"] for a in t.actors()]
                entry = {"largest": room[0] if room else -1, "free": room[1] if room else -1,
                         "used": room[2] if room else -1, "animals": here.count("Normal_Npc"),
                         "houses": here.count("House") + here.count("MyHouse"),
                         "there": sorted(set(here) & set(WATCH))}
                result["blocks"]["%d,%d" % b] = entry
                if result["least"] is None or entry["largest"] < result["least"]["largest"]:
                    result["least"] = dict(entry, block="%d,%d" % b)
            result["stops"] = list(t.stops)         # talks that started by themselves, played through
            n64.screenshot(os.path.join(work, "toured.png"))
        except Hang as e:
            result["hang"] = str(e)
    return result


def indoors(emu_dir, rom_path, af_en, town, out, label):
    """The town powered on on a plain afternoon; the player's house, or a building, gone into: its arena."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    work = os.path.join(out, label)
    os.makedirs(work, exist_ok=True)
    save = open(town, "rb").read()
    result = {"label": label, "arrived": False, "inside": False, "hang": None, "placed": 0, "tried": 0}
    if label == "house-gyroids":
        save, result["placed"] = room_with_gyroids(save)
        town = os.path.join(work, "room.town")
        open(town, "wb").write(save)
    emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(town))
    with N64(emu_dir, rom, work, timeout=60, clock=AFTERNOON) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en))
        try:
            result["arrived"] = emu_year.arrive(t)
            result["outside"] = t.arena(syms["zelda_arena"])
            if label.startswith("house"):
                t.drop(emu_town.MAT[0], emu_town.MAT[1], 40)
                n64.frames(15, emu_town.INTO_DOOR)
                n64.frames(3, emu_town.INTO_DOOR | A)
                want = emu_town.INSIDE
            else:
                outside, want, doors, press = BUILDINGS[label]
                for b in [(bx, bz) for bz in range(1, 7) for bx in range(1, 6)]:    # the block it stands in
                    if want in emu_town.names(t) or not t.go_block(b, set()):
                        continue
                    for place in [a for a in t.actors() if a["name"] == outside]:
                        for (dx, dz), push in doors:
                            if want in emu_town.names(t):
                                break
                            result["tried"] += 1
                            t.drop(place["pos"][0] + dx, place["pos"][2] + dz, 30)
                            n64.frames(15, stick(*push))
                            n64.frames(3, stick(*push) | (A if press else 0))
                            n64.frames(30, 0 if press else stick(*push))
                            emu_town.follow(t, quiet=12)    # a keeper's welcome at the door; a house's word
                            for _ in range(40):             # that nobody is in
                                if want in emu_town.names(t):
                                    break
                                n64.frames(15, 0)
            for _ in range(60):
                n64.frames(15, 0)
                if want in emu_town.names(t) and t.player_main() == emu_year.PLAYER_WAIT:
                    break
            emu_town.follow(t, quiet=12)
            n64.frames(120, 0)
            result["inside"] = want in emu_town.names(t)
            result["arena"] = t.arena(syms["zelda_arena"])
            result["actors"] = sorted(emu_town.names(t))
            n64.screenshot(os.path.join(work, "inside.png"))
        except Hang as e:
            result["hang"] = str(e)
    return result


def main(argv):
    if argv[:1] == ["--town"]:
        print(json.dumps(make_town(*argv[1:6])))
        return 0
    if argv[:1] == ["--tour"]:
        print(json.dumps(tour(*argv[1:7])))
        return 0
    if argv[:1] == ["--indoors"]:
        print(json.dumps(indoors(*argv[1:7])))
        return 0
    only, jobs = [], 1
    while "--only" in argv:
        i = argv.index("--only")
        only.append(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    if "--jobs" in argv:
        i = argv.index("--jobs")
        jobs = int(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    emu_dir, rom_path, af_en, state, out = argv
    af_en = os.path.abspath(af_en)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)

    def child(*args):
        r = subprocess.run([sys.executable, os.path.abspath(__file__)] + list(args), capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
        if r.returncode or not lines:
            print(r.stdout[-1500:], r.stderr[-1500:])
            return None
        return json.loads(lines[-1])

    done = child("--town", emu_dir, rom_path, af_en, state, out)
    check(bool(done) and emu_town.settled(done, True), "the town: %s" % (emu_town.describe(done) if done else "not made"))
    if not done:
        return 1
    town = os.path.join(out, "grown.town")
    labels = [label for label, _, _ in emu_year.EVENTS if not only or label in only]
    rooms = [label for label in ROOMS if not only or label in only]
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:      # each a process of its own
        tours = list(pool.map(lambda lb: child("--tour", emu_dir, rom_path, af_en, town, out, lb), labels))
        insides = list(pool.map(lambda lb: child("--indoors", emu_dir, rom_path, af_en, town, out, lb), rooms))
    report = {"town": done, "tours": tours, "indoors": insides}
    watch = done.get("watch", {})
    check(set(watch) == set(WATCH) and all(v > 0 for v in watch.values()) and sum(watch.values()) < FLOOR,
          "the actors that come and go outdoors (%s): their overlays together are %d bytes, by the game's table "
          "of actors, less than the floor of a block's largest free block, %d" % (
              ", ".join("%s %d" % (name, watch.get(name, 0)) for name in WATCH), sum(watch.values()), FLOOR))
    worst, seen = None, set()
    for label, res in zip(labels, tours):
        least = res and res["least"]
        seen |= {name for entry in (res["blocks"].values() if res else ()) for name in entry["there"]}
        stopped = [p for st in (res["stops"] if res else ()) for p in st["problems"]]
        check(bool(res) and res["arrived"] and not res["hang"] and not res["unreached"] and bool(least) and
              least["largest"] >= FLOOR and not stopped,
              "%s: the %d players' town of 15 animals, every block walked into%s: the arena's largest free block "
              "at its least %s%s" % (
                  label, PLAYERS,
                  " (%d pages of talks that started by themselves played through on the way)" % len(res["stops"])
                  if res and res["stops"] else "",
                  "%d bytes in block %s (%d free, %d in use; %d animals and %d houses there%s), the floor %d" % (
                      least["largest"], least["block"], least["free"], least["used"], least["animals"],
                      least["houses"],
                      "; of the passing actors: %s" % ", ".join(least["there"]) if least["there"] else "", FLOOR)
                  if least else "not read",
                  "; not reached: %s" % ", ".join(res["unreached"]) if res and res["unreached"] else ""))
        if least and (worst is None or least["largest"] < worst[1]["largest"]):
            worst = (label, least)
    if worst:
        absent = sum(size for name, size in watch.items() if name not in worst[1]["there"])
        print("    the least of all: %d bytes, %s, block %s%s; with every passing actor that was not there on top "
              "(%d bytes) it would have %d left; seen on the tours: %s" % (
                  worst[1]["largest"], worst[0], worst[1]["block"],
                  " (%s there)" % ", ".join(worst[1]["there"]) if worst[1]["there"] else "", absent,
                  worst[1]["largest"] - absent, ", ".join(sorted(seen)) or "none of them"))
    total, largest = furniture_bytes(af_en)
    for label, res in zip(rooms, insides):
        room = res and res.get("arena")
        what = {"house": "the player's house as it is", "shop": "the shop, after its keeper's welcome",
                "post-office": "the post office", "police-box": "the police box, after its policeman's word",
                "animal-house": "the house of an animal that is in (%d door%s tried)" % (
                    res["tried"] if res else 0, "" if res and res["tried"] == 1 else "s"),
                "house-gyroids": "the player's house with a different gyroid on each of its %d places" % (
                    res["placed"] if res else 0)}[label]
        check(bool(res) and res["arrived"] and res["inside"] and not res["hang"] and bool(room) and
              room[0] >= INDOORS_FLOOR and (label != "house-gyroids" or res["placed"] >= 16),
              "%s: inside, the arena's largest free block %s" % (what, "%d bytes (%d in use), the floor %d" % (
                  room[0], room[2], INDOORS_FLOOR) if room else "not read"))
    houses = [res["arena"][0] for label, res in zip(rooms, insides) if res and res.get("arena") and
              label in ("house", "house-gyroids", "animal-house")]
    if houses:
        check(total < min(houses), "all the furniture's overlays together, each counted once, are %d bytes (the "
              "largest %d): less than the %d a room has free" % (total, largest, min(houses)))
    json.dump(report, open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_memory: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
