"""B1: offline hatta eklem (track.json sürüm 2), parça hedefi `{id, part}` ve parça görünümleri."""
import json

import numpy as np
import pytest

from tracker.bytetrack import Track
from tracker.config import Config
from tracker.geometry import BBox
from tracker.main import main
from tracker.offline import (
    SCHEMA_VERSION,
    apply_target,
    associate_poses,
    build_part_views,
    load_doc,
    make_doc,
    part_boxes,
    run_detection,
    save_doc,
    save_views,
    views_path,
)
from tracker.pose import N_KPT, Pose

W, H = 200, 100
ANKLE = 15  # sol ayak bileği


def _cfg(**kw):
    return Config(ipc_path="x", **kw)


def _kp(x, y, c=0.9):
    row = [0.0, 0.0, 0.0] * N_KPT
    row[3 * ANKLE: 3 * ANKLE + 3] = [x, y, c]
    return row


def _sample(k, x, c=0.9, with_kp=True, pid=1):
    s = {"t": round(0.1 * k, 3), "d": [[pid, x - 0.02, 0.2, x + 0.02, 0.8, 0.9]]}
    if with_kp:
        s["kp"] = {str(pid): _kp(x, 0.75, c)}
    return s


def _doc(samples, pose=True):
    return make_doc("v.mp4", (W, H), 25.0, 10.0, 10.0, samples, pose=pose)


def _pose(box, x, y, c=0.9):
    k = np.zeros((N_KPT, 3), np.float32)
    k[ANKLE] = (x, y, c)
    return Pose(BBox(*box), 0.9, k)


class FakePipe:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def step(self, frame):
        self.i += 1
        return self.seq[self.i - 1]


class FakePose:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def estimate(self, frame):
        self.i += 1
        return self.seq[self.i - 1]


def test_associate_poses_by_iou_each_pose_once():
    tr = [Track(1, BBox(0, 0, 50, 100), 0.9, 3, 0), Track(2, BBox(100, 0, 150, 100), 0.9, 3, 0)]
    pa, pb, far = (_pose((2, 0, 52, 100), 10, 90), _pose((98, 0, 148, 100), 120, 90),
                   _pose((60, 0, 90, 100), 70, 90))
    got = associate_poses(tr, [pb, far, pa])
    assert got == {1: pa, 2: pb}
    assert associate_poses(tr, [far]) == {}  # IoU yetersiz → eşleşme yok


def test_run_detection_writes_kp_only_for_matched_tracks():
    frames = [(0.0, np.zeros((H, W, 3), np.uint8)), (0.1, np.zeros((H, W, 3), np.uint8))]
    seq = [[Track(7, BBox(20, 10, 60, 90), 0.9, 3, 0)], [Track(7, BBox(20, 10, 60, 90), 0.9, 3, 0)]]
    poses = [[_pose((20, 10, 60, 90), 40, 80, 0.876)], []]
    samples, _ = run_detection(iter(frames), FakePipe(seq), pose=FakePose(poses))
    row = samples[0]["kp"]["7"]
    assert len(row) == 3 * N_KPT
    assert row[3 * ANKLE: 3 * ANKLE + 3] == [0.2, 0.8, 0.88]  # normalize + yuvarlak
    assert "kp" not in samples[1]
    plain, _ = run_detection(iter(frames[:1]), FakePipe(seq[:1]))
    assert "kp" not in plain[0]  # pose verilmezse şema aynı


def test_schema_versions_and_v1_still_loads(tmp_path):
    assert _doc([], pose=False)["version"] == SCHEMA_VERSION == 1
    d2 = _doc([_sample(0, 0.5)])
    assert d2["version"] == 2
    p = tmp_path / "a.track.json"
    save_doc(d2, p)
    assert load_doc(p) == d2
    p.write_text(json.dumps({"version": 1, "video": {}, "samples": []}), encoding="utf-8")
    assert load_doc(p)["views"] == []
    p.write_text(json.dumps({"version": 3, "video": {}, "samples": []}), encoding="utf-8")
    with pytest.raises(ValueError):
        load_doc(p)


