#!/bin/sh
# Builds the second emulator the checks can drive: ares (pinned below) with emu/ares/headless.cpp as
# ares-headless, and the ares app itself (desktop-ui) from the same tree, for a look at the real thing on a
# virtual display. Our changes are the two patches here: the screen hands each frame over inside run() (a
# frontend that reads it right after needs that; the app only loses a thread), and headless/ in the build.
#
#   emu/ares/build.sh <src-dir> <out-dir>
#
# Needs: git, cmake (3.28+), ninja, a C++20 compiler, libvulkan-dev and a Vulkan driver (on a machine
# without a GPU, mesa-vulkan-drivers: lavapipe), and the app's libraries (libgtk-3-dev, libsdl2-dev,
# libao-dev, libopenal-dev, libxrandr-dev, libgl-dev), which ares's CMake asks for even to build the core.
set -eu

mkdir -p "$1" "$2"
SRC=$(cd "$1" && pwd)
OUT=$(cd "$2" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)

ARES_URL=https://github.com/ares-emulator/ares
ARES_REV=0aafd85789215e84e1e43415c07d4c88461b7899  # v148

if [ "$(git -C "$SRC/ares" rev-parse HEAD 2>/dev/null || true)" != "$ARES_REV" ]; then
    rm -rf "$SRC/ares"
    git init -q "$SRC/ares"
    git -C "$SRC/ares" fetch -q --depth 1 "$ARES_URL" "$ARES_REV"
    git -C "$SRC/ares" checkout -q FETCH_HEAD
fi

# Our changes, on a clean tree every time.
git -C "$SRC/ares" checkout -q .
for p in "$HERE"/ares-*.patch; do
    git -C "$SRC/ares" apply "$p"
done
rm -rf "$SRC/ares/headless"
mkdir "$SRC/ares/headless"
cp "$HERE/headless.cpp" "$HERE/CMakeLists.txt" "$SRC/ares/headless/"

# The N64 core only; ares's prebuilt dependencies (librashader, shaders) are the app's, not needed here.
cmake -S "$SRC/ares" -B "$SRC/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DARES_SKIP_DEPS=ON \
    -DARES_CORES=n64 >"$SRC/cmake.log" 2>&1 || { tail -20 "$SRC/cmake.log"; exit 1; }
ninja -C "$SRC/build" ares-headless desktop-ui >"$SRC/ninja.log" 2>&1 || { tail -40 "$SRC/ninja.log"; exit 1; }

cp "$SRC/build/headless/ares-headless" "$OUT/ares-headless"
rm -rf "$OUT/app"
cp -r "$SRC/build/rundir" "$OUT/app"
echo "ares: $OUT/ares-headless, the app in $OUT/app/bin/ares"
