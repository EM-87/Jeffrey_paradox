#!/usr/bin/env python3
"""Prove that a rebuilt `code` moved its data as one block.

    tools/af_shiftcheck.py REF_MAP NEW_MAP [--moved OBJ ...] [--moved-list FILE]

The game's data, rodata and bss (and the `buffers` bss after them) hold
addresses the linker never sees whole: `lui` halves shared between two
symbols (tools/af_luicheck.py), IDO's own sharing inside an object, and
whatever else the cartridge's code assumes about where things are. All of
it survives one change for certain: the whole block moving by a multiple
of 0x10000, which keeps every %hi/%lo pair and every distance. This
checks exactly that against the reference (matching) build's map:

  * every input section of the block (.data/.rodata/.bss of each object,
    and buffers) has the same size and sits at its old address + D;
  * every symbol of the block is at its old address + D;
  * D is a multiple of 0x10000 (D = 0 when the text did not grow);
  * what is not in the block (boot, dmadata, the overlays) did not move:
    every symbol, and every section mark of boot and dmadata; inside
    dmadata only the marks and the table's start are held, since the
    table grows into its own padding when the translation adds files.

An object named with --moved is exempt, as is each `object section` line
of --moved-list (what tools/af_relink.py writes: only the sections whose
size changed leave the block): a moved section may be anywhere outside
the block, but must fit in one 64 KB %hi window, since IDO shares `lui`s
inside an object. The linker script decides where they go (af_relink);
this only proves the result.
Exit status 1 with the violations when the proof fails.
"""

import re
import sys

BLOCK_SECTIONS = (".code", ".code_bss", ".buffers_bss")


def hi16(x):
    return ((x + 0x8000) >> 16) & 0xFFFF


class LinkMap:
    """Input sections and symbols of a GNU ld map, by output section."""

    def __init__(self, path):
        self.inputs = []        # (output section, input section, object, address, size)
        self.symbols = {}       # name -> (address, output section)
        self.marks = {}         # NAME = . assignments
        out, pending = None, None
        for line in open(path):
            line = line.rstrip("\n")
            m = re.match(r"^(\.[\w.]+)(?:\s+0x([0-9a-f]+)\s+0x([0-9a-f]+))?", line)
            if m and not line.startswith(" "):
                out = m.group(1)
                continue
            m = re.match(r"^ (\.[\w.]+)\s*$", line)
            if m:
                pending = m.group(1)
                continue
            m = re.match(r"^ (\.[\w.]+)\s+0x([0-9a-f]+)\s+0x([0-9a-f]+)\s+(\S+)$", line)
            if m:
                self.inputs.append((out, m.group(1), m.group(4), int(m.group(2), 16), int(m.group(3), 16)))
                continue
            m = re.match(r"^\s+0x([0-9a-f]+)\s+0x([0-9a-f]+)\s+(\S+)$", line)
            if m and pending:
                self.inputs.append((out, pending, m.group(3), int(m.group(1), 16), int(m.group(2), 16)))
                pending = None
                continue
            m = re.match(r"^\s+0x([0-9a-f]+)\s+([A-Za-z_][A-Za-z0-9_.$]*)(?:\s+=\s+(.*))?\s*$", line)
            if m:
                addr = int(m.group(1), 16)
                if m.group(3) is not None:
                    if m.group(3).strip() == ".":
                        self.marks[m.group(2)] = addr
                else:
                    self.symbols.setdefault(m.group(2), (addr, out))


def is_moved(moved, obj, sec):
    return obj in moved or (obj, sec) in moved


def check(ref, new, moved=()):
    """moved: object paths (all their sections) and/or (object, section) pairs."""
    errors = []
    block_lo, block_hi = ref.marks["code_DATA_START"], ref.marks["buffers_VRAM_END"]
    delta = new.marks["code_DATA_START"] - block_lo
    if delta % 0x10000 or delta < 0:
        errors.append("the data start moved by %#x, not a multiple of 0x10000" % delta)
    new_lo, new_hi = new.marks["code_DATA_START"], new.marks["buffers_VRAM_END"]

    def in_block(out, addr):
        return out in BLOCK_SECTIONS and block_lo <= addr < block_hi

    moved_ranges = []
    new_inputs = {(s, obj): (a, n) for o, s, obj, a, n in new.inputs if n}
    for out, sec, obj, addr, size in ref.inputs:
        if not in_block(out, addr) or size == 0:
            continue
        got = new_inputs.get((sec, obj))
        if is_moved(moved, obj, sec):
            moved_ranges.append((addr, addr + size))
            if got is None:
                continue
            a, n = got
            if new_lo <= a < new_hi and n:
                errors.append("%s %s was moved but still sits in the block at %#x" % (obj, sec, a))
            if n and hi16(a) != hi16(a + n - 1):
                errors.append("%s %s at %#x..%#x crosses a 64 KB %%hi window" % (obj, sec, a, a + n))
            continue
        if got is None:
            errors.append("%s %s (%#x, %#x bytes) is gone" % (obj, sec, addr, size))
        elif got != (addr + delta, size):
            errors.append("%s %s: %#x+%#x -> %#x+%#x, expected %#x+%#x"
                          % (obj, sec, addr, size, got[0], got[1], addr + delta, size))

    def in_moved(addr):
        return any(lo <= addr < hi for lo, hi in moved_ranges)

    for name, addr in ref.marks.items():
        if name.startswith(("boot_", "dmadata_", "makerom_")):
            got = new.marks.get(name)
            if got != addr:
                errors.append("mark %s: %#x -> %s, expected %#x" % (name, addr, "gone" if got is None else "%#x" % got, addr))
    dmadata_first = min((addr for addr, out in ref.symbols.values() if out == ".dmadata"), default=None)
    for name, (addr, out) in ref.symbols.items():
        if out is not None and out.startswith(".segment_"):
            continue                        # assets: nominal addresses
        if out == ".dmadata" and addr != dmadata_first:
            continue                        # the table may grow into its padding
        got = new.symbols.get(name)
        if in_block(out, addr):
            if in_moved(addr):
                continue
            want = addr + delta
        elif out in BLOCK_SECTIONS or (out or "").startswith(".boot") or (out or "").startswith(".ovl") \
                or (out or "").endswith("_ovl") or (out or "").startswith(".dmadata") or (out or "").startswith(".makerom"):
            want = addr                     # text, boot, dmadata, overlays: wherever, but text may move
            if out == ".code":
                continue                    # text: no constraint
        else:
            continue
        if got is None:
            errors.append("symbol %s (%#x) is gone" % (name, addr))
        elif got[0] != want:
            errors.append("symbol %s: %#x -> %#x, expected %#x" % (name, addr, got[0], want))
    return delta, errors


def main(argv):
    moved, args = [], []
    i = 0
    while i < len(argv):
        if argv[i] == "--moved":
            moved.append(argv[i + 1])
            i += 2
        elif argv[i] == "--moved-list":
            for l in open(argv[i + 1]):
                parts = l.split()
                if parts:
                    moved.append(tuple(parts) if len(parts) == 2 else parts[0])
            i += 2
        else:
            args.append(argv[i])
            i += 1
    delta, errors = check(LinkMap(args[0]), LinkMap(args[1]), moved)
    for e in errors[:40]:
        print("  " + e)
    if len(errors) > 40:
        print("  ... %d more" % (len(errors) - 40))
    print("af_shiftcheck: the block moved by %#x; %d violation%s" % (delta, len(errors), "" if len(errors) == 1 else "s"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
