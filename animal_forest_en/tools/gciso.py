#!/usr/bin/env python3
"""Read files out of a GameCube disc image, and out of the RARC archives in it.

    tools/gciso.py list ISO [ARCHIVE]
    tools/gciso.py extract ISO PATH[/MEMBER] OUT

A disc image (full, or shrunk: the FST decides where files are) has its
header at 0: game code at 0, name at 0x20, the FST's offset and size at
0x424/0x428. The FST is 12-byte entries (flags+name offset, offset or
parent, size or next index) followed by a string table. Animal Crossing's
text lives in RARC archives (`forest_2nd.arc/message_data.bin` and its
`_table.bin`; the free strings, choices, NPC names and mail banks in
`forest_1st.arc`/`forest_2nd.arc` too), so a PATH may continue into an
archive with `/MEMBER`. Everything read stays on the user's machine
(CLAUDE.md, rule 2): this tool brings nothing into the repository.
"""

import struct
import sys


class Disc:
    def __init__(self, data):
        self.data = data
        self.game, self.name = data[:6].decode("ascii", "replace"), data[0x20:0x60].split(b"\0")[0].decode("ascii", "replace")
        fst_off, fst_size = struct.unpack(">II", data[0x424:0x42C])
        fst = data[fst_off:fst_off + fst_size]
        n = struct.unpack(">I", fst[8:12])[0]
        names = fst[12 * n:]

        def name(i):
            o = struct.unpack(">I", fst[12 * i:12 * i + 4])[0] & 0xFFFFFF
            return names[o:names.index(b"\0", o)].decode("ascii", "replace")

        self.files = {}
        stack, path, i = [], [], 1
        while i < n:
            flags_off, off, size = struct.unpack(">III", fst[12 * i:12 * i + 12])
            while stack and i >= stack[-1]:
                stack.pop()
                path.pop()
            if flags_off >> 24:
                stack.append(size)
                path.append(name(i))
            else:
                self.files["/".join(path + [name(i)])] = (off, size)
            i += 1

    def read(self, path):
        off, size = self.files[path]
        return self.data[off:off + size]


class Rarc:
    """Nintendo's RARC: header, a data header with the directory and file
    entries, a string table, then the data."""

    def __init__(self, data):
        if data[:4] != b"RARC":
            raise ValueError("not a RARC archive")
        dh, self.data_off = struct.unpack(">II", data[8:16])
        ndirs, diroff, nfiles, fileoff, strsize, stroff = struct.unpack(">IIIIII", data[dh:dh + 24])
        fileoff, stroff = fileoff + dh, stroff + dh
        self.data, self.files = data, {}
        for i in range(nfiles):
            _, _, flags_noff, off, size, _ = struct.unpack(">HHIIII", data[fileoff + 20 * i:fileoff + 20 * i + 20])
            if (flags_noff >> 24) & 2:          # a directory
                continue
            noff = stroff + (flags_noff & 0xFFFFFF)
            self.files[data[noff:data.index(b"\0", noff)].decode("ascii", "replace")] = (off, size)

    def read(self, member):
        off, size = self.files[member]
        return self.data[self.data_off + off:self.data_off + off + size]


def extract(disc, path):
    """Bytes of PATH, which may be FILE or ARCHIVE/MEMBER."""
    if path in disc.files:
        return disc.read(path)
    archive, _, member = path.rpartition("/")
    return Rarc(disc.read(archive)).read(member)


def main(argv):
    cmd = argv[0]
    disc = Disc(open(argv[1], "rb").read())
    if cmd == "list":
        if len(argv) > 2:
            for name, (off, size) in sorted(Rarc(disc.read(argv[2])).files.items()):
                print("%-36s %#9x" % (name, size))
        else:
            print("%s %s, %d files" % (disc.game, disc.name, len(disc.files)))
            for name, (off, size) in sorted(disc.files.items()):
                print("%-36s %#9x at %#x" % (name, size, off))
        return 0
    if cmd == "extract":
        data = extract(disc, argv[2])
        open(argv[3], "wb").write(data)
        print("%s: %#x bytes" % (argv[3], len(data)))
        return 0
    raise SystemExit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
