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

  at home: an animal met, a letter written to it from a sheet of paper in the pockets (two are given), longer
  than the 96 bytes the cartridge keeps of a letter's body (the en build files the rest in the save's
  extension, under a key made of the letter's own bytes: m_handbill.c); the letter stays in the pockets. The
  station's master,
  the first answer: he saves the player onto the Controller Pak, the town onto the Game Pak, and the train
  leaves. The game takes the traveller's things out of the save then, letters too, so no letter of the save
  holds the rest's key while the trip lasts: the extension must keep it all the same (the copy of the
  player's record for the Pak files a record of the trip, and all that was filed before it is kept:
  code/m_string.c's mCPk_en_CopyPrivateInfo, m_handbill.c's mHandbill_en_CollectExt). And the rest goes with
  the player: the Controller Pak's record of a player ends in 0xFE bytes the game never reads, where the en
  build packs the rests of the letters in the pockets (code/m_string.c's mCPk_en_PackRests);

  in the other town: the greeter's one question, and the player arrives as a visitor (the player's number is
  4, the player's record is not the save's but g_foreigner_private), pockets and letter come with the Pak;
  an animal of that town is spoken to. Then the power is cut there, as a visitor may do it too: at the next
  power-on of that town the visitor arrives again, a reset is counted in the traveller's own record (the
  count comes and goes with the Pak) and Mr. Resetti is up by the station; he is heard out. The letter is
  whole in that town, both times: its rest comes out of the Pak's record into the town's extension in RAM
  (mCPk_en_PakPrivateLoad). An animal of that town is met and a second letter written to it there, from
  the other sheet of paper; then the station's master and the train home: the Pak's record now carries both
  rests, the one written there first (no other town has it). The game writes a town's flash when a visitor
  arrives and when one leaves, so the collection keeps the rests of a visitor's letters while the visitor is
  in play (mCPk_en_VisitorKeys): without that the letter was whole for four frames;

  at home again: the greeter copies the Pak's record into the save and the player is a resident again, both
  letters in the pockets, their rests in the extension as they were written; the gyroid saves, and the flash
  holds them.

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

  the next power-on: the clerk again, the letter back from the store into the pockets, whole;

  and one more: the letter into the store again, and the closing question's other two answers. The question
  is the edit-end check's, which the cartridge asks with the same words of the letter board, the bulletin
  board, the town tune and the store; the GameCube's English speaks of a text, so for the store the en
  build has words of its own (script/ui.txt; code/m_string.c's mSM_en_EEQuestion). The second answer goes
  back to the letters, the menu still open and the letter where it was put; the third leaves with nothing
  written, the letter in the pockets again and the Pak's file as it was. The answers' table is read in RAM
  while they are up.

Then a second trip, with a Controller Pak and flashes of its own made of the same two towns: the mailbox's
letter, one the player received, goes along in the pockets; the other town's post office puts it into the
Pak's store (a visitor's letters are in no save: the mark that keeps a stored letter's rest is made in that
town's extension); at home the gyroid saves, and at the next power-on the clerk gives the letter back. Its
rest is at home, where no letter holds its key once the trip is over: the copy of the player's record for
the Pak lists the keys of the letters the traveller leaves with (a record before the trip's), and the copy
back marks as the Pak's the rests of those that are not in the pockets (mCPk_en_CopyPrivateInfo). The
build before that gave the letter back cut at the cartridge's bytes.

Every page read on the way is judged as the other checks judge one (emu_year's Town.page_problems), and the
allocator's hook is in place from each power-on (tests/emu_memory.py's watch): the arena must never say no.
"""

import glob
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(HERE, "..", "emu"))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
sys.path.insert(0, HERE)
import af_ui  # noqa: E402
import aflz  # noqa: E402
import emu_job  # noqa: E402
import emu_letters  # noqa: E402
import emu_memory  # noqa: E402
import emu_menus  # noqa: E402
import emu_psel  # noqa: E402
import emu_reset  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_write  # noqa: E402
import emu_year  # noqa: E402
from afplay import DIRECTIONS, dialogue_open, name_dial_open, presses, type_on_dial, until, walk  # noqa: E402
from n64emu import N64, ORIGINAL_MD5, A, B, START, Z, Hang  # noqa: E402

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
TRIP_KEY = 0x54524930                   # and the record before it: the keys of the letters the traveller left with
DAY = (2001, 4, 15)
CLOCKS = {"home": DAY + (10, 0, 0), "visit": DAY + (11, 30, 0), "again": DAY + (12, 15, 0), "back": DAY + (13, 0, 0),
          "store": DAY + (14, 30, 0), "fetch": DAY + (16, 0, 0), "leave": DAY + (17, 0, 0),
          # the second trip: its own Controller Pak and flashes
          "out": DAY + (10, 0, 0), "there": DAY + (11, 30, 0), "return": DAY + (13, 0, 0),
          "claim": DAY + (14, 30, 0)}
PAPERS = 2                              # the sheets of paper given at home: a letter there, one in the other town
# the Controller Pak's record of a player (m_cpak.h's Passport): the player's record in it, and its last bytes,
# where the en build packs the pockets' letters' rests: a tag, the records' length, the records
PASSPORT_PRIVATE, PASSPORT_RESTS, PASSPORT_SIZE = 8, 0x1102, 0x1200
PACK_TAG, PACK_HEAD, RECORD_HEAD = b"LT", 3, 7
STORE_WINDOW = 3                        # the edit-end check's window the letter store opens
EE_WINDOW_AT = 0x10088 + 15 * 0x48 + 0x38   # the submenu's overlays: the window program 15 was opened with
# the store's own words for that question's second and third answers (script/ui.txt), and their strings
STORE_ANSWERS = (("EE_STORE_ANSWER1", "mEE_en_storeAnswer1"), ("EE_STORE_ANSWER2", "mEE_en_storeAnswer2"))
CLERK = "Npc_Post_Girl"
MAILBOX = "MailBox"                     # the actor of a house's mailbox (ac_mailbox)
RESETTI = "Npc_Majin"                   # the first of Mr. Resetti's actors: a first reset's (tests/emu_reset.py)
# the cartridge itself (a plan's "plain": the matching ROM, played from its own tree's symbols): its message
# window (the en build's is mMsg_window, in code_en) and the name the emulator's list gives its saves
JP_WINDOW, JP_NAME = "B_80142410_jp", "Doubutsu no Mori (J) [!]"
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
    """What a check asks of a player's record and an extension: the things, the letters' keys, the records;
    a trip's own records apart: whose trips (the players' numbers), and the keys each left with (taken)."""
    records = emu_letters.Ext.parse(bytes(ext)).records if ext else []
    keys = [key for key, h, b, f in records]
    trips = [key for key in keys if HOLD_KEY <= key < HOLD_KEY + 4]
    lists = {key: b for key, h, b, f in records if TRIP_KEY <= key < TRIP_KEY + 4}
    own = set(trips) | set(lists)                                  # no letter's rests
    line = max((keys.index(key) for key in trips), default=-1)     # what lies before the last of them is kept
    marked = emu_letters.Ext.parse(bytes(ext)).held if ext else set()
    return {"name": bytes(private[:6]).hex(), "id": bytes(private[:0x10]).hex(), "exists": private[EXISTS],
            "marked": ["%08x" % key for key in keys if key in marked],
            "pockets": [v for v in struct.unpack(">15H", bytes(private[POCKETS:POCKETS + 30])) if v],
            "letters": ["%08x" % emu_letters.mail_key(m) for m in held(private)],
            "mail": [m.hex() for m in held(private)],
            "rests": {"%08x" % key: b.hex() for key, h, b, f in records if key not in own},
            "whole": {"%08x" % key: [h.hex(), b.hex(), f.hex()] for key, h, b, f in records if key not in own},
            "trips": [key - HOLD_KEY for key in trips],
            "taken": {str(key - TRIP_KEY): ["%08x" % k for k in struct.unpack(">%dI" % (len(b) // 4),
                                                                             b[:len(b) // 4 * 4])]
                      for key, b in lists.items()},
            "held": ["%08x" % key for key in keys[:line + 1] if key not in own]}


def packed(area):
    """{key: [the header's rest, the body's, the footer's]} (hex, in their order) of the records in the last
    bytes of a Controller Pak's record of a player; {} where the bytes are not the en build's."""
    out = {}
    if bytes(area[:2]) != PACK_TAG or area[2] > len(area) - PACK_HEAD:
        return out
    at, end = PACK_HEAD, PACK_HEAD + area[2]
    while at + RECORD_HEAD <= end:
        key, h, b, f = struct.unpack(">I3B", bytes(area[at:at + RECORD_HEAD]))
        h &= 0x7F
        if at + RECORD_HEAD + h + b + f > end:
            break
        rest = bytes(area[at + RECORD_HEAD:at + RECORD_HEAD + h + b + f])
        out["%08x" % key] = [rest[:h].hex(), rest[h:h + b].hex(), rest[h + b:].hex()]
        at += RECORD_HEAD + h + b + f
    return out


def store_words():
    """The store's own answers as the font's codes: [the second's bytes, the third's] (script/ui.txt)."""
    own = af_ui.read_text(os.path.join(ROOT, "script", "ui.txt"))
    return [af_ui.n64_bytes(name, own[name]) for name, _ in STORE_ANSWERS]


def cart_file(work, rom, kind):
    """The emulator's file in work for a cartridge's flash (kind ".fla") or its pad's Controller Pak (".mpk")."""
    md5 = hashlib.md5(rom).hexdigest().upper()
    return os.path.join(work, "%s-%s%s" % (JP_NAME if md5 == ORIGINAL_MD5 else "DOUBUTSUNOMORI (rebuilt)", md5[:8], kind))


def flash_facts(work, rom):
    """The flash in work, as the game left it: its first player's facts (the cartridge's own flash has no
    extension)."""
    save, ext = aflz.unpack(emu_save.read_fla(cart_file(work, rom, ".fla"))[:0x10000])
    return facts(save[PRIVATE:PRIVATE + PRIVATE_SIZE], ext or b"")


def pak_facts(work, me):
    """The Controller Pak's file in work: the facts of the record that begins with the player's id, and the
    rests packed after it; or None."""
    for path in glob.glob(os.path.join(work, "*.mpk")):
        pak = open(path, "rb").read()
        at = pak.find(bytes.fromhex(me))
        if at >= 0 and at + PRIVATE_SIZE <= len(pak):
            record = at - PASSPORT_PRIVATE
            return dict(facts(pak[at:at + PRIVATE_SIZE], b""),
                        packed=packed(pak[record + PASSPORT_RESTS:record + PASSPORT_SIZE]))
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
    it. The plan: label, clock, answers (the greeter's); town (a fresh flash made of it, PAPERS sheets of
    paper in its first player's first free pockets) or flash (a flash's file copied in); steps, of resetti
    (he is heard out), meet (an animal spoken to), write (a letter, from a sheet of paper in the pockets;
    turn: the dial's pushes begin a direction further on, for a letter of another text: two letters of the
    same text have one key and one rest between them),
    talk (an animal spoken to, no more asked), depart (the station's master, the train), store and fetch
    (the post office's clerk: the pockets' first letter into the Controller Pak's store, and back), unstored
    (into the store, and the closing question's second and third answers), save (the gyroid, to stop);
    early: those of mail and store to do before depart (the steps' own order has the train first). With
    plain, rom_path and af_en are the cartridge itself
    and the matching tree: the flash is the cartridge's (no compression, no extension), the allocator is not
    hooked, and the pages, which are Japanese, are counted and not judged."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    names = emu_year.actor_names(af_en)
    work, label, steps, plain = plan["work"], plan["label"], plan["steps"], bool(plan.get("plain"))
    if plain:
        syms["mMsg_window"] = syms[JP_WINDOW]
    os.makedirs(work, exist_ok=True)
    fla = cart_file(work, rom, ".fla")
    if plan.get("town"):
        save = bytearray(open(plan["town"], "rb").read())
        flags = struct.unpack(">I", save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4])[0] & ~emu_year.JOB_BITS
        save[emu_year.EVENT_FLAGS:emu_year.EVENT_FLAGS + 4] = struct.pack(">I", flags)
        for _ in range(PAPERS if "write" in steps else 0):  # bought at the shop, as a player would have
            at = PRIVATE + POCKETS
            slot = next(s for s in range(15) if not save[at + 2 * s:at + 2 * s + 2].strip(b"\0"))
            save[at + 2 * slot:at + 2 * slot + 2] = struct.pack(">H", PAPER)
        if plain:                                       # the cartridge's slot: the save as it is
            image, compressed = emu_psel.fix(bytes(save)) + bytes(aflz.SLOT_SIZE - len(save)), True
        else:
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
        # (the cartridge has no table of English answers: its window's address stands in, read and not used)
        t = emu_year.Town(n64, syms["mMsg_window"], syms.get("mChoice_en_strings", syms["mMsg_window"]), names, syms)
        pages = result["pages"]
        p = emu_job.Player(n64, t, syms, pages)

        def now():
            """The player in play: the number, whose record it is, and its facts with RAM's extension."""
            at = n64.u32(cd + PRIVATE_PTR)
            size = 0 if plain else n64.u32(syms["mFRm_en_extSize"])
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
            got["menu"] = p.open_mailbox(mine)          # at its corner, facing it
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

        def answers_up():
            """The edit-end check's answers while they are up: {window, answers: [[the string's address, its
            bytes in hex]]}, from the check's own tables in RAM (tests/emu_menus.py finds them so), or None."""
            ram = n64.read(0x80000000, 0x800000)
            at = ram.find(emu_menus.EE_WIN0)
            ovl = n64.u32(t.play() + emu_write.SUBMENU + emu_write.SUBMENU_OVL)
            if at < 0 or not 0x80000000 <= ovl < 0x80800000:
                return None
            out = []
            for i in range(3):
                ptr, size = struct.unpack(">Ii", ram[at + emu_menus.EE_ANSWERS_AT + 8 * i:][:8])
                text = n64.read(ptr, size) if 0x80000000 <= ptr < 0x80800000 and 0 <= size <= 32 else b""
                out.append([ptr, text.hex()])
            return {"window": n64.u32(ovl + EE_WINDOW_AT), "answers": out}

        def tap(buttons, wait=14):
            n64.frames(6, buttons)
            n64.frames(wait, 0)

        def clerk(fetch, wanted=None, ends=(0,)):
            """The post office's clerk, her third answer, and in her menu a letter of the pockets' into the
            Controller Pak's store, or back (fetch: the letter of the key wanted). The letter stored is the
            first the player received (not one to send) whose key no other letter of the save has (a
            visitor's: no letter of the save at all, the visitor's own being in none). Z takes
            and puts down; B, the question, its first answer, and the game writes the Pak and the flash; then
            her last answer, when she asks again. ends: the question's answers given in turn, B and A before
            each (its second goes back to the letters, its third leaves with nothing written). What was seen
            on the way."""
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
                others = 0 if got["before"]["record"] == "a visitor's" else 1
                pick = [(i, key, m) for i, key, m in mine
                        if m[KIND_AT] != SENDING and everywhere.count(key) == others]
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
            got["asked_at"] = []
            for end in ends:
                n64.press(B, 3, 80)                     # the question,
                n64.press(A, 3, 80)                     # its answers,
                for _ in range(end):
                    tap(down)
                n64.screenshot(os.path.join(work, "%s-store-answer-%d.png" % (label, end)))
                words = answers_up()
                n64.press(A, 3, 40)                     # the first: the Pak and the flash are written
                n64.frames(60 if end == 1 else 0, 0)
                got["asked_at"].append({"answer": end, "menu": bool(t.menu_open()), "pockets": pockets(),
                                        "store": store(), "words": words})
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

        def resetti():
            """Mr. Resetti for a visitor: the count and the flag of the player in play, which of his actors is
            there, how he is met (he stops a player who walks off, or waits to be gone to), and his talk."""
            def his():
                return [a for a in t.actors() if "Majin" in a["name"]]

            def state():
                at = n64.u32(cd + PRIVATE_PTR)
                return {"count": n64.read(at + emu_reset.RESET_COUNT, 1)[0],
                        "flag": n64.read(cd + emu_reset.RESET_FLAG, 1)[0]}
            got = {"start": state(), "actors": sorted({a["name"] for a in his()}), "how": [], "pages": 0,
                   "messages": [], "ended": None, "end": None, "left": None}
            read = len(pages)
            for _ in range(12):                         # until he is gone and the flag down
                mine = his()
                if not mine and not state()["flag"]:
                    break
                if os.environ.get("EMU_TRIP_TRACE"):
                    print("resetti: %s, the player at %s, %s, pages %s" % (
                        [(a["name"], [round(v) for v in a["pos"]]) for a in mine],
                        [round(v) for v in t.pos(t.player())], state(),
                        ["%#x" % pg["msg"] for pg in pages[read:]]), file=sys.stderr, flush=True)
                if t.window_main() == emu_year.HIDDEN and mine:
                    n64.frames(40, DIRECTIONS["d"])     # walking off: he stops the player
                    n64.frames(30, 0)
                    spoke = t.window_main() != emu_year.HIDDEN
                    if spoke or emu_reset.approach(t, mine[0]):
                        got["how"].append("walked off" if spoke else "went to him")
                        if len(got["how"]) == 1:
                            n64.frames(30, 0)
                            n64.screenshot(os.path.join(work, label + "-resetti.png"))
                got["ended"] = p.advance(1500, 12)
            got["pages"] = len(pages) - read
            got["messages"] = sorted({pg["msg"] for pg in pages[read:]})
            got["end"] = state()
            got["left"] = sorted({a["name"] for a in his()})
            return got

        try:
            low, used = (lambda: None, 0) if plain else emu_memory.watch_soon(n64, af_en, syms)
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
            if result["arrived"] and "resetti" in steps:
                result["resetti"] = resetti()
            if result["arrived"] and "meet" in steps:
                homes = animals()
                for fg in list(homes) * 2:              # a window that opened is no proof: the animal's memory is
                    how = p.animal(fg, homes)
                    result["met"] = {"animal": fg, "ended": how,        # known: to the player in play
                                     "known": fg in emu_job.acquainted(n64.read(cd, 0x10000),
                                                                       bytes.fromhex(now()["id"]))}
                    if how == "free" and result["met"]["known"]:
                        break
            if result["arrived"] and "write" in steps:
                before = now()["letters"]                # (the record in play: a visitor's is not the save's)
                slot = list(struct.unpack(">15H", n64.read(n64.u32(cd + PRIVATE_PTR) + POCKETS, 30))).index(PAPER)
                p.items()
                p.hand(slot)
                p.tag(1)                                # to write a letter: whom to
                n64.frames(60, 0)
                n64.press(A, 3, 40)                     # the address book,
                n64.press(A, 3, 60)                     # its first entry
                n64.frames(40, 0)
                for n in range(PUSHES):                  # (turn: another text, so another key)
                    v = DIRECTIONS["urld"[(n + plan.get("turn", 0)) % 4]]
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
                                     "papers": after["pockets"].count(PAPER)}
            if result["arrived"] and "talk" in steps:
                homes = animals()
                for fg in list(homes) * 2:
                    read = len(pages)
                    how = p.animal(fg, homes)
                    result["talked"] = {"animal": fg, "ended": how, "pages": len(pages) - read}
                    if how == "free" and len(pages) > read:
                        break
            early = plan.get("early") or []
            if result["arrived"] and "mail" in early:
                result["mail"] = mailbox()
            if result["arrived"] and "store" in early:
                result["stored"] = clerk(False)
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
            if result["arrived"] and "unstored" in steps:   # into the store and out of it with nothing written
                result["unstored"] = clerk(False, ends=(1, 2))
            if result["arrived"] and "save" in steps:
                result["before"] = now()
                result["saved"] = emu_memory.save_and_stop(t, emu_town.MAT, pages)
                n64.frames(120, 0)
            result["watch"] = low()
            result["made"] = emu_memory.made(result["watch"], names)
            result["flash"] = fla
        except Hang as e:
            result["hang"] = str(e)
            n64.screenshot(os.path.join(work, label + "-hang.png"))
    result["problems"] = ["%#x at %d: %s" % (pg["msg"], pg["at"], ", ".join(pg["problems"])) for pg in result["pages"]
                          if pg["problems"]]
    result["said"] = [[pg["msg"], pg["text"][:200], pg["problems"]] for pg in result["pages"]] if plan.get("said") else None
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
           copies=(), again=None, pak2=None, leave=None, trip2=None):
    """The checks. towns: {home, other}: make_town's answers; home, visit, back: the trip's three sessions, and
    again: the other town powered on a second time, the power cut on the visitor;
    away, other, kept: the flash's facts after each (the home's with its player away, the other town's, the
    home's with its player back); pak, pak2: the Controller Pak's facts after the first and as it leaves the
    other town. store, fetch, leave: the three sessions at the post office; lying, whole: the flash's facts
    after the first two (the letter lying on the Pak, and back in the pockets); copies: how many times the
    Pak's file held the letter's text before them and after each. trip2: the second trip's four sessions
    (out, there, return, claim) and the flash's facts after each (away, other, kept, whole)."""
    check(bool(towns) and all(towns.get(k) and towns[k]["settled"] and towns[k]["sound"] for k in ("home", "other")) and
          towns["home"]["id"][:24] != towns["other"]["id"][:24],
          "two towns made by the game from the route's states, the first house chosen by its talk: the route's "
          "own and one with other names typed at the dials (the players' and the towns' names differ: %s)" % (
              bool(towns) and bool(towns.get("home")) and bool(towns.get("other")) and
              towns["home"]["id"][:24] != towns["other"]["id"][:24]))
    met, letter = (home or {}).get("met"), (home or {}).get("written")
    rest = bytes.fromhex(letter["rest"]) if letter and letter.get("rest") else b""
    check(clean(home) and bool(met) and met["ended"] == "free" and met["known"] and bool(letter) and
          bool(letter["key"]) and letter["papers"] == PAPERS - 1 and len(rest) > 0,
          "at home: an animal met, and a letter written to it from a sheet of paper in the pockets (the "
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
    listed = bool(away) and bool(letter) and away["trips"] == [0] and letter["key"] in away["held"] and \
        away.get("taken") == {"0": [letter["key"]]}
    check(bool(away) and bool(letter) and bool(rest) and away["rests"].get(letter["key"]) == letter["rest"] and listed,
          "while the player is away no letter of the save holds the rest's key: %s" % (
              "the extension keeps it all the same, as it was written, before the record of the player's trip, "
              "and a record there lists the key as one the traveller left with"
              if away and letter and away["rests"].get(letter["key"]) == letter["rest"] and listed
              else "THE REST IS GONE FROM THE FLASH (the extension's rests %s, the records of a trip %s, kept "
                   "by them %s, the keys listed as left with %s)" % (
                       away and sorted(away["rests"]), away and away["trips"], away and away["held"],
                       away and away.get("taken"))))
    record = (((home or {}).get("before") or {}).get("whole") or {}).get(letter["key"]) if letter else None
    carried = bool(pak) and bool(letter) and bool(record) and list(pak["packed"]) == [letter["key"]] and \
        pak["packed"][letter["key"]] == record and record[1] == letter["rest"]
    check(carried,
          "and the rest goes with the player: %s" % (
              "the last bytes of the Controller Pak's record of the player hold the letter's record, as the "
              "extension has it (%d bytes with its key and lengths, of the %d there)" % (
                  RECORD_HEAD + sum(len(part) // 2 for part in record), PASSPORT_SIZE - PASSPORT_RESTS - PACK_HEAD)
              if carried else "THE PAK'S RECORD DOES NOT CARRY IT (%s; the extension's %s)" % (
                  pak and pak["packed"], record)))
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
    once = (again or {}).get("now")
    there_whole = bool(there) and bool(letter) and bool(rest) and there["rests"].get(letter["key"]) == letter["rest"]
    once_whole = bool(once) and bool(letter) and bool(rest) and once["rests"].get(letter["key"]) == letter["rest"]
    check(there_whole and once_whole and not there["trips"] and not once["trips"],
          "the letter is whole in the other town: %s" % (
              "its %d bytes past the cartridge's come out of the Controller Pak's record into that town's "
              "extension in RAM, under its key, as they were written, at both power-ons there" % len(rest)
              if there_whole and once_whole
              else "IT ENDS AT ITS %dTH BYTE THERE (the extension's rests at the first power-on %s, at the "
                   "second %s)" % (BODY, there and sorted(there["rests"]), once and sorted(once["rests"]))))
    his = (again or {}).get("resetti")
    check(clean(again) and bool(his) and bool(once) and once["number"] == VISITOR and once["record"] == "a visitor's"
          and his["start"] == {"count": 1, "flag": 1} and his["actors"] == [RESETTI] and bool(his["how"]) and
          his["ended"] == "free" and his["pages"] >= 5 and his["end"] == {"count": 1, "flag": 0} and not his["left"],
          "the power cut on the visitor, and the other town powered on again: %s" % (
              "the player arrives as a visitor once more, a reset counted in the traveller's own record (1, the "
              "flag up) and Mr. Resetti up by the station (%s: the player %s); his %d pages of %d messages heard "
              "out, clean; the flag is down and he is gone" % (
                  RESETTI, his["how"][0], his["pages"], len(his["messages"]))
              if his and his["how"] and his["ended"] == "free" and not his["left"]
              else "NOT AS A RESET GOES (%s; the player %s)" % (
                  his, once and {k: once[k] for k in ("number", "record")})))
    met2, letter2 = (again or {}).get("met"), (again or {}).get("written")
    rest2nd = bytes.fromhex(letter2["rest"]) if letter2 and letter2.get("rest") else b""
    check(clean(again) and bool(met2) and met2["ended"] == "free" and met2["known"] and bool(letter2) and
          bool(letter2["key"]) and letter2["papers"] == 0 and len(rest2nd) > 0 and bool(letter) and
          letter2["key"] != letter["key"],
          "a letter written in the other town: %s" % (
              "an animal of that town met, and a second letter written to it from the other sheet of paper: "
              "in the pockets, %d bytes of body, the %d past the cartridge's %d filed in that town's extension"
              % (BODY + len(rest2nd), len(rest2nd), BODY)
              if met2 and letter2 and letter2["key"] and rest2nd else "NOT WRITTEN AS MEANT (%s; %s)" % (met2, letter2)))
    left = (again or {}).get("left")
    check(clean(again) and bool(left) and left["spoken"] and left["gone"] and bool(other) and bool(towns) and
          bool(towns.get("other")) and other["id"] == towns["other"]["id"] and other["exists"] == 1,
          "the other town's station's master, the first answer: %s" % (
              "the player is saved onto the Controller Pak again, the train leaves, and the town's own player is "
              "in its flash as before" if left and left["gone"] and other and other["exists"] == 1
              else "NOT GONE AS THE GAME GOES (%s)" % (left,)))
    both = [letter2["key"], letter["key"]] if letter and letter2 and letter2.get("key") else []
    theirs = [((again or {}).get("before") or {}).get("whole", {}).get(k) for k in both]
    taken = bool(both) and bool(pak2) and list(pak2["packed"]) == both and \
        [pak2["packed"][k] for k in both] == theirs and all(theirs) and \
        [r[1] for r in theirs] == [letter2["rest"], letter["rest"]]
    check(taken and bool(other) and not other["trips"],
          "what goes home on the Controller Pak: %s" % (
              "its record of the player carries both letters' rests, the one written there first (no other town "
              "has it), each as that town's extension had it (that town's flash, written with the visitor "
              "still in play, has %s of them: its next save drops them)" % (
                  ["neither", "one", "both"][sum(k in other["rests"] for k in both)],)
              if taken and other and not other["trips"]
              else "NOT BOTH RESTS (the Pak's %s; the town's flash %s, its trips %s)" % (
                  pak2 and pak2["packed"], other and sorted(other["rests"]), other and other["trips"])))
    here = (back or {}).get("now")
    wrote = {m["key"]: m["rest"] for m in (letter, letter2) if m and m.get("key") and m.get("rest")}
    check(clean(back) and back["asked"] == 1 and bool(here) and here["number"] == 0 and
          here["record"] == "the save's" and here["exists"] == 1 and bool(again) and bool(again.get("before")) and
          here["pockets"] == again["before"]["pockets"] and bool(letter) and bool(letter2) and
          here["mail"] == [letter["mail"], letter2["mail"]],
          "at home again: %s; %s" % (
              "the greeter's one question, the Pak's record copied into the save, and the player is a resident "
              "again (the player's number 0), pockets and both letters back" if here and here["number"] == 0 and
              here["record"] == "the save's" else "NOT HOME (%s)" % (
                  here and {k: here[k] for k in ("number", "record", "exists", "pockets", "letters")},),
              arena(back)))
    whole_here = bool(here) and len(wrote) == 2 and all(here["rests"].get(k) == r for k, r in wrote.items())
    over = bool(here) and not here["trips"] and not here.get("taken") and not here["marked"]
    check(whole_here and over,
          "both letters are whole at home: %s" % (
              "the first's %d bytes past the cartridge's are in the extension under its key, as they were "
              "written here, the second's %d come out of the Controller Pak's record, as they were written "
              "there; the trip's records are gone, and no rest is marked as the Pak's (the letter that left "
              "came back in the pockets)" % (len(rest), len(rest2nd))
              if whole_here and over
              else "THEY ARE NOT (the extension's rests %s, the records of a trip %s, the keys left with %s, "
                   "the Pak's marks %s): A LETTER ENDS AT ITS %dTH BYTE, OR THE TRIP'S RECORDS STAY" % (
                       here and sorted(here["rests"]), here and here["trips"], here and here.get("taken"),
                       here and here["marked"], BODY)))
    kept_whole = bool(kept) and len(wrote) == 2 and kept["letters"] == list(wrote) and \
        all(kept["rests"].get(k) == r for k, r in wrote.items())
    check(emu_memory.saved(back) and kept_whole and not kept["trips"] and kept["exists"] == 1,
          "the player's gyroid saves and the game leaves for its title: %s" % (
              "the flash holds both letters and their rests" if kept_whole
              else "THE FLASH DOES NOT HOLD THE LETTERS WHOLE (%s)" % (
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
    own = dict(wrote)
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
          len(own) == 2 and all(k in lying["letters"] and lying["rests"].get(k) == r for k, r in own.items()),
          "the gyroid's save after it, the letter lying on the Controller Pak: %s" % (
              "the flash has no such letter of the player's, and its rest all the same, marked; the letters the "
              "player wrote are in the pockets with their own"
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
    undo = (leave or {}).get("unstored") or {}
    asks = undo.get("asked_at") or []
    mine3, was = (undo.get("letter") or {}).get("key"), ((undo.get("before") or {}).get("letters"))
    up = [a.get("words") or {} for a in asks]
    said = [[bytes.fromhex(text) for _, text in a.get("answers", [])] for a in up]
    ours = store_words()
    worded = len(up) == 2 and all(a.get("window") == STORE_WINDOW for a in up) and \
        all(len(s) == 3 and bool(s[0]) and s[1:] == ours for s in said)
    stayed = len(asks) == 2 and asks[0]["answer"] == 1 and asks[0]["menu"] and bool(undo.get("put")) and \
        asks[0]["store"] == undo["put"]["store"] == [[STORE_HAND, mine3]] and asks[0]["pockets"] == undo["put"]["pockets"]
    undone = len(asks) == 2 and asks[1]["answer"] == 2 and not asks[1]["menu"] and bool(was) and \
        asks[1]["pockets"] == was and bool(undo.get("now")) and undo["now"]["letters"] == was and \
        len(copies) > 3 and copies[3] == copies[2]
    check(clean(leave) and bool(undo.get("spoken")) and bool(undo.get("menu")) and bool(mine3) and worded and stayed
          and undone and bool(undo.get("closed")) and undo.get("ended") == "free",
          "the store's closing question, its other answers: %s" % (
              "asked of the store the check has the en build's own words (window %d; after the first answer %r "
              "and %r); a letter put into the store, the second answer: the menu is still open and the letter "
              "where it was put; the third: the menus close, the letter is in the pockets again, and the "
              "Controller Pak's file holds its text %s, as before" % (
                  STORE_WINDOW, ours[0].decode("ascii"), ours[1].decode("ascii"), times(copies[3]))
              if worded and stayed and undone
              else "NOT THE STORE'S WORDS (the window and answers up: %s)" % (up,) if not worded
              else "NOT AS THE WORDS SAY (%s; the Pak's file %s)" % (
                  {k: undo.get(k) for k in ("spoken", "menu", "put", "asked_at", "closed", "ended")}, list(copies))))
    t2 = trip2 or {}
    out2, there2, back2, claim2 = (t2.get(k) for k in ("out", "there", "return", "claim"))
    away2, other2, kept2, whole2 = (t2.get(k) for k in ("away", "other", "kept", "whole"))
    box2 = (out2 or {}).get("mail") or {}
    came2 = [k for k in (box2.get("after") or {}).get("pockets", []) if k not in box2["before"]["pockets"]] \
        if box2.get("before") else []
    rests2 = {k: ((box2.get("now") or {}).get("whole") or {}).get(k) for k in came2}
    left2 = (out2 or {}).get("left")
    listed2 = bool(away2) and bool(came2) and away2["exists"] == 0 and away2["trips"] == [0] and \
        sorted((away2.get("taken") or {}).get("0", [])) == sorted(came2) and \
        all(rests2[k] and away2["whole"].get(k) == rests2[k] for k in came2)
    check(clean(out2) and bool(box2.get("menu")) and bool(box2.get("closed")) and bool(came2) and bool(left2) and
          left2["spoken"] and left2["gone"] and listed2,
          "a second trip, with a Controller Pak and flashes of its own: %s" % (
              "the mailbox's %d letter%s, which the game had delivered, to the pockets, and the station's master; "
              "while the player is away the flash keeps the rest%s, and a record before the trip's lists the "
              "key%s as left with the traveller" % (
                  len(came2), "" if len(came2) == 1 else "s", "" if len(came2) == 1 else "s",
                  "" if len(came2) == 1 else "s")
              if box2.get("menu") and came2 and left2 and left2["gone"] and listed2
              else "NOT GONE WITH A LETTER RECEIVED, OR ITS KEY IS NOT LISTED (the mailbox %s; the master %s; the "
                   "flash: the records of a trip %s, the keys left with %s, the rests %s)" % (
                       {k: box2.get(k) for k in ("menu", "closed", "before", "after")}, left2,
                       away2 and away2["trips"], away2 and away2.get("taken"), away2 and sorted(away2["rests"]))))
    put2 = (there2 or {}).get("stored") or {}
    mine2 = put2.get("letter") or {}
    key2s = mine2.get("key")
    rest2s = rests2.get(key2s)
    size2s = sum(len(part) // 2 for part in rest2s) if rest2s else 0
    seen2 = (there2 or {}).get("now")
    moved2 = (bool(key2s) and key2s in came2 and bool(put2.get("put")) and key2s not in put2["put"]["pockets"] and
              put2["put"]["store"] == [[STORE_HAND, key2s]])
    left3 = (there2 or {}).get("left")
    check(clean(there2) and bool(seen2) and seen2["number"] == VISITOR and seen2["record"] == "a visitor's" and
          bool(put2.get("spoken")) and bool(put2.get("menu")) and moved2 and bool(put2.get("closed")) and
          put2.get("ended") == "free" and mine2.get("kind") != SENDING and bool(left3) and left3["spoken"] and
          left3["gone"] and bool(other2) and key2s in other2["marked"],
          "the other town's post office, as a visitor: %s" % (
              "the clerk's third answer, and the letter goes from the pockets (its place %d) into the Controller "
              "Pak's store; the game writes the Pak and that town's flash, where the letter's rest, which came "
              "with the Pak, is marked as the Pak's; then the station's master, and the train home without the "
              "letter in the pockets" % mine2["place"]
              if moved2 and put2.get("closed") and left3 and left3["gone"] and other2 and key2s in other2["marked"]
              else "NOT STORED THERE AS MEANT (%s; the master %s; that town's flash: the Pak's marks %s)" % (
                  {k: put2.get(k) for k in ("spoken", "menu", "letter", "opened", "taken", "put", "closed", "ended")},
                  left3, other2 and other2["marked"])))
    here2 = (back2 or {}).get("now")
    others2 = [k for k in came2 if k != key2s]          # the letters that came back in the pockets
    safe2 = (bool(kept2) and bool(key2s) and bool(rest2s) and key2s not in kept2["letters"] and
             kept2["whole"].get(key2s) == rest2s and key2s in kept2["marked"] and not kept2["trips"] and
             not kept2.get("taken") and
             all(k in kept2["letters"] and k not in kept2["marked"] and kept2["whole"].get(k) == rests2[k]
                 for k in others2))
    check(clean(back2) and bool(here2) and here2["number"] == 0 and here2["record"] == "the save's" and
          bool(key2s) and key2s not in here2["letters"] and emu_memory.saved(back2) and safe2,
          "at home again without the letter, and the gyroid's save: %s" % (
              "no letter of the save holds the rest's key, and the flash keeps its %d bytes all the same, marked "
              "as the Controller Pak's (the letter left with the traveller and is not in the pockets that came "
              "back); %s; the trip's records are gone" % (
                  size2s, "the mailbox had no other letter" if not others2 else
                  "the other %d of the mailbox came back in the pockets and %s rest is in the flash unmarked" % (
                      len(others2), "its" if len(others2) == 1 else "each one's"))
              if safe2
              else "THE REST IS GONE FROM THE FLASH (its letters %s, its rests %s, the Pak's marks %s, the "
                   "records of a trip %s, the keys left with %s): THE LETTER COMES OUT OF THE STORE CUT AT ITS "
                   "%dTH BYTE" % (kept2["letters"], sorted(kept2["rests"]), kept2["marked"], kept2["trips"],
                                  kept2.get("taken"), BODY)
              if kept2 and key2s
              else "NOT HOME AND SAVED (%s)" % (here2 and {k: here2[k] for k in ("number", "record", "letters")},)))
    got2 = (claim2 or {}).get("fetched") or {}
    seen3 = got2.get("now")
    out_again = (bool(key2s) and bool(got2.get("opened")) and got2["opened"]["store"] == [[STORE_HAND, key2s]] and
                 bool(got2.get("put")) and key2s in got2["put"]["pockets"] and got2["put"]["store"] == [])
    same2 = bool(seen3) and bool(rest2s) and seen3["whole"].get(key2s) == rest2s
    check(clean(claim2) and bool(got2.get("spoken")) and bool(got2.get("menu")) and out_again and
          bool(got2.get("closed")) and got2.get("ended") == "free" and same2 and emu_memory.saved(claim2) and
          bool(whole2) and key2s in whole2["letters"] and whole2["whole"].get(key2s) == rest2s,
          "the clerk at home, the next power-on: %s" % (
              "the store read from the Controller Pak has the letter the other town's post office put there; Z "
              "takes it, Z puts it down in the pockets, B and the first answer: the letter is whole, its %d bytes "
              "past the cartridge's under its key, in RAM and in the flash the gyroid then writes" % size2s
              if out_again and same2 and whole2 and whole2["whole"].get(key2s) == rest2s
              else "THE LETTER IS BACK WITHOUT ITS REST (the extension's rests %s; the flash's %s): IT ENDS AT "
                   "ITS %dTH BYTE" % (sorted(seen3["rests"]), whole2 and sorted(whole2["rests"]), BODY)
              if out_again and seen3
              else "NOT FETCHED AS MEANT (%s)" % (
                  {k: got2.get(k) for k in ("spoken", "menu", "opened", "taken", "put", "closed", "ended")},)))
    runs = [r for r in (home, visit, again, back, store, fetch, leave, out2, there2, back2, claim2) if r]
    check(len(runs) == 11 and all(clean(r) for r in runs),
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

    def run(label, where=None, **plan):
        path = os.path.join(out, "plan-%s.json" % label)
        json.dump(dict(plan, label=label, work=where or work, clock=CLOCKS[label]), open(path, "w"))
        return child("--session", emu_dir, rom_path, af_en, path)

    print("emu_trip: %s, a trip with the Controller Pak from %s" % (rom_path, route))
    towns = {"home": child("--town", emu_dir, rom_path, af_en, os.path.join(route, "4-houses.st"),
                           os.path.join(out, "home")),
             "other": child("--town", emu_dir, rom_path, af_en, os.path.join(route, "1-name.st"),
                            os.path.join(out, "other"), *OTHER_NAMES)}
    work = os.path.join(out, "pak")                     # one pad's Controller Pak through the seven power-ons
    home = away = pak = visit = again = other = back = kept = store = lying = fetch = whole = pak2 = leave = None
    copies = []
    if towns["home"] and towns["other"]:
        home = run("home", town=towns["home"]["town"], answers=[0, 0], steps=["meet", "write", "depart"])
    if home and home["left"] and home["left"]["gone"]:
        away, pak = flash_facts(work, rom), pak_facts(work, home["now"]["id"])
        fla = cart_file(work, rom, ".fla")
        shutil.copyfile(fla, os.path.join(out, "home-away.fla"))
        os.remove(fla)                                  # the other Game Pak
        visit = run("visit", town=towns["other"]["town"], answers=[0], steps=["talk"])
    if visit and visit["arrived"]:                      # the power cut there; the same flash and Pak again
        again = run("again", answers=[0], steps=["resetti", "meet", "write", "depart"], turn=1)
    if again and again["left"] and again["left"]["gone"]:
        other, pak2 = flash_facts(work, rom), pak_facts(work, home["now"]["id"])
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
        leave = run("leave", answers=[0, 0], steps=["unstored"])
        copies.append(pak_copies(work, stored and stored["mail"]))
    trip2, work2 = {}, os.path.join(out, "pak-2")       # the second trip: another pad's Controller Pak
    if towns["home"] and towns["other"]:
        trip2["out"] = run("out", work2, town=towns["home"]["town"], answers=[0, 0], steps=["depart"],
                           early=["mail"])
    if trip2.get("out") and trip2["out"]["left"] and trip2["out"]["left"]["gone"]:
        trip2["away"] = flash_facts(work2, rom)
        fla = cart_file(work2, rom, ".fla")
        shutil.copyfile(fla, os.path.join(out, "home-away-2.fla"))
        os.remove(fla)
        trip2["there"] = run("there", work2, town=towns["other"]["town"], answers=[0], steps=["depart"],
                             early=["store"])
    if trip2.get("there") and trip2["there"]["left"] and trip2["there"]["left"]["gone"]:
        trip2["other"] = flash_facts(work2, rom)
        trip2["return"] = run("return", work2, flash=os.path.join(out, "home-away-2.fla"), answers=[0],
                              steps=["save"])
    if trip2.get("return") and trip2["return"]["saved"]:
        trip2["kept"] = flash_facts(work2, rom)
        trip2["claim"] = run("claim", work2, answers=[0, 0], steps=["fetch", "save"])
    if trip2.get("claim") and trip2["claim"]["saved"]:
        trip2["whole"] = flash_facts(work2, rom)
    report(towns, home, away, pak, visit, other, back, kept, store, lying, fetch, whole, copies, again, pak2, leave,
           trip2)
    json.dump({"towns": towns, "home": home, "away": away, "pak": pak, "visit": visit, "again": again, "other": other,
               "pak2": pak2, "back": back, "kept": kept, "store": store, "lying": lying, "fetch": fetch,
               "whole": whole, "leave": leave, "copies": copies, "trip2": trip2},
              open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_trip: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
