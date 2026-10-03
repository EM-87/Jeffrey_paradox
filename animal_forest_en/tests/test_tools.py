"""The tools' own tests: nothing here needs the cartridge (make test)."""

import collections
import hashlib
import os
import random
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "tools"))
sys.path.insert(0, os.path.join(HERE, "..", "emu"))

import aflz  # noqa: E402
import af_anchors  # noqa: E402
import af_align  # noqa: E402
import af_dmaorder  # noqa: E402
import af_luicheck  # noqa: E402
import af_names  # noqa: E402
import af_ranges  # noqa: E402
import af_relink  # noqa: E402
import af_relsyms  # noqa: E402
import af_shiftcheck  # noqa: E402
import af_text  # noqa: E402
import gciso  # noqa: E402
import msgbank  # noqa: E402
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

    def test_relink_moves_code_to_the_end_and_keeps_the_block_in_place(self):
        script = ("SECTIONS\n{\n    __romPos = 0;\n"
                  "    code_ROM_START = __romPos;\n    .code 0x80051A80 : AT(code_ROM_START) SUBALIGN(16)\n    {\n"
                  "        code_TEXT_START = .;\n        build/src/code/a.o(.text);\n        code_TEXT_END = .;\n"
                  "        code_DATA_START = .;\n        code_a = .;\n        build/src/code/a.o(.data);\n"
                  "        code_b = .;\n        build/src/code/b.o(.data);\n        code_DATA_END = .;\n"
                  "        code_RODATA_START = .;\n        build/src/code/b.o(.rodata);\n        code_RODATA_END = .;\n    }\n"
                  "    __romPos += SIZEOF(.code);\n    code_ROM_END = __romPos;\n    code_VRAM_END = .;\n"
                  "    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    buffers_ROM_START = __romPos;\n    .buffers_bss code_VRAM_END (NOLOAD) { *(.bss); }\n"
                  "    buffers_ROM_END = __romPos;\n    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    ovl_select_ROM_START = __romPos;\n    .ovl_select 0x80800000 : AT(ovl_select_ROM_START) { *(.ovl); }\n"
                  "    __romPos += SIZEOF(.ovl_select);\n    ovl_select_ROM_END = __romPos;\n"
                  "    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    /DISCARD/ :\n    {\n        *(*);\n    }\n}\n")

        def mapfile(b_size, a_text=0xadcf0):
            return (".code           0x80051a80   0x100000 load address 0x01914000\n"
                    "                0x80051a80                        code_TEXT_START = .\n"
                    " .text          0x80051a80    0x%x build/src/code/a.o\n"
                    "                0x800ff370                        code_TEXT_END = .\n"
                    "                0x800ff370                        code_DATA_START = .\n"
                    " .data          0x800ff370       0x20 build/src/code/a.o\n"
                    " .data          0x800ff390      0x%x build/src/code/b.o\n"
                    "                0x80116110                        code_RODATA_START = .\n"
                    " .rodata        0x80116110       0x10 build/src/code/b.o\n"
                    ".buffers_bss    0x801524c0    0x42420\n"
                    " .bss           0x801524c0    0x42420 build/asm/buffers.o\n"
                    "                0x801948e0                        buffers_VRAM_END = .\n" % (a_text, b_size))
        with tempfile.TemporaryDirectory() as d:
            p, ref, new = [os.path.join(d, n) for n in ("a.ld", "ref.map", "new.map")]
            open(p, "w").write(script)
            open(ref, "w").write(mapfile(0x100))
            open(new, "w").write(mapfile(0x140, 0xadce0))              # b.o's data grew, a.o's text shrank
            pristine = os.path.join(d, "a.ld.splat")
            af_relink.main([p, "--ref", ref, "--next-rom", "0x73F4D0", "--pristine", pristine])
            out = open(p).read()
            self.assertTrue(os.path.exists(pristine))
            af_relink.main([p, "--ref", ref, "--next-rom", "0x73F4D0", "--pristine", pristine])  # idempotent
            self.assertEqual(open(p).read(), out)
            lines = out.splitlines()
            self.assertLess(lines.index("    __romPos = 0x73F4D0; /* ovl_select keeps the cartridge's vrom */"),
                            lines.index("    ovl_select_ROM_START = __romPos;"))
            self.assertLess(lines.index("    ovl_select_ROM_END = __romPos;"), lines.index("    code_ROM_START = __romPos;"))
            self.assertLess(lines.index("    code_ROM_START = __romPos;"), lines.index("    buffers_ROM_START = __romPos;"))
            self.assertLess(lines.index("    buffers_ROM_START = __romPos;"), lines.index("    /DISCARD/ :"))
            self.assertIn("        build/src/code/b.o(.data);", lines)      # nothing moved yet
            self.assertFalse(any("code_en" in l for l in lines))
            # second pass, with the map of the first link: b.o's data goes to code_en after buffers, its
            # slot stays as a hole; a.o's text stays, padded to its old size; code's ROM runs on to code_en
            moved = os.path.join(d, "moved.txt")
            af_relink.main([p, "--ref", ref, "--map", new, "--moved-out", moved, "--next-rom", "0x73F4D0", "--pristine", pristine])
            lines = open(p).read().splitlines()
            self.assertEqual(open(moved).read(), "build/src/code/a.o .text shrunk\nbuild/src/code/b.o .data\n")
            hole = [l for l in lines if l.startswith("        . += 0x100; /* af_relink: build/src/code/b.o(.data) grew")]
            self.assertEqual(len(hole), 1)
            self.assertLess(lines.index("        code_a = .;"), lines.index(hole[0]))
            pad = [l for l in lines if l.startswith("        . += 0x10; /* af_relink: build/src/code/a.o(.text) shrank")]
            self.assertEqual(lines.index(pad[0]), lines.index("        build/src/code/a.o(.text);") + 1)
            self.assertLess(lines.index("    buffers_ROM_START = __romPos;"), lines.index("    code_en_VRAM = ALIGN(., 16);"))
            self.assertIn("    .code_en code_en_VRAM : AT(code_ROM_START + (code_en_VRAM - code_VRAM)) SUBALIGN(16)", lines)
            self.assertLess(lines.index("        code_en_DATA_START = .;"), lines.index("        build/src/code/b.o(.data);"))
            self.assertLess(lines.index("        build/src/code/b.o(.data);"), lines.index("        code_en_DATA_END = .;"))
            self.assertEqual(lines.count("    code_ROM_END = __romPos;"), 1)
            self.assertEqual(lines.index("    code_ROM_END = __romPos;") - 1,
                             lines.index("    __romPos = code_ROM_START + (. - code_VRAM);"))
            self.assertLess(lines.index("    code_ROM_END = __romPos;"), lines.index("    buffers_ROM_END = __romPos;"))
            self.assertIn("        build/src/code/b.o(.rodata);", lines[lines.index("        code_RODATA_START = .;"):])

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

    def test_luicheck_sees_a_shared_lui_break(self):
        asm = ("glabel func_80051A80_jp\n"
               "/* 0 80051A80 3C018011 */  lui   $at, %hi(RO_FLT_80117250_jp)\n"
               "/* 4 80051A84 C4247250 */  lwc1  $f4, %lo(RO_FLT_80117250_jp)($at)\n"
               "/* 8 80051A88 C4267254 */  lwc1  $f6, %lo(RO_FLT_80117254_jp)($at)\n"
               "/* C 80051A8C 3C018012 */  lui   $at, %hi(D_80120000_jp)\n"
               "/* 10 80051A90 8C210000 */  lw    $at, %lo(D_80120000_jp)($at)\n"
               "  jr    $ra\n   nop\n")
        def mapfile(first, second, other=0x80120000):
            return ("                0x%08x                RO_FLT_80117250_jp\n"
                    "                0x%08x                RO_FLT_80117254_jp\n"
                    "                0x%08x                D_80120000_jp\n" % (first, second, other))
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "asm", "code"))
            open(os.path.join(d, "asm", "code", "f.s"), "w").write(asm)
            ref, same, broken = [os.path.join(d, n) for n in ("ref.map", "same.map", "broken.map")]
            open(ref, "w").write(mapfile(0x80117250, 0x80117254))
            open(same, "w").write(mapfile(0x80137250, 0x80137254))           # both, two windows up
            open(broken, "w").write(mapfile(0x80117250, 0x80118254))         # the halves part
            pairs = af_luicheck.mixed_pairs(os.path.join(d, "asm"))
            self.assertEqual([(p[3], p[5]) for p in pairs], [("RO_FLT_80117250_jp", "RO_FLT_80117254_jp")])
            self.assertEqual(af_luicheck.main([ref, same, "--asm", os.path.join(d, "asm")]), 0)
            self.assertEqual(af_luicheck.main([ref, broken, "--asm", os.path.join(d, "asm")]), 1)

    def test_shiftcheck_wants_nothing_moved(self):
        def mapfile(delta, rodata_extra=0, moved_at=None):
            d = delta
            text = (".code           0x80051a80   0x100000 load address 0x01914000\n"
                    "                0x80051a80                        code_TEXT_START = .\n"
                    " .text          0x80051a80     0x1000 build/src/code/a.o\n"
                    "                0x80051a80                func_80051A80_jp\n"
                    "                0x%08x                        code_DATA_START = .\n" % (0x800ff370 + d))
            if moved_at is None:
                text += (" .data          0x%08x       0x20 build/src/code/a.o\n"
                         "                0x%08x                D_800FF370_jp\n" % (0x800ff370 + d, 0x800ff370 + d))
            nxt = 0x800ff390 + d            # a moved object leaves its slot as a hole
            text += (" .data          0x%08x      0x100 build/src/code/b.o\n"
                     "                0x%08x                D_800FF390_jp\n"
                     "                0x%08x                        code_RODATA_START = .\n"
                     " .rodata        0x%08x       0x10 build/src/code/b.o\n"
                     "                0x%08x                RO_800FF490_jp\n"
                     % (nxt, nxt, nxt + 0x100 + rodata_extra, nxt + 0x100 + rodata_extra, nxt + 0x100 + rodata_extra))
            end = nxt + 0x110 + rodata_extra
            text += (".code_bss       0x%08x     0x1000\n"
                     "                0x%08x                        code_BSS_START = .\n"
                     " .bss           0x%08x     0x1000 build/src/code/b.o\n"
                     "                0x%08x                B_800FF4A0_jp\n"
                     ".buffers_bss    0x%08x      0x100\n"
                     " .bss           0x%08x      0x100 build/asm/buffers.o\n"
                     "                0x%08x                        buffers_VRAM_END = .\n"
                     % (end, end, end, end, end + 0x1000, end + 0x1000, end + 0x1100))
            if moved_at is not None:
                text += (".code_en_data   0x%08x       0x20\n"
                         " .data          0x%08x       0x20 build/src/code/a.o\n"
                         "                0x%08x                D_800FF370_jp\n" % (moved_at, moved_at, moved_at))
            text += (".ovl_x          0x80800000      0x100 load address 0x00800000\n"
                     " .text          0x80800000      0x100 build/src/overlays/x.o\n"
                     "                0x80800000                func_80800000_jp\n")
            return text
        with tempfile.TemporaryDirectory() as d:
            ref = os.path.join(d, "ref.map")
            open(ref, "w").write(mapfile(0))
            def run(text, *args):
                p = os.path.join(d, "new.map")
                open(p, "w").write(text)
                return af_shiftcheck.check(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(p), list(args))
            self.assertEqual(run(mapfile(0)), (0, []))
            delta, errors = run(mapfile(0x20000))                            # even a whole-window shift
            self.assertEqual(delta, 0x20000)
            self.assertTrue(any("moved by 0x20000" in e for e in errors), errors)
            gone = []
            p2 = os.path.join(d, "gone.map")
            open(p2, "w").write(mapfile(0).replace("                0x800ff490                RO_800FF490_jp\n", ""))
            self.assertEqual(af_shiftcheck.check(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(p2), [], gone), (0, []))
            self.assertEqual(gone, ["RO_800FF490_jp"])
            delta, errors = run(mapfile(0x1000))
            self.assertTrue(any("moved by 0x1000" in e for e in errors))
            delta, errors = run(mapfile(0, rodata_extra=0x10))               # b.o's data grew: bss slid
            self.assertTrue(any("build/src/code/b.o .rodata" in e for e in errors), errors)
            delta, errors = run(mapfile(0, moved_at=0x80200000))             # a.o's data left the block
            self.assertTrue(any("build/src/code/a.o .data" in e for e in errors), errors)   # left without --moved
            self.assertEqual(run(mapfile(0, moved_at=0x80200000), ("build/src/code/a.o", ".data")), (0, []))
            delta, errors = run(mapfile(0, moved_at=0x80207FF0), ("build/src/code/a.o", ".data"))
            self.assertTrue(any("crosses a 64 KB" in e for e in errors), errors)
            # text is held too; a section padded in place may shrink, its symbols are free
            moved_text = mapfile(0).replace(" .text          0x80051a80     0x1000 build/src/code/a.o\n"
                                            "                0x80051a80                func_80051A80_jp\n",
                                            " .text          0x80051a90      0xff0 build/src/code/a.o\n"
                                            "                0x80051a90                func_80051A80_jp\n")
            delta, errors = run(moved_text)
            self.assertTrue(any("build/src/code/a.o .text" in e for e in errors), errors)
            shrunk = mapfile(0).replace(" .text          0x80051a80     0x1000 build/src/code/a.o\n",
                                        " .text          0x80051a80      0xff0 build/src/code/a.o\n")
            p3 = os.path.join(d, "shrunk.map")
            open(p3, "w").write(shrunk)
            self.assertNotEqual(af_shiftcheck.check(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(p3))[1], [])
            self.assertEqual(af_shiftcheck.check(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(p3), [], None,
                                                 [("build/src/code/a.o", ".text")]), (0, []))
            # dmadata: the padding may move inside the section, its marks and table may not
            dma = (".dmadata        0x80044690     0xd3f0 load address 0x19d40\n"
                   " .data          0x80044690     0xd3f0 build/src/dmadata/dmadata.o\n"
                   "                0x80044690                dma_rom_ad\n"
                   "                0x%08x                sDmaDataPadding\n"
                   "                0x%08x                        dmadata_VRAM_END = .\n")
            open(ref, "w").write(dma % (0x80051990, 0x80051A80) + mapfile(0))
            self.assertEqual(run(dma % (0x800519B0, 0x80051A80) + mapfile(0)), (0, []))
            delta, errors = run(dma % (0x800519B0, 0x80051AA0) + mapfile(0))
            self.assertTrue(any("mark dmadata_VRAM_END" in e for e in errors), errors)

    def test_anchors_catch_a_fold_spelled_with_a_function(self):
        mapfile = (".code           0x80051a80   0x100000 load address 0x01914000\n"
                   "                0x80051a80                        code_TEXT_START = .\n"
                   " .text          0x800fae00     0x1000 build/asm/jp/code/audio.o\n"
                   "                0x800fae84                Na_KishaStatusLevel\n"
                   "                0x800ff370                        code_DATA_START = .\n"
                   " .data          0x800ff370    0xb000 build/asm/jp/data/code/a.data.o\n"
                   "                0x800ff370                D_800FF370_jp\n"
                   " .data          0x8010af00      0x5a0 build/src/code/m_name_table.o\n"
                   "                0x8010af00                move_obj_profile_table\n"
                   "                0x8010af2c                actor_profile_table\n"
                   ".code_bss       0x8010b4a0     0x1000\n"
                   " .bss           0x8010b4a0     0x1000 build/src/code/m_name_table.o\n"
                   ".buffers_bss    0x8010c4a0      0x100\n"
                   " .bss           0x8010c4a0      0x100 build/asm/buffers.o\n"
                   "                0x8010c5a0                        buffers_VRAM_END = .\n")
        wrong = ("glabel func_80936000_jp\n"
                 "/* 0 80936000 3C078010 */  lui   $a3, %hi(Na_KishaStatusLevel + 0x7C)\n"
                 "/* 4 80936004 00E83821 */  addu  $a3, $a3, $t0\n"
                 "/* 8 80936008 84E7AF00 */  lh    $a3, %lo(Na_KishaStatusLevel + 0x7C)($a3)\n"
                 "/* C 8093600C 8C62000C */  lw    $v0, %lo(D_800FF370_jp + 0xC)($v1)\n")
        right = wrong.replace("Na_KishaStatusLevel + 0x7C", "move_obj_profile_table - 0x10000")
        with tempfile.TemporaryDirectory() as d:
            m = os.path.join(d, "x.map")
            open(m, "w").write(mapfile)
            os.makedirs(os.path.join(d, "asm", "f"))
            s = os.path.join(d, "asm", "f", "f.s")
            open(s, "w").write(wrong)
            layout = af_anchors.Layout(m)
            errors, notes = af_anchors.check(layout, os.path.join(d, "asm"))
            self.assertEqual([k[0] for k in errors], ["Na_KishaStatusLevel"])
            self.assertEqual(layout.tables_above(0x800FAF00), ["move_obj_profile_table (-0x10000)"])
            self.assertEqual(af_anchors.main([m, "--asm", os.path.join(d, "asm")]), 1)
            open(s, "w").write(right)
            self.assertEqual(af_anchors.main([m, "--asm", os.path.join(d, "asm")]), 0)


