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

6. **Every claim of "done" runs `make test` and `make emu-check`.**
   `make test` needs no ROM and runs in CI; `make emu-check` needs the
   user's dump and stays local. A change to the
   save code, or one that grows `code_en` (its bytes come off the heap:
   below), also runs `make save-check-en`, one to the letters `make
   letters-check-en`, one to the letter board or the font `make
   board-check-en`, one to the editor or writing a letter `make
   write-check-en`, one to the bulletin board or its posts `make
   notice-check-en`, one to the message bank or its layout `make
   messages-check-en`, one to the menus' strings or word textures `make
   menus-check-en`, one to the structures' textures or palettes `make
   signs-check-en`, one to the choice window or the player-select actor
   `make psel-check-en`, one to `emu/` `make emu-determinism`.

## Where things live

- `Makefile` — baserom, decomp checkout/extract/build, verify, emulator,
  emu-check, nafe, test; `rom-en`, `emu-check-en`, `route-en` and
  `decomp-patch-en` for the translation's build in `build/af_en`; `script`
  for the GameCube text (ISO=).
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
- `tools/af_relink.py`, `af_relsyms.py`, `af_ranges.py`, `af_dmaorder.py` —
  the translation's build (`make rom-en`, in build/af_en): `code` moved to
  the end of the ROM so that it can grow, nothing of it moved in RAM (a
  section that grows, or a new one, goes to `code_en` after `buffers`, a
  shrunk one is padded in place), the RAM addresses splat left absolute
  made relative, and the compressed ROM's ranges and dmadata order (NOTES,
  "Shiftability"). `tools/af_ovlreloc.py` gives an overlay the translation
  changes (`EN_OVERLAYS`) its relocation table anew, from fado, in the
  cartridge's layout, after proving fado on the matching build (NOTES,
  "The letter board in the en build").
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
  official text's codes and the GameCube's own").
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
  (`string_tokens.txt`; NOTES, "Catchphrases longer than 4").
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
  object_00D5B000's data; each checked against the cartridge's lists and
  texels (NOTES, "The GameCube's signs").
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
  letters").
- `tools/af_answers.py` — the answers an overlay copies from its own
  data instead of the choice bank (the player-select actor's for a new
  player), pointed at the bank's English by a token over the en tree's
  copy of the overlay's data at `make rom-en`, each word checked against
  the cartridge's (NOTES, "The player-select actor's answers").
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
  `emu_signs.py` (the GameCube's signs in the en ROM, every copy, and in
  RAM once the structures' clip sets each structure up by the player,
  the cartridge's gone: `make signs-check-en`),
  `emu_psel.py` (the player-select actor's choices from power-on with a
  saved town of one, two and four players, made from the houses' state:
  which player plays, whose house to remove, each choice's answers read in RAM
  and the game going on; with ares built, the two players' town again on
  ares: `make psel-check-en`),
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
  byte the en build adds there is a byte less for the arena. The title's
  load of the save takes 0xF980 bytes of it (66 KB to spare, measured at
  the item names' batch); in play at the houses 0x51B0 are left, and a
  load called there fails (NOTES, "Item names"). The save and letters
  checks went four batches unrun while the swap table grew `code_en`,
  and nothing else noticed. A table that can live in ROM goes to a
  segment.
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
- **A font pixel is 1.954 frame pixels wide, not 2.** The VI shows the
  320 pixels over about 625 of the frame's 640 (`emu_board.BOARD_X`,
  measured on the letter board). The name entry's check assumed 2 and
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
