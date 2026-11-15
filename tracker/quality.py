"""A2: canlı takip kalitesi penceresi + «taranmış ize geç» kararı (IO'suz, testli).

Canlı döngü her adımda `QualityWindow.add` çağırır; son `auto_window_s` sn boyunca canlı zayıfsa
(hız bütçenin altında / gecikme yüksek / hedef çok kayıp) `verdict` bir sebep döner. Taranmış iz
(`<video>.views.json`) hazır ve canlı hedefle aynı kişiyi gösteriyorsa (`scanned_agrees`) Lua'ya
`track-use-scanned` gider. Gerçek-klip eşikleri P23 `bench` ile ayarlanacak (V1)."""
from __future__ import annotations

import json
from collections import deque
from pathlib import Path

from .config import Config

SOURCES = ("auto", "live", "scanned")
MIN_SAMPLES = 3  # yavaş canlıda pencerede az örnek olur; asıl koşul süre
AGREE_TOL = 0.15  # canlı hedef merkezi ile taranmış görünüm merkezi arası (kare oranı)


class QualityWindow:
    def __init__(self, window_s: float = 5.0):
        self.window_s = window_s
        self._s: deque[tuple[float, float, bool]] = deque()  # (zaman, gecikme sn, hedef kayıp)
        self._t0: float | None = None  # ölçümün başladığı an (reset'ten sonraki ilk örnek)

    def reset(self) -> None:
        self._s.clear()
        self._t0 = None

    def add(self, now: float, lag_s: float, lost: bool) -> None:
        if self._t0 is None:
            self._t0 = now
        self._s.append((now, lag_s, lost))
        while self._s and now - self._s[0][0] > self.window_s:
            self._s.popleft()

    def stats(self) -> dict | None:
        """Ölçüm en az bir pencere süresi sürmeden (ve <3 örnekte) None."""
        n = len(self._s)
        if n < MIN_SAMPLES or self._t0 is None or self._s[-1][0] - self._t0 < self.window_s:
            return None
        span = self._s[-1][0] - self._s[0][0]
        return {"fps": (n - 1) / span if span > 0 else 0.0,
                "lag_ms": 1000 * sum(s[1] for s in self._s) / n,
                "lost_frac": sum(1 for s in self._s if s[2]) / n}

    def verdict(self, cfg: Config) -> str | None:
        """Canlı kötüyse sebep (OSD metni), değilse None."""
        st = self.stats()
        if st is None or cfg.auto_window_s <= 0:
            return None
        if st["fps"] < cfg.live_fps * cfg.auto_fps_ratio:
            return f"canlı yavaş: {st['fps']:.1f} kare/sn"
        if st["lag_ms"] > cfg.auto_lag_ms:
            return f"canlı gecikmeli: {st['lag_ms']:.0f} ms"
        if st["lost_frac"] > cfg.auto_lost_frac:
            return f"hedef sık kayıp: %{100 * st['lost_frac']:.0f}"
        return None


def views_ready(video: str | None) -> list | None:
    """`<video>.views.json` var, bu videoya ait ve anahtar karesi varsa görünümler, yoksa None."""
    if not video:
        return None
    from .cache import views_match

    p = Path(video + ".views.json")
    try:
        views = json.loads(p.read_text(encoding="utf-8")).get("views") or None
    except (OSError, ValueError, AttributeError):
        return None
    return views if views and views_match(p, video) else None


def scanned_agrees(views: list, t: float, dot: tuple[float, float] | None,
                   tol: float = AGREE_TOL) -> bool:
    """Canlı hedef (`dot`, video-normalize; None = kayıp) taranmış görünümün merkeziyle aynı yerde
    mi? Merkez = 0.5 − pan. Kayıpta (dot yok) taranmış iz güvenilir sayılır."""
    if dot is None:
        return True
    from .offline import view_at

    _, px, py = view_at(views, t)
    return abs((0.5 - px) - dot[0]) <= tol and abs((0.5 - py) - dot[1]) <= tol
