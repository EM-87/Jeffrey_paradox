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
  a rebuilt ROM (measured). Without that entry (`like=None`, what a stock
  mupen64plus does with the en ROM) nothing breaks, measured on the en
  ROM of the dial's small letters: the core logs "unknown rom" and `Save
  type: 0` (4 KB EEPROM), but it only tells SRAM from the rest (core at
  `emu/build.sh`'s CORE_REV, `src/device/cart/cart.c`: `use_flashram` is
  -1 for SRAM, else the cartridge's second domain becomes Flash RAM at
  its first access), so the game saves to Flash RAM all the same. The
  route plays to the houses (frames 3289, 9279, 12148: a frame or three
  from the entry's) and `tests/emu_save.py`'s checks all pass, the boot's
  included, the flash file then named after the ROM's header name
  (`DOUBUTSUNOMORI-<md5>.fla`). The en ROM keeps the cartridge's header
  name and game code (NAFJ), which other emulators and flash carts look a
  game up by; the 2010 patch's ROM changed them.
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
- **Under `-g3` every declared local has a 4-byte home** at the top of
  the frame, used or not, so a function whose instructions all match can
  still have a larger frame (and every sp offset off) when its C declares
  more locals than the cartridge's source did. The object IDO writes
  lists each local's home in its `.mdebug`; asm-processor's object, made
  from the GLOBAL_ASM, has none, so read the compiler's own (an object
  built from the C alone). func_80877B0C_jp: five where the cartridge
  has room for four ("The letter's tag").
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
  numbering too (the N64's answers 37 and 38 are its
  37 and 38, a catchphrase's entry its own): `tools/af_text.py
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
  (the first window's two of 16 and 14 bytes, the second's two), drawn in
  the yellow balloon over the question (the full-width font makes the balloon wide: phase 5).
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
  236 names of 8 bytes, its name n + 4 being the N64's n (measured on
  four animals' names, the two games' by their sounds). `tools/af_names.py`
  writes them into a copy of the segment that the en build uses as the
  segment's .bin: the segment holds 220 of the 232, and 38 of those are
  longer than 6. Widening the field would change the save data's layout;
  those names are tokens instead ("Names longer than 6", below).
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
  leftovers. Seen on screen: the villager at the start put a run of あ
  before the town's name (message 5106: the GameCube's text names the
  memory card's slot there, free string 4, where the Japanese has only
  the town). 64 official messages did this (`af_text.py check
  --reference` warns; the draft marks them `TODO adapt`): most the
  GameCube's memory card and its slot (FREE3/4/5 in the start's and the
  copy's messages 5106-5342 and 2383-2390), the rest another villager's
  name (FREE13, 9429-9430), the harvest moon's date (FREE16/17, where the
  cartridge's talks of the Thirteenth Night) and a few items and names.
  None is left: ours, from the Japanese, or the GameCube's text of a
  message whose Japanese is the same ("The official text's codes and the
  GameCube's own"); the bank's check has no warning.
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
  the numbering: the shop letters and the villager letters are the same
  letter at the same number (511 of the 544 bodies have the same free
  strings; the other 33, the same letters with the date in other slots;
  the villagers' footers, "<name>より", are "From <name>" and the like),
  but for the event notices: read letter by letter, the GameCube dropped
  some of the cartridge's events and wrote notices for its own, moving
  the rest by a number or two (17 of the cartridge's are at another
  number: `af_text.LETTER_REMAP`; 10 have none), and its White Day
  letters say Valentine's Day ("The strings and letters still in
  Japanese"). The GameCube's longest entries: header 23 bytes, body 177
  (490 of the 544 pass the cartridge's 96), footer 30; the villagers'
  parts 20, 62, 90, 32. `make script` dumps both, drafts `en_mail_*.txt`/`en_vmail_*.txt`
  and checks them (0 errors; 6 bodies and 5 villager openings printed free
  strings the cartridge's letter does not: the bodies were the moved
  notices and a treasure hunt, now ours or remapped, and of the openings
  4 print names the game fills for every villager letter: "The strings
  and letters still in Japanese"). Trailing newlines, which letters have,
  are written `{cd}` in the dumps.
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
  font's average lowercase width (6.5 px with the GameCube's letters, 7.3
  with the cartridge's): names 6 and the catchphrase 4, their fields'
  sizes (`PLAYER_NAME_LEN`, `ANIMAL_CATCHPHRASE_LEN`), but the speaker's
  name 9 and the catchphrase 11, since those are tokens that can stand
  for more ("Catchphrases longer than 4"); the date's parts as the en
  build writes them; anything else 8 (TODO(verify)). On the English
  bank: 1,934 pages with words moved on,
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
  set: 503 and 508, 508 in 430's place since 430's text is the
  GameCube's at another number) and two villagers' letters. Each band's
  first and last text pixel is where a model of the board puts the first
  and last characters' inked columns (with the fringe the boards'
  resampling shows: "The bulletin board"), within 2 frame pixels, on all
  88 bands of the 11 boards; the model fails 29 bands with its lines
  broken 20 px early and 71 with a space 1 px wider (measured with 430).
  The text's pixels are told by colour
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
  GameCube's own body has seven lines, its signature on the seventh, a
  notice the en build now takes from another number, "The strings and
  letters still in Japanese";
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
  n), so mixed case can be written. The en build adds the small letters,
  opens on them and has no kana modes: "The dial's small letters".
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

## The menus' strings where the font draws them (phase 5, the menus' strings)

- **One door for every menu's text.** Every submenu overlay draws its
  strings through `func_80090E98_jp` (mFont_SetLineStrings in AC GCN: a
  0x78-byte call of `mFont_SetLineStrings_AndSpace` with no extra space;
  in C in matching.patch, matching). The remaining menus draw theirs from
  large functions (the amount's 0x324 bytes, the clock's 0x334, the
  birthday's 0x40C, the town tune's 0xCA8), with each string's length an
  immediate, so changing each in its own function means matching each
  first. The en build instead swaps the string where it is drawn: its
  `func_80090E98_jp` looks the string up by its bytes and length in a
  table (`mFont_en_subst`, written by tools/af_ui.py from its `SUBST`
  list at make rom-en: the cartridge's bytes from the user's dump, never
  in the repository) and draws the English in its place. The menus' code
  stays the cartridge's.
- **The English in the cartridge's box.** The cartridge's string had
  full-width characters, 12 pixels each at the caller's scale; the
  English is centred in that box (or in a box of so many cells around
  it: BOX) or aligned to its left or right, narrowed to it when wider, or
  (a template the menu draws numbers into) a character centred in each of
  its cells. The function returns the box's width, as it returned the
  cartridge's string's. Our own text (script/ui.txt) goes over the
  GameCube's and stands alone where the GameCube has none.
- **What is swapped.** The amount to enter (`ovl__0079B120`): its title
  and its end (the same bytes end the birthday's and the clock's
  windows); the birthday (`ovl__0079DA50`): its title (its month and
  day words are textures); the clock (`ovl__0078AE30`): its title, and
  the date's and the time's words, which were already broken in the en
  build (the en font's 5-pixel space moved them over the numbers drawn at
  the cartridge's cells): ours, in the cells, year-month-day with
  hyphens (the N64's font has no slash) and a colon after the hour; the
  town tune's question and its two answers (`ovl__0079C020`); the
  Controller Pak notes' title (`ovl__007A10E0`, ours: no GameCube
  counterpart). The GameCube's are read from build/gc's copies of the
  disc's module and map (`kingaku_str$537`, `kettei_str$538`,
  `chr_table$472`, `str_line_adjust$655`, `mMS_str_*`).
- **The clock's cells.** The clock draws the date's template at x + 83
  and scale 0.875, and its numbers at x = 104, 146 and 188
  (`D_FLT_80884220_jp`): 21 pixels after the template's start, then 42,
  then 42, which is two cells and four cells of 10.5 (12 at 0.875): the
  numbers go in the template's blank cells 2-3, 6-7 and 10-11. The time's
  template, at x + 114, has its minutes at 124.5 (cell 1) and its hour at
  93, before it.
- **On screen** (`tests/emu_menus.py`, make menus-check-en). The swap
  table in RAM holds each entry's key and English. Each menu is opened
  three times: as it is; with the English's bytes in the table made
  spaces; and with the swap off (the entry's key changed) and the
  cartridge's bytes in the overlay made a ruler. The second against the
  first gives the English's ink, against the third the ruler's: the
  menu's own bytes are as they were in the second, so a menu laid out by
  its string keeps its layout, and the ruler reaches only as far as the
  string's last character (the tag sizes its window by that count,
  `mMl_strlen`'s: a ruler compared with the string made spaces moved the
  window and misjudged the box by 62 pixels). The bytes are found by the
  longest stretch of the cartridge's data from the string on that RAM
  holds once: a two-byte string alone is found elsewhere first, and the
  string alone counts only from 8 bytes (with the long names' build the
  map's shop label, 3 bytes, turned up in the heap before the map was in
  RAM: the ruler went there and the map drew its own string). The
  ruler is the en font's `w` repeated, its widest glyph whose columns
  are whole ink or none. From the ruler's ink, drawn by the same call,
  the box the cartridge's string took and its scale; from those, where
  the layout puts the English, each edge where the font's model puts it
  or one column out where a faint column (under 8 of 15) is (the frame
  counts one or not by the colours behind it). The cartridge's own
  glyphs first served as the ruler, but their faint edges are just what
  the model places a column wrong: over the Controller Pak's
  ten-character lines that misjudged the scale by 1.5% and the English
  by 3.5 pixels. With `e`, 6 pixels wide, a long English over a short
  string came out 2 pixels off at its far end. The English's ink matches
  within 2 frame pixels for all ten: the amount's title and end (the
  title's ink from 311 to 422, the model's 311.1 to 421.5), the
  birthday's and the clock's titles, the date's four pieces and the
  time's colon in their cells, the Controller Pak's title, and the town
  tune's question and answers, none more than 1.3 pixels from the
  model's edges. The town tune takes buttons only once it is at rest: R
  at 120 frames after it opens does nothing, at 180 it brings up the
  question. The screenshots show each string in its window.
- **The warnings and the Controller Pak's messages** (the second
  batch). The item menu's warnings (`ovl__0079A290`) are two lines each,
  from tables of place, string and length (`D_80897508_jp` and after),
  each line centred by its own x. The GameCube breaks the same warnings
  over two or three longer lines, so the English is ours, each line
  centred where the cartridge's was; the letters' answers are the
  GameCube's (`wr_Yes`, `wr_No`). The Controller Pak's messages
  (`ovl__0079F810`) are built the same way and have no GameCube
  counterpart: ours. Lines the cartridge shares between messages (the
  same bytes) share their English. A box of so many cells (BOX: 17 for
  the warnings' and the Pak's windows, measured on the screenshots)
  keeps a longer English line from being narrowed more than its window
  needs.
- **The gyroid types its line.** The gyroid's shop (`ovl__00794380`)
  builds its balloon's line in a buffer of 12 spaces (`func_8088CCF4_jp`,
  its state + 0x20) from its table (`D_8088DA64_jp`, twelve strings and
  lengths). The last four follow a three-character word for "that is"
  (`D_8088D9F0_jp`), and the last of them also follows a price's digits
  (`func_8088CC04_jp`: `mFont_UnintToString`, `mMl_strlen2`, `mem_copy`),
  before the word for bells. `func_8088CFB0_jp` types it, a character a
  frame up to 12 (state + 0x14, back to 0 when the line changes), and
  `func_8088D5D0_jp` draws that many: the string drawn is never the
  table's. So a key can be typed (`TYPED(12)`): it is matched against the
  whole buffer, without its trailing spaces, from the first character
  typed (a string of one character is looked up too), and the English is
  typed in proportion (as many of its characters as the menu has typed of
  its own, rounded up). A key can also be in pieces with a hole where the
  menu puts its own part: the price's key is the word for "that is" and
  the word for bells, and the digits go where the English has `{}`. The
  price's English is ours, in the GameCube's words: it builds the same
  from a word, the digits and a space, and the word for bells. The eleven
  other lines are the GameCube's (`mes_ikaga$433` and on, in the
  cartridge's order but for `mes_dono2`, which the cartridge lacks), the
  three after the word keyed with it. They take a box of 16 cells at the
  balloon's left edge.
- **On screen** (the second batch). Each of its menus is opened the same
  way: the warnings for letters, for pockets and for bells (program 16
  with data0 0 to 2: both lines, and the letters' answers), the
  Controller Pak's erase failure and its request for a Pak (program 18
  with data0 2 and 3; opened this way, 1 hangs and 0 draws no window),
  and the gyroid's shop (program 14): its first line, and a price. No
  item of the route's save has a price, so the check writes one into the
  gyroid's buffer as the gyroid builds it (the word, 1000, the word for
  bells; the ruler and the spaces go there too). All fifteen match: each
  within 2 frame pixels of the model's edges, or of a faint column's.
  The farthest is a warning's line that ends in an exclamation mark, its
  right edge at 431 against 428.8, 0.2 past where the mark's faint last
  column puts it (430.8). The price runs from 195 to 354 against 195.0
  to 353.9. The erase message's second line has the same bytes as
  another message's, and the overlay holds them twice (at 0x7A0B84 and
  0x7A0BBC): the check writes into the copy the message draws.
  Screenshots a frame apart show the gyroid typing the English in
  proportion: the first line's first two characters once one of the
  cartridge's nine is typed, the whole line once all nine are.

- **The tag's words** (the third batch). The tag a menu opens on an
  item (tag_ovl) lists its actions, each drawn whole from its own 12-byte
  record (`D_80878AE0_jp` and on, 39 of them: 8 characters, the word and
  spaces, then the procedure it runs; `func_8087792C_jp` draws each with
  length 8). The GameCube's are 16 characters and procedures
  (`mTG_tag_word_c`), named by the word (`mTG_tag_word_akeru` and on), in
  the cartridge's order with some of its own between; af_ui reads a
  GameCube symbol given with a length that far and drops its trailing
  spaces. 31 words are the GameCube's, left where the cursor points; the
  one to erase is ours (the GameCube has a placeholder); the second word
  for opening has the first's bytes and English; the amounts are digits,
  and the price's two labels frame digits drawn apart, so they stay. The
  question before something is thrown away (`func_80877FF4_jp`) is the
  GameCube's on one line and ours on two (the GameCube has
  placeholders). The question and most words are not on screen in the
  route's save.
