#!/usr/bin/env python3
"""The first job, played: a new game's first hour as the game has a player go through it, in the en build.

    tests/emu_job.py EMU_DIR EN_ROM AF_EN_DIR STATE OUT_DIR

STATE is a state of EN_ROM in play (make route-en's 4-houses.st): a new game, Nook showing the houses. The
game lets nobody save before the first job for Nook is done, an hour of errands; the towns the other checks
power on are stamped by hand with that job marked done (tests/emu_year.py, town_image), so none of them goes
this road. Here it is gone with the pad, as a player goes it, the allocator's hook in place
(tests/emu_memory.py's watch):

  the first house chosen by the game's own talk (tests/emu_town.py); then, chore by chore, what the job's
  record in the player's data asks for (PrivateInfo + 0x25C, the first of its errands: its kind, its progress,
  2 while a chore is open and 0 once it is done, and the animal it is for; mQst_GetFirstJobData), Nook spoken
  to in his shop between them:

    3   the work clothes he hands over put on: the item screen (START), the pocket's tag's first entry, which
        takes the clothes in the hand, the hand moved to the player's figure, A;
    4   the bags of seeds and the saplings planted: each one's tag's second entry, at a free place by the shop;
    12  everybody greeted: every animal of the town found and spoken to, until each one's own memory holds
        the player (Animal_c.memories);
    5   a piece of furniture delivered to the animal the record names: spoken to, the first answer; where the
        item screen opens to ask which, the pocket Nook filled;
    6   a letter written to the animal the record names (the paper's tag's second entry, the address book's
        entry, letters typed on the dial until the body is past the 96 bytes the cartridge keeps, START, the
        end's question answered) and mailed at the post office (the clerk's first answer, the letter chosen
        in the item screen, no second one);
    13  Nook not ready: an animal asked for work, then Nook again;
    7   a carpet delivered; 8 an axe delivered (to the letter's animal, who shows the letter that came);
    9   a notice written on the bulletin board by the houses (A at the board, A to write, the dial, START, the
        end's question answered, B to leave);

  and Nook's last word: the record empty, the job's two event flags cleared by the game itself
  (emu_year.JOB_BITS), the player sent out of the shop. Then the first Bells: back in the shop, now its
  keeper's, something of the pockets is sold (his second answer, the pocket chosen in the item screen, his
  price taken) and something bought off his table (A before it, the first answer); the pocketbook must go up
  and down and the pockets with it; and at the post office, whose clerk now takes payments towards the house
  (her second answer), a hundred Bells are set on the counter and paid. Then the player's own gyroid, which had no save to offer, saves and the
  game leaves for its title; and the flash it wrote is powered on again in a process of its own
  (tests/emu_memory.py's session): the town loads, the house is gone into, the gyroid saves again.

  The letter is followed to its end. Its animal keeps it, the cartridge's bytes of it (Anmmem_c.letter), and
  shows it at the last delivery; by the gyroid's save none of the save's own letter places holds it any
  more (the pockets, the mailboxes, the post office's). The en build keeps the rest of a letter in the
  save's extension under a key made of the letter's bytes and drops what no letter holds: the letter an
  animal keeps must hold its rest too, in the flash the gyroid wrote and in the next morning's.

  And the animals' own errands, in a third power-on of the flash an hour later (errand(), a process of its
  own). Chore 13 left one open: the animal asked for work gave a thing for another (PrivateInfo.deliveries).
  It is taken there: the animal the record names spoken to, the first answer, the item screen over the talk,
  the thing handed over for its thanks. Then work is asked for anew, animal by animal, until the player's
  record holds some (a delivery, or a thing to fetch, PrivateInfo.errands: which, or a wish of the animal's
  own that is in neither, is the game's draw), and that is followed as its record says, from animal to
  animal, until the record is empty.

Every page read on the way is judged as the other checks judge one (emu_year's Town.page_problems: kana, a
token left in, the name plate, a line drawn past its bubble); the arena must never say no. A choice is
answered by its place, never by its words: the first answer, but for the clerk's "more?" (the last).
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
import aflz  # noqa: E402
import emu_letters  # noqa: E402
import emu_memory  # noqa: E402
import emu_psel  # noqa: E402
import emu_reset  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_year  # noqa: E402
from afplay import DIRECTIONS  # noqa: E402
from n64emu import N64, A, B, START, Hang, stick  # noqa: E402

FG = emu_memory.FG_SCENE
PRIVATE = emu_psel.PRIVATE_AT           # the first player's record in the save (PrivateInfo)
POCKETS, CLOTHES = 0x14, 0xA76          # PrivateInfo: its 15 pockets (u16 each); the clothes worn (the item at +2)
MAIL, MAIL_SIZE = 0x40A, 0xA4           # PrivateInfo.mail: the ten letters carried (Mail_c)
WALLET = 0x38                           # PrivateInfo.inventory: the Bells in the pocketbook
MAILBOX_REACH, MAILBOX_TURNS = 36.0, (-18, 0, 18)   # where a mailbox is opened from: its corner, three tries
COUNTER = tuple((x, z) for z in (240, 220, 200, 260) for x in (180, 220, 260, 300, 140))   # before the shop's table
PAYMENT = 100                           # paid towards the house: the hundreds' figure of the counter up by one
QUEST = 0x25C                           # PrivateInfo.errands[0], the first job's record (QuestErrand, 0x50 bytes)
Q_RECIPIENT = 0x0C                      # QuestErrand.recipient: the animal's name on the map first (0xE000 + n)
NONE = 3                                # QuestBase.questType of a record that holds nothing
# QuestBase.questKind through the first job, in the order Nook gives them (read in the running game)
SHOP, CLOTHE, PLANT, GREET, FURNITURE, LETTER, VILLAGERS, CARPET, AXE, NOTICE = 14, 3, 4, 12, 5, 6, 13, 7, 8, 9
CHORES = (CLOTHE, PLANT, GREET, FURNITURE, LETTER, VILLAGERS, CARPET, AXE, NOTICE)
WHAT = {SHOP: "to the shop", CLOTHE: "the work clothes put on", PLANT: "the seeds and saplings planted",
        GREET: "everybody greeted", FURNITURE: "a piece of furniture delivered", LETTER: "a letter written and mailed",
        VILLAGERS: "an animal asked for work while Nook gets ready", CARPET: "a carpet delivered",
        AXE: "an axe delivered", NOTICE: "a notice written on the bulletin board"}
OPEN, DONE = 2, 0                       # QuestBase.progress
ANIMAL = "Normal_Npc"                   # an animal's actor
POST_DOOR = emu_memory.BUILDINGS["post-office"][2]
BOARD_AT = ((320, 350), (300, 350), (340, 350), (320, 370))     # before the board, from the houses' block's corner
TYPED = "urldurld"                      # on the dial: a letter for each push of the stick
LONG = "urld" * 45                      # and a letter's body past the 96 bytes the cartridge keeps of one
KEY_BYTES = (0x2A, 0xA4)                # a Mail_c's header, body and footer: what a letter's key is made of
AGAIN = (2001, 4, 15, 10, 0, 0)         # the flash powered on again: the next morning
ERRAND = (2001, 4, 15, 11, 0, 0)        # and once more, an hour on: an animal's errand to its end
# PrivateInfo.deliveries (m_private.h): the errands the animals give, one record each (mQst_delivery_c: a
# QuestBase, then the animal it goes to and the one who gave it, their names on the map first; read in the
# running game)
# a room's own map: g_fdinfo (FieldMakeInfo) -> blockInfo, whose first block's FieldMakeFGInfo (+ 0x580) points
# at its 16 x 16 items (m_field_make.h); the units of its way out hold EXIT_DOOR or EXIT_DOOR1 (m_name_table.h)
FIELD_BLOCKS, FIELD_ITEMS, EXIT_DOORS = 0x148, 0x584, (0x4080, 0x4081)
DELIVERIES, DELIVERY_SIZE, DELIVERY_NUM = 0x40, 0x24, 15
D_RECIPIENT, D_SENDER = 0x0C, 0x18
# PrivateInfo.errands: five records as the first job's (QUEST is the first), the animals' once the job is done:
# a thing of an animal's to fetch, which went from animal to animal (QuestErrand; the cartridge's animal ids
# are 12 bytes, so the animal to see next and the one seen last are where the deliveries have theirs)
ERRAND_SIZE, ERRAND_NUM = 0x50, 5
HOPS = 8                                # the animals an errand may send the player to before the check gives up
CONTEST = 0x4EC                         # Animal_c.contestQuest: what an animal wishes for itself (a QuestBase first)
# PrivateInventory.item_conditions: two bits a pocket, the first pocket lowest; an errand's thing is marked
CONDITIONS, COND_QUEST = 0x34, 2

failures = []


def acquainted(save, me=None):
    """The animals of the save (their names on the map) that have met its first player, or the player whose
    name record me is (a visitor's): one of an animal's memories (Animal_c.memories, the bytes between its
    own name and its home) holds the player's own name record (PersonalID_c, the first 16 bytes of
    PrivateInfo)."""
    me = bytes(me or save[PRIVATE:PRIVATE + 0x10])
    out = set()
    for i in range(emu_town.ANIMAL_MAX):
        a = bytes(save[emu_town.ANIMALS_AT + i * emu_town.ANIMAL_SIZE:emu_town.ANIMALS_AT + (i + 1) * emu_town.ANIMAL_SIZE])
        npc = struct.unpack(">H", a[0:2])[0]
        if npc >> 12 == 0xE and me in a[0x0C:emu_town.ANIMAL_HOME]:
            out.add(npc)
    return out


def keeping(save, ext, letter):
    """Where a save and its extension have the letter written for the job ({bytes, key, rest}: its header,
    body and footer, its key, its body's rest): {animal: an animal's memory has its bytes; place: one of the
    save's own letter places has them (the pockets, the mailboxes, the post office's); rest: the body's rest
    the extension has for its key, or None}."""
    if not letter:
        return {"animal": False, "place": False, "rest": None}
    own = bytes.fromhex(letter["bytes"])
    animals = bytes(save[emu_town.ANIMALS_AT:emu_town.ANIMALS_AT + emu_town.ANIMAL_MAX * emu_town.ANIMAL_SIZE])
    places = [bytes(save[at + KEY_BYTES[0]:at + KEY_BYTES[1]]) for at in emu_letters.SAVE_MAIL]
    rest = emu_letters.Ext.parse(bytes(ext)).rest(int(letter["key"], 16)) if ext else None
    return {"animal": own in animals, "place": own in places, "rest": rest[1].hex() if rest else None}


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


class Player:
    """The game played with the pad: talks followed, rooms gone into, the item screen worked."""

    def __init__(self, n64, t, syms, pages):
        self.n64, self.t, self.cd, self.pages = n64, t, syms["common_data"], pages
        self.field = syms.get("g_fdinfo")
        self.read, self.asked, self.menus = set(), 0, 0

    def scene(self):
        return self.t.i32(self.cd + emu_memory.SCENE)

    def private(self, at, size):
        return self.n64.read(self.cd + PRIVATE + at, size)

    def pockets(self):
        return list(struct.unpack(">15H", self.private(POCKETS, 30)))

    def marked(self):
        """The pockets that hold an errand's thing."""
        word = struct.unpack(">I", self.private(CONDITIONS, 4))[0]
        return [k for k in range(15) if word >> 2 * k & 3 == COND_QUEST]

    def clothes(self):
        return struct.unpack(">H", self.private(CLOTHES + 2, 2))[0]

    def wallet(self):
        return struct.unpack(">I", self.private(WALLET, 4))[0]

    def letters(self):
        """How many of the ten letters the player can carry are there (a place without one is blank)."""
        raw = self.private(MAIL, 10 * MAIL_SIZE)
        return sum(1 for i in range(10) if raw[i * MAIL_SIZE:i * MAIL_SIZE + 12].strip(b" "))

    def quest(self):
        """(the first job's kind, its progress, the animal it is for), or None once the record is empty."""
        raw = self.private(QUEST, 0x10)
        if raw[0] >> 6 == NONE:
            return None
        return raw[0] & 0x3F, raw[1] >> 3 & 0xF, struct.unpack(">H", raw[Q_RECIPIENT:Q_RECIPIENT + 2])[0]

    def flags(self):
        return self.n64.u32(self.cd + emu_year.EVENT_FLAGS)

    def advance(self, limit=600, quiet=12, pick=None):
        """A on every page, each choice answered (pick: the number of its answers -> the answer's place; the
        first without it), until the player has waited free for `quiet` looks ("free"), a menu opens over the
        talk ("menu": the item screen, a letter held up) or the tries are out ("limit")."""
        n64, t = self.n64, self.t
        still = 0
        for _ in range(limit):
            try:
                if t.menu_open():
                    self.menus += 1
                    return "menu"
                window = t.window_main()
                if window == emu_year.WAITING:
                    page = t.page()
                    if page and (page[0], page[1]) not in self.read:
                        self.read.add((page[0], page[1]))
                        self.pages.append({"msg": page[0], "at": page[1], "text": emu_year.text(page[2])[:700],
                                           "problems": t.page_problems(page[2])})
                if t.choice_waiting():
                    n64.frames(20, 0)
                    count = len(t.answers())
                    for _ in range(pick(count) if pick else 0):
                        n64.frames(4, DIRECTIONS["d"])
                        n64.frames(8, 0)
                    n64.press(A, 3, 30)
                    self.asked += 1
                    still = 0
                elif window != emu_year.HIDDEN:
                    n64.press(A, 3, 22)
                    still = 0
                else:
                    n64.frames(15, 0)
                    still = still + 1 if t.player_main() == emu_year.PLAYER_WAIT else 0
                    if still >= quiet:
                        return "free"
            except (ValueError, struct.error):          # between two scenes the pointers read are no one's
                n64.frames(15, 0)
        return "limit"

    def talk(self, actor, **kw):
        """The actor spoken to and the talk played: (whether its window opened, how the talk ended)."""
        return emu_reset.approach(self.t, actor), self.advance(**kw)

    def enter(self, outside, doors):
        """Into the building of the block at its door (tests/emu_memory.py's way): True once in a room."""
        t, n64 = self.t, self.n64
        for place in [a for a in t.actors() if a["name"] == outside]:
            for (dx, dz), push in doors:
                t.drop(place["pos"][0] + dx, place["pos"][2] + dz, 30)
                n64.frames(15, stick(*push))
                n64.frames(3, stick(*push) | A)
                n64.frames(30, 0)
                for _ in range(40):
                    try:
                        if self.scene() != FG or t.window_main() != emu_year.HIDDEN:
                            break
                    except (ValueError, struct.error):
                        break
                    n64.frames(5, 0)
                self.advance(200, 10)
                if self.scene() != FG:
                    return True
        return False

    def way_out(self):
        """(x, z) just inside the way out of the room the player is in: the unit above its exit's units in the
        room's own map (g_fdinfo's first block's items: the items EXIT_DOORS), or None."""
        n64 = self.n64
        try:
            blocks = n64.u32(n64.u32(self.field) + FIELD_BLOCKS)
            words = struct.unpack(">256H", n64.read(n64.u32(blocks + FIELD_ITEMS), 512))
        except (TypeError, ValueError, struct.error):
            return None
        doors = [(k % 16, k // 16) for k, v in enumerate(words) if v in EXIT_DOORS]
        if not doors:
            return None
        return (sum(ux for ux, _ in doors) / len(doors) + 0.5) * 40.0, (max(uz for _, uz in doors) - 0.5) * 40.0

    def leave(self):
        """Out of a room by its door at the bottom (a keeper may have a word at the door): True once in the
        town. Straight down from where the player stands; where that is not before the door (a room gone
        into by a talk at its door, a thing looked at in a corner), from before the door."""
        n64, t = self.n64, self.t
        for n in range(5):
            out = self.way_out()
            x, level, _ = t.pos(t.player())
            if out and (n or abs(x - out[0]) > 40.0):   # not before the door, or the way down led nowhere
                for _ in range(3):
                    t.put(out[0], level, out[1])
                    n64.frames(2, 0)
                n64.frames(10, 0)
            n64.frames(60, DIRECTIONS["d"])
            for _ in range(40):
                if self.scene() == FG:
                    break
                try:
                    spoken = t.window_main() != emu_year.HIDDEN or t.choice_waiting()
                except (ValueError, struct.error):
                    spoken = False
                if spoken:
                    self.advance(100, 4)
                else:
                    n64.frames(15, 0)
            if self.scene() == FG:
                self.advance(300, 10)
                return True
        return False

    def outside(self):
        return self.scene() == FG or self.leave()

    # the player set somewhere and turned: Town.drop puts it above a place, and it falls for some 28 frames
    # more than drop waits, taking no stick meanwhile; a push meant to turn it then does nothing
    def land(self, x, z):
        """The player put at (x, z) from above, and waited for until it stands there."""
        n64, t = self.n64, self.t
        t.drop(x, z, 10)
        for _ in range(120):
            if t.player_main() == emu_year.PLAYER_WAIT:
                break
            n64.frames(1, 0)
        n64.frames(4, 0)
        return t.player_main() == emu_year.PLAYER_WAIT

    def yaw(self):
        """Where the player's figure looks, in degrees: 0 down the screen (+z), 90 to its right (+x)."""
        raw = self.n64.read(self.t.player() + emu_year.A_SHAPE_ROT_Y, 2)
        return struct.unpack(">h", raw)[0] * 360.0 / 65536

    def off_by(self, dx, dz):
        return (self.yaw() - math.degrees(math.atan2(dx, dz)) + 180) % 360 - 180

    def face(self, dx, dz):
        """Short pushes of the stick until the figure looks the way of (dx, dz); a push moves it a step or
        two."""
        n64 = self.n64
        d = math.hypot(dx, dz) or 1.0
        for _ in range(8):
            if abs(self.off_by(dx, dz)) <= 12:
                return True
            n64.frames(2, stick(int(round(80 * dx / d)), int(round(-80 * dz / d))))
            n64.frames(8, 0)
        return abs(self.off_by(dx, dz)) <= 12

    def open_mailbox(self, box):
        """A before a house's mailbox (its actor): True once the item screen is open over its letters. A
        mailbox answers only the one who stands at its corner (ac_mailbox's func_8096CA0C_jp: the way from it
        to the player within 38 degrees of the way 45 degrees short of where it looks, the unit before the
        figure the mailbox's own): a player's looks to the right of the screen, so that is its lower right,
        and straight below it A does nothing."""
        n64, t = self.n64, self.t
        looks = struct.unpack(">h", n64.read(box["addr"] + emu_year.A_SHAPE_ROT_Y, 2))[0] * 360.0 / 65536
        for turn in MAILBOX_TURNS:
            way = math.radians(looks - 45 + turn)
            dx, dz = MAILBOX_REACH * math.sin(way), MAILBOX_REACH * math.cos(way)
            if not (self.land(box["pos"][0] + dx, box["pos"][2] + dz) and self.face(-dx, -dz)):
                continue
            n64.press(A, 3, 60)
            n64.frames(60, 0)
            if t.menu_open():
                return True
            self.advance(60, 6)                     # a word instead (someone by the mailbox): played through
        return False

    def block_of(self, kind):
        return next((b for b, kinds in sorted(self.t.blocks().items()) if kind in kinds), None)

    # the item screen: START opens and closes it, the hand on the first pocket; 15 pockets, five in a row
    def items(self, on=True):
        n64, t = self.n64, self.t
        for _ in range(4):
            if t.menu_open() == on:
                return True
            n64.press(START, 3, 60)
            n64.frames(40, 0)
        return t.menu_open() == on

    def hand(self, slot, at=0):
        n64 = self.n64
        cols, rows = slot % 5 - at % 5, slot // 5 - at // 5
        for way, steps in (("r" if cols > 0 else "l", abs(cols)), ("d" if rows > 0 else "u", abs(rows))):
            for _ in range(steps):
                n64.frames(6, DIRECTIONS[way])
                n64.frames(14, 0)

    def tag(self, entry):
        """A on the pocket under the hand, and its tag's entry."""
        n64 = self.n64
        n64.press(A, 3, 30)
        for _ in range(entry):
            n64.frames(6, DIRECTIONS["d"])
            n64.frames(12, 0)
        n64.press(A, 3, 40)

    def type(self, typed=TYPED):
        """Letters on the dial, START, and the end's question answered with its first answer."""
        n64 = self.n64
        for way in typed:
            v = DIRECTIONS[way]
            n64.frames(8, v)
            n64.frames(6, v | A)
            n64.frames(4, v)
            n64.frames(8, 0)
        n64.press(START, 6, 60)
        n64.press(A, 3, 40)
        n64.press(A, 3, 60)
        n64.frames(60, 0)

    def nook(self):
        """Nook spoken to in his shop: how the talk ended, or why there was none."""
        t = self.t
        if not self.outside():
            return "not out of the room"
        if self.scene() == FG:
            if not t.go_block(self.block_of("shop"), set()) or not self.enter("Shop", emu_memory.DOORS):
                return "not into the shop"
        keeper = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["name"] not in (ANIMAL, "Npc")]
        if not keeper:
            return "nobody in the shop"
        spoken, how = self.talk(keeper[0])
        return how if spoken else "not spoken to"

    def animal(self, fg, homes, pick=None):
        """The animal found (its home's block, the blocks beside it, then every block) and spoken to: how the
        talk ended, or None if it was not found."""
        t = self.t
        if not self.outside():
            return None
        home = homes.get(fg)
        near = [home] + [(home[0] + dx, home[1] + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))] if home else []
        every = [(x, z) for z in range(1, 7) for x in range(1, 6)]
        for blk in [b for b in near if b in every] + [b for b in every if b not in near]:
            if not t.go_block(blk, set()):
                continue
            self.n64.frames(30, 0)
            for _ in range(4):                          # it may be walking: wait and try again
                who = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["fg"] == fg]
                if not who:
                    break
                spoken, how = self.talk(who[0], limit=500, quiet=10, pick=pick)
                if spoken:
                    return how
                self.n64.frames(90, 0)
        return None

    def through(self, how, slot=None):
        """A talk that opened a menu played to its end: the item screen (the pocket chosen, its tag's first
        entry), then whatever else is held up (a letter: A)."""
        for n in range(5):
            if how != "menu":
                break
            self.n64.frames(40, 0)
            if n == 0 and slot is not None:
                self.hand(slot)
                self.n64.press(A, 3, 40)
            self.n64.press(A, 3, 60)
            self.n64.frames(40, 0)
            how = self.advance(limit=500, quiet=10)
        return how


