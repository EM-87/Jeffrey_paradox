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

## The message system (code/m_msg_main, phase 2)

A C file since `decomp/matching.patch` (yaml: `c`, `.rodata`); functions
move from asm to C one at a time, and `make verify` proves the ROM is still
the cartridge. Traced from the N64 code (function names are the decomp's
`func_` ones until renamed; the GameCube name in brackets):

- **MessageWindow** (`B_80142410_jp`, 0x2F0 bytes, `include/m_msg_main.h`).
  Same as the GameCube's up to 0x34; then free strings **20 x 10 bytes**
  at 0x38 (GC 20 x 16), item strings **5 x 10** at 0x100 (GC 5 x 16), the
  mail string **1 x 0x44** at 0x132 (GC 132), colours at 0x176 (packed: an
  RGBA8 has alignment 1), the choice window at 0x1B0 (0xBC bytes, GC 0x100);
  from 0x280 on, the GameCube's fields shifted by -0x180 (status flags
  0x28C, cursors 0x29C/0x2A0, main index 0x2B4, request data 0x2E0). No
  English articles. (`mMsg_Set_free_str`, `_item_str`, `_mail_str` and
  `func_8009D9A4_jp`/`func_8009DA1C_jp` [Get_free/item_str] all pad with
  spaces to 10; the mail string forces a new line (0xCD) every 16 characters
  and stops at 5 lines.)
- **The bank** (`func_8009E388_jp` [mMsg_Get_MsgDataAddressAndSize]):
  0x2DE8 messages; index of u32 end offsets at ROM 0xCF9000, text at
  0xBD4000; a message longer than **0x400** bytes is refused (address 0).
- **The buffer** (`func_8009E558_jp` [mMsg_LoadMsgData], `func_8009E6F8_jp`
  [mMsg_init]): `B_80141FF0_jp`, 0x420 bytes = a 0x10 header and **0x410 of
  text**, immediately followed by the window. The DMA writes
  `(ofs + size + 7) & ~7` bytes, at most 0x408 with the 0x400 cap.
- **Substitutions** (codes 0x1A-0x3F after 0x7F: player name, talk name,
  tail, year..sec, free strings 0-19, determination, country name, random
  number, items 0-4; table in `src/code/m_choice_main.c`) expand in place
  through `func_8009EA2C_jp` [mMsg_MoveDataCut]. Its guard: if the grown
  message would pass 0x400 the rest is not moved, but the length still
  grows and the caller copies its string over what follows (same flaw as
  the GameCube's). Control-code sizes: table `D_80106BF4_jp` (2 bytes per
  code, 0x61 codes; `func_8009034C_jp`).
- **Names**: player name 6 bytes (`PrivateInfo.playerId.playerName`,
  `common_data.privateInfo`), town name 6, animal names 6, catchphrase
  ("tail") 4.
- **Choices**: the message system loads up to 4 choice strings from ROM
  (index < 460) into a static `char[4][10]` at 0x80142700, right after the
  window (`func_800A0DF4_jp` -> `mChoice_Load_ChoseStringFromRom`).
- **Control codes** (N64 numbering, differs from GC): 0x00 last, 0x01
  continue, 0x03 cursor time (no x2: 30 fps logic), 0x05 colour, 0x08-0x0C
  demo orders, 0x0D select window, 0x51 sound cut, 0x56/0x57 bgm make/delete,
  0x58 time end, 0x59 system sound.

### The 2010 patch against these buffers (verdict so far)

Measured on its ROM (`tools/nafe_diff.py` and the bank itself):

