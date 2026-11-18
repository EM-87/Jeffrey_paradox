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

Then the same Controller Pak takes the letter itself, in two more power-ons at home. The post office keeps up
to 160 letters on a Controller Pak (the clerk's third answer; ovl__0079E430's menu, code/m_cpak.c's file), the
cartridge's bytes of each, and while a letter lies there no letter of the save holds its rest's key either:

  the mailbox first: the town has mail the game itself delivered (the towns keep the extension the game had
  filed when they were made, tests/emu_year.py's write_town: a town read out of RAM without it starts with
  every such letter cut at the cartridge's 96 bytes). A before the player's mailbox opens the item screen
  over its letters (ac_mailbox's func_8096CFF4_jp: the submenu's program 1, mode 1), Z takes them to the
  pockets, B closes;

  the clerk's menu: the hand begins on the store's first place, the pockets' letters two a row to its left;
  it goes to a letter the player received
  whose key no other letter of the save has (the store takes no letter its own player wrote and has not
  sent: the inventory's letters are open to the hand by their kind, m_inventory_ovl.s, the menu's mode 0xE,
  mMl_check_send_mail; and a letter the post office still keeps a copy of would have its rest kept for the
  copy's sake); Z takes it, in the store's second place Z puts it down, B, and the question's first answer
  (A shows the answers, A chooses); the game writes the store onto the Controller Pak and the town onto the
  Game Pak, and the clerk asks again. The extension must keep the rest, marked as the Pak's (m_submenu.c's
  mSM_en_PakCheckSum), through that save and the gyroid's after it;

  the next power-on: the clerk again, the letter back from the store into the pockets, whole.

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
import emu_write  # noqa: E402
import emu_year  # noqa: E402
from afplay import DIRECTIONS, dialogue_open, name_dial_open, presses, type_on_dial, until, walk  # noqa: E402
from n64emu import N64, A, B, START, Z, Hang  # noqa: E402

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
CLOCKS = {"home": DAY + (10, 0, 0), "visit": DAY + (11, 30, 0), "back": DAY + (13, 0, 0),
          "store": DAY + (14, 30, 0), "fetch": DAY + (16, 0, 0)}
CLERK = "Npc_Post_Girl"
MAILBOX = "MailBox"                     # the actor of a house's mailbox (ac_mailbox)
BOX_FIRST, BOX_COUNT = 40, 10           # the first home's mailbox among the save's letter places (emu_letters)
POCKET_COLUMNS = 2                      # the pockets' letters on the item screen: two a row
KIND_AT, SENDING = 0x26, 1              # Mail_c: the letter's kind (mMl_check_send_mail: 1 is one to send)
KEY_FROM = emu_job.KEY_BYTES[0]         # where a Mail_c's text begins: its header
STORE_PTR = 0x10714                     # the submenu's overlays: the letter store's work area (ovl__0079E430)
STORE_AT, STORE_SLOTS = 0x52, 160       # its letters, after the checksum and 8 fields of 10 bytes
STORE_HAND = 1                          # the store's place used: three rights from the pockets' first, the hand's
TEXT_AT, TEXT_SIZE = 0x2A, 122          # a Mail_c's header, body and footer: what its key is made of

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


def placed(private):
    """[(the place, a Mail_c's bytes)] of the letters a player's record carries."""
    raw = private[MAIL:MAIL + 10 * MAIL_SIZE]
    return [(i, bytes(raw[i * MAIL_SIZE:(i + 1) * MAIL_SIZE])) for i in range(10)
            if raw[i * MAIL_SIZE:i * MAIL_SIZE + 12].strip(b" ")]


def facts(private, ext):
    """What a check asks of a player's record and an extension: the things, the letters' keys, the records."""
    records = emu_letters.Ext.parse(bytes(ext)).records if ext else []
    keys = [key for key, h, b, f in records]
    trips = [key for key in keys if HOLD_KEY <= key < HOLD_KEY + 4]
    line = max((keys.index(key) for key in trips), default=-1)     # what lies before the last of them is kept
    marked = emu_letters.Ext.parse(bytes(ext)).held if ext else set()
    return {"name": bytes(private[:6]).hex(), "id": bytes(private[:0x10]).hex(), "exists": private[EXISTS],
            "marked": ["%08x" % key for key in keys if key in marked],
            "pockets": [v for v in struct.unpack(">15H", bytes(private[POCKETS:POCKETS + 30])) if v],
            "letters": ["%08x" % emu_letters.mail_key(m) for m in held(private)],
            "mail": [m.hex() for m in held(private)],
            "rests": {"%08x" % key: b.hex() for key, h, b, f in records if key not in trips},
            "whole": {"%08x" % key: [h.hex(), b.hex(), f.hex()] for key, h, b, f in records if key not in trips},
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


def pak_copies(work, mail):
    """How many times the Controller Pak's files in work hold a letter's text (mail: a Mail_c's bytes in hex)."""
    text = bytes.fromhex(mail)[TEXT_AT:TEXT_AT + TEXT_SIZE] if mail else b""
    return sum(open(path, "rb").read().count(text) for path in glob.glob(os.path.join(work, "*.mpk"))) if text else 0


def keep_pak(work, where):
    """The Controller Pak's files in work copied to the directory where: what the Pak held at this moment."""
    shutil.rmtree(where, ignore_errors=True)
    os.makedirs(where)
    for path in glob.glob(os.path.join(work, "*.mpk")):
        shutil.copyfile(path, os.path.join(where, os.path.basename(path)))
    return where


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
        ext = emu_year.ram_ext(n64, syms)               # the rests of the letters the town has in it
    save = emu_psel.stamp(common)
    path = os.path.join(out, "town.town")
    emu_year.write_town(path, save, ext)
    return {"town": path, "settled": emu_town.settled(done), "said": emu_town.describe(done),
            "id": bytes(save[PRIVATE:PRIVATE + 0x10]).hex(), "sound": emu_save.checksum_ok(save)}


def session(emu_dir, rom_path, af_en, plan):
    """One power-on of the flash in plan["work"], the Controller Pak's file there as the last power-on left
    it. The plan: label, clock, answers (the greeter's); town (a fresh flash made of it, a sheet of paper in
    its first player's first free pocket) or flash (a flash's file copied in); steps, of meet (an animal
    spoken to), write (the letter), talk (an animal spoken to, no more asked), depart (the station's master,
    the train), store and fetch (the post office's clerk: the pockets' first letter into the Controller Pak's
    store, and back), save (the gyroid, to stop)."""
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
        image, compressed = aflz.pack(emu_psel.fix(bytes(save)), emu_year.town_ext(plan["town"]))
        if not compressed:
            raise SystemExit("emu_trip: %s's save does not fit a compressed slot" % label)
        emu_save.write_fla(fla, image + image)
    elif plan.get("flash"):
        shutil.copyfile(plan["flash"], fla)
    result = {"label": label, "arrived": False, "hang": None, "pages": [], "now": None, "met": None,
              "written": None, "talked": None, "left": None, "saved": None, "watch": None, "made": [],
              "mail": None, "stored": None, "fetched": None}
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

        def store():
            """The letter store's letters while the clerk's menu is open: [[place, key]], or None."""
            ovl = n64.u32(t.play() + emu_write.SUBMENU + emu_write.SUBMENU_OVL)
            area = n64.u32(ovl + STORE_PTR) if 0x80000000 <= ovl < 0x80800000 else 0
            if not 0x80000000 <= area < 0x80800000 - STORE_AT - STORE_SLOTS * MAIL_SIZE:
                return None
            raw = n64.read(area + STORE_AT, STORE_SLOTS * MAIL_SIZE)
            mails = [raw[i * MAIL_SIZE:(i + 1) * MAIL_SIZE] for i in range(STORE_SLOTS)]
            return [[i, "%08x" % emu_letters.mail_key(m)] for i, m in enumerate(mails) if m[:12].strip(b" ")]

        def pockets():
            return ["%08x" % emu_letters.mail_key(m) for m in held(n64.read(n64.u32(cd + PRIVATE_PTR), PRIVATE_SIZE))]

        def places():
            """[(the place, the key, the Mail_c's bytes)] of the pockets' letters."""
            return [(i, "%08x" % emu_letters.mail_key(m), m)
                    for i, m in placed(n64.read(n64.u32(cd + PRIVATE_PTR), PRIVATE_SIZE))]

        def save_keys():
            """The keys of the save's letter places as they are in RAM (the pockets, the mailboxes, the keep)."""
            mails = [n64.read(cd + at, MAIL_SIZE) for at in emu_letters.SAVE_MAIL]
            return ["%08x" % emu_letters.mail_key(m) for m in mails if m[KEY_FROM:KEY_FROM + 12].strip(b" \0")]

        def box():
            mails = [n64.read(cd + emu_letters.SAVE_MAIL[BOX_FIRST + k], MAIL_SIZE) for k in range(BOX_COUNT)]
            return ["%08x" % emu_letters.mail_key(m) for m in mails if m[KEY_FROM:KEY_FROM + 12].strip(b" \0")]

        def mailbox():
            """The player's mailbox: A before it, Z in its menu (its letters go to the pockets), B. What the
            mailbox and the pockets held before and after."""
            got = {"before": {"pockets": pockets(), "box": box()}, "menu": False, "after": None, "closed": False}
            boxes = [a for a in t.actors() if a["name"] == MAILBOX]
            if not (p.outside() and boxes):
                return got
            mine = min(boxes, key=lambda a: (a["pos"][0] - emu_town.MAT[0]) ** 2 + (a["pos"][2] - emu_town.MAT[1]) ** 2)
            for below in (40, 30, 50):                  # before it, facing it
                t.drop(mine["pos"][0], mine["pos"][2] + below, 20)
                n64.frames(10, 0)
                n64.frames(10, DIRECTIONS["u"])
                n64.frames(4, 0)
                n64.press(A, 3, 60)
                n64.frames(60, 0)
                if t.menu_open():
                    got["menu"] = True
                    break
                p.advance(60, 6)                        # a word instead (someone by the mailbox): played through
            if got["menu"]:
                tap(Z, 40)
                n64.screenshot(os.path.join(work, label + "-mailbox.png"))
                n64.press(B, 3, 60)
                for _ in range(60):
                    if not t.menu_open():
                        got["closed"] = True
                        break
                    n64.frames(10, 0)
                n64.frames(40, 0)
            got["after"] = {"pockets": pockets(), "box": box()}
            got["now"] = now()
            return got

        def tap(buttons, wait=14):
            n64.frames(6, buttons)
            n64.frames(wait, 0)

        def clerk(fetch, wanted=None):
            """The post office's clerk, her third answer, and in her menu a letter of the pockets' into the
            Controller Pak's store, or back (fetch: the letter of the key wanted). The letter stored is the
            first the player received (not one to send) whose key no other letter of the save has. Z takes
            and puts down; B, the question, its first answer, and the game writes the Pak and the flash; then
            her last answer, when she asks again. What was seen on the way."""
            got = {"before": now(), "spoken": False, "menu": False, "letter": None, "taken": None, "put": None,
                   "closed": False, "ended": None, "now": None}
            if not (p.outside() and t.go_block(p.block_of("post"), set()) and
                    p.enter("Post_Office", emu_job.POST_DOOR)):
                return got
            who = [a for a in t.actors() if a["name"] == CLERK]
            if not who:
                return got
            everywhere = save_keys()
            mine = places()
            if fetch:
                free = [k for k in range(10) if k not in [i for i, _, _ in mine]]
                place = free[0] if free else None       # where it goes back: the pockets' first free place
            else:
                pick = [(i, key, m) for i, key, m in mine if m[KIND_AT] != SENDING and everywhere.count(key) == 1]
                place = pick[0][0] if pick else None
                if pick:
                    got["letter"] = {"place": place, "key": pick[0][1], "mail": pick[0][2].hex(),
                                     "kind": pick[0][2][KIND_AT], "rest": got["before"]["whole"].get(pick[0][1])}
            if place is None:
                return got
            column, row = place % POCKET_COLUMNS, place // POCKET_COLUMNS
            asked = []

            def answer(count):
                """Her third answer first; when she asks again after the menu, her last (no more)."""
                asked.append(count)
                return 2 if len(asked) == 1 else count - 1
            got["spoken"], how = p.talk(who[0], limit=300, quiet=10, pick=answer)
            got["menu"] = how == "menu"
            if not got["menu"]:
                return got
            for _ in range(30):                         # the store read from the Pak
                if store() is not None and (not fetch or store()):
                    break
                n64.frames(20, 0)
            n64.frames(40, 0)
            got["opened"] = {"pockets": pockets(), "store": store()}
            left, right, up, down = (DIRECTIONS[k] for k in "lrud")
            # the hand begins on the store's first place, in its first row: the pockets' two columns are to its left
            home = [left] * (STORE_HAND + 2) + [0]                  # the hand on the pockets' first place
            to_pocket = [right] * column + [down] * row
            to_store = [up] * row + [right] * (STORE_HAND + 2 - column)
            for way in home:
                tap(way)
            for way in ([right] * (STORE_HAND + 2) if fetch else to_pocket) + [Z]:   # Z takes the letter under it
                tap(way, 40 if way == Z else 14)
            got["taken"] = {"pockets": pockets(), "store": store()}
            for way in ([left] * (STORE_HAND + 2 - column) + [down] * row if fetch else to_store) + [Z]:   # and puts it
                tap(way, 40 if way == Z else 14)
            got["put"] = {"pockets": pockets(), "store": store()}
            n64.screenshot(os.path.join(work, label + "-store.png"))
            n64.press(B, 3, 80)                         # the question,
            n64.press(A, 3, 80)                         # its answers,
            n64.press(A, 3, 40)                         # the first: the Pak and the flash are written
            shut = 0
            for _ in range(120):                        # the menus over one another close one by one
                shut = 0 if t.menu_open() else shut + 1
                if shut == 6:
                    break
                n64.frames(20, 0)
            got["closed"] = shut == 6
            n64.frames(60, 0)
            got["ended"] = p.advance(400, 12, pick=answer)
            got["asked"] = asked
            got["now"] = now()
            got["everywhere"] = save_keys()
            got["out"] = p.outside()
            return got

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
                for fg in list(homes) * 2:              # a window that opened is no proof: the animal's memory is
                    how = p.animal(fg, homes)
                    result["met"] = {"animal": fg, "ended": how,
                                     "known": fg in emu_job.acquainted(n64.read(cd, 0x10000))}
                    if how == "free" and result["met"]["known"]:
                        break
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
                for fg in list(homes) * 2:
                    read = len(pages)
                    how = p.animal(fg, homes)
                    result["talked"] = {"animal": fg, "ended": how, "pages": len(pages) - read}
                    if how == "free" and len(pages) > read:
                        break
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
            if result["arrived"] and "mail" in steps:
                result["mail"] = mailbox()
            if result["arrived"] and "store" in steps:
                result["stored"] = clerk(False)
            if result["arrived"] and "fetch" in steps:
                result["fetched"] = clerk(True, plan.get("wanted"))
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


def times(n):
    """How many times the Controller Pak's file holds a letter's text, in words."""
    return "nowhere" if n == 0 else "once" if n == 1 else "%d times" % n


def clean(run):
    """Whether a session arrived, read its pages clean, never hung, and the arena never said no."""
    return (bool(run) and run["arrived"] and not run["hang"] and not run["problems"] and run["pages"] > 0 and
            bool(run["watch"]) and run["watch"]["fails"] == 0)


def arena(run):
    seen = run and run["watch"]
    return ("the arena never said no (%d allocations, the largest free block %s at its least)" % (
        seen["calls"], seen["least"]) if seen and not seen["fails"] else "THE ARENA SAID NO" if seen else "not watched")


def report(towns, home, away, pak, visit, other, back, kept, store=None, lying=None, fetch=None, whole=None,
           copies=()):
    """The checks. towns: {home, other}: make_town's answers; home, visit, back: the trip's three sessions;
    away, other, kept: the flash's facts after each (the home's with its player away, the other town's, the
    home's with its player back); pak: the Controller Pak's facts after the first. store, fetch: the two
    sessions at the post office; lying, whole: the flash's facts after each (the letter lying on the Pak, and
    back in the pockets); copies: how many times the Pak's file held the letter's text before them and after
    each."""
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
    box = (store or {}).get("mail") or {}
    came = [k for k in (box.get("after") or {}).get("pockets", []) if k not in box["before"]["pockets"]] \
        if box.get("before") else []
    check(bool(box.get("menu")) and bool(box.get("closed")) and bool(came) and
          sorted(came) == sorted(box["before"]["box"]) and box["after"]["box"] == [] and
          all(k in box["now"]["whole"] for k in came),
          "the mailbox, as a player empties it: %s" % (
              "A before it opens the item screen over its %d letter%s, which the game had delivered; Z takes "
              "them to the pockets, B closes; each has its rest in the extension (the town keeps the "
              "extension it was made with)" % (len(came), "" if len(came) == 1 else "s")
              if box.get("menu") and came and all(k in box["now"]["whole"] for k in came)
              else "NOT EMPTIED AS MEANT, OR A LETTER OF IT HAS NO REST (%s)" % (
                  {k: box.get(k) for k in ("menu", "closed", "before", "after")},)))
    put = (store or {}).get("stored") or {}
    mine = put.get("letter") or {}
    seen, key, rest2 = put.get("now"), mine.get("key"), mine.get("rest")
    own = [letter["key"]] if letter else []
    moved = (bool(key) and bool(put.get("taken")) and key not in put["taken"]["pockets"] and
             bool(put.get("put")) and key not in put["put"]["pockets"] and put["put"]["store"] == [[STORE_HAND, key]])
    written = len(copies) > 1 and copies[1] == copies[0] + 1
    alone = bool(put.get("everywhere") is not None) and key not in (put.get("everywhere") or [key])
    safe = bool(seen) and bool(key) and bool(rest2) and seen["whole"].get(key) == rest2 and key in seen["marked"]
    size2 = sum(len(part) // 2 for part in rest2) if rest2 else 0
    check(clean(store) and bool(put.get("spoken")) and bool(put.get("menu")) and moved and bool(put.get("closed")) and
          put.get("ended") == "free" and written and bool(seen) and alone and safe and mine.get("kind") != SENDING,
          "the post office's clerk, her third answer, with a letter the player received (the store takes no "
          "letter its player wrote to send): %s" % (
              "Z takes it from the pockets (its place %d), Z puts it down in the store, B and the question's "
              "first answer: the game writes the store onto the Controller Pak (its file holds the letter's "
              "text %s now, %s before) and the town onto the Game Pak; no letter of the save holds the rest's "
              "key now, and the extension keeps its %d bytes (the header's, the body's and the footer's), marked "
              "as the Pak's" % (mine["place"], times(copies[1]), times(copies[0]), size2)
              if moved and put.get("closed") and written and alone and safe
              else "THE REST IS GONE FROM THE EXTENSION (its rests %s, the Pak's marks %s): THE LETTER COMES "
                   "BACK FROM THE PAK CUT AT ITS %dTH BYTE" % (sorted(seen["rests"]), seen["marked"], BODY)
              if moved and put.get("closed") and written and seen and alone
              else "NOT STORED AS MEANT (%s; the Pak's file %s)" % (
                  {k: put.get(k) for k in ("spoken", "menu", "letter", "opened", "taken", "put", "closed", "ended",
                                           "everywhere")}, list(copies))))
    check(emu_memory.saved(store) and bool(lying) and bool(key) and key not in lying["letters"] and bool(rest2) and
          lying["whole"].get(key) == rest2 and key in lying["marked"] and
          all(k in lying["letters"] and lying["rests"].get(k) == letter["rest"] for k in own),
          "the gyroid's save after it, the letter lying on the Controller Pak: %s" % (
              "the flash has no such letter of the player's, and its rest all the same, marked; the letter the "
              "player wrote is in the pockets with its own"
              if lying and key and lying["whole"].get(key) == rest2 and key in lying["marked"]
              else "THE FLASH DOES NOT KEEP THE REST (%s)" % (
                  lying and {"letters": lying["letters"], "rests": sorted(lying["rests"]), "marked": lying["marked"]},)))
    got = (fetch or {}).get("fetched") or {}
    seen = got.get("now")
    text = bytes.fromhex(mine["mail"])[TEXT_AT:TEXT_AT + TEXT_SIZE] if mine.get("mail") else None
    home_again = (bool(key) and bool(got.get("opened")) and got["opened"]["store"] == [[STORE_HAND, key]] and
                  key not in got["opened"]["pockets"] and bool(got.get("taken")) and got["taken"]["store"] == [] and
                  bool(got.get("put")) and key in got["put"]["pockets"] and got["put"]["store"] == [])
    back_mail = [bytes.fromhex(m) for m in seen["mail"]] if seen else []
    same = (bool(seen) and bool(text) and any(m[TEXT_AT:TEXT_AT + TEXT_SIZE] == text for m in back_mail) and
            seen["whole"].get(key) == rest2)
    check(clean(fetch) and bool(got.get("spoken")) and bool(got.get("menu")) and home_again and
          bool(got.get("closed")) and got.get("ended") == "free" and bool(rest2) and same and
          emu_memory.saved(fetch) and bool(whole) and key in whole["letters"] and
          whole["whole"].get(key) == rest2 and len(copies) > 2 and copies[2] == copies[0],
          "the next power-on, the clerk again: %s" % (
              "the store read from the Controller Pak has the letter in its place; Z takes it, Z puts it down "
              "in the pockets, B and the first answer: the letter is whole, its %d bytes past the cartridge's "
              "under its key as the game filed them, in RAM and in the flash the gyroid then writes; the Pak's "
              "file holds its text %s, as before the store" % (size2, times(copies[2]))
              if home_again and same and whole and whole["whole"].get(key) == rest2 and len(copies) > 2
              else "THE LETTER IS BACK WITHOUT ITS REST (the extension's rests %s; the flash's %s): IT ENDS AT "
                   "ITS %dTH BYTE" % (sorted(seen["rests"]), whole and sorted(whole["rests"]), BODY)
              if home_again and seen
              else "NOT FETCHED AS MEANT (%s; the Pak's file %s)" % (
                  {k: got.get(k) for k in ("spoken", "menu", "opened", "taken", "put", "closed", "ended")},
                  list(copies))))
    runs = [r for r in (home, visit, back, store, fetch) if r]
    check(len(runs) == 5 and all(clean(r) for r in runs),
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
    work = os.path.join(out, "pak")                     # one pad's Controller Pak through the five power-ons
    home = away = pak = visit = other = back = kept = store = lying = fetch = whole = None
    copies = []
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
    stored = None                                       # the letter the store session puts on the Pak
    if back and back["saved"]:
        kept = flash_facts(work, rom)
        before = keep_pak(work, os.path.join(out, "pak-before-store"))
        store = run("store", answers=[0, 0], steps=["mail", "store", "save"])
        stored = ((store or {}).get("stored") or {}).get("letter")
        copies.append(pak_copies(before, stored and stored["mail"]))
    if store and store["saved"]:
        lying = flash_facts(work, rom)
        copies.append(pak_copies(work, stored and stored["mail"]))
        fetch = run("fetch", answers=[0, 0], steps=["fetch", "save"])
    if fetch and fetch["saved"]:
        whole = flash_facts(work, rom)
        copies.append(pak_copies(work, stored and stored["mail"]))
    report(towns, home, away, pak, visit, other, back, kept, store, lying, fetch, whole, copies)
    json.dump({"towns": towns, "home": home, "away": away, "pak": pak, "visit": visit, "other": other,
               "back": back, "kept": kept, "store": store, "lying": lying, "fetch": fetch, "whole": whole,
               "copies": copies}, open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_trip: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
