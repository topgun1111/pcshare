import base64
import json
import os
import queue
import socket
import threading

import numpy as np
import pytest

from tracker.bytetrack import Track
from tracker.config import Config
from tracker.frames import decode_raw, grab_frame
from tracker.geometry import BBox, View
from tracker.ipc import MpvCommandError, MpvIpc, MpvIpcError
from tracker.live import CLOSED, LiveController, pick_track, run_live
from tracker.smooth import ViewSmoother, ease_view

FRAME = np.zeros((100, 200, 3), np.uint8)


def _tr(i, x1, y1, x2, y2):
    return Track(i, BBox(x1, y1, x2, y2), 0.9, 5, 0)


def _click(px, py, w=200, h=100):
    return {"event": "client-message",
            "args": ["track-click", str(px / w), str(py / h), str(w), str(h), "1.0"]}


def _msg(name):
    return {"event": "client-message", "args": [name]}


def _toggle(on):
    return {"event": "client-message", "args": ["track-toggle", "on" if on else "off"]}


class FakePipeline:
    def __init__(self, seq):
        self.seq, self.i, self.resets = seq, 0, 0

    def step(self, frame):
        out = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return out

    def reset(self):
        self.resets += 1


def _cfg():
    return Config(ipc_path="x")


# --- frames ---
def _node(w=3, h=2, stride=16, fmt="bgr0", b64=True):
    raw = bytearray(stride * h)
    for y in range(h):
        for x in range(w):
            raw[y * stride + x * 4: y * stride + x * 4 + 4] = bytes([x, y, 7, 255])
    data = base64.b64encode(bytes(raw)).decode() if b64 else list(raw)
    return {"w": w, "h": h, "stride": stride, "format": fmt, "data": data}


def test_decode_raw_stride_and_channels():
    f = decode_raw(_node())
    assert f.shape == (2, 3, 3) and f.dtype == np.uint8 and f.flags["C_CONTIGUOUS"]
    assert tuple(f[1, 2]) == (2, 1, 7)  # padding (stride > w*4) atılır, alfa/0 kanalı düşer


def test_decode_raw_list_data_and_errors():
    assert decode_raw(_node(b64=False)).shape == (2, 3, 3)
    with pytest.raises(ValueError):
        decode_raw(_node(fmt="rgb24"))
    bad = _node()
    bad["data"] = base64.b64encode(b"short").decode()
    with pytest.raises(ValueError):
        decode_raw(bad)
    with pytest.raises(ValueError):
        decode_raw({"w": 1})


# --- seçim ---
def test_pick_track():
    a, b = _tr(1, 0, 0, 100, 100), _tr(2, 60, 0, 160, 100)
    assert pick_track([a, b], 20, 50).id == 1
    assert pick_track([a, b], 140, 50).id == 2
    assert pick_track([a, b], 100, 50).id == 2  # iki kutuda: merkezi yakın olan
    assert pick_track([a, b], 190, 50) is None


def test_pick_track_overlap_prefers_small_box_under_cursor():
    big, small = _tr(1, 0, 0, 100, 100), _tr(2, 60, 20, 80, 60)
    assert pick_track([big, small], 70, 40).id == 2  # küçük kutunun merkezine tıklandı
    assert pick_track([big, small], 20, 50).id == 1


def test_pick_track_tolerance_nearest_box():
    a, b = _tr(1, 0, 0, 40, 100), _tr(2, 100, 0, 140, 100)
    assert pick_track([a, b], 46, 50) is None  # tol=0: kutu dışı geçersiz
    assert pick_track([a, b], 46, 50, tol=10).id == 1
    assert pick_track([a, b], 92, 50, tol=10).id == 2
    assert pick_track([a, b], 70, 50, tol=10) is None  # iki kutuya da uzak
    assert pick_track([a, b], 70, 50, tol=40).id in (1, 2)


# --- denetleyici ---
def test_disabled_does_nothing():
    c = LiveController(FakePipeline([[]]), _cfg())
    assert not c.needs_frame(1.0)
    assert c.step(FRAME, 1.0).view is None


