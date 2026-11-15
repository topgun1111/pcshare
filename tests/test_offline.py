import json

import numpy as np
import pytest

from tracker.bytetrack import Track
from tracker.config import Config
from tracker.geometry import BBox, View
from tracker.main import main
from tracker.offline import (
    SCHEMA_VERSION,
    VideoSource,
    apply_target,
    build_views,
    list_tracks,
    load_doc,
    make_doc,
    pick_target_id,
    run_detection,
    save_doc,
)
from tracker.smooth import smooth_zero_phase

W, H = 200, 100


def _cfg(**kw):
    return Config(ipc_path="x", **kw)


def _doc(samples):
    return make_doc("v.mp4", (W, H), 25.0, 10.0, 10.0, samples)


def _s(t, *dets):
    return {"t": t, "d": [list(d) for d in dets]}


def _box(i, cx, cy=0.5, w=0.05, h=0.2, score=0.9):
    return (i, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score)


def _moving(n=40, dx=0.01, start=0.2):
    return [_s(round(0.1 * k, 3), _box(1, start + dx * k)) for k in range(n)]


class FakePipe:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def step(self, frame):
        out = self.seq[self.i]
        self.i += 1
        return out


def test_run_detection_normalizes_and_rounds():
    frames = [(0.0, np.zeros((H, W, 3), np.uint8)), (0.1, np.zeros((H, W, 3), np.uint8))]
    seq = [[Track(7, BBox(20, 10, 60, 90), 0.87654, 3, 0)], []]
    samples, size = run_detection(iter(frames), FakePipe(seq))
    assert size == (W, H)
    assert samples[0] == {"t": 0.0, "d": [[7, 0.1, 0.1, 0.3, 0.9, 0.877]]}
    assert samples[1]["d"] == []


def test_save_load_roundtrip_and_validation(tmp_path):
    doc = _doc(_moving(5))
    p = tmp_path / "a.json"
    save_doc(doc, p)
    assert load_doc(p) == doc and doc["version"] == SCHEMA_VERSION
    p.write_text(json.dumps({"version": 99}), encoding="utf-8")
    with pytest.raises(ValueError):
        load_doc(p)
    p.write_text(json.dumps({"version": 1, "video": {}}), encoding="utf-8")
    with pytest.raises(ValueError):
        load_doc(p)


def test_list_tracks_and_pick_target():
    doc = _doc([_s(0.0, _box(1, 0.2), _box(2, 0.7)), _s(0.1, _box(1, 0.21)),
                _s(0.2, _box(1, 0.22), _box(2, 0.72))])
    ls = list_tracks(doc)
    assert [e["id"] for e in ls] == [1, 2] and ls[0]["n"] == 3 and ls[1]["last"] == 0.2
    assert pick_target_id(doc, 0.0, 0.7, 0.5) == 2
    assert pick_target_id(doc, 0.0, 0.45, 0.5) is None  # boş yer (tolerans dışı)
    assert pick_target_id(doc, 0.09, 0.2, 0.5) == 1  # en yakın örnek
    assert pick_target_id(_doc([]), 0.0, 0.5, 0.5) is None


def test_zero_phase_has_no_lag():
    ramp = [float(i) for i in range(50)]
    sm = smooth_zero_phase(ramp, 0.3)
    assert all(abs(a - b) < 0.5 for a, b in zip(sm[5:-5], ramp[5:-5], strict=True))
    assert smooth_zero_phase([], 0.3) == []


def test_build_views_follows_target_without_lag():
    doc = _doc(_moving())
    kfs = build_views(doc, 1, _cfg())
    assert kfs[0][0] == 0.0 and kfs[-1][0] == pytest.approx(3.9)
    mid = next(k for k in kfs if abs(k[0] - 2.0) < 1e-6)
    cx = 0.2 + 0.01 * 20  # hedef merkezi t=2.0'da
    assert mid[1] > 0.5 and abs(mid[2] - (0.5 - cx)) < 0.02  # pan = 0.5 - c, gecikmesiz
    assert all(0 <= k[1] <= 2.5 for k in kfs)


