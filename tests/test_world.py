"""WorldPointTracker: kamera kayarken hedef kare dışına çıkıp geri gelince takip sürer."""
import numpy as np
import pytest

cv2 = pytest.importorskip("cv2")

from tracker.clicks import parse_click  # noqa: E402
from tracker.point import WorldPointTracker  # noqa: E402

W, H = 640, 360


def _scene():
    rng = np.random.default_rng(3)
    bg = cv2.GaussianBlur(rng.integers(0, 255, (1200, 3000, 3), dtype=np.uint8), (0, 0), 2.0)
    bg = cv2.addWeighted(bg, 2.0, bg, 0, -100)
    tgt = rng.integers(0, 255, (24, 24, 3), dtype=np.uint8)
    return bg, tgt


def _frame(bg, tgt, cam_x, tx):
    f = bg[300:300 + H, cam_x:cam_x + W].copy()
    x = tx - cam_x  # hedef dünya x'i (sabit y=500)
    if -24 < x < W:
        xs, xe = max(x, 0), min(x + 24, W)
        f[200:224, xs:xe] = tgt[:, xs - x:xe - x]
    return f


def test_target_followed_off_screen_and_back():
    bg, tgt = _scene()
    cam, tx = 500, 800
    tr = WorldPointTracker(_frame(bg, tgt, cam, tx), tx - cam + 12, 212,
                           box=(tx - cam - 4, 196, tx - cam + 28, 228))
    # kamera sağa 8 px/kare kayar, hedef dünyada duruyor → sola çıkar (x<0), sonra kamera döner
    seen_off = False
    for i in range(1, 70):
        cam = 500 + 8 * i
        b = tr.update(_frame(bg, tgt, cam, tx))
        if not tr.visible:
            seen_off = True
        assert seen_off or abs(b.cx - (tx - cam + 12)) < 6
    assert seen_off and b.cx < 0  # kare dışı konum tahmin edildi (negatif x)
    assert abs(b.cx - (tx - cam + 12)) < 15  # tahmin gerçek konuma yakın
    for i in range(70, 140):
        cam = 500 + 8 * (140 - i)
        b = tr.update(_frame(bg, tgt, cam, tx))
    assert tr.visible
    assert abs(b.cx - (tx - cam + 12)) < 8 and abs(b.cy - 212) < 8


def test_parse_rect():
    ev = {"event": "client-message",
          "args": ["track-rect", "0.5", "0.6", "0.4", "0.7", "1920", "1080", "3.0"]}
    c = parse_click(ev)
    assert c.kind == "rect" and c.box == pytest.approx((768, 648, 960, 756))
    assert parse_click({"event": "client-message", "args": ["track-rect", "0.5"]}) is None
