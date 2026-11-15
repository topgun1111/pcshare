"""A2: canlı kalite penceresi, taranmış iz uyumu, kaynak modu ve canlı döngüde otomatik geçiş."""
import json
import queue
import threading

from test_live import FRAME, FakeMpv, FakePipeline, _click, _toggle, _tr

from tracker.cache import file_sig
from tracker.config import Config
from tracker.live import LiveController, run_live
from tracker.quality import QualityWindow, scanned_agrees, views_ready


def _fill(q, n, dt, lag, lost=False, t0=0.0):
    for i in range(n):
        q.add(t0 + i * dt, lag, lost)


def test_window_needs_full_span_and_samples():
    q = QualityWindow(5.0)
    _fill(q, 2, 4.5, 0.0)
    assert q.stats() is None  # 2 örnek < 3
    q = QualityWindow(5.0)
    _fill(q, 12, 0.1, 0.0)
    assert q.stats() is None  # 1.1 sn < %80 pencere
    q = QualityWindow(5.0)
    _fill(q, 51, 0.1, 0.05)
    st = q.stats()
    assert abs(st["fps"] - 10.0) < 0.1 and abs(st["lag_ms"] - 50) < 1 and st["lost_frac"] == 0


def test_verdict_fps_lag_lost_and_healthy():
    cfg = Config(live_fps=10.0)
    ok = QualityWindow(5.0)
    _fill(ok, 51, 0.1, 0.05)
    assert ok.verdict(cfg) is None
    slow = QualityWindow(5.0)
    _fill(slow, 26, 0.2, 0.05)  # 5 kare/sn < 6
    assert "yavaş" in slow.verdict(cfg)
    lag = QualityWindow(5.0)
    _fill(lag, 51, 0.1, 0.4)
    assert "gecikmeli" in lag.verdict(cfg)
    lost = QualityWindow(5.0)
    for i in range(51):
        lost.add(i * 0.1, 0.05, i % 2 == 0)
    assert "kayıp" in lost.verdict(cfg)
    assert slow.verdict(Config(live_fps=10.0, auto_window_s=0)) is None  # kapalı
    slow.reset()
    assert slow.stats() is None


def test_views_ready_requires_matching_video(tmp_path):
    v = tmp_path / "a.mp4"
    v.write_bytes(b"x")
    assert views_ready(None) is None and views_ready(str(v)) is None
    vp = tmp_path / "a.mp4.views.json"
    vp.write_text(json.dumps({"views": [], "vsig": file_sig(v)}))
    assert views_ready(str(v)) is None  # anahtar kare yok
    vp.write_text(json.dumps({"views": [[0, 1, 0, 0]], "vsig": file_sig(v)}))
    assert views_ready(str(v)) == [[0, 1, 0, 0]]
    vp.write_text(json.dumps({"views": [[0, 1, 0, 0]], "vsig": "baska"}))
    assert views_ready(str(v)) is None  # başka videoya ait
    vp.write_text(json.dumps({"views": [[0, 1, 0, 0]]}))  # eski dosya
    assert views_ready(str(v)) is not None


def test_scanned_agrees_with_live_target_or_lost():
    views = [[0.0, 2.0, 0.05, 0.0], [10.0, 2.0, -0.3, 0.0]]  # merkez 0.45 → 0.8
    assert scanned_agrees(views, 0.0, (0.45, 0.5))
    assert not scanned_agrees(views, 0.0, (0.8, 0.5))  # başka kişiyi izliyor
    assert scanned_agrees(views, 10.0, (0.8, 0.5))
    assert scanned_agrees(views, 0.0, None)  # kayıp: taranmış güvenilir


def test_source_message_sets_mode():
    ctl = LiveController(FakePipeline([[]]), Config(ipc_path="x"))
    assert ctl.source == "auto"
    ctl.handle_event({"event": "client-message", "args": ["track-source", "live"]})
    assert ctl.source == "live"
    ctl.handle_event({"event": "client-message", "args": ["track-source", "saçma"]})
    assert ctl.source == "auto"


class _SlowMpv(FakeMpv):
    def __init__(self, stop, video):
        super().__init__(stop, 99)
        self.video, self.sent = video, []

    def get_property(self, name):
        return self.video if name == "path" else super().get_property(name)

    def command(self, *a):
        if a[0] == "script-message-to":
            self.sent.append(a[2:])
        else:
            super().command(*a)


def _run_slow(tmp_path, source, with_views=True, views_px=0.05):
    tmp_path.mkdir(exist_ok=True)
    v = tmp_path / "c.mp4"
    v.write_bytes(b"x")
    if with_views:
        (tmp_path / "c.mp4.views.json").write_text(
            json.dumps({"views": [[0.0, 2.0, views_px, 0.0]], "vsig": file_sig(v)}))
    stop = threading.Event()
    mpv = _SlowMpv(stop, str(v))
    clock_t = [0.0]

    def clock():
        clock_t[0] += 0.2  # her çağrı 0.2 sn: adım ≈ 1.2 sn → çok yavaş canlı
        return clock_t[0]

    def grab(m):
        m.grabs += 1
        if m.grabs >= 40:
            stop.set()
        return FRAME

    ev = queue.Queue()
    ev.put(_toggle(True))
    ev.put({"event": "client-message", "args": ["track-source", source]})
    ev.put(_click(90, 50))
    run_live(mpv, ev, FakePipeline([[_tr(3, 85, 40, 95, 60)]]), Config(ipc_path="x"), grab=grab,
             stop=stop, log=lambda *_: None, sleep=lambda _s: None, clock=clock)
    return mpv.sent


def test_live_switches_to_scanned_when_slow(tmp_path):
    sent = [m for m in _run_slow(tmp_path, "auto") if m[0] == "track-use-scanned"]
    # gerçekte Lua canlıyı kapatır; burada 10 sn bekleme ile tekrar gönderilir (her adım değil)
    assert 1 <= len(sent) <= 8 and "yavaş" in sent[0][1]


def test_no_switch_in_live_mode_without_views_or_other_target(tmp_path):
    def use(m):
        return [x for x in m if x[0] == "track-use-scanned"]

    assert use(_run_slow(tmp_path / "a", "live")) == []
    assert use(_run_slow(tmp_path / "b", "auto", with_views=False)) == []
    assert use(_run_slow(tmp_path / "c", "auto", views_px=-0.3)) == []  # başka kişi
