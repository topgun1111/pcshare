"""Sağ tık ile takip noktası + ekran üstü zoom kontrolü (track.lua sahte `mp` ile çalıştırılır)."""
import shutil
import subprocess
from pathlib import Path

import pytest

LUA = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
ROOT = Path(__file__).resolve().parent.parent
TRACK_LUA = ROOT / "mpv" / "portable_config" / "scripts" / "track.lua"
HARNESS = Path(__file__).with_name("lua_harness_zoom.lua")


@pytest.mark.skipif(LUA is None, reason="lua/texlua yok")
def test_right_click_pick_and_zoom_control(tmp_path):
    data = tmp_path / "d.lua"
    data.write_text("return {}", encoding="utf-8")
    env = {"TRACK_LUA": str(TRACK_LUA), "VIDEO": "", "DATA_LUA": str(data)}
    r = subprocess.run([LUA, str(HARNESS)], env=env, capture_output=True, text=True,
                       encoding="utf-8", timeout=60)
    assert r.returncode == 0, r.stderr
    d = {ln.split("\t")[0]: ln.split("\t")[1:] for ln in r.stdout.splitlines()}

    # görünürlük ve yerleşim
    assert d["BEFORE"] == ["true"]
    assert d["SHOWN"] == ["true", "true", "false"]  # çizildi; düğme dışında sol tık yakalanmaz
    assert d["BUTTONS"] == ["2"]  # + ve - (değer kutusu tıklanamaz)

    # takip kapalı: video-zoom (log2) 0.25 adım, 0..4 sınır, değer etiketi güncel
    assert [d["PLUS"][0], d["PLUS"][1], d["PLUS"][2]] == ["0.25", "nil", "true"]
    assert float(d["MINUS"][0]) == 0.0 and d["MINUS"][1] == "true"
    assert float(d["FLOOR"][0]) == 0.0
    assert d["CEIL"][1] == "true" and float(d["CEIL"][0]) == 4.0  # 2^4 = 16x
    assert float(d["KEY_OUT"][0]) == 0.75 and float(d["KEY_IN"][0]) == 1.0  # + / - tuşları

    # zoom varken kontrol panel gizlense de kalır; 1x ve etkinlik yoksa kaybolur
    assert d["OBS"] == ["true"]
    assert d["STAYS"] == ["true", "false"]
    assert d["GONE"] == ["true"]

    # takip açıkken: hedef kilitliyse doluluk, değilse video-zoom
    assert d["ON_FREE"] == ["0.25", "0"]  # video-zoom yazıldı, track-setting komutu yok
    assert d["ON_LOCKED"] == ["script-message track-setting fill 1",
                              "script-message track-setting fill -1", "nil"]
    assert d["UNLOCKED"] == ["0.5", "0"]

    # sağ tık: takibi açar, sonra noktayı gönderir (video-normalize 0.5, 0.5)
    assert d["PICK1"] == ["script-message track-toggle on",
                          "script-message track-point 0.500000 0.500000 1920 1080 12.500"]
    assert d["PICK2"] == ["script-message track-point 0.250000 0.250000 1920 1080 12.500", "1"]
    assert d["PICK_BTN"] == ["0"]  # zoom düğmesinin üstünde hedef seçilmez
    assert d["PICK_OUT"] == ["0"]  # video dışı


def test_zoom_and_right_click_wiring_in_config_files():
    inp = (ROOT / "mpv" / "portable_config" / "input.conf").read_text(encoding="utf-8")
    lines = {ln.split(None, 1)[0]: ln for ln in inp.splitlines()
             if ln.strip() and not ln.startswith("#")}
    assert lines["MBTN_RIGHT"].endswith("script-binding track/pick")  # sağ tık = takip noktası
    assert lines["+"].endswith("script-binding track/zoom-in")
    assert lines["-"].endswith("script-binding track/zoom-out")
    lua = TRACK_LUA.read_text(encoding="utf-8")
    for name in ('"pick"', '"zoom-in"', '"zoom-out"'):
        assert f"add_key_binding(nil, {name}" in lua
    assert lua.count('add_forced_key_binding("MBTN_LEFT"') == 1  # sol tık bağı tek yerde
    assert "MBTN_RIGHT" not in lua  # sağ tık yalnız input.conf'ta (kullanıcı geçersiz kılabilir)
