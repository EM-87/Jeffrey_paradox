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
`build/animalforest-match.z64` identical to the dump (`cmp`), about two minutes
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
  4 cores with angrylion's workers, about 22 on its one thread (point 6);
  Animal Forest renders about one frame per two VIs and polls the
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
6. Those checks all ran in one process, from one state. Across runs the
   route's milestones still moved (2026-10-03, the same en ROM: the town
   dial at 4118 or 4120, the houses at 12172, 12176, 12178). Two causes:
   - `save_state()` ran frames until the core's writer thread reported the
     file written (point 4: the snapshot is taken at the first interrupt
     after the request, the gzip and the write happen on a workqueue
     thread), so a save cost 1 to 8 frames depending on the host's disk,
     and every input after it came that much later. Now it runs exactly
     the one frame the snapshot is taken in and waits for the file with
     the machine held (`n64emu.py` `_state`). `emu_check.py`: "a save takes
     one frame", "a run with a save in it is the run without"; the old
     frontend fails both (saved at 537, at 545 after the save).
   - angrylion's render workers raced. Each draws its own scanlines of a
     batch of up to 1024 commands, joined only at SYNC_FULL (the plugin's
     DpCompat 0), but a texture or TLUT load reads lines the others draw:
     it saw the framebuffer half drawn, or half redrawn. Animal Forest
     copies its own screen 33 frames before the name dial (frame 2381 on
     the cartridge, 3256 on the en ROM), so two runs drew different pixels
     there and the game kept them; by the dial 3,615 words of RAM
     differed, three quarters outside the framebuffer. Ending a batch
     before each load and running it alone made the runs identical but
     cost 80 s against the workers' 45 s and one thread's 65 s for the same
     1,464 frames, so the frontend renders on one thread (`Parallel` off).
     The title's md5 is unchanged.
   - What was left differing was in the state files, not the machine: the
     core saves its interrupt queue from a 1 KB stack buffer filled only
     as far as the events go, so each file carried stale host pointers.
     `emu/patches/mupen64plus-core-state-bytes.patch` zeroes it (and the
     state buffer). `tests/emu_determinism.py` (`make emu-determinism`)
     plays the route's opening in two processes at once, compares RDRAM
     after every frame and the states at the dial byte for byte: it passes,
     and with the workers back (`--parallel`) the runs part at frame 2381.

- **The save type comes from mupen64plus.ini, by MD5.** The original's
  entry (`A4F7C57C...`) says `SaveType=Flash RAM`, `Mempak=Yes`; the 2010
  translation is listed with `RefMD5` pointing at it. A ROM the ini does
  not know gets 4 KB EEPROM. `N64()` writes a copy of the ini into its
  workdir with an entry for the ROM's MD5 referring to the original
  (`like=ORIGINAL_MD5`); the core then logs `Save type: 3` (Flash RAM) for
  a rebuilt ROM (measured). What that would otherwise break is not
  measured: it was not what stalled the route (next item).
- **The route's name-dial detector was tuned on the 2010 patch only.**
  `afplay.name_dial_open` read the dial's ring at (270,195), which is
  (41,57,169) on the English dial and (75,131,195) on the kana dial, so
  every Japanese-layout ROM, the original included, "never reached the
  name dial" (frame 5464: the 200-press budget), while screenshots showed
  the dial open since frame ~3089. The ring's deep blue at (280,200) is
  (67,83,234) on both; the pointer at (320,205) was already common.
  Lesson kept in CLAUDE.md: prove a detector on the original before
  reading its verdict on a rebuilt ROM.
- **The game's random numbers are seeded from the cycle counter**
  (`init_rnd`, `code/sys_math.s`, calls `osGetCount`). The same ROM gives
  the same seed every run, but a build that changes what runs before the
  seed, or how long code's DMA at boot takes, gives another: two en
  builds differing only in `m_font.c` (a 256-byte table more in code)
  show the title demo's player in different clothes (measured,
  `make emu-check-en`). So the en title's md5 is not a check across
  builds; the matching build's is (it is the cartridge).


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
  `default:` block so that the switch is laid out as on the cartridge;
  func_80093B28_jp and func_80093F94_jp have one each, permuter finds).
- **A loop bound held in a local is not reloaded; a memory one is.** A
  copy bounded by an address-taken `size` reloads it after every store;
  `sz = size;` keeps it in a register, and IDO unrolls the loop by four
  unless the bound stays `size`'s own load (func_80093C98_jp matched with
  `sz` as the bound and `size` in the call after it, func_80093DA8_jp
  unrolled with a computed `sz`).
- **Parameters walked directly, or locals walking them, are different
  code**: func_800939B8_jp matched only with `*dst++ = *src` on the
  parameters themselves; func_80092E80_jp only with `dst_idx++;
  src_idx++;` as statements and the growth `dst_idx - src_idx` a local of
  the `else` block.
- **Constants of different types are not shared**: `bzero(buf, 0x78)`
  and a `size_t` field set to 0x78 load the constant twice, as the
  cartridge does (func_800942A0_jp); with an `s32` field IDO keeps one
  register for both.
- `tools/af_bench.py` compiles variants of one function alone, with the
  file's prelude, in about 0.1 s each, and counts differences against
  `expected/`: the way to sweep declaration orders and expression shapes.
  The prelude gets prototypes of the functions the file defines before
  the one benched: without them a call to a file-local function is an
  implicit declaration, which IDO compiles differently
  (func_80094400_jp: 46 differences alone, none in the file, until the
  prototype of `func_80093478_jp` was added).
  The permuter (decomp-permuter, `import.py` then `permuter.py`) finds
  what to look for when nothing obvious works; its results are rewritten
  as natural C or marked FAKE, never committed as they come.
- **Adding a type to a shared header can reorder another file's bss**
  (IDO): a `struct HandOverItemClip` definition in `m_clip.h` swapped
  `l_fossil_block` and `l_haniwa_block` in `m_all_grow.o`. Only the
  whole-ROM check catches it; such types stay local to the file using them.

## Shiftability (phase 3): what pins the cartridge's layout

Measured on the decomp at AF_REV, to decide how the translation can grow
code and text without breaking the rest. The result is a layout rule, the
tools that enforce it, and one asm correction; all of it byte-neutral for
the matching build (`make verify` stays identical).

