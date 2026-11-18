# Working in animal_forest_en/

An English translation of Doubutsu no Mori (N64) built by recompiling the
game from zeldaret/af, not by hex-patching the ROM. Read `README.md` for
the why and the state, `reference/NOTES.md` for what is verified.

## Ground rules

1. **The cartridge is the source of truth.** The decomp rebuilds it byte
   for byte; anything we claim about the game (a buffer's size, what a
   control code does, where text lives) is checked against the ROM, the
   decomp's C/asm, or the running game, and the source is cited next to it
   (`src/code/m_foo.c:123`, `asm/jp/.../func_80xxxxxx.s`, a frame number
   in a test). The GameCube decomp (ac-decomp) is a map, not proof: the N64
   code is its ancestor and differs in places. Never present an untraced
   value as confirmed; mark it `TODO(verify)` where it is used.

2. **Nothing from Nintendo enters this repository.** No ROM, no disc
   image, no extracted text, graphics or audio, no screenshots. What may
   be committed: our code, our patches to the decomp (C we wrote, edits to
   theirs), our own translation, and hashes (an md5 of a frame is a check,
   not a copy). The official GameCube script and art are read from the
   user's disc at build time, on their machine. The deliverable is a
   `.bps` patch against the user's own dump.

3. **Recompile, don't patch.** A change to the game is a change to the
   decomp's source, kept as a patch in `decomp/patches/` (in order, applied
   by the Makefile to the pinned `decomp/AF_REV`). Bytes poked into the
   built ROM are a last resort and need a comment saying why the source
   could not do it.