- **On screen** (the third batch). The inventory (program 1) holds a bag
  of money in the route's save; A on it brings up its tag with two
  words, which match where the layout puts them (the first's ink from
  249 to 305, the model's 248.0 to 306.0). With them the check measures
  27 swapped strings on screen; all 213 of its checks pass.

- **The map's labels** (the fourth batch). The map (map_ovl) labels
  the acre under its cursor from its table (`D_8088FF20_jp`, 28-byte
  entries: the label's string and its length at +0x14 and +0x18, read
  by `func_8088F110_jp`): the shop (3 characters), the police box (4),
  the post office (4, and a second line of 3 drawn after it), the shrine
  (4), the station (2), the dump (5). Entries 0 and 1 are houses, which
  list their residents' names instead, 6 characters each, and a word for
  a house nobody lives in (`D_8088FE84_jp`, padded to 6, which
  `func_8088DE88_jp` copies into each such resident's name). The
  GameCube's words are used for the shop, the post office's two lines,
  the dump and the empty house (`mMP_label_str_*`, `akiya_str$483`, read
  8 bytes far); the police box, the shrine and the station, which it
  breaks over two lines and the cartridge draws on one, are ours in its
  words. Each is left after the label's icon, in at most 6 cells (a
  name's). Two more strings ride along: a wrapped item's name in its tag
  (`func_8086FD3C_jp` copies it into the item's name, 10 characters, and
  draws it as far as `mMl_strlen` finds it; the GameCube's
  `present_str$820`; not on screen in the route's save), and the number
  of the post the bulletin board shows (`ovl__00797A50`,
  `func_80895298_jp`: its number written into the template's first two
  cells, then the word after it; ours, with the number in the hole). This
  batch took that overlay for the Controller Pak's list of letters; it
  is the bulletin board (the sixth batch: BB_NUMBER, once CP_NTH).
- **On screen** (the fourth batch). The map opens on the houses' acre,
  and the route's save has four empty houses: their word shows in each
  copy, so the check writes its ruler into every copy, every frame. The
  town is not the same with each ROM: a rebuilt ROM's new game draws
  another town (its seed follows the run's timing, while the route's
  milestones stay on their frames), and the acre where the last ROM had
  the shop has the post office in this one. So the check moves no
  cursor: it gives the houses' acre's record the label to show (the
  label's index at +0x12; the record found by its first four words,
  pointers to the copies of the empty house's word), and all seven
  labels show there in turn. The station's string has 2 characters, so
  its ruler has 2 (about 28 frame pixels) for a label narrowed into 6
  cells (104): a pixel of the ruler's width is 3.5% of the scale it
  gives. The check carries the ruler's error to each edge past the
  ruler's own length (a pixel of its width, at the edge's distance): the
  station's right edge is 3.8 pixels off, within the 4.9 that allows;
  the map's other edges are within 1.5 of the model's (the shrine's
  right edge, inside its faint column's reach). With these the check
  measures 35 swapped strings on screen; all 231 of its checks pass.

- **The letter's tag** (the fifth batch). A letter in the pockets is
  named in its tag by `func_80877B0C_jp` (tag_ovl), in three lines from
  the letter's record (its type at +0x02; the addressee's name at +0x44,
  of which 6 characters are drawn; the sender's at +0x4E): the
  addressee's name and the word for "to" after it (its last two
  characters alone, without the honorific, for type 4); what the letter
  is (type 1 a delivery, 8 a fortune, the rest a letter); the sender's
  name and the word for "from" after it (its short form for types 3 and
  7, the player's mother being 7; none after a fortune, nor after the
  HRA, type 9, whose name is a word of the overlay's). Each piece goes
  where the last ends, at the name's `mMl_strlen` times the width of a
  character, and each name takes a colour by type from the overlay's
  table (`D_808794C0_jp`). The GameCube
  (`mTG_init_tag_data_item_win_sub_mail_item`) puts what the letter is
  with "to" first, then the addressee, then "from" and the sender, with
  the player's home as the mother's (`str_otegami`, `str_otodokemono`,
  `str_omikuji`, `str_title1`, `str_happy_room`, `mother_str`): English
  in the cartridge's order would read backwards, so no swap does. The en
  build draws the GameCube's order instead (`mSM_en_tagMailName` in
  m_submenu.c, each piece where the last ends at the en font's widths;
  the cartridge's own order and pieces without the disc's words), from
  code because the overlay cannot grow: the overlay's function only
  calls it with its own strings and colours, which code cannot name, and
  tag_ovl gets its relocation table anew (EN_OVERLAYS: fado gives the
  cartridge's 792 from the matching objects, the en build writes 762).
  The GameCube's word for "from" ends in a space of its own (0xD3, which
  its padding, 0x20, does not trim; af_ui reads it as a space): the
  sender is drawn after it.
- **The tag overlay in C** (matching.patch). tag_ovl was asm; its text
  is now a C file of `GLOBAL_ASM` (135 functions) so that one of them
  can be C. Its rodata comes with the functions' asm (the late rodata
  of the seven that have any), its bss is defined in the C file (three
  symbols, which an asm bss file held before), and its data stays the
  extracted asm. `func_80877B0C_jp` is in C behind `NON_MATCHING`, the
  asm in its place: every instruction is the cartridge's, one for one,
  but its frame is 104 bytes where the cartridge's is 96, so every
  offset from sp differs. IDO under `-g3` gives every declared local a
  4-byte home at the top of the frame, used or not; the object IDO
  itself writes says where, in its `.mdebug` (the object asm-processor
  leaves has none). The cartridge's frame has room for four homes, and
  this C declares five: the string, its length, the colour, the name,
  and the name's width, which the cartridge holds in t0 across the type
  tests.
  About 2000 variants swept with `tools/af_bench.py` (declarations at
  function scope and in blocks, `switch` against `if`, types, expression
  shapes) found no way with four. The en build replaces the function
  anyway.
- **A table that splat wrote in halves** (matching.patch).
  `D_80878A00_jp` is 16 records of 12 bytes, two halfwords and two
  pointers; `func_80874720_jp` reads the second halfword of the first
  record. The decomp's symbol list named that halfword
  (`D_80878A02_jp`) and typed both `s16`, so splat wrote the whole table
  as halfwords: the same bytes, but its 32 pointers became halves of
  addresses, which are no relocations to fado. The ROM still matched;
  fado, from the matching objects, gave 760 of the overlay's 792, and
  af_ovlreloc, which proves fado on the matching objects before it
  writes an en table, refused. Typed `s32` with its size (0xC0), the
  table is words and pointers again, and the function's %hi/%lo pair
  is `D_80878A00_jp + 2` (two entries in reloc_addrs).
- **On screen** (the fifth batch). The route's save has no letter in
  the pockets, so the check writes one into each of the ten slots (the
  player's mother's, made by the hook with `mPr_GetMotherMail`; the
  pockets' letters at +0x40A of the private info, 0xA4 bytes each),
  opens the inventory (program 1), brings the cursor onto the letters
  (five pushes right, one down) and waits for the tag to rest (150
  frames): it shows what the letter is, the player's name, and the word
  for "from" with the player's home. Each piece's ink is the frame
  against one with the piece made spaces (the word for "from" against
  one without the sender too: spaces in its place would move the
  sender). The ruler goes into the en build's copy of the first line's
  string (`mSM_en_tgLetterTo`), which gives the box's left and the
  scale (0.748). The first line's ink runs from 459 to 534 against the
  model's 459.0 to 535.6; the word for "from" from 459 to 498 against
  459.0 to 499.0; the sender from 506 to 550 against 507.3 to 550.2,
  where the word for "from" ends at the font's even width. With these
  the check measures 38 strings on screen; all 237 of its checks pass.

- **What the font still draws in Japanese** (a survey, the sixth
  batch). Every call of the font's line (`func_80090E98_jp`, and
  `mFont_SetLineStrings_AndSpace` where a caller skips it) was listed
  with the string it passes, and each submenu program opened from the
  route's save. Besides the item names (a file of their own, 10 bytes
  each, which the catalog, the inventory, the tags and the shops draw),
  the dial's kana (kept on purpose) and the bulletin board's lines
  (broken by a count of 16, mid-word in English): the tag's price window
  and the bulletin board's date (below), and the title's warning that no
  controller is in the first port (`ovl_Animal_Logo`, `func_80AA08E4_jp`,
  when `common_data` + 0x10AB0 is 0: three lines of 20 characters, drawn
  with `mFont_SetLineStrings_AndSpace` directly; the 2010 patch has its
  own English there: ours since, "The title's logo"). Never on screen:
  the title's list of options
  (`func_80AA0668_jp`, five lines of 19 characters, two of them in Latin
  letters already: a line is drawn when its bit of the actor's + 0x2F8
  is set, which only the actor's constructor and `func_80AA0360_jp`
  write, both clearing), the save menu (gamestate 7, `save_menu.c`, a
  debug screen whose other lines are in Latin letters) and the player
  select (`player_select.c`, debug). Program 2 shows the gyroid's
  message, the player's own words; program 17's windows show no text in
  the route's save.
- **The tag's price window** (the sixth batch). The word to set a price
  (`D_80878B94_jp`, its procedure `func_80873278_jp`) opens a tag of
  kind 39, which `func_80877FF4_jp` draws with `func_8087792C_jp`: its two
  words (`D_80878EE8_jp`: the price's, then the word for bells after five
  spaces), and the price's five digits (`mFont_UnintToString`), a cell
  each from the second line's left (the one being set in red, from
  `D_808794A8_jp`). The GameCube's words are used (`mTG_tag_word_okane`,
  `mTG_tag_word_beru`): the price's left, and the bells' from the cell of
  the cartridge's first character (FIRST, a new layout: the cells before
  it are the digits').
- **The bulletin board's number and date** (the sixth batch). The board
  (program 6, `ovl__00797A50`) draws the post's number and date from the
  same place (`func_80895298_jp` at the call's x, `func_80895328_jp` 109
  pixels on, both at a scale of 0.75). The date's template
  (`D_80895C10_jp`) is ten cells, the year's four, a space, the month's
  two, a space, the day's two, which `func_80895328_jp` writes every frame;
  the board's texture has its marks under the two spaces. The en font's
  digits are narrower than the cartridge's cells, so the marks fell among
  them. A key can now name the cells a menu writes its digits into
  (WRITTEN: '0' in the key, matched by a digit or a space, at least one
  digit); such a template (DIGITS, in cells) needs no English: the key
  itself, the menu's digits a character a cell, as the cartridge has
  them. The GameCube's board names the month instead and writes the day
  and the year after it (`mNT_set_day_strings_dl`), which the cartridge's
  texture has no room for. The number is the fourth batch's BB_NUMBER.
- **On screen** (the sixth batch). No tag of the route's save has a
  price, so the check opens the money's tag (kind 8) with kind 39's list
  of words: once the inventory has loaded the tag overlay, it points kind
  8's list in the tag's table (`D_80878F08_jp`, a list and its length for
  each kind, found in RAM by the lengths of kinds 1 to 8) at kind 39's.
  The tag then shows the two words where the price window has them
  (without the digits, which only its own function draws): the price's
  from 248 to 326 against the model's 249.0 to 325.9, the bells' from 367
  to 424 against 366.2 to 421.9 (2.4 allowed at that edge: the ruler is 7
  cells). The bulletin board writes its number and date into their
  templates every frame, so no ruler stays there: with the date's swap
  off the board draws its own date from the template's place at the
  call's scale, which gives the place (its ink from 343 to 450, the
  model's 343.0 to 450.5 at 0.75, the scale confirmed by the far edge);
  with the swap the eight digits each fall in its cell within 1.5 frame
  pixels, and the texture's marks between them; the number's English
  is centred in its box 109 pixels before the date's (151 to 196 against
  150.5 to 196.5). With these the check measures 42 strings on screen;
  all 245 of its checks pass.

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
  still type), the town tune's note names (kana textures, I4) and the
  bulletin board's text, which breaks its lines by count, not by width.
  The item names were, until "Item names" below; the kana modes, until
  "The dial's small letters".
- **The title is more than textures.** The title's logo lives in
  `segment_01136000` (0x5CD0 bytes; segment 6 while the logo actor
  draws). The patch rewrote it (0x4EB0 bytes) and the logo actor that
  draws it (`ovl_Animal_Logo`): the letters' places and display lists in
  its data (`D_FLT_80AA216C_jp` and after), four values of
  `func_80AA0C98_jp`'s code, and the strings `func_80AA08E4_jp` draws
  with the font on the title (their lengths, places and a 0.6875 scale
  instead of 1.0, with its own English text in `D_80AA2118_jp`). So the
  logo needed that function in C before the en build could take it: "The
  title's logo" below. The cartridge's title line under the logo is
  already in Latin letters.

## The GameCube's signs (phase 5, the graphics' fourth step)

- **What still had Japanese words.** Every file's display lists walked
  (the textures they load: format, size, palette), the wide ones drawn
  out of the en ROM outside the menus the 2010 patch covered (387), and
  the decomp's named objects' extracted images, looked at. Words in
  Japanese on: the four shops (the first one's name over its door, a
  paper sign on its door and an emblem with a character; the second's
  name, twice; the third's; the fourth's name and its sale banners), the
  black market's crest, the dump's sign, the police box's plate, the
  countdown board's units, the fishing tourney's stall and its
  headquarters' sign, the houses' name plates, the fortune stall and its
  slips; outside the structures, letter papers (a New Year's greeting, a
  safety slogan), the birthday window's words for month and day (segment
  0xADD000), a quotation frame (0xA94000) and two small cards (files
  0x13BF000 and 0x13C7000). The post office's sign and the countdown
  board's name were English already.
