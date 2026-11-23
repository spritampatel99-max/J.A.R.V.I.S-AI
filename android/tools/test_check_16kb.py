"""Tests for check_16kb.py using tiny hand-built ELF headers (no real libraries needed). Run: python3 tools/test_check_16kb.py"""
import os
import struct
import tempfile
import unittest
import zipfile

import check_16kb as c


def fake_elf(*aligns: int) -> bytes:
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4], header[5] = 2, 1  # 64-bit, little-endian
    struct.pack_into("<Q", header, 32, 64)  # program headers start right after the ELF header
    struct.pack_into("<HH", header, 54, 56, len(aligns))
    body = bytearray()
    for a in aligns:
        ph = bytearray(56)
        struct.pack_into("<I", ph, 0, 1)  # PT_LOAD
        struct.pack_into("<Q", ph, 48, a)
        body += ph
    return bytes(header) + bytes(body)


def make_apk(path, name, data, stored_at_aligned_offset):
    with zipfile.ZipFile(path, "w") as z:
        info = zipfile.ZipInfo(name)
        info.compress_type = zipfile.ZIP_STORED
        if stored_at_aligned_offset:
            # Pad the extra field so the file data starts on a 16384 boundary: 30-byte header + name + extra.
            pad = (-(30 + len(name))) % c.PAGE
            info.extra = b"\x00" * pad
        z.writestr(info, data)


class CheckTest(unittest.TestCase):
    def run_on(self, data, aligned_zip=True, compressed=False):
        with tempfile.TemporaryDirectory() as d:
            apk = os.path.join(d, "t.apk")
            if compressed:
                with zipfile.ZipFile(apk, "w") as z:
                    z.writestr("lib/arm64-v8a/libx.so", data, compress_type=zipfile.ZIP_DEFLATED)
            else:
                make_apk(apk, "lib/arm64-v8a/libx.so", data, aligned_zip)
            return c.check(apk)

    def test_16k_elf_and_aligned_zip_passes(self):
        self.assertEqual(0, self.run_on(fake_elf(0x4000, 0x4000)))

    def test_64k_alignment_passes(self):
        self.assertEqual(0, self.run_on(fake_elf(0x10000)))

    def test_4k_elf_fails(self):
        self.assertEqual(1, self.run_on(fake_elf(0x4000, 0x1000)))

    def test_misaligned_stored_entry_fails(self):
        self.assertEqual(1, self.run_on(fake_elf(0x4000), aligned_zip=False))

    def test_compressed_entry_only_needs_elf_alignment(self):
        self.assertEqual(0, self.run_on(fake_elf(0x4000), compressed=True))

    def test_32_bit_abis_are_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            apk = os.path.join(d, "t.apk")
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("lib/armeabi-v7a/libold.so", fake_elf(0x1000))
            self.assertEqual(0, c.check(apk))

    def test_garbage_so_is_reported(self):
        self.assertEqual(1, self.run_on(b"not an elf file", compressed=True))

    def test_elf_without_load_segments_fails(self):
        self.assertEqual(1, self.run_on(fake_elf(), compressed=True))

    def test_missing_file_is_exit_2(self):
        self.assertEqual(2, c.check("/nonexistent/file.apk"))


if __name__ == "__main__":
    unittest.main()