def test_short_gap_interpolated_long_gap_zooms_out_and_back():
    samples = _moving(60, dx=0.0)  # durağan hedef, t=0..5.9
    for k in range(10, 13):  # 0.3 sn'lik kayıp → doldurulur
        samples[k]["d"] = []
    for k in range(30, 50):  # ~2 sn kayıp → zoom-out
        samples[k]["d"] = []
    doc = _doc(samples)
    cfg = _cfg(lost_hold_s=0.5, lost_zoomout_s=0.5)
    kfs = build_views(doc, 1, cfg)
    base = kfs[0][1]
    assert base > 0.5

    def z(t):  # anahtar kareler arası doğrusal (Parça 10'daki oynatıcı gibi)
        return float(np.interp(t, [k[0] for k in kfs], [k[1] for k in kfs]))

    assert z(1.1) == pytest.approx(base, abs=0.02)  # kısa kayıp: zoom korunur
    assert z(3.4) == pytest.approx(base, abs=0.02)  # uzun kaybın bekleme aşaması (t0=3.0, +0.5)
    assert z(4.25) == pytest.approx(0.0, abs=1e-3)  # zoom-out 3.5–4.0, zoom-in 4.5–5.0 → arada 1x
    assert z(4.9) > 0.0  # hedef 5.0'da döner; geri yaklaşma 4.5'te başlar
    assert z(5.5) == pytest.approx(base, abs=0.02)  # döndükten sonra


def test_gap_zoom_monotonic_and_no_edge_exposure():
    samples = _moving(40, dx=0.0, start=0.9)  # kenara yakın hedef
    for k in range(10, 40):
        samples[k]["d"] = []
    kfs = build_views(_doc(samples), 1, _cfg(lost_hold_s=0.3, lost_zoomout_s=1.0))
    zs = [k[1] for k in kfs if k[0] >= 1.0]
    assert all(a >= b - 1e-9 for a, b in zip(zs, zs[1:], strict=False))
    for _, zoom, px, py in kfs:
        half = 0.5 - 0.5 / 2**zoom
        assert abs(px) <= half + 1e-3 and abs(py) <= half + 1e-3


def test_target_never_seen_and_leading_gap():
    assert build_views(_doc(_moving(5)), 99, _cfg()) == [[0.0, 0.0, 0.0, 0.0], [0.4, 0.0, 0.0, 0.0]]
    samples = _moving(40, dx=0.0)
    for k in range(10):
        samples[k]["d"] = []
    kfs = build_views(_doc(samples), 1, _cfg(lost_zoomout_s=0.5))
    assert kfs[0][1] == 0.0  # hedef gelene dek 1x
    assert build_views(_doc([]), 1, _cfg()) == []


def test_apply_target_and_dedupe():
    doc = apply_target(_doc(_moving(40, dx=0.0)), 1, _cfg())
    assert doc["target"] == {"id": 1} and 2 <= len(doc["views"]) < 40  # sabit → seyreltilmiş
    assert doc["views"][0][0] == 0.0 and doc["views"][-1][0] == pytest.approx(3.9)


def test_cli_offline_view_list_and_retarget(tmp_path, capsys):
    p = tmp_path / "t.json"
    save_doc(_doc(_moving(30)), p)
    assert main(["offline-view", str(p), "--list"]) == 0
    assert "ID 1" in capsys.readouterr().out
    out = tmp_path / "o.json"
    assert main(["offline-view", str(p), "--click", "1.0", "0.3", "0.5", "--out", str(out)]) == 0
    d = json.loads(out.read_text(encoding="utf-8"))
    assert d["target"] == {"id": 1} and d["views"]
    assert main(["offline-view", str(p), "--click", "1.0", "0.9", "0.1"]) == 1  # kimse yok
    assert main(["offline-view", str(tmp_path / "yok.json")]) == 1


def test_video_source_samples_at_fps(tmp_path):
    import cv2

    path = str(tmp_path / "v.avi")
    wr = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"MJPG"), 20.0, (64, 48))
    if not wr.isOpened():
        pytest.skip("MJPG yazıcı yok")
    for i in range(40):  # 2 sn @20fps
        wr.write(np.full((48, 64, 3), i * 5, np.uint8))
    wr.release()
    src = VideoSource(path, fps=5.0)
    assert (src.w, src.h) == (64, 48) and src.duration == pytest.approx(2.0, abs=0.1)
    got = list(src)
    assert len(got) == 10 and got[0][0] == 0.0 and got[1][0] == pytest.approx(0.2)
    assert got[0][1].shape == (48, 64, 3)
    with pytest.raises(FileNotFoundError):
        VideoSource(str(tmp_path / "yok.avi"))


def test_view_dataclass_untouched():
    assert View() == View(0.0, 0.0, 0.0)
