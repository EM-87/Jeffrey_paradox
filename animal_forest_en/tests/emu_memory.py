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
     and its largest free block noted, and which actors' overlays the block has loaded (the game's table of
     actors). The least of each date must stay over FLOOR;
  3. what a block held when it was walked into is not all it can hold. An actor that comes takes its overlay
     and its instance out of the arena, a node of the arena before each (sizes: the overlay's by the game's
     table of actors, the instance's in its profile, read in the build's uncompressed ROM), unless it is an
     animal, a special character or a structure: those are made in room that ovl_Npc and ovl_Structure hold
     in their own overlays (m_actor.c, func_80057940_jp and the function after it: the clips' own areas for
     the names 0xD000 and 0xE000 and for 0x5000), which each tour sees for every actor it meets: its instance
     and its overlay lie inside one of those two overlays, or they do not. So the postman, Mr. Resetti at
     the door, the day's visitor or one more animal take nothing; a balloon, bees out of a shaken tree, a
     fish let go, a snowman built, a tool in an animal's hand and the winter's snowballs do (EXTRA). Every
     actor whose overlay no tour finds loaded must be accounted for: made in those overlays' own room (its
     profile's part, the structures' table), in EXTRA, found loaded in a room or on the new game's route
     (make route-en's states: the train, the station, the houses), or named in ELSEWHERE; and in every block
     of every date the largest free block must take all of EXTRA that was not there, at once, and keep
     RESERVE;
  4. indoors, on a plain afternoon, each in a process of its own: the player's house as it is, and the same
     house with a different gyroid on each of its 16 places (put in the saved town's room before the power-on:
     the game gives a player no furniture to place here); the shop, the post office, the police box and the
     house of an animal that is in, each gone into at its door, where its own code looks for the player
     (BUILDINGS). The arena's largest free block in each must stay over INDOORS_FLOOR.

  5. the title. START there has the game read the save into a buffer it asks the arena for
     (func_8008F968_jp: zelda_malloc of the save's 0xF980 bytes, from the logo's func_80AA01A4_jp, which does
     not look at the answer: with no room the town on the cartridge would not be the one played). The town
     is powered on and left alone through the title's demos, which follow one another (mTD_demono_get: five
     of them, each a town of its own season): while the logo is there the largest free block must take that
     buffer in every one of them and keep RESERVE.

