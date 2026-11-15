"""Android Part 10b reference: per-keypoint One-Euro, off-screen propagation, parent-joint inference."""
import numpy as np
import pytest

from tracker.config import Config
from tracker.offline import build_preset_views, make_doc, run_detection
from tracker.offscreen import propagate_boxes, smooth_keypoints
from tracker.parts import group_box, infer_joint, preset_boxes
from tracker.pipeline import cam_row

W, H = 1000, 500


def _row(over=None, x=0.5, y=0.5, conf=0.9):
    over = over or {}
    row = []
    for j in range(17):
        row += list(over.get(j, (x + 0.01 * j, y + 0.02 * j, conf)))
    return row


def _doc(samples):
    return make_doc("v.mp4", (W, H), 30.0, 10.0, 10.0, samples, pose=True)


def _s(i, box=(0.4, 0.2, 0.5, 0.9), kp=None, **extra):
    s = {"t": round(i * 0.1, 3), "d": [[1, *box, 0.9]] if box else []}
    if kp is not None:
        s["kp"] = {"1": kp}
    return {**s, **extra}


def test_smooth_keypoints_reduces_jitter_and_keeps_low_conf_joints():
    rng = np.random.default_rng(3)
    samples = []
    for i in range(30):
        jit = rng.uniform(-0.01, 0.01)
        kp = _row({15: (0.45 + jit, 0.8, 0.9), 16: (0.5 + jit, 0.8, 0.1)})
        samples.append(_s(i, kp=kp))
    doc = _doc(samples)
    out = smooth_keypoints(doc, {1}, Config())
    raw = [s["kp"]["1"][45] for s in samples]
    sm = [o["1"][45] for o in out]
    assert np.std(sm[5:]) < 0.6 * np.std(raw[5:])
    assert [o["1"][48] for o in out] == [s["kp"]["1"][48] for s in samples]  # conf 0.1: dokunulmaz
    assert out[0]["1"][45] == pytest.approx(raw[0])  # ilk değer aynen


def test_smooth_keypoints_restarts_after_cut_and_long_gap():
    def kp(x):
        return _row({0: (x, 0.3, 0.9)})
    samples = [_s(0, kp=kp(0.2)), _s(1, kp=kp(0.2)), _s(2, kp=kp(0.8), cut=1),
               _s(30, kp=kp(0.2)), _s(31, kp=kp(0.9))]
    out = smooth_keypoints(_doc(samples), {1}, Config(kp_hold_s=1.5))
    assert out[2]["1"][0] == pytest.approx(0.8)  # kesmeden sonra süzgeç yeniden başlar
    assert out[3]["1"][0] == pytest.approx(0.2)  # 2.8 sn boşluk > kp_hold_s
    assert 0.2 < out[4]["1"][0] < 0.9
    assert smooth_keypoints(_doc(samples), {2}, Config()) == [{}, {}, {}, {}, {}]  # başka kimlik


def _box(cx, cy=0.5, w=0.1, h=0.2):
    return [cx, cy, w, h]


def test_propagation_continues_with_damped_velocity_and_stops_after_hold():
    n = 20
    doc = _doc([_s(i) for i in range(n)])
    boxes = {i: _box(0.5 + 0.05 * i) for i in range(5)}  # 0.05 / adım sağa; 5'ten sonra yok
    out = propagate_boxes(doc, boxes, Config(kp_hold_s=0.5, offline_interp_s=0.0))
    assert sorted(out) == [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]  # 0.5 sn = 5 örnek
    xs = [out[i][0] for i in range(4, 10)]
    steps = np.diff(xs)
    assert all(s > 0 for s in steps) and all(b < a for a, b in zip(steps, steps[1:], strict=False))
    assert steps[0] < 0.05 and out[5][2:] == [0.1, 0.2]  # sönümlenir; boyut korunur


def test_propagation_follows_the_background_when_the_camera_pans():
    cam = [1.0, 0.0, -0.1, 0.0]  # arka plan her adımda 0.1·h = 0.05·w sola kayar
    samples = [_s(i, cam=cam) for i in range(10)]
    doc = _doc(samples)
    out = propagate_boxes(doc, {0: _box(0.5), 1: _box(0.5 - 0.05)}, Config(offline_interp_s=0.0))
    # parça arka planda duruyor: gözlemlerde de aynı hızla kayıyor -> hız ~ 0 dünya, kamera kaydırır
    assert out[2][0] < out[1][0] and out[3][0] < out[2][0]
    assert out[2][0] == pytest.approx(0.5 - 0.10, abs=1e-6)  # tam arka plan kayması


def test_short_gaps_are_left_to_linear_interpolation_and_cuts_stop_propagation():
    doc = _doc([_s(i) for i in range(10)])
    boxes = {0: _box(0.3), 1: _box(0.35), 4: _box(0.5)}
    out = propagate_boxes(doc, boxes, Config(offline_interp_s=0.6))
    assert 2 not in out and 3 not in out  # 0.3 sn boşluk: doğrusal dolgu `_views_from_boxes`'ta
    assert 5 in out  # 4'ten sonra gerçek kayıp: taşınır
    samples = [_s(i) for i in range(10)]
    samples[4]["cut"] = 1
    out = propagate_boxes(_doc(samples), {0: _box(0.3), 1: _box(0.35)},
                          Config(kp_hold_s=1.5, offline_interp_s=0.0))
    assert sorted(out) == [0, 1, 2, 3]  # kesmede durur


