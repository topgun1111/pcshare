"""Sınıfsız nokta/nesne takibi (Parça 18): PointTracker + LiveController entegrasyonu."""
import numpy as np
import pytest

from tracker.clicks import ClickEvent
from tracker.config import Config
from tracker.geometry import BBox
from tracker.live import LiveController
from tracker.pipeline import NullPipeline
from tracker.point import PointTracker, default_box, refine_box

W, H = 640, 360


def _scene(seed=1):
    rng = np.random.default_rng(seed)
    import cv2

    bg = cv2.GaussianBlur(rng.integers(60, 120, (H, W, 3)).astype(np.uint8), (0, 0), 2)
    tex = cv2.GaussianBlur(rng.integers(0, 255, (40, 40, 3)).astype(np.uint8), (0, 0), 1.2)
    return bg, tex


def _frame(bg, tex, i, hide=False):
    """Dokulu nesne sağa ve hafif yukarı/aşağı gider; arka plan da kayar (kamera pan'ı)."""
    import cv2

    f = cv2.warpAffine(bg, np.float32([[1, 0, -2 * i], [0, 1, 0]]), (W, H),
                       borderMode=cv2.BORDER_REFLECT)
    cx, cy = 120 + 6 * i, 180 + 20 * np.sin(i / 8)
    if not hide:
        x, y = int(cx - 20), int(cy - 20)
        f[y:y + 40, x:x + 40] = tex
    return f, (cx, cy)


def test_default_box_is_square_and_stays_inside_frame():
    x1, y1, x2, y2 = default_box(200, 100, 5, 98, 0.2)
    assert (x2 - x1, y2 - y1) == (20.0, 20.0)
    assert x1 == 0 and y2 == 100  # köşeye yakın tıklama: kutu kareden taşmaz kıstırılır
    assert default_box(200, 100, 100, 50, 0.2) == (90.0, 40.0, 110.0, 60.0)


def test_refine_box_grows_over_same_color_region_and_rejects_noise():
    import cv2

    f = np.full((H, W, 3), (40, 120, 40), np.uint8)  # çim
    cv2.circle(f, (300, 180), 15, (240, 240, 245), -1)  # beyaz top
    b = refine_box(f, 302, 182)
    assert b is not None
    cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
    assert abs(cx - 300) < 3 and abs(cy - 180) < 3
    assert 30 <= b[2] - b[0] <= 50  # topun ~30 px çapı + pay
    noise = np.random.default_rng(0).integers(0, 255, (H, W, 3)).astype(np.uint8)
    assert refine_box(noise, 300, 180) is None  # doku/gürültü: bölge çıkmaz
    assert refine_box(np.full((H, W, 3), 90, np.uint8), 300, 180) is None  # her yer aynı renk


def test_point_tracker_follows_textured_object_through_scale_free_motion():
    bg, tex = _scene()
    f, (cx, cy) = _frame(bg, tex, 0)
    t = PointTracker(f, cx + 2, cy - 1, refine=False, box_frac=0.12)
    errs = []
    for i in range(1, 40):
        f, (cx, cy) = _frame(bg, tex, i)
        b = t.update(f)
        assert b is not None, i
        errs.append(np.hypot(b.cx - cx, b.cy - cy))
    assert max(errs) < 8.0  # nesne her karede ~6 px kaydı; izleyici geride kalmadı


def test_point_tracker_loses_when_hidden_and_reacquires():
    bg, tex = _scene(2)
    f, (cx, cy) = _frame(bg, tex, 0)
    t = PointTracker(f, cx, cy, refine=False, box_frac=0.12)
    for i in range(1, 10):
        assert t.update(_frame(bg, tex, i)[0]) is not None
    for i in range(10, 13):
        assert t.update(_frame(bg, tex, i, hide=True)[0]) is None  # nesne yok → kayıp
    assert t.lost == 3
    f, (cx, cy) = _frame(bg, tex, 13)
    b = t.update(f)  # geri geldi (arama penceresi kayıpla büyür)
    assert b is not None and t.lost == 0
    assert np.hypot(b.cx - cx, b.cy - cy) < 8.0


