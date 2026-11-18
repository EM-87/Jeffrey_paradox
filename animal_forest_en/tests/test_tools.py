"""The tools' own tests: nothing here needs the cartridge (make test)."""

import collections
import contextlib
import hashlib
import io
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
import af_answers  # noqa: E402
import af_dial  # noqa: E402
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
import af_gc_nes  # noqa: E402
import af_gc_signs  # noqa: E402
import af_menu_tex  # noqa: E402
import af_own_ftr  # noqa: E402
import af_own_signs  # noqa: E402
import gciso  # noqa: E402
import msgbank  # noqa: E402
import rom  # noqa: E402
import ups  # noqa: E402
from contact import read_png  # noqa: E402
from n64emu import stick, to_physical, write_png  # noqa: E402
sys.path.insert(0, HERE)
import emu_job  # noqa: E402
import emu_letters  # noqa: E402
import emu_trip  # noqa: E402
import emu_memory  # noqa: E402
import emu_reset  # noqa: E402
import emu_town  # noqa: E402
import emu_year  # noqa: E402


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

    def test_relink_packs_grown_text_into_the_slots_others_left(self):
        # the text sections that left their slots: as many bytes of them as can be go into the slots the
        # others left (slots that touch are one run), each whole inside one %hi window; a kept slot stays empty
        t = ".text"
        grown = [(t, "a.o", 0x300, 0x200), (t, "b.o", 0x280, 0x180), (t, "c.o", 0x180, 0x100), (t, "k.o", 0x500, 0x400),
                 (".data", "a.o", 0x40, 0x20)]
        ref_at = {(t, "a.o"): 0x80060000, (t, "b.o"): 0x80060200, (t, "c.o"): 0x80070000, (t, "k.o"): 0x80071000,
                  (".data", "a.o"): 0x80100000}
        runs, placed = af_relink.pack_holes(grown, ref_at, ["k.o"])
        self.assertEqual(runs, [(0x80060000, 0x80060380, ["a.o", "b.o"]), (0x80070000, 0x80070100, ["c.o"]),
                                (0x80071000, 0x80071400, ["k.o"])])
        self.assertEqual(placed, {"a.o": 0x80060000})                    # the most that fits: 0x300 of 0x380
        runs, placed = af_relink.pack_holes(grown, ref_at)               # with k.o's slot: b.o and c.o fill it
        self.assertEqual(placed, {"a.o": 0x80060000, "b.o": 0x80071000, "c.o": 0x80071280})
        # every choice is tried: the first fit of the largest would leave one of these out
        grown = [(t, "p.o", 0x400, 0x600), (t, "q.o", 0x300, 0x500), (t, "r.o", 0x300, 0), (t, "s.o", 0x100, 0)]
        ref_at = {(t, "p.o"): 0x80060000, (t, "q.o"): 0x80070000}
        runs, placed = af_relink.pack_holes(grown, ref_at)
        self.assertEqual(sorted(placed), ["p.o", "q.o", "r.o", "s.o"])
        self.assertEqual(sorted(placed.values()), [0x80060000, 0x80060300, 0x80070000, 0x80070400])
        # a section that would cross into the next window starts at the window's start, if it still fits
        grown = [(t, "w.o", 0x200, 0x300)]
        self.assertEqual(af_relink.pack_holes(grown, {(t, "w.o"): 0x80067F00})[1], {"w.o": 0x80068000})
        self.assertEqual(af_relink.pack_holes(grown, {(t, "w.o"): 0x80067E80})[1], {})        # 0x180 left there
        self.assertEqual(af_relink.window_start(0x80067FF0, 0x10), 0x80067FF0)
        self.assertEqual(af_relink.lay([(t, "x.o", 0x20, 0), (t, "y.o", 0x30, 0)], 0x80197FD8),
                         ([(0x80197FE0, (t, "x.o", 0x20, 0)), (0x80198000, (t, "y.o", 0x30, 0))], 0x80198030))

        # through the script: a.o's and b.o's text grew; their slots touch, and the run holds a.o (the larger
        # that fits); b.o goes to code_en
        script = ("SECTIONS\n{\n    __romPos = 0;\n"
                  "    code_ROM_START = __romPos;\n    .code 0x80051A80 : AT(code_ROM_START) SUBALIGN(16)\n    {\n"
                  "        code_TEXT_START = .;\n        build/src/code/a.o(.text);\n        build/src/code/b.o(.text);\n"
                  "        build/src/code/c.o(.text);\n        code_TEXT_END = .;\n"
                  "        code_DATA_START = .;\n        build/src/code/c.o(.data);\n        code_DATA_END = .;\n    }\n"
                  "    __romPos += SIZEOF(.code);\n    code_ROM_END = __romPos;\n    code_VRAM_END = .;\n"
                  "    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    buffers_ROM_START = __romPos;\n    .buffers_bss code_VRAM_END (NOLOAD) { *(.bss); }\n"
                  "    buffers_ROM_END = __romPos;\n    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    ovl_select_ROM_START = __romPos;\n    .ovl_select 0x80800000 : AT(ovl_select_ROM_START) { *(.ovl); }\n"
                  "    __romPos += SIZEOF(.ovl_select);\n    ovl_select_ROM_END = __romPos;\n"
                  "    __romPos = ALIGN(__romPos, 16);\n    . = ALIGN(., 16);\n\n"
                  "    /DISCARD/ :\n    {\n        *(*);\n    }\n}\n")

        def mapfile(a, b, c=0x200):
            return (".code           0x80051a80   0x100000 load address 0x01914000\n"
                    "                0x80051a80                        code_TEXT_START = .\n"
                    " .text          0x80051a80      0x%x build/src/code/a.o\n"
                    " .text          0x%08x      0x%x build/src/code/b.o\n"
                    " .text          0x%08x      0x%x build/src/code/c.o\n"
                    "                0x%08x                        code_DATA_START = .\n"
                    " .data          0x%08x       0x20 build/src/code/c.o\n"
                    ".buffers_bss    0x801524c0    0x42420\n"
                    " .bss           0x801524c0    0x42420 build/asm/buffers.o\n"
                    "                0x801948e0                        buffers_VRAM_END = .\n"
                    % (a, 0x80051a80 + a, b, 0x80051a80 + a + b, c, 0x80051a80 + a + b + c, 0x80051a80 + a + b + c))
        with tempfile.TemporaryDirectory() as d:
            p, ref, new, moved = [os.path.join(d, n) for n in ("a.ld", "ref.map", "new.map", "moved.txt")]
            open(p, "w").write(script)
            open(ref, "w").write(mapfile(0x100, 0x100))
            open(new, "w").write(mapfile(0x180, 0x120))
            args = [p, "--ref", ref, "--map", new, "--moved-out", moved, "--next-rom", "0x73F4D0",
                    "--pristine", os.path.join(d, "a.ld.splat")]
            af_relink.main(args)
            lines = open(p).read().splitlines()
            self.assertEqual(open(moved).read(), "build/src/code/a.o .text\nbuild/src/code/b.o .text\n")
            at = lines.index("        code_TEXT_START = .;")
            self.assertTrue(lines[at + 1].startswith("        /* af_relink: build/src/code/a.o(.text) grew to 0x180: a run "
                                                     "of slots, 0x200 bytes from 0x80051a80,"))
            self.assertEqual(lines[at + 2], "        build/src/code/a.o(.text);")
            self.assertEqual(lines[at + 3], "        . += 0x80; /* af_relink: the rest of the run */")
            self.assertTrue(lines[at + 4].startswith("        /* af_relink: build/src/code/b.o(.text) grew to 0x120: its slot "
                                                     "is part of the run"))
            self.assertEqual(lines[at + 5], "        build/src/code/c.o(.text);")
            self.assertEqual(lines.count("        build/src/code/a.o(.text);"), 1)
            en = lines.index("        code_en_TEXT_START = .;")
            self.assertEqual(lines[en + 1:en + 3], ["        build/src/code/b.o(.text);", "        code_en_TEXT_END = .;"])
            # a section placed in a run that is not its own: with x.o (unchanged) between them, c.o's text (a
            # slot of 0x40) grew to 0x180 and fits where a.o's and b.o's, grown past their run of 0x200, do not;
            # its own slot stays a hole of its old size and the section stands once, in the run (its own line
            # was once taken for the one written there: the section went back into its slot and all that
            # follows it moved, which af_shiftcheck refused)
            script2 = script.replace("        build/src/code/c.o(.text);\n",
                                     "        build/src/code/x.o(.text);\n        build/src/code/c.o(.text);\n")

            def mapfile2(a, b, c):
                x = 0x80051a80 + a + b
                return mapfile(a, b, c).replace(" .text          0x%08x      0x%x build/src/code/c.o\n" % (x, c),
                                                " .text          0x%08x       0x80 build/src/code/x.o\n"
                                                " .text          0x%08x      0x%x build/src/code/c.o\n" % (x, x + 0x80, c))
            p2 = os.path.join(d, "b.ld")
            open(p2, "w").write(script2)
            open(ref, "w").write(mapfile2(0x100, 0x100, 0x40))
            open(new, "w").write(mapfile2(0x300, 0x280, 0x180))
            af_relink.main([p2] + args[1:-1] + [os.path.join(d, "b.ld.splat")])
            lines = open(p2).read().splitlines()
            at = lines.index("        code_TEXT_START = .;")
            self.assertTrue(lines[at + 1].startswith("        /* af_relink: build/src/code/a.o(.text) grew to 0x300: a run "
                                                     "of slots, 0x200 bytes from 0x80051a80,"))
            self.assertEqual(lines[at + 2:at + 4], ["        build/src/code/c.o(.text);",
                                                    "        . += 0x80; /* af_relink: the rest of the run */"])
            self.assertTrue(lines[at + 4].startswith("        /* af_relink: build/src/code/b.o(.text) grew to 0x280: its slot "))
            self.assertEqual(lines[at + 5], "        build/src/code/x.o(.text);")
            self.assertEqual(lines[at + 6], "        . += 0x40; /* af_relink: build/src/code/c.o(.text) grew to 0x180: "
                                            "its slot stays, it is in another's */")
            self.assertEqual(lines[at + 7], "        code_TEXT_END = .;")
            self.assertEqual(lines.count("        build/src/code/c.o(.text);"), 1)
            en = lines.index("        code_en_TEXT_START = .;")
            self.assertEqual(sorted(lines[en + 1:en + 3]), ["        build/src/code/a.o(.text);", "        build/src/code/b.o(.text);"])
            open(ref, "w").write(mapfile(0x100, 0x100))
            open(new, "w").write(mapfile(0x180, 0x120))
            # a kept slot: nothing goes into the run it is part of
            af_relink.main(args + ["--keep-hole", "build/src/code/b.o"])
            lines = open(p).read().splitlines()
            at = lines.index("        code_TEXT_START = .;")
            self.assertTrue(lines[at + 1].startswith("        . += 0x100; /* af_relink: build/src/code/a.o(.text) grew to 0x180: its slot stays */"))
            self.assertTrue(lines[at + 2].startswith("        . += 0x100; /* af_relink: build/src/code/b.o(.text) grew to 0x120: its slot stays */"))
            en = lines.index("        code_en_TEXT_START = .;")
            self.assertEqual(sorted(lines[en + 1:en + 3]), ["        build/src/code/a.o(.text);", "        build/src/code/b.o(.text);"])

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
            # a moved section may sit in code again, but only inside the slots that moved sections left
            self.assertEqual(run(mapfile(0, moved_at=0x800ff370), ("build/src/code/a.o", ".data")), (0, []))
            delta, errors = run(mapfile(0, moved_at=0x80051b00), ("build/src/code/a.o", ".data"))
            self.assertTrue(any("outside the slots moved sections left" in e for e in errors), errors)
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

    def test_no_text_names_a_control_the_cartridges_pad_has_not(self):
        named = lambda text, **kw: [w for lv, w in af_text.check_message(   # noqa: E731
            0, msgbank.parse(text), 32, 4, 192, **kw) if lv == "error" and "GameCube's pad" in w]
        # a letter or a notice, a message (a colour's code between the words, a line's end), a string
        self.assertEqual(len(named("Use the C Stick to turn\nthe pages.", letter=True)), 1)
        self.assertEqual(len(named("Tilt that <COLORCHARS c3821e07>C\nStick!<MSGEND>")), 1)
        self.assertEqual(len(named("the X Button", single=True)), 1)
        self.assertEqual(len(named("Press START/PAUSE, then the Y Button.<MSGEND>")), 2)
        # the cartridge's own: its C is four buttons, and it has a stick, a pad, A, B, R, Z and START
        ours = "Press the A Button. The C Buttons turn the pages, the B Button ends. START, the R Button, " \
               "the Control Stick, the Control Pad."
        self.assertEqual(named(ours, letter=True), [])
        self.assertEqual(af_text.gc_controls(msgbank.parse("a magic stick, an X, buttons")), [])

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

    def test_string_draft_takes_a_name_from_another_number(self):
        # STRING_REMAP: a spare slot of the en string bank takes the GameCube's string of another number (a name
        # the GameCube's table gives a character whom the cartridge's table names with another's string)
        (en, theirs), = af_text.STRING_REMAP.items()
        self.assertGreater(theirs, af_text.N64_BANKS["string"][2])          # past the cartridge's bank
        with tempfile.TemporaryDirectory() as d:
            jp, gc, out = (os.path.join(d, f) for f in ("jp.txt", "gc.txt", "out.txt"))
            open(jp, "w", encoding="utf-8").write("## %d\nよび\n\n" % en)
            open(gc, "w", encoding="utf-8").write("## %d\n\n\n## %d\nDeskman\n\n" % (en, theirs))
            old = af_text.N64_COUNT
            try:
                af_text.N64_COUNT = en + 1
                af_text.draft(jp, gc, "-", out, [], af_text.STRING_REMAP)
            finally:
                af_text.N64_COUNT = old
            text = open(out, encoding="utf-8").read()
            self.assertEqual(msgbank.render(msgbank.Bank.parse_dump(text)[en]), "Deskman")
            self.assertIn("## %d\n#: the GameCube's string %d (STRING_REMAP)" % (en, theirs), text)

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
        # and so with an item's string or the chosen answer (the draft once looked at the free strings only)
        en = msgbank.parse("<STR_FREE2>'s <STR_ITEM2>, <STR_COUNTRYNAME>" + end)
        self.assertEqual(af_text.check_message(8, en, 32, 4, 0x400, original=jp),
                         [("warning", "prints STR_ITEM2, which the cartridge's message does not: the game may not fill it")])
        with tempfile.TemporaryDirectory() as d:
            text, index = os.path.join(d, "t.bin"), os.path.join(d, "i.bin")
            all_msgs = {n: msgbank.parse("<MSGEND>") for n in range(af_text.N64_COUNT)}
            all_msgs.update(msgs)
            af_text.compile_bank(all_msgs, text, index)
            t = open(text, "rb").read()
            ends = struct.unpack(">%dI" % af_text.N64_COUNT, open(index, "rb").read()[:af_text.N64_COUNT * 4])
            self.assertEqual(t[:ends[0]], msgbank.encode(msgs[0]))
            self.assertEqual(t[ends[0]:ends[1]], msgbank.encode(msgs[1]))
            self.assertEqual(len(t) % 16, 0)

    def test_the_cartridges_tail_prints_nothing_again(self):
        # the cartridge's codes after its last text, laid after the English's: its catchphrase and full stop
        # after a pause (which _text_end does not count as text) are not printed again; its pause, newlines
        # and the codes that steer the game stay
        cart = msgbank.parse("\u3042\u3044<PAUSE 06><STR_TAIL>\u3002\n<SETNEXTMSG0 0001><FORCENEXT>\n<MSGCONTINUE>")
        theirs = msgbank.parse("Hi, <STR_TAIL>!<SETNEXTMSG0 0002><FORCENEXT>\n<MSGCONTINUE>")
        out, how = af_text.mend(cart, theirs)
        self.assertIn("codes after the text", how)
        self.assertEqual(msgbank.render(out),
                         "Hi, <STR_TAIL>!<PAUSE 06>\n<SETNEXTMSG0 0001><FORCENEXT>\n<MSGCONTINUE>")
        self.assertEqual(af_text.steering(out), af_text.steering(cart))
        self.assertEqual(af_text.japanese_text({1: out, 2: cart, 3: msgbank.parse("Hi\uff5e<MSGEND>")}), [2])

    def test_index_reads_stay_in_the_table(self):
        # the game reads entry n's ends 8 bytes from n - 1 (n odd) or 16 from n - 2 (n even, n > 0): with an odd
        # count, the last entry's read went past the table and the DMA manager stopped the game (December's name)
        def window(n):
            return (0, 8) if n == 0 else ((n - 1) * 4, 8) if n & 1 else ((n - 2) * 4, 16)
        old = af_text.N64_COUNT
        with tempfile.TemporaryDirectory() as d:
            text, index = os.path.join(d, "t.bin"), os.path.join(d, "i.bin")
            for count in (5, 6, 7, 8, 0x679):
                try:
                    af_text.N64_COUNT = count
                    af_text.compile_bank({n: msgbank.parse("s%d" % n) for n in range(count)}, text, index)
                finally:
                    af_text.N64_COUNT = old
                table = open(index, "rb").read()
                self.assertEqual(len(table) % 16, 0)
                for n in range(count):
                    at, size = window(n)
                    self.assertLessEqual(at + size, len(table), (count, n))
                ends = struct.unpack(">%dI" % count, table[:count * 4])
                body = open(text, "rb").read()
                self.assertEqual(body[ends[count - 2]:ends[count - 1]], msgbank.encode([("t", "s%d" % (count - 1))]))

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
        # a word printed twice in a row printed once (af_text.DOUBLED): the line break or space between kept
        und = lambda text: msgbank.render(af_text.undouble(msgbank.parse(text)))
        self.assertEqual(und("a big big dog<PAUSE 08> big big\n<MSGEND>"), "a big dog<PAUSE 08> big big\n<MSGEND>")
        self.assertEqual(und("go to\nto the shop<BTN>\n<MSGEND>"), "go to\nthe shop<BTN>\n<MSGEND>")
        with self.assertRaises(ValueError):
            af_text.undouble(msgbank.parse("that's that\n<MSGEND>"))
        # a row of the map by its number where the text has the GameCube's letter (af_text.ACRE_ROWS)
        rule = lambda f, text: msgbank.render(f(msgbank.parse(text)))
        self.assertEqual(rule(af_text.acre_rows, "It's in Acre B-3,<PAUSE 06> or Acre F?\n<MSGEND>"),
                         "It's in Acre 2-3,<PAUSE 06> or Acre 6?\n<MSGEND>")
        with self.assertRaises(ValueError):
            af_text.acre_rows(msgbank.parse("Acre 2-3, an Acreage, Acre G\n<MSGEND>"))
        # a birthday's two numbers joined (af_text.MONTH_DAY): the space between the two strings only
        self.assertEqual(rule(af_text.month_day, "<STR_ITEM3>? On <STR_ITEM3> <STR_ITEM4>, <STR_ITEM4> <STR_ITEM3>\n<MSGEND>"),
                         "<STR_ITEM3>? On <STR_ITEM3>-<STR_ITEM4>, <STR_ITEM4> <STR_ITEM3>\n<MSGEND>")
        with self.assertRaises(ValueError):
            af_text.month_day(msgbank.parse("<STR_ITEM3>, <STR_ITEM4>\n<MSGEND>"))
        # the first sentence that starts with a small letter given its capital (af_text.SMALL_START):
        # after a sentence's end and codes or blanks only, not after an ellipsis
        self.assertEqual(rule(af_text.capital, "Wait... what? no way.<PAUSE 08>\nyes way.\n<MSGEND>"),
                         "Wait... what? No way.<PAUSE 08>\nyes way.\n<MSGEND>")
        self.assertEqual(rule(af_text.capital, "Done.<PAUSE 06><BTN>\n<MSGCLEAR>and now?\n<MSGEND>"),
                         "Done.<PAUSE 06><BTN>\n<MSGCLEAR>And now?\n<MSGEND>")
        with self.assertRaises(ValueError):
            af_text.capital(msgbank.parse("Well... all right. Fine!\n<MSGEND>"))
        # the space after a comma that runs into a printed string, pauses between (af_text.COMMA_RUN)
        self.assertEqual(rule(af_text.comma_space, "So,<PAUSE 08><STR_TAIL>! Hey,<STR_PLAYERNAME>, <STR_TAIL>\n<MSGEND>"),
                         "So, <PAUSE 08><STR_TAIL>! Hey, <STR_PLAYERNAME>, <STR_TAIL>\n<MSGEND>")
        with self.assertRaises(ValueError):
            af_text.comma_space(msgbank.parse("So, <STR_TAIL>,<PAUSE 08> then\n<MSGEND>"))
        # each numbered rule's numbers are messages, and a rule applies to each of its own
        for numbers, f, what, count in af_text.OFFICIAL_RULES:
            self.assertTrue(numbers and all(0 <= n < af_text.N64_BANKS["message"][2] for n in numbers), what)
        # a line that ends in a dash after a letter goes on in the next line's first word, no space between,
        # moved on or filled anew; a dash after a code that prints a string, or alone, is a word's end
        self.assertEqual(lay("aaaa bbbb ccー<PAUSE 06>\ndd e\n<MSGEND>"), "aaaa bbbb\nccー<PAUSE 06>dd e\n<MSGEND>")
        self.assertEqual(lay("aaaa bbbb cc-\ndd\n\ne\nf\n<MSGEND>"), "aaaa bbbb\ncc-dd e f\n<MSGEND>")
        self.assertEqual(lay("aaaa bbbb <STR_FREE2>-<STR_FREE3>\ndd\n<MSGEND>"),
                         "aaaa bbbb\n<STR_FREE2>-<STR_FREE3> dd\n<MSGEND>")
        self.assertEqual(lay("aaaa bbbb c -\ndd\n<MSGEND>"), "aaaa bbbb\nc - dd\n<MSGEND>")
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
        # the heart is in the N64 charset; a code the draft has no rule for is kept, for the check to refuse
        self.assertEqual(msgbank.render(out), "<CUTARTICLE>Hey, cafe ♥!<MALEFEMALECHK 00010002><MSGEND>")
        self.assertEqual(counts["dropped SETCURSORJUST"], 1)
        refused = [text for level, text in af_text.check_message(0, out, 32, 4, 0x400) if level == "error"]
        self.assertEqual(refused, ["code %d is the GameCube's, not the N64's" % af_text.NAMES["MALEFEMALECHK"]])
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
                7: p("<MSGEND>"),
                8: p("<DEMONPC0 0000ff>\n<BTN>\n<MSGCLEAR><PAUSE 08><STR_TAIL>\n<MSGEND>")}  # pages with no text
        ours = dict(cart)
        ours[5] = p("Ours.<MSGEND>")                         # translated already: not a candidate
        theirs = {0: p("<DEMONPC0 000016>Hello,<PAUSE 06> <STR_PLAYERNAME>!<BTN>\n<MSGCLEAR>Bye.<MSGEND>"),
                  1: p("<DEMONPC0 090002>Hi.<MSGEND>"),       # another order in slot 9: the shop acts
                  2: p("Hi <STR_FREE2> and <STR_FREE3>.<MSGEND>"),   # a free string the game does not fill
                  3: p("Hi.<MSGCONTINUE>"),                  # another end: the conversation goes on
                  4: p("あいう<MSGEND>"),                    # still Japanese
                  5: p("Theirs.<MSGEND>"),
                  6: p("<DEMONPC0 000016>Hi.<MSGEND>"),      # another talk order in slot 0: the cartridge's put back
                  7: p("Hm.<MSGEND>"),
                  8: p("<DEMONPC0 0000ff>Hm.<BTN>\n<MSGCLEAR><STR_TAIL>\n<MSGEND>")}
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
                # English, or no text at all where the cartridge's prints none (blank.txt)
                self.assertTrue(af_nafe_text.english(tokens) or not af_nafe_text.text_of(tokens).strip(), n)
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

    def test_resetti_strings_fit_the_dial_and_his_compares(self):
        # tools/af_dial.py phrases: a phrase to type is 1 to 10 characters the dial has; an insult is at most as
        # long as its place in the list compares (2, then one more from words 6, 16, 25, 30 and 31), and is in
        # no phrase nor in a try one slip away from one
        self.assertEqual([af_dial.insult_len(i) for i in (0, 5, 6, 15, 16, 24, 25, 29, 30, 31)],
                         [2, 2, 3, 3, 4, 4, 5, 5, 6, 7])
        self.assertTrue(af_dial.typed("Oh, it's 5 o'clock! \u2665"))
        self.assertFalse(af_dial.typed("a = b") or af_dial.typed("two\nlines") or af_dial.typed("caf\u00e9"))
        # a string's characters are its bytes: the dump's escape before < is none, a raw byte is one no dial types
        text = af_dial.string_text(msgbank.parse("I \\<3 U {80}"))
        self.assertEqual((text, len(text)), ("I <3 U \0", 8))
        self.assertTrue(af_dial.typed(text[:6]) and not af_dial.typed(text))
        good = {af_dial.PENANCE_FIRST + i: "Phrase %d!" % i for i in range(af_dial.PENANCE_COUNT)}
        good.update({af_dial.INSULT_FIRST + i: "qzxjvwk"[:af_dial.insult_len(i)] for i in range(af_dial.INSULT_COUNT)})
        self.assertEqual(af_dial.phrase_problems(good), [])
        bad = dict(good)
        bad[af_dial.PENANCE_FIRST] = "one = two"                       # a sign no mode has
        bad[af_dial.PENANCE_FIRST + 1] = "eleven chars"                # past the window
        bad[af_dial.INSULT_FIRST] = "qzx"                              # 3 where 2 are compared
        bad[af_dial.INSULT_FIRST + 6] = "RaS"                          # inside a phrase to type, whatever the case
        bad[af_dial.INSULT_FIRST + 7] = "Pra"                          # in "Prase 2!": a letter left out
        bad[af_dial.INSULT_FIRST + 8] = "e 3"                          # inside phrase 3
        bad[af_dial.INSULT_FIRST + 9] = "e!"                           # with its space: in "Phrase!", the digit left out
        bad[af_dial.INSULT_FIRST + 16] = "qzx"                         # shorter than its place: with a space, fine
        self.assertEqual([t.split(":")[0] for t in af_dial.phrase_problems(bad)],
                         [str(af_dial.PENANCE_FIRST), str(af_dial.PENANCE_FIRST + 1), str(af_dial.INSULT_FIRST)] +
                         [str(af_dial.INSULT_FIRST + k) for k in (6, 7, 8, 9)])
        self.assertIn("one slip", af_dial.phrase_problems(bad)[4])
        self.assertIn("one slip", af_dial.phrase_problems(bad)[6])
        # the tries one slip away, as the window holds them: 10 characters, spaces after
        tries = af_dial.slips("I ate")
        self.assertTrue({"I at".ljust(10), "I ete".ljust(10), "I aet".ljust(10), "I late".ljust(10),
                         "I ate!".ljust(10)} <= tries)
        self.assertNotIn("I ate".ljust(10), tries)
        self.assertFalse(any(len(t) != 10 for t in tries))
        self.assertFalse(any(len(t.rstrip()) > 10 for t in af_dial.slips("ten chars!")))    # no room for one more
        # ours (script/string/resetti.txt): the three phrases and the whole list of insults; the GameCube's
        # thirteen other phrases are checked with them when the bank is compiled (make rom-en)
        ours = {n: msgbank.render(t) for n, t in self.own(os.path.join(HERE, "..", "script", "string")).items()}
        strings = {af_dial.PENANCE_FIRST + i: "x" for i in range(af_dial.PENANCE_COUNT)}
        strings.update({n: t for n, t in ours.items()
                        if af_dial.PENANCE_FIRST <= n < af_dial.PENANCE_FIRST + af_dial.PENANCE_COUNT
                        or af_dial.INSULT_FIRST <= n < af_dial.INSULT_FIRST + af_dial.INSULT_COUNT})
        self.assertEqual(sum(1 for n in strings if n >= af_dial.INSULT_FIRST), af_dial.INSULT_COUNT)
        self.assertEqual(af_dial.phrase_problems(strings), [])

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
                # English, or empty where the cartridge's letter is (blank.txt)
                self.assertTrue(af_nafe_text.english(tokens) or not af_nafe_text.text_of(tokens).strip(), (bank, n))


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
            self.assertIn("    3, 2, 1, 0, 0, 255, 255, 0x90, 0x91, 0x92, 0x48, 0x69, /* A */ \\\n", text)
            self.assertIn("    2, 2, 0, 6, 0, 255, 255, 0xB0, 0xB1, 0x4F, 0x6B, /* C */ \\\n", text)
            self.assertTrue(text.endswith("    0\n"))                # packed: no padding, a key of length 0 last
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
            self.assertIn("    4, 11, 0, 16, 12, 2, 5, ", af_ui.subst_include(subs))
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
            pad = af_items.NAME_MAX - 4
            self.assertEqual(data[:size], bytes([4, 1, 3, 0]) + b"bass" + b" " * pad)         # 24 px: 2 cells, 3 at least
            self.assertEqual(data[size:2 * size], bytes([4, 0, 3, 0]) + b"carp" + b" " * pad)  # ours first
            self.assertEqual(data[2 * size:2 * size + 6], bytes([2, 0, 3, 0, 0x10, 0x11]))    # the cartridge's
            self.assertEqual(counts, {"fish": [1, 1, 1], "ftr": [1, 0, 0]})
            self.assertEqual(af_items.cells(b"x" * 16, [9] * 256), af_items.NAME_LEN)       # 144 px: the buffer's 10
            with self.assertRaises(SystemExit):
                af_items.records(bytes(rom), {("fish", 0): ("a name of twenty-one!", 1)}, {}, widths)
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
        quillon = af_items.n64_bytes("t", "Quillon").ljust(af_items.NAME_MAX)
        self.assertEqual(data[:size], bytes([7, 0, 5, 0]) + quillon)                # 50 px
        self.assertEqual([n for _, n, _ in tokens], [1703, 1704, 1705])
        self.assertEqual(tokens[0][2], bytes([0x80, 0x81 + 1703 // 64, 0x81 + 1703 % 64, 0x80, 0x80, 0x20]))
        self.assertEqual(af_items.token(5, 6, 6), bytes([0x80, 0x81, 0x86, 0x80, 0x80, 0x80]))
        self.assertEqual(af_items.cells(b"x" * 9, [9] * 256, af_items.ANIMAL_NAME_LEN), 6)     # 82 px: the field's
        with self.assertRaises(ValueError):
            af_items.token(64 * 64, 3, 6)                             # past two digits
        inc = af_items.include(True, tokens)
        self.assertIn("#define ITEM_EN_RECORDS %d " % (len(af_items.slots()) + 3), inc)
        self.assertIn("#define ITEM_EN_SPECIAL_NAMES { { 0x205, 1705 }, { 0x000, 0 }, }", inc)

    def test_a_special_character_named_apart_from_the_cartridges_table(self):
        # the cartridge's table of the special characters' names gives the policeman at the desk the string of
        # the one at the door; the GameCube's gives him one of his own, which the en string bank keeps in a
        # spare slot (af_text.STRING_REMAP) and the include tells the en table (ITEM_EN_NAME_<number>)
        (number, (cart, en)), = af_items.NAMES_APART.items()
        self.assertIn(en, af_text.STRING_REMAP)
        self.assertIn(en, af_items.SPECIAL_NAME_STRINGS)               # a name longer than 6 there takes a token
        self.assertIn("#define ITEM_EN_NAME_%04X 0x%03X " % (number, en), af_items.include(True, (), True))
        self.assertIn("#define ITEM_EN_NAME_%04X 0x%03X " % (number, cart), af_items.include(False))  # no disc
        self.assertTrue(af_items.include_apart(af_items.include(True, (), True)))
        self.assertFalse(af_items.include_apart(af_items.include(True)))   # special-names refuses this one
        source = ("SpecialNpcData l_sp_actor_name[] = {\n"
                  "    { 0xD00D, NPC_SEX_MALE, 0x0210, 2 },   { 0x%04X, NPC_SEX_MALE, ITEM_EN_NAME_%04X, 3 },\n"
                  "    { 0xD04E, NPC_SEX_MALE, 0x0210, 2 },\n};\n" % (number, number))
        table = af_items.en_special_names(source)
        self.assertEqual(table, [(0xD00D, 0x210), (number, en), (0xD04E, 0x210)])
        self.assertEqual(af_items.en_special_names(source, False)[1], (number, cart))
        theirs = [(0xD00D, 0x210), (number, af_text.STRING_REMAP[en]), (0xD04E, 0x210), (0xD07E, 1752)]
        self.assertEqual(af_items.special_name_problems(table, theirs), [])
        # the cartridge's table as it was: he takes the other's string, and the tool says so
        problems = af_items.special_name_problems([(0xD00D, 0x210), (number, cart), (0xD04E, 0x210)], theirs)
        self.assertEqual(len(problems), 2)
        self.assertIn("%#x" % number, problems[0])
        # a character the GameCube's table does not have, with a string no token knows
        self.assertEqual(len(af_items.special_name_problems(table + [(0xD0FF, 0x300)], theirs)), 2)
        with self.assertRaises(SystemExit):
            af_items.en_special_names(source.replace("ITEM_EN_NAME_%04X" % number, "ITEM_EN_NAME_D00D"))
        with self.assertRaises(SystemExit):                                # an entry the reader cannot read
            af_items.en_special_names(source.replace("0x0210, 2 },   {", "0x0210, 0x2 },   {"))
        # the en string bank must hold the GameCube's string there: a bank drafted before the remap is refused
        gc = {af_text.STRING_REMAP[en]: msgbank.parse("Deskman")}
        self.assertTrue(af_items.names_apart({en: msgbank.parse("Deskman")}, gc))
        with self.assertRaises(SystemExit):
            af_items.names_apart({en: msgbank.parse("spare")}, gc)
        # the GameCube's table is read from its module's .data by the map's place: 12 bytes an entry
        entry = af_items.SPECIAL_ENTRY
        self.assertEqual(entry.size, 12)
        self.assertEqual(entry.unpack(entry.pack(number, 0, 1753, 3))[::2], (number, 1753))

    def test_item_names_are_read_from_the_decompressed_image(self):
        # a name lies at its file's vrom, which is its place in the decompressed image only: a read that
        # falls outside the image (the dump, where the file is elsewhere) is refused, not answered with nothing
        rom = bytearray(af_items.NAME_FILE + 0x30)
        rom[af_items.NAME_FILE + 8:af_items.NAME_FILE + 18] = bytes(range(0x10, 0x1A))
        self.assertEqual(af_items.n64_name(bytes(rom), "paper", 0), bytes(range(0x10, 0x1A)))
        with self.assertRaises(SystemExit):
            af_items.n64_name(bytes(0x1000000), "paper", 0)

    def test_articles_follow_the_first_sound(self):
        # a or an by the name's first sound: the first letter, but a number as it is read, initials letter by
        # letter and the words read against their first letter
        a, an = af_items.ARTICLES.index("a"), af_items.ARTICLES.index("an")
        for text, want in (("apple crate", an), ("pear crate", a), ("8 ball rug", an), ("11 o'clock tea", an),
                           ("18th hole", an), ("7 seas map", a), ("80s lamp", an), ("X mark tee", an),
                           ("U turn sign", a), ("MVP cap", an), ("TV set", a), ("one-way sign", a),
                           ("unicycle", a), ("umbrella", an), ("hourglass", an), ("horn", a), ("(odd) box", an),
                           ("...", None)):
            self.assertEqual(af_items.sound_article(text), want, text)
        saved = af_items.KINDS, af_items.FTR_CLOTH
        try:
            af_items.KINDS = [("cloth", 2, 0x10, "itemName_cloth", "itemArt_Cloth"), ("ftr", 3, 0x40, "ftrName_table", "ftrArt")]
            af_items.FTR_CLOTH = (1, 3)                    # the furniture 1 and 2 are the clothes 0 and 1
            rom = bytes(af_items.NAME_FILE + 0x100)
            the = af_items.ARTICLES.index("the")
            gc = {("cloth", 0): ("eel print", a), ("cloth", 1): ("oak leaf tee", the), ("ftr", 0): ("gold urn", an),
                  ("ftr", 1): ("eel print", a), ("ftr", 2): ("oak leaf tee", the)}
            mended = []
            data, counts = af_items.records(rom, gc, {}, [6] * 256, mended)
            size = af_items.RECORD_SIZE
            self.assertEqual([data[k * size + 1] for k in range(5)], [an, the, a, an, the])   # a and an only
            self.assertEqual(mended, [("cloth", 0), ("ftr", 0), ("ftr", 1)])
            # a name of ours is taken as it is, and names the same clothes as furniture
            own = {("cloth", 0): ("urn print", a)}
            data, counts = af_items.records(rom, gc, own, [6] * 256)
            self.assertEqual(data[:size], bytes([9, a, 5, 0]) + b"urn print" + b" " * (af_items.NAME_MAX - 9))
            self.assertEqual(data[3 * size:4 * size], data[:size])
            self.assertEqual(counts, {"cloth": [1, 1, 0], "ftr": [2, 1, 0]})
            # a record holds a name of ours past the GameCube's 16 characters, up to NAME_MAX; with its article
            # it must fit what a sentence or an answer takes of it
            self.assertGreater(af_items.NAME_MAX, af_items.GC_NAME_LEN)
            self.assertEqual(size, 4 + af_items.NAME_MAX)
            long_name = "x" * af_items.NAME_MAX
            data, _ = af_items.records(rom, gc, {("cloth", 0): (long_name, a)}, [6] * 256)
            self.assertEqual(data[:size], bytes([af_items.NAME_MAX, a, 10, 0]) + long_name.encode())
            for own in ({("cloth", 0): (long_name + "x", a)},
                        {("cloth", 0): (long_name, af_items.ARTICLES.index("some"))}):
                with self.assertRaises(SystemExit):
                    af_items.records(rom, gc, own, [6] * 256)
        finally:
            af_items.KINDS, af_items.FTR_CLOTH = saved
        self.assertEqual(max(len(w) for w in af_items.ARTICLE_WORDS) + af_items.GC_NAME_LEN, 21)
        self.assertLessEqual(21, af_items.TEXT_MAX)                 # every name of the GameCube's fits with any
        # ours (script/items.txt): NAME_MAX characters at most, and a or an as the first sound asks
        for (kind, i), (text, art) in af_items.read_names(os.path.join(HERE, "..", "script", "items.txt")).items():
            self.assertLessEqual(len(text), af_items.NAME_MAX, (kind, i))
            self.assertLessEqual(len(af_items.ARTICLE_WORDS[art]) + len(text), af_items.TEXT_MAX, (kind, i))
            if art in (a, an):
                self.assertEqual(af_items.sound_article(text), art, (kind, i, text))

    def test_a_reply_prints_only_what_the_game_fills(self):
        # a villager's reply (mNpc_SetRemailFreeString): free strings 0 to 13 for every reply, 14 and 15 only for
        # an animal of another town, whose parts start at 0xC0; the letter bank's replies to a letter that
        # could not be read, 18 from 0xC5 and 18 from 0xD8, the same; no other entry is ruled on
        free = [msgbank.NAMES["STR_FREE%d" % i] for i in range(20)]
        self.assertEqual(af_text.reply_fills("vmail_a", 0xBF), set(free[:14]))
        self.assertEqual(af_text.reply_fills("vmail_footer", 0xC0), set(free[:16]))
        self.assertEqual(af_text.reply_fills("mail_body", 0xC5 + 17), set(free[:14]))
        self.assertEqual(af_text.reply_fills("mail_body", 0xD8), set(free[:16]))
        for bank, number in (("mail_body", 0xC4), ("mail_body", 0xD7), ("mail_body", 0xD8 + 18), ("message", 1),
                             ("string", 0xC0)):
            self.assertIsNone(af_text.reply_fills(bank, number), (bank, number))
        town, name = msgbank.parse("From <STR_FREE14>"), msgbank.parse("Dear <STR_FREE0>,")
        cartridge = msgbank.parse("Hello,")                           # the cartridge's part prints neither
        for tokens, number, level in ((name, 0x10, None), (town, 0x10, "error"), (town, 0xC0, None),
                                      (msgbank.parse("<STR_FREE16>"), 0xC0, "error")):
            problems = af_text.check_message(number, tokens, 32, 4, 96, original=cartridge, letter=True,
                                             fills=af_text.reply_fills("vmail_a", number))
            self.assertEqual([lv for lv, _ in problems], [level] if level else [], (tokens, number))
        # an entry the rule does not know keeps the warning against the cartridge's own text
        problems = af_text.check_message(1, name, 32, 4, 96, original=cartridge, letter=True)
        self.assertEqual([lv for lv, _ in problems], ["warning"])

    def test_strings_loaded_into_fewer_bytes_take_tokens(self):
        # the strings the cartridge's code loads into fewer bytes than the English may take (SHORT_LOADED: by
        # who loads them): one longer than its loader's bytes takes a token that fills them, its record after
        # the others'; one that fits stays text
        numbers = {}
        for first, count, field in af_items.SHORT_LOADED:
            self.assertIn(field, (4, 10))
            for n in range(first, first + count):
                numbers.setdefault(n, set()).add(field)
        self.assertEqual(len(numbers), 4 * 32 + 9 * 32 + 2 * 12 + 4 + 4 + 6 * 32 + 73 + 4 + 8 * 15)
        self.assertTrue(all(len(f) == 1 for f in numbers.values()))          # no string loaded two ways
        # what Mr. Resetti compares byte for byte is never a token (tools/af_dial.py holds it to 10 characters)
        compared = set(range(af_dial.PENANCE_FIRST, af_dial.PENANCE_FIRST + af_dial.PENANCE_COUNT)) | set(
            range(af_dial.INSULT_FIRST, af_dial.INSULT_FIRST + af_dial.INSULT_COUNT))
        self.assertFalse(compared & set(numbers))
        strings = {673: msgbank.parse("Luck in all things"[:16]), 674: msgbank.parse("So-so"),
                   1368: msgbank.parse("Corner Shop Co."), 100: msgbank.parse("Elsewhere, long"),
                   0x219: msgbank.parse("silver minnow"), 0x21A: msgbank.parse("carp"),
                   0x454: msgbank.parse("corner shop"), 0x455: msgbank.parse("mart"),
                   0x566: msgbank.parse("pieces")}
        found = af_items.short_loaded(strings)
        self.assertEqual({field: [w for w, _ in names] for field, names in found.items()},
                         {4: [("loaded", 0x454)], 10: [("loaded", 0x219), ("loaded", 673), ("loaded", 1368)]})
        records, tokens = af_items.name_records(found[10], 1950, [6] * 256, 10)
        self.assertEqual(records[:4], bytes([13, 0, 7, 0]))                 # 78 px: 7 cells
        self.assertEqual(tokens[0][2], bytes([0x80, 0x81 + 1950 // 64, 0x81 + 1950 % 64] + [0x80] * 4 + [0x20] * 3))
        self.assertEqual([len(t[2]) for t in tokens], [10, 10, 10])
        _, short = af_items.name_records(found[4], 1953, [6] * 256, 4)
        self.assertEqual(short[0][2], bytes([0x80, 0x81 + 1953 // 64, 0x81 + 1953 % 64, 0x80]))   # 66 px: its 4
        bank = msgbank.Bank.parse_dump(af_items.string_tokens(tokens + short))
        self.assertEqual(msgbank.encode(bank[673]), tokens[1][2])           # the bank's compile reads them back
        self.assertEqual(msgbank.encode(bank[0x454]), short[0][2])

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

    @staticmethod
    def tiled(texels, w, h):
        """The texels as the GameCube keeps a CI4 texture: 8x8 tiles, left to right, then down."""
        out = bytearray()
        for ty in range(h // 8):
            for tx in range(w // 8):
                for r in range(8):
                    row = texels[(ty * 8 + r) * w + tx * 8:(ty * 8 + r) * w + tx * 8 + 8]
                    out += bytes(row[c] << 4 | row[c + 1] for c in range(0, 8, 2))
        return bytes(out)

    @staticmethod
    def room(drawn, size=0x400):
        """A made-up room's file: lists that load each palette and draw each CI4 texture with it (drawn:
        [(the palette's offset, the texture's, its width, its height)]), zeros after them."""
        lists = b""
        for palette, texture, w, h in drawn:
            lists += struct.pack(">II", 0xFD100000, 0x06000000 | palette) + struct.pack(">II", 0xF0000000, 0)
            lists += struct.pack(">II", 0xFD500000, 0x06000000 | texture) + struct.pack(">II", 0xF3000000, 0x07000000)
            lists += struct.pack(">II", 0xF5400000, 0) + struct.pack(">II", 0xF2000000, (w - 1) << 14 | (h - 1) << 2)
        return bytearray(lists + bytes(size - len(lists)))

    def test_a_rooms_lists_tell_each_textures_palette(self):
        room = self.room([(0x100, 0x200, 16, 8), (0x100, 0x280, 8, 8), (0x140, 0x300, 8, 8)])
        self.assertEqual(af_gc_signs.drawn_ci(bytes(room)), {0x200: (4, 16, 8), 0x280: (4, 8, 8), 0x300: (4, 8, 8)})
        self.assertEqual(af_gc_signs.drawn_palettes(bytes(room)), {0x200: 0x100, 0x280: 0x100, 0x300: 0x140})

    def test_a_rooms_sign_is_the_gamecubes_where_it_is_the_same_sign_redrawn(self):
        w, h = 16, 8
        old = [1 + (x + y) % 2 for y in range(h) for x in range(w)]              # texels 1 and 2
        new = list(old)
        new[5:9] = [3, 3, 3, 3]                                                  # four texels redrawn, in entry 3
        colours = [0x0001 | i << 11 for i in range(16)]                          # sixteen opaque reds
        room = self.room([(0x100, 0x200, w, h), (0x100, 0x280, 8, 8)])
        room[0x100:0x120] = struct.pack(">16H", *colours)
        room[0x200:0x240] = bytes(old[i] << 4 | old[i + 1] for i in range(0, w * h, 2))
        room[0x280:0x2A0] = bytes([0x12]) * 32                                   # the palette's other user: 1 and 2
        # the GameCube's palette: opaque RGB555 entries of the same colours
        theirs = [0x8000 | i << 10 for i in range(16)]
        tex = {"sign": self.tiled(new, w, h), "pal": struct.pack(">16H", *theirs)}
        table = [("sign", "pal", 0x1000, 0x200, (w, h), 0.9, "a sign")]
        out = af_gc_signs.room_signs(tex, bytes(room), 0x1000, table)
        self.assertEqual(list(out), [0x200])
        self.assertEqual(af_gc_signs.texels(out[0x200]), new)
        self.assertEqual(af_gc_signs.room_signs(tex, bytes(room), 0x2000, table), {})    # another room's
        # the GameCube changed the colour of the entry it redrew in: written, the other user not using it
        tex["pal"] = struct.pack(">16H", *(theirs[:3] + [0x8000 | 31] + theirs[4:]))
        out = af_gc_signs.room_signs(tex, bytes(room), 0x1000, table)
        self.assertEqual(sorted(out), [0x100 + 2 * 3, 0x200])
        self.assertEqual(out[0x100 + 2 * 3], struct.pack(">H", 31 << 1 | 1))
        # a colour changed that the texture does not use is left alone
        tex["pal"] = struct.pack(">16H", *(theirs[:9] + [0x8000 | 31] + theirs[10:]))
        self.assertEqual(list(af_gc_signs.room_signs(tex, bytes(room), 0x1000, table)), [0x200])
        # the palette's other user draws with the entry changed: refused
        tex["pal"] = struct.pack(">16H", *(theirs[:3] + [0x8000 | 31] + theirs[4:]))
        room[0x280:0x2A0] = bytes([0x13]) * 32
        with self.assertRaises(SystemExit):
            af_gc_signs.room_signs(tex, bytes(room), 0x1000, table)
        room[0x280:0x2A0] = bytes([0x12]) * 32
        # not the same sign (too few texels the same), the very same texture, another size: refused
        for texels in ([3] * (w * h), old):
            with self.assertRaises(SystemExit):
                af_gc_signs.room_signs(dict(tex, sign=self.tiled(texels, w, h)), bytes(room), 0x1000, table)
        with self.assertRaises(SystemExit):
            af_gc_signs.room_signs(tex, bytes(room), 0x1000, [("sign", "pal", 0x1000, 0x280, (w, h), 0.9, "a sign")])

    def test_a_rooms_file_takes_the_signs_over_the_cartridges_only(self):
        work = tempfile.mkdtemp()
        try:
            vrom, room = 0x12345000, bytes(range(256)) * 2
            path = os.path.join(work, af_gc_signs.ROOM_BIN % vrom)
            os.makedirs(os.path.dirname(path))
            open(path, "wb").write(room)
            af_gc_signs.put_room(work, vrom, room, {0x10: b"abcd", 0x100: b"xy"})
            held = open(path, "rb").read()
            self.assertEqual((held[0x10:0x14], held[0x100:0x102]), (b"abcd", b"xy"))
            self.assertEqual(held[:0x10] + held[0x14:0x100] + held[0x102:], room[:0x10] + room[0x14:0x100] + room[0x102:])
            # again over what an earlier make put there: whatever it was, the rest must be the cartridge's
            af_gc_signs.put_room(work, vrom, room, {0x10: b"efgh", 0x100: b"xy"})
            self.assertEqual(open(path, "rb").read()[0x10:0x14], b"efgh")
            with self.assertRaises(SystemExit):
                af_gc_signs.put_room(work, vrom, room, {0x10: b"efgh"})        # 0x100 is not the cartridge's
        finally:
            shutil.rmtree(work)



class OwnSigns(unittest.TestCase):
    """Our English for the fireworks stall's banner and cards and the police box's notice board
    (tools/af_own_signs.py; reference/NOTES.md, "Our own signs"), on a made-up object."""

    def test_the_art_fits_its_places(self):
        rows = af_own_signs.grid(af_own_signs.BANNER)
        x0, y0 = af_own_signs.BANNER_PLACE
        bx0, bx1, by0, by1 = af_own_signs.BANNER_BOX
        self.assertTrue(bx0 <= x0 and x0 + len(rows[0]) <= bx1 and by0 <= y0 and y0 + len(rows) <= by1)
        self.assertEqual(set("".join(rows)) - {"."}, set(af_own_signs.BANNER_INKS))
        for _, _, inks, art, _, _ in af_own_signs.CARDS:
            rows = af_own_signs.grid(art)
            self.assertEqual((len(rows[0]), len(rows)), (16, 32))
            self.assertEqual(set("".join(rows)) - {"."}, set(inks))
        for _, art in af_own_signs.BOARDS:
            rows = af_own_signs.grid(art)
            x0, x1, y0, y1 = af_own_signs.BOARD_BOX
            self.assertEqual((len(rows[0]), len(rows)), (x1 - x0, y1 - y0))
            self.assertEqual(set("".join(rows)) - {"."}, set(af_own_signs.BOARD_INKS))
        placed = [(af_own_signs.HQ, af_own_signs.HQ_PLACE, af_own_signs.HQ_BOX, af_own_signs.HQ_INKS),
                  (af_own_signs.PLAQUE, af_own_signs.PLAQUE_PLACE, af_own_signs.PLAQUE_BOX, af_own_signs.PLAQUE_INKS)]
        placed += [(art, (x, y), af_own_signs.SLIPS_BOX, af_own_signs.SLIPS_INKS) for x, y, art in af_own_signs.SLIPS]
        for art, (x0, y0), (bx0, bx1, by0, by1), inks in placed:
            rows = af_own_signs.grid(art)
            self.assertTrue(bx0 <= x0 and x0 + len(rows[0]) <= bx1 and by0 <= y0 and y0 + len(rows) <= by1)
            self.assertEqual(set("".join(rows)) - {"."}, set(inks))
        with self.assertRaises(ValueError):
            af_own_signs.grid("\n..\n...\n")

    def test_a_card_hangs_turned_a_quarter_clockwise(self):
        # upright, the card's top row is the texture's right column, read downwards
        self.assertEqual(af_own_signs.card_cell(64, 0, 0, 0), 64 + 31)
        self.assertEqual(af_own_signs.card_cell(64, 0, 15, 0), 15 * 128 + 64 + 31)
        self.assertEqual(af_own_signs.card_cell(96, 16, 0, 31), 16 * 128 + 96)
        art = "\n".join(["#" + "." * 15] + ["." * 16] * 31)
        t = af_own_signs.card([0] * (128 * 32), 64, 0, 0xD, {"#": 5}, art, None)
        self.assertEqual(t[64 + 31], 5)
        self.assertEqual({t[y * 128 + x] for y in range(16) for x in range(64, 96)}, {5, 0xD})
        self.assertEqual(t[16 * 128 + 64], 0)                   # the card below it untouched
        kept = af_own_signs.card([7] * (128 * 32), 64, 0, 0xD, {"#": 5}, art, (0, 16, 30, 32))
        self.assertEqual(kept[af_own_signs.card_cell(64, 0, 3, 31)], 7)
        self.assertEqual(kept[af_own_signs.card_cell(64, 0, 3, 29)], 0xD)

    def test_the_kana_give_way_to_the_cloth_beside_them(self):
        row = [3, 3, 0xF, 0xF, 2, 6, 0xF, 1, 1]
        t = row + [0] * (128 - len(row))
        out = af_own_signs.clean_rows(t, (0, 128, 0, 1), {0xF, 6})
        self.assertEqual(out[:len(row)], [3, 3, 3, 2, 2, 2, 1, 1, 1])     # the left where both are as near
        with self.assertRaises(ValueError):
            af_own_signs.clean_rows([0xF] * 128, (0, 128, 0, 1), {0xF})

    def test_composed_over_a_made_up_object(self):
        offsets = [at for copies, _, _, _ in af_own_signs.TEXTURES for at in copies]
        obj = bytearray(max(offsets) + af_own_signs.SIZE)
        for at in offsets:
            obj[at:at + af_own_signs.SIZE] = bytes([0x23]) * af_own_signs.SIZE       # texels 2 and 3
        out = af_own_signs.compose(bytes(obj))
        self.assertEqual(sorted(out), sorted(offsets))
        self.assertEqual(out[af_own_signs.HQ_AT[0]], out[af_own_signs.HQ_AT[1]])     # both copies the same
        banner = af_gc_signs.texels(out[af_own_signs.BANNER_AT])
        x0, y0 = af_own_signs.BANNER_PLACE
        self.assertEqual(banner[y0 * 128 + x0], 0xF)                                  # the R's corner
        self.assertEqual(banner[20 * 128:], [2, 3] * (12 * 64))                       # the hem kept
        self.assertEqual(banner[y0 * 128 + 64:y0 * 128 + 128], [2, 3] * 32)          # the figures beside it kept
        board = af_gc_signs.texels(out[af_own_signs.SUMMER_AT])
        self.assertEqual(board[0 * 128 + 2], af_own_signs.BOARD_GROUND)
        self.assertEqual(board[22 * 128 + 2:22 * 128 + 4], [2, 3])                   # its foot kept
        self.assertEqual(board[0 * 128 + 32:0 * 128 + 34], [2, 3])                   # the poster beside it kept
        self.assertNotEqual(out[af_own_signs.SUMMER_AT], out[af_own_signs.WINTER_AT])

    def test_the_posters_titles(self):
        for p in af_own_signs.POSTERS:
            rows = af_own_signs.grid(p.art)
            (x0, x1, y0, y1), (px, py) = p.box, p.place
            self.assertTrue(x0 <= px and px + len(rows[0]) <= x1 and y0 <= py and py + len(rows) <= y1)
            self.assertEqual(set("".join(rows)) - {"."}, set(p.inks))
            self.assertTrue(0 <= x0 < x1 <= p.w and 0 <= y0 < y1 <= p.h)
        self.assertEqual(af_own_signs.ROOMS, sorted({af_own_signs.POLICE_ROOM, af_own_signs.SHOP2_ROOM}))
        # over a made-up room: each poster's box its ground and our letters, the rest of the poster kept
        for vrom in af_own_signs.ROOMS:
            mine = [p for p in af_own_signs.POSTERS if p.room == vrom]
            room = bytes([0xBC]) * max(p.at + p.w * p.h // 2 for p in mine)           # texels b and c
            out = af_own_signs.compose_room(room, vrom)
            self.assertEqual(sorted(out), sorted(p.at for p in mine))
            for p in mine:
                (x0, x1, y0, y1), (px, py) = p.box, p.place
                t = af_gc_signs.texels(out[p.at])
                self.assertEqual(len(t), p.w * p.h)
                box = {t[y * p.w + x] for y in range(y0, y1) for x in range(x0, x1)}
                self.assertEqual(box, {p.ground} | set(p.inks.values()))
                rows = af_own_signs.grid(p.art)
                dy = next(i for i, row in enumerate(rows) if "#" in row)
                self.assertEqual(t[(py + dy) * p.w + px + rows[dy].index("#")], p.inks["#"])
                self.assertEqual(t[y1 * p.w:], [0xB, 0xC] * ((p.h - y1) * p.w // 2))  # below the box kept
                self.assertEqual(t[0:2], [0xB, 0xC])                                  # the poster's edge kept
                if x1 < p.w:
                    self.assertEqual(t[y0 * p.w + x1], (0xB, 0xC)[(y0 * p.w + x1) % 2])   # and right of the box
        # the second shop's poster: a title, the rule under it where the cartridge's is, three lines
        help_ = next(p for p in af_own_signs.POSTERS if p.room == af_own_signs.SHOP2_ROOM)
        rows = af_own_signs.grid(help_.art)
        rules = [i for i, row in enumerate(rows) if "=" in row]
        self.assertEqual([help_.place[1] + i for i in rules], [8])
        self.assertEqual(rows[rules[0]].strip("."), "=" * 40)
        lines = [i for i, row in enumerate(rows) if "#" in row]
        self.assertEqual(len(lines), 4 * 5)                                           # four lines, five rows each
        self.assertTrue(all(help_.gc is None or len(p.gc) == 3 for p in af_own_signs.POSTERS))
        self.assertEqual(af_own_signs.GC_NAMES, sorted({n for p in af_own_signs.POSTERS if p.gc for n in p.gc[:2]}))
        with self.assertRaises(ValueError):
            af_own_signs.poster([0] * (32 * 48), 32, (1, 31, 2, 9), 3, "####", (28, 2), {"#": 7})

    def test_the_slips_keep_their_band(self):
        t = [0xF] * (128 * 32)
        for x in range(98, 126):
            t[17 * 128 + x] = 0x6                       # the band's red
            t[23 * 128 + x] = 0x8                       # a red mark under it
            t[10 * 128 + x] = 0xD                       # the green writing
        out = af_own_signs.slips(t)
        self.assertEqual(out[17 * 128 + 100], 0x6)
        self.assertEqual(out[23 * 128 + 100], af_own_signs.SLIPS_PAPER)
        self.assertEqual(out[10 * 128 + 100], af_own_signs.SLIPS_PAPER)
        self.assertEqual(out[10 * 128 + 97], 0xF)        # outside the slips' box untouched


class OwnFurniture(unittest.TestCase):
    """Our English for the furniture's words (tools/af_own_ftr.py; reference/NOTES.md, "Our own furniture
    words"), on made-up textures."""

    def test_the_fonts_and_the_words(self):
        for size, font in af_own_ftr.FONTS.items():
            for ch, rows in font.items():
                self.assertEqual(len(rows), size, ch)
                self.assertEqual(len({len(r) for r in rows}), 1, ch)
                self.assertTrue(set("".join(rows)) <= {"#", "."}, ch)
        self.assertEqual(af_own_ftr.word("AB", 5)[0], ".#..##.")
        self.assertEqual(len(af_own_ftr.word("A B", 5)[0]), 3 + af_own_ftr.SPACE[5] + 3)
        for pic in (af_own_ftr.ARROW, af_own_ftr.ADULT, af_own_ftr.CHILD):
            self.assertEqual(len({len(r) for r in pic}), 1)
        for f in af_own_ftr.FURNITURE:
            for op in f[9]:
                if op[0] == "text":
                    self.assertTrue(all(c == " " or c in af_own_ftr.FONTS[op[1]] for c in op[2]), op[2])

    def test_kept_for_loadblock(self):
        raw = bytes(range(64))                                      # 16x8 CI4: odd rows' words swapped
        rows = af_own_ftr.unswap(raw, 16, 8)
        self.assertEqual(rows[0][:2], [0, 0])
        self.assertEqual(rows[1][:2], [0x0, 0xC])                  # row 1 starts with its second word (byte 12)
        self.assertEqual(af_own_ftr.swap(rows), raw)

    def test_the_operations(self):
        rows = [[1, 2, 3, 4, 5, 6] for _ in range(4)]
        self.assertEqual(af_own_ftr.apply(rows, ("paint", (1, 3, 0, 2), 9))[1], [1, 9, 9, 4, 5, 6])
        ink = [[1, 7, 7, 4, 7, 6]]
        self.assertEqual(af_own_ftr.apply(ink, ("clean", (0, 6, 0, 1), {7}))[0], [1, 1, 4, 4, 4, 6])
        stripes = [[0, 1, 2, 3, 4, 5], [9] * 6, [9] * 6]
        out = af_own_ftr.apply(stripes, ("restripe", (0, 4, 1, 3)))
        self.assertEqual(out[1][:4], [1, 2, 3, 4])
        self.assertEqual(out[2][:4], [2, 3, 4, 9])                 # the row above as it now is
        ground = [[0] * 9 for _ in range(7)]
        text = af_own_ftr.apply(ground, ("text", 5, "I", ("centre", (0, 9, 0, 7)), 5, ("outline", 6)))
        self.assertEqual(text[1][3:6], [5, 5, 5])                  # centred: rows 1-5, columns 3-5
        self.assertEqual(text[0][2:7], [6, 6, 6, 6, 6])            # the outline round it
        self.assertEqual(text[2][3], 6)                            # inside the I's serif, not ink: outline
        shadow = af_own_ftr.apply(ground, ("text", 5, "I", (0, 0), 5, ("shadow", 6)))
        self.assertEqual(shadow[1][2], 6)                          # under and right of the serif's end
        self.assertEqual(shadow[0][3], 0)
        with self.assertRaises(ValueError):
            af_own_ftr.apply(ground, ("text", 5, "SAFETY", ("centre", (0, 9, 0, 7)), 5, None))
        with self.assertRaises(ValueError):
            af_own_ftr.apply(ground, ("art", ["##"], (8, 0), 5))

    def test_composed_over_made_up_textures(self):
        for vrom, tmem, w, h, base, pal, md5, pal_md5, what, ops in af_own_ftr.FURNITURE:
            raw = bytes((x * 7 + 3) & 0xFF for x in range(w * h // 2))
            new = af_own_ftr.compose(raw, w, h, ops)
            self.assertEqual(len(new), len(raw), what)
            self.assertNotEqual(new, raw, what)

    def test_the_check(self):
        # a made-up object: its texture block at 0x20 and a render tile CI4 16x16 at TMEM 0 (line 1)
        tile = struct.pack(">II", 0xF5000000 | (2 << 21) | (0 << 19) | (1 << 9), 0)
        size = struct.pack(">II", 0xF2000000, (15 << 2) << 12 | (15 << 2))
        tex = bytes(range(128))
        data = bytes(32) + tex + tile + size
        md5 = hashlib.md5(tex).hexdigest()
        pal = hashlib.md5(data[0:32]).hexdigest()
        af_own_ftr.check(data, 0, 16, 16, 0x20, 0, md5, pal, "made up")
        with self.assertRaises(SystemExit):
            af_own_ftr.check(data, 0, 32, 16, 0x20, 0, md5, pal, "made up")      # no such tile
        with self.assertRaises(SystemExit):
            af_own_ftr.check(data, 0, 16, 16, 0x20, 0, "0" * 32, pal, "made up")  # not the cartridge's texels


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


class Dial(unittest.TestCase):
    """The en build's dial without its kana modes (tools/af_dial.py; reference/NOTES.md, "The dial's small
    letters"): our label, the panel's rows and the symbols' ring, on a made-up segment and overlay."""

    @staticmethod
    def label_list(tex):
        words = [0xE7000000, 0, af_dial.SET_TIMG, 0x0C000000 | tex, 0xF5900000, 0x07000000, 0xE6000000, 0,
                 0xF3000000, 0x070BF2AB, 0xE7000000, 0, 0xF5800600, 0x00FD0360, 0xF2000000, 0x000BC03C,
                 0x01004008, 0x0C000A50, 0x06000204, 0x00020604, 0xDF000000, 0]
        return b"".join(struct.pack(">I", w) for w in words)

    def cartridge_segment(self):
        seg = bytearray(0x2000)
        for dl, tex in ((0x1440, 0x1848), (0x1498, 0x19C8), (0x14F0, 0x1CC8), (0x1548, 0x1E48), (0x15A0, 0x1B48)):
            lst = self.label_list(tex)
            seg[dl:dl + len(lst)] = lst
        for at in (0x1848, 0x19C8, 0x1B48, 0x1CC8, 0x1E48):
            seg[at:at + 0x180] = bytes([0x5A]) * 0x180
        return bytes(seg)

    def cartridge_overlay(self):
        ovl = bytearray(0x3800)
        marks = [msgbank.N64_BYTES[c] for c in "、。・「」"]
        table = bytes(marks[:3]) + af_dial.symbol_codes()[:10] + bytes(marks[3:]) + af_dial.symbol_codes()[10:]
        at = af_dial.SYMBOLS_AT - af_dial.EDITOR_VRAM
        ovl[at:at + 32] = table + bytes(2)
        for v in (af_dial.SYMBOLS_COUNT_AT, af_dial.SYMBOLS_RING_AT):
            ovl[v - af_dial.EDITOR_VRAM:v - af_dial.EDITOR_VRAM + 4] = struct.pack(">I", 30)
        return bytes(ovl)

    @staticmethod
    def data_s(ovl, vrams):
        lines = []
        for v in vrams:
            w = af_dial.word_at(ovl, af_dial.EDITOR_VRAM, v)
            lines.append("    /* %06X %08X %08X */ .word 0x%08X" % (v - 0x80000000, v, w, w))
        return "\n".join(lines)

    def test_the_label_is_ours_drawn_by_hand(self):
        tex = af_dial.lower_label()
        self.assertEqual(len(tex), 48 * 16 // 2)
        texels = [[(tex[(y * 48 + x) // 2] >> (4 if x % 2 == 0 else 0)) & 15 for x in range(48)] for y in range(16)]
        self.assertEqual(texels, [[af_dial.LEGEND.index(c) for c in row] for row in af_dial.LOWER_LABEL])
        inked_above = sorted({x for y in range(6) for x in range(48) if texels[y][x] >= 8})
        self.assertEqual(inked_above, [12, 13, 14])                 # above the small letters, the b's stem alone

    def test_the_rows_lists_point_at_the_four_labels(self):
        cart = self.cartridge_segment()
        edits = af_dial.segment_edits(cart)
        new = af_dial.apply_segment(cart, cart, edits)
        self.assertEqual([af_dial.word_at(new, 0, dl + 12) for dl in (0x1440, 0x15A0, 0x1498, 0x14F0, 0x1548)],
                         [0x0C001848, 0x0C001CC8, 0x0C001B48, 0x0C001E48, 0x0C001E48])
        self.assertEqual(new[0x1848:0x1848 + 0x180], af_dial.lower_label())
        self.assertEqual(af_dial.apply_segment(new, cart, edits), new)          # again: the same
        other = bytearray(new)
        other[0x15AC:0x15B0] = b"\x0c\x00\x19\xc8"
        with self.assertRaises(SystemExit):
            af_dial.apply_segment(bytes(other), cart, edits)
        moved = bytearray(cart)
        moved[0x14A4:0x14A8] = b"\x0c\x00\x18\x48"
        with self.assertRaises(SystemExit):                       # a list that does not draw the label it should
            af_dial.segment_edits(bytes(moved))

    def test_the_symbols_ring_loses_the_japanese_marks(self):
        ovl = self.cartridge_overlay()
        words = af_dial.ring_edits(ovl)
        vrams = sorted(words)
        data_s = self.data_s(ovl, vrams)
        new_s = af_dial.rewrite_data(data_s, ovl, words)
        table = b"".join(struct.pack(">I", words[v]) for v in range(af_dial.SYMBOLS_AT, af_dial.SYMBOLS_AT + 32, 4))
        self.assertEqual(table[:25], af_dial.symbol_codes())
        self.assertEqual(table[25:], b" " * 5 + bytes(2))
        self.assertEqual((words[af_dial.SYMBOLS_COUNT_AT], words[af_dial.SYMBOLS_RING_AT]), (25, 25))
        self.assertEqual(af_dial.rewrite_data(new_s, ovl, words), new_s)       # again: the same
        self.assertEqual(af_dial.SYMBOLS[:5], "?!' \n")             # a sentence's ends, a space and a newline first
        wrong = bytearray(ovl)
        wrong[af_dial.SYMBOLS_AT - af_dial.EDITOR_VRAM] = msgbank.N64_BYTES["A"]
        with self.assertRaises(SystemExit):
            af_dial.ring_edits(bytes(wrong))

    def test_the_editor_finds_the_panel_from_its_state(self):
        with open(os.path.join(HERE, "..", "decomp", "changes.patch"), encoding="utf-8") as f:
            patch = f.read()
        if "mED_en_dialLabels" not in patch:
            self.skipTest("needs the en dial in decomp/changes.patch")
        self.assertIn("+mED_en_stateToLabels = ABSOLUTE(B_80888AF0_jp) - ABSOLUTE(D_808888CC_jp);", patch)


class Answers(unittest.TestCase):
    """The answers an overlay copies from its own data, pointed at the choice bank by a token
    (tools/af_answers.py; reference/NOTES.md, "The player-select actor's answers"), on a made-up .data.s."""

    @staticmethod
    def data_s(words):
        return "\n".join("    /* %06X %08X %08X */ .word 0x%08X" % (v - 0x80000000 + 0x100, v, w, w)
                         for v, w in words)

    def test_the_token_is_the_mark_twice_and_two_digits(self):
        self.assertEqual(struct.pack(">I", af_answers.token(121)), bytes([0x80, 0x80, 0x81 + 1, 0x81 + 57]))
        self.assertEqual(struct.pack(">I", af_answers.token(0)), bytes([0x80, 0x80, 0x81, 0x81]))
        with self.assertRaises(SystemExit):
            af_answers.token(64 * 64)
        # never an item's token, whose mark is followed by a digit (the mark + 1 and up): ours by the mark
        self.assertLess(af_answers.TOKEN_MARK, af_answers.TOKEN_DIGIT0)

    def test_the_word_is_rewritten_where_the_cartridges_is(self):
        cart = 0x01020304
        data_s = self.data_s([(0x809C0C34, 0x00010200), (0x809C0C38, cart), (0x809C0C3C, 0x10)])
        new_s = af_answers.rewrite(data_s, 0x809C0C38, cart, af_answers.token(121), "test")
        self.assertIn("809C0C38 01020304 */ .word 0x808082BA", new_s)
        self.assertEqual(new_s.count(".word"), 3)
        self.assertEqual(af_answers.rewrite(new_s, 0x809C0C38, cart, af_answers.token(121), "test"), new_s)
        with self.assertRaises(SystemExit):                               # not the cartridge's word
            af_answers.rewrite(data_s, 0x809C0C38, 0x0A0B0C0D, af_answers.token(121), "test")
        with self.assertRaises(SystemExit):                               # no such line
            af_answers.rewrite(data_s, 0x809C0C40, cart, af_answers.token(121), "test")

    def test_the_en_choice_code_reads_the_same_token(self):
        with open(os.path.join(HERE, "..", "decomp", "changes.patch"), encoding="utf-8") as f:
            patch = f.read()
        if "Choice_EN_TOKEN_MARK" not in patch:
            self.skipTest("needs the answers' token in decomp/changes.patch")
        for name, value in (("MARK", af_answers.TOKEN_MARK), ("DIGIT0", af_answers.TOKEN_DIGIT0),
                            ("BASE", af_answers.TOKEN_BASE)):
            self.assertRegex(patch, r"\+#define Choice_EN_TOKEN_%s (0x%X|%d)\b" % (name, value, value))

    def test_the_bank_goes_on_past_the_cartridges_answers(self):
        # the answers past the cartridge's 460: numbered on from it, the bank's count the same in the tool, the
        # Makefile's compile and the en reader
        numbers = [number for _, _, _, _, _, number, _, _ in af_answers.EXTRA]
        self.assertEqual(numbers, list(range(af_answers.CHOICE_COUNT, af_answers.EN_CHOICE_COUNT)))
        for _, _, _, _, size, _, (sym, obj), _ in af_answers.EXTRA:
            self.assertEqual(size % 4, 0)
            self.assertTrue(obj.endswith(".o") and sym)
        with open(os.path.join(HERE, "..", "Makefile"), encoding="utf-8") as f:
            self.assertIn("EN_CHOICE_COUNT := %d\n" % af_answers.EN_CHOICE_COUNT, f.read())
        with open(os.path.join(HERE, "..", "decomp", "changes.patch"), encoding="utf-8") as f:
            patch = f.read()
        if "Choice_EN_CHOICE_NUM" not in patch:
            self.skipTest("needs the en choice bank's count in decomp/changes.patch")
        self.assertIn("+#define Choice_EN_CHOICE_NUM %d" % af_answers.EN_CHOICE_COUNT, patch)

    def test_the_answers_past_the_bank_are_ours_without_the_gamecubes(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "extra.txt")
            af_answers.write_extra(d, os.path.join(HERE, "..", "script", "answers.txt"), out)
            back = msgbank.Bank.parse_dump(open(out, encoding="utf-8").read())
            self.assertEqual(sorted(back), list(range(af_answers.CHOICE_COUNT, af_answers.EN_CHOICE_COUNT)))
            self.assertTrue(all(back.values()))
            own = os.path.join(d, "own.txt")
            open(own, "w", encoding="utf-8").write("## 460\nNone of these.\n\n## 461\nMore.\n\n")
            with self.assertRaises(SystemExit):                          # not the numbers EXTRA names
                af_answers.write_extra(d, own, out)
        self.assertEqual(af_answers.extra_dump({460: "Abc"}), "## 460\nAbc\n\n")

    def test_a_longer_copy_takes_the_token_then_spaces(self):
        # the stall copies 8 bytes: the token in the first word, spaces in the next (as the answer's slot is
        # cleared), each word where the cartridge's is
        cart = (0x01020304, 0x05060708)
        data_s = self.data_s([(0x80A73ABC, 0x0A0B0C0D), (0x80A73AC0, cart[0]), (0x80A73AC4, cart[1])])
        new_s = af_answers.rewrite(data_s, 0x80A73AC0, cart[0], af_answers.token(460), "test")
        new_s = af_answers.rewrite(new_s, 0x80A73AC4, cart[1], af_answers.SPACE_WORD, "test")
        self.assertIn("80A73AC0 01020304 */ .word 0x%08X" % af_answers.token(460), new_s)
        self.assertIn("80A73AC4 05060708 */ .word 0x20202020", new_s)
        self.assertIn("80A73ABC 0A0B0C0D */ .word 0x0A0B0C0D", new_s)


class GcNes(unittest.TestCase):
    """tools/af_gc_nes.py (reference/NOTES.md, "The NES") on made-up lists, a made-up module and a made-up
    .data.s."""

    @staticmethod
    def packed(tris):
        """The triangles as the GameCube's lists pack them: nine vertices in the first order, twelve in each
        after it, five bits each from the low end up, above four spare bits."""
        flat = [v for t in tris for v in t]
        first, rest = flat[:9] + [0] * (9 - len(flat[:9])), flat[9:]
        bits = sum(v << (4 + 5 * k) for k, v in enumerate(first))
        orders = [(af_gc_nes.G_TRIN << 24 | (len(tris) - 1) << 17 | bits >> 32, bits & 0xFFFFFFFF)]
        while rest:
            chunk, rest = rest[:12] + [0] * (12 - len(rest[:12])), rest[12:]
            bits = sum(v << (4 + 5 * k) for k, v in enumerate(chunk))
            orders.append((bits >> 32, bits & 0xFFFFFFFF))
        return orders

    def test_packed_triangles_are_read_back(self):
        rng = random.Random(7)
        for count in (1, 2, 3, 4, 7, 8, 16, 31):
            tris = [tuple(rng.randrange(32) for _ in range(3)) for _ in range(count)]
            orders = self.packed(tris)
            self.assertEqual(len(orders), 1 + max(0, -(-(3 * count - 9) // 12)))
            self.assertEqual(af_gc_nes.triangles(orders + [(0xDF000000, 0)]), (tris, len(orders)))
        two = self.packed([(0, 1, 2), (0, 2, 3)])
        self.assertEqual(two, [(0x0A020000, 0x62008200)])                  # two triangles of a square
        with self.assertRaises(ValueError):                                # a spare bit set
            af_gc_nes.triangles([(two[0][0], two[0][1] | 1)])
        with self.assertRaises(ValueError):                                # a vertex past the count
            af_gc_nes.triangles([(two[0][0] | 1 << 12, two[0][1])])
        with self.assertRaises(ValueError):                                # more triangles than orders
            af_gc_nes.triangles(self.packed([(1, 2, 3)] * 4)[:1])
        with self.assertRaises(ValueError):
            af_gc_nes.triangles([(0x06000204, 0x00000406)])

    def test_triangles_go_two_an_order(self):
        self.assertEqual(af_gc_nes.tri_orders([(0, 1, 2), (0, 2, 3), (4, 5, 6)]),
                         [(0x06000204, 0x00000406), (0x05080A0C, 0)])
        self.assertEqual(af_gc_nes.tri_orders([]), [])

    def test_a_texture_is_loaded_as_a_block(self):
        clamp, mirror = af_gc_nes.WRAP[0], af_gc_nes.WRAP[2]
        self.assertEqual((clamp, af_gc_nes.WRAP[1], mirror), (2, 0, 1))    # G_TX_CLAMP, G_TX_WRAP, G_TX_MIRROR
        self.assertEqual(af_gc_nes.load_block(0x06000040, 16, 8, clamp, clamp),
                         [(0xFD500000, 0x06000040), (0xF5500000, 0x0708C240), (0xE6000000, 0),
                          (0xF3000000, 0x0701F800), (0xE7000000, 0), (0xF5400200, 0x00F8C240),
                          (0xF2000000, 0x0003C01C)])
        wide = af_gc_nes.load_block(0x06000080, 128, 16, clamp, clamp)
        self.assertEqual([wide[3], wide[5], wide[6]],
                         [(0xF3000000, 0x071FF100), (0xF5401000, 0x00F90270), (0xF2000000, 0x001FC03C)])
        twice = af_gc_nes.load_block(0x06000680, 16, 16, mirror, mirror)
        self.assertEqual([twice[1], twice[5]], [(0xF5500000, 0x07050140), (0xF5400200, 0x00F50140)])
        with self.assertRaises(ValueError):
            af_gc_nes.load_block(0, 24, 16, clamp, clamp)                  # not a power of two
        self.assertEqual(af_gc_nes.load_palette(0x06000020),
                         [(0xE7000000, 0), (0xFD100000, 0x06000020), (0xE8000000, 0), (0xF50001F0, 0x07000000),
                          (0xE6000000, 0), (0xF0000000, 0x0703C000), (0xE7000000, 0)])

    def lists(self):
        """Two made-up lists as the GameCube keeps one: its state, a palette, a texture by its size and a
        tile, vertices, packed triangles; the second with the label's texture and palette."""
        state = [(0xD7000002, 0), (0xFC127E60, 0xFFFFF3F8), (0xE200001C, 0xC8113078)]
        one = state + [(af_gc_nes.GC_TLUT, 0), (0xFD440C0F, 0), (0xD2F0F000, 0), (0xFA000080, 0xFFFFFFFF),
                       (0xD9000000, 0x00230405), (0x01004008, 0)] + self.packed([(0, 1, 2), (0, 2, 3)]) + \
            [(0xDF000000, 0)]
        two = state + [(0xFA0000FF, 0xFFFFFFFF), (af_gc_nes.GC_TLUT, 9 << 24), (0xFD441C1F, 8 << 24),
                       (0xD2F0FA00, 0), (0xD9000000, 0x00230405), (0x01003006, 0)] + self.packed([(2, 1, 0)]) + \
            [(0xDF000000, 0)]
        return [(one, {3: af_gc_nes.PALETTE, 4: "tex", 8: (af_gc_nes.VERTICES, 0)}),
                (two, {4: 9, 5: 8, 8: (af_gc_nes.VERTICES, 64)})]

    def test_the_lists_are_said_in_the_n64s_orders(self):
        where = {af_gc_nes.PALETTE: 0, 9: 0x20, "tex": 0x40, 8: 0xC0, af_gc_nes.VERTICES: 0x2C0}
        orders, textures, drawn = af_gc_nes.convert(self.lists(), lambda what, at: 0x06000000 + where[what] + at,
                                                    af_gc_nes.PALETTE)
        self.assertEqual(textures, {"tex": (16, 16), 8: (32, 32)})
        self.assertEqual(drawn, 7)
        want = [af_gc_nes.TEXTURE_ON, (0xE7000000, 0), af_gc_nes.TLUT_RGBA16, (0xFC127E60, 0xFFFFF3F8),
                (0xE200001C, 0xC8113078)]
        want += af_gc_nes.load_block(0x06000040, 16, 16, 2, 2)             # the palette is the room's: no load
        want += [(0xFA000080, 0xFFFFFFFF), (0xD9000000, 0x00230405), (0x01004008, 0x060002C0),
                 (0x06000204, 0x00000406)]
        want += [(0xFA0000FF, 0xFFFFFFFF)]                                 # the state the same: said once
        want += af_gc_nes.load_palette(0x06000020) + af_gc_nes.load_block(0x060000C0, 32, 32, 1, 1)
        want += [(0x01003006, 0x06000300), (0x05040200, 0), (0xDF000000, 0)]
        self.assertEqual(orders, want)
        # a palette is loaded again only when it is another
        again = self.lists() + [self.lists()[0]]
        orders, _, _ = af_gc_nes.convert(again, lambda what, at: where[what] + at, af_gc_nes.PALETTE)
        self.assertEqual(sum(1 for w0, _ in orders if w0 >> 24 == af_gc_nes.G_LOADTLUT), 2)

    def test_a_list_this_does_not_know_is_refused(self):
        def said(change):
            lists = self.lists()
            change(lists)
            with contextlib.redirect_stderr(io.StringIO()):
                return af_gc_nes.convert(lists, lambda what, at: at, af_gc_nes.PALETTE)
        for change in (lambda ls: ls[0][0].insert(3, (0xBB000000, 0)),                    # an order of no one's
                       lambda ls: ls[0][0].__setitem__(9, (0x0A020000, 0x62008A00)),      # a vertex not loaded
                       lambda ls: ls[0][0].__setitem__(4, (0xFD100C0F, 0)),               # not CI4 by its size
                       lambda ls: ls[0][0].__setitem__(5, (0xD2F0F001, 0)),               # a tile with a shift
                       lambda ls: ls[0][0].__setitem__(0, (0xD7000002, 0x80008000)),      # another texture scale
                       lambda ls: ls[0][1].pop(8),                                        # vertices nothing names
                       lambda ls: ls[0][1].__setitem__(8, (af_gc_nes.VERTICES, 8)),       # the middle of a vertex
                       lambda ls: ls[0][0].pop()):                                        # no end
            with self.assertRaises(SystemExit):
                said(change)
        self.assertEqual(len(said(lambda ls: None)[0]), 34)

    def data(self):
        vertices = bytes(range(112))
        return {af_gc_nes.PALETTE: bytes([1]) * 32, "tex": bytes([2]) * 128, af_gc_nes.VERTICES: vertices,
                "label-a": bytes([3]) * 512, "pal-a": bytes([4]) * 32,
                "label-b": bytes([5]) * 512, "pal-b": bytes([6]) * 32}

    def test_an_object_is_its_palettes_its_textures_its_vertices_and_its_list(self):
        a, at = af_gc_nes.build(self.lists(), self.data(), ("label-a", "pal-a"))
        b, at_b = af_gc_nes.build(self.lists(), self.data(), ("label-b", "pal-b"))
        self.assertEqual((at, at_b), (0x330, 0x330))
        self.assertEqual(a[:0x20], bytes([1]) * 32)                        # the console's palette, the profile's
        self.assertEqual(a[0x20:0x40], bytes([4]) * 32)                    # the label's
        self.assertEqual(a[0x40:0xC0], bytes([2]) * 128)
        self.assertEqual(a[0xC0:0x2C0], bytes([3]) * 512)
        self.assertEqual(a[0x2C0:0x330], bytes(range(112)))
        orders = [struct.unpack(">II", a[i:i + 8]) for i in range(at, len(a), 8)]
        self.assertEqual(len(orders), 34)
        self.assertEqual(orders[-1], (0xDF000000, 0))
        self.assertIn((0xFD500000, 0x06000040), orders)
        self.assertIn((0xFD500000, 0x060000C0), orders)
        self.assertIn((0xFD100000, 0x06000020), orders)
        self.assertIn((0x01003006, 0x06000300), orders)
        self.assertEqual({i for i in range(len(a)) if a[i] != b[i]}, set(range(0x20, 0x40)) | set(range(0xC0, 0x2C0)))
        for change in ({"tex": bytes(64)}, {af_gc_nes.VERTICES: bytes(96)}, {"pal-a": bytes(30)}):
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                af_gc_nes.build(self.lists(), dict(self.data(), **change), ("label-a", "pal-a"))

    def test_an_object_is_audited_for_the_room(self):
        obj, at = af_gc_nes.build(self.lists(), self.data(), ("label-a", "pal-a"))
        self.assertEqual(af_gc_nes.audit(obj, at), [])
        self.assertEqual(af_gc_nes.audit(obj + bytes(8), at), [])              # padded to 16 bytes
        orders = [struct.unpack(">II", obj[i:i + 8]) for i in range(at, len(obj), 8)]

        def with_order(k, w0, w1):
            return obj[:at + 8 * k] + struct.pack(">II", w0, w1) + obj[at + 8 * k + 8:]
        timg = next(k for k, (w0, _) in enumerate(orders) if w0 >> 24 == af_gc_nes.G_SETTIMG)
        vtx = next(k for k, (w0, _) in enumerate(orders) if w0 >> 24 == af_gc_nes.G_VTX)
        tri = next(k for k, (w0, _) in enumerate(orders) if w0 >> 24 == af_gc_nes.G_TRI2)
        for broken in (with_order(timg, 0xFD500000, 0x06000000 + at),          # a texture in the list itself
                       with_order(timg, 0xFD500000, 0x05000040),               # in another segment
                       with_order(timg, 0xFD500000, 0x06000000 + at - 8),      # its block past the data
                       with_order(vtx, 0x01004008, 0x06000000 + at - 32),      # vertices past the data
                       with_order(tri, 0x06000208, 0x00000406),                # a vertex that is not loaded
                       with_order(tri, 0xBB000000, 0),                         # an order of no one's
                       obj[:-8] + struct.pack(">II", 0xE7000000, 0),           # no end
                       obj + struct.pack(">II", 0xE7000000, 0)):               # an order after the end
            self.assertTrue(af_gc_nes.audit(broken, at), broken[at:].hex())
        self.assertTrue(af_gc_nes.audit(obj, at + 4))
        self.assertTrue(af_gc_nes.audit(obj, len(obj)))
        self.assertRegex(af_gc_nes.OBJECTS_MD5, r"^[0-9a-f]{32}$")

    @staticmethod
    def module():
        """A made-up module: a text section of 0x20 bytes and a data section of 0x40, the data's words at 4
        and 0x24 pointed at its own 0x30 and 0x10 and the text's at 8 at the data's 0x20, by its relocations to
        itself; and the map that names them."""
        text, data = bytes(range(0x20)), bytes(range(0x40, 0x80))
        table_at, text_at = 0x30, 0x80
        data_at, imp_at = text_at + len(text), text_at + len(text) + len(data)
        rels_at = imp_at + 16
        sections = [(0, 0), (text_at | 1, len(text)), (0, 0), (0, 0), (0, 0), (data_at, len(data)), (0, 0x10)]
        rels = [(0, 202, 5, 0), (4, 1, 5, 0x30), (0x20, 1, 5, 0x10), (0, 202, 1, 0), (8, 201, 0, 0), (2, 6, 5, 0x20),
                (0, 203, 0, 0)]
        other = [(0, 202, 5, 0), (8, 1, 1, 0), (0, 203, 0, 0)]             # another module's: not this one's
        head = struct.pack(">12I", 1, 0, 0, len(sections), table_at, 0, 0, 3, 0x10, rels_at, imp_at, 16)
        body = head.ljust(table_at, b"\0") + b"".join(struct.pack(">II", *s) for s in sections)
        body = body.ljust(text_at, b"\0") + text + data
        body += struct.pack(">IIII", 0, rels_at + 8 * len(rels), 1, rels_at)
        body += b"".join(struct.pack(">HBBI", *r) for r in rels + other)
        def lay(rows):
            return "".join("  %08x %06x %08x  4 %s \t%s \n" % (o, s, o, sym, obj) for o, s, sym, obj in rows)
        text_map = ".text section layout\n" + lay([(0, 0x20, ".text", "f.o"), (0, 0x10, "one", "f.o"),
                                                    (0x10, 0x10, "two", "f.o")])
        data_map = ".data section layout\n" + lay([(0, 0x40, ".data", "d.obj"), (0, 0x10, "list", "d.obj"),
                                                    (0x10, 0x10, "tex", "d.obj"), (0x20, 0x10, "table", "f.o"),
                                                    (0x30, 0x10, "pal", "d.obj")])
        return af_gc_nes.Module(body, text_map + "\n" + data_map)

    def test_a_modules_relocations_name_what_a_word_points_at(self):
        m = self.module()
        self.assertEqual(m.find(".data", "tex", "d.obj"), (0x10, 0x10))
        self.assertEqual(m.bytes(".data", "tex", "d.obj"), bytes(range(0x50, 0x60)))
        self.assertEqual(m.bytes(".text", "two", "f.o"), bytes(range(0x10, 0x20)))
        self.assertEqual(m.target(".data", "list", "d.obj", 4), ("pal", "d.obj", 0))
        self.assertEqual(m.target(".data", "table", "f.o", 4), ("tex", "d.obj", 0))
        self.assertIsNone(m.target(".data", "list", "d.obj", 8))           # another module's relocation
        self.assertIsNone(m.target(".data", "list", "d.obj", 0))
        self.assertEqual(m.named_in(".text", "one", "f.o"), {"table"})     # by a relocation of another kind
        self.assertEqual(m.named_in(".text", "two", "f.o"), set())
        self.assertEqual(m.named(5, 0x3F), ("pal", "d.obj", 0xF))
        self.assertIsNone(m.named(5, 0x40))
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            m.find(".data", "tex", "f.o")

    def test_a_profiles_words_are_written_and_put_back(self):
        vram = 0x80A44154 - af_gc_nes.PROFILE_AT
        cart = [0x01495000, 0x01495E80, 0x06000000, 0x06000E80, 0x06000CC0, 0x06000E18, 0, 0, 0x06000020,
                0x06000000]
        data_s = "dlabel D_80A44154_jp\n" + "".join(
            "    /* %X %08X %08X */ .word %s\n" % (0x90BBF4 + 4 * k, 0x80A44154 + 4 * k, w,
                                                    "D_6000E80" if k == 3 else "0x%08X" % w)
            for k, w in enumerate(cart)) + "enddlabel D_80A44154_jp\n"
        ours = af_gc_nes.profile_words(vram, 2, 0x11E0, 0xF40)
        new_s = af_gc_nes.set_words(data_s, ours, "test")
        self.assertIn("/* 90BBF4 80A44154 01495000 */ .word ftr_en_nes_ROM_START + 0x23C0\n", new_s)
        self.assertIn("/* 90BBF8 80A44158 01495E80 */ .word ftr_en_nes_ROM_START + 0x35A0\n", new_s)
        self.assertIn("/* 90BBFC 80A4415C 06000000 */ .word 0x06000000\n", new_s)       # not one of ours
        self.assertIn("/* 90BC00 80A44160 06000E80 */ .word 0x060011E0\n", new_s)
        self.assertIn("/* 90BC04 80A44164 06000CC0 */ .word 0x06000F40\n", new_s)
        self.assertIn("/* 90BC08 80A44168 06000E18 */ .word 0x00000000\n", new_s)       # one list
        self.assertIn("/* 90BC14 80A44174 06000020 */ .word 0x00000000\n", new_s)       # no block to load
        self.assertIn("/* 90BC18 80A44178 06000000 */ .word 0x06000000\n", new_s)       # the console's palette
        self.assertEqual(af_gc_nes.set_words(new_s, ours, "test"), new_s)
        back = af_gc_nes.set_words(new_s, af_gc_nes.profile_words(vram, 2, None, None), "test")
        self.assertEqual(back, data_s.replace("D_6000E80", "0x06000E80"))               # the cartridge's words
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            af_gc_nes.set_words(data_s, {0x80A44200: None}, "test")

    def test_the_build_names_the_segment(self):
        root = os.path.join(HERE, "..")
        with open(os.path.join(root, "Makefile")) as f:
            make = f.read()
        self.assertIn("--segment %s=build/%s.o" % (af_gc_nes.SEGMENT, af_gc_nes.BIN[:-len(".bin")]), make)
        self.assertIn("$(PYTHON) tools/af_gc_nes.py $(BASEROM) $(AF_EN_DIR)", make)
        with open(os.path.join(root, "decomp", "changes.patch")) as f:
            patch = f.read()
        self.assertIn('+DEFINE_DMA_ENTRY(%s, "%s")' % (af_gc_nes.SEGMENT, af_gc_nes.SEGMENT), patch)
        added = len(re.findall(r"^\+DEFINE_DMA_ENTRY\(", patch, flags=re.M))
        self.assertIn("+u8 sDmaDataPadding[0xF0 - %d * sizeof(DmaEntry)]" % added, patch)
        self.assertEqual(af_gc_nes.PIECES, list(range(842, 850)))
        self.assertEqual([af_gc_nes.CART[piece][0] for piece in af_gc_nes.PIECES], [1, 2, 3, 4, 5, 6, 7, 0])
        for piece in af_gc_nes.PIECES:                     # the cartridge's profile of each: 19 words
            words = af_gc_nes.cart_profile(piece, 0x01495000, 0x80A440C0)
            self.assertEqual(len(words), af_gc_nes.PROFILE_WORDS)
            self.assertLessEqual(words[af_gc_nes.P_END] - words[af_gc_nes.P_ROM], af_gc_nes.PLACE)
            self.assertEqual(words[af_gc_nes.P_SEG_END] - af_gc_nes.SEG, af_gc_nes.CART[piece][1])
            self.assertEqual(words[12:16], af_gc_nes.SHAPE)
            self.assertEqual(words[16], 0x80A440C0 + af_gc_nes.FUNCS_AT)
        self.assertEqual(af_gc_nes.cart_profile(849, 0x016DB000, 0)[:6],
                         [0x016DB000, 0x016DBE60, 0x06000000, 0x06000E60, 0x06000C80, 0x06000DF0])


class EmulatorChecks(unittest.TestCase):
    """The parts of the emulator checks that judge without an emulator."""

    def frame(self, ink=(), bubble=True):
        w, h = 640, 240
        rgb = bytearray([60, 90, 60] * (w * h))                 # a dark scene
        if bubble:
            for y in range(150, 220):
                rgb[(y * w + 110) * 3:(y * w + 556) * 3] = bytes([220, 230, 215]) * 446
        for x, y in ink:
            rgb[(y * w + x) * 3:(y * w + x) * 3 + 3] = bytes([40, 30, 30])
        return w, bytes(rgb)

    def test_a_line_past_the_bubble_is_seen(self):
        self.assertEqual(emu_year.margin_ink(*self.frame()), [])
        # text ends at the frame's 512: ink right of it, inside the bubble, is a line too wide
        bad = emu_year.margin_ink(*self.frame([(520, 160), (521, 161)]))
        self.assertEqual(len(bad), 1)
        self.assertIn("line 1", bad[0])
        self.assertIn("x 520 to 521", bad[0])
        self.assertIn("line 3", emu_year.margin_ink(*self.frame([(530, 190)]))[0])
        # the next page's arrow is no line: a triangle, 517 to 531 wide at row 205, its point at row 211
        arrow = [(x, 205 + k) for k in range(7) for x in range(517 + k, 532 - k)]
        self.assertEqual(emu_year.margin_ink(*self.frame(arrow)), [])
        self.assertIn("line 4", emu_year.margin_ink(*self.frame(arrow + [(540, 208)]))[0])
        # a sign's board, a window still opening, no frame: nothing to judge
        self.assertIsNone(emu_year.margin_ink(*self.frame(bubble=False)))
        self.assertIsNone(emu_year.margin_ink(0, b""))

    def home(self, inner):
        """A save whose first home's room has walls around inner x inner places, a door's two items under it."""
        save = bytearray(0x10000)
        wall = struct.pack(">H", emu_memory.WALL)
        for z in range(inner + 2):
            for x in range(inner + 2):
                if z in (0, inner + 1) or x in (0, inner + 1):
                    at = emu_memory.HOME_LAYER + (z * 16 + x) * 2
                    save[at:at + 2] = wall
        for x, item in ((2, emu_memory.WALL), (3, 0x4000), (4, 0x4001), (5, emu_memory.WALL)):
            at = emu_memory.HOME_LAYER + ((inner + 2) * 16 + x) * 2
            save[at:at + 2] = struct.pack(">H", item)
        return save

    def test_furniture_goes_on_each_place_inside_the_walls(self):
        pieces = [(n, 100 + n) for n in range(80)]
        for inner in (4, 6, 8):
            save, put = emu_memory.furnished(self.home(inner), pieces)
            self.assertEqual(len(put), inner * inner)           # every place, and none on the door's
            items = struct.unpack(">256H", save[emu_memory.HOME_LAYER:emu_memory.HOME_LAYER + 0x200])
            there = [i for i in items if emu_memory.FURNITURE <= i < 0x4000]
            self.assertEqual(there, [emu_memory.FURNITURE + n * 4 for n, _ in put])
            self.assertEqual(items.count(emu_memory.WALL), 4 * inner + 4 + 2)
            self.assertIn(0x4000, items)
        # no more pieces than there are
        self.assertEqual(len(emu_memory.furnished(self.home(8), pieces[:10])[1]), 10)

    def test_a_save_is_edited_under_a_mask(self):
        save = bytearray(range(16))
        emu_memory.edited(save, [[2, "f0", "30"], [4, "aabb", None], [8, "01", "01"]])
        self.assertEqual(bytes(save), bytes([0, 1, 0x32, 3, 0xAA, 0xBB, 6, 7, 9, 9, 10, 11, 12, 13, 14, 15]))

    def test_the_fifth_reset_goes_back_to_three(self):
        self.assertEqual([emu_reset.kept(n) for n in range(1, 9)], [1, 2, 3, 4, 3, 4, 3, 4])

    def test_the_hooks_count_of_actors_made_is_named(self):
        names = {3: "Fieldm_Draw", 7: "Lamp_Light"}
        self.assertEqual(emu_memory.made({"made": {7: 1, 200: 2}}, names), ["0xc8", "Lamp_Light"])
        self.assertEqual(emu_memory.made(None, names), [])
        self.assertEqual(len(emu_memory.UNSEEN), 9)
        self.assertTrue(set(emu_memory.TEST_SCENES) <= set(emu_memory.UNSEEN))
        self.assertEqual(emu_memory.MADE, 0x100)                # a count for each number an actor can have

    def session(self, label, scene, **more):
        """A session's answer as tests/emu_memory.py's report reads it: a room gone into, nothing amiss."""
        count = {"fails": 0, "failed": 0, "least": 88000, "calls": 100, "most": 250720, "made": {}}
        run = {"label": label, "clock": [2002, 5, 15, 14, 0, 0], "arrived": True, "hang": None, "placed": 0,
               "overlays": 0, "facts": {"house": 0, "ordered": 0, "shop": 0, "sales": 0, "renewal": False,
                                        "special": 0, "special_day": [5, 2], "special_hour": 18, "where": {}},
               "inside": {"scene": scene, "arena": [300000, 310000, 500000], "actors": [], "loaded": [],
                          "furniture": 0, "signs": [], "got": True},
               "upstairs": None, "talk": None, "left": True, "saved": {"spoken": True, "left": True, "pages": 3},
               "pages": 5, "problems": [], "watch": dict(count), "before": dict(count), "made": [], "tried": 1,
               "sign": 0, "scenes": {"7": []}}
        for key, value in more.items():
            if isinstance(value, dict) and isinstance(run.get(key), dict):
                run[key] = dict(run[key], **value)
            else:
                run[key] = value
        return run

    def reported(self, chains, pieces=None):
        """The rooms' report over made-up sessions: the checks that failed."""
        emu_memory.failures.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            emu_memory.report_rooms(chains, 152736, pieces or {20: 24, 21: 32, 22: 48})
        failed = list(emu_memory.failures)
        emu_memory.failures.clear()
        return failed

    def test_a_rooms_report_holds_it_to_its_floor_and_to_the_arenas_word(self):
        self.assertEqual(self.reported({"night": [self.session("night", 20)]}), [])
        low = self.session("night", 20, inside={"arena": [200000, 210000, 600000]})
        self.assertEqual(len(self.reported({"night": [low]})), 1)                 # under a room's floor
        said_no = self.session("night", 20, watch={"fails": 1, "failed": 4096})
        self.assertEqual(len(self.reported({"night": [said_no]})), 1)             # the arena refused once
        early = self.session("night", 20, before={"fails": 1, "failed": 4096})
        self.assertEqual(len(self.reported({"night": [early]})), 1)               # before the town, too
        self.assertEqual(len(self.reported({"night": [None]})), 1)                # the session did not run

    def test_a_furnished_house_is_held_to_the_rooms_own_count_of_pieces(self):
        def house(letter, size, scene, free):
            return self.session("house-" + letter, scene, facts={"house": size},
                                inside={"arena": [free, free, 480000]})

        def full(letter, size, scene, placed, loaded, free):
            return self.session("house-%s-full" % letter, scene, facts={"house": size}, placed=placed,
                                overlays=59504, inside={"arena": [free, free, 530000], "furniture": loaded})
        good = [house("m", 1, 21, 410352), full("m", 1, 21, 36, 32, 360144),
                house("l", 2, 22, 313872), full("l", 2, 22, 64, 48, 246624)]
        self.assertEqual(self.reported({"houses": good}), [])
        # every piece put found loaded would be more than the room makes: the report wants the room's count
        wrong = list(good)
        wrong[1] = full("m", 1, 21, 36, 36, 360144)
        self.assertEqual(len(self.reported({"houses": wrong})), 1)
        # a full room must still have free what all the furniture's overlays together take
        tight = list(good)
        tight[3] = full("l", 2, 22, 64, 48, 150000)
        self.assertEqual(len(self.reported({"houses": tight})), 1)

    def test_a_shops_signs_must_be_the_en_roms_in_ram(self):
        def shop(label, level, outside, scene, signs, actors=(), **more):
            return self.session(label, scene, facts={"shop": level, "renewal": True, "where": {outside: [2, 3, 5, 5]}},
                                inside={"signs": signs, "actors": list(actors)}, **more)
        there, old_too, absent = ["a sign", True, False, True], ["a sign", True, True, True], ["a sign", False, False, True]
        cart = ["a sign", True, True, False]                    # the en ROM's as the cartridge's: tells nothing
        closed = self.session("shop-closed", 7, facts={"renewal": True}, sign=1, inside={"got": False})
        up = {"scene": 29, "arena": [400000, 410000, 480000], "actors": [], "loaded": [], "furniture": 0,
              "signs": [there], "got": True}
        raffle = shop("depart-raffle", 3, "Depart", 25, [there, there, there], actors=["Garagara"])
        chain = [closed, shop("conveni", 1, "Conveni", 23, [there]), shop("super", 2, "Super", 24, []),
                 shop("depart", 3, "Depart", 25, [there, there, there], upstairs=up), raffle]
        self.assertEqual(self.reported({"shops": chain}), [])
        for bad in (old_too, absent):
            worse = list(chain)
            worse[1] = shop("conveni", 1, "Conveni", 23, [bad])
            self.assertEqual(len(self.reported({"shops": worse})), 1)
        # built without the GameCube's files a sign is the cartridge's own: with one of ours beside it the room
        # still tells; with none it does not
        mixed = list(chain)
        mixed[3] = shop("depart", 3, "Depart", 25, [there, cart, cart], upstairs=up)
        self.assertEqual(self.reported({"shops": mixed}), [])
        mixed[3] = shop("depart", 3, "Depart", 25, [cart, cart, cart], upstairs=up)
        self.assertEqual(len(self.reported({"shops": mixed})), 1)


    def test_the_tent_the_save_had_last_comes_second(self):
        def order(last):
            return [what for _, what in emu_memory.tents_order(last)]
        self.assertEqual(order(emu_memory.BROKER), ["gypsy", "broker"])
        self.assertEqual(order(emu_memory.GYPSY), ["broker", "gypsy"])
        self.assertEqual(order(0x41), ["broker", "gypsy"])
        self.assertEqual({kind for kind, _ in emu_memory.tents_order(0)}, {emu_memory.BROKER, emu_memory.GYPSY})

    def test_the_tents_report_takes_them_in_either_order(self):
        talk = {"actor": "Ev_Broker2", "talked": True, "ended": True, "pages": 5, "choices": [], "problems": []}

        def tent(what, kind, outside, scene, last):
            first = self.session(what + "-set", 7, facts={"special": kind}, inside=None)
            visit = self.session(what, scene, facts={"special": kind, "where": {outside: [1, 6, 5, 5]}}, talk=talk,
                                 inside={"arena": [490000, 495000, 396000]}, saved=None if last else
                                 {"spoken": True, "left": True, "pages": 3})
            return [first, visit]
        for broker_last in (False, True):
            broker = tent("broker", emu_memory.BROKER, "BrShop", 12, broker_last)
            gypsy = tent("gypsy", emu_memory.GYPSY, "Buggy", 18, not broker_last)
            runs = gypsy + broker if broker_last else broker + gypsy
            self.assertEqual(self.reported({"tents": runs}), [])
        # the game set another kind than the one asked for: both of that tent's checks fail
        wrong = tent("broker", 0x41, "BrShop", 12, False)[:1]
        self.assertEqual(len(self.reported({"tents": wrong + gypsy})), 2)

    def walked(self, labels, walks, crossings=(), snow=("winter",)):
        """The report of the towns walked into every block from every side, over made-up tours and walks: the
        checks that failed. crossings: {a walk's label: [(to, largest free block)]} besides a sound one."""
        count = {"fails": 0, "failed": 0, "least": 80000, "calls": 600, "most": 250720, "made": {}}
        blocks = ["%d,%d" % (x, z) for z in range(1, 7) for x in range(1, 6)]

        def tour(label):
            loaded = ["House"] + (["Snowman"] if label in snow else [])
            return {"label": label, "loaded": loaded,       # the snowballs lie in the last block
                    "blocks": {b: {"largest": 90000, "low": 89000, "loaded": loaded if b == blocks[-1] else ["House"]}
                               for b in blocks}}

        def walk(label):
            ways = [("1,1", 88000)] + list(dict(crossings).get(label, ()))
            return {"label": label, "arrived": True, "hang": None, "closed": [], "stops": [], "made": [],
                    "watch": dict(count), "before": dict(count),
                    "crossings": [{"from": "2,1", "to": to, "largest": free, "low": free - 900, "loaded": ["House"]}
                                  for to, free in ways]}
        emu_memory.failures.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            emu_memory.report_edges([walk(w) for w in walks], walks, [tour(lb) for lb in labels], labels,
                                    lambda loaded, today: (51932 if "Snowman" in today else 34480, []))
        failed = list(emu_memory.failures)
        emu_memory.failures.clear()
        return failed

    def test_every_date_of_snow_is_walked_every_way(self):
        dates = ["winter", "summer"]
        self.assertEqual(self.walked(dates, ["winter", "summer"]), [])
        # the tour of a date met the snowballs and the date is not walked every way; or no date without snow is
        self.assertEqual(len(self.walked(dates, ["summer"])), 1)
        self.assertEqual(len(self.walked(dates, ["winter"])), 1)
        self.assertEqual(len(self.walked(dates, ["winter", "summer"], snow=("winter", "summer"))), 1)
        # a way in under the floor; one that does not keep the reserve with all that can come on top
        low = self.walked(dates, ["winter", "summer"], {"summer": [("3,2", emu_memory.FLOOR - 16)]})
        self.assertEqual(len(low), 1)
        tight = self.walked(dates, ["winter", "summer"], {"winter": [("3,2", 51932 + emu_memory.RESERVE - 16)]})
        self.assertEqual(len(tight), 1)
        self.assertEqual(self.walked(dates, ["winter", "summer"], {"winter": [("3,2", 51932 + emu_memory.RESERVE)]}), [])
        # the dates the check walks: each one of the year's, and more than one
        self.assertTrue(set(emu_memory.EDGE_DATES) <= {label for label, _, _ in emu_year.EVENTS})
        self.assertGreater(len(emu_memory.EDGE_DATES), 1)

    def test_an_animal_has_met_the_player_once_its_memory_holds_them(self):
        save = bytearray(0x10000)
        save[emu_job.PRIVATE:emu_job.PRIVATE + 0x10] = b"cea   cea   " + bytes.fromhex("f066306a")
        at, size = emu_job.emu_town.ANIMALS_AT, emu_job.emu_town.ANIMAL_SIZE
        for i, npc in enumerate((0xE037, 0xE016, 0xE0CA)):
            save[at + i * size:at + i * size + 2] = struct.pack(">H", npc)
        self.assertEqual(emu_job.acquainted(save), set())
        save[at + 0x10:at + 0x20] = save[emu_job.PRIVATE:emu_job.PRIVATE + 0x10]             # the first's first memory
        save[at + 2 * size + 0x0C + 0xB0 * 3 + 4:][:0] = b""
        third = at + 2 * size + 0x0C + 0xB0 * 3 + 4                                          # the third's fourth
        save[third:third + 0x10] = save[emu_job.PRIVATE:emu_job.PRIVATE + 0x10]
        self.assertEqual(emu_job.acquainted(save), {0xE037, 0xE0CA})
        # the player's name in an animal's home or after it is no memory
        save[at + size + emu_job.emu_town.ANIMAL_HOME:][:0] = b""
        home = at + size + emu_job.emu_town.ANIMAL_HOME
        save[home:home + 0x10] = save[emu_job.PRIVATE:emu_job.PRIVATE + 0x10]
        self.assertEqual(emu_job.acquainted(save), {0xE037, 0xE0CA})

    def job(self, **more):
        """The first job played, as tests/emu_job.py's report reads it: (the job, the flash powered on again,
        what the flash holds), nothing amiss."""
        j = emu_job
        count = {"fails": 0, "failed": 0, "least": 80000, "calls": 4000, "most": 250720, "made": {}}

        def chore(kind, **what):
            return dict({"kind": kind, "nook": "free", "open": True, "for": 0, "done": True}, **what)
        chores = [chore(j.CLOTHE, worn=True, kept=True), chore(j.PLANT, bags=10, planted=10, tries=11),
                  chore(j.GREET, met=6, animals=6), chore(j.FURNITURE, found=True, ended="free", menus=0, **{"for": 0xE037}),
                  chore(j.LETTER, written=True, mailed=True, **{"for": 0xE0D0}), chore(j.VILLAGERS, asked=1, found=True),
                  chore(j.CARPET, found=True, ended="free", menus=1, **{"for": 0xE016}),
                  chore(j.AXE, found=True, ended="free", menus=2, **{"for": 0xE0D0}), chore(j.NOTICE, board=True)]
        played = {"house": True, "chores": chores, "over": "free", "hang": None, "pages": 240, "messages": 70,
                  "problems": [], "watch": dict(count), "made": [], "animals": 6, "asked": 40, "menus": 5,
                  "gyroid": {"spoken": True, "asked": 2, "left": True, "pages": 5}, "flash": "job.fla",
                  "trade": {"in": True, "sold": True, "bought": True, "wallet": [0, 325, 205]},
                  "paid": {"in": True, "wallet": [205, 105]},
                  "end": {"quest": True, "flags": 0, "scene": 7, "pockets": [0x2401], "clothes": 0x2410, "keeps": True},
                  "letter": {"bytes": "aa" * 122, "key": "1368ac01", "rest": "63656d6f6b"}}
        for key, value in more.items():
            played[key] = dict(played[key], **value) if isinstance(value, dict) and isinstance(played.get(key), dict) \
                else value
        again = self.session("again", 20, pages=7)
        held = {"animal": True, "place": False, "rest": "63656d6f6b"}
        return played, again, {"name": "636561", "flags": 0, "clothes": 0x2410, "letter": [dict(held), dict(held)]}

    def job_failed(self, played, again, kept):
        emu_job.failures.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            emu_job.report(played, again, kept)
        failed = list(emu_job.failures)
        emu_job.failures.clear()
        return failed

    def test_the_first_job_is_held_to_each_chore_and_to_its_end(self):
        j = emu_job
        self.assertEqual(self.job_failed(*self.job()), [])
        # every check fails when the job was not played: the house, nine chores, its end, the Bells, the
        # payment, the pages, the gyroid, the flash again, the letter an animal keeps
        self.assertEqual(len(self.job_failed(None, None, None)), 17)

        def with_chore(kind, **what):
            played, again, kept = self.job()
            played["chores"] = [dict(c, **what) if c["kind"] == kind else c for c in played["chores"]]
            return played, again, kept
        for kind, what in ((j.CLOTHE, {"worn": False}), (j.CLOTHE, {"kept": False}), (j.PLANT, {"planted": 9}),
                           (j.GREET, {"met": 5}), (j.FURNITURE, {"found": False}), (j.CARPET, {"ended": "menu"}),
                           (j.LETTER, {"written": False}), (j.LETTER, {"mailed": False}), (j.VILLAGERS, {"asked": 0}),
                           (j.AXE, {"done": False}), (j.NOTICE, {"board": False}), (j.PLANT, {"nook": "not into the shop"})):
            self.assertEqual(len(self.job_failed(*with_chore(kind, **what))), 1, (kind, what))
        # a chore never given: its own check and the order's
        played, again, kept = self.job()
        played["chores"] = [c for c in played["chores"] if c["kind"] != j.AXE]
        self.assertEqual(len(self.job_failed(played, again, kept)), 2)
        # a chore tried twice is judged by its last try
        played, again, kept = self.job()
        first = dict(next(c for c in played["chores"] if c["kind"] == j.CARPET), done=False, found=False)
        at = [c["kind"] for c in played["chores"]].index(j.CARPET)
        played["chores"].insert(at, first)
        self.assertEqual(self.job_failed(played, again, kept), [])

    def test_the_first_jobs_end_its_bells_and_its_save(self):
        ok = self.job()
        for more in ({"end": {"flags": 4}}, {"end": {"quest": False}}, {"end": {"scene": 9}}, {"over": "limit"},
                     {"trade": {"sold": False}}, {"trade": {"bought": False}}, {"trade": {"wallet": [0, 0, 0]}},
                     {"trade": None}, {"paid": None}, {"paid": {"wallet": [205, 205]}}, {"paid": {"in": False}},
                     {"problems": ["0x7f4 at 0: a line past its bubble"]}, {"watch": {"fails": 1}},
                     {"gyroid": {"asked": 0, "left": False}}, {"gyroid": None}, {"hang": "no frame"}):
            self.assertEqual(len(self.job_failed(self.job(**more)[0], ok[1], ok[2])), 1, more)
        # the flash powered on again: the town must load, the house be gone into, the gyroid save, and the save
        # be the one of the player who did the job
        played, again, kept = ok
        self.assertEqual(len(self.job_failed(played, None, kept)), 1)
        self.assertEqual(len(self.job_failed(played, again, None)), 2)       # and the letter's two flashes
        self.assertEqual(len(self.job_failed(played, again, dict(kept, flags=4))), 1)
        self.assertEqual(len(self.job_failed(played, again, dict(kept, clothes=0x2401))), 1)
        self.assertEqual(len(self.job_failed(played, dict(again, saved=None), kept)), 1)
        self.assertEqual(len(emu_job.CHORES), 9)

    def test_the_letter_an_animal_keeps_holds_its_rest(self):
        played, again, kept = self.job()
        self.assertEqual(self.job_failed(played, again, kept), [])
        gone = {"animal": True, "place": False, "rest": None}
        whole = kept["letter"][0]
        # the rest gone from a flash (the fault: no letter place of the save holds the letter an animal keeps)
        self.assertEqual(len(self.job_failed(played, again, dict(kept, letter=[whole, gone]))), 1)
        self.assertEqual(len(self.job_failed(played, again, dict(kept, letter=[gone, whole]))), 1)
        self.assertEqual(len(self.job_failed(played, again, dict(kept, letter=[whole]))), 1)
        # not as it was written; the animal without the letter; a letter place still holding it proves nothing
        for what in ({"rest": "636561"}, {"animal": False}, {"place": True}):
            self.assertEqual(len(self.job_failed(played, again, dict(kept, letter=[whole, dict(whole, **what)]))), 1)
        # a letter no longer than the cartridge keeps proves nothing either, nor one no animal had at the end
        self.assertEqual(len(self.job_failed(self.job(letter={"rest": None})[0], again, kept)), 1)
        self.assertEqual(len(self.job_failed(self.job(letter=None)[0], again, kept)), 1)
        self.assertEqual(len(self.job_failed(self.job(end={"keeps": False})[0], again, kept)), 1)
        ok, said = emu_job.letter_kept(played, kept)
        self.assertTrue(ok and "101 bytes of body" in said and "the 5 past them" in said, said)
        # keeping(): the letter in an animal's memory, in a letter place, its rest in the extension
        letter = {"bytes": bytes(range(1, 123)).hex(), "key": "%08x" % emu_letters.fnv(bytes(range(1, 123))),
                  "rest": "72657374"}
        save = bytearray(0x10000)
        self.assertEqual(emu_job.keeping(save, b"", letter), {"animal": False, "place": False, "rest": None})
        at = emu_job.emu_town.ANIMALS_AT + 3 * emu_job.emu_town.ANIMAL_SIZE + 0x47
        save[at:at + 122] = bytes(range(1, 123))
        ext = emu_letters.Ext()
        ext.records = [(emu_letters.fnv(bytes(range(1, 123))), b"", b"rest", b"")]
        self.assertEqual(emu_job.keeping(save, ext.bytes(), letter), {"animal": True, "place": False, "rest": "72657374"})
        place = emu_letters.SAVE_MAIL[41] + emu_job.KEY_BYTES[0]
        save[place:place + 122] = bytes(range(1, 123))
        self.assertTrue(emu_job.keeping(save, ext.bytes(), letter)["place"])
        self.assertEqual(emu_job.keeping(save, ext.bytes(), None), {"animal": False, "place": False, "rest": None})

    def trip(self):
        """A trip with the Controller Pak as tests/emu_trip.py's report reads it: [towns, home, away, pak,
        visit, other, back, kept, store, lying, fetch, whole, copies], nothing amiss."""
        count = {"fails": 0, "failed": 0, "least": 85000, "calls": 600, "most": 250720, "made": {}}
        me, you = "636561202020636561202020f066306a", "6b6f6d202020656b6d202020f066306a"
        key, rest, mail = "1368ac01", "63656d6f6b", "aa" * 0xA4
        got, whole, mail2 = "2ab7d99e", ["6162", "63" * 59, "64" * 7], "bb" * 0xA4     # one the game delivered
        pockets = [0x2401, 0x13D0]

        def facts(**what):
            given = dict({"name": me[:12], "id": me, "exists": 1, "pockets": list(pockets), "letters": [key],
                          "mail": [mail], "rests": {key: rest}, "trips": [], "held": [], "marked": []}, **what)
            given.setdefault("whole", {k: ["", r, ""] for k, r in given["rests"].items()})
            return given

        def both(**what):
            """The facts with the delivered letter's record in the extension too."""
            given = facts(**what)
            given["rests"] = dict(given["rests"], **{got: whole[1]})
            given["whole"] = dict(given["whole"], **{got: list(whole)})
            return given

        def run(label, **what):
            return dict({"label": label, "arrived": True, "hang": None, "pages": 18, "problems": [],
                         "watch": dict(count), "made": [], "asked": 1, "met": None, "written": None,
                         "talked": None, "left": None, "saved": None, "stored": None, "fetched": None}, **what)
        towns = {"home": {"town": "home.town", "settled": True, "said": "", "id": me, "sound": True},
                 "other": {"town": "other.town", "settled": True, "said": "", "id": you, "sound": True}}
        home = run("home", asked=2, now=facts(number=0, record="the save's", letters=[], mail=[], rests={}),
                   met={"animal": 0xE037, "ended": "free", "known": True},
                   written={"key": key, "mail": mail, "rest": rest, "paper": False},
                   before=facts(number=0, record="the save's"), left={"spoken": True, "gone": True})
        away = facts(exists=0, pockets=[], letters=[], mail=[], trips=[0], held=[key])
        pak = facts(rests={})
        visit = run("visit", now=facts(number=4, record="a visitor's", rests={}),
                    talked={"animal": 0xE037, "ended": "free", "pages": 4},
                    before=facts(number=4, record="a visitor's", rests={}), left={"spoken": True, "gone": True})
        other = facts(name=you[:12], id=you, pockets=[], letters=[], mail=[], rests={})
        back = run("back", now=facts(number=0, record="the save's"), before=facts(number=0, record="the save's"),
                   saved={"spoken": True, "asked": 2, "left": True, "pages": 5})
        done = {"spoken": True, "asked": 2, "left": True, "pages": 5}
        here = both(number=0, record="the save's")                                    # the letter in the mailbox
        taken = both(number=0, record="the save's", letters=[key, got], mail=[mail, mail2])
        gone = both(number=0, record="the save's", marked=[got])                      # the letter on the Pak
        store = run("store", asked=2, now=here, before=gone, saved=dict(done), mail={
            "before": {"pockets": [key], "box": [got]}, "menu": True, "closed": True,
            "after": {"pockets": [key, got], "box": []}, "now": taken}, stored={
            "before": taken, "spoken": True, "menu": True,
            "letter": {"place": 1, "key": got, "mail": mail2, "kind": 0, "rest": list(whole)},
            "opened": {"pockets": [key, got], "store": []}, "taken": {"pockets": [key], "store": []},
            "put": {"pockets": [key], "store": [[1, got]]}, "closed": True, "ended": "free", "asked": [4, 4],
            "now": gone, "everywhere": [key], "out": True})
        lying = both(marked=[got])
        back_in = both(number=0, record="the save's", letters=[key, got], mail=[mail, mail2], marked=[got])
        fetch = run("fetch", asked=2, now=gone, before=back_in, saved=dict(done), fetched={
            "before": gone, "spoken": True, "menu": True, "letter": None,
            "opened": {"pockets": [key], "store": [[1, got]]}, "taken": {"pockets": [key], "store": []},
            "put": {"pockets": [key, got], "store": []}, "closed": True, "ended": "free", "asked": [4, 4],
            "now": back_in, "everywhere": [key, got], "out": True})
        return [towns, home, away, pak, visit, other, back, facts(), store, lying, fetch,
                both(letters=[key, got], mail=[mail, mail2], marked=[got]), [0, 1, 0]]

    def trip_failed(self, parts):
        emu_trip.failures.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            emu_trip.report(*parts)
        failed = list(emu_trip.failures)
        emu_trip.failures.clear()
        return failed

    def test_a_trip_is_held_to_the_letter_whole_at_home_again(self):
        self.assertEqual(self.trip_failed(self.trip()), [])
        self.assertEqual(len(self.trip_failed([None] * 8)), 16)      # nothing played: every check fails
        self.assertEqual(len(self.trip_failed(self.trip()[:8])), 5)  # the trip alone: the Pak's letters not played
        TOWNS, HOME, AWAY, PAK, VISIT, OTHER, BACK, KEPT, STORE, LYING, FETCH, WHOLE, COPIES = range(13)

        def failed(part, *path, **what):
            """How many checks fail with one thing of a part changed (path: the dicts down to it)."""
            parts = self.trip()
            at = parts[part]
            for key in path:
                at[key] = dict(at[key])
                at = at[key]
            at.update(what)
            return len(self.trip_failed(parts))
        # the fault the check was written for: the game takes the traveller's letters out of the save, and
        # the extension dropped their rests with them. Away: the flash without the rest, without the record
        # of the trip, or the rest filed after it; home again: the rest not there, or the record still there
        self.assertEqual(failed(AWAY, rests={}), 1)
        self.assertEqual(failed(AWAY, trips=[]), 1)
        self.assertEqual(failed(AWAY, trips=[1]), 1)                                     # another player's
        self.assertEqual(failed(AWAY, held=[]), 1)
        self.assertEqual(failed(BACK, "now", rests={}), 1)
        self.assertEqual(failed(BACK, "now", rests={"1368ac01": "636561"}), 1)           # not as it was written
        self.assertEqual(failed(BACK, "now", trips=[0]), 1)
        self.assertEqual(failed(KEPT, trips=[0]), 1)
        self.assertEqual(failed(KEPT, rests={}), 1)
        self.assertEqual(failed(KEPT, letters=[]), 1)
        # the letter: written, longer than the cartridge keeps, the paper used
        self.assertEqual(failed(HOME, "written", paper=True), 1)
        self.assertEqual(failed(HOME, "met", known=False), 1)
        # ... a letter with nothing past the cartridge's 96 bytes proves nothing: its own check and the three
        # that hold its rest on the trip; and the gyroid's save at the post office, which holds it again
        self.assertEqual(failed(HOME, "written", rest=None), 5)
        # the departure: the game must have left, the player be away in the flash, the Pak hold the record
        self.assertEqual(failed(HOME, "left", gone=False), 1)
        self.assertEqual(failed(AWAY, exists=1), 1)
        self.assertEqual(failed(AWAY, letters=["1368ac01"]), 1)
        self.assertEqual(failed(PAK, letters=[]), 1)
        # the other town: a visitor, the Pak's things, one question; the rest not there; its own player kept
        self.assertEqual(failed(VISIT, "now", number=0), 1)
        self.assertEqual(failed(VISIT, "now", record="the save's"), 1)
        self.assertEqual(failed(VISIT, "now", pockets=[]), 1)
        self.assertEqual(failed(VISIT, asked=2), 1)
        self.assertEqual(failed(VISIT, "talked", ended="limit"), 1)
        self.assertEqual(failed(VISIT, "now", rests={"1368ac01": "63656d6f6b"}), 1)
        self.assertEqual(failed(OTHER, rests={"1368ac01": "63656d6f6b"}), 1)
        self.assertEqual(failed(OTHER, exists=0), 1)
        self.assertEqual(failed(VISIT, "left", spoken=False), 1)
        # home again: a resident, the things back
        self.assertEqual(failed(BACK, "now", number=4), 1)
        self.assertEqual(failed(BACK, "now", exists=0), 1)
        self.assertEqual(failed(BACK, "now", mail=[]), 1)
        self.assertEqual(failed(BACK, "saved", left=False), 1)
        # the mailbox: its letters must come to the pockets, each with its rest (a town made without the
        # extension the game had filed starts with them cut: the fault this step found in the checks' towns)
        got, mail2 = "2ab7d99e", "bb" * 0xA4
        self.assertEqual(failed(STORE, "mail", menu=False), 1)
        self.assertEqual(failed(STORE, "mail", closed=False), 1)
        self.assertEqual(failed(STORE, "mail", "after", pockets=["1368ac01"]), 1)        # nothing taken
        self.assertEqual(failed(STORE, "mail", "after", box=[got]), 1)                   # left in the mailbox
        self.assertEqual(failed(STORE, "mail", "now", whole={"1368ac01": ["", "63656d6f6b", ""]}), 1)   # no rest
        # the fault the two sessions at the post office were written for: the letter in the Controller Pak's
        # store, and the extension dropping its rest at the save the game makes then. The rest gone, or kept
        # without the Pak's mark (the next save would drop it), or not whole (its header's, its footer's);
        # the gyroid's save; and the letter back
        mine = {"1368ac01": ["", "63656d6f6b", ""]}
        self.assertEqual(failed(STORE, "stored", "now", whole=dict(mine)), 1)
        self.assertEqual(failed(STORE, "stored", "now", marked=[]), 1)
        self.assertEqual(failed(STORE, "stored", "now", whole=dict(mine, **{got: ["6162", "63" * 59, ""]})), 1)
        self.assertEqual(failed(STORE, "stored", "now", whole=dict(mine, **{got: ["", "63" * 59, "64" * 7]})), 1)
        self.assertEqual(failed(LYING, whole=dict(mine)), 1)
        self.assertEqual(failed(LYING, marked=[]), 1)
        self.assertEqual(failed(LYING, letters=["1368ac01", got]), 1)                    # still in the pockets
        self.assertEqual(failed(LYING, letters=[]), 1)                                   # the player's own gone
        self.assertEqual(failed(FETCH, "fetched", "now", whole=dict(mine)), 1)
        self.assertEqual(failed(FETCH, "fetched", "now", mail=["aa" * 0xA4, "cc" * 0xA4]), 1)   # another's text
        self.assertEqual(failed(WHOLE, whole=dict(mine)), 1)
        self.assertEqual(failed(WHOLE, letters=["1368ac01"]), 1)
        # ... the letter's kind is not part of its text: the letter back is the letter stored
        other_kind = "bb" * 0x26 + "03" + "bb" * (0xA4 - 0x27)
        self.assertEqual(failed(FETCH, "fetched", "now", mail=["aa" * 0xA4, other_kind]), 0)
        # the letter stored: one the player received, whose key no other letter of the save has (a copy in
        # the post office's keep would have the rest kept for its sake, and the mark prove nothing)
        self.assertEqual(failed(STORE, "stored", "letter", kind=1), 1)
        self.assertEqual(failed(STORE, "stored", everywhere=["1368ac01", got]), 1)
        self.assertEqual(failed(STORE, "stored", letter=None), 3)                        # none to store
        self.assertEqual(failed(STORE, "stored", "letter", rest=None), 3)                # one with no rest
        # the menu: the letter must leave the pockets for the hand and the hand for the store's place, the
        # menu close, the talk end, and the Controller Pak's file gain the letter's text and lose it again
        self.assertEqual(failed(STORE, "stored", "taken", pockets=["1368ac01", got]), 1)
        self.assertEqual(failed(STORE, "stored", "put", store=[]), 1)
        self.assertEqual(failed(STORE, "stored", "put", store=[[2, got]]), 1)
        self.assertEqual(failed(STORE, "stored", closed=False), 1)
        self.assertEqual(failed(STORE, "stored", menu=False), 1)
        self.assertEqual(failed(STORE, "stored", ended="limit"), 1)
        self.assertEqual(failed(STORE, "saved", left=False), 1)
        self.assertEqual(failed(FETCH, "fetched", "opened", store=[]), 1)                # not read from the Pak
        self.assertEqual(failed(FETCH, "fetched", "put", pockets=["1368ac01"]), 1)
        self.assertEqual(failed(FETCH, "fetched", closed=False), 1)
        self.assertEqual(failed(FETCH, "saved", left=False), 1)
        parts = self.trip()
        parts[COPIES] = [0, 0, 0]                                                        # never written to the Pak
        self.assertEqual(len(self.trip_failed(parts)), 1)
        parts[COPIES] = [0, 1, 1]                                                        # never taken off it
        self.assertEqual(len(self.trip_failed(parts)), 1)
        parts[COPIES] = [1, 2, 1]                                                        # a trip's file with it too
        self.assertEqual(len(self.trip_failed(parts)), 0)
        said = io.StringIO()
        parts = self.trip()
        parts[STORE]["stored"]["now"] = dict(parts[STORE]["stored"]["now"], whole=dict(mine))
        emu_trip.failures.clear()
        with contextlib.redirect_stdout(said):
            emu_trip.report(*parts)
        emu_trip.failures.clear()
        self.assertIn("COMES BACK FROM THE PAK CUT AT ITS 96TH BYTE", said.getvalue())
        # a page with a fault, a hang, the arena saying no: the session's checks and the last one
        self.assertEqual(failed(STORE, problems=["0x1be7 at 0: a line past its bubble"]), 2)
        self.assertEqual(failed(FETCH, hang="no frame"), 2)
        self.assertEqual(failed(VISIT, problems=["0x7dd at 0: a line past its bubble"]), 4)
        self.assertEqual(failed(BACK, hang="no frame"), 2)
        self.assertEqual(failed(HOME, "watch", fails=1), 3)
        # the two towns must differ in their names: the same player of the same town is a resident
        self.assertEqual(failed(TOWNS, "other", id="636561202020636561202020f066306a"), 2)

    def test_a_players_record_and_an_extension_read_for_the_trip(self):
        private = bytearray(emu_trip.PRIVATE_SIZE)
        private[:0x10] = bytes.fromhex("636561202020636561202020f066306a")
        private[emu_trip.EXISTS] = 1
        private[emu_trip.POCKETS:emu_trip.POCKETS + 4] = struct.pack(">2H", 0x2401, 0x2000)
        private[emu_trip.MAIL:emu_trip.MAIL + 10 * emu_trip.MAIL_SIZE] = b" " * (10 * emu_trip.MAIL_SIZE)
        letter = bytes(range(emu_trip.MAIL_SIZE))
        at = emu_trip.MAIL + 3 * emu_trip.MAIL_SIZE
        private[at:at + emu_trip.MAIL_SIZE] = letter
        key = emu_letters.mail_key(letter)
        ext = emu_letters.Ext()
        ext.records = [(key, b"", b"rest", b""), (7, b"", b"older", b""), (emu_trip.HOLD_KEY + 2, b"", b"\0", b""),
                       (9, b"", b"filed after", b"")]
        got = emu_trip.facts(private, ext.bytes())
        self.assertEqual((got["exists"], got["pockets"], got["letters"], got["mail"]),
                         (1, [0x2401, 0x2000], ["%08x" % key], [letter.hex()]))
        # the record of a trip is no rest; what was filed before it is kept by it
        self.assertEqual(got["rests"], {"%08x" % key: b"rest".hex(), "00000007": b"older".hex(),
                                        "00000009": b"filed after".hex()})
        self.assertEqual((got["trips"], got["held"]), ([2], ["%08x" % key, "00000007"]))
        ext.records.append((emu_trip.HOLD_KEY, b"", b"\0", b""))            # two away: the last one's line
        got = emu_trip.facts(private, ext.bytes())
        self.assertEqual((got["trips"], got["held"]), ([2, 0], ["%08x" % key, "00000007", "00000009"]))
        self.assertEqual(emu_trip.facts(private, b"")["rests"], {})
        self.assertEqual(emu_trip.facts(private, b"")["trips"], [])
        self.assertEqual(emu_trip.held(private), [letter])
        # the Controller Pak's mark on a record: a bit of its first length, and no part of its rest
        self.assertEqual(got["marked"], [])
        ext.hold({key, 9, 1234})
        got = emu_trip.facts(private, ext.bytes())
        self.assertEqual(got["marked"], ["%08x" % key, "00000009"])
        self.assertEqual(got["rests"]["%08x" % key], b"rest".hex())
        self.assertEqual(emu_trip.facts(private, b"")["marked"], [])
        # the Controller Pak's file: how many times it holds a letter's text
        with tempfile.TemporaryDirectory() as work:
            self.assertEqual(emu_trip.pak_copies(work, letter.hex()), 0)
            text = letter[emu_trip.TEXT_AT:emu_trip.TEXT_AT + emu_trip.TEXT_SIZE]
            self.assertEqual(len(text), 122)
            with open(os.path.join(work, "a.mpk"), "wb") as f:
                f.write(bytes(0x300) + letter + bytes(0x100) + b"\x03" + text + bytes(7))
            self.assertEqual(emu_trip.pak_copies(work, letter.hex()), 2)
            self.assertEqual(emu_trip.pak_copies(work, (b"\x55" * emu_trip.MAIL_SIZE).hex()), 0)
            self.assertEqual(emu_trip.pak_copies(work, ""), 0)

    def test_the_extension_keeps_what_the_controller_pak_holds(self):
        """tests/emu_letters.py's model of the extension, as m_handbill.c files and collects: a record the
        Controller Pak's store has held is marked, and the collection leaves it alone."""
        def letter(n, body=100):
            return bytes([n]) * 24, bytes([n]) * body + b"\xcd" * (192 - body), bytes([n]) * 16 + b" " * 16

        def key(n):
            return emu_letters.fnv(bytes([n]) * 10 + bytes([n]) * 96 + bytes([n]) * 16)
        ext = emu_letters.Ext()
        for n in (1, 2, 3):
            ext.file(*letter(n), save_keys=set())
        self.assertEqual([r[0] for r in ext.records], [key(1), key(2), key(3)])
        ext.hold({key(3), key(9)})                       # the store written with the third in it, and one unknown
        self.assertEqual(ext.held, {key(3)})
        raw = ext.bytes()
        first = [raw[at + 4] for at in (2, 2 + 7 + 18, 2 + 2 * (7 + 18))]
        self.assertEqual(first, [14, 14, 14 | emu_letters.PAK_HELD])
        again = emu_letters.Ext.parse(raw)
        self.assertEqual((again.records, again.held, again.bytes()), (ext.records, ext.held, raw))
        self.assertEqual(again.rest(key(3)), (bytes([3]) * 14, bytes([3]) * 4, b""))
        ext.collect({key(1)})                            # the save holds the first; the Pak the third
        self.assertEqual([r[0] for r in ext.records], [key(1), key(3)])
        ext.collect(set())
        self.assertEqual([r[0] for r in ext.records], [key(3)])
        ext.file(*letter(3, body=120), save_keys=set())  # the same letter made again: the mark stays with its key
        self.assertEqual((ext.held, ext.rest(key(3))[1]), ({key(3)}, bytes([3]) * 24))
        ext.file(bytes([3]) * 10 + b" " * 14, bytes([3]) * 96 + b"\xcd" * 96, bytes([3]) * 16 + b" " * 16, set())
        self.assertEqual((ext.records, ext.held), ([], set()))        # nothing past the cartridge's: no record
        # the extension full: the oldest goes, marked or not, and its mark with it
        ext = emu_letters.Ext()
        ext.file(*letter(1, body=192), save_keys=set())
        ext.hold({key(1)})
        n = 2
        while ext.size() + 7 + 14 + 96 <= emu_letters.EXT_CAP:
            ext.file(*letter(n, body=192), save_keys={key(m) for m in range(2, n + 1)})
            n += 1
        self.assertEqual((ext.records[0][0], ext.held), (key(1), {key(1)}))
        ext.file(*letter(n, body=192), save_keys={key(m) for m in range(2, n + 1)})
        self.assertEqual((ext.records[0][0], ext.held), (key(2), set()))
        self.assertLessEqual(ext.size(), emu_letters.EXT_CAP)

    def test_the_gyroid_is_tried_again_when_the_talk_was_somebody_elses(self):
        """tests/emu_memory.py's save_and_stop: an animal by the houses that has not met the player has its
        word first, and the gyroid's save is asked for again."""
        gyroid = {"name": emu_reset.GYROID, "pos": (2060.0, 160.0, 1500.0)}
        far = {"name": emu_reset.GYROID, "pos": (2420.0, 160.0, 1780.0)}

        class Town:
            def __init__(self, there=True, here=True):
                self.there, self.here, self.left = there, here, False

            def go_block(self, block, blocked):
                return self.there

            def actors(self):
                return ([far, gyroid] if self.here else []) + ([{"name": emu_reset.TITLE, "pos": (0, 0, 0)}]
                                                              if self.left else [])

        def played(talks, tries=3, **town):
            """save_and_stop over talks: each (the pages it reads, the choices it gives, whether the game
            then leaves). What it answers, the pages handed on, the gyroids walked to."""
            t, pages, walked, left = Town(**town), [], [], iter(talks)
            real = emu_reset.approach, emu_town.follow

            def approach(town, actor):
                walked.append(actor)
                return True

            def follow(town, answers=(), limit=0, quiet=0, pages=None, choices=None):
                said, asked, gone = next(left)
                pages.extend({"msg": m} for m in said)
                town.left = gone
                return asked
            emu_reset.approach, emu_town.follow = approach, follow
            try:
                return emu_memory.save_and_stop(t, emu_town.MAT, pages, tries), pages, walked
            finally:
                emu_reset.approach, emu_town.follow = real
        save = ([emu_reset.GYROID_ASK, 0x92D, 0x2B0F], 2, True)
        animal = ([0x2D52, 0x2D52, 0x2D52], 0, False)
        out, pages, walked = played([save])
        self.assertEqual(out, {"spoken": True, "asked": 2, "left": True, "pages": 3, "tried": 1})
        self.assertEqual(walked, [gyroid])                   # the one by the first house's mat, not the nearest listed
        out, pages, walked = played([animal, save])          # the fault of the day after the shop's renewal
        self.assertEqual(out, {"spoken": True, "asked": 2, "left": True, "pages": 6, "tried": 2})
        self.assertEqual([p["msg"] for p in pages], animal[0] + save[0])     # the animal's pages are judged too
        out, pages, walked = played([animal, animal, animal, save])
        self.assertEqual((out["left"], out["tried"], out["pages"], len(walked)), (False, 3, 9, 3))
        # where no save is expected (a new player's gyroid) it is spoken to once
        out, pages, walked = played([animal, save], tries=1)
        self.assertEqual(out, {"spoken": True, "asked": 0, "left": False, "pages": 3, "tried": 1})
        self.assertEqual(played([save], there=False)[0], {"spoken": False, "asked": 0, "left": False, "pages": 0, "tried": 0})
        self.assertEqual(played([save], here=False)[0], {"spoken": False, "asked": 0, "left": False, "pages": 0, "tried": 0})

    def newcomer(self, **more):
        """The answer of the road of a player who comes later, as the report reads it: nothing amiss."""
        count = {"fails": 0, "failed": 0, "least": 60000, "calls": 500, "most": 250720, "made": {}}

        def door(x, z, mine):
            return {"house": [x, z], "inside": True, "scene": 20, "arena": [426144, 426144, 465072], "out": True,
                    "asked": 3 if mine else 0, "mine": mine}

        def stop(scene, block, free, guide):
            return {"scene": scene, "block": block, "arena": [free, free, 800000], "low": free - 500,
                    "loaded": ["Intro_Demo", "MyHouse"], "guide": guide}
        run = {"label": "newcomer", "clock": [2002, 1, 1, 10, 0, 0], "players": 3, "arrived": True, "hang": None,
               "trail": [0, 33, 27, 16, 7, 20, 7, 20, 7, 34], "dials": 2, "choices": [[2, 0], [4, 3], [2, 0]],
               "pages": 90, "problems": [],
               "stops": {"station": stop(7, [3, 1], 100000, False), "houses": stop(7, [3, 2], 76000, True),
                         "end": stop(7, [3, 2], 76000, False)},
               "doors": [door(2080, 1440, False), door(2400, 1440, True)],
               "gyroid": {"spoken": True, "asked": 0, "left": False, "pages": 3, "owner": True},
               "watch": dict(count), "before": dict(count), "made": ["Intro_Demo", "Npc_Guide2", "Player"],
               "scenes": {"7": []}}
        for key, value in more.items():
            run[key] = dict(run[key], **value) if isinstance(value, dict) and isinstance(run.get(key), dict) else value
        return run

    def newcomer_failed(self, run, on_top=51932):
        emu_memory.failures.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            emu_memory.report_newcomer(run, lambda loaded, today: (on_top, []), ["Snowman"])
        failed = list(emu_memory.failures)
        emu_memory.failures.clear()
        return failed

    def test_a_player_who_comes_later_goes_the_games_own_road(self):
        self.assertEqual(self.newcomer_failed(self.newcomer()), [])
        self.assertEqual(len(self.newcomer_failed(None)), 3)                                # the road not gone
        # the select's second choice: the players' names and one more, the last taken
        self.assertEqual(len(self.newcomer_failed(self.newcomer(choices=[[2, 0], [4, 0]]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(choices=[[2, 0], [3, 3]]))), 1)
        # the first player's name typed again must be refused: the dial comes twice
        self.assertEqual(len(self.newcomer_failed(self.newcomer(dials=1))), 1)
        # the train of a town that is there, its guide, the arrival's actor
        self.assertEqual(len(self.newcomer_failed(self.newcomer(trail=[0, 33, 27, 15, 7]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(trail=[0, 33, 16, 27, 7]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(made=["Intro_Demo", "Npc_Guide"]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(before={"fails": 1}))), 1)

    def test_the_houses_block_takes_a_newcomer_and_all_that_can_come(self):
        ok = self.newcomer()
        self.assertEqual(self.newcomer_failed(ok, 76000 - emu_memory.RESERVE), [])
        self.assertEqual(len(self.newcomer_failed(ok, 76000 - emu_memory.RESERVE + 1)), 1)  # the reserve not kept
        low = self.newcomer(stops={"houses": dict(ok["stops"]["houses"], arena=[40000, 40000, 850000])})
        self.assertEqual(len(self.newcomer_failed(low, 0)), 1)                              # under a block's floor
        elsewhere = self.newcomer(stops={"houses": dict(ok["stops"]["houses"], block=[3, 1])})
        self.assertEqual(len(self.newcomer_failed(elsewhere)), 1)

    def test_a_taken_house_is_looked_into_and_a_free_one_taken(self):
        ok = self.newcomer()
        taken, mine = ok["doors"]
        # no house that is taken was looked into; a taken one asked about; none taken; Nook still there
        self.assertEqual(len(self.newcomer_failed(self.newcomer(doors=[mine]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(doors=[dict(taken, asked=1), mine]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(doors=[taken, dict(mine, mine=False, asked=0)]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(doors=[taken, dict(mine, inside=False, arena=None)]))), 1)
        waits = self.newcomer(stops={"end": dict(ok["stops"]["end"], guide=True)})
        self.assertEqual(len(self.newcomer_failed(waits)), 1)
        small = self.newcomer(doors=[dict(taken, arena=[200000, 200000, 700000]), mine])
        self.assertEqual(len(self.newcomer_failed(small)), 1)                               # under a room's floor
        # the new player's gyroid: a word, no save offered before the first job is done; a page amiss on the road
        self.assertEqual(len(self.newcomer_failed(self.newcomer(gyroid=None))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(gyroid={"spoken": False, "pages": 0}))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(gyroid={"asked": 2, "left": True}))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(gyroid={"owner": False}))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(problems=["0x7e3 at 0: a line past its bubble"]))), 1)
        self.assertEqual(len(self.newcomer_failed(self.newcomer(watch={"fails": 2}))), 1)


if __name__ == "__main__":
    unittest.main()
