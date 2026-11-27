#!/usr/bin/env python3
"""Create a byte-exact delta of signed APKs, copying unchanged raw ZIP payloads.
Never unzip/repack an APK: its signing block must survive byte for byte.
"""
import argparse
import gzip
import hashlib
import io
from pathlib import Path
import struct
import tempfile
import zipfile

MAGIC = b"PCDELTA1"
MAX_APK = 512 * 1024 * 1024

def sha(data):
    return hashlib.sha256(data).digest()

def entries(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        result = {}
        for item in archive.infolist():
            offset = item.header_offset
            if data[offset:offset+4] != b"PK\x03\x04":
                raise ValueError("invalid local ZIP header")
            name_len, extra_len = struct.unpack_from("<HH", data, offset + 26)
            start = offset + 30 + name_len + extra_len
            end = start + item.compress_size
            if end > len(data):
                raise ValueError("invalid ZIP payload bounds")
            result[item.filename] = (start, end)
        return result

def create(base, target):
    if not 0 < len(target) <= MAX_APK:
        raise ValueError("invalid target size")
    old = entries(base)
    matches = []
    for name, (start, end) in entries(target).items():
        if name in old and end - start >= 64:
            a, b = old[name]
            if b-a == end-start and base[a:b] == target[start:end]:
                matches.append((start, end, a))
    stream = io.BytesIO()
    with gzip.GzipFile(fileobj=stream, mode="wb", mtime=0) as out:
        out.write(MAGIC + sha(base) + sha(target) + struct.pack(">Q", len(target)))
        cursor = 0
        for start, end, source in sorted(matches):
            if start < cursor:
                continue
            if start > cursor:
                out.write(struct.pack(">BI", 1, start-cursor))
                out.write(target[cursor:start])
            out.write(struct.pack(">BQI", 0, source, end-start))
            cursor = end
        if cursor < len(target):
            out.write(struct.pack(">BI", 1, len(target)-cursor))
            out.write(target[cursor:])
        out.write(b"\x02")
    return stream.getvalue()

def reconstruct(base, patch):
    """Independent CI round-trip verification of the generated patch."""
    with gzip.GzipFile(fileobj=io.BytesIO(patch)) as inp:
        if inp.read(8) != MAGIC or inp.read(32) != sha(base):
            raise ValueError("base mismatch")
        target_hash = inp.read(32)
        size, = struct.unpack(">Q", inp.read(8))
        if not 0 < size <= MAX_APK:
            raise ValueError("invalid output size")
        out = bytearray()
        while True:
            op = inp.read(1)
            if op == b"\x02":
                break
            if op == b"\x00":
                offset, length = struct.unpack(">QI", inp.read(12))
                if offset + length > len(base):
                    raise ValueError("invalid copy")
                chunk = base[offset:offset+length]
            elif op == b"\x01":
                length, = struct.unpack(">I", inp.read(4))
                chunk = inp.read(length)
            else:
                raise ValueError("invalid record")
            if not length or len(chunk) != length or len(out)+length > size:
                raise ValueError("invalid length")
            out.extend(chunk)
        if inp.read(1) or len(out) != size or sha(out) != target_hash:
            raise ValueError("target mismatch")
        return bytes(out)

def self_test():
    def apk(changed, order):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w", compression=zipfile.ZIP_DEFLATED) as z:
            for name in order:
                z.writestr(name, changed if name == "classes.dex" else bytes(range(256))*400)
        return data.getvalue()
    a = apk(b"old code", ["classes.dex", "assets/font"])
    b = apk(b"new longer code", ["assets/font", "classes.dex"])
    patch = create(a, b)
    assert reconstruct(a, patch) == b
    assert len(patch) < len(b)
    try:
        reconstruct(b, patch)
    except ValueError:
        pass
    else:
        raise AssertionError("wrong base accepted")
    print("APK delta self-test passed")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("base", type=Path, nargs="?")
    parser.add_argument("target", type=Path, nargs="?")
    parser.add_argument("edition", choices=["full", "lite"], nargs="?")
    parser.add_argument("output", type=Path, nargs="?")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not all((args.base, args.target, args.edition, args.output)):
        parser.error("base target edition output required")
    base, target = args.base.read_bytes(), args.target.read_bytes()
    patch = create(base, target)
    if reconstruct(base, patch) != target:
        raise RuntimeError("round-trip failed")
    if len(patch) >= len(target)*0.85:
        print("Delta not worthwhile; full APK remains available")
        return
    args.output.mkdir(parents=True, exist_ok=True)
    name = f"LANShare-delta-{sha(base).hex()}-{sha(target).hex()}-{args.edition}.patch"
    (args.output / name).write_bytes(patch)
    print(f"{args.edition}: {len(patch):,} / {len(target):,} bytes")

if __name__ == "__main__":
    main()
