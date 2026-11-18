#!/usr/bin/env python3
"""The tools, played: the rod, the net, the shovel and the axe used as a player uses them, in the en build.

    tests/emu_tools.py EMU_DIR EN_ROM AF_EN_DIR TOWN OUT_DIR

TOWN is a town made by tests/emu_psel.py (make psel-check-en's 2-two.town), its first player's first job done.
No other check takes a tool in hand: what a catch says, with the name of what was caught in it, and what the
game asks when the pockets are full, had never been on the screen of the en build. Here the first player's
pockets are filled by hand before power-on (the four tools, a table and ten apples: fifteen pockets, none
free) and everything after it is done with the pad, the allocator's hook in place (tests/emu_memory.py's
watch):

  the rod    taken from its pocket to the player's figure in the item screen (the tag's first entry, as
             clothes are put on): the tool in hand is PrivateInfo + 0x3EC and its pocket is empty. A river's
             block walked to; a fish found (the fish actor keeps its shadows' places: a point of its that lies
             over a unit of water), the player set on the land nearest it and turned to it, A: the line is
             cast. The bobber's own state (its actor + 0x1D8, read in the running game: 4 while it floats, 6 at
             a nibble, 7 while the fish pulls) says when to press A. What the player says is read; a fish is in
             the one pocket that was free.
  the net    held the same way (the rod goes back to the net's pocket: the pockets are full now). An insect
             found (a point of the insect actor's above the ground of its block), waited for until it is 25 to
             65 units from the player, the player turned to it, A. The catch, and then, the pockets being
             full, the question whether to swap it for something: the first answer, the item screen with the
             insect in the hand, A over an apple's pocket (the two change places), B (the apple is let go).
             That screen opens with the hand where the last one left it, not on the first pocket: the hand is
             taken to the pockets' last row and first column first.
  the shovel A before a free unit of grass digs a hole (the unit's item in the save's map changes); with the
             hole before the player the table's tag has a third entry, to bury it: the pocket is empty and the
             unit holds the table; A digs it up: the player says what it dug up, the table is back in the
             pockets and the hole is there again; the player, who turned to say it, turned back, A fills the
             hole in. (A fruit is not buried but planted: the unit holds a sapling, and the fruit is gone.)
  the axe    A before a grown tree until the tree is no more (the unit's item changes).

A rainy day has no butterflies, and which day rains is the game's draw: where no insect is found in any of the
blocks around, the whole session is played again on the next day, three days at most.

Every page read on the way is judged as the other checks judge one (emu_year's Town.page_problems); the arena
must never say no.
"""

import json
import math
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "emu"))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, HERE)

import emu_job  # noqa: E402
import emu_memory  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_year  # noqa: E402
from afplay import DIRECTIONS  # noqa: E402
from n64emu import N64, A, B, START, Hang, stick  # noqa: E402

NET, AXE, SHOVEL, ROD = 0x2200, 0x2201, 0x2202, 0x2203      # the tools' items (m_name_table's kind 0x22)
APPLE, TABLE = 0x2800, 0x1270
FISH, INSECTS = range(0x2300, 0x2400), range(0x2D00, 0x2E00)
POCKETS = [ROD, NET, SHOVEL, AXE, TABLE] + [APPLE] * 10      # the first player's fifteen pockets at power-on
HELD = 0x3EC                            # PrivateInfo: the tool in the player's hands (read in the running game)
DAYS = ((2001, 4, 15, 11, 0, 0), (2001, 4, 16, 11, 0, 0), (2001, 4, 17, 11, 0, 0))
# the ground: g_fdinfo (FieldMakeInfo) -> blockInfo, 0x614 bytes a block, its 16 x 16 units' collision words at
# + 0x20 (m_field_make.h); a word's low six bits are the unit's attribute (m_collision_bg.h)
BLOCK_INFO, X_MAX, BLOCK_SIZE, UNITS_AT = 0x148, 0x166, 0x614, 0x20
GRASS, LAND = set(range(0, 4)), set(range(0, 8)) | {22}
WATER = set(range(12, 22)) | {24}       # a pond, a waterfall, the river's eight ways, the sea
UNIT, SIDE = 40.0, 16
TREE = 0x0804                           # a grown tree (TREE_SAPLING + 4)
UKI_STATE, FLOATING, BITE = 0x1D8, 4, 7     # the bobber's actor: what it is doing
SPAN, FIRST = 0x1800, 0x160             # the fish actor's and the insect actor's memory looked through
NEAR, FAR = 25.0, 65.0                  # the net lands 40 to 53 units before the player, and takes what is within
                                        # 39 of its way down (func_808CC54C_jp: the insect's 24 and 15 more)