- **ROM (vrom) layout.** dmadata is generated from the linker's segment
  symbols (`src/dmadata/dmadata.c`, `tables/dmadata_table.h`) and the
  actor/game overlay tables from `SEGMENT_ROM_START(ovl_*)`, so those
  follow any move. What does not: about **4,400 vrom addresses written as
  plain numbers in asm data tables** (`.word 0x011E6000`: 1,902 each in
  `ac_my_room` and `catalog_ovl`, 222 in `ef_effect_control`, 56 in
  `code/729E40.data.s`...) and ~120 `D_XXXXXX = 0xXXXXXX;` absolutes in
  `linker_scripts/jp/undefined_syms.ld` (plus splat's `auto/`). Moving any
  original segment would need all of them symbolic. **Decision: nothing in
  the original vrom layout moves.** `code` is cut out of its place in the
  generated linker script and appended after the last segment
  (`tools/af_relink.py`: `__romPos` pinned to 0x73F4D0 where it was, so
  every other segment keeps its vrom; the compressed ROM has no hole since
  compress.py lays out only what dmadata lists). New text banks will be
  new segments at the end too. Nothing refers to code's vrom but
  `SEGMENT_ROM_START(code)` in `src/boot/idle.c` (grepped: no numeric
  reference to 0x675720-0x73F4D0 anywhere). Proof: the relocated ROM
  (code at vrom 0x1914000, nothing else changed) plays the whole route
  like the cartridge (name dial frame 2414, town 2976, station 8154,
  houses 11032, against 2414/2975/8154/11030; the drift is one frame of
  timing).
- **RAM: what must stay symbolic.** `buffers` is placed at `code_VRAM_END`
  and the system heap runs from `SEGMENT_VRAM_END(buffers)` to the
  framebuffer (`src/code/main.c`), so a bigger `code` only shrinks the
  heap (a control with 64 KB less heap and nothing moved plays the whole
  route: 2414/2970/8147/11023). Pins that broke a 64 KB growth (the game
  hung at frame 1: the graphics pools were written into code's moved bss):
  four `D_801524C0_jp`/`gGfxPools`... absolutes in `undefined_syms.ld`
  (removed; the bss asm defines them) and 22 absolutes splat emits for
  addresses inside data it gave no label to (`D_80104509_jp` =
  `D_80104508_jp+1`...), made relative from the matching build's map by
  `tools/af_relsyms.py`. One `lui` splat had not paired with its `lo`
  (`B_8011B8B0_jp`, `code/67D890.s`) is paired through
  `relocs/reloc_addrs-jp.txt`.
- **RAM: what cannot be made symbolic, and the rule that keeps it.** Two
  things in the code's own addresses are invisible to the linker:
  1. `lui` halves shared between two symbols. MIPS loads an address as
     `lui %hi(A)` + `%lo(B)(reg)`, and IDO (and the libultra asm) reuses
     one `lui` for neighbouring symbols; the disassembly keeps the pair
     with two names. The halves agree only while both symbols are in the
     same 64 KB `%hi` window, so the pair breaks when they move by
     different amounts, or by the same amount that is not a multiple of
     0x10000. `tools/af_luicheck.py` finds them in the asm (165 pairs
     tracked by register, 41 it cannot track) and tells which a new map
     breaks; IDO's own sharing inside each C object is not in the asm at
     all.
  2. Distances between objects. A whole-data shift by 0x10000 keeps every
     pair, and `af_luicheck` agreed, yet the ROM failed: pads of 64 KB put
     at section boundaries (before .data, .rodata, .bss, buffers) showed
     that only moving **.data** broke it, and bisecting the pad point over
     the data objects left one, `m_name_table.o`. A read watchpoint on its
     old addresses caught the culprit at frame 1639: `ovl_Birth_Control`'s
     `func_8093629C_jp` reading `move_obj_profile_table` by an address
     spelled `Na_KishaStatusLevel + 0x7C` (a function in the audio code).
     IDO had folded the actor name's type bits into the address
     (`table[name - 0x8000]` on an s16 table = `table - 0x10000`), the
     address landed inside an unrelated function, and splat named it after
     the nearest symbol below. Same bytes as the cartridge; but the table
     moves and the function does not, so the spawner read zeros, the train
     window never came, and Rover stood at the door (the "no second
     dialogue" of every grown ROM, frame 1964 on). The decomp itself had
     the other one in the same function on a wrong data symbol
     (`D_80100C30_jp - 0x7D04` = `actor_profile_table - 0x12000`, type
     0x9000). Both are now spelled with their table in
     `relocs/reloc_addrs-jp.txt` (addend -0x10000 and -0x12000), and
     `tools/af_anchors.py` scans the asm for the class: a load or store
     spelled with a text symbol plus an offset, or any reference whose
     address falls in another object than its symbol and is not a
     4 KB-round negative fold. In `code` there were these two; the eight
     outside are overlay-internal folds (`Npc_Police_Profile - 0x74`...)
     and boot's `sBootStack + 0x400`, which move with their own overlay or
     never move.

  **The first rule, and why it was wrong.** The first layout let text grow
  in place and moved the whole data block (code's .data, .rodata, .bss and
  `buffers`) by a multiple of 0x10000, padding its start back to its old
  address modulo 0x10000; objects whose data changed size went to a
  `code_en` region between the text and the block. It kept every `%hi`
  pair and every distance, reached the name dial at 2414 like the
  cartridge and played the route to the houses; and from the first English
  ROM on, every town but the route's one was drawn wrong: the station's
  wall, rails and ground black (Japanese text, a 64 KB data object added:
  `padexp/fix/en_dummy`) or pink noise (the English ROM), the houses'
  ground the same. The route's own town looked right, and the English text
  changed the timing enough for the game to generate another town (station
  type 1 instead of 3), which is why it showed up with the text. Not heap:
  64 KB more of it (`func_800D94F0_jp` returning 0x80410000 in a throwaway
  build) changed nothing. The proof: the English ROM's town, written to the
  flash in the cartridge's format and loaded by both ROMs with the same
  pad, is drawn right by the cartridge and wrong by the English ROM; the
  two heaps then hold the same bytes except code addresses, and in the
  frame's display lists both ROMs load a TLUT from physical 0x00120F10 and
  a texture from 0x0011DED0. In the cartridge those are bss
  (`B_80120F10_jp`, `B_8011DED0_jp`), the field's texture and palette
  buffers that the table at `D_80106560_jp` fills from the ROM; in the
  shifted build they had moved 64 KB up and 0x120F10 was static data. The
  addresses come from the cartridge's own files: the field models' display
  lists name those buffers by their RAM address
  (`gsDPSetTextureImage(..., 0x80120F10)` at ROM 0x1257D10, for one).
  Counted over the ROM (G_SETTIMG followed by a tile or load command,
  outside code): 2,348 such loads in 204 segments, all into
  `B_8011B8C0_jp`..`B_80123AD0_jp` (one bss object,
  `asm/jp/data/code/8011B8B0.bss.o`). Asset files are not linked, so no
  relocation can follow a move. (An earlier scan for such words printed its
  first 40 hits, which happened to be texture noise, and was set aside:
  count the classes before judging a scan by its head.)

  **The rule (`tools/af_relink.py`, proved after every link by
  `tools/af_shiftcheck.py`): nothing the cartridge placed moves.** Not the
  text, not the data block. A second `af_relink` pass, with the map of a
  first link, takes every input section of code that grew, or is new, out
  of its slot (left as a hole of its old size) into a `code_en` region
  after `buffers`; a section that shrank stays, padded to its old size (so
  far m_msg_main's text, 0x6210 -> 0x61E0). code_en's ROM image follows
  code's (the bss and buffers between are zeros in the ROM, almost free in
  the compressed file), so the boot's one DMA of code (`src/boot/idle.c`)
  loads it and its bss arrives zeroed; `buffers_VRAM_END` follows it, so
  the system heap starts after it (`src/code/main.c`). Each moved section
  is kept inside one 64 KB `%hi` window (IDO shares `lui`s within an
  object). `af_shiftcheck` checks every input section and symbol of code,
  text included, against the matching map (same address and size; moved
  ones outside the cartridge's range and inside one window; padded ones at
  their address and no bigger), and boot and the overlays unmoved. The
  build now (the save code included): code's text ends and its data starts
  at 0x800FF370 as in the cartridge, code_en is 0x8FC0 bytes at
  0x801948E0, and the heap is 36 KB smaller than the cartridge's (the
  first rule cost 64 KB plus code_en). The town that was pink is drawn
  right, from the cartridge-format save and on the route.

- **Compression.** compress.py numbers dmadata entries sorted by vrom while
  compress_ranges.py numbers them in yaml order; with code last they
  disagree, so `tools/af_ranges.py` computes the ranges from the map, and
  `tools/af_dmaorder.py` writes the table back in the table's order. The
  checksum is recomputed by `tools/rom.py fixcrc`. Nothing in the game
  reads the table by index (the DMA manager's index functions have no
  callers).
- **The first "failure" of the relocated ROM was the test, not the ROM**:
  see "The route's name-dial detector" above. Before blaming a layout, run
  the cartridge through the same check; before blaming a symbol, watch its
  old address (`N64(watch=True)`, `watch(start, end, read=True)`): one
  hit named the function in a run that four rounds of bisection had only
  narrowed to an object.

## The GameCube script (phase 4): what the disc holds and how it maps

Measured on the user's Animal Crossing disc (GAFE01, "AnimalCrossing", a
compacted image: 10 files, the FST at 0xFE500 placing them) and the
cartridge's bank, with `make script ISO=...` (`tools/gciso.py`,
`msgbank.py`, `af_align.py`; everything it writes stays in `build/gc/`).

- **Where the text is.** `forest_2nd.arc` (RARC) holds
  `message_data.bin` (0x2932E0 bytes) and `message_data_table.bin`
  (0x11170: 17,500 u32 ends), plus `npc_name_str_table.bin`;
  `forest_1st.arc` holds the free strings (`string_data`, 2,500 entries),
  the choices (`select_data`, 750), the mail banks (`mail_data`,
  `maila/b/c_data`), the shop and sign banks (`ps_data`, `super_data`...),
  each a data file with a `_table.bin` of ends. The disc also carries
  `foresta.map` (4.8 MB: the game's full symbol map) and `foresta.rel.szs`.
- **The bank format is the N64's.** One byte per character, 0x7F then a
  code number for a control code; the table is u32 end offsets; message n
  is `[end(n-1), end(n))` (GameCube `mMsg_Get_BodyParam`, N64
  `func_8009E388_jp`). The GameCube's two files start with 32 bytes that
  the game's ARAM copies skip (eight zero entries in the table; some
  character codes in the data): message 0 ends at the table's ninth entry.
  Proof: 16,273 terminators (MSGEND/MSGCONTINUE/MSGTIMEEND) in the data,
  16,273 non-zero table entries, each exactly 32 less than a terminator's
  end. The N64's files are stored plain in the cartridge (dmadata: text at
  prom 0x9C11C0, index at 0xAE5BD0), so the dump reads them directly;
  `tools/msgbank.py check-n64` dumps, parses and re-encodes the 11,752
  messages to the same bytes.
- **Control codes are the same numbers.** The 97 N64 codes
  (`D_80106BF4_jp`: two bytes per code, size then kind, read by
  `func_8009034C_jp`) have the sizes of GameCube codes 0..96, name by
  name (MSGEND 0 ... STR_MAIL 64, SNDCUT 0x51, BGMMAKE 0x56, BGMDELETE
  0x57, MSGTIMEEND 0x58, SNDTRGSYS 0x59). The N64 bank uses 0..94. The
  GameCube added 97..122; inside the N64's numbering it uses CUTARTICLE
  (1,097 times), CAPITALIZE (125), SETCURSORJUST/CLRCURSORJUST (143/132),
  STR_AMPM (45), SETNEXTMSG4/5, SETSELSTR5/6, SELNOBCLOSE,
  SETNEXTMSGRNDSECTION, STR_ISLANDNAME, MALEFEMALECHK, SPACE: the English
  article machinery and the island, which the N64 code does not have. The
  compiler (phase 4) must implement or strip each of them.
- **Charsets.** Both ASCII-shaped from 0x20 (`0`-`9` at 0x30, `A` at 0x41,
  `a` at 0x61, 0xCD newline, 0x2B a heart, 0x2F a note); the N64 has kana
  where the GameCube has accented Latin (the AF Project's text table, in
  `tools/msgbank.py`; 0x80 unnamed, written `{80}`), the GameCube's is
  ac-decomp's CHAR_MAP (CC0). No English letter needs a new code; the
  accented ones and the GameCube's symbols are phase 5's font work.
- **The numbering is shared.** The GameCube kept Doubutsu no Mori's
  message numbers: the follow-up numbers inside SETNEXTMSG* codes are
  identical in both banks (N64 7633 and GameCube 7633 both continue with
  0x1DE8-0x1DEA), and compared number by number on the codes that carry
  meaning (expressions, substitutions, follow-ups, choices, sounds; not
  pauses, buttons, line breaks or the article codes): of 11,752 N64
  messages, **7,239 (61.6%) have the same structural codes** in the same
  slot, 1,413 (12.0%) have none on either side (same by numbering alone:
  greetings, one-liners), 2,131 (18.1%) share codes but differ (the
  message rewritten or re-timed for English; still the same message, to
  take with care), 709 (6.0%) have an empty or bare GameCube slot (the
  debug messages 1-18, and lines the GameCube dropped, like 100 and 197
  of the moving-in talk: to translate from the Japanese), 260 (2.2%) have
  nothing in common (the slot was reused). The GameCube's 4,521 further
  messages (11,752-16,272) are its own content. `build/gc/align.tsv` has
  the class and both texts for every number.
- **A first wrong cut** (the table read from entry 0) made the GameCube
  look like the N64 shifted by 8: 385 of 552 exact structural twins at
  +8. The terminator count settled it; the lesson is the usual one: a
  header is a measurement, not an assumption.

## The English bank in the ROM (phase 4, task 11)

How the translation's text gets into the game, measured on the build and
the emulator (`make rom-en`, `make script ISO=...`).

