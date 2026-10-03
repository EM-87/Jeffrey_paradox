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
  mail banks are read by `code/m_handbill.s` (asm): three tables of 0x220
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
- **What is left to translate ourselves.** Of the 969 messages with no
  official text (709 removed, 260 reused), 287 are spare placeholders
  (よび, "spare"), 211 the debug and test texts of numbers 0-210, 78 have
  only codes; 393 are real lines, about 23,500 Japanese characters (the
  moving-in talk, letters' replies 7167-7230, a few shop lines...). Plus
  107 free strings. They go in `script/` as our own translation.
- **What the first English ROM shows** (screenshots in the scratchpad):
  K.K.'s intro and Rover's questions in English, typed in the Japanese
  font's full-width cells (16 px per character: the 0x7000-byte font at
  ROM 0xBCD000 is 256 glyphs of 16x14 at 4 bpp), so an English line is
  cut at about 20 characters; the NPC name tag is still Japanese (the
  names are another bank). The game's flow is intact: pressing A every
  25 frames, the name dial opens after 113 presses (frame 3289) against
  the cartridge's 78 (frame 2414), the difference being English pages
  that take two presses (one ends the typing, one turns the page). The
  "edited" messages of the train intro (1124, 10950, 1127, 10953) differ
  from the N64's only in pauses, the date's field order and an
  expression code, so taking the GameCube's codes there was safe. That is phase 5's work: a proportional Latin font
  in `m_font` (`code/6B3DC0`, asm; the GameCube's `m_font_offset` is the
  map for widths), the name and item banks (`D_D16000/D_D18000`,
  `D_D05000/D_D06000`, the NPC names).
