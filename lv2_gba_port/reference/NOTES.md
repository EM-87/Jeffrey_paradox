# The Lost Vikings II (SNES) → GBA: what the cartridge has been seen doing

The index of everything traced so far, with the address it was read at, so
the next session starts from here instead of from zero. Same rule as the
Tetris port: an item is VERIFIED only when the cartridge was seen doing it
(an instruction read, a trace, a byte-for-byte match against its own
output), and it says how.

## The dumps

| File | What it is | SHA-1 |
| --- | --- | --- |
| `Lost_Vikings_2_USA.sfc` | The Lost Vikings II, SNES, USA. LoROM SlowROM (map $20), 1 MB, header "The Lost Vikings II", licensee $33, © 1995 Interplay / Blizzard. **The source of truth for the port.** | `7a461513fcea58e3c8c9dbaa431ab6635dbb3ffd` |
| `Lost_Vikings_The_USA.sfc` | The Lost Vikings, SNES, USA. LoROM FastROM (map $30), 1 MB. | `ed1b3038825835d6614ec8a2f3d22517f5334deb` |
| `Lost_Vikings_Proto.sfc` | A prototype of the *first* game (its texts are LV1's; blank header). | `b6471ea55959e97b678f2f157c56c3c0f077906c` |
| `Lost_Vikings_The_USA.gba` | The Lost Vikings, GBA (game code ALVE), 4 MB: Blizzard's own port of LV1 to the GBA. | `670affd3e3cf3f80f939517421ed7927c0c14310` |

None of them is, or will be, in the repository. Tools take their path.

OpenVikings (MIT, Yuri Kunde Schlesner) is a reverse-engineering of the
*DOS* Lost Vikings: its `tools/lvtools/compression.py` and its notes on
chunks and object-script opcodes turned out to describe the SNES games'
data too (below).

## Chunks and their compression — VERIFIED

- **One table reaches all the packed data**: `$8B:8000`, 4-byte entries,
  read by `$80:B80C` (chunk number in A → bank in A, address in X). Entry N
  is `lo, hi` (two little-endian words); chunk N starts at CPU address
  `(0x8B + hi):(lo + 0x8000)`, i.e. file offset `(0x0B + hi) * 0x8000 + lo`.
  The first entry points just past the table: `0x554 / 4` = **341 chunks**.
- **The format is the DOS game's LZSS** (OpenVikings
  `tools/lvtools/compression.py`), read off the two decompressors:
  `$80:B8DE` (into any WRAM address, window at `[$1C]`, cleared first by the
  loop at `$80:B934`) and `$80:BA4A` (to VRAM, through a 4 KB window at
  `$7E:2000` that it DMAs out 4 KB at a time via `$80:BB5B`):
  - a 16-bit length, then a flag byte read LSB first (`$80:B945`): 1 = a
    literal byte, 0 = a reference;
  - a reference is a little-endian word: low 12 bits an absolute position
    in the 4 KB window, high 4 bits the length − 3 (`$80:B9DB`,
    `$80:BB05`);
  - output byte *i* goes to window position *i* mod 4096, which starts
    zeroed.
  - **Unlike DOS, the length word is the exact length**, not length − 1
    (`$10`/`$16EF` count down to zero).
- How it was checked: `tools/lv2data.py` decompresses the whole table; 303
  of the 341 streams end on exactly the byte where the next chunk begins.
  And in level 1 (`STRT`), chunk 25 decompressed by the tool is byte for
  byte the 30,464 bytes the cartridge leaves in VRAM at byte `$8000` (the
  BG tiles); chunks 0, 1, 4 and 125 likewise at `$1800`, `$1300`, `$7DC0`,
  `$7FC0`.
- **Not all chunks are packed.** Chunks 5–21 sit 0x180 bytes apart and
  don't decode as streams (their "length" is $00FF); 22 and 23 are asked for
  a dozen times each during a level load with an index in Y. TODO(verify):
  what reads them, and how the loader knows which chunks are raw.

## Level 1's load — VERIFIED (trace)

The chunks `$80:B80C` is asked for, in order, from the end of the intro to
level 1 being playable (`tools/snesdbg.py`, breakpoint on `$80:B80C`):
26, 25, 27, 1, 284, 2, 144, 126, 107, 208, 110, 111, 135, 124, 128, 123,
139, 278, 202, 114, 112, 120, 115, 113, 121, 122, 3, 0, 3, 5, 11, 12,
23 (×12), 22 (×12), 4, 125.

Level 1 on screen: PPU mode 1; BG1 and BG2 are 64×32 maps of 4bpp tiles (map
word addresses `$1800` and `$1000`), BG3 a 32×32 2bpp map at `$0C00` with
priority (dialogue boxes); sprites 16×16 / 32×32. The level's maps stream in
by column through DMAs of 32 entries out of WRAM `$7E:1743..18A7`. The HUD
(three portraits, life dots, item slots) is the top 48 lines.

## The first game shares all of this — VERIFIED

- LV1 (SNES, USA) has the **same table at `$8B:8000`** (its lookup is the
  same instruction sequence, found at file offset `$3C3D`) and the same LZSS:
  343 of its 361 streams end where the next chunk begins. The LV2 engine is
  the LV1 engine, grown.
- **Blizzard's GBA port of LV1 is a rewrite, not an emulator**: only about
  3% of the SNES ROM's bytes occur in it (884 of 30,752 non-trivial 32-byte
  windows). It holds none of the packed streams, but it holds **108 of
  LV1's chunks byte for byte, unpacked** (e.g. chunks 26–41, 48–58, 64–80,
  88–100: pairs of a ~600-byte and a ~4–12 KB chunk, which looks like level
  data). The big ~32 KB chunks (tile sets) are absent, so the graphics were
  converted. In other words, the GBA engine reads the SNES game's own data
  formats for whatever those 108 chunks are. TODO(verify): what they are.

## Open

- What the raw chunks (5–23) are, and how the loader tells them apart.
- The level format: which chunks make a level (the load list above is
  where to start), the map, the metatiles, the objects.
- The object scripts: OpenVikings `notes/opcodes.txt` describes the DOS
  LV1's bytecode; whether LV1 SNES, LV2 SNES and LV1 GBA run the same one.
- The sound: SPC700 driver, sequences, BRR samples.
- What the 108 shared chunks are, and what the GBA engine does with them —
  that decides how much of LV1 GBA's approach (screen, HUD, controls,
  sound) can be reused.