def test_point_tracker_bbox_contains_click_and_is_clamped():
    bg, tex = _scene()
    f, _ = _frame(bg, tex, 0)
    t = PointTracker(f, 2, 2, refine=False, box_frac=0.1)  # köşeye tıklama
    b = t.bbox
    assert b.contains(2, 2) and b.x1 >= 0 and b.y1 >= 0 and b.x2 <= W and b.y2 <= H


# --- LiveController entegrasyonu ---
class FakePoint:
    """Sıralı kutular veren sahte nesne izleyici (None = kayıp)."""

    def __init__(self, seq):
        self.seq, self.i = seq, 0
        self.bbox = seq[0]

    def update(self, frame):
        b = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        if b is not None:
            self.bbox = b
        return b


class CountingPipeline(NullPipeline):
    def __init__(self):
        self.steps = self.resets = 0

    def step(self, frame):
        self.steps += 1
        return []

    def reset(self):
        self.resets += 1


FRAME = np.zeros((100, 200, 3), np.uint8)


def _click(px, py, kind="track-point"):
    return {"event": "client-message",
            "args": [kind, str(px / 200), str(py / 100), "200", "100", "1"]}


def _ctl(seq, pipe=None):
    made = []

    def factory(frame, px, py, cfg):
        p = FakePoint(seq)
        made.append((px, py, p))
        return p

    c = LiveController(pipe or CountingPipeline(), Config(ipc_path="x"), point_factory=factory)
    return c, made


def test_right_click_tracks_any_point_without_running_the_detector():
    box = BBox(90, 40, 110, 60)
    pipe = CountingPipeline()
    c, made = _ctl([box, BBox(100, 40, 120, 60)], pipe)
    c.handle_event(_click(100, 50))
    assert c.enabled and c.hud_text() == "Takip: hedef seç (tıkla)"
    out = c.step(FRAME, 1.0)
    assert (made[0][0], made[0][1]) == (100.0, 50.0)  # tıklanan kare pikseli
    assert out.osd == "Hedef: nesne" and out.view is not None
    assert c.point is not None and c.target_id is None and c.has_target
    assert c.hud_text() == "Takip: nesne"
    out2 = c.step(FRAME, 1.1)
    assert out2.view is not None and pipe.steps == 0  # person modeli hiç çalışmadı


def test_right_click_on_tracked_object_releases_it():
    c, _ = _ctl([BBox(90, 40, 110, 60)])
    c.handle_event(_click(100, 50))
    c.step(FRAME, 1.0)
    c.handle_event(_click(101, 51))  # nesnenin üstüne tekrar sağ tık
    out = c.step(FRAME, 1.1)
    assert c.point is None and not c.has_target
    assert out.view == View0() and out.osd == "Hedef bırakıldı"


def View0():
    from tracker.geometry import View

    return View()


def test_right_click_elsewhere_switches_to_new_object():
    c, made = _ctl([BBox(90, 40, 110, 60)])
    c.handle_event(_click(100, 50))
    c.step(FRAME, 1.0)
    first = c.point
    c.handle_event(_click(30, 20))
    c.step(FRAME, 1.1)
    assert len(made) == 2 and c.point is not first


def test_object_lost_then_zooms_out_and_recovers():
    box = BBox(90, 40, 110, 60)
    c, _ = _ctl([box, None, None, box])  # başlangıç kare(ler)i update çağırmaz
    c.cfg.lost_hold_s = 0.0
    c.handle_event(_click(100, 50))
    c.step(FRAME, 1.0)  # başlat
    c.step(FRAME, 1.1)  # görüldü
    out = c.step(FRAME, 1.2)  # kayıp
    assert c.hud_text().startswith("Takip: hedef kayıp") and out.osd == "Hedef kayboldu"
    c.step(FRAME, 1.3)  # hâlâ kayıp
    out = c.step(FRAME, 1.4)  # geri geldi
    assert c.hud_text() == "Takip: nesne" and out.view is not None


