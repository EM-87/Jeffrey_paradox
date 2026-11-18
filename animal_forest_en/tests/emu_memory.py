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
     with a lot and a house), the actors saved as the game saves them; four players, each with a house.
     The arena is read on the way too: at each of the route's four states (the two names typed on the
     train, the station, Nook showing the houses) its largest free block must be over FLOOR;
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
     profile's part, the structures' table), in EXTRA, found loaded in a room, at the title, on the way
     into town or on the new game's road (make route-en's states and the arrival between two of them), or
     one of the nine nothing makes (UNSEEN). That is not left to the looks between frames: the one place
     where the game makes an actor (m_actor.c, Actor_info_make_actor) tells a hook in the running game of
     each actor made, from power-on, in every session of this check (watch); the nine must be among none of
     them, the scenes' files must name them in no scene a session passes or a door leads to, and all of them
     together must fit in RESERVE; and in every block of every date the largest free block must take all of
     EXTRA that was not there, at once, and keep RESERVE;
  4. indoors, on a plain afternoon, each in a process of its own: the player's house as it is, and the same
     house with a different gyroid on each of its 16 places (put in the saved town's room before the power-on:
     the game gives a player no furniture to place here); the shop, the post office, the police box and the
     house of an animal that is in, each gone into at its door, where its own code looks for the player
     (BUILDINGS). The arena's largest free block in each must stay over INDOORS_FLOOR.

  5. the allocator itself. In every session the arena's two ways in pass their answer to the hook (watch):
     the arena must never say no, from power-on, and the least its largest free block was after any
     allocation is kept, between two frames too. What a block has free depends on the way there (what the
     block before held decides where the next one's actors land); on the dates of snow and on a summer
     night (EDGE_DATES) the town is walked into every block from every side that has a way in (edges), each
     way in held to the floor and to EXTRA's sum; how far a block's room is apart by the way in is told;
  6. the rooms a day's power-on does not reach (MORE), each reached as the game reaches it over the
     power-ons of one flash, the player saving at the gyroid in between: the first house enlarged twice
     by the game's own start of play, each size gone into as it is and with a different piece of
     furniture on each of its 36 and 64 places (the one-unit pieces whose overlays are the largest
     first: as many as ovl_My_Room makes in that room, its own count for the scene, must be found loaded
     by its table, and the room still have free what all the furniture's overlays together take); the
     shop grown three times by the game's own renewal (closed
     with a sign at its door the day before), each larger shop gone into, the last one's second floor too;
     the black market's tent and the fortune teller's, each set by the game at a start of play and gone
     into on its day, its keeper spoken to; the igloo, its camper spoken to; the shop on a month's last
     day, with its raffle's machine, the first one and the last; the house at night. Each room's arena
     must stay over INDOORS_FLOOR, every page read on the way be clean; and in the second shop and the
     fourth's three rooms the signs the en build redraws inside them (tools/af_gc_signs.py's ROOMS,
     tools/af_own_signs.py's POSTERS) are in RAM as the en ROM has them, the cartridge's not;
  7. the title. START there has the game read the save into a buffer it asks the arena for
     (func_8008F968_jp: zelda_malloc of the save's 0xF980 bytes, from the logo's func_80AA01A4_jp, which does
     not look at the answer: with no room the town on the cartridge would not be the one played). The town
     is powered on and left alone through the title's demos, which follow one another (mTD_demono_get: five
     of them, each a town of its own season): while the logo is there the largest free block must take that
     buffer in every one of them and keep RESERVE;
  8. a player made in a town that has players (newcomer): the town of one player fewer powered on on the
     year's tightest day and the game's own road gone for the one who comes later: the select's answer
     after the players' names, the train of a town that is there (its own scene and guide; a name a player
     of the town has is not taken), the station, Nook's welcome and the walk to the houses, where the arena
     is held as a block's is on a tour; a house that is taken looked into and left, a free one looked into
     and taken; the new player's gyroid has a word and no save to offer (nobody saves before the first job
     for Nook is done). Every page read on the road must be clean.

The furniture's code is loaded into the arena piece by piece (ovl_My_Room, func_8093678C_jp: zelda_malloc
and ovlmgr_Load for each kind's own little overlay, by a table of 947 at D_80947638_jp, 536 different ones;
the 127 gyroids share one). All of them together, each counted once, are summed from the build's table and
held under what a room has free: whatever is put in a room, its code fits.
"""

import concurrent.futures
import datetime
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
import af_gc_signs  # noqa: E402
import af_own_signs  # noqa: E402
import aflz  # noqa: E402
import emu_psel  # noqa: E402
import emu_reset  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_year  # noqa: E402
from n64emu import N64, A, START, Hang, stick  # noqa: E402
from afplay import DIRECTIONS, name_dial_open, presses, type_on_dial, walk  # noqa: E402

FLOOR = emu_year.ARENA_FLOOR    # a town block's largest free block at least: the year check's floor
RESERVE = 0x4000                # and what must be left of it with all of EXTRA on top: 16 KB (the largest
                                # free block of a block depends on the way there by some KB: NOTES)
NODE = 0x10                     # the arena's own bytes before each block it gives out
INDOORS_FLOOR = 0x40000         # a room's: 256 KB
PLAYERS = 4
ROOM_FOR_ONE = "grown-%d.town" % (PLAYERS - 1)  # the same town with a house free, for the one who comes later
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
# the rooms a day's power-on does not reach, and the road of a player who comes later
MORE = ("houses", "shops", "tents", "igloo", "raffle", "night", "newcomer")
NOON = (14, 0, 0)
# the dates whose towns are walked into every block from every side: the dates of snow (what moves a block's
# room most is the snowballs' overlay, loaded or not on the way in: a block's room is 35 KB apart by it, and
# what it keeps with all on top 18 KB; every date whose tour meets the snowballs must be one of these), and a
# summer night with a festival's stalls (without snow the way in takes 7 KB at most from what a block keeps,
# on every date: NOTES, "The rooms a day does not reach")
EDGE_DATES = ("new-years-day", "kk-saturday", "turnips-sunday", "countdown", "fireworks")
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
SNOWBALLS = "Snowman"           # the snowballs' actor (the snowman built of two is Psnowman)
SAVE_BUFFER = 0xF980            # what START at the title asks the arena for: the save, read into it
DEMOS, DEMO_FRAMES = 5, 2200    # the title's demos (mTD_demono_get counts 1 to 5 and again) and a demo's frames
TITLE_DEMO, LOGO = "D_8010EDA4_jp", "Animal_Logo"      # the demo's number; the title's logo
POOLS = ("Npc", "Structure")    # the overlays that hold room of their own for the actors they make
NPC_PART = 3                    # ActorProfile.part of an animal or a special character
# The actors nothing makes wherever the game goes: the town on the year's dates, every room, the title's
# demos, the way into town, the new game's road. Four of them are empty (their ct, dt, move and draw do
# nothing: 112 bytes each), three a test's; Lamp_Light is in the list of one scene, a test's (TEST_SCENES);
# Ride_Off_Demo in no scene's and in no table the sources have. The hook sees every actor the game makes and
# must see none of them; whatever might make one, all of them at once, overlays and instances, must fit in
# RESERVE. (Every other actor never found on a tour is accounted for by what it is or by where it was found.)
UNSEEN = ("Arrange_Furniture", "BoxManager", "BoxMove", "BoxTrick01", "Fieldm_Draw", "Lamp_Light", "Mbg",
          "Ride_Off_Demo", "Sample")
# The scenes' files (m_scene.c, Scene_ct: 8-byte commands, the first byte the kind, the second a count, the
# second word where the list is in the file): the control actors' numbers, the actors (16 bytes each, the
# number first), the doors (20 bytes each, the next scene first); 10 ends the file's commands
SCENE_TABLE, SCENE_ENTRY = "scene_data_status", 0x14
SCENE_CTRL, SCENE_ACTORS, SCENE_DOORS, SCENE_END = 1, 2, 4, 10
# the one scene that names one of UNSEEN: the debug menu's list has it (ovl_select, a state the game enters
# from the debug player select, which nothing enters, and from two functions of the title's logo that are
# called through a table by a mask its constructor clears and nothing sets: func_80AA0478_jp)
TEST_SCENES = {"Lamp_Light": ["SCENE_FIELD_TOOL_INSIDE"]}
FTR_LOADED = "B_8094D320_jp"    # ovl_My_Room: where each piece's overlay is loaded, or 0 (func_8093678C_jp)
# ovl_My_Room makes a room's furniture in as many places of its own as its table has for the scene
# (func_80938EF0_jp: the scenes at D_8094CA48_jp, ended by -1, a count for each at D_8094CA7C_jp; func_80938A18_jp
# makes a piece only while fewer than that are there): a room shows no more pieces than that, whatever its floor
FTR_SCENES, FTR_COUNTS = "D_8094CA48_jp", "D_8094CA7C_jp"
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
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names, syms)
        first = t.arena(syms["zelda_arena"])         # the new game's first day, Nook showing the houses
        done = emu_town.settle(n64, af_en, syms, more=True)
        common = n64.read(syms["common_data"], emu_psel.RTC_TIME + 8)
        ext = emu_year.ram_ext(n64, syms)            # the rests of the letters the town has in it
        done["first_day"] = list(first) if first else None
        done["actors"] = actor_sizes(n64, syms, names, af_en)
        done["route"], done["road"] = {}, {}
        for name in ROUTE:                           # the new game's road: what each of its states has loaded,
            path = os.path.join(os.path.dirname(state), name)       # its scene and the arena there
            if os.path.exists(path):
                n64.load_state(path)
                n64.frames(20, 0)
                done["route"][name] = sorted(loaded_overlays(n64, syms, names))
                room = t.arena(syms["zelda_arena"])
                done["road"][name] = [t.i32(syms["common_data"] + SCENE)] + (list(room) if room else [])
        path = os.path.join(os.path.dirname(state), ROUTE[1])
        if os.path.exists(path):                     # and from the town's name to the station: the arrival,
            n64.load_state(path)                     # the hook in place (a state holds the game without it)
            low = watch(n64, af_en, syms)
            type_on_dial(n64, "urld")
            between = set()
            for _ in range(50):
                presses(n64, 4)
                between |= set(loaded_overlays(n64, syms, names))
            done["route"]["between"] = sorted(between)
            done["made"] = made(low(), names)
    emu_year.write_town(os.path.join(out, "grown.town"), emu_psel.town(emu_psel.stamp(common), PLAYERS), ext)
    emu_year.write_town(os.path.join(out, ROOM_FOR_ONE), emu_psel.town(emu_psel.stamp(common), PLAYERS - 1), ext)
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
              "blocks": {}, "least": None, "unreached": [], "stops": [], "loaded": [], "pooled": [], "own": [],
              "watch": None, "before": None, "made": []}
    names = emu_year.actor_names(af_en)
    number = {name: n for n, name in names.items()}
    met, pooled, own = set(), set(), set()
    with N64(emu_dir, rom, work, timeout=60, clock=date + hm + (0,)) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names, syms)

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
            low, used = watch_soon(n64, af_en, syms)
            result["arrived"] = emu_year.arrive(t, lead=400 - used)
            result["before"] = low(afresh=True)
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
                         "houses": here.count("House") + here.count("MyHouse"), "loaded": sorted(loaded),
                         "low": low(True)["least"]}
                result["blocks"]["%d,%d" % b] = entry
                if result["least"] is None or entry["largest"] < result["least"]["largest"]:
                    result["least"] = dict(entry, block="%d,%d" % b)
            result["stops"] = list(t.stops)         # talks that started by themselves, played through
            result["loaded"], result["pooled"], result["own"] = sorted(met), sorted(pooled), sorted(own)
            result["watch"] = low()
            result["made"] = made(result["watch"], names)
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
    result = {"label": label, "hang": None, "demos": {}, "loaded": [], "watch": None, "made": []}
    names, loaded = emu_year.actor_names(af_en), set()
    with N64(emu_dir, rom, work, timeout=120, clock=AFTERNOON) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)
        try:
            low, _ = watch_soon(n64, af_en, syms)
            turns = 0
            for _ in range((DEMOS + 1) * DEMO_FRAMES // 20):
                n64.frames(20, 0)
                demo = str(t.i32(syms[TITLE_DEMO]))
                try:                                # between two states the pointers read are no one's
                    room = t.arena(syms["zelda_arena"])
                    there = bool(room) and room[2] > 0 and LOGO in emu_town.names(t)
                except (ValueError, struct.error):
                    there = False
                if there:
                    loaded |= set(loaded_overlays(n64, syms, names))
                if there and (demo not in result["demos"] or room[0] < result["demos"][demo][0]):
                    turns += demo not in result["demos"]
                    result["demos"][demo] = list(room)
                elif not there and turns >= DEMOS and len(result["demos"]) >= DEMOS:
                    break                           # every demo seen to its end
            result["watch"] = low()
            result["made"] = made(result["watch"], names)
            n64.screenshot(os.path.join(work, "title.png"))
        except Hang as e:
            result["hang"] = str(e)
    result["loaded"] = sorted(loaded)
    return result


def indoors(emu_dir, rom_path, af_en, town, out, label):
    """The town powered on on a plain afternoon; the player's house, or a building, gone into: its arena."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    work = os.path.join(out, label)
    os.makedirs(work, exist_ok=True)
    save = open(town, "rb").read()
    result = {"label": label, "arrived": False, "inside": False, "hang": None, "placed": 0, "tried": 0,
              "watch": None, "made": []}
    if label == "house-gyroids":
        save, result["placed"] = room_with_gyroids(save)
        source, town = town, os.path.join(work, "room.town")
        emu_year.write_town(town, save, emu_year.town_ext(source))
    emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(town))
    with N64(emu_dir, rom, work, timeout=60, clock=AFTERNOON) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)
        try:
            low, used = watch_soon(n64, af_en, syms)
            result["arrived"] = emu_year.arrive(t, lead=400 - used)
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
            result["watch"] = low()
            result["made"] = made(result["watch"], emu_year.actor_names(af_en))
            n64.screenshot(os.path.join(work, "inside.png"))
        except Hang as e:
            result["hang"] = str(e)
    return result


