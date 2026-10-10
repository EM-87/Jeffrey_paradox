# The Lost Vikings II (SNES) -> GBA. Research phase: tools only so far.
#
#   make check                 no dump needed: the tools' own tests
#   make snes-core             the instrumented snes9x core snesdbg.py drives
#   make chunks ROM=lv2.sfc    every chunk, decompressed, into build/chunks/

PYTHON ?= python3

.PHONY: check snes-core chunks

check:
	$(PYTHON) -I tools/test_lv2data.py
	$(PYTHON) -m py_compile tools/*.py

snes-core:
	tools/snes9x/build.sh build/snes9x

chunks:
	@test -n "$(ROM)" || { echo "usage: make chunks ROM=/path/to/lv2.sfc"; exit 2; }
	$(PYTHON) -I tools/lv2data.py "$(ROM)" dumpall build/chunks
