"""Takım rengi (Parça 12): üst gövde HSV histogramı + k-means (k=2).

- `team_feature`: kutunun üst gövde bölgesinden (çim yeşili maskelenmiş) 15 boyutlu renk özelliği
  (12 ton kutusu + siyah/gri/beyaz); karekök (Hellinger) alınıp L2-normalize edilir.
- `kmeans2` / `TeamClassifier` (canlı): izlenen herkesten özellik toplar, yeterince örnekte iki
  kümeye ayırır; `label(f)` belirsizse (iki merkeze yakın) `None` döndürür.
- `TeamBank` (offline): izlerin ortalama özelliğinden `doc["team"] = {centers, labels}` üretir.
Takım yalnızca *eleme* içindir: etiketler biliniyor ve farklıysa aday elenir; belirsizse elenmez.
"""
from __future__ import annotations

from collections import deque

import cv2
import numpy as np

from .config import Config
from .geometry import BBox
from .reid import crop_ok, crop_person, l2norm

MIN_SEP = 0.15  # iki küme merkezi arası en az bu uzaklıkta değilse tek takım görünümü sayılır
DIM = 15


def torso(crop: np.ndarray, y0: float = 0.18, y1: float = 0.52, x0: float = 0.25,
          x1: float = 0.75) -> np.ndarray | None:
    h, w = crop.shape[:2]
    a, b, c, d = int(h * y0), max(int(h * y1), int(h * y0) + 1), int(w * x0), int(w * x1)
    if d - c < 1:
        return None
    r = crop[a:b, c:max(d, c + 1)]
    return r if r.size else None


def team_feature(frame: np.ndarray, box: BBox, min_px: int = 100) -> np.ndarray | None:
    """Üst gövde renk özelliği (DIM,) ya da yeterli geçerli piksel yoksa None."""
    crop = crop_person(frame, box)
    if crop is None:
        return None
    t = torso(crop)
    if t is None:
        return None
    hsv = cv2.cvtColor(np.ascontiguousarray(t), cv2.COLOR_BGR2HSV).reshape(-1, 3).astype(np.int32)
    h, s, v = hsv[:, 0], hsv[:, 1], hsv[:, 2]
    grass = (h >= 35) & (h <= 85) & (s >= 50) & (v >= 40)  # saha yeşili dışarıda
    hist = np.zeros(DIM, np.float64)
    keep = ~grass
    chroma = keep & (s >= 40) & (v >= 50)
    hb = ((h[chroma] + 7) // 15) % 12  # kırmızı bin 0'ın ortasında
    hist[:12] = np.bincount(hb, minlength=12)[:12]
    ach = keep & ~chroma
    low = ach & ((v < 70) | (s >= 40))  # koyu (ya da koyu-renkli) → siyah
    hist[12] = low.sum()
    rest = ach & ~low
    hist[13] = (rest & (v <= 170)).sum()  # gri
    hist[14] = (rest & (v > 170)).sum()  # beyaz
    n = hist.sum()
    if n < min_px:
        return None
    return l2norm(np.sqrt(hist / n)).astype(np.float32)


def kmeans2(x: np.ndarray, iters: int = 12) -> np.ndarray | None:
    """Deterministik k=2 k-means; kümeler ayrışmıyorsa (MIN_SEP) None. x: (N, D), N ≥ 2."""
    if len(x) < 2:
        return None
    c0 = x[int(np.argmax(np.linalg.norm(x - x.mean(0), axis=1)))]
    c1 = x[int(np.argmax(np.linalg.norm(x - c0, axis=1)))]
    c = np.stack([c0, c1]).astype(np.float64)
    for _ in range(iters):
        d = np.linalg.norm(x[:, None, :] - c[None], axis=2)
        a = d.argmin(1)
        new = np.array([x[a == k].mean(0) if np.any(a == k) else c[k] for k in range(2)])
        if np.allclose(new, c):
            break
        c = new
    if np.linalg.norm(c[0] - c[1]) < MIN_SEP:
        return None
    return c.astype(np.float32)


def assign(centers: np.ndarray, f: np.ndarray, margin: float) -> int | None:
    """En yakın merkez; (d2-d1)/(d1+d2) < margin ise belirsiz → None."""
    d = np.linalg.norm(centers - f, axis=1)
    k = int(d.argmin())
    tot = float(d.sum())
    if tot <= 1e-9 or (d.max() - d.min()) / tot < margin:
        return None
    return k


class TeamClassifier:
    """Canlı: özellik toplar, `team_min_samples`'ta ilk kez, sonra `team_refit` örnekte bir
    yeniden kümeler. Etiket numaraları yeniden kümelemede yer değiştirebilir; bu yüzden hedefin
    takımı etiketle değil özelliğiyle saklanır (bkz. ReIdManager)."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.feats: deque[np.ndarray] = deque(maxlen=600)
        self.centers: np.ndarray | None = None
        self._since = 0

    def add(self, f: np.ndarray) -> None:
        self.feats.append(f)
        self._since += 1
        n = len(self.feats)
        if n >= self.cfg.team_min_samples and (
                self.centers is None and self._since >= 10 or self._since >= self.cfg.team_refit):
            self._since = 0
            self.centers = kmeans2(np.stack(self.feats))

    def label(self, f: np.ndarray | None) -> int | None:
        if f is None or self.centers is None:
            return None
        return assign(self.centers, f, self.cfg.team_margin)


class TeamBank:
    """Offline: izlerin ortalama renk özelliği → iki takım etiketi."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.st: dict[int, list] = {}  # id → [toplam, adet]

    def update(self, frame: np.ndarray, tracks) -> None:
        fh, fw = frame.shape[:2]
        for tr in tracks:
            if not crop_ok(tr, tracks, fw, fh, self.cfg.reid_min_h, self.cfg.reid_max_iou):
                continue
            f = team_feature(frame, tr.bbox, self.cfg.team_min_px)
            if f is None:
                continue
            s = self.st.setdefault(tr.id, [np.zeros(DIM, np.float64), 0])
            s[0] += f
            s[1] += 1

    def result(self) -> dict | None:
        ids = [i for i, s in self.st.items() if s[1] >= self.cfg.team_min_samples]
        if len(ids) < 2:
            return None
        means = {i: l2norm(self.st[i][0] / self.st[i][1]) for i in ids}
        c = kmeans2(np.stack([means[i] for i in ids]))
        if c is None:
            return None
        labels = {}
        for i in ids:
            k = assign(c, means[i], self.cfg.team_margin)
            if k is not None:
                labels[str(i)] = k
        return {"centers": [[int(round(float(x) * 100)) for x in r] for r in c],
                "labels": labels}
