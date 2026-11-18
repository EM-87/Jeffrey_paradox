#!/usr/bin/env python3
"""The signs the GameCube's English redrew and ours, in the en build's ROM and in the running game.

    tests/emu_signs.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR [--gc GC_DIR] [--town TOWN]

tools/af_gc_signs.py writes them over the en tree's copy of the structures'
object (reference/NOTES.md, "The GameCube's signs"). First the ROM: each
sign's every copy in EN_ROM's object (its file through the dmadata) differs
from the cartridge's (build/baserom.z64, beside AF_EN_DIR) and, with
--gc (build/gc: the disc's foresta.map and foresta.rel.szs), is the
GameCube's texture laid out as the N64's; and so the palette the GameCube
changed; and our own (tools/af_own_signs.py: the fireworks stall's banner
and cards, the police box's notice board in summer and in winter, the
fishing tourney headquarters' sign, the fortune stall's plaque and slips;
NOTES, "Our own signs") are the textures the tool makes from the
cartridge's, every copy. Then the game: STATE is a state of EN_ROM in play
(make route-en's 4-houses.st); for each structure with a sign
(STRUCTURES), a hook asks the structures' clip to set it up 170 units
south of the player, as the field sets one up (its setupActorProc: the
profile, the object's part and the palette loaded), until it is made;
then the structure's signs are in RAM as the en build has them, and the
cartridge's are not, and the black market's palette as the GameCube has
it; and so our own: the police box's board,
the fireworks stall's two, the headquarters' sign with the tourney's
stall, the fortune stall's two (the clip sets the events' structures up
out of their seasons as well). The houses' name plates come with a
villager's house, which the route's town does not put by the player: the
ROM's check only. The route's town is in summer (no snow): the summer
copies are the ones loaded. Writes a screenshot of each structure, and a
sheet of them, to OUT_DIR.

The signs inside the rooms, in the ROM: the fourth shop's seven
(af_gc_signs' ROOMS, in its rooms' files) differ from the cartridge's and,
with --gc, are the GameCube's, and the entry of a palette it changed with
them; the posters (af_own_signs' POSTERS: the police box's two and the
second shop's) are the en build's, as the tool makes them from the
cartridge's: ours, or with --gc the GameCube's own English ones of the
police box; and the rest of each of those rooms' files is the
cartridge's. In the game, the shops' are looked for in RAM where the
shops are gone into (tests/emu_memory.py's sessions grow the shop to
each size); the police box's here: with --town (make psel-check-en's town
of two players, as make year-check-en powers it on), in a process of its
own: the town powered on, the player walked to the police box's block
and in at its door as the box lets one in (the door's point and the way
in from its actor's check), and in the room, the posters in RAM the en
build's and the cartridge's not.

The furniture's words (tools/af_own_ftr.py; NOTES, "Our own furniture
words"): each object in the ROM (through the dmadata, decompressed as the
game reads it) holds ours where the cartridge's texture was, as the tool
makes it from the cartridge's, and the rest of the object is the
cartridge's: its lists, which set the render tile at that TMEM address
(the tool's check), unchanged.

The NES (tools/af_gc_nes.py; NOTES, "The NES"): in the ROM, the segment
after code holds the eight objects the tool makes of the disc's files
(with --gc; without, it is empty and the eight profiles are the
cartridge's), whole through the dmadata as a file read in parts; each of
the eight pieces' overlays has its profile pointed at its object there
and is otherwise the cartridge's; each object passes the tool's audit;
the cartridge's own eight objects are still its files. In the game, with
--town, in a process of its own: the eight pieces put in the first
home's room of the saved town, a free place before each, the town powered
on and the player walked in at the door; then each piece's overlay is
loaded, its profile in RAM is the ROM's, its object is in RAM whole and
the cartridge's Famicom is not; the player stands before each piece and
presses A: before the drive's, the cartridge's page for it and no game
handed on; before one with a game, the cartridge's message for that game
(func_8093A1DC_jp: 0x2B6A and the game's number), answered no at six and
yes at the last, after which the game's number is the one handed to the
emulator's start (goto_emu_game's, common_data + 0x109F8). The check
stops there: the cartridge's emulator of the console runs tasks this
frontend does not count as frames. A screenshot of the room is written
to look at.
"""

import math
import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
from n64emu import N64, stick  # noqa: E402
import af_gc_nes  # noqa: E402
import af_gc_signs  # noqa: E402
import af_own_ftr  # noqa: E402
import af_own_signs  # noqa: E402
import contact  # noqa: E402
import emu_save  # noqa: E402
import nafe_diff  # noqa: E402

