# Animal Forest (N64) in English, rebuilt from source. See README.md.
#
#   make ROM=path/to/dump            the cartridge rebuilt from zeldaret/af
#                                    + our decompiled code, checked identical
#   make emu-check ROM=...           boots it in the headless emulator
#   make rom-en ROM=...              the translation: the same source with
#                                    decomp/changes.patch, code free to grow
#   make emu-check-en / route-en     the translation booted / played to the
#                                    houses in the emulator
#   make save-check-en               its compressed save written and read back
#   make nafe ROM=... NAFE_UPS=...   the AF Project's 2010 patch, to study
#   make nafe-diff                   what that patch changed, by function
#   make test                        the tools' own tests (no ROM needed)
#   make decomp-tools / decomp-patch / decomp-patch-en
#                                    working on the decomp (see below)
#
# ROM is only needed once: it is normalised into build/baserom.z64.

ROM      ?=
NAFE_UPS ?=
PYTHON   ?= python3
JOBS     ?= $(shell nproc 2>/dev/null || echo 2)

# Our command-line variables (ROM=, NAFE_UPS=) must not reach the decomp's
# make: it has a ROM variable of its own.
MAKEOVERRIDES :=

BUILD    := build
AF_URL   := https://github.com/zeldaret/af
AF_REV   := $(shell cat decomp/AF_REV)
AF_DIR   := $(BUILD)/af
AF_EN_DIR := $(BUILD)/af_en
EMU_DIR  := $(BUILD)/emu
# Our work on the decomp, two cumulative patches (see decomp/README.md):
# matching.patch is decompiled code that must still build the cartridge
# byte for byte; changes.patch is what the translation changes, applied on
# top of it in a second checkout, build/af_en.
MATCHING := $(wildcard decomp/matching.patch)
CHANGES  := $(wildcard decomp/changes.patch)
M2C_URL  := https://github.com/matt-kempster/m2c
M2C_REV  := 708d2d2cb2698f091a92492b328f73b24209f72d

BASEROM  := $(BUILD)/baserom.z64
OUT_ROM  := $(BUILD)/animalforest-match.z64
EN_ROM   := $(BUILD)/animalforest-en.z64
NAFE_ROM := $(BUILD)/nafe-2010-12-02.z64

.PHONY: all bps baserom decomp rom verify decomp-patch decomp-tools decomp-en rom-en emu-check-en route-en save-check-en letters-check-en board-check-en write-check-en notice-check-en messages-check-en menus-check-en emu-determinism decomp-patch-en emu emu-check nafe nafe-diff test clean distclean

all: verify

# -- the user's dump --------------------------------------------------------

$(BASEROM):
	@test -n "$(ROM)" || { echo "Needs your Doubutsu no Mori dump once: make ROM=path/to/dump"; exit 1; }
	@mkdir -p $(BUILD)
	$(PYTHON) tools/rom.py normalize "$(ROM)" $@

baserom: $(BASEROM)

# -- the decomp -------------------------------------------------------------
# zeldaret/af at decomp/AF_REV, our decomp/patches/*.patch applied in order
# on a clean tree (git clean without -x keeps the decomp's own build output,
# which its .gitignore covers).

$(AF_DIR)/.stamp-checkout: decomp/AF_REV $(MATCHING)
	@if [ "$$(git -C $(AF_DIR) rev-parse HEAD 2>/dev/null)" != "$(AF_REV)" ]; then \
		rm -rf $(AF_DIR) && git init -q $(AF_DIR) && \
		git -C $(AF_DIR) fetch -q --depth 1 $(AF_URL) $(AF_REV) && \
		git -C $(AF_DIR) checkout -q FETCH_HEAD; \
	fi
	git -C $(AF_DIR) checkout -q . && git -C $(AF_DIR) clean -fdq
	@for p in $(MATCHING); do [ -s "$$p" ] || continue; echo "  patch $$p"; git -C $(AF_DIR) apply "$(CURDIR)/$$p" || exit 1; done
	@touch $@

$(AF_DIR)/.stamp-setup: $(AF_DIR)/.stamp-checkout $(BASEROM)
	cp $(BASEROM) $(AF_DIR)/baseroms/jp/baserom.z64
	$(MAKE) -C $(AF_DIR) venv setup >$(BUILD)/af-setup.log
	@touch $@

$(AF_DIR)/.stamp-extract: $(AF_DIR)/.stamp-setup tools/af_dumpdata.py
	$(MAKE) -C $(AF_DIR) extract >$(BUILD)/af-extract.log 2>&1
	$(PYTHON) tools/af_dumpdata.py $(AF_DIR)
	@touch $@

decomp: $(AF_DIR)/.stamp-extract

rom: decomp
	$(MAKE) -C $(AF_DIR) -j$(JOBS) rom
	$(MAKE) -C $(AF_DIR) compress
	cp $(AF_DIR)/build/animalforest-jp-compressed.z64 $(OUT_ROM)
	$(PYTHON) tools/rom.py info $(OUT_ROM)

