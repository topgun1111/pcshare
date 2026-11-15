import json
import os
import socket
import threading

import pytest

from tracker.geometry import View
from tracker.ipc import MpvIpc, MpvIpcError

pytestmark = pytest.mark.skipif(os.name == "nt", reason="Unix soketi gerekir")


def _serve(path, ready, received):
    srv = socket.socket(socket.AF_UNIX)
    srv.bind(path)
    srv.listen(1)
    ready.set()
    conn, _ = srv.accept()
    f = conn.makefile("rwb")
    for line in f:
        req = json.loads(line)
        received.append(req["command"])
        f.write(b'{"event":"noise"}\n')  # olay satırı atlanmalı
        if req["command"][1:2] == ["bad"]:
            resp = {"error": "property not found", "request_id": req["request_id"]}
        else:
            resp = {"error": "success", "data": "ok", "request_id": req["request_id"]}
        f.write(json.dumps(resp).encode() + b"\n")
        f.flush()
    srv.close()


def test_ipc_roundtrip(tmp_path):
    path = str(tmp_path / "mpv.sock")
    ready, received = threading.Event(), []
    threading.Thread(target=_serve, args=(path, ready, received), daemon=True).start()
    ready.wait(2)
    with MpvIpc(path) as mpv:
        assert mpv.get_property("mpv-version") == "ok"
        mpv.set_view(View(1.0, 0.1, -0.1))
        with pytest.raises(MpvIpcError):
            mpv.get_property("bad")
    assert ["set_property", "video-zoom", 1.0] in received
    assert ["set_property", "video-pan-y", -0.1] in received


def test_connect_failure(tmp_path):
    with pytest.raises(MpvIpcError):
        MpvIpc(str(tmp_path / "yok.sock")).connect()


def _serve_events(path, ready):
    srv = socket.socket(socket.AF_UNIX)
    srv.bind(path)
    srv.listen(1)
    ready.set()
    conn, _ = srv.accept()
    ev = {"event": "client-message", "args": ["track-click", "0.5", "0.5", "10", "10", "0"]}
    conn.sendall(json.dumps(ev).encode() + b"\n")  # komuttan önce gelen olay
    f = conn.makefile("rwb")
    req = json.loads(f.readline())
    f.write(json.dumps({"error": "success", "data": 1, "request_id": req["request_id"]}).encode()
            + b"\n")
    f.flush()
    conn.sendall(json.dumps({"event": "seek"}).encode() + b"\n")
    f.read()  # istemci kapanana kadar bekle
    srv.close()


def test_events_queued_and_polled(tmp_path):
    path = str(tmp_path / "ev.sock")
    ready = threading.Event()
    threading.Thread(target=_serve_events, args=(path, ready), daemon=True).start()
    ready.wait(2)
    with MpvIpc(path) as mpv:
        assert mpv.command("get_property", "x") == 1
        first = mpv.poll_event(1.0)  # command() sırasında kuyruğa alınan olay kaybolmamalı
        assert first["event"] == "client-message"
        assert mpv.poll_event(1.0)["event"] == "seek"
        assert mpv.poll_event(0.1) is None  # zaman aşımı
