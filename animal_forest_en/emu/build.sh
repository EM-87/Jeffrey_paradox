#!/bin/sh
# Builds the headless N64 emulator the checks drive: mupen64plus-core with
# its debugger (memory watchpoints), the cxd4 RSP (low-level, which
# angrylion needs) and angrylion-rdp-plus (a software RDP, pixel-exact)
# with emu/headless_output.c in place of its OpenGL window, plus
# emu/input_headless.c. Every upstream is pinned to the commit below.
#
#   emu/build.sh <src-dir> <out-dir>
#
# Needs: git, a C/C++ compiler, make, libsdl2-dev, libpng-dev, zlib1g-dev.
set -eu

mkdir -p "$1" "$2"
SRC=$(cd "$1" && pwd)
OUT=$(cd "$2" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
JOBS=$(nproc 2>/dev/null || echo 2)

CORE_URL=https://github.com/mupen64plus/mupen64plus-core
CORE_REV=b20b27ebf9e5b099a978e86dba609111dc98c837
CXD4_URL=https://github.com/mupen64plus/mupen64plus-rsp-cxd4
CXD4_REV=00906a92641c540a64e4e7505012157315314810
ALP_URL=https://github.com/ata4/angrylion-rdp-plus
ALP_REV=9c8b9ed3e7d7f00dff8bc872ccdd3fba1a3673fc

fetch() { # url rev dir
    if [ "$(git -C "$3" rev-parse HEAD 2>/dev/null || true)" = "$2" ]; then
        return
    fi
    rm -rf "$3"
    git init -q "$3"
    git -C "$3" fetch -q --depth 1 "$1" "$2"
    git -C "$3" checkout -q FETCH_HEAD
}

fetch "$CORE_URL" "$CORE_REV" "$SRC/mupen64plus-core"
fetch "$CXD4_URL" "$CXD4_REV" "$SRC/mupen64plus-rsp-cxd4"
fetch "$ALP_URL" "$ALP_REV" "$SRC/angrylion-rdp-plus"

# Our changes (emu/patches/), on a clean tree every time.
git -C "$SRC/mupen64plus-core" checkout -q .
for p in "$HERE"/patches/mupen64plus-core-*.patch; do
    git -C "$SRC/mupen64plus-core" apply "$p"
done
git -C "$SRC/angrylion-rdp-plus" checkout -q .
for p in "$HERE"/patches/angrylion-rdp-plus-*.patch; do
    git -C "$SRC/angrylion-rdp-plus" apply "$p"
done

API=$SRC/mupen64plus-core/src/api

# Core. OSD, netplay and Vulkan off: nothing here draws a window.
make -s -C "$SRC/mupen64plus-core/projects/unix" -j"$JOBS" all \
    DEBUGGER=1 DEBUGGER_NO_DISASM=1 OSD=0 VULKAN=0 NETPLAY=0 >/dev/null
cp "$SRC/mupen64plus-core/projects/unix/libmupen64plus.so.2.0.0" "$OUT/libmupen64plus.so.2"
mkdir -p "$OUT/data"
cp "$SRC/mupen64plus-core/data/mupen64plus.ini" "$OUT/data/"

# RSP.
make -s -C "$SRC/mupen64plus-rsp-cxd4/projects/unix" -j"$JOBS" all APIDIR="$API" >/dev/null
cp "$SRC"/mupen64plus-rsp-cxd4/projects/unix/mupen64plus-rsp-cxd4*.so "$OUT/mupen64plus-rsp-cxd4.so"

# RDP: angrylion's core and mupen64plus glue, our output instead of OpenGL.
ALP=$SRC/angrylion-rdp-plus/src
sed -e "s/@GIT_BRANCH@/master/" -e "s/@GIT_TAG@/$(echo $ALP_REV | cut -c1-7)/" \
    -e "s/@GIT_COMMIT_HASH@/$ALP_REV/" -e "s/@GIT_COMMIT_DATE@//" \
    "$ALP/core/version.h.in" > "$ALP/core/version.h"
OBJ=$SRC/alp-obj
mkdir -p "$OBJ"
CFLAGS="-O2 -fPIC -I$ALP -I$ALP/plugin/mupen64plus"
cc $CFLAGS -c "$ALP/core/n64video.c" -o "$OBJ/n64video.o"
c++ $CFLAGS -std=c++14 -c "$ALP/core/parallel.cpp" -o "$OBJ/parallel.o"
cc $CFLAGS -c "$ALP/plugin/mupen64plus/gfx_m64p.c" -o "$OBJ/gfx_m64p.o"
cc $CFLAGS -c "$ALP/plugin/mupen64plus/msg.c" -o "$OBJ/msg.o"
cc $CFLAGS -c "$HERE/headless_output.c" -o "$OBJ/headless_output.o"
c++ -shared -o "$OUT/mupen64plus-video-angrylion-headless.so" "$OBJ"/*.o -lpthread -ldl

# Input.
cc -O2 -fPIC -shared -I"$API" "$HERE/input_headless.c" -o "$OUT/mupen64plus-input-headless.so"

echo "emu: built in $OUT"