def deliveries(private):
    """[(the record's place, the animal it goes to, the animal who gave it)] of the errands a player's record
    holds (a record that holds none has the type NONE)."""
    out = []
    for k in range(DELIVERY_NUM):
        r = private[DELIVERIES + k * DELIVERY_SIZE:DELIVERIES + (k + 1) * DELIVERY_SIZE]
        if r[0] >> 6 != NONE:
            out.append((k, struct.unpack(">H", r[D_RECIPIENT:D_RECIPIENT + 2])[0],
                        struct.unpack(">H", r[D_SENDER:D_SENDER + 2])[0]))
    return out


def errands(private):
    """[(which list, the record's place, the animal to see, the animal seen last)] of all a player's record
    holds of the animals' work: the deliveries, then the errands."""
    out = [("delivery", k, to, giver) for k, to, giver in deliveries(private)]
    for k in range(ERRAND_NUM):
        r = private[QUEST + k * ERRAND_SIZE:QUEST + (k + 1) * ERRAND_SIZE]
        if r[0] >> 6 != NONE:
            out.append(("errand", k, struct.unpack(">H", r[D_RECIPIENT:D_RECIPIENT + 2])[0],
                        struct.unpack(">H", r[D_SENDER:D_SENDER + 2])[0]))
    return out