class Script(unittest.TestCase):
    """The text tools, on banks made up here (the real ones stay on the user's machine)."""

    def test_message_text_round_trips(self):
        raw = bytes([0x7F, 9, 0, 0, 0x16]) + "こんにちは<!".encode("ascii", "ignore")  # DEMONPC0 then text
        raw = bytes([0x7F, 9, 0, 0, 0x16]) + msgbank.encode([("t", "こんにちは\n<!")]) + bytes([0x7F, 3, 8, 0x7F, 0])
        text = msgbank.render(msgbank.decode(raw, msgbank.N64_CHARS))
        self.assertEqual(text, "<DEMONPC0 000016>こんにちは\n\\<!<PAUSE 08><MSGEND>")
        self.assertEqual(msgbank.encode(msgbank.parse(text)), raw)
        self.assertEqual(msgbank.render(msgbank.decode(b"\x80\x7f\x00", msgbank.N64_CHARS)), "{80}<MSGEND>")
        self.assertEqual(msgbank.encode(msgbank.parse("{80}<MSGEND>")), b"\x80\x7f\x00")
        with self.assertRaises(ValueError):
            msgbank.encode([("t", "é")])                       # not in the N64 charset

    def test_banks_and_dumps(self):
        msgs = [b"A\x7f\x00", b"", b"B\xcdC\x7f\x01"]
        ends, acc = [], 0
        for m in msgs:
            acc += len(m)
            ends.append(acc if m else 0)
        table = struct.pack(">%dI" % len(ends), *ends)
        bank = msgbank.Bank.from_table(b"".join(msgs), table, msgbank.N64_CHARS)
        self.assertEqual(bank.messages, msgs)
        gc = msgbank.Bank.from_table(bytes(32) + b"".join(msgs), bytes(32) + table, msgbank.GC_CHARS, header=32)
        self.assertEqual(gc.messages, msgs)
        dump = bank.dump()
        self.assertEqual(dump, "## 0\nA<MSGEND>\n\n## 1\n\n\n## 2\nB\nC<MSGCONTINUE>\n\n")
        back = msgbank.Bank.parse_dump(dump)
        self.assertEqual([msgbank.encode(back[i]) for i in range(3)], msgs)
        # a cartridge: dmadata at 0x19D40 names the index and text files, stored plain
        rom = bytearray(0x30000)
        entries = [(0xBD4000, 0xBD4000 + 0x20, 0x20000, 0), (0xCF9000, 0xCF9000 + 4 * 0x2DE8, 0x21000, 0)]
        for i, e in enumerate(entries):
            struct.pack_into(">IIII", rom, 0x19D40 + 16 * i, *e)
        rom[0x20000:0x20000 + len(b"".join(msgs))] = b"".join(msgs)
        rom[0x21000:0x21000 + len(table)] = table
        cart = msgbank.Bank.from_n64(bytes(rom))
        self.assertEqual(cart.messages, msgs)

    def test_disc_and_archive(self):
        # a RARC with one file, inside a disc with one file in a directory
        name_tab = b"\0.\0..\0msg.bin\0"
        member = b"hello"
        dh = struct.pack(">IIIIII", 1, 0x20, 2, 0x20 + 0x10, len(name_tab), 0x20 + 0x10 + 40)
        dirs = b"ROOT" + struct.pack(">IHHI", 0, 0, 2, 0)
        files = struct.pack(">HHIIII", 0, 0, (0x02 << 24) | 1, 0, 0, 0)          # "." directory entry
        files += struct.pack(">HHIIII", 1, 0, (0x11 << 24) | 6, 0, len(member), 0)  # msg.bin
        body = dh + bytes(8) + dirs + files + name_tab                               # dirs at 0x20, files at 0x30, names at 0x58
        data_off = 0x20 + len(body)
        rarc = struct.pack(">4sIII", b"RARC", data_off + len(member), 0x20, data_off) + bytes(16) + body + member
        arc = gciso.Rarc(rarc)
        self.assertEqual(arc.read("msg.bin"), member)
        fst_names = b"\0dir\0a.arc\0"
        fst = struct.pack(">III", 1 << 24, 0, 3)                       # root: 3 entries
        fst += struct.pack(">III", (1 << 24) | 1, 0, 3)                 # "dir", parent 0, next index 3
        fst += struct.pack(">III", 5, 0x1000, len(rarc))                # "a.arc" at 0x1000
        fst += fst_names
        disc = bytearray(0x2000)
        disc[:6] = b"GAFE01"
        disc[0x20:0x2E] = b"AnimalCrossing"
        struct.pack_into(">II", disc, 0x424, 0x500, len(fst))
        disc[0x500:0x500 + len(fst)] = fst
        disc[0x1000:0x1000 + len(rarc)] = rarc
        d = gciso.Disc(bytes(disc))
        self.assertEqual((d.game, d.name), ("GAFE01", "AnimalCrossing"))
        self.assertEqual(list(d.files), ["dir/a.arc"])
        self.assertEqual(gciso.extract(d, "dir/a.arc/msg.bin"), member)

    def test_alignment_classes(self):
        demo = ("c", msgbank.NAMES["DEMONPC0"], b"\x00\x00\x16")
        end = ("c", msgbank.NAMES["MSGEND"], b"")
        pause = ("c", msgbank.NAMES["PAUSE"], b"\x08")
        self.assertEqual(af_align.classify([demo, ("t", "a"), end], [demo, ("t", "b"), pause, end]), "same")
        self.assertEqual(af_align.classify([("t", "a"), end], [("t", "b"), end]), "plain")
        self.assertEqual(af_align.classify([demo, ("t", "a"), end], [end]), "removed")
        self.assertEqual(af_align.classify([end], [end]), "plain")
        name = ("c", msgbank.NAMES["STR_PLAYERNAME"], b"")
        self.assertEqual(af_align.classify([demo, name, end], [demo, end]), "edited")
        other = ("c", msgbank.NAMES["DEMONPC0"], b"\x00\x00\x03")
        self.assertEqual(af_align.classify([demo, end], [other, ("t", "x"), end]), "different")

    def test_compiler_checks_and_writes_the_files(self):
        end, btn, clear = "<MSGEND>", "<BTN>", "<MSGCLEAR>"
        good = {0: "Hello\nthere" + end, 1: "a\nb\nc\nd" + btn + "\n" + clear + "e" + end}
        msgs = {n: msgbank.parse(s) for n, s in good.items()}
        self.assertEqual(af_text.check_message(0, msgs[0], 32, 4, 0x400), [])
        self.assertEqual(af_text.check_message(1, msgs[1], 32, 4, 0x400), [])
        five = msgbank.parse("a\nb\nc\nd\ne" + end)
        self.assertEqual([lv for lv, _ in af_text.check_message(2, five, 32, 4, 0x400)], ["error"])
        self.assertIn("as the original", af_text.check_message(2, five, 32, 4, 0x400, allowed_lines=5)[0][1])
        split = af_text.split_pages(five)
        self.assertEqual(msgbank.render(split), "a\nb\nc\nd<BTN>\n<MSGCLEAR>e<MSGEND>")
        self.assertEqual(af_text.check_message(2, split, 32, 4, 0x400), [])
        # a trailing newline before a break opens no line, and a cleared page starts again
        self.assertEqual(msgbank.render(af_text.split_pages(msgbank.parse("a\nb\nc\nd\n<BTN>\n<MSGCLEAR>e\nf<MSGEND>"))),
                         "a\nb\nc\nd\n<BTN>\n<MSGCLEAR>e\nf<MSGEND>")
        self.assertEqual(msgbank.render(af_text.split_pages(msgbank.parse("a\nb\nc\nd\n<STR_TAIL>.<MSGEND>"))),
                         "a\nb\nc\nd<BTN>\n<MSGCLEAR><STR_TAIL>.<MSGEND>")
        self.assertEqual(af_text.page_lines(five), 5)
        self.assertEqual([lv for lv, _ in af_text.check_message(3, msgbank.parse("x" * 40 + end), 32, 4, 0x400)], ["warning"])
        self.assertEqual([lv for lv, _ in af_text.check_message(4, msgbank.parse("no end"), 32, 4, 0x400)], ["error"])
        self.assertEqual([lv for lv, _ in af_text.check_message(5, msgbank.parse("é" + end), 32, 4, 0x400)], ["error"])
        self.assertEqual([lv for lv, _ in af_text.check_message(6, msgbank.parse("<CUTARTICLE>x" + end), 32, 4, 0x400)], ["error"])
        self.assertEqual([lv for lv, _ in af_text.check_message(7, msgbank.parse("x" * 0x500 + end), 32, 4, 0x400)][0], "error")
        # a free string the cartridge's message does not print: the game may not fill it
        jp = msgbank.parse("<STR_FREE2>, <STR_COUNTRYNAME>" + end)
        en = msgbank.parse("<STR_FREE2> in <STR_FREE4>'s <STR_COUNTRYNAME>" + end)
        self.assertEqual(af_text.check_message(8, en, 32, 4, 0x400, original=jp),
                         [("warning", "prints STR_FREE4, which the cartridge's message does not: the game may not fill it")])
        self.assertEqual(af_text.check_message(8, jp, 32, 4, 0x400, original=jp), [])
        with tempfile.TemporaryDirectory() as d:
            text, index = os.path.join(d, "t.bin"), os.path.join(d, "i.bin")
            all_msgs = {n: msgbank.parse("<MSGEND>") for n in range(af_text.N64_COUNT)}
            all_msgs.update(msgs)
            af_text.compile_bank(all_msgs, text, index)
            t = open(text, "rb").read()
            ends = struct.unpack(">%dI" % af_text.N64_COUNT, open(index, "rb").read())
            self.assertEqual(t[:ends[0]], msgbank.encode(msgs[0]))
            self.assertEqual(t[ends[0]:ends[1]], msgbank.encode(msgs[1]))
            self.assertEqual(len(t) % 16, 0)

    def test_draft_adapts_the_official_text(self):
        counts = collections.Counter()
        gc = msgbank.parse("<CUTARTICLE>Hey, caf\u00e9 \u2665!<MALEFEMALECHK 00010002><MSGEND>")
        out = af_text.adapt(gc, counts)
        self.assertEqual(msgbank.render(out), "Hey, cafe ♥!<MSGEND>")      # the heart is in the N64 charset
        self.assertEqual(counts["dropped CUTARTICLE"], 1)
        with tempfile.TemporaryDirectory() as d:
            paths = {k: os.path.join(d, k + ".txt") for k in ("n64", "gc", "ours", "out")}
            n64 = "".join("## %d\n%s<MSGEND>\n\n" % (n, "jp%d" % n) for n in range(af_text.N64_COUNT))
            gc = "".join("## %d\n%s<MSGEND>\n\n" % (n, "en%d" % n) for n in range(5))
            open(paths["n64"], "w").write(n64)
            open(paths["gc"], "w").write(gc)
            open(paths["ours"], "w").write("## 1\nours<MSGEND>\n\n")
            align = os.path.join(d, "align.tsv")
            rows = ["number\tclass\tn64\tgc\n", "0\tsame\t\t\n", "1\tplain\t\t\n", "2\tedited\t\t\n", "3\tremoved\t\t\n", "4\tdifferent\t\t\n"]
            open(align, "w").write("".join(rows))
            af_text.draft(paths["n64"], paths["gc"], align, paths["out"], [paths["ours"]])
            back = msgbank.Bank.parse_dump(open(paths["out"], encoding="utf-8").read())
            got = {n: msgbank.render(back[n]) for n in range(6)}
            self.assertEqual(got, {0: "en0<MSGEND>", 1: "ours<MSGEND>", 2: "en2<MSGEND>", 3: "jp3<MSGEND>",
                                   4: "jp4<MSGEND>", 5: "jp5<MSGEND>"})
            self.assertEqual(len(back), af_text.N64_COUNT)

    def test_relink_adds_plain_segments(self):
        script = ("SECTIONS\n{\n    __romPos = 0;\n"
                  "    code_ROM_START = __romPos;\n    .code 0x80051A80 : AT(code_ROM_START) SUBALIGN(16)\n    {\n"
                  "        code_TEXT_START = .;\n        code_TEXT_END = .;\n        code_DATA_START = .;\n    }\n"
                  "    __romPos += SIZEOF(.code);\n    code_ROM_END = __romPos;\n    code_VRAM_END = .;\n"
                  "    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    buffers_ROM_START = __romPos;\n    buffers_ROM_END = __romPos;\n    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    ovl_select_ROM_START = __romPos;\n    ovl_select_ROM_END = __romPos;\n    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    /DISCARD/ :\n    {\n        *(*);\n    }\n}\n")
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "a.ld")
            open(p, "w").write(script)
            af_relink.main([p, "--next-rom", "0x73F4D0", "--pristine", p + ".splat",
                            "--segment", "msg_en_text=build/assets/jp/en/msg_text.o"])
            lines = open(p).read().splitlines()
            start = lines.index("    msg_en_text_ROM_START = __romPos;")
            self.assertLess(lines.index("    buffers_ROM_START = __romPos;"), start)
            self.assertLess(start, lines.index("    /DISCARD/ :"))
            self.assertIn("        build/assets/jp/en/msg_text.o(.data);", lines[start:start + 8])
            self.assertEqual(lines[start - 1], "    __romPos = ALIGN(__romPos, 0x1000);")

    def test_single_line_banks_and_names(self):
        ok = msgbank.parse("That's right!")
        self.assertEqual(af_text.check_message(37, ok, 32, 4, 24, single=True), [])
        self.assertEqual([lv for lv, _ in af_text.check_message(0, msgbank.parse("two\nlines"), 32, 4, 24, single=True)], ["error"])
        self.assertEqual([lv for lv, _ in af_text.check_message(0, msgbank.parse("x<MSGEND>"), 32, 4, 24, single=True)], ["error"])
        # the name segment: 8 bytes of header, 6-byte names; the GameCube's table skips 4 and is 8 wide
        seg = bytes(8) + msgbank.encode([("t", "ニコバン  ")]) + msgbank.encode([("t", "オリビア  ")])
        table = bytes(32) + b"Bob     " + b"Cashmere"
        self.assertEqual(af_names.english_names(table), ["Bob", "Cashmere"])
        out, cut = af_names.rename(seg, af_names.english_names(table))
        self.assertEqual(out[8:14], b"Bob   ")
        self.assertEqual(out[14:20], b"Cashme")
        self.assertEqual(cut, [(1, "Cashmere")])

    def test_carried_codes_and_counts(self):
        toks = msgbank.parse("It's <STR_HOUR> <STR_AMPM>.<MSGEND>")
        self.assertEqual(af_text.check_message(0, toks, 32, 4, 0x400), [])        # carried: not an error
        self.assertIn(bytes([0x7F, msgbank.NAMES["LUCK_6"]]), msgbank.encode(af_text.carried(toks)))
        with tempfile.TemporaryDirectory() as d:
            paths = {k: os.path.join(d, k + ".txt") for k in ("n64", "gc", "out")}
            open(paths["n64"], "w").write("## 0\nあ\n\n## 1\nい\n\n")
            open(paths["gc"], "w").write("## 0\nRed\n\n## 1\nBlue\n\n## 2\n\n\n## 3\nJanuary\n\n")
            old = af_text.N64_COUNT
            try:
                af_text.N64_COUNT = 4
                af_text.draft(paths["n64"], paths["gc"], "-", paths["out"], [])
            finally:
                af_text.N64_COUNT = old
            back = msgbank.Bank.parse_dump(open(paths["out"], encoding="utf-8").read())
            self.assertEqual([msgbank.render(back[n]) for n in range(4)], ["Red", "Blue", "", "January"])


