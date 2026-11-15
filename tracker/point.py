"""Sınıfsız nokta/nesne izleyici (Parça 18): sağ tıklanan nokta ne olursa olsun takip edilir.

YOLO/ByteTrack yalnız "person" bilir; burada nesnenin ne olduğu önemli değildir (top, araba, kuş,
tahtadaki bir şekil...). Tıklanan noktadan bir başlangıç kutusu çıkarılır, sonra her karede:

1. **Hareket**: kutunun çekirdeğindeki köşe noktaları Lucas-Kanade optik akışıyla izlenir
   (ileri-geri tutarlılık süzgeciyle); medyan kayma kutuyu taşır, noktaların merkeze uzaklık
   oranı ölçeği verir.
2. **Doğrulama**: kutunun içeriği, başlangıç görünümünden (yavaşça güncellenen) şablonla normalize
   çapraz ilinti (NCC) ile karşılaştırılır; düşükse akış yanlış şeyi (arka planı) izliyor demektir.
3. **Yeniden bulma**: akış tutmazsa (dokusuz nesne, hızlı hareket) ya da doğrulama düşerse son
   konumun çevresinde şablon eşleştirme yapılır; bulunamazsa nesne **kayıp** sayılır (`update` None
   döner) ve arama penceresi kayıp süresiyle büyür.

Yalnız OpenCV çekirdek işlevleri (`calcOpticalFlowPyrLK`, `matchTemplate`, `floodFill`) kullanılır:
`cv2.TrackerCSRT/KCF` OpenCV 5'te çekirdekten çıktı (contrib gerekir), o yüzden kullanılmadı.
Kareler en uzun kenarı `max_side` olacak şekilde küçültülerek işlenir.

Tek tıklamadan nesnenin kapladığı alan bilinemez; `refine_box` aynı renkteki bitişik bölgeyi
büyüterek (flood fill) kutuyu tahmin eder, uygun bir bölge çıkmazsa karenin `box_frac` oranında bir
kare kutu kullanılır. Zoom miktarı `+`/`-` ile (doluluk) ayarlanır.
"""
from __future__ import annotations

import numpy as np

from .geometry import BBox
from .gmc import CameraMotion, estimate_step

_MIN_WORK_BOX = 20  # çalışma ölçeğinde kutunun en küçük kenarı (px)
_CORE = 0.6  # köşe noktaları kutunun ortadaki bu oranından seçilir (kenardaki arka planı ele)
_FB_ERR = 1.0  # ileri-geri akış hatası üst sınırı (px; 10 fps'te hızlı harekette 1 px çok sıkıydı)
_MIN_PTS = 6  # akışın güvenilir sayılması için en az iyi nokta
_REFRESH_PTS = 12  # bunun altına inince yeni köşe noktaları aranır
_UPDATE_SCORE = 0.75  # bunun üstünde şablon yavaşça güncellenir (görünüm değişince kayıp olmasın)
_TRUST_PTS = 10  # akış bu kadar tutarlı noktayla destekleniyorsa şablon skoru düşük olsa da güven
_TRUST_SCORE = 0.3  # ... ama şablon skoru bunun altına inmemeli (arka plana kaymayı önler)
_TRUST_SPREAD = 1.5  # noktaların kayma vektörleri medyandan en çok bu kadar (px) sapabilir
_TRUST_MAX = 8  # ardışık en çok bu kadar kare akışa şablon onayı olmadan güvenilir
_MAX_SCALE_STEP = 1.1  # kare başına en çok ölçek değişimi


def default_box(fw: int, fh: int, px: float, py: float, frac: float) -> tuple[float, ...]:
    """(px, py) merkezli, kare yüksekliğinin `frac` oranında kare kutu; kare içinde kalır."""
    side = max(min(frac * fh, fw, fh), 8.0)
    x1 = min(max(px - side / 2, 0.0), fw - side)
    y1 = min(max(py - side / 2, 0.0), fh - side)
    return x1, y1, x1 + side, y1 + side


