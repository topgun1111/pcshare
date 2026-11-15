import math

from tracker.geometry import BBox, compute_view, pan_for_center, zoom_for_bbox


def test_bbox_props():
    b = BBox.from_xywh(100, 200, 40, 80)
    assert (b.cx, b.cy, b.w, b.h) == (120, 240, 40, 80)
    assert b.contains(120, 240) and not b.contains(0, 0)


def test_zoom_scales_to_target_fill():
    b = BBox.from_xywh(0, 0, 50, 100)  # 1080p'de ~%9.3 yükseklik
    z = zoom_for_bbox(b, 1920, 1080, target_fill=0.30, max_zoom=5)
    assert math.isclose(2**z, 0.30 / (100 / 1080), rel_tol=1e-9)


def test_zoom_clamped():
    tiny = BBox.from_xywh(0, 0, 2, 4)
    assert zoom_for_bbox(tiny, 1920, 1080, max_zoom=2.5) == 2.5
    huge = BBox.from_xywh(0, 0, 1900, 1000)
    assert zoom_for_bbox(huge, 1920, 1080, min_zoom=0.0) == 0.0


def test_pan_centered_target_is_zero():
    assert pan_for_center(0.5, 0.5, 1.0) == (0.0, 0.0)


def test_pan_clamped_at_edges():
    px, _ = pan_for_center(0.0, 0.5, 1.0)  # s=2 -> half=0.25 -> cx=0.25
    assert math.isclose(px, 0.25)
    px0, _ = pan_for_center(0.1, 0.5, 0.0)  # zoom yok -> kaydırma yok
    assert px0 == 0.0


def test_compute_view_right_target_pans_left():
    b = BBox.from_xywh(1400, 500, 30, 80)
    v = compute_view(b, 1920, 1080)
    assert v.zoom > 0 and v.pan_x < 0


def test_screen_to_video_letterbox():
    from tracker.geometry import screen_to_video

    # 1000x600 pencere, video yatayda 100px kenar boşluklu (pillarbox)
    assert screen_to_video(500, 300, 1000, 600, 100, 100, 0, 0) == (0.5, 0.5)
    assert screen_to_video(50, 300, 1000, 600, 100, 100, 0, 0) is None  # siyah bant


def test_screen_to_video_zoomed_negative_margins():
    from tracker.geometry import screen_to_video

    # 2x zoom: video pencereyi taşar, kenar boşlukları negatif
    nx, ny = screen_to_video(500, 300, 1000, 600, -500, -500, -300, -300)
    assert (nx, ny) == (0.5, 0.5)
    assert screen_to_video(0, 0, 1000, 600, -500, -500, -300, -300) == (0.25, 0.25)


def test_screen_to_video_degenerate():
    from tracker.geometry import screen_to_video

    assert screen_to_video(1, 1, 100, 100, 60, 60, 0, 0) is None


def test_bbox_scaled():
    b = BBox(10, 20, 30, 60).scaled(0.5, 2.0)
    assert (b.x1, b.y1, b.x2, b.y2) == (5.0, 40.0, 15.0, 120.0)