def errand(emu_dir, rom_path, af_en, flash, work):
    """The flash's file powered on once more, in work: the animals' errands, to their ends. The one the first
    job left open (the animal asked for work while Nook got ready gave a thing to take to another), and one
    asked for now (each animal's talk in turn, its first answers, until the player's record holds work).
    Either is followed as its record says: the animal it names spoken to (the first answer), the item screen
    gone through when it opens over the talk, until the record is empty. A dict for the report."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    os.makedirs(work, exist_ok=True)
    shutil.copyfile(flash, emu_save.fla_path(work, rom))
    result = {"arrived": False, "hang": None, "pages": [], "town": [], "left": None, "asked": None, "tried": 0,
              "wishes": 0, "watch": None}
    with N64(emu_dir, rom, work, timeout=60, clock=ERRAND) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)
        p = Player(n64, t, syms, result["pages"])

        def records():
            return errands(p.private(0, QUEST + ERRAND_NUM * ERRAND_SIZE))

        def follow(homes):
            """The open record's animals seen one after another: what each talk did."""
            first, hops = records()[0], []
            for _ in range(HOPS):
                held = records()
                if not held:
                    break
                which, _, to, giver = held[0]
                pockets, marked, wallet, menus = p.pockets(), p.marked(), p.wallet(), p.menus
                how = p.animal(to, homes, pick=lambda count: 0)
                opened = how == "menu"
                how = p.through(how, None)
                hops.append({"which": which, "to": to, "from": giver, "menu": opened and p.menus > menus,
                             "ended": how, "pockets": [pockets, p.pockets()], "marked": [marked, p.marked()],
                             "wallet": [wallet, p.wallet()]})
                if how != "free":
                    break
            return {"which": first[0], "to": first[2], "from": first[3], "hops": hops,
                    "left": [list(r) for r in records()]}
        try:
            low, used = emu_memory.watch_soon(n64, af_en, syms)
            n64.frames(400 - used, 0)
            n64.press(START, 3, 60)
            for _ in range(60):
                if p.advance(400, 12) == "free" and p.scene() == FG:
                    result["arrived"] = True
                    break
                n64.frames(30, 0)
            homes = {fg: tuple(place[:2]) for fg, place in emu_town.animals(n64.read(p.cd, 0x10000))}
            result["town"] = sorted(homes)
            if result["arrived"] and records():         # the errand the first job left
                result["left"] = follow(homes)
            for fg in homes if result["arrived"] and not records() else []:     # and one asked for now
                result["tried"] += 1
                how = p.animal(fg, homes, pick=lambda count: 0)
                if how == "menu":                       # pockets opened over the talk: closed with nothing chosen
                    n64.press(B, 3, 30)
                    p.advance(limit=300, quiet=10)
                if records():
                    result["asked"] = follow(homes)
                    break
            save = n64.read(p.cd, 0x10000)              # what the animals wish for themselves is in their own
            result["wishes"] = sum(1 for i in range(emu_town.ANIMAL_MAX) if save[
                emu_town.ANIMALS_AT + i * emu_town.ANIMAL_SIZE + CONTEST] >> 6 != NONE and save[
                emu_town.ANIMALS_AT + i * emu_town.ANIMAL_SIZE] == 0xE0)
            result["watch"] = low()
        except Hang as e:
            result["hang"] = str(e)
    result["problems"] = ["%#x at %d: %s" % (pg["msg"], pg["at"], ", ".join(pg["problems"]))
                          for pg in result["pages"] if pg["problems"]]
    result["messages"] = len({pg["msg"] for pg in result["pages"]})
    result["pages"] = len(result["pages"])
    return result