- **The GameCube has them.** Its foresta.rel (`dataobject.obj`) keeps the
  structures' textures under the decomp's names, at the same sizes, in
  its own format: CI4 in 8x8 tiles (32 bytes a tile, the high nibble
  first: as its font sheet), palettes RGB5A3. Untiled and compared texel
  by texel with object_00D5E000's, it redrew 22 of them with English
  words (`tools/af_gc_signs.py`'s SIGNS), each the same texture with the
  sign redrawn (80 to 99% of the texels the same; the fourth shop's name
  60%, the sign being most of it); and the fourth shop's sale banner (its
  katakana), which it drew anew as an emblem and a pattern beside the same
  window and small sign (half of the texels the same): taken too, its
  side seen in the game without anything amiss. Their palettes are the cartridge's
  colours (a transparent entry's channels a step apart: the GameCube
  keeps four bits of them), but the black market's: its two colours for
  the new words. It kept the tourney headquarters' sign and the black
  market's winter crest as the cartridge has them, and changed textures
  without words (a door's window, a clock's ticks, the postal mark, small
  details of the houses): left as they are. The cartridge has some
  textures twice (summer and winter, the same): both take the sign.
- **Taken at build time** (`tools/af_gc_signs.py`, at make rom-en when
  build/gc has the disc's foresta.map and foresta.rel.szs). Each checked:
  the cartridge's lists draw a CI4 texture of that size there; the
  GameCube's and the cartridge's are the same texture redrawn (a share of
  the texels the same, not all); the other palettes are the cartridge's
  colours. Written over the en tree's copy: the PNG splat extracts for a
  named texture (8-bit indexed: pigment64 takes its indices), the
  unextracted part's .bin for the rest; the black market's palette into
  object_00D5B000's data (`object_00D5B000.data.s`: its `.palette.inc.c`
  files are not compiled), its two changed entries only. 31 copies and a
  palette; nothing of it enters the repository.
- **In the game** (`make signs-check-en`, tests/emu_signs.py). The en ROM
  holds each copy as the GameCube's texels laid out, not the
  cartridge's, and the palette's colours; and a hook asks the structures'
  clip to set each structure up 170 units in front of the player, as the
  field sets one up (`aSTR_setupActor_proc`: its object's part and
  palette loaded): the four shops, the police box, the black market, the
  countdown board, the tourney's stall and the dump, each with its signs
  in RAM as the en build has them and the cartridge's gone (the route's
  town is in summer). The screenshots show them. The houses' name plates
  come with a villager's house: the ROM's check only.
- **Still Japanese.** The fortune stall and its slips (the New Year's
  shrine): the GameCube's stall is another model (two textures and two
  palettes for the cartridge's three and one), nothing of it fits. The
  tourney headquarters' sign and the black market's winter crest, which
  the GameCube kept. The two small "cards" of the survey (the shop's
  inside, `ovl_Shop_Indoor`'s segments 0x13BF000 and 0x13C7000) are a mat
  and a cart's side, no words, and the GameCube's are the same.

## The word textures (phase 5, the graphics' fifth step)

- **The town tune's notes** (program 8, `ovl__0079C020`): a frog for each
  note with the note's name on it, kana: 16x16 I4 textures in
  segment_00AD1000 from 0x2868, 0x80 apart, which the overlay's note
  table (its data: 16 entries, each a segment 0x0C address, a place and
  two colours) names. The GameCube's town tune puts letters on its notes
  (`onp_win_a_tex_rgb_i4` to `g`, `q`: 16x16 I4 in 8x8 tiles, as its font
  sheet): C for do, D for re, E, F, G, A and B for ti, a question mark for
  the random note. The tie (a dash) and the rest (z's) are the same
  texture on both. Taken from the disc at build time
  (`tools/af_menu_tex.py`), the cartridge's kana without its files.
- **The birthday's month and day** (program 9, `ovl__0079DA50`,
  segment_00ADD000 at 0x2DB8 and 0x2EB8, I4 32x16): the words for month
  and day after the two numbers. The GameCube's window spells the month
  out (a texture a month, `month_tex` in `m_birthday_ovl.o`) and has
  nothing like them. Ours: the en font's dash after the month and nothing
  after the day, as the clock writes its date (month - day).
- **The catchphrase window's quotes** (the text editor's segment 0x0C,
  segment_00A94000 at 0x1318, I4 128x16): Japanese corner quotes round
  the catchphrase, a wave dash for the sentence before it and a full stop
  after it. The GameCube's window has no such texture. Ours: the en
  font's double quote and an ellipsis where the corner quote and the
  wave dash were, a double quote where the full stop and the closing
  quote were.
- **The letter papers' words** (segment_00AE3000, CI4 on each paper's
  palette): a safety slogan in two halves (32x16 each) and a New Year's
  card's greeting (64x16). The GameCube's papers have no texture like them
  (its paper textures of these sizes agree with them on the ground only).
  Ours: "Safety" (a pixel less between its glyphs, to fit) and "First",
  and "New Year", the en font's glyphs centred, in the paper's own inks
  (the glyph's strong texels the darkest stroke's index, its middling and
  faint ones two lighter ones the kanji used, the rest the ground). A
  third paper texture of the survey writes a size in digits (no words).
- **Built** by `tools/af_menu_tex.py` at make rom-en (after the font, whose
  glyphs and cut table it reads from the en tree), over the en tree's
  copies of the segments (.bin); each checked first: the cartridge's lists
  draw a texture of that format and size there (the notes: the overlay's
  table names each), not empty. Nothing of the cartridge's or the
  GameCube's enters the repository: ours are the en font's glyphs, put
  where the kana were. 14 textures.
- **In the game** (`make menus-check-en`): the town tune, the birthday and
  the text editor (catchphrase mode) opened, each segment found in RAM as
  the 2010 labels' are, its textures the en ROM's and not the
  cartridge's; the papers' words (no menu opens a letter there) in the en
  ROM as the en font letters them.

## The title's logo (phase 5, the graphics' third step)

- **Where it lives.** Six pieces, which the logo actor (`ovl_Animal_Logo`)
  draws in two layers each: `func_80AA0C98_jp` (in C, matching, in
  decomp/matching.patch) puts piece `idx` at x `(D_FLT_80AA216C_jp[idx] -
  160) * 16 - 2190 - 20 * idx` and the logo's y (`D_FLT_80AA2184_jp`),
  turns it by the actor's + 0x304 angle, and draws into the font's list:
  the actor's own list `D_80AA20C0_jp`, the colour set's (actor + 0x2B4)
  primitive colour with alpha 120 and environment colour with alpha 255,
  the piece's first layer (`D_80AA2188_jp[idx]`), the actor's list
  `D_80AA20E8_jp`, the second colours (environment alpha 255), the second
  layer (`D_80AA21A0_jp[idx]`). The layers' lists, vertices and textures
  are in `segment_01136000` (vrom 0x1136000, 0x5CD0 bytes), which
  `aAL_actor_ct` reads itself into the zelda arena (from the raw ROM
  addresses `D_1136000` to `D_113BCD0`) and `func_80AA19CC_jp` makes
  segment 6 (`asm/jp/nonmatchings/overlays/actors/ovl_Animal_Logo/`);
  `m_trademark.c` reads it too, into a buffer of its state the decomp
  marks unused. The segment holds first the title's other textures, the
  ones the actor's code names (0x1110, 0x1510 and 0x1910 the line that
  asks for START, 0x1D10 and 0x2110 the copyright line, IA8 64x16 each;
  0x2510 the trademark mark, IA8 16x8), then from 0x2590 to its end the pieces
  (their 12 lists, vertices and textures, with only 8-byte alignment
  between them).
- **What the 2010 patch changed** (`tools/nafe_diff.py`, and both ROMs'
  lists walked). Its segment is 0x4EB0 bytes: the cartridge's up to
  0x2590 but for the intensity of 1,897 texels with no alpha (nothing that
  shows), then its own pieces, the game's English name on two lines,
  from 0x2590 to 0x4EA8. In the actor: both tables of the pieces' lists,
  their x and the logo's y, the colour tables (set 0's primitive colour
  and every set's first-layer environment colour; the second layer's
  stay), the trademark mark's x (`D_80AA227C_jp`, 0x112 to
  0xFA), one command of `D_80AA20E8_jp` (the back-face culling it set,
  replaced by a texture-LUT command its other-mode word already gives:
  the second layer is drawn from both sides), and four values of
  `func_80AA0C98_jp`'s code: the first layer begins with `D_80AA20E8_jp`
  instead of `D_80AA20C0_jp` (another colour combiner), with primitive
  alpha 255 instead of 120, and both environment alphas are 120 instead
  of 255. Besides the logo it changed the warning shown when no
  controller is in the first port (`func_80AA08E4_jp`'s places, lengths
  and scale, its text `D_80AA2118_jp` to `D_80AA2140_jp`, running on
  over `RO_STR_80AA2320_jp`, the actor's file name) and three words of
  the title's list of options (`D_80AA2038_jp`, never on screen).
- **Taken at build time** (`tools/af_nafe_logo.py`, run by make rom-en
  when build/nafe-2010-12-02.z64 is there). The segment: the cartridge's
  up to 0x2590 and the patch's from there, padded with zeros to 0x5CD0
  (the actor reads the cartridge's length; nothing after it moves). The
  15 data symbols (77 words, 40 of them changed) over the en tree's
  `ac_animal_logo.data.s`. The four values as
  `assets/jp/en/animal_logo.inc`, which the en build's `func_80AA0C98_jp`
  uses (decomp/changes.patch). Checked first: the patch's pieces start
  where the cartridge's do and the cartridge's run to the segment's end;
  what the actor's code names in segment 6 lies below them; below them
  the patch changed no texel that shows; the four instructions are the
  cartridge's, and the patch's differ only in their values. Without the
  patch's ROM the cartridge's segment, words and values are written (the
  en object is then the matching build's). The warning, the options'
  words and the file name are left out (the tool lists them): the
  warning's English is ours to write. Nothing of the patch enters the
  repository.
- **The code that results.** With `D_80AA20E8_jp` named twice, IDO keeps
  its address in a register: the en `func_80AA0C98_jp` is two
  instructions shorter, with two relocations fewer. The overlay is in
  EN_OVERLAYS: its relocation table is made anew (`af_ovlreloc`: fado
  gives the cartridge's 145 from the matching build's objects, the en
  build's 143), and af_relink lists its text (the same size, its later
  functions 8 bytes earlier) as padded in place; af_shiftcheck: 0
  violations.
- **On screen** (make emu-check-en, the title at frame 400): the
  English name where the cartridge's logo was. Against the 2010 patch's ROM
  at the same frame, no pixel of the letters differs; what differs
  (1,501 pixels by more than 24 of 255) is the player's character behind
  the logo and the falling petals, which follow each ROM's own town
  (Emulator traps in CLAUDE.md: a rebuilt ROM draws another town).
- **The warning without a controller** (`func_80AA08E4_jp`, in C,
  matching). The logo's draw (`func_80AA19CC_jp`) calls it while
  common_data's pad_connected (+ 0x10AB0) is 0, which second_game.c sets
  once at boot (`padmgr_isConnectedAnyPak(0) != 0`): over a yellow oval
  (`func_80AA07F4_jp`), three lines of 20 characters (`D_80AA2118_jp`,
  `D_80AA212C_jp`, `D_80AA2140_jp`, the first two ending in spaces) at x
  55, 64 and 50 and y 120, 140 and 160, drawn with
  `mFont_SetLineStrings_AndSpace` with no extra space. The en build calls
  the font's line instead (`func_80090E98_jp`, the same call without the
  space), so that the swap table puts the English in (TL_NOPAD1 to 3:
  ours, the GameCube has no such warning), each line centred in the
  cartridge's 20 cells, which the en build puts at x 40, centred on the
  screen. The overlay was in EN_OVERLAYS already; its relocations stay
  143. The swap table grows by three entries in code_en (189 bytes).
- **The warning on screen** (make menus-check-en). Booted to the title,
  pad_connected cleared, and from that state the warning drawn three
  times as the menus' swaps are: as it is, the English blanked, the
  swaps off with the cartridge's strings in the logo actor's data made a
  ruler. Each line's English lies where the layout puts it in the box its
  ruler shows (the box from frame x 85, scale 0.997 to 0.999): inks at
  141-497, 175-465 and 193-446 of the frame's 640, the model's 140.5-495.0
  (496.9 counting faint columns), 175.7-464.6 and 193.4-445.4. While the
  warning shows, the logo's second line is behind the oval, as it is in
  the 2010 patch (the oval is the cartridge's in both).

## Item names (phase 5, the menus' strings)

- **The cartridge's.** `mIN_copy_name_str` (`m_item_name.c`, split from
  `code` at 0x6BA3B0 in matching.patch) copies an item's name, 10
  characters, with a DMA of its own from a file at vrom 0x10F4000
  (0xB1A0 bytes, addressed as segment 6): a name type of 2 is an item of
  one of 16 kinds (paper, money, tools, fish, clothes, other things,
  carpets, wallpaper, fruit, plants, records, an empty kind, tickets,
  insects, grab bags, turnips: the kind in bits 8-11, its names from the
  kind's place in `D_801076BC_jp`, the index in the low byte); 1 is
  furniture, each name four times (once for each way it faces: the
  number with its two low bits is the index, from `D_6001D98`); nothing
  is a blank name (`D_801076B0_jp`); anything else leaves the buffer as
  it is. Before the lookup `mRmTp_FtrItemNo2Item1ItemNo` turns the
  furniture numbers of clothes, insects, fish and umbrellas into their
  items, so those furniture names are never read. The copy's helper
  (`func_80096710_jp`) matches; `mIN_copy_name_str` is NON_MATCHING:
  the cartridge's shape (the frame, a 16-byte buffer, the switch, the
  segment's base loaded as an address) but not its registers (it keeps
  the parameter, then a 16-bit copy of the converted number, in a2, and
  the number in v1; no declaration order or type swept with
  `tools/af_bench.py` gives that: 19 instructions differ at best). The
  en build replaces it.
- **The GameCube's** (read from the user's disc at build time, from
  build/gc's copies of `foresta.rel.szs` and `foresta.map`, by
  `tools/af_items.py gc-files`; never committed): `itemName_*` and
  `ftrName_table` in `dataobject.obj`, 16 characters each, and each
  name's article in `itemArt_*` and `ftrArt` (`m_item_name.o`): none,
  a, an, the or some (`mIN_ARTICLE_*`). The same kinds in the same
  order, with items of its own added: a plant after the first (the
  plants shift by one), and in the furniture, after the clothes, its
  mannequins, more insects, fish and umbrellas' patterns (blocks of 8),
  and more games after the cartridge's Famicoms (which all share one of
  its names). The clothes the cartridge numbers as furniture (491 to
  745) take the clothes' names, as `mRmTp_FtrItemNo2Item1ItemNo` reads
  them; a bag of money among the other things takes the money's. Of the
  cartridge's 1,703 names (947 pieces of furniture), 1,696 are the
  GameCube's and 7 are ours (`script/items.txt`: the errands' letter,
  cloth and money, a fortune slip, the town's map, the empty kind's one
  name, the Famicom's disk drive), each checked against the cartridge's
  name kind by kind (`af_items.py pairs`, local, prints only).