HOOK_C = r"""
typedef unsigned char u8;
typedef unsigned short u16;
typedef short s16;
typedef int s32;
typedef float f32;
extern void* gamePT;
extern u8 common_data[];
typedef void* (*SetupFn)(void* play, u16 name, f32 x, f32 z, s16 params);
typedef struct HookCtl { s32 cmd; s32 result; s32 arg[4]; f32 at[2]; } HookCtl;
HookCtl hook_ctl;

void hook_main(void) {
    HookCtl* h = &hook_ctl;
    void** clip = *(void***)(common_data + STRUCTURE_CLIP);
    u8* player = *(u8**)((u8*)gamePT + PLAYER_HEAD);

    if (h->cmd == 1 && clip != 0 && player != 0) {
        /* the structure arg[0], set up as the field sets one up (its setupActorProc) from the player's place moved
           by at; it is made once its object's part is loaded: asked each frame, arg[1] the tries */
        h->result = (s32)((SetupFn)clip[0])(gamePT, (u16)h->arg[0], *(f32*)(player + WORLD_X) + h->at[0],
                                             *(f32*)(player + WORLD_Z) + h->at[1], 0);
        h->arg[1]++;
        if (h->result != 0 || h->arg[1] > TRIES) {
            h->cmd = 0;
        }
    }
}
""".replace("STRUCTURE_CLIP", "0x10098").replace("PLAYER_HEAD", "0x1C90").replace("WORLD_X", "0x28").replace(
    "WORLD_Z", "0x30").replace("TRIES", "200")
# common_data.clip.structureClip (m_common_data.h, m_clip.h: its setupActorProc first), Game_Play.actorInfo's
# player list's head (actorLists[ACTOR_PART_PLAYER]: 0x1C78 + 4 + 2 * 8 + 4), Actor.world.pos x and z
CTL = struct.Struct(">ii4I2f")
SOUTH = 170.0                               # in front of the player, as the camera looks: the sign in view
SETTLE = 60
# the structures (their names, setupInfo_table's index + 0x5800) and the signs (SIGNS' names) they load
STRUCTURES = [(0x5804, "the first shop", ["obj_s_shop1_roof_txt", "obj_s_shop1_front_txt", "obj_s_shop1_side_txt"]),
              (0x5805, "the second shop", ["obj_s_shop2_t2_tex_txt"]),
              (0x5806, "the third shop", ["obj_s_shop3_t3_tex_txt"]),
              (0x5807, "the fourth shop", ["obj_s_shop4_t3_tex_txt", "obj_s_shop4_t2_tex_txt"]),
              (0x580C, "the police box", ["obj_s_kouban_t1_tex_txt"]),
              (0x5826, "the black market", ["obj_s_yamishop_t1_tex_txt"]),
              (0x5831, "the countdown board", ["obj_e_count_t2_tex_txt", "obj_e_count_t3_tex_txt"]),
              (0x5837, "the fishing tourney's stall", ["obj_e_turi_t1_tex"]),
              (0x583A, "the dump", ["obj_s_dump_t1_tex"]),
              (0x582C, "the fireworks stall", []),
              (0x5830, "the fortune stall", [])]
# the structures that load our own (af_own_signs' offsets in the object: the summer board in the summer town)
OURS = {0x580C: [af_own_signs.SUMMER_AT], 0x582C: [af_own_signs.BANNER_AT, af_own_signs.CARDS_AT],
        0x5837: [af_own_signs.HQ_AT[0]], 0x5830: [af_own_signs.PLAQUE_AT, af_own_signs.SLIPS_AT]}
# the police box's door: its point from the actor's place and the way the player faces walking in, between
# north and west (ac_police_box func_80A07C2C_jp: x + 24, z + 40, within 33 units, the shape's angle in
# 0x8001..0xBFFF); the officers one of whom keeps it, the date and time the town is powered on at
POLICE_DOOR = (24.0, 40.0, 0xA000)
OFFICERS = ("Npc_Police", "Npc_Police2")
ROOM_DATE = (2002, 3, 12, 10, 30, 0)

