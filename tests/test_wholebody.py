"""O2: tüm-vücut poz (RTMPose-wholebody) — kırpma, SimCC çözme, eklem seçimi, takip, bağlantılar."""
import io
import json
import sys
import zipfile
from pathlib import Path

import numpy as np
import pytest

from tracker.config import Config
from tracker.geometry import BBox
from tracker.live import LiveController
from tracker.pipeline import NullPipeline
from tracker.pose import KEYPOINT_NAMES, KeypointTracker, Pose, nearest_keypoint
from tracker.wholebody import (
    FACE,
    LHAND,
    N_WB,
    RHAND,
    WB_NAMES,
    WholeBodyEstimator,
    crop_params,
    decode_simcc,
    parent_joint,
    preprocess_crop,
    split_outputs,
    to_frame,
)

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
FRAME = np.zeros((120, 200, 3), np.uint8)
SIZE = (256, 192)
L_TIP = WB_NAMES.index("sol el işaret 4")  # sol işaret parmağı ucu


def test_names_and_groups():
    assert len(WB_NAMES) == N_WB == 133 and len(set(WB_NAMES)) == 133
    assert WB_NAMES[:17] == KEYPOINT_NAMES
    assert len(FACE) == 68 and len(LHAND) == len(RHAND) == 21
    assert WB_NAMES[91] == "sol el bilek" and WB_NAMES[112] == "sağ el bilek"
    assert WB_NAMES[17] == "sol ayak başparmağı" and WB_NAMES[22] == "sağ topuk"


def test_parent_joint_maps_to_coco_17():
    assert parent_joint(5) == 5
    assert parent_joint(17) == 15 and parent_joint(19) == 15  # sol ayak → sol ayak bileği
    assert parent_joint(20) == 16 and parent_joint(22) == 16
    assert parent_joint(30) == 0  # yüz → burun
    assert parent_joint(95) == 9 and parent_joint(120) == 10  # sol/sağ el → bilek
    assert all(parent_joint(k) < 17 for k in range(N_WB))


def test_crop_params_keeps_model_aspect_and_pads():
    cx, cy, w, h = crop_params(BBox(100, 0, 140, 200), SIZE)  # ince uzun kişi
    assert (cx, cy) == (120, 100) and h == pytest.approx(200 * 1.25)
    assert w / h == pytest.approx(192 / 256)
    _, _, w2, h2 = crop_params(BBox(0, 0, 300, 100), SIZE)  # geniş kutu: yükseklik uzatılır
    assert w2 == pytest.approx(300 * 1.25) and w2 / h2 == pytest.approx(192 / 256)


def test_preprocess_and_to_frame_roundtrip():
    frame = np.full((300, 400, 3), 50, np.uint8)
    frame[100:140, 190:230] = (0, 0, 255)  # BGR kırmızı kare
    x, crop = preprocess_crop(frame, BBox(150, 50, 250, 250), SIZE)
    assert x.shape == (1, 3, 256, 192) and x.dtype == np.float32
    assert x[0, 0].max() > 1.5 > x[0, 2].max()  # RGB: kırmızı kanal yüksek, mavi düşük
    # model girdisinin merkezi = kutu merkezi; sol üst köşe = kırpma sol üstü
    c = to_frame(np.array([[96.0, 128.0], [0.0, 0.0]], np.float32), crop, SIZE)
    assert tuple(c[0]) == pytest.approx((200.0, 150.0))
    assert tuple(c[1]) == pytest.approx((crop[0] - crop[2] / 2, crop[1] - crop[3] / 2))


def _simcc(peaks, conf=0.9, k=N_WB, wx=384, hy=512):
    sx, sy = np.zeros((1, k, wx), np.float32), np.zeros((1, k, hy), np.float32)
    for i, (x, y) in peaks.items():
        sx[0, i, x] = conf
        sy[0, i, y] = conf
    return sx, sy


def test_decode_simcc_peak_halved_confidence_is_min_and_empty_is_unknown():
    sx, sy = _simcc({3: (100, 40)}, 0.8)
    sy[0, 3, 40] = 0.5  # y tepesi daha zayıf → güven = min
    locs, conf = decode_simcc(sx, sy)
    assert tuple(locs[3]) == (50.0, 20.0) and conf[3] == pytest.approx(0.5)
    assert conf[4] == 0.0 and tuple(locs[4]) == (-1.0, -1.0)  # hiç tepe yok
    with pytest.raises(ValueError):
        decode_simcc(np.zeros((1, 5, 8)), np.zeros((1, 6, 8)))


