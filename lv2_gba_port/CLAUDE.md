# Working in lv2_gba_port/

Port of The Lost Vikings II (SNES) to the GBA. Research phase: the tools to
watch the cartridge run and read its data exist; the port does not yet.
Read `reference/NOTES.md` before anything else: it is the index of what has
been seen, with addresses.

## Ground rules

1. **The SNES cartridge is the source of truth.** Not memory of how the game
   plays, not the DOS version, not LV1. Those are cross-references: the DOS
   game and LV1 GBA can suggest where to look, and the cartridge decides.
2. **Nothing derived from a dump is committed**: not the ROMs, not chunk
   dumps, not disassembly listings, not save states. Tools take the dump's
   path and write under `build/` (ignored). Same policy as the Tetris port.
3. **VERIFIED means seen**, and NOTES.md says how (an instruction read at an
   address, a trace, a byte-for-byte match against the cartridge's own
   output). Anything else is `TODO(verify)` at its point of use and in
   NOTES.md's Open list. Cite CPU addresses (`$80:B80C`) next to anything
   lifted from the ROM.
4. **Measure on the running cartridge** (`tools/snesdbg.py`) before
   reasoning about it. A breakpoint, a DMA log or a trace answers in
   minutes what reading 65816 cold takes hours to guess at.

## Tools

- `make check` — the tools' own tests, no dump needed.
- `make snes-core` — builds `build/snes9x/libretro/snes9x_libretro.so`:
  upstream snes9x at the commit pinned in `tools/snes9x/build.sh`, plus
  `dbg.cpp` and `hooks.patch`. To change the instrumentation, edit
  `dbg.cpp`, or regenerate `hooks.patch` with `git diff` in the build tree
  (dbg.cpp/dbg.h stay untracked there; build.sh copies them in).
- `tools/snesdbg.py` — one core per process (libretro keeps globals). The
  code/data log persists across `load_state`, so one process can sweep
  several states into a single log.
- `tools/dis65816.py`, `tools/lv2data.py` — see their docstrings.

Getting to level 1 from power-on took about 13,000 frames: START skips the
logos and picks NEW GAME, the story then plays on its own timer, and the
dialogue once the level starts waits for button presses. Save a state once
and work from it.