# -- the allocator, watched ---------------------------------------------------------------------------------------
# The arena's two ways in (m_malloc.c: zelda_malloc and zelda_malloc_r, through which every overlay and every
# instance that is not made in ovl_Npc's or ovl_Structure's own room comes) hand their answer to a hook in the
# running game: how often the arena said no, the largest request, and the least the arena's largest free block
# was after any allocation, between two frames too (a block's change makes the new block's actors before a
# frame is over). And the one place where an actor is made (m_actor.c, Actor_info_make_actor: every scene's
# lists, every table and every actor that makes another come through it) tells the hook of each: how many of
# each number were made, 255 at most.
WATCH_C = r"""
typedef int s32;
typedef unsigned int u32;
typedef short s16;
typedef struct Node { s16 magic; s16 isFree; u32 size; struct Node* next; struct Node* prev; } Node;
void* __osMalloc(Node** arena, u32 size);
void* __osMallocR(Node** arena, u32 size);
typedef struct HookCtl { s32 cmd; s32 result; u32 fails; u32 failed; u32 least; u32 calls; u32 most; } HookCtl;
HookCtl hook_ctl = { 0, 0, 0, 0, 0xFFFFFFFF, 0, 0 };
typedef struct Actor { s16 id; } Actor;
void Actor_info_part_new(void* info, Actor* actor, unsigned char part);
unsigned char hook_made[0x100] = { 0 };

static void note(Node** arena, u32 size, void* got) {
    HookCtl* h = &hook_ctl;
    Node* n;
    u32 largest = 0;

    h->calls++;
    if (size > h->most) {
        h->most = size;
    }
    if (got == 0) {
        h->fails++;
        h->failed = size;
        return;
    }
    for (n = *arena; n != 0; n = n->next) {
        if (n->isFree && n->size > largest) {
            largest = n->size;
        }
    }
    if (largest < h->least) {
        h->least = largest;
    }
}

void* hook_malloc(Node** arena, u32 size) {
    void* got = __osMalloc(arena, size);

    note(arena, size, got);
    return got;
}

void* hook_malloc_r(Node** arena, u32 size) {
    void* got = __osMallocR(arena, size);

    note(arena, size, got);
    return got;
}

void hook_part_new(void* info, Actor* actor, unsigned char part) {
    u32 id = (u32)actor->id & 0xFF;

    if (hook_made[id] != 0xFF) {
        hook_made[id]++;
    }
    Actor_info_part_new(info, actor, part);
}

void hook_main(void) {
}
"""
WATCH = struct.Struct(">ii5I")          # hook_ctl: two words of emu_save's, then fails, failed, least, calls, most
WATCHED = (("zelda_malloc", "__osMalloc", "hook_malloc"), ("zelda_malloc_r", "__osMallocR", "hook_malloc_r"),
           ("Actor_info_make_actor", "Actor_info_part_new", "hook_part_new"))
MADE = 0x100                            # hook_made: a count for each actor's number
CODE_FRAMES = 200                       # the game's code is in RAM this long after power-on, at the latest


def watch(n64, af_en, syms):
    """The hook in place: zelda_malloc's and zelda_malloc_r's one call each, and Actor_info_make_actor's of
    Actor_info_part_new, made the hook's. A function that reads its counts ({fails, failed, least, calls,
    most, made}; least None if nothing was asked for since; made: {an actor's number: how many were made}
    since the hook is in place), and begins `least` anew, or all the arena's counts afresh, when told to."""
    af_en = os.path.abspath(af_en)
    en_elf = os.path.join(af_en, "build", "animalforest-jp.elf")
    ref_map = os.path.join(os.path.dirname(af_en), "af", "build", "animalforest-jp.map")
    hole, hole_size = emu_save.section_of(ref_map, "build/src/code/m_choice_main.o", ".text")
    mttime = syms["mTM_time"]
    words = struct.unpack(">II", n64.read(mttime, 8))
    blob, hook = emu_save.build_hook_syms(af_en, en_elf, hole, hole_size, words, mttime, WATCH_C)
    emu_save.write_code(n64, hole, blob)
    nm = subprocess.run(["mips-linux-gnu-nm", "-S", en_elf], capture_output=True, text=True, check=True).stdout
    size = {p[3]: int(p[1], 16) for p in (ln.split() for ln in nm.splitlines()) if len(p) == 4}
    for outer, inner, mine in WATCHED:
        code = n64.read(syms[outer], size[outer])
        jal = 0x0C000000 | ((syms[inner] >> 2) & 0x3FFFFFF)
        calls = [at for at in range(0, len(code), 4) if struct.unpack(">I", code[at:at + 4])[0] == jal]
        if len(calls) != 1:
            raise SystemExit("emu_memory: %s calls %s %d times" % (outer, inner, len(calls)))
        emu_save.write_code(n64, syms[outer] + calls[0], struct.pack(">I", 0x0C000000 | ((hook[mine] >> 2) & 0x3FFFFFF)))
    ctl, counts = hook["hook_ctl"], hook["hook_made"]

    def read(anew=False, afresh=False):
        _, _, fails, failed, least, calls, most = WATCH.unpack(n64.read(ctl, WATCH.size))
        made = {number: count for number, count in enumerate(n64.read(counts, MADE)) if count}
        if afresh:
            n64.write(ctl + 8, struct.pack(">5I", 0, 0, 0xFFFFFFFF, 0, 0))
        elif anew:
            n64.write(ctl + 16, struct.pack(">I", 0xFFFFFFFF))
        return {"fails": fails, "failed": failed, "least": None if least == 0xFFFFFFFF else least, "calls": calls,
                "most": most, "made": made}
    return read


def watch_soon(n64, af_en, syms):
    """The hook in place as soon as the game's code is in RAM after power-on (before the first scene, so
    before the first actor): (watch's function, the frames run)."""
    plain = open(os.path.join(os.path.abspath(af_en), "build", "animalforest-jp.z64"), "rb").read()
    at = syms["code_ROM_START"] + syms["mTM_time"] - syms["code_VRAM"]
    for used in range(1, CODE_FRAMES + 1):
        n64.frames(1, 0)
        if n64.read(syms["mTM_time"], 8) == plain[at:at + 8]:
            return watch(n64, af_en, syms), used
    raise SystemExit("emu_memory: the game's code is not in RAM %d frames after power-on" % CODE_FRAMES)


def made(seen, names):
    """The names of the actors a watch's reading says were made."""
    return sorted(names.get(number, "%#x" % number) for number in (seen or {}).get("made", {}))


def scene_lists(af_en, names):
    """{a scene's name (m_scene_table.h): (the actors its file's lists name, the scenes its doors lead to)},
    read in the build's uncompressed ROM (a file lies at its vrom there)."""
    af_en = os.path.abspath(af_en)
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    rom = open(os.path.join(af_en, "build", "animalforest-jp.z64"), "rb").read()
    header = open(os.path.join(af_en, "include", "m_scene_table.h"), encoding="utf-8").read()
    scenes = [name for name in re.findall(r"/\* \d+ \*/ (SCENE_\w+)", header) if name != "SCENE_NUM"]
    table = syms["code_ROM_START"] + syms[SCENE_TABLE] - syms["code_VRAM"]
    out = {}
    for n, scene in enumerate(scenes):
        start, end = struct.unpack(">2I", rom[table + n * SCENE_ENTRY:table + n * SCENE_ENTRY + 8])
        data, actors, doors = rom[start:end], [], []
        for at in range(0, len(data) - 7, 8):
            kind, count = data[at], data[at + 1]
            where = struct.unpack(">I", data[at + 4:at + 8])[0] & 0xFFFFFF
            if kind == SCENE_END:
                break
            if kind == SCENE_CTRL:
                actors += struct.unpack(">%dh" % count, data[where:where + 2 * count])
            elif kind == SCENE_ACTORS:
                actors += [struct.unpack(">h", data[where + 16 * j:where + 16 * j + 2])[0] for j in range(count)]
            elif kind == SCENE_DOORS:
                doors += [struct.unpack(">i", data[where + 20 * j:where + 20 * j + 4])[0] for j in range(count)]
        out[scene] = (sorted({names.get(a, "%#x" % a) for a in actors}),
                      sorted({scenes[d] if 0 <= d < len(scenes) else "%d" % d for d in doors}))
    return out


