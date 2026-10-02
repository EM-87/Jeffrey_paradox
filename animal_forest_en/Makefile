# Animal Forest (N64) in English, rebuilt from source. See README.md.
#
#   make ROM=path/to/dump            the ROM, from zeldaret/af + our patches,
#                                    checked (today: identical to the dump)
#   make emu-check ROM=...           boots it in the headless emulator
#   make nafe ROM=... NAFE_UPS=...   the AF Project's 2010 patch, to study
#   make nafe-diff                   what that patch changed, by function
#   make test                        the tools' own tests (no ROM needed)
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
PATCHES  := $(sort $(wildcard decomp/patches/*.patch))

BASEROM  := $(BUILD)/baserom.z64
OUT_ROM  := $(BUILD)/animalforest-en.z64
NAFE_ROM := $(BUILD)/nafe-2010-12-02.z64

.PHONY: all baserom decomp rom verify emu emu-check nafe nafe-diff test clean distclean

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

$(AF_DIR)/.stamp-checkout: decomp/AF_REV $(PATCHES)
	@if [ "$$(git -C $(AF_DIR) rev-parse HEAD 2>/dev/null)" != "$(AF_REV)" ]; then \
		rm -rf $(AF_DIR) && git init -q $(AF_DIR) && \
		git -C $(AF_DIR) fetch -q --depth 1 $(AF_URL) $(AF_REV) && \
		git -C $(AF_DIR) checkout -q FETCH_HEAD; \
	fi
	git -C $(AF_DIR) checkout -q . && git -C $(AF_DIR) clean -fdq
	@for p in $(PATCHES); do echo "  patch $$p"; git -C $(AF_DIR) apply "$(CURDIR)/$$p" || exit 1; done
	@touch $@

$(AF_DIR)/.stamp-setup: $(AF_DIR)/.stamp-checkout $(BASEROM)
	cp $(BASEROM) $(AF_DIR)/baseroms/jp/baserom.z64
	$(MAKE) -C $(AF_DIR) venv setup >$(BUILD)/af-setup.log
	@touch $@

$(AF_DIR)/.stamp-extract: $(AF_DIR)/.stamp-setup
	$(MAKE) -C $(AF_DIR) extract >$(BUILD)/af-extract.log 2>&1
	@touch $@

decomp: $(AF_DIR)/.stamp-extract

# With no patches yet, the decomp's own md5 check applies; once there are
# any, COMPARE=0 turns it off. Passed only then: a COMPARE=1 on the command
# line reaches libultra's make too, which then wants reference objects.
AF_FLAGS := $(if $(PATCHES),COMPARE=0)

rom: decomp
	$(MAKE) -C $(AF_DIR) -j$(JOBS) rom $(AF_FLAGS)
	$(MAKE) -C $(AF_DIR) compress $(AF_FLAGS)
	cp $(AF_DIR)/build/animalforest-jp-compressed.z64 $(OUT_ROM)
	$(PYTHON) tools/rom.py info $(OUT_ROM)

# Until the first patch lands, what comes out must be the dump itself,
# byte for byte: that proves the toolchain before anything is changed.
verify: rom
ifeq ($(PATCHES),)
	cmp $(OUT_ROM) $(BASEROM) && echo "verify: identical to the original cartridge"
else
	@echo "verify: $(words $(PATCHES)) patch(es) applied, output differs from the dump by design"
endif

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