def test_click_selects_and_follows():
    c = LiveController(FakePipeline([[_tr(3, 85, 40, 95, 60)]]), _cfg())
    c.handle_event(_toggle(True))
    c.handle_event(_click(90, 50))
    out = c.step(FRAME, 1.0)
    assert c.target_id == 3 and out.osd == "Hedef: ID 3"
    assert out.view is not None and out.view.zoom > 0.5
    assert abs(out.view.pan_x) < 0.06  # hedef yatayda ortaya yakın


def test_click_on_empty_area():
    c = LiveController(FakePipeline([[_tr(3, 85, 40, 95, 60)]]), _cfg())
    c.handle_event(_click(5, 5))
    out = c.step(FRAME, 1.0)
    assert c.target_id is None and out.osd == "Burada kişi yok" and out.view is None


def test_target_lost_osd_once_and_view_held():
    t = _tr(3, 85, 40, 95, 60)
    c = LiveController(FakePipeline([[t], [], []]), _cfg())
    c.handle_event(_click(90, 50))
    c.step(FRAME, 1.0)
    o1, o2 = c.step(FRAME, 1.04), c.step(FRAME, 1.08)
    assert o1.osd == "Hedef kayboldu" and o1.view is None
    assert o2.osd is None and c.target_id == 3  # varsayılan bekleme 1 sn


def _lost_ctl(seq, **kw):
    cfg = Config(ipc_path="x", lost_hold_s=1.0, lost_zoomout_s=2.0, **kw)
    c = LiveController(FakePipeline(seq), cfg)
    c.handle_event(_click(90, 50))
    out = c.step(FRAME, 1.0)
    return c, out.view


def test_lost_holds_then_zooms_out_gradually():
    t = _tr(3, 85, 40, 95, 60)
    c, v0 = _lost_ctl([[t]] + [[]] * 80)
    assert v0.zoom > 0.5
    views = {}
    for k in range(1, 40):  # t = 1.1 .. 4.9
        views[round(1.0 + 0.1 * k, 1)] = c.step(FRAME, 1.0 + 0.1 * k).view
    assert views[1.5] is None and views[2.0] is None  # bekleme: görünüm değişmez
    zs = [views[x].zoom for x in (2.5, 2.9, 3.4, 3.9)]  # zoom-out: tekdüze azalan
    assert all(a > b for a, b in zip(zs, zs[1:])) and 0.0 < zs[-1] < v0.zoom
    assert views[4.9] == View() and views[4.9].zoom == 0.0  # 1 + 1 (bekle) + 2 (zoom-out) = 4 sn
    assert c.target_id == 3  # hedef korunur


def test_lost_zoomout_zero_duration_snaps():
    c, _ = _lost_ctl([[_tr(3, 85, 40, 95, 60)]] + [[]] * 5)
    c.cfg.lost_zoomout_s = 0.0
    assert c.step(FRAME, 1.5).view is None
    assert c.step(FRAME, 2.6).view == View()  # kayıp 1.5'te başladı, bekleme 2.5'te bitti


def test_lost_time_is_video_time_not_frame_count():
    c, _ = _lost_ctl([[_tr(3, 85, 40, 95, 60)]] + [[]] * 50)
    for k in range(1, 20):  # 19 kare ama yalnız 0.19 sn
        assert c.step(FRAME, 1.0 + 0.01 * k).view is None


def test_target_returns_during_zoomout_resumes_without_jump():
    t = _tr(3, 85, 40, 95, 60)
    c, v0 = _lost_ctl([[t]] + [[]] * 15 + [[t]] * 5)
    last = None
    for k in range(1, 16):  # t=1.1..2.6 → zoom-out yarıda
        last = c.step(FRAME, 1.0 + 0.1 * k).view or last
    assert last is not None and 0.0 < last.zoom < v0.zoom
    back = c.step(FRAME, 2.7).view
    assert back is not None and abs(back.zoom - last.zoom) < 0.15  # sıçrama yok
    for k in range(60):
        back = c.step(FRAME, 2.8 + 0.1 * k).view
    assert back.zoom > v0.zoom - 0.1  # tekrar yakınlaştı
    assert c._lost_t0 is None and not c._zooming_out