CASTS, SWINGS, STROKES = 3, 8, 8
BLOCKS = 6                              # the blocks looked into for a fish, and for an insect

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def tooled(save):
    """The town's save with the first player's pockets as the check wants them, nothing marked, nothing held."""
    save = bytearray(save)
    at = emu_job.PRIVATE
    save[at + emu_job.POCKETS:at + emu_job.POCKETS + 30] = struct.pack(">15H", *POCKETS)
    save[at + emu_job.CONDITIONS:at + emu_job.CONDITIONS + 4] = bytes(4)
    save[at + HELD:at + HELD + 2] = bytes(2)
    return bytes(save)


def nearest(attrs, block, x, z, kinds=LAND, reach=120.0):
    """The centre of the unit of one of the kinds nearest the point (x, z) of a block, or None past reach."""
    bx, bz = block
    best = None
    for uz in range(SIDE):
        for ux in range(SIDE):
            if attrs[uz][ux] in kinds:
                cx, cz = bx * emu_year.BLOCK + ux * UNIT + UNIT / 2, bz * emu_year.BLOCK + uz * UNIT + UNIT / 2
                d = math.hypot(cx - x, cz - z)
                if d <= reach and (best is None or d < best[0]):
                    best = (d, cx, cz)
    return best and best[1:]


