"""The tools' own tests: nothing here needs the cartridge (make test)."""

import collections
import hashlib
import os
import random
import re
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
import bps  # noqa: E402
import af_anchors  # noqa: E402
import af_bench  # noqa: E402
import af_align  # noqa: E402
import af_dmaorder  # noqa: E402
import af_dumpdata  # noqa: E402
import af_font  # noqa: E402
import af_luicheck  # noqa: E402
import af_nafe_gfx  # noqa: E402
import af_nafe_logo  # noqa: E402
import af_nafe_text  # noqa: E402
import af_names  # noqa: E402
import af_ovlreloc  # noqa: E402
import af_ranges  # noqa: E402
import af_relink  # noqa: E402
import af_relsyms  # noqa: E402
import af_shiftcheck  # noqa: E402
import af_text  # noqa: E402
import af_items  # noqa: E402
import af_ui  # noqa: E402
import af_gc_signs  # noqa: E402
import af_menu_tex  # noqa: E402
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


class Bps(unittest.TestCase):
    """tools/bps.py: the deliverable's format, made and applied."""

    def test_varints_are_the_ups_ones(self):
        for value in (0, 1, 127, 128, 255, 16511, 16512, 1 << 24, (1 << 40) + 3):
            self.assertEqual(bps.read_varint(bps.varint(value), 0), (value, len(bps.varint(value))))
            self.assertEqual(ups._varint(bps.varint(value), 0)[0], value)

    def test_round_trip_with_moved_runs_edits_and_padding(self):
        rnd = random.Random(3)
        source = bytes(rnd.randrange(256) for _ in range(60000))
        target = (source[:4000] + b"new bytes" * 50 + source[30001:50000] + b"\xff" * 2000 + source[4000:20000] +
                  bytes(77) + source[50000:])
        patch, counts = bps.create(source, target, b"meta")
        self.assertEqual(bps.apply(source, patch), target)
        self.assertEqual(sum(counts.values()), len(target))
        self.assertEqual(counts["source read"], 4000)                  # the run left in its place
        self.assertEqual(counts["source copy"], 19999 + 16000 + 10000)  # the moved runs, at any offset
        self.assertGreater(counts["target copy"], 1900)                # the padding, written once
        self.assertLess(len(patch), 2000)
        self.assertEqual(patch[:4], b"BPS1")

    def test_wrong_source_and_damaged_patch_are_refused(self):
        source = bytes(range(256)) * 40
        patch, _ = bps.create(source, source[::-1])
        with self.assertRaises(ValueError):
            bps.apply(source[:-1] + b"x", patch)
        damaged = bytearray(patch)
        damaged[len(damaged) // 2] ^= 1
        with self.assertRaises(ValueError):
            bps.apply(source, bytes(damaged))


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

    def test_relink_sees_symbols_move_inside_a_section_of_the_same_size(self):
        def mapfile(f2):
            return (".code           0x80051a80   0x100000 load address 0x01914000\n"
                    " .text          0x80051a80       0x40 build/src/code/a.o\n"
                    "                0x80051a80                f1\n"
                    "                0x%08x                f2\n"
                    " .text          0x80051ac0       0x20 build/src/code/b.o\n"
                    "                0x80051ac0                g1\n" % f2)
        with tempfile.TemporaryDirectory() as d:
            ref, new = os.path.join(d, "ref.map"), os.path.join(d, "new.map")
            open(ref, "w").write(mapfile(0x80051a90))
            open(new, "w").write(mapfile(0x80051a84))           # f1 is 12 bytes shorter; the size rounds to 0x40
            grown, shrunk = af_relink.changed_sections(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(new))
            self.assertEqual(grown, [])
            self.assertEqual(shrunk, [(".text", "build/src/code/a.o", 0x40, 0x40)])
            grown, shrunk = af_relink.changed_sections(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(ref))
            self.assertEqual((grown, shrunk), ([], []))

    def test_overlays_shrink_in_place_and_never_grow(self):
        def mapfile(text_size, f2):
            return (".code           0x80051a80     0x1000 load address 0x01914000\n"
                    "                0x80051a80                        code_TEXT_START = .\n"
                    " .text          0x80051a80     0x1000 build/src/code/a.o\n"
                    "                0x80052a80                        code_DATA_START = .\n"
                    ".buffers_bss    0x80052a80      0x100\n"
                    " .bss           0x80052a80      0x100 build/asm/buffers.o\n"
                    "                0x80052b80                        buffers_VRAM_END = .\n"
                    ".board_ovl      0x80888e90     0x2000 load address 0x007908a0\n"
                    " .text          0x80888e90    0x%x build/src/overlays/board.o\n"
                    "                0x80888e90                func_80888E90_jp\n"
                    "                0x%08x                func_8088A2D0_jp\n"
                    " .data          0x8088a7a0      0x430 build/asm/board.data.o\n"
                    "                0x8088a7a0                D_8088A7A0_jp\n" % (text_size, f2))
        with tempfile.TemporaryDirectory() as d:
            ref, new = os.path.join(d, "ref.map"), os.path.join(d, "new.map")
            open(ref, "w").write(mapfile(0x1910, 0x8088a2d0))
            open(new, "w").write(mapfile(0x1910, 0x8088a2d0))
            self.assertEqual(af_relink.changed_overlay_sections(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(new)), [])
            open(new, "w").write(mapfile(0x1800, 0x8088a1c0))                 # the text shrank
            shrunk = af_relink.changed_overlay_sections(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(new))
            self.assertEqual(shrunk, [(".text", "build/src/overlays/board.o", 0x1800, 0x1910)])
            open(new, "w").write(mapfile(0x1910, 0x8088a2c4))                 # same size, a function moved
            shrunk = af_relink.changed_overlay_sections(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(new))
            self.assertEqual(shrunk, [(".text", "build/src/overlays/board.o", 0x1910, 0x1910)])
            open(new, "w").write(mapfile(0x1920, 0x8088a2d0))                 # the text grew: refused
            with self.assertRaises(SystemExit):
                af_relink.changed_overlay_sections(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(new))
            # padded in place: the symbols inside are free, the section's start and size are held
            open(new, "w").write(mapfile(0x1910, 0x8088a1c0))
            r, n = af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(new)
            self.assertTrue(any("func_8088A2D0_jp" in e for e in af_shiftcheck.check(r, n, [])[1]))
            self.assertEqual(af_shiftcheck.check(r, n, [], None, [("build/src/overlays/board.o", ".text")])[1], [])
            open(new, "w").write(mapfile(0x1920, 0x8088a1c0))
            errors = af_shiftcheck.check(af_shiftcheck.LinkMap(ref), af_shiftcheck.LinkMap(new), [], None,
                                         [("build/src/overlays/board.o", ".text")])[1]
            self.assertTrue(any("no bigger" in e for e in errors), errors)

    def test_overlay_relocation_tables_compare_by_groups(self):
        hi, lo, j26, w32 = 0x45000000, 0x46000000, 0x44000000, 0x82000000
        cart = [hi | 0xBC, lo | 0xC0, j26 | 0x3E0, hi | 0x7F4, lo | 0x804, hi | 0x7DC, lo | 0x7F8, w32 | 0x408]
        swapped = [hi | 0xBC, lo | 0xC0, j26 | 0x3E0, hi | 0x7DC, lo | 0x7F8, hi | 0x7F4, lo | 0x804, w32 | 0x408]
        self.assertTrue(af_ovlreloc.same_table(cart, swapped))            # whole HI/LO groups in another order
        broken = [hi | 0xBC, lo | 0x804, j26 | 0x3E0, hi | 0x7F4, lo | 0xC0, hi | 0x7DC, lo | 0x7F8, w32 | 0x408]
        self.assertFalse(af_ovlreloc.same_table(cart, broken))            # a LO16 under another HI16
        self.assertFalse(af_ovlreloc.same_table(cart, cart[:-1]))
        self.assertEqual(af_ovlreloc.groups([hi | 4, lo | 8, lo | 12, j26 | 16]), [(hi | 4, lo | 8, lo | 12), (j26 | 16,)])

    def test_overlay_objects_keep_each_sections_order(self):
        # fado lays each section out in its arguments' order: an overlay whose .data starts in
        # splat's data object and goes on in the C object gets the data object first
        c, data = "build/src/x.o", "build/asm/jp/data/x.data.o"
        split = [(c, ".text"), (data, ".data"), (c, ".data"), (c, ".rodata")]
        self.assertEqual(af_ovlreloc.object_order(split), [data, c])
        plain = [(c, ".text"), (c, ".data"), (data, ".data"), (c, ".rodata")]
        self.assertEqual(af_ovlreloc.object_order(plain), [c, data])
        with self.assertRaises(SystemExit):
            af_ovlreloc.object_order([(c, ".text"), (data, ".text"), (data, ".data"), (c, ".data")])

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

    def test_letters_keep_their_trailing_newlines(self):
        raw = msgbank.encode([("t", "To my sweet,\n,\n")])
        bank = msgbank.Bank([raw, msgbank.encode([("t", "Bye!")])], msgbank.N64_CHARS)
        dump = bank.dump()
        self.assertEqual(dump, "## 0\nTo my sweet,\n,{cd}\n\n## 1\nBye!\n\n")
        back = msgbank.Bank.parse_dump(dump)
        self.assertEqual(msgbank.encode(back[0]), raw)
        self.assertEqual(msgbank.render(back[0]), "To my sweet,\n,\n")

    def test_letter_banks_check_and_draft(self):
        body = msgbank.parse("Hello, <STR_FREE0>.\nThanks!\n")
        self.assertEqual(af_text.check_message(0, body, 32, 4, 192, letter=True), [])
        self.assertEqual([lv for lv, _ in af_text.check_message(0, msgbank.parse("x<BTN>"), 32, 4, 192, letter=True)],
                         ["error"])                                      # a letter has no page codes
        self.assertEqual([lv for lv, _ in af_text.check_message(0, msgbank.parse("x" * 200), 32, 4, 192, letter=True)],
                         ["error"])
        # the letters' expander handles the free strings only: any other string code would hang it
        self.assertEqual([lv for lv, _ in af_text.check_message(0, msgbank.parse("Hi <STR_PLAYERNAME>!"), 32, 4, 192,
                                                                letter=True)], ["error"])
        self.assertEqual(af_text.check_message(0, msgbank.parse("<STR_FREE19>"), 32, 4, 192, letter=True), [])
        with tempfile.TemporaryDirectory() as d:
            jp, out = os.path.join(d, "jp.txt"), os.path.join(d, "out.txt")
            open(jp, "w", encoding="utf-8").write("## 0\n<STR_FREE1>\u3088\u308a\n\n## 1\n<STR_FREE14>\u306e<STR_FREE1>\u3088\u308a\n\n")
            old = af_text.N64_COUNT
            try:
                af_text.N64_COUNT = 2
                af_text.draft(jp, "-", "-", out, [])                    # the villagers' footers: the sender, ours
            finally:
                af_text.N64_COUNT = old
            back = msgbank.Bank.parse_dump(open(out, encoding="utf-8").read())
            self.assertEqual([msgbank.render(back[n]) for n in range(2)], ["<STR_FREE1>", "<STR_FREE1>"])

    def test_letter_draft_takes_a_moved_letter_from_its_number(self):
        # LETTER_REMAP: the GameCube keeps some event notices at other numbers; the draft takes them from there
        remap = af_text.LETTER_REMAP["mail_body"]
        self.assertTrue(all(420 <= n <= 462 and 420 <= g <= 462 for n, g in remap.items()))
        self.assertEqual(len(set(remap.values())), len(remap))              # no GameCube letter taken twice
        with tempfile.TemporaryDirectory() as d:
            jp, gc, out = (os.path.join(d, f) for f in ("jp.txt", "gc.txt", "out.txt"))
            open(jp, "w", encoding="utf-8").write("".join("## %d\nあ%d\n\n" % (n, n) for n in range(4)))
            open(gc, "w", encoding="utf-8").write("## 0\nzero\n\n## 1\none\n\n## 2\ntwo\n\n## 3\n\n\n")
            old = af_text.N64_COUNT
            try:
                af_text.N64_COUNT = 4
                af_text.draft(jp, gc, "-", out, [], {1: 2, 3: 1})
            finally:
                af_text.N64_COUNT = old
            text = open(out, encoding="utf-8").read()
            back = msgbank.Bank.parse_dump(text)
            self.assertEqual([msgbank.render(back[n]) for n in range(4)], ["zero", "two", "two", "one"])
            self.assertIn("## 1\n#: the GameCube's letter 2", text)

    def test_fallbacks_keep_the_marks_the_cartridge_has(self):
        # the GameCube's drop and anger mark are the N64 font's; its semicolon and slash, punctuation it has
        self.assertEqual(af_text.ascii_fallback("a; b/c \U0001f322\U0001f4a2☃"), "a, b-c \U0001f4a7✚～")
        self.assertTrue(all(c in msgbank.N64_BYTES for c in "".join(af_text.FALLBACK.values()) if c != "*"))

    def test_letters_compile_to_one_table(self):
        # the eight banks, numbered on from one another, each entry from the end before it
        self.assertEqual([af_text.LETTER_FIRST[b] for b in af_text.LETTER_BANKS],
                         [0, 544, 1088, 1632, 2016, 2400, 2784, 3168])
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for bank in af_text.LETTER_BANKS:
                path = os.path.join(d, bank + ".txt")
                count = af_text.letter_count(bank)
                open(path, "w", encoding="utf-8").write("".join("## %d\n%s%d\n\n" % (n, bank[:2], n % 7)
                                                                  for n in range(count)))
                paths.append(path)
            old = af_text.N64_COUNT
            try:
                text, index = af_text.compile_letters(paths, os.path.join(d, "t.bin"), os.path.join(d, "i.bin"))
            finally:
                af_text.N64_COUNT = old
            ends = struct.unpack(">3552I", index[:3552 * 4])
            self.assertEqual(len(index) % 16, 0)
            self.assertEqual(len(text) % 16, 0)

            def entry(g):
                return text[ends[g - 1] if g else 0:ends[g]]
            self.assertEqual(entry(0), msgbank.encode([("t", "ma0")]))
            self.assertEqual(entry(544 + 9), msgbank.encode([("t", "ma2")]))
            self.assertEqual(entry(3168 + 383), msgbank.encode([("t", "vm5")]))
            open(paths[1], "a", encoding="utf-8").write("## 3\nHi <STR_PLAYERNAME>\n\n")
            try:
                self.assertIsNone(af_text.compile_letters(paths, os.path.join(d, "t.bin"), os.path.join(d, "i.bin")))
            finally:
                af_text.N64_COUNT = old

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
        self.assertEqual([lv for lv, _ in af_text.check_message(6, msgbank.parse("<SETCURSORJUST>x" + end), 32, 4, 0x400)], ["error"])
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

    def test_layout_fits_lines_to_the_window(self):
        widths = [12] * 256                                     # kana stay 12; Latin 6, the space 4
        for c, ch in enumerate(msgbank.N64_CHARS):
            if len(ch) == 1 and "!" <= ch <= "~":
                widths[c] = 6
        widths[msgbank.N64_BYTES[" "]] = 4

        def lay(text, counts=None):
            return msgbank.render(af_text.layout(msgbank.parse(text), widths, 60, 4, counts))

        same = "aaaa bbbb\ncccc\n<MSGEND>"                     # every line fits: untouched
        self.assertEqual(lay(same), same)
        kana = "ああああああ ああああああ\n<MSGEND>"            # Japanese: the cartridge's own lines
        self.assertEqual(lay(kana), kana)
        # 9 letters fit in 60 pixels with a space (4 + 4 + 1 + 4 letters = 58): "aaaa bbbb cc" is 70
        counts = collections.Counter()
        self.assertEqual(lay("aaaa bbbb cc\ndddd\n<MSGEND>", counts), "aaaa bbbb\ncc dddd\n<MSGEND>")
        self.assertEqual(counts["pages with lines moved on"], 1)
        self.assertEqual(lay("aaaa bbbb cc\nd\ne\nf\n<MSGEND>"), "aaaa bbbb\ncc d\ne\nf\n<MSGEND>")
        # moving on would make a fifth line (before the empty one): the page is filled anew
        self.assertEqual(lay("aaaa bbbb cc\n\nd\ne\n<MSGEND>", counts), "aaaa bbbb\ncc d e\n<MSGEND>")
        self.assertEqual(counts["pages filled anew"], 1)
        # more than a page: split after the last sentence that fits, waiting for A once
        text = "aa bb. cc dd. ee ff.<PAUSE 08> gg hh. ii jj.\nkk ll. mm nn.<BTN>\n<MSGCLEAR>oo.<MSGEND>"
        out = lay(text, counts)
        self.assertEqual(out, "aa bb. cc\ndd. ee ff.<PAUSE 08>\ngg hh. ii\njj. kk ll.<BTN>\n<MSGCLEAR>mm nn.<BTN>\n"
                              "<MSGCLEAR>oo.<MSGEND>")
        self.assertEqual(counts["pages split"], 1)
        self.assertEqual(af_text.page_lines(msgbank.parse(out)), 4)
        cut = lay("aa bb cc dd ee ff gg hh ii jj kk.<BTN> ll mm.<MSGEND>")
        self.assertNotIn("<BTN><BTN>", cut)
        self.assertNotIn("<MSGCLEAR><BTN>", cut)
        # one word wider than the window stays, and the check says so
        wide = msgbank.parse("abcdefghijklm\n<MSGEND>")
        self.assertEqual(af_text.layout(wide, widths, 60), wide)
        self.assertEqual(af_text.wide_lines(wide, af_text.Measure(widths), 60), [78])
        # a free string counts as a name's or a thing's length in average letters
        self.assertEqual(af_text.Measure(widths)(af_text._items(msgbank.parse("<STR_PLAYERNAME>"))), 36)

    def test_draft_adapts_the_official_text(self):
        counts = collections.Counter()
        gc = msgbank.parse("<SETCURSORJUST><CUTARTICLE>Hey, caf\u00e9 \u2665!<MALEFEMALECHK 00010002><MSGEND>")
        out = af_text.adapt(gc, counts)
        self.assertEqual(msgbank.render(out), "<CUTARTICLE>Hey, cafe ♥!<MSGEND>")   # the heart is in the N64 charset
        self.assertEqual(counts["dropped SETCURSORJUST"], 1)
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

    def test_the_official_text_takes_the_cartridges_codes(self):
        p, r = msgbank.parse, msgbank.render

        def mended(cart, theirs):
            out, how = af_text.mend(p(cart), p(theirs))
            return (r(out) if out is not None else None), how

        # the same codes: as it is
        self.assertEqual(mended("あ<DEMONPC0 000015>い<MSGEND>", "Hi<DEMONPC0 000015> yo<MSGEND>"),
                         ("Hi<DEMONPC0 000015> yo<MSGEND>", ""))
        # a choice of its own (an answer more, past the cartridge's choices): the cartridge's codes after the text
        cart = "あ?<SETSELSTR2 00030004><BTN><OPENCHOICE><SETNEXTMSG0 0100><SETNEXTMSG1 0101><FORCENEXT><MSGCONTINUE>"
        theirs = ("Well?<SETSELSTR3 000301f00004><BTN><OPENCHOICE><SETNEXTMSG0 0100><SETNEXTMSG1 2de0>"
                  "<SETNEXTMSG2 0101><FORCENEXT><MSGCONTINUE>")
        self.assertEqual(mended(cart, theirs)[0], "Well?" + cart[2:])
        # its own strings for the answers (a quiz's for its own events): the cartridge's
        self.assertEqual(mended(cart, "Well?" + cart[2:].replace("00030004", "00440010"))[0], "Well?" + cart[2:])
        # talk orders of every slot (a quest's, the end's in slots 2 and 8) put back at the English's beats
        self.assertEqual(mended("<DEMONPC0 05006e><DEMONPC0 000016>あい<DEMONPC0 020001><DEMONPC0 080001><MSGEND>",
                                "<DEMONPCQST 050069><DEMONPC0 000016>Hi there.<MSGEND>")[0],
                         "<DEMONPC0 05006e><DEMONPC0 000016>Hi there.<DEMONPC0 020001><DEMONPC0 080001><MSGEND>")
        # a sound more or less in a run of them: dropped, or put by its neighbour
        self.assertEqual(mended("あ<SNDTRGSYS 05>い<SNDTRGSYS 05><MSGEND>",
                                "Hi <SNDTRGSYS 05>yo <SNDTRGSYS 05>ho<SNDTRGSYS 05><MSGEND>")[0],
                         "Hi <SNDTRGSYS 05>yo ho<SNDTRGSYS 05><MSGEND>")
        self.assertEqual(mended("あ<SNDTRGSYS 05><SNDTRGSYS 05>い<MSGEND>", "Hi<SNDTRGSYS 05>yo<MSGEND>")[0],
                         "Hi<SNDTRGSYS 05><SNDTRGSYS 05>yo<MSGEND>")
        # what it does after its text otherwise (a question the cartridge's does not ask), or other codes in its
        # text (a luck code dropped): not that message's English
        self.assertEqual(mended("あ<MSGEND>", "Well?" + cart[2:]), (None, "what it does after its text"))
        self.assertEqual(mended("あ<LUCK_NEUTRAL>い<MSGEND>", "Hi yo<MSGEND>"), (None, "codes in its text"))
        # the draft: the cartridge's codes put back, or the cartridge's Japanese; a number's text from another
        with tempfile.TemporaryDirectory() as d:
            paths = {k: os.path.join(d, k + ".txt") for k in ("n64", "gc", "out")}
            n64 = {n: "jp%d<MSGEND>" % n for n in range(af_text.N64_COUNT)}
            n64[1] = "<DEMONPC0 020001>jp1<MSGEND>"
            n64[3] = n64[4] = "<DEMONPC0 000016>jp3<MSGEND>"
            open(paths["n64"], "w").write("".join("## %d\n%s\n\n" % kv for kv in sorted(n64.items())))
            gc = {0: "en0<MSGEND>", 1: "en1<MSGEND>", 2: "en2?<SETSELSTR2 00030004><OPENCHOICE><MSGCONTINUE>",
                  3: "en3, its own<MSGEND>", 4: "<DEMONPC0 00000e>en4<MSGEND>"}
            open(paths["gc"], "w").write("".join("## %d\n%s\n\n" % kv for kv in sorted(gc.items())))
            align = os.path.join(d, "align.tsv")
            open(align, "w").write("number\tclass\tn64\tgc\n" + "".join("%d\tsame\t\t\n" % n for n in range(5)))
            af_text.draft(paths["n64"], paths["gc"], align, paths["out"], [], {3: 4})
            text = open(paths["out"], encoding="utf-8").read()
            back = msgbank.Bank.parse_dump(text)
            self.assertEqual([r(back[n]) for n in range(5)],
                             ["en0<MSGEND>", "<DEMONPC0 020001>en1<MSGEND>", "jp2<MSGEND>",
                              "<DEMONPC0 000016>en4<MSGEND>", "<DEMONPC0 000016>en4<MSGEND>"])
            self.assertIn("#: TODO translate (the GameCube's text steers the game otherwise", text)
            self.assertIn("#: the GameCube's message 4 (MESSAGE_REMAP)", text)
        self.assertEqual(sorted(af_text.MESSAGE_REMAP.values()), [11724, 11729, 11731, 11733, 11735])

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
        # the article's codes: carried as LUCK_7 and LUCK_8, in messages and in letters
        toks = msgbank.parse("<CAPTIALIZE><CUTARTICLE><STR_ITEM0>!<MSGEND>")
        self.assertEqual(af_text.check_message(0, toks, 32, 4, 0x400), [])
        self.assertEqual(msgbank.encode(af_text.carried(toks))[:4],
                         bytes([0x7F, msgbank.NAMES["LUCK_8"], 0x7F, msgbank.NAMES["LUCK_7"]]))
        letter = msgbank.parse("Thanks for <CUTARTICLE><STR_FREE0>!")
        self.assertEqual(af_text.check_message(0, letter, 32, 4, 0x400, letter=True), [])
        self.assertEqual([lv for lv, _ in af_text.check_message(0, msgbank.parse("<STR_TAIL>"), 32, 4, 0x400,
                                                                   letter=True)], ["error"])
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


class NafeText(unittest.TestCase):
    """tools/af_nafe_text.py: which of the 2010 patch's messages the en bank takes (made-up messages)."""

    def test_taken_where_the_codes_that_steer_the_game_are_the_cartridges(self):
        p = msgbank.parse
        cart = {0: p("<DEMONPC0 000016>あいう<PAUSE 08>¶えお<STR_TAIL>!<BTN>¶<MSGCLEAR>かき<MSGEND>".replace("¶", "\n")),
                1: p("<DEMONPC0 090001>あい<MSGEND>"),
                2: p("あい<STR_FREE2>う<MSGEND>"),
                3: p("あい<MSGEND>"),
                4: p("あい<MSGEND>"),
                5: p("あい<MSGEND>"),
                6: p("<DEMONPC0 0000ff>あい<MSGEND>"),
                7: p("<MSGEND>")}
        ours = dict(cart)
        ours[5] = p("Ours.<MSGEND>")                         # translated already: not a candidate
        theirs = {0: p("<DEMONPC0 000016>Hello,<PAUSE 06> <STR_PLAYERNAME>!<BTN>\n<MSGCLEAR>Bye.<MSGEND>"),
                  1: p("<DEMONPC0 090002>Hi.<MSGEND>"),       # another order in slot 9: the shop acts
                  2: p("Hi <STR_FREE2> and <STR_FREE3>.<MSGEND>"),   # a free string the game does not fill
                  3: p("Hi.<MSGCONTINUE>"),                  # another end: the conversation goes on
                  4: p("あいう<MSGEND>"),                    # still Japanese
                  5: p("Theirs.<MSGEND>"),
                  6: p("<DEMONPC0 000016>Hi.<MSGEND>"),      # another talk order in slot 0: the cartridge's put back
                  7: p("Hm.<MSGEND>")}
        taken, why = af_nafe_text.take(ours, cart, theirs)
        # pauses, pages and the names the game has at hand may differ; the talk orders in slot 0 are the
        # cartridge's, put back; in other slots they must be
        self.assertEqual(sorted(taken), [0, 6])
        self.assertEqual(msgbank.render(taken[6]), "<DEMONPC0 0000ff>Hi.<MSGEND>")
        self.assertEqual(why, collections.Counter({"taken": 1, "taken, the cartridge's talk orders put back": 1,
                                                   "codes that steer the game differ": 2,
                                                   "prints a string the game does not fill there": 1,
                                                   "not in English in the 2010 patch": 1}))
        # a fifth line goes on a page of its own, as the draft splits the bank's
        five = {0: p("あ\nい\nう\nえ\nお<MSGEND>")}
        taken, _ = af_nafe_text.take(five, five, {0: p("a\nb\nc\nd\ne<MSGEND>")})
        self.assertEqual(msgbank.render(taken[0]), "a\nb\nc\nd<BTN>\n<MSGCLEAR>e<MSGEND>")
        # written as the bank's dump, a note naming its source, and read back
        back = msgbank.Bank.parse_dump(af_nafe_text.dump(taken))
        self.assertEqual(back, taken)
        self.assertIn("#: the AF Project's 2010 translation", af_nafe_text.dump(taken))
        with self.assertRaises(ValueError):
            af_nafe_text.messages(b"not the patch's ROM")

    def test_the_cartridges_talk_orders_put_back(self):
        p = msgbank.parse

        def back(cart, theirs):
            return msgbank.render(af_nafe_text.recover(p(cart), p(theirs)))

        # an order the patch added goes; the cartridge's takes the patch's order's place
        self.assertEqual(back("<DEMONPC0 0000ff>あいう<MSGEND>", "<DEMONPC0 000017>Hi.<DEMONPC0 000016> Bye.<MSGEND>"),
                         "<DEMONPC0 0000ff>Hi. Bye.<MSGEND>")
        # one the patch dropped comes back where the cartridge's was: a page's start
        self.assertEqual(back("<DEMONPC0 0000ff>あい<BTN>\n<MSGCLEAR><DEMONPC0 000016>うえ<MSGEND>",
                              "<DEMONPC0 0000ff>Hi.<BTN>\n<MSGCLEAR>Bye.<MSGEND>"),
                         "<DEMONPC0 0000ff>Hi.<BTN>\n<MSGCLEAR><DEMONPC0 000016>Bye.<MSGEND>")
        # each stays between the codes that steer the game it was between; the patch's order across one goes
        self.assertEqual(back("<DEMONPC0 000016>あ<SNDTRGSYS 04><DEMONPC0 0000ff>い<MSGEND>",
                              "Hi<DEMONPC0 000016><SNDTRGSYS 04>yo<MSGEND>"),
                         "<DEMONPC0 000016>Hi<SNDTRGSYS 04><DEMONPC0 0000ff>yo<MSGEND>")
        # codes that steer the game otherwise: nothing to put back
        self.assertIsNone(af_nafe_text.recover(p("あ<MSGEND>"), p("Hi<MSGCONTINUE>")))
        self.assertIsNone(af_nafe_text.recover(p("<DEMONPC0 090001>あ<MSGEND>"), p("<DEMONPC0 090002>Hi<MSGEND>")))

    def test_compile_lays_the_overrides_over_the_bank(self):
        with tempfile.TemporaryDirectory() as d:
            bank, over = os.path.join(d, "bank.txt"), os.path.join(d, "over.txt")
            text, index = os.path.join(d, "t.bin"), os.path.join(d, "i.bin")
            open(bank, "w").write("".join("## %d\n<MSGEND>\n\n" % n for n in range(af_text.N64_COUNT)))
            open(over, "w").write("## 3\n#: a note\nLaid over.<MSGEND>\n\n")
            with open(os.devnull, "w") as quiet:
                old = sys.stdout
                sys.stdout = quiet
                try:
                    self.assertEqual(af_text.main(["compile", bank, text, index, "--overrides", over]), 0)
                finally:
                    sys.stdout = old
            t = open(text, "rb").read()
            ends = struct.unpack(">%dI" % af_text.N64_COUNT, open(index, "rb").read())
            self.assertEqual(t[ends[2]:ends[3]], msgbank.encode(msgbank.parse("Laid over.<MSGEND>")))
            self.assertEqual(t[ends[3]:ends[4]], msgbank.encode(msgbank.parse("<MSGEND>")))

    def test_compile_refuses_an_override_that_steers_the_game_otherwise(self):
        count = msgbank.N64_BANKS["message"][2]
        with tempfile.TemporaryDirectory() as d:
            bank, ref, over = (os.path.join(d, f) for f in ("bank.txt", "ref.txt", "over.txt"))
            text, index = os.path.join(d, "t.bin"), os.path.join(d, "i.bin")
            open(bank, "w").write("".join("## %d\n<MSGEND>\n\n" % n for n in range(count)))
            cart = "<DEMONPC0 000016>あ<PAUSE 08>い<STR_TAIL><BTN>\n<MSGCLEAR>う<MSGEND>"
            open(ref, "w", encoding="utf-8").write("".join("## %d\n%s\n\n" % (n, cart if n == 3 else "<MSGEND>")
                                                           for n in range(count)))

            def compile_with(message):
                open(over, "w").write("## 3\n%s\n\n" % message)
                with open(os.devnull, "w") as quiet:
                    old = sys.stdout
                    sys.stdout = quiet
                    try:
                        return af_text.main(["compile", bank, text, index, "--reference", ref, "--overrides", over])
                    finally:
                        sys.stdout = old

            # pages, pauses, colours, the strings the game always has and the en build's capital are the English's
            self.assertEqual(compile_with("<DEMONPC0 000016>Hey,<BTN>\n<MSGCLEAR><CAPTIALIZE><STR_TAIL> "
                                          "<STR_PLAYERNAME>!<BTN>\n<MSGCLEAR><COLORCHARS 4ba00003>Bye.<MSGEND>"), 0)
            # the talk orders, the end and the strings filled for one message are the cartridge's
            self.assertEqual(compile_with("<DEMONPC0 000017>Hey.<MSGEND>"), 1)
            self.assertEqual(compile_with("Hey.<MSGEND>"), 1)
            self.assertEqual(compile_with("<DEMONPC0 000016>Hey.<MSGCONTINUE>"), 1)
            self.assertEqual(compile_with("<DEMONPC0 000016>Hey, <STR_FREE1>.<MSGEND>"), 1)
            # the bank's own messages are not held to it: only those laid over
            self.assertEqual(af_text.laid_problems(msgbank.parse("Hey.<MSGEND>"), msgbank.parse(cart)),
                             [("error", "its codes that steer the game are not the cartridge's: <MSGEND>, the "
                               "cartridge's <DEMONPC0 000016><MSGEND>")])


class OwnMessages(unittest.TestCase):
    """script/message/*.txt: our own English for messages the GameCube's script has no counterpart for."""

    def test_ours_are_english_messages_the_window_takes(self):
        folder = os.path.join(HERE, "..", "script", "message")
        paths = sorted(os.path.join(folder, f) for f in os.listdir(folder) if f.endswith(".txt"))
        self.assertTrue(paths)
        count, seen = msgbank.N64_BANKS["message"][2], set()
        for path in paths:
            ours = af_text.load_bank(path)
            self.assertTrue(ours, path)
            self.assertFalse(seen & set(ours), "a message in two files")
            seen |= set(ours)
            for n, tokens in ours.items():
                self.assertLess(n, count)
                self.assertTrue(af_nafe_text.english(tokens), n)
                # ends, codes the N64 has (the en build's carried ones as theirs), at most four lines a page
                errors = [t for level, t in af_text.check_message(n, tokens, 32, 4, 0x600) if level == "error"]
                self.assertEqual(errors, [], n)


class OwnStringsAndLetters(unittest.TestCase):
    """script/string/*.txt and script/<letter bank>/*.txt: our own English for the strings and letters."""

    def own(self, folder):
        paths = sorted(os.path.join(folder, f) for f in os.listdir(folder) if f.endswith(".txt"))
        self.assertTrue(paths, folder)
        merged = {}
        for path in paths:
            ours = af_text.load_bank(path)
            self.assertTrue(ours, path)
            self.assertFalse(set(merged) & set(ours), "an entry in two files: %s" % path)
            merged.update(ours)
        return merged

    def test_strings_are_single_lines_the_string_loader_takes(self):
        ours = self.own(os.path.join(HERE, "..", "script", "string"))
        for n, tokens in ours.items():
            self.assertLess(n, 0x679)
            text = msgbank.render(tokens)
            self.assertNotIn("<", text, n)                               # no codes in a string
            self.assertLessEqual(len(msgbank.encode(tokens)), 64, n)    # mString_Get_StringDataAddressAndSize: < 65
            self.assertFalse(any("぀" <= c <= "ヿ" for c in text), n)
        self.assertEqual(ours[484], [])                                 # the town's name with nothing after it
        # the staff credits: the orange box loads and draws 15 bytes a line (ovl_Mikanbox)
        credits = af_text.load_bank(os.path.join(HERE, "..", "script", "string", "credits.txt"))
        self.assertEqual(sorted(credits), list(range(1258, 1365)))
        self.assertTrue(all(len(msgbank.encode(t)) <= 15 for t in credits.values()))

    def test_letters_fit_their_banks(self):
        for bank in af_text.LETTER_BANKS:
            folder = os.path.join(HERE, "..", "script", bank)
            if not os.path.isdir(folder):
                continue
            for n, tokens in self.own(folder).items():
                self.assertLess(n, af_text.letter_count(bank))
                self.assertEqual(af_text.check_message(n, tokens, 32, 4, af_text.LETTER_CAPS[bank], letter=True), [],
                                 (bank, n))
                lines = msgbank.render(tokens).rstrip("\n").split("\n")
                self.assertLessEqual(len(lines), 6, (bank, n))            # the board's six lines
                self.assertTrue(af_nafe_text.english(tokens), (bank, n))


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


class Font(unittest.TestCase):
    """tools/af_font.py on a made-up font file of the cartridge's shape."""

    @staticmethod
    def put(seg, c, x, y, v):
        at = af_font.SHEET + ((c // 16 * 16 + y) * 192 + c % 16 * 12 + x) // 2
        seg[at] = (seg[at] & 0x0F) | (v << 4) if x % 2 == 0 else (seg[at] & 0xF0) | v

    def test_latin_letters_move_left_and_get_their_width(self):
        seg = bytearray(random.Random(5).randbytes(af_font.SIZE))
        for c in range(256):                                       # empty cells
            for y in range(16):
                for x in range(12):
                    self.put(seg, c, x, y, 0)
        A, kana, dot = msgbank.N64_BYTES["A"], msgbank.N64_BYTES["あ"], msgbank.N64_BYTES["."]
        for y in range(2, 14):
            for x in range(3, 9):
                self.put(seg, A, x, y, 15)                         # A inked in columns 3..8
            for x in range(1, 11):
                self.put(seg, kana, x, y, 9)
        self.put(seg, dot, 4, 12, 15)
        out, cut = af_font.align(bytes(seg))
        self.assertEqual(len(out), af_font.SIZE)
        self.assertEqual(out[:af_font.SHEET], seg[:af_font.SHEET])  # nothing outside the sheet
        self.assertEqual(out[af_font.SHEET + 0x6000:], seg[af_font.SHEET + 0x6000:])
        self.assertEqual(af_font.inked(out, A), list(range(0, 6)))
        self.assertEqual(cut[A], 12 - 7)                           # six columns and one empty
        self.assertEqual(af_font.inked(out, dot), [0])
        self.assertEqual(cut[dot], 10)
        self.assertEqual(cut[msgbank.N64_BYTES[" "]], 8)           # the space: 4 pixels
        self.assertEqual(af_font.inked(out, kana), list(range(1, 11)))  # kana stay, full width
        self.assertEqual(cut[kana], 0)
        self.assertEqual(af_font.widths(cut)[A], 7)
        self.assertEqual(af_font.align(bytes(seg), gap=2, space=5)[1][A], 12 - 8)
        inc = af_font.initializer(cut)
        self.assertEqual([int(n[:-1]) for n in inc.split("\n", 1)[1].split()], cut)
        with self.assertRaises(ValueError):
            af_font.align(bytes(seg[:-16]))

    @staticmethod
    def yaz0_literal(data):
        """A Yaz0 file of literals only (code bytes 0xFF)."""
        out = bytearray(b"Yaz0" + struct.pack(">I", len(data)) + bytes(8))
        for i in range(0, len(data), 8):
            out += b"\xff" + data[i:i + 8]
        return bytes(out)

    def test_yaz0_literals_and_back_references(self):
        # "ab", then 6 bytes from 2 back (nibble 4), then 0x12 + 1 bytes from 1 back (nibble 0, third byte)
        src = b"Yaz0" + struct.pack(">I", 2 + 6 + 0x13 + 1) + bytes(8) + bytes([0b11001000]) + b"ab" + \
            bytes([0x40, 0x01]) + bytes([0x00, 0x00, 0x01]) + b"z"
        self.assertEqual(gciso.yaz0(src), b"abababab" + b"b" * 0x13 + b"z")
        self.assertEqual(gciso.yaz0(src, 5), b"ababa")
        self.assertEqual(gciso.yaz0(self.yaz0_literal(b"hello, world")), b"hello, world")
        with self.assertRaises(ValueError):
            gciso.yaz0(b"Yay0" + bytes(12))

    def gc_disc_files(self, cut_obj="m_font.o", data_extra=0):
        """(foresta.rel.szs, foresta.map) made up: the sheet tiled, a cut table, as the disc has them."""
        rnd = random.Random(7)
        sheet = rnd.randbytes(af_font.SHEET_SIZE)              # the sheet as the N64 lays it out
        tiled = bytearray(af_font.SHEET_SIZE)
        for t in range(af_font.SHEET_SIZE // 32):
            tx, ty = t % 24, t // 24
            for y in range(8):
                at = (ty * 8 + y) * 96 + tx * 4
                tiled[t * 32 + y * 4:t * 32 + y * 4 + 4] = sheet[at:at + 4]
        cuts = bytes(rnd.randrange(0, 9) for _ in range(256))
        data = bytearray(0x40) + cuts + bytearray(0xC0) + tiled  # cut table at 0x40, sheet at 0x200
        sections = [(0, 0), (0x101, 0x100), (0, 0), (0, 0), (0, 0), (0x200, len(data)), (0, 0x80)]
        rel = bytearray(0x200)
        struct.pack_into(">II", rel, 0xC, len(sections), 0x40)
        for i, (o, s) in enumerate(sections):
            struct.pack_into(">II", rel, 0x40 + 8 * i, o, s)
        rel = bytes(rel) + bytes(data)
        lines = [".text section layout", "  Starting        Virtual", "  address  Size   address", "  -----------------------",
                 "  00000000 000100 00000000  1 .text \tdummy.o ", "",
                 ".data section layout", "  Starting        Virtual", "  address  Size   address", "  -----------------------",
                 "  00000000 000040 00000000  4 offset_data$4207 \tm_collision_bg.o ",
                 "  00000040 000100 00000040  4 offset_data$396 \t%s " % cut_obj,
                 "  00000200 006000 00000200 32 FONT_nes_tex_font1 \tdataobject.obj ",
                 "  00006200 %06x 00006200  4 tail \tdummy.o " % (len(data) - 0x6200 + data_extra), "",
                 ".bss section layout", ""]
        return self.yaz0_literal(rel), "\r\n".join(lines), sheet, list(cuts)

    def test_gc_font_found_by_name_in_the_map_and_untiled(self):
        szs, map_text, sheet, cuts = self.gc_disc_files()
        got_sheet, got_cuts = af_font.gc_font(szs, map_text)
        self.assertEqual(got_sheet, sheet)
        self.assertEqual(got_cuts, cuts)
        with self.assertRaises(SystemExit):                        # the cut table is m_font.o's only
            af_font.gc_font(*self.gc_disc_files(cut_obj="m_other.o")[:2])
        with self.assertRaises(SystemExit):                        # the map's .data is not the module's
            af_font.gc_font(*self.gc_disc_files(data_extra=4)[:2])

    def test_gc_latin_characters_and_marks_replace_the_cartridges(self):
        seg = random.Random(5).randbytes(af_font.SIZE)
        gc_sheet = random.Random(6).randbytes(af_font.SHEET_SIZE)
        gc_cut = bytes((c * 7) % 9 for c in range(256))
        out, cut = af_font.gc_align(seg, gc_sheet + gc_cut)
        self.assertEqual(len(out), af_font.SIZE)
        self.assertEqual(out[:af_font.SHEET], seg[:af_font.SHEET])
        self.assertEqual(out[af_font.SHEET + 0x6000:], seg[af_font.SHEET + 0x6000:])
        g = {ch: i for i, ch in reversed(list(enumerate(msgbank.GC_CHARS)))}
        for ch, gc_ch in (("A", "A"), ("z", "z"), (" ", " "), ("ー", "ー"), ("～", "~"), ("☺", "😃")):
            c = msgbank.N64_BYTES[ch]
            for y in range(16):
                self.assertEqual(af_font._row(out, c, y)[0], af_font._row(b"\0" * af_font.SHEET + gc_sheet, g[gc_ch], y)[0])
            self.assertEqual(cut[c], gc_cut[g[gc_ch]])
        kana = msgbank.N64_BYTES["あ"]
        self.assertEqual(cut[kana], 0)
        self.assertEqual([af_font._row(out, kana, y)[0] for y in range(16)], [af_font._row(seg, kana, y)[0] for y in range(16)])
        with self.assertRaises(ValueError):
            af_font.gc_align(seg, gc_sheet)


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


class MenusWords(unittest.TestCase):
    # tools/af_ui.py and tools/af_dumpdata.py, on made-up data
    def test_strings_text_round_trips(self):
        for s in ("Pick a word.", "a\\b\tc\nd", ""):
            self.assertEqual(af_ui.unescape(af_ui.escape(s)), s)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "ui.txt")
            open(path, "w").write("# comment\nONE\tHello!\nTWO\tA\\tB\n")
            self.assertEqual(af_ui.read_text(path), {"ONE": "Hello!", "TWO": "A\tB"})

    def test_strings_are_the_n64_fonts_codes(self):
        self.assertEqual(af_ui.n64_bytes("X", "Ok."), bytes([0x4F, 0x6B, 0x2E]))
        with self.assertRaises(SystemExit):
            af_ui.n64_bytes("X", "\u00e9")                  # no such character in the N64's font

    def test_strings_include(self):
        text = af_ui.include([("ONE", b"\x41\x42"), ("TWO", b"")])
        self.assertIn("#define UI_EN_ONE { 0x41, 0x42 }\n#define UI_EN_ONE_LEN 2\n", text)
        self.assertIn("#define UI_EN_TWO_LEN 0\n", text)

    def test_strings_of_the_map(self):
        # a symbol is found in the map's .data once, or the read stops
        layout = {".data": [(0x10, 4, "a_str", "m_a.o"), (0x20, 7, "b_str", "m_b.o"), (0x30, 7, "b_str", "m_c.o")]}
        self.assertEqual(af_font.gc_find(layout, "a_str"), (0x10, 4))
        self.assertEqual(af_font.gc_find(layout, "b_str", "m_c.o"), (0x30, 7))
        with self.assertRaises(SystemExit):
            af_font.gc_find(layout, "b_str")

    def test_dump_data_initializers(self):
        rom = bytes(range(16))
        text = af_dumpdata.initializers(rom, [("D_A", 2, 3), ("D_B", 8, 2)])
        self.assertIn("u8 D_A[3] = { 0x02, 0x03, 0x04 };\nu8 D_B[2] = { 0x08, 0x09 };\n", text)

    def test_swaps(self):
        # tools/af_ui.py SUBST: the key is the cartridge's bytes from the dump, our text goes over the
        # GameCube's, a template is padded to its cells with spaces, and a sentinel ends the table
        saved = af_ui.SUBST
        try:
            af_ui.SUBST = [("A", (0, 3), af_ui.CENTER, "sym_a", "a.o"), ("B", (4, 4), af_ui.CELLS, None, None),
                           ("C", (8, 2), af_ui.LEFT | af_ui.BOX(6), "sym_c", "c.o"), ("D", (10, 2), af_ui.LEFT, None, None)]
            rom = bytes([0x90, 0x91, 0x92, 0, 0xA0, 0xA1, 0xA2, 0xA3, 0xB0, 0xB1, 0xC0, 0xC1])
            subs = af_ui.substitutions(rom, {"A": "Hi", "C": "No"}, {"B": "1:", "C": "Ok"})
            self.assertEqual(subs, [("A", rom[0:3], b"Hi", af_ui.CENTER, None, None),
                                    ("B", rom[4:8], b"1:  ", af_ui.CELLS, None, None),
                                    ("C", rom[8:10], b"Ok", af_ui.LEFT | af_ui.BOX(6), None, None)])  # D: no English
            text = af_ui.subst_include(subs)
            self.assertIn("#define UI_EN_SUBST_COUNT 3\n", text)
            self.assertIn("    { 3, 2, 1, 0, 0, 255, 255, { 0x90, 0x91, 0x92 }, { 0x48, 0x69 } }, /* A */ \\\n", text)
            self.assertIn("    { 2, 2, 0, 6, 0, 255, 255, { 0xB0, 0xB1 }, { 0x4F, 0x6B } }, /* C */ \\\n", text)
            self.assertTrue(text.endswith("    { 0, 0, 0, 0, 0, 0, 0, { 0 }, { 0 } }\n"))
            with self.assertRaises(SystemExit):
                af_ui.substitutions(rom, {}, {"B": "12:00"})                       # more cells than the key's
            self.assertIn(("C", "sym_c", "c.o"), af_ui.gc_sources())
            self.assertNotIn(("B", None, None), af_ui.gc_sources())
        finally:
            af_ui.SUBST = saved

    def test_gc_words_sized(self):
        # a GameCube symbol given with a length (a tag word: 16 characters, then its procedures) is read that
        # far, its trailing spaces dropped; one without is read whole
        saved = (af_ui.SUBST, af_ui.STRINGS, af_ui.map_layout, af_ui.gc_rel_data)
        try:
            af_ui.STRINGS = []
            af_ui.SUBST = [("W", (0, 8), af_ui.LEFT, ("word_sym", 16), "t.o"),
                           ("S", (0, 4), af_ui.LEFT, "str_sym", "t.o")]
            layout = {".data": [(0x00, 20, "word_sym", "t.o"), (0x14, 6, "str_sym", "t.o")]}
            data = b"Grab" + b" " * 12 + b"\x80\x87\x00\x10" + b"Yes it"
            af_ui.map_layout = lambda text: layout
            af_ui.gc_rel_data = lambda szs, lay, upto: data[:upto]
            self.assertEqual(af_ui.gc_strings(b"", ""), {"W": "Grab", "S": "Yes it"})
        finally:
            af_ui.SUBST, af_ui.STRINGS, af_ui.map_layout, af_ui.gc_rel_data = saved

    def test_swap_keys_in_pieces(self):
        # a key in pieces with a hole (a string the menu builds round its own part, typed into a buffer):
        # the key's bytes without the hole, where it is in them, and where the English's {} is in it
        saved = af_ui.SUBST
        try:
            layout = af_ui.TYPED(12) | af_ui.LEFT | af_ui.BOX(16)
            af_ui.SUBST = [("P", [(0, 2), af_ui.HOLE, (4, 2)], layout, None, None),
                           ("Q", [(0, 2), (6, 1)], layout, None, None)]
            rom = bytes([0x90, 0x91, 0, 0, 0xA0, 0xA1, 0xB0])
            subs = af_ui.substitutions(rom, {}, {"P": "It's {} Bells", "Q": "Free"})
            self.assertEqual(subs, [("P", bytes([0x90, 0x91, 0xA0, 0xA1]), b"It's  Bells", layout, 2, 5),
                                    ("Q", bytes([0x90, 0x91, 0xB0]), b"Free", layout, None, None)])
            self.assertIn("    { 4, 11, 0, 16, 12, 2, 5, ", af_ui.subst_include(subs))
            for own in ({"P": "No hole"}, {"Q": "A {} hole"}, {"P": "{} two {}"}):
                with self.assertRaises(SystemExit):
                    af_ui.substitutions(rom, {}, own)                          # holes in the key and the English
            af_ui.SUBST = [("R", (0, 7), af_ui.TYPED(6), None, None)]
            with self.assertRaises(SystemExit):
                af_ui.substitutions(rom, {}, {"R": "Long"})                     # more than its buffer holds
        finally:
            af_ui.SUBST = saved

    def test_swap_templates_of_digits(self):
        # cells a menu writes its digits into (a date) are '0' in the key: such a template needs no English
        # (the key itself: the menu's digits in the cartridge's cells), and our English may show the digits
        # only in those cells; the layout from the first character is a whole string's
        saved = af_ui.SUBST
        zero, w = af_ui.N64_BYTES["0"], af_ui.WRITTEN
        try:
            date = [w(4), (0, 1), w(2), (0, 1), w(2)]
            af_ui.SUBST = [("D", date, af_ui.CELLS | af_ui.DIGITS, None, None)]
            rom = b"  "
            key = bytes([zero] * 4) + b" " + bytes([zero] * 2) + b" " + bytes([zero] * 2)
            self.assertEqual(af_ui.substitutions(rom, {}, {}),
                             [("D", key, key, af_ui.CELLS | af_ui.DIGITS, None, None)])
            self.assertEqual(af_ui.substitutions(rom, {}, {"D": "0000-00-00"})[0][2],
                             key[:4] + b"-" + key[5:7] + b"-" + key[8:])
            with self.assertRaises(SystemExit):
                af_ui.substitutions(rom, {}, {"D": "00000 00 0"})               # a digit off its cells
            for layout in (af_ui.CELLS, af_ui.DIGITS):                           # written cells: a template
                af_ui.SUBST = [("D", date, layout, None, None)]
                with self.assertRaises(SystemExit):
                    af_ui.substitutions(rom, {}, {})
            af_ui.SUBST = [("B", (0, 2), af_ui.LEFT | af_ui.FIRST, None, None)]
            self.assertEqual(af_ui.substitutions(rom, {}, {"B": "Bells"})[0][3], af_ui.FIRST)
            for spec, layout in (((0, 2), af_ui.CELLS), ([(0, 1), af_ui.HOLE], af_ui.LEFT),
                                 ((0, 2), af_ui.TYPED(4))):
                af_ui.SUBST = [("B", spec, layout | af_ui.FIRST, None, None)]
                with self.assertRaises(SystemExit):
                    af_ui.substitutions(rom, {}, {"B": "x{}" if af_ui.HOLE in af_ui.key_pieces(spec) else "x"})
        finally:
            af_ui.SUBST = saved

    def test_item_names_mapped(self):
        # tools/af_items.py: the cartridge's names numbered by kind, densely; the GameCube's at the same index
        # but for the plants (one added second), the furniture's blocks, the clothes it shows as furniture (the
        # clothes' names), the Famicoms (one name) and a bag of money among the other items
        slots = af_items.slots()
        self.assertEqual(len(slots), sum(n for _, n, _, _, _ in af_items.KINDS))
        bases = af_items.kind_bases()
        self.assertEqual(bases["paper"], 0)
        self.assertEqual(bases["ftr"], sum(n for k, n, _, _, _ in af_items.KINDS if k != "ftr"))
        self.assertEqual(slots[bases["ftr"]], ("ftr", 0))
        src = af_items.gc_source
        self.assertEqual(src("fish", 31), ("fish", 31))
        self.assertEqual((src("plant", 0), src("plant", 1), src("plant", 9)), (("plant", 0), ("plant", 2), ("plant", 10)))
        self.assertEqual((src("ftr", 0), src("ftr", 490)), (("ftr", 0), ("ftr", 490)))
        self.assertEqual((src("ftr", 491), src("ftr", 745)), (("cloth", 0), ("cloth", 254)))
        self.assertEqual((src("ftr", 746), src("ftr", 778), src("ftr", 810)), (("ftr", 754), ("ftr", 794), ("ftr", 834)))
        self.assertEqual((src("ftr", 842), src("ftr", 848), src("ftr", 849)), (("ftr", 893), ("ftr", 893), None))
        self.assertEqual((src("ftr", 850), src("ftr", 946)), (("ftr", 894), ("ftr", 990)))
        self.assertEqual(src("etc", 13), ("money", 0))
        # a placeholder in the GameCube's table is no name; an accented small letter in one is
        self.assertTrue(af_items.printable("bass") and af_items.printable("Se\u00f1or K.K."))
        self.assertFalse(af_items.printable("\u00c1\u00e0\u00c5\u00ed") or af_items.printable(""))

    def test_item_name_records(self):
        # a record: the name's length, its article, its cells (the token's at least, the buffer's at most),
        # a byte, the characters padded to 16; ours over the GameCube's; without either, the cartridge's
        saved = af_items.KINDS
        try:
            af_items.KINDS = [("fish", 3, 0x10, "itemName_fish", "itemArt_Fish"), ("ftr", 1, 0x40, "ftrName_table", "ftrArt")]
            rom = bytearray(af_items.NAME_FILE + 0x80)
            rom[af_items.NAME_FILE + 0x10 + 20:af_items.NAME_FILE + 0x10 + 30] = bytes([0x10, 0x11]) + b" " * 8
            widths = [6] * 256
            gc = {("fish", 0): ("bass", 1), ("fish", 1): ("a very long name", 2), ("ftr", 0): ("sofa", 1)}
            own = {("fish", 1): ("carp", 0)}
            data, counts = af_items.records(bytes(rom), gc, own, widths)
            size = af_items.RECORD_SIZE
            self.assertEqual(len(data), 4 * size)
            self.assertEqual(data[:size], bytes([4, 1, 3, 0]) + b"bass" + b" " * 12)          # 24 px: 2 cells, 3 at least
            self.assertEqual(data[size:2 * size], bytes([4, 0, 3, 0]) + b"carp" + b" " * 12)  # ours first
            self.assertEqual(data[2 * size:2 * size + 6], bytes([2, 0, 3, 0, 0x10, 0x11]))    # the cartridge's
            self.assertEqual(counts, {"fish": [1, 1, 1], "ftr": [1, 0, 0]})
            self.assertEqual(af_items.cells(b"x" * 16, [9] * 256), af_items.NAME_LEN)       # 144 px: the buffer's 10
            with self.assertRaises(SystemExit):
                af_items.records(bytes(rom), {("fish", 0): ("a name of seventeen", 1)}, {}, widths)
            inc = af_items.include(True)
            self.assertIn("#define ITEM_EN_FROM_GC 1", inc)
            self.assertIn("#define ITEM_EN_FTR_BASE 3", inc)
            self.assertIn("#define ITEM_EN_RECORDS 4 ", inc)                      # no names: the items'
            self.assertIn("#define ITEM_EN_SPECIAL_NAMES { { 0x000, 0 }, }", inc)   # the zero that ends it
        finally:
            af_items.KINDS = saved

    def test_long_names_take_tokens(self):
        # the names longer than an animal's 6 bytes: the animals' the segment holds (the GameCube's table,
        # its first four placeholders), then the special characters' from the string bank; their records
        # follow the items', with no article, at most 6 cells; a token fills the 6-byte field
        def gc_name(text):
            return bytes(af_names_gc(c) for c in text.ljust(af_items.af_names.GC_LEN))

        def af_names_gc(c):
            return msgbank.GC_CHARS.index(c)
        table = b"".join(gc_name(t) for t in ("", "", "", "", "Abe", "Zed", "Quillon", "Marblex", "Tumbleby"))
        strings = {0x205: msgbank.parse("Granddad"), 0x206: msgbank.parse("Dove"), 0x300: msgbank.parse("Elsewhere")}
        names = af_items.long_names(table, 4, strings)                # the segment holds 4: the fifth does not fit
        self.assertEqual([w for w, _ in names], [("animal", 2), ("animal", 3), ("string", 0x205)])
        self.assertEqual(names[0][1], af_items.n64_bytes("t", "Quillon"))
        data, tokens = af_items.name_records(names, 1703, [7] * 256)
        size = af_items.RECORD_SIZE
        self.assertEqual(data[:size], bytes([7, 0, 5, 0]) + af_items.n64_bytes("t", "Quillon") + b" " * 9)  # 50 px
        self.assertEqual([n for _, n, _ in tokens], [1703, 1704, 1705])
        self.assertEqual(tokens[0][2], bytes([0x80, 0x81 + 1703 // 64, 0x81 + 1703 % 64, 0x80, 0x80, 0x20]))
        self.assertEqual(af_items.token(5, 6, 6), bytes([0x80, 0x81, 0x86, 0x80, 0x80, 0x80]))
        self.assertEqual(af_items.cells(b"x" * 9, [9] * 256, af_items.ANIMAL_NAME_LEN), 6)     # 82 px: the field's
        with self.assertRaises(ValueError):
            af_items.token(64 * 64, 3, 6)                             # past two digits
        inc = af_items.include(True, tokens)
        self.assertIn("#define ITEM_EN_RECORDS %d " % (len(af_items.slots()) + 3), inc)
        self.assertIn("#define ITEM_EN_SPECIAL_NAMES { { 0x205, 1705 }, { 0x000, 0 }, }", inc)

    def test_long_catchphrases_take_tokens_in_the_string_bank(self):
        # the catchphrases the animals' default data names (each entry's u16 at +2), once each, those longer
        # than 4 bytes; their tokens fill 4 bytes and go into the string bank as its text
        data = b"".join(struct.pack(">HHBx", 0, n, 0) for n in (12, 7, 12, 30))
        strings = {7: msgbank.parse("hmm"), 12: msgbank.parse("plonkar"), 30: msgbank.parse("wibbly zo")}
        phrases = af_items.long_catchphrases(data, strings)
        self.assertEqual([w for w, _ in phrases], [("catchphrase", 12), ("catchphrase", 30)])
        records, tokens = af_items.name_records(phrases, 1800, [6] * 256, af_items.CATCHPHRASE_LEN)
        self.assertEqual(records[:4], bytes([7, 0, 4, 0]))                  # 42 px: 4 cells, the field's
        self.assertEqual(tokens[0][2], bytes([0x80, 0x81 + 1800 // 64, 0x81 + 1800 % 64, 0x80]))
        text = af_items.string_tokens(tokens)
        bank = msgbank.Bank.parse_dump(text)
        self.assertEqual(msgbank.encode(bank[12]), tokens[0][2])            # the bank's compile reads them back
        self.assertEqual(msgbank.encode(bank[30]), tokens[1][2])

    def test_layout_counts_cover_the_tokens_fields(self):
        # the layout's letters for what the game fills in: the speaker's name and the catchphrase at least
        # their tokens' longest (8 and 10 letters since the names and catchphrases are tokens)
        self.assertGreaterEqual(af_text.CODE_LETTERS["STR_TALKNAME"], 8)
        self.assertGreaterEqual(af_text.CODE_LETTERS["STR_TAIL"], 10)

    def test_labels_drawn_by_the_lists(self):
        # tools/af_nafe_gfx.py: a texture is where a load in the segment's own
        # segment names it, in its render tile's format and its tile's size
        def cmd(w0, w1):
            return struct.pack(">II", w0, w1)
        lists = (cmd(0xFD900000, 0x0C000100)            # image: segment 0x0C, 0x100
                 + cmd(0xF3000000, 0x07000000)          # load
                 + cmd(0xF5000000 | (4 << 21) | (0 << 19), 0x00000000)   # render tile 0: I, 4 bits
                 + cmd(0xF2000000, ((32 - 1) << 2 << 12) | ((16 - 1) << 2))  # 32x16
                 + cmd(0xFD700000, 0x0C000200)
                 + cmd(0xF3000000, 0x07000000)
                 + cmd(0xF5000000 | (3 << 21) | (1 << 19), 0x00000000)   # IA, 8 bits
                 + cmd(0xF2000000, ((64 - 1) << 2 << 12) | ((16 - 1) << 2))
                 + cmd(0xFD100000, 0x04000300)          # another segment's: not this file's
                 + cmd(0xF3000000, 0x07000000)
                 + cmd(0xF5100000, 0x00000000)
                 + cmd(0xF2000000, ((8 - 1) << 2 << 12) | ((8 - 1) << 2))
                 + cmd(0xDF000000, 0))
        data = lists + bytes(0x400 - len(lists))
        self.assertEqual(af_nafe_gfx.drawn(data), {0x100: ("I", 4, 32, 16), 0x200: ("IA", 8, 64, 16)})

    def test_labels_table_is_drawable(self):
        # every label is a texture of the formats the table knows, inside its segment's file
        for (vrom, path), labels in af_nafe_gfx.LABELS.items():
            self.assertTrue(path.startswith("assets/jp/"))
            for at, fmt, bits, w, h, what in labels:
                self.assertIn(fmt, af_nafe_gfx.FORMATS)
                self.assertIn(bits, af_nafe_gfx.SIZES)
                self.assertEqual(w * h * bits % 64, 0, what)        # whole 8-byte words

    def test_logo_pieces_use_their_lists_vertices_and_blocks(self):
        # tools/af_nafe_logo.py: a piece's display list uses itself, the vertices it loads and the
        # textures it loads by blocks (lrs + 1 texels of the image's size)
        def cmd(w0, w1):
            return struct.pack(">II", w0, w1)
        dl = (cmd(0xD7000002, 0xFFFFFFFF)
              + cmd(0xFD700000, 0x06000200)             # image at 0x200, 16-bit texels
              + cmd(0xF3000000, 0x0717F400)             # 0x180 texels: 0x300 bytes
              + cmd(0x01004008, 0x06000080)             # 4 vertices at 0x80
              + cmd(0x06000204, 0x00000406)
              + cmd(0xDF000000, 0))
        seg = bytearray(0x500)
        seg[0x100:0x100 + len(dl)] = dl
        used = af_nafe_logo.pieces_used(bytes(seg), [0x100])
        self.assertEqual(used, [(0x80, 0x40), (0x100, len(dl)), (0x200, 0x300)])
        self.assertEqual(af_nafe_logo.span(used, len(seg)), (0x80, 0x500, [(0xC0, 0x100), (0x130, 0x200)]))
        with self.assertRaises(SystemExit):                  # reaching past the segment
            af_nafe_logo.span(used, 0x4FF)
        seg[0x100:0x108] = cmd(0xDE000000, 0x06000000)       # another list: a layout it does not know
        with self.assertRaises(SystemExit):
            af_nafe_logo.pieces_used(bytes(seg), [0x100])

    def test_logo_data_lines_are_rewritten_as_words(self):
        # the taken symbols' lines become .word with the new value, the comment (splat's: the
        # cartridge's word) kept, so that a second run finds the cartridge's line again
        base = af_nafe_logo.OVERLAY_VRAM
        cart = struct.pack(">4I", 0xE7000000, 0x433F0000, 0xC2A60000, 0x06002FF0)
        lines = ["dlabel D_A", "    /* 960000 %08X 433F0000 */ .float 191" % (base + 4),
                 "    /* 960004 %08X C2A60000 */ .float -83" % (base + 8), "enddlabel D_A",
                 "dlabel D_B", "    /* 960008 %08X 06002FF0 */ .word 0x06002FF0" % (base + 12), "enddlabel D_B"]
        data_s = "\n".join(lines)
        self.assertEqual(af_nafe_logo.symbols(data_s), {"D_A": [base + 4, base + 8], "D_B": [base + 12]})
        out = af_nafe_logo.rewrite_data(data_s, cart, {base + 4: 0x43570000, base + 12: 0x06002890})
        self.assertIn("    /* 960000 %08X 433F0000 */ .word 0x43570000" % (base + 4), out)
        self.assertIn("    /* 960008 %08X 06002FF0 */ .word 0x06002890" % (base + 12), out)
        self.assertIn(".float -83", out)                     # not asked for: left as it was
        self.assertEqual(af_nafe_logo.rewrite_data(out, cart, {base + 4: 0x433F0000, base + 12: 0x06002FF0})
                         .count(".word"), 2)
        with self.assertRaises(SystemExit):                  # a line that is not the cartridge's
            af_nafe_logo.rewrite_data(data_s.replace("433F0000 */", "433F0001 */"), cart, {base + 4: 1})
        with self.assertRaises(SystemExit):                  # a word with no line
            af_nafe_logo.rewrite_data(data_s, cart, {base: 1})

    def test_logo_include_names_every_value(self):
        values = {name: "1" for _, _, name, _ in af_nafe_logo.CODE}
        inc = af_nafe_logo.include(values)
        for _, insn, name, kind in af_nafe_logo.CODE:
            self.assertIn("#define %s 1" % name, inc)
            if kind == "dl":                                 # the cartridge's list is one the table knows
                self.assertIn(insn & 0xFFFF, af_nafe_logo.OWN_DLS)
        self.assertTrue(set(af_nafe_logo.PIECES) <= set(af_nafe_logo.TAKEN))


class Matching(unittest.TestCase):
    def test_bench_prototypes(self):
        # af_bench declares what the file defines before the benched
        # function, and not what the build cannot see (NON_MATCHING, static)
        text = ("#include \"global.h\"\n\n"
                "s32 first(u8* a, s32 b) {\n    return b;\n}\n\n"
                "#ifdef NON_MATCHING\nvoid hidden(void) {\n}\n#else\n#pragma GLOBAL_ASM(\"x.s\")\n#endif\n\n"
                "static void local(void) {\n}\n\n"
                "void second(s32* p,\n            s32 q) {\n}\n\n"
                "void target(void) {\n    first(0, 1);\n}\n\n"
                "void after(void) {\n}\n")
        self.assertEqual(af_bench.prototypes(text, "target"),
                         "s32 first(u8* a, s32 b);\nvoid second(s32* p,\n            s32 q);\n")



class GcSigns(unittest.TestCase):
    """tools/af_gc_signs.py on made-up textures and palettes."""

    def test_untile_lays_8x8_tiles_out_as_rows(self):
        w, h = 16, 8                                    # two tiles side by side
        texels = [(x * 3 + y * 5) % 16 for y in range(h) for x in range(w)]
        tiled = bytearray()
        for tx in range(w // 8):
            for r in range(8):
                row = texels[r * w + tx * 8:r * w + tx * 8 + 8]
                tiled += bytes(row[c] << 4 | row[c + 1] for c in range(0, 8, 2))
        linear = af_gc_signs.untile_ci4(bytes(tiled), w, h)
        self.assertEqual(af_gc_signs.texels(linear), texels)
        with self.assertRaises(ValueError):
            af_gc_signs.untile_ci4(bytes(tiled[:-1]), w, h)

    def test_rgb5a3_entries_as_rgba5551(self):
        self.assertEqual(af_gc_signs.rgb5a3_to_rgba5551(0xFFFF), 0xFFFF)          # opaque white
        self.assertEqual(af_gc_signs.rgb5a3_to_rgba5551(0x8000 | 31 << 10), 31 << 11 | 1)   # opaque red
        self.assertEqual(af_gc_signs.rgb5a3_to_rgba5551(0x0F00), 31 << 11)        # translucent red: transparent
        self.assertTrue(af_gc_signs.same_colour(0x7E30, af_gc_signs.rgb5a3_to_rgba5551(0x07CC)))
        self.assertFalse(af_gc_signs.same_colour(0xA5F5, 0xE041))

    def test_palette_words_are_rewritten_in_place(self):
        data = "nonmatching pal\n\ndlabel pal\n" + "".join(
            "    /* %X */ .word 0x%08X\n" % (k, 0x11112222 * (k + 1) & 0xFFFFFFFF) for k in range(8)) + \
            "enddlabel pal\n"
        held, _ = af_gc_signs.palette_words(data, "pal")
        self.assertEqual(held[:2], [0x1111, 0x2222])
        new = list(range(16))
        out = af_gc_signs.with_palette(data, "pal", new)
        self.assertEqual(af_gc_signs.palette_words(out, "pal")[0], new)
        self.assertIn("/* 0 */ .word 0x00000001", out)

    def test_indexed_png_round_trip(self):
        work = tempfile.mkdtemp()
        try:
            w, h = 8, 4
            path = os.path.join(work, "t.ci4.png")

            def chunk(kind, body):
                return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
            raw = b"".join(b"\0" + bytes(range(y, y + w)) for y in range(h))
            open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 3, 0, 0, 0))
                                   + chunk(b"PLTE", bytes(48)) + chunk(b"IDAT", zlib.compress(raw))
                                   + chunk(b"IEND", b""))
            self.assertEqual(af_gc_signs.texels(af_gc_signs.read_ci4_png(path)),
                             [y + x for y in range(h) for x in range(w)])
            new = bytes((k * 7) & 255 for k in range(w * h // 2))
            af_gc_signs.write_ci4_png(path, new, w, h)
            self.assertEqual(af_gc_signs.read_ci4_png(path), new)
            self.assertEqual(af_gc_signs.png_indices(path)[2], 8)       # its depth kept
        finally:
            shutil.rmtree(work)



class MenuTextures(unittest.TestCase):
    """tools/af_menu_tex.py on a made-up font and display list."""

    @staticmethod
    def font_with(ch, columns, rows):
        """A font segment (af_font's sheet layout) whose glyph for ch is inked at these columns and rows."""
        font = bytearray(af_font.SIZE)
        code = msgbank.N64_BYTES[ch]
        for y in rows:
            _, at = af_font._row(font, code, y)
            for c in columns:
                font[at + c // 2] |= 15 << (4 if c % 2 == 0 else 0)
        return bytes(font)

    def test_a_glyph_is_put_by_its_first_inked_column(self):
        font = self.font_with("-", range(1, 8), (7, 8))
        tex = af_menu_tex.lettered(font, [("-", 10)], 32, 16)
        texels = af_gc_signs.texels(tex)
        inked = sorted({i % 32 for i, v in enumerate(texels) if v})
        self.assertEqual(inked, list(range(10, 17)))
        self.assertEqual(sorted({i // 32 for i, v in enumerate(texels) if v}), [7, 8])
        with self.assertRaises(ValueError):
            af_menu_tex.lettered(font, [("-", 30)], 32, 16)

    def test_a_word_takes_the_palette_s_inks(self):
        font = self.font_with("-", range(0, 4), (5,))
        widths = [6] * 256
        tex = af_menu_tex.lettered_ci4(font, widths, "--", 2, 32, 16, 0, (3, 13, 11, 0))
        idx = af_gc_signs.texels(tex)
        self.assertEqual([x for x in range(32) if idx[5 * 32 + x] == 3], [2, 3, 4, 5, 8, 9, 10, 11])
        self.assertEqual(set(idx) - {3}, {0})

    def test_the_lists_name_their_textures(self):
        words = [0xFD100000, 0x06000100,                  # the image: segment 6's 0x100
                 0xF5100000, 0x07000000,                  # a load tile
                 0xF3000000, 0x0703F800,                  # the load
                 0xF5800000 | (4 << 21), 0x00000000,      # the render tile: I4
                 0xF2000000, 0x0007C03C]                  # its size: 32x16
        dl = b"".join(struct.pack(">I", w) for w in words) + bytes(8)
        self.assertEqual(af_menu_tex.drawn_i4(dl), {0x100: (32, 16)})


class DialLabel(unittest.TestCase):
    """The en build's label for the dial's small letters (reference/NOTES.md, "The dial's small letters"): our
    art and the list that draws it, in decomp/changes.patch."""

    INC = "src/overlays/submenu/ovl__0078CB80/ovl__0078CB80_en_label.inc"
    LEGEND = " .:-=+*#%@ABCDEF"

    def setUp(self):
        self.text = changes_patch_file(self.INC)
        if self.text is None:
            self.skipTest("needs %s in decomp/changes.patch" % self.INC)

    def test_the_art_is_its_bytes(self):
        rows = [line for line in self.text.splitlines() if line.startswith(".byte")]
        self.assertEqual(len(rows), 16)
        texels = []
        for row in rows:
            code, art = row.split("/*")
            data = [int(b, 16) for b in re.findall(r"0x([0-9A-F]{2})", code)]
            art = art.split("*/")[0].strip().replace("_", " ")
            self.assertEqual((len(data), len(art)), (24, 48))
            self.assertEqual([v for b in data for v in (b >> 4, b & 15)], [self.LEGEND.index(c) for c in art])
            texels.append([self.LEGEND.index(c) for c in art])
        inked_above = sorted({x for y in range(6) for x in range(48) if texels[y][x] >= 8})
        self.assertEqual(inked_above, [12, 13, 14])                 # above the small letters, the b's stem alone

    def test_the_list_draws_the_texture_where_the_letters_label_is(self):
        head = self.text[self.text.index("glabel mED_en_lowerLabelDL"):self.text.index("glabel mED_en_lowerLabelTex")]
        words = [w.strip() for line in head.splitlines() if line.startswith(".word")
                 for w in line.split("/*")[0][len(".word"):].split(",")]
        self.assertEqual(len(words), 24)                            # 0x60 bytes: the texture 8-byte aligned after it
        self.assertEqual(words[2:4], ["0xFD900000", "mED_en_lowerLabelTex"])    # I, 16-bit loads
        self.assertEqual(words[8:10], ["0xF3000000", "0x070BF2AB"])  # 192 words: 48x16 at 4 bits
        self.assertEqual(words[12:14], ["0xF5800600", "0x00FD0360"])  # the render tile: I4, 24 bytes a row
        self.assertEqual(words[14:16], ["0xF2000000", "0x000BC03C"])  # 48x16
        self.assertEqual(words[16:18], ["0x01004008", "0x0C000A50"])  # the letters' label's four vertices
        self.assertEqual(words[20], "0xDF000000")

    def test_the_editor_finds_it_from_its_state(self):
        with open(os.path.join(HERE, "..", "decomp", "changes.patch"), encoding="utf-8") as f:
            patch = f.read()
        self.assertIn("+mED_en_stateToLabels = ABSOLUTE(B_80888AF0_jp) - ABSOLUTE(D_808888CC_jp);", patch)
        self.assertIn("+mED_en_stateToLowerLabel = ABSOLUTE(B_80888AF0_jp) - ABSOLUTE(mED_en_lowerLabelDL);", patch)


if __name__ == "__main__":
    unittest.main()
