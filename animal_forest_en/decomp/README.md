# Our work on zeldaret/af

- `AF_REV` — the zeldaret/af commit we build on.
- `matching.patch` — everything we have decompiled, as one patch on top of
  `AF_REV`. Every function in it compiles to the cartridge's own bytes:
  `make verify` builds the ROM with it and compares with the dump. It is
  code we wrote; no data from the ROM goes in it.

- `changes.patch` — what the translation changes on purpose (buffers, the
  text system, the script), applied on top of `matching.patch` in a second
  checkout, `build/af_en`, where that patch is committed as the baseline.
  Its ROM is never compared with the cartridge: `make rom-en` builds it
  with `code` moved to the end of the ROM so that it can grow (see
  `reference/NOTES.md`, "Shiftability"), and `make route-en` plays it.

## Working on it

The Makefile keeps a checkout of `AF_REV` with `matching.patch` applied in
`build/af`. Work there, then keep the work:

```sh
make decomp-tools          # once: m2c, asm-differ's modules, expected/
cd build/af
# move a file from asm to C in yamls/jp/*.yaml, `make extract`, then
# replace a function's GLOBAL_ASM pragma with C:
.venv/bin/python3 ../m2c/m2c.py --context ctx.c asm/jp/nonmatchings/code/m_msg_main/FUNC.s
python3 ../../tools/af_match.py --diff src/code/m_msg_main.c FUNC
python3 ../../tools/af_match.py --all src/code/m_msg_main.c
cd ../.. && make decomp-patch && make verify
```

The translation's changes are made the same way in `build/af_en` and kept
with `make decomp-patch-en` (the diff against the matching baseline there).

`tools/m2ctx.py src/code/FILE.c` (in build/af) regenerates `ctx.c`, the
context m2c reads types from. A function is done when `af_match.py` says
`match` and `make verify` still says identical.

## Where we are

`code/m_msg_main` (the message system, 322 functions at 0x8009D1F0-
0x800A5630) is a C file; functions move from asm to C in batches: 283 in
C so far, 279 matching (the other 4 behind NON_MATCHING): the whole message
system. The museum and mushroom code that followed it in the same splat
segment is now `code/m_museum` (0x800A3400 on, still asm); the GameCube has
them as two files, but on the cartridge the mushroom code starts at
0x800A4448, not on the 16 bytes every object starts on, so here they are one. `tools/af_bench.py`
sweeps variants of one function in seconds when the first try does not
match. The
GameCube decomp's `src/game/m_msg*.c_inc` is the map; the layout of
`MessageWindow` and its buffers is traced from the N64 code itself
(`reference/NOTES.md`, "The message system").