4. **Match first, then change.** When a function moves from asm to C, make
   it match the original first (`diff.py`, the decomp's own tooling): that
   is the proof the C is the same code. Only then change behaviour, in a
   separate patch.

5. **Look at the screen, deterministically.** Anything the player sees
   gets checked in the emulator (`emu/n64emu.py`). The emulator is
   deterministic on purpose (fixed RTC, fixed Controller Pak seed, fixed VI
   noise, no interrupt jitter: `emu/patches/`); a check that depends on
   timing or the host clock is a bug in the check. Questions about pixels
   are answered by measuring the frame, not by reasoning about it.

6. **Every claim of "done" runs `make test` and `make emu-check`.** `make
   test` needs no ROM and runs in CI; `make emu-check` needs the user's
   dump and stays local. A change to the save code, or one that grows
   `code_en` (its bytes come off the heap: below), also runs `make
   save-check-en`, one to the letters `make letters-check-en`, one to the
   letter board or the font `make board-check-en`, one to the editor or
   writing a letter `make write-check-en`, one to the name entry, the
   dial's rings or Mr. Resetti's strings `make resetti-check-en`, one to
   the bulletin board or its posts `make notice-check-en`, one to the
   message bank or its layout `make messages-check-en`, one to the menus'
   strings or word textures `make menus-check-en`, one to the structures',
   a room's or the furniture's textures or palettes `make signs-check-en`,
   one to the choice window or the player-select actor `make
   psel-check-en`, one to an event, a date, the banks' tables or an
   actor's talk `make year-check-en` (which also reads the room the arena
   has left in every block of every date: run it too for a change that
   grows `code_en`, and `make memory-check-en`, the same in a town at its
   fullest and in the rooms), one to a string the cartridge's code loads
   into fewer bytes than its English takes, or to a special character's
   name, `make words-check-en`, one to the start of play, a save or Mr.
   Resetti's visits `make reset-check-en`, one to what a new game's
   first hour goes through (Nook's first job: the item screen and its
   tags, a letter written from the pockets and mailed, the bulletin
   board, the shop's counter) `make job-check-en`, one to the letters'
   extension, to what a save holds of a player, or to the Controller
   Pak's road (the station's master, a visitor, a homecoming) `make
   trip-check-en`, one to `emu/` `make emu-determinism`. And after a
   push, read the CI run's jobs: its emulator job failed for want of nasm
   from the first commit on, 89 runs, while the tools' tests passed, and a
   run that is always red hides the day `make test` fails (NOTES, "The
   second review").

## Where things live

- `Makefile` — baserom, decomp checkout/extract/build, verify, emulator,
  emu-check, nafe, test; `rom-en`, `emu-check-en`, `route-en` and
  `decomp-patch-en` for the translation's build in `build/af_en`; `script`
  for the GameCube text (ISO=), and `drafts` for its drafts again from
  build/gc's copies of the disc's files, without the disc (after a change
  to a rule of the draft's or to `script/`).
- `decomp/AF_REV`, `decomp/matching.patch` (decompiled code, must rebuild
  the cartridge), `decomp/changes.patch` (the translation, on top) — what
  we build.
- `emu/` — the headless N64. `build.sh` pins and builds mupen64plus-core
  (debugger on), mupen64plus-rsp-cxd4, angrylion-rdp-plus with
  `headless_output.c` instead of OpenGL, and `input_headless.c`;
  `patches/` are our changes to them; `n64emu.py` is the frontend;
  `ares/` the second emulator (`build.sh` pins ares and builds its N64
  core with `headless.cpp`, driven over a pipe, and the ares app; `make
  ares`), `ares64.py` its frontend with n64emu's interface (NOTES, "Other
  emulators");
  `afplay.py` reads the game's screens (dialogue, choices, name dial);
  `symbols.py` names addresses from the decomp's map; `contact.py` puts
  screenshots on one sheet.
- `tools/af_match.py`, `af_try.py`, `af_wrap.py`, `af_bench.py` —
  matching: per-function check, trying variants in the file, NON_MATCHING
  wrapping, and sweeping many variants fast outside the build (run in
  build/af).
- `tools/af_relink.py`, `af_relsyms.py`, `af_ranges.py`, `af_dmaorder.py`
  — the translation's build (`make rom-en`, in build/af_en): `code` moved
  to the end of the ROM so that it can grow, nothing of it moved in RAM (a
  section that grows, or a new one, leaves its slot: a text section goes
  into the slots other grown ones left where it fits, the rest to
  `code_en` after `buffers`; a shrunk one is padded in place), the RAM
  addresses splat left absolute made relative, and the compressed ROM's
  ranges and dmadata order (NOTES, "Shiftability", "The second review").
  `tools/af_ovlreloc.py` gives an overlay the translation changes
  (`EN_OVERLAYS`) its relocation table anew, from fado, in the cartridge's
  layout, after proving fado on the matching build (NOTES, "The letter
  board in the en build").
- `tools/af_shiftcheck.py`, `af_anchors.py`, `af_luicheck.py` — the proofs
  `make rom-en` runs before compressing: nothing of the cartridge's moved,
  no address spelled with the wrong symbol, no shared `lui` broken.
- `tools/` — `rom.py`, `ups.py`, `bps.py` (the deliverable: `make bps`), `nafe.py`, `nafe_diff.py` (the 2010
  patch against the original, by file and function), `route_newgame.py`
  (power-on to the houses, states on the way).
- `tools/gciso.py`, `msgbank.py`, `af_align.py`, `af_text.py`,
  `af_names.py` — the GameCube script (`make script ISO=...`): the disc's
  files and RARC members, both games' banks (messages, choices, strings,
  the eight letter banks) as one tagged text (and the N64's back to
  bytes), the message-by-message comparison, the English banks drafted,
  checked and compiled for the ROM, and the animals' names; output in
  `build/gc/`, never committed. `script/message/`, `script/choice/`,
  `script/string/`, `script/<letter bank>/` hold our own text,
  laid over the drafts (NOTES, "The GameCube script", "The English bank
  in the ROM", "The strings and letters still in Japanese"; the event
  notices the GameCube keeps at another number are drafted from there:
  `af_text.LETTER_REMAP`, and five messages from a twin of the same
  Japanese: `MESSAGE_REMAP`); our messages over the compiled bank again at
  `make rom-en`, where `af_text.py` refuses any message laid over (ours or
  the 2010 patch's) whose codes that steer the game are not the
  cartridge's (`af_text.steering`; NOTES, "Our own messages"). The draft
  gives every official message the cartridge's codes (`af_text.mend`), or
  where it cannot, the cartridge's Japanese to translate (NOTES, "The
  official text's codes and the GameCube's own"). Where the official
  English is wrong for the cartridge in a way a rule can state, the draft
  mends it by the message's number, never by its words
  (`af_text.OFFICIAL_RULES`: a word printed twice, a row of the map by
  the GameCube's letter, a birthday's two numbers, a sentence's capital,
  a comma's space; NOTES, "The second review").
- `tools/af_font.py` — the en build's font, made from the user's dump at
  `make rom-en`: the GameCube's Latin letters and their widths (read from
  the disc by `make script` into `build/gc/gc_font.bin`), or without the
  disc the cartridge's at the left of their 12-pixel cells; the table the
  en `m_font.c` includes (NOTES, "The font in the en build").
- `tools/af_ui.py` — the menus' own words (strings in the menus' data,
  not in the banks): the GameCube's read by `make script` (`build/gc/gc_ui.txt`;
  a string added to its table later is read from build/gc's copies of the
  disc's module and map, without the disc), the include the en build's
  menus use written at `make rom-en`, the cartridge's own without the disc
  (NOTES, "The menus' own words"); and `SUBST`, the strings the en
  build's font swaps where it draws them (keyed by the cartridge's bytes
  from the dump: a string, a buffer a menu types into, or pieces around
  the part a menu builds itself; our own text in `script/ui.txt`: NOTES,
  "The menus' strings where the font draws them").
- `tools/af_items.py` — the item names: the GameCube's and their
  articles (read by `make script` into `build/gc/gc_items.txt`, from
  build/gc's copies of the disc's module and map), mapped kind by kind
  onto the cartridge's 1,703 (ours in `script/items.txt` where the
  GameCube has none), compiled at `make rom-en` into a ROM segment of
  records (`item_en_names`) and the include the en `m_item_name.c`
  uses; the en build writes a token into the cartridge's 10-byte name
  buffers, which the font draws and the message, letter and choice code
  expand, with the article as the GameCube puts it (NOTES, "Item
  names"); the names longer than an animal's 6 bytes take the same
  tokens, their records after the items', the animals' written into the
  en name segment (NOTES, "Names longer than 6"), and so do the
  catchphrases longer than 4, their tokens laid over the string bank
  (`string_tokens.txt`; NOTES, "Catchphrases longer than 4"), and the
  strings the cartridge's code loads into fewer bytes than their English
  takes (`SHORT_LOADED`: the words of the villagers' talk, the fortune
  teller's, a villager's reply's, the fortune slip's, the shop's name
  under a notice and its kind; NOTES, "Strings cut by their loaders");
  a name of the GameCube's with a or an takes the one its first sound
  asks for (`sound_article`; NOTES, "The second review"); one special
  character the GameCube's table names apart from the cartridge's takes
  his string through the include (`NAMES_APART`), and `special-names`
  holds the en table of those names to the GameCube's own at every build
  (NOTES, "What the second review had left open").
- `tools/af_nafe_gfx.py` — the 42 menu labels the AF Project's 2010
  patch drew in English, taken at `make rom-en` from the user's copy
  (`make nafe NAFE_UPS=...`, build/nafe-2010-12-02.z64) over the en
  tree's extracted segments, each checked against both ROMs' display
  lists; the cartridge's labels without it (NOTES, "The 2010 patch's
  labels").
- `tools/af_nafe_logo.py` — the title's logo the 2010 patch drew in
  English, taken at `make rom-en` from the same copy: its pieces into the
  en tree's copy of the logo's segment (the cartridge's textures below
  them kept), the logo actor's data over its `.data.s`, and the four
  values of `func_80AA0C98_jp` it changed as the include the en
  `ac_animal_logo.c` uses; the cartridge's logo without it (NOTES, "The
  title's logo"). The actor is in `EN_OVERLAYS`: its code shrinks.
- `tools/af_gc_signs.py` — the signs the GameCube's English redrew on
  the town's structures (the shops' names and a sale banner, the dump's,
  the black market's, the countdown board's, the police box's plate, the
  fishing tourney's stall, the houses' name plates), taken at `make rom-en` from
  the disc's foresta.rel (make script's): its tiled CI4 laid out as the
  N64's over the en tree's copy of object_00D5E000 (its PNGs, its
  unextracted .bin), every copy, and the black market's palette into
  object_00D5B000's data; and the seven signs inside the fourth shop,
  in its rooms' own files (`ROOMS`; a palette's entry with them); each
  checked against the cartridge's lists and texels (NOTES, "The
  GameCube's signs").
