"""B2: dayanıklı eklem takibi — kareden çıkıp girme, kamera telafisi, One-Euro, yeniden bağlama."""
import cv2
import numpy as np
import pytest

from tracker.geometry import BBox
from tracker.point import CameraComp
from tracker.pose import KEYPOINT_NAMES, KeypointTracker, Pose
from tracker.smooth import OneEuro

ANKLE = KEYPOINT_NAMES.index("sol ayak bileği")
W, H = 200, 120
FRAME = np.zeros((H, W, 3), np.uint8)


def _pose(x, y=100.0, h=80.0, conf=0.9):
    """x'te duran, `h` boyunda kişi; eklem (x, y)."""
    k = np.zeros((17, 3), np.float32)
    k[ANKLE] = (x, y, conf)
    return Pose(BBox(x - 20, y - h + 5, x + 20, y + 5), 0.9, k)


class Est:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def estimate(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


class Clock:
    def __init__(self, dt=0.1):
        self.t, self.dt = 0.0, dt

    def __call__(self):
        self.t += self.dt
        return self.t


def _kt(seq, start=_pose(150), **kw):
    kw.setdefault("hold_s", 1.0)
    kw.setdefault("clock", Clock())
    return KeypointTracker(Est(seq), start, ANKLE, **kw)


def test_one_euro_smooths_rest_jitter_but_follows_fast_motion():
    f = OneEuro(min_cutoff=1.0, beta=0.0)
    ys = [f.update(10.0 + (0.5 if k % 2 else -0.5), 0.1 * k) for k in range(30)]
    assert max(ys[10:]) - min(ys[10:]) < 0.4  # ±0.5 titreşim bastırıldı
    g = OneEuro(min_cutoff=1.0, beta=2.0)
    out = [g.update(5.0 * k, 0.1 * k) for k in range(30)]
    assert 5.0 * 29 - out[-1] < 12  # hızlı harekette gecikme sınırlı
    g.reset()
    assert g.update(3.0, 0.0) == 3.0


def test_exit_and_reenter_same_side_resumes_where_it_left():
    # sağa yürür, kareden çıkar (kimse yok), aynı kenardan döner
    seq = [[_pose(170)], [_pose(190)], [], [], [], [_pose(196)], [_pose(190)]]
    kt = _kt(seq, _pose(150))
    assert kt.update(FRAME) is not None and kt.visible
    assert kt.update(FRAME) is not None
    b = kt.update(FRAME)  # çıktı: öngörü sürer, kare dışına gider
    assert b is not None and b.cx > 190
    assert kt.update(FRAME) is not None and kt.update(FRAME) is not None
    assert not kt.visible  # öngörülen konum kare dışında → artı çizilmez
    b = kt.update(FRAME)  # geri girdi: öngörüye yakın → aynı kişi
    assert b is not None and b.contains(196, 100) and kt.visible
    assert kt.update(FRAME).contains(190, 100)


def test_prediction_gives_up_after_hold_but_keeps_trying():
    seq = [[_pose(100)]] + [[]] * 15 + [[_pose(105)]]
    kt = _kt(seq, _pose(100), hold_s=0.5)
    assert kt.update(FRAME) is not None
    res = [kt.update(FRAME) for _ in range(15)]
    assert res[0] is not None and res[-1] is None  # hold_s (0.5 sn) sonra kayıp
    b = kt.update(FRAME)  # ama izleyici yaşıyor: geri gelince bağlanır
    assert b is not None and b.contains(105, 100)


def test_reenter_opposite_side_needs_single_matching_person():
    gone = [[_pose(190)]] + [[]] * 14
    fresh = [_pose(20)]  # sol kenardan girdi (öngörüden çok uzak)
    kt = _kt(gone + [fresh], _pose(180), hold_s=0.5)
    for _ in range(15):
        kt.update(FRAME)
    b = kt.update(FRAME)
    assert b is not None and b.contains(20, 100)  # uzun kayıp + tek uygun kişi

    kt2 = _kt(gone + [fresh], _pose(180), hold_s=0.5, reacq_single=False)
    for _ in range(15):
        kt2.update(FRAME)
    assert kt2.update(FRAME) is None  # kapalıyken bağlanmaz

    crowd = [_pose(20), _pose(60)]  # iki kişi: hangisi belli değil
    kt3 = _kt(gone + [crowd], _pose(180), hold_s=0.5)
    for _ in range(15):
        kt3.update(FRAME)
    assert kt3.update(FRAME) is None

    kt4 = _kt(gone + [[_pose(20, h=300.0)]], _pose(180), hold_s=0.5)  # boy tutarsız
    for _ in range(15):
        kt4.update(FRAME)
    assert kt4.update(FRAME) is None


def test_reacquire_picks_person_near_prediction_among_several():
    seq = [[_pose(100)], [_pose(110)], [], [_pose(30), _pose(121)]]
    kt = _kt(seq, _pose(100))
    kt.update(FRAME), kt.update(FRAME), kt.update(FRAME)
    b = kt.update(FRAME)
    assert b is not None and b.contains(121, 100) and not b.contains(30, 100)


def test_occluded_but_in_frame_keeps_point_visible():
    seq = [[_pose(100)], []]
    kt = _kt(seq, _pose(100))
    kt.update(FRAME)
    assert kt.update(FRAME) is not None and kt.visible  # kapanma: konum kare içinde


class PanCam:
    """Her güncellemede kamera 5 px sağa kayar: sabit bir dünya noktası kareden 5 px sola gider."""

    def __init__(self):
        self.off = 0.0

    def update(self, frame):
        self.off += 5.0

    def to_world(self, x, y):
        return x + self.off, y

    def to_frame(self, wx, wy):
        return wx - self.off, wy


def test_camera_compensation_moves_predicted_position_with_the_pan():
    cam = PanCam()
    seq = [[_pose(95)]] + [[]] * 3  # durağan parça ilk karede de 5 px sola kaymış görünür
    kt = _kt(seq, _pose(100), cam=cam)
    kt.update(FRAME)
    xs = [kt.update(FRAME).cx for _ in range(3)]
    assert xs[0] > xs[1] > xs[2]  # sahne kayarken durağan parça karede geriye kayar
    assert xs[0] - xs[2] == pytest.approx(10.0, abs=1.0)
    plain = _kt([[_pose(100)]] + [[]] * 3, _pose(100))  # kamerasız: konum yerinde kalır
    plain.update(FRAME)
    assert [plain.update(FRAME).cx for _ in range(3)][0] == pytest.approx(100.0, abs=1.0)


def test_smooth_option_filters_joint_position_not_when_off():
    seq = [[_pose(100 + (3 if k % 2 else -3))] for k in range(20)]
    on = _kt(seq, _pose(100), smooth=True)
    off = _kt(seq, _pose(100))
    a = [on.update(FRAME).cx for _ in range(20)][5:]
    b = [off.update(FRAME).cx for _ in range(20)][5:]
    assert max(a) - min(a) < max(b) - min(b)


def test_camera_comp_estimates_pan_on_textured_frames():
    rng = np.random.default_rng(1)
    base = cv2.GaussianBlur((rng.random((240, 320)) * 255).astype(np.uint8), (0, 0), 2)
    f1 = cv2.cvtColor(base, cv2.COLOR_GRAY2BGR)
    f2 = cv2.cvtColor(np.roll(base, 6, axis=1), cv2.COLOR_GRAY2BGR)  # içerik 6 px sağa
    cam = CameraComp(f1)
    cam.update(f2)
    wx, wy = cam.to_world(106.0, 100.0)  # f2'deki (106,100) = f1'deki (100,100)
    assert wx == pytest.approx(100.0, abs=1.5) and wy == pytest.approx(100.0, abs=1.5)
    fx, fy = cam.to_frame(wx, wy)
    assert (fx, fy) == pytest.approx((106.0, 100.0), abs=1e-6)
    blank = CameraComp(FRAME)  # özelliksiz kare: dönüşüm kimlik kalır
    blank.update(FRAME)
    assert blank.to_world(50.0, 60.0) == pytest.approx((50.0, 60.0))
