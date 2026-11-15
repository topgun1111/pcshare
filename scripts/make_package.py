"""dist/TakipliOynat paket klasörünü hazırlar (mpv.exe'yi fetch_deps.ps1 ekler).

Çıktı yapısı:
  dist/TakipliOynat/
    mpv/portable_config/...   (mpv.conf, input.conf, scripts/track.lua)
    tracker/                  (Python paketi)
    models/                   (YOLO ONNX buraya konur; MODELLER.txt)
    Baslat.bat, Offline.bat   (başlatıcılar; python/ klasörünü build_embedded.py ekler)
    requirements.txt, README.md, VERSION, THIRD_PARTY_NOTICES.md (+ LICENSE varsa)
"""

from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "dist" / "TakipliOynat"


def _copytree(src: Path, dst: Path) -> None:
    shutil.copytree(
        src, dst, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__", "*.pyc")
    )


def main() -> int:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    _copytree(ROOT / "tracker", OUT / "tracker")
    _copytree(ROOT / "mpv" / "portable_config", OUT / "mpv" / "portable_config")
    (OUT / "models").mkdir()
    (OUT / "models" / ".gitkeep").touch()
    for name in ("MODELLER.txt", "manifest.json"):
        shutil.copy2(ROOT / "models" / name, OUT / "models" / name)
    for bat in sorted((ROOT / "launcher").glob("*.bat")):  # CRLF: cmd.exe için
        data = bat.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        (OUT / bat.name).write_bytes(data)

    for name in ("requirements.txt", "README.md", "VERSION", "THIRD_PARTY_NOTICES.md"):
        shutil.copy2(ROOT / name, OUT / name)

    if (ROOT / "LICENSE").is_file():  # proje lisansı (sahibi ekler)
        shutil.copy2(ROOT / "LICENSE", OUT / "LICENSE")

    files = sum(1 for p in OUT.rglob("*") if p.is_file())
    print(f"Paket hazır: {OUT} ({files} dosya)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
