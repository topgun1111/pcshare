"""Gömülü Python'u (python.org embeddable, amd64) hazırlar ve bağımlılıkları içine kurar.

Kullanım (Windows, CI): python scripts/build_embedded.py [--out dist/TakipliOynat] [--no-install]
Sonuç: <out>/python/python.exe (`python -m tracker`); pip ve test/önbellek dosyaları ayıklanır.
Saf mantık (`patch_pth`, `prune`) platformdan bağımsız testlidir; indirme/kurulum yalnız Windows'ta.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY_VERSION = "3.11.9"  # requirements: cp311 tekerlekleri (numpy/opencv/onnxruntime-directml)
PY_URL = f"https://www.python.org/ftp/python/{PY_VERSION}/python-{PY_VERSION}-embed-amd64.zip"
GET_PIP_URL = "https://bootstrap.pypa.io/get-pip.py"
# Tedarik zinciri: indirilen dosyaların SHA256'sı. None = henüz sabitlenmedi; derleme günlüğü
# gerçek özeti yazar ("SHA256 python-...: <hex>") → doğrulayıp buraya yapıştır (sonra uyuşmazlık
# derlemeyi durdurur). get-pip.py sürümsüz/güncel olduğundan çoğunlukla sabitlenmez.
PY_SHA256: str | None = None
GET_PIP_SHA256: str | None = None

# site-packages içinde çalışma zamanında gerekmeyen, boyut yapan kalemler
PRUNE_DIRS = {"__pycache__", "tests", "test", "testing", "_pyinstaller"}
PRUNE_TOP = ("pip", "setuptools", "wheel", "pkg_resources", "_distutils_hack",
             "distutils-precedence.pth")


def patch_pth(text: str) -> str:
    """`pythonXY._pth`: `import site` açılır (site-packages), uygulama kökü (`..`) yola eklenir
    (gömülü Python PYTHONPATH/cwd'yi yok sayar; `python -m tracker` kökten çalışsın)."""
    lines = [ln.rstrip("\r") for ln in text.splitlines()]
    out: list[str] = []
    for ln in lines:
        if ln.strip().lstrip("#").strip() == "import site":
            ln = "import site"
        out.append(ln)
    if "import site" not in out:
        out.append("import site")
    if ".." not in out:
        idx = out.index("import site")
        out.insert(idx, "..")  # `import site` satırından önce: yol girdileri
    return "\n".join(out) + "\n"


def prune(site: Path) -> tuple[int, int]:
    """(silinen dizin, silinen dosya sayısı). numpy/opencv/onnxruntime çalışma zamanı etkilenmez."""
    dirs = files = 0
    if not site.is_dir():
        return 0, 0
    for p in sorted(site.rglob("*"), key=lambda q: len(q.parts), reverse=True):
        if p.is_dir() and p.name in PRUNE_DIRS and p.exists():
            shutil.rmtree(p)
            dirs += 1
        elif p.is_file() and p.suffix == ".pyc" and p.exists():
            p.unlink()
            files += 1
    for name in PRUNE_TOP:
        for p in list(site.glob(name)) + list(site.glob(f"{name}-*")):
            if p.is_dir():
                shutil.rmtree(p)
                dirs += 1
            else:
                p.unlink()
                files += 1
    return dirs, files


def _download(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "takipli-oynat-ci"})
    with urllib.request.urlopen(req, timeout=120) as r:  # noqa: S310 (sabit https adresleri)
        return r.read()


def verify_sha256(name: str, data: bytes, expected: str | None) -> str:
    """Özeti yazdırır; `expected` verilmişse uyuşmazlıkta SystemExit. Gerçek özeti döndürür."""
    digest = hashlib.sha256(data).hexdigest()
    print(f"SHA256 {name}: {digest}")
    if expected and digest != expected.lower():
        raise SystemExit(f"HATA: {name} SHA256 uyuşmuyor (beklenen {expected}, gelen {digest})")
    return digest


def build(out: Path, install: bool = True) -> Path:
    py = out / "python"
    if py.exists():
        shutil.rmtree(py)
    py.mkdir(parents=True)
    print(f"Python {PY_VERSION} indiriliyor...")
    py_zip = _download(PY_URL)
    verify_sha256(PY_URL.rsplit("/", 1)[-1], py_zip, PY_SHA256)
    with zipfile.ZipFile(io.BytesIO(py_zip)) as z:
        z.extractall(py)
    pths = list(py.glob("python*._pth"))
    if len(pths) != 1:
        raise RuntimeError(f"_pth dosyası bulunamadı: {pths}")
    pths[0].write_text(patch_pth(pths[0].read_text(encoding="utf-8")), encoding="utf-8")
    if install:
        exe = py / "python.exe"
        gp = py / "get-pip.py"
        gp_data = _download(GET_PIP_URL)
        verify_sha256("get-pip.py", gp_data, GET_PIP_SHA256)
        gp.write_bytes(gp_data)
        run = [str(exe), "-m", "pip", "install", "--no-warn-script-location",
               "--disable-pip-version-check"]
        subprocess.run([str(exe), str(gp), "--no-warn-script-location"], check=True)
        subprocess.run([*run, "-r", str(ROOT / "requirements.txt")], check=True)
        gp.unlink()
        dirs, files = prune(py / "Lib" / "site-packages")
        print(f"ayıklandı: {dirs} dizin, {files} dosya")
    mb = sum(p.stat().st_size for p in py.rglob("*") if p.is_file()) / 1e6
    print(f"gömülü Python hazır: {py} ({mb:.0f} MB)")
    return py


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "dist" / "TakipliOynat"))
    ap.add_argument("--no-install", action="store_true", help="yalnız Python'u aç (test)")
    a = ap.parse_args(argv)
    if sys.platform != "win32":
        print("Uyarı: gömülü Python yalnız Windows'ta çalışır; yine de dosyalar hazırlanır.")
    build(Path(a.out), install=not a.no_install)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
