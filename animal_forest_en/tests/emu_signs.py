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

The police box's two posters inside (af_own_signs' POSTERS, in the room's
file): in the ROM ours, as the tool makes them from the cartridge's; and
with --town (make psel-check-en's town of two players, as make
year-check-en powers it on), in a process of its own: the town powered on,
the player walked to the police box's block and in at its door as the box
lets one in (the door's point and the way in from its actor's check), and
in the room, the posters in RAM ours and the cartridge's not.

The furniture's words (tools/af_own_ftr.py; NOTES, "Our own furniture
words"): each object in the ROM (through the dmadata, decompressed as the
game reads it) holds ours where the cartridge's texture was, as the tool
makes it from the cartridge's, and the rest of the object is the
cartridge's: its lists, which set the render tile at that TMEM address
(the tool's check), unchanged.
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
import af_font  # noqa: E402
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
    gc = found = None
    if gc_dir and os.path.exists(os.path.join(gc_dir, "foresta.rel.szs")):
        layout = af_font.map_layout(open(os.path.join(gc_dir, "foresta.map"), errors="replace").read())
        names = [n for n, _, _, _, _ in af_gc_signs.SIGNS] + [g for g, _ in af_gc_signs.PALETTES]
        found = {sym: (o, s) for o, s, sym, ob in layout[".data"] if ob == af_gc_signs.GC_OBJECT and sym in names}
        gc = af_font.gc_rel_data(open(os.path.join(gc_dir, "foresta.rel.szs"), "rb").read(), layout,
                                 max(o + s for o, s in found.values()))
    print("emu_signs: the signs %s" % ("against the GameCube's" if gc else "against the cartridge's only"))

    # the ROM: each copy the en build's, not the cartridge's (and the GameCube's, given its files)
    signs = {}
    for name, offsets, (w, h), _, what in af_gc_signs.SIGNS:
        size = w * h // 2
        ours = [obj[at:at + size] for at in offsets]
        theirs = gc and af_gc_signs.untile_ci4(gc[found[name][0]:found[name][0] + size], w, h)
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
        theirs = gc and b"".join(struct.pack(">H", af_gc_signs.rgb5a3_to_rgba5551(struct.unpack(">H", gc[
            found[theirs_name][0] + 2 * i:found[theirs_name][0] + 2 * i + 2])[0])) for i in range(16))
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

    room, cart_room = file_at(rom, af_own_signs.ROOM_VROM), file_at(cart, af_own_signs.ROOM_VROM)
    posters = af_own_signs.compose_room(cart_room)
    for at, w, h, _, _, _, _, _, _, what in af_own_signs.POSTERS:
        size = w * h // 2
        check(room[at:at + size] == posters[at] != cart_room[at:at + size],
              "the ROM: %s at %#x of the police box's room ours, as af_own_signs makes it from the cartridge's" % (
                  what, at))
    for vrom, tmem, w, h, base, _, _, _, what, ops in af_own_ftr.FURNITURE:
        at, size = base + tmem * 8, w * h // 2
        ours, theirs = file_at(rom, vrom), file_at(cart, vrom)
        new = af_own_ftr.compose(theirs[at:at + size], w, h, ops)
        check(ours[at:at + size] == new != theirs[at:at + size] and ours[:at] == theirs[:at]
              and ours[at + size:] == theirs[at + size:],
              "the ROM: %s (vrom %#x), ours at %#x as af_own_ftr makes it from the cartridge's, the rest of the "
              "object the cartridge's" % (what, vrom, at))
    if town:
        r = subprocess.run([sys.executable, os.path.abspath(__file__), "--room", emu_dir, rom_path, af_en, town,
                            os.path.join(out, "room")], capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("  ok  ") or ln.startswith("  FAIL")]
        for ln in lines:
            print(ln)
            if ln.startswith("  FAIL"):
                failures.append(ln[8:])
        if r.returncode and not any(ln.startswith("  FAIL") for ln in lines):
            check(False, "the game: the police box's room visited (%s)" % (r.stdout + r.stderr)[-600:])
    else:
        print("emu_signs: the police box's room not visited (no --town)")

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


def room_visit(emu_dir, rom_path, af_en, town, out):
    """The saved town powered on, the player walked into the police box at its door, the posters looked for in
    RAM (run in a process of its own: the emulator's plugins start once per process)."""
    import emu_year
    os.makedirs(out, exist_ok=True)
    rom = open(rom_path, "rb").read()
    cart_room = file_at(open(os.path.join(os.path.dirname(os.path.abspath(af_en)), "baserom.z64"), "rb").read(),
                        af_own_signs.ROOM_VROM)
    posters = af_own_signs.compose_room(cart_room)
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    emu_save.write_fla(emu_save.fla_path(out, rom), emu_year.town_image(town))
    with N64(emu_dir, rom, out, timeout=60, clock=ROOM_DATE) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en))
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
            for at, w, h, _, _, _, _, _, _, what in af_own_signs.POSTERS:
                theirs = cart_room[at:at + w * h // 2]
                check(ram.find(posters[at]) >= 0 and ram.find(theirs) < 0,
                      "the game: in the police box, %s in RAM ours, the cartridge's not" % what)
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--room":
        sys.exit(room_visit(*sys.argv[2:7]))
    sys.exit(main(sys.argv[1:]))