# the NES: ovl_My_Room's tables of each piece's loaded overlay and profile (func_8093678C_jp), the message before a
# piece with a game (func_8093A1DC_jp: this and the game's number), the game's number where goto_emu_game leaves
# it (m_scene.c: common_data.unk109F8), the first home's room in a save and a place's size in a room
NES_LOADED, NES_PROFILES = "B_8094D320_jp", "B_8094E1F0_jp"
NES_MSG, NES_GAME_AT = 0x2B6A, 0x109F8
NES_NO_GAME = (0x17B1, 0x2B69)              # the drive's page: D_8094CBD0_jp's for func_8093BC94_jp's two states
NES_DATE = (2002, 3, 12, 10, 30, 0)
PLACE_SIZE = 40.0

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def file_at(rom, vrom):
    return next(nafe_diff.file_data(rom, e) for e in nafe_diff.dma_entries(rom) if e[0] == vrom)


def main(argv):
    gc_dir = None
    if "--gc" in argv:
        i = argv.index("--gc")
        gc_dir = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    town = None
    if "--town" in argv:
        i = argv.index("--town")
        town = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    emu_dir, rom_path, af_en, state, out = argv
    af_en = os.path.abspath(af_en)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    rom = open(rom_path, "rb").read()
    cart = open(os.path.join(os.path.dirname(af_en), "baserom.z64"), "rb").read()
    obj, cart_obj = file_at(rom, af_gc_signs.OBJECT_VROM), file_at(cart, af_gc_signs.OBJECT_VROM)
    pals, cart_pals = file_at(rom, af_gc_signs.PALETTE_VROM), file_at(cart, af_gc_signs.PALETTE_VROM)
    gc = gc_textures(gc_dir)
    print("emu_signs: the signs %s" % ("against the GameCube's" if gc else "against the cartridge's only"))

    # the ROM: each copy the en build's, not the cartridge's (and the GameCube's, given its files)
    signs = {}
    for name, offsets, (w, h), _, what in af_gc_signs.SIGNS:
        size = w * h // 2
        ours = [obj[at:at + size] for at in offsets]
        theirs = gc and af_gc_signs.untile_ci4(gc[name], w, h)
        ok = all(o != cart_obj[at:at + size] for o, at in zip(ours, offsets)) and len(set(ours)) == 1 and \
            (theirs is None or ours[0] == theirs)
        check(ok, "the ROM: %s (%s), its %d cop%s at %s the en build's%s, not the cartridge's" % (
            name, what, len(offsets), "y" if len(offsets) == 1 else "ies", ", ".join("%#x" % at for at in offsets),
            ", the GameCube's texels" if theirs else ""))
        signs[name] = (ours[0], cart_obj[offsets[0]:offsets[0] + size])
    palettes = {}
    for theirs_name, ours_name in af_gc_signs.PALETTES:
        at = next(o for o, _, n, _ in af_gc_signs_palette_places(af_en) if n == ours_name)
        new, old = pals[at:at + 32], cart_pals[at:at + 32]
        theirs = gc and struct.pack(">16H", *af_gc_signs.gc_palette(gc[theirs_name], theirs_name))
        same = theirs is None or all(af_gc_signs.same_colour(a, b) for a, b in zip(
            struct.unpack(">16H", new), struct.unpack(">16H", theirs)))
        check(new != old and same, "the ROM: %s at %#x the en build's%s" % (
            ours_name, at, ", the GameCube's colours" if theirs else ""))
        palettes[ours_name] = (new, old)
    own = af_own_signs.compose(cart_obj)
    for offsets, _, _, what in af_own_signs.TEXTURES:
        size = af_own_signs.SIZE
        check(all(obj[at:at + size] == own[at] != cart_obj[at:at + size] for at in offsets),
              "the ROM: %s, its cop%s at %s ours, as af_own_signs makes %s from the cartridge's" % (
                  what, "y" if len(offsets) == 1 else "ies", ", ".join("%#x" % at for at in offsets),
                  "it" if len(offsets) == 1 else "them"))

    # the rooms' files: the fourth shop's signs, the posters, and the rest of each file the cartridge's
    for vrom in sorted({sign[2] for sign in af_gc_signs.ROOMS} | set(af_own_signs.ROOMS)):
        room, cart_room = file_at(rom, vrom), file_at(cart, vrom)
        rest = bytearray(room)
        for name, _, where, at, (w, h), _, what in af_gc_signs.ROOMS:
            if where == vrom:
                size = w * h // 2
                theirs = gc and af_gc_signs.untile_ci4(gc[name], w, h)
                check(room[at:at + size] != cart_room[at:at + size] and (theirs is None or room[at:at + size] == theirs),
                      "the ROM: %s (%s) at %#x of the room's file at %#x the en build's%s, not the cartridge's" % (
                          name, what, at, vrom, ", the GameCube's texels" if theirs else ""))
                rest[at:at + size] = cart_room[at:at + size]
        if gc and vrom in {sign[2] for sign in af_gc_signs.ROOMS}:
            entries = {at: data for at, data in af_gc_signs.room_signs(gc, cart_room, vrom).items() if len(data) == 2}
            check(all(room[at:at + 2] == data != cart_room[at:at + 2] for at, data in entries.items()),
                  "the ROM: the room's file at %#x: %s" % (vrom, "the %d entr%s of its palettes the GameCube changed "
                                                           "as it has %s (at %s)" % (
                                                               len(entries), "y" if len(entries) == 1 else "ies",
                                                               "it" if len(entries) == 1 else "them",
                                                               ", ".join("%#x" % at for at in sorted(entries)))
                                                           if entries else "no entry of its palettes changed"))
            for at in entries:
                rest[at:at + 2] = cart_room[at:at + 2]
        if vrom in af_own_signs.ROOMS:
            posters = af_own_signs.compose_room(cart_room, vrom, gc)
            mine = af_own_signs.compose_room(cart_room, vrom)
            for p in af_own_signs.POSTERS:
                if p.room == vrom:
                    size = p.w * p.h // 2
                    check(room[p.at:p.at + size] == posters[p.at] != cart_room[p.at:p.at + size],
                          "the ROM: %s at %#x of the room's file at %#x %s" % (
                              p.what, p.at, vrom, "the GameCube's own English one" if posters[p.at] != mine[p.at]
                              else "ours, as af_own_signs makes it from the cartridge's"))
            for at, data in posters.items():
                rest[at:at + len(data)] = cart_room[at:at + len(data)]
        check(bytes(rest) == cart_room, "the ROM: the rest of the room's file at %#x is the cartridge's" % vrom)
    for vrom, tmem, w, h, base, _, _, _, what, ops in af_own_ftr.FURNITURE:
        at, size = base + tmem * 8, w * h // 2
        ours, theirs = file_at(rom, vrom), file_at(cart, vrom)
        new = af_own_ftr.compose(theirs[at:at + size], w, h, ops)
        check(ours[at:at + size] == new != theirs[at:at + size] and ours[:at] == theirs[:at]
              and ours[at + size:] == theirs[at + size:],
              "the ROM: %s (vrom %#x), ours at %#x as af_own_ftr makes it from the cartridge's, the rest of the "
              "object the cartridge's" % (what, vrom, at))
    nes_rom(rom, cart, af_en, gc_dir if gc else None)
    for flag, where, what in (("--room", "room", "the police box's room"), ("--nes", "nes", "the room with the NES")):
        if not town:
            print("emu_signs: %s not visited (no --town)" % what)
            continue
        r = subprocess.run([sys.executable, os.path.abspath(__file__), flag, emu_dir, rom_path, af_en, town,
                            os.path.join(out, where)] + ([gc_dir] if gc else []), capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("  ok  ") or ln.startswith("  FAIL")]
        for ln in lines:
            print(ln)
            if ln.startswith("  FAIL"):
                failures.append(ln[8:])
        if r.returncode and not any(ln.startswith("  FAIL") for ln in lines):
            check(False, "the game: %s visited (%s)" % (what, (r.stdout + r.stderr)[-600:]))

    # the game: each structure set up by the player, its signs in RAM
    en_elf = os.path.join(af_en, "build", "animalforest-jp.elf")
    syms = emu_save.elf_symbols(en_elf)
    ref_map = os.path.join(os.path.dirname(af_en), "af", "build", "animalforest-jp.map")
    hole, hole_size = emu_save.section_of(ref_map, "build/src/code/m_choice_main.o", ".text")
    mttime = syms["mTM_time"]
    shots = []
    with N64(emu_dir, rom, os.path.join(out, "cart"), timeout=120) as n64:
        for name, what, wanted in STRUCTURES:
            n64.load_state(state)
            words = struct.unpack(">II", n64.read(mttime, 8))
            blob, stub, ctl = emu_save.build_hook(af_en, en_elf, hole, hole_size, words, mttime, HOOK_C)
            emu_save.write_code(n64, hole, blob)
            emu_save.write_code(n64, mttime, struct.pack(">II", 0x08000000 | ((stub >> 2) & 0x3FFFFFF), 0))
            n64.write(ctl, CTL.pack(1, 0, name, 0, 0, 0, 0.0, SOUTH))
            for _ in range(220):
                n64.frames(1, 0)
                if struct.unpack(">i", n64.read(ctl, 4))[0] == 0:
                    break
            made = CTL.unpack(n64.read(ctl, CTL.size))[1]
            n64.frames(SETTLE, 0)
            path = os.path.join(out, "%04x.png" % name)
            n64.screenshot(path)
            shots.append(path)
            ram = n64.read(0x80000000, 0x800000)
            bad = [s for s in wanted if ram.find(signs[s][0]) < 0 or ram.find(signs[s][1]) >= 0]
            mine = OURS.get(name, [])
            bad += ["ours at %#x" % at for at in mine
                    if ram.find(own[at]) < 0 or ram.find(cart_obj[at:at + af_own_signs.SIZE]) >= 0]
            wanted = wanted + ["ours at %s" % ", ".join("%#x" % at for at in mine)] if mine else wanted
            if name == 0x5826:
                # (the winter palette is the cartridge's summer one, so only the new one's presence tells)
                bad += [p for p, (new, _) in palettes.items() if ram.find(new) < 0]
            check(made and not bad, "the game: %s (%#x) set up by the player, %s in RAM as the en build has %s, "
                  "the cartridge's not%s" % (what, name, ", ".join(wanted), "them" if len(wanted) > 1 else "it",
                                             "; not: " + ", ".join(bad) if bad else ""))
    contact.main([os.path.join(out, "sheet.png"), "3"] + shots)
    print("emu_signs: %d failures; screenshots in %s" % (len(failures), out))
    return 1 if failures else 0


