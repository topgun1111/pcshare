"""G3: canlıda Re-ID bayrakları (config/otomatik) ve eklem takibinde görünümle kurtarma."""
import json
import types
from pathlib import Path

import numpy as np

from tracker.config import Config
from tracker.geometry import BBox
from tracker.main import live_reid_plan
from tracker.pose import KEYPOINT_NAMES, KeypointTracker, Pose
from tracker.reid import PartAppearance

ROOT = Path(__file__).resolve().parent.parent
ANKLE = KEYPOINT_NAMES.index("sol ayak bileği")
W, H = 200, 120


def _args(**kw):
    return types.SimpleNamespace(**{"reid": False, "team": False, "number": False, **kw})


def test_live_reid_plan_auto_on_off_and_flags():
    cfg = Config()
    assert cfg.reid_live == "auto" and cfg.team_live is False and cfg.number_live is False
    assert live_reid_plan(cfg, _args(), True, False) == (True, False, False)  # model var → açık
    assert live_reid_plan(cfg, _args(), False, False) == (False, False, False)  # model yok → kapalı
    cfg.reid_live = "off"
    assert live_reid_plan(cfg, _args(), True, True) == (False, False, False)
    assert live_reid_plan(cfg, _args(reid=True), False, False)[0] is True  # --reid her zaman açar
    cfg.reid_live = "on"
    assert live_reid_plan(cfg, _args(), False, False)[0] is True
    cfg.reid_live, cfg.team_live, cfg.number_live = "auto", True, True
    assert live_reid_plan(cfg, _args(), True, False) == (True, True, False)  # numara modeli yok
    assert live_reid_plan(cfg, _args(), True, True) == (True, True, True)
    # Re-ID yok → takım/numara da yok
    assert live_reid_plan(cfg, _args(), False, True) == (False, False, False)
    assert live_reid_plan(Config(), _args(reid=True, team=True), True, False) == (True, True, False)


def test_config_roundtrip_keeps_new_flags(tmp_path):
    p = tmp_path / "c.json"
    p.write_text(json.dumps({"reid_live": "off", "team_live": True}), encoding="utf-8")
    cfg = Config.load(p)
    assert cfg.reid_live == "off" and cfg.team_live is True and cfg.kp_reid is True


class ColorEmbedder:
    """Kutudaki ortalama renk → L2-normalize vektör (kırmızı ve mavi dik)."""

    def embed(self, frame, boxes):
        rows = []
        for b in boxes:
            c = frame[int(b.y1):int(b.y2), int(b.x1):int(b.x2)].reshape(-1, 3).mean(axis=0)
            n = float(np.linalg.norm(c))
            rows.append(c / n if n > 0 else np.zeros(3))
        return np.array(rows, np.float32)


def _pose(x, y=100.0, h=80.0):
    k = np.zeros((17, 3), np.float32)
    k[ANKLE] = (x, y, 0.9)
    return Pose(BBox(x - 20, y - h + 5, x + 20, y + 5), 0.9, k)


def _frame(people):
    f = np.zeros((H, W, 3), np.uint8)
    for p, col in people:
        b = p.bbox
        f[int(b.y1):int(b.y2), int(b.x1):int(b.x2)] = col
    return f


RED, BLUE = (0, 0, 255), (255, 0, 0)


class Est:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def estimate(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        self.t += 0.1
        return self.t


def _run(appearance):
    red0 = _pose(150)
    red_back, blue_near = _pose(20), _pose(140)  # kırmızı uzak kenardan döndü; mavi öngörüye yakın
    seq = [[red0]] * 3 + [[]] * 6 + [[red_back, blue_near]]
    kt = KeypointTracker(Est(seq), red0, ANKLE, hold_s=0.5, clock=Clock(), appearance=appearance)
    for _ in range(3):
        kt.update(_frame([(red0, RED)]))
    for _ in range(6):
        kt.update(np.zeros((H, W, 3), np.uint8))
    return kt.update(_frame([(red_back, RED), (blue_near, BLUE)]))


def test_reappearing_person_found_by_appearance_in_crowd_not_by_proximity():
    cfg = Config()
    app = PartAppearance(ColorEmbedder(), cfg)
    b = _run(app)
    assert len(app.gallery) >= 1
    assert b is not None and b.contains(20, 100) and not b.contains(140, 100)  # kırmızı kişi
    b0 = _run(None)  # Re-ID yok: eski davranış öngörüye yakın (yanlış) kişiyi seçer
    assert b0 is not None and b0.contains(140, 100)


def test_pick_needs_threshold_margin_and_clean_crops():
    cfg = Config()
    app = PartAppearance(ColorEmbedder(), cfg)
    f = _frame([(_pose(150), RED)])
    assert app.pick(f, [_pose(150).bbox]) is None  # galeri boş
    app.observe(f, _pose(150).bbox, [], 0.0)
    assert len(app.gallery) == 1
    app.observe(f, _pose(150).bbox, [], 0.1)  # reid_every_s (0.3) dolmadı
    two = _frame([(_pose(40), RED), (_pose(150), RED)])  # iki aynı görünüm → marj yok
    assert app.pick(two, [_pose(40).bbox, _pose(150).bbox]) is None
    mix = _frame([(_pose(40), BLUE), (_pose(150), RED)])
    assert app.pick(mix, [_pose(40).bbox, _pose(150).bbox]) == 1
    assert app.pick(_frame([(_pose(40), BLUE)]), [_pose(40).bbox]) is None  # eşik altı
    app.observe(f, BBox(0, 0, 30, 5), [], 5.0)  # küçük/kenarda kırpıntı: galeri büyümez
    assert len(app.gallery) == 1
    app.clear()
    assert len(app.gallery) == 0


def test_g3_wiring_files_and_optional_model_in_manifest():
    man = json.loads((ROOT / "models" / "manifest.json").read_text(encoding="utf-8"))
    osnet = [e for e in man["files"] if e["path"] == "models/osnet_x0_25.onnx"]
    assert osnet and osnet[0]["optional"] is True
    wf = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    assert "export_reid.py" in wf and "continue-on-error: true" in wf
    assert wf.index("export_reid.py") < wf.index("verify_models.py")  # doğrulamadan önce üretilir
    ex = (ROOT / "scripts" / "export_reid.py").read_text(encoding="utf-8")
    assert "256, 128" in ex and "dynamic_axes" in ex and "torchreid" in ex
    m = (ROOT / "models" / "MODELLER.txt").read_text(encoding="ascii")
    assert "reid_live" in m and "osnet_x0_25.onnx" in m
    n = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    assert "osnet_x0_25.onnx" in n and "ImageNet" in n