def test_part_boxes_square_pixels_and_confidence_gate():
    doc = _doc([_sample(0, 0.5), _sample(1, 0.5, c=0.1)])
    b = part_boxes(doc, {1}, ANKLE, _cfg(kp_box=0.1))
    assert set(b) == {0}  # düşük güvenli eklem atlandı
    cx, cy, w, h = b[0]
    assert (cx, cy) == (0.5, 0.75)
    assert w * W == pytest.approx(h * H) == pytest.approx(max(0.1 * 0.6 * H, 8.0))  # kare kutu


def test_part_views_follow_joint_and_fill_short_gap():
    cfg = _cfg()
    xs = [0.3 + 0.02 * k for k in range(30)]
    samples = [_sample(k, x, c=0.05 if k in (10, 11) else 0.9) for k, x in enumerate(xs)]
    views = build_part_views(_doc(samples), 1, ANKLE, cfg)
    assert views and all(v[1] >= cfg.min_zoom for v in views)
    pans = [v[2] for v in views]
    assert pans[-1] < pans[0]  # mpv: nesne sağa giderken pan_x azalır (görüntü sağa kayar)
    assert all(b <= a + 1e-6 for a, b in zip(pans, pans[1:], strict=False))  # düzgün, sıçrama yok
    assert max(v[1] for v in views) > 1.5  # küçük kutu → yakınlaşır
    # 2 örneklik eksiklik (0.2 sn ≤ offline_interp_s) → zoom-out yok: zoom ~sabit
    mid = [v[1] for v in views if 0.8 <= v[0] <= 1.4]
    assert max(mid) - min(mid) < 0.3


def test_part_views_long_loss_zooms_out_and_back():
    # 5 sn kayıp > bekleme (1) + zoom-out (2) + zoom-in (2) sn:
    # video-zoom 0'a (= 1x) inip geri çıkar
    samples = [_sample(k, 0.5, with_kp=not (8 <= k < 58)) for k in range(70)]
    views = build_part_views(_doc(samples), 1, ANKLE, _cfg())
    zooms = [v[1] for v in views]
    assert min(zooms) == pytest.approx(0.0, abs=0.05) and zooms[0] > 1.5 and zooms[-1] > 1.5


def test_apply_target_part_requires_kp_and_records_part():
    plain = _doc([_sample(k, 0.5, with_kp=False) for k in range(5)], pose=False)
    with pytest.raises(ValueError):
        apply_target(plain, 1, _cfg(), part=ANKLE)
    doc = _doc([_sample(k, 0.5) for k in range(5)])
    apply_target(doc, 1, _cfg(), part=ANKLE)
    assert doc["target"] == {"id": 1, "part": ANKLE} and doc["views"]
    with pytest.raises(ValueError):
        build_part_views(doc, 1, N_KPT, _cfg())


def test_views_file_stays_version_1_for_lua(tmp_path):
    doc = _doc([_sample(k, 0.5) for k in range(5)])
    apply_target(doc, 1, _cfg(), part=ANKLE)
    vp = tmp_path / "x.mp4.views.json"
    save_views(doc, vp)
    small = json.loads(vp.read_text(encoding="utf-8"))
    assert small["version"] == 1 and small["target"]["part"] == ANKLE and "samples" not in small
    assert views_path(tmp_path / "x.mp4.track.json") == vp


def test_cli_offline_view_part_by_name_and_index(tmp_path, capsys):
    p = tmp_path / "t.json"
    save_doc(_doc([_sample(k, 0.3 + 0.01 * k) for k in range(30)]), p)
    out = tmp_path / "o.json"
    args = ["offline-view", str(p), "--target-id", "1", "--part", "sol ayak bileği",
            "--out", str(out)]
    assert main(args) == 0
    assert "sol ayak bileği" in capsys.readouterr().out
    assert json.loads(out.read_text(encoding="utf-8"))["target"] == {"id": 1, "part": ANKLE}
    out2 = tmp_path / "o2.json"  # yalnız --part: kayıtlı hedefin eklemini değiştir
    assert main(["offline-view", str(out), "--part", "16", "--out", str(out2)]) == 0
    assert json.loads(out2.read_text(encoding="utf-8"))["target"] == {"id": 1, "part": 16}
    assert main(["offline-view", str(p), "--target-id", "1", "--part", "kanat"]) == 1
    plain = tmp_path / "p.json"
    save_doc(_doc([_sample(k, 0.5, with_kp=False) for k in range(5)], pose=False), plain)
    assert main(["offline-view", str(plain), "--target-id", "1", "--part", "0"]) == 1
