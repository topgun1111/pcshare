"""Parça 16: ekran üstü düğme paneli (track.lua) sahte `mp` ile gerçekten çalıştırılır."""
import shutil
import subprocess
from pathlib import Path

import pytest

LUA = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
ROOT = Path(__file__).resolve().parent.parent
TRACK_LUA = ROOT / "mpv" / "portable_config" / "scripts" / "track.lua"
HARNESS = Path(__file__).with_name("lua_harness_panel.lua")


@pytest.mark.skipif(LUA is None, reason="lua/texlua yok")
def test_panel_buttons_hit_test_and_actions(tmp_path):
    data = tmp_path / "d.lua"
    data.write_text("return {}", encoding="utf-8")
    env = {"TRACK_LUA": str(TRACK_LUA), "VIDEO": "", "DATA_LUA": str(data)}
    r = subprocess.run([LUA, str(HARNESS)], env=env, capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr
    lines = [ln.split("\t") for ln in r.stdout.splitlines()]
    d = {ln[0]: ln[1:] for ln in lines if ln[0] != "BTN"}
    btn = {int(ln[1]): ln[2:] for ln in lines if ln[0] == "BTN"}

    assert d["BEFORE"] == ["0", "false"]  # fare hareketi olmadan panel yok
    assert d["SHOWN"] == ["true", "false"]  # panel çizildi, ama sol tık yakalanmıyor
    assert d["LABELS"] == ["true", "true", "true"]
    assert int(d["ROWS"][0]) >= 2 and d["BTNS"] == ["11"]  # 11 düğme, birden çok satıra sarılı

    cmd = {i: v[0] for i, v in btn.items()}
    assert cmd[1] == "script-message track-toggle on"
    assert cmd[2] == "script-message track-release"
    assert cmd[3] == "script-message track-reset"
    assert cmd[4] == "-"  # offline: video dosyası yok → komut yok (OSD uyarısı)
    assert cmd[5] == "script-message track-setting speed -1"
    assert cmd[6] == "script-message track-setting speed 1"
    assert cmd[7] == "script-message track-setting lead -1"
    assert cmd[8] == "script-message track-setting lead 1"
    assert cmd[9] == "script-message track-save"
    assert cmd[10] == "script-message track-status"
    assert cmd[11] == "-"  # Sabit: yalnız panel durumu

    assert d["ENABLED_BOUND"] == ["false"]  # düğme üstünde değil: sol bağ yok
    assert d["TARGET_CLICK"] == ["-"]
    assert d["HIDDEN"] == ["true"]
    assert d["UNBOUND"] == ["true"]


def test_panel_wiring_in_config_files():
    inp = (ROOT / "mpv" / "portable_config" / "input.conf").read_text(encoding="utf-8")
    assert "ctrl+b script-binding track/panel" in inp
    lua = TRACK_LUA.read_text(encoding="utf-8")
    assert '"mouse-pos"' in lua and "set_mouse_area" not in lua  # OSC ile bağ çakışması yok
    assert lua.count('add_forced_key_binding("MBTN_LEFT"') == 1  # tek yerde, hover/enabled'a bağlı


@pytest.mark.skipif(LUA is None, reason="lua/texlua yok")
def test_left_drag_pans_video_in_zoom_mode(tmp_path):
    data = tmp_path / "d.lua"
    data.write_text("return {}", encoding="utf-8")
    env = {"TRACK_LUA": str(TRACK_LUA), "VIDEO": "", "DATA_LUA": str(data)}
    harness = Path(__file__).with_name("lua_harness_pan.lua")
    r = subprocess.run([LUA, str(harness)], env=env, capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr
    d = {ln.split("\t")[0]: ln.split("\t")[1:] for ln in r.stdout.splitlines()}
    assert d["NOZOOM"] == ["nil"]  # zoom yokken kaydırma yok
    assert d["DRAG"] == ["-0.0391", "0.0278"]  # x2: 100 px / 2560, 40 px / 1440
    assert d["DRAG2"] == ["-0.0781"]
    assert d["RELEASED"] == ["-0.0781"]  # tuş bırakılınca kaymaz
    assert d["CLAMP"] == ["0.2500"]  # pencere boş kalmaz
    assert d["OVERBTN"] == ["true", "nil"]  # düğme üstünde başlayan sürükleme kaydırmaz
    assert d["LOCKED"] == ["nil", "true"]  # kilitliyken kaydırma yok, ipucu var


def test_left_drag_pan_wiring():
    inp = (ROOT / "mpv" / "portable_config" / "input.conf").read_text(encoding="utf-8")
    assert "MBTN_LEFT script-binding track/pan" in inp