def play(emu_dir, rom_path, af_en, state, out):
    """The job played from the state to the gyroid's save: a dict for the report."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    work = os.path.join(out, "job")
    os.makedirs(work, exist_ok=True)
    result = {"house": False, "chores": [], "over": None, "hang": None, "pages": [], "watch": None, "made": [],
              "gyroid": None, "flash": emu_save.fla_path(work, rom), "animals": 0, "trade": None, "paid": None,
              "letter": None}
    with N64(emu_dir, rom, work, timeout=60) as n64:
        n64.load_state(state)
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], names, syms)
        p = Player(n64, t, syms, result["pages"])
        try:
            low = emu_memory.watch(n64, af_en, syms)
            result["house"] = emu_town.choose_house(t)
            p.advance(600, 16)
            homes = {fg: tuple(place[:2]) for fg, place in emu_town.animals(n64.read(p.cd, 0x10000))}
            order = list(homes)
            result["animals"] = len(homes)
            door, state = None, {"asked": 0}

            def given(before):
                """The pockets Nook filled at the last talk."""
                return [s for s, (a, b) in enumerate(zip(before, p.pockets())) if b and a != b]

            def clothe(slots):
                worn, item = p.clothes(), p.pockets()[slots[0]] if slots else 0
                p.items()
                p.hand(slots[0] if slots else 0)
                p.tag(0)                                 # the clothes in the hand
                p.hand(0, slots[0] if slots else 0)      # the hand over the first pocket,
                n64.frames(6, DIRECTIONS["u"])           # and up to the player's figure
                n64.frames(25, 0)
                n64.press(A, 3, 60)
                n64.frames(60, 0)
                p.items(False)
                return {"worn": p.clothes() == item != 0, "kept": worn in p.pockets()}

            def plant(slots):
                tries = 0
                x0, z0 = door or (0, 0)
                spots = [(x0 + dx, z0 + dz) for dz in (92, 172, 252, 332, -252)
                         for dx in (0, -40, 40, -80, 80, -120, 120, -160, 160)]
                left = list(slots)
                for x, z in spots:
                    if not left:
                        break
                    tries += 1
                    t.drop(x, z, 30)
                    n64.frames(10, DIRECTIONS["d"])
                    n64.frames(10, 0)
                    p.items()
                    p.hand(left[0])
                    p.tag(1)                             # planted before the player
                    n64.frames(60, 0)
                    if t.menu_open():                    # nothing to plant on here
                        p.items(False)
                    p.advance(100, 6)
                    if not p.pockets()[left[0]]:
                        left.pop(0)
                return {"bags": len(slots), "planted": len(slots) - len(left), "tries": tries}

            def greet(_):
                def met():                               # by the animals' own memories, as the game counts them
                    return acquainted(n64.read(p.cd, 0x10000)) & set(homes)   # (mNpc_GetFriendAnimalNum)

                def open_still():
                    q = p.quest()
                    return bool(q) and q[0] == GREET and q[1] != DONE

                p.outside()
                every = [(x, z) for z in range(1, 7) for x in (range(1, 6) if z % 2 else range(5, 0, -1))]
                first = sorted(set(homes.values()))
                talks = 0
                for blocks in (first + [b for b in every if b not in first], first, every):
                    for blk in blocks:                   # an animal that walked off is looked for again
                        known = met()
                        if not open_still() or len(known) == len(homes):
                            break
                        if not t.go_block(blk, set()):
                            continue
                        n64.frames(30, 0)
                        for _ in range(3):
                            there = [a["fg"] for a in t.actors() if a["part"] == emu_year.PART_NPC
                                     and a["name"] == ANIMAL and a["fg"] not in known]
                            for fg in there:             # each where it stands now
                                who = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["fg"] == fg]
                                if who:
                                    p.talk(who[0], limit=400, quiet=10)
                                    talks += 1
                            known = met()
                            if not set(there) - known:
                                break
                            n64.frames(90, 0)
                for _ in range(2):                       # the job's record follows the memories (the quest
                    for _ in range(10):                  # manager's own look at them): it is given a moment,
                        if not open_still():             # then one more talk to look at
                            break
                        n64.frames(30, 0)
                    if not open_still() or len(met()) < len(homes):
                        break
                    for blk in [None] + first:           # an animal of this block, or of the nearest home's
                        if blk is not None:
                            if not t.go_block(blk, set()):
                                continue
                            n64.frames(30, 0)
                        who = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["name"] == ANIMAL]
                        if who:
                            p.talk(who[0], limit=400, quiet=10)
                            talks += 1
                            break
                return {"met": len(met()), "animals": len(homes), "talks": talks}

            def deliver(slots):
                q = p.quest()
                before = p.menus
                how = p.animal(q[2], homes)
                how = p.through(how, slots[0] if slots else None)
                return {"found": how is not None, "ended": how, "menus": p.menus - before}

            def letter(slots):
                q = p.quest()
                p.items()
                p.hand(slots[0] if slots else 0)
                p.tag(1)                                 # to write a letter: whom to
                n64.frames(60, 0)
                n64.press(A, 3, 40)                      # the address book
                for _ in range(order.index(q[2]) if q[2] in order else 0):
                    n64.frames(6, DIRECTIONS["d"])
                    n64.frames(14, 0)
                n64.press(A, 3, 60)
                n64.frames(40, 0)
                p.type(LONG)
                p.items(False)
                written = bool(slots) and not p.pockets()[slots[0]] and p.letters() == 1
                raw = p.private(MAIL, 10 * MAIL_SIZE)
                mail = next((raw[i * MAIL_SIZE:(i + 1) * MAIL_SIZE] for i in range(10)
                             if raw[i * MAIL_SIZE:i * MAIL_SIZE + 12].strip(b" ")), None)
                if mail:                                 # the letter's own bytes, and its rest in the extension
                    size = n64.u32(syms["mFRm_en_extSize"])
                    ext = n64.read(syms["mFRm_en_ext"], size) if 0 < size <= emu_letters.EXT_CAP else b""
                    key = emu_letters.mail_key(mail)
                    rest = emu_letters.Ext.parse(ext).rest(key)
                    result["letter"] = {"bytes": mail[KEY_BYTES[0]:KEY_BYTES[1]].hex(), "key": "%08x" % key,
                                        "rest": rest[1].hex() if rest else None}
                mailed = False
                if p.outside() and t.go_block(p.block_of("post"), set()) and p.enter("Post_Office", POST_DOOR):
                    clerk = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["name"] not in (ANIMAL, "Npc")]
                    if clerk:
                        more = lambda count: 0 if count != 2 else 1     # noqa: E731  no second letter
                        spoken, how = p.talk(clerk[0], pick=more)
                        for _ in range(3):
                            if how != "menu":
                                break
                            n64.frames(40, 0)
                            n64.press(A, 3, 40)          # the letter, and its tag's first entry
                            n64.press(A, 3, 40)
                            n64.frames(40, 0)
                            how = p.advance(pick=more)
                        mailed = spoken and how == "free" and p.letters() == 0
                return {"written": written, "mailed": mailed}

            def villagers(_):
                fg = order[state["asked"] % len(order)]
                state["asked"] += 1
                how = p.animal(fg, homes)
                if how == "menu":                        # pockets opened over the talk: closed with nothing chosen
                    n64.press(B, 3, 30)
                    how = p.advance(limit=300, quiet=10)
                return {"asked": state["asked"], "found": how is not None}

            def notice(_):
                x0, z0 = emu_town.PLAYERS_BLOCK[0] * emu_year.BLOCK, emu_town.PLAYERS_BLOCK[1] * emu_year.BLOCK
                opened = False
                if p.outside() and t.go_block(emu_town.PLAYERS_BLOCK, set()):
                    for dx, dz in BOARD_AT:
                        t.drop(x0 + dx, z0 + dz, 30)
                        n64.frames(12, DIRECTIONS["u"])
                        n64.frames(10, 0)
                        n64.press(A, 3, 40)
                        if t.menu_open():
                            opened = True
                            break
                if opened:
                    n64.frames(40, 0)
                    n64.press(A, 3, 60)                  # to write
                    n64.frames(40, 0)
                    p.type()
                    for _ in range(3):                   # and away from the board
                        n64.press(B, 3, 60)
                        n64.frames(60, 0)
                        if not t.menu_open():
                            break
                    p.advance(100, 8)
                return {"board": opened}

            def trade():
                """The first Bells: something sold to the shop's keeper, something bought off his table."""
                out = {"in": False, "sold": False, "bought": False, "wallet": [p.wallet()]}
                if not p.outside() or not t.go_block(p.block_of("shop"), set()) or not p.enter("Shop", emu_memory.DOORS):
                    return out
                out["in"] = True
                keeper = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["name"] not in (ANIMAL, "Npc")]
                have = p.pockets()
                slot = next((s for s in range(14, -1, -1) if have[s]), None)
                if keeper and slot is not None:
                    answers = iter((1, 0, 1))            # to sell; the price taken; nothing else to sell
                    pick = lambda count: next(answers, count - 1)       # noqa: E731
                    spoken, how = p.talk(keeper[0], limit=200, quiet=10, pick=pick)
                    if spoken and how == "menu":
                        n64.frames(40, 0)
                        p.hand(slot)
                        n64.press(A, 3, 40)
                        n64.press(A, 3, 60)
                        n64.frames(40, 0)
                        how = p.advance(limit=300, quiet=10, pick=pick)
                    if t.menu_open():                    # asked for another: closed with nothing chosen
                        n64.press(B, 3, 40)
                        p.advance(limit=200, quiet=10, pick=lambda count: count - 1)
                    out["sold"] = not p.pockets()[slot] and p.wallet() > out["wallet"][0]
                out["wallet"].append(p.wallet())
                have = p.pockets()
                for x, z in COUNTER:                     # before the table, facing it, A: the keeper names the price
                    for _ in range(3):
                        t.put(x, 40.0, z, -0x8000)
                        n64.frames(2, 0)
                    n64.frames(8, DIRECTIONS["u"])
                    n64.frames(6, 0)
                    n64.press(A, 3, 30)
                    if t.window_main() == emu_year.HIDDEN and not t.choice_waiting():
                        continue
                    p.advance(limit=200, quiet=10)
                    if p.wallet() < out["wallet"][1] and sum(1 for v in p.pockets() if v) > sum(1 for v in have if v):
                        out["bought"] = True
                        break
                out["wallet"].append(p.wallet())
                return out

            def pay():
                """A payment towards the house at the post office: the clerk's second answer, a hundred Bells
                set on the counter (the hundreds' figure up by one), its OK."""
                out = {"in": False, "wallet": [p.wallet()]}
                if p.outside() and t.go_block(p.block_of("post"), set()) and p.enter("Post_Office", POST_DOOR):
                    out["in"] = True
                    clerk = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["name"] not in (ANIMAL, "Npc")]
                    answers = iter((1,))                 # a payment; then nothing else
                    pick = lambda count: next(answers, count - 1)       # noqa: E731
                    if clerk:
                        spoken, how = p.talk(clerk[0], limit=100, quiet=8, pick=pick)
                        if spoken and how == "menu":     # the counter: six figures, then its OK
                            n64.frames(40, 0)
                            for way in "rrru" + "rrr":
                                n64.frames(6, DIRECTIONS[way])
                                n64.frames(14, 0)
                            n64.press(A, 3, 40)
                            n64.frames(40, 0)
                            p.advance(limit=200, quiet=8, pick=pick)
                out["wallet"].append(p.wallet())
                return out

            chores = {CLOTHE: clothe, PLANT: plant, GREET: greet, FURNITURE: deliver, LETTER: letter,
                      VILLAGERS: villagers, CARPET: deliver, AXE: deliver, NOTICE: notice}
            held = {}                                     # a chore's pockets, should it take a second try
            for _ in range(len(CHORES) + 8):
                if p.quest() is None:
                    break
                before = p.pockets()
                said = p.nook()
                q = p.quest()
                if q is None:
                    result["over"] = said
                    break
                kind = q[0]
                entry = {"kind": kind, "nook": said, "open": q[1] == OPEN, "for": q[2]}
                if kind in chores and q[1] == OPEN:
                    held[kind] = given(before) or held.get(kind, [])
                    if kind == PLANT and p.outside() and door is None:
                        door = tuple(t.pos(t.player())[::2])    # the shop's doormat
                    entry.update(chores[kind](held[kind]))
                    after = p.quest()
                    entry["done"] = after is not None and (after[1] == DONE or after[0] != kind)
                result["chores"].append(entry)
                n64.screenshot(os.path.join(work, "job-%02d-kind-%d.png" % (len(result["chores"]), kind)))
            p.advance(300, 16)                            # sent out of the shop: the player's own word
            result["end"] = {"quest": p.quest() is None, "flags": p.flags() & emu_year.JOB_BITS, "scene": p.scene(),
                             "pockets": [v for v in p.pockets() if v], "clothes": p.clothes(),
                             "keeps": keeping(n64.read(p.cd, 0x10000), None, result["letter"])["animal"]}
            n64.screenshot(os.path.join(work, "job-over.png"))
            if result["end"]["quest"]:
                result["trade"] = trade()
                n64.screenshot(os.path.join(work, "job-trade.png"))
                result["paid"] = pay()
                n64.screenshot(os.path.join(work, "job-paid.png"))
            if result["end"]["quest"] and p.outside() and t.go_block(emu_town.PLAYERS_BLOCK, set()):
                result["gyroid"] = emu_memory.save_and_stop(t, emu_town.MAT, result["pages"])
                n64.frames(120, 0)
            result["watch"] = low()
            result["made"] = emu_memory.made(result["watch"], names)
            result["asked"], result["menus"] = p.asked, p.menus
        except Hang as e:
            result["hang"] = str(e)
            n64.screenshot(os.path.join(work, "job-hang.png"))
    result["problems"] = ["%#x at %d: %s" % (pg["msg"], pg["at"], ", ".join(pg["problems"])) for pg in result["pages"]
                          if pg["problems"]]
    result["messages"] = len({pg["msg"] for pg in result["pages"]})
    result["pages"] = len(result["pages"])
    return result