def refine_box(frame_bgr: np.ndarray, px: float, py: float, tol: int = 14,
               min_frac: float = 0.03, max_frac: float = 0.35) -> tuple[float, ...] | None:
    """Tıklanan noktayla aynı renkteki bitişik bölgenin kutusu (kenarlarına %30 pay).

    Bölge çok küçükse (gürültü/doku), kutu çerçevesini taşırıp arka planı kapsıyorsa
    ya da dolgu oranı düşükse (ince çizgi) None döner → çağıran varsayılan kutuyu kullanır.
    """
    import cv2

    fh, fw = frame_bgr.shape[:2]
    r = int(0.3 * fh)
    x0, y0 = max(int(px) - r, 0), max(int(py) - r, 0)
    x1, y1 = min(int(px) + r + 1, fw), min(int(py) + r + 1, fh)
    crop = frame_bgr[y0:y1, x0:x1]
    h, w = crop.shape[:2]
    sx, sy = int(px) - x0, int(py) - y0
    if h < 8 or w < 8 or not (0 <= sx < w and 0 <= sy < h):
        return None
    img = cv2.GaussianBlur(np.ascontiguousarray(crop), (5, 5), 0)
    mask = np.zeros((h + 2, w + 2), np.uint8)
    flags = 4 | cv2.FLOODFILL_FIXED_RANGE | cv2.FLOODFILL_MASK_ONLY | (255 << 8)
    cv2.floodFill(img, mask, (sx, sy), (0, 0, 0), (tol,) * 3, (tol,) * 3, flags)
    ys, xs = np.nonzero(mask[1:-1, 1:-1])
    if len(xs) < 25:
        return None
    bx1, bx2, by1, by2 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    bw, bh = bx2 - bx1, by2 - by1
    if max(bw, bh) > max_frac * fh or len(xs) / (bw * bh) < 0.35:
        return None
    cx, cy = x0 + (bx1 + bx2) / 2, y0 + (by1 + by2) / 2
    side_w = max(bw * 1.3, min_frac * fh)
    side_h = max(bh * 1.3, min_frac * fh)
    nx1 = min(max(cx - side_w / 2, 0.0), fw - side_w)
    ny1 = min(max(cy - side_h / 2, 0.0), fh - side_h)
    return nx1, ny1, nx1 + side_w, ny1 + side_h