- Its message locator (`func_8009E388_jp`, replaced by a stub into its own
  routine at 0x800C3E54) **clamps** sizes to 0x400 ("max strlen"), so the
  buffer cannot overflow from loading; and no message of its bank is longer
  than 0x400 anyway (largest **0x3F9**; the original's largest is 0x38A).
- Expansion: counting every substitution at the 10-byte maximum, one
  message (0x2511) could reach 0x401; the original's worst case is 0x392.
  Not a source of frequent failures.
- **Choice strings**: its strings run to 19 bytes and its version of
  `mChoice_Load_ChoseStringFromRom` drops the 10-byte clamp
  (`sltiu at,v1,-1`), so each string spills up to 9 bytes past its 10-byte
  slot; past the last slot that is past the `char[4][10]` array, into 8
  bytes of padding and, at 19 bytes, the first byte of `B_80142730_jp` (the
  museum's mail record, `mMsm_*`). It also passes the full length on to
  `mChoice_Add_choice_data`, whose slots are 10 bytes too. Real memory
  corruption, measured; **not proven** to be the hang people report.
- **The grown mail-text tables** (vroms 0xD10000 body, 0xD12000 header,
  0xD15000 footer; `m_handbill`'s `func_80093878/5F8/738_jp` locate an
  entry, `func_80093DA8/B28/C98_jp` load it) do **not** overflow. The
  originals refuse an entry past 0x220 or longer than 105/14/19 bytes; the
  patch allows 0x3D6 entries of 104/24/32 bytes (its locator at 0x800C3E54
  takes the limit as an argument). The body is DMA'd into `B_80140748_jp`
  (0x78 bytes; the largest aligned transfer, 112, fits) and copied into the
  96-byte `MailContent.body` through a `< 96` clamp; the header goes
  through a 23-byte stack buffer and a copy clamped to the caller's size;
  the footer's copy had no clamp in the original (16-byte field, 19-byte
  limit) and the patch *added* one (`min(size, len)`). The hypothesis above
  ("where those grown tables are loaded into RAM buffers sized for the
  original") is dropped: every loader reads one entry at a time.
- **Verdict so far:** the message buffer, the mail text and the name
  fields hold; the one measured corruption is the choice strings (above),
  inside the 6,784-byte block the patch wrote over the cursor's handlers
  (`func_800A05A8_jp` on; now `func_800A0DF4_jp` in C). Our translation
  will size that array from the strings, not the other way round.
- Its notes claim 8-byte names "wherever they may be used"; the save
  structures hold 6 (player and town names in `PersonalID_c`). Unchecked.

### Matching notes

- IDO 7.1 `-O2 -g3`, like all of `code/` (IDO 5.3 gives 55/64, `-O2`
  alone 27/64 on the first 64 functions).
- **Functions returning a truth value are `int`, not `s32`.** `s32` is
  `long` to IDO, and `return A && B;` in a `long` function goes through a
  temporary (the comparison is `int`, then converted): the result lands in
  v1 and is moved to v0. Declared `int`, it is computed in v0, as the
  cartridge has it (m_bgm.c in the decomp does the same). This was the
  whole difference in three functions held back as NON_MATCHING, and in
  three more of the sound block.
- **Under `-g3`, a block that declares something is laid out apart.** An
  `if` branch with a declaration in it (even an `extern`) gets its own exit
  (the `move v0,v1` duplicated per branch, a load not hoisted above the
  branch). The GameCube source's block-local `static` tables (`mode_table`
  in `mMsg_sound_voice_mode_get`) are such declarations; while the table
  stays in the asm data, an `extern` in the block reproduces it
  (func_8009FC5C_jp).
- **Which locals are declared at function scope, and in what order, sets
  the stack frame.** func_8009F8AC_jp matched only with `data`, `npcId`,
  `voice` at function scope in that order and the voice mode in its block;
  func_8009D308_jp only with `f32 ofsX; char name[16]; s32 len;`.
- **Expression shape picks registers and operand order.** Two bytes made
  into a message number match as `n = data[i + 2] << 8; n = (0xFF &
  data[i + 3]) | n;` (the GameCube's `n |= ...` gives the operands of the
  `or` the other way round); a character tested once needed a local
  (`u8 c = data[idx];`); the length growth in func_8009EA2C_jp a local of
  its own.
- **Statement order is the GameCube's even when the stores are not**: IDO
  sinks a store to the end of a run of stores to the same base, so
  `msg->mainData.savedMainIndex = requestData->value;` written first (as
  the GameCube has it) is stored last on the cartridge, and written last it
  takes other registers (func_800A289C_jp and its siblings).
- `x = x | y` and `x |= y` on a `u16` array element compile differently
  (func_800A0BB4_jp needed the former).
- **A fake match, when nothing natural is found, is marked as the decomp
  marks them**: `if (1) {} //! FAKE` (func_8009FFB0_jp: it splits a
  `default:` block so that the switch is laid out as on the cartridge).
- `tools/af_bench.py` compiles variants of one function alone, with the
  file's prelude, in about 0.1 s each, and counts differences against
  `expected/`: the way to sweep declaration orders and expression shapes.
  The permuter (decomp-permuter, `import.py` then `permuter.py`) finds
  what to look for when nothing obvious works; its results are rewritten
  as natural C or marked FAKE, never committed as they come.
- **Adding a type to a shared header can reorder another file's bss**
  (IDO): a `struct HandOverItemClip` definition in `m_clip.h` swapped
  `l_fossil_block` and `l_haniwa_block` in `m_all_grow.o`. Only the
  whole-ROM check catches it; such types stay local to the file using them.