# Everything in decomp/matching.patch is decompiled code that must compile
# to the cartridge's own bytes: the decomp's md5 check runs in `rom`, and
# this compares the final ROM with the dump.
verify: rom
	cmp $(OUT_ROM) $(BASEROM) && echo "verify: identical to the original cartridge"

# -- working on the decomp ----------------------------------------------------
# Edit build/af directly, then `make decomp-patch` to keep the work: it
# rewrites decomp/matching.patch from the tree (new files included).
# `make decomp-tools` adds m2c and asm-differ's modules to the decomp's venv;
# tools/af_match.py (run in build/af) says which functions match.

DECOMP_PATHS := src include yamls linker_scripts relocs

decomp-patch:
	git -C $(AF_DIR) add -N $(DECOMP_PATHS)
	git -C $(AF_DIR) diff --binary $(AF_REV) -- $(DECOMP_PATHS) > decomp/matching.patch
	@touch $(AF_DIR)/.stamp-checkout $(AF_DIR)/.stamp-setup $(AF_DIR)/.stamp-extract
	@echo "decomp/matching.patch: $$(grep -c '^diff --git' decomp/matching.patch) files"

# -- the translation's build ---------------------------------------------------
# A second checkout: AF_REV, matching.patch committed on top as a baseline,
# changes.patch applied on that. Its ROM is never compared with the
# cartridge; instead `code` is moved to the end of the ROM so that it can
# grow without moving anything else (tools/af_relink.py: about 4,400 vrom
# addresses sit as plain numbers in asm data tables), nothing of it moves in
# RAM (the field models name its bss by address): sections that grew, and
# new ones, go to a code_en region after buffers, loaded with code (same
# tool, from the map of a first link), the RAM addresses splat left
# absolute are made relative (tools/af_relsyms.py, from the matching
# build's map), and the layout is proved before the ROM is compressed:
# af_shiftcheck (nothing of the cartridge's moved), af_anchors (no address
# spelled with the wrong symbol), af_luicheck (no shared lui broken). Then compress.py, the dmadata back in the
# table's order (af_ranges.py, af_dmaorder.py) and the checksum. The result
# is checked by playing it (route-en). NOTES.md, "Shiftability".
REF_MAP  := $(AF_DIR)/build/animalforest-jp.map
EN_MAP   := $(AF_EN_DIR)/build/animalforest-jp.map
EN_LD    := linker_scripts/jp/animalforest.ld
EN_MOVED := $(AF_EN_DIR)/build/af_en_moved.txt
# The message bank the ROM gets: the English draft when `make script` has
# made it from the user's disc, else the cartridge's own (so the build and
# its plumbing work without the disc). Compiled into two plain segments
# after code (tools/af_text.py, af_relink --segment, dmadata entries in
# changes.patch); the objects come from the .bin files the decomp picks up.
GC_DIR    := $(BUILD)/gc
EN_BANK   := $(if $(wildcard $(GC_DIR)/en_bank.txt),$(GC_DIR)/en_bank.txt,$(GC_DIR)/n64_jp.txt)
EN_CHOICE := $(if $(wildcard $(GC_DIR)/en_choice.txt),$(GC_DIR)/en_choice.txt,$(GC_DIR)/n64_choice_jp.txt)
EN_STRING := $(if $(wildcard $(GC_DIR)/en_string.txt),$(GC_DIR)/en_string.txt,$(GC_DIR)/n64_string_jp.txt)
EN_ASSETS := $(AF_EN_DIR)/assets/jp/en
EN_SEGMENTS := --segment msg_en_text=build/assets/jp/en/msg_text.o --segment msg_en_index=build/assets/jp/en/msg_index.o \
	--segment msg_en_choice_text=build/assets/jp/en/choice_text.o --segment msg_en_choice_index=build/assets/jp/en/choice_index.o \
	--segment msg_en_string_text=build/assets/jp/en/string_text.o --segment msg_en_string_index=build/assets/jp/en/string_index.o \
	--segment mail_en_text=build/assets/jp/en/mail_text.o --segment mail_en_index=build/assets/jp/en/mail_index.o \
	--segment item_en_names=build/assets/jp/en/item_names.o