def test_new_target_during_zoomout_does_not_jump():
    a, b = _tr(3, 85, 40, 95, 60), _tr(4, 150, 40, 160, 60)
    c, v0 = _lost_ctl([[a, b]] + [[b]] * 15 + [[b]] * 5)
    last = None
    for k in range(1, 16):
        last = c.step(FRAME, 1.0 + 0.1 * k).view or last
    c.handle_event(_click(155, 50))
    out = c.step(FRAME, 2.7)
    assert c.target_id == 4 and out.view is not None
    assert abs(out.view.zoom - last.zoom) < 0.2


def test_release_and_seek_clear_lost_state():
    c, _ = _lost_ctl([[_tr(3, 85, 40, 95, 60)]] + [[]] * 5)
    c.step(FRAME, 1.1)
    assert c._lost_t0 is not None
    c.handle_event(_msg("track-release"))
    assert c._lost_t0 is None and c._view == View()


def test_ease_view_endpoints_and_monotonic():
    a = View(2.0, 0.2, -0.1)
    assert ease_view(a, View(), 0.0) == a and ease_view(a, View(), 1.0) == View()
    assert ease_view(a, View(), 2.0) == View() and ease_view(a, View(), -1.0) == a
    zs = [ease_view(a, View(), i / 10).zoom for i in range(11)]
    assert all(x >= y for x, y in zip(zs, zs[1:]))


def test_smoother_seed_continues_from_view():
    sm = ViewSmoother()
    sm.update(View(2.0, 0.1, 0.1))
    sm.seed(View(1.0, 0.05, 0.0))
    out = sm.update(View(1.0, 0.05, 0.0))
    assert out == View(1.0, 0.05, 0.0)


def test_pause_skips_frames_until_click():
    c = LiveController(FakePipeline([[_tr(3, 85, 40, 95, 60)]]), _cfg())
    c.handle_event(_toggle(True))
    assert c.needs_frame(5.0)  # ilk kare
    c.step(FRAME, 5.0)
    assert not c.needs_frame(5.0)
    c.handle_event(_click(90, 50))
    assert c.needs_frame(5.0)  # duraklatılmışken tıklama yine işlenir


def test_seek_resets_target():
    p = FakePipeline([[_tr(3, 85, 40, 95, 60)]])
    c = LiveController(p, _cfg())
    c.handle_event(_click(90, 50))
    c.step(FRAME, 10.0)
    out = c.step(FRAME, 60.0)  # ileri atlama
    assert c.target_id is None and p.resets == 1
    assert out.view == View() and "Atlama" in out.osd
    c.handle_event(_click(90, 50))
    c.step(FRAME, 60.1)
    out = c.step(FRAME, 20.0)  # geri atlama
    assert c.target_id is None and p.resets == 2


def test_reset_and_toggle_off_restore_view():
    c = LiveController(FakePipeline([[_tr(3, 85, 40, 95, 60)]]), _cfg())
    c.handle_event(_click(90, 50))
    c.step(FRAME, 1.0)
    assert c.handle_event({"event": "client-message", "args": ["track-reset"]}).view == View()
    assert c.target_id is None and c.enabled
    assert c.handle_event(_toggle(False)).view == View() and not c.enabled


# --- döngü ---
# --- Parça 6: hedef değiştirme / bırakma ---
def _two_people():
    return [_tr(3, 20, 30, 40, 70), _tr(4, 140, 30, 160, 70)]


def test_click_near_miss_uses_tolerance():
    c = LiveController(FakePipeline([[_tr(3, 85, 40, 95, 60)]]), _cfg())
    c.handle_event(_click(98, 50))  # kutunun 3 px dışı; tol = 0.03 * 100 = 3 px
    assert c.step(FRAME, 1.0).osd == "Hedef: ID 3"


def test_click_other_person_switches_target_without_view_jump():
    c = LiveController(FakePipeline([_two_people()]), _cfg())
    c.handle_event(_click(30, 50))
    first = c.step(FRAME, 1.0)
    assert c.target_id == 3 and first.view is not None
    c.handle_event(_click(150, 50))
    out = c.step(FRAME, 1.04)
    assert c.target_id == 4 and out.osd == "Hedef: ID 4"
    # yumuşatıcı sıfırlanmadı: görünüm eski hedeften yeniye kayarak gider (anlık atlama yok)
    assert out.view is None or out.view.pan_x - first.view.pan_x < 0.2