- `tools/af_own_signs.py` — the structures' words no English redrew,
  drawn by us (ASCII grids in the tool, in each texture's own palette
  indices): the fireworks stall's banner and cards, the police box's
  notice board (summer and winter), the fishing tourney headquarters'
  sign, the fortune stall's plaque and slips; the kana cleaned off from
  the user's dump (a cloth or paper: each ink texel the nearest of its
  row that is not ink) or the face painted its ground, at `make rom-en`
  after af_gc_signs (the same .bin); and the posters inside the rooms
  (`POSTERS`, in the rooms' files): the second shop's, ours, and the
  police box's two, the GameCube's own English ones given the disc's
  files and ours without; each texture and palette checked by md5
  (NOTES, "Our own signs").
- `tools/af_own_ftr.py` — the words on seventeen pieces of furniture
  (road works signs and barricades, a bus stop, a drum, a bath's prices,
  labels, a sticker, a plaque), drawn by us in two fonts of our own (ASCII
  in the tool) over each texture's box painted, cleaned or restriped, in
  its own palette's indices, at `make rom-en` over the en tree's .bin of
  each furniture object; each checked: the object's lists set the render
  tile at its TMEM address, texels and palette md5s, the texture block's
  LoadBlock row swap undone and redone (NOTES, "Our own furniture
  words").
- `tools/af_menu_tex.py` — the word textures the 2010 patch left in
  Japanese: the town tune's note names (the GameCube's letters, from the
  disc), the birthday's month and day, the catchphrase window's quotes
  and the letter papers' words (ours: the en font's glyphs, put where the
  kana were), over the en tree's copies of their segments at `make
  rom-en` (NOTES, "The word textures").
- `tools/af_dial.py` — the dial without its kana modes: our label for
  the small letters over the hiragana's in the dial's segment, the
  panel's rows' lists pointed at the capitals', the symbols' and the
  digits' labels, and the symbols' ring without its Japanese marks in
  the editor's data, at `make rom-en` (NOTES, "The dial's small
  letters"); and `phrases`, which holds the en string bank to what the
  dial types: Mr. Resetti's phrases to type and the words he takes as an
  insult, each as long as his code compares (NOTES, "The second review").
- `tools/af_answers.py` — the answers an overlay copies from its own
  data instead of the choice bank (the player-select actor's for a new
  player, the fireworks stall's), pointed at the bank's English by a
  token over the en tree's copy of the overlay's data at `make rom-en`,
  each word checked against the cartridge's (NOTES, "The player-select
  actor's answers"); one no answer of the bank says goes past the
  cartridge's 460 (`extra`: the GameCube's from build/gc's module and
  map, ours in `script/answers.txt` without them; NOTES, "The year's
  events").
- `tools/af_nafe_text.py` — the messages the GameCube's script has no
  counterpart for, in the 2010 patch's English where its codes that steer
  the game are the cartridge's (ends, choices, next messages, the talk
  orders in every slot; where only the talking animal's orders in slot 0
  differ, the cartridge's are put back into the English), taken at `make
  rom-en` from the user's copy and laid over the draft (`af_text.py
  compile --overrides`), never committed (NOTES, "The 2010 patch's
  messages").
- `tools/af_dumpdata.py` — the cartridge's own data a file we decompiled
  includes but this repository must not hold (text), written from the
  user's dump as C initializers under each tree's `assets/` after its
  extract.
- `tools/aflz.py` — the en build's save compressor in Python (the twin
  of `include/af_lz.h` in changes.patch): slot images packed and read.
- `tests/` — `test_tools.py`, `emu_check.py`, `emu_save.py` (the en
  save driven through a hook in the running game: `make save-check-en`),
  `emu_letters.py` (the en letter loaders through the same hook, against
  a model of the cartridge's expander: `make letters-check-en`),
  `emu_board.py` (the letter board opened on letters, its lines measured
  on the frame against a model of the board, one letter addressed to an
  animal whose name is a token: `make board-check-en`),
  `emu_write.py` (letters typed on the editor's dial, their lines, cursor
  and end mark measured against the same model, finished and read back
  whole, 192 bytes; the dial opening on its small letters, Z through its
  four modes (no kana), the panel's rows in RAM and their labels' ink on
  screen, the symbols' ring: `make write-check-en`),
  `emu_resetti.py` (Mr. Resetti's window opened as he opens it, each of
  his 16 phrases typed on the dial and judged by his own code, his
  overlay loaded as the game loads an actor's; the 32 words he takes as
  an insult; his check against the model `af_dial.py phrases` stands on;
  his page with each phrase: `make resetti-check-en`),
  `emu_notice.py` (the bulletin board: the game's notices put up as the
  game puts them, each post's lines measured on the board's frame
  against the same model, a notice typed past 96 bytes, finished, read
  back whole, saved and loaded: `make notice-check-en`),
  `emu_messages.py` (messages of the bank shown in the message window as
  the game asks for one, each first page's lines measured against the
  font's model: those taken from the 2010 patch, ours and two of the
  GameCube's; and those that play through without a choice, played to
  their end, the talking animal's order read every frame against the
  cartridge's orders; the names longer than 6, Tom Nook's on the plate
  and in a page with his token and without, the animals' loaded as the
  game loads them and one put in a page; the catchphrases longer than 4
  loaded as the game loads one, the widest put in a page by STR_TAIL; a
  fish's size put in the fishing tourney's messages as it puts it, the
  size's page measured: `make messages-check-en`),
  `emu_menus.py` (the name entry's five windows, the edit-end check's
  question and answers and the address book's balloon, their strings in
  RAM and their ink measured against the font's model; each swapped
  string's ink against where the layout puts it in the cartridge's
  string's box, measured with a ruler in its place; the menus that hold
  the 2010 patch's labels, each label found in RAM; the items' names,
  copied as the game copies them and put into messages, choices and
  letters by the game's own code with their articles, and the money's
  name measured in the inventory; strings of the string bank as the game
  loads them, the staff credits' among them, and the credits on screen,
  each of their 16 pages drawn by the orange box as K.K.'s show has it
  draw them and each line measured; the word textures (the town tune,
  the birthday, the catchphrase window opened and found in RAM; the
  papers' words in the ROM); the title's warning without a controller,
  forced at the title, each line measured as the swapped strings are:
  `make menus-check-en`),
  `emu_signs.py` (the GameCube's signs and ours in the en ROM, every
  copy, and in RAM once the structures' clip sets each structure up by
  the player, the cartridge's gone; the rooms' signs and posters in the
  ROM, the rest of each room's file the cartridge's; the police box's
  posters in RAM once the player walks in at its door, psel-check-en's
  town powered on in a process of its own; the furniture's words in the
  ROM, each object otherwise the cartridge's: `make signs-check-en`),
  `emu_psel.py` (the player-select actor's choices from power-on with a
  saved town of one, two and four players, made from the houses' state:
  which player plays, whose house to remove, each choice's answers read in RAM
  and the game going on; with ares built, the two players' town again on
  ares: `make psel-check-en`),
  `emu_year.py` (that two players' town powered on at each of the year's
  21 event dates and times: the greeter's pages, every block walked
  into, the event's actors, its villagers, the special characters and
  two ordinary villagers talked to, every page and answer read for kana
  and tokens, its name plate judged as the game fills it and its frame
  looked at for a line drawn past the bubble (`Town.page_problems`,
  which the other checks' pages go through too), a hang a failure:
  `make year-check-en`),
  `emu_words.py` (the strings the cartridge's code loads into 10 bytes or
  4, each loaded its way and put in as a message puts one in; the talk's
  and the fortune teller's own fillers, their overlays loaded as the game
  loads one; the special characters' names by the game's own code: `make
  words-check-en`),
  `emu_town.py` (not a check: how the checks make a saved town, the first
  house chosen by the game's talk, the actors saved as the game saves
  them before a save, the town grown to 15 animals by the game's code),
  `emu_reset.py` (one flash powered on seven times, saved at the gyroid
  to go on, to stop, or not at all: Mr. Resetti's visits by the game's
  own count, his phrase typed wrong, as an insult and right, the arena
  while he is there; and the fifth of his actors, who comes up during
  K.K.'s show on a Saturday night: `make reset-check-en`),
  `emu_memory.py` (a town of four players and 15 animals powered on at
  the year's 21 dates, the arena read in every block and held to take
  what can come on top; a hook in the allocator's two ways in and in the
  one place where an actor is made, from power-on, in every session: the
  arena never says no, and every actor made is counted; the town walked
  into every block from every side on the dates of snow and a summer
  night; every actor no tour finds loaded accounted for, the nine nothing
  makes never made; the
  player's house, one full of gyroids, the shop, the post office, the
  police box and an animal's house gone into; and over the power-ons of
  one flash, saving at the gyroid in between, the house enlarged twice
  by the game and furnished, the shop grown three times by the game and
  each gone into, its signs in RAM, both tents on the day the game sets,
  the igloo, the raffle's day, the house at night; a player made by the
  game's own road in the same town with a house free (the train of a
  town that is there, Nook at the houses, a house taken); the title left
  alone through its five demos, where START asks the arena for the
  save's buffer: `make memory-check-en`),
  `emu_job.py` (a new game's first hour played with the pad from the
  houses' state: the house chosen, then Nook's first job chore by chore
  as its record in the player's data asks (the work clothes put on from
  the item screen, the seeds planted, everybody greeted, three
  deliveries, a letter written and mailed, a notice on the board), the
  job's flags cleared by the game, something sold and something bought
  in the shop, the gyroid's save, the flash powered on again; the
  letter, written long, must be whole once only its animal keeps it;
  every page judged: `make job-check-en`),
  `emu_trip.py` (the fourth of the game's starts of play, a player who
  comes with a Controller Pak, and the road to it: two towns made by
  the game, the second with other names typed at the dials; a letter
  longer than the cartridge keeps written at home, the station's
  master, the train, the other town as a visitor, an animal there
  spoken to, and home again, where the letter must be whole and the
  gyroid save it so; then two more power-ons at the post office, the
  letter into the Controller Pak's store, two saves, and back whole:
  `make trip-check-en`),
  `emu_determinism.py` (two runs compared frame by frame: `make
  emu-determinism`).
- `reference/NOTES.md` — verified facts with sources.

## Decomp traps

- **The whole ROM is the arbiter.** `tools/af_match.py` compares one
  function at a time (data references masked); only `make verify` sees
  rodata order, bss order and anything another file does. Run it before
  calling a function done.
- **A shared header change can reorder another file's bss under IDO**
  (see NOTES). Keep new types local to the file that needs them.
- **Truth-valued functions return `int`** (`s32` is `long` to IDO and
  adds a temporary), and **declarations shape code under `-g3`**: which
  block a local is declared in, and the order, change branches and the
  stack frame (NOTES, "Matching notes").
- **C that does not match goes behind `#ifdef NON_MATCHING`** with the
  GLOBAL_ASM in the `#else` (`tools/af_wrap.py`); the matching patch must
  build the cartridge.
