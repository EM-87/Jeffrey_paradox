"""Names for addresses, from the decomp's linker map.

    syms = Symbols("build/af/build/animalforest-jp.map")
    syms.at(0x8003aa8c)          -> "osCreateViManager+0x28c"
    syms.addr("__osViIntrCount") -> 0x8004330c

The map is of the ROM the decomp built. For the AF Project's ROM it still
names everything the 2010 patch did not move: its hacks sit past 16 MiB
and in a handful of hooked functions, the rest of the code is where the
original has it.
"""

import bisect
import re

_LINE = re.compile(r"\s+0x0*([0-9a-fA-F]{8})\s+([A-Za-z_][\w.$]*)\s*$")


class Symbols:
    def __init__(self, mapfile):
        by_addr = {}
        with open(mapfile, errors="replace") as f:
            for line in f:
                m = _LINE.match(line)
                if m:
                    addr = int(m.group(1), 16)
                    if addr >= 0x80000000:
                        by_addr.setdefault(addr, m.group(2))
        self._addrs = sorted(by_addr)
        self._names = [by_addr[a] for a in self._addrs]
        self._by_name = {n: a for a, n in zip(self._addrs, self._names)}

    def at(self, addr):
        """'name+0xoff' for the symbol at or below addr (KSEG0 or physical)."""
        if addr < 0x80000000:
            addr |= 0x80000000
        i = bisect.bisect_right(self._addrs, addr) - 1
        if i < 0:
            return "%08x" % addr
        off = addr - self._addrs[i]
        return self._names[i] + ("+0x%x" % off if off else "")

    def addr(self, name):
        return self._by_name[name]
