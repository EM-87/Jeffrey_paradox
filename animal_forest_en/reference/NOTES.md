# Animal Forest (N64) → English: verified facts

What has been checked, and how. Anything not here is unverified.

## Inputs

| What | Identity | How checked |
| --- | --- | --- |
| Doubutsu no Mori (NUS-NAFJ-JPN) | 16 MiB, md5 `a4f7c57c180297b2e7ba5a5feb44fe0b` as `.z64`; header CRCs `BD8E206D 98C35E1C` | zeldaret/af `baseroms/jp/checksum-compressed.md5`; mupen64plus.ini `[!]` entry; `tools/rom.py info` recomputes the CRCs (CIC 6102/7101 algorithm) and finds them valid |
| The user's dump | `.v64` order (`37 80 40 12`) despite the `.n64` name | `tools/rom.py info` |
| zeldaret/af | commit `4ddba04604ee7b4c4cfc0b64f8ee4d094bb385be` (2026-08-16) | `decomp/AF_REV`; the user's `af-main.zip` is the same tree (only build output differs) |
| Animal Crossing (GAFE01, rev 0) | trimmed image (27.5 MB: no filler; all 10 files inside); `main.dol` sha1 `2ae8f56e…f9515bf`, `foresta.rel` (Yaz0-decompressed `foresta.rel.szs`) sha1 `c59d278a…bef203` | both equal ac-decomp `config/GAFE01_00/config.yml`. The disc's own sha1 differs from the full-size image's (`2d2b1fa3…`) only because it is trimmed |
| AF Project patch | `NAFE-WIP-2_12_2010.ups`: input 0x2000000 bytes, crc32 `f9bf11e1` (the ROM padded with **zeros** to 32 MiB; `0xFF` gives `dbd4ee14`), output crc32 `46239627` | `tools/nafe.py`; the output's md5 `01bdcc85…a24d94f` is mupen64plus.ini's "Doubutsu no Mori (J) [T+Eng2010-12-02_Zoinkity]" |

## The decomp rebuilds the cartridge

`make` (setup, extract, build, compress) produces
`build/animalforest-en.z64` identical to the dump (`cmp`), about two minutes
from a fresh checkout on 4 cores. The uncompressed intermediate is
`d7ae64f2f47a9fa3f87686a3c5ce09af` (the decomp's `checksum.md5`).

Decomp progress at the pinned commit, by segment entries in the splat
yamls: `code` 81 C / 53 asm, overlays 198 C / 300 asm. Still asm and
relevant to us: `code/m_msg_main` (0x6C0E90, the message system),
`code/m_item_name` (0x6BA3B0), `code/m_handbill` (0x6B6560, letters).
ac-decomp has `src/game/m_msg*.c_inc` in C (GameCube; the N64 code's
descendant). The GameCube message buffer is `mMsg_MSG_BUF_SIZE` 1600 bytes,
mail strings `mMsg_MAIL_STRING_LEN` 132 (`include/m_msg.h`); the N64 sizes
are not yet traced.

## The emulator is deterministic

Measured with `tests/emu_check.py` and ad-hoc runs on the original ROM:

- Two runs from power-on give the same frame, byte for byte, at frame 600
  and at frame 400 (the title: md5 `5eda39514e69467957ec900c2e4355fd`).
- A state saved at frame F and loaded gives, at F+30, the same pixels and
  the same 4 MiB of RDRAM as the first pass.
- Speed: about 40 rendered frames a second (75 VIs/s, 1.25× real time) on
  4 cores; Animal Forest renders about one frame per two VIs and polls the
  controller about four times per rendered frame.

Memory watchpoints (`N64(watch=True)`, cached interpreter) were checked
on `__osViIntrCount` (0x8004330c, +2 per rendered frame): 6 hits in 3
frames, all from `osCreateViManager+0x28c` (the VI manager thread), named
by `emu/symbols.py` from the decomp's map. The core matches watchpoints on
physical addresses; virtual ones never fire. They see CPU accesses only:
a framebuffer the RDP writes never triggers one.

What it took (each found by a failing comparison, in this order):

1. `RandomizeInterrupt` (on by default) jitters PI/SI interrupts with
   `rand()` seeded from `time()`. Off in `n64emu.py`.
2. The AF cartridge RTC (`device/cart/af_rtc.c`) reads `time(NULL)`.
   `emu/patches/mupen64plus-core-fixed-clock.patch` adds
   `HeadlessFixClock(base, vis)`: the clock reads `base + VIs/60`, VIs
   counted in `new_vi()`. The frontend makes `localtime()` UTC.
3. The Controller Pak is formatted with a serial seeded from `time(NULL)`
   (`main.c`, `mpk_seed`); the same patch uses 0 when the clock is fixed,
   as netplay does.
4. The core writes savestates on a worker thread and reports completion
   frames after the snapshot, so reading the clock count in that callback
   gave a count 6 frames late (RAM after a load matched the first pass 6
   frames earlier). The patch puts the VI count and the frame number in
   the state itself, in the spare room of the 4096-byte extra-state area
   behind a magic word (`HLCK`); an unpatched core leaves those bytes zero.
5. After that RAM matched but 84% of pixels differed by exactly one level
   in one channel: angrylion's VI gamma dither noise (`vi.c`,
   `gamma_filters`, per-worker `vi_rseed`). Reseeded per scanline by
   `emu/patches/angrylion-rdp-plus-vi-noise.patch`. The hardware's noise is
   random, so a fixed seed is no less faithful.

