"""Vücut parçası (eklem) takibi: sentetik pose çıktısıyla."""
import numpy as np
import pytest

from tracker.config import Config
from tracker.detector import LetterboxInfo
from tracker.geometry import BBox
from tracker.live import LiveController
from tracker.pipeline import NullPipeline
from tracker.pose import (
    KEYPOINT_NAMES,
    KeypointTracker,
    Pose,
    PoseEstimator,
    decode_yolo_pose,
    nearest_keypoint,
)

INFO = LetterboxInfo(scale=0.5, pad_x=0, pad_y=40)
ANKLE = KEYPOINT_NAMES.index("sol ayak bileği")


def _row(cx, cy, w, h, score, kpts):
    r = np.zeros(56, np.float32)
    r[:5] = (cx, cy, w, h, score)
    for k, (x, y, c) in kpts.items():
        r[5 + 3 * k: 8 + 3 * k] = (x, y, c)
    return r


def test_decode_maps_boxes_and_keypoints_back_to_frame_pixels():
    out = np.stack([_row(100, 240, 60, 200, 0.9, {ANKLE: (90, 330, 0.8)}),
                    _row(400, 240, 60, 200, 0.1, {})], axis=1)[None]  # [1,56,2]
    poses = decode_yolo_pose(out, INFO, 1280, 720, conf_thres=0.3)
    assert len(poses) == 1
    p = poses[0]
    assert (p.bbox.x1, p.bbox.x2) == (140.0, 260.0)  # (70..130)/0.5
    assert (p.bbox.y1, p.bbox.y2) == (200.0, 600.0)  # (140..340 - 40)/0.5
    assert tuple(p.kpts[ANKLE]) == (180.0, 580.0, pytest.approx(0.8))
    assert decode_yolo_pose(out, INFO, 1280, 720, conf_thres=0.95) == []
    assert len(decode_yolo_pose(out.transpose(0, 2, 1), INFO, 1280, 720, 0.3)) == 1
    with pytest.raises(ValueError, match="56"):
        decode_yolo_pose(np.zeros((1, 84, 10), np.float32), INFO, 1280, 720)


def _pose(x, y, conf=0.9, box=(80, 20, 120, 100)):
    k = np.zeros((17, 3), np.float32)
    k[ANKLE] = (x, y, conf)
    return Pose(BBox(*box), 0.9, k)


def test_nearest_keypoint_respects_tolerance_and_confidence():
    poses = [_pose(100, 95)]
    assert nearest_keypoint(poses, 102, 93) == (0, ANKLE)
    assert nearest_keypoint(poses, 100, 60) is None  # çok uzak (boy 80 * 0.12 = 9.6 px)
    assert nearest_keypoint([_pose(100, 95, conf=0.1)], 100, 95) is None


class FakeEst:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def estimate(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


FRAME = np.zeros((120, 200, 3), np.uint8)


def test_keypoint_tracker_follows_same_person_and_loses_on_low_confidence():
    other = _pose(30, 90, box=(10, 15, 50, 100))
    est = FakeEst([[other, _pose(104, 96, box=(84, 21, 124, 101))],
                   [_pose(110, 97, conf=0.1, box=(90, 22, 130, 102))],
                   []])
    kt = KeypointTracker(est, _pose(100, 95), ANKLE)
    assert kt.label == "sol ayak bileği"
    b = kt.update(FRAME)
    assert b.contains(104, 96) and not b.contains(30, 90)  # diğer kişiye atlamadı
    assert kt.update(FRAME) is None  # eklem güveni düşük
    assert kt.update(FRAME) is None  # kimse yok


def test_estimator_with_fake_session():
    class Sess:
        def get_inputs(self):
            return [type("I", (), {"name": "images", "type": "tensor(float)",
                                   "shape": [1, 3, 640, 640]})()]

        def run(self, _, feed):
            assert feed["images"].shape == (1, 3, 640, 640)
            row = _row(320, 320, 100, 300, 0.9, {ANKLE: (320, 460, 0.9)})
            return [row[:, None][None]]

    est = PoseEstimator(session=Sess())
    poses = est.estimate(np.zeros((640, 640, 3), np.uint8))
    assert len(poses) == 1 and poses[0].kpts[ANKLE][2] > 0.8


def _click(px, py):
    return {"event": "client-message",
            "args": ["track-point", str(px / 200), str(py / 120), "200", "120", "1"]}


def _ctl(est):
    fell_back = []

    class P:
        bbox = BBox(0, 0, 10, 10)

        def update(self, f):
            return self.bbox

    def factory(frame, px, py, cfg, box=None):
        fell_back.append((px, py))
        return P()

    c = LiveController(NullPipeline(), Config(ipc_path="x"), point_factory=factory, pose=est)
    return c, fell_back


def test_right_click_near_joint_tracks_joint_else_falls_back_to_object():
    c, fb = _ctl(FakeEst([[_pose(100, 95)]]))
    c.handle_event(_click(101, 94))
    out = c.step(FRAME, 1.0)
    assert out.osd == "Hedef: sol ayak bileği" and not fb
    assert c.hud_text() == "Takip: sol ayak bileği" and c.dot is not None

    c2, fb2 = _ctl(FakeEst([[_pose(100, 95)]]))
    c2.handle_event(_click(170, 30))  # eklemden uzak: nesne takibi
    assert c2.step(FRAME, 1.0).osd == "Hedef: nesne" and fb2

    c3, fb3 = _ctl(None)  # pose modeli yok: eski davranış
    c3.handle_event(_click(101, 94))
    assert c3.step(FRAME, 1.0).osd == "Hedef: nesne" and fb3


def test_keypoint_tracker_hold_survives_brief_confidence_drop_then_loses():
    est = FakeEst([[_pose(104, 96, box=(84, 21, 124, 101))],
                   [_pose(110, 97, conf=0.1, box=(90, 22, 130, 102))],  # eklem güveni düştü
                   [],  # kişi de kayıp
                   [_pose(112, 98, box=(92, 23, 132, 103))],  # geri geldi
                   [], [], []])
    kt = KeypointTracker(est, _pose(100, 95), ANKLE, hold=2)
    assert kt.update(FRAME) is not None
    b = kt.update(FRAME)  # güven düşük ama kişi var: eklem kişiyle birlikte kayar
    assert b is not None and b.cx > 104
    assert kt.update(FRAME) is not None  # kişi yok: hâlâ tolerans içinde
    assert kt.update(FRAME).contains(112, 98)  # geri geldi: sayaç sıfırlanır
    assert kt.update(FRAME) is not None and kt.update(FRAME) is not None
    assert kt.update(FRAME) is None  # tolerans (2) aşıldı → kayıp
