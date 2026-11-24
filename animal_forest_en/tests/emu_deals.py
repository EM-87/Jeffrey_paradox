#!/usr/bin/env python3
"""The town's dealings, played: turnips, the lost property, the catalog and the raffle, in the en build.

    tests/emu_deals.py EMU_DIR EN_ROM AF_EN_DIR TOWN OUT_DIR

TOWN is a town made by tests/emu_psel.py (make psel-check-en's 2-two.town), its first player's first job done.
The other checks read what the town's keepers say; none had bought, taken, ordered or won anything from them.
Here the first player is given Bells and April's raffle tickets by hand (twelve pockets of five), and four days
are played with the pad, each a power-on of the flash the day before saved (a process each, the allocator's
hook in place):

  a Sunday morning   the turnips' seller found (she walks one of the town's blocks) and heard out (her first
                     answers), ten turnips bought: the pocketbook pays ten times her price and a pocket holds
                     them. The police box gone into (its door wants no button) and a thing of the lost property
                     asked for: A before it, the officer says what it is and asks, the first answer takes it.
                     The player's gyroid saves.
  the Monday         Nook's shop: his first answer names the price he gives for turnips; his second sells
                     them (the item screen, the turnips' pocket), the player the richer by ten times that; his
                     third opens the player's catalog, whose first thing is ordered (A, the tag's first entry,
                     Nook's question answered yes) and paid for. The gyroid saves.
  the Tuesday        the mailbox opened (A at its corner, Z, B): the order has come, a letter with the thing.
  the month's last   the shop holds its raffle: its keeper's first answer takes a pocket's five tickets and
                     the machine turns, and he asks again, in the same talk, as long as there are tickets.
                     A draw loses 65 times in 100 (ac_npc_shop_mastersp's func_80A6D0DC_jp: a number under
                     100, and under 5, 15 and 35 the first, second and third prize, each given once), so
                     the first answer is given until a draw has won and one has lost, and then the last:
                     the prize is in the pockets, and a draw lost leaves nothing there. Should twelve draws
                     all lose (once in 175 times), the day is played again at another hour.

What the player is worth is the pocketbook and the bags of Bells in the pockets: a sale that takes the
pocketbook past its limit comes back as a bag (49,270 Bells and 760 more became 20,030 and a bag of 30,000).
Every page read on the way is judged as the other checks judge one (emu_year's Town.page_problems); the arena
must never say no. A choice is answered by its place, never by its words.
"""

import json
import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "emu"))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, HERE)

import emu_job  # noqa: E402
import emu_letters  # noqa: E402
import emu_memory  # noqa: E402
import emu_reset  # noqa: E402
import emu_save  # noqa: E402
import emu_town  # noqa: E402
import emu_year  # noqa: E402
from n64emu import N64, A, B, START, Z, Hang, stick  # noqa: E402

TICKETS = 0x2C00 + 3 * 8 + 4            # five of April's raffle tickets in one pocket (eight items a month)
DRAWS = 12                              # the pockets of tickets given: as many draws at the raffle
TURNIPS = range(0x2F00, 0x2F03)         # ten, fifty, a hundred turnips
BAGS = {0x2100: 1000, 0x2101: 10000, 0x2102: 30000, 0x2103: 100}    # the bags of Bells a pocket can hold
BELLS = 20000
SUNDAY, MONDAY, TUESDAY, LAST = (2001, 4, 15, 9, 0, 0), (2001, 4, 16, 11, 0, 0), (2001, 4, 17, 11, 0, 0), \
    (2001, 4, 30, 11, 0, 0)
RAFFLE_HOURS = (11, 13, 15)             # the month's last day again, should no draw of the twelve win
SELLER, OFFICER, MAILBOX = "Ev_KabuPeddler", "Npc_Police2", "MailBox"
POLICE_DOOR = emu_memory.BUILDINGS["police-box"][2][0]      # where the player is put from the box, and pushed
LOST_AT = [(x, z) for z in (60, 100, 140) for x in (60, 100, 140, 180, 220)]    # the lost property's places
PRESENT = 0x24                          # Mail_c: the thing that comes with a letter
BOX_FIRST, BOX_COUNT = 40, 10           # the first home's mailbox among the save's letter places (emu_letters)

failures = []


