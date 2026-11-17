#!/usr/bin/env python3
"""Check the native library and its notices in an assembled Notify APK."""

import struct
import sys
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile


def check(apk_path):
    root = Path(__file__).resolve().parent.parent
    with ZipFile(apk_path) as apk, open(apk_path, "rb") as raw:
        native_path = "lib/arm64-v8a/libnotify_android.so"
        assert native_path in apk.namelist(), "APK is missing Notify's native crypto library"
        assert apk.read(native_path) == (
            root / "crypto/jniLibs/arm64-v8a/libnotify_android.so"
        ).read_bytes(), "APK contains a different native crypto build"

        libraries = [item for item in apk.infolist() if item.filename.endswith(".so")]
        for item in libraries:
            assert item.filename.startswith("lib/arm64-v8a/"), (
                f"Unsupported ABI packaged without Notify crypto: {item.filename}"
            )
            assert item.compress_type == ZIP_STORED, f"Compressed library: {item.filename}"
            raw.seek(item.header_offset)
            header = raw.read(30)
            name_size, extra_size = struct.unpack_from("<HH", header, 26)
            assert (item.header_offset + 30 + name_size + extra_size) % 16384 == 0, (
                f"APK library is not 16 KB aligned: {item.filename}"
            )
            data = apk.read(item)
            assert data[:6] == b"\x7fELF\x02\x01", f"Not little-endian ELF64: {item.filename}"
            assert struct.unpack_from("<H", data, 18)[0] == 183, "Not AArch64"
            offset = struct.unpack_from("<Q", data, 32)[0]
            entry_size, count = struct.unpack_from("<HH", data, 54)
            load_segments = 0
            for index in range(count):
                entry = offset + index * entry_size
                if struct.unpack_from("<I", data, entry)[0] == 1:
                    load_segments += 1
                    alignment = struct.unpack_from("<Q", data, entry + 48)[0]
                    assert alignment >= 16384, f"ELF is not 16 KB aligned: {item.filename}"
            assert load_segments, f"ELF has no load segments: {item.filename}"

        assets = root / "app/src/main/assets/third-party"
        notices = [path for path in assets.rglob("*") if path.is_file()]
        assert (assets / "notify-crypto.txt").is_file(), "Native notices are missing"
        assert (assets / "notify-crypto-dependencies.json").is_file(), "Dependency inventory is missing"
        for path in notices:
            assert apk.read("assets/third-party/" + path.relative_to(assets).as_posix()) == (
                path.read_bytes()
            ), f"Missing or changed packaged notice: {path.name}"
        print(f"APK verified: {len(libraries)} arm64 libraries, 16 KB alignment, {len(notices)} notice assets.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 crypto/check-apk.py path/to/notify.apk")
    check(Path(sys.argv[1]))