# The letters the ROM gets, the same way: the English drafts where `make
# script` has made them, else the cartridge's own banks; all eight in one
# text and one index segment (tools/af_text.py compile-letters; the en
# build's m_handbill.c reads them).
LETTER_BANKS := mail_header mail_body mail_footer vmail_header vmail_a vmail_b vmail_c vmail_footer
N64_LETTERS := $(foreach b,$(LETTER_BANKS),$(GC_DIR)/n64_$(b).txt)
EN_LETTERS := $(foreach b,$(LETTER_BANKS),$(if $(wildcard $(GC_DIR)/en_$(b).txt),$(GC_DIR)/en_$(b).txt,$(GC_DIR)/n64_$(b).txt))
# The animals' names: the cartridge's name segment with the GameCube's names
# in its 6-byte fields (tools/af_names.py), as the en build's copy of the
# segment's .bin; the cartridge's own when the disc's table is not there.
EN_NAMES := $(AF_EN_DIR)/assets/jp/segment_00E04000.bin
# The font: the cartridge's sheet with the GameCube's Latin characters
# (build/gc/gc_font.bin, which make script reads from the disc), or without
# it the cartridge's own at the left of their cells, as the en build's copy
# of the segment's .bin, and their widths as the table the en build's
# m_font.c includes (tools/af_font.py). The messages are laid out with the
# same widths (FONT_OPTS).
EN_FONT := $(AF_EN_DIR)/assets/jp/segment_00BCD000.bin
GC_FONT := $(wildcard $(GC_DIR)/gc_font.bin)
FONT_OPTS := --font $(BASEROM) $(if $(GC_FONT),--gc-font $(GC_FONT))
# The menus' own words (strings in the menus' data, not in the banks): the
# GameCube's (build/gc/gc_ui.txt, which make script reads from the disc), or
# the cartridge's own without it, as the include the en build's menus use
# (tools/af_ui.py; NOTES, "The menus' own words").
EN_UI := $(AF_EN_DIR)/assets/jp/en/ui_strings.inc
GC_UI := $(if $(wildcard $(GC_DIR)/foresta.map $(GC_DIR)/gc_ui.txt),$(GC_DIR)/gc_ui.txt)
# The items' names: the GameCube's (build/gc/gc_items.txt, read from build/gc's copies of the disc's module
# and map), ours where it has none (script/items.txt), as a ROM segment of records and the include the en
# build's m_item_name.c uses; without the disc, the cartridge's own names stay (tools/af_items.py; NOTES,
# "Item names").
EN_ITEMS := $(EN_ASSETS)/item_names.bin
EN_ITEMS_INC := $(EN_ASSETS)/item_names.inc
GC_ITEMS := $(if $(wildcard $(GC_DIR)/foresta.map $(GC_DIR)/gc_items.txt),$(GC_DIR)/gc_items.txt)
# The labels the AF Project's 2010 patch drew in English (the dial's, the
# inventory's, the map's and other menus'): taken from its ROM (make nafe
# NAFE_UPS=..., from the user's copy of the patch) over the en tree's copies of
# their segments, the cartridge's own without it (tools/af_nafe_gfx.py; NOTES,
# "The 2010 patch's labels").
EN_NAFE_GFX := $(AF_EN_DIR)/assets/jp/en/nafe_gfx.stamp
# The title's logo the 2010 patch drew in English: its pieces taken from its
# ROM into the en tree's copy of the logo's segment, with the logo actor's
# data and the four values of its drawing the patch changed for them (the
# include the en ac_animal_logo.c uses); the cartridge's logo without it
# (tools/af_nafe_logo.py; NOTES, "The title's logo").
EN_NAFE_LOGO := $(EN_ASSETS)/animal_logo.inc
# The overlays the translation changes: each gets its relocation table made
# anew from its objects after the first link (tools/af_ovlreloc.py, which
# first proves fado on the matching build), and af_relink pads its sections
# back to their old sizes: the letter board, the text editor, the name
# entry, the edit-end check, the address book, the item window, the
# bulletin board and the title's logo.
EN_OVERLAYS := board_ovl ovl__0078CB80 ledit_ovl ovl__00799580 ovl__00792700 tag_ovl ovl__00797A50 ovl_Animal_Logo

$(AF_EN_DIR)/.stamp-checkout: decomp/AF_REV $(MATCHING) $(CHANGES)
	@if [ "$$(git -C $(AF_EN_DIR) rev-parse HEAD~0 2>/dev/null)" = "" ]; then \
		rm -rf $(AF_EN_DIR) && git init -q $(AF_EN_DIR) && \
		git -C $(AF_EN_DIR) fetch -q --depth 1 $(AF_URL) $(AF_REV) && \
		git -C $(AF_EN_DIR) checkout -q FETCH_HEAD; \
	fi
	git -C $(AF_EN_DIR) checkout -q -f $(AF_REV) && git -C $(AF_EN_DIR) clean -fdq
	@for p in $(MATCHING); do [ -s "$$p" ] || continue; echo "  patch $$p"; git -C $(AF_EN_DIR) apply "$(CURDIR)/$$p" || exit 1; done
	git -C $(AF_EN_DIR) add -A $(DECOMP_PATHS) && git -C $(AF_EN_DIR) -c user.name=af -c user.email=af@localhost commit -q --allow-empty -m "matching.patch"
	@for p in $(CHANGES); do [ -s "$$p" ] || continue; echo "  patch $$p"; git -C $(AF_EN_DIR) apply "$(CURDIR)/$$p" || exit 1; done
	@touch $@

$(AF_EN_DIR)/.stamp-setup: $(AF_EN_DIR)/.stamp-checkout $(BASEROM)
	cp $(BASEROM) $(AF_EN_DIR)/baseroms/jp/baserom.z64
	$(MAKE) -C $(AF_EN_DIR) venv setup >$(BUILD)/af_en-setup.log
	@touch $@

