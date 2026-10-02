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
   user's dump and stays local.

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
  `afplay.py` reads the game's screens (dialogue, choices, name dial);
  `symbols.py` names addresses from the decomp's map; `contact.py` puts
  screenshots on one sheet.
- `tools/af_match.py`, `af_try.py`, `af_wrap.py`, `af_bench.py` —
  matching: per-function check, trying variants in the file, NON_MATCHING
  wrapping, and sweeping many variants fast outside the build (run in
  build/af).
- `tools/af_relink.py`, `af_relsyms.py`, `af_ranges.py`, `af_dmaorder.py` —
  the translation's build (`make rom-en`, in build/af_en): `code` moved to
  the end of the ROM so that it can grow, its data block kept at its
  address modulo 0x10000 with changed objects taken out to `code_en`, the
  RAM addresses splat left absolute made relative, and the compressed ROM's
  ranges and dmadata order (NOTES, "Shiftability").
- `tools/af_shiftcheck.py`, `af_anchors.py`, `af_luicheck.py` — the proofs
  `make rom-en` runs before compressing: the block moved as one piece, no
  address spelled with the wrong symbol, no shared `lui` broken.
- `tools/` — `rom.py`, `ups.py`, `nafe.py`, `nafe_diff.py` (the 2010
  patch against the original, by file and function), `route_newgame.py`
  (power-on to the houses, states on the way).
- `tools/gciso.py`, `msgbank.py`, `af_align.py`, `af_text.py`,
  `af_names.py` — the GameCube script (`make script ISO=...`): the disc's
  files and RARC members, both games' banks (messages, choices, strings)
  as one tagged text (and the N64's back to bytes), the message-by-message
  comparison, the English banks drafted, checked and compiled for the ROM,
  and the animals' names; output in `build/gc/`, never committed.
  `script/*.txt`, `script/choice/`, `script/string/` hold our own text,
  laid over the drafts (NOTES, "The GameCube script", "The English bank
  in the ROM").
- `tests/` — `test_tools.py`, `emu_check.py`.
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
  object a different way (NON_MATCHING), delete it and the ELF before
  checking the ROM. `af_match.py` always rebuilds its object.
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
- **A screen detector proven on one ROM is not proven on another.** The
  route's name-dial check was tuned on the 2010 patch's English dial and
  missed the cartridge's kana dial (one ring pixel differs); every rebuilt
  ROM then "failed" the route at the same frame, and so did the original.
  Before blaming a ROM, run the original through the same check, and look
  at the screenshots.
