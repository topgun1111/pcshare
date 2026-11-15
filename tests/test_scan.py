"""A1: hedefle aç + arka plan taraması (seçim çözümü, ilerleme, iptal, sürdürme)."""
import json

import cv2
import numpy as np

from tracker.bytetrack import ByteTracker, Track
from tracker.config import Config
from tracker.geometry import BBox
from tracker.offline import VideoSource
from tracker.scan import Scan, Selection, resolve_selection, run_scan, selection_from_args

W, H = 1000, 500
ANKLE = 15


def _kp(ankle_xy, conf=0.9):
    row = [0.0, 0.0, 0.0] * 17
    row[3 * ANKLE:3 * ANKLE + 3] = [ankle_xy[0] / W, ankle_xy[1] / H, conf]
    return [round(v, 4) for v in row]


def _doc(with_kp=True):
    # kişi 1: x 100-200, y 100-400 (boy 300, ayak bileği (150, 380)); kişi 2: x 600-700
    d = [[1, 0.1, 0.2, 0.2, 0.8, 0.9], [2, 0.6, 0.2, 0.7, 0.8, 0.9]]
    s = {"t": 1.0, "d": d}
    if with_kp:
        s["kp"] = {"1": _kp((150, 380))}
    return {"video": {"w": W, "h": H}, "samples": [s]}


CFG = Config()


def test_point_near_joint_resolves_part_else_person():
    doc = _doc()
    assert resolve_selection(doc, Selection.point(1.0, 0.15, 0.76), CFG) == (1, ANKLE)
    assert resolve_selection(doc, Selection.point(1.0, 0.15, 0.50), CFG) == (1, None)
    assert resolve_selection(doc, Selection.point(1.0, 0.65, 0.50), CFG) == (2, None)
    assert resolve_selection(doc, Selection.point(1.0, 0.40, 0.50), CFG) is None
    nokp = _doc(with_kp=False)  # eklem verisi yok → kişi
    assert resolve_selection(nokp, Selection.point(1.0, 0.15, 0.76), CFG) == (1, None)


def test_rect_resolves_person_or_joint():
    doc = _doc()
    big = Selection.rect(1.0, 0.09, 0.15, 0.21, 0.85)  # kişi 1'in hepsi
    assert resolve_selection(doc, big, CFG) == (1, None)
    small = Selection.rect(1.0, 0.14, 0.74, 0.16, 0.78)  # ayak bileği çevresi
    assert resolve_selection(doc, small, CFG) == (1, ANKLE)
    mid = Selection.rect(1.0, 0.12, 0.25, 0.18, 0.45)  # kutu içinde, eklemsiz
    assert resolve_selection(doc, mid, CFG) == (1, None)
    assert resolve_selection(doc, Selection.rect(1.0, 0.3, 0.2, 0.4, 0.8), CFG) is None


def test_selection_from_args_and_normalised_rect():
    class A:
        select_point = None
        select_rect = (2.0, 0.6, 0.7, 0.2, 0.1)

    s = selection_from_args(A())
    assert s.kind == "rect" and (s.x1, s.y1, s.x2, s.y2) == (0.2, 0.1, 0.6, 0.7)
    A.select_rect, A.select_point = None, (1.0, 0.5, 0.5)
    assert selection_from_args(A()).kind == "point"
    A.select_point = None
    assert selection_from_args(A()) is None


class FakeSrc:
    w, h, start = W, H, 0.0

    def __init__(self, n=30, fps=10.0, hook=None):
        self.n, self.fps, self.hook = n, fps, hook

    def __iter__(self):
        k0 = int(round(self.start * self.fps)) + (1 if self.start else 0)
        for k in range(k0, self.n):
            if self.hook:
                self.hook(k)
            yield k / self.fps, np.zeros((H, W, 3), np.uint8)


class FakePipe:
    def __init__(self):
        self.tracker = ByteTracker()
        self.ids = []

    def step(self, frame):
        tid = self.tracker._next_id if not self.ids else self.ids[-1]
        if not self.ids:
            self.tracker._next_id += 1
        self.ids.append(tid)
        return [Track(tid, BBox(100, 100, 200, 400), 0.9, 1, 0)]


