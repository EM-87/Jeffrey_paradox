#!/usr/bin/env python3
"""An overlay's relocation table made from its objects, for the en build.

    tools/af_ovlreloc.py MATCHING_DIR EN_DIR NAME [NAME...]

An overlay is linked at a nominal address and moved where it is loaded;
the table of what to patch then (`DoRelocation`, src/boot/O2/
loadfragment2.c: a header of the text, data, rodata and bss sizes and a
count, then one word per relocation, section in the top 2 bits, ELF type
in the next 6, offset in the section below) is the segment NAME_reloc,
which the decomp takes from the cartridge as data
(asm/jp/data/.../NAME_reloc.data.s). An overlay whose code changes needs a
new one: the decomp ships zeldaret's `fado` (tools/fado), which makes it
from the objects.

For each NAME this first proves, on the matching build (MATCHING_DIR,
build/af), that fado gives the cartridge's table from the cartridge's own
objects: the same relocations, a HI16 still followed by its LO16s, only
whole groups in another order (the original toolchain emitted relocations
in its own order; DoRelocation pairs them the same way either way). Then
it runs fado on the en build's objects (EN_DIR, build/af_en) and writes
the table over the en tree's NAME_reloc.data.s, in the cartridge's
layout: the header's sizes are the cartridge's (af_relink pads a shrunk
overlay's sections back to them, so they must be no smaller than the
new ones), the relocations, zeros up to the cartridge's length, and its
last word, so the segment keeps its size and nothing after it moves. An
overlay with more relocations than that room is refused (it would have to
move). The objects are the overlay's inputs in the decomp's linker script,
in their order.
"""

import os
import re
import subprocess
import sys

RELOC_DATA = "asm/jp/data/%s_reloc.data.s"


def words(path):
    return [int(w, 16) for w in re.findall(r"\.word\s+0x([0-9A-Fa-f]+)", open(path).read())]


def overlay_objects(decomp, name):
    """The overlay's input objects, in the linker script's order, and the
    path of its reloc data file (relative to decomp)."""
    script = open(os.path.join(decomp, "linker_scripts/jp/animalforest.ld")).read()
    m = re.search(r"^    \.%s \S+ : AT\(%s_ROM_START\).*?^    \}\n(?:.*?\n)*?    \.%s_bss \(NOLOAD\).*?^    \}"
                  % (re.escape(name), re.escape(name), re.escape(name)), script, flags=re.M | re.S)
    if not m:
        raise SystemExit("af_ovlreloc: no overlay %s in the linker script" % name)
    objs = []
    for obj in re.findall(r"^\s+(build/\S+\.o)\(\.\w+\);", m.group(0), flags=re.M):
        if obj not in objs:
            objs.append(obj)
    r = re.search(r"^\s+build/(asm/jp/data/\S+_reloc\.data)\.o\(\.data\);", script[m.end():], flags=re.M)
    return objs, r.group(1) + ".s"


def fado(decomp, objs, name):
    out = subprocess.run([os.path.join(os.path.abspath(decomp), "tools/fado/fado.elf"), "-n", name] + objs,
                         cwd=decomp, capture_output=True, text=True, check=True).stdout
    return [int(w, 16) for w in re.findall(r"\.word 0x([0-9A-F]+) # R_", out)]


def groups(relocs):
    """Each HI16 with the LO16s that follow it (same section), alone the rest."""
    out = []
    for r in relocs:
        kind = (r >> 24) & 0x3F
        if kind == 6 and out and ((out[-1][0] >> 24) & 0x3F) in (5, 6) and (out[-1][0] >> 30) == (r >> 30):
            out[-1] = out[-1] + (r,)
        else:
            out.append((r,))
    return out


def same_table(a, b):
    return sorted(groups(a)) == sorted(groups(b))


def cartridge_table(path):
    """(sizes, relocations, length in words, last word) of a reloc data file."""
    w = words(path)
    return w[:4], w[5:5 + w[4]], len(w), w[-1]


def section_sizes(decomp, objs):
    """text, data, rodata, bss sizes of the objects together (as fado adds them)."""
    sizes = {".text": 0, ".data": 0, ".rodata": 0, ".bss": 0}
    for obj in objs:
        out = subprocess.run(["mips-linux-gnu-readelf", "-SW", obj], cwd=decomp, capture_output=True, text=True,
                             check=True).stdout
        for m in re.finditer(r"\]\s+(\.\w+)\s+\w+\s+[0-9a-f]+\s+[0-9a-f]+\s+([0-9a-f]+)\s+[0-9a-f]+\s+\S*\s+\d+\s+\d+\s+(\d+)",
                             out):
            sec, size, align = m.group(1), int(m.group(2), 16), int(m.group(3))
            if sec in sizes:
                sizes[sec] += size + (-size % 16)          # fado pads each section to 16, as the script does
    return [sizes[s] for s in (".text", ".data", ".rodata", ".bss")]


def main(argv):
    matching, en, names = argv[0], argv[1], argv[2:]
    for name in names:
        objs, reloc = overlay_objects(matching, name)
        sizes, cart, length, last = cartridge_table(os.path.join(matching, reloc))
        ours = fado(matching, objs, name)
        if not same_table(cart, ours):
            raise SystemExit("af_ovlreloc: fado does not give %s's table from the matching build's objects "
                             "(%d relocations against the cartridge's %d)" % (name, len(ours), len(cart)))
        en_objs, en_reloc = overlay_objects(en, name)
        new = fado(en, en_objs, name)
        new_sizes = section_sizes(en, en_objs)
        for kind, old, now in zip(("text", "data", "rodata", "bss"), sizes, new_sizes):
            if now > old:
                raise SystemExit("af_ovlreloc: %s's %s grew from %#x to %#x: the overlay would have to move"
                                 % (name, kind, old, now))
        room = length - 6                                   # header and count, the last word
        if len(new) > room:
            raise SystemExit("af_ovlreloc: %s needs %d relocations, its table has room for %d" % (name, len(new), room))
        body = sizes + [len(new)] + new + [0] * (room - len(new)) + [last]
        lines = [".include \"macro.inc\"", "", ".section .data", "",
                 "/* tools/af_ovlreloc.py: %s's relocations, made by fado from the en build's objects, in the"
                 " cartridge's layout */" % name, "", "glabel %s_reloc_table" % name.replace("/", "_")]
        lines += ["    .word 0x%08X" % w for w in body]
        open(os.path.join(en, en_reloc), "w").write("\n".join(lines) + "\n")
        print("af_ovlreloc: %s: fado gives the cartridge's %d relocations from its objects; the en build's %d "
              "written in its layout (sizes %s)" % (name, len(cart), len(new),
                                                    ", ".join("%#x" % s for s in sizes)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