- **The decomp's make does not track every object**: after building an
  object a different way (NON_MATCHING), delete it, the ELF and the ROM
  (`build/animalforest-jp.z64`) before checking the ROM. The decomp's
  Makefile declares every target `.SECONDARY`, so a missing object or ELF
  rebuilds nothing while the ROM is newer than the sources: `make verify`
  then re-checks the old ROM, and af_ovlreloc finds no objects to prove
  fado on. `af_match.py` always rebuilds its object.
- **Under `-g3` every declared local has a 4-byte home** at the top of
  the frame, used or not (IDO's own object says where, in `.mdebug`;
  asm-processor's has none). A function whose instructions all match but
  whose frame is too large declares more locals than the cartridge's
  source did (func_80877B0C_jp, NOTES "The letter's tag").
- **`code_en` comes out of the heap.** The system heap starts after it,
  and play gives the zelda arena the rest of its state's heap, so every
  byte the en build adds there is a byte less for the arena, and an
  actor's overlay that finds no room stops the game. With every grown
  section in `code_en` (112 KB) the arena had 0x51B0 left at the houses
  and 32 KB at its least on the morning-aerobics date's tour; the grown
  text now fills the slots other grown sections left in code and the
  font's swap table is packed: `code_en` is 0xBC50 (48 KB). The year's
  least on a tour is 66,544 bytes in a young town and 62,896 in one of
  four players and 15 animals, on New Year's Day in a block with the
  snowballs in it; walked into from every side, the block of the four
  houses has 56,080 that day; and a room has over 400 KB (NOTES, "Item
  names", "The second review", "What the second review had left open",
  "The rooms a day does not reach"). `make year-check-en` and `make
  memory-check-en` hold it to a
  floor, and the second sums what can come into a block on top of what
  it holds (a balloon, bees, a fish let go, a tool in an animal's hand,
  the snowballs) against every block's room. An animal, a special
  character or a structure is not in that sum: `ovl_Npc` and
  `ovl_Structure` keep room for the actors they make inside their own
  overlays, and the arena never sees them come. The save
  and letters checks once went four batches unrun while the swap table
  grew `code_en`, and nothing else noticed. A table that can live in ROM
  goes to a segment; one that must be in RAM is as short as its entries.
- **A few bytes more can cost thousands.** A text section that grew
  lies in the slots the other grown ones left, as many bytes of them
  as `tools/af_relink.py` can place, and the rest in `code_en`. The
  packing is tight (after the letters' fixes `m_handbill.o` and
  `m_choice_main.o` share a slot with 96 bytes to spare, `m_flashrom.o`'s
  run has 112, `m_submenu.o`'s 16): 880 bytes more in `m_handbill.c` put
  6,320 into `code_en`, and
  a few instructions more in a file the translation had not touched
  (`m_private.c`) sent its 12,000 bytes of text there whole. After a
  change to the en tree's C, read the relink's line (`N sections in
  code_en, N in the slots others left, N padded`) and `code_en`'s end
  in the map against the build before; put new code where there is
  room, and in a file whose text may not grow leave a call (NOTES, "A
  trip, played").
- **An overlay's relocation table cannot grow either.** Its `_reloc`
  segment keeps the cartridge's size, and `tools/af_ovlreloc.py` refuses
  more relocations than it holds (the editor's: room for 214, the en
  build's 213). Each reference to a symbol the overlay's objects define
  costs one (a HI16 and each LO16, a jal, a word of data); a distance the
  linker gives (`undefined_syms.ld`, `ABSOLUTE(a) - ABSOLUTE(b)`) costs
  none, since fado relocates only what the objects define. An overlay's
  text that the en build left short of its size can hold data
  (`.word`/`.byte` in a GLOBAL_ASM file) where its data cannot grow; a
  segment's texture or list the en build no longer draws is room too
  (NOTES, "The dial's small letters").
- **A function the translation widens writes past its cartridge callers.**
  The en choice loader wrote its English answers' 24 bytes into every
  buffer it was given; the actors still in asm give it 10, and the
  player-select actor keeps its choice window's pointer after them: with
  two players, removing a house hung the game (NOTES, "The player-select
  actor's answers"). Before a function writes more than the cartridge's,
  find every caller, the overlays' asm included (`grep -rn "jal
  <name>" asm/`), and what each gives it.
- **A bank's table of ends is read two ends at a time.** The readers
  (`mString_Get_StringDataAddressAndSize`, the message and choice
  loaders, the letters') take an even entry's ends 16 bytes from the
  entry two before it, so the last entry of an odd count reads 4 bytes
  past the table; the DMA manager stops the game on a read that crosses
  a segment's end. The en string bank's 0x679 strings hung the game on
  every December name (NOTES, "The year's events"); `af_text.py` pads
  every table to 16 bytes.
- **Never run two makes on `rom-en` at once.** Every en check depends on
  `rom-en`, which is phony: each make runs its relink again in
  build/af_en (the linker script rewritten, the overlays' relocations,
  the link). Two at once corrupt the map: af_shiftcheck then reports
  every section shifted, af_luicheck stops on a missing symbol. Run the
  checks as goals of one make (`make -k test emu-check
  messages-check-en ...`), which builds `rom-en` once.
- **The cartridge's own files hold RAM addresses.** 2,348 texture loads in
  the field models name code's bss buffers by address (NOTES,
  "Shiftability"); assets are not linked, so nothing of code may move.
- **The disassembly can name an address after the wrong symbol and still
  match.** IDO folds constant indexes into addresses (`table[name -
  0x8000]` becomes `table - 0x10000`, inside some unrelated function), and
  splat spells that with the nearest symbol below. The bytes are the
  cartridge's until the layout changes. Spell such a reference with its
  table in `relocs/reloc_addrs-jp.txt` (`addend:-0x10000`); never trust a
  matching build as proof that the symbols are right. `tools/af_anchors.py`
  finds the class (NOTES, "Shiftability").

## Text traps

- **The official English is the GameCube's, and the GameCube is another
  game.** It letters the map's rows where the cartridge numbers them,
  fills a month's name where the cartridge fills a number, drew some
  clothes, floors and walls anew under their old numbers and named the
  new drawing, and speaks of things the cartridge has not (a tailor, a
  lighthouse, a ghost). A message can keep the cartridge's codes and
  still say another thing: the comparison by codes (`tools/af_align.py`)
  cannot see it. Read the Japanese beside it, and look at the texture
  beside a name.
- **A string of the bank longer than the buffer its loader gives it is
  cut, silently.** The cartridge's code loads a string into 4, 6 or 10
  bytes and hands those on; a letter's free string is 10. Before a string
  grows, find every loader of its number (the immediates in the asm, the
  tables in the overlays' data) and what each does with the bytes; where
  they are too few, a token (`tools/af_items.py`).
- **The cartridge's tables can share what English tells apart.** Its
  table of the special characters' names gives the policeman at the desk
  the string of the one at the door: one Japanese word, two English
  names. Comparing the two games string by string shows nothing; compare
  the tables that hand the strings out (`tools/af_items.py
  special-names` does, for the names, at every build).
- **What the player types is compared byte for byte.** Mr. Resetti's
  phrases are compared over 10 bytes with what the dial typed, his
  insults over a length fixed by their place: a character the dial lacks,
  or a word of another length, never matches (`tools/af_dial.py
  phrases` at every build; `make resetti-check-en` types them in the
  running game).
- **The official English names the GameCube's pad.** Its C is a stick,
  it has an X and a Y; the cartridge's C is four buttons. The bulletin
  board's own notice had the pages turned with the stick, in a letter
  bank no control was ever looked for in. `af_text.py` refuses a text
  that names one (`GC_CONTROLS`), in every bank; a control the text
  names is held against the cartridge's Japanese (NOTES, "The first
  job").
- **A name can have a line's end in it.** Fifteen messages of the 2010
  patch had their name for the Controller Pak mended; a sixteenth, the
  name broken over two lines, was found a review later. Search the
  text flattened (a line's end for a space, the codes out), in the
  banks as compiled, and count each word against the script's other
  words for the same thing: the one that stands alone is the stray
  (NOTES, "A trip, played").
- **A tool given the wrong image must refuse.** A file lies at its vrom
  in the decompressed image only; `af_items.n64_name`, given the dump,
  answered every name with nothing, and the listing that would have shown
  a wrong mapping showed blanks instead.

## Emulator traps (each cost a wrong result before it was known)

- **The core saves states on another thread.** `M64CORE_STATE_SAVECOMPLETE`
  arrives when the file is written, frames after the snapshot. Anything
  read in that callback is late. The snapshot is taken at the first
  interrupt after the request, so `save_state()` returns the frame it was
  called at; the frame count and the fixed clock live inside the state.
  Running frames until the file was written made a save cost a
  host-dependent 1-8 frames and moved every route milestone after it:
  `save_state()` runs exactly one frame and waits with the machine held.
- **angrylion's render workers race on texture loads.** Each worker draws
  its own lines, but a load reads lines the others draw; Animal Forest
  copies its own screen before the name dial, and two runs kept different
  pixels. The frontend renders on one thread. Equality within one process
  proves nothing about this: compare two processes (`make
  emu-determinism`).
- **The AF RTC reads the host clock** unless `HeadlessFixClock` is set, and
  a state remembers the time the RTC last read: restoring a state without
  restoring the clock makes the game's time jump.
- **angrylion's VI gamma dither is random noise** from a per-worker seed
  that no savestate holds. Reseeded per line by our patch; without it every
  pixel can differ by one after a load while RAM is identical.
- **RDRAM is host-endian 32-bit words** in mupen64plus: byte `a` of the
  N64's view is at `rdram[a ^ 3]`. `N64.read()` does the swap.
- **A frame is a finished graphics task**, not a VI; Animal Forest renders
  at 30 fps here (about two VIs per frame), and reads the controller
  several times per rendered frame.
- **Memory watchpoints need an interpreter and physical addresses.**
  `N64(watch=True)` switches to the cached interpreter (the dynarec does
  not check them) and `watch()` converts to physical, which is what the
  core compares. They only see the CPU: RDP/RSP writes never fire.
- **The core picks the save type by the ROM's MD5** (mupen64plus.ini:
  Flash RAM for this cartridge; an unknown ROM gets 4 KB EEPROM). `N64()`
  gives an unknown ROM the original's entry (`like=`), so a rebuilt ROM
  saves as the cartridge does.
- **`frames(n)` without buttons keeps the pad as it was.** A loop that
  presses A with `frames(3, A)` and then waits with `frames(1)` holds A
  for the whole wait: the game sees one long press and never turns the
  page (two probes "found" a stuck dialogue this way). Release it:
  `frames(n, 0)`, or `press()`.
- **A font pixel is 1.954 frame pixels wide, not 2, where the font's
  projection draws it.** `guOrtho(-2560, 2560, ...)` has an x scale of
  25.6/65536 and the matrix keeps 25: what goes through it (the boards,
  the menus, a name plate) is 25/25.6 as wide (`emu_board.BOARD_X`,
  measured on the letter board; `emu_board.projections` finds the matrix
  in the frame's lists). The message window's body text is drawn with
  texture rectangles and is 2. The VI has no part in it (this note
  once said it had). The name entry's check assumed 2 and
  passed anyway: the VI filter's fringe (a frame pixel on each side, which
  its ink threshold counts) cancelled the error on short titles, until the
  address book's 27 characters came out 6 pixels short of the model. When
  a model and the frame disagree, measure the pitch of a test string (one
  letter repeated) before changing either.
- **Measure with a glyph that has no faint edge.** The font's model counts
  a glyph's column as ink from 8 of 15; on the frame, whether a faint
  column passes the ink threshold depends on the colours behind it. The
  swap check first measured the cartridge's box from the cartridge's own
  glyphs: over the Controller Pak's ten-character lines a column at each
  end misjudged the scale by 1.5%, and three strings "failed". It now
  writes a ruler in their place (`emu_menus.ruler`: the widest glyph
  whose columns are whole ink or none, since a short ruler's error grows
  over a longer English) and measures that, and lets an English edge
  reach out to a faint column. A menu can lay itself out by its string
  (the tag sizes its window by `mMl_strlen`, the count before trailing
  spaces): the ruler reaches only as far as the string's last character
  and is compared with the open where only the English is blanked, the
  menu's bytes untouched. A ruler compared with the string made spaces
  moved the tag's window and misjudged the box by 62 pixels.
- **A rebuilt ROM draws another town.** The new game's town comes from a
  seed that follows the run's timing, so a ROM whose code changed (even a
  table in code: the swap list) plays the route to the same milestones on
  the same frames and still draws another town: where one ROM had the
  shop, the next had the post office. A check about the town (where a
  building is) finds it in RAM rather than remembering it: the map's
  check gives the record of the acre the map opens on the label to show.
- **A route that reaches its milestones has not looked at the screen.**
  The relocated ROMs passed the route for a day while every town but the
  route's own was drawn with black or pink ground; nothing checked the
  pictures. Look at the route's sheet against the cartridge's.
- **A screen detector proven on one ROM is not proven on another.** The
  route's name-dial check was tuned on the 2010 patch's English dial and
  missed the cartridge's kana dial (one ring pixel differs); every rebuilt
  ROM then "failed" the route at the same frame, and so did the original.
  Before blaming a ROM, run the original through the same check, and look
  at the screenshots.
- **A copy of RAM is not a save.** While a house, a mailbox or a gyroid
  stands, its place on the map holds a stand-in; the game puts the item
  back before each save (`Actor_info_save_actor`). A town made by
  stamping a copy of `common_data` powers on without the players' houses,
  and every check that used one went on passing. `tests/emu_town.py`
  makes the town; a check of a saved town looks at the houses' block.
- **A state belongs to its date.** Loaded under another clock than the
  one it was saved at, the game sets the town up again and the probe
  reads a scene nobody played: give `N64()` the state's `clock`.
- **And to its ROM.** The emulator loads a state only under the ROM that
  saved it. The make targets remake the route's states before a check;
  a check run by hand after `make rom-en` gave another ROM needs `make
  route-en` first ("state load failed" is this).
- **A door wants A, and where its own code looks.** Pushing at a door
  does nothing. Each building's actor checks the player's place and
  facing its own way (`tests/emu_memory.py`, `BUILDINGS`); and a probe
  that asks "am I inside?" must ask for an actor only that room has:
  `Field_Draw` and `Shop_Goods` are in every room.
- **The arena's largest free block is not a block's own.** It depends
  on the way the player came: what the block before held decides where
  the next one's actors land, and the same block on the same day
  differs by up to 35 KB (a day of snow: whether the block before had
  the snowballs' overlay loaded). What a block keeps with all that can
  come on top differs by 18 KB with it, more than the 16 KB a tour is
  held to keep: a tour's word is not enough on a day of snow.
  `tests/emu_memory.py` walks every way into every block on the dates of
  snow and a summer night (`edges`; it fails if a tour meets the
  snowballs on a date it does not walk) and hooks the allocator itself
  (`watch`: the least after any allocation, between two frames too). A
  figure in the notes says which walk it is from.
- **Count what is made where it is made.** A look between frames misses
  what comes and goes, and a table read says where an actor could be,
  not where it is: reading had put three actors in places they are not.
  `Actor_info_make_actor` is the one place an actor is made; the hook
  counts each number there, from power-on (`watch`'s `made`).
- **A room makes only so many pieces of furniture.** `ovl_My_Room` has
  places for 24, 32 and 48 pieces in the three sizes of a player's room,
  30 in an animal's, 5 in a shop (`func_80938EF0_jp`'s table): a check
  that puts a piece on every place of a floor finds that many made, the
  first ones in the room's order, and no more.
- **A sign is as often tall as wide.** A survey of the wide textures
  missed the signs inside the fourth shop. The GameCube's module keeps
  the rooms' textures under names of its own: lay each texture a room's
  lists draw out as the GameCube keeps one and look for it there (285 of
  the rooms' 358 are there byte for byte), then look at the rest
  (NOTES, "The GameCube's signs").
- **Not every actor comes out of the arena.** `m_actor.c` asks the
  clips of `ovl_Npc` and `ovl_Structure` for the overlay and the instance
  of what they make (the names 0xD000, 0xE000 and 0x5000): both lie
  inside those two overlays, loaded once. A sum of "what can come on
  top" that counts the postman counts nothing real, and one that counts
  overlays without instances counts half (`tests/emu_memory.py` did both
  at first).
- **A town stamped by hand skips the road to it.** The game lets nobody
  save before the first job for Nook is done, so the checks' towns were
  made with the job marked done, and for a long time nothing played the
  hour every new game starts with, nor the road of a player who comes
  to a town that has players: both go through code and text no saved
  town reaches (the item screen worked with the pad, the address book,
  the post office's counter, a train with another guide). The game's
  own lists say which roads there are: the scenes' table, the starts of
  play (`m_start_data_init.c`), the kinds a quest's record takes. Hold
  the checks against those, not against what a saved town happens to
  pass (`tests/emu_job.py`, `tests/emu_memory.py`'s newcomer).
- **The game takes a traveller's things out of the save.** Before the
  flash is written for a trip the save's own record of the player has
  no pockets, no Bells and no letters (the Controller Pak has them),
  and the player is marked away. What the translation keeps beside the
  save by what the save holds (the letters' rests, by the letters'
  bytes) was dropped at that write, and every letter carried came home
  cut. Anything keyed to the save's content must be held through a
  trip; and a check that never leaves the town never sees it
  (`tests/emu_trip.py`).
- **A letter lies in more places than the save's letter places.** The
  pockets, the mailboxes and the post office's seven are 87; an animal
  keeps the last letter each player sent it (105 more, in the animals'
  memories), and the post office stores up to 160 on a Controller Pak.
  Both lost their rests the same way and are held now (the animals'
  by their keys, the Pak's by a mark on the record: NOTES, "A trip,
  played"). Before keying anything to "the letters of the save", ask
  where else the game copies a letter's bytes.
- **The post office's letter store is worked with Z.** In the clerk's
  third answer's menu the hand begins on the pockets' first letter; Z
  takes and puts down the letter under it (A does not), B asks the
  closing question and A must be pressed once to show its answers and
  once more to choose; the hand cannot take a letter its player wrote
  and has not sent; and when the menu is done the clerk asks her
  answers again (the first would open the item screen: answer the
  last). `tests/emu_trip.py` has the steps.
- **An animal met at its door takes the player indoors.**
  `Player.animal` (`tests/emu_job.py`) walks up to an animal and
  presses A; one that stands at its own door lets the player into its
  house, the talk happens there, and `leave()` walks straight down,
  which finds the door only from in front of it. Seen once, by hand,
  on the town of an earlier build at 10:00: the player stayed in the
  house and every step after it failed, loudly ("NOT STORED AS
  MEANT"). The town of the delivered build has no animal at its door
  at the hours the checks use. If a new build's town has, the first
  job's check and the trip's fail at the step after a talk: give
  `leave()` the way to the door (the room's middle, then down) and run
  both again.
- **The talk that opens by the gyroid is not always the gyroid's.** An
  animal that has not met the player has its first word when the
  player comes by. On the delivered build's town, the day after the
  shop's renewal, the player walking to the gyroid to save got that
  word instead (three pages, no choice, the game went on): the memory
  check's chain of shops broke at its second shop, five failures of
  one cause, none the game's. `save_and_stop` (`tests/emu_memory.py`)
  now tries the gyroid again while the game has not left. A step that
  takes "a window opened" for "the one I walked to answered" can meet
  this anywhere outdoors: hold it to what the game does next.
- **Two towns of one route are one town.** The names typed are the
  same and the ids come out the same: a trip between two of the checks'
  towns is a homecoming into the wrong town. A check that wants a
  visitor makes its second town with other names at the dials.
- **A menu can open over a talk.** The item screen (to hand something
  over, to choose what to sell), a letter held up: the window is not
  hidden then, and a loop that presses A while a window shows presses
  it into the menu for as long as it is allowed. Look for the menu
  first (`Town.menu_open`), then at the window.
- **The item screen's hand starts on the first pocket** each time START
  opens it, and a tag's entries depend on the pocket and on the place: a
  bag of seeds has an entry to plant only where the ground before the
  player is free. A check that works the screen reads the pockets after
  each try rather than trusting the entry.
- **A talk can start by itself.** On Halloween night the animals run the
  player down, ask for candy and open the pockets; a tour that only walks
  stands there until its tries run out and then reports blocks it "could
  not reach". `emu_year.Town.cross` plays such a talk through (A on its
  pages, the first answer, B on a menu it opened) and makes the try again,
  and the checks that tour a town fail on any block not reached.
- **A special visit is set at one start of play and kept at another.**
  The black market's tent, the fortune teller's and the designer's car
  stand one to seven days after the start of play that set them
  (`func_8007E780_jp`: the kind by that start's date and hour, never the
  last visit's kind), and the save keeps the day. Powering a saved town
  on at day after day from the same save sets a new visit each time and
  never sees one: a month scanned that way found no tent. Save at the
  gyroid, then power on at the day the save holds
  (`tests/emu_memory.py`, `tents_chain`).
- **Saving and going on, then cutting the power, is a reset.** The mark
  stays, and Mr. Resetti is at the door at the next start. A check that
  powers one flash on again and again saves and stops (the answer
  that leaves for the title) unless he is what it is after.
- **What the game does over days, let the game do.** A house is
  enlarged at the start of play after the day it was ordered, a size at
  a time (the walls of the size before are taken down, no other); the
  shop is closed with a sign for the two days before its renewal, then
  swapped on the map at the town's start; the raffle's machine is in the
  shop on a month's last day. Write into a save what the player's
  dealings would have left there (an order, the sales), never the
  result, and power on.
- **mupen64plus starts once per process.** A second `N64()` in the same
  process fails at PluginStartup (error 2): a check that powers on more
  than once runs each in a process of its own (`emu_save.py --boot`,
  `emu_psel.py --play`).
- **ares prints on its stdout** ("CPU JIT: flushing all blocks"), so
  `ares-headless` answers on a copy of stdout taken before ares runs,
  ares's own prints going to the log. Its RDRAM is behind an emulated
  data cache: reads and writes go through `dcache.readDebug` and
  `writeDebug`, or a line the CPU has not written back reads stale. Its
  clock is the host's, kept in the cartridge's .rtc: a saved town loaded
  there lives on the host's date, and a run is not frame for frame
  repeatable; check what a run does, not the frame it does it on.
- **The title logo's colours are the demo's.** "Forest" takes its colour
  set from `mTD_get_titledemo_no()`: two shots of the title a minute
  apart differ on every emulator. Compare a title with one of the same
  demo, never with another emulator's shot taken at another moment.
- **Project64 runs only with the cartridge's game settings** (3.0.1:
  Protect memory; 4.0: the 32-bit engine and Store Instruction; NOTES,
  "Other emulators"), and under Wine on Xvfb its window must not pause
  without the focus (Auto Sleep=0). With no window manager, keys reach
  it only with the focus (`xdotool windowfocus`) or the pointer over it.
  4.0's stick is on the keypad, whose keys xdotool does not send
  reliably (they toggle Num Lock): put the stick on letters in its cfg
  (`[Input-Controller 1]`, `AnalogUp=` and so on, SDL scancodes). A
  driver killed while it holds a key leaves the key held.
- **RetroArch with Mupen64Plus-Next keeps the flash in its .srm**, at
  0x28800 in mupen64plus's byte order (NOTES, "Other emulators"). It
  writes the .srm at its autosave interval and when it quits (its
  network command QUIT), never when killed. Its GL window, grabbed from
  the root window, now and then comes out all black: grab again before
  reading a screen.
- **No frame and the CPU in the idle thread** (0x80026084,
  `Idle_ThreadEntry`): a thread stopped and the rest wait on it. The DMA
  manager stops on a read past a segment's end (above) or a part of a
  compressed file; `Hang.pcs` says where the CPU was, not which thread
  stopped.
- **simple64 runs here only as its last Windows build under WineHQ
  11** (its Flathub app is retired, its repository gone): Wine 9 lacks
  Vulkan in a child window. gopher64 segfaults at start here, with the
  cartridge too.
- **RMG (Flathub) runs here only with D-Bus**: `flatpak run` says "Could
  not connect" until a system bus runs (`dbus-daemon --system --fork`)
  and it has a session bus (`dbus-run-session --`). With no input
  profile in its mupen64plus.cfg nothing is plugged in, and the game
  stops at the title's no-controller warning.
