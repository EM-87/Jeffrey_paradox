# Our work on zeldaret/af

- `AF_REV` — the zeldaret/af commit we build on.
- `matching.patch` — everything we have decompiled, as one patch on top of
  `AF_REV`. Every function in it compiles to the cartridge's own bytes:
  `make verify` builds the ROM with it and compares with the dump. It is
  code we wrote; no data from the ROM goes in it.

- `changes.patch` — what the translation changes on purpose, applied on
  top of `matching.patch` in a second checkout, `build/af_en`, where that
  patch is committed as the baseline. Today: the message, choice and
  string loaders read their banks from six plain segments of their own
  (named in the dmadata table, whose padding shrinks by their entries);
  the message cap is 0x600 with a buffer of its own, choice strings are
  24 bytes, the window's free and item strings 32, the window and the
  choice strings are C globals, the four NON_MATCHING functions of
  `m_msg_main.c` are built from their C, dates and times are built in
  the GameCube's formats (`m_string.c`, STR_AMPM on code 71), and the
  flash save is written compressed with room for the letters' extension
  (`m_flashrom.c`, `include/af_lz.h`; reference/NOTES.md, "The compressed
  save").
  Its ROM is never compared with the cartridge: `make rom-en` builds it
  with `code` moved to the end of the ROM so that it can grow and nothing
  of it moved in RAM (a section that grows, or a new one, goes to a
  `code_en` region after `buffers`; one that shrinks is padded in place),
  proves the layout against the matching build's map
  (`tools/af_shiftcheck.py`, `af_anchors.py`, `af_luicheck.py`; see
  `reference/NOTES.md`, "Shiftability"), and `make route-en` plays it.
  Every grown section costs heap (code_en comes off the system heap's
  start), so keep changes small and new data in new objects.

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

`code/m_flashrom` (the save in Flash RAM, 42 functions) is a C file too: 35
compile to the cartridge's bytes, 6 are behind NON_MATCHING (the checksum
loop, which IDO unrolls and the cartridge does not; the load into
common_data; the slot repair read; the page reader and writer; the save
buffer's free) and the boot comparison of the two slots is still asm. The
translation changes it to compress each copy (`reference/NOTES.md`, "The
letters").

`code/m_handbill` (49 functions at 0x800928C0-0x80094514: the letters'
loaders and their free strings, after the balloon and feng shui code) is
a C file too: the 42 letter functions all compile to the cartridge's
bytes (two with a `//! FAKE` the permuter found), the 7 before them are
still asm (`reference/NOTES.md`, "How the loaders read them").

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
