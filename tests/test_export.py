"""E1: takip kamerası dışa aktarma (kırpma geometrisi, ffmpeg komutu, uçtan uca)."""
import json
import shutil
import subprocess

import cv2
import numpy as np
import pytest

from tracker.export import (
    crop_rect,
    default_out,
    export_video,
    ffmpeg_cmd,
    find_ffmpeg,
    load_views,
    render_frame,
)
from tracker.geometry import pan_for_center


def test_crop_rect_identity_zoom_and_clamp():
    assert crop_rect((0.0, 0.0, 0.0), 640, 360) == (0, 0, 640, 360)
    x, y, w, h = crop_rect((1.0, 0.0, 0.0), 640, 360)  # 2x, merkez
    assert (w, h) == (320, 180) and (x, y) == (160, 90)
    z = 2.0  # hedef (0.8, 0.5): pan geometri.py ile aynı formül
    px, py = pan_for_center(0.8, 0.5, z)
    x, y, w, h = crop_rect((z, px, py), 640, 360)
    assert w == 160 and abs((x + w / 2) - 0.8 * 640) <= 1
    x, y, w, h = crop_rect((1.0, -0.5, 0.5), 640, 360)  # taşan pan kıstırılır
    assert x + w == 640 and y == 0
    assert crop_rect((-1.0, 0.0, 0.0), 640, 360) == (0, 0, 640, 360)  # 1x altı yok


def test_render_frame_crops_the_target_region():
    f = np.zeros((360, 640, 3), np.uint8)
    f[150:210, 380:440] = 255  # (0.6, 0.5) çevresinde beyaz kare
    px, py = pan_for_center(0.62, 0.5, 3.0)
    img = render_frame(cv2, f, (3.0, px, py), (640, 360))
    assert img.shape == (360, 640, 3) and img[180, 320].min() == 255
    assert img[5, 5].max() == 0 or img[5, 5].max() == 255  # çökmeden


def test_ffmpeg_cmd_options():
    c = ffmpeg_cmd("ff", (1280, 720), 29.97, "in.mp4", "out.mp4")
    assert c[:2] == ["ff", "-y"] and "libx264" in c and "1280x720" in c
    assert "-c:a" in c and c[c.index("-c:a") + 1] == "copy" and "1:a:0?" in c
    n = ffmpeg_cmd("ff", (640, 360), 25.0, "in.mp4", "o.mp4", audio=False, crf=23)
    assert "-c:a" not in n and n[n.index("-crf") + 1] == "23"
    assert default_out("C:/v/maç.mp4").endswith("maç.takipli.mp4")


def test_load_views_requires_keyframes(tmp_path):
    p = tmp_path / "a.views.json"
    p.write_text(json.dumps({"version": 1, "views": []}))
    with pytest.raises(ValueError):
        load_views(p)
    p.write_text(json.dumps({"version": 1, "views": [[0, 0, 0, 0]]}))
    assert load_views(p) == [[0, 0, 0, 0]]


def _make_video(path, n=30, w=320, h=180):
    vw = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), 10.0, (w, h))
    for k in range(n):
        f = np.zeros((h, w, 3), np.uint8)
        f[:, :, 1] = 40
        x = int(20 + k * 8)
        f[80:110, x:x + 30] = 255  # sağa giden beyaz kare
        vw.write(f)
    vw.release()


def test_export_end_to_end_follow_cam(tmp_path):
    v, out = tmp_path / "c.avi", tmp_path / "c.takipli.mp4"
    _make_video(v)
    views = [[0.0, 2.0, *pan_for_center(35 / 320, 95 / 180, 2.0)],
             [2.9, 2.0, *pan_for_center(267 / 320, 95 / 180, 2.0)]]
    r = export_video(str(v), views, str(out))
    assert out.exists() and r["frames"] == 30 and r["size"] == (320, 180)
    assert not list(tmp_path.glob("*.partial*"))
    st = json.loads((tmp_path / "c.takipli.mp4.export.json").read_text())
    assert st["state"] == "done" and st["pct"] == 100
    cap = cv2.VideoCapture(str(out))
    ok, first = cap.read()
    cap.set(cv2.CAP_PROP_POS_FRAMES, 28)
    ok2, last = cap.read()
    cap.release()
    assert ok and ok2
    for img in (first, last):  # takip: beyaz kare hep ortada
        assert img[70:110, 140:180].max() > 200


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg yok")
def test_export_keeps_audio_track(tmp_path):
    v = tmp_path / "s.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                    "testsrc=size=320x180:rate=10:duration=2", "-f", "lavfi", "-i",
                    "sine=frequency=440:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-shortest", str(v)], check=True)
    out = tmp_path / "s.takipli.mp4"
    r = export_video(str(v), [[0, 1.0, 0.0, 0.0]], str(out))
    assert r["audio"] and find_ffmpeg()
    p = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of",
                        "csv=p=0", str(out)], capture_output=True, text=True)
    assert "audio" in p.stdout and "video" in p.stdout


def test_export_cancel_removes_partial(tmp_path):
    v, out = tmp_path / "c.avi", tmp_path / "o.mp4"
    _make_video(v, n=40)
    (tmp_path / "o.mp4.export.cancel").write_text("x")
    # iptal dosyası başlangıçta silinir; 1. kareden sonra tekrar koyan saat ile iptal tetiklenir
    ticks = iter(range(0, 1000, 2))

    def clock():
        (tmp_path / "o.mp4.export.cancel").write_text("x")
        return next(ticks)

    r = export_video(str(v), [[0, 0.0, 0.0, 0.0]], str(out), clock=clock)
    assert r["cancelled"] and not out.exists() and not list(tmp_path.glob("*.partial*"))
    assert json.loads((tmp_path / "o.mp4.export.json").read_text())["state"] == "cancelled"