class PointTracker:
    """Tek nesne izleyici. `update(frame)` → BBox (tam çözünürlük pikseli) ya da None (kayıp)."""

    def __init__(self, frame_bgr: np.ndarray, px: float, py: float, *,
                 box_frac: float = 0.06, refine: bool = True, max_side: int = 640,
                 min_score: float = 0.45, box: tuple[float, ...] | None = None):
        import cv2

        self._cv2 = cv2
        self.fh, self.fw = frame_bgr.shape[:2]
        self.min_score = min_score
        if box is None:  # kullanıcı kutu çizmediyse tıklanan noktadan tahmin et
            box = refine_box(frame_bgr, px, py) if refine else None
        if box is None:
            box = default_box(self.fw, self.fh, px, py, box_frac)
        x1, y1, x2, y2 = box
        s = min(1.0, max_side / max(self.fw, self.fh))
        small = max(min(x2 - x1, y2 - y1), 1.0)
        if small * s < _MIN_WORK_BOX:
            s = min(1.0, _MIN_WORK_BOX / small)
        self.s = s
        gray = self._prep(frame_bgr)
        self.gh, self.gw = gray.shape
        self.cx, self.cy = (x1 + x2) / 2 * s, (y1 + y2) / 2 * s
        self.bw, self.bh = (x2 - x1) * s, (y2 - y1) * s
        self.tmpl = self._crop(gray, self.cx, self.cy, self.bw, self.bh)
        self.tmpl0 = self.tmpl.copy()  # ilk görünüm: yavaş güncellenen şablon kayarsa yedek
        self.prev = gray
        self.pts = self._detect(gray)
        self.lost = 0  # ardışık kayıp kare sayısı
        self._spread = 0.0  # son akış kestiriminin tutarsızlığı (px)
        self._trust = 0  # şablon onayı olmadan kabul edilen ardışık akış kareleri

    # --- dış arayüz ---
    @property
    def bbox(self) -> BBox:
        s, hw, hh = self.s, self.bw / 2, self.bh / 2
        return BBox(max((self.cx - hw) / s, 0.0), max((self.cy - hh) / s, 0.0),
                    min((self.cx + hw) / s, float(self.fw)),
                    min((self.cy + hh) / s, float(self.fh)))

    def update(self, frame_bgr: np.ndarray) -> BBox | None:
        cur = self._prep(frame_bgr)
        res = None  # (cx, cy, bw, bh, skor, noktalar)
        if self.lost == 0:
            lk = self._lk(cur)
            if lk is not None:
                score = self._score(cur, *lk[:4])
                trusted = (score >= _TRUST_SCORE and len(lk[4]) >= _TRUST_PTS
                           and self._spread <= _TRUST_SPREAD and self._trust < _TRUST_MAX)
                if score >= self.min_score:
                    res = (*lk[:4], score, lk[4])
                    self._trust = 0
                elif trusted:  # akış güçlü ama görünüm değişti (dönme/ışık): şablon kaybolmasın
                    res = (*lk[:4], score, lk[4])
                    self._trust += 1
        if res is None:
            found = self._search(cur)
            if found is None:
                self.lost += 1
                self.prev, self.pts = cur, None
                return None
            res = (found[0], found[1], self.bw, self.bh, found[2], None)
        cx, cy, bw, bh, score, pts = res
        self.cx = min(max(cx, 0.0), self.gw - 1.0)
        self.cy = min(max(cy, 0.0), self.gh - 1.0)
        self.bw, self.bh = bw, bh
        self.lost = 0
        if score >= _UPDATE_SCORE:
            self._blend(cur)
        self.pts = pts
        if self.pts is None or len(self.pts) < _REFRESH_PTS:
            fresh = self._detect(cur)
            if fresh is not None:
                self.pts = fresh if self.pts is None else np.concatenate([self.pts, fresh])
        self.prev = cur
        return self.bbox

    # --- iç işlemler ---
    def _prep(self, frame_bgr: np.ndarray) -> np.ndarray:
        cv2 = self._cv2
        g = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
        if self.s < 1.0:
            g = cv2.resize(g, (max(round(self.fw * self.s), 1), max(round(self.fh * self.s), 1)),
                           interpolation=cv2.INTER_AREA)
        return g

    def _rect(self, cx: float, cy: float, bw: float, bh: float) -> tuple[int, int, int, int]:
        """Görüntü içine kıstırılmış (x1, y1, x2, y2) tamsayı dikdörtgen; en az 4x4."""
        x1, y1 = int(round(cx - bw / 2)), int(round(cy - bh / 2))
        x2, y2 = max(x1 + 4, int(round(cx + bw / 2))), max(y1 + 4, int(round(cy + bh / 2)))
        x1, x2 = max(min(x1, self.gw - 4), 0), min(max(x2, 4), self.gw)
        y1, y2 = max(min(y1, self.gh - 4), 0), min(max(y2, 4), self.gh)
        return x1, y1, x2, y2

    def _crop(self, gray: np.ndarray, cx: float, cy: float, bw: float, bh: float) -> np.ndarray:
        x1, y1, x2, y2 = self._rect(cx, cy, bw, bh)
        return gray[y1:y2, x1:x2].copy()

    def _detect(self, gray: np.ndarray) -> np.ndarray | None:
        cv2 = self._cv2
        hw, hh = max(self.bw * _CORE / 2, 4.0), max(self.bh * _CORE / 2, 4.0)
        x1, y1, x2, y2 = self._rect(self.cx, self.cy, 2 * hw, 2 * hh)
        mask = np.zeros_like(gray)
        mask[y1:y2, x1:x2] = 255
        pts = cv2.goodFeaturesToTrack(gray, 40, 0.01, 3, mask=mask)
        return pts if pts is not None and len(pts) >= _MIN_PTS else None

    def _lk(self, cur: np.ndarray):
        """Optik akışla (cx, cy, bw, bh, pts) önerisi; güvenilir değilse None."""
        cv2 = self._cv2
        pts = self.pts if self.pts is not None else self._detect(self.prev)
        if pts is None or len(pts) < _MIN_PTS:
            return None
        lk = {"winSize": (21, 21), "maxLevel": 3,
              "criteria": (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03)}
        p1, st, _ = cv2.calcOpticalFlowPyrLK(self.prev, cur, pts, None, **lk)
        p0r, st2, _ = cv2.calcOpticalFlowPyrLK(cur, self.prev, p1, None, **lk)
        fb = np.linalg.norm((pts - p0r).reshape(-1, 2), axis=1)
        good = (st.ravel() == 1) & (st2.ravel() == 1) & (fb < _FB_ERR)
        if int(good.sum()) < _MIN_PTS:
            return None
        a, b = pts[good].reshape(-1, 2), p1[good].reshape(-1, 2)
        dx, dy = np.median(b - a, axis=0)
        self._spread = float(np.median(np.linalg.norm((b - a) - np.array([dx, dy]), axis=1)))
        bw, bh = self.bw, self.bh
        if len(a) >= 8:  # ölçek: merkeze uzaklıkların oranı
            d0 = np.linalg.norm(a - a.mean(axis=0), axis=1)
            d1 = np.linalg.norm(b - b.mean(axis=0), axis=1)
            ok = d0 > 2.0
            if int(ok.sum()) >= 6:
                k = float(np.median(d1[ok] / d0[ok])) ** 0.5  # yarıya vurup yumuşat
                k = min(max(k, 1 / _MAX_SCALE_STEP), _MAX_SCALE_STEP)
                bw, bh = bw * k, bh * k
        return self.cx + float(dx), self.cy + float(dy), bw, bh, b.reshape(-1, 1, 2)

    def _tmpl_for(self, w: int, h: int) -> np.ndarray:
        t = self.tmpl
        if t.shape[1] == w and t.shape[0] == h:
            return t
        return self._cv2.resize(t, (w, h), interpolation=self._cv2.INTER_AREA)

    def _score(self, cur: np.ndarray, cx: float, cy: float, bw: float, bh: float) -> float:
        cv2 = self._cv2
        patch = self._crop(cur, cx, cy, bw, bh)
        t = self._tmpl_for(patch.shape[1], patch.shape[0])
        if t.std() < 2.0 or patch.std() < 2.0:  # düz bölge: NCC tanımsız → ortalama farkı
            return float(max(0.0, 1.0 - abs(float(t.mean()) - float(patch.mean())) / 64.0))
        v = float(cv2.matchTemplate(patch, t, cv2.TM_CCOEFF_NORMED)[0, 0])
        return v if np.isfinite(v) else 0.0

    def _search(self, cur: np.ndarray) -> tuple[float, float, float] | None:
        """Son konum çevresinde şablon eşleştirme → (cx, cy, skor); eşik altıysa None.
        Önce güncel şablon, olmazsa ilk görünüm denenir (en iyi skor kazanır)."""
        cv2 = self._cv2
        best = None
        for base in (self.tmpl, self.tmpl0):
            w, h = max(int(round(self.bw)), 4), max(int(round(self.bh)), 4)
            t = base if (base.shape[1] == w and base.shape[0] == h) else cv2.resize(
                base, (w, h), interpolation=cv2.INTER_AREA)
            th, tw = t.shape[:2]
            grow = min(2.5 + 0.5 * self.lost, 6.0)  # hızlı hareket: ilk aramada da geniş pencere
            rx, ry = tw * grow, th * grow
            x1, y1 = max(int(self.cx - tw / 2 - rx), 0), max(int(self.cy - th / 2 - ry), 0)
            x2 = min(int(self.cx + tw / 2 + rx), self.gw)
            y2 = min(int(self.cy + th / 2 + ry), self.gh)
            region = cur[y1:y2, x1:x2]
            if region.shape[0] < th or region.shape[1] < tw or t.std() < 2.0:
                continue
            res = np.nan_to_num(cv2.matchTemplate(region, t, cv2.TM_CCOEFF_NORMED), nan=0.0)
            _, score, _, loc = cv2.minMaxLoc(res)
            if best is None or score > best[2]:
                best = (x1 + loc[0] + tw / 2, y1 + loc[1] + th / 2, float(score))
        thr = self.min_score + (0.1 if self.lost else 0.0)
        if best is None or best[2] < thr:
            return None
        return best

    def _blend(self, cur: np.ndarray) -> None:
        """Şablonu yeni görünümle yavaşça karıştır (ışık/poz değişimine uyum; kayma riskine karşı
        yalnız yüksek skorda)."""
        cv2 = self._cv2
        patch = self._crop(cur, self.cx, self.cy, self.bw, self.bh)
        t = self._tmpl_for(patch.shape[1], patch.shape[0])
        self.tmpl = cv2.addWeighted(t, 0.85, patch, 0.15, 0)