def check(ok, what):
    print("  %s  %s" % ("ok  " if ok else "FAIL", what))
    if not ok:
        failures.append(what)


def supplied(save):
    """The town's save with Bells in its first player's pocketbook and the tickets alone in its pockets."""
    save = bytearray(save)
    at = emu_job.PRIVATE
    save[at + emu_job.POCKETS:at + emu_job.POCKETS + 30] = struct.pack(">15H", *[TICKETS] * DRAWS + [0] * (15 - DRAWS))
    save[at + emu_job.CONDITIONS:at + emu_job.CONDITIONS + 4] = bytes(4)
    save[at + emu_job.WALLET:at + emu_job.WALLET + 4] = struct.pack(">I", BELLS)
    return bytes(save)


def gained(had, has):
    """The things of has that had lacks, a thing held twice counted twice."""
    left, out = list(had), []
    for v in has:
        if v in left:
            left.remove(v)
        else:
            out.append(v)
    return out


def letters(save, places):
    """[(the letter's key, the thing that comes with it)] of the save's letter places that hold a letter."""
    out = []
    for k in places:
        mail = save[emu_letters.SAVE_MAIL[k]:emu_letters.SAVE_MAIL[k] + emu_letters.MAIL_SIZE]
        if mail[emu_job.KEY_BYTES[0]:emu_job.KEY_BYTES[0] + 12].strip(b" \0"):
            out.append(["%08x" % emu_letters.mail_key(mail), struct.unpack(">H", mail[PRESENT:PRESENT + 2])[0]])
    return out