- **Two plain segments at the end of the ROM.** `tools/af_text.py compile`
  writes `msg_text.bin` and `msg_index.bin` (the cartridge's format: text,
  then u32 ends, 16-byte padded) into `build/af_en/assets/jp/en/`; the
  decomp's Makefile turns any `assets/jp/**/*.bin` into an object;
  `tools/af_relink.py --segment` places each as its own ROM segment after
  `code` (4 KB aligned, vrom 0x19DE000 and 0x1B03000 in the first build);
  two `DEFINE_DMA_ENTRY` lines (changes.patch,
  `include/tables/dmatables/dmadata_table_jp.h`) give them dmadata
  entries, and `sDmaDataPadding` in `src/dmadata/dmadata.c` shrinks by
  their 32 bytes so dmadata keeps its size (0xD3F0: 3,375 entries, the
  terminator and 0xF0 of padding), hence its ROM and RAM neighbours keep
  their addresses (dmadata_VRAM_END = code_VRAM = 0x80051A80, checked).
  They are plain, not compressed, because the loader reads messages in
  pieces straight from the ROM (`DmaMgr_RequestSyncDebug` of a few bytes
  of index, then of the message), which only a plain file allows.
  `func_8009E388_jp` reads `SEGMENT_ROM_START(msg_en_index)` and
  `SEGMENT_ROM_START(msg_en_text)` instead of 0xCF9000/0xBD4000
  (changes.patch, `src/code/m_msg_main.c`). Proof: with the cartridge's
  own bank compiled into the segments (byte-identical, checked in the
  uncompressed ROM) the game reaches the name dial at frame 2414 as the
  cartridge does.
- **The buffer.** The cartridge's message buffer `B_80141FF0_jp` is 0x420
  bytes in an asm bss file (0x10 of header, 0x410 of text) and the loader
  refuses messages over 0x400 (`mMsg_MSG_SIZE_MAX`). Ten of the official
  English messages are 1,031-1,224 bytes (the GameCube's buffer is 1,536,
  1,600 allocated), so the en build sets the cap to 0x600 and gives the
  window its own buffer, a C global of 0x620 in `m_msg_main.c` (the
  cartridge's is only ever reached through `msgData`, set at init):
  new bss, which the layout rule carries to `code_en` (0x800FF370, the
  block then at +0x10000; af_shiftcheck 0 violations).
- **The draft** (`tools/af_text.py draft`, from `build/gc/align.tsv`):
  7,239 same + 1,413 plain + 2,131 edited messages take the GameCube's
  text, 709 removed + 260 different keep the Japanese with a `TODO`
  note (the 6% to translate; `script/*.txt` is where ours go). Of the
  GameCube-only codes inside: CUTARTICLE dropped 970 times, CAPITALIZE 92,
  SETCURSORJUST/CLRCURSORJUST 56/46, STR_AMPM 43, SPACE 3, SELNOBCLOSE
  mapped to the N64's SELNOB 10 (the closet prompt); characters outside
  the N64 charset: ç->c 13, ÷->? 13, ú/ü->u, a few GameCube marks (□ ☃ ⚷)
  to `?` (phase 5 gives them glyphs or words). `af_text.py check` with
  the cap at 0x600: 0 errors, 84 warnings (70 lines over 32 characters,
  the five-line pages). Of the 11,752 messages, 94% keep their longest
  line within 32 characters.
- **Lines per page: four, and a fifth is lost.** The window draws at
  most `textLines` lines (4, `mMsg_MAX_LINE`) from the page's first
  character (`startTextCursorIdx`), which only MSGCLEAR moves
  (`func_8009E344_jp`); BTN waits for the button and lets the text go
  on (`func_800A0770_jp`). So a fifth line before the next MSGCLEAR is
  typed but never drawn. Measured with a test page of five lines in
  K.K.'s first message: the window shows LINE ONE to LINE FOUR, then
  the clear. The GameCube's drawing loop is the same
  (`m_msg_draw_font.c_inc`, `text_lines` = 4), so its 12 English pages of
  five lines and the cartridge's own 14 lose a line too. `af_text.py
  draft` splits such pages (a BTN and a MSGCLEAR before the fifth line:
  9 splits in the English draft) and the checker makes a page of more
  than four lines an error, unless the cartridge's own message already
  had it (`--reference`).
- **The other banks, and the window's strings.** The answers a choice
  window offers and the free strings (catchphrases, the names of animals,
  fish and insects, the date's words) are two more banks of the same
  shape (`m_choice_main.c`: index 0xD06000, text 0xD05000, 460 entries,
  at most 10 bytes; `m_string.c`: 0xD18000/0xD16000, 1,562 entries, at
  most 64), and the GameCube's `select_data` and `string_data` keep their
  numbering too (N64 37/38 あってるよ/ちがってるよ! are its "That's
  right!"/"That's wrong!", 'だニ' is 'kittycat'): `tools/af_text.py
  --bank choice|string` drafts them entry by entry (460 of 460 and 1,455
  of 1,562 have GameCube text) and compiles them into four more plain
  segments (six dmadata entries in all, 0x60 of the 0xF0 padding). The
  English answers run to 19 bytes and 238 of them pass 10, so the en
  build's `Choice_CHOICE_STRING_LEN` is 24 and the reader's cap follows
  it; one free string, 1372 (the door note of an animal who is out, four
  16-cell lines on the N64), is 88 bytes on the GameCube and gets our own
  64 in `script/string/`. The window's free and item strings grow from 10
  to 32 bytes (`mMsg_FREE_STRING_LEN`, `mMsg_ITEM_STRING_LEN`): the
  struct no longer fits the cartridge's 0x2F0 bytes of asm bss, so the en
  build's window and choice strings are C globals (`mMsg_window`,
  `mMsg_choice_str`, in code_en) and the four functions that never
  matched the cartridge (`func_8009E558_jp`, `func_800A03B0_jp`,
  `func_800A0DF4_jp`, `func_800A223C_jp`) are built from their C there
  (`#define NON_MATCHING` in `m_msg_main.c`). The asm elsewhere reaches
  both windows through `mMsg_Get_base_window_p` (675 calls) and
  `mChoice_Get_base_window_p` (155), almost always to hand the pointer to
  a C function; it reads a field by offset once (0x186, a font colour,
  `ovl__00814FA0`). So the structs keep every original field where it
  was: the window's 10-byte arrays stay as `legacyFreeStr`/`legacyItemStr`
  and the 32-byte ones are appended after 0x2F0 (the window is 0x610),
  and the choice window's answers live in globals of their own
  (`mChoice_en_strings`, `mChoice_en_determination`) instead of its
  struct. With the three banks and the names in (and the literal below
  fixed), the English ROM plays the whole route like the cartridge: name
  dial 3289, town 4120, station 9300, houses 12176 (the cartridge:
  2414/2975/8154/11030; English pages take a press more). A probe that seemed to show the choice window
  missing was holding A (CLAUDE.md, emulator traps); the window really
  was missing, though, for another reason: `mChoice_Add_choice_data`
  guards its length with a literal `< 11` that renaming the constant did
  not reach, so the English answers (13 bytes) were never added, the
  window opened with no strings and resolved itself at once, and Nook's
  questions later hung the game (frame 11052). Measured from RAM: the
  loaded answers were in `mMsg_choice_str`, `stringLens` all zero. When
  a constant grows, grep the number. With the literal fixed, a probe that
  watches the choice window's `isWindowVisible` in RAM (Choice + 0xA4)
  finds both of the intro's choice windows with the English answers
  ("I'm ready to go!" / "Before I go...", 16 and 14 bytes; "That's
  right!" / "That's wrong!"), drawn in the yellow balloon over the
  question (the full-width font makes the balloon wide: phase 5).
- **Dates and times, the GameCube's way.** The cartridge's STR_YEAR..STR_SEC
  write the number and a unit word from the string bank (年, 月, 日, 時, 分,
  `mString_Load_*StringFromRom`); with the GameCube's words in those slots
  Rover's question read "p.m.OH:1Min on Satu 4Mon 14Da, 2001Ye?". The
  GameCube builds them in code (`m_string.c`: the year, the month's name
  from string 0x66D on, the weekday from 9, the day's ordinal from 0x64E
  on, the hour 1-12, the minute in two digits) and has a separate
  STR_AMPM (code 0x76). The en build: the string bank runs to 0x679
  entries so that it holds those names and ordinals (`af_text.py --count`;
  the reader's cap follows), the message substitutions use new builders
  in the GameCube's formats with 16-byte buffers (`mString_en_*`), and
  STR_AMPM rides on code 71 (LUCK_6 on the cartridge, used by neither
  bank: its handler copies "a.m."/"p.m." in the en build; the compiler
  writes 71 where the bank text says `<STR_AMPM>`). The old builders keep
  their byte limits, since overlays in asm call them with their own
  buffers (`ovl__00815B70`, `6C97F0`, `func_8095BA60_jp`...), and produce
  English inside them: "2001", "Apr" (the month name's first three
  letters, its standard abbreviation for all twelve), "14th", "12p.m.".
  Measured: Rover's question now reads "12:01 p.m. on Saturday, April
  14th, 2001?" (the emulator's clock starts at noon on 14 April 2001).
- **The animals' names** are 6 bytes on the N64 (`PLAYER_NAME_LEN`, used
  as `ANIMAL_NAME_LEN`), read from the file at vrom 0xE04000 at 8 + 6n
  (`mNpc_LoadNpcNameString`); the GameCube's `npc_name_str_table.bin` is
  236 names of 8 bytes, its name n + 4 being the N64's n (Bob/ニコバン,
  Olivia/オリビア, Kabuki/かぶきち, Monique/ジェーン...). `tools/af_names.py`
  writes them into a copy of the segment that the en build uses as the
  segment's .bin: 232 names, 38 of them longer than 6 (Hornsby, Baabara,
  Cashmere...) and cut for now. Widening the field means the save data's
  layout: a decision for phase 5 or 6, noted here.
- **The letters are half the GameCube's size, in the save data.** The
  mail banks are read by `code/m_handbill.c`: three tables of 0x220
  (544) u32 ends — headers (`D_D12000`, text at `D_D11000`, entries under
  14 bytes), footers (`D_D15000`/`D_D13000`, under 19) and bodies
  (`D_D10000`/`D_D07000`, under 105) — plus `D_D1A000` (0xA970 bytes,
  `func_80093F94_jp`), and `m_field_make.s` reads three small files at
  `D_D58000`-`D_D5A000`. The letter itself is saved: `Mail_c`
  (`include/m_mail.h`) holds a header of 10 bytes (16 minus the 6 of the
  name), a body of 96 and a footer of 16, where the GameCube's holds 26,
  192 and 32 (its banks: `mail_data` 1,500 entries, `maila/b/c_data`,
  `ps_data`, `super_data`...). So the official English letters do not fit
  the cartridge's letters: either the save layout grows (every `Mail_c`
  in the houses' mailboxes, the pockets, the post office: the flash
  save's size and every struct that holds one), or the letters are
  rewritten to fit 96 bytes (about a thousand of them, our own text). A
  decision, not a measurement: noted for the user. The asm readers can be
  redirected without touching the asm: they use only %hi/%lo of the
  `D_Dxxxxx` symbols, which the en build can define as new segments'
  `_ROM_START` in its linker scripts.
