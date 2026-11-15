"""Kamera hareketi telafisi (GMC): kareden kareye arka plan dönüşümü (LK + RANSAC, benzerlik).

`estimate_step` saf kestirimdir; `CameraMotion` kareleri ardışık besler, hem kareden kareye
dönüşümü (`step` → önceki kare → bu kare, 3x3, tam çözünürlük) hem de birikmiş kare → dünya
dönüşümünü (`W`) tutar. ByteTrack tahmini (`Pipeline`), offline tarama ve nokta/eklem takibi
(point.py, pose.py) aynı kestirimi kullanır. Yalnız OpenCV çekirdek işlevleri.
"""
from __future__ import annotations

import numpy as np

# Kestirilemezse ya da hareket bundan büyükse (çekim kesmesi) kimlik döner.
MAX_SHIFT = 0.5  # kare genişliği oranı; bunun üstü kamera hareketi değil, kesme sayılır


def estimate_step(cv2, prev: np.ndarray, cur: np.ndarray, mask: np.ndarray | None = None):
    """Gri `prev` → `cur` arka plan dönüşümü: 2x3 benzerlik matrisi (cur → prev) ya da None."""
    pts = cv2.goodFeaturesToTrack(prev, 250, 0.01, 8, mask=mask)
    if pts is None or len(pts) < 10:
        return None
    lk = {"winSize": (21, 21), "maxLevel": 4,
          "criteria": (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03)}
    p1, st, _ = cv2.calcOpticalFlowPyrLK(prev, cur, pts, None, **lk)
    p0, st2, _ = cv2.calcOpticalFlowPyrLK(cur, prev, p1, None, **lk)
    fb = np.linalg.norm((pts - p0).reshape(-1, 2), axis=1)
    good = (st.ravel() == 1) & (st2.ravel() == 1) & (fb < 1.0)
    if int(good.sum()) < 10:
        return None
    a, b = p1[good].reshape(-1, 2), pts[good].reshape(-1, 2)  # kare t -> kare t-1
    A, inl = cv2.estimateAffinePartial2D(a, b, method=cv2.RANSAC, ransacReprojThreshold=2.0)
    if A is None or inl is None or int(inl.sum()) < 8:
        return None
    return A


class CameraMotion:
    """Ardışık karelerden kamera hareketi. `W`: kare → dünya (tam çözünürlük pikseli)."""

    def __init__(self, frame_bgr: np.ndarray | None = None, cam_side: int = 480):
        import cv2

        self._cv2 = cv2
        self.cam_side = cam_side
        self.fw = self.fh = 0
        self.cs = 1.0
        self.cprev: np.ndarray | None = None
        self.W = np.eye(3)
        self.last = np.eye(3)  # son adım: önceki kare → bu kare
        self.ok = False  # son adım kestirilebildi mi
        if frame_bgr is not None:
            self._init(frame_bgr)

    def _init(self, frame_bgr: np.ndarray) -> None:
        self.fh, self.fw = frame_bgr.shape[:2]
        self.cs = min(1.0, self.cam_side / max(self.fw, self.fh))
        self.cprev = self._cgray(frame_bgr)

    def reset(self) -> None:
        """Sonraki kare yeni başlangıç olur (atlama/kesme/hedef bırakma sonrası)."""
        self.cprev = None
        self.W = np.eye(3)
        self.last = np.eye(3)
        self.ok = False

    def _cgray(self, frame_bgr: np.ndarray) -> np.ndarray:
        cv2 = self._cv2
        g = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
        if self.cs < 1.0:
            g = cv2.resize(g, (round(self.fw * self.cs), round(self.fh * self.cs)),
                           interpolation=cv2.INTER_AREA)
        return g

    def step(self, frame_bgr: np.ndarray, boxes=()) -> np.ndarray:
        """Kareyi işle → önceki kareden bu kareye 3x3 dönüşüm (kestirilemezse kimlik).

        `boxes`: hareketli ön plan kutuları (önceki kare koordinatında; `BBox`) — köşe seçiminde
        maskelenir, böylece kişilerin hareketi kamera sanılmaz."""
        h, w = frame_bgr.shape[:2]
        if self.cprev is None or (w, h) != (self.fw, self.fh):
            self._init(frame_bgr)
            self.W, self.last, self.ok = np.eye(3), np.eye(3), False
            return self.last
        cur = self._cgray(frame_bgr)
        prev = self.cprev
        mask = np.full(prev.shape, 255, np.uint8)
        c = self.cs
        for b in boxes:
            x1, y1, x2, y2 = (int(b.x1 * c), int(b.y1 * c), int(b.x2 * c) + 1, int(b.y2 * c) + 1)
            mask[max(y1, 0):max(y2, 0), max(x1, 0):max(x2, 0)] = 0
        self.cprev = cur
        A = estimate_step(self._cv2, prev, cur, mask)
        self.ok = A is not None
        if A is None:
            self.last = np.eye(3)
            return self.last
        S = np.diag([c, c, 1.0])
        back = np.linalg.inv(S) @ np.vstack([A, [0, 0, 1]]) @ S  # kare t -> kare t-1
        fwd = np.linalg.inv(back)  # kare t-1 -> kare t
        if abs(fwd[0, 2]) > MAX_SHIFT * self.fw or abs(fwd[1, 2]) > MAX_SHIFT * self.fh:
            self.ok = False
            self.last = np.eye(3)
            return self.last
        self.W = self.W @ back
        self.last = fwd
        return fwd

    # CameraComp (eklem takibi) arayüzü
    def update(self, frame_bgr: np.ndarray, boxes=()) -> None:
        self.step(frame_bgr, boxes)

    def to_world(self, x: float, y: float) -> tuple[float, float]:
        v = self.W @ np.array([x, y, 1.0])
        return float(v[0]), float(v[1])

    def to_frame(self, wx: float, wy: float) -> tuple[float, float]:
        v = np.linalg.inv(self.W) @ np.array([wx, wy, 1.0])
        return float(v[0]), float(v[1])
