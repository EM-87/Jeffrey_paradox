"""The tools' own tests: nothing here needs the cartridge (make test)."""

import hashlib
import os
import struct
import sys
import tempfile
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))

import af_dmaorder  # noqa: E402
import af_ranges  # noqa: E402
import af_relink  # noqa: E402
import af_relsyms  # noqa: E402
import rom  # noqa: E402
import ups  # noqa: E402
from contact import read_png  # noqa: E402
from n64emu import stick, to_physical, write_png  # noqa: E402


def ups_varint(n):
    out = bytearray()
    while True:
        x = n & 0x7F
        n >>= 7
        if n == 0:
            out.append(0x80 | x)
            return bytes(out)
        out.append(x)
        n -= 1


def make_ups(source, target):
    """A minimal UPS writer (the inverse of tools/ups.py), for the tests."""
    body = bytearray(b"UPS1" + ups_varint(len(source)) + ups_varint(len(target)))
    src = source.ljust(len(target), b"\0")
    last, i = 0, 0
    while i < len(target):
        if src[i] == target[i]:
            i += 1
            continue
        body += ups_varint(i - last)
        while i < len(target) and src[i] != target[i]:
            body.append(src[i] ^ target[i])
            i += 1
        body.append(0)
        i += 1
        last = i
    body += struct.pack("<II", zlib.crc32(source) & 0xFFFFFFFF, zlib.crc32(target) & 0xFFFFFFFF)
    body += struct.pack("<I", zlib.crc32(bytes(body)) & 0xFFFFFFFF)
    return bytes(body)


