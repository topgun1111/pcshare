"""T1: hedef kümesi — birleşik kadraj, min-zoom sınırı + histerezis, canlı ekleme/çıkarma."""
from pathlib import Path
from types import SimpleNamespace

import numpy as np

from tracker.config import Config
from tracker.geometry import BBox
from tracker.live import LiveController
from tracker.targets import TargetSet, union_box

ROOT = Path(__file__).resolve().parent.parent
FRAME = np.zeros((300, 600, 3), np.uint8)


class Tr:
    """Konumu elle verilen sahte izleyici."""

    def __init__(self, x, y=140, s=40, label="a"):
        self.box, self.label, self.visible = BBox(x, y, x + s, y + s), label, True
        self.cut = 0

    @property
    def bbox(self):
        return self.box

    def update(self, frame):
        return self.box

    def on_cut(self):
        self.cut += 1


def test_union_of_visible_members_and_label():
    ts = TargetSet(Tr(100), min_zoom=-9)
    ts.add(Tr(300, label="b"))
    b = ts.update(FRAME)
    assert (b.x1, b.x2) == (100, 340) and len(ts) == 2 and ts.label == "2 hedef"
    assert union_box([BBox(0, 0, 1, 1), BBox(2, 3, 4, 5)]) == BBox(0, 0, 4, 5)
    ts.remove(1)
    assert ts.label == "a" and ts.update(FRAME).x2 == 140


def test_min_zoom_cap_drops_far_low_priority_then_hysteresis_readmits():
    a, near, far = Tr(100), Tr(150, label="n"), Tr(540, label="f")
    ts = TargetSet(a, fill=0.3, min_zoom=0.35, hyst=0.25)
    ts.add(near)
    ts.add(far)
    b = ts.update(FRAME)
    assert b.x2 == 190  # uzak hedef kadrajdan çıktı (480 px > 424 px sınırı)
    assert ts.framed == 2 and "kadrajda" in ts.label
    assert ts.update(FRAME).x2 == b.x2  # kararlı
    # uzak hedef biraz yaklaşır: eşiğin altında kalır ama histerezis payı yüzünden hâlâ dışarıda
    far.box = BBox(450, 140, 490, 180)  # 390 px: sınır (424) altı ama histerezis (356) üstü
    assert ts.update(FRAME).x2 == 190
    far.box = BBox(380, 140, 420, 180)  # 320 px: açıkça sığar → geri alınır
    assert ts.update(FRAME).x2 == 420 and ts.framed == 3


def test_anchor_always_kept_and_weights_decide_priority():
    a, b = Tr(0), Tr(560, label="b")
    ts = TargetSet(a, min_zoom=2.0)  # hiçbir birleşim sığmaz
    ts.add(b)
    assert ts.update(FRAME) == a.box  # ilk eklenen (en yüksek öncelik) kalır
    ts.set_weight(1, 5.0)
    assert ts.update(FRAME) == b.box  # öncelik değişti


def test_invisible_prediction_ignored_when_others_visible_and_all_lost_none():
    a, off = Tr(100), Tr(900, label="off")
    off.visible = False
    ts = TargetSet(a, min_zoom=-9)
    ts.add(off)
    assert ts.update(FRAME) == a.box and ts.visible
    a.update = lambda f: None
    off.update = lambda f: None
    assert ts.update(FRAME) is None and not ts.visible


def test_member_at_and_cut_forwarded():
    a, b = Tr(100), Tr(300)
    ts = TargetSet(a)
    ts.add(b)
    assert ts.member_at(120, 160) == 0 and ts.member_at(320, 160) == 1
    assert ts.member_at(5, 5) is None
    ts.on_cut()
    assert a.cut == 1 and b.cut == 1


def _msg(*a):
    return {"event": "client-message", "args": list(a)}


def _click(px, py):
    return _msg("track-point", str(px / 600), str(py / 300), "600", "300", "1")


def test_live_add_mode_union_remove_member_and_unwrap():
    seq = iter([Tr(100, label="x"), Tr(300, label="y")])
    made = []

    def factory(frame, px, py, cfg, box=None):
        t = next(seq)
        made.append(t)
        return t

    class NP:
        detector = None

        def reset(self):
            pass

        def on_cut(self):
            pass

    c = LiveController(NP(), Config(ipc_path="x"), point_factory=factory)
    c.handle_event(_click(120, 160))
    assert c.step(FRAME, 1.0).osd == "Hedef: nesne" or c.point is made[0]
    assert "AÇIK" in c.handle_event(_msg("track-addmode", "on")).osd
    c.handle_event(_click(320, 160))
    out = c.step(FRAME, 1.1)
    assert out.osd.startswith("Hedef eklendi: y (2 hedef)") and isinstance(c.point, TargetSet)
    assert c.hud_text() == "Takip: 2 hedef"
    out = c.step(FRAME, 1.2)  # sonraki kare: birleşik kutu iki hedefi de içerir
    assert out.view is not None and c.point.bbox.x1 == 100 and c.point.bbox.x2 == 340
    # üyeye sağ tık → çıkar; tek kalınca küme açılır
    c.handle_event(_click(320, 160))
    out = c.step(FRAME, 1.3)
    assert "Hedef çıkarıldı: y (1 kaldı)" in out.osd and c.point is made[0]
    c.handle_event(_msg("track-addmode", "off"))
    assert not c.add_mode
    assert Config().set_min_zoom > 0 and Config().set_hyst > 0


def test_live_remove_message_and_lua_wiring():
    c = LiveController(SimpleNamespace(detector=None, reset=lambda: None, on_cut=lambda: None),
                       Config(ipc_path="x"))
    ts = TargetSet(Tr(100))
    ts.add(Tr(300, label="y"))
    c.enabled, c.point = True, ts
    assert "y" in c.handle_event(_msg("track-remove")).osd and not isinstance(c.point, TargetSet)
    assert c.handle_event(_msg("track-remove")).osd == "Hedef bırakıldı" and c.point is None
    lua = (ROOT / "mpv" / "portable_config" / "scripts" / "track.lua").read_text(encoding="utf-8")
    assert "track-addmode" in lua and "track-remove" in lua
    assert "a script-binding track/add" in (
        ROOT / "mpv" / "portable_config" / "input.conf").read_text(encoding="utf-8")