def af_gc_signs_palette_places(af_en):
    """[(offset in the palettes' file, size, name, kind)] of the palettes the en tree's splat config names."""
    import re
    text = open(os.path.join(af_en, "yamls", "jp", "assets.yaml")).read()
    return [(int(m.group(1), 16) - af_gc_signs.PALETTE_VROM, 32, m.group(2), "palette")
            for m in re.finditer(r"start: (0x[0-9A-Fa-f]+), type: af_palette, name: (\w+)", text)]


def gc_textures(gc_dir):
    """{name: its bytes} of the GameCube's textures and palettes the en build takes, from the disc's files in
    gc_dir (af_gc_signs.gc_data), or None without them."""
    if not gc_dir or not os.path.exists(os.path.join(gc_dir, "foresta.rel.szs")):
        return None
    names = [n for n, _, _, _, _ in af_gc_signs.SIGNS] + [g for g, _ in af_gc_signs.PALETTES]
    names += [n for sign in af_gc_signs.ROOMS for n in sign[:2]] + af_own_signs.GC_NAMES
    return af_gc_signs.gc_data(os.path.join(gc_dir, "foresta.map"), os.path.join(gc_dir, "foresta.rel.szs"),
                               sorted(set(names)))


def room_visit(emu_dir, rom_path, af_en, town, out, gc_dir=None):
    """The saved town powered on, the player walked into the police box at its door, the posters looked for in
    RAM (run in a process of its own: the emulator's plugins start once per process)."""
    import emu_year
    os.makedirs(out, exist_ok=True)
    rom = open(rom_path, "rb").read()
    cart_room = file_at(open(os.path.join(os.path.dirname(os.path.abspath(af_en)), "baserom.z64"), "rb").read(),
                        af_own_signs.POLICE_ROOM)
    posters = af_own_signs.compose_room(cart_room, af_own_signs.POLICE_ROOM, gc_textures(gc_dir))
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    emu_save.write_fla(emu_save.fla_path(out, rom), emu_year.town_image(town))
    with N64(emu_dir, rom, out, timeout=60, clock=ROOM_DATE) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)
        arrived = emu_year.arrive(t)
        target = [b for b, kinds in t.blocks().items() if "police" in kinds]
        there = arrived and bool(target) and t.go_block(target[0], set())
        box = [a for a in t.actors() if a["name"] == "Police_Box"] if there else []
        inside = False
        if box:
            bx, by, bz = box[0]["pos"]
            dx, dz, angle = POLICE_DOOR
            wx, wz = math.sin(angle * math.pi / 0x8000), math.cos(angle * math.pi / 0x8000)
            pad = stick(int(round(80 * wx)), int(round(-80 * wz)))                   # the stick's up is -z
            yaw = angle - 0x10000 if angle >= 0x8000 else angle
            for back in (30, 40, 22):
                x, z = bx + dx - back * wx, bz + dz - back * wz
                t.drop(x, z, 20)
                for _ in range(3):
                    t.put(x, by + 10, z, yaw)
                    n64.frames(2, 0)
                n64.frames(80, pad)
                for _ in range(40):
                    n64.frames(10, 0)
                    try:
                        inside = any(a["name"] in OFFICERS for a in t.actors())
                    except ValueError:                  # the scene changing: its lists not yet there
                        continue
                    if inside:
                        break
                if inside:
                    break
        check(inside, "the game: the player walked into the police box at its door (%s)" % (
            "block %s" % (target[0],) if target else "no police box's block"))
        if inside:
            n64.frames(120, 0)
            n64.screenshot(os.path.join(out, "police-box.png"))
            ram = n64.read(0x80000000, 0x800000)
            for p in af_own_signs.POSTERS:
                if p.room == af_own_signs.POLICE_ROOM:
                    theirs = cart_room[p.at:p.at + p.w * p.h // 2]
                    check(ram.find(posters[p.at]) >= 0 and ram.find(theirs) < 0,
                          "the game: in the police box, %s in RAM the en build's, the cartridge's not" % p.what)
    return 1 if failures else 0


def nes_objects(gc_dir):
    """(the eight objects, the list's offset in one) as af_gc_nes makes them of the disc's files in gc_dir, or
    (None, None) without them."""
    import gciso
    if not gc_dir or not os.path.exists(os.path.join(gc_dir, "foresta.rel.szs")):
        return None, None
    module = af_gc_nes.Module(gciso.yaz0(open(os.path.join(gc_dir, "foresta.rel.szs"), "rb").read()),
                              open(os.path.join(gc_dir, "foresta.map"), errors="replace").read())
    return af_gc_nes.gc_objects(module)


def nes_profile(words, start, index, size, list_at):
    """The cartridge's profile words as the en build has them for the object `index` of the segment at start."""
    want = list(words)
    want[af_gc_nes.P_ROM], want[af_gc_nes.P_END] = start + index * size, start + (index + 1) * size
    want[af_gc_nes.P_SEG_END], want[af_gc_nes.P_LIST] = af_gc_nes.SEG + size, af_gc_nes.SEG + list_at
    want[af_gc_nes.P_LIST2], want[af_gc_nes.P_TEXTURES], want[af_gc_nes.P_PALETTE] = 0, 0, af_gc_nes.SEG
    return want


def nes_rom(rom, cart, af_en, gc_dir):
    """The ROM's part of the NES: the segment, the eight profiles, the objects' audit, the cartridge's own."""
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    start, end = syms[af_gc_nes.SEGMENT + "_ROM_START"], syms[af_gc_nes.SEGMENT + "_ROM_END"]
    objects, list_at = nes_objects(gc_dir)
    entry = [e for e in nafe_diff.dma_entries(rom) if e[0] == start]
    held = nafe_diff.file_data(rom, entry[0]) if entry else b""
    want = b"".join(objects) if objects else bytes(16)
    check(len(entry) == 1 and entry[0][1] == end and entry[0][3] == 0 and held == want,
          "the ROM: the segment %s at %#x, a file of the dmadata read in parts (not compressed), %s" % (
              af_gc_nes.SEGMENT, start, "the %d objects af_gc_nes makes of the disc's files, %#x bytes each" %
              (len(objects), len(objects[0])) if objects else "empty (no GameCube files)"))
    pieces = af_gc_nes.cart_pieces(cart)
    size = len(objects[0]) if objects else None
    for index, (vrom, vram, words) in enumerate(pieces):
        ovl, cart_ovl = file_at(rom, vrom), file_at(cart, vrom)
        at = af_gc_nes.PROFILE_AT
        got = list(struct.unpack(">%dI" % af_gc_nes.PROFILE_WORDS, ovl[at:at + 4 * af_gc_nes.PROFILE_WORDS]))
        expect = nes_profile(words, start, index, size, list_at) if objects else words
        rest = ovl[:at] == cart_ovl[:at] and ovl[at + 4 * af_gc_nes.PROFILE_WORDS:] == \
            cart_ovl[at + 4 * af_gc_nes.PROFILE_WORDS:]
        check(got == expect and rest and file_at(rom, words[af_gc_nes.P_ROM]) == file_at(cart, words[af_gc_nes.P_ROM]),
              "the ROM: piece %d's overlay (vrom %#x): its profile %s, the rest of it and the cartridge's own "
              "object (vrom %#x) the cartridge's" % (
                  af_gc_nes.PIECES[index], vrom, "names object %d of the segment, its list at %#x, one list, no "
                  "block of textures" % (index, list_at) if objects else "the cartridge's", words[af_gc_nes.P_ROM]))
    if objects:
        bad = [b for obj in objects for b in af_gc_nes.audit(obj, list_at)]
        check(not bad and len(objects[0]) <= af_gc_nes.PLACE,
              "the ROM: each object's list is the N64's orders, every address, block, palette and vertex of it in "
              "the object, which fits the room's place of %#x%s" % (af_gc_nes.PLACE, "; not: " + "; ".join(bad[:3])
                                                                 if bad else ""))


def nes_places(save, count):
    """Places of the first home's room for `count` pieces, each with an empty place below it for the player:
    [(x, z)] in the room's 16 x 16, or fewer than count. A piece takes a place inside the walls, an empty one
    or one that holds a piece of furniture (tests/emu_memory.py's furnished)."""
    import emu_memory
    rows = [struct.unpack(">16H", save[emu_memory.HOME_LAYER + z * 32:emu_memory.HOME_LAYER + z * 32 + 32])
            for z in range(16)]

    def within(x, z):
        row = rows[z]
        return row[x] != emu_memory.WALL and emu_memory.WALL in row[:x] and emu_memory.WALL in row[x + 1:]

    def takes(x, z):
        return within(x, z) and (rows[z][x] == 0 or emu_memory.FURNITURE <= rows[z][x] < 0x4000) \
            and rows[z][x] != 0x1F3F
    out, taken = [], set()
    for z in range(15):
        for x in range(16):
            if len(out) < count and takes(x, z) and within(x, z + 1) and rows[z + 1][x] == 0 and \
                    not {(x, z), (x, z + 1)} & taken:
                out.append((x, z))
                taken |= {(x, z), (x, z + 1)}
    return out


def nes_visit(emu_dir, rom_path, af_en, town, out, gc_dir=None):
    """The saved town with the eight pieces in the first home's room powered on, the player walked in at the
    door: the pieces in RAM, a talk before each (run in a process of its own)."""
    import aflz
    import emu_memory
    import emu_psel
    import emu_town
    import emu_year
    from n64emu import A
    os.makedirs(out, exist_ok=True)
    rom = open(rom_path, "rb").read()
    cart = open(os.path.join(os.path.dirname(os.path.abspath(af_en)), "baserom.z64"), "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    start = syms[af_gc_nes.SEGMENT + "_ROM_START"]
    objects, list_at = nes_objects(gc_dir)
    pieces = af_gc_nes.cart_pieces(cart)
    # each piece's Famicom on the cartridge: its vertices, from the block of textures' end to its first list
    famicoms = [file_at(cart, words[af_gc_nes.P_ROM])[0x820:words[af_gc_nes.P_LIST] & 0xFFFFFF]
                for _, _, words in pieces]
    save = bytearray(open(town, "rb").read())
    flags = struct.unpack(">I", save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4])[0] & ~emu_year.JOB_BITS
    save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4] = struct.pack(">I", flags)
    places = nes_places(save, len(af_gc_nes.PIECES))
    check(len(places) == len(af_gc_nes.PIECES), "the game: the first home's room has a place for each of the %d "
          "pieces with a free place before it (%s)" % (len(af_gc_nes.PIECES), places))
    if len(places) != len(af_gc_nes.PIECES):
        return 1
    for (x, z), piece in zip(places, af_gc_nes.PIECES):
        at = emu_memory.HOME_LAYER + (z * 16 + x) * 2
        save[at:at + 2] = struct.pack(">H", emu_memory.FURNITURE + piece * 4)
    image, compressed = aflz.pack(emu_psel.fix(bytes(save)), emu_year.town_ext(town))
    if not compressed:
        raise SystemExit("emu_signs: the town does not fit a compressed slot")
    emu_save.write_fla(emu_save.fla_path(out, rom), image + image)
    cd = syms["common_data"]
    with N64(emu_dir, rom, out, timeout=60, clock=NES_DATE) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)

        def calm(want, tries=40):
            """Talks played through until the scene is `want` and the player waits."""
            for _ in range(tries):
                try:
                    emu_town.follow(t, quiet=4)
                    if t.player_main() == emu_year.PLAYER_WAIT and t.window_main() == emu_year.HIDDEN and \
                            t.i32(cd + emu_memory.SCENE) == want:
                        return True
                except (ValueError, struct.error):          # between two scenes the pointers read are no one's
                    pass
                n64.frames(15, 0)
            return False
        arrived = emu_year.arrive(t)
        size = n64.read(cd + emu_town.HOMES_AT + emu_memory.HOME_BITS, 1)[0] >> 6
        inside = False
        if arrived:
            t.drop(emu_town.MAT[0], emu_town.MAT[1], 40)
            n64.frames(15, emu_town.INTO_DOOR)
            n64.frames(3, emu_town.INTO_DOOR | A)
            inside = calm(emu_memory.HOUSE_SCENES[size])
        check(inside, "the game: the town powered on, the player walked into the first home at its door")
        if not inside:
            return 1
        n64.frames(120, 0)
        n64.screenshot(os.path.join(out, "nes.png"))
        room = emu_memory.loaded_overlays(n64, syms, emu_year.actor_names(af_en)).get("My_Room")
        check(bool(room), "the game: the room's actor's overlay is loaded")
        if not room:
            return 1
        base = room - syms["ovl_My_Room_VRAM"]
        ram = n64.read(0x80000000, 0x800000)
        size = len(objects[0]) if objects else None
        for index, (piece, (vrom, vram, words)) in enumerate(zip(af_gc_nes.PIECES, pieces)):
            loaded = n64.u32(base + syms[NES_LOADED] + 4 * piece)
            profile = n64.u32(base + syms[NES_PROFILES] + 4 * piece)
            got = list(struct.unpack(">%dI" % af_gc_nes.PROFILE_WORDS,
                                     n64.read(profile, 4 * af_gc_nes.PROFILE_WORDS))) if profile else []
            expect = nes_profile(words, start, index, size, list_at) if objects else list(words)
            expect[16] = loaded + af_gc_nes.FUNCS_AT                   # its functions, where the overlay is loaded
            where = ram.find(objects[index]) if objects else ram.find(famicoms[index])
            check(bool(loaded) and profile == loaded + af_gc_nes.PROFILE_AT and got == expect and where >= 0
                  and (not objects or ram.find(famicoms[index]) < 0),
                  "the game: piece %d: its overlay loaded, its profile the ROM's, %s" % (
                      piece, "its object in RAM whole (at %#x), the cartridge's Famicom not" % (0x80000000 + where)
                      if objects else "the cartridge's Famicom in RAM"))
        # A before each piece: the drive's and six of the games' first, the last game's last (after its yes the
        # emulator of the console starts)
        order = sorted(zip(af_gc_nes.PIECES, places), key=lambda v: (af_gc_nes.CART[v[0]][0] == 7, v[0]))
        for piece, (x, z) in order:
            game = af_gc_nes.CART[piece][0]
            last = piece == order[-1][0]
            pages = []
            t.drop(x * PLACE_SIZE + PLACE_SIZE / 2, (z + 1) * PLACE_SIZE + PLACE_SIZE / 2, 20)
            n64.frames(10, 0)
            n64.frames(12, stick(0, 80))                    # up to the piece: the player turns to it
            n64.frames(4, 0)
            n64.press(A, 3, 40)
            handed = 0
            if not last:
                emu_town.follow(t, answers=(1,), quiet=6, pages=pages)
            else:                                           # yes: the game's number is handed on, and no frame after
                for _ in range(900):
                    handed = n64.read(cd + NES_GAME_AT, 1)[0]
                    if handed:
                        break
                    if t.window_main() == emu_year.WAITING:
                        page = t.page()
                        if page and page[0] not in [p["msg"] for p in pages]:
                            pages.append({"msg": page[0], "problems": t.page_problems(page[2])})
                    if t.choice_waiting():
                        n64.frames(20, 0)
                        n64.press(A, 3, 1)
                    elif t.window_main() != emu_year.HIDDEN:
                        n64.press(A, 3, 1)
                    else:
                        n64.frames(1, 0)
            asked = [p["msg"] for p in pages]
            problems = [q for p in pages for q in p["problems"]]
            now = handed if last else n64.read(cd + NES_GAME_AT, 1)[0]
            if game:
                check(asked[:1] == [NES_MSG + game] and not problems and now == (game if last else 0),
                      "the game: A before piece %d: the cartridge's message for game %d (%#x), %s (pages %s%s)" % (
                          piece, game, NES_MSG + game, "answered yes: the game's number handed to the emulator's "
                          "start" if last else "answered no", ", ".join("%#x" % m for m in asked),
                          "; " + "; ".join(problems[:2]) if problems else ""))
            else:
                check(len(asked) == 1 and asked[0] in NES_NO_GAME and not problems and now == 0,
                      "the game: A before piece %d, the drive's: the cartridge's page for it and no game handed on "
                      "(pages %s%s)" % (piece, ", ".join("%#x" % m for m in asked),
                                        "; " + "; ".join(problems[:2]) if problems else ""))
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--room":
        sys.exit(room_visit(*sys.argv[2:8]))
    if len(sys.argv) > 1 and sys.argv[1] == "--nes":
        sys.exit(nes_visit(*sys.argv[2:8]))
    sys.exit(main(sys.argv[1:]))
