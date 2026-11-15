"""U2: alt çubuk (mpcbar.lua) sahte `mp` ile çalıştırılır: çarpışma kutuları, seek/ses sınırları,
sol tık bağının kurulup kalkması, tam ekranda gizleme. lua/texlua yoksa atlanır."""
import shutil
import subprocess
from pathlib import Path

import pytest

LUA = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
ROOT = Path(__file__).resolve().parent.parent
BAR = ROOT / "mpv" / "portable_config" / "scripts" / "mpcbar.lua"
HARNESS = Path(__file__).with_name("lua_harness_bar.lua")


@pytest.fixture(scope="module")
def d():
    r = subprocess.run([LUA, str(HARNESS)], env={"BAR_LUA": str(BAR)}, capture_output=True,
                       text=True, timeout=60)
    assert r.returncode == 0, r.stderr
    rows = [ln.split("\t") for ln in r.stdout.splitlines()]
    return {ln[0]: ln[1:] for ln in rows}


needs_lua = pytest.mark.skipif(LUA is None, reason="lua/texlua yok")


@needs_lua
def test_left_click_bound_only_over_controls(d):
    assert d["INIT"] == ["0"]
    assert d["VIDEO"] == ["0"]  # video üstü: mpv/track.lua sol tıkı serbest
    assert d["ONPLAY"] == ["1", "1"]  # düğme üstü: tek + çift tık yakalanır
    assert d["LEAVE"] == ["0"]


@needs_lua
def test_buttons_hit_boxes(d):
    assert d["PLAY"] == ["cycle pause"]
    assert d["STOP"][0] == "seek 0 absolute" and d["STOP"][1] == "true"


@needs_lua
def test_seek_ratio_drag_and_release(d):
    # 1280 geniş, u=1: oluk x 12..1268 → orta = %50 → 50 sn
    parts = d["SEEK"][0].split()
    assert parts[0] == "seek" and abs(float(parts[1]) - 50) < 0.5 and parts[2] == "absolute+exact"
    assert d["TIP"] == ["1"]  # fare üstü zaman ipucu
    assert d["DRAG_OUT"][0].split()[1] == "100" and d["DRAG_OUT"][1] == "1"  # sınır + bağ kalır
    assert d["UP"] == ["0"]  # bırakınca (fare çubukta değil) bağ kalkar
    assert d["AFTER"] == ["0"]


@needs_lua
def test_chapter_tooltip(d):
    assert d["CHAP"] == ["1"]  # 50 sn → «Çözüm» bölümü
    assert d["CHAP0"] == ["1"]
    assert d["NOCHAP"] == ["0"]  # bölüm yoksa yalnız zaman


@needs_lua
def test_ab_loop_markers(d):
    assert d["AB"] == ["true", "true"]  # A işareti, sonra B + aralık çizgisi
    assert d["AB_OFF"] == ["true"]  # A silinince işaretler kalkar


@needs_lua
def test_volume_slider_bounds_and_unmute(d):
    assert d["VOL_MID"] == ["65", "false"]  # vmax 130 → orta 65; sessiz kalkar
    assert d["VOL_LOW"] == ["0"]
    assert d["VOL_HIGH"] == ["130"]  # volume-max üstüne çıkmaz


@needs_lua
def test_speed_buttons(d):
    assert d["SPD_UP"] == ["1.1", "Hız: 1.1x"]
    assert d["SPD_DN"] == ["1.0"]
    assert d["SPD_RESET"] == ["1", "Hız: 1.0x"]  # şeritten 1x
    assert d["SPD_2X"] == ["2"]


@needs_lua
def test_speed_strip_open_close(d):
    assert d["STRIP_OPEN"] == ["1.3", "1"]  # hız düğmesi hızı değiştirmez, şerit çizilir
    assert d["STRIP_HOVER"] == ["1"]  # şerit üstünde sol tık bağı kurulu
    assert d["STRIP_CLOSE"] == ["0"]  # fare video alanına çıkınca kapanır
    assert d["STRIP_OTHER"] == ["0"]  # başka düğmeye tık: kapanır


@needs_lua
def test_volume_tip_and_wheel(d):
    assert d["VOLTIP"] == ["1"]
    assert d["WHEEL_BOUND"] == ["1"]
    assert d["WHEEL_UP"] == ["85", "Ses: 85%"]
    assert d["WHEEL_MIN"] == ["0"]
    assert d["WHEEL_MAX"] == ["130"]  # volume-max
    assert d["WHEEL_FREE"] == ["0"]  # fare ayrılınca tekerlek bağı kalkar (mpv tuşları serbest)


@needs_lua
def test_fullscreen_autohide(d):
    assert d["FS_HOVER"] == ["0", "1"]  # fare çubuktayken gizlenmez
    assert d["FS_HIDE"] == ["true", "0"]  # ayrılınca gizlenir, sol bağ kalkar
    assert d["WIN_STAYS"] == ["0"]  # pencere modunda hiç gizlenmez


def test_bar_wiring_in_files():
    lua = BAR.read_text(encoding="utf-8")
    conf = (ROOT / "mpv" / "portable_config" / "mpv.conf").read_text(encoding="utf-8")
    assert "osc=no" in conf
    # yalnız sync() içinde: sol tık + tekerlek (hover/sürükleme)
    assert lua.count("add_forced_key_binding") == 2
    assert "MBTN_RIGHT" not in lua  # sağ tık takibine dokunulmaz