def test_person_left_click_replaces_object_tracking():
    from tracker.bytetrack import Track

    pipe = CountingPipeline()
    c, _ = _ctl([BBox(90, 40, 110, 60)], pipe)
    c.handle_event(_click(100, 50))
    c.step(FRAME, 1.0)
    pipe.step = lambda frame: [Track(7, BBox(20, 10, 60, 90), 0.9, 5, 0)]
    c.handle_event(_click(40, 50, "track-click"))
    out = c.step(FRAME, 1.1)
    assert c.point is None and c.target_id == 7 and out.osd == "Hedef: ID 7"


def test_person_left_click_on_empty_spot_keeps_object_tracking():
    c, _ = _ctl([BBox(90, 40, 110, 60)])
    c.handle_event(_click(100, 50))
    c.step(FRAME, 1.0)
    c.handle_event(_click(10, 10, "track-click"))  # boş yer, oyuncu yok
    out = c.step(FRAME, 1.1)
    assert c.point is not None and out.osd == "Burada kişi yok" and out.view is not None


def test_seek_and_reset_drop_the_object():
    c, _ = _ctl([BBox(90, 40, 110, 60)])
    c.handle_event(_click(100, 50))
    c.step(FRAME, 1.0)
    out = c.step(FRAME, 30.0)  # atlama
    assert c.point is None and out.osd == "Atlama: hedefi yeniden seç"
    c.handle_event(_click(100, 50))
    c.step(FRAME, 30.1)
    c.handle_event({"event": "client-message", "args": ["track-reset"]})
    assert c.point is None and not c.has_target


def test_factory_failure_is_reported_not_raised():
    def boom(frame, px, py, cfg):
        raise RuntimeError("cv2 yok")

    c = LiveController(CountingPipeline(), Config(ipc_path="x"), point_factory=boom)
    c.handle_event(_click(100, 50))
    out = c.step(FRAME, 1.0)
    assert "başlatılamadı" in out.osd and c.point is None


def test_status_text_mentions_object_target():
    c, _ = _ctl([BBox(90, 40, 110, 60)])
    c.handle_event(_click(100, 50))
    c.step(FRAME, 1.0)
    out = c.handle_event({"event": "client-message", "args": ["track-status"]})
    assert "Hedef: nesne" in out.osd


def test_end_to_end_real_tracker_pans_toward_a_moving_object():
    """Gerçek PointTracker + LiveController: nesne sağa giderken pan sola (video sağa kayar → -)."""
    bg, tex = _scene(3)
    c = LiveController(NullPipeline(), Config(ipc_path="x", lookahead_s=0.0))
    f, (cx, cy) = _frame(bg, tex, 0)
    c.handle_event({"event": "client-message", "args": [
        "track-point", str(cx / W), str(cy / H), str(W), str(H), "0"]})
    first = c.step(f, 0.0)
    assert first.view is not None and first.view.zoom > 0.0  # nesne küçük: yakınlaştırır
    last = first
    for i in range(1, 30):
        f, _ = _frame(bg, tex, i)
        out = c.step(f, i * 0.1)
        last = out if out.view is not None else last
    assert c.point is not None and c._lost_t0 is None  # kaybetmedi
    assert last.view.pan_x < first.view.pan_x  # nesne sağa gitti → pan_x azaldı


def test_config_has_point_defaults():
    cfg = Config(ipc_path="x")
    assert (cfg.point_box, cfg.point_refine, cfg.point_min_score) == (0.06, True, 0.45)
    assert BBox(0, 0, 1, 1).contains(0.5, 0.5)
    with pytest.raises(TypeError):
        Config(ipc_path="x", nonexistent=1)


