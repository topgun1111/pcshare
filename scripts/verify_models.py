"""Paketteki zorunlu model/araç dosyalarını models/manifest.json'a göre denetler (CI'da).

Kullanım: python scripts/verify_models.py [dist/TakipliOynat] [--write-sums]
Denetim: dosya var, boyut >= min_bytes, sha256 sabitlenmişse eşleşir, lisans dosyası var.
--write-sums: <paket>/models/SHA256SUMS.txt yazar (kurulan dosyaların özeti; elle doğrulama için).
Çıkış 0 = tamam, 1 = eksik/bozuk. Saf Python: Linux'ta da testlenir.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_manifest(path: Path | None = None) -> list[dict]:
    doc = json.loads((path or ROOT / "models" / "manifest.json").read_text(encoding="utf-8"))
    if doc.get("version") != 1:
        raise ValueError("models/manifest.json: desteklenmeyen sürüm")
    return doc["files"]


def check(pkg: Path, files: list[dict]) -> tuple[list[str], dict[str, str]]:
    """(hatalar, {yol: sha256}) döner."""
    errors: list[str] = []
    sums: dict[str, str] = {}
    for e in files:
        p = pkg / e["path"]
        if not p.is_file():
            if e.get("optional"):
                print(f"UYARI: isteğe bağlı dosya yok: {e['path']} ({e.get('feature', '')})")
            else:
                errors.append(f"EKSİK: {e['path']} ({e.get('feature', '')})")
            continue
        size = p.stat().st_size
        if size < e["min_bytes"]:
            errors.append(f"ÇOK KÜÇÜK: {e['path']} ({size} bayt < {e['min_bytes']})")
            continue
        sha = sha256_of(p)
        sums[e["path"]] = sha
        if e.get("sha256") and sha != e["sha256"].lower():
            errors.append(f"ÖZET UYUŞMUYOR: {e['path']} (beklenen {e['sha256']}, gelen {sha})")
        lic = e.get("license_file")
        if lic and not (pkg / lic).is_file():
            errors.append(f"LİSANS DOSYASI EKSİK: {lic} ({e['path']}, {e['license']})")
    return errors, sums


def write_sums(pkg: Path, sums: dict[str, str]) -> Path:
    out = pkg / "models" / "SHA256SUMS.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(f"{s}  {p}\n" for p, s in sorted(sums.items())), encoding="utf-8")
    return out


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    pkg = Path(args[0]) if args else ROOT / "dist" / "TakipliOynat"
    errors, sums = check(pkg, load_manifest())
    for p, s in sorted(sums.items()):
        print(f"OK {s}  {p}")
    if errors:
        for e in errors:
            print("HATA:", e, file=sys.stderr)
        return 1
    if "--write-sums" in argv:
        print("Özet dosyası:", write_sums(pkg, sums))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