def synthetic_rom():
    data = bytearray(0x101000)
    data[0:4] = b"\x80\x37\x12\x40"
    data[0x1000:] = b"".join(hashlib.sha256(i.to_bytes(4, "big")).digest() for i in range(0x100000 // 32))
    return bytes(data)


class ByteOrder(unittest.TestCase):
    def test_all_three_orders_come_back_as_z64(self):
        z64 = bytes(range(256)) * 16
        z64 = b"\x80\x37\x12\x40" + z64[4:]
        v64 = bytearray(z64)
        v64[0::2], v64[1::2] = z64[1::2], z64[0::2]
        n64 = bytearray(len(z64))
        for i in range(0, len(z64), 4):
            n64[i:i + 4] = z64[i:i + 4][::-1]
        self.assertEqual(rom.byte_order(v64), "v64")
        self.assertEqual(rom.byte_order(n64), "n64")
        self.assertEqual(rom.to_z64(z64), z64)
        self.assertEqual(rom.to_z64(bytes(v64)), z64)
        self.assertEqual(rom.to_z64(bytes(n64)), z64)

    def test_not_a_rom(self):
        with self.assertRaises(ValueError):
            rom.byte_order(b"GAFE01")


class HeaderCrc(unittest.TestCase):
    def test_matches_ipl3checksum(self):
        # Computed with ipl3checksum's CIC_6102_7101 (the decomp's tool) on
        # the same synthetic ROM. The real cartridge also checks out:
        # tools/rom.py info on the dump prints "valid".
        self.assertEqual(rom.cic_6102_crc(synthetic_rom()), (0x9D00C867, 0x2F8C68C3))

    def test_fix_crc_writes_the_header(self):
        fixed = rom.fix_crc(synthetic_rom())
        self.assertEqual(struct.unpack(">II", fixed[0x10:0x18]), (0x9D00C867, 0x2F8C68C3))


class Ups(unittest.TestCase):
    def test_round_trip_growing_the_file(self):
        source = bytes(range(256)) * 64
        target = bytearray(source + bytes(5000))
        target[10:20] = b"0123456789"
        target[300] ^= 0xFF
        target[len(source) + 4000:len(source) + 4004] = b"TEXT"
        patch = make_ups(source, bytes(target))
        self.assertEqual(ups.apply(patch, source), bytes(target))

    def test_wrong_input_is_refused(self):
        source = b"A" * 100
        patch = make_ups(source, b"B" * 100)
        with self.assertRaises(ValueError):
            ups.apply(patch, b"C" * 100)

    def test_damaged_patch_is_refused(self):
        source = b"A" * 100
        patch = bytearray(make_ups(source, b"AB" * 50))
        patch[8] ^= 1
        with self.assertRaises(ValueError):
            ups.apply(bytes(patch), source)


class Emulator(unittest.TestCase):
    def test_png_round_trip(self):
        w, h = 7, 3
        rgb = bytes(v for y in range(h) for x in range(w) for v in (x * 30, y * 80, (x + y) * 10))
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "t.png")
            write_png(path, w, h, rgb)
            self.assertEqual(read_png(path), (w, h, rgb))

    def test_addresses(self):
        self.assertEqual(to_physical(0x80123456), 0x123456)
        self.assertEqual(to_physical(0xA0123456), 0x123456)
        self.assertEqual(to_physical(0x00123456), 0x123456)

    def test_stick(self):
        self.assertEqual(stick(0, 127), 0x7F000000)
        self.assertEqual(stick(-128, 0), 0x00800000)


class Shiftability(unittest.TestCase):
    """The translation build's tools, on synthetic inputs (make rom-en)."""

    def test_relsyms_makes_pins_relative(self):
        mapfile = ("                0x80104508                D_80104508_jp\n"
                   "                0x80107b70                D_80107B70_jp\n"
                   "                0x80107b75                D_80107B75_jp\n"
                   "                0x80107b78                D_80107B78_jp\n")
        ld = ("D_80104509_jp = 0x80104509;\n"
              "D_80107B75_jp = 0x80107B75;\n"
              "D_CF9000 = 0xCF9000;\n"
              "gGfxPools = 0x801540C0; //\n")
        with tempfile.TemporaryDirectory() as d:
            m, l = os.path.join(d, "x.map"), os.path.join(d, "x.ld")
            open(m, "w").write(mapfile)
            open(l, "w").write(ld)
            af_relsyms.main([m, l])
            out = open(l).read().splitlines()
        self.assertEqual(out[0], "D_80104509_jp = D_80104508_jp + 0x1; /* af_relsyms: was 0x80104509 */")
        self.assertNotIn("D_80107B75_jp", "\n".join(out))      # a label defines it: dropped
        self.assertIn("D_CF9000 = 0xCF9000;", out)              # ROM: untouched
        self.assertEqual(out[-1], "gGfxPools = D_80107B78_jp + 0x4C548; /* af_relsyms: was 0x801540C0 */")

    def test_dmaorder_restores_the_table_order(self):
        def table(entries):
            data = bytearray(0x200)
            for i, e in enumerate(entries):
                struct.pack_into(">IIII", data, 0x100 + 16 * i, *e)
            return data
        ref = table([(0x1000, 0x2000, 0x1000, 0), (0x2000, 0x3000, 0x2000, 0), (0x3000, 0x4000, 0x3000, 0)])
        mine = table([(0x2000, 0x3000, 0x40, 0x80), (0x3000, 0x4000, 0x80, 0xC0), (0x1000, 0x2000, 0, 0x40)])
        with tempfile.TemporaryDirectory() as d:
            r, m = os.path.join(d, "ref.z64"), os.path.join(d, "mine.z64")
            open(r, "wb").write(ref)
            open(m, "wb").write(mine)
            af_dmaorder.main([m, r, "--dma-start", "0x100"])
            got = af_dmaorder.entries(open(m, "rb").read(), 0x100)
        self.assertEqual(got, [(0x1000, 0x2000, 0, 0x40), (0x2000, 0x3000, 0x40, 0x80), (0x3000, 0x4000, 0x80, 0xC0)])

    def test_relink_moves_code_to_the_end(self):
        script = ("SECTIONS\n{\n    __romPos = 0;\n"
                  "    code_ROM_START = __romPos;\n    .code 0x80051A80 : AT(code_ROM_START) { *(.text); }\n"
                  "    __romPos += SIZEOF(.code);\n    code_ROM_END = __romPos;\n    code_VRAM_END = .;\n"
                  "    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    buffers_ROM_START = __romPos;\n    .buffers_bss code_VRAM_END (NOLOAD) { *(.bss); }\n"
                  "    buffers_ROM_END = __romPos;\n    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    ovl_select_ROM_START = __romPos;\n    .ovl_select 0x80800000 : AT(ovl_select_ROM_START) { *(.ovl); }\n"
                  "    __romPos += SIZEOF(.ovl_select);\n    ovl_select_ROM_END = __romPos;\n"
                  "    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    /DISCARD/ :\n    {\n        *(*);\n    }\n}\n")
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "a.ld")
            open(p, "w").write(script)
            af_relink.main([p, "--next-rom", "0x73F4D0"])
            out = open(p).read()
            af_relink.main([p, "--next-rom", "0x73F4D0"])     # idempotent
            self.assertEqual(open(p).read(), out)
        lines = out.splitlines()
        self.assertLess(lines.index("    __romPos = 0x73F4D0; /* ovl_select keeps the cartridge's vrom */"),
                        lines.index("    ovl_select_ROM_START = __romPos;"))
        self.assertLess(lines.index("    ovl_select_ROM_END = __romPos;"), lines.index("    code_ROM_START = __romPos;"))
        self.assertLess(lines.index("    code_ROM_START = __romPos;"), lines.index("    buffers_ROM_START = __romPos;"))
        self.assertLess(lines.index("    buffers_ROM_START = __romPos;"), lines.index("    /DISCARD/ :"))

    def test_ranges_follow_the_vrom_order(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "include/tables/dmatables"))
            os.makedirs(os.path.join(d, "yamls/jp"))
            open(os.path.join(d, "include/tables/dmadata_table.h"), "w").write('#include "dmatables/t.h"\n')
            open(os.path.join(d, "include/tables/dmatables/t.h"), "w").write(
                'DEFINE_DMA_ENTRY(makerom, "makerom")\nDEFINE_DMA_ENTRY(code, "code")\nDEFINE_DMA_ENTRY(ovl_a, "ovl_a")\n'
                'DEFINE_DMA_ENTRY(ovl_b, "ovl_b")\nDEFINE_DMA_ENTRY(tex, "tex")\n')
            open(os.path.join(d, "yamls/jp/a.yaml"), "w").write(
                "segments:\n  - name: makerom\n    start: 0\n  - name: code\n    start: 0x100\n    compress: True\n"
                "  - name: ovl_a\n    start: 0x200\n    compress: True\n  - name: ovl_b\n    start: 0x300\n    compress: True\n"
                "  - name: tex\n    start: 0x400\n")
            # the map: code relinked to the end of the ROM
            open(os.path.join(d, "x.map"), "w").write(
                "                0x0000000000000000                makerom_ROM_START = __romPos\n"
                "                0x0000000000000200                ovl_a_ROM_START = __romPos\n"
                "                0x0000000000000300                ovl_b_ROM_START = __romPos\n"
                "                0x0000000000000400                tex_ROM_START = __romPos\n"
                "                0x0000000000001000                code_ROM_START = __romPos\n")
            cwd = os.getcwd()
            os.chdir(d)
            try:
                import contextlib
                import io
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    af_ranges.main([os.path.join(d, "x.map")])
            finally:
                os.chdir(cwd)
        # vrom order: makerom(0) ovl_a(1) ovl_b(2) tex(3) code(4): compressed are 1, 2 and 4
        self.assertEqual(buf.getvalue().strip(), "1-2,4")


if __name__ == "__main__":
    unittest.main()
