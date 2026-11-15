import json
import shutil
import subprocess
from pathlib import Path

import numpy as np
import pytest

from tracker.config import Config
from tracker.offline import (
    apply_target,
    load_doc,
    make_doc,
    save_doc,
    save_views,
    view_at,
    views_path,
)

LUA = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
ROOT = Path(__file__).resolve().parent.parent
TRACK_LUA = ROOT / "mpv" / "portable_config" / "scripts" / "track.lua"
HARNESS = Path(__file__).with_name("lua_harness.lua")


def _lua(v):
    if isinstance(v, dict):
        return "{" + ",".join(f"[{json.dumps(k)}]={_lua(x)}" for k, x in v.items()) + "}"
    if isinstance(v, list):
        return "{" + ",".join(_lua(x) for x in v) + "}"
    if v is None:
        return "nil"
    return json.dumps(v)


def _doc():
    samples = []
    for k in range(60):
        cx = 0.2 + 0.01 * k
        d = [] if 20 <= k < 40 else [[1, cx - 0.025, 0.4, cx + 0.025, 0.6, 0.9]]
        samples.append({"t": round(0.1 * k, 3), "d": d})
    doc = make_doc("v.mp4", (200, 100), 25.0, 6.0, 10.0, samples)
    return apply_target(doc, 1, Config(ipc_path="x", lost_hold_s=0.3, lost_zoomout_s=0.5))


def test_views_path_naming():
    assert views_path("a/x.mp4.track.json").name == "x.mp4.views.json"
    assert views_path("a/other.json").name == "other.views.json"


def test_save_views_is_small_and_loadable(tmp_path):
    doc = _doc()
    p = tmp_path / "v.mp4.views.json"
    save_views(doc, p)
    small = json.loads(p.read_text(encoding="utf-8"))
    assert set(small) == {"version", "video", "target", "views"} and small["target"] == {"id": 1}
    assert p.stat().st_size < len(json.dumps(doc)) / 2


def test_view_at_reference():
    v = [[0.0, 0.0, 0.0, 0.0], [1.0, 2.0, 0.2, -0.2], [3.0, 1.0, 0.0, 0.0]]
    assert view_at(v, -1) == (0.0, 0.0, 0.0) and view_at(v, 9) == (1.0, 0.0, 0.0)
    assert view_at(v, 0.5) == pytest.approx((1.0, 0.1, -0.1))
    assert view_at(v, 2.0) == pytest.approx((1.5, 0.1, -0.1))
    assert view_at([], 1.0) == (0.0, 0.0, 0.0)


@pytest.mark.skipif(LUA is None, reason="lua/texlua yok")
def test_lua_playback_matches_python_reference(tmp_path):
    doc = _doc()
    video = tmp_path / "v.mp4"
    video.write_bytes(b"")
    save_views(doc, tmp_path / "v.mp4.views.json")
    data = tmp_path / "data.lua"
    data.write_text("return " + _lua({"version": 1, "target": doc["target"],
                                      "views": doc["views"]}), encoding="utf-8")
    times = [-1.0, 0.0, 0.05, 1.33, 2.0, 2.5, 3.3, 4.01, 5.5, 5.9, 9.0]
    env = {"TRACK_LUA": str(TRACK_LUA), "VIDEO": str(video), "DATA_LUA": str(data),
           "TIMES": ",".join(map(str, times)), "PATH": ""}
    r = subprocess.run([LUA, str(HARNESS)], env=env, capture_output=True, text=True,
                       encoding="utf-8", timeout=30)
    assert r.returncode == 0, r.stderr
    rows = [ln.split("\t") for ln in r.stdout.splitlines()]
    by = {row[0]: row for row in rows if row[0] != "T"}
    assert "offline takip verisi var" in by["HINT"][1]
    assert "offline AÇIK" in by["ON"][1] and "hedef ID 1" in by["ON"][1]
    ts = [row for row in rows if row[0] == "T"]
    assert len(ts) == len(times)
    last = None
    for row, t in zip(ts, times, strict=True):
        exp = view_at(doc["views"], t)
        if row[2] == "nil":  # önceki değerle aynı (dedupe): set çağrılmaz
            assert last is not None and exp == pytest.approx(last, abs=1e-4)
            continue
        got = tuple(float(x) for x in row[2:5])
        assert got == pytest.approx(exp, abs=1e-9), t
        last = got
    assert np.isfinite(np.array(last)).all()
    assert by["OFF_ZOOM"][1] == "nil" and "zoom sabit" in by["OFF_ZOOM"][2]  # offline: zoom +/- yok
    assert "AÇIK - nesneye sağ tıkla" in by["LIVE"][1]  # canlı mod açıldı
    assert by["LIVE"][2] == "0"  # görünüm sıfırlandı
    assert by["AFTER_LIVE_SET"][1] == "nil"
    assert "offline AÇIK" in by["REON"][1] and by["REON"][2] == "1"
    assert by["TIMER"][1] != "nil"  # gecikmeli yeniden uygulama
    assert by["ENDFILE"][1] == "nil"
    assert "bulunamadı" in by["MISSING"][1]
    # A2: otomatik geçiş yumuşak (başlangıç → hedef), sebep OSD'de, canlı kapanır
    assert "taranmış iz kullanılıyor" in by["AUTO_OSD"][1] and "yavaş" in by["AUTO_OSD"][1]
    exp = view_at(doc["views"], 1.0)
    assert float(by["AUTO_START"][1]) == pytest.approx(0.0, abs=1e-9)  # 0'dan başlar
    mid = float(by["AUTO_MID"][1])
    assert 0.0 < mid < exp[0] or exp[0] < mid < 0.0  # arada
    assert float(by["AUTO_END"][1]) == pytest.approx(exp[0], abs=1e-9)
    assert by["AUTO_TIMER"][1] == "2" and by["AUTO_TWICE"][1] == "true"


def test_cli_offline_view_writes_views_file(tmp_path):
    from tracker.main import main

    p = tmp_path / "v.mp4.track.json"
    save_doc(make_doc("v.mp4", (200, 100), 25.0, 3.0, 10.0, _doc()["samples"]), p)
    assert main(["offline-view", str(p), "--target-id", "1"]) == 0
    vp = tmp_path / "v.mp4.views.json"
    assert vp.exists() and json.loads(vp.read_text(encoding="utf-8"))["views"]
    assert load_doc(p)["target"] == {"id": 1}