class WorldPointTracker:
    """Kamera hareketini telafi eden izleyici: hedef "dünya" koordinatında tutulur.

    Her karede arka plan köşe noktalarından kareden kareye benzerlik dönüşümü (kaydırma+zoom+
    dönme, RANSAC) kestirilir ve birikir. Hedefin dünya konumu bu sayede kamera kaydığında ya da
    hedef kare dışına çıktığında bile hesaplanabilir: hedef görünürken `PointTracker` konumu
    düzeltir; görünmüyorsa (kare dışı / kapanma) son dünya hızıyla (sönümlenerek) ilerletilir ve
    öngörülen konum karenin içine girince şablon aramasıyla yeniden bulunur.
    `update` her zaman bir kutu döner (kare dışında olabilir); `visible` gerçek görünürlüğü söyler.
    """

    def __init__(self, frame_bgr: np.ndarray, px: float, py: float, *, box=None,
                 cam_side: int = 480, vel_decay: float = 0.9, **kw):
        import cv2

        self._cv2 = cv2
        self.inner = PointTracker(frame_bgr, px, py, box=box, **kw)
        self.fw, self.fh = self.inner.fw, self.inner.fh
        self.cs = min(1.0, cam_side / max(self.fw, self.fh))
        self.cprev = self._cgray(frame_bgr)
        self.W = np.eye(3)  # kare -> dünya (tam çözünürlük pikseli)
        self.vel_decay = vel_decay
        b = self.inner.bbox
        self.wx, self.wy = self._to_world(b.cx, b.cy)
        self.bw, self.bh = b.x2 - b.x1, b.y2 - b.y1  # tam çözünürlük kutu boyutu
        self._wsize = (self.bw, self.bh)  # kutunun dünya boyutu
        self.wvx = self.wvy = 0.0  # dünya hızı (px/kare)
        self.visible = True
        self.blind = 0  # ardışık görünmez kare

    # --- dış arayüz ---
    @property
    def bbox(self) -> BBox:
        """Son bilinen/öngörülen kutu (kare dışına taşabilir)."""
        x, y = self._to_frame(self.wx, self.wy)
        return BBox(x - self.bw / 2, y - self.bh / 2, x + self.bw / 2, y + self.bh / 2)

    def on_cut(self) -> None:
        """Çekim kesmesi (G4): kamera geçmişi/hız geçersiz; son kare konumu yeni sahnede başlangıç
        olur ve şablon araması orada denenir (benzerlik düşükse nesne kayıp sayılır)."""
        x, y = self._to_frame(self.wx, self.wy)
        self._cut = True
        self.wx, self.wy = x, y
        self.wvx = self.wvy = 0.0

    def update(self, frame_bgr: np.ndarray) -> BBox:
        cur = self._cgray(frame_bgr)
        if getattr(self, "_cut", False):
            self._cut = False
            self.W = np.eye(3)
            self.cprev = cur  # kamera kestirimi yeni sahnenin ilk karesinden başlar
        self._camera(cur)
        self.cprev = cur
        in_frame = self._inside(*self._to_frame(self.wx, self.wy))
        box = None
        if in_frame or self.inner.lost == 0:
            if self.inner.lost:  # öngörülen konuma göre yeniden bul
                x, y = self._to_frame(self.wx, self.wy)
                self.inner.cx = min(max(x * self.inner.s, 0.0), self.inner.gw - 1.0)
                self.inner.cy = min(max(y * self.inner.s, 0.0), self.inner.gh - 1.0)
            box = self.inner.update(frame_bgr)
        else:
            self.inner.prev = self.inner._prep(frame_bgr)
            self.inner.lost += 1
        if box is not None:
            nx, ny = self._to_world(box.cx, box.cy)
            k = 0.3 if self.blind == 0 else 0.0
            self.wvx += k * ((nx - self.wx) - self.wvx)
            self.wvy += k * ((ny - self.wy) - self.wvy)
            self.wx, self.wy = nx, ny
            sc = self._scale()
            self.bw, self.bh = (box.x2 - box.x1), (box.y2 - box.y1)
            self._wsize = (self.bw / sc, self.bh / sc)
            self.visible, self.blind = True, 0
        else:
            self.wvx *= self.vel_decay
            self.wvy *= self.vel_decay
            self.wx += self.wvx
            self.wy += self.wvy
            self.visible = False
            self.blind += 1
            self.bw, self.bh = self._wsize[0] * self._scale(), self._wsize[1] * self._scale()
        return self.bbox

    # --- iç işlemler ---
    def _cgray(self, frame_bgr: np.ndarray) -> np.ndarray:
        g = self._cv2.cvtColor(frame_bgr, self._cv2.COLOR_BGR2GRAY)
        if self.cs < 1.0:
            g = self._cv2.resize(g, (round(self.fw * self.cs), round(self.fh * self.cs)),
                                 interpolation=self._cv2.INTER_AREA)
        return g

    def _scale(self) -> float:
        """Dünya -> kare ölçeği (kamera zoom'u)."""
        return float(np.sqrt(abs(np.linalg.det(np.linalg.inv(self.W)[:2, :2]))) or 1.0)

    def _inside(self, x: float, y: float, m: float = 0.0) -> bool:
        return -m <= x <= self.fw + m and -m <= y <= self.fh + m

    def _to_world(self, x: float, y: float) -> tuple[float, float]:
        v = self.W @ np.array([x, y, 1.0])
        return float(v[0]), float(v[1])

    def _to_frame(self, wx: float, wy: float) -> tuple[float, float]:
        v = np.linalg.inv(self.W) @ np.array([wx, wy, 1.0])
        return float(v[0]), float(v[1])

    def _camera(self, cur: np.ndarray) -> None:
        """Önceki kareden bu kareye arka plan hareketi; W'yi günceller (kestirilemezse değişmez)."""
        cv2 = self._cv2
        prev = self.cprev
        mask = np.full(prev.shape, 255, np.uint8)
        if self.visible:  # hedefin ve çevresinin köşelerini kullanma
            b = self.bbox
            c = self.cs
            m = max(b.x2 - b.x1, b.y2 - b.y1) * c
            x1, y1 = int(b.x1 * c - m), int(b.y1 * c - m)
            x2, y2 = int(b.x2 * c + m), int(b.y2 * c + m)
            mask[max(y1, 0):max(y2, 0), max(x1, 0):max(x2, 0)] = 0
        A = estimate_step(cv2, prev, cur, mask)
        if A is None:
            return
        S = np.diag([self.cs, self.cs, 1.0])
        step = np.vstack([A, [0, 0, 1]])
        full = np.linalg.inv(S) @ step @ S
        self.W = self.W @ full


CameraComp = CameraMotion  # eklem takibi (B2) için hedefsiz kamera telafisi (gmc.py)