def test_scan_resolves_target_when_reached_and_cleans_up(tmp_path):
    out = str(tmp_path / "v.mp4.track.json")
    sel = Selection.point(1.0, 0.15, 0.5)
    samples, size, sc = run_scan(FakeSrc(30), FakePipe(), CFG, out, sel, 10.0, 30)
    assert len(samples) == 30 and size == (W, H)
    assert sc.target == {"id": 1}
    prog = json.loads((tmp_path / "v.mp4.track.json.scan.json").read_text())
    assert prog["state"] == "done" and prog["target"] == {"id": 1} and prog["pct"] == 100
    assert not (tmp_path / "v.mp4.track.json.partial.json").exists()


def test_scan_unresolvable_target_reports_error(tmp_path):
    out = str(tmp_path / "v.track.json")
    sel = Selection.point(1.0, 0.9, 0.1)  # kimse yok
    _, _, sc = run_scan(FakeSrc(40), FakePipe(), CFG, out, sel, 10.0, 40)
    assert sc.target is None and "kişi" in sc.error
    prog = json.loads((tmp_path / "v.track.json.scan.json").read_text())
    assert prog["state"] == "done" and prog["error"]


def test_cancel_keeps_partial_and_resume_continues_ids(tmp_path):
    out = tmp_path / "v.track.json"
    cancel = tmp_path / "v.track.json.scan.cancel"

    def hook(k):
        if k == 12:
            cancel.write_text("x")

    samples, _, sc = run_scan(FakeSrc(30, hook=hook), FakePipe(), CFG, str(out), None, 10.0, 30)
    assert sc.cancelled and 12 <= len(samples) < 30
    part = json.loads((tmp_path / "v.track.json.partial.json").read_text())
    assert len(part["samples"]) == len(samples)
    assert json.loads((tmp_path / "v.track.json.scan.json").read_text())["state"] == "cancelled"
    pipe = FakePipe()
    samples2, _, sc2 = run_scan(FakeSrc(30), pipe, CFG, str(out), None, 10.0, 30, resume=True)
    assert not sc2.cancelled and len(samples2) == 30
    ts = [s["t"] for s in samples2]
    assert ts == sorted(ts) and len(set(ts)) == 30  # tekrar yok
    assert pipe.tracker._next_id >= 2  # kimlikler önceki örneklerle çakışmaz
    assert not (tmp_path / "v.track.json.partial.json").exists()


def test_partial_with_other_fps_or_size_is_ignored(tmp_path):
    out = str(tmp_path / "v.track.json")
    a = Scan(out, CFG, None, (W, H), 10.0, 10)
    a.part.write_text(json.dumps({"fps": 10.0, "size": [W, H], "samples": [{"t": 0.0, "d": []}]}))
    assert len(a.load_partial()) == 1
    assert Scan(out, CFG, None, (W, H), 5.0, 10).load_partial() == []
    assert Scan(out, CFG, None, (640, 360), 10.0, 10).load_partial() == []


def test_video_source_start_skips_earlier_frames(tmp_path):
    p = str(tmp_path / "c.avi")
    vw = cv2.VideoWriter(p, cv2.VideoWriter_fourcc(*"MJPG"), 10.0, (64, 48))
    for k in range(30):
        vw.write(np.full((48, 48 + 16, 3), k * 8, np.uint8)[:48, :64])
    vw.release()
    full = [t for t, _ in VideoSource(p, 10.0)]
    part = [t for t, _ in VideoSource(p, 10.0, start=1.0)]
    assert full[0] == 0.0 and len(full) == 30
    assert part and part[0] > 1.0 - 1e-6 and part[-1] == full[-1]


def test_cli_parses_select_resume_and_progress():
    from tracker.main import build_parser

    a = build_parser().parse_args(["offline", "v.mp4", "--select-point", "1.5", "0.4", "0.6",
                                   "--resume"])
    assert a.select_point == [1.5, 0.4, 0.6] and a.resume and a.select_rect is None
    b = build_parser().parse_args(["offline", "v.mp4", "--select-rect", "2", "0.1", "0.2", "0.3",
                                   "0.4", "--progress"])
    assert b.select_rect == [2.0, 0.1, 0.2, 0.3, 0.4] and b.progress