- **Why a token.** The GameCube's names run to 16 characters (739 of
  its 1,024 furniture names are longer than 10), and the buffers the
  cartridge copies a name into stay 10 bytes in some 27 overlays (all
  asm, and an overlay cannot grow). So the en `mIN_copy_name_str` writes
  a token there, which every one of them copies and keeps as it would a
  name: 0x80 (a code no string the game draws has), the name's number in
  the en table in two digits (0x81 + n / 64, 0x81 + n % 64: never a
  space nor 0xCD), 0x80 again up to the cells the English takes, then
  spaces. The cells are the name's width in the en font (even, as
  `mFont_GetStringWidth` gives it) in 12-pixel cells, from 3 to 10: what
  `mMl_strlen` counts (the characters before the trailing spaces), and
  so how wide a menu makes the name's place (the tag, the item's balloon
  in `6EFC20.s` sizes itself by it). The names are records of 20 bytes
  (length, article, cells, a zero, 16 characters in the N64 font's
  codes) in a ROM segment of their own (`item_en_names`, a ninth dmadata
  entry), read by DMA as the cartridge reads its names, the last 16 kept:
  a table in RAM would grow `code_en`, and the heap gives way to it.
- **Who takes a token.** Every caller of `mIN_copy_name_str` was listed
  (`jal` in the asm and the C, and what each does next with the buffer):
  messages (`mMsg_Set_item_str`, `mMsg_Set_free_str`, the coloured free
  string `func_8009D820_jp`), letters (`mHandbill_Set_free_str`), a
  choice (the night stall's goods, `ac_ev_yomise`: `mChoice_Set_choice_data`),
  the font's line (`func_80090E98_jp`: the inventory, the catalog's
  pages, the tag, the item's balloon) and `mMl_strlen`. Nothing sorts or
  compares names (the catalog's page, once named, loads its picture),
  and the save keeps an item's number, not its name (a letter keeps its
  text, with the name already put in). So: the en
  font draws a token as its name, from the left of its place and
  narrowed into the cells the call gives (`m_font.c`); the message code
  puts in the name (`m_msg_main.c`) and the letters' expander too
  (`m_handbill.c`), each after the name's article; a choice answers with
  the name alone, as the GameCube's stall does (`m_choice_main.c`,
  `mChoice_Add_choice_data`).
- **The articles.** The GameCube puts an item's article before its name
  where the code that sets the string gives one (`mMsg_Set_item_str_art`,
  `mMsg_Set_free_str_art`, `mHandbill_Set_free_str_art`): every one of
  its calls that sets an item's name does (18 item strings, 9 free
  ones); its setters without an article set numbers, typed text and
  people's names. Its text cuts the article where the sentence has its
  own word (CUTARTICLE, its code 116: 1,097 times in the message bank,
  40 in the letters) and asks for a capital at a sentence's start
  (CAPTIALIZE, the decomp's spelling, 117: 125 and 11 times; before the
  catchphrase 58 times, item and free strings 55, the answer chosen 4).
  The cartridge's codes stop at 96, so the translation's bank carries
  the two as codes 72 and 73 (`af_text.N64_CARRIED`; LUCK_7 and LUCK_8
  on the cartridge, used by neither game's bank: 71 already carries
  STR_AMPM), 970 and 92 of them in the messages the drafts take from the
  GameCube, 31 and 10 in the letters. The message window's handlers for
  the two (`func_800A1AA4_jp`, `func_800A1AC4_jp`) pass over the code as
  the cartridge's luck codes do and set a request: the next free or item
  string goes without its article, the next string the GameCube
  capitalizes (the player's name, the animal's, its catchphrase, the
  free and item strings, the town's name, a letter's string) begins with
  a capital (a to z: the en charset's letters are ASCII's). A request
  ends with the string, or when a message starts (`func_800A2538_jp`), as
  the GameCube's status flags do. Two differences: the GameCube leaves
  a capital asked before the answer chosen for the next name (its answer
  handler does not read it), the translation gives it to the answer; and
  the GameCube's letters never clear their capital (its
  `mHandbill_clr_capital_flag` clears the forced article instead), the
  translation's clear both after the string. A choice's free or item
  string takes the article, never cut (the GameCube's choices pass
  `mIN_ARTICLE_NUM`). In the letters the two codes go from the text (the
  cartridge's expander has no handler for them, and would stop on them),
  the two bytes filled with the letter's fill where the GameCube leaves
  them as they were.
- **In the game** (`tests/emu_menus.py`, part of make menus-check-en).
  The hook copies names as the game does and reads the token and the
  name it stands for: the first and last of each kind, five pieces of
  furniture facing two ways, the first clothes and insect by their
  furniture numbers, and no item (the blank). It puts a token into a
  message's item string and runs the message window's own handlers on a
  text of the hook's (the item and free strings, the article's two
  codes: cut, a capital, both, a name with no article, the requests
  spent by the first string, a capital on a string of no item), a
  choice's item string, and a letter's free string through the letters'
  expander. On screen: the inventory opened on the route's save shows
  the money's name in the GameCube's English under its cursor; measured
  as the swaps are (a ruler as long as the token's cells written into
  every copy of the token, every frame), its ink runs from 203 to 338,
  the model's from 202.9 to 337.1, from the place of its 6 cells at the
  scale 0.995. And the message window shows three messages of the
  translation's bank (chosen by their codes: the first in the GameCube's
  English that needs no speaker, is one page and puts in item string 0
  as it is, with its article cut, or with a capital) with the first
  tool's token in item string 0, and again with the English it should
  give written as the string itself: the frames are the same, pixel for
  pixel (so the article's codes, left in the text as the cartridge
  leaves its luck codes, draw nothing), and with the wrong English (the
  article missing, or there where the text cuts it) they differ.
