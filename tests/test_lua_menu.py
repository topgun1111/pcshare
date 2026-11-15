"""M1: üst menü çubuğu motoru (script-modules/trackmenu.lua) + track.lua bağlama, sahte `mp` ile."""
import re
import shutil
import subprocess
from pathlib import Path

import pytest

LUA = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
ROOT = Path(__file__).resolve().parent.parent
PC = ROOT / "mpv" / "portable_config"
TRACK_LUA = PC / "scripts" / "track.lua"
MODULES = PC / "script-modules"
HARNESS = Path(__file__).with_name("lua_harness_menu.lua")


@pytest.mark.skipif(LUA is None, reason="lua/texlua yok")
def test_menu_bar_flow(tmp_path):
    data = tmp_path / "d.lua"
    data.write_text("return {}", encoding="utf-8")
    env = {"TRACK_LUA": str(TRACK_LUA), "TRACK_MODULES": str(MODULES), "VIDEO": "",
           "DATA_LUA": str(data)}
    r = subprocess.run([LUA, str(HARNESS)], env=env, capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr
    d = {ln.split("\t")[0]: ln.split("\t")[1:] for ln in r.stdout.splitlines()}

    assert d["WINDOWED"] == ["true", "true"]  # pencereli modda çubuk hep açık
    assert d["EXITFS"] == ["true"] and d["STAYS"] == ["true"] and d["UPDATE"] == ["true"]
    assert d["HIDDEN"] == ["true", "false"]  # fare hareketi yok: çubuk yok
    assert d["CENTER"] == ["false", "false"]  # fare hareketinde çubuk görünür; tık yakalanmaz
    assert d["SHOWN"] == ["true", "true", "true"]  # çubuk üstünde: tık yakalanır, todo gri
    assert d["TITLES"] == [",".join(["true"] * 7)]  # 7 başlık (B3: «Parça» eklendi)
    assert d["PARTMENU"] == ["true", "true"]  # B3: Parça menüsü tüm ön ayarları listeler
    assert d["PARTSEL"] == ["script-message track-part left_arm", "true"]  # seçim + kapanır
    assert d["PARTCHK"] == ["true"] and d["PARTLBL"] == ["true"]  # ✓ + dinamik «Vücut parçası: …»
    assert d["PARTKEY"] == ["script-message track-part right_arm"]  # G tuşu: döngü
    assert d["OPEN"] == ["true", "true"]  # liste açıldı, klavye bağları kuruldu
    assert d["GREY"] == ["-", "true"]  # gri öğe: komut yok, menü açık
    assert d["TOGGLE"] == ["script-message track-toggle on", "true"]  # eylem + kapanır
    assert d["CHECKED"] == ["true"]  # Takip açık → işaretli
    assert d["ESC"] == ["true", "true"]  # Esc kapatır, klavye bağları kalkar
    assert d["F10"] == ["true", "true"]
    assert d["RIGHT"] == ["true"]
    assert d["ENTER"] == ["true", "0.25"]  # ↓ Enter: "Yakınlaştır" → video-zoom 0.25
    assert d["OUTSIDE"] == ["true"]
    assert d["AUTOHIDE"] == ["true", "false"]
    assert d["ROTATE"] == ["90,180,270,0"]


def test_menu_table_is_declarative_and_consistent():
    src = (MODULES / "trackmenu.lua").read_text(encoding="utf-8")
    lua = TRACK_LUA.read_text(encoding="utf-8")

    cmds = re.findall(r'cmd = "(\w+)"', src)
    assert len(cmds) == len(set(cmds))  # her eylem tek menüde
    todo = set(re.findall(r'cmd = "(\w+)"[^}]*todo = true', src))
    body = lua[lua.index("local ACT"):]
    acts = set(re.findall(r"^\s+(\w+) = (?:function|do_|setting)", body, re.M))
    # todo olmayan her komut track.lua'da bir eyleme bağlı; todo'lar bağlı değil (gri kalır)
    for c in cmds:
        if c in todo:
            assert c not in acts, f"{c} todo ama eylemi var"
    assert "scan" in todo and not {"export", "open", "open_target"} & todo  # E1 açık, scan gri
    inp = (PC / "input.conf").read_text(encoding="utf-8")
    assert "F10 script-binding track/menu" in inp
    assert "k script-binding track/rotate" in inp
    assert "MBTN_LEFT_DBL script-binding track/dblclick" in inp
