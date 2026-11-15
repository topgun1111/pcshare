"""E1: takip kamerası dışa aktarma — `<video>.views.json` anahtar karelerinden kare başına
kırp + ölçekle, ffmpeg'e ham BGR ver (libx264), özgün sesi aynen ekle.

Geometri (mpv ile aynı): ölçek s = 2^zoom, görünen alan = karenin 1/s'i, merkez = 0.5 − pan
(normalize), kenar boşluğu görünmesin diye kıstırılır. Çıktı boyutu kaynakla aynıdır.
ffmpeg yoksa (PATH / `TAKIPLI_FFMPEG` / `ffmpeg\\ffmpeg.exe`) `cv2.VideoWriter` ile sessiz
yedek kullanılır (uyarı basılır).
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from .config import Config
from .offline import view_at


def crop_rect(view: tuple[float, float, float], vw: int, vh: int) -> tuple[int, int, int, int]:
    """(zoom log2, pan_x, pan_y) → piksel kırpma (x, y, w, h); kare içinde, boyutlar ≥2."""
    z, px, py = view
    s = max(2.0 ** max(z, 0.0), 1.0)  # 1x altına inilmez: kenar boşluğu gösterilmez
    cw, ch = vw / s, vh / s
    cx = min(max((0.5 - px) * vw, cw / 2), vw - cw / 2)
    cy = min(max((0.5 - py) * vh, ch / 2), vh - ch / 2)
    w, h = max(int(round(cw)), 2), max(int(round(ch)), 2)
    x = min(max(int(round(cx - cw / 2)), 0), vw - w)
    y = min(max(int(round(cy - ch / 2)), 0), vh - h)
    return x, y, w, h


def render_frame(cv2, frame, view, size: tuple[int, int]):
    """Karenin görünüme göre kırpılıp `size` (w, h) boyuna ölçeklenmiş hali."""
    h, w = frame.shape[:2]
    x, y, cw, ch = crop_rect(view, w, h)
    crop = frame[y:y + ch, x:x + cw]
    if (cw, ch) == size:
        return crop
    interp = cv2.INTER_CUBIC if cw < size[0] else cv2.INTER_AREA
    return cv2.resize(crop, size, interpolation=interp)


def find_ffmpeg(explicit: str | None = None) -> str | None:
    cands = [explicit, os.environ.get("TAKIPLI_FFMPEG")]
    exe = Path(sys.executable).resolve().parent
    cands += [str(exe.parent / "ffmpeg" / "ffmpeg.exe"), str(exe / "ffmpeg.exe")]
    for c in cands:
        if c and Path(c).is_file():
            return c
    return shutil.which("ffmpeg")


def ffmpeg_cmd(ff: str, size: tuple[int, int], fps: float, src: str, out: str,
               audio: bool = True, crf: int = 18) -> list[str]:
    cmd = [ff, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24",
           "-s", f"{size[0]}x{size[1]}", "-r", f"{fps:.6f}", "-i", "-"]
    if audio:
        cmd += ["-i", src, "-map", "0:v:0", "-map", "1:a:0?", "-c:a", "copy", "-shortest"]
    return cmd + ["-c:v", "libx264", "-crf", str(crf), "-preset", "veryfast",
                  "-pix_fmt", "yuv420p", "-movflags", "+faststart", out]


def load_views(path: str | Path, video: str | None = None) -> list[list[float]]:
    """`video` verilirse ve dosya başka bir videonun imzasını (`vsig`) taşıyorsa ValueError (A3)."""
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    if video is not None:
        from .cache import views_match

        if not views_match(path, video):
            raise ValueError(f"{path}: bu görünüm başka bir videoya (ya da değişmiş dosyaya) ait; "
                             "yeniden tara (--rescan)")
    v = d.get("views") or []
    if not v:
        raise ValueError(f"{path}: görünüm anahtar karesi yok (önce hedef seçip tara)")
    return v


def _write_state(path: Path, state: str, n: int, total: int, error: str | None = None) -> None:
    tmp = path.with_name(path.name + ".tmp")
    pct = round(100 * n / total) if total else None
    tmp.write_text(json.dumps({"state": state, "n": n, "total": total, "pct": pct,
                               "error": error}), encoding="utf-8")
    os.replace(tmp, path)


def export_video(video: str, views: list[list[float]], out: str, cfg: Config | None = None, *,
                 ffmpeg: str | None = None, audio: bool = True, crf: int = 18,
                 progress=None, clock=time.monotonic) -> dict:
    """Videoyu görünümlerle dışa aktar → {frames, ffmpeg, audio, size, out}.
    `<out>.export.json` ilerleme, `<out>.export.cancel` varsa iptal (yarım çıktı silinir)."""
    import cv2

    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise FileNotFoundError(f"video açılamadı: {video}")
    w, h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
    size = (w - w % 2, h - h % 2)  # libx264/yuv420p çift boyut ister
    outp = Path(out)
    tmp = outp.with_name(outp.stem + ".partial" + outp.suffix)
    st = outp.with_name(outp.name + ".export.json")
    cancel = outp.with_name(outp.name + ".export.cancel")
    cancel.unlink(missing_ok=True)
    ff = find_ffmpeg(ffmpeg)
    proc = writer = None
    if ff:
        proc = subprocess.Popen(ffmpeg_cmd(ff, size, fps, video, str(tmp), audio, crf),
                                stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    else:
        print("UYARI: ffmpeg bulunamadı; sessiz yedek yazıcı (mp4v) kullanılıyor",
              file=sys.stderr)
        audio = False
        writer = cv2.VideoWriter(str(tmp), cv2.VideoWriter_fourcc(*"mp4v"), fps, size)
    n, last, stopped, err = 0, -1e9, False, None
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            img = render_frame(cv2, frame[:size[1], :size[0]], view_at(views, n / fps), size)
            if proc:
                try:
                    proc.stdin.write(img.tobytes())
                except (BrokenPipeError, OSError):
                    err = proc.stderr.read().decode("utf-8", "replace").strip() or "ffmpeg kapandı"
                    break
            else:
                writer.write(img)
            n += 1
            now = clock()
            if now - last >= 1.0:
                last = now
                _write_state(st, "running", n, total)
                if progress:
                    progress(n, total)
                if cancel.exists():
                    stopped = True
                    break
    finally:
        cap.release()
        if proc:
            try:
                proc.stdin.close()
            except OSError:
                pass
            rc = proc.wait()
            if rc != 0 and not stopped and err is None:
                err = proc.stderr.read().decode("utf-8", "replace").strip() or f"ffmpeg kodu {rc}"
        if writer:
            writer.release()
    if stopped or err:
        tmp.unlink(missing_ok=True)
        _write_state(st, "cancelled" if stopped else "error", n, total, err)
        cancel.unlink(missing_ok=True)
        if err:
            raise RuntimeError(f"dışa aktarma başarısız: {err}")
        return {"frames": n, "cancelled": True, "out": str(outp)}
    os.replace(tmp, outp)
    _write_state(st, "done", n, n or total)
    return {"frames": n, "ffmpeg": bool(ff), "audio": audio, "size": size, "out": str(outp)}


def default_out(video: str) -> str:
    p = Path(video)
    return str(p.with_name(p.stem + ".takipli.mp4"))
