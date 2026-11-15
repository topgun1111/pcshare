"""Birleştirme işaretleri kalmasın; Lua betikleri sözdizimi olarak yüklensin.
İşaret kalınca track.lua yüklenmiyordu: üst çubuk, artı işareti, sağ tık çalışmıyordu."""
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
LUA = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
EXTS = {".py", ".lua", ".conf", ".md", ".yml", ".yaml", ".iss", ".bat", ".ps1", ".toml", ".txt"}


def test_no_merge_conflict_markers():
    bad = []
    for p in ROOT.rglob("*"):
        if (not p.is_file() or p.suffix not in EXTS or ".git" in p.parts
                or "dist" in p.parts or p.name == "test_no_conflict_markers.py"):
            continue
        for i, ln in enumerate(p.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if ln.startswith(("<<<<<<< ", ">>>>>>> ")):
                bad.append(f"{p.relative_to(ROOT)}:{i}")
    assert not bad, "çözülmemiş birleştirme işareti: " + ", ".join(bad)


@pytest.mark.skipif(LUA is None, reason="lua/texlua yok")
def test_lua_files_parse(tmp_path):
    chk = tmp_path / "chk.lua"
    chk.write_text(
        "local f, e = loadfile(arg[1]); if not f then io.stderr:write(e); os.exit(1) end",
        encoding="utf-8")
    for p in (ROOT / "mpv" / "portable_config").rglob("*.lua"):
        r = subprocess.run([LUA, str(chk), str(p)], capture_output=True, text=True)
        assert r.returncode == 0, f"{p.name}: {r.stderr}"