- **Free strings the game does not fill.** The game fills a free string
  (STR_FREE0..19) for the message it is about to show; an official
  message that prints one the cartridge's own does not prints the slot's
  leftovers. Seen on screen: the cow at the start says "in" and fourteen
  あ (message 5106: the GameCube's "in <STR_FREE4>'s <STR_COUNTRYNAME>",
  where the Japanese has only the town). 64 official messages do this
  (`af_text.py check --reference` warns; the draft marks them `TODO
  adapt`): most are the GameCube's memory card and its slot (FREE3/4/5 in
  the welcome and copy messages 5106-5342 and 2383-2390), the rest
  another villager's name (FREE13, 9429-9430), the harvest moon's date
  (FREE16/17) and a few items and names. Wider: 104 messages mention the
  Memory Card where the cartridge has the Controller Pak and the
  cartridge itself. Adapting them is translation work of its own: rules
  applied to the official text at build time, or our own lines from the
  Japanese in `script/`, never the official text in the repository.
- **The letter banks, both games, by number.** The cartridge's
  (`tools/msgbank.py` N64_BANKS, byte-exact round trip): the shop's and
  the game's letters, 544 each of headers (`D_D12000`/`D_D11000`), bodies
  (`D_D10000`/`D_D07000`) and footers (`D_D15000`/`D_D13000`), cut by
  their loaders to 10, 96 and 16 bytes (`func_80093B28_jp`,
  `func_80093DA8_jp`, which pads with newlines, `func_80093C98_jp`); and
  the villagers' letters, five sub-banks of the file at 0xD1A000 read by
  `func_80093F94_jp` through `D_801071A4_jp` (caps 13, 104, 104, 104,
  18), `D_801071B8_jp` (tables) and `D_801071CC_jp` (texts, as segment-6
  offsets): header, three body parts and a footer, 384 entries each. The
  disc's (`forest_1st.arc`): `super_data`, `mail_data` and `ps_data`
  (982 entries: headers, bodies, footers) and `superz_data`,
  `maila/b/c_data`, `psz_data` (384; ac-decomp's `m_handbill.c` names
  them, and maps one to one onto the N64's functions). The GameCube kept
  the numbering: all 544 shop letters and all 384 villager letters are
  the same letter at the same number (511 of the 544 bodies have the same
  free strings; the other 33, the same letters with the date in other
  slots; the villagers' footers, "<name>より", are "From <name>" and
  the like). The GameCube's longest entries: header 23 bytes, body 177
  (490 of the 544 pass the cartridge's 96), footer 30; the villagers'
  parts 20, 62, 90, 32. `make script` dumps both, drafts `en_mail_*.txt`/`en_vmail_*.txt`
  and checks them (0 errors; 6 bodies and 5 villager openings print free
  strings the cartridge's letter does not: to adapt). Trailing newlines,
  which letters have, are written `{cd}` in the dumps.
- **How the loaders read them** (`src/code/m_handbill.c`, matching since
  it moved to C). A shop letter's header is DMA'd into 0x1D bytes on the
  stack, copied without its one newline (`func_800939B8_jp`: the
  newline's position is where the player's name goes,
  `header_back_start`; with no newline or two, the end) into 0x17 bytes,
  free strings put in (`func_80093520_jp`), and cut to the caller's size;
  the footer is DMA'd into 0x28 bytes on the stack and padded with
  spaces; the body through `B_80140748_jp` (0x78 bytes), cut to 0x60 and
  padded with newlines (0xCD). Entries are found by their table's u32
  ends (entry n: from end n-1 to end n), read eight-aligned. A villager's
  letter (`mHandbillz_load`, the `HandbillzInfo` of `m_handbill.h`) is
  five lookups in the file at `D_D1A000`, each part's table and text
  given as segment-6 addresses (`D_801071B8_jp`, `D_801071CC_jp`) and
  read two-aligned; a part longer than its cap (`D_801071A4_jp`: 13,
  104, 104, 104, 18) fails the whole letter, as does a body whose three
  parts pass the caller's `mailBufSize`; the three parts are joined in
  the caller's buffer and padded with newlines. All of these take the
  caller's sizes, so longer letters need the callers' buffers and the
  caps, not new loaders.
- **What is left to translate ourselves.** Of the 969 messages with no
  official text (709 removed, 260 reused), 287 are spare placeholders
  (よび, "spare"), 211 the debug and test texts of numbers 0-210, 78 have
  only codes; 393 are real lines, about 23,500 Japanese characters (the
  moving-in talk, letters' replies 7167-7230, a few shop lines...). Plus
  107 free strings. They go in `script/` as our own translation.
- **What the first English ROM shows** (screenshots in the scratchpad):
  K.K.'s intro and Rover's questions in English, typed in the Japanese
  font's full-width cells (12 px per character: "The font in the en
  build" below), so an English line is cut at about 16 characters (the
  window's 192 px); the NPC name tag is still Japanese (the
  names are another bank). The game's flow is intact: pressing A every
  25 frames, the name dial opens after 113 presses (frame 3289) against
  the cartridge's 78 (frame 2414), the difference being English pages
  that take two presses (one ends the typing, one turns the page). The
  "edited" messages of the train intro (1124, 10950, 1127, 10953) differ
  from the N64's only in pauses, the date's field order and an
  expression code, so taking the GameCube's codes there was safe. That is phase 5's work: a proportional Latin font
  (done with the cartridge's own glyphs: "The font in the en build"), the
  name and item banks (`D_D16000/D_D18000`, `D_D05000/D_D06000`, the NPC
  names).

## The compressed save (phase 4, the letters' first step)

The official letters need more room than the cartridge's save gives them
(above: header 26/body 192/footer 32 against 10/96/16). The user chose to
keep the save's layout and both flash copies, and to store each copy
compressed, with room after it for the letters' extension (the extra
text, kept in RAM by the en build: `mFRm_en_ext`, up to 0x2400 bytes;
what goes in it: "The letters in the en build").

- **The cartridge's flash save** (`src/code/m_flashrom.c`, in C and
  matching but for six functions, decomp/matching.patch): the first
  0xF980 bytes of `common_data`, in one of two slots of 0x200 pages of 128
  bytes (pages 0 and 0x200), a header (`mFRm_chk_t`) holding "NAFJ", the
  town's id, the time and a checksum making the 16-bit sum zero. Four
  ways in: the boot check (`func_8008F24C_jp`, asm, from the trademark
  screen: both slots read and compared; a bad one is repaired from the
  good one by `func_8008F530_jp`..`F648`), the normal save
  (`func_80090044_jp`'s states: copy, stamp, write slot 0, read back,
  write slot 1, read back; from `m_cpak.c` and the train), the slot
  writer (`func_8008F1BC_jp`: one slot from a buffer the caller fills and
  stamps; the gyroid's save and quit, `Npc_Restart`, and the station,
  through `code/6B8F20.s`), and the save menu's synchronous write
  (`func_8008F7C8_jp`: erase the chip, write slot 0 page by page;
  `ovl_save_menu`, and `Animal_Logo` at a new game). Every read goes
  through `func_8008F8A0_jp(dst, page)`; the game loads with
  `func_8008F968_jp` (title) and `func_8008F938_jp` (new game).
- **The buffers they use.** The normal save's and the gyroid's buffer is
  not malloc'd: `func_800D97A0_jp` asks graph for the framebuffer it is
  about to stop showing (`graph.c`, `cfbinfo.c`; any size under 0x25800,
  320x240x2) and the screen holds the last frame while the save runs.
  The save menu's write has no buffer at all (it writes from
  `common_data`).
- **The en build's slot image** (decomp/changes.patch: `m_flashrom.c`,
  `include/af_lz.h`; `tools/aflz.py` is the same in Python): a 20-byte
  header ("AFZ1", the save stream's size, the extension's raw and
  compressed sizes and the sum of its bytes, big-endian), the save stream,
  the extension stream, zeros to 0x10000. The compressor is LZSS in
  Yaz0's encoding, greedy, hash chains of 32 steps inside a 4 KB window,
  16 KB of work area; the C and the Python produce the same bytes (a host
  build of the header against the Python, in `make test`). When the two
  streams do not fit in 0x10000 the slot gets the cartridge's format (the
  save as it is). Reads take both formats: a slot without "AFZ1" is the
  cartridge's, so the cartridge's saves load in the en build (measured:
  a town written in the cartridge's format is loaded and played). The
  cartridge cannot read the en build's compressed slots.
- **Where each path finds its memory.** The borrowed framebuffer
  (0x25800 bytes) holds the image, the save's copy and the work: 0x23980
  bytes, image first for the normal save (its states rewind to
  `work->base`), the caller's copy first for the gyroid's. The save menu
  takes them from the game state's heap when it has room (the save
  menu's state has its megabyte nearly free) and writes the cartridge's
  format when it has not, which is the new game's case (play holds the
  whole heap; nothing has an extension yet). Reads decompress straight
  from the flash a page at a time through a 128-byte buffer on its own
  cache lines (`sFRm_ReadPage` invalidates them); the boot repair copies
  the good slot's image as it is (0x200 pages into the trademark screen's
  64 KB buffers); the read-back checks compare the written pages with the
  image instead of a checksum. A damaged stream leaves the rest of the
  save 0xFF, as an erased chip reads, so the checksum fails as it would.
- **Measured** (`tests/emu_save.py`, `make save-check-en`: a hook built
  with the decomp's IDO into the hole `m_choice_main`'s text left in code,
  called each frame from `mTM_time`, drives the game's own functions from
  the houses' state): the save compresses from 63,872 bytes to about
  3,700; with 3,000 bytes of extension the image is 4,342 bytes of the
  slot's 65,536. The normal save takes 31 frames, the gyroid's 32; both
  leave both slots compressed, the save as stamped and the extension
  whole, and the load brings both back. The save menu's write is
  compressed where the game state's heap has room (called on the
  trademark screen at frame 100, its megabyte nearly free; play has taken
  the whole heap by frame 200) and falls back to the cartridge's format
  in play; both erase slot 1 as the cartridge does. A fresh boot with slot 0 compressed and slot 1 erased
  repairs slot 1 with slot 0's image by frame 400, loads the save and the
  extension, and the save the game makes by itself on arriving keeps the
  extension in both slots.

## The letters in the en build (phase 4, the letters' second step)

