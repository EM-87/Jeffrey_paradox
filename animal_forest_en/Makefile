# Animal Forest (N64) in English, rebuilt from source. See README.md.
#
#   make ROM=path/to/dump            the ROM, from zeldaret/af + our
#                                    decompiled code, checked identical
#   make emu-check ROM=...           boots it in the headless emulator
#   make nafe ROM=... NAFE_UPS=...   the AF Project's 2010 patch, to study
#   make nafe-diff                   what that patch changed, by function
#   make test                        the tools' own tests (no ROM needed)
#   make decomp-tools / decomp-patch  working on the decomp (see below)
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
EMU_DIR  := $(BUILD)/emu
# Our work on the decomp, one cumulative patch: decompiled code that must
# still build the cartridge byte for byte (see decomp/README.md).
MATCHING := $(wildcard decomp/matching.patch)
M2C_URL  := https://github.com/matt-kempster/m2c
M2C_REV  := 708d2d2cb2698f091a92492b328f73b24209f72d

BASEROM  := $(BUILD)/baserom.z64
OUT_ROM  := $(BUILD)/animalforest-en.z64
NAFE_ROM := $(BUILD)/nafe-2010-12-02.z64

.PHONY: all baserom decomp rom verify decomp-patch decomp-tools emu emu-check nafe nafe-diff test clean distclean

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
	@for p in $(MATCHING); do echo "  patch $$p"; git -C $(AF_DIR) apply "$(CURDIR)/$$p" || exit 1; done
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

DECOMP_PATHS := src include yamls linker_scripts

decomp-patch:
	git -C $(AF_DIR) add -N $(DECOMP_PATHS)
	git -C $(AF_DIR) diff --binary $(AF_REV) -- $(DECOMP_PATHS) > decomp/matching.patch
	@touch $(AF_DIR)/.stamp-checkout $(AF_DIR)/.stamp-setup $(AF_DIR)/.stamp-extract
	@echo "decomp/matching.patch: $$(grep -c '^diff --git' decomp/matching.patch) files"

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
	rm -f $(OUT_ROM) $(NAFE_ROM)
	rm -rf $(BUILD)/emu-check
	-$(MAKE) -C $(AF_DIR) clean

distclean:
	rm -rf $(BUILD)