def edges(emu_dir, rom_path, af_en, town, out, label):
    """The town powered on at the event's date and every block walked into from each neighbour that has a way
    in: a dict for the report. What a block has free depends on the way there; this is every last step."""
    date, hm = next((d, h) for lb, d, h in emu_year.EVENTS if lb == label)
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    work = os.path.join(out, "edges-" + label)
    os.makedirs(work, exist_ok=True)
    emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(town))
    result = {"label": label, "arrived": False, "hang": None, "crossings": [], "closed": [], "watch": None,
              "before": None, "made": [], "stops": []}
    names = emu_year.actor_names(af_en)
    blocks = [(bx, bz) for bz in range(1, 7) for bx in range(1, 6)]
    ways = {(a, (a[0] + dx, a[1] + dz)) for a in blocks for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))
            if (a[0] + dx, a[1] + dz) in blocks}
    with N64(emu_dir, rom, work, timeout=60, clock=date + hm + (0,)) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names, syms)
        try:
            low, used = watch_soon(n64, af_en, syms)
            result["arrived"] = emu_year.arrive(t, lead=400 - used)
            result["before"] = low(afresh=True)
            walked, blocked = set(), set()

            def crossed(a, b):
                room = t.arena(syms["zelda_arena"])
                walked.add((a, b))
                result["crossings"].append({
                    "from": "%d,%d" % a, "to": "%d,%d" % b, "largest": room[0] if room else -1,
                    "low": low(True)["least"], "loaded": sorted(loaded_overlays(n64, syms, names))})
            t.crossed = crossed
            for _ in range(4 * len(ways)):
                here = t.block()
                left = ways - walked - blocked
                if not left:
                    break
                # the nearest block with a way out not walked yet, over the ways not known closed
                dist, queue = {here: 0}, [here]
                while queue:
                    b = queue.pop(0)
                    for a, nb in ways:
                        if a == b and nb not in dist and (a, nb) not in blocked:
                            dist[nb] = dist[b] + 1
                            queue.append(nb)
                reach = sorted((dist[a], a, b) for a, b in left if a in dist)
                if not reach:
                    break
                _, a, b = reach[0]
                if not t.go_block(a, blocked) or not t.cross(b):
                    blocked.add((a, b))
            result["closed"] = sorted("%d,%d>%d,%d" % (a + b) for a, b in ways - walked)
            result["stops"] = list(t.stops)
            result["watch"] = low()
            result["made"] = made(result["watch"], names)
            n64.screenshot(os.path.join(work, "walked.png"))
        except Hang as e:
            result["hang"] = str(e)
    return result


# -- the rooms a day's power-on does not reach -------------------------------------------------------------------
# Each is reached as the game reaches it, over the power-ons of one flash (a process each), the player saving
# at the gyroid and stopping between them (tests/emu_reset.py's way). What is written by hand is what the
# player's dealings would have left in the save, a few bytes, before a power-on; what follows is the game's:
#   the house: an enlargement ordered (mHm_hs_c + 0x22: the size ordered beside the size, and the order's
#     date at 0x1E; Tom Nook's talk writes them once the loan is paid). The start of play enlarges a house
#     ordered on an earlier day (mHm_CheckRehouseOrder, from mSDI_StartInitAfter: the new walls by
#     func_80094A04_jp, a size at a time);
#   the shop: the sales that earn the next shop (common_data + 0xED74; func_800C16F0_jp: 25,000, 90,000,
#     240,000 and, for the last, a visitor from another town at 0xED88), and the renewal ovl_Shop_Level's
#     func_80958B38_jp sets once it sees them: event flag 0 of type 1 and its date at 0xED80. On the two days
#     before that date the shop is closed, a sign at its door (func_800C23EC_jp); from that date on, the
#     town's start swaps the building on the map and raises the shop's size (func_809588D4_jp,
#     func_80958824_jp, func_800C1674_jp). The raffle's machine (ovl_Garagara, made by Shop_Design) is
#     another day's: a month's last (func_800C2EE0_jp);
#   the tents: nothing. The game sets the next special visit when the last is over (func_8007E780_jp): its
#     kind by the date and the hour of that start (the table at D_80104FA0_jp, entry (year - month + the
#     player's id + 1 + day + hour) mod 6, the next one if it is the last visit's kind), its day one to seven
#     days on, its hour by its kind (the black market's tent from 18:00, the fortune teller's from 21:00).
#     So the town is powered on at an hour that gives the kind wanted, saved, and powered on again on the
#     day and at the hour the game wrote into the save; and since the game never sets the last visit's kind
#     again, and which kind a new town had last is its new game's dice, that one's tent comes second
#     (tents_order);
#   the igloo stands in winter (the year's tour meets it on its Saturday in February).
SCENE, FG_SCENE = 0x14, 7                       # common_data: the scene's number (m_scene_table.h); the town's
HOME_BITS, HOME_ORDERED = 0x22, 0x1E            # mHm_hs_c: its sizes' byte; the order's day, month, year
SHOP_LEVEL, SHOP_SALES, SHOP_RENEWAL, SHOP_VISITOR = 0xED72, 0xED74, 0xED80, 0xED88
SALES = (25000, 90000, 240000)                  # func_800C16F0_jp
SPECIAL, SPECIAL_DATES = 0xEE40, 0xEE44         # the special visit's kind; the save's dates: [4] its day, [7] its hour
SPECIAL_KINDS, SPECIAL_COUNT = "D_80104FA0_jp", "D_80104FAC_jp"
BROKER, GYPSY = 0x3E, 0x40                      # the black market's tent, the fortune teller's
PLAYER_ID = emu_town.PRIVATE_AT + 0xC           # the first player's id (PersonalID_c)
STRUCTURE_ITEMS = {0x5804: "Shop", 0x5805: "Conveni", 0x5806: "Super", 0x5807: "Depart", 0x5826: "BrShop",
                   0x5827: "Buggy", 0x5828: "S_Car", 0x5829: "Kamakura"}       # ac_structure.c's table
UP, UP_RIGHT = (0, 80), (57, 57)
# A door of these rooms: the building's actor, the room's scene, where the player is put from the actor and
# pushed, A at the door. The door is where the building's own code sends the player and the push the way it
# turns them: the shops' at x - 50 and z + 50, x - 25 and z + 82.5, x - 45 and z + 102.5 (the Super's and the
# Depart's), all turned to 0x6000, up and to the right; the igloo's at z + 68 and a tent's at z + 64, turned up
ROOM_DOORS = {
    "shop": ("Shop", 9, DOORS),
    "conveni": ("Conveni", 23, tuple(((-25 - d, 82 + d), UP_RIGHT) for d in (0, 10, -8, 20))),
    "super": ("Super", 24, tuple(((-45 - d, 102 + d), UP_RIGHT) for d in (0, 10, -8, 20))),
    "depart": ("Depart", 25, tuple(((-45 - d, 102 + d), UP_RIGHT) for d in (0, 10, -8, 20))),
    "igloo": ("Kamakura", 31, tuple(((dx, dz), UP) for dz in (85, 78, 95) for dx in (0, -15, 15))),
    "broker": ("BrShop", 12, tuple(((dx, dz), UP) for dz in (80, 72, 90) for dx in (0, -15, 15))),
    "gypsy": ("Buggy", 18, tuple(((dx, dz), UP) for dz in (80, 72, 90) for dx in (0, -15, 15))),
}
HOUSE_SCENES = (20, 21, 22)                     # the player's room, small, medium and large
UPSTAIRS = 29                                   # the Depart's second floor
STAIRS = ((300, 110), (300, 130), (320, 110), (280, 110), (300, 90), (340, 110), (260, 110))
FTR_SIZE = "mRmTp_ftr_size"                     # a piece's size on the floor: 0 one unit (m_room_type.c)