- **The banks.** `tools/af_text.py compile-letters` writes the eight
  letter banks (the English drafts of `make script`, or the cartridge's
  own without the disc) as one text file and one table of u32 ends, two
  plain segments after code (`mail_en_text`, `mail_en_index`; dmadata now
  has eight entries of the translation's and seven of padding). The banks
  follow one another in the table (shop headers from 0, bodies from 544,
  footers from 1088, then the villagers' header, three parts and footer
  from 1632, 2016, 2400, 2784, 3168), each entry running from the end
  before it, so the cartridge's way of finding an entry (an odd number
  reads two ends, an even one four, eight-aligned) works on the whole
  table. Caps are the en build's (LETTER_CAPS: 32, 192, 32; 32, 96, 96,
  48, 32): the GameCube's longest entries are 23, 177, 30 and 20, 62, 90,
  32. The English banks are 0x24700 bytes of text, the cartridge's
  0x13070.
- **A letter's only codes are the free strings.** The expander
  (`func_80093478_jp`, `func_80093520_jp`) looks a code up in
  `D_80107020_jp`, which has handlers for STR_FREE0..19 and nothing else,
  and on any other code returns without moving on: the game would hang.
  `af_text.py` refuses any other code in a letter; none of the official
  letters has one.
- **The loaders** (decomp/changes.patch, `src/code/m_handbill.c`) read
  the en segments and make the whole letter at the GameCube's sizes in
  `mHandbill_en_letter` (header 24 bytes and its name position, body
  192, footer 32: ac-decomp's MAIL_HEADER_LEN, MAIL_BODY_LEN,
  MAIL_FOOTER_LEN), and give their callers the first bytes as the
  cartridge did, so every caller (the mother's letters, the shops', the
  events', the villagers') and `Mail_c` (10, 96, 16) stay as they are.
  The header goes through a 43-byte buffer, as the GameCube's does; the
  villagers' body is the three parts joined, failing past 192 bytes
  (none can: the longest parts make 184).