The furniture's code is loaded into the arena piece by piece (ovl_My_Room, func_8093678C_jp: zelda_malloc
and ovlmgr_Load for each kind's own little overlay, by a table of 947 at D_80947638_jp, 536 different ones;
the 127 gyroids share one). All of them together, each counted once, are summed from the build's table and
held under what a room has free: whatever is put in a room, its code fits.
"""

import concurrent.futures
import json
import os
import re
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

FLOOR = emu_year.ARENA_FLOOR    # a town block's largest free block at least: the year check's floor
RESERVE = 0x4000                # and what must be left of it with all of EXTRA on top: 16 KB (the largest
                                # free block of a block depends on the way there by some KB: NOTES)
NODE = 0x10                     # the arena's own bytes before each block it gives out
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
# What can come into a town block on top of what it held when the tour walked in, out of the arena:
# (what, the actors, how many of them at once: None for all, where: None for any block, or an actor the day's
# tour must have met for the group to count that day). A member the block has loaded is there already.
TOOLS = "the tools"             # stands for the actors ovl_Tools makes (ac_tools.c's profile_table)
EXTRA = (
    ("a balloon, bees out of a shaken tree, a fish let go and its shadow, a snowman built, a paper airplane, "
     "the cherry blossoms' manager",
     ("Fuusen", "Bee", "Gyo_Release", "Gyo_Kage", "Psnowman", "Airplane", "Ev_Cherry_Manager"), None, None),
    ("a tool in the hand of each of four animals (the four dearest: an umbrella, a telephone, a fan...)",
     TOOLS, 4, None),
    ("the winter's snowballs, rolled in from the next block", ("Snowman",), None, "Snowman"),
)
SAVE_BUFFER = 0xF980            # what START at the title asks the arena for: the save, read into it
DEMOS, DEMO_FRAMES = 5, 2200    # the title's demos (mTD_demono_get counts 1 to 5 and again) and a demo's frames
TITLE_DEMO, LOGO = "D_8010EDA4_jp", "Animal_Logo"      # the demo's number; the title's logo
POOLS = ("Npc", "Structure")    # the overlays that hold room of their own for the actors they make
NPC_PART = 3                    # ActorProfile.part of an animal or a special character
# The actors whose overlays nothing here ever finds loaded and that never come into a town block: where they
# live instead, by their names and the tables that list them (src/code/m_name_table.c): read, not seen. (The
# others never found on a tour are accounted for by what they are or where they were found.)
ELSEWHERE = {
    "Animal_Logo": "the title", "Ride_Off_Demo": "the arrival by train",
    "Arrange_Furniture": "an animal's room", "Garagara": "the shop, on its raffle's day",
    "Kamakura_Indoor": "inside the igloo", "Lamp_Light": "a room at night",
    "Broker_Design": "the trader's tent",
    "BoxManager": "nothing makes it", "BoxMove": "nothing makes it", "BoxTrick01": "nothing makes it",
    "Fieldm_Draw": "nothing makes it", "Mbg": "nothing makes it", "Sample": "nothing makes it",
}
ROUTE = ("1-name.st", "2-town.st", "3-station.st", "4-houses.st")       # make route-en's states, beside STATE
ACTOR_TABLE, ACTOR_ENTRY = "actor_dlftbls", 0x20   # the game's table of actors (ActorOverlay): the overlay's
ENTRY_VROM, ENTRY_VRAM, ENTRY_LOADED, ENTRY_PROFILE = 0, 8, 0x10, 0x14   # vrom, vram start and end, where it is
PROFILE_PART, PROFILE_INSTANCE = 2, 0xC            # loaded, its profile; ActorProfile: the part, the instance's size

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


def table_names(path, table, end):
    """The actors a C file of the en tree names (ACTOR_ enums) between the texts table and end, by their names
    in the actors' table."""
    source = open(path, encoding="utf-8").read()
    block = source[source.index(table):]
    enums = set(re.findall(r"ACTOR_[A-Z0-9_]+", block[:block.index(end)]))
    header = open(os.path.join(os.path.dirname(path), *[".."] * 4, "include", "tables", "actor_table.h")).read()
    names = {enum: name for name, enum in re.findall(r"DEFINE_ACTOR(?:_INTERNAL)?\((\w+),\s*(ACTOR_\w+)", header)}
    return sorted(names[e] for e in enums if e in names)


def structures(af_en):
    """The actors ovl_Structure makes (its setupInfo_table, and the houses and the station its code names
    before it makes the actor): made in the room its own overlay holds."""
    return table_names(os.path.join(af_en, "src", "overlays", "actors", "ovl_Structure", "ac_structure.c"),
                       "setupInfo_table[] = {", "Actor_info_make_actor(")


def tools(af_en):
    """The actors ovl_Tools makes (its profile_table): what an animal holds in its hand."""
    return table_names(os.path.join(af_en, "src", "overlays", "actors", "ovl_Tools", "ac_tools.c"),
                       "profile_table[] = {", "};")


def actor_sizes(n64, syms, names, af_en):
    """{an actor's name: [its overlay's bytes, its instance's, its part]} of the actors that have an overlay:
    the overlay's by the game's table of actors in RAM, the profile read where the table says it is, in the
    build's uncompressed ROM (a file lies at its vrom there)."""
    plain = open(os.path.join(af_en, "build", "animalforest-jp.z64"), "rb").read()
    raw = n64.read(syms[ACTOR_TABLE], (max(names) + 1) * ACTOR_ENTRY)
    out = {}
    for number, name in names.items():
        vrom, _, start, end, _, profile = struct.unpack(">6I", raw[number * ACTOR_ENTRY:number * ACTOR_ENTRY + 24])
        if end > start and start <= profile < end:
            at = vrom + profile - start
            out[name] = [end - start, struct.unpack(">I", plain[at + PROFILE_INSTANCE:at + PROFILE_INSTANCE + 4])[0],
                         plain[at + PROFILE_PART]]
    return out