$(AF_EN_DIR)/.stamp-extract: $(AF_EN_DIR)/.stamp-setup tools/af_dumpdata.py
	$(MAKE) -C $(AF_EN_DIR) extract >$(BUILD)/af_en-extract.log 2>&1
	$(PYTHON) tools/af_dumpdata.py $(AF_EN_DIR)
	rm -f $(AF_EN_DIR)/build/$(EN_LD).splat
	@touch $@

decomp-en: $(AF_EN_DIR)/.stamp-extract

# The matching build's map is the reference: the RAM addresses to make
# relative, the sizes every object's data had, and the layout to prove
# against. Two links: the first measures the objects, the second lays them
# out (the script is rewritten from splat's pristine copy each time).
$(GC_DIR)/n64_jp.txt $(GC_DIR)/n64_choice_jp.txt $(GC_DIR)/n64_string_jp.txt: $(BASEROM) tools/msgbank.py
	mkdir -p $(GC_DIR)
	$(PYTHON) tools/msgbank.py check-n64 $(BASEROM)
	$(PYTHON) tools/msgbank.py dump-n64 $(BASEROM) $(GC_DIR)/n64_jp.txt message
	$(PYTHON) tools/msgbank.py dump-n64 $(BASEROM) $(GC_DIR)/n64_choice_jp.txt choice
	$(PYTHON) tools/msgbank.py dump-n64 $(BASEROM) $(GC_DIR)/n64_string_jp.txt string