- **Measured** (`make letters-check-en`, `tests/emu_letters.py`: the
  loaders called from a hook in the houses' state, twenty known free
  strings written first, against a Python model of the cartridge's
  expander over the en build's own files): all 544 shop letters and 60
  villagers' letters come out byte for byte, in the callers' buffers and
  whole; 333 of the shop letters' parts put free strings in, and 492 of
  the 544 bodies run past the letter's 96 bytes.
- **The rest of each letter lives in the extension** (`mFRm_en_ext`,
  saved and loaded with the save: "The compressed save"). When a loader's
  entry point (`mHandbill_Load_HandbillFromRom`, `...Rom2`,
  `mHandbillz_load`) has made a letter, what lies past the 10, 96 and 16
  bytes `Mail_c` keeps (header up to 24, body up to 192, footer up to 32,
  padding left off) is filed under a key made of those 122 bytes (FNV-1a),
  so every copy of the letter finds it: the game moves letters by copying
  `Mail_c` (mailbox to pocket, post office to mailbox). A letter with
  nothing past them is not filed. A letter the player writes is filed the
  same way when it is finished, its body's rest past 96 bytes
  (`mHandbill_en_BoardSent`: "Writing a letter in the en build").
  `mHandbill_en_GetLetter` gives a letter back whole, for the letter
  screen. Before every write (`mFRm_en_Pack`,
  the one place all four write paths build their image) the records no
  letter of the save holds are dropped; when a new one does not fit, the
  same collection runs on `common_data`, then the oldest go. The save's
  letters are 87: 4 pockets of 10 (`PrivateInfo.mail`), 4 mailboxes of 10
  (`mHm_hs_c.mailbox`), and the post office's, which the decomp has not
  named: 5 kept letters at `common_data + 0xEF6C` (`code/6DA460.s`
  `func_800B67C0_jp` copies into them with `mMl_copy_mail`) and two at
  `0xF2A0` and `0xF344` (`mPO_receipt_proc`). The layout: "LX", then
  records, oldest first (key, three lengths, the rests); an extension
  without the tag is left alone and read as empty. Two letters with the
  same 122 bytes and different rests share a record (the later one's).
- **What it holds.** A record is 7 bytes and the rests: the English shop
  letters' average 63 bytes (502 of the 544 have a rest), the longest
  101; the villagers' 57 and 85. 87 letters of the longest make 8.8 KB,
  so the cap went from 0x2000 to 0x2400.
- **Measured** (`make letters-check-en`, against a Python model of the
  records): on its way to the houses the game itself files 5 letters'
  rests, of which only one is still held, in the post office's letter at
  `0xF344` (the shop's flyer); the other four are nowhere in RAM any
  more, so the next save drops them. From there, 604 more letters filed
  past the cap leave exactly the model's records; letters loaded into the
  save's pockets as the callers load them are filed, a copy in a mailbox
  gets its whole letter back, a letter outside the save is dropped by the
  next save, both flash slots hold the others' rests, a letter cleared
  from the save loses its rest at the save after, and the load brings the
  extension back. The letter screen still shows only `Mail_c`'s bytes:
  that is the next step.

## The font in the en build (phase 5, first step)

- **The sheet.** The font is the file at vrom 0xBCD000 (0x62E0 bytes,
  stored uncompressed in the cartridge, at 0x9BAEE0). `func_80090188_jp`
  loads 0x6000 bytes from offset 0x128 into `B_8013A680_jp`; the code
  spells that offset `int_sum_clchest03_hikiura_txt + 8 - 0x06000000`,
  another symbol of the same segment (0x06000120), not a font one. It is
  a 192x256 I4 sheet of 256 characters, 12x16 pixels each, sixteen to a
  row: character c at (c % 16 * 12, c / 16 * 16). The Latin letters sit
  near the middle of their cells, one to five empty columns on the left
  (A in columns 2-10, i 5-7, m 1-10; measured, `tools/af_font.py`).
- **Widths.** A character is drawn from the left edge of its cell,
  12 - cut pixels wide (`func_8009167C_jp`, `func_800917C8_jp`: the
  rectangle is cropped on the right, the pen moves on by the same width),
  where cut is `func_8009028C_jp(c, flag)`: the character's entry in
  `D_80106AF4_jp`, a 256-byte table, when the flag is set, else 0. The
  table is all zeros on the cartridge, and its only reader is
  `func_80090140_jp` (two more callers, `func_80091094_jp` and
  `func_80091104_jp`, are called from nowhere). The flag is bit 0 of the
  character's flags (byte 5 of the drawing struct, from bit 0 of the
  sentence flags in `func_80091AB0_jp`): the message window passes 0x40
  and 0x58 (`m_msg_main.c`), the choices and the name plate pass FALSE
  (`m_choice_main.c`, `func_800A2BB0_jp`), and so does every width they
  measure with `mFont_GetStringWidth`, which rounds a width up to even.
  The cartridge draws everything monospaced.
- **Matching.** `code/6B3DC0` is now `src/code/m_font.c` (the GameCube's
  name: its functions are mFont_*), the 57 functions not needed here as
  GLOBAL_ASM, its rodata (0x73ABD0: four DMA debug strings, two floats and
  a jump table into the file) attached to it, and the width path in C:
  `func_80090120_jp`/`func_80090130_jp` (the loaded flag),
  `func_80090140_jp`, `func_80090178_jp`, `func_8009028C_jp`,
  `mFont_GetStringWidth`. IDO converts a `u8` to float as unsigned (an
  extra add of 2^32 for negatives) unless it is cast to `s32` first; the
  string width matches only with the character's width in a temporary
  declared inside the loop.
- **The en build** (`decomp/changes.patch`, `m_font.c`) reads its widths
  from `mFont_en_cut`, which includes `assets/jp/en/font_cut.inc`, and
  `func_8009028C_jp` uses them whatever the flag: measuring and drawing
  both go through it, so the name plate stays centred and the choice
  window fits its lines. `tools/af_font.py` makes the table and the en
  build's copy of the sheet from the user's dump at build time (`make
  rom-en`): each Latin character moved to the left edge of its cell, its
  width its inked columns and one empty one (3 to 12 pixels), the space
  4; kana, kanji and the marks keep 12 and their cells, so Japanese draws
  as before. On screen (the opening, K.K. and Rover on the train, the
  date question's choices): proportional English, nothing cropped, the
  name plate centred, the choice window as wide as its answers.
- **How the English fits** (the GameCube's lines at these widths, a free
  string counted as 48 px): of 71,713 English lines, 2,640 (3.7%) are
  wider than the window's 192 px and 417 wider than 200; with two empty
  columns between letters it would be 28%. The GameCube's own font is
  narrower (its line of "I never could sell that thing at the shop...",
  44 characters, is 276 px here). So the en build now takes the
  GameCube's glyphs when the disc is there (next item).
- **The GameCube's Latin characters** (`tools/af_font.py --gc`, `make
  script` reads them: `af_font.py gc ISO`). Animal Crossing draws a
  character as the N64 does, from the left of a 12x16 cell, 12 pixels
  less its entry in a cut table (`mFont_GetCodeWidth` in ac-decomp's
  `m_font.c`, a map), and its charset has the N64's Latin characters at
  the same codes (tools/msgbank.py `GC_CHARS`, `N64_CHARS`). The disc
  ships `foresta.map` (CodeWarrior's map, CRLF lines) beside
  `foresta.rel.szs` (the game's REL module, Yaz0: `gciso.yaz0`): the
  map's `.data` holds `FONT_nes_tex_font1` at 0x204700 (0x6000 bytes:
  192x256 I4 in the GameCube's 8x8 tiles, 32 bytes a tile, tiles left to
  right) and `m_font.o`'s `offset_data$396` at 0x6D98 (256 cuts); the
  map lists its sections in the module's order and its `.data` ends at
  0xADF4F8, the size of the module's section 5 (at 0x2DD340), as `.text`
  and `.bss` match sections 1 and 6. `af_font.py gc` finds both by name,
  checks that extent, decodes only as far as it needs (0.4 s), untiles
  the sheet and writes it and the cuts to `build/gc/gc_font.bin` (never
  committed); the en build's sheet then has the GameCube's glyphs and
  cuts for the 79 Latin characters, the space (5 px) and the marks the
  English text shares with it at the same code (♥ ♪ ー, and ～ ☺ for
  the GameCube's ~ and 😃, as `af_text` compiles them); kana and kanji
  stay the cartridge's. Its letters are 4 to 11 pixels wide, a column
  of faint ink (1 to 5 of 15) at each edge of most. Counted as above
  (here 96,230 lines of the dump), the lines wider than 192 px go from
  3,764 (3.9%) to 143 (0.1%), 90 of them wider than 200; the message
  layout (next item) now moves lines on 94 pages, fills 10 anew and
  splits 4. On screen: Nook's lines in the GameCube's letters, the
  route on the same frames. Without `build/gc/gc_font.bin` the build
  keeps the cartridge's realigned glyphs.
- **The lines laid out to the window** (`tools/af_text.py --font`, in
  `make rom-en`: layout()). A page whose lines all fit 192 px is left as
  it is, and so is Japanese text; else a long line's last words go on to
  the next line of text, cascading; when that would make a fifth line the
  page's text is filled anew; when that does not fit four lines the page
  is split after the last sentence that does, with `<BTN>`, a newline and
  `<MSGCLEAR>` as the cartridge's pages end (a `<BTN>` the text had at the
  cut is not doubled). What the game fills in counts as letters of the
  font's average lowercase width (7.3 px): names 6 and the catchphrase 4,
  their fields' sizes (`PLAYER_NAME_LEN`, `ANIMAL_CATCHPHRASE_LEN`), the
  date's parts as the en build writes them, anything else 8
  (TODO(verify)). On the English bank: 1,934 pages with words moved on,
  232 filled anew, 372 split (304 of the cuts at a sentence's end, 68
  inside one); 3 lines stay wider than the window (one long word, or free
  strings). (Those counts are with the cartridge's realigned glyphs; with
  the GameCube's, above.) Most of it is a few pixels: at 196 px the splits would be 149,
  at 200 px 43; a space of 3 px instead of 4 gives 146, but words run
  together in these bold letters. On screen, K.K.'s "Still, if you've got
  some / really tight friends somewhere / ..." ran into the window's edge
  and now ends inside it, the continue mark clear; the route reaches its
  milestones on the same frames.
- **A section can change and keep its size.** The en `func_8009028C_jp`
  is 12 bytes shorter; `m_font.o`'s text, rounded to 16 bytes, stayed
  0x27A0, so `tools/af_relink.py` (which compared sizes) left it in place
  unpadded with every function after it 12 bytes earlier, and
  `af_shiftcheck` refused the link (113 symbols moved). `af_relink` now
  also compares the symbols inside each section and treats a section of
  the same size whose symbols moved as shrunk (in place, its symbols
  free).

## The letter board in the en build (phase 4, the letters' third step)

- **The board on the cartridge** (`board_ovl`, the submenu program 12,
  `src/overlays/submenu/board_ovl/m_board_ovl.c`). Its state is
  `mBD_Ovl_c` (0xC0 bytes, the overlay's bss, `B_8088AC00_jp`: the field
  being written, the header's name length, the three fields' lengths,
  a copy of the letter, where to put it back, offsets); the submenu's
  `mBD_MenuInfo` for it (`menuInfo[12]` of the submenu overlay, at
  0x10088) carries the caller's arguments: data0 0 to write a letter, 1
  to read one, else to answer one; data1 the paper's pocket; data2 the
  letter (`m_submenu_ovl.c` copies `mSM_open_submenu_new`'s arguments
  there). The init (`func_8088A2D0_jp`) copies the letter, measures the
  fields (`mMl_strlen`: the length less trailing spaces) and takes the
  footer's leading spaces out; the header, body and footer are drawn by
  `func_80889CD8_jp`, `func_80889A9C_jp` and `func_808899E4_jp` with
  `func_80090E98_jp` (the line drawer), monospaced: the body in lines of
  16 characters (a newline ends one early), the footer ending at 16
  characters, the header with the recipient's name drawn red at
  `headerBackStart`, or as it is for a letter of mail type 2, 3 or 5.
- **In C** (decomp/matching.patch): the init, the body and the footer
  match (`make verify`: the cartridge, byte for byte); the header is C
  behind `NON_MATCHING`. Matching needed the paper's index as
  `(pockets[data1] - 0x2000) & 0xFF`, the name's length declared at the
  top of its block, a `u8 tmp[20]`, and in the body a `//! FAKE` empty
  `if`. The splat yaml cuts the overlay as C with its data, rodata and
  bss in the same object (`[0x7908A0, c, ...]`): fado sees an overlay's
  bss relocations only in the object that holds the bss.
- **An overlay's relocations** (`tools/af_ovlreloc.py`, run by `make
  rom-en` after the first link). The table the loader applies
  (`DoRelocation`) is the segment `NAME_reloc`: the text, data, rodata
  and bss sizes, a count, then one word per relocation (section in the
  top 2 bits, ELF type in the next 6, offset below), zeros, and a last
  word. The decomp takes it from the cartridge; an overlay whose code
  changes needs its own. `af_ovlreloc` first proves zeldaret's `fado`
  gives the cartridge's table from the matching build's objects (board:
  the same 52 relocations; a HI16 with its LO16s compared as a group, in
  any order), then writes fado's table for the en objects in the
  cartridge's layout (its sizes, its length, its last word), refusing
  an overlay that grew or needs more room. `af_relink` pads a shrunk
  overlay's sections back to the cartridge's sizes in place and refuses
  one that grew, so no overlay moves.
- **The en board** (decomp/changes.patch: `m_board_ovl.c` forwards to
  `m_handbill.c`, `m_board_ovl_en.h`). Reading a letter, the init also
  calls `mHandbill_en_BoardLoad`, which fetches the letter whole as the
  save keeps it (`mHandbill_en_GetLetter`: header 24, body 192, footer
  32; NOTES, "The letters in the en build"). The header is drawn with
  the name put in at its position (or as it is, types 2, 3 and 5); the
  body is broken into lines at the board's 192 pixels after the last
  space that fits, its own newlines kept, over its six lines, the rest
  not drawn (the GameCube's board: `mBD_MAX_WIDTH` 192,
  `mBD_BODY_LINE_NUM` 6, in ac-decomp's `m_board_ovl.c`, a map); the
  footer ends at the body's right edge. Writing a letter: next section.
- **On screen** (`tests/emu_board.py`, `make board-check-en`). From the
  houses' state of route-en, a hook loads a letter into the first pocket
  letter as the game's callers do and opens the board as
  `mSM_submenu_ctrl` opens a program; nine shop letters (the longest
  bodies, the longest footer, a notice of type 2 and the same as type
  4, indented lines, two that run past six lines with the free strings
  set) and two villagers' letters. Each band's first and last text pixel
  is where a model of the board puts the first and last characters'
  inked columns, within 2 frame pixels, on all 88 bands of the 11
  boards; the model fails 29 bands with its lines broken 20 px early
  and 71 with a space 1 px wider. The text's pixels are told by colour
  (red over blue over green; the postmark has blue and green equal, the
  ruled lines blue over red): the board's thin strokes are resampled
  (next item) and come out lighter than the postmark (an "l" at r+g+b
  401-414, the postmark's darkest 386).
- **The board draws its text about 2% narrow.** The message window's
  text lands at 2 frame pixels to the pixel (Nook's first two lines: the
  model's (130, 365) and (130, 423), measured (130, 364) and (130,
  422)); the board's at 1.954, around the screen's centre (fitted on the
  88 bands, residuals within 1.7 frame pixels), vertically exact (the
  lines 16 rows apart). It is the cartridge's own call
  (`func_80090E98_jp`, mode 0, scale 1.0, as the matching board passes
  it), so Japanese text on the cartridge's board is drawn the same way.
  The play's viewport is the whole screen (vscale 640, 480) and the
  font's projection 16 units to the pixel (`mFont_SetMatrix`:
  `guOrtho(-2560, 2560, -1920, 1920)`); where the 2% comes from is
  TODO(verify).
- **What it fixed.** With the cartridge's realigned glyphs, 62 lines of
  the GameCube's letters (in 60 letters) were wider than 192 px before
  any free string, and 56 to 60 shop letters ran past six lines with
  short free strings (a word carried to a line of its own). With the
  GameCube's glyphs no line of the eight letter banks without a free
  string is wider than 192 px (the widest, a body's, 184); with
  short free strings 1 to 3 shop letters reach a seventh line (430: the
  GameCube's own body has seven lines, its signature on the seventh;
  503 and 508 when an acre's free strings are names).

## Writing a letter in the en build (phase 4, the letters' fourth step)

- **The editor** (submenu program 10, `ovl__0078CB80`: text 0x33C0, data
  0x5A0, rodata 0x50, bss 0x30 bytes). The board opens it to write
  (`func_8088A2D0_jp`; first the address book, program 13,
  `ovl__00792700`, which lists the villagers who remember the player:
  `mNpc_GetAnimalMemoryIdx` over each one's seven memories at +0x10).
  Its state is the whole bss (`B_80888AF0_jp`, `mED_Ovl_c`): the stick's
  area at 0, the dial's mode at 4 (Z cycles hiragana, symbols, katakana,
  capitals, digits), its ring at 5, the cursor's fade at 0xC, the command
  at 0x11, the code to type at 0x13, the cursor's index at 0x16,
  characters a line at 0x18, lines at 0x1A, the text's length at 0x1C,
  the column and line at 0x20/0x22, the text at 0x24, the end mark's and
  the cursor's draw procs at 0x28/0x2C (`func_8088587C_jp`, the init:
  the lines from `D_808885F0_jp` by the text's kind, 6 for a letter and 6
  for kind 2, a notice; 16 characters a line for a letter's body).
- **Its lines** are characters: `func_80885AEC_jp` gives the column and
  line after n characters (a line ends at its 16th character or a
  newline; past the last line the cursor stays at its end),
  `func_80885A74_jp` the index from them, `func_80885CBC_jp` a line's
  length. A character is typed (`func_80885DD4_jp`) only if the text is
  shorter than its characters a line times its lines (16 x 6 = 96 for a
  letter's body, `MAIL_BODY_LEN`) and, the character put in at the cursor,
  still ends within the lines: it builds that text in a 100-byte buffer on
  its stack (from sp+0x3C to the frame's top, 0xA0) and lays it out with
  `func_80885AEC_jp`; `func_80885D2C_jp` puts the character in. Up and
  down (`func_80885F6C_jp`, `func_80885FCC_jp`) keep the column; down at
  the text's end types a newline, right a space (`func_80885EC8_jp`). The
  C buttons move the cursor (right 1, left 2, up 3, down 4), B deletes
  (`func_8088537C_jp`, the game's 0x400F). A letter's header and footer
  are single lines over their own fields in the board's copy: up out of a
  field or down out of it (`func_80886588_jp`, not while the cursor is on
  the header's name) the editor takes the next field's text, characters a
  line from `D_80888810_jp` (header 10, body 16 x 6, footer 16) and length
  from the board's `lengths`.
- **The finish** (`func_80889288_jp`, ac-decomp's `mBD_move_Obey`).
  START ends the editing; the board opens the edit-end check (program 15,
  its menu info the submenu's 16th, at +0x104C0): a balloon, then on A its
  two answers, done and back to writing. Its `data1` is the answer: 0, the
  letter is copied to its place (`mMl_copy_mail` to the board's `dst`),
  the footer put as far right as it was written, the header and footer
  remembered for the next letter (the private info's `savedMailHeader`,
  +0x3EE), the paper's pocket (the board's `data1`, -1 for none) emptied
  (`mPr_SetPossessionItem`) and the item window told
  (`tag_ovl`, the submenu's +0x106D0, its +0x10C), the board closed
  (`func_80889198_jp`); 1, the editor reopened on the body; else the
  board closed, nothing kept. Then the editor's cursor, column and line
  are zeroed.
- **The dial** (`func_808851D8_jp`): the stick's angle in eight areas, 0
  to 4 left to right over the upper half, 5 to 7 the lower half, 8 the
  centre. In mode 3 the code is the table's `ring + area`
  (`func_80885140_jp`, `func_80885690_jp`) over `D_80888594_jp`, 30
  entries: A to Z, comma, full stop, newline, space. The stick pushed
  down turns the ring a page of five (with A, two). Capitals only; the
  convert command shows the letter before the cursor's other case (N to
  n), so mixed case can be written.
- **The board's cursor** (`func_80889878_jp`): `64 + x + (col - 0.7) * 12`,
  six columns more after the name, the footer's counted from its 16th;
  the name's box (`func_8088973C_jp`) at `(headerBackStart + 3) * 12`. The
  bar (`func_808881C0_jp`, `mFont_SetMarkChar`) is red (195, 80, 80) at
  alpha `(10 - v) * 255 / 10`, v the step at 0xC counted to 10 and back.
- **In C** (decomp/matching.patch): the editor moves to C (its data,
  rodata and bss in the object, as the board's), the init, the three
  line functions, up and down, the typing test and the field switch
  match; so do the board's cursor (the GameCube's shape: the editor's
  pointer re-read for the call, `ofsY = line = 0.0f`) and its finish, the
  paper's pocket an unsigned field of the board's menu info (signed, it
  is loaded straight into the call's argument register: three
  instructions differ); the name's box is C behind `NON_MATCHING` (one
  reload in v0 where the cartridge's is in v1). `make verify`: identical.
- **The en build** (decomp/changes.patch). A letter's body is laid out
  one way, written and read: `mHandbill_en_BoardLine` (192 px, after the
  last space that fits, newlines kept). The editor's three line functions
  forward to `m_handbill.c` (the overlay cannot grow; code can), which
  lays the body out (the column and line of an index, over the whole
  text: a word that goes on can carry it to the next line) and keeps the
  cartridge's lines for every other text (the init records the kind) and
  for the header and footer. Up and down keep the cursor's x: the column
  nearest it (as ac-decomp's `mED_set_idxcol_inLineWidth` does with its
  pixel offset). The board's writing header, body (a line's newline mark
  shown while writing, as on the cartridge), end mark and footer are at
  the font's widths, the cursor at its text's width, the name's box at
  the name's middle. The bar is 3.6 px wide; the cartridge's offset puts
  its left edge 2.7 px before our characters' boundary (over the gap a
  Japanese glyph leaves in its cell), covering the last column: -7.5 px
  centres it. A new letter has the GameCube's "To " before the name and
  "from " before the player's (ac-decomp's `header_str`, `footer_str`; the
  cartridge's put the name first, then its Japanese "to", and the player's
  name, then "from": `D_8088ABB8_jp`, `D_8088ABBC_jp`). fado gives the
  cartridge's 212 relocations of the editor from the matching objects
  (`tools/af_ovlreloc.py`, `EN_OVERLAYS`); the en build's are 211, the
  board's go from 52 to 42.
- **The body is the GameCube's 192 bytes.** While a letter is written its
  body is `mHandbill_en_editBody` (`m_handbill.c`, in code), space-padded
  as the cartridge pads a Mail_c's. The board gives it to the address book
  (which hands the editor what it was given, with 16 characters a line:
  `ovl__00792700`, `a3 = 0x10`) and to the editor when writing goes on;
  the editor takes 32 characters a line for it (ac-decomp's
  `letter_table_col`, `MAIL_BODY_LEN / mBD_BODY_LINE_NUM`), the init
  measuring it over the 192 bytes where it measured 96, and the field
  switch points the body at it. The typing test moves to the letters'
  code (`mHandbill_en_EditRoom`, its own 193-byte buffer: 192 bytes and a
  character in the cartridge's 100 would run over its stack frame).
  Finishing, once the footer is in place the body's first 96 bytes go
  into the letter and the rest, trailing spaces left off, into the save's
  extension under the letter's key (`mHandbill_en_BoardSent`, as the
  loaders file an official letter's rest); a letter written before and
  opened to be written again (the board's `data0` 2) is taken up whole,
  spaces after it (`mHandbill_en_GetLetterPadded`), and read whole as any
  other.
- **On screen** (`tests/emu_write.py`, `make write-check-en`). From the
  houses' state, paper in the pockets and a villager who remembers the
  player, the board opened to write and the first address chosen; the
  letters typed on the dial as a player types them. A sentence of 66
  capitals is the body, the cursor at the model's line 2, column 15, the
  three lines the dial leaves visible where the model draws them, the end
  mark right after the text; five left and two up land at the model's
  places (up by the nearest x), the bar centred on the boundary (frame x
  267-273 around 270.3); a W typed mid-line and deleted reflows as the
  model does; the letter, finished and read on the board, is drawn with
  the model's lines, and the lines it shared with the writing screen
  alike. The text is measured on the frame of a
  fade cycle where the bar is faintest (faint over a ruled line's blue it
  passes for text; the frame shown trails the editor's step), the bar
  where it is strongest. Against a model breaking lines 20 px early, 6 of
  the 9 measured lines fail; with a space 1 px wider, 9.
- **The 192 bytes on screen** (the same check). Typing on keeps the text
  past 96 bytes (the sentence and 69 more, 135, whole) and stops where the
  model stops it, a character in only while the text one longer ends on
  the six lines (158 of 179 bytes: the letters of a word that would start
  a seventh line refused, spaces after them still taken). Finished (START,
  A, A; the hook opens the board without an item window, so the one the
  finish tells of the paper is the hook's stand-in), the letter holds the
  first 96 bytes and the extension the rest, 60 bytes, under the letter's
  key; read, its six lines are where the model has them. Opened to be
  written again, the board's body is the letter whole and the editor is
  on it, 32 characters a line, 156 bytes long (its trailing spaces left
  off, as the cartridge measures a body: `mMl_strlen`). Full stops and
  spaces, narrow, stop at the 192 bytes on five lines, and finished and
  read are whole too.
- **Left as it is.** The villagers read a player's letter in its first
  96 bytes (`m_npc.c`: `mNpc_CheckNormalMail_sub` over `MAIL_BODY_LEN`,
  `mMC_get_mail_hit_rate` and the mail-check overlay). Two written letters
  with the same first 96 bytes, header and footer share one rest (the key
  is made of those bytes): the later one's replaces the earlier's. The
  address book's two balloons (the book is empty; first choose who it is
  to) are still Japanese: they are strings in its own data, 12 bytes each
  (`ovl__00792700`'s `D_8088C888_jp`, `D_8088C894_jp`; not textures, as
  this note first guessed: "The menus' own words"). So is the edit-end
  check's question (`ovl__00799580`'s `D_808969F4_jp`); where its two
  answers come from is TODO(verify). The dial's labels are textures.

## The menus' own words (phase 5, the graphics' first step)

- **Neither banks nor textures.** Some of the Japanese the menus show is
  strings in the menus' own data, drawn with the font
  (`func_80090E98_jp`). A survey of the submenu overlays' data (labels
  that read as the N64 font's codes, kept local: CLAUDE.md, rule 2) finds
  them in the name entry (its five titles and the word after the
  destination: `ledit_ovl`), the map (its places: `map_ovl`), the clock
  setting (`ovl__0078AE30`), the editor (its way back:
  `ovl__0078CB80`), the address book (its two balloons:
  `ovl__00792700`), the gyroid's shop (`ovl__00794380`), the edit-end
  check (its question and its answers: `ovl__00799580`), the item menu's
  warnings (`ovl__0079A290`), an amount to enter (`ovl__0079B120`), the
  town tune's (`ovl__0079C020`, program 8), the birthday (`ovl__0079DA50`), the
  Controller Pak's messages (`ovl__0079F810`, `ovl__007A10E0`) and the
  item menu's words (`tag_ovl`). The GameCube keeps the same strings in
  its code, in English (ac-decomp's `mLE_player_title`,
  `mAD_title_str`, `mEE_str_table`, `str_omikuji` and others), named
  with their sizes in foresta.map's .data, local statics too.
- **The menus' art has no words.** The menus' shared segment 0x0C is
  `segment_00A94000` (0x185E0 bytes; the name entry loads it itself:
  `D_80884E38_jp` is its ROM start and end; `D_0C00xxxx` in the
  submenu's code). Its display lists draw 46 textures (each load's render
  tile and size): the windows' pieces, icons, arrows, none with a word.
  The GameCube's name entry draws the same pieces (`nam_win_w1_tex` to
  `nam_win_fuki_tex`, the same sizes) and its titles as strings.
- **The name entry** (`ledit_ovl`, program 4). Its table
  (`mLE_win_data`, 5 windows of 0x28 bytes: where the text typed and the
  title go, the title and its length, the text's length and colour, the
  window's two display lists in segment 0x0C) is ac-decomp's
  `mLE_win_data_c` without the scales. `func_80884514_jp` draws the
  title at 0.875 (`0x3F600000`) in white, then the text typed in the
  window's colour; for the destination (data0 1) it then draws three
  characters at the text's x + 75 in (145, 55, 0). The GameCube draws
  the title (at 0.875 too), the text and the cursor, nothing after
  (`mLE_ledit_ovl_draw`). The init (`func_80884B30_jp`) picks the text by
  mode (the private info's name, `common_data + 0x2F60`, a catchphrase
  in `B_80885088_jp`, data2; for a song it reads the songs' names into
  `B_80884E60_jp`), clears it and opens the editor (program 10, kind 3).
- **In C** (decomp/matching.patch): the overlay's data and bss, in one
  object. IDO and the assembler both pad a `.data` to 16 bytes, so the
  cartridge's 0x120 bytes split between a C object and splat's data
  moved (the table started 8 bytes late). The titles and the word after
  the destination are text, so tools/af_dumpdata.py writes them as C
  initializers from the user's dump at each extract, under the tree's
  `assets/` (git-ignored), and the C includes them. `func_80884B30_jp`
  reads `editMax` through the table's +0x18 (spelled so in
  `relocs/reloc_addrs-jp.txt`). `make verify`: identical. fado gives the
  cartridge's 37 relocations once the bss is in the same object too (it
  resolves only what its objects define).
- **The en build.** tools/af_ui.py reads the GameCube's strings named in
  its table from the disc (`gc`, run by make script into
  build/gc/gc_ui.txt; `af_font.gc_rel_data` reads foresta.rel's .data as
  the font is read) and writes the include the en build's menus use
  (`compile`, at make rom-en; the cartridge's own strings without the
  disc). The five titles live in code (`m_submenu.c`: the overlay cannot
  grow) and the table points there, with their lengths (7 to 18
  characters where the cartridge had 8 and 9). The three characters
  after the destination are spaces. The name entry joins `EN_OVERLAYS`
  (its 37 relocations become 32).
- **On screen** (`tests/emu_menus.py`, `make menus-check-en`). From the
  houses' state the hook opens the name entry in its five modes. Each
  window's table entry holds the en string in RAM; its title's ink (the
  window opened twice from the same state, the second time with the
  title's length 0 from the frame the overlay is in RAM: the frames
  differ by the title only) is as wide as the font's model at 0.875
  (a difference of 60 in the colours, summed, is ink). The model first
  took 2 frame pixels a font pixel and left out the VI filter's fringe;
  the two errors cancelled on these titles (next section): with 1.954
  frame pixels a font pixel and the fringe, 171, 185, 172, 68 and 160 frame pixels against 169.6, 183.2, 171.3,
  67.0 and 159.3. The
  destination draws nothing after the text. The route's
  name and destination screens show the titles on their windows' tabs.
- **Left as it is.** The dial's labels and its kana ring are textures
  the GameCube has no counterpart for (its editor is a keyboard,
  `kai_sousa_*`); the 2010 patch's English labels are used where the user
  has it (next section). The catchphrase's window shows corner quotes.
  The other menus' strings are the next step.

## The edit-end check and the address book (phase 5, the menus' strings)

- **The edit-end check** (`ovl__00799580`, program 15: the question asked
  when a letter, a post or a tune is finished, and its answers). Its
  windows' table (`D_80896950_jp`, four windows of 0x24 bytes: how many
  answers, the window's two display lists in segment 0x0C, where the
  answers' text, their window and the question go) is ac-decomp's
  `win_data` without its fifth window, with the same numbers. The
  question is drawn by `func_8089646C_jp` (mEE_set_question_character)
  with its length as an immediate, at the window's x + 96; the answers by
  `func_8089652C_jp` from a table of string and length (`D_80896A18_jp`)
  in the colours of `D_80896A30_jp`, as many as the window has (the
  letter board opens window 0, two answers: `func_8088920C_jp`; the
  bulletin board, the town tune and the item menu open it too).
- **The address book** (`ovl__00792700`, program 13: whom a new letter
  goes to, opened by the letter board as it starts one,
  `mSM_open_submenu_new(submenu, 0xD, ...)` in `m_board_ovl.c`). Its first
  balloon (`func_8088B9F4_jp`, mAD_set_first_tag) asks for an addressee,
  or says the book is empty, by a byte of the book's state (+2), 12
  characters either way, at x - 72 + 160: centred on the balloon. The
  GameCube's two strings are 20 and 27 characters, each at its own offset
  for its own balloon.
- **In C** (decomp/matching.patch; make verify: identical). The edit-end
  check's data, rodata and bss move to C in one object
  (`ovl__00799580.c`, new; its 14 functions as GLOBAL_ASM but the
  question's, which matches in C once y is moved in place: `y -=
  194.0f`, then `-y`). The address book's .data splits at its vertices'
  end (0x300 bytes, a 16-byte boundary): the vertices stay in splat's
  data, the rest is C (`ovl__00792700.c`, new; its balloon function
  matches in C with a pad word in its frame and the test for an empty
  book first). The four strings and the two balloons are text, so
  tools/af_dumpdata.py writes them from the dump at each extract. fado
  gives the cartridge's relocations from both overlays' objects, 37 and
  73, once `tools/af_ovlreloc.py` hands fado the address book's objects
  in an order that keeps each section's (its .text starts in the C
  object, its .data in splat's).
- **The en build.** tools/af_ui.py reads the GameCube's question and
  answers (`mEE_str_table`, `mEE_str_data0` to `2`) and balloons
  (`mAD_title_str`, `mAD_title_str2`); a string added to its table is
  read from build/gc's copies of the disc's module and map (`af_ui.py
  gc-files`; make script now keeps them), without the disc. The strings
  live in code (`m_submenu.c`): the answers' table points there and the
  question is drawn at the GameCube's x + 107 (32 and 69 relocations
  where the cartridge has 37 and 73). The balloon is drawn by a function
  in code (`mSM_en_adFirstTag`), because the overlay's code cannot grow by
  the instructions a choice of string, length and place takes: each
  string centred on the balloon as the cartridge's 12 characters are, and
  narrowed to their 144 pixels when it is wider (the empty book's 27
  characters, 170 pixels, at 0.847); the cartridge's own strings, without
  the disc, come out exactly where the cartridge puts them. Both overlays
  join `EN_OVERLAYS`.
- **On screen** (`tests/emu_menus.py`, make menus-check-en, from the
  houses' state). The check's answers' table, found in RAM by its first
  window, points at the en strings in code and holds them. Opened twice,
  the second time with the string's bytes in RAM made spaces, the frames
  differ by its ink only: the question is 133 frame pixels wide against
  the model's 132.9; after A, the answers, row by row (16 frame rows
  apart), 37 and 85 in window 0 and 37, 85 and 148 in window 1, against
  37.2, 86.0 and 148.5; the empty book's balloon, at 0.847, 277 against
  276.8. The screenshots show the question and both answer windows inside
  their windows and the balloon's text inside the balloon.
- **The frame is not 2 pixels a font pixel.** A test string of one
  letter repeated, written into the balloon's bytes, advances 11.69
  frame pixels a 6-pixel letter (and 10.27 at the name entry's 0.875):
  1.95 frame pixels a font pixel, as the board's check had measured
  (`emu_board.BOARD_X`, 1.954: the VI shows the 320 pixels over about 625
  of the frame's 640), not the 2 the name entry's check assumed. That
  check passed because the VI filter's fringe, a frame pixel on each side
  that its ink threshold counts, cancelled the error on short titles; the
  model is now 1.954 frame pixels a font pixel plus the fringe, and the
  name entry's titles measure 171, 185, 172, 68 and 160
  against 169.6, 183.2, 171.3, 67.0 and 159.3.

## The 2010 patch's labels (phase 5, the graphics' second step)

- **What it redrew.** The AF Project's 2010 patch (Zoinkity; make nafe
  builds it from the user's copy of NAFE-WIP-2_12_2010.ups) redrew some
  of the menus' word textures in English. Found by walking the display
  lists of every file it changed (58 files differ, read through both
  dmadatas and decompressed): for each texture a list draws (the image
  set, the load, the render tile's format, the tile's size), the
  cartridge's texels against the patch's at the same offset. 56 textures
  differ in 11 files: 44 in nine menu segments and gameplay_keep, each in
  its place, format and size (both ROMs' lists draw it alike, and those
  files keep their sizes: the text editor's dial, the inventory, the
  map, ovl__00789B60's mailbox, the bulletin board, the amount to enter,
  the town tune, the Controller Pak's notes, the catalog, and three in
  gameplay_keep), and 12 in the title's segment.
- **Taken at build time** (`tools/af_nafe_gfx.py`, run by make rom-en
  when build/nafe-2010-12-02.z64 is there; the cartridge's labels
  without it). Its table names 42 labels by segment, offset, format and
  size, and each is checked before it is written over the en tree's
  extracted segment (which splat rewrites at every extract): the
  cartridge's lists and the patch's both draw a texture there in that
  format and size, and the patch changed it. Nothing else of the patch is taken. Two small
  glyphs it also changed are not labels and stay the cartridge's (the
  bulletin board's 0xCE18, the notes' 0x7660, I4 16x16). Nothing of it
  enters the repository.
- **On screen** (`tests/emu_menus.py`, part of make menus-check-en). The
  hook opens each menu whose segment holds labels (program 1 the
  inventory, 4 the name entry and its dial, 5 the map, 6 the bulletin
  board, 7 the amount, 8 the town tune, 19 the Controller Pak's notes, 20
  the catalog: `mSM_program_dlftbl`), finds the segment in RAM by a
  stretch of the en tree's copy away from the labels, and compares each
  label with the patch's texels (or the cartridge's, without the patch):
  42 labels and the 8 segments and gameplay_keep found, 50 checks, on
  the same state as the name entry's. The screenshots show the labels in
  place. Program 11 (`ovl__00789B60`, the mailbox label) is opened by the
  inventory from inside it (`mSM_open_submenu(submenu, 0xB, 0, 0)`, in
  `m_inventory_ovl.s`) and hangs when opened on its own: its label is
  checked against the lists only.
- **Still Japanese in those menus.** The dial's two kana modes and its
  kana ring (the patch kept them; the en font keeps the kana, so they
  still type), the town tune's note names (kana textures, I4), the
  menus' strings (the amount's, the clock's, the birthday's, the
  Controller Pak's titles and their way out: strings in the overlays'
  data, previous section), the item names (a file of their own, 10 bytes
  each at vrom 0x10F4000: `mIN_copy_name_str`, `m_item_name.s`) and the
  bulletin board's text, which breaks its lines by count, not by width.
- **The title is more than textures.** The title's logo lives in
  `segment_01136000` (0x5CD0 bytes, loaded by `ovl_trademark`,
  `m_trademark.c`; segment 6). The patch rewrote it (0x4EB0 bytes, 158
  places) and the logo actor that draws it (`ovl_Animal_Logo`, all asm):
  the letters' places and display lists in its data (`D_FLT_80AA216C_jp`
  and after), three alphas swapped in `func_80AA0C98_jp` (0x78 and
  0xFF), and the strings `func_80AA08E4_jp` draws with the font on the
  title (their lengths, places and a 0.6875 scale instead of 1.0, with
  its own English text in `D_80AA2118_jp`). So the logo needs that actor
  in C before the en build can take it; the cartridge's title line under
  the logo is already in Latin letters.