def loaded_overlays(n64, syms, names):
    """{an actor's name: where its overlay is loaded} of the overlays in RAM now."""
    raw = n64.read(syms[ACTOR_TABLE], (max(names) + 1) * ACTOR_ENTRY)
    at = {name: struct.unpack(">I", raw[n * ACTOR_ENTRY + ENTRY_LOADED:n * ACTOR_ENTRY + ENTRY_LOADED + 4])[0]
          for n, name in names.items()}
    return {name: addr for name, addr in at.items() if addr}


def make_town(emu_dir, rom_path, af_en, state, out):
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    with N64(emu_dir, rom, os.path.join(out, "state"), timeout=120) as n64:
        n64.load_state(state)
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names)
        first = t.arena(syms["zelda_arena"])         # the new game's first day, Nook showing the houses
        done = emu_town.settle(n64, af_en, syms, more=True)
        common = n64.read(syms["common_data"], emu_psel.RTC_TIME + 8)
        done["first_day"] = list(first) if first else None
        done["actors"] = actor_sizes(n64, syms, names, af_en)
        done["route"] = {}
        for name in ROUTE:                           # the new game's road: what each of its states has loaded
            path = os.path.join(os.path.dirname(state), name)
            if os.path.exists(path):
                n64.load_state(path)
                n64.frames(20, 0)
                done["route"][name] = sorted(loaded_overlays(n64, syms, names))
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
              "blocks": {}, "least": None, "unreached": [], "stops": [], "loaded": [], "pooled": [], "own": []}
    names = emu_year.actor_names(af_en)
    number = {name: n for n, name in names.items()}
    met, pooled, own = set(), set(), set()
    with N64(emu_dir, rom, work, timeout=60, clock=date + hm + (0,)) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names)

        def pools(loaded):
            """The room POOLS' overlays take in RAM: [(start, end)]."""
            out = []
            for name in POOLS:
                if name in loaded:
                    start, end = struct.unpack(">2I", n64.read(
                        syms[ACTOR_TABLE] + number[name] * ACTOR_ENTRY + ENTRY_VRAM, 8))
                    out.append((loaded[name], loaded[name] + end - start))
            return out
        try:
            result["arrived"] = emu_year.arrive(t)
            blocked = set()
            order = [(bx if bz % 2 else 6 - bx, bz) for bz in range(1, 7) for bx in range(1, 6)]
            for b in order:
                if not t.go_block(b, blocked):
                    result["unreached"].append("%d,%d" % b)
                    continue
                room = t.arena(syms["zelda_arena"])
                loaded = loaded_overlays(n64, syms, names)
                inside = pools(loaded)
                here = []
                for a in t.actors():                # where each actor's instance and its overlay lie
                    here.append(a["name"])
                    if a["name"] in POOLS:
                        continue
                    places = [a["addr"]] + ([loaded[a["name"]]] if a["name"] in loaded else [])
                    for place in places:
                        (pooled if any(lo <= place < hi for lo, hi in inside) else own).add(a["name"])
                met |= set(loaded)
                entry = {"largest": room[0] if room else -1, "free": room[1] if room else -1,
                         "used": room[2] if room else -1, "animals": here.count("Normal_Npc"),
                         "houses": here.count("House") + here.count("MyHouse"), "loaded": sorted(loaded)}
                result["blocks"]["%d,%d" % b] = entry
                if result["least"] is None or entry["largest"] < result["least"]["largest"]:
                    result["least"] = dict(entry, block="%d,%d" % b)
            result["stops"] = list(t.stops)         # talks that started by themselves, played through
            result["loaded"], result["pooled"], result["own"] = sorted(met), sorted(pooled), sorted(own)
            n64.screenshot(os.path.join(work, "toured.png"))
        except Hang as e:
            result["hang"] = str(e)
    return result