def test_click_empty_keeps_current_target():
    c = LiveController(FakePipeline([_two_people()]), _cfg())
    c.handle_event(_click(30, 50))
    c.step(FRAME, 1.0)
    c.handle_event(_click(90, 5))
    out = c.step(FRAME, 1.04)
    assert c.target_id == 3 and out.osd == "Burada kişi yok"


def test_click_on_target_releases_it():
    c = LiveController(FakePipeline([_two_people()]), _cfg())
    c.handle_event(_click(30, 50))
    c.step(FRAME, 1.0)
    c.handle_event(_click(30, 50))
    out = c.step(FRAME, 1.04)
    assert c.target_id is None and out.osd == "Hedef bırakıldı" and out.view == View()
    assert c.enabled
    c.handle_event(_click(150, 50))  # bıraktıktan sonra yeni hedef seçilebilir
    assert c.step(FRAME, 1.08).osd == "Hedef: ID 4"


def test_release_key_message():
    c = LiveController(FakePipeline([_two_people()]), _cfg())
    c.handle_event(_toggle(True))
    assert c.handle_event(_msg("track-release")).osd == "Bırakılacak hedef yok"
    c.handle_event(_click(30, 50))
    c.step(FRAME, 1.0)
    out = c.handle_event(_msg("track-release"))
    assert c.target_id is None and out.view == View() and out.osd == "Hedef bırakıldı"
    assert c.enabled and not c.needs_frame(1.0)  # takip açık, görünüm 1x


def test_release_drops_pending_click():
    c = LiveController(FakePipeline([_two_people()]), _cfg())
    c.handle_event(_click(30, 50))
    c.handle_event(_msg("track-release"))
    assert c.step(FRAME, 1.0).osd is None and c.target_id is None


def _final_pan_x(lookahead_s, steps=20, dx=2):
    cfg = _cfg()
    cfg.lookahead_s = lookahead_s
    seq = [[_tr(1, 75 + dx * i, 30, 85 + dx * i, 50)] for i in range(steps)]  # 20 px/sn sağa
    c = LiveController(FakePipeline(seq), cfg, ViewSmoother(1.0, 1.0, 0.0, 0.0))
    c.handle_event(_click(80, 40))
    out = None
    for i in range(steps):
        out = c.step(FRAME, 0.1 * (i + 1))
    return out.view.pan_x, c


def test_lookahead_leads_moving_target():
    pan_off, _ = _final_pan_x(0.0)
    pan_on, c = _final_pan_x(0.5)
    assert pan_on < pan_off - 0.03  # hedef sağa gidiyor: pan = 0.5 - c, öndeki merkez → daha küçük
    assert c.flt.velocity[0] > 0.05


def test_lookahead_zero_for_static_target():
    pan_off, _ = _final_pan_x(0.0, dx=0)
    pan_on, _ = _final_pan_x(0.5, dx=0)
    assert pan_on == pytest.approx(pan_off, abs=1e-6)


def test_filter_resets_on_target_switch_and_release():
    seq = [[_tr(1, 75 + 2 * i, 30, 85 + 2 * i, 50), _tr(2, 150, 30, 160, 50)] for i in range(12)]
    c = LiveController(FakePipeline(seq), _cfg())
    c.handle_event(_click(80, 40))
    for i in range(8):
        c.step(FRAME, 0.1 * (i + 1))
    assert c.flt.velocity[0] > 0.0
    c.handle_event(_click(155, 40))  # ikinci kişiye geç
    c.step(FRAME, 0.9)
    assert c.target_id == 2 and c.flt.velocity == (0.0, 0.0)
    c.handle_event(_msg("track-release"))
    assert not c.flt.active


class FakeMpv:
    def __init__(self, stop, frames):
        self.stop, self.frames, self.t, self.views, self.texts = stop, frames, 0.0, [], []
        self.grabs = 0

    def get_property(self, name):
        assert name == "time-pos"
        self.t += 0.1
        return self.t

    def command(self, *a):
        if a[0] == "show-text":
            self.texts.append(a[1])

    def set_view(self, v):
        self.views.append(v)


