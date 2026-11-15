"""M2: Dosya menüsü (trackfiles.lua + track.lua bağlama), sahte `mp` ve sahte PowerShell ile."""
import shutil
import subprocess
from pathlib import Path

import pytest

LUA = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
ROOT = Path(__file__).resolve().parent.parent
PC = ROOT / "mpv" / "portable_config"
HARNESS = Path(__file__).with_name("lua_harness_files.lua")


@pytest.mark.skipif(LUA is None, reason="lua/texlua yok")
def test_file_menu_flow(tmp_path):
    data = tmp_path / "d.lua"
    data.write_text("return {}", encoding="utf-8")
    state = tmp_path / "appdata"
    (state / "TakipliOynat").mkdir(parents=True)
    video = tmp_path / "klip.mp4"
    video.write_bytes(b"x")
    env = {"TRACK_LUA": str(PC / "scripts" / "track.lua"),
           "TRACK_MODULES": str(PC / "script-modules"),
           "VIDEO": str(video), "TRACK_PYTHON": "/fake/python.exe",
           "DATA_LUA": str(data), "TRACK_STATE_DIR": str(state)}
    r = subprocess.run([LUA, str(HARNESS)], env=env, capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr
    d = {ln.split("\t")[0]: ln.split("\t")[1:] for ln in r.stdout.splitlines()}

    # asenkron, stdout yakalanır
    assert d["OPEN_ARGS"] == ["powershell.exe", "true", "false", "true"]
    # Türkçe yol, CRLF atıldı, 2. çağrı yok
    assert d["OPEN_LOAD"] == ["loadfile C:\\Videolar\\maç ğüşi.mp4 replace", "true"]
    # son klasör + son açılan kalıcı
    assert d["OPEN_STATE"] == ["C:\\Videolar", "C:\\Videolar\\maç ğüşi.mp4"]
    assert d["CANCEL"] == ["true", "true"]
    assert d["FAIL"][0] == "true" and "PowerShell" in d["FAIL"][1]
    assert d["RECENT"] == ["true", "true"]
    assert d["RECENT_PICK"] == ["loadfile C:\\Videolar\\maç ğüşi.mp4 replace"]
    assert d["FOLDER"] == ["true"] and d["FOLDER_LOAD"] == ["loadfile E:\\Maclar replace"]
    assert d["FOLDER_DIR"] == ["E:\\Maclar"]
    assert d["URL_BAD"] == ["true"]
    assert d["URL_OK"] == ["loadfile https://example.com/v.mp4 replace"]
    assert d["SAVE_VIEW"] == ["1.5", "0.25", "-0.5", "90"]
    assert d["LOAD_VIEW"] == ["1.5", "0.25", "-0.5", "90"]
    assert d["LOAD_BAD"][0] == "true" and "okunamadı" in d["LOAD_BAD"][1]
    assert d["LOAD_BUSY"][0] == "true" and "kapat" in d["LOAD_BUSY"][1]
    assert d["CLOSE"] == ["stop"]
    assert d["PURE_QUOTE"] == ["'a''b'"]
    assert d["PURE_CLEAN"] == ["C:\\a b.mp4", "nil"]
    assert d["PURE_RECENT"] == ["/a/1.mp4,/b/2.mp4"]  # tekrar öne alınır, kopya yok
    assert d["PURE_CAP"] == ["10", "/v/15.mp4", "/v"]
    assert d["PURE_URL"] == ["true", "false", "nil"]
    assert d["PURE_SNAP"] == ["90", "nil", "nil"]
    assert d["MENU_COUNT"] == ["11"]
    # E1: dışa aktarma
    assert d["EXPORT_NOVIEWS"][0] == "true" and "görünüm dosyası yok" in d["EXPORT_NOVIEWS"][1]
    assert d["EXPORT_SPAWN"][0] == "true" and d["EXPORT_SPAWN"][2:] == ["export", "true", "true"]
    assert "%55" in d["EXPORT_PROGRESS"][0] and "bitti" in d["EXPORT_DONE"][0]
    # A1: hedefli açma
    assert d["SCAN_NOT_PENDING"] == ["true"]
    assert d["SCAN_OPEN"] == ["loadfile C:\\Videolar\\hedef.mp4 replace", "yes"]
    assert d["SCAN_SPAWN"][:2] == ["true", "/fake/python.exe"]
    assert d["SCAN_SPAWN"][2:] == ["-m tracker --config", "offline", str(video), "--select-point",
                                   "no"]
    assert d["SCAN_ARGS"] == ["true"] and d["SCAN_ONCE"] == ["true"]
    assert "%40" in d["SCAN_PROGRESS"][0] and "ID 3" in d["SCAN_PROGRESS"][0]
    assert "tarama bitti" in d["SCAN_DONE"][0]
    # A3: yeniden tara + yavaş bayrağı
    assert d["RESCAN"] == ["true", "--rescan", "--select-point", "true"]
    assert d["RESCAN_PAUSED"] == ["true"] and d["RESCAN_PLAY"] == ["true"]
    assert d["RESCAN_END"] == ["true"]
