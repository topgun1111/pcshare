"""G1: kamera hareketi telafisi — CameraMotion, ByteTrack tahmini, Pipeline, offline."""
import cv2
import numpy as np

from tracker.bytetrack import ByteTracker, KalmanBox
from tracker.config import Config
from tracker.detector import Detection
from tracker.geometry import BBox
from tracker.gmc import CameraMotion
from tracker.offline import run_detection
from tracker.pipeline import Pipeline, make_gmc


def _tex(seed=1, h=240, w=320):
    rng = np.random.default_rng(seed)
    g = cv2.GaussianBlur((rng.random((h, w)) * 255).astype(np.uint8), (0, 0), 2)
    return g


def _bgr(g):
    return cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)


def det(x, y, w=40, h=100, s=0.9):
    return Detection(BBox.from_xywh(x, y, w, h), s)


def _translation(dx, dy=0.0):
    return np.array([[1, 0, dx], [0, 1, dy], [0, 0, 1.0]])


def test_kalman_compensate_translation_scale_and_velocity():
    kf = KalmanBox(BBox(100, 100, 140, 200))
    kf.x[4:6] = (4.0, -2.0)
    kf.compensate(_translation(-30, 10))
    assert abs(kf.x[0] - 90) < 1e-9 and abs(kf.x[1] - 160) < 1e-9
    assert tuple(kf.x[4:6]) == (4.0, -2.0) and kf.x[3] == 100
    kf2 = KalmanBox(BBox(100, 100, 140, 200))
    kf2.x[4:6] = (4.0, 0.0)
    kf2.compensate(np.diag([2.0, 2.0, 1.0]))  # kamera 2x yakınlaştı
    assert abs(kf2.x[3] - 200) < 1e-9 and abs(kf2.x[4] - 8.0) < 1e-9


def test_bytetrack_keeps_id_when_camera_pans_beyond_iou_reach():
    # kişi kare içinde sabit; kamera 70 px kaydı → kutu 70 px kayar (IoU 0)
    plain, comp = ByteTracker(), ByteTracker()
    for tr in (plain, comp):
        tr.update([det(100, 50)])
    p = plain.update([det(170, 50)])
    c = comp.update([det(170, 50)], motion=_translation(70))
    assert p[0].id != 1  # telafisiz: yeni kimlik
    assert c[0].id == 1  # telafili: aynı kimlik


def test_bytetrack_motion_none_is_unchanged_behaviour():
    tr = ByteTracker()
    a = tr.update([det(100, 50)])
    b = tr.update([det(104, 50)], motion=None)
    assert a[0].id == b[0].id == 1


def test_camera_motion_step_is_prev_to_cur_translation():
    base = _tex()
    cam = CameraMotion(_bgr(base))
    M = cam.step(_bgr(np.roll(base, 6, axis=1)))  # içerik 6 px sağa
    assert cam.ok
    x, y = M[:2, :2] @ np.array([100.0, 100.0]) + M[:2, 2]
    assert abs(x - 106) < 1.5 and abs(y - 100) < 1.5
    wx, wy = cam.to_world(106.0, 100.0)
    assert abs(wx - 100) < 1.5 and abs(wy - 100) < 1.5


def test_camera_motion_masks_foreground_boxes():
    base = _tex(2)
    f1 = base.copy()
    f2 = np.roll(base, 5, axis=1)
    f1[60:160, 100:160] = _tex(7)[60:160, 100:160]  # kamerayla ters hareket eden "kişi"
    f2[60:160, 100:160] = np.roll(_tex(7), -9, axis=1)[60:160, 100:160]
    cam = CameraMotion(_bgr(f1))
    M = cam.step(_bgr(f2), boxes=[BBox(95, 55, 165, 165)])
    assert abs(M[0, 2] - 5) < 1.5


def test_camera_motion_blank_cut_and_reset_give_identity():
    blank = np.zeros((120, 200, 3), np.uint8)
    cam = CameraMotion(blank)
    assert np.allclose(cam.step(blank), np.eye(3)) and not cam.ok
    big = _bgr(_tex(3, 240, 640))  # çekim kesmesi: bambaşka içerik
    cam2 = CameraMotion(big)
    M = cam2.step(_bgr(_tex(4, 240, 640)))
    assert np.allclose(M, np.eye(3), atol=1e-9) or abs(M[0, 2]) <= 0.5 * 640
    cam2.reset()
    assert cam2.cprev is None and np.allclose(cam2.W, np.eye(3))
    assert np.allclose(cam2.step(big), np.eye(3))  # reset sonrası ilk kare: kimlik


class _Det:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def detect(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


class _FakeGmc:
    def __init__(self, M=None, boom=False):
        self.M, self.boom, self.calls, self.resets = M, boom, [], 0

    def step(self, frame, boxes=()):
        self.calls.append(list(boxes))
        if self.boom:
            raise RuntimeError("cv2 hatası")
        return self.M

    def reset(self):
        self.resets += 1


def test_pipeline_passes_motion_and_masks_last_boxes():
    g = _FakeGmc(_translation(70))
    pipe = Pipeline(_Det([[det(100, 50)], [det(170, 50)]]), gmc=g)
    f = np.zeros((10, 10, 3), np.uint8)
    pipe.step(f)
    out = pipe.step(f)
    assert out[0].id == 1  # kamera 70 px kaydı → aynı kimlik
    assert g.calls[0] == [] and len(g.calls[1]) == 1  # 2. karede 1. karedeki kutu maskelenir


def test_pipeline_survives_gmc_failure_and_resets_it():
    g = _FakeGmc(boom=True)
    pipe = Pipeline(_Det([[det(100, 50)], [det(104, 50)]]), gmc=g)
    f = np.zeros((10, 10, 3), np.uint8)
    pipe.step(f)
    assert pipe.step(f)[0].id == 1
    pipe.reset()
    assert g.resets == 1


def test_make_gmc_follows_config():
    assert make_gmc(Config(gmc=False)) is None
    assert isinstance(make_gmc(Config(gmc=True)), CameraMotion)


def test_offline_scan_keeps_one_id_through_a_camera_pan():
    base = _tex(5, 240, 640)
    frames, dets = [], []
    for k in range(6):
        g = np.roll(base, 40 * k, axis=1)  # kamera her örnekte 40 px kayar
        frames.append((k * 0.1, _bgr(g)))
        dets.append([det(300 + 40 * k, 60)])  # sahnede sabit kişi → karede 40 px kayar
    cfg = Config()
    pipe = Pipeline(_Det(dets), ByteTracker(), gmc=make_gmc(cfg))
    samples, _ = run_detection(iter(frames), pipe)
    assert {d[0] for s in samples for d in s["d"]} == {1}
    plain = Pipeline(_Det(dets), ByteTracker())
    s2, _ = run_detection(iter(frames), plain)
    assert len({d[0] for s in s2 for d in s["d"]}) > 1  # telafisiz kimlik kopar