def handed_over(hand):
    """(whether an errand's thing left the pockets and was paid for, what to say) from one of errand()'s talks:
    the pocket that held the marked thing holds no marked thing after; the pay is Bells (that pocket is
    empty and the pocketbook is fuller) or a thing (that pocket, or an empty one in its stead, holds the
    thanks and no other pocket changed: the animals pay with either)."""
    if not hand:
        return False, "nothing handed"
    (before, after), (marked, left), (had, has) = hand["pockets"], hand["marked"], hand["wallet"]
    if len(marked) != 1 or left:
        return False, "the pockets' marked things were %s and are %s" % (marked, left)
    slot = marked[0]
    changed = [k for k in range(len(before)) if before[k] != after[k]]
    if after[slot] == 0 and changed == [slot] and has > had:
        return True, "the pocket that held its thing is empty and the pocketbook goes from %d to %d Bells" % (had, has)
    came = [k for k in changed if k != slot]
    if has >= had and after[slot] and not came:
        return True, "the pocket that held its thing holds the animal's thanks, another thing (%#x for %#x)" % (
            after[slot], before[slot])
    if has >= had and after[slot] == 0 and len(came) == 1 and before[came[0]] == 0:
        return True, "the pocket that held its thing is empty and another holds the animal's thanks (%#x for %#x)" % (
            after[came[0]], before[slot])
    return False, "the pockets were %s and are %s, the pocketbook %d and %d" % (before, after, had, has)


