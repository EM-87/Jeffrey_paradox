#!/bin/sh
# Builds the instrumented snes9x libretro core tools/snesdbg.py drives:
# upstream snes9x at a pinned commit, plus dbg.cpp/dbg.h and the few hooks in
# hooks.patch (the CPU loop, ROM reads, DMA, the frame counter, and the
# symbols' export). Nothing in it changes what the emulated console does.
#
#   tools/snes9x/build.sh [WORKDIR]      (default: build/snes9x)
#
# The core lands at WORKDIR/libretro/snes9x_libretro.so, where snesdbg.py
# looks for it (or point LV2_SNES_CORE at it).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${1:-build/snes9x}
COMMIT=fae2fea08f74180759ef540ee94259213f503480

if [ ! -d "$WORK/.git" ]; then
	mkdir -p "$WORK"
	git -C "$WORK" init -q
	git -C "$WORK" remote add origin https://github.com/libretro/snes9x.git
fi
git -C "$WORK" fetch -q --depth 1 origin "$COMMIT"
git -C "$WORK" checkout -q -f "$COMMIT"
git -C "$WORK" clean -q -fdx
cp "$HERE/dbg.cpp" "$HERE/dbg.h" "$WORK/"
git -C "$WORK" apply "$HERE/hooks.patch"
# Upstream's own warnings go to a log; on failure its tail is shown.
make -C "$WORK/libretro" -j"$(nproc 2>/dev/null || echo 4)" >"$WORK/build.log" 2>&1 ||
	{ tail -30 "$WORK/build.log"; exit 1; }
echo "$WORK/libretro/snes9x_libretro.so"
