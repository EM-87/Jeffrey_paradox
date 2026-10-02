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
  emu-check, nafe, test.
- `decomp/AF_REV`, `decomp/patches/` — what we build.
- `emu/` — the headless N64. `build.sh` pins and builds mupen64plus-core
  (debugger on), mupen64plus-rsp-cxd4, angrylion-rdp-plus with
  `headless_output.c` instead of OpenGL, and `input_headless.c`;
  `patches/` are our changes to them; `n64emu.py` is the frontend;
  `afplay.py` reads the game's screens (dialogue, choices, name dial);
  `symbols.py` names addresses from the decomp's map; `contact.py` puts
  screenshots on one sheet.
- `tools/af_match.py`, `af_try.py`, `af_wrap.py` — matching: per-function
  check, trying variants, NON_MATCHING wrapping (run in build/af).
- `tools/` — `rom.py`, `ups.py`, `nafe.py`, `nafe_diff.py` (the 2010
  patch against the original, by file and function), `route_newgame.py`
  (power-on to the houses, states on the way).
- `tests/` — `test_tools.py`, `emu_check.py`.
- `reference/NOTES.md` — verified facts with sources.

## Decomp traps

- **The whole ROM is the arbiter.** `tools/af_match.py` compares one
  function at a time (data references masked); only `make verify` sees
  rodata order, bss order and anything another file does. Run it before
  calling a function done.
- **A shared header change can reorder another file's bss under IDO**
  (see NOTES). Keep new types local to the file that needs them.
- **C that does not match goes behind `#ifdef NON_MATCHING`** with the
  GLOBAL_ASM in the `#else` (`tools/af_wrap.py`); the matching patch must
  build the cartridge.
- **The decomp's make does not track every object**: after building an
  object a different way (NON_MATCHING), delete it and the ELF before
  checking the ROM. `af_match.py` always rebuilds its object.

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