# The messages the GameCube's script has no counterpart for, where the AF
# Project's 2010 translation has them in English with the cartridge's codes:
# taken from the user's copy of the patch (make nafe) at make rom-en and laid
# over the English draft (tools/af_nafe_text.py), never committed; none
# without the patch's ROM or the draft (NOTES, "The 2010 patch's messages").
NAFE_TEXT := $(if $(and $(wildcard $(NAFE_ROM)),$(wildcard $(GC_DIR)/en_bank.txt)),$(GC_DIR)/nafe_messages.txt)
# Our own English for messages the GameCube's script has no counterpart for
# (script/message/*.txt): laid over the draft by make script, and over the
# bank again at make rom-en, after the 2010 patch's, so that a change to them
# needs no new make script; af_text.py holds both to the cartridge's codes
# that steer the game.
OWN_MESSAGES := $(wildcard script/message/*.txt)
$(GC_DIR)/nafe_messages.txt: $(NAFE_ROM) $(GC_DIR)/en_bank.txt $(GC_DIR)/n64_jp.txt tools/af_nafe_text.py tools/af_text.py tools/msgbank.py tools/nafe_diff.py
	$(PYTHON) tools/af_nafe_text.py $(NAFE_ROM) $(GC_DIR)/en_bank.txt $(GC_DIR)/n64_jp.txt $@

# The messages are laid out at the window's width with the en font's
# widths, which tools/af_font.py reads from the dump and the disc's font.
$(EN_ASSETS)/msg_text.bin: $(EN_BANK) $(NAFE_TEXT) $(OWN_MESSAGES) $(EN_CHOICE) $(EN_STRING) $(GC_DIR)/n64_jp.txt tools/af_text.py tools/msgbank.py tools/af_font.py $(GC_FONT)
	mkdir -p $(EN_ASSETS)
	$(PYTHON) tools/af_text.py compile $(EN_BANK) $(EN_ASSETS)/msg_text.bin $(EN_ASSETS)/msg_index.bin --reference $(GC_DIR)/n64_jp.txt --max-bytes 0x600 $(FONT_OPTS) \
		$(if $(NAFE_TEXT),--overrides $(NAFE_TEXT)) $(foreach f,$(OWN_MESSAGES),--overrides $(f))
	$(PYTHON) tools/af_text.py compile $(EN_CHOICE) $(EN_ASSETS)/choice_text.bin $(EN_ASSETS)/choice_index.bin --bank choice --max-bytes 24
	$(PYTHON) tools/af_text.py compile $(EN_STRING) $(EN_ASSETS)/string_text.bin $(EN_ASSETS)/string_index.bin --bank string --max-bytes 64 --count 0x679

$(N64_LETTERS): $(GC_DIR)/n64_%.txt: $(BASEROM) tools/msgbank.py
	mkdir -p $(GC_DIR)
	$(PYTHON) tools/msgbank.py dump-n64 $(BASEROM) $@ $*

$(EN_ASSETS)/mail_text.bin: $(EN_LETTERS) tools/af_text.py tools/msgbank.py
	mkdir -p $(EN_ASSETS)
	$(PYTHON) tools/af_text.py compile-letters $(EN_ASSETS)/mail_text.bin $(EN_ASSETS)/mail_index.bin $(EN_LETTERS)

# Both depend on the extract: splat writes the cartridge's .bin over them.
$(EN_NAMES): $(BASEROM) tools/af_names.py $(wildcard $(GC_DIR)/npc_name_str_table.bin) $(AF_EN_DIR)/.stamp-extract
	@if [ -s $(GC_DIR)/npc_name_str_table.bin ]; then $(PYTHON) tools/af_names.py $(BASEROM) $(GC_DIR)/npc_name_str_table.bin $@ | tail -1; \
	else echo "names: the cartridge's (no $(GC_DIR)/npc_name_str_table.bin)"; fi

$(EN_FONT): $(BASEROM) tools/af_font.py tools/af_names.py tools/msgbank.py tools/gciso.py $(GC_FONT) $(AF_EN_DIR)/.stamp-extract
	mkdir -p $(EN_ASSETS)
	$(PYTHON) tools/af_font.py $(BASEROM) $@ $(EN_ASSETS)/font_cut.inc $(if $(GC_FONT),--gc $(GC_FONT))

# a string added to af_ui's table is read from the disc's module that make
# script keeps in build/gc, without the disc
$(GC_DIR)/gc_ui.txt: tools/af_ui.py $(wildcard $(GC_DIR)/foresta.rel.szs $(GC_DIR)/foresta.map)
	@if [ -s $(GC_DIR)/foresta.rel.szs ] && [ -s $(GC_DIR)/foresta.map ]; then \
		$(PYTHON) tools/af_ui.py gc-files $(GC_DIR)/foresta.rel.szs $(GC_DIR)/foresta.map $@; \
	else echo "$@: make script ISO=... reads the strings from the disc"; fi

$(EN_UI): tools/af_ui.py tools/af_font.py tools/msgbank.py tools/gciso.py $(GC_UI) script/ui.txt $(AF_EN_DIR)/.stamp-extract
	$(PYTHON) tools/af_ui.py compile $(AF_EN_DIR) $@ $(if $(GC_UI),--gc $(GC_UI)) --own script/ui.txt

$(GC_DIR)/gc_items.txt: tools/af_items.py $(wildcard $(GC_DIR)/foresta.rel.szs $(GC_DIR)/foresta.map)
	@if [ -s $(GC_DIR)/foresta.rel.szs ] && [ -s $(GC_DIR)/foresta.map ]; then \
		$(PYTHON) tools/af_items.py gc-files $(GC_DIR)/foresta.rel.szs $(GC_DIR)/foresta.map $@; \
	else echo "$@: make script ISO=... reads the names from the disc"; fi

$(EN_ITEMS): tools/af_items.py tools/af_font.py tools/af_text.py tools/msgbank.py $(GC_ITEMS) script/items.txt $(GC_FONT) $(AF_EN_DIR)/.stamp-extract
	mkdir -p $(EN_ASSETS)
	$(PYTHON) tools/af_items.py compile $(AF_EN_DIR)/baseroms/jp/baserom-decompressed.z64 $@ $(EN_ITEMS_INC) \
		$(if $(GC_ITEMS),--gc $(GC_ITEMS)) --own script/items.txt $(FONT_OPTS)

$(EN_NAFE_GFX): tools/af_nafe_gfx.py tools/nafe_diff.py $(wildcard $(NAFE_ROM)) $(AF_EN_DIR)/.stamp-extract
	mkdir -p $(EN_ASSETS)
	@if [ -s $(NAFE_ROM) ]; then $(PYTHON) tools/af_nafe_gfx.py $(BASEROM) $(NAFE_ROM) $(AF_EN_DIR); \
	else echo "labels: the cartridge's (no $(NAFE_ROM): make nafe NAFE_UPS=...)"; fi
	@touch $@

# also writes the segment's .bin and the actor's .data.s, which splat rewrites at every extract
$(EN_NAFE_LOGO): tools/af_nafe_logo.py tools/nafe_diff.py $(wildcard $(NAFE_ROM)) $(AF_EN_DIR)/.stamp-extract
	mkdir -p $(EN_ASSETS)
	@if [ -s $(NAFE_ROM) ]; then $(PYTHON) tools/af_nafe_logo.py $(BASEROM) $(NAFE_ROM) $(AF_EN_DIR); \
	else $(PYTHON) tools/af_nafe_logo.py $(BASEROM) - $(AF_EN_DIR); fi
	@touch $@

en-bank: $(EN_ASSETS)/msg_text.bin $(EN_ASSETS)/mail_text.bin $(EN_NAMES) $(EN_FONT) $(EN_UI) $(EN_NAFE_GFX) $(EN_NAFE_LOGO) $(EN_ITEMS)

# The deliverable: the en ROM as a BPS patch against the user's dump in .z64 order (build/baserom.z64),
# made and applied back by tools/bps.py; any BPS patcher applies it.
bps: rom-en
	$(PYTHON) tools/bps.py create $(BASEROM) $(EN_ROM) $(BUILD)/animalforest-en.bps \
		--metadata "animal_forest_en $$(git rev-parse --short HEAD 2>/dev/null)"

rom-en: decomp-en rom en-bank
	cd $(AF_EN_DIR) && $(PYTHON) $(CURDIR)/tools/af_relsyms.py $(CURDIR)/$(REF_MAP) linker_scripts/jp/auto/undefined_syms_auto.ld
	cd $(AF_EN_DIR) && $(PYTHON) $(CURDIR)/tools/af_relink.py $(EN_LD) --ref $(CURDIR)/$(REF_MAP) $(EN_SEGMENTS)
	rm -f $(AF_EN_DIR)/build/linker_scripts/jp/auto/undefined_syms_auto.ld $(AF_EN_DIR)/build/animalforest-jp.elf $(AF_EN_DIR)/build/animalforest-jp.z64
	$(MAKE) -C $(AF_EN_DIR) -j$(JOBS) build/animalforest-jp.elf
	$(PYTHON) tools/af_ovlreloc.py $(AF_DIR) $(AF_EN_DIR) $(EN_OVERLAYS)
	cd $(AF_EN_DIR) && $(PYTHON) $(CURDIR)/tools/af_relink.py $(EN_LD) --ref $(CURDIR)/$(REF_MAP) --map build/animalforest-jp.map --moved-out $(CURDIR)/$(EN_MOVED) $(EN_SEGMENTS)
	$(MAKE) -C $(AF_EN_DIR) -j$(JOBS) build/animalforest-jp.z64
	$(PYTHON) tools/af_shiftcheck.py $(REF_MAP) $(EN_MAP) --moved-list $(EN_MOVED)
	cd $(AF_EN_DIR) && $(PYTHON) $(CURDIR)/tools/af_anchors.py $(CURDIR)/$(REF_MAP)
	cd $(AF_EN_DIR) && $(PYTHON) $(CURDIR)/tools/af_luicheck.py $(CURDIR)/$(REF_MAP) build/animalforest-jp.map
	cd $(AF_EN_DIR) && .venv/bin/python3 tools/compress.py --in build/animalforest-jp.z64 --out $(CURDIR)/$(EN_ROM) \
		--dma-start `tools/dmadata_start.sh mips-linux-gnu-nm build/animalforest-jp.elf` \
		--compress `$(PYTHON) $(CURDIR)/tools/af_ranges.py build/animalforest-jp.map` --threads $(JOBS)
	$(PYTHON) tools/af_dmaorder.py $(EN_ROM) $(AF_EN_DIR)/build/animalforest-jp.z64 --dma-start `cd $(AF_EN_DIR) && tools/dmadata_start.sh mips-linux-gnu-nm build/animalforest-jp.elf`
	$(PYTHON) tools/rom.py fixcrc $(EN_ROM)
	$(PYTHON) tools/rom.py info $(EN_ROM)

emu-check-en: emu rom-en
	$(PYTHON) tests/emu_check.py $(EMU_DIR) $(EN_ROM) $(BUILD)/emu-check-en

route-en: emu rom-en
	$(PYTHON) tools/route_newgame.py $(EMU_DIR) $(EN_ROM) $(BUILD)/route-en

# The en build's compressed save, driven from the houses' state of route-en
# (tests/emu_save.py; NOTES, "The compressed save").
save-check-en: route-en
	$(PYTHON) tests/emu_save.py $(EMU_DIR) $(EN_ROM) $(AF_EN_DIR) $(BUILD)/route-en/4-houses.st $(BUILD)/emu-save-en

# The en build's letter loaders, called from a hook in the houses' state of
# route-en, against a model of the cartridge's expander over the en build's
# letter files (tests/emu_letters.py; NOTES, "The letters in the en build").
letters-check-en: route-en
	$(PYTHON) tests/emu_letters.py $(EMU_DIR) $(EN_ROM) $(AF_EN_DIR) $(CURDIR)/$(BUILD)/route-en/4-houses.st $(BUILD)/emu-letters-en

# The en build's letter board, opened on letters from the same state, its
# lines measured on the frame against a model of the board (tests/
# emu_board.py; NOTES, "The letter board in the en build").
board-check-en: route-en
	$(PYTHON) tests/emu_board.py $(EMU_DIR) $(EN_ROM) $(AF_EN_DIR) $(CURDIR)/$(BUILD)/route-en/4-houses.st $(BUILD)/emu-board-en

# Letters written with the en build's editor on the dial, from the same
# state: their lines, cursor and end mark measured on the frame against the
# same model, the body past 96 bytes up to the GameCube's 192, the letters
# finished and read back whole (tests/emu_write.py; NOTES, "Writing a
# letter in the en build").
write-check-en: route-en
	$(PYTHON) tests/emu_write.py $(EMU_DIR) $(EN_ROM) $(AF_EN_DIR) $(CURDIR)/$(BUILD)/route-en/4-houses.st $(BUILD)/emu-write-en

# Messages of the en build's bank shown in the message window from the same
# state, as the game asks for one, and each line of their first page
# measured on the frame against the font's model: those taken from the 2010
# translation, ours, and two of the GameCube's; and those that play through
# with talk orders, played to their end (tests/emu_messages.py; NOTES, "The
# 2010 patch's messages", "Our own messages").
messages-check-en: route-en
	$(PYTHON) tests/emu_messages.py $(EMU_DIR) $(EN_ROM) $(AF_EN_DIR) $(CURDIR)/$(BUILD)/route-en/4-houses.st $(BUILD)/emu-messages-en \
		$(if $(NAFE_TEXT),--nafe $(NAFE_TEXT)) $(foreach f,$(OWN_MESSAGES),--own $(f)) $(if $(GC_FONT),--gc-font $(GC_FONT))

# The en build's bulletin board, from the same state: the game's notices put
# up as the game puts them, the board turned page by page and each post's
# lines measured on the frame against the letter board's model, a notice
# typed on the dial past the cartridge's 96 bytes, finished, read back whole,
# saved and loaded (tests/emu_notice.py; NOTES, "The bulletin board").
notice-check-en: route-en
	$(PYTHON) tests/emu_notice.py $(EMU_DIR) $(EN_ROM) $(AF_EN_DIR) $(CURDIR)/$(BUILD)/route-en/4-houses.st $(BUILD)/emu-notice-en

# The menus' own words in the en build: the name entry's five windows opened
# from the same state, their titles' strings in RAM and their ink measured on
# the frame against the font's model (tests/emu_menus.py; NOTES, "The menus'
# own words").
menus-check-en: route-en
	$(PYTHON) tests/emu_menus.py $(EMU_DIR) $(EN_ROM) $(AF_EN_DIR) $(CURDIR)/$(BUILD)/route-en/4-houses.st $(BUILD)/emu-menus-en \
		$(if $(GC_UI),--gc $(GC_UI)) --own script/ui.txt $(if $(wildcard $(NAFE_ROM)),--nafe $(NAFE_ROM))

# -- the GameCube script (phase 4) -------------------------------------------
# ISO=path to the user's Animal Crossing disc image (GAFE01). Everything it
# produces lands in build/gc, which git ignores: the banks, their dumps and
# the message-by-message comparison with the cartridge's bank.
GC_FILES := forest_2nd.arc/message_data.bin forest_2nd.arc/message_data_table.bin \
	forest_2nd.arc/npc_name_str_table.bin forest_1st.arc/string_data.bin forest_1st.arc/string_data_table.bin \
	forest_1st.arc/select_data.bin forest_1st.arc/select_data_table.bin \
	$(foreach f,mail super ps superz maila mailb mailc psz,forest_1st.arc/$(f)_data.bin forest_1st.arc/$(f)_data_table.bin)
# The letters: each cartridge bank (tools/msgbank.py N64_BANKS) and the
# GameCube's bank of the same numbering (NOTES, "The English bank in the
# ROM", the letter banks).
LETTER_PAIRS := mail_header:super mail_body:mail mail_footer:ps vmail_header:superz vmail_a:maila vmail_b:mailb vmail_c:mailc vmail_footer:psz
script: $(BASEROM) $(GC_DIR)/n64_jp.txt $(GC_DIR)/n64_choice_jp.txt $(GC_DIR)/n64_string_jp.txt
	@test -n "$(ISO)" || { echo "make script ISO=path/to/Animal_Crossing_USA.iso"; exit 1; }
	@for f in $(GC_FILES); do $(PYTHON) tools/gciso.py extract "$(ISO)" $$f $(GC_DIR)/$$(basename $$f) || exit 1; done
	$(PYTHON) tools/af_font.py gc "$(ISO)" $(GC_DIR)/gc_font.bin
	$(PYTHON) tools/gciso.py extract "$(ISO)" foresta.rel.szs $(GC_DIR)/foresta.rel.szs
	$(PYTHON) tools/gciso.py extract "$(ISO)" foresta.map $(GC_DIR)/foresta.map
	$(PYTHON) tools/af_ui.py gc-files $(GC_DIR)/foresta.rel.szs $(GC_DIR)/foresta.map $(GC_DIR)/gc_ui.txt
	$(PYTHON) tools/af_items.py gc-files $(GC_DIR)/foresta.rel.szs $(GC_DIR)/foresta.map $(GC_DIR)/gc_items.txt
	$(PYTHON) tools/msgbank.py dump-gc $(GC_DIR)/message_data.bin $(GC_DIR)/message_data_table.bin $(GC_DIR)/gc_en.txt
	$(PYTHON) tools/af_align.py $(BASEROM) $(GC_DIR)/message_data.bin $(GC_DIR)/message_data_table.bin $(GC_DIR)/align.tsv
	$(PYTHON) tools/af_text.py draft $(GC_DIR)/n64_jp.txt $(GC_DIR)/gc_en.txt $(GC_DIR)/align.tsv $(GC_DIR)/en_bank.txt \
		$(foreach f,$(OWN_MESSAGES),--overrides $(f))
	$(PYTHON) tools/af_text.py check $(GC_DIR)/en_bank.txt --reference $(GC_DIR)/n64_jp.txt --max-bytes 0x600 --font $(BASEROM) --gc-font $(GC_DIR)/gc_font.bin | tail -1
	$(PYTHON) tools/msgbank.py dump-gc $(GC_DIR)/select_data.bin $(GC_DIR)/select_data_table.bin $(GC_DIR)/gc_choice_en.txt
	$(PYTHON) tools/msgbank.py dump-gc $(GC_DIR)/string_data.bin $(GC_DIR)/string_data_table.bin $(GC_DIR)/gc_string_en.txt
	$(PYTHON) tools/af_text.py draft $(GC_DIR)/n64_choice_jp.txt $(GC_DIR)/gc_choice_en.txt - $(GC_DIR)/en_choice.txt --bank choice \
		$(foreach f,$(wildcard script/choice/*.txt),--overrides $(f)) | tail -1
	$(PYTHON) tools/af_text.py draft $(GC_DIR)/n64_string_jp.txt $(GC_DIR)/gc_string_en.txt - $(GC_DIR)/en_string.txt --bank string --count 0x679 \
		$(foreach f,$(wildcard script/string/*.txt),--overrides $(f)) | tail -1
	$(PYTHON) tools/af_text.py check $(GC_DIR)/en_choice.txt --bank choice --max-bytes 24 | tail -1
	$(PYTHON) tools/af_text.py check $(GC_DIR)/en_string.txt --bank string --max-bytes 64 --count 0x679 | tail -1
	@for pair in $(LETTER_PAIRS); do bank=$${pair%%:*}; gc=$${pair##*:}; \
		$(PYTHON) tools/msgbank.py dump-n64 $(BASEROM) $(GC_DIR)/n64_$$bank.txt $$bank >/dev/null || exit 1; \
		if [ "$$gc" = "-" ]; then gcdump=-; else gcdump=$(GC_DIR)/gc_$${gc}.txt; \
			$(PYTHON) tools/msgbank.py dump-gc $(GC_DIR)/$${gc}_data.bin $(GC_DIR)/$${gc}_data_table.bin $$gcdump >/dev/null || exit 1; fi; \
		$(PYTHON) tools/af_text.py draft $(GC_DIR)/n64_$$bank.txt $$gcdump - $(GC_DIR)/en_$$bank.txt --bank $$bank \
			$$(for f in script/$$bank/*.txt; do [ -f "$$f" ] && echo --overrides $$f; done) | tail -1 || exit 1; \
		$(PYTHON) tools/af_text.py check $(GC_DIR)/en_$$bank.txt --bank $$bank --reference $(GC_DIR)/n64_$$bank.txt | tail -1; \
	done

# Edit build/af_en directly, then keep the work in decomp/changes.patch
# (the diff against the committed matching baseline).
decomp-patch-en:
	git -C $(AF_EN_DIR) add -N $(DECOMP_PATHS)
	git -C $(AF_EN_DIR) diff --binary HEAD -- $(DECOMP_PATHS) > decomp/changes.patch
	@touch $(AF_EN_DIR)/.stamp-checkout $(AF_EN_DIR)/.stamp-setup $(AF_EN_DIR)/.stamp-extract
	@echo "decomp/changes.patch: $$(grep -c '^diff --git' decomp/changes.patch) files"

$(BUILD)/m2c/m2c.py:
	git init -q $(BUILD)/m2c
	git -C $(BUILD)/m2c fetch -q --depth 1 $(M2C_URL) $(M2C_REV)
	git -C $(BUILD)/m2c checkout -q FETCH_HEAD

decomp-tools: decomp $(BUILD)/m2c/m2c.py
	$(AF_DIR)/.venv/bin/pip install -q pycparser colorama watchdog levenshtein cxxfilt
	$(MAKE) -C $(AF_DIR) diff-init >/dev/null

# -- the emulator -----------------------------------------------------------

EMU_INPUTS := emu/build.sh $(wildcard emu/*.c) $(wildcard emu/patches/*.patch)

$(EMU_DIR)/.stamp: $(EMU_INPUTS)
	emu/build.sh $(BUILD)/emu-src $(EMU_DIR)
	@touch $@

emu: $(EMU_DIR)/.stamp

emu-check: emu rom
	$(PYTHON) tests/emu_check.py $(EMU_DIR) $(OUT_ROM) $(BUILD)/emu-check

# Two runs of the route's opening at once, RAM compared after every frame
# and the states at the name dial byte for byte (tests/emu_determinism.py;
# NOTES, "The emulator is deterministic"). Run it after touching emu/.
emu-determinism: emu rom
	$(PYTHON) tests/emu_determinism.py $(EMU_DIR) $(OUT_ROM) $(BUILD)/emu-determinism

# -- the AF Project's 2010 translation, to study ----------------------------

$(NAFE_ROM): $(BASEROM)
	@test -n "$(NAFE_UPS)" || { echo "make nafe NAFE_UPS=path/to/NAFE-WIP-2_12_2010.ups"; exit 1; }
	$(PYTHON) tools/nafe.py $(BASEROM) "$(NAFE_UPS)" $@

nafe: $(NAFE_ROM)

# What the 2010 patch changed, file by file, function by function.
nafe-diff: $(NAFE_ROM) rom
	$(PYTHON) tools/nafe_diff.py $(BASEROM) $(NAFE_ROM) $(AF_DIR)/build/animalforest-jp.map

# -- housekeeping -----------------------------------------------------------

test:
	$(PYTHON) -m unittest discover -s tests -p 'test_*.py'

clean:
	rm -f $(OUT_ROM) $(EN_ROM) $(NAFE_ROM)
	rm -rf $(BUILD)/emu-check
	-$(MAKE) -C $(AF_DIR) clean

distclean:
	rm -rf $(BUILD)