def test_run_live_end_to_end():
    stop = threading.Event()
    mpv = FakeMpv(stop, 4)

    def grab(m):
        m.grabs += 1
        if m.grabs >= 4:
            stop.set()
        return FRAME

    ev = queue.Queue()
    ev.put(_toggle(True))
    ev.put(_click(90, 50))
    cfg = _cfg()
    cfg.live_fps = 1000.0
    run_live(mpv, ev, FakePipeline([[_tr(3, 85, 40, 95, 60)]]), cfg, grab=grab, stop=stop,
             log=lambda *_: None, sleep=lambda _s: None)
    assert mpv.texts == ["Hedef: ID 3"]
    assert mpv.views and mpv.views[-1].zoom > 0.5
    assert len(mpv.views) <= 4


def test_run_live_closed_event_and_command_error():
    ev = queue.Queue()
    ev.put(CLOSED)
    run_live(object(), ev, FakePipeline([[]]), _cfg(), log=lambda *_: None)

    class NoVideo(FakeMpv):
        def get_property(self, name):
            self.grabs += 1
            if self.grabs > 2:
                raise MpvIpcError("mpv bağlantısı kapandı")
            raise MpvCommandError("mpv hatası: property unavailable")

    ev = queue.Queue()
    ev.put(_toggle(True))
    logs = []
    run_live(NoVideo(None, 0), ev, FakePipeline([[]]), _cfg(), log=logs.append,
             sleep=lambda _s: None)
    assert "kesildi" in logs[-1]  # komut hatası döngüyü bozmaz; bağlantı hatası temiz çıkar


def test_run_live_survives_rejected_set_view():
    stop = threading.Event()

    class Rejecting(FakeMpv):
        def set_view(self, v):
            self.views.append(v)
            raise MpvCommandError("mpv hatası: refused")

    mpv = Rejecting(stop, 4)

    def grab(m):
        m.grabs += 1
        if m.grabs >= 3:
            stop.set()
        return FRAME

    ev = queue.Queue()
    ev.put(_toggle(True))
    ev.put(_click(90, 50))
    cfg = _cfg()
    cfg.live_fps = 1000.0
    logs = []
    run_live(mpv, ev, FakePipeline([[_tr(3, 85, 40, 95, 60)]]), cfg, grab=grab, stop=stop,
             log=logs.append, sleep=lambda _s: None)
    assert mpv.grabs >= 3 and len(mpv.views) >= 2  # hata sonrası döngü sürdü, yeniden denendi
    assert any("uygulanamadı" in x for x in logs)


# --- gerçek soket: büyük satır ---
@pytest.mark.skipif(os.name == "nt", reason="Unix soketi gerekir")
def test_grab_frame_over_socket_large_payload(tmp_path):
    path = str(tmp_path / "m.sock")
    w, h = 1280, 720
    payload = {"w": w, "h": h, "stride": w * 4, "format": "bgr0",
               "data": base64.b64encode(bytes(w * h * 4)).decode()}
    ready = threading.Event()

    def serve():
        srv = socket.socket(socket.AF_UNIX)
        srv.bind(path)
        srv.listen(1)
        ready.set()
        conn, _ = srv.accept()
        f = conn.makefile("rwb")
        for line in f:
            req = json.loads(line)
            f.write(json.dumps({"event": "noise"}).encode() + b"\n")
            f.write(json.dumps({"error": "success", "data": payload,
                                "request_id": req["request_id"]}).encode() + b"\n")
            f.flush()
        srv.close()

    threading.Thread(target=serve, daemon=True).start()
    ready.wait(2)
    with MpvIpc(path, timeout=10.0, keep_events=False) as mpv:
        frame = grab_frame(mpv)
        assert frame.shape == (h, w, 3)
        assert len(mpv._events) == 0


def test_grab_frame_falls_back_to_file_when_raw_json_is_broken(monkeypatch):
    import cv2

    from tracker import frames

    monkeypatch.setattr(frames, "_file_mode", False)
    calls = []

    class Broken:
        def command(self, *a):
            calls.append(a[0])
            if a[0] == "screenshot-raw":
                json.loads('{"data":,')  # JSONDecodeError (ValueError)
            cv2.imwrite(a[1], np.full((20, 30, 3), 128, np.uint8))

    assert frames.grab_frame(Broken()).shape == (20, 30, 3)
    assert frames.grab_frame(Broken()).shape == (20, 30, 3)
    assert calls == ["screenshot-raw", "screenshot-to-file", "screenshot-to-file"]
