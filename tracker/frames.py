"""mpv `screenshot-raw` yanıtını BGR numpy karesine çevirir (Parça 5)."""
from __future__ import annotations

import base64
import os
import tempfile
from typing import Any

import numpy as np

from . import errlog


def decode_raw(node: dict[str, Any]) -> np.ndarray:
    """`{w,h,stride,format,data}` → (h, w, 3) uint8 BGR.

    JSON IPC bayt dizilerini base64 metni olarak verir; eski sürümler için sayı listesi de kabul.
    """
    try:
        w, h, stride = int(node["w"]), int(node["h"]), int(node["stride"])
        fmt, data = node["format"], node["data"]
    except (KeyError, TypeError, ValueError) as e:
        raise ValueError(f"screenshot-raw yanıtı bozuk: {e}") from e
    if fmt not in ("bgr0", "bgra"):
        raise ValueError(f"desteklenmeyen kare formatı: {fmt}")
    buf = base64.b64decode(data) if isinstance(data, str) else bytes(data)
    if w <= 0 or h <= 0 or stride < w * 4 or len(buf) < stride * h:
        raise ValueError(f"kare verisi eksik: {w}x{h} stride={stride} bayt={len(buf)}")
    arr = np.frombuffer(buf, dtype=np.uint8, count=stride * h).reshape(h, stride)
    return np.ascontiguousarray(arr[:, : w * 4].reshape(h, w, 4)[:, :, :3])


_file_mode = False  # screenshot-raw JSON'u bozuksa (bazı Windows mpv derlemeleri) dosya yoluna geç


def _grab_file(mpv) -> np.ndarray:
    """`screenshot-to-file` ile kareyi geçici dosyadan okur (büyük JSON satırına gerek yok)."""
    import cv2

    from .ipc import MpvCommandError

    path = os.path.join(tempfile.gettempdir(), f"takipli_kare_{os.getpid()}.jpg")
    try:
        mpv.command("screenshot-to-file", path, "video")
        frame = cv2.imread(path)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
    if frame is None:
        raise MpvCommandError("kare dosyası okunamadı")  # geçici hata: döngü yeniden dener
    return np.ascontiguousarray(frame)


def grab_frame(mpv) -> np.ndarray:
    """O anki video karesini (altyazı/OSD'siz, zoom'suz, tam çözünürlük) BGR olarak alır."""
    global _file_mode
    if not _file_mode:
        try:
            return decode_raw(mpv.command("screenshot-raw", "video", "bgr0"))
        except ValueError as e:  # JSONDecodeError dahil: yanıt okunamadı → kalıcı olarak dosya yolu
            _file_mode = True
            errlog.record("screenshot-raw bozuk yanıt; screenshot-to-file'a geçildi", e)
    return _grab_file(mpv)