def save_like(seed=1):
    """0xF980 bytes shaped like a save: records with small values, names, long runs of zeros."""
    rnd = random.Random(seed)
    out = bytearray(aflz.SAVE_SIZE)
    for at in range(0, 0x9000, 0x40):
        out[at:at + 16] = bytes(rnd.randrange(8) for _ in range(16))
        out[at + 16:at + 22] = b"urld\x00\x00"
    for at in range(0x9000, 0xC000, 2):
        out[at] = rnd.choice((0, 0, 0x11, 0x25))
    return bytes(out)


def changes_patch_file(name):
    """A new file's text as decomp/changes.patch adds it (None if it does not)."""
    lines, inside = [], False
    with open(os.path.join(HERE, "..", "decomp", "changes.patch"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("diff --git"):
                inside = line.rstrip().endswith(" b/" + name)
            elif inside and line.startswith("+") and not line.startswith("+++"):
                lines.append(line[1:])
    return "".join(lines) or None


class Save(unittest.TestCase):
    """The en build's compressed save (tools/aflz.py and its C twin, include/af_lz.h)."""

    def test_round_trips(self):
        rnd = random.Random(2)
        cases = [b"", b"x", b"abcabcabcabcabc", bytes(0xF980), save_like(),
                 rnd.randbytes(3000),
                 b" ".join(rnd.choice((b"dear", b"the", b"letter", b"town")) for _ in range(900))]
        for data in cases:
            packed = aflz.compress(data)
            self.assertEqual(aflz.decompress(packed, len(data)), data)
        self.assertLess(len(aflz.compress(save_like())), 0x4000)

    def test_encoding(self):
        # Yaz0's: group byte MSB first, set bit = literal; 2-byte ref (len-2)<<12 | dist-1; 3-byte for 18+
        self.assertEqual(aflz.compress(b"abcabcabcabcabc"), bytes([0xE0, 0x61, 0x62, 0x63, 0xA0, 0x02]))
        self.assertEqual(aflz.compress(bytes(20)), bytes([0x80, 0x00, 0x00, 0x00, 0x01]))
        self.assertEqual(aflz.compress(bytes(0x113)), bytes([0xA0, 0x00, 0x00, 0x00, 0xFF, 0x00]))  # 1 + 0x111 + 1
        self.assertIsNone(aflz.compress(bytes(0xFFFF)))                # positions are 16-bit in the C
        self.assertIsNone(aflz.compress(bytes(range(256)) * 4, cap=100))

    def test_decompress_checks(self):
        packed = aflz.compress(b"abcabcabcabcabc")
        self.assertIsNone(aflz.decompress(packed[:-1], 15))           # ends early
        self.assertIsNone(aflz.decompress(packed + b"\x00", 15))       # bytes left over
        self.assertIsNone(aflz.decompress(bytes([0x00, 0x10, 0x05]), 3))  # refers before the start
        self.assertIsNone(aflz.decompress(packed, 14))                 # runs past the end

    def test_slot_images(self):
        save = save_like()
        ext = b"Dear urld, the letter's official text." * 40
        image, compressed = aflz.pack(save, ext)
        self.assertTrue(compressed)
        self.assertEqual(len(image), aflz.SLOT_SIZE)
        magic, save_comp, ext_size, ext_comp, ext_sum = struct.unpack(">IIIII", image[:20])
        self.assertEqual((magic, ext_size, ext_sum), (0x41465A31, len(ext), sum(ext)))
        self.assertEqual(image[20 + save_comp + ext_comp:], bytes(aflz.SLOT_SIZE - 20 - save_comp - ext_comp))
        self.assertEqual(aflz.unpack(image), (save, ext))
        self.assertEqual(aflz.unpack(aflz.pack(save)[0]), (save, b""))
        # a save that does not compress is written as the cartridge writes it, and read so
        noise = random.Random(3).randbytes(aflz.SAVE_SIZE)
        image, compressed = aflz.pack(noise, ext)
        self.assertFalse(compressed)
        self.assertEqual(image, noise + bytes(aflz.SLOT_SIZE - aflz.SAVE_SIZE))
        self.assertEqual(aflz.unpack(image), (noise, None))
        damaged = bytearray(aflz.pack(save, ext)[0])
        damaged[20 + save_comp + 3] ^= 0xFF
        with self.assertRaises(ValueError):
            aflz.unpack(bytes(damaged))

    def test_c_twin_matches(self):
        header = changes_patch_file("include/af_lz.h")
        cc = shutil.which("cc") or shutil.which("gcc")
        if header is None or cc is None:
            self.skipTest("needs include/af_lz.h in decomp/changes.patch and a host C compiler")
        prog = r"""
#include <stdio.h>
#include "af_lz.h"
static aflz_Work work;
static unsigned char src[0x10000], dst[0x12000], back[0x10000];
int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "rb");
    int n = fread(src, 1, sizeof(src), f), c;
    fclose(f);
    c = aflz_compress(src, n, dst, sizeof(dst), &work);
    if (c < 0 || !aflz_decompress(dst, c, back, n)) return 2;
    f = fopen(argv[2], "wb");
    fwrite(dst, 1, c, f);
    fclose(f);
    return 0;
}
"""
        rnd = random.Random(4)
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "af_lz.h"), "w") as f:
                f.write(header)
            with open(os.path.join(tmp, "t.c"), "w") as f:
                f.write(prog)
            exe = os.path.join(tmp, "t")
            subprocess.run([cc, "-O1", "-o", exe, os.path.join(tmp, "t.c")], check=True)
            for data in (save_like(5), rnd.randbytes(5000), b"abcabcabcabcabc"):
                with open(os.path.join(tmp, "in"), "wb") as f:
                    f.write(data)
                subprocess.run([exe, os.path.join(tmp, "in"), os.path.join(tmp, "out")], check=True)
                with open(os.path.join(tmp, "out"), "rb") as f:
                    self.assertEqual(f.read(), aflz.compress(data))


if __name__ == "__main__":
    unittest.main()
