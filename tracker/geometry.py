"""bbox -> mpv video-zoom / video-pan dönüşümü (saf matematik).

mpv semantiği:
  video-zoom   : log2 ölçek (0 = 1x, 1 = 2x)
  video-pan-x/y: ölçeklenmiş video boyutunun oranı; pozitif değer videoyu sağa/aşağı kaydırır.
Hedef normalize konumda (cx, cy) ise ekran merkezine getirmek için pan = 0.5 - c
(zoom'dan bağımsız). Kenar boşluğu görünmesin diye merkez [0.5/s, 1-0.5/s] aralığına kıstırılır.
"""
from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class BBox:
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def w(self) -> float:
        return max(self.x2 - self.x1, 0.0)

    @property
    def h(self) -> float:
        return max(self.y2 - self.y1, 0.0)

    @property
    def cx(self) -> float:
        return (self.x1 + self.x2) / 2

    @property
    def cy(self) -> float:
        return (self.y1 + self.y2) / 2

    @classmethod
    def from_xywh(cls, x: float, y: float, w: float, h: float) -> BBox:
        return cls(x, y, x + w, y + h)

    def scaled(self, sx: float, sy: float) -> BBox:
        return BBox(self.x1 * sx, self.y1 * sy, self.x2 * sx, self.y2 * sy)

    def contains(self, px: float, py: float) -> bool:
        return self.x1 <= px <= self.x2 and self.y1 <= py <= self.y2


@dataclass(frozen=True)
class View:
    zoom: float = 0.0
    pan_x: float = 0.0
    pan_y: float = 0.0


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def zoom_for_bbox(
    bbox: BBox,
    frame_w: float,
    frame_h: float,
    target_fill: float = 0.30,
    min_zoom: float = 0.0,
    max_zoom: float = 2.5,
) -> float:
    """bbox kare yüksekliğinin `target_fill` oranını kaplayacak log2 zoom."""
    h_frac = max(bbox.h / frame_h, 1e-6)
    w_frac = max(bbox.w / frame_w, 1e-6)
    scale = min(target_fill / h_frac, 0.9 / w_frac)  # genişlik de sığsın
    return _clamp(math.log2(max(scale, 1e-6)), min_zoom, max_zoom)


def pan_for_center(cx: float, cy: float, zoom: float) -> tuple[float, float]:
    """Normalize (0..1) merkezi ekran ortasına getiren pan değerleri."""
    half = 0.5 / (2.0**zoom)
    cx = _clamp(cx, half, 1.0 - half)
    cy = _clamp(cy, half, 1.0 - half)
    return 0.5 - cx, 0.5 - cy


def compute_view(
    bbox: BBox,
    frame_w: float,
    frame_h: float,
    target_fill: float = 0.30,
    min_zoom: float = 0.0,
    max_zoom: float = 2.5,
) -> View:
    zoom = zoom_for_bbox(bbox, frame_w, frame_h, target_fill, min_zoom, max_zoom)
    pan_x, pan_y = pan_for_center(bbox.cx / frame_w, bbox.cy / frame_h, zoom)
    return View(zoom, pan_x, pan_y)


def screen_to_video(
    mx: float, my: float, w: float, h: float, ml: float, mr: float, mt: float, mb: float
) -> tuple[float, float] | None:
    """Pencere pikselini video-normalize (0..1) koordinata çevirir.

    mpv `osd-dimensions` alanları: w,h pencere boyutu; ml,mr,mt,mb videonun pencere kenarlarına
    uzaklığı (zoom/pan dahil, negatif olabilir). Video dışına tıklanırsa None döner.
    track.lua içindeki `screen_to_video` ile birebir aynı formüldür.
    """
    vw = w - ml - mr
    vh = h - mt - mb
    if vw <= 0 or vh <= 0:
        return None
    nx = (mx - ml) / vw
    ny = (my - mt) / vh
    if not (0.0 <= nx <= 1.0 and 0.0 <= ny <= 1.0):
        return None
    return nx, ny