def test_split_outputs_finds_x_and_y_whatever_the_order():
    x, y = np.zeros((1, 133, 384)), np.zeros((1, 133, 512))
    assert split_outputs([x, y], SIZE)[0] is x
    assert split_outputs([y, x], SIZE)[0] is x
    with pytest.raises(ValueError):
        split_outputs([x, np.zeros((1, 133, 100))], SIZE)


class Sess:
    """Sahte RTMPose: sol işaret ucu girdinin merkezine, bilek merkezin solundan 48 px'e düşer."""

    def __init__(self, fail=False):
        self.fail, self.calls = fail, 0

    def get_inputs(self):
        return [type("I", (), {"name": "input", "type": "tensor(float)",
                               "shape": [1, 3, 256, 192]})()]

    def get_providers(self):
        return ["CPUExecutionProvider"]

    def run(self, _, feed):
        self.calls += 1
        if self.fail:
            raise RuntimeError("model hatası")
        assert feed["input"].shape == (1, 3, 256, 192)
        return list(_simcc({L_TIP: (192, 256), 91: (96, 256)}))  # girdi px = tepe / 2


def _pose(box=(60, 20, 140, 110), conf=0.9):
    k = np.zeros((17, 3), np.float32)
    k[9] = (100.0, 60.0, conf)  # sol bilek
    return Pose(BBox(*box), 0.9, k)


def test_estimator_refine_keeps_yolo_body_and_adds_wholebody_points():
    sess = Sess()
    est = WholeBodyEstimator(session=sess)
    p = _pose()
    r = est.refine(FRAME, p)
    assert r.kpts.shape == (N_WB, 3) and r.bbox == p.bbox
    assert np.array_equal(r.kpts[:17], p.kpts)  # vücut noktaları değişmez
    cx, cy = p.bbox.cx, p.bbox.cy
    assert tuple(r.kpts[L_TIP][:2]) == pytest.approx((cx, cy), abs=1e-3)
    assert r.kpts[L_TIP][2] == pytest.approx(0.9) and sess.calls == 1
    bad = WholeBodyEstimator(session=Sess(fail=True)).refine(FRAME, p)
    assert bad is p  # model hatası → 17 noktalı poz aynen


def test_nearest_keypoint_uses_fine_tolerance_for_wholebody_points():
    r = WholeBodyEstimator(session=Sess()).refine(FRAME, _pose())
    h = 90.0
    tip = r.kpts[L_TIP]
    near = nearest_keypoint([r], tip[0] + 0.03 * h, tip[1], 0.12, 0.35, 0.05, 0.3)
    assert near == (0, L_TIP)
    # ince nokta için 0.05h sınırı: 0.08h uzaktaki tık parmağı seçmez
    far = nearest_keypoint([r], tip[0] + 0.08 * h, tip[1], 0.12, 0.35, 0.05, 0.3)
    assert far is None or far[1] != L_TIP
    # düşük güvenli ince nokta seçilmez
    low = r.kpts.copy()
    low[L_TIP, 2] = 0.2
    assert nearest_keypoint([Pose(r.bbox, 0.9, low)], tip[0], tip[1], 0.12, 0.35, 0.05, 0.3) != (
        0, L_TIP)
    # 17 noktalı poz: eski davranış (kaba tolerans)
    assert nearest_keypoint([_pose()], 101, 61) == (0, 9)