def session(emu_dir, rom_path, af_en, plan):
    """One day: the flash in plan["work"] powered on at plan["clock"] (plan["town"]: a fresh flash made of it,
    supplied; plan["flash"]: a flash's file copied in) and plan["steps"] played in turn. A dict for the report."""
    rom = open(rom_path, "rb").read()
    syms = emu_save.elf_symbols(os.path.join(af_en, "build", "animalforest-jp.elf"))
    work = plan["work"]
    os.makedirs(work, exist_ok=True)
    if plan.get("town"):
        mine = os.path.join(work, "deals.town")
        emu_year.write_town(mine, supplied(open(plan["town"], "rb").read()), emu_year.town_ext(plan["town"]))
        emu_save.write_fla(emu_save.fla_path(work, rom), emu_year.town_image(mine))
    else:
        shutil.copyfile(plan["flash"], emu_save.fla_path(work, rom))
    result = {"label": plan["label"], "clock": list(plan["clock"]), "arrived": False, "hang": None, "pages": [],
              "watch": None, "flash": emu_save.fla_path(work, rom)}
    with N64(emu_dir, rom, work, timeout=60, clock=tuple(plan["clock"])) as n64:
        t = emu_year.Town(n64, syms["mMsg_window"], syms["mChoice_en_strings"], emu_year.actor_names(af_en), syms)
        p = emu_job.Player(n64, t, syms, result["pages"])
        cd = syms["common_data"]

        def said(mark):
            return [pg["msg"] for pg in result["pages"][mark:]]

        def things():
            return [v for v in p.pockets() if v]

        def worth():
            return p.wallet() + sum(BAGS.get(v, 0) for v in p.pockets())

        def keeper():
            """Into the shop, if not there: the one who keeps it."""
            if p.scene() == emu_job.FG:
                if not t.go_block(p.block_of("shop"), set()) or not p.enter("Shop", emu_memory.DOORS):
                    return None
            there = [a for a in t.actors() if a["part"] == emu_year.PART_NPC and a["name"] not in (emu_job.ANIMAL, "Npc")]
            return there[0] if there else None

        def turnips():
            out = {"found": None, "wallet": [worth()], "bought": [], "pages": [], "answers": [], "ended": None}
            here = t.block()
            for block in sorted(t.blocks(), key=lambda b: abs(b[0] - here[0]) + abs(b[1] - here[1])):
                if not t.go_block(block, set()):
                    continue
                seller = next((a for a in t.actors() if a["name"] == SELLER), None)
                if not seller:
                    continue
                out["found"] = list(block)
                mark, had = len(result["pages"]), things()

                def pick(count):                    # hear her out (her talk comes round again: four yeses
                    out["answers"].append(count)    # at most); ten, the first time she asks how many
                    if count == 4:
                        return 0 if out["answers"].count(4) == 1 else count - 1
                    return 0 if out["answers"].count(count) <= 4 else count - 1
                if emu_reset.approach(t, seller):
                    out["ended"] = p.advance(900, 12, pick)
                out["pages"] = said(mark)
                out["bought"] = gained(had, things())
                break
            out["wallet"].append(worth())
            return out

        def lost():
            out = {"inside": False, "wallet": worth(), "taken": [], "pages": [], "answers": [], "ended": None,
                   "out": False}
            if not t.go_block(p.block_of("police"), set()):
                return out
            box = next((a for a in t.actors() if a["name"] == "Police_Box"), None)
            (dx, dz), push = POLICE_DOOR
            if not box or not p.land(box["pos"][0] + dx, box["pos"][2] + dz):
                return out
            n64.frames(48, stick(*push))
            for _ in range(60):
                n64.frames(15, 0)
                try:
                    if p.scene() != emu_job.FG and t.player_main() == emu_year.PLAYER_WAIT:
                        break
                except (ValueError, struct.error):
                    pass
            p.advance(100, 8)
            out["inside"] = p.scene() != emu_job.FG and any(a["name"] == OFFICER for a in t.actors())
            level = t.pos(t.player())[1]
            for x, z in LOST_AT if out["inside"] else ():
                for _ in range(3):
                    t.put(x, level, z + 34.0)
                    n64.frames(2, 0)
                n64.frames(10, 0)
                if not p.face(0, -1):
                    continue
                mark, had = len(result["pages"]), things()
                n64.press(A, 3, 30)
                n64.frames(30, 0)
                if t.window_main() == emu_year.HIDDEN and not t.choice_waiting():
                    continue

                def pick(count):
                    out["answers"].append(count)
                    return 0
                out["ended"] = p.advance(300, 10, pick)
                out["pages"] = said(mark)
                out["taken"] = gained(had, things())
                if out["taken"]:
                    break
            out["out"] = p.outside()
            return out

        def price():
            out = {"pages": [], "answers": [], "ended": None}
            who = keeper()
            if who:
                mark = len(result["pages"])

                def pick(count):                    # the price, then nothing more
                    out["answers"].append(count)
                    return 0 if len(out["answers"]) == 1 else count - 1
                spoken, out["ended"] = p.talk(who, limit=300, quiet=10, pick=pick)
                out["pages"] = said(mark)
            return out

        def sell():
            out = {"wallet": [worth()], "had": [v for v in p.pockets() if v in TURNIPS], "left": None,
                   "menu": False, "pages": [], "ended": None}
            who = keeper()
            if who and out["had"]:
                slot = next(k for k, v in enumerate(p.pockets()) if v in TURNIPS)
                mark = len(result["pages"])
                answers = iter((1, 0))              # to sell; his price taken
                pick = lambda count: next(answers, count - 1)       # noqa: E731
                spoken, how = p.talk(who, limit=300, quiet=10, pick=pick)
                if spoken and how == "menu":
                    out["menu"] = True
                    n64.frames(40, 0)
                    p.hand(slot)
                    n64.press(A, 3, 40)
                    n64.press(A, 3, 60)
                    n64.frames(40, 0)
                    how = p.advance(300, 10, pick)
                if t.menu_open():                   # asked for another: closed with nothing chosen
                    n64.press(B, 3, 40)
                    how = p.advance(200, 10, lambda count: count - 1)
                out["ended"] = how
                out["pages"] = said(mark)
            out["left"] = [v for v in p.pockets() if v in TURNIPS]
            out["wallet"].append(worth())
            return out

        def order():
            out = {"wallet": [worth()], "menu": False, "pages": [], "ended": None, "post": None}
            who = keeper()
            before = letters(n64.read(cd, 0x10000), range(len(emu_letters.SAVE_MAIL)))
            if who:
                mark = len(result["pages"])
                answers = iter((2, 0))              # the catalog; yes, that one
                pick = lambda count: next(answers, count - 1)       # noqa: E731
                spoken, how = p.talk(who, limit=300, quiet=10, pick=pick)
                if spoken and how == "menu":
                    out["menu"] = True
                    n64.frames(60, 0)
                    n64.screenshot(os.path.join(work, "catalog.png"))
                    n64.press(A, 3, 40)             # the first thing of the first page: its tag,
                    n64.press(A, 3, 60)             # whose first entry orders it
                    n64.frames(40, 0)
                    how = p.advance(400, 10, pick)
                if t.menu_open():
                    n64.press(B, 3, 40)
                    how = p.advance(200, 10, lambda count: count - 1)
                out["ended"] = how
                out["pages"] = said(mark)
            after = letters(n64.read(cd, 0x10000), range(len(emu_letters.SAVE_MAIL)))
            out["post"] = [m for m in after if m not in before]
            out["wallet"].append(worth())
            return out

        def mail():
            """The player's mailbox emptied into the pockets: A at its corner (emu_job's open_mailbox), Z, B."""
            def box():
                return letters(n64.read(cd, 0x10000), range(BOX_FIRST, BOX_FIRST + BOX_COUNT))

            def held():
                return letters(n64.read(cd, 0x10000), range(10))
            out = {"box": [box()], "pockets": [held()], "menu": False}
            boxes = [a for a in t.actors() if a["name"] == MAILBOX]
            if not (p.outside() and t.go_block(emu_town.PLAYERS_BLOCK, set()) and boxes):
                return out
            mine = min(boxes, key=lambda a: (a["pos"][0] - emu_town.MAT[0]) ** 2 + (a["pos"][2] - emu_town.MAT[1]) ** 2)
            out["menu"] = p.open_mailbox(mine)
            if out["menu"]:
                n64.frames(6, Z)
                n64.frames(40, 0)
                n64.screenshot(os.path.join(work, "mailbox.png"))
                n64.press(B, 3, 60)
                for _ in range(60):
                    if not t.menu_open():
                        break
                    n64.frames(10, 0)
                n64.frames(40, 0)
            out["box"].append(box())
            out["pockets"].append(held())
            return out

        def raffle():
            """The raffle, one talk with its keeper: his question comes again after each draw, and its first
            answer draws again; so until a draw has won and one has lost, or the tickets are gone. A draw: what
            the pockets held when he asked and when he asked next (or the talk ended)."""
            out = {"keeper": None, "draws": [], "pages": [], "ended": None}
            who = keeper()
            if who:
                out["keeper"] = who["name"]
                mark, draws = len(result["pages"]), out["draws"]

                def pick(count):
                    now = things()
                    if draws:
                        draws[-1]["has"] = now
                    kinds = {bool(gained(d["had"], d["has"])) for d in draws}
                    if TICKETS not in now or kinds == {True, False} or len(draws) >= DRAWS:
                        return count - 1
                    draws.append({"had": now, "has": None, "answers": count})
                    return 0
                spoken, out["ended"] = p.talk(who, limit=400, quiet=12, pick=pick)
                n64.frames(30, 0)
                out["pages"] = said(mark)
                if draws and draws[-1]["has"] is None:
                    draws[-1]["has"] = things()
            return out

        def save():
            return emu_memory.save_and_stop(t, emu_town.MAT, result["pages"]) if p.outside() else None
        steps = {"turnips": turnips, "lost": lost, "price": price, "sell": sell, "order": order, "mail": mail,
                 "raffle": raffle, "save": save}
        try:
            low, used = emu_memory.watch_soon(n64, af_en, syms)
            n64.frames(400 - used, 0)
            n64.press(START, 3, 60)
            for _ in range(60):
                if p.advance(400, 12) == "free" and p.scene() == emu_job.FG:
                    result["arrived"] = True
                    break
                n64.frames(30, 0)
            for step in plan["steps"] if result["arrived"] else ():
                result[step] = steps[step]()
                if os.environ.get("EMU_DEALS_TRACE"):
                    print("%s: %s at frame %d: %s" % (plan["label"], step, n64.frame, result[step]), file=sys.stderr,
                          flush=True)
            result["watch"] = low()
        except Hang as e:
            result["hang"] = str(e)
    result["problems"] = ["%#x at %d: %s" % (pg["msg"], pg["at"], ", ".join(pg["problems"]))
                          for pg in result["pages"] if pg["problems"]]
    result["messages"] = sorted({pg["msg"] for pg in result["pages"]})
    result["pages"] = len(result["pages"])
    return result