def followed(run):
    """(whether an errand was followed to its end, what to say) from errand()'s "left" or "asked": every talk
    ends, the record is empty after the last, the last is the item screen over the talk and the thing handed
    over for its thanks (handed_over); and a thing to fetch came to a marked pocket from the animal before."""
    if not run or not run["hops"]:
        return False, "not followed"
    hops = run["hops"]
    if run["left"] or any(h["ended"] != "free" for h in hops):
        return False, "after %d animals (their talks ended %s) the record holds %s" % (
            len(hops), [h["ended"] for h in hops], run["left"])
    ok, said = handed_over(hops[-1])
    if not ok or not hops[-1]["menu"]:
        return False, "the last animal's talk: %s%s" % (said, "" if hops[-1]["menu"] else "; no item screen over it")
    if run["which"] == "delivery":
        return len(hops) == 1, "a thing of the animal %#x for the animal %#x: its talk's first answer, the item " \
            "screen over the talk, the thing handed over; %s" % (run["from"], run["to"], said)
    got = [n for n, h in enumerate(hops) if len(h["marked"][1]) == len(h["marked"][0]) + 1]
    if got != [len(hops) - 2]:
        return False, "the thing came to the pockets at the talks %s of %d" % (got, len(hops))
    return True, "a thing of the animal %#x's to fetch from the animals it was lent on to: %d seen (the talk's " \
        "first answer; each names the next until one hands the thing over, a pocket marked as an errand's), then " \
        "its owner, the item screen over the talk; %s" % (hops[-1]["to"], len(hops) - 1, said)


