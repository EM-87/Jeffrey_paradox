# Animal Forest (N64) in English, rebuilt from source. See README.md.
#
#   make ROM=path/to/dump            the cartridge rebuilt from zeldaret/af
#                                    + our decompiled code, checked identical
#   make emu-check ROM=...           boots it in the headless emulator
#   make rom-en ROM=...              the translation: the same source with
#                                    decomp/changes.patch, code free to grow
#   make emu-check-en / route-en     the translation booted / played to the
#                                    houses in the emulator
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

.PHONY: all baserom decomp rom verify decomp-patch decomp-tools decomp-en rom-en emu-check-en route-en decomp-patch-en emu emu-check nafe nafe-diff test clean distclean

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

$(AF_DIR)/.stamp-extract: $(AF_DIR)/.stamp-setup
	$(MAKE) -C $(AF_DIR) extract >$(BUILD)/af-extract.log 2>&1
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
# addresses sit as plain numbers in asm data tables), the RAM addresses
# splat left absolute are made relative (tools/af_relsyms.py, from the
# matching build's map), and the ROM is compressed with the dmadata in the
# table's order (tools/af_ranges.py, af_dmaorder.py) and its checksum
# recomputed. The result is checked by playing it (route-en).

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

$(AF_EN_DIR)/.stamp-extract: $(AF_EN_DIR)/.stamp-setup
	$(MAKE) -C $(AF_EN_DIR) extract >$(BUILD)/af_en-extract.log 2>&1
	cd $(AF_EN_DIR) && $(PYTHON) $(CURDIR)/tools/af_relink.py linker_scripts/jp/animalforest.ld
	@touch $@

decomp-en: $(AF_EN_DIR)/.stamp-extract

# The matching build's map names the RAM addresses to make relative.
rom-en: decomp-en rom
	cd $(AF_EN_DIR) && $(PYTHON) $(CURDIR)/tools/af_relsyms.py $(CURDIR)/$(AF_DIR)/build/animalforest-jp.map linker_scripts/jp/auto/undefined_syms_auto.ld
	rm -f $(AF_EN_DIR)/build/linker_scripts/jp/auto/undefined_syms_auto.ld $(AF_EN_DIR)/build/animalforest-jp.elf $(AF_EN_DIR)/build/animalforest-jp.z64
	$(MAKE) -C $(AF_EN_DIR) -j$(JOBS) build/animalforest-jp.z64
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