## The AF Project ROM in the emulator

Played by hand from power-on through the intro (K.K., the train, Rover,
both name dials), the arrival, Nook's welcome, viewing three of the four
houses, declining two and taking the third, and Nook's rundown of the
house (Gyroid, saving): no hang, text fits its balloons. Its title reads
"Animal Forest"; the RTC reads "12:01 p.m. on Saturday" (2001-04-14 was a
Saturday). `tools/route_newgame.py` replays the way to the houses on its
own (states at frames 3289 name dial, 4119 town dial, 9301 platform, 12178
houses, on the NAFE ROM), in about six minutes.

How the game takes the pad (found by trying; `emu/afplay.py`): A advances
dialogue; a choice balloon moves with the stick, not the D-pad; the name
dial types the letter the stick points at when A is pressed with the stick
held (centred, A does nothing); START ends a name, Z switches alphabet.

The user's report, from what people describe online: the hang comes at
different moments and places, some only after days of play. That fits the
corruption of long-lived or saved data better than one broken scene, and
is not something a scripted walk will find. So the approach is static.

## What the 2010 patch changed (tools/nafe_diff.py)

Both ROMs read through their dmadata and every file Yaz0-decompressed,
paired by vrom address (the patch reordered the table and reused the
16-byte `anime_*_static` placeholder entries for its own banks; its notes
call vroms "codewords"). 76 files differ; 234,143 bytes changed inside the
original files and 2,491,837 non-zero bytes added past the original data's
end (ROM 0xFBC870).

- **Code.** `code`: 14,502 bytes in 116 places, named by the map:
  `mChoice_*` (choice balloons: data, widths, ROM loads, drawing),
  `mMsg_CopyTalkName`, `mMsg_CopyTail`, `mMsg_CopyYear`,
  `mMsg_CopyDetermination` (the substitutions into messages),
  `mIN_copy_name_str` (item names), and many still unnamed
  `func_800xxxxx_jp`, the largest a 6,784-byte run from
  `func_800A05A8_jp`. Overlays: `ovl_select`, `tag_ovl`, `ledit_ovl`,
  `board_ovl` (message board), `map_ovl`, `catalog_ovl`,
  `ovl_Quest_Manager`, `ovl_Npc_Rcn_Guide`/`2`, `ovl_Animal_Logo`,
  `ovl_Mikanbox` and a dozen unnamed ones; `boot` (`bcopy`,
  `fault_AddHungupAndCrashImpl`) and the header.
- **Text banks.** The main message text (vrom 0xBD4000, 0x124A10 bytes) is
  gone from the table; its replacement is vrom 0x1914000, 0x290000 bytes at
  ROM 0x1000000. Its index (vrom 0xCF9000) grew 0xB7B0 → 0x10000. Four
  tables grew 0x890 → 0xF60 (vroms 0xD10000, 0xD12000, 0xD15000) and one
  0x1870 → 0x2000 (0xD18000): "as many valid entries as in Animal
  Crossing", per the patch notes. New banks at vroms 0xD09000 and
  0x1BA5000–0x1BE6000 (select, mail, super, ps, string texts and more).
- **Hypothesis, unverified:** where those grown tables are loaded into RAM
  buffers sized for the original, the copy runs past them. The references
  are not symbolic in the decomp (no `segment_00D10000` use outside
  dmadata); they will show when `m_msg_main` (10,569 lines of asm) is in
  C, which is phase 2 anyway.