def report(job, again, kept, favour=None):
    """The checks. job: play()'s answer; again: the session that powered the flash on again; kept: what the
    flash holds after it ({name, flags, clothes, letter: keeping()'s answers for the flash the gyroid wrote
    and for the one written the next morning}), or None; favour: errand()'s answer, the flash powered on a
    third time."""
    chores = job["chores"] if job else []
    by_kind = {}
    for c in chores:                    # a chore given twice is judged by its last try
        by_kind[c["kind"]] = c
    check(bool(job) and job["house"] and not job["hang"] and job["animals"] > 0,
          "a new game, the first house chosen by the game's own talk; the town has %d animals%s" % (
              job["animals"] if job else 0, "" if job and not job["hang"] else "; THE GAME HUNG: %s" % (job and job["hang"])))
    details = {
        CLOTHE: lambda c: "the clothes he hands over taken from the pocket (the tag's first entry) to the player's "
                          "figure: %s, the clothes worn before %s" % (
                              "worn" if c.get("worn") else "NOT WORN", "in a pocket" if c.get("kept") else "LOST"),
        PLANT: lambda c: "%d of the %d bags and saplings planted by the shop (the tag's second entry; %d places "
                         "tried)" % (c.get("planted", 0), c.get("bags", 0), c.get("tries", 0)),
        GREET: lambda c: "%d of the town's %d animals hold the player in their memories (%d talks)" % (
            c.get("met", 0), c.get("animals", 0), c.get("talks", 0)),
        FURNITURE: lambda c: "the animal the record names (%04x) %s" % (
            c["for"], "spoken to, the delivery handed over" if c.get("found") else "NOT FOUND"),
        LETTER: lambda c: "a letter to the animal the record names (%04x): %s (the paper's tag's second entry, the "
                          "address book, the dial, the end's question), %s (the clerk's first answer, the letter "
                          "chosen in the item screen)" % (
                              c["for"], "written" if c.get("written") else "NOT WRITTEN",
                              "mailed at the post office" if c.get("mailed") else "NOT MAILED"),
        VILLAGERS: lambda c: "%d animal%s asked for work" % (c.get("asked", 0), "" if c.get("asked") == 1 else "s"),
        NOTICE: lambda c: "the board by the houses %s" % (
            "opened with A, a notice written (the dial, the end's question)" if c.get("board") else "NOT OPENED"),
    }
    details[CARPET] = details[AXE] = details[FURNITURE]
    sound = {
        CLOTHE: lambda c: c.get("worn") and c.get("kept"),
        PLANT: lambda c: c.get("bags", 0) > 0 and c.get("planted") == c.get("bags"),
        GREET: lambda c: c.get("animals", 0) > 0 and c.get("met") == c.get("animals"),
        FURNITURE: lambda c: c.get("found") and c.get("ended") == "free",
        LETTER: lambda c: c.get("written") and c.get("mailed"),
        VILLAGERS: lambda c: c.get("asked", 0) >= 1 and c.get("found"),
        NOTICE: lambda c: c.get("board"),
    }
    sound[CARPET] = sound[AXE] = sound[FURNITURE]
    for kind in CHORES:
        c = by_kind.get(kind)
        check(bool(c) and c["open"] and c["nook"] == "free" and bool(c.get("done")) and bool(sound[kind](c)),
              "Nook's chore %d, %s: %s; %s" % (
                  kind, WHAT[kind], details[kind](c) if c else "NOT GIVEN",
                  "the job's record says it is done" if c and c.get("done") else "THE RECORD DOES NOT SAY IT IS DONE"))
    order = [c["kind"] for c in chores if c["kind"] in CHORES and c["open"]]
    end = job.get("end") if job else None
    check(bool(end) and end["quest"] and end["flags"] == 0 and end["scene"] == FG and job["over"] == "free" and
          [k for n, k in enumerate(order) if k not in order[:n]] == list(CHORES),
          "Nook's last word: %s; the chores came in the order %s" % (
              "the job's record is empty, its two event flags cleared by the game, the player out of the shop"
              if end and end["quest"] and not end["flags"] and end["scene"] == FG
              else "THE JOB IS NOT OVER (the record %s, the flags %#x, scene %s)" % (
                  "empty" if end and end["quest"] else "still there", end["flags"] if end else -1,
                  end["scene"] if end else "?"),
              ", ".join(str(k) for k in order) or "none"))
    deal = job and job["trade"]
    check(bool(deal) and deal["in"] and deal["sold"] and deal["bought"] and
          deal["wallet"][0] < deal["wallet"][1] > deal["wallet"][2],
          "the first Bells, in the shop the job has left to its keeper: %s" % (
              "%s; %s" % (
                  "something of the pockets sold (the keeper's second answer, the pocket chosen in the item screen, his "
                  "price taken: the pocketbook from %d to %d Bells)" % tuple(deal["wallet"][:2]) if deal["sold"]
                  else "NOTHING SOLD",
                  "something bought off his table (A before it, the first answer: %d Bells left, the thing in a "
                  "pocket)" % deal["wallet"][2] if deal["bought"] else "NOTHING BOUGHT")
              if deal and deal["in"] else "NOT GONE INTO"))
    paid = job and job["paid"]
    # (what is left after the shop is the town's: its keeper's price and his table's are the game's draw, and
    # the counter goes no higher than the pocketbook)
    due = min(PAYMENT, paid["wallet"][0]) if paid and paid.get("wallet") else 0
    check(bool(paid) and paid["in"] and due > 0 and paid["wallet"][0] - paid["wallet"][1] == due,
          "a payment towards the house at the post office (the clerk's second answer, %d Bells set on the counter, "
          "its OK): %s" % (PAYMENT, "the pocketbook from %d to %d Bells%s" % (
              tuple(paid["wallet"]) + ("" if paid["wallet"][0] >= PAYMENT or paid["wallet"][1] else
                                       " (it had less than that: the counter stops at what it has, all paid)",))
                           if paid and paid["in"] else "NOT GONE INTO"))
    seen = job and job["watch"]
    check(bool(job) and job["pages"] > 0 and not job["problems"] and bool(seen) and seen["fails"] == 0,
          "on the way %s; %s" % (
              "%d pages of %d messages read, %s; %d choices answered, %d menus opened over a talk" % (
                  job["pages"], job["messages"], "clean" if not job["problems"] else job["problems"][:3],
                  job.get("asked", 0), job.get("menus", 0)) if job else "nothing read",
              "the arena never said no (%d allocations, the largest free block %s at its least)" % (
                  seen["calls"], seen["least"]) if seen and not seen["fails"]
              else "THE ARENA SAID NO" if seen else "not watched"))
    word = job and job["gyroid"]
    check(bool(word) and word["spoken"] and word["asked"] >= 2 and word["left"],
          "the player's own gyroid, the job done: %s" % (
              "it offers the save, saves, and the game leaves for its title" if word and word["spoken"] and word["left"]
              else "NO SAVE (%s)" % (word,)))
    check(emu_memory.saved(again) and again["arrived"] and not again["hang"] and not again["problems"] and
          bool(again["inside"]) and again["inside"]["got"] and bool(kept) and bool(kept["name"]) and
          kept["flags"] == 0 and bool(end) and kept["clothes"] == end["clothes"],
          "the flash the game wrote, powered on again the next morning: %s" % (
              "the town loads (%d pages read, clean), the player is the one who did the job (the work clothes on, "
              "the job's flags clear), goes into the house and saves at the gyroid again" % again["pages"]
              if again and again["arrived"] and kept and not kept["flags"] else "NOT AS THE GAME LEFT IT (%s)" % (
                  again and {k: again[k] for k in ("arrived", "hang", "problems", "saved")},)))
    check(*letter_kept(job, kept))
    left, asked = (favour or {}).get("left"), (favour or {}).get("asked")
    (left_ok, left_said), (asked_ok, asked_said) = followed(left), followed(asked)
    check(left_ok or bool(favour) and favour["arrived"] and left is None and asked_ok,
          "the errand the first job left open (the animal asked for work while Nook got ready), that morning: %s" % (
              left_said if left_ok else "NOT TO ITS END (%s)" % left_said if left else
              "none left (the animal asked then gave no thing to take), and nothing to do"
              if favour and favour["arrived"] and asked_ok else "NOT PLAYED (%s)" % (
                  favour and {k: favour[k] for k in ("arrived", "hang")},)))
    check(asked_ok or bool(favour) and favour["arrived"] and asked is None and left_ok,
          "an errand asked for now, from the asking to its end: %s" % (
              "%s; %d animal%s asked (the talk's first answers)" % (
                  asked_said, favour["tried"], "" if favour["tried"] == 1 else "s") if asked_ok else
              "NOT TO ITS END (%s)" % asked_said if asked else
              "no animal had one to give (%d asked; %d have a wish of their own, which is no errand)" % (
                  favour["tried"], favour["wishes"]) if favour and favour["arrived"] and left_ok else
              "NOT PLAYED (%s)" % (favour and {k: favour[k] for k in ("arrived", "hang", "tried")},)))
    seen = favour and favour["watch"]
    clean = bool(favour) and favour["arrived"] and not favour["hang"] and not favour["problems"] and bool(seen) and \
        seen["fails"] == 0
    check(clean and (left_ok or asked_ok),
          "the errands' pages: %s" % (
              "%d pages of %d messages read, clean; the arena never said no (%d allocations, the largest free "
              "block %s at its least)" % (favour["pages"], favour["messages"], seen["calls"], seen["least"])
              if clean else "NOT CLEAN (%s)" % (favour and {k: favour[k] for k in ("hang", "problems", "watch")},)))


