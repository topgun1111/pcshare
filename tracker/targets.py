"""T1: birden çok hedef (kişi/parça/nesne izleyicisi) + birleşik kadraj.

`TargetSet` bir izleyici gibi davranır (`update/bbox/visible/label/on_cut`, point.py arayüzü): her
üyeyi ayrı günceller, görünen üyelerin kutusunu BİRLEŞTİRİR. Birleşim, kare boyutuna göre en az
`min_zoom` (log2) yakınlaştırma gerektirecek kadar büyürse (hedefler birbirinden çok uzak), en
düşük öncelikli/en uzak üyeler kadrajdan çıkarılır; en öncelikli üye her zaman kalır. Histerezis:
çıkarılan üye, ancak eşiğin `hyst` (log2) üstünde sığıyorsa geri alınır (kadraj titremez).
Öncelik = ağırlık; varsayılan eklenme sırası (ilk eklenen en yüksek).
"""
from __future__ import annotations

import math
from dataclasses import dataclass

from .geometry import BBox, zoom_for_bbox


def union_box(boxes: list[BBox]) -> BBox:
    return BBox(min(b.x1 for b in boxes), min(b.y1 for b in boxes),
                max(b.x2 for b in boxes), max(b.y2 for b in boxes))


@dataclass
class Member:
    tracker: object
    weight: float


class TargetSet:
    def __init__(self, first, *, fill: float = 0.30, min_zoom: float = 0.35,
                 hyst: float = 0.25) -> None:
        self.fill, self.min_zoom, self.hyst = fill, min_zoom, hyst
        self.members: list[Member] = []
        self._dropped: set[int] = set()  # kadrajdan çıkarılan üyeler (id(tracker))
        self._box: BBox | None = None
        self.visible = True
        self.add(first)

    def __len__(self) -> int:
        return len(self.members)

    @property
    def trackers(self) -> list:
        return [m.tracker for m in self.members]

    @property
    def framed(self) -> int:
        """Şu an kadrajdaki üye sayısı."""
        return len(self.members) - len(self._dropped & {id(m.tracker) for m in self.members})

    def add(self, tracker, weight: float | None = None) -> None:
        w = weight if weight is not None else max(1.0 - 0.1 * len(self.members), 0.1)
        self.members.append(Member(tracker, w))
        self._box = union_box([m.tracker.bbox for m in self.members])

    def set_weight(self, i: int, weight: float) -> None:
        self.members[i].weight = max(weight, 0.01)

    def remove(self, i: int) -> object:
        m = self.members.pop(i)
        self._dropped.discard(id(m.tracker))
        if self.members:
            self._box = union_box([x.tracker.bbox for x in self.members])
        return m.tracker

    def member_at(self, px: float, py: float) -> int | None:
        """(px, py) hangi üyenin kutusunun içinde (birden çoksa en yakın merkez); yoksa None."""
        hits = [(math.hypot(m.tracker.bbox.cx - px, m.tracker.bbox.cy - py), i)
                for i, m in enumerate(self.members) if m.tracker.bbox.contains(px, py)]
        return min(hits)[1] if hits else None

    @property
    def label(self) -> str:
        if len(self.members) == 1:
            return getattr(self.members[0].tracker, "label", "nesne")
        n, f = len(self.members), self.framed
        return f"{n} hedef" + (f" ({f} kadrajda)" if f < n else "")

    @property
    def bbox(self) -> BBox:
        return self._box if self._box is not None else union_box(
            [m.tracker.bbox for m in self.members])

    def on_cut(self) -> None:
        for m in self.members:
            if hasattr(m.tracker, "on_cut"):
                m.tracker.on_cut()

    def _fits(self, box: BBox, fw: int, fh: int, extra: float) -> bool:
        z = zoom_for_bbox(box, fw, fh, self.fill, -99.0, 99.0)
        return z >= self.min_zoom + extra

    def update(self, frame) -> BBox | None:
        fh, fw = frame.shape[:2]
        live = []
        for m in self.members:
            b = m.tracker.update(frame)
            if b is not None:
                live.append((m, b, bool(getattr(m.tracker, "visible", True))))
        if not live:
            self.visible = False
            return None
        seen = [x for x in live if x[2]] or live  # kare dışına öngörülenler: görünen varsa sayılmaz
        self.visible = any(x[2] for x in live)
        seen.sort(key=lambda x: -x[0].weight)  # sıralama kararlı: eşitlikte eklenme sırası
        anchor = seen[0]
        inc = [anchor[1]]
        ax, ay = anchor[1].cx, anchor[1].cy
        for m, b, _ in sorted(seen[1:], key=lambda x: (-x[0].weight,
                                                      math.hypot(x[1].cx - ax, x[1].cy - ay))):
            key = id(m.tracker)
            extra = self.hyst if key in self._dropped else 0.0
            if self._fits(union_box(inc + [b]), fw, fh, extra):
                inc.append(b)
                self._dropped.discard(key)
            else:
                self._dropped.add(key)
        self._box = union_box(inc)
        return self._box