def test_lua_red_dot_overlay(tmp_path):
    import shutil
    import subprocess
    from pathlib import Path

    lua = shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
    if lua is None:
        return
    root = Path(__file__).resolve().parent.parent
    data = tmp_path / "d.lua"
    data.write_text("return {}", encoding="utf-8")
    env = {"TRACK_LUA": str(root / "mpv/portable_config/scripts/track.lua"), "VIDEO": "",
           "DATA_LUA": str(data)}
    r = subprocess.run([lua, str(Path(__file__).with_name("lua_harness_dot.lua"))], env=env,
                       capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert r.returncode == 0, r.stderr
    d = {ln.split("\t")[0]: ln.split("\t")[1] for ln in r.stdout.splitlines()}
    assert d["OFF_WHEN_DISABLED"] in ("", "<yok>")
    for k in ("CENTER", "ZOOMED"):  # merkez (500,250); kol = %1.8 yükseklik → 9 px
        assert "\\1c&HFFFFFF&" in d[k] and "\\p1" in d[k], d[k]  # beyaz, çizim modu
        assert "\\pos(491,241)" in d[k], d[k]
        assert d[k].count("m ") == 4 and " b " not in d[k], d[k]  # 4 kollu artı, daire (bezier) yok
    # sarı, (250,100)
    assert "\\1c&H00E0FF&" in d["PROV"] and "\\pos(241,91)" in d["PROV"], d["PROV"]
    assert d["WARN"] == "true"
    assert "\\1c&HFFFFFF&" in d["CONFIRMED"] and "\\pos(491,241)" in d["CONFIRMED"]
    assert d["OUT"] == "" and d["HIDE"] == ""
    # zoom/pan + kıstırılmış kenar boşlukları: artı yine videonun üstünde (pencereye yapışmaz)
    assert "\\pos(491,241)" in d["CLAMPED"], d["CLAMPED"]
    assert d["CLICKCMD"].startswith("script-message track-point 0.250000 0.500000"), d["CLICKCMD"]


def test_dot_is_drawn_ahead_of_moving_target_by_dot_lead_and_off_when_zero():
    """Kare işlenene kadar nesne ilerler: artı hız * dot_lead_s kadar önde çizilir (≤ %3)."""
    bg, tex = _scene()

    def run(lead):
        cfg = Config(ipc_path="x", dot_lead_s=lead)
        c = LiveController(NullPipeline(), cfg)
        c.enabled = True
        f, (cx, cy) = _frame(bg, tex, 0)
        c._click = ClickEvent(cx / W, cy / H, W, H, 0.0, kind="point")
        c.step(f, 0.0)
        for i in range(1, 40):
            f, (cx, cy) = _frame(bg, tex, i)
            c.step(f, i / 30)
        d = c.dot()
        return d[0] * W - cx

    assert abs(run(0.0)) < 3.0  # kapalı: tam nesnenin üstünde
    ahead = run(0.08)  # nesne ~180 px/sn sağa gider → ~14 px önde, sınırlı
    assert 5.0 < ahead < 0.03 * W


def test_event_listener_starts_before_models_load(monkeypatch):
    """Modeller yüklenirken gelen sağ tık kaybolmasın: dinleyici model yüklemesinden ÖNCE başlar."""
    import argparse

    from tracker import detector as detector_mod
    from tracker import live as live_mod
    from tracker import main as main_mod

    order: list[str] = []

    class FakeListener:
        def __init__(self, path, out):
            self.out = out

        def start(self):
            order.append("listener")

        def stop(self):
            pass

    def fake_detector(*a, **k):
        order.append("model")
        raise RuntimeError("model yok")  # NullPipeline yoluna düşer

    class FakeInst:
        def __init__(self, *a, **k):
            pass

        def claim(self):
            return False

        def release(self):
            pass

    class FakeIpc:
        def __init__(self, *a, **k):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def command(self, *a):
            order.append("msg:" + str(a[-1]))

    monkeypatch.setattr(live_mod, "EventListener", FakeListener)
    monkeypatch.setattr(live_mod, "run_live", lambda mpv, events, *a, **k: order.append("run"))
    monkeypatch.setattr(detector_mod, "Detector", fake_detector)
    monkeypatch.setattr(main_mod, "MpvIpc", FakeIpc)
    monkeypatch.setattr("tracker.single.SingleInstance", FakeInst)
    cfg = Config(ipc_path="x", pose_model="__yok__.onnx", reid_model="__yok__.onnx",
                 number_model="__yok__.onnx")
    args = argparse.Namespace(
        team=False, number=False, reid=False, reid_model=None, fps=0, wait=0, single_pose=False,
        model=None, device=None, stats=0.0, cfg_path="x.json", headless=False)
    assert main_mod.run_live_cmd(cfg, args) == 0
    assert order.index("listener") < order.index("model") < order.index("run")
    assert order[1] == "msg:1" and "msg:0" in order  # yükleniyor → hazır bildirimi
