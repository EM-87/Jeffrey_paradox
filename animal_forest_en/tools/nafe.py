#!/usr/bin/env python3
"""Build the AF Project's 2010 translation (Zoinkity, "NAFE WIP 2/12/2010")
from the user's ROM, to study its bugs. Not part of our ROM.

    tools/nafe.py BASEROM NAFE-WIP-2_12_2010.ups OUT

The patch's notes say to extend the ROM to 0x2000000 first; the input
CRC32 in the patch (f9bf11e1) is that of the ROM padded with zeros, and the
result is the ROM mupen64plus.ini lists as "Doubutsu no Mori (J)
[T+Eng2010-12-02_Zoinkity]", which this checks.
"""

import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rom  # noqa: E402
import ups  # noqa: E402

NAFE_SIZE = 0x2000000
# mupen64plus-core data/mupen64plus.ini, [01BDCC854D0AB798500E7BC31A24D94F].
NAFE_MD5 = "01bdcc854d0ab798500e7bc31a24d94f"


def build(baserom, patch):
    source = baserom + bytes(NAFE_SIZE - len(baserom))
    out = ups.apply(patch, source)
    if hashlib.md5(out).hexdigest() != NAFE_MD5:
        raise ValueError("patched, but not the known NAFE 2010-12-02 ROM")
    return out


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    base = rom.load_baserom(argv[0])
    with open(argv[1], "rb") as f:
        patch = f.read()
    out = build(base, patch)
    with open(argv[2], "wb") as f:
        f.write(out)
    print("%s: NAFE 2010-12-02, md5 %s" % (argv[2], NAFE_MD5))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
