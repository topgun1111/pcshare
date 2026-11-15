"""mpv JSON IPC istemcisi (Windows named pipe + Unix socket)."""
from __future__ import annotations

import json
import os
import socket
import time
from collections import deque
from typing import Any

from .geometry import View


class MpvIpcError(RuntimeError):
    pass


class MpvCommandError(MpvIpcError):
    """mpv komutu reddetti (ör. dosya yokken `time-pos` → property unavailable)."""


class MpvIpc:
    def __init__(self, path: str, timeout: float = 2.0, keep_events: bool = True):
        self.path = path
        self.timeout = timeout
        self._next_id = 1
        self._buf = bytearray()
        self.keep_events = keep_events  # False: olay okumayan bağlantıda kuyruk şişmesin
        self._sock: socket.socket | None = None
        self._pipe = None
        self._events: deque[dict[str, Any]] = deque()

    # --- bağlantı ---
    def connect(self) -> None:
        if os.name == "nt":
            try:
                self._pipe = open(self.path, "r+b", buffering=0)
            except OSError as e:
                raise MpvIpcError(f"mpv pipe açılamadı: {self.path}: {e}") from e
        else:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.settimeout(self.timeout)
            try:
                s.connect(self.path)
            except OSError as e:
                s.close()
                raise MpvIpcError(f"mpv soketi açılamadı: {self.path}: {e}") from e
            self._sock = s

    def close(self) -> None:
        if self._sock:
            self._sock.close()
            self._sock = None
        if self._pipe:
            self._pipe.close()
            self._pipe = None

    def __enter__(self) -> MpvIpc:
        self.connect()
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # --- düşük seviye ---
    def _write(self, data: bytes) -> None:
        if self._sock:
            self._sock.sendall(data)
        elif self._pipe:
            self._pipe.write(data)
        else:
            raise MpvIpcError("bağlı değil")

    def _fill(self, timeout: float | None) -> bool:
        """Tampona veri ekler. Zaman aşımında False döner (yalnız Unix soketi)."""
        if self._pipe:
            try:
                chunk = self._pipe.read(1 << 16)  # satır satır okumak büyük karelerde çok yavaş
            except OSError as e:
                raise MpvIpcError(f"mpv pipe okunamadı: {e}") from e
        else:
            assert self._sock is not None
            self._sock.settimeout(self.timeout if timeout is None else timeout)
            try:
                chunk = self._sock.recv(1 << 20)
            except TimeoutError as e:
                if timeout is not None:
                    return False
                raise MpvIpcError("mpv yanıt zaman aşımı") from e
            finally:
                self._sock.settimeout(self.timeout)
        if not chunk:
            raise MpvIpcError("mpv bağlantısı kapandı")
        self._buf += chunk
        return True

    def _readline(self, timeout: float | None = None) -> bytes | None:
        """Bir satır okur. `timeout` verilirse ve süre dolarsa None döner (yalnız Unix soketi;
        Windows pipe'ında okuma bloklar → canlı modda olaylar ayrı bağlantı + iş parçacığında)."""
        start = 0
        while True:
            i = self._buf.find(b"\n", start)
            if i >= 0:
                break
            start = len(self._buf)  # her turda tamponu baştan taramamak için (MB'lık satırlar)
            if not self._fill(timeout):
                return None
        line = bytes(self._buf[:i])
        del self._buf[: i + 1]
        return line

    # --- komutlar ---
    def command(self, *args: Any) -> Any:
        rid = self._next_id
        self._next_id += 1
        self._write(json.dumps({"command": list(args), "request_id": rid}).encode() + b"\n")
        while True:
            line = self._readline()
            assert line is not None
            try:
                msg = json.loads(line)
            except ValueError:
                continue  # bozuk/yarım satır (ör. kanal açılırken) takip programını çökertmesin
            if not isinstance(msg, dict):
                continue
            if msg.get("request_id") != rid:
                if "event" in msg and self.keep_events:
                    self._events.append(msg)  # komut beklerken gelen olayı kaybetme
                continue
            if msg.get("error") != "success":
                raise MpvCommandError(f"mpv hatası: {msg.get('error')}")
            return msg.get("data")

    def poll_event(self, timeout: float = 0.5) -> dict[str, Any] | None:
        """Sıradaki mpv olayını döndürür; `timeout` içinde olay yoksa None."""
        if self._events:
            return self._events.popleft()
        while True:
            line = self._readline(timeout)
            if line is None:
                return None
            try:
                msg = json.loads(line)
            except ValueError:
                continue  # bozuk satır olay iş parçacığını sessizce öldürmesin
            if "event" in msg:
                return msg
            # request_id'li yanıtlar yalnız command() içinde işlenir; burada yok sayılır

    def set_property(self, name: str, value: Any) -> None:
        self.command("set_property", name, value)

    def get_property(self, name: str) -> Any:
        return self.command("get_property", name)

    def set_view(self, view: View) -> None:
        self.set_property("video-zoom", view.zoom)
        self.set_property("video-pan-x", view.pan_x)
        self.set_property("video-pan-y", view.pan_y)


def wait_for_ipc(path: str, timeout: float, *, connect=None, sleep=time.sleep,
                 clock=time.monotonic) -> bool:
    """mpv'nin IPC kanalı açılana kadar (en çok `timeout` sn) 0.5 sn arayla dener.
    Başlatıcı mpv'yi ve izleyiciyi aynı anda açtığında izleyicinin erken düşmesini önler."""
    def default_connect() -> None:
        c = MpvIpc(path)
        c.connect()
        c.close()

    connect = connect or default_connect
    end = clock() + max(timeout, 0.0)
    while True:
        try:
            connect()
            return True
        except MpvIpcError:
            if clock() >= end:
                return False
            sleep(0.5)