class FakeEst:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def estimate(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


def test_keypoint_tracker_follows_hand_point_and_refines_only_matched_person():
    sess = Sess()
    wb = WholeBodyEstimator(session=sess)
    start = wb.refine(FRAME, _pose())
    other = _pose(box=(0, 10, 30, 100))
    est = FakeEst([[other, _pose(box=(64, 20, 144, 110))], [other, _pose(box=(68, 20, 148, 110))]])
    sess.calls = 0
    kt = KeypointTracker(est, start, L_TIP, 0.05, 0.3, refiner=wb)
    assert kt.label == "sol el işaret 4" and kt._ck == 9
    b = kt.update(FRAME)
    assert b is not None and sess.calls == 1  # yalnız eşleşen kişi, kare başına 1 çıkarım
    assert b.contains(104, 65)  # nokta kişiyle birlikte kaydı (kutu merkezi 104, 65)
    kt.update(FRAME)
    assert sess.calls == 2


def test_hand_point_survives_wholebody_failure_via_hold_and_body_joint_unaffected():
    wb_ok = WholeBodyEstimator(session=Sess())
    start = wb_ok.refine(FRAME, _pose())
    bad = WholeBodyEstimator(session=Sess(fail=True))
    kt = KeypointTracker(FakeEst([[_pose()]]), start, L_TIP, 0.05, 0.3, hold=2, refiner=bad)
    assert kt.update(FRAME) is not None  # model hatası: nokta kişiyle taşınır (hold içinde)
    assert kt.update(FRAME) is not None and kt.update(FRAME) is None  # hold bitince kayıp
    kt2 = KeypointTracker(FakeEst([[_pose()]]), start, 9, 0.1, 0.35, refiner=None)
    assert kt2.update(FRAME) is not None and kt2.label == "sol bilek"


def _click(px, py):
    return {"event": "client-message",
            "args": ["track-point", str(px / 200), str(py / 120), "200", "120", "1"]}


def _ctl(wb, pose_seq=None):
    fb = []

    class P:
        bbox = BBox(0, 0, 10, 10)

        def update(self, f):
            return self.bbox

    def factory(frame, px, py, cfg, box=None):
        fb.append((px, py))
        return P()

    c = LiveController(NullPipeline(), Config(ipc_path="x"), point_factory=factory,
                       pose=FakeEst(pose_seq or [[_pose()]]), wholebody=wb)
    return c, fb


def test_live_right_click_on_finger_tracks_finger_only_with_wholebody():
    wb = WholeBodyEstimator(session=Sess())
    c, fb = _ctl(wb)
    r = wb.refine(FRAME, _pose())
    tx, ty = float(r.kpts[L_TIP][0]), float(r.kpts[L_TIP][1])
    c.handle_event(_click(tx, ty))
    out = c.step(FRAME, 1.0)
    assert out.osd == "Hedef: sol el işaret 4" and not fb
    assert c.hud_text() == "Takip: sol el işaret 4"

    c2, fb2 = _ctl(None)  # model yok: parmak yok → eklem/nesne (eski davranış)
    c2.handle_event(_click(tx, ty))
    assert c2.step(FRAME, 1.0).osd != "Hedef: sol el işaret 4"


def test_config_defaults_and_files_wiring():
    cfg = Config(ipc_path="x")
    assert cfg.wholebody == "auto" and cfg.wholebody_model.endswith("rtmpose-wholebody.onnx")
    assert 0 < cfg.wb_tol < cfg.kp_tol and 0 < cfg.wb_min_conf < 1
    man = json.loads((ROOT / "models" / "manifest.json").read_text(encoding="utf-8"))
    e = [f for f in man["files"] if f["path"] == "models/rtmpose-wholebody.onnx"]
    assert e and e[0]["optional"] is True
    wf = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    assert "export_wholebody.py" in wf and wf.index("export_wholebody.py") < wf.index(
        "verify_models.py")
    assert "133" in wf
    m = (ROOT / "models" / "MODELLER.txt").read_text(encoding="ascii")
    assert "rtmpose-wholebody.onnx" in m and "wholebody" in m
    n = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    assert "rtmpose-wholebody.onnx" in n and "COCO-WholeBody" in n


def test_export_script_picks_end2end_from_archive(tmp_path):
    import export_wholebody as ex

    assert ex.find_onnx(["x/deploy.json", "x/end2end.onnx"]) == "x/end2end.onnx"
    assert ex.find_onnx(["a.onnx"]) == "a.onnx"
    assert ex.find_onnx(["a.onnx", "b.onnx"]) is None
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("m/end2end.onnx", b"1234")
    assert ex.extract_model(buf.getvalue(), tmp_path / "o.onnx") == 4
    bad = io.BytesIO()
    with zipfile.ZipFile(bad, "w") as z:
        z.writestr("readme.txt", b"x")
    with pytest.raises(ValueError):
        ex.extract_model(bad.getvalue(), tmp_path / "p.onnx")
