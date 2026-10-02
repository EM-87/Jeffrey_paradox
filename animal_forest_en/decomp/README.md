# Our work on zeldaret/af

- `AF_REV` — the zeldaret/af commit we build on.
- `matching.patch` — everything we have decompiled, as one patch on top of
  `AF_REV`. Every function in it compiles to the cartridge's own bytes:
  `make verify` builds the ROM with it and compares with the dump. It is
  code we wrote; no data from the ROM goes in it.

Later, `changes.patch` will hold what we change on purpose (the
translation's buffers, the text system), applied on top of matching code.

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

`tools/m2ctx.py src/code/FILE.c` (in build/af) regenerates `ctx.c`, the
context m2c reads types from. A function is done when `af_match.py` says
`match` and `make verify` still says identical.

## Where we are

`code/m_msg_main` (the message system, 322 functions at 0x8009D1F0-
0x800A5630) is a C file; functions move from asm to C one at a time. The
GameCube decomp's `src/game/m_msg*.c_inc` is the map; the layout of
`MessageWindow` and its buffers is traced from the N64 code itself
(`reference/NOTES.md`, "The message system").
