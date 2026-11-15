"""ByteTrack: Kalman (cx,cy,a,h) + IoU, iki aşamalı eşleştirme. Saf numpy."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .geometry import BBox


@dataclass(frozen=True)
class Track:
    id: int
    bbox: BBox
    score: float
    age: int  # toplam kare sayısı (doğumdan beri)
    lost: int  # ardışık eşleşmeyen kare (0 = bu karede görüldü)


def linear_assignment(cost: np.ndarray) -> list[tuple[int, int]]:
    """Macar algoritması (O(n^3), dikdörtgen matris destekli). (satır, sütun) çiftleri."""
    if cost.size == 0:
        return []
    transposed = cost.shape[0] > cost.shape[1]
    c = cost.T if transposed else cost
    n, m = c.shape  # n <= m
    inf = float("inf")
    u = np.zeros(n + 1)
    v = np.zeros(m + 1)
    p = np.zeros(m + 1, dtype=int)
    way = np.zeros(m + 1, dtype=int)
    for i in range(1, n + 1):
        p[0] = i
        j0 = 0
        minv = np.full(m + 1, inf)
        used = np.zeros(m + 1, dtype=bool)
        while True:
            used[j0] = True
            i0 = p[j0]
            delta = inf
            j1 = 0
            for j in range(1, m + 1):
                if used[j]:
                    continue
                cur = c[i0 - 1, j - 1] - u[i0] - v[j]
                if cur < minv[j]:
                    minv[j] = cur
                    way[j] = j0
                if minv[j] < delta:
                    delta = minv[j]
                    j1 = j
            for j in range(m + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while j0:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
    pairs = [(int(p[j]) - 1, j - 1) for j in range(1, m + 1) if p[j]]
    return [(b, a) for a, b in pairs] if transposed else pairs


def iou_matrix(a: list[BBox], b: list[BBox]) -> np.ndarray:
    out = np.zeros((len(a), len(b)))
    for i, p in enumerate(a):
        for j, q in enumerate(b):
            iw = min(p.x2, q.x2) - max(p.x1, q.x1)
            ih = min(p.y2, q.y2) - max(p.y1, q.y1)
            if iw <= 0 or ih <= 0:
                continue
            inter = iw * ih
            union = p.w * p.h + q.w * q.h - inter
            out[i, j] = inter / union if union > 0 else 0.0
    return out


def match(
    tracks: list[BBox], dets: list[BBox], iou_thr: float
) -> tuple[list[tuple[int, int]], list[int], list[int]]:
    """IoU ile eşleştir → (eşleşmeler, eşleşmeyen track idx, eşleşmeyen det idx)."""
    if not tracks or not dets:
        return [], list(range(len(tracks))), list(range(len(dets)))
    iou = iou_matrix(tracks, dets)
    pairs = [(i, j) for i, j in linear_assignment(1.0 - iou) if iou[i, j] >= iou_thr]
    mt = {i for i, _ in pairs}
    md = {j for _, j in pairs}
    return (
        pairs,
        [i for i in range(len(tracks)) if i not in mt],
        [j for j in range(len(dets)) if j not in md],
    )


class KalmanBox:
    """Sabit hız modeli. Durum: [cx,cy,a,h, vcx,vcy,va,vh]; a = w/h."""

    _STD_POS = 1 / 20
    _STD_VEL = 1 / 160

    def __init__(self, bbox: BBox):
        h = max(bbox.h, 1.0)
        self.x = np.array([bbox.cx, bbox.cy, bbox.w / h, h, 0, 0, 0, 0], dtype=float)
        self.F = np.eye(8)
        for i in range(4):
            self.F[i, i + 4] = 1.0
        self.H = np.eye(4, 8)
        s = [2 * self._STD_POS * h] * 2 + [1e-2] + [2 * self._STD_POS * h]
        s += [10 * self._STD_VEL * h] * 2 + [1e-5] + [10 * self._STD_VEL * h]
        self.P = np.diag(np.square(s))

    def predict(self) -> None:
        h = self.x[3]
        q = [self._STD_POS * h] * 2 + [1e-2] + [self._STD_POS * h]
        q += [self._STD_VEL * h] * 2 + [1e-5] + [self._STD_VEL * h]
        self.x = self.F @ self.x
        self.P = self.F @ self.P @ self.F.T + np.diag(np.square(q))
        self.x[3] = max(self.x[3], 1.0)
        self.x[2] = max(self.x[2], 1e-3)

    def update(self, bbox: BBox) -> None:
        h = max(bbox.h, 1.0)
        z = np.array([bbox.cx, bbox.cy, bbox.w / h, h])
        r = [self._STD_POS * self.x[3]] * 2 + [1e-1] + [self._STD_POS * self.x[3]]
        R = np.diag(np.square(r))
        S = self.H @ self.P @ self.H.T + R
        K = self.P @ self.H.T @ np.linalg.inv(S)
        self.x = self.x + K @ (z - self.H @ self.x)
        self.P = (np.eye(8) - K @ self.H) @ self.P

    def compensate(self, M: np.ndarray) -> None:
        """Kamera hareketi: önceki kare → bu kare 3x3 benzerlik dönüşümü (konum, boy, hız)."""
        L = M[:2, :2]
        self.x[:2] = L @ self.x[:2] + M[:2, 2]
        self.x[4:6] = L @ self.x[4:6]
        s = float(np.sqrt(abs(np.linalg.det(L))))
        if s > 0:
            self.x[3] *= s
            self.x[7] *= s
            self.x[3] = max(self.x[3], 1.0)

    @property
    def bbox(self) -> BBox:
        cx, cy, a, h = self.x[:4]
        w = a * h
        return BBox(float(cx - w / 2), float(cy - h / 2), float(cx + w / 2), float(cy + h / 2))


class _Internal:
    def __init__(self, tid: int, bbox: BBox, score: float):
        self.id = tid
        self.kf = KalmanBox(bbox)
        self.score = score
        self.age = 1
        self.lost = 0
        self.hits = 1

    @property
    def bbox(self) -> BBox:
        return self.kf.bbox


class ByteTracker:
    def __init__(
        self,
        high_thr: float = 0.5,
        low_thr: float = 0.1,
        new_thr: float | None = None,
        match_iou: float = 0.3,
        second_iou: float = 0.5,
        unconfirmed_iou: float = 0.3,
        max_lost: int = 30,
        min_hits: int = 1,
    ):
        self.high_thr, self.low_thr = high_thr, low_thr
        self.new_thr = high_thr if new_thr is None else new_thr
        self.match_iou, self.second_iou = match_iou, second_iou
        self.unconfirmed_iou = unconfirmed_iou
        self.max_lost = max_lost
        self.min_hits = min_hits
        self._tracks: list[_Internal] = []
        self._next_id = 1

    def set_next_id(self, n: int) -> None:
        """Taramayı sürdürürken kimlikler önceki örneklerle çakışmasın."""
        self._next_id = max(self._next_id, int(n))

    def reset(self) -> None:
        self._tracks = []
        self._next_id = 1

    def clear(self) -> None:
        """İzleri bırak ama kimlik sayacını KORU (G4: çekim kesmesi; eski kimlikler yeniden
        verilmez, hedefi sonradan Re-ID/poz ile yeni kimliğe bağlamak mümkün kalır)."""
        self._tracks = []

    @property
    def boxes(self) -> list[BBox]:
        """Canlı izlerin son kutuları (kamera kestirimi köşe seçerken bunları maskeler)."""
        return [t.bbox for t in self._tracks]

    def update(self, detections, motion: np.ndarray | None = None) -> list[Track]:
        """detections: Detection listesi (bbox, score). Dönüş: bu karede GÖRÜLEN izler.
        `motion`: önceki kare → bu kare kamera dönüşümü (3x3, gmc.py); verilirse tahmin
        kamera kaymasıyla birlikte yapılır (kimlik/None = sabit kamera)."""
        high = [d for d in detections if d.score >= self.high_thr]
        low = [d for d in detections if self.low_thr <= d.score < self.high_thr]

        for t in self._tracks:
            t.kf.predict()
            if motion is not None:
                t.kf.compensate(motion)
            t.age += 1
            t.lost += 1  # eşleşirse sıfırlanır

        pool = list(self._tracks)
        seen: set[int] = set()

        def apply(tracks, dets, pairs):
            for i, j in pairs:
                t = tracks[i]
                t.kf.update(dets[j].bbox)
                t.score = dets[j].score
                t.lost = 0
                t.hits += 1
                seen.add(t.id)

        # 1. aşama: yüksek skor
        pairs, ut, ud = match([t.bbox for t in pool], [d.bbox for d in high], self.match_iou)
        apply(pool, high, pairs)
        rest = [pool[i] for i in ut]
        rest_high = [high[j] for j in ud]

        # 2. aşama: düşük skor, kalan izler (ByteTrack'in özü)
        pairs2, _, _ = match([t.bbox for t in rest], [d.bbox for d in low], self.second_iou)
        apply(rest, low, pairs2)
        # eşleşmeyen izler lost>=1 olarak kalır

        # yeni nesne: eşleşmeyen yüksek skorlu tespitler
        for d in rest_high:
            if d.score >= self.new_thr:
                t = _Internal(self._next_id, d.bbox, d.score)
                self._next_id += 1
                self._tracks.append(t)
                seen.add(t.id)

        # kayıp izleri temizle
        self._tracks = [t for t in self._tracks if t.lost <= self.max_lost]
        return [
            Track(t.id, t.bbox, t.score, t.age, t.lost)
            for t in self._tracks
            if t.id in seen and t.hits >= self.min_hits
        ]

    @property
    def lost_tracks(self) -> list[Track]:
        """Şu an kayıp (tahmin edilen konumda bekleyen) izler — Parça 8 için."""
        return [Track(t.id, t.bbox, t.score, t.age, t.lost) for t in self._tracks if t.lost > 0]
