"""Detector + ByteTracker birleşimi: kare başına step(frame)."""
from __future__ import annotations

from .bytetrack import ByteTracker, Track


class Pipeline:
    """`gmc` (gmc.CameraMotion) verilirse ByteTrack tahmini kamera hareketiyle telafi edilir.

    `cuts` (cuts.CutDetector, G4) verilirse her karede çekim kesmesi aranır: kesmede izler
    bırakılır (kimlik sayacı korunur), kamera kestirimi sıfırlanır ve `cut` True olur.
    (Canlı modda kesmeyi `LiveController` arar; Pipeline'a `cuts` verilmez.)"""

    def __init__(self, detector, tracker: ByteTracker | None = None, gmc=None, cuts=None):
        self.detector = detector
        self.tracker = tracker or ByteTracker()
        self.gmc = gmc
        self.cuts = cuts
        self.cut = False  # son step bir kesmeyle mi başladı
        self.cam: list[float] | None = None  # son step: [a, b, tx/h, ty/h] (yalnız kestirim tamamsa)

    def step(self, frame_bgr) -> list[Track]:
        motion = None
        self.cut = False
        self.cam = None
        if self.gmc is not None:
            try:
                motion = self.gmc.step(frame_bgr, self.tracker.boxes)
            except Exception:  # kamera kestirimi asla izlemeyi düşürmesin
                motion = None
        if self.cuts is not None:
            try:
                cam_ok = getattr(self.gmc, "ok", None) if self.gmc is not None else None
                self.cut = self.cuts.step(frame_bgr, cam_ok)
            except Exception:  # algılama asla izlemeyi düşürmesin
                self.cut = False
            if self.cut:
                self.on_cut()
                motion = None
        if motion is not None and getattr(self.gmc, "ok", False):
            self.cam = cam_row(motion, frame_bgr.shape[0])
        return self.tracker.update(self.detector.detect(frame_bgr), motion)

    def on_cut(self) -> None:
        """Çekim kesmesi: eski sahnenin izleri/kamera geçmişi geçersiz."""
        self.tracker.clear()
        if self.gmc is not None:
            self.gmc.reset()

    def reset(self) -> None:
        self.tracker.reset()
        if self.gmc is not None:
            self.gmc.reset()
        if self.cuts is not None:
            self.cuts.reset()
        self.cut = False


def cam_row(motion, h: int) -> list[float]:
    """3x3 önceki→bu kare benzerlik dönüşümü → [a, b, tx/h, ty/h] (a = ölçek·cos, b = ölçek·sin;
    öteleme kare yüksekliği biriminde: çözünürlükten bağımsız). `track.json` örneğindeki `cam`."""
    return [round(float(motion[0][0]), 6), round(float(motion[1][0]), 6),
            round(float(motion[0][2]) / h, 5), round(float(motion[1][2]) / h, 5)]


def make_cuts(cfg):
    """Config.cut_detect açıksa ve OpenCV varsa CutDetector, yoksa None."""
    if not getattr(cfg, "cut_detect", False):
        return None
    try:
        from .cuts import CutDetector

        return CutDetector(cfg.cut_hist, cfg.cut_diff)
    except Exception:
        return None


def make_gmc(cfg):
    """Config.gmc açıksa ve OpenCV varsa CameraMotion, yoksa None."""
    if not getattr(cfg, "gmc", False):
        return None
    try:
        from .gmc import CameraMotion

        return CameraMotion(cam_side=cfg.gmc_side)
    except Exception:
        return None


class NullPipeline:
    """Person modeli yokken: iz üretmez. Yalnız sağ tık nesne takibi (point.py) çalışır."""

    def step(self, frame_bgr) -> list[Track]:
        return []

    def on_cut(self) -> None:
        pass

    def reset(self) -> None:
        pass
