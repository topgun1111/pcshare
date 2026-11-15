"""G4: çekim kesmesi algılama — algılayıcı, Pipeline/ByteTrack, offline görünümler, canlı akış."""
import numpy as np
import pytest

cv2 = pytest.importorskip("cv2")

from tracker.bytetrack import ByteTracker, Track  # noqa: E402
from tracker.config import Config  # noqa: E402
from tracker.cuts import CutDetector  # noqa: E402
from tracker.detector import Detection  # noqa: E402
from tracker.geometry import BBox  # noqa: E402
from tracker.live import LiveController  # noqa: E402
from tracker.offline import (  # noqa: E402
    build_views,
    cut_indices,
    make_doc,
    run_detection,
)
from tracker.pipeline import Pipeline, make_cuts  # noqa: E402
from tracker.point import WorldPointTracker  # noqa: E402
from tracker.pose import KEYPOINT_NAMES, KeypointTracker, Pose  # noqa: E402


def _scene(seed: int, h=144, w=256):
    rng = np.random.default_rng(seed)
    base = rng.integers(0, 255, (h // 16, w // 16, 3), dtype=np.uint8)
    return cv2.resize(base, (w, h), interpolation=cv2.INTER_NEAREST)


A, B = _scene(1), _scene(2)


def test_detector_flags_hard_cut_not_static_shift_or_brightness():
    d = CutDetector()
    assert d.step(A) is False  # ilk kare: referans
    assert d.step(A.copy()) is False and d.last_diff == 0.0
    shifted = np.roll(A, 3, axis=1)  # küçük kaydırma (kamera pan)
    assert d.step(shifted) is False
    bright = np.clip(shifted.astype(np.int16) + 15, 0, 255).astype(np.uint8)
    assert d.step(bright) is False  # hafif parlaklık değişimi
    assert d.step(B) is True and d.last_hist >= 0.35 and d.last_diff >= 0.18
    assert d.step(B.copy()) is False  # yeni sahnede sakin
    d.reset()
    assert d.step(A) is False  # reset sonrası ilk kare


def test_detector_relaxes_thresholds_when_camera_estimation_collapsed():
    d0 = CutDetector()
    d0.step(A)
    d0.step(B)
    h, df = d0.last_hist, d0.last_diff
    strict = CutDetector(hist_thr=h * 1.1, diff_thr=df * 1.1)  # eşiğin hemen altında
    strict.step(A)
    assert strict.step(B) is False
    relaxed = CutDetector(hist_thr=h * 1.1, diff_thr=df * 1.1)
    relaxed.step(A)
    assert relaxed.step(B, cam_ok=False) is True  # LK çöktü → gevşek eşik
    ok = CutDetector(hist_thr=h * 1.1, diff_thr=df * 1.1)
    ok.step(A)
    assert ok.step(B, cam_ok=True) is False


class _Det:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def detect(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


class _ScriptCuts:
    def __init__(self, flags):
        self.flags, self.i, self.resets = flags, 0, 0

    def step(self, frame, cam_ok=None):
        f = self.flags[min(self.i, len(self.flags) - 1)]
        self.i += 1
        return f

    def reset(self):
        self.resets += 1


class _Gmc:
    ok = True

    def __init__(self):
        self.resets = 0

    def step(self, frame, boxes=()):
        return np.eye(3)

    def reset(self):
        self.resets += 1


def _det(x):
    return Detection(BBox(x, 20, x + 20, 80), 0.9)


def test_bytetracker_clear_keeps_id_counter():
    t = ByteTracker()
    assert [x.id for x in t.update([_det(10)])] == [1]
    t.clear()
    assert t.boxes == []
    assert [x.id for x in t.update([_det(12)])] == [2]  # eski kimlik yeniden verilmez


def test_pipeline_cut_clears_tracks_resets_gmc_and_flags():
    g = _Gmc()
    cuts = _ScriptCuts([False, False, True, False])
    pipe = Pipeline(_Det([[_det(10)]] * 4), ByteTracker(), gmc=g, cuts=cuts)
    frame = np.zeros((100, 200, 3), np.uint8)
    ids, flags = [], []
    for _ in range(4):
        ids.append([t.id for t in pipe.step(frame)])
        flags.append(pipe.cut)
    assert flags == [False, False, True, False]
    assert ids[:2] == [[1], [1]] and ids[2] == [2] and ids[3] == [2]  # kesmede yeni kimlik
    assert g.resets == 1
    pipe.reset()
    assert cuts.resets == 1 and pipe.cut is False


def test_pipeline_cut_failure_never_breaks_tracking():
    class Boom:
        def step(self, frame, cam_ok=None):
            raise RuntimeError("x")

    pipe = Pipeline(_Det([[_det(10)]]), ByteTracker(), cuts=Boom())
    assert [t.id for t in pipe.step(np.zeros((100, 200, 3), np.uint8))] == [1] and not pipe.cut


def test_make_cuts_follows_config():
    assert make_cuts(Config(ipc_path="x", cut_detect=False)) is None
    c = make_cuts(Config(ipc_path="x", cut_hist=0.4, cut_diff=0.3))
    assert c.hist_thr == 0.4 and c.diff_thr == 0.3


def test_run_detection_marks_cut_samples_and_make_doc_lists_them():
    cuts = _ScriptCuts([False, False, True, False])
    pipe = Pipeline(_Det([[_det(10)]] * 4), ByteTracker(), cuts=cuts)
    frames = [(0.1 * i, np.zeros((100, 200, 3), np.uint8)) for i in range(4)]
    samples, size = run_detection(iter(frames), pipe)
    assert [bool(s.get("cut")) for s in samples] == [False, False, True, False]
    doc = make_doc("v.mp4", size, 25.0, 0.4, 10.0, samples)
    assert doc["cuts"] == [pytest.approx(0.2)] and cut_indices(doc) == {2}
    plain = make_doc("v.mp4", size, 25.0, 0.4, 10.0, [{"t": 0.0, "d": []}])
    assert "cuts" not in plain


def _two_scene_doc(cut: bool, gap: bool = False):
    """0-5: kimlik 1 solda (x≈0.2), 6-11: kimlik 2 sağda (x≈0.8); kesme indeks 6."""
    samples = []
    for i in range(12):
        x = 0.2 if i < 6 else 0.8
        d = [[1 if i < 6 else 2, x - 0.03, 0.45, x + 0.03, 0.55, 0.9]]
        if gap and i == 5:
            d = []
        s = {"t": round(0.1 * i, 3), "d": d}
        if cut and i == 6:
            s["cut"] = 1
        samples.append(s)
    return {"version": 1, "video": {"w": 200, "h": 100}, "samples": samples}


def _pan(views, t):
    from tracker.offline import view_at

    return view_at(views, t)[1]


def test_views_do_not_smooth_across_a_cut():
    cfg = Config(ipc_path="x")
    with_cut = build_views(_two_scene_doc(True), {1, 2}, cfg)
    no_cut = build_views(_two_scene_doc(False), {1, 2}, cfg)
    # kesmeden hemen önceki örnek kendi sahnesinin görünümünde kalır; kesme yoksa komşuyla karışır
    assert _pan(with_cut, 0.5) == pytest.approx(_pan(with_cut, 0.0), abs=1e-3)
    assert _pan(no_cut, 0.5) != pytest.approx(_pan(no_cut, 0.0), abs=1e-3)
    assert _pan(with_cut, 0.6) == pytest.approx(_pan(with_cut, 1.1), abs=1e-3)


def test_short_gap_is_not_interpolated_across_a_cut():
    cfg = Config(ipc_path="x", offline_interp_s=1.0)
    gap_cut = build_views(_two_scene_doc(True, gap=True), {1, 2}, cfg)
    gap_nocut = build_views(_two_scene_doc(False, gap=True), {1, 2}, cfg)
    # kesmesiz: boşluk (t=0.5) iki komşu arasında doldurulur; kesmeli: eski sahnede kalır
    assert _pan(gap_cut, 0.5) == pytest.approx(_pan(gap_cut, 0.4), abs=1e-3)
    assert _pan(gap_nocut, 0.5) != pytest.approx(_pan(gap_nocut, 0.4), abs=1e-3)


def _tr(i, x1, y1, x2, y2):
    return Track(i, BBox(x1, y1, x2, y2), 0.9, 5, 0)


class _LivePipe:
    def __init__(self, seq):
        self.seq, self.i, self.cuts_seen = seq, 0, 0

    def step(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r

    def on_cut(self):
        self.cuts_seen += 1

    def reset(self):
        pass


def _click(px, py):
    return {"event": "client-message", "args": ["track-click", str(px / 200), str(py / 100),
                                                "200", "100", "1.0"]}


def test_live_cut_resets_pipeline_and_target_is_searched_under_new_id():
    frame = np.zeros((100, 200, 3), np.uint8)
    cuts = _ScriptCuts([False, False, True, False])
    pipe = _LivePipe([[_tr(3, 85, 40, 95, 60)], [_tr(3, 85, 40, 95, 60)], [_tr(9, 20, 40, 30, 60)]])
    c = LiveController(pipe, Config(ipc_path="x"), cuts=cuts)
    c.handle_event({"event": "client-message", "args": ["track-toggle", "on"]})
    c.handle_event(_click(90, 50))
    assert c.step(frame, 1.0).osd == "Hedef: ID 3"
    c.step(frame, 1.1)
    out = c.step(frame, 1.2)  # kesme: yeni sahnede ID 3 yok
    assert pipe.cuts_seen == 1 and c.cut_count == 1
    assert out.osd == "Hedef kayboldu" or "Çekim kesildi" in (out.osd or "")
    assert c.target_id == 3 and c.has_target  # kayıp akışına girer, hedef bırakılmaz


def test_live_cut_notifies_object_tracker_and_ignores_cuts_when_disabled():
    frame = np.zeros((100, 200, 3), np.uint8)

    class P:
        bbox = BBox(0, 0, 10, 10)
        got = 0

        def update(self, f):
            return self.bbox

        def on_cut(self):
            P.got += 1

    c = LiveController(_LivePipe([[]]), Config(ipc_path="x"), cuts=_ScriptCuts([False, True]),
                       point_factory=lambda *a, **k: P())
    c.handle_event({"event": "client-message", "args": ["track-toggle", "on"]})
    c.handle_event({"event": "client-message",
                    "args": ["track-point", "0.5", "0.5", "200", "100", "1"]})
    c.step(frame, 1.0)
    c.step(frame, 1.1)
    assert P.got == 1
    c2 = LiveController(_LivePipe([[]]), Config(ipc_path="x"))  # cuts yok: eski davranış
    c2.handle_event({"event": "client-message", "args": ["track-toggle", "on"]})
    assert c2.step(frame, 1.0).view is None and c2.cut_count == 0


ANKLE = KEYPOINT_NAMES.index("sol ayak bileği")


class _Est:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def estimate(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


class _Clock:
    t = 0.0

    def __call__(self):
        self.t += 0.1
        return self.t


def _pose(x, y=100.0, h=80.0):
    k = np.zeros((17, 3), np.float32)
    k[ANKLE] = (x, y, 0.9)
    return Pose(BBox(x - 20, y - h + 5, x + 20, y + 5), 0.9, k)


def test_keypoint_tracker_after_cut_does_not_rebind_by_proximity():
    frame = np.zeros((120, 200, 3), np.uint8)
    near, other = _pose(150), _pose(40)
    # kesmesiz (kontrol): kayıp başlar, öngörülene yakın kişi hemen bağlanır
    kt0 = KeypointTracker(_Est([[near, other]]), _pose(150), ANKLE, hold_s=1.0, clock=_Clock())
    kt0._blind_t = 0.5
    assert kt0.update(frame) is not None and kt0._blind_t is None
    # kesmeli: iki kişi → görünüm (Re-ID) yok → yakın olana BAĞLANMAZ; yalnız tek kişi kuralı
    kt = KeypointTracker(_Est([[near, other]]), _pose(150), ANKLE, hold_s=1.0, clock=_Clock())
    kt.on_cut()
    assert kt._cut and kt._blind_t is not None
    for _ in range(15):
        out = kt.update(frame)
    assert out is None and kt._blind_t is not None  # hold doldu, kimseye bağlanmadı
    # tek kişi kalınca (hold geçmiş) yeniden bağlanır ve kesme bayrağı temizlenir
    kt.est.seq = [[near]]
    assert kt.update(frame) is not None and not kt._cut
    # B2 kapalıyken (hold_s=0) on_cut etkisizdir
    kt2 = KeypointTracker(_Est([[near]]), _pose(150), ANKLE, hold_s=0.0)
    kt2.on_cut()
    assert not kt2._cut


def test_world_point_tracker_on_cut_resets_camera_history():
    rng = np.random.default_rng(5)
    f1 = cv2.GaussianBlur(rng.integers(0, 255, (360, 640, 3), dtype=np.uint8), (0, 0), 2.0)
    f2 = cv2.GaussianBlur(rng.integers(0, 255, (360, 640, 3), dtype=np.uint8), (0, 0), 2.0)
    tr = WorldPointTracker(f1, 300, 180, box=(280, 160, 320, 200))
    tr.update(f1)
    tr.on_cut()
    b = tr.update(f2)  # tamamen farklı sahne: hata vermez
    assert b is not None
    assert np.allclose(tr.W, np.eye(3), atol=1e-6)  # kamera geçmişi yeni sahneden başladı


def test_config_defaults_and_pipeline_in_main_wiring():
    cfg = Config(ipc_path="x")
    assert cfg.cut_detect is True and 0 < cfg.cut_hist < 1 and 0 < cfg.cut_diff < 1
    from pathlib import Path

    src = (Path(__file__).resolve().parent.parent / "tracker" / "main.py").read_text("utf-8")
    assert src.count("make_cuts(cfg)") == 2  # offline Pipeline + canlı run_live