def title(emu_dir, rom_path, af_en, town, out, label):
    """The town powered on and left at its title through its demos: {the demo's number: the arena's (largest
    free block, free, in use) at the largest's least while the logo is there}, for the report."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    work = os.path.join(out, label)
    os.makedirs(work, exist_ok=True)
    emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(town))
    result = {"label": label, "hang": None, "demos": {}}
    with N64(emu_dir, rom, work, timeout=120, clock=AFTERNOON) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en))
        try:
            turns = 0
            for _ in range((DEMOS + 1) * DEMO_FRAMES // 20):
                n64.frames(20, 0)
                demo = str(t.i32(syms[TITLE_DEMO]))
                try:                                # between two states the pointers read are no one's
                    room = t.arena(syms["zelda_arena"])
                    there = bool(room) and room[2] > 0 and LOGO in emu_town.names(t)
                except (ValueError, struct.error):
                    there = False
                if there and (demo not in result["demos"] or room[0] < result["demos"][demo][0]):
                    turns += demo not in result["demos"]
                    result["demos"][demo] = list(room)
                elif not there and turns >= DEMOS and len(result["demos"]) >= DEMOS:
                    break                           # every demo seen to its end
            n64.screenshot(os.path.join(work, "title.png"))
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
            result["loaded"] = sorted(loaded_overlays(n64, syms, emu_year.actor_names(af_en)))
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
    if argv[:1] == ["--title"]:
        print(json.dumps(title(*argv[1:7])))
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
        shown = (pool.submit(child, "--title", emu_dir, rom_path, af_en, town, out, "title")
                 if not only or "title" in only else None)
        tours = list(pool.map(lambda lb: child("--tour", emu_dir, rom_path, af_en, town, out, lb), labels))
        insides = list(pool.map(lambda lb: child("--indoors", emu_dir, rom_path, af_en, town, out, lb), rooms))
        shown = shown.result() if shown else None
    report = {"town": done, "tours": tours, "indoors": insides, "title": shown}
    if shown is not None or not only:
        demos = (shown or {}).get("demos", {})
        need = SAVE_BUFFER + NODE + RESERVE
        check(bool(shown) and not shown["hang"] and len(demos) == DEMOS and
              all(room[0] >= need for room in demos.values()),
              "the title, left alone through its %d demos: START there asks the arena for the save's %d bytes; "
              "while the logo is there the largest free block is at its least %s, and must be %d" % (
                  DEMOS, SAVE_BUFFER,
                  ", ".join("%d in demo %s" % (demos[d][0], d) for d in sorted(demos)) or "not read", need))
    first = done.get("first_day")
    check(bool(first) and first[0] >= FLOOR, "a new game's first day, Nook showing the houses (the route's state): "
          "the arena's largest free block %s" % ("%d bytes (%d in use), the floor %d" % (first[0], first[2], FLOOR)
                                                 if first else "not read"))
    sizes = done.get("actors", {})
    made_by = {name: "ovl_Structure" for name in structures(af_en)}
    made_by.update({name: "ovl_Npc" for name, (_, _, part) in sizes.items() if part == NPC_PART})
    groups = [(what, tuple(tools(af_en)) if actors == TOOLS else actors, most, where)
              for what, actors, most, where in EXTRA]
    extra_names = {name for _, actors, _, _ in groups for name in actors}

    def cost(name, loaded):
        """What the actor takes out of the arena when it comes: its instance, and its overlay if not there."""
        return sizes[name][1] + NODE + (0 if name in loaded else sizes[name][0] + NODE)

    def extra(loaded, today):
        """(the bytes EXTRA can take in a block on top of what it has loaded, [(the group, its bytes)])."""
        parts = []
        for what, actors, most, where in groups:
            if where is None or where in today:
                costs = sorted((cost(name, loaded) for name in actors if name in sizes and name not in loaded),
                               reverse=True)
                parts.append((what, sum(costs if most is None else costs[:most])))
        return sum(c for _, c in parts), parts

    # where the actors met lie: in the overlays that keep room for them, or in blocks of the arena of their own
    met = {name for res in tours if res for name in res["loaded"]}
    pooled = {name for res in tours if res for name in res["pooled"]}
    own = {name for res in tours if res for name in res["own"]}
    check((bool(pooled) or not labels) and pooled <= set(made_by) and not own & set(made_by) and
          not extra_names & set(made_by),
          "of the %d actors met on the tours, %d have their instance and their overlay inside ovl_Npc's or "
          "ovl_Structure's own overlay every time (animals, special characters, structures: all of the part or "
          "the table that says so) and the other %d never (%s)" % (
              len(pooled | own), len(pooled), len(own),
              "; ".join(["not of them: %s" % ", ".join(sorted(pooled - set(made_by)))] * bool(pooled - set(made_by)) +
                        ["of them, in the arena: %s" % ", ".join(sorted(own & set(made_by)))] * bool(own & set(made_by)))
              or "none the other way round"))
    # every actor no tour finds loaded is accounted for
    inside = {name for res in insides if res for name in res.get("loaded", [])}
    route = {name for state in done.get("route", {}).values() for name in state}
    never = sorted(set(sizes) - met)
    kinds = {"made in ovl_Npc's or ovl_Structure's own room": [n for n in never if n in made_by],
             "in EXTRA": [n for n in never if n in extra_names and n not in made_by]}
    rest = [n for n in never if n not in made_by and n not in extra_names]
    kinds["found loaded in a room"] = [n for n in rest if n in inside]
    kinds["found loaded on the new game's route"] = [n for n in rest if n in route and n not in inside]
    rest = [n for n in rest if n not in inside and n not in route]
    kinds["named in ELSEWHERE"] = [n for n in rest if n in ELSEWHERE]
    unknown = [n for n in rest if n not in ELSEWHERE]
    stale = sorted(set(ELSEWHERE) & (met | inside | route))
    check(bool(sizes) and (bool(only) or not unknown) and not stale and len(done.get("route", {})) == len(ROUTE),
          "of the %d actors with an overlay, the tours find %d loaded; the other %d: %s%s%s" % (
              len(sizes), len(met & set(sizes)), len(never),
              "; ".join("%d %s%s" % (len(v), k, " (%s)" % ", ".join(v) if k in ("in EXTRA", "named in ELSEWHERE")
                                     else "") for k, v in kinds.items()),
              "; %s: %s" % ("not accounted for on this part of the run" if only else "NOT ACCOUNTED FOR",
                            ", ".join(unknown)) if unknown else "",
              "; named in ELSEWHERE and found loaded: %s" % ", ".join(stale) if stale else ""))
    alone = extra(set(), {"Snowman"})
    print("    what can come into a block on top of what it holds, out of the arena (overlay, instance and a node "
          "before each): %s; %d bytes in all" % ("; ".join("%s: %d" % p for p in alone[1]), alone[0]))
    worst, tight = None, None
    for label, res in zip(labels, tours):
        least = res and res["least"]
        stopped = [p for st in (res["stops"] if res else ()) for p in st["problems"]]
        today = set(res["loaded"]) if res else set()
        margins = sorted((entry["largest"] - extra(set(entry["loaded"]), today)[0], b, entry["largest"])
                         for b, entry in (res["blocks"].items() if res else ()))
        if margins and (tight is None or margins[0] < tight[1]):
            tight = (label, margins[0], extra(set(res["blocks"][margins[0][1]]["loaded"]), today))
        check(bool(margins) and margins[0][0] >= RESERVE,
              "%s: in every block the largest free block takes all of that on top at once and keeps %d: the "
              "tightest %s" % (label, RESERVE, "block %s, %d free, %d left" % (
                  margins[0][1], margins[0][2], margins[0][0]) if margins else "not read"))
        check(bool(res) and res["arrived"] and not res["hang"] and not res["unreached"] and bool(least) and
              least["largest"] >= FLOOR and not stopped,
              "%s: the %d players' town of 15 animals, every block walked into%s: the arena's largest free block "
              "at its least %s%s" % (
                  label, PLAYERS,
                  " (%d pages of talks that started by themselves played through on the way)" % len(res["stops"])
                  if res and res["stops"] else "",
                  "%d bytes in block %s (%d free, %d in use; %d animals and %d houses there%s), the floor %d" % (
                      least["largest"], least["block"], least["free"], least["used"], least["animals"],
                      least["houses"], "; the snowballs too" if "Snowman" in least["loaded"] else "", FLOOR)
                  if least else "not read",
                  "; not reached: %s" % ", ".join(res["unreached"]) if res and res["unreached"] else ""))
        if least and (worst is None or least["largest"] < worst[1]["largest"]):
            worst = (label, least)
    if worst and tight:
        print("    the least of all: %d bytes, %s, block %s%s; the tightest with all that can come on top: %s, "
              "block %s, %d free, %d could come (%s), %d left" % (
                  worst[1]["largest"], worst[0], worst[1]["block"],
                  " (the snowballs there)" if "Snowman" in worst[1]["loaded"] else "", tight[0], tight[1][1],
                  tight[1][2], tight[2][0], "; ".join("%s: %d" % p for p in tight[2][1] if p[1]), tight[1][0]))
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