- **What the heap has left.** Every byte `code_en` takes comes off the
  system heap, and so off the game state's heap and the zelda arena play
  carves from it (`m_play.c`: the whole rest of the state's heap).
  `code_en` is now 0x1B560 bytes: mostly the sections of `m_msg_main`,
  `m_handbill`, `m_font`, `m_flashrom`, `m_choice_main`, `m_submenu` and
  `m_string` that grew and moved there (their old places in code stay
  empty), the swap table (`m_font`'s data, 0x1FD0) and the letters'
  extension (`m_flashrom`'s bss, 0x24A0). The names add `m_item_name`'s
  text (0x580), the 16 records kept (bss, 0x1B0) and the kinds' table
  (data and rodata, 0x90), and the articles some hundreds of bytes of
  code. Measured on the en build: on a fresh boot, when the title loads
  the save (frame 433; `func_8008F968_jp` reads it into 0xF980 bytes
  taken from the zelda arena, to check it before it is copied), the
  arena's largest free block is 0x1FBE0 bytes, so 66 KB to spare; in
  play at the houses after the route it is 0x51B0 (of 0xC9F40 in use or
  free). So that load, which the save and letters checks called in play,
  now fails there (zelda_malloc returns NULL; they last ran at the 2010
  labels' batch, before the swap table); the game itself calls it on the
  title screen only (`ac_animal_logo`), where the save check's boot sees
  it load. The two checks now call the title's direct load in play
  (`func_8008F938_jp`: the save read straight into `common_data`, the
  extension after it, the same reading code). `code_en` should take no
  more than it must: a table goes to a ROM segment, as the names do.
- **The tag's cursor** (the swap check). The word under the money tag's
  cursor is drawn some 5% larger, its ruler too (scale 1.043 against
  0.984), from a place the cartridge's box does not give: measured
  there, the first word's English starts 2 frame pixels right of where
  the box's left puts it and its ruler 2 to the left. Each word is now
  measured where the cursor is not on it (the stick pushed down for the
  first): its ink from 245 to 299, the model's 243.1 to 298.9. With
  the names, the articles and this, all 307 of the menus check's checks
  pass.

## Names longer than 6 (phase 5, the menus' strings)

- **The fields.** An animal's name is 6 bytes wherever the cartridge
  keeps or copies one (`PLAYER_NAME_LEN`). Of the 220 animals' names the
  name segment holds, 38 are longer in English (7 or 8 letters), and so
  are 8 of the special characters' (the strings `mNpc_GetNpcWorldNameP`
  loads into 6 bytes, m_npc.c's `l_sp_actor_name`: Tom Nook's among
  them). They were cut.
- **Tokens, as the items'.** Their records follow the items' in
  `item_en_names` (tools/af_items.py: no article, 3 to 6 cells): 1,703
  items' and 46 names', 1,749 records. Each animal's token is written
  into the en name segment, in its field (af_items.py after
  af_names.py), so `mNpc_LoadNpcNameString`, the segment's one reader,
  copies the token as it copied the name: no code changes for them. The
  special names' tokens are written where they are loaded: the en
  `mString_Load_StringFromRom`, for a load into 6 bytes of a string the
  include lists (`ITEM_EN_SPECIAL_NAMES`, m_item_name.c). The first try
  wrote them in m_npc.c instead: its text (0x8290 bytes) then went to
  code_en whole, and at the walk to the houses the arena could no longer
  load an actor's overlay ("ovlmgr: Out of Memory" through
  `fault_AddHungupAndCrash`, route-en hung at frame 10923). m_string.o's
  text is in code_en already: a few bytes there.
- **Who takes a name.** Every caller of the name loaders
  (`mNpc_LoadNpcNameString`, `mNpc_GetNpcWorldName*`,
  `mNpc_GetRandomAnimalName`, `mNpc_GetAnimalPlateName`) was listed with
  what it does next with the buffer: free strings of messages (the moon
  viewing's, the fishing's, the reserve's, the plate in front of a house,
  the guide's coloured string) and of letters (the bulletin board's
  posts, the remail, the event, birthday and goodbye letters, a moved
  animal's), the font's line (the map, the tag, the address book), a
  mail's recipient or sender (`mMl_set_mail_name_npcinfo`, kept in the
  save), and the message window itself. All of it takes tokens already
  (the items'), but three: the speaker's plate (`func_8009D308_jp`
  centres it by its bytes' width, `func_800A2BB0_jp` draws it with
  `mFont_SetLineStrings_AndSpace`), the speaker's name put in a page
  (`mMsg_CopyTalkName` copies the bytes), and the letter board's header
  when the letter is addressed to an animal (m_handbill.c builds the
  line with the name's bytes, and measures them after the font's line
  draws it). The en code puts in the name in each (m_msg_main.c,
  m_handbill.c).
- **Kept in the save.** A mail to or from an animal keeps its token in
  its personal ID; the token stands for the same name while the table's
  order stays (the items', then the names by number), as an item's
  number does.
- **On screen** (make messages-check-en, make board-check-en). Tom Nook,
  with his token and with it off (the special names' table emptied in
  RAM: his name cut to 6): the page that puts in his name (message 25)
  where the model has its four lines both ways; the plate's ink with the
  whole name at frame x 143-251, the model's 143.3-250.7, centred as
  `func_8009D308_jp` centres it (the plate placed by the cut name's ink,
  whose right edge is within 0.1 of the model; the plate is drawn at the
  screen's 1.954 frame pixels to the pixel, not the window text's 2.005).
  The 38 animals' names loaded as the game loads them
  (`mNpc_GetNpcWorldNameTableNo`), each its token; one of 7 letters put
  in a page as a free string (message 374), its four lines where the
  model has them. The letter board: a shop letter addressed to that
  animal, the token in the recipient's name, its header with the whole
  name and every line where the model has them.

## Catchphrases longer than 4 (phase 5, the menus' strings)

- **The field.** An animal's catchphrase is 4 bytes
  (`ANIMAL_CATCHPHRASE_LEN`, `Animal_c.catchphrase`, kept in the save),
  loaded from the string bank when an animal gets its default data or
  has its catchphrase reset (`mNpc_SetDefAnimalInfo`,
  `mNpc_ResetWordEnding`): the string each animal's default data names
  (`NpcDefaultData.catchphraseIdx`, in the file at vrom 0xE03000, 6 bytes
  an animal, 218 of them). Of the 215 strings they name, 198 are longer
  than 4 in English (5 to 10 letters). They were cut, and a catchphrase
  ends most of what an animal says.
- **Tokens in the string bank.** Their records follow the names' in
  `item_en_names` (tools/af_items.py: no article, 3 or 4 cells), and
  their tokens are laid over the en string bank in their words' place
  (af_items.py writes `assets/jp/en/string_tokens.txt` in the en tree,
  which the string bank's compile takes as `--overrides`): the game loads
  and copies a token as it did the words, with no code for it and
  nothing in code_en. Those strings are only loaded as catchphrases: none
  of the string numbers the code loads by a constant is one (9 such
  loads in the decomp's C and asm), and the loads through tables (names,
  counters, units, credits) take other ranges.
- **Who takes a catchphrase.** `mNpc_GetWordEnding`'s one caller,
  `mMsg_CopyTail` (STR_TAIL), which copied its bytes into the page: the
  en build puts in the words (m_msg_main.c, as for the speaker's name).
  The text editor never shows an animal's catchphrase: its catchphrase
  mode starts with spaces (`func_80884B30_jp`) and copies its 4 bytes
  over the old one at the end (`func_80884370_jp`), so a catchphrase the
  player gives stays 4 letters. Without an animal behind the speaker the
  code puts in a placeholder of its own (`l_no_ending_npc_ending`, kana),
  as it does for the name (`l_no_name_npc_name`); no message the game
  shows takes either (TODO(verify)).
- **The layout.** The message bank is laid out with what the game fills
  in counted in average letters; the speaker's name now counts 9 and the
  catchphrase 11 (the widest the tokens stand for: 56 and 70 pixels at
  the GameCube's letters, 6.5 pixels a letter on average). 38 of the
  11,752 messages come out laid differently: 35 pages filled anew, 5
  split and 111 with lines moved on, where 11, 4 and 103 were.
- **Kept in the save.** `Animal_c.catchphrase` holds the token, which
  stands for the same words while the table's order stays (the items',
  the names', then the catchphrases by their strings' numbers).
- **On screen** (make messages-check-en). The 198 loaded as the game
  loads one (`mString_Load_StringFromRom` into 4 bytes), each its token;
  the widest (70 pixels, 9 letters) put in a page by STR_TAIL, Tom Nook
  speaking with the town's first animal behind him (`Npc.npcInfo.animal`
  set to the save's, its catchphrase loaded so): the page's four lines
  where the model has them, the plate with the animal's name.

## The bulletin board (phase 5, the menus' strings)

- **The posts.** Fifteen in the save from `common_data` + 0x2F6A, 0x68
  bytes each: the text (0x60 bytes) and the time it went up, an empty
  one with `mTM_rtcTime_clear_code` as its time (`code/m_notice.c`, split
  from `6C97F0` in matching.patch; ac-decomp's `m_notice.c` is the map).
  A new game's board starts with letters 30 to 33 of the shop letters'
  bank (`D_8010B4A0_jp`), loaded straight into the first four posts with
  `mHandbill_Load_HandbillFromRom` and dated now (`func_800A5BC4_jp`,
  mNtc_SetInitData), the other eleven empty (`func_800A5B50_jp`). A post
  goes up (`func_800A5D30_jp`, mNtc_notice_write) in the first empty
  place (`func_800A5CB0_jp`), or, the board full, last after the others
  move up and the oldest goes; the player's from the board
  (`ovl__00797A50`), the game's from `func_800A5F08_jp` and
  `func_800A65C4_jp`, which load a letter into a post of their own first
  (letters from 0x1A4 on). These four match.
- **The board's text** (`ovl__00797A50`, submenu program 6, now a C
  file in matching.patch). `func_8089562C_jp` (mNT_set_page_dl, matches)
  draws a page: its frame, the post's number and date
  (`func_80895298_jp`, `func_80895328_jp`), its text from 63 pixels in
  and 17 below the date, and, the editor open over the board (the post
  being written, page 15, its length the editor's), the editor's cursor
  at its column's 12-pixel cell and its line (`((col - 1) + 0.3) * 12`,
  `line * 16`) and the end mark after the text. `func_8089542C_jp`
  (mNT_set_strings_dl) draws six lines, each ending at its 16th
  character or a newline (drawn only while the editor is open), at the
  font's scale 1.0 in (30, 0, 0); the end mark goes at the next line's
  start after a full line or a newline, else after the last character's
  cell. NON_MATCHING by one instruction: the cartridge copies `str` into
  the loop's pointer with `addu v0, s5, $zero`, the C with `or`; eleven
  forms of the loop tried. The editor (`ovl__0078CB80`) lays a notice
  (its kind 2) out the same way, 16 characters a line over 6 lines.
- **Writing a notice** (matching.patch: both match). Reading the posts
  (`func_80894560_jp`, mNT_Play_page_read), the stick's areas or the C
  buttons turn a page or go to the first or the last (the page compared
  `> 0`, the cartridge's `blezl`: `!= 0` gives `beqzl`); A goes to page
  15, dated now (`common_data.time.rtcTime`), its text cleared, and
  opens the editor on it (`mSM_open_submenu_new(submenu, 10, 2, 16,
  post)`: a notice, 16 characters a line); B or START closes the board.
  When the edit-end check answers (`func_80894D28_jp`, mNT_move_Obey;
  the check's menu info is the submenu's 16th), done puts the post up
  (`func_800A5D30_jp`) and closes the board, back to writing reopens the
  editor (its kind 2, no line length: the editor keeps its own), cancel
  goes back to the last post. While a notice is written the board rises
  (its menu info's y, 16 with four lines) to keep the cursor's line
  above the dial.
- **What English needs.** At 16 characters a line the English breaks in
  mid-word; at 96 bytes a post holds less than the game's own: of the 124
  letters from 0x1A4 (the notices the game posts), 110 are longer than 96
  bytes in the translation's bank with the checks' free strings (the
  longest 174), and would be cut; so are the new game's four (136 to 165).
  The GameCube keeps 192 bytes a post (`mNtc_board_post_c`, 0xC8 with its
  time) and breaks its lines by width (`mNT_strLineCheck`:
  `mNT_MAX_WIDTH` 192, `mNT_MAX_LINES` 6; by character, its text breaking
  its own lines; a seventh line is not drawn, as notice 430's, whose
  GameCube text has seven).
- **The posts in the en build** (changes.patch). A post keeps the
  cartridge's 96 bytes and its time in the save; the rest of the
  GameCube's 192 goes into the save's extension as a letter's rest does
  ("The letters in the en build"), under a key made of the post's 0x68
  bytes (FNV-1a: a post is copied whole when it goes up and when the
  posts move up, and its time keeps two posts of the same text apart),
  the record's header and footer rests empty. Where the rest comes from,
  `mHandbill_en_FileNotice` tells by the post's own 96 bytes: the notice
  the player wrote (`mHandbill_en_editBody`, whose first 96 bytes the
  board puts in the post), or the letter the loaders made last (the
  game's posts are letters loaded into a post just before it goes up).
  `func_800A5D30_jp` forwards to `m_handbill.c` (`mHandbill_en_NoticeWrite`:
  the rest filed, then the cartridge's), and the new game's board files
  each of its four posts after loading it; m_notice's text shrinks (0xCC0
  to 0xC30) and stays in place, its old bytes padded. The collection
  before each save keeps the records of the save's 15 posts as well as
  its 87 letters'; the letters the game's posts were loaded from are
  filed under their own key too and go at the next save. The cap stays
  0x2400: a post's record is at most 103 bytes (15 make 1,545), the
  English letters' average 70; with every letter and post of a save at
  its longest the oldest records would go first.
- **The board in the en build.** `func_8089542C_jp` forwards to
  `mHandbill_en_NoticeText`, which lays the post out as a letter's body
  (`mHandbill_en_DrawBody`, the letter board's writing body, now shared:
  192 pixels after the last space that fits, its newlines kept, six
  lines, a line's newline drawn while the editor is open, the end mark
  after the last character) in the board's (30, 0, 0); `func_8089562C_jp`
  reads a post whole (`mHandbill_en_GetNotice`: its 96 bytes, the
  extension's rest, spaces after) and puts the editor's cursor at its
  text's width less 7.5, as the letter board's. A notice is written in
  `mHandbill_en_editBody`: A clears it and gives it to the editor, back to
  writing gives it again, done copies its first 96 bytes into the post;
  the editor (`mHandbill_en_EditInit`) lays a notice (kind 2) out by width
  as a letter's body, 32 characters a line. The overlay shrinks (text
  0x1970 to 0x17E0; `EN_OVERLAYS`: fado gives the cartridge's 69
  relocations from the matching objects, the en build's are 67).
  `code_en` grows by 0xC0 bytes (to 0x1B620): the code that draws the
  letter board's body is the notice's too.
- **Measured** (`tests/emu_notice.py`, `make notice-check-en`, from the
  houses' state of route-en, the free strings set to known text). The new
  game's four posts hold their letters' first 96 bytes, and the
  extension their rests (44, 69, 40 and 57 bytes) under each post's key;
  five of the game's notices put up from a hook as `func_800A5F08_jp`
  puts one (536, 439, 528, 511 and the empty 463; 430 until its text was
  taken from another number) are filed the same way,
  none for the empty one. Opened, the board turned from the newest post
  to the oldest draws each post whole with the model's lines (each
  line's first and last text pixel against tests/emu_board.py's model of
  the letter board, at the board's place: its text from the screen's
  (63, 63), the frame's x of its left edge 129.35 and 1.960 frame pixels
  to the pixel, fitted on the 96 line ends of the nine posts, residuals
  within 1.7; the letter board's 1.954 leaves 2.1): all 54 bands; the
  model fails 12 with its lines broken 20 px early, 47 with a space 1 px
  wider, and the cartridge's 16 characters a line 46 (measured with 430
  in 439's place). The model's ink: a glyph's columns of 8 of 15 or more,
  and of 6 or more beside one (`emu_board.FRINGE`): the boards draw their
  text resampled at about 1.95 frame pixels to the pixel, which shows such
  a fringe (439's "Fireworks Festival", ending in an "l" whose right
  column is 6, ended 2.6 frame pixels past the strong columns: the one
  band the strong columns alone failed); the message window at 2.005
  shows none (17 of its lines would end 2 pixels short of the fringe's
  model), and its model and the menus' keep the strong columns. The board full, a
  post put up moves the others up, the oldest going, each keeping its
  rest. A notice typed on the dial (A on the board) is written in the
  letters' 192-byte body, 32 characters a line: 105 bytes kept whole, the
  cursor at the model's line and column, the board risen 16 rows, the
  four lines where the model has them and the end mark right after the
  text; finished (START, the edit-end check's first answer) the post
  holds its first 96 bytes and its time, the extension the other 9 under
  the post's key, and read on the board it is drawn whole. The save keeps
  the 14 posts' rests in both slots and drops the letters the posts were
  loaded from (14 records of 24); the load brings the extension back, and
  the notice is read whole again.

## The 2010 patch's messages (phase 5)

- **What is left untranslated.** Of the cartridge's 10,847 messages with
  text, the en bank (`make script`'s draft from the GameCube's script and
  our own text) still had the cartridge's Japanese for 909: no official
  counterpart (af_align) and none of ours. The AF Project's 2010
  translation (Zoinkity, `NAFE-WIP-2_12_2010.ups`; `make nafe` builds its
  ROM from the user's copy) has 175 of them in English, 207 still in
  Japanese, 7 mixed and 520 emptied.
- **Its bank.** The patch keeps the cartridge's index file (vrom
  0xCF9000) and moves its text to a new file at 0x1914000 (found through
  its dmadata; its ROM is checked by md5 first, `tools/nafe.py`), so its
  message n is the cartridge's n where its codes are the cartridge's: of
  the cartridge's 11,650 messages with a string or an end code, 10,166
  have the same ones in the same order in the patch. Of the 175, the
  codes say otherwise for some: an end
  that goes on to the next message where the cartridge's stops (or the
  reverse), a choice or a next message the cartridge's has not, talk
  orders added or moved. In some numbers the patch has other text or
  another flow.
- **What the en build takes** (`tools/af_nafe_text.py`, at `make rom-en`
  from the user's copy, laid over the bank by `af_text.py compile
  --overrides`; never committed, and nothing without the patch's ROM or
  the draft): a message whose en text is the cartridge's own, whose patch
  text is English (Latin letters, no kana), and whose codes that steer
  the game are the cartridge's, the same in the same order with the same
  arguments. Free to differ: page breaks, pauses, colours, scales and
  voice switches, and the names, dates and town the game always has at
  hand. The talk orders count, in every slot. Slot 0 of `DEMONPC0` is the
  villagers' talk animation (ac_npc2 `func_80998740_jp`, ac_npc
  `func_80976284_jp`: a table by the animal's kind), and the patch often
  moved those orders to the beats of its English; but the gyroid acts on
  slot 0 (ac_haniwa `func_8096B7AC_jp`: set, it reads the choice made;
  its conversations start at messages 0x925 to 0x935, D_8096C458_jp), and
  ac_npc_rtc, ac_npc_p_sel and the player's code read it too. Slot 9 is
  read by the shops' masters, the post office and the events' actors to
  act (ac_npc_conv_master, ac_npc_shop_master, ac_npc_post_girl,
  ac_ev_yomise...), slot 1 by the events' and the villagers', 2 and 8 by
  the villagers'. Which actor says a message the bank does not tell, so
  no talk order may differ from the cartridge's. Also refused: a string
  the game fills for one message (a free string, an item,
  `STR_DETERMINATION`, `STR_RNDNUM`, `STR_MAIL`) that the cartridge's
  message does not print (it would not be filled).
- **The talk orders put back.** Where the codes differ only in the
  orders in slot 0 (the patch moved them to the beats of its English,
  added some, dropped some, changed a value), the patch's are dropped
  and the cartridge's put back (`af_nafe_text.recover`): the same values
  in the same order, each between the same codes that steer the game as
  on the cartridge, so the game does what the cartridge's message makes
  it do whoever speaks; where in the English each goes, segment by
  segment between those codes: at one of the patch's own order's places
  when the orders align (an order-preserving match by how far into the
  segment's text each is, a place of the patch's order of another value
  allowed), else at the start of the segment, a page, a wait for A, a
  line or a word, as the cartridge's follows, nearest its place in the
  text. 84 taken as they are and 46 with the cartridge's orders, 130 in
  all; 38 refused for their codes, 7 for a string (a first version took
  129 with the patch's own orders in slot 0, until the gyroid's reading
  turned up). A page of five lines is split at four as the draft splits
  the bank's (`af_text.split_pages`); the compile lays the messages out
  for the en font like the rest of the bank. Messages: 9,818 in English
  of the 10,847 before, 9,947 after (91.7%); 779 keep the
  cartridge's text.
- **Credit.** The English of those 130 messages is the AF Project's
  (Zoinkity's 2010 translation), taken from the user's own copy of their
  patch at build time, as their 42 labels are ("The 2010 patch's
  labels"); README says so.
- **Measured** (`tests/emu_messages.py`, `make messages-check-en`, from
  the houses' state of route-en). The en tree's compiled bank holds the
  130 as the patch's text laid out for the en font, and their talk orders
  in slot 0 are the cartridge's, the same values in the same order (all
  130). Each message whose first page needs no speaker (text, pauses,
  colours, talk orders) and waits for the player (`<BTN>` or `<MSGEND>`:
  a page that clears itself after a pause, or a message that closes after
  a time, is gone by the screenshot) is asked for as an actor asks
  (`mMsg_request_main_appear` on the base window, white, no speaker): 49
  of the 130, and two of the GameCube's as controls. Its first page's
  lines, each line's first and last ink pixel (a colour under 150: the
  window over the field is 216 and up, its text dark or coloured), are
  where the en font's model puts them: rows 153 + 16k, the frame's x
  127.18 + 2.005 per pixel (fitted on 316 line ends of 51 messages when a
  first version took 129, residuals within 1.9; this build's 326 within
  1.5; the next page's arrow, from x 516, left out): 0 of the 177 lines
  fail (163 with text); the model fails 146 of the 163 with a space 1 px
  wider and all with the lower-case letters 1 px narrower. The window
  draws its text at 2.005 frame pixels to the pixel, the letter board's
  at 1.954 and the bulletin board's at 1.960 ("The letter board in the
  en build", "The bulletin board"): why they differ is TODO(verify).
- **The orders, played.** Each message taken that plays to its end without
  a choice or a jump (it ends with `<MSGEND>`; no choice, next message,
  luck or quest code) and has talk orders, 38 of the 130, is asked for the
  same way and played through, A pressed each time the window waits for it
  (`MessageWindow.mainIndex` 2, mMsg_INDEX_NORMAL: measured, 3 while it
  types, 0 once hidden); the talking animal's order in slot 0 is read
  every frame (`demo_data` + 0x60: `orderData[DEMO_ORDER_NPC0][0]`,
  m_demo.h; the orders are cleared only when a conversation starts and at
  the scene's start, `mDemo_Init_OrderValue`). Its values as they change
  are the message's orders as the window meets them (orders with no
  character, pause or page between them land in one frame), in all 38;
  first measured on the cartridge's own message 2171 (0xff, 0x17, 0xff,
  0x07), whose English now puts the same four in the patch's three pages.
- **Left for later.** The 45 refused differ in what the game does (an
  end that goes on, a choice, a next message, a string not filled): their
  text is ours now ("Our own messages"), and the draft holds it, so the
  tool's candidates are 864 (130 taken, 734 not in English in the patch).

## Our own messages (phase 5)

- **Which.** The 45 messages the 2010 patch has in English that the en
  build refuses ("The 2010 patch's messages": 38 for their codes, 7 for a
  string), translated by us from the cartridge's Japanese (the dump's,
  `build/gc/n64_jp.txt`), not from the patch's English:
  `script/message/ours.txt`. Ten are spares (the opening's five, four
  event notices', one more: our "extra", the word the GameCube's English
  gives its own) and three are debug messages that ask the developers to
  be told if they show, in the gyroid's polite speech (a verbal tic of
  its own on the cartridge, 2341 to 2359; the GameCube's English renders
  it formal, and so does ours, here and in the saving message beside
  them). The other
  32: the villagers (an errand not done, a first meeting late at night, a
  fondness that wore off, visiting other towns, the furniture and a
  letter on the part-time job, telling the time by the sun, letters not
  written, holes left open, winter, a new catchphrase), the shop's
  refusal of an order with its second voice repeating each sentence's
  end, the Controller Pak's warning (six, one a personality's, each going
  on to its own message), a broken town record, Mr. Resetti on coming out
  at every RESET, the morning aerobics, and the six questions whether the
  player is done for now, before the choice to save and quit or save and
  keep playing (choice strings 0x1AE and 0x1AF).
- **The rule.** Each keeps the cartridge's codes that steer the game, the
  same in the same order with the same arguments, and prints no string
  the game fills for one message that the cartridge's does not
  (`af_text.steering` and `af_text.filled`, moved from
  `af_nafe_text.py`, which uses them). Pauses, colours, scales and page
  breaks are placed for the English. A colour's count (`COLORCHARS`'
  last byte, the characters it colours, spaces included, as the
  GameCube's English counts them) is the English word's. The shop's
  second voice repeats the English sentence's end in small letters, a
  `CHARSCALE` before each as on the cartridge. Codes the rule calls the
  English's own may be added: the en build's capital (`CAPTIALIZE`,
  before a catchphrase that starts a sentence: `SHAPING`) and the strings
  the game always has (`GLOBAL`: `STR_AMPM`, and a villager's whole time
  again where the cartridge's says only the seconds have moved on: the en
  build prints them as two digits).
- **The rest of the bank's Japanese.** After the 45, 734 messages kept the
  cartridge's text; 717 of them had Japanese in it, and they are ours now
  (`script/message/`, each file's header says what it holds):
  `spares.txt`, 339 developers' labels (269 spare slots, who or what each
  was kept for and its number; 28 rumours' placeholders; 42 script bugs'
  labels); `tests.txt`, 35 test messages and dummies (the developers'
  checks of strings, choices, colours, scales, offsets, sounds and moods:
  every code where the cartridge has it, a test's single kana a letter);
  `gyroid.txt`, 10 messages of an early house gyroid (898 to 907), in the
  gyroid's formal English; `greetings.txt`, 192 of an early set of the
  villagers' greetings (19 to 210: a first meeting, meeting again someone
  known in another town, a villager moved to the player's town, the day's
  greetings, after weeks or months away), by personality and time of day,
  among them a personality the final villagers do not have (a Kansai
  speaker, given a folksy English); `nook.txt`, 21 of an early version of
  Tom Nook's shop (732 to 752), in his English's polite "yes" and "hm";
  `villagers.txt`, 27 more villagers' messages (letters kept and shown,
  Children's Day's carp streamers, the summer's fireworks); `postoffice.txt`,
  16 of the post office's clerks about the Controller Pak (7125 to 7142:
  it cannot be read, its notes are full, it is broken and repaired), the
  curt clerk's asides in grey; `letterlines.txt`, 76 letter-like lines
  (7167 to 7262: thanks, news, a present and where it came from, with the
  free strings the game fills), laid out for the message window;
  `visitors.txt`, the sailor washed up on the beach (9219). The other 17
  have no Japanese in them (music notes, numbers, codes, a "?!": 2836,
  2933, 4002 to 4004, 5442, 5443, 5818 to 5820, 5895, 5896, 8260, 9454 to
  9456, 10392) and stay as they are.
- **In the build.** `make script` lays `script/message/*.txt` over the
  draft (no longer every `script/*.txt`: `ui.txt` and `items.txt` are not
  messages); `make rom-en` lays them over the compiled bank again, after
  the 2010 patch's, so that a change to them needs no new `make script`.
  `af_text.py check` and `compile` with `--reference` refuse a message
  laid over that breaks the rule (`laid_problems`: the 2010 patch's
  messages pass it by construction). The 45 are laid out at the window's
  192 pixels as written (none moved by the layout, the widest line 188 by
  its model), and the bank's check has the same 64 warnings before and
  after. Messages in English (Latin letters, no kana): 9,947 of the
  10,847 before, 9,992 after (92.1%); 734 keep the cartridge's text, none
  of them in English in the 2010 patch.
- **The bank in English.** With all 762 of ours, `make rom-en` lays them
  over the bank with no error, the layout moves none of them (the same 11
  pages filled anew, 4 split and 103 with lines moved, all the
  GameCube's), and the bank's check has the same 64 warnings. Of the
  10,847 messages with text, 10,709 are English by the count above (Latin
  letters, no kana), 104 are the GameCube's English with its dash drawn as
  the long vowel mark (`ー`, which the count takes for kana), and 34 have
  no letters (marks, numbers, codes); none is Japanese.
- **Measured, all 762** (`make messages-check-en`, every file of
  `script/message/` given with `--own`). The en tree's compiled bank holds
  the 762 as written, laid out for the en font, each with the cartridge's
  codes that steer the game and no string it does not fill (all 762,
  against the dump). 521 of their first pages need no speaker and wait for
  the player: asked for as an actor asks, their 1,031 lines with text are
  where the font's model puts them (0 of the 1,624 lines of the 572
  messages shown fail, the 2010 patch's 49 and two of the GameCube's
  among them). The 95 of ours that play to their end with talk orders
  (most of them spare labels that carry one) were played through, the
  talking animal's order the cartridge's sequence in all 95. The check
  now takes a page's screenshot once the window has waited for A for 10
  frames (`mainIndex` 2, at most the 300 frames it used to wait every
  time): a four-line page has typed out by frame 126, and the run of 572
  messages takes well under half the time.
- **Measured** (`make messages-check-en`: `tests/emu_messages.py --own`).
  The en tree's compiled bank holds the 45 as written, laid out for the en
  font (all 45), each keeping the cartridge's codes that steer the game
  and printing no string it does not fill (all 45, against the dump). The
  29 whose first page needs no speaker and waits for the player are asked
  for as an actor asks for a message: their 62 lines with text are where
  the font's model puts them (0 of the 253 lines of the 80 messages shown
  fail), each colour on its English word (looked at: the Controller
  Pak's, the post office's). The 9 that play to their end with talk
  orders were played through: the talking animal's order in slot 0, read
  every frame, is the cartridge's sequence in all 9 (the longest 20
  orders). The check first played 2 of ours: it took codes 71 to 73, the
  en build's `STR_AMPM`, `CUTARTICLE` and `CAPTIALIZE` (`LUCK_6` to
  `LUCK_8` on the cartridge, in neither game's bank), for luck codes that
  go on to another message; it counts them as strings now (the 2010
  patch's 38 unchanged).

## The strings and letters still in Japanese (phase 5)

- **What was left.** After the messages, 96 strings kept the cartridge's
  text (95 with kana), and the drafts held Japanese the GameCube never
  translated: its banks keep some entries as the Japanese bytes, which
  its charset reads as accented letters and pictures (and the draft's
  fallback as question marks). Found by reading the GameCube's bytes with
  the cartridge's charset (kana): strings 156-355, messages 5905, 5906,
  6123, 6124 and 11288-11296 (spare labels: ours, `spares.txt`), shop
  letter bodies 192-196 (spare slots: ours). Read against the cartridge,
  the strings' English was wrong in three places the player sees (the
  town's name, the fish's size, the staff credits), and the letters'
  numbering in the event notices.
- **The strings, ours** (`script/string/`, 423, laid over the draft by
  `make script`). `names.txt`, 156-355: an early list of 200 animal names
  (31 are names the final animals have, at other places); no code reads
  them: every call of the string loader in the decomp's C and asm
  (`mString_Load_StringFromRom`, `mString_Load_NumberStringAddUnitFromRom`)
  takes other numbers (the catchphrases of the animals' default data,
  segment 00E03000: 16-155 and 577-668; the special characters' names,
  0x205-0x218 and 0x4E0-0x4E2; the random words' bases; the fortune
  teller's, the shops', the box's and the notices' tables; the unit), and
  the GameCube left them as bytes. `catchphrases.txt`: 22 the GameCube
  has a placeholder word for and 131, none an animal's (the default data
  name none of them). `spares.txt`: 55 spare slots. `counters.txt`: the
  shops' counters for long thin things and for small animals (1427-1441,
  1457-1471; the shop overlays' `D_809A9C18_jp` picks a counter by the
  item's kind: the tools, kind 2, and one fruit, the fish, kind 3, and the
  insects, kind D), which only the two sell-all messages print (4234,
  5949), whose GameCube English prints the number alone. `resetti.txt`:
  two of the phrases Mr. Resetti has the player type (1166, 1169), whose
  GameCube English has a picture the cartridge's charset lacks.
- **The town's name.** The message code (`STR_COUNTRYNAME`,
  `src/code/m_msg_main.c`) and `mLd_AddMuraString` put string 484 after
  the town's name: the cartridge's word for village, which the GameCube's
  bank has empty (its English says the name alone) and the draft had
  kept in kana. Ours is empty (`units.txt`): the loader clears the
  buffer to spaces (`mString_Load_StringFromRom`, size 0), the length is 0
  and nothing is put in. The messages check showed no page with a string
  code (`first_page`), so it never saw the kana; it now measures a first
  page with the town's name in it, the name the state's save holds
  (`common_data` + 0x2F60, one character in route-en's): 7 of ours (4,
  52, 90, 98, 103, 8032, 10178), every line where the model of the name
  alone puts it (the kana would have added some 24 pixels).
- **The fish's size.** The fishing tourney and the pond (`ovl_Ev_Angler`,
  `ovl_Turi`) print a size as its number and string 670 after it
  (`mString_Load_NumberStringAddUnitFromRom`): the cartridge's number is
  in centimetres and its unit metric; the GameCube's English has an
  imperial unit there (its code converts the number), which the en build
  printed after the centimetres with no space. Ours, `units.txt`: the
  metric units with a space before them (669-672; only 670 is read).
  In the running game (`make menus-check-en`: a hook calls
  `mString_Load_NumberStringAddUnitFromRom` as the tourney does), a fish
  of 40 is the 5 bytes of "40 cm". On screen (`make messages-check-en`):
  the tourney's host speaks 0x2305 to 0x2307, whose talk order has it
  put the size in free string 2 (`func_809D6694_jp`), and they go on to
  the message it picks (`func_809D5F68_jp`: its event clip's + 2, or
  0x1122 for the record's holder); the three that show a size (0x1112,
  0x1116, 0x111A, on their second or third page) shown with the size of
  a fish of 40 put in as the tourney puts it, A pressed on the pages
  before it: the size's page where the model has its line, "40 cm"
  with its space.
- **The staff credits** (`credits.txt`, 1258-1364). The draft had taken
  the GameCube's own staff list by number (other people, another order).
  The cartridge's are drawn by the orange box (`ovl_Mikanbox`):
  `func_80AA3D08_jp` loads ten lines of 15 bytes from 1258 + a page's
  first line (`D_80AA4D54_jp`: sixteen pages), `func_80AA3D94_jp` draws
  them from x 150 at 0.9, a page's first line and the lines listed in
  `D_80AA4D68_jp`/`D_80AA4D69_jp` at 0.85 of that: the job titles, as
  their strings show. Ours: the cartridge's list line for line, a title
  where it has a title, a name where it has a name, a blank where it has
  one, 15 bytes at most (the GameCube's lines, ten spaces and a name, were
  cut to the first letters); given name first, 14 names longer than 15
  with the given name's initial. The widest line is 104 pixels at the en
  font's widths, 94 at 0.9. In the running game (`make menus-check-en`:
  a hook loads each line with `mString_Load_StringFromRom` at the box's
  15 bytes) the 107 lines are this file's. The box draws them during K.K.
  Slider's show: event 0x21's save area is his (`m_kankyo.c` reads it as
  `KKData`, and dims the lights for the staff roll:
  `staffroll_light_proc_start`), its 0x4000 the staff roll's flag. The
  box's constructor puts its clip at `common_data` + 0x100E0, whose
  first two functions load a page and draw it; K.K.'s draw
  (`func_80A8AF68_jp`) calls the second after his own, with the game,
  the show's frame (it sets the alpha: none before 20, all from 60 to
  183) and the page, while the flag is on. On screen (`make
  menus-check-en`): the box spawned in play, each of the 16 pages loaded
  as it loads one and drawn as K.K.'s draw has it drawn, from a hook on
  `mBGM_main` (after the play has drawn: the text over the scene, as in
  the show), with his event save area made and the flag on; drawn at the
  show's frame 100 and at frame 0 (the same work, invisible), the ink
  what moved at least half the way to the text's white. The 107 lines
  where the model has them (the board's measure of the screen, from x
  150 at 0.9, the titles at 0.85 of that), within a screen pixel more
  than the windows' tolerance: scaled, each glyph lands on whole pixels
  (the left ends within 1.4 frame pixels of the model, the right ends
  from 1.3 left to 3.2 right, the lines with a "w" furthest right; a
  letter more or less moves an end 9 to 16).
- **The event notices** (mail_body 420-462: the bulletin board posts
  them, `m_notice.c`, and some come as letters). Read letter by letter,
  the GameCube's are not all at the cartridge's numbers: it dropped some
  of the cartridge's events or holds them on other dates, wrote notices
  for its own (a groundhog, a kite, a holiday of July, a meteor shower, a
  harvest festival, the clocks' changes), and moved the rest by a number
  or two. 17 of the cartridge's are the same notice at another number:
  `af_text.LETTER_REMAP` (numbers only), the draft takes the GameCube's
  from there (the free strings checked as before). 10 have none, ours
  (`script/mail_body/notices.txt`, from the cartridge's): the Doll
  Festival's and Children's Day's sales, White Day's mailbox notice, the
  cherry blossoms' forecast, the spring sports fair's date, the summer
  tourney's last week, the morning aerobics' dates, the fireworks every
  Saturday of August, the thirteenth night's moon, the post office's note
  on the fall sports fair. And 500, a treasure hunt whose GameCube text
  names an item the cartridge's does not (ours, with the cartridge's free
  strings only). The rest of 420-462 are the same notice at the same
  number.
- **The other letters.** Six villagers' White Day letters (103-110: the
  day a present is given back for Valentine's) are the same letters in
  the GameCube's English with the day said to be Valentine's: ours
  (`whiteday.txt`; the header and footer stay the GameCube's). The
  mother's letters (300-419) and the other shop letters are the same
  letters freely rewritten; a few of the mother's trade a holiday of
  Japan for one of the West, her own news: left as they are. One
  villager's opening (vmail_a 172) greets the sender by its own name
  (free string 1): ours (`script/vmail_a/openers.txt`). The other four
  the check flags (67, 180, 318, 368) print the player's name, the
  animal's or the other town's, which the game fills for every villager
  letter (`mNpc_SetRemailFreeString`: 0 and 1, and 14 and 15 for a letter
  from another town, 368's block): left as they are, its 4 warnings.
- **Characters.** The draft's fallback had turned the GameCube's
  semicolons into question marks (7 messages and 4 letters, a question
  where a clause went on): commas now; a slash, a hyphen; the GameCube's
  drop and anger mark, the N64 font's own (💧, and ✚: the font's glyph
  0x5C is an anger vein, and the cartridge's letter 508 has it where the
  GameCube's has its mark); its sigh's mark, the wave dash. Message 5373
  names the GameCube controller's pad with a sign the font lacks: ours
  (`script/message/fixes.txt`, from the cartridge's, which names the
  Control Pad and the 3D Stick, the Control Stick).
- **Counts.** Ours: 776 messages, 423 strings, 23 letters (and 17 taken
  from another number). No entry of the message, choice, string or
  letter drafts has kana now but the GameCube's dashes and wave dashes
  (`ー`, `～`), and no character of the GameCube's falls back to a
  question mark.
- **Measured.** `make messages-check-en` (every file of
  `script/message/` given): the bank holds the 776 of ours as written,
  laid out for the en font, each with the cartridge's codes that steer
  the game and no string it does not fill; 536 of their first pages are
  shown (55 of the 2010 patch's, 2 of the GameCube's as controls): 593
  messages, 0 of their 1,679 lines off the model; 134 played to their end
  (96 of ours), the talking animal's orders the cartridge's in all.
  `make notice-check-en` puts 439, ours, up on the bulletin board: its
  lines where the model has them, once the model counts the fringe the
  boards' resampling shows ("The bulletin board"); the 17 notices taken
  from another number and the other 10 of `notices.txt` have the board model's
  layout within six lines with the checks' free strings (not each put
  up). `make board-check-en` (11 boards, 508 in 430's place),
  `write-check-en`, `letters-check-en`, `save-check-en`,
  `menus-check-en` (now also the string bank as the game loads it: the
  credits' lines, what follows the town's name, a fish's size),
  `emu-check` and `emu-check-en`: 0 failures; `make test` 66. The credits
  and the fish's size were seen on screen afterwards (above).

## The official text's codes and the GameCube's own (phase 5, the QA pass)

- **The codes.** The draft took the GameCube's message for each number
  `af_align` matched with the cartridge's (same, plain, edited), with the
  GameCube's own codes; only the messages laid over (the 2010 patch's and
  ours) were held to the cartridge's. Read against the cartridge's
  (`af_text.steering`), 688 official messages steered the game otherwise:
  the talking animal's orders moved to the beats of the English, dropped
  or added (slot 0 in 314 messages; slots 2 and 8, which end many
  villagers' talks, dropped in some 30; a quest's order in slot 5 given as
  `DEMONPCQST` with another value in 30), a choice's strings (191; one of
  the GameCube's own past the cartridge's 460, the post office's fourth
  answer), a next message at random among the GameCube's own (one past
  the bank's 11,752: 1889 asked for 0x3057), a question the cartridge's
  message does not ask (12), a luck code dropped (7), a sound trigger more
  or less (9), an end where the cartridge's message goes on. The en build
  runs the cartridge's code: it would do what the GameCube's game does
  there, or read past its banks.
- **The mend** (`af_text.mend`, in `draft`). An official message keeps
  its English and takes the cartridge's codes: in its text, the
  cartridge's arguments where the codes are the same ones in the same
  order (talk orders aside), a sound trigger the GameCube's has more or
  less in a run of them dropped or put by its neighbour
  (`_sounds_aligned`, an order-preserving match); after its text, where
  the two do the same (`_flow`: both or neither open a choice, force a
  next message, pick one at random; the same ending), the cartridge's
  codes whole, a choice's strings among them (a first version kept the
  GameCube's where as many answers led to the same messages; the quizzes
  showed why not: their answers are its own events); then the talking
  animal's orders of every slot dropped and the cartridge's put back at
  the beats of the English (`af_nafe_text.recover` with `any_order`;
  `recover` now also offers the end of a segment's text, after its last
  word, for an order the cartridge's has there). A full stop after a
  message's last code (9770's), which the window never reaches, is not
  text. Where it cannot (the codes in the text otherwise, or what it does
  after its text), the draft takes the cartridge's Japanese, to translate
  (`TODO translate`, the GameCube's text "steers the game otherwise"): the
  2010 patch's English where it serves, else ours. 627 mended; 8 left to
  the cartridge's (seven empty messages, 2930-2937, and 4225, the 2010
  patch's); 19 ours (`script/message/flow.txt`: luck put back to neutral,
  Tom Nook's talks on the house and the job's end, questions the
  cartridge's villagers do not ask). Every official message now steers
  the game as the cartridge's.
- **Twins** (`af_text.MESSAGE_REMAP`). Five meetings late at night
  (11198-11211) have, codes and all, the Japanese of five others
  (11724-11737); the GameCube wrote the first for a meeting of its own (a
  villager moved in from another town, free string 10). The draft takes
  the GameCube's text of the twin, numbers only.
- **The GameCube's own events.** The GameCube moved the calendar's talk to
  its own events, keeping the numbers. Found by the cartridge's event
  words in the Japanese against the English (White Day, the Doll
  Festival, Children's Day, the moon viewings, the fireworks...) and by
  the months the Japanese names (`Nがつ`) against the English's: White
  Day (March 14th) became its Groundhog Day (11 messages), Children's
  Day's carp streamers its autumn Harvest Festival (12), the mid-autumn
  moon viewing its August meteor shower (19, the announcement on the
  Thirteenth Night's evening among them), the Thirteenth Night its harvest
  moon (12, printing the harvest moon's date, free strings 16 and 17, where
  the cartridge's prints the Thirteenth Night's, 18 and 19: the talk
  overlay fills both, from the old calendar), the fireworks every
  Saturday in August its July 4th (12), the sports fair's April 20th and
  Health and Sports Day (October's second Monday) its March and
  September (10), a quiz's Christmas Eve its cherry blossoms. Ours from
  the Japanese: `events.txt`, `moon.txt`. The choice bank too: entries
  187, 191 and 193 (Children's Day, the moon viewing, Christmas Eve) were
  its Father's Day, meteor shower and a winter festival
  (`script/choice/events.txt`). Left as they are: talk at the events
  both games have, which the GameCube words its own way (a holiday season
  for Christmas, a wishing well for the shrine's plaza).
- **The GameCube's own things** (`things.txt`): its console's clock in the
  message for a stopped clock (the Game Pak's), a mayor and a museum,
  the island's boatman in a quiz's joke, spring cleaning (the cartridge's
  is the year's end), and two of Mr. Resetti's asides on them.
- **Travel and the Controller Pak** (`travel.txt`, 89). The GameCube's
  text for the station's master and for the villager who starts the game
  (one set per personality) names its memory card in a slot of the
  GameCube; the cartridge's names the Controller Pak, in the controller,
  and the Game Pak. The orange notices are the same words in every set,
  as on the cartridge.
- **Buttons** (`controls.txt`). Four of Tom Nook's and the police box's
  lessons named the GameCube's Y and X Buttons for the item screen and
  the map: on the cartridge the START Button and the R Button, in their
  colours.
- **Measured.** Against the dump: every message of the draft that is not
  left to translate, the official ones and ours, 11,538, has the
  cartridge's codes that steer the game, the same in the same order with
  the same arguments, and none prints a free string the cartridge's does
  not fill (no `TODO adapt` left; the compile, 0 errors and 0 warnings,
  the layout moving none of ours). `make messages-check-en`, every file
  of `script/message/` given with `--own`: the en tree's compiled bank
  holds all of ours as written; 700 messages shown, their 1,964 lines
  where the font's model puts them, and 245 that play to their end with
  talk orders played through, the talking animal's orders the
  cartridge's sequence in all 245. A first run failed one check, the
  name plate's: it took the plate for the box of its green, and the
  scene behind (the route made again, in the evening's light) had a
  yellow item by the mailbox of that green, which stretched the box to
  the right; the plate is now the rows' long runs of its green
  (`tests/emu_messages.py` `plate_ink`), Tom Nook's whole name's ink
  (143, 251) and the cut name's (155, 237) on the same screenshots, where
  the model puts them. Run again with it: 0 failures.

## The dial's small letters (phase 5, after the QA pass)

- **The cartridge's dial.** Z goes through five modes
  (`func_808854CC_jp`: the mode, the editor's state's byte 4, one on,
  after the digits the first; the ring, byte 5, back to 0; the kana row,
  the s16 at 6, -1): hiragana, symbols, katakana, the letters, the
  digits, and the editor opens on the hiragana (the init,
  `func_8088587C_jp`). The letters type capitals (their table,
  `D_80888594_jp`: A to Z, comma, full stop, newline, space). The code
  the dial gives comes from one function, for the ring it draws and for
  the code typed alike (`func_80885140_jp`: the mode's table,
  `D_808885C8_jp`, at ring + area round its count, `D_808885DC_jp`; the
  kana modes by rows of five). R is the convert command
  (`func_808857F8_jp`, command 7): the character before the cursor
  becomes its pair in `D_80888710_jp`, which pairs each capital with its
  small letter both ways; R's button shows the pair (`func_80886168_jp`
  looks it up into the state's 0x1E, `func_80887DF0_jp` draws the
  character before the cursor and it with the font). The 2010 patch
  opened the editor on the letters and made their table small letters
  (R for a capital), its label for the mode left as it drew it.
- **The en build: both cases, no kana.** The letters type small letters
  (abc) while a flag is set and capitals (ABC) when not. The flag is the
  state's byte 0x12 (`enLower`), which the cartridge never reads or
  writes: not in the editor's code, nor in the board, the bulletin board
  or the name entry, which read the state through the submenu's pointer
  at +0x106E0. In abc `func_80885140_jp` gives a capital's small letter
  (its code + 0x20, the pair `D_80888710_jp` holds), so the ring shows
  them and they are typed. The dial has no kana modes: Z goes from abc to
  ABC on the same ring, then to the symbols (mode 1), the digits (mode 4)
  and abc again, and the editor opens on abc, whatever it writes (a
  letter, a notice, a name, a catchphrase); nothing else sets the mode
  (the editor's asm stores to its byte 4 nowhere; what the bulletin
  board stores at 4 is its own state's). R turns the letter before the
  cursor into its capital, the cartridge's own command. In C first
  (decomp/matching.patch): `func_80885140_jp` and `func_808854CC_jp`
  match; `make verify` identical.
- **The panel.** The modes' panel draws each mode's label from a list a
  mode (`D_808888CC_jp`, segment 0x0C addresses: each list loads its
  label, I4 48x16, and draws it at its own four vertices, rows 12 units
  apart: hiragana's at the top, then the symbols', katakana's, the
  letters' and the digits'): the current mode's in red (225, 45, 0), the
  others in (205, 135, 80) (`func_80886CC4_jp`). `func_80886A60_jp`
  turns the buttons' textures' tables into RAM addresses, not this one.
  In the en build the panel shows the four modes from the top in Z's
  order, the fifth row empty. `tools/af_dial.py` writes, at `make
  rom-en`, our label for the small letters where the hiragana's was
  (drawn by hand at the height and weight of the 2010 patch's capitals,
  its art in the tool), and points the second, third and fourth rows'
  lists at the capitals', the symbols' and the digits' labels (their
  texture words, 0x0C0015AC, 0x0C0014A4, 0x0C0014FC). The editor
  (`mED_en_dialLabels`, from the init and Z) fills `D_808888CC_jp`: the
  letters' entry the row of the case the dial types, the hiragana's the
  other case's, the symbols' and the digits' their rows, katakana's an
  empty list (the end of the first row's, 0x0C001490), so the fifth
  row's list is drawn by none.
- **The symbols' ring.** The cartridge's 30 marks hold five Japanese ones
  (its comma, full stop, middle dot and corner brackets), which the en
  font draws with the cartridge's glyphs; the font has no other mark an
  English text wants but the equals sign. The en build's ring is the
  other 25 in our order (`af_dial.SYMBOLS`: a question and an
  exclamation mark, an apostrophe, a space and a newline first), its
  count (`D_808885DC_jp[1]`) and its ring's length (`D_808885B4_jp[1]`)
  25, five pages: `af_dial.py` writes them over the editor's data
  (.data.s) after checking the cartridge's. The ring turns (in
  `func_80886724_jp`) by a step a frame, one or, with A, two, and wraps
  when it equals the length: two-page turns from the last page step over
  25 as they step over the cartridge's 30 from its last but one, and the
  pages stay whole (the code is the ring and the stick's place round the
  count), as on the cartridge.
- **Where it lives.** The dial's segment (`segment_00A40000`, 0x17140
  bytes) has no free room: its lists, walked with the textures they load
  (the buttons' through segments 8 and 9, set from the overlay's tables),
  read all of it but four gaps of 8 bytes. The hiragana's label was
  free once the kana went, and holds ours. A first version kept the
  label and its list as data in the overlay's text (asm-processor takes
  `.word` and `.byte` in a GLOBAL_ASM block in text, which the en
  build's line model had left 0x2D0 bytes short of its 0x33C0), the
  panel's letters' entry swapped between ours and the cartridge's. The
  relocation table (`ovl__0078CB80_reloc`, 0x370 bytes) has room for
  214; the en build had 211, and the two calls of `mED_en_dialLabels`
  take two: 213. It finds `D_808888CC_jp` from the editor's state, the
  overlay's bss, at a distance the linker gives (`undefined_syms.ld`:
  `ABSOLUTE(B_80888AF0_jp) - ABSOLUTE(D_808888CC_jp)`, 0x224), which
  takes no relocation: fado relocates only references to symbols the
  overlay's objects define (`tools/fado/src/fado.c`). The first version,
  naming the table and its list, needed 220.
- **On screen** (`tests/emu_write.py`, make write-check-en). The dial
  opens on abc, the panel's entries in RAM its four rows (the letters'
  abc, the hiragana's ABC, katakana's nothing); Z gives ABC on the same
  ring, the symbols, the digits and abc again, the entries following;
  the symbols' ring in RAM is our 25 marks, both counts 25; d, R, e, a,
  r and, on the symbols, a question mark type "Dear?". The red label
  measured in each mode: the capitals' (row 2) give the panel's pitch
  across (1.375 frame pixels a texel) and its place, the digits' (row 4)
  a row's height (12 frame lines); the small letters' label is red in
  row 1 at x 144-187 and y 129-137 (the model's 144.4-185.6 and
  129.0-136.5), above the small letters' height only the b's stem
  (x 158-161, the model's 158.1-162.2), the symbols' in row 3, the
  digits' where the model has them, and the fifth row has no label's
  ink in any mode. The rest of the check types its sentences in ABC, as
  before.
- **The route** (`tools/route_newgame.py`) types the player's and the
  town's names on the dial with the same four strokes, now on abc: up c,
  right e, left a (down with A turns the ring), the names "cea". It
  reaches its milestones at the same frames as before (the name dial
  3289, the town's 4110, the station 9281, the houses 12151).

## The player-select actor's answers (after the other emulators)

- **What found it.** Played on ares (below, "Other emulators") from a
  saved town, the actor that greets the player at power-on (ovl_Npc_P_Sel2,
  the talk before the town loads) showed its answer for a new player in
  Japanese. None of our checks had powered on with a saved town's players
  before: the route starts a new game, the other checks a state in play.
- **Its choices.** Three functions build them from the players' names (10
  bytes an answer, `mChoice_Set_choice_data`, lengths 10) and the choice
  bank (`mChoice_Load_ChoseStringFromRom`), into a buffer of 4 x 10 bytes
  on their stack: `func_809BF244_jp` (the players, then a last answer at
  their count: with 1, a word of its own data, D_809C0C38_jp, 4 bytes by
  `mem_copy`; with 0, the bank's 41), and `func_809BF3E4_jp` (two players,
  then `func_809BF1E8_jp` loads the bank's 441 and 41 into the third and
  fourth answers). With 1 it is the question at power-on of which player
  plays; with 0, the question of whose house to remove (the first menu's
  second answer, the next menu's second, then its first), for 2 or 3
  players, and `func_809BF3E4_jp` for 4
  (`func_809BFBB4_jp`, after the talk order message 5138's choice sets).
- **The en build's loader wrote 24 bytes** (`Choice_EN_STRING_LEN`, its
  answers' width) into each buffer it was given; the cartridge's writes 10.
  The actor's stack keeps the choice window's pointer right after its 40
  bytes (sp+0x84 in `func_809BF244_jp`, sp+0x7C in `func_809BF3E4_jp`), so
  the answer at the third place of 2 players, the fourth of 3, or the
  fourth of 4 wrote spaces over it, and `mChoice_Set_choice_data` was given
  0x20202020. Measured (mupen64plus, a town of two players): asked to
  remove a house, the game hangs (no frame in 60 s after frame 1075, the CPU at
  0x80026084); the cartridge, the same town and answers, lists both
  players and its answer to cancel. ac_quest_manager's `func_80955814_jp` gives
  it 10-byte places too (the fourth one's 24 bytes reach 10 into the
  arguments' home of its own frame, which no caller keeps anything in).
  The loader also took its answer by DMA into a `char[29]` on its stack:
  the cartridge's 10 bytes and the 7 before them fitted, an English 24 and
  7 come as 32.
- **The fix** (`src/code/m_choice_main.c` in changes.patch). The loader
  writes its caller the 10 bytes the cartridge's does and keeps the whole
  answer (`mChoice_en_loaded`, four of them, each under the buffer it
  filled); `mChoice_Add_choice_data` takes the whole answer for a buffer
  that still begins with it, and `mChoice_Set_choice_data` forgets them
  once the window has its answers. The DMA goes to 32 bytes, aligned. The
  message window's choices (`func_800A0DF4_jp`, our C, buffers of 24) get
  the same answers as before; the actor's and the quest manager's, until
  now cut at 10 bytes, get them whole.
- **The word for a new player** (D_809C0C38_jp) is the first four
  characters of the bank's 121, the answer for a first visit, which the en
  bank has in the GameCube's English:
  `tools/af_answers.py` writes over it, at make rom-en, the token of that
  answer (`Choice_EN_TOKEN_MARK`: 0x80 twice, then 121 in two digits from
  0x81, as an item's token writes its number; no item's token has the mark
  second), after checking the word against the cartridge's overlay and the
  cartridge's 121 beginning with it; `mChoice_Add_choice_data` takes a
  token as the bank's answer, whole.
- **Measured** (`tests/emu_psel.py`, make psel-check-en: the houses'
  common_data stamped as the game stamps a save, its player copied into
  the next slots with other names, ids and houses, each town in both slots
  of a fresh flash): one player, which plays? the player and the bank's
  121; two, both players and 121; two, whose house to remove? both
  players and the bank's 41, and the game goes on; four, two players and
  the bank's 441 and 41, and it goes on; the answers whole, as the bank
  has them (13, 12 and 13 bytes).
  With ares built, the two players' town plays the same on ares.

## Other emulators (ares and Project64)

- **ares** (v148, `emu/ares/`: `build.sh` pins it and builds its N64 core
  with `headless.cpp` as `ares-headless`, and the ares app; `emu/ares64.py`
  drives it with n64emu's interface). The cartridge is identified by ares's
  own mia from the header's id, as the app does: NAFJ is Doubutsu no Mori,
  128 KB of Flash RAM, the real-time clock and a Controller Pak (its log of
  the en ROM); CIC 6102. A frame is a step of `gamePT->frameCounter`, read
  through the CPU's data cache (`dcache.readDebug`: ares emulates the
  caches, and RDRAM alone can hold a stale line); the RDP is paraLLEl-RDP
  on Vulkan (lavapipe without a GPU), about 37 VI fields a second here.
  The route plays as on mupen64plus: the name dial at frame 3288 (3289),
  the town's 4108 (4110), the station 9278 (9281), the houses 12147
  (12151); the cartridge's route on ares as well. The flash: a fresh
  cartridge with emu_save's town in slot 0 and slot 1 erased is repaired
  by frame 400 (slot 1 the image as it is), the town loads (code and
  town's id), and the game's own save on the way writes both slots,
  compressed, the extension whole and the checksum good; a second
  power-on with that flash and clock loads it again. ares's clock is the
  host's, kept in the cartridge's .rtc: a town saved in 2001 and loaded in
  2026 gets the letters of the years between, which filed rests into the
  extension (it went from emu_save's 5,000 bytes of text to a record), so
  its runs are not frame for frame repeatable as mupen64plus's are. The
  ares app itself, on a virtual display, shows the en ROM's title.
- **Project64 3.0.1** (the release from pj64-emu.com, under Wine 9 on a
  virtual display, its own video plugin; its window must not pause the
  game when it loses the focus, Auto Sleep=0). Its database knows the
  cartridge and the 2010 patch by the header's CRCs, with Flash RAM, 4 MB,
  the 32-bit core and SMM-Protect=1; the en ROM's CRCs are its own, so it
  plays with the defaults, and hangs at the N64 logo. The cartridge does
  too without its entry, at the same place: the game needs the
  self-modifying code method that protects memory (SMM-Protect=1 alone
  makes both boot; Flash RAM, 4 MB or the 32-bit core alone do not). With
  it the en ROM goes through K.K.'s talk (Enter is START, X is A), the
  name's and the town's dials and Rover's talk to the town's station, on
  its own video plugin and on GLideN64 (the plugin its first start
  offers). Its binary says writing the cartridge's clock is
  unimplemented (not tried). In its window: Options, Settings, untick
  "Hide advanced settings"; then the game's "Edit Game Settings",
  Recompiler, "Protect memory" among the self-mod methods.
- **Project64 4.0** (the development build 4.0.0.6807 from pj64-emu.com,
  under the same Wine; GLideN64, its default). Its database gives the
  cartridge the 32-bit engine, Flash RAM and the self-modifying code
  method Store Instruction (32bit=1, Save Type=FlashRam,
  SMM-StoreInst=1); the en ROM, by its own CRCs, gets the defaults. With
  them the en ROM plays the title's demo and stops a minute in ("Failed
  to translate PC to a PAddr: 0", emulation stopped); the cartridge
  without its entry stops there too. With Store Instruction alone the
  demo goes on, but K.K.'s talk stops it: "Failed to find a free
  register" in its log (the recompiler's x86 register cache,
  CX86RegInfo::Map_TempReg), and the cartridge with Store Instruction and
  Flash RAM but not the 32-bit engine stops in its K.K.'s talk the same
  way. With the 32-bit engine as well (the cartridge's entry, or the two
  ticks in its window with no entry) the en ROM plays: the new game
  through K.K.'s talk, both dials and Rover's talk to the town's station;
  and emu_psel's two players' town, put into its flash, loads. Which
  player plays lists both players and the new-player answer; removing a
  house asks whose with both players and the cancel answer, and
  cancelling goes on (where the en ROM hung before the loader's fix);
  choosing a player into the town writes both slots, compressed, the
  checksum good and the clock the host's; powering on again loads what
  it wrote. The save type can stay "Use first-used save type": it finds
  the Flash RAM. Its .fla, Save/DOUBUTSUNOMORI-(32 hex digits of its
  own)/DOUBUTSUNOMORI.fla, is in mupen64plus's byte order (each 32-bit
  word reversed); in the N64's order the game finds no town and starts a
  new one. Its input plugin's defaults put the stick on the keypad (8,
  2, 4, 6) and the D-pad on the arrows; A is X, START is Enter. Its
  binary says writing the clock is unimplemented here too (not tried).
  In its window: Options, Configuration, General settings, untick "Hide
  advanced settings"; then Config: DOUBUTSUNOMORI, Recompiler, tick
  "32-bit engine" and, under Self mod methods, "Store Instruction"; OK,
  and the game reset or opened again. It keeps them in
  Config/Project64.rdb.user, under the ROM's CRCs (32bit=1,
  SMM-StoreInst=1).
- The title logo's "Forest" changes colour with the title's demo: the
  logo actor's constructor takes its colour set from
  `mTD_get_titledemo_no()`, so it is green in the first demos, then
  lavender, then light blue, on mupen64plus as on both Project64s. (An
  earlier note here had Project64's plugins drawing it lavender where
  angrylion and ares draw it green: those shots were of different
  demos.)

## The patch (the deliverable)

- **`make bps`** writes `build/animalforest-en.bps`, the en ROM as a BPS
  patch (byuu's format: `tools/bps.py`) against the user's dump in .z64
  order, `build/baserom.z64`; any BPS patcher applies it, and
  `tools/bps.py apply` does. The source's runs are found wherever the en
  build put them (its 32 bytes indexed at every 16th offset, the target
  looked up at every offset not yet covered, each hit grown both ways),
  the rest written as it is. For the ROM of 11baf6b's tree: 3,116,598
  bytes for 25,165,824 (6,718,435 read in place from the dump, 15,335,399
  copied from elsewhere in it, 3,111,990 new), made in 1.4 s; applied to
  the dump it gives the en ROM byte for byte (md5 0639929805be...),
  checked by `create` before it writes the file. The patch holds what the
  build took from the user's copies (the GameCube's script and font, the
  2010 patch's labels and messages), as the ROM does: it is for the
  user's own use, and never enters the repository (`build/`).