def unit_of(block, x, z):
    return int((x - block[0] * emu_year.BLOCK) // UNIT), int((z - block[1] * emu_year.BLOCK) // UNIT)


def before_one(attrs, items, wanted, rows=range(1, SIDE - 2)):
    """(ux, uz) of a unit that holds the wanted item with a free unit of grass below it (where the player
    stands, looking up at it), both of grass and away from the block's edges; or None."""
    for uz in rows:
        for ux in range(1, SIDE - 1):
            if items[uz][ux] == wanted and items[uz + 1][ux] == 0 and attrs[uz][ux] in GRASS and \
                    attrs[uz + 1][ux] in GRASS:
                return ux, uz
    return None


def points(raw, block, keep):
    """The places an actor's memory holds within a block (three floats, kept by keep(x, y, z)), each once."""
    bx, bz = block
    out = []
    for off in range(FIRST, len(raw) - 12, 4):
        x, y, z = struct.unpack(">3f", raw[off:off + 12])
        if not (x == x and y == y and z == z):                              # a NaN is no place
            continue
        if bx * emu_year.BLOCK <= x < (bx + 1) * emu_year.BLOCK and bz * emu_year.BLOCK <= z < (bz + 1) * emu_year.BLOCK \
                and keep(x, y, z) and all(math.hypot(x - o[1], z - o[3]) > 5 for o in out):
            out.append((off, x, y, z))
    return out


def play(emu_dir, rom_path, af_en, town, work, clock):
    """The town powered on at the clock, the tools in the first player's pockets: a dict for the report."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    os.makedirs(work, exist_ok=True)
    mine = os.path.join(work, "tools.town")
    emu_year.write_town(mine, tooled(open(town, "rb").read()), emu_year.town_ext(town))
    emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(mine))
    result = {"clock": list(clock), "arrived": False, "hang": None, "pages": [], "held": [], "fish": None,
              "insect": None, "shovel": None, "axe": None, "watch": None}
    with N64(emu_dir, rom, work, timeout=60, clock=tuple(clock)) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)
        p = emu_job.Player(n64, t, syms, result["pages"])
        cd = syms["common_data"]

        def held():
            return struct.unpack(">H", p.private(HELD, 2))[0]

        def attrs(block):
            info = n64.u32(syms["g_fdinfo"])
            raw = n64.read(n64.u32(info + BLOCK_INFO) + (block[0] + block[1] * n64.u8(info + X_MAX)) * BLOCK_SIZE
                           + UNITS_AT, SIDE * SIDE * 4)
            words = struct.unpack(">%dI" % (SIDE * SIDE), raw)
            return [[words[z * SIDE + x] & 0x3F for x in range(SIDE)] for z in range(SIDE)]

        def items(block):
            raw = n64.read(cd + emu_town.FG_AT + ((block[1] - 1) * 5 + block[0] - 1) * emu_town.FG_BLOCK,
                           SIDE * SIDE * 2)
            words = struct.unpack(">%dH" % (SIDE * SIDE), raw)
            return [[words[z * SIDE + x] for x in range(SIDE)] for z in range(SIDE)]

        def item(block, ux, uz):
            return items(block)[uz][ux]

        def actor(name):
            return next((a for a in t.actors() if a["name"] == name), None)

        def yaw():
            return struct.unpack(">h", n64.read(t.player() + emu_year.A_SHAPE_ROT_Y, 2))[0] * 360.0 / 65536

        def off_by(dx, dz):
            return (yaw() - math.degrees(math.atan2(dx, dz)) + 180) % 360 - 180

        def face(dx, dz):
            """Short pushes of the stick until the figure looks that way (a push moves it a step or two)."""
            d = math.hypot(dx, dz) or 1.0
            for _ in range(8):
                if abs(off_by(dx, dz)) <= 12:
                    return True
                n64.frames(2, stick(int(round(80 * dx / d)), int(round(-80 * dz / d))))
                n64.frames(8, 0)
            return abs(off_by(dx, dz)) <= 12

        def land(x, z):
            """The player put at (x, z) from above, and waited for until it stands there: the pad does nothing
            while it falls."""
            t.drop(x, z, 10)
            for _ in range(120):
                if t.player_main() == emu_year.PLAYER_WAIT:
                    break
                n64.frames(1, 0)
            n64.frames(4, 0)
            return t.player_main() == emu_year.PLAYER_WAIT

        def enter(block):
            """Into a block, onto the unit of land nearest its middle."""
            if t.block() == block:
                return True
            spot = nearest(attrs(block), block, (block[0] + 0.5) * emu_year.BLOCK, (block[1] + 0.5) * emu_year.BLOCK,
                           GRASS, 400.0)
            return bool(spot) and t.go(spot[0], spot[1]) and land(spot[0], spot[1])

        def around(want=lambda kinds: True):
            """The town's blocks of a kind, the nearest first."""
            here = t.block()
            return [b for _, b in sorted((abs(b[0] - here[0]) + abs(b[1] - here[1]), b)
                                         for b, kinds in t.blocks().items() if want(kinds))]

        def hold(tool):
            """A tool from its pocket to the player's figure."""
            pockets = p.pockets()
            if tool not in pockets:
                result["held"].append([tool, False])
                return False
            slot = pockets.index(tool)
            p.items()
            p.hand(slot)
            p.tag(0)                                # the tool in the hand
            p.hand(0, slot)                         # the hand over the first pocket,
            n64.frames(6, DIRECTIONS["u"])          # and up to the player's figure
            n64.frames(25, 0)
            n64.press(A, 3, 60)
            n64.frames(60, 0)
            p.items(False)
            n64.frames(30, 0)
            ok = held() == tool and p.pockets()[slot] != tool
            result["held"].append([tool, ok])
            return ok

        def said(mark):
            return [pg["msg"] for pg in result["pages"][mark:]]

        def fish():
            out = {"blocks": 0, "casts": 0, "bite": None, "pages": [], "got": None, "ended": None}
            for block in around(lambda kinds: "river" in kinds)[:BLOCKS]:
                if out["got"] or not enter(block):
                    continue
                out["blocks"] += 1
                ground = attrs(block)
                for _ in range(CASTS):
                    gyoei = actor("Gyoei")
                    swimming = gyoei and points(n64.read(gyoei["addr"], SPAN), block, lambda x, y, z: y > 50.0 and ground[
                        unit_of(block, x, z)[1]][unit_of(block, x, z)[0]] in WATER)
                    stand = swimming and nearest(ground, block, swimming[0][1], swimming[0][3])
                    if not stand:
                        break
                    _, fx, _, fz = swimming[0]
                    if not (land(*stand) and face(fx - stand[0], fz - stand[1])):
                        continue
                    bobber = actor("Uki")
                    if not bobber:
                        break
                    out["casts"] += 1
                    mark, pockets = len(result["pages"]), p.pockets()
                    n64.press(A, 3, 0)
                    for f in range(1500):
                        n64.frames(1, 0)
                        if struct.unpack(">h", n64.read(bobber["addr"] + UKI_STATE, 2))[0] == BITE:
                            out["bite"] = f
                            n64.press(A, 3, 0)
                            break
                    else:                           # no fish came: the line taken in
                        n64.press(A, 3, 90)
                        continue
                    for _ in range(300):
                        if t.window_main() == emu_year.WAITING:
                            break
                        n64.frames(2, 0)
                    n64.screenshot(os.path.join(work, "fish.png"))
                    out["ended"] = p.advance(600, 12)
                    out["pages"] = said(mark)
                    new = [v for v, old in zip(p.pockets(), pockets) if v != old]
                    out["got"] = next((v for v in new if v in FISH), None)
                    if out["got"]:
                        break
            return out

        def insect():
            out = {"blocks": 0, "swings": 0, "pages": [], "answers": [], "menu": False, "got": None, "let_go": None,
                   "ended": None}
            for block in around()[:BLOCKS]:
                if out["got"] or not enter(block):
                    continue
                n64.frames(40, 0)
                insects = actor("Insect")
                level = t.pos(t.player())[1]

                def flying():
                    return points(n64.read(insects["addr"], SPAN), block, lambda x, y, z: level + 8 < y < level + 90)
                if not insects or not flying():
                    continue
                out["blocks"] += 1
                ground, at = attrs(block), flying()[0][0]
                for f in range(1500):
                    if out["swings"] >= SWINGS:
                        break
                    n64.frames(2, 0)
                    px, _, pz = t.pos(t.player())
                    ix, _, iz = struct.unpack(">3f", n64.read(insects["addr"] + at, 12))
                    d = math.hypot(ix - px, iz - pz)
                    if d > 2 * FAR:                 # it flew off: after it, onto the land nearest it
                        spot = nearest(ground, block, ix, iz + 45.0, GRASS)
                        if not (spot and land(*spot)):
                            break
                        continue
                    if not NEAR <= d <= FAR or not face(ix - px, iz - pz):
                        continue
                    px, _, pz = t.pos(t.player())
                    ix, _, iz = struct.unpack(">3f", n64.read(insects["addr"] + at, 12))
                    if not NEAR <= math.hypot(ix - px, iz - pz) <= FAR or abs(off_by(ix - px, iz - pz)) > 25:
                        continue
                    out["swings"] += 1
                    mark, pockets = len(result["pages"]), p.pockets()
                    n64.press(A, 2, 0)
                    for _ in range(60):
                        if t.window_main() != emu_year.HIDDEN:
                            break
                        n64.frames(2, 0)
                    if t.window_main() == emu_year.HIDDEN:
                        continue                    # the net came down on nothing
                    for _ in range(100):
                        if t.window_main() == emu_year.WAITING:
                            break
                        n64.frames(2, 0)
                    n64.screenshot(os.path.join(work, "insect.png"))

                    def pick(count):
                        out["answers"].append(count)
                        return 0
                    how = p.advance(600, 12, pick)
                    if how == "menu":               # the pockets are full: the insect in the hand, over them
                        out["menu"] = True
                        slot = pockets.index(APPLE)
                        n64.frames(40, 0)
                        for way, steps in (("l", 7), ("d", 4), ("u", 2)):       # the hand is where the last
                            for _ in range(steps):                              # item screen left it: to the
                                n64.frames(6, DIRECTIONS[way])                  # first pocket, by the last
                                n64.frames(14, 0)                               # row's first
                        p.hand(slot)
                        n64.press(A, 3, 0)          # the insect to the pocket, the apple to the hand
                        n64.frames(70, 0)
                        n64.screenshot(os.path.join(work, "swap.png"))
                        n64.press(B, 3, 0)          # and the apple let go
                        n64.frames(70, 0)
                        how = p.advance(600, 12, pick)
                        out["let_go"] = pockets[slot] if p.pockets()[slot] != pockets[slot] else None
                    out["ended"] = how
                    out["pages"] = said(mark)
                    new = [v for v, old in zip(p.pockets(), pockets) if v != old]
                    out["got"] = next((v for v in new if v in INSECTS), None)
                    break
            return out

        def stand_before(block, ux, uz):
            x, z = block[0] * emu_year.BLOCK + ux * UNIT + UNIT / 2, block[1] * emu_year.BLOCK + (uz + 1) * UNIT + UNIT / 2
            return land(x, z) and face(0, -1)

        def strike(block, ux, uz, changed):
            """A, and the unit's item once the tool has come down."""
            n64.press(A, 3, 0)
            for _ in range(80):
                n64.frames(2, 0)
                if changed(item(block, ux, uz)) and t.player_main() == emu_year.PLAYER_WAIT:
                    break
            p.advance(200, 8)
            return item(block, ux, uz)

        def shovel():
            out = {"spot": None, "hole": None, "buried": None, "tables": [], "dug": None, "pages": [], "filled": None}
            for block in around()[:BLOCKS]:
                if not enter(block):
                    continue
                spot = before_one(attrs(block), items(block), 0, range(8, SIDE - 2))
                if not spot or not stand_before(block, *spot):
                    continue
                out["spot"] = [list(block), list(spot)]
                out["hole"] = strike(block, *spot, lambda v: v != 0)
                if not out["hole"] or TABLE not in p.pockets():
                    break
                slot = p.pockets().index(TABLE)
                p.items()
                p.hand(slot)
                p.tag(2)                            # with a hole before the player: grab, drop, bury
                n64.frames(90, 0)
                p.items(False)
                n64.frames(30, 0)
                out["buried"] = item(block, *spot)
                out["tables"].append(p.pockets().count(TABLE))
                mark = len(result["pages"])
                out["dug"] = strike(block, *spot, lambda v: v != out["buried"])
                out["pages"] = said(mark)
                out["tables"].append(p.pockets().count(TABLE))
                if face(0, -1):                     # the player turned to say what it dug up
                    out["filled"] = strike(block, *spot, lambda v: v != out["dug"])
                break
            return out

        def axe():
            out = {"spot": None, "strokes": 0, "tree": TREE, "left": TREE}
            for block in around()[:BLOCKS]:
                if not enter(block):
                    continue
                spot = before_one(attrs(block), items(block), TREE)
                if not spot or not stand_before(block, *spot):
                    continue
                out["spot"] = [list(block), list(spot)]
                for _ in range(STROKES):
                    out["strokes"] += 1
                    out["left"] = strike(block, *spot, lambda v: v != TREE)
                    if out["left"] != TREE:
                        break
                break
            return out
        try:
            low, used = emu_memory.watch_soon(n64, af_en, syms)
            n64.frames(400 - used, 0)
            n64.press(START, 3, 60)
            for _ in range(60):
                if p.advance(400, 12) == "free" and p.scene() == emu_job.FG:
                    result["arrived"] = True
                    break
                n64.frames(30, 0)
            if result["arrived"]:
                result["fish"] = hold(ROD) and fish() or None
                result["insect"] = hold(NET) and insect() or None
                result["shovel"] = hold(SHOVEL) and shovel() or None
                result["axe"] = hold(AXE) and axe() or None
            result["watch"] = low()
        except Hang as e:
            result["hang"] = str(e)
    result["problems"] = ["%#x at %d: %s" % (pg["msg"], pg["at"], ", ".join(pg["problems"]))
                          for pg in result["pages"] if pg["problems"]]
    result["messages"] = len({pg["msg"] for pg in result["pages"]})
    result["pages"] = len(result["pages"])
    return result


def report(run):
    """The checks, from play()'s answer for the day that stands (the last one played)."""
    held = dict((tool, ok) for tool, ok in run["held"]) if run else {}
    check(bool(run) and run["arrived"] and all(held.get(tool) for tool in (ROD, NET, SHOVEL, AXE)),
          "the four tools, each from its pocket to the player's figure in the item screen: %s" % (
              "the tool in hand is the one taken (PrivateInfo + %#x) and its pocket has it no more; the tool held "
              "before goes back to that pocket" % HELD if run and len(held) == 4 and all(held.values())
              else "NOT HELD (%s)" % (run and {"arrived": run["arrived"], "held": run["held"], "hang": run["hang"]},)))
    fish = run and run["fish"]
    check(bool(fish) and fish["got"] in FISH and fish["ended"] == "free" and len(fish["pages"]) >= 1,
          "the rod: %s" % (
              "the line cast before a fish of the river (cast %d), the bobber pulled under %d frames on, A: the catch "
              "is said in %d page%s (the message %#x) and the fish %#x is in the pocket that was free" % (
                  fish["casts"], fish["bite"], len(fish["pages"]), "" if len(fish["pages"]) == 1 else "s",
                  fish["pages"][0], fish["got"])
              if fish and fish["got"] and fish["pages"] else "NO FISH (%s)" % (fish,)))
    bug = run and run["insect"]
    check(bool(bug) and bug["got"] in INSECTS and bug["ended"] == "free" and bug["menu"] and bug["answers"] == [2] and
          bug["let_go"] == APPLE and len(bug["pages"]) >= 2,
          "the net, the pockets full: %s" % (
              "an insect waited for and the net swung at it (swing %d): the catch is said, and the pockets being "
              "full the question of its two answers (%d pages; the messages %s); the first, the item screen with "
              "the insect in the hand, A over an apple, B: the insect %#x is in the apple's pocket and the apple "
              "let go" % (bug["swings"], len(bug["pages"]), ", ".join("%#x" % m for m in sorted(set(bug["pages"]))),
                          bug["got"])
              if bug and bug["got"] and bug["menu"] and bug["let_go"] else "NOT CAUGHT AND SWAPPED (%s)" % (bug,)))
    dig = run and run["shovel"]
    check(bool(dig) and bool(dig["hole"]) and dig["buried"] == TABLE and dig["tables"] == [0, 1]
          and dig["dug"] == dig["hole"] and len(dig["pages"]) >= 1 and dig["filled"] == 0,
          "the shovel: %s" % (
              "A before a free unit of grass digs a hole (the unit's item %#x); the table's tag then has its third "
              "entry, which buries it (the unit holds it, the pockets do not); A digs it up: the player says what "
              "it dug up (the message %#x), the table is back in the pockets and the hole is there again; A fills "
              "it in" % (dig["hole"], dig["pages"][0])
              if dig and dig["hole"] and dig["buried"] == TABLE and dig["tables"] == [0, 1] and dig["pages"]
              and dig["filled"] == 0 else "NOT DUG, BURIED AND DUG UP (%s)" % (dig,)))
    cut = run and run["axe"]
    check(bool(cut) and bool(cut["spot"]) and cut["left"] != TREE,
          "the axe: %s" % (
              "A before a grown tree, %d strokes: the tree is no more (the unit's item %#x -> %#x)" % (
                  cut["strokes"], TREE, cut["left"])
              if cut and cut["spot"] and cut["left"] != TREE else "THE TREE STANDS (%s)" % (cut,)))
    seen = run and run["watch"]
    clean = bool(run) and run["arrived"] and not run["hang"] and not run["problems"] and bool(seen) and \
        seen["fails"] == 0
    check(clean,
          "the tools' pages: %s" % (
              "%d pages of %d messages read, clean; the arena never said no (%d allocations, the largest free block "
              "%s at its least)" % (run["pages"], run["messages"], seen["calls"], seen["least"])
              if clean else "NOT CLEAN (%s)" % (run and {k: run[k] for k in ("arrived", "hang", "problems", "watch")},)))


def main(argv):
    if argv[:1] == ["--play"]:
        print(json.dumps(play(argv[1], argv[2], argv[3], argv[4], argv[5], json.loads(argv[6]))))
        return 0
    emu_dir, rom_path, af_en, town, out = argv
    af_en = os.path.abspath(af_en)
    os.makedirs(out, exist_ok=True)
    print("emu_tools: %s, the tools in the town %s" % (rom_path, town))
    run, days = None, []
    for n, clock in enumerate(DAYS):
        r = subprocess.run([sys.executable, os.path.abspath(__file__), "--play", emu_dir, rom_path, af_en, town,
                            os.path.join(out, "day-%d" % (n + 1)), json.dumps(clock)], capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
        if r.returncode or not lines:
            print(r.stdout[-1500:], r.stderr[-1500:])
            break
        run = json.loads(lines[-1])
        days.append(run)
        if run["insect"] and run["insect"]["blocks"]:           # an insect was about: the day stands
            break
        print("  no insect about on %04d-%02d-%02d (%d blocks looked into): the next day" % (
            clock[0], clock[1], clock[2], BLOCKS))
    report(run)
    json.dump(days, open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_tools: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
