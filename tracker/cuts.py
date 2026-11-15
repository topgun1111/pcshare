"""Çekim kesmesi (shot cut) algılama (G4).

Kesme = ardışık iki kare arasında sahnenin tamamen değişmesi (kamera hareketi değil). İki bağımsız
işaret birlikte aranır (ikisi de eşiği geçmeli): (1) 2x2 ızgara renk (HSV) histogramı uzaklığı,
(2) küçük gri önizlemede ortalama mutlak fark. Üçüncü işaret LK kestirimi çökmesidir
(`gmc.CameraMotion.ok` False): varsa eşikler `relax` ile gevşetilir. Yavaş/hızlı kamera kaydırması
histogramı çok değiştirmez, ani yanıp sönme (flaş) fark verir ama histogram uzaklığı ızgarada
düşük kalır; bu yüzden ikisi birden gerekir. Yumuşak geçişler (çapraz solma) ARANMAZ.
Eşikler gerçek videoda ayarlanmadı (handover: G4 doğrulaması).
"""
from __future__ import annotations

import numpy as np


class CutDetector:
    def __init__(self, hist_thr: float = 0.35, diff_thr: float = 0.18, relax: float = 0.7,
                 width: int = 64) -> None:
        self.hist_thr, self.diff_thr, self.relax, self.width = hist_thr, diff_thr, relax, width
        self._hist: np.ndarray | None = None
        self._thumb: np.ndarray | None = None
        self.last_hist = self.last_diff = 0.0  # son karenin ölçümleri (ayar/bench için)

    def reset(self) -> None:
        self._hist = self._thumb = None

    def _features(self, frame_bgr: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        import cv2

        h, w = frame_bgr.shape[:2]
        tw = self.width
        th = max(int(round(h * tw / max(w, 1))), 8)
        small = cv2.resize(frame_bgr, (tw, th), interpolation=cv2.INTER_AREA)
        hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
        hist = []
        for ys in (slice(0, th // 2), slice(th // 2, th)):
            for xs in (slice(0, tw // 2), slice(tw // 2, tw)):
                blk = hsv[ys, xs]
                hh = cv2.calcHist([blk], [0, 1], None, [8, 4], [0, 180, 0, 256]).ravel()
                hist.append(hh / max(float(hh.sum()), 1.0))
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
        return np.concatenate(hist) / 4.0, gray  # hist toplamı 1

    def step(self, frame_bgr: np.ndarray, cam_ok: bool | None = None) -> bool:
        """Kareyi işle → önceki kareye göre kesme mi? İlk kare (ya da `reset` sonrası) False."""
        hist, thumb = self._features(frame_bgr)
        prev_h, prev_t = self._hist, self._thumb
        self._hist, self._thumb = hist, thumb
        if prev_h is None or prev_t is None or prev_t.shape != thumb.shape:
            self.last_hist = self.last_diff = 0.0
            return False
        self.last_hist = float(0.5 * np.abs(hist - prev_h).sum())  # 0..1
        self.last_diff = float(np.abs(thumb - prev_t).mean())  # 0..1
        k = self.relax if cam_ok is False else 1.0
        return self.last_hist >= self.hist_thr * k and self.last_diff >= self.diff_thr * k
