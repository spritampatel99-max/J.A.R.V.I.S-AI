#!/usr/bin/env python3
"""Checks that an APK (or AAR) is ready for phones with 16 KB memory pages.

Usage:  python3 tools/check_16kb.py app-debug.apk

For every native library (lib/<abi>/*.so) in the 64-bit ABIs it checks two things:
  1. ELF: every PT_LOAD segment is aligned to at least 16384 bytes (0x4000).
  2. ZIP: a library stored uncompressed starts at an offset that is a multiple of 16384 (needed to map it straight from the APK).
A library that fails (1) must be rebuilt or upgraded by its publisher (Porcupine, zstd-jni ...). A library that fails only (2) is fixed
by building with Android Gradle Plugin 8.5.1 or later, or by running zipalign -P 16 on the APK.
Exit code 0 = all good, 1 = at least one problem, 2 = could not read the file. Standard library only.
"""
import struct
import sys
import zipfile

PAGE = 16384
BITS64 = ("arm64-v8a", "x86_64")


def elf_load_alignments(data: bytes):
    """Returns the p_align of every PT_LOAD segment, or None when the data is not a 64-bit little-endian ELF file."""
    if data[:4] != b"\x7fELF" or len(data) < 64 or data[4] != 2 or data[5] != 1:
        return None
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        if base + 56 > len(data):
            return None
        p_type = struct.unpack_from("<I", data, base)[0]
        if p_type == 1:  # PT_LOAD
            aligns.append(struct.unpack_from("<Q", data, base + 48)[0])
    return aligns


def data_offset(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Where the file's bytes start inside the zip (after its local header)."""
    with open(archive.filename, "rb") as raw:
        raw.seek(info.header_offset)
        header = raw.read(30)
        name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check(path: str) -> int:
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        print(f"Cannot read {path}: {e}")
        return 2
    problems = 0
    checked = 0
    for info in archive.infolist():
        parts = info.filename.split("/")
        if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so") or parts[1] not in BITS64:
            continue
        checked += 1
        aligns = elf_load_alignments(archive.read(info))
        if aligns is None:
            print(f"?? {info.filename}: not a readable 64-bit ELF file")
            problems += 1
            continue
        elf_ok = all(a >= PAGE for a in aligns) and bool(aligns)
        zip_ok = True
        if info.compress_type == zipfile.ZIP_STORED:
            zip_ok = data_offset(archive, info) % PAGE == 0
        status = "OK " if elf_ok and zip_ok else "BAD"
        notes = []
        if not elf_ok:
            notes.append("ELF segments aligned to " + ", ".join(hex(a) for a in aligns) + " (needs 0x4000 or more: upgrade or rebuild the library)")
        if not zip_ok:
            notes.append("stored at an offset that is not a multiple of 16384 (build with AGP 8.5.1+ or run zipalign -P 16)")
        print(f"{status} {info.filename}" + ("  <- " + "; ".join(notes) if notes else ""))
        problems += 0 if elf_ok and zip_ok else 1
    if checked == 0:
        print("No 64-bit native libraries found, so there is nothing to check.")
    print(f"{checked} libraries checked, {problems} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(check(sys.argv[1]))
