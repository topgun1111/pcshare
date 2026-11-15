"""Android Part 10b: eklem başına One-Euro + kare dışına çıkan parçanın kamera telafili taşınması.

Offline taramada (`offline.build_preset_views`) eklem satırları `smooth_keypoints` ile süzülür, sonra
kutu hiç üretilemeyen (kişi kayıp/kare dışı) örnekler `propagate_boxes` ile doldurulur. Kotlin eşi:
`core/.../Offscreen.kt`; `scripts/gen_golden.py::_target_views` ikisini karşılaştırır.
"""
from __future__ import annotations

from .smooth import OneEuro

VEL_EMA = 0.3  # canlı `KeypointTracker._set` ile aynı hız yumuşatma katsayısı
VEL_DAMP = 0.9  # kayıpken her adımda hız bununla çarpılır (`_blind`)


def _size(doc: dict) -> tuple[int, int]:
    return doc["video"]["w"] or 1, doc["video"]["h"] or 1


def smooth_keypoints(doc: dict, ids: set[int], cfg) -> list[dict]:
    """Örnek başına {kimlik: 51 sayılık satır}: hedef kimliklerin güvenilir (`kp_min_conf`) eklemleri
    kişi boyuna bölünerek One-Euro'dan geçer (canlı `KeypointTracker._set` gibi); güvenilmeyenler
    olduğu gibi kalır. Süzgeç kesmede ve `kp_hold_s`'den uzun eklem boşluğunda sıfırlanır."""
    w, h = _size(doc)
    state: dict[tuple[int, int], list] = {}
    out: list[dict] = []
    for s in doc["samples"]:
        if s.get("cut"):
            state.clear()
        t = s["t"]
        kps = s.get("kp") or {}
        rows: dict[str, list[float]] = {}
        for d in s["d"]:
            pid, row = d[0], kps.get(str(d[0]))
            if pid not in ids or not row:
                continue
            ph = max((d[4] - d[2]) * h, 1.0)
            new = list(row)
            for j in range(len(row) // 3):
                if row[3 * j + 2] < cfg.kp_min_conf:
                    continue
                st = state.get((pid, j))
                if st is None or t - st[2] > cfg.kp_hold_s:
                    st = state[(pid, j)] = [OneEuro(), OneEuro(), t]
                st[2] = t
                new[3 * j] = st[0].update(row[3 * j] * w / ph, t) * ph / w
                new[3 * j + 1] = st[1].update(row[3 * j + 1] * h / ph, t) * ph / h
            rows[str(pid)] = new
        out.append(rows)
    return out


def _apply(cam, x: float, y: float) -> tuple[float, float]:
    """Önceki → bu örnek benzerlik dönüşümü (kare yüksekliği biriminde); `cam` yoksa kimlik."""
    if not cam:
        return x, y
    return cam[0] * x - cam[1] * y + cam[2], cam[1] * x + cam[0] * y + cam[3]


def _rot(cam, x: float, y: float) -> tuple[float, float]:
    """Dönüşümün doğrusal kısmı (öteleme yok): hız vektörü için."""
    if not cam:
        return x, y
    return cam[0] * x - cam[1] * y, cam[1] * x + cam[0] * y


def propagate_boxes(doc: dict, boxes: dict[int, list[float]], cfg) -> dict[int, list[float]]:
    """Kutusu olmayan örnekleri son kutudan taşıyarak doldur (yalnız ileri yönde, ≤ `kp_hold_s` sn).

    Konum kare yüksekliği biriminde (X = x·w/h, Y = y) tutulur; her adımda önce hız `VEL_DAMP` ile
    sönümlenir, konum hızla ilerler, sonra örneğin `cam` dönüşümüyle yeni kareye taşınır (arka plan
    sabitse parça arka planla birlikte kayar); hız doğrusal kısımla döner. Hız, ardışık görünür
    örneklerdeki dünya yer değiştirmesinin `VEL_EMA` üslü ortalamasıdır; boşluktan sonra sıfırlanır.
    Kesmede dur. İki görünür kutu arası `offline_interp_s` içindeyse (doğrusal dolgu daha doğru) ve
    kesme yoksa dokunma. Taşınan kutu son kutunun boyutundadır, kare dışına çıkabilir
    (`compute_view` kenara kıstırır); kayıp uzarsa `lost_hold_s` + zoom-out devralır."""
    samples, n = doc["samples"], len(doc["samples"])
    w, h = _size(doc)
    asp = w / h
    ts = [s["t"] for s in samples]
    cuts = {i for i, s in enumerate(samples) if s.get("cut")}
    nxt: list[int | None] = [None] * n  # i'den SONRAKİ ilk görünür örnek
    nx: int | None = None
    for i in range(n - 1, -1, -1):
        nxt[i] = nx
        if i in boxes:
            nx = i
    out = {k: list(v) for k, v in boxes.items()}
    pos: tuple[float, float] | None = None
    vel = (0.0, 0.0)
    size = (0.0, 0.0)
    last = -1  # son görünür örnek
    for i in range(n):
        cam = samples[i].get("cam")
        if i in cuts:
            pos, vel = None, (0.0, 0.0)
        if i in boxes:
            b = boxes[i]
            cur = (b[0] * asp, b[1])
            if pos is not None and last == i - 1:  # ardışık: dünya yer değiştirmesi
                m = _apply(cam, *pos)
                d = (cur[0] - m[0], cur[1] - m[1])
                r = _rot(cam, *vel)
                vel = (r[0] + VEL_EMA * (d[0] - r[0]), r[1] + VEL_EMA * (d[1] - r[1]))
            else:
                vel = (0.0, 0.0)
            pos, size, last = cur, (b[2], b[3]), i
            continue
        if pos is None or last < 0 or ts[i] - ts[last] > cfg.kp_hold_s:
            pos = None
            continue
        b2 = nxt[i]
        if b2 is not None and ts[b2] - ts[last] <= cfg.offline_interp_s and not any(
                last < c <= b2 for c in cuts):
            continue  # kısa boşluk: `_views_from_boxes` doğrusal doldurur
        vel = (vel[0] * VEL_DAMP, vel[1] * VEL_DAMP)
        pos = _apply(cam, pos[0] + vel[0], pos[1] + vel[1])
        vel = _rot(cam, *vel)
        out[i] = [pos[0] / asp, pos[1], size[0], size[1]]
    return out