def letter_kept(job, kept):
    """The check of the letter an animal keeps: (whether it holds, what to say)."""
    letter, end = job and job.get("letter"), job and job.get("end")
    held = (kept or {}).get("letter") or []
    rest = bytes.fromhex(letter["rest"]) if letter and letter.get("rest") else b""
    ok = (bool(rest) and bool(end) and bool(end.get("keeps")) and len(held) == 2 and
          all(h["animal"] and not h["place"] and h["rest"] == letter["rest"] for h in held))
    if ok:
        said = ("%d bytes of body; its animal keeps the cartridge's %d (in RAM at the job's end, and in the flash "
                "the gyroid wrote and the next morning's, where none of the save's own letter places holds it), "
                "and the extension the %d past them, as they were written, in both" % (
                    96 + len(rest), 96, len(rest)))
    elif not rest:
        said = "NOT LONGER THAN THE CARTRIDGE KEEPS, OR NOT FOUND (%s)" % (letter,)
    else:
        said = "THE REST IS NOT KEPT WITH IT (at the job's end an animal has it: %s; the two flashes: %s)" % (
            bool(end) and end.get("keeps"), held)
    return ok, "the letter written for the job, mailed, and shown by its animal: %s" % said


def main(argv):
    if argv[:1] == ["--play"]:
        print(json.dumps(play(*argv[1:6])))
        return 0
    if argv[:1] == ["--errand"]:
        print(json.dumps(errand(*argv[1:6])))
        return 0
    emu_dir, rom_path, af_en, state, out = argv
    af_en = os.path.abspath(af_en)
    os.makedirs(out, exist_ok=True)

    def child(script, *args):
        r = subprocess.run([sys.executable, script] + list(args), capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
        if r.returncode or not lines:
            print(r.stdout[-1500:], r.stderr[-1500:])
            return None
        return json.loads(lines[-1])

    print("emu_job: %s, the first job played from %s" % (rom_path, state))
    job = child(os.path.abspath(__file__), "--play", emu_dir, rom_path, af_en, os.path.abspath(state), out)
    again, kept, favour = None, None, None
    if job and job["gyroid"] and job["gyroid"]["left"]:
        plan = os.path.join(out, "plan-again.json")
        work = os.path.join(out, "again")
        json.dump({"label": "again", "work": work, "clock": AGAIN, "flash": job["flash"], "enter": "house",
                   "leave": True, "save": True}, open(plan, "w"))
        first = aflz.unpack(emu_save.read_fla(job["flash"])[:0x10000])      # as the gyroid wrote it
        again = child(os.path.join(HERE, "emu_memory.py"), "--session", emu_dir, rom_path, af_en, plan)
        rom = open(rom_path, "rb").read()
        save, ext = aflz.unpack(emu_save.read_fla(emu_save.fla_path(work, rom))[:0x10000])
        kept = {"name": emu_psel.name_of(save, 0).hex(),
                "flags": struct.unpack(">I", save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4])[0] & emu_year.JOB_BITS,
                "clothes": struct.unpack(">H", save[PRIVATE + CLOTHES + 2:PRIVATE + CLOTHES + 4])[0],
                "letter": [keeping(first[0], first[1], job["letter"]), keeping(save, ext, job["letter"])]}
        if emu_memory.saved(again):                     # the flash as that morning's gyroid wrote it
            favour = child(os.path.abspath(__file__), "--errand", emu_dir, rom_path, af_en,
                           emu_save.fla_path(work, rom), os.path.join(out, "errand"))
    report(job, again, kept, favour)
    json.dump({"job": job, "again": again, "kept": kept, "errand": favour},
              open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_job: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