def clean(run):
    return bool(run) and run["arrived"] and not run["hang"] and not run["problems"] and bool(run["watch"]) and \
        run["watch"]["fails"] == 0


def report(sunday, monday, tuesday, last):
    """The checks, from the four days' sessions (None for one that was not played)."""
    got = sunday and sunday.get("turnips")
    paid = got["wallet"][0] - got["wallet"][1] if got else 0
    check(bool(got) and bool(got["found"]) and got["ended"] == "free" and len(got["bought"]) == 1 and
          got["bought"][0] == TURNIPS[0] and paid > 0 and paid % 10 == 0 and got["answers"].count(4) == 2,
          "the turnips, a Sunday morning: %s" % (
              "their seller found in the block %s and heard out (%d pages, %d questions answered); ten bought, the "
              "first of her four answers: a pocket holds them and the pocketbook paid %d Bells, %d a turnip; her "
              "question whether more are wanted, the last answer" % (
                  got["found"], len(got["pages"]), len(got["answers"]), paid, paid // 10)
              if got and got["found"] and got["bought"] and paid > 0 else "NOT BOUGHT (%s)" % (
                  got or (sunday and {k: sunday[k] for k in ("arrived", "hang")}),)))
    lost = sunday and sunday.get("lost")
    check(bool(lost) and lost["inside"] and len(lost["taken"]) == 1 and lost["answers"] == [2] and
          lost["ended"] == "free" and len(lost["pages"]) >= 2 and lost["out"],
          "the lost property: %s" % (
              "into the police box; A before a thing there, the officer says what it is and asks (%d pages; the "
              "question's two answers); the first: the thing %#x is in the pockets; and out again" % (
                  len(lost["pages"]), lost["taken"][0])
              if lost and lost["inside"] and lost["taken"] else "NOTHING TAKEN (%s)" % (lost,)))
    kept = monday and monday.get("sell")
    quote = monday and monday.get("price")
    earned = kept["wallet"][1] - kept["wallet"][0] if kept else 0
    check(bool(quote) and quote["ended"] == "free" and len(quote["pages"]) >= 2 and quote["answers"][:1] == [4] and
          bool(kept) and kept["menu"] and len(kept["had"]) == 1 and kept["left"] == [] and earned > 0 and
          earned % 10 == 0 and kept["ended"] == "free",
          "the turnips sold, the Monday (the flash the Sunday's gyroid wrote): %s" % (
              "Nook's first answer of four names his price (%d pages); his second, the item screen, the turnips' "
              "pocket: the turnips are gone and the player has %d Bells more, %d a turnip (they cost %d)" % (
                  len(quote["pages"]), earned, earned // 10, paid // 10)
              if quote and kept and kept["menu"] and earned > 0 and kept["left"] == [] else "NOT SOLD (%s; %s)" % (
                  quote, kept or (monday and {k: monday[k] for k in ("arrived", "hang")}))))
    asked = monday and monday.get("order")
    cost = asked["wallet"][0] - asked["wallet"][1] if asked else 0
    check(bool(asked) and asked["menu"] and cost > 0 and asked["ended"] == "free" and len(asked["pages"]) >= 3,
          "the catalog: %s" % (
              "Nook's third answer opens the player's own; A on its first thing, the tag's first entry, and his "
              "question answered yes (%d pages): the pocketbook pays %d Bells" % (len(asked["pages"]), cost)
              if asked and asked["menu"] and cost > 0 else "NOTHING ORDERED (%s)" % (asked,)))
    came = tuesday and tuesday.get("mail")
    new = [m for m in came["pockets"][1] if m not in came["pockets"][0]] if came else []
    parcel = [m for m in new if m[1]]
    check(bool(came) and came["menu"] and len(parcel) == 1 and came["box"][1] == [] and
          [m for m in came["box"][0] if m[1]] == parcel,
          "the order, in the mail the Tuesday (the flash the Monday's gyroid wrote): %s" % (
              "the mailbox opened (A at its corner), Z takes its %d letter%s, B: one came with a thing, %#x, and is "
              "in the pockets with it; the mailbox is empty" % (
                  len(came["box"][0]), "" if len(came["box"][0]) == 1 else "s", parcel[0][1])
              if came and came["menu"] and len(parcel) == 1 else "NOT COME (%s)" % (
                  came or (tuesday and {k: tuesday[k] for k in ("arrived", "hang")}),)))
    won = last and last.get("raffle")
    draws = won["draws"] if won else []
    prizes = [gained(d["had"], d["has"]) for d in draws]
    fair = [d["answers"] == 2 and gained(d["has"], d["had"]) == [TICKETS] and len(prize) <= 1
            for d, prize in zip(draws, prizes)]
    wins = [prize[0] for prize in prizes if prize]
    check(bool(draws) and all(fair) and len(wins) >= 1 and won["ended"] == "free" and len(won["pages"]) >= 3,
          "the raffle, the month's last day: %s" % (
              "the shop is the raffle's (its keeper %s); one talk of %d pages, his question's first answer given %d "
              "time%s, each taking a pocket's five tickets: %d lost and left nothing in the pockets (65 draws in 100 "
              "lose); %s" % (
                  won["keeper"], len(won["pages"]), len(draws), "" if len(draws) == 1 else "s", len(draws) - len(wins),
                  "; ".join("one won, the thing %#x is in the pockets" % v for v in wins))
              if draws and all(fair) and wins and won["ended"] == "free" else "NOTHING WON (%s)" % (
                  won or (last and {k: last[k] for k in ("arrived", "hang")}),)))
    saves = [run and run.get("save") for run in (sunday, monday)]
    check(all(s and s["left"] for s in saves),
          "the gyroid's saves that carry one day to the next: %s" % (
              "the Sunday's and the Monday's, each asked at the player's gyroid and the game left for its title"
              if all(s and s["left"] for s in saves) else "NOT SAVED (%s)" % (saves,)))
    runs = [sunday, monday, tuesday, last]
    check(all(clean(run) for run in runs),
          "the four days' pages: %s" % (
              "%d pages of %d messages read, clean; the arena never said no (the largest free block %d at its least)"
              % (sum(run["pages"] for run in runs), len({m for run in runs for m in run["messages"]}),
                 min(run["watch"]["least"] for run in runs))
              if all(clean(run) for run in runs) else "NOT CLEAN (%s)" % (
                  [run and {k: run[k] for k in ("label", "arrived", "hang", "problems")} for run in runs],)))


def main(argv):
    if argv[:1] == ["--session"]:
        print(json.dumps(session(argv[1], argv[2], argv[3], json.load(open(argv[4])))))
        return 0
    emu_dir, rom_path, af_en, town, out = argv
    af_en = os.path.abspath(af_en)
    os.makedirs(out, exist_ok=True)
    print("emu_deals: %s, four days in the town %s" % (rom_path, town))

    def run(plan):
        path = os.path.join(out, "plan-%s.json" % plan["label"])
        json.dump(dict(plan, work=os.path.join(out, plan["label"])), open(path, "w"))
        r = subprocess.run([sys.executable, os.path.abspath(__file__), "--session", emu_dir, rom_path, af_en, path],
                           capture_output=True, text=True)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("{")]
        if r.returncode or not lines:
            print(r.stdout[-1500:], r.stderr[-1500:])
            return None
        return json.loads(lines[-1])

    def saved(day):
        return bool(day) and bool(day.get("save")) and day["save"]["left"]
    sunday = run({"label": "sunday", "clock": SUNDAY, "town": town, "steps": ["turnips", "lost", "save"]})
    monday = saved(sunday) and run({"label": "monday", "clock": MONDAY, "flash": sunday["flash"],
                                    "steps": ["price", "sell", "order", "save"]}) or None
    tuesday = saved(monday) and run({"label": "tuesday", "clock": TUESDAY, "flash": monday["flash"],
                                     "steps": ["mail"]}) or None
    # the raffle: the tickets have been in the pockets since the Sunday, and the flash the Monday wrote has them
    last = None
    for hour in RAFFLE_HOURS if saved(monday) else ():
        last = run({"label": "last", "clock": LAST[:3] + (hour, 0, 0), "flash": monday["flash"], "steps": ["raffle"]})
        if not (last and last.get("raffle")) or any(gained(d["had"], d["has"]) for d in last["raffle"]["draws"]):
            break
    report(sunday, monday, tuesday, last)
    json.dump({"sunday": sunday, "monday": monday, "tuesday": tuesday, "last": last},
              open(os.path.join(out, "report.json"), "w"), indent=1)
    print("emu_deals: %s; the report in %s" % ("all passed" if not failures else "%d FAILED" % len(failures), out))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
