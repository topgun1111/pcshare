import json

import pytest

from tracker.bench import TimedPipeline, analyze, format_report, likely_switches, timing
from tracker.config import Config
from tracker.main import main

W, H, FPS = 200, 100, 10.0


def _doc(rows_by_t):
    samples = [{"t": round(i / FPS, 3), "d": d} for i, d in enumerate(rows_by_t)]
    return {"version": 1, "video": {"path": "x", "w": W, "h": H, "fps": 25, "duration": 10},
            "sample_fps": FPS, "samples": samples, "target": None, "views": []}


def _box(i, x):
    return [i, x, 0.4, x + 0.1, 0.8, 0.9]


def _cfg():
    return Config(ipc_path="x")


def test_stable_track_no_switches_no_loss():
    doc = _doc([[_box(1, 0.1 + 0.005 * k)] for k in range(60)])
    r = analyze(doc, _cfg())
    assert r["ids"] == 1 and r["likely_id_switches"] == 0 and r["short_tracks"] == 0
    assert r["target"]["lost_pct"] == 0.0 and r["target"]["coverage_pct"] == 100.0


def test_id_switch_detected_near_end_of_track():
    rows = [[_box(1, 0.3)] for _ in range(30)] + [[_box(2, 0.31)] for _ in range(30)]
    doc = _doc(rows)
    assert likely_switches(doc) == 1
    far = _doc([[_box(1, 0.1)] for _ in range(30)] + [[_box(2, 0.8)] for _ in range(30)])
    assert likely_switches(far) == 0


def test_lost_percent_inside_track_span():
    rows = [[_box(1, 0.3)] if k % 4 else [] for k in range(40)]  # her 4. örnekte yok
    rows[0] = [_box(1, 0.3)]
    r = analyze(_doc(rows), _cfg(), target_id=1)["target"]
    assert 20 < r["lost_pct"] < 30


def test_live_jitters_more_than_offline_on_noisy_target():
    import random

    random.seed(1)
    rows = [[_box(1, 0.4 + random.uniform(-0.03, 0.03))] for _ in range(80)]
    t = analyze(_doc(rows), _cfg())["target"]
    assert t["offline_jitter"] <= t["live_jitter"] * 1.5
    assert t["live_vs_offline_pan_dev"] >= 0.0


def test_unknown_target_and_empty():
    with pytest.raises(ValueError):
        analyze(_doc([[_box(1, 0.2)]] * 5), _cfg(), target_id=9)
    r = analyze(_doc([[] for _ in range(5)]), _cfg())
    assert r["ids"] == 0 and r["target"] is None


def test_timing_and_wrapper():
    class P:
        def step(self, f):
            return [f]

    tp = TimedPipeline(P())
    assert tp.step(3) == [3] and len(tp.ms) == 1
    t = timing([10.0, 20.0, 30.0, 40.0], 1.0)
    assert t["frames"] == 4 and t["mean_ms"] == 25.0 and t["fps"] == 4.0
    assert timing([], 1.0) == {}


def test_cli_bench_json(tmp_path, capsys):
    p = tmp_path / "a.track.json"
    p.write_text(json.dumps(_doc([[_box(1, 0.2)] for _ in range(30)])))
    out = tmp_path / "r.json"
    assert main(["bench", str(p), "--out", str(out)]) == 0
    rep = json.loads(out.read_text())
    assert rep["ids"] == 1 and "lost_pct" in rep["target"]
    assert "likely_id_switches" in capsys.readouterr().out
    assert main(["bench", str(p), "--target-id", "7"]) == 1
    assert "ids" in format_report(rep)
