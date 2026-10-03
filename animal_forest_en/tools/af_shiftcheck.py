#!/usr/bin/env python3
"""Prove that a rebuilt `code` left everything of the cartridge's in place.

    tools/af_shiftcheck.py REF_MAP NEW_MAP [--moved OBJ ...] [--moved-list FILE]

The game's code holds addresses the linker never sees whole: `lui` halves
shared between two symbols (tools/af_luicheck.py), IDO's own sharing inside
an object, and the cartridge's files themselves (2,348 texture loads in
the field models name code's bss buffers by their RAM address: NOTES,
"Shiftability"). All of it survives for certain only if nothing moves.
This checks exactly that against the reference (matching) build's map:

  * every input section of code (text, data, rodata, bss of each object,
    and buffers) sits at its old address with its old size;
  * every symbol of code is at its old address (a symbol the new link no
    longer defines is only reported: nothing can refer to it);
  * what is not in code (boot, dmadata, the overlays) did not move either:
    every symbol, and every section mark of boot and dmadata; inside
    dmadata only the marks and the table's start are held, since the
    table grows into its own padding when the translation adds files.

An object named with --moved is exempt, as is each `object section` line
of --moved-list (what tools/af_relink.py writes: the sections that grew or
are new): a moved section must be outside code's cartridge range and fit
in one 64 KB %hi window, since IDO shares `lui`s inside an object. An
`object section shrunk` line is a section that stayed, padded to its old
size: it must start where it did and be no bigger; the symbols inside it
are free. The linker script decides where things go (af_relink); this
only proves the result. Exit status 1 with the violations when the proof
fails.
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


def check(ref, new, moved=(), gone=None, shrunk=()):
    """moved: object paths (all their sections) and/or (object, section) pairs
    that left their slot; shrunk: (object, section) pairs padded in place.
    gone, a list, collects the reference's symbols the new link no longer has
    (a C function built in place of its asm loses the asm's labels): not a
    break, since nothing can refer to a symbol the linker does not define.
    Returns (displacement of the data block, errors)."""
    errors = []
    gone = [] if gone is None else gone
    block_lo, block_hi = ref.marks["code_TEXT_START"], ref.marks["buffers_VRAM_END"]
    delta = new.marks["code_DATA_START"] - ref.marks["code_DATA_START"]
    if delta:
        errors.append("the data block moved by %#x" % delta)

    def in_block(out, addr):
        return out in BLOCK_SECTIONS and block_lo <= addr < block_hi

    free_ranges = []                    # moved or shrunk: their symbols are free
    new_inputs = {(s, obj): (a, n) for o, s, obj, a, n in new.inputs if n}
    for out, sec, obj, addr, size in ref.inputs:
        if not in_block(out, addr) or size == 0:
            continue
        got = new_inputs.get((sec, obj))
        if is_moved(moved, obj, sec):
            free_ranges.append((addr, addr + size))
            if got is None:
                continue
            a, n = got
            if block_lo <= a < block_hi:
                errors.append("%s %s was moved but still sits in code at %#x" % (obj, sec, a))
            if n and hi16(a) != hi16(a + n - 1):
                errors.append("%s %s at %#x..%#x crosses a 64 KB %%hi window" % (obj, sec, a, a + n))
            continue
        if (obj, sec) in shrunk:
            free_ranges.append((addr, addr + size))
            if got is not None and (got[0] != addr or got[1] > size):
                errors.append("%s %s: %#x+%#x -> %#x+%#x, expected at %#x, no bigger"
                              % (obj, sec, addr, size, got[0], got[1], addr))
            continue
        if got is None:
            errors.append("%s %s (%#x, %#x bytes) is gone" % (obj, sec, addr, size))
        elif got != (addr, size):
            errors.append("%s %s: %#x+%#x -> %#x+%#x" % (obj, sec, addr, size, got[0], got[1]))
    for (sec, obj), (a, n) in new_inputs.items():  # new objects: outside code's range, in one window
        if is_moved(moved, obj, sec) and not any(o == obj and s == sec for _, s, o, _, _ in ref.inputs):
            if block_lo <= a < block_hi:
                errors.append("%s %s is new but sits in code at %#x" % (obj, sec, a))
            if hi16(a) != hi16(a + n - 1):
                errors.append("%s %s at %#x..%#x crosses a 64 KB %%hi window" % (obj, sec, a, a + n))

    def free(addr):
        return any(lo <= addr < hi for lo, hi in free_ranges)

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
        if in_block(out, addr):
            if free(addr):
                continue
        elif not (out in BLOCK_SECTIONS or (out or "").startswith((".boot", ".ovl", ".dmadata", ".makerom"))
                  or (out or "").endswith("_ovl")):
            continue
        got = new.symbols.get(name)
        if got is None:
            gone.append(name)                # nothing can refer to it: the link would have failed
        elif got[0] != addr:
            errors.append("symbol %s: %#x -> %#x" % (name, addr, got[0]))
    return delta, errors


def main(argv):
    moved, shrunk, args = [], [], []
    i = 0
    while i < len(argv):
        if argv[i] == "--moved":
            moved.append(argv[i + 1])
            i += 2
        elif argv[i] == "--moved-list":
            for l in open(argv[i + 1]):
                parts = l.split()
                if len(parts) == 3 and parts[2] == "shrunk":
                    shrunk.append((parts[0], parts[1]))
                elif parts:
                    moved.append(tuple(parts) if len(parts) == 2 else parts[0])
            i += 2
        else:
            args.append(argv[i])
            i += 1
    gone = []
    delta, errors = check(LinkMap(args[0]), LinkMap(args[1]), moved, gone, shrunk)
    for e in errors[:40]:
        print("  " + e)
    if gone:
        print("  %d symbol%s of the reference no longer exist (nothing can refer to them): %s%s"
              % (len(gone), "" if len(gone) == 1 else "s", ", ".join(gone[:4]), "..." if len(gone) > 4 else ""))
    if len(errors) > 40:
        print("  ... %d more" % (len(errors) - 40))
    print("af_shiftcheck: %d section%s moved out, %d padded in place; %d violation%s"
          % (len(moved), "" if len(moved) == 1 else "s", len(shrunk), len(errors), "" if len(errors) == 1 else "s"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