def _exit_reenter(side_out, side_in):
    """Parça `side_out` kenarından çıkar, 8 örnek yok, `side_in` kenarından döner (kamera sabit)."""
    xs_out = [0.5 + 0.1 * (i + 1) * (1 if side_out == "r" else -1) for i in range(4)]
    boxes = {i: _box(x) for i, x in enumerate(xs_out)}
    for k in range(4):
        boxes[12 + k] = _box(1.0 - 0.1 * (k + 1) if side_in == "r" else 0.1 * (k + 1))
    samples = []
    for i in range(16):
        d = []
        if i in boxes:
            cx, cy, w, h = boxes[i]
            d = [[1, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 0.9]]
        kp = _row({j: (boxes[i][0], boxes[i][1], 0.9) for j in range(17)}) if i in boxes else None
        samples.append({"t": round(i * 0.1, 3), "d": d, **({"kp": {"1": kp}} if kp else {})})
    return _doc(samples)


@pytest.mark.parametrize(("out", "back"), [("r", "r"), ("r", "l"), ("l", "l"), ("l", "r")])
def test_exit_and_reentry_keep_following_and_come_back(out, back):
    doc = _exit_reenter(out, back)
    cfg = Config(kp_hold_s=0.5, lost_hold_s=0.3, lost_zoomout_s=0.4, offline_interp_s=0.0, max_zoom=2.5)
    on = build_preset_views(doc, 1, "left_foot", cfg)
    off = build_preset_views(doc, 1, "left_foot", Config(**{**cfg.__dict__, "kp_world": False, "kp_filter": False}))
    assert on and on != off
    pan = {k[0]: k[2] for k in on}
    last = max(pan)
    assert last == pytest.approx(1.5)
    # kayıpta (örnek 4..8) kamera çıkış kenarına doğru kayar: sağdan çıkan parça -> pan_x negatif
    t_exit = min(k[0] for k in on if k[0] >= 0.5)
    exit_pans = [k[2] for k in on if 0.4 <= k[0] <= 0.8]
    assert exit_pans and (min(exit_pans) < -0.01 if out == "r" else max(exit_pans) > 0.01), (t_exit, exit_pans)
    # dönüşte hedefe geri kilitlenir: son pan, dönülen kenarın işaretinde
    assert (on[-1][2] < 0) if back == "r" else (on[-1][2] > 0)


def test_infer_joint_extrapolates_the_limb_and_needs_confident_parents():
    row = _row({11: (0.5, 0.4, 0.9), 13: (0.5, 0.6, 0.9), 15: (0.5, 0.8, 0.1)})
    x, y, c = infer_joint(row, 15, W, H, 0.35)
    assert (x, y) == pytest.approx((500.0, 400.0)) and c == pytest.approx(0.45)
    assert infer_joint(row, 13, W, H, 0.35) is None  # diz kestirilmez
    low = _row({11: (0.5, 0.4, 0.2), 13: (0.5, 0.6, 0.9)})
    assert infer_joint(low, 15, W, H, 0.35) is None


def test_group_box_uses_inferred_ankle_before_the_person_slice():
    row = _row({11: (0.5, 0.4, 0.9), 13: (0.5, 0.6, 0.9), 15: (0.5, 0.8, 0.1)})
    off = group_box((15,), row, W, H, 400.0, 0.35, 0.10)
    on = group_box((15,), row, W, H, 400.0, 0.35, 0.10, infer=True)
    assert off is None and on[0][1] == pytest.approx(0.8) and on[1] == pytest.approx(0.45)
    leg_off = group_box((11, 13, 15), row, W, H, 400.0, 0.35, 0.10)[0]
    leg_on = group_box((11, 13, 15), row, W, H, 400.0, 0.35, 0.10, infer=True)[0]
    assert leg_on[1] > leg_off[1] and leg_on[3] > leg_off[3]  # ayak bileği alt sınırı uzatır
    doc = _doc([_s(0, kp=row)])
    b = preset_boxes(doc, {1}, "left_foot", Config(kp_infer=True))[0]
    assert b[1] == pytest.approx(0.8)
    s = preset_boxes(doc, {1}, "left_foot", Config(kp_infer=False))[0]  # kişi alt kutusu
    assert s[1] == pytest.approx(0.2 + 0.7 * 0.94)


def test_cam_row_and_run_detection_store_camera_motion_only_when_ok():
    m = np.array([[0.99, -0.01, 12.0], [0.01, 0.99, -6.0], [0, 0, 1.0]])
    assert cam_row(m, 360) == [0.99, 0.01, 0.03333, -0.01667]

    class _Pipe:
        def __init__(self):
            self.i, self.cut, self.cam = 0, False, None
            self.tracker = type("T", (), {"boxes": []})()

        def step(self, frame):
            self.i += 1
            self.cam = [1.0, 0.0, 0.1, 0.0] if self.i == 2 else None
            self.cut = self.i == 3
            if self.i == 3:
                self.cam = [1.0, 0.0, 0.1, 0.0]  # kesmede asla yazılmaz
            return []

    frames = ((i * 0.1, np.zeros((H, W, 3), np.uint8)) for i in range(4))
    samples, _ = run_detection(frames, _Pipe())
    assert [s.get("cam") for s in samples] == [None, [1.0, 0.0, 0.1, 0.0], None, None]
