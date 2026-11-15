"""Basit hata günlüğü: takip hatalarını masaüstü/TakipHata/takip_hata.log'a ekler."""
from __future__ import annotations

import time
import traceback
from collections.abc import Callable
from pathlib import Path

MAX_BYTES = 1_000_000
get_video: Callable[[], object] | None = None  # run_live: mpv'deki geçerli video yolu


def _path() -> Path | None:
    home = Path.home()
    for d in (home / "Desktop", home / "OneDrive" / "Desktop", home / "Masaüstü"):
        if d.is_dir():
            return d / "TakipHata" / "takip_hata.log"
    return None


def record(where: str, exc: BaseException | str | None = None) -> None:
    """Hiçbir zaman hata fırlatmaz: günlük yazılamıyorsa sessizce geçer."""
    try:
        p = _path()
        if p is None:
            return
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.exists() and p.stat().st_size > MAX_BYTES:
            p.write_text("", encoding="utf-8")
        video = ""
        if get_video is not None:
            try:
                video = str(get_video() or "")
            except Exception:  # noqa: BLE001
                video = ""
        if isinstance(exc, BaseException):
            detail = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__)).rstrip()
        else:
            detail = str(exc or "")
        with open(p, "a", encoding="utf-8") as f:
            stamp = time.strftime("%Y-%m-%d %H:%M:%S")
            f.write(f"[{stamp}] {where} | video: {video}\n{detail}\n\n")
    except Exception:  # noqa: BLE001, S110
        pass