def redrawn(rom, cart, vrom):
    """[(what it is, the en ROM's texture, the cartridge's)] of the signs and posters the en build redraws in
    the room's file at vrom (af_gc_signs' ROOMS, af_own_signs' POSTERS), each read in its ROM."""
    places = [(at, w * h // 2, what) for _, _, where, at, (w, h), _, what in af_gc_signs.ROOMS if where == vrom]
    places += [(p.at, p.w * p.h // 2, p.what) for p in af_own_signs.POSTERS if p.room == vrom]
    ours, theirs = (af_gc_signs.file_at(rom, vrom), af_gc_signs.file_at(cart, vrom)) if places else (b"", b"")
    return [(what, ours[at:at + size], theirs[at:at + size]) for at, size, what in places]


def flash_save(work, rom):
    """(the save, its extension) of the flash in work: its first slot unpacked (the game writes both alike)."""
    save, ext = aflz.unpack(emu_save.read_fla(emu_save.fla_path(work, rom))[:0x10000])
    if ext is None:
        raise SystemExit("emu_memory: the flash in %s holds no compressed save" % work)
    return bytearray(save), ext


def dearest(af_en, count):
    """The one-unit pieces of furniture whose overlays are the largest, each overlay once: [(the piece's
    number, its overlay's bytes)], `count` of them."""
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    rom = open(os.path.join(af_en, "build", "animalforest-jp.z64"), "rb").read()
    unit = syms["code_ROM_START"] + syms[FTR_SIZE] - syms["code_VRAM"]
    at = syms["ovl_My_Room_ROM_START"] + syms[FTR_TABLE] - syms["ovl_My_Room_VRAM"]
    best = {}
    for i in range(FTR_COUNT):
        _, _, start, end = struct.unpack(">4I", rom[at + i * 16:at + i * 16 + 16])
        if rom[unit + i] == 0 and start not in best:
            best[start] = (i, (end - start + 7) & ~7)
    return sorted(best.values(), key=lambda v: (-v[1], v[0]))[:count]


def room_pieces(af_en):
    """{a scene's number: how many pieces of furniture ovl_My_Room makes in it at most}, by its own table in
    the build's uncompressed ROM."""
    af_en = os.path.abspath(af_en)
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    rom = open(os.path.join(af_en, "build", "animalforest-jp.z64"), "rb").read()
    base = syms["ovl_My_Room_ROM_START"] - syms["ovl_My_Room_VRAM"]
    out = {}
    for n in range(64):
        scene = struct.unpack(">i", rom[base + syms[FTR_SCENES] + 4 * n:base + syms[FTR_SCENES] + 4 * n + 4])[0]
        if scene == -1:
            break
        out[scene] = struct.unpack(">i", rom[base + syms[FTR_COUNTS] + 4 * n:base + syms[FTR_COUNTS] + 4 * n + 4])[0]
    return out


def furnished(save, pieces):
    """The save with a piece on each place inside the first home's walls, while there are pieces: (the save,
    the pieces put)."""
    save = bytearray(save)
    put = []
    for z in range(16):
        row = struct.unpack(">16H", save[HOME_LAYER + z * 32:HOME_LAYER + z * 32 + 32])
        for x in range(16):
            if row[x] != WALL and WALL in row[:x] and WALL in row[x + 1:] and not 0x4000 <= row[x] < 0x5000 \
                    and row[x] != 0x1F3F and len(put) < len(pieces):
                at = HOME_LAYER + (z * 16 + x) * 2
                save[at:at + 2] = struct.pack(">H", FURNITURE + pieces[len(put)][0] * 4)
                put.append(pieces[len(put)])
    return save, put


def edited(save, edits):
    """The save (a bytearray) with each of the edits written into it: [offset, the bytes' hex, a mask's hex or
    None]: under a mask only its bits are written."""
    for at, data, mask in edits:
        data = bytes.fromhex(data)
        mask = bytes.fromhex(mask) if mask else b"\xff" * len(data)
        save[at:at + len(data)] = bytes((old & ~m | new & m) & 0xFF for old, new, m in
                                        zip(save[at:at + len(data)], data, mask))
    return save


def session(emu_dir, rom_path, af_en, plan):
    """One power-on of the flash in plan["work"], as the plan says: a dict for the report. The plan: label,
    clock; town (a fresh flash made of it) or flash (a flash's file copied in) or neither (the flash as the
    last power-on left it); edits ([offset, hex, mask's hex or None]: bytes written into the save before the
    power-on); furnish (how many of the dearest pieces to put in the first home); enter (a room of
    ROOM_DOORS, or "house"); upstairs, talk, leave, save (at the gyroid, to stop); signs ({"inside" or
    "upstairs": the room's file whose redrawn signs to look for in RAM there})."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    work, label = plan["work"], plan["label"]
    os.makedirs(work, exist_ok=True)
    fla = emu_save.fla_path(work, rom)
    if plan.get("town"):
        save, ext = bytearray(open(plan["town"], "rb").read()), emu_year.town_ext(plan["town"])
        flags = struct.unpack(">I", save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4])[0] & ~emu_year.JOB_BITS
        save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4] = struct.pack(">I", flags)
    else:
        if plan.get("flash"):
            shutil.copyfile(plan["flash"], fla)
        save, ext = flash_save(work, rom)
    edited(save, plan.get("edits", ()))
    put = []
    if plan.get("furnish"):
        save, put = furnished(save, dearest(af_en, plan["furnish"]))
    image, compressed = aflz.pack(emu_psel.fix(bytes(save)), ext)
    if not compressed:
        raise SystemExit("emu_memory: %s's save does not fit a compressed slot" % label)
    emu_save.write_fla(fla, image + image)
    result = {"label": label, "clock": plan["clock"], "arrived": False, "hang": None, "placed": len(put),
              "overlays": sum(size for _, size in put), "facts": None, "inside": None, "upstairs": None,
              "talk": None, "left": None, "saved": None, "pages": [], "watch": None, "before": None, "made": [],
              "tried": 0, "sign": 0, "scenes": {}}
    cd = syms["common_data"]
    cart = open(os.path.join(os.path.dirname(os.path.abspath(af_en)), "baserom.z64"), "rb").read() \
        if plan.get("signs") else b""
    with N64(emu_dir, rom, work, timeout=60, clock=tuple(plan["clock"])) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names, syms)
        pages = result["pages"]
        plain, seen = n64.frames, {}

        def scene():
            return t.i32(cd + SCENE)

        def frames(n=1, buttons=None):
            """n64.frames, and after it the overlays the scene has loaded (every scene the power-on passes)."""
            done = plain(n, buttons)
            try:
                seen.setdefault(scene(), set()).update(loaded_overlays(n64, syms, names))
            except (ValueError, struct.error):
                pass
            return done
        n64.frames = frames

        def facts():
            home = n64.read(cd + emu_town.HOMES_AT + HOME_BITS, 1)[0]
            dates = n64.read(cd + SPECIAL_DATES, 16)
            sv = n64.read(cd, 0x10000)
            where = {}
            for b in range(30):
                block = (b % 5 + 1, b // 5 + 1)
                for j, item in enumerate(emu_town.block_items(sv, block)):
                    if item in STRUCTURE_ITEMS:
                        where[STRUCTURE_ITEMS[item]] = [block[0], block[1], j % 16, j // 16]
            return {"house": home >> 6, "ordered": home >> 4 & 3, "shop": n64.read(cd + SHOP_LEVEL, 1)[0] >> 6,
                    "sales": n64.u32(cd + SHOP_SALES), "renewal": bool(n64.u32(cd + emu_year.EVENT_FLAGS) & 1),
                    "special": n64.read(cd + SPECIAL, 1)[0], "special_day": [dates[8], dates[9]],
                    "special_hour": dates[15], "where": where}

        def room(where="inside"):
            got = t.arena(syms["zelda_arena"])
            loaded = loaded_overlays(n64, syms, names)
            table = loaded["My_Room"] + syms[FTR_LOADED] - syms["ovl_My_Room_VRAM"] if "My_Room" in loaded else 0
            signs = []
            if plan.get("signs", {}).get(where):    # the room's redrawn signs: [what, in RAM, the cartridge's in RAM]
                ram = n64.read(0x80000000, 0x800000)
                signs = [[what, ram.find(ours) >= 0, ram.find(theirs) >= 0, ours != theirs]
                         for what, ours, theirs in redrawn(rom, cart, plan["signs"][where])]
            return {"scene": scene(), "arena": list(got) if got else None, "actors": sorted(emu_town.names(t)),
                    "loaded": sorted(loaded),       # and the pieces put whose own overlay ovl_My_Room has loaded
                    "furniture": sum(1 for piece, _ in put if table and n64.u32(table + 4 * piece)),
                    "signs": signs}

        def settle(want, tries=40):
            """Talks played through until the scene is `want` and the player waits: True. False once the player
            waits in another scene three looks in a row (a door that did not open), or the tries are out."""
            calm = 0
            for _ in range(tries):
                try:
                    emu_town.follow(t, quiet=4, pages=pages)
                    idle = t.player_main() == emu_year.PLAYER_WAIT and t.window_main() == emu_year.HIDDEN
                    if idle and scene() == want:
                        return True
                    calm = calm + 1 if idle else 0
                    if calm >= 3:
                        return False
                except (ValueError, struct.error):          # between two scenes the pointers read are no one's
                    calm = 0
                n64.frames(15, 0)
            return False

        def enter(which, size):
            if which == "house":
                t.drop(emu_town.MAT[0], emu_town.MAT[1], 40)
                n64.frames(15, emu_town.INTO_DOOR)
                n64.frames(3, emu_town.INTO_DOOR | A)
                result["tried"] += 1
                return settle(HOUSE_SCENES[size])
            outside, want, doors = ROOM_DOORS[which]
            where = facts()["where"].get(outside)
            if not where or not t.go_block((where[0], where[1]), set()):
                return False
            place = next((a for a in t.actors() if a["name"] == outside), None)
            if place is None:
                return False
            read = len(pages)
            for (dx, dz), push in doors:
                result["sign"] = len(pages) - read
                if plan.get("closed") and result["sign"]:
                    return False                    # the door's sign has been read: the shop is closed
                result["tried"] += 1
                t.drop(place["pos"][0] + dx, place["pos"][2] + dz, 30)
                n64.frames(15, stick(*push))
                n64.frames(3, stick(*push) | A)
                n64.frames(20, stick(*push))
                taken = False
                for _ in range(30):                 # the door takes the player, or someone speaks
                    try:
                        if scene() != FG_SCENE or t.window_main() != emu_year.HIDDEN or \
                                t.player_main() != emu_year.PLAYER_WAIT:
                            taken = True
                            break
                    except (ValueError, struct.error):
                        taken = True
                        break
                    n64.frames(3, 0)
                if os.environ.get("EMU_MEMORY_TRACE"):
                    n64.screenshot(os.path.join(work, "%s-try-%02d.png" % (label, result["tried"])))
                    print("try %d at (%d, %d) push %s: taken %s, frame %d, scene %s, window %s, player %s, pages %d" % (
                        result["tried"], dx, dz, push, taken, n64.frame, scene(), t.window_main(), t.player_main(),
                        len(pages)), file=sys.stderr, flush=True)
                if taken and settle(want, 16):
                    return True
                settle(FG_SCENE, 10)                # a word at the door, and the town again
            return False

        def upstairs():
            for x, z in STAIRS:
                t.drop(x, z, 20)
                n64.frames(70, stick(*UP))
                if settle(UPSTAIRS, 12):
                    return True
                if scene() != 25:
                    return False
            return False

        def leave(entry):
            for _ in range(5):
                if entry:
                    t.put(*entry)
                    n64.frames(4, 0)
                n64.frames(60, DIRECTIONS["d"])
                if settle(FG_SCENE, 16):
                    return True
            return False

        try:
            low, used = watch_soon(n64, af_en, syms)
            result["arrived"] = emu_year.arrive(t, pages=pages, lead=400 - used)
            result["before"] = low(afresh=True)
            result["facts"] = facts()
            n64.screenshot(os.path.join(work, label + "-town.png"))
            if plan.get("enter") and result["arrived"]:
                got = enter(plan["enter"], result["facts"]["house"])
                n64.frames(120, 0)
                result["inside"] = dict(room(), got=got)
                n64.screenshot(os.path.join(work, label + "-inside.png"))
                entry = t.pos(t.player()) if got else None
                if got and plan.get("talk"):
                    who = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["name"] != "Npc"]
                    if who:
                        said = emu_year.talk(t, who[0], work, label, 0, limit=4000)
                        result["talk"] = {"actor": said["actor"], "talked": said["talked"], "ended": said["ended"],
                                          "pages": len(said["pages"]), "choices": said["choices"],
                                          "problems": said["problems"]}
                        result["inside"]["after"] = room()["arena"]
                if got and plan.get("upstairs"):
                    up = upstairs()
                    n64.frames(120, 0)
                    result["upstairs"] = dict(room("upstairs"), got=up)
                    n64.screenshot(os.path.join(work, label + "-upstairs.png"))
                elif got and plan.get("leave"):
                    result["left"] = leave(entry)
            if plan.get("save") and result["arrived"]:
                result["saved"] = save_and_stop(t, emu_town.MAT, pages)
                n64.frames(120, 0)
            result["watch"] = low()
            result["made"] = made(result["watch"], names)
        except Hang as e:
            result["hang"] = str(e)
            n64.screenshot(os.path.join(work, label + "-hang.png"))
        result["scenes"] = {str(k): sorted(v) for k, v in sorted(seen.items())}
    result["problems"] = ["%#x at %d: %s" % (p["msg"], p["at"], ", ".join(p["problems"])) for p in result["pages"]
                          if p["problems"]]
    result["pages"] = len(result["pages"])
    return result


def save_and_stop(t, mat, pages, tries=3):
    """The gyroid of the house whose doormat is at mat spoken to, the save asked for, and then to stop:
    {spoken, asked (the choices it gave), left (the game left for its title), pages, tried}; the pages read go
    to `pages`. The talk that opens by the houses is not always the gyroid's: an animal there that has not met
    the player has its first word instead (seen on a town of the en build the day after the shop's renewal:
    three pages, no choice, and the game went on). So while the game has not left, the gyroid is tried again,
    `tries` times in all; one try where no save is expected."""
    out = {"spoken": False, "asked": 0, "left": False, "pages": 0, "tried": 0}
    if not t.go_block(emu_town.PLAYERS_BLOCK, set()):
        return out
    for _ in range(tries):
        gyroids = [a for a in t.actors() if a["name"] == emu_reset.GYROID]
        if not gyroids:
            break
        mine = min(gyroids, key=lambda a: (a["pos"][0] - mat[0]) ** 2 + (a["pos"][2] - mat[1]) ** 2)
        out["tried"] += 1
        out["spoken"] = emu_reset.approach(t, mine)
        said = []
        try:
            out["asked"] = emu_town.follow(t, answers=(emu_reset.GYROID_SAVE, emu_reset.STOP), limit=900, quiet=60,
                                           pages=said)
            out["left"] = emu_reset.TITLE in emu_town.names(t)
        except (ValueError, struct.error):
            out["left"] = True
        out["pages"] += len(said)
        pages.extend(said)
        if out["left"]:
            break
    return out


# -- a player made in a town that has players -------------------------------------------------------------------
# The game's own road for the one who comes later: at the select the answer after the players' names
# (mSDI_StartInitNewPlayer); the train of a town that is there already, a scene of its own with a guide of its
# own, who asks for the name only and does not take one a player of the town has; the station, Nook's welcome
# and the walk to the houses; there a house that is taken may be looked into, and a free one looked into is
# asked about and taken. The new player's own gyroid then has a word and no save to offer: the game lets
# nobody save before the first job for Nook is done. A house's door is found from its actor: the mat 48 below
# it and 48 towards the middle of the four, the door up and outwards from the mat.
SELECT_SCENE, START_DEMO2 = 27, 16              # m_scene_table.h: the select of a saved town; that train
NEWCOMER_DATE = "new-years-day"                 # the day: the year's tightest in the houses' block
NEW_NAMES = ("urld", "dlru")                    # on the dial: the route's own name (the first player's), another
NEW_GUIDE, ARRIVAL, HOUSE = "Npc_Guide2", "Intro_Demo", "MyHouse"


def newcomer(emu_dir, rom_path, af_en, town, out, label, players):
    """The town (of `players` players) powered on at the event's date and a new player made by the game's own
    road, a house taken, its gyroid spoken to: a dict for the report."""
    players = int(players)
    date, hm = next((d, h) for lb, d, h in emu_year.EVENTS if lb == label)
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    work = os.path.join(out, "newcomer")
    os.makedirs(work, exist_ok=True)
    image, compressed = aflz.pack(emu_psel.fix(open(town, "rb").read()), emu_year.town_ext(town))
    if not compressed:
        raise SystemExit("emu_memory: the town of %d players does not fit a compressed slot" % players)
    emu_save.write_fla(emu_save.fla_path(work, rom), image + image)
    result = {"label": "newcomer", "clock": list(date + hm + (0,)), "players": players, "arrived": False,
              "hang": None, "trail": [], "dials": 0, "choices": [], "pages": [], "stops": {}, "doors": [],
              "gyroid": None, "watch": None, "before": None, "made": [], "scenes": {}}
    cd = syms["common_data"]
    mat = None
    with N64(emu_dir, rom, work, timeout=60, clock=date + hm + (0,)) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names, syms)
        pages, read, seen = result["pages"], set(), {}
        plain = n64.frames

        def scene():
            return t.i32(cd + SCENE)

        def frames(n=1, buttons=None):
            """n64.frames, and after it the scene (the road's trail) and the overlays it has loaded."""
            done = plain(n, buttons)
            try:
                seen.setdefault(scene(), set()).update(loaded_overlays(n64, syms, names))
                if result["trail"][-1:] != [scene()]:
                    result["trail"].append(scene())
            except (ValueError, struct.error):
                pass
            return done
        n64.frames = frames

        def advance(limit, quiet=12, where=(FG_SCENE,)):
            """A on every page, each choice's first answer (the select's second choice: the answer after the
            players' names), the dial typed on, until the player has waited free for `quiet` looks in one of
            the scenes: True."""
            still = 0
            for _ in range(limit):
                if name_dial_open(n64):
                    type_on_dial(n64, NEW_NAMES[min(result["dials"], len(NEW_NAMES) - 1)])
                    result["dials"] += 1
                    still = 0
                    continue
                try:
                    window = t.window_main()
                    if window == emu_year.WAITING:
                        page = t.page()
                        if page and (page[0], page[1]) not in read:
                            read.add((page[0], page[1]))
                            pages.append({"msg": page[0], "at": page[1], "text": emu_year.text(page[2])[:700],
                                          "problems": t.page_problems(page[2])})
                    if t.choice_waiting():
                        n64.frames(20, 0)
                        pick = players if len(result["choices"]) == 1 else 0
                        result["choices"].append([len(t.answers()), pick])
                        for _ in range(pick):
                            n64.frames(4, DIRECTIONS["d"])
                            n64.frames(8, 0)
                        n64.press(A, 3, 30)
                        still = 0
                    elif window != emu_year.HIDDEN:
                        n64.press(A, 3, 22)
                        still = 0
                    else:
                        n64.frames(15, 0)
                        still = still + 1 if scene() in where and t.player_main() == emu_year.PLAYER_WAIT else 0
                        if still >= quiet:
                            return True
                except (ValueError, struct.error):          # between two scenes the pointers read are no one's
                    n64.frames(15, 0)
            return False

        def stop(name):
            room = t.arena(syms["zelda_arena"])
            result["stops"][name] = {"scene": scene(), "block": list(t.block()), "arena": list(room) if room else None,
                                     "low": low(True)["least"], "loaded": sorted(loaded_overlays(n64, syms, names)),
                                     "guide": emu_town.GUIDE in emu_town.names(t)}
            n64.screenshot(os.path.join(work, "newcomer-%s.png" % name))

        def mine():
            """Whether a house is the new player's: a home's owner in RAM the new player's own record, under
            another name than the first player's."""
            sv = n64.read(cd, emu_psel.HOMES_AT + 4 * emu_psel.HOME_SIZE)
            me = sv[emu_psel.PRIVATE_AT + players * emu_psel.PRIVATE_SIZE:][:0x10]
            return bool(emu_psel.name_of(sv, players)) and emu_psel.name_of(sv, players) != emu_psel.name_of(sv, 0) \
                and any(sv[emu_psel.HOMES_AT + i * emu_psel.HOME_SIZE:][:0x10] == me for i in range(4))

        def door(house, middle):
            """The house looked into at its door and left again: what came of it."""
            side = 1 if house[0] < middle else -1
            push = stick(-57 * side, 57)
            asked = len(result["choices"])
            t.drop(house[0] + 48 * side, house[1] + 48, 40)
            n64.frames(15, push)
            n64.frames(3, push | A)
            n64.frames(30, 0)
            advance(200, 8, HOUSE_SCENES + (FG_SCENE,))    # Nook's word at the door; the player goes in
            info = {"house": [round(v) for v in house], "inside": scene() in HOUSE_SCENES, "scene": scene(),
                    "arena": None, "out": False}
            if info["inside"]:
                room = t.arena(syms["zelda_arena"])
                info["arena"] = list(room) if room else None
                for _ in range(4):                          # out again, and what Nook says to it
                    n64.frames(60, DIRECTIONS["d"])
                    for _ in range(40):
                        if scene() == FG_SCENE:
                            break
                        n64.frames(15, 0)
                    if scene() == FG_SCENE:
                        info["out"] = advance(600)
                        break
            info["asked"] = len(result["choices"]) - asked
            info["mine"] = mine()
            return info

        try:
            low, used = watch_soon(n64, af_en, syms)
            n64.frames(400 - used, 0)
            n64.press(START, 3, 60)
            result["arrived"] = advance(1500)              # the select, the train, the station
            result["before"] = low(afresh=True)
            if result["arrived"]:
                stop("station")
                for way, steps in (("r", 3), ("d", 4)):     # off the platform, as the route's first player goes
                    for _ in range(steps):
                        walk(n64, way, 15)
                for _ in range(40):                         # Nook calls
                    if t.window_main() != emu_year.HIDDEN:
                        break
                    n64.frames(15, 0)
                advance(600)                                # his welcome, the walk to the houses
                stop("houses")
                houses = [(a["pos"][0], a["pos"][2]) for a in t.actors() if a["name"] == HOUSE]
                middle = sum(x for x, _ in houses) / max(1, len(houses))
                first = emu_town.MAT                        # the first player's house first: it is taken
                houses.sort(key=lambda h: (abs(h[0] + 48 - first[0]) + abs(h[1] + 48 - first[1]) > 1, h[1], h[0]))
                for house in houses:
                    result["doors"].append(door(house, middle))
                    if result["doors"][-1]["mine"]:
                        mat = (house[0] + (48 if house[0] < middle else -48), house[1] + 48)
                        break
                stop("end")
                if mat:                                     # the new player's gyroid: a word, and no save yet
                    result["gyroid"] = save_and_stop(t, mat, pages, tries=1)
                    n64.frames(120, 0)
                    result["gyroid"]["owner"] = mine()
            result["watch"] = low()
            result["made"] = made(result["watch"], names)
        except Hang as e:
            result["hang"] = str(e)
            n64.screenshot(os.path.join(work, "newcomer-hang.png"))
        result["scenes"] = {str(k): sorted(v) for k, v in sorted(seen.items())}
    result["problems"] = ["%#x at %d: %s" % (p["msg"], p["at"], ", ".join(p["problems"])) for p in result["pages"]
                          if p["problems"]]
    result["pages"] = len(result["pages"])
    return result


def special_hour(af_en, save, date, want, last):
    """An hour of the date at which the start of play sets the special visit `want`, the visit before it of
    the kind `last` (func_8007E780_jp: the kinds' table entry (year - month + the first player's id + 1 + day +
    hour) mod its count, the next entries while it is `last`), or None."""
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    rom = open(os.path.join(af_en, "build", "animalforest-jp.z64"), "rb").read()
    at = syms["code_ROM_START"] - syms["code_VRAM"]
    count = struct.unpack(">i", rom[at + syms[SPECIAL_COUNT]:at + syms[SPECIAL_COUNT] + 4])[0]
    kinds = struct.unpack(">%dh" % count, rom[at + syms[SPECIAL_KINDS]:at + syms[SPECIAL_KINDS] + 2 * count])
    ident = struct.unpack(">H", save[PLAYER_ID:PLAYER_ID + 2])[0]
    for hour in range(8, 23):
        k = date[0] - date[1] + ident + 1 + date[2] + hour
        while kinds[k % count] == last:
            k += 1
        if kinds[k % count] == want:
            return hour
    return None


def saved(run):
    """Whether the session saved at the gyroid and the game left for its title."""
    return bool(run) and bool(run["saved"]) and run["saved"]["spoken"] and run["saved"]["left"]


def houses_chain(run, rom, town, out):
    """The first house enlarged twice, each size gone into as the game leaves it and with a piece on every
    place: the sessions."""
    work = os.path.join(out, "houses")
    home = emu_town.HOMES_AT

    def ordered(size, on):
        return [[home + HOME_BITS, "%02x" % (size << 4), "30"],
                [home + HOME_ORDERED, "%02x%02x%04x" % (on[2], on[1], on[0]), None]]
    runs = [run(dict(label="house-m", work=work, clock=(2002, 5, 15) + NOON, town=town, edits=ordered(1, (2002, 5, 14)),
                     enter="house", leave=True, save=True))]
    if saved(runs[-1]):
        kept = os.path.join(out, "house-m.fla")
        shutil.copyfile(emu_save.fla_path(work, rom), kept)
        runs.append(run(dict(label="house-m-full", work=work + "-m-full", clock=(2002, 5, 16) + NOON, flash=kept,
                             furnish=36, enter="house")))
        runs.append(run(dict(label="house-l", work=work, clock=(2002, 5, 16) + NOON, edits=ordered(2, (2002, 5, 15)),
                             enter="house", leave=True, save=True)))
        if saved(runs[-1]):
            runs.append(run(dict(label="house-l-full", work=work + "-l-full", clock=(2002, 5, 17) + NOON,
                                 flash=emu_save.fla_path(work, rom), furnish=64, enter="house")))
    return runs


def shops_chain(run, town, out):
    """The shop grown three times by the game's own renewal, each shop gone into: the sessions."""
    work = os.path.join(out, "shops")

    def renewal(sales, on, visitor=False):
        edits = [[SHOP_SALES, "%08x" % sales, None], [emu_year.EVENT_FLAGS + 3, "01", "01"],
                 [SHOP_RENEWAL, "000000%02x00%02x%04x" % (on[2], on[1], on[0]), None]]
        return edits + ([[SHOP_VISITOR, "00000001", None]] if visitor else [])
    plans = (dict(label="shop-closed", work=work, clock=(2002, 5, 15) + NOON, town=town,
                  edits=renewal(SALES[0], (2002, 5, 16)), enter="shop", closed=True, save=True),
             dict(label="conveni", work=work, clock=(2002, 5, 16) + NOON, enter="conveni", leave=True, save=True,
                  signs={"inside": af_own_signs.SHOP2_ROOM}),
             dict(label="super", work=work, clock=(2002, 5, 17) + NOON, edits=renewal(SALES[1], (2002, 5, 17)),
                  enter="super", leave=True, save=True),
             dict(label="depart", work=work, clock=(2002, 5, 18) + NOON, edits=renewal(SALES[2], (2002, 5, 18), True),
                  enter="depart", upstairs=True,
                  signs={"inside": af_gc_signs.SHOP4_1F, "upstairs": af_gc_signs.SHOP4_2F}),
             # the same flash (the last power-on did not save), on the month's last day: the raffle's room
             dict(label="depart-raffle", work=work, clock=(2002, 5, 31) + NOON,
                  edits=renewal(SALES[2], (2002, 5, 18), True), enter="depart",
                  signs={"inside": af_gc_signs.SHOP4_RAFFLE}))
    runs = []
    for plan in plans:
        runs.append(run(plan))
        if plan.get("save") and not saved(runs[-1]):
            break
    return runs


def tents_order(last):
    """The two tents in the order a save allows: the game never sets the kind of the last visit again, and a
    town's last kind is its new game's dice (the first player's id is in the reckoning), so the tent the
    save has as its last visit comes second."""
    tents = [(BROKER, "broker"), (GYPSY, "gypsy")]
    return tents[::-1] if last == BROKER else tents


def tents_chain(run, af_en, town, out):
    """The black market's tent and the fortune teller's, each set by the game at a start of play and gone
    into on its day, in the order the town's save allows (tents_order): the sessions."""
    work = os.path.join(out, "tents")
    save = open(town, "rb").read()
    runs = []

    def visit(kind, what, start, last, **more):
        hour = special_hour(af_en, save, start.timetuple()[:3], kind, last)
        if hour is None:
            return None
        runs.append(run(dict(label=what + "-set", work=work, clock=start.timetuple()[:3] + (hour, 0, 0), save=True,
                             **({"town": town} if not runs else {}))))
        facts = runs[-1] and runs[-1]["facts"]
        if not saved(runs[-1]) or facts["special"] != kind:
            return None
        day = datetime.date(start.year, *facts["special_day"])
        runs.append(run(dict(label=what, work=work, clock=day.timetuple()[:3] + (facts["special_hour"], 30, 0),
                             enter=what, talk=True, **more)))
        return day
    last, start = save[SPECIAL], datetime.date(2002, 5, 1)
    for n, (kind, what) in enumerate(tents_order(last)):
        more = dict(leave=True, save=True) if n == 0 else {}
        day = visit(kind, what, start, last, **more)
        if not day or (more and not saved(runs[-1])):
            break
        last, start = kind, day + datetime.timedelta(days=2)
    return runs


def report_edges(walked, walks, tours, labels, extra):
    """The checks of the towns walked into every block from every side. The widest a block's room was apart by
    the way in."""
    widest = (0, None)
    for label, res in zip(walks, walked):
        into = {}
        for c in (res["crossings"] if res else ()):
            into.setdefault(c["to"], []).append(c)
        tour = next((t for lb, t in zip(labels, tours) if lb == label and t), None)
        for b, entry in (tour["blocks"].items() if tour else ()):      # the tour's own way in counts too
            into.setdefault(b, []).append({"from": "the tour", "to": b, "largest": entry["largest"],
                                           "low": entry.get("low"), "loaded": entry["loaded"]})
        today = {name for cs in into.values() for c in cs for name in c["loaded"]}
        least = min((c for cs in into.values() for c in cs), key=lambda c: c["largest"], default=None)
        margins = sorted((c["largest"] - extra(set(c["loaded"]), today)[0], c["to"], c["from"], c["largest"])
                         for cs in into.values() for c in cs)
        apart = sorted(((max(c["largest"] for c in cs) - min(c["largest"] for c in cs), b) for b, cs in into.items()),
                       reverse=True)
        if apart and apart[0][0] > widest[0]:
            widest = (apart[0][0], "%s, block %s" % (label, apart[0][1]))
        seen = res and res.get("watch")
        lows = [c["low"] for cs in into.values() for c in cs if c.get("low") is not None]
        stopped = [p for st in (res["stops"] if res else ()) for p in st["problems"]]
        check(bool(res) and res["arrived"] and not res["hang"] and len(into) == 30 and bool(least) and
              least["largest"] >= FLOOR and margins[0][0] >= RESERVE and bool(seen) and seen["fails"] == 0 and
              bool(res["before"]) and res["before"]["fails"] == 0 and not stopped,
              "%s, every block walked into from every side that has a way in%s: %s" % (
                  label, " (%d crossings; %d ways closed by water or a cliff)" % (
                      len(res["crossings"]), len(res["closed"])) if res else "",
                  "the arena's largest free block at its least %d bytes (block %s, from %s), the floor %d; with all "
                  "that can come on top at once the tightest is block %s from %s, %d free, %d left, to keep %d; a "
                  "block's room is up to %d bytes apart by the way in (block %s); the arena never said no (%d "
                  "allocations, between two looks %d at its least)" % (
                      least["largest"], least["to"], least["from"], FLOOR, margins[0][1], margins[0][2],
                      margins[0][3], margins[0][0], RESERVE, apart[0][0], apart[0][1], seen["calls"], min(lows))
                  if res and least and seen and lows else "not walked (%s)" % (res and res["hang"])))
    if walks and widest[1]:                     # told, not judged: every way in is held to the floor and to
        print("    the way into a block moves its largest free block by %d bytes at most (%s): what was loaded in "
              "the block before decides where the next one's actors land" % widest)       # what can come on top
    snowy = [label for label, t in zip(labels, tours) if t and SNOWBALLS in t["loaded"]]
    if walks and labels:
        check(set(snowy) <= set(walks) and len(snowy) < len(walks),
              "every date whose tour meets the snowballs is one of those walked every way (%s), and a date "
              "without snow with them%s" % (", ".join(snowy) or "none", "".join(
                  "; NOT WALKED EVERY WAY: %s" % label for label in snowy if label not in walks)))
    return widest[0]


def report_rooms(chains, furniture, pieces):
    """The checks of the rooms a day's power-on does not reach. furniture: all the furniture's overlays'
    bytes; pieces: room_pieces'."""
    def sound(r):
        return (bool(r) and r["arrived"] and not r["hang"] and not r["problems"] and bool(r["watch"]) and
                r["watch"]["fails"] == 0 and bool(r["before"]) and r["before"]["fails"] == 0)

    def within(r, scene, where="inside", floor=INDOORS_FLOOR):
        i = r and r[where]
        return bool(i) and i["got"] and i["scene"] == scene and bool(i["arena"]) and i["arena"][0] >= floor

    def room(r, where="inside", floor=INDOORS_FLOOR):
        i = r and r[where]
        if not i or not i["arena"]:
            return "not read"
        return "%s, the arena's largest free block %d bytes (%d in use), the floor %d" % (
            "inside" if i["got"] else "NOT inside (scene %d after %d tries)" % (i["scene"], r["tried"]),
            i["arena"][0], i["arena"][2], floor)

    def signs(r, where="inside"):
        """Whether the room's redrawn signs are in RAM as the en ROM has them and the cartridge's are not (a
        sign the en ROM has as the cartridge does, built without the GameCube's files, tells nothing)."""
        found = [(there, old) for _, there, old, redrawn_ in ((r and r[where]) or {}).get("signs", []) if redrawn_]
        return bool(found) and all(there and not old for there, old in found)

    def signed(r, where="inside"):
        i = (r and r[where]) or {}
        found = i.get("signs", [])
        ours = [what for what, there, old, redrawn_ in found if redrawn_ and there and not old]
        return "; the %d sign%s the en build redraws in the room: %d in RAM as the en ROM has %s, the cartridge's " \
               "not%s" % (len(found), "" if len(found) == 1 else "s", len(ours), "it" if len(found) == 1 else "them",
                          "".join("; NOT: %s" % what for what, there, old, redrawn_ in found
                                  if redrawn_ and not (there and not old))
                          + "".join("; the cartridge's own in the en ROM: %s" % what for what, _, _, redrawn_ in found
                                    if not redrawn_))

    def rest(r):
        return "%s%s" % (
            "; %d pages read, %s" % (r["pages"], "clean" if not r["problems"] else r["problems"][:2]) if r else "",
            "; the arena never said no (%d allocations, the largest free block %d at its least on the way)" % (
                r["watch"]["calls"], r["watch"]["least"] or 0) if r and r["watch"] and not r["watch"]["fails"]
            else "; THE ARENA SAID NO" if r and r["watch"] else "")

    def step(runs, label):
        return next((r for r in runs if r and r["label"] == label), None)

    if "houses" in chains:
        runs = chains["houses"]
        for size, name, places in ((1, "medium", 36), (2, "large", 64)):
            label = "house-%s" % name[0]
            r, full = step(runs, label), step(runs, label + "-full")
            check(sound(r) and r["facts"]["house"] == size and within(r, HOUSE_SCENES[size]) and r["left"] and
                  saved(r),
                  "the first house, the %s one ordered the day before (the order written into the save): the start "
                  "of play has enlarged it (its size %s); its door: %s; out again and saved at the gyroid (%s)%s" % (
                      name, r["facts"]["house"] if r and r["facts"] else "?", room(r),
                      "left: %s, saved: %s" % (r["left"], saved(r)) if r else "not run", rest(r)))
            there = full["inside"]["furniture"] if full and full["inside"] else 0
            most = pieces.get(HOUSE_SCENES[size], 0)
            check(sound(full) and full["placed"] == places and 0 < most <= places and there == most and
                  within(full, HOUSE_SCENES[size], floor=furniture),
                  "the %s house with a different piece of furniture on each of its %d places (the one-unit pieces "
                  "whose overlays are the largest first, %d bytes of them): the room makes %d pieces at most "
                  "(ovl_My_Room's own count for the scene), and the overlays of %d of them are loaded by its "
                  "table; %s: what all the furniture's overlays together take%s" % (
                      name, full["placed"] if full else 0, full["overlays"] if full else 0, most, there,
                      room(full, floor=furniture), rest(full)))
    if "shops" in chains:
        runs = chains["shops"]
        r = step(runs, "shop-closed")
        check(sound(r) and r["facts"]["renewal"] and r["facts"]["shop"] == 0 and r["sign"] >= 1 and
              not r["inside"]["got"] and saved(r),
              "the shop's renewal set for the next day (the sales that earn the next shop, the renewal's flag and "
              "date written into the save): the day before, the shop is closed and the sign at its door is read "
              "(%s); saved at the gyroid%s" % ("%d page%s" % (r["sign"], "" if r["sign"] == 1 else "s") if r
                                               else "not run", rest(r)))
        for level, label, outside, scene in ((1, "conveni", "Conveni", 23), (2, "super", "Super", 24),
                                             (3, "depart", "Depart", 25)):
            r = step(runs, label)
            check(sound(r) and r["facts"]["shop"] == level and outside in r["facts"]["where"] and within(r, scene) and
                  (level == 3 or (r["left"] and saved(r))) and (level == 2 or signs(r)),
                  "the next day%s: the town's start has raised the shop to size %s and put the %s on the map (%s); "
                  "its door: %s%s%s%s" % (
                      "" if level == 1 else ", the sales that earn the next written into the save",
                      r["facts"]["shop"] if r and r["facts"] else "?", outside,
                      "it stands in block %d,%d" % tuple(r["facts"]["where"][outside][:2])
                      if r and r["facts"] and outside in r["facts"]["where"] else "it does not stand", room(r),
                      signed(r) if level != 2 else "",
                      "; out again and saved (%s)" % (bool(r["left"]) and saved(r)) if r and level < 3 else "",
                      rest(r)))
        r = step(runs, "depart")
        check(within(r, UPSTAIRS, "upstairs") and signs(r, "upstairs"),
              "the Depart's second floor, up its stairs: %s%s" % (room(r, "upstairs"), signed(r, "upstairs")))
        r = step(runs, "depart-raffle")
        check(sound(r) and r["facts"]["shop"] == 3 and within(r, 25) and "Garagara" in r["inside"]["actors"] and
              signs(r),
              "the Depart on the month's last day: its door: %s; the raffle's machine is %s%s%s" % (
                  room(r), "there" if r and r["inside"] and "Garagara" in r["inside"]["actors"] else "NOT there",
                  signed(r), rest(r)))
    if "tents" in chains:
        runs = chains["tents"]
        for kind, label, outside, scene, what in ((BROKER, "broker", "BrShop", 12, "the black market's tent"),
                                                  (GYPSY, "gypsy", "Buggy", 18, "the fortune teller's tent")):
            first, r = step(runs, label + "-set"), step(runs, label)
            facts = first and first["facts"]
            check(sound(first) and facts["special"] == kind and saved(first),
                  "a start of play at %s sets the next special visit: %s, as the game's table has it for that "
                  "date and hour (func_8007E780_jp); saved at the gyroid%s" % (
                      "%04d-%02d-%02d %02d:00" % tuple(first["clock"][:4]) if first else "?",
                      "%s on %d/%d from %d:00" % (what, facts["special_day"][0], facts["special_day"][1],
                                                  facts["special_hour"]) if facts and facts["special"] == kind
                      else "NOT %s (kind %#x)" % (what, facts["special"]) if facts else "not run", rest(first)))
            talk = r and r["talk"]
            check(sound(r) and outside in r["facts"]["where"] and within(r, scene) and bool(talk) and
                  talk["talked"] and talk["ended"] and not talk["problems"] and (r["saved"] is None or saved(r)),
                  "that day at %s: %s; its door: %s; %s%s" % (
                      "%02d:%02d" % tuple(r["clock"][3:5]) if r else "?",
                      "%s stands in block %d,%d" % ((what,) + tuple(r["facts"]["where"][outside][:2]))
                      if r and r["facts"] and outside in r["facts"]["where"] else "%s does not stand" % what,
                      room(r), "%s spoken to, the talk played to its end: %d pages, %s" % (
                          talk["actor"], talk["pages"], "clean" if not talk["problems"] else talk["problems"][:2])
                      if talk else "nobody spoken to", rest(r)))
    if "igloo" in chains:
        r = chains["igloo"][0]
        talk = r and r["talk"]
        check(sound(r) and "Kamakura" in r["facts"]["where"] and within(r, 31) and bool(talk) and talk["talked"] and
              talk["ended"] and not talk["problems"],
              "the igloo of a winter's day: its door: %s; %s%s" % (
                  room(r), "%s spoken to: %d pages, %s" % (
                      talk["actor"], talk["pages"], "clean" if not talk["problems"] else talk["problems"][:2])
                  if talk else "nobody spoken to", rest(r)))
    if "raffle" in chains:
        r = chains["raffle"][0]
        check(sound(r) and within(r, 9) and "Garagara" in r["inside"]["actors"],
              "the shop on a month's last day: its door: %s; the raffle's machine is %s%s" % (
                  room(r), "there" if r and r["inside"] and "Garagara" in r["inside"]["actors"] else "NOT there",
                  rest(r)))
    if "night" in chains:
        r = chains["night"][0]
        check(sound(r) and within(r, HOUSE_SCENES[0]), "the player's house at nine at night: %s%s" % (room(r), rest(r)))


def report_newcomer(r, extra, today):
    """The checks of the player made in the town that has the others. extra: main's (the bytes that can come
    into a block on top of what it has loaded); today: the actors that date's tour met."""
    def in_order(trail, wanted):
        rest = iter(trail)
        return all(any(sc == w for sc in rest) for w in wanted)

    players = r["players"] if r else 0
    stops = r["stops"] if r else {}
    station, houses = stops.get("station"), stops.get("houses")
    early = r and r["before"]
    check(bool(r) and r["arrived"] and not r["hang"] and bool(early) and early["fails"] == 0 and
          in_order(r["trail"], (SELECT_SCENE, START_DEMO2, FG_SCENE)) and
          r["choices"][1:2] == [[players + 1, players]] and r["dials"] == len(NEW_NAMES) and
          NEW_GUIDE in r["made"] and ARRIVAL in r["made"] and bool(station) and bool(station["arena"]) and
          station["arena"][0] >= FLOOR,
          "a player made in the town of %d players by the game's own road, on %s: at the select the answer after "
          "the players' names (%s); the train of a town that is there (scene %d: %s, its guide %s); the name "
          "typed %d time%s (the first player's again is not taken, the next is); the station%s: the arena's "
          "largest free block %s; %s" % (
              players, "%04d-%02d-%02d %02d:%02d" % tuple(r["clock"][:5]) if r else "?",
              "%d answers, the last taken" % r["choices"][1][0] if r and len(r["choices"]) > 1 else "not asked",
              START_DEMO2, "passed" if r and START_DEMO2 in r["trail"] else "NOT passed",
              "made" if r and NEW_GUIDE in r["made"] else "NOT made", r["dials"] if r else 0,
              "" if r and r["dials"] == 1 else "s", ", with the arrival's own actor" if r and ARRIVAL in r["made"] else "",
              "%d bytes, the floor %d" % (station["arena"][0], FLOOR) if station and station["arena"] else "not read",
              "the arena never said no from power-on (%d allocations, the largest free block %s at its least)" % (
                  early["calls"], early["least"]) if early and not early["fails"]
              else "THE ARENA SAID NO" if early else "not watched" if r else "not run (%s)" % (r and r["hang"])))
    doors = r["doors"] if r else []
    taken, mine = [d for d in doors if not d["mine"]], [d for d in doors if d["mine"]]
    left = houses["arena"][0] - extra(set(houses["loaded"]), set(today) | set(houses["loaded"]))[0] \
        if houses and houses["arena"] else None
    looked = [d["arena"][0] for d in doors if d["inside"] and d["arena"]]
    gone = "end" in stops and not stops["end"]["guide"]
    check(bool(houses) and houses["block"] == list(emu_town.PLAYERS_BLOCK) and houses["guide"] and left is not None and
          houses["arena"][0] >= FLOOR and left >= RESERVE and bool(taken) and
          all(d["inside"] and d["out"] and d["asked"] == 0 for d in taken) and len(mine) == 1 and doors[-1]["mine"] and
          mine[0]["inside"] and mine[0]["out"] and mine[0]["asked"] >= 2 and len(looked) == len(doors) and
          min(looked) >= INDOORS_FLOOR and gone,
          "Nook's welcome and the walk to the houses: there (block %s) the arena's largest free block %s; %d "
          "house%s taken: looked into as Nook allows and left again, nothing asked (%s); the next looked into, "
          "asked about and taken (%s); a room's largest free block %s at its least, the floor %d" % (
              "%d,%d" % tuple(houses["block"]) if houses else "?",
              "%d bytes, the floor %d; with all that can come on top at once %d left, to keep %d" % (
                  houses["arena"][0], FLOOR, left, RESERVE) if left is not None else "not read",
              len(taken), "" if len(taken) == 1 else "s",
              "so" if taken and all(d["inside"] and d["out"] and d["asked"] == 0 for d in taken) else "NOT SO",
              "its owner in RAM the new player, %d answers given, Nook gone" % mine[0]["asked"]
              if mine and gone else "NOT TAKEN", min(looked) if looked else "not read",
              INDOORS_FLOOR))
    word, seen = r and r["gyroid"], r and r["watch"]
    lows = [v for v in [st["low"] for st in stops.values()] + [seen["least"] if seen else None] if v is not None]
    check(bool(word) and word["spoken"] and word["pages"] >= 1 and word["asked"] == 0 and not word["left"] and
          word["owner"] and not r["problems"] and bool(seen) and seen["fails"] == 0,
          "the new player's own gyroid: %s; %s; %s" % (
              "spoken to, %d page%s, %s" % (
                  word["pages"], "" if word["pages"] == 1 else "s",
                  "no save offered (the game lets nobody save before the first job for Nook is done), the house "
                  "still the new player's" if not word["asked"] and not word["left"] and word["owner"]
                  else "NOT AS A NEW PLAYER'S (%d choices, the game %s)" % (
                      word["asked"], "left for its title" if word["left"] else "went on"))
              if word and word["spoken"] else "NOT spoken to",
              "%d pages read on the road, %s" % (r["pages"], "clean" if not r["problems"] else r["problems"][:2])
              if r else "not run",
              "the arena never said no (%d allocations after the station, the largest free block %s at its least)" % (
                  seen["calls"], min(lows) if lows else "not read") if seen and not seen["fails"]
              else "THE ARENA SAID NO" if seen else "not watched"))


def main(argv):
    if argv[:1] == ["--newcomer"]:
        print(json.dumps(newcomer(*argv[1:8])))
        return 0
    if argv[:1] == ["--session"]:
        print(json.dumps(session(argv[1], argv[2], argv[3], json.load(open(argv[4])))))
        return 0
    if argv[:1] == ["--edges"]:
        print(json.dumps(edges(*argv[1:7])))
        return 0
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
    more = [m for m in MORE if not only or m in only]
    walks = [label for label in EDGE_DATES if not only or "edges" in only]
    rom = open(rom_path, "rb").read()

    def run(plan):
        """A session of a chain: the plan kept beside the report, the child's answer."""
        plan_path = os.path.join(out, "plan-%s.json" % plan["label"])
        with open(plan_path, "w") as f:
            json.dump(plan, f)
        return child("--session", emu_dir, rom_path, af_en, plan_path)

    def alone(label, clock, enter, **kw):
        return [run(dict(label=label, work=os.path.join(out, label), clock=clock + NOON[len(clock) - 3:], town=town,
                         enter=enter, **kw))]
    chain = {"houses": lambda: houses_chain(run, rom, town, out), "shops": lambda: shops_chain(run, town, out),
             "tents": lambda: tents_chain(run, af_en, town, out),
             "igloo": lambda: alone("igloo", (2002, 2, 2), "igloo", talk=True),
             "raffle": lambda: alone("raffle", (2002, 5, 31), "shop"),
             "night": lambda: alone("night", (2002, 5, 15, 21, 0, 0), "house"),
             "newcomer": lambda: [child("--newcomer", emu_dir, rom_path, af_en, os.path.join(out, ROOM_FOR_ONE),
                                        out, NEWCOMER_DATE, str(PLAYERS - 1))]}
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:      # each a process of its own
        shown = (pool.submit(child, "--title", emu_dir, rom_path, af_en, town, out, "title")
                 if not only or "title" in only else None)
        chains = {m: pool.submit(chain[m]) for m in more}                      # the longest first
        walked = [pool.submit(child, "--edges", emu_dir, rom_path, af_en, town, out, lb) for lb in walks]
        tours = [pool.submit(child, "--tour", emu_dir, rom_path, af_en, town, out, lb) for lb in labels]
        insides = [pool.submit(child, "--indoors", emu_dir, rom_path, af_en, town, out, lb) for lb in rooms]
        tours, insides, walked = ([f.result() for f in fs] for fs in (tours, insides, walked))
        chains = {m: f.result() for m, f in chains.items()}
        shown = shown.result() if shown else None
    report = {"town": done, "tours": tours, "indoors": insides, "title": shown, "edges": walked, "rooms": chains}
    if shown is not None or not only:
        demos = (shown or {}).get("demos", {})
        need = SAVE_BUFFER + NODE + RESERVE
        seen = (shown or {}).get("watch")
        check(bool(shown) and not shown["hang"] and len(demos) == DEMOS and
              all(room[0] >= need for room in demos.values()) and bool(seen) and seen["fails"] == 0,
              "the title, left alone through its %d demos: START there asks the arena for the save's %d bytes; "
              "while the logo is there the largest free block is at its least %s, and must be %d; %s" % (
                  DEMOS, SAVE_BUFFER,
                  ", ".join("%d in demo %s" % (demos[d][0], d) for d in sorted(demos)) or "not read", need,
                  "the arena never said no from power-on (%d allocations)" % seen["calls"]
                  if seen and not seen["fails"] else "THE ARENA SAID NO" if seen else "not watched"))
    first = done.get("first_day")
    check(bool(first) and first[0] >= FLOOR, "a new game's first day, Nook showing the houses (the route's state): "
          "the arena's largest free block %s" % ("%d bytes (%d in use), the floor %d" % (first[0], first[2], FLOOR)
                                                 if first else "not read"))
    road = done.get("road", {})
    check(len(road) == len(ROUTE) and all(len(r) == 4 and r[1] >= FLOOR for r in road.values()),
          "the new game's road, at each of the route's states (the two names typed on the train, the station, "
          "the houses): the arena's largest free block %s; the floor %d" % (
              ", ".join("%d bytes in %s (scene %d)" % (r[1], name.split(".")[0], r[0]) if len(r) == 4
                        else "not read in %s" % name for name, r in sorted(road.items())) or "not read", FLOOR))
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
    sessions = [r for runs in chains.values() for r in runs if r]
    inside |= {name for r in sessions for names_ in r["scenes"].values() for name in names_}
    inside |= set((shown or {}).get("loaded", []))
    route = {name for state in done.get("route", {}).values() for name in state}
    # every actor the game made, from power-on, in every session: the hook's own count, not a look's
    watched = [r for r in tours + insides + walked + sessions + [shown] if r and r.get("watch")]
    born = {name for r in watched for name in r["made"]} | set(done.get("made", []))
    inside |= born
    never = sorted(set(sizes) - met)
    kinds = {"made in ovl_Npc's or ovl_Structure's own room": [n for n in never if n in made_by],
             "in EXTRA": [n for n in never if n in extra_names and n not in made_by]}
    rest = [n for n in never if n not in made_by and n not in extra_names]
    kinds["found loaded in a room, at the title or on the way into town"] = [n for n in rest if n in inside]
    kinds["found loaded on the new game's route"] = [n for n in rest if n in route and n not in inside]
    rest = [n for n in rest if n not in inside and n not in route]
    kinds["found nowhere"] = [n for n in rest if n in UNSEEN]
    unknown = [n for n in rest if n not in UNSEEN]
    stale = sorted(set(UNSEEN) & (met | inside | route))
    unseen = sum(cost(n, set()) for n in UNSEEN if n in sizes)
    check(bool(sizes) and (bool(only) or not unknown) and not stale and unseen <= RESERVE and
          set(UNSEEN) <= set(sizes) and len(done.get("route", {})) == len(ROUTE) + 1,
          "of the %d actors with an overlay, the tours find %d loaded; the other %d: %s%s%s; those found nowhere "
          "would take %d bytes all at once, overlays and instances: within the %d kept in every block" % (
              len(sizes), len(met & set(sizes)), len(never),
              "; ".join("%d %s%s" % (len(v), k, " (%s)" % ", ".join(v) if k in ("in EXTRA", "found nowhere")
                                     else "") for k, v in kinds.items()),
              "; %s: %s" % ("not accounted for on this part of the run" if only else "NOT ACCOUNTED FOR",
                            ", ".join(unknown)) if unknown else "",
              "; named as found nowhere and found loaded: %s" % ", ".join(stale) if stale else "", unseen,
              RESERVE))
    lists = scene_lists(af_en, emu_year.actor_names(af_en))
    order = list(lists)
    naming = {n: [sc for sc in order if n in lists[sc][0]] for n in UNSEEN}
    tests = {sc for scenes in naming.values() for sc in scenes}
    led = {d for _, doors in lists.values() for d in doors}
    passed = {order[int(k)] if int(k) < len(order) else k for r in sessions for k in r["scenes"]}
    check(bool(watched) and not set(UNSEEN) & born and {n: v for n, v in naming.items() if v} == TEST_SCENES and
          not tests & (led | passed),
          "the game tells the hook of every actor it makes (Actor_info_make_actor), from power-on: %d kinds in the "
          "%d sessions watched and on the new game's arrival, %s of the %d found nowhere; of those the %d scenes' "
          "files name %s, and no door of a scene leads there (the doors lead to %s) and no session passed it "
          "(they passed %s)" % (
              len(born), len(watched), "none" if not set(UNSEEN) & born else ", ".join(sorted(set(UNSEEN) & born)),
              len(UNSEEN), len(lists),
              "; ".join("%s in %s alone" % (n, ", ".join(v)) for n, v in sorted(naming.items()) if v) or "none",
              ", ".join(sorted(led)), ", ".join(sorted(passed)) or "none"))
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
        seen = res and res.get("watch")
        lows = [e["low"] for e in res["blocks"].values() if e.get("low") is not None] if res else []
        early = res and res.get("before")
        check(bool(seen) and seen["fails"] == 0 and bool(lows) and bool(early) and early["fails"] == 0,
              "%s: the arena never said no (%s)" % (
                  label, "%d allocations from power-on to the town, %d on the tour, the largest %d bytes; between "
                  "two looks the largest free block was %d at its least" % (
                      early["calls"], seen["calls"], max(seen["most"], early["most"]), min(lows))
                  if seen and lows and early and not seen["fails"] and not early["fails"]
                  else "not watched" if not seen or not early else "%d refused, the last one of %d bytes" % (
                      seen["fails"] + early["fails"], seen["failed"] or early["failed"])))
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
    report_edges(walked, walks, tours, labels, extra)
    report_rooms(chains, total, room_pieces(af_en))
    if "newcomer" in chains:
        day = next((res for lb, res in zip(labels, tours) if lb == NEWCOMER_DATE and res), None)
        report_newcomer(chains["newcomer"][0], extra, day["loaded"] if day else ())
    json.dump(report, open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_memory: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
