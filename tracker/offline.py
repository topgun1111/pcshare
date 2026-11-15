"""Offline mod (Parça 9): videoyu baştan sona işle → `track.json`.

Aşamalar (hepsi IO'suz, testli; yalnız `VideoSource` cv2 kullanır):
  1. `run_detection`: kareler → Pipeline → örnek başına normalize izler.
  2. `pick_target_id`: hedef kimliği (ID ya da zaman+nokta ile).
  3. `build_views`: hedefin izinden zoom/pan anahtar kareleri. Canlı moddan farkı **nedensel
     olmamasıdır**: sıfır-faz yumuşatma (gecikme yok), kısa kayıplar ileri-geri doğrusal
     doldurulur, uzun kayıplarda bekle → zoom-out (Parça 8) ve hedef dönmeden önce zoom-in.

Şema (sürüm 1), tüm koordinatlar kare boyutuna göre normalize (0..1), zaman sn (video zamanı):
  {"version": 1, "video": {"path", "w", "h", "fps", "duration"}, "sample_fps": 10.0,
   "samples": [{"t": 0.1, "d": [[id, x1, y1, x2, y2, skor], ...]}, ...],
   "target": {"id": 7} | null,
   "views": [[t, zoom, pan_x, pan_y], ...]}   # target yoksa []
Sürüm 2 (`offline --pose`, B1): her örnekte isteğe bağlı `"kp": {"<id>": [x, y, güven] × 17}`
(düz liste, 51 sayı; x/y normalize, güven 0..1; COCO sırası = `pose.KEYPOINT_NAMES`) ve hedefte
`"part": <eklem indeksi>`. Android 10b: örnekte isteğe bağlı `"cam": [a, b, tx/h, ty/h]` (önceki örnekten bu
örneğe arka plan benzerlik dönüşümü: ölçek·cos, ölçek·sin, öteleme kare yüksekliği biriminde; yalnız
kamera kestirimi tamamsa ve kesme değilse), hedefte `"preset": <ad>`. Sürüm 1 dosyaları aynen okunur; `kp` yoksa parça hedefi seçilemez.
Hedef seçilince ayrıca küçük bir `<video>.views.json` yazılır (`save_views`): yalnız
{"version", "video": {"path"}, "target", "views"}; Lua (Parça 10) bunu okur, büyük `samples`'ı
ayrıştırmaz. `views` doğrudan mpv `video-zoom`/`video-pan-x/y` değerleridir (Parça 10 Lua bunları
`time-pos`'a göre interpole eder); `samples` hedefi sonradan değiştirmek içindir.
"""
from __future__ import annotations

import json
from pathlib import Path

from .bytetrack import Track
from .config import Config
from .geometry import BBox, View, compute_view
from .live import pick_track
from .pose import N_KPT, _iou
from .smooth import ease_view, smooth_zero_phase

SCHEMA_VERSION = 1  # eklemsiz dosyalar + `<video>.views.json` (Lua yalnız 1'i kabul eder)
POSE_SCHEMA_VERSION = 2  # eklem verisi (`kp`) içeren track.json
SUPPORTED_VERSIONS = (SCHEMA_VERSION, POSE_SCHEMA_VERSION)


class VideoSource:
    """cv2.VideoCapture ile `fps` hızında örneklenmiş (t, BGR kare); sabit kare hızı varsayılır."""

    def __init__(self, path: str, fps: float = 10.0, start: float = 0.0):
        import cv2

        self.path, self.fps, self.start = path, fps, start
        self._cv2 = cv2
        self._cap = cv2.VideoCapture(path)
        if not self._cap.isOpened():
            raise FileNotFoundError(f"video açılamadı: {path}")
        self.w = int(self._cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        self.h = int(self._cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        self.src_fps = self._cap.get(cv2.CAP_PROP_FPS) or 25.0
        self.n_frames = int(self._cap.get(cv2.CAP_PROP_FRAME_COUNT))
        self.duration = self.n_frames / self.src_fps if self.n_frames > 0 else 0.0

    def __iter__(self):
        idx, next_t, step = 0, 0.0, 1.0 / max(self.fps, 0.1)
        if self.start > 0:  # sürdür: bu süreden (dahil değil) sonrasından başla
            idx = int(self.start * self.src_fps)
            self._cap.set(self._cv2.CAP_PROP_POS_FRAMES, idx)
            next_t = self.start + step
        try:
            while self._cap.grab():  # grab ucuzdur; yalnızca örneklenen kare çözülür (retrieve)
                t = idx / self.src_fps
                idx += 1
                if t + 1e-9 < next_t:
                    continue
                ok, frame = self._cap.retrieve()
                if not ok:
                    break
                next_t += step
                yield t, frame
        finally:
            self._cap.release()


def associate_poses(tracks, poses, min_iou: float = 0.3) -> dict:
    """İz ↔ poz eşleme (kutu IoU'su, açgözlü, her poz bir kez): {iz kimliği: Pose}."""
    pairs = sorted(((_iou(tr.bbox, p.bbox), i, j) for i, tr in enumerate(tracks)
                    for j, p in enumerate(poses)), reverse=True)
    out, used = {}, set()
    for v, i, j in pairs:
        if v < min_iou:
            break
        if tracks[i].id not in out and j not in used:
            out[tracks[i].id] = poses[j]
            used.add(j)
    return out


def _kp_row(p, w: int, h: int) -> list[float]:
    return [v for x, y, c in p.kpts
            for v in (round(float(x) / w, 4), round(float(y) / h, 4), round(float(c), 2))]


def run_detection(frames, pipeline, progress=None, total: int | None = None, bank=None,
                  team=None, pose=None, samples: list | None = None, on_sample=None,
                  should_stop=None):
    """`frames`: (t, kare) üreteci → (örnekler, (w, h)). Kare boyutu ilk kareden alınır.
    `bank` (reid.TrackletBank) verilirse izlerin ilk/son embedding'leri toplanır;
    `team` (team.TeamBank) verilirse izlerin takım rengi toplanır;
    `pose` (pose.PoseEstimator) verilirse izlere eşlenen eklemler örneğe `kp` olarak yazılır.
    `samples`: sürdürmede önceden hesaplanmış örnekler (üzerine eklenir); `on_sample(n, samples)`
    her örnekten sonra; `should_stop()` True dönerse tarama (örnekler korunarak) durur."""
    samples, size = (samples if samples is not None else []), None
    for n, (t, frame) in enumerate(frames, 1):
        h, w = frame.shape[:2]
        size = size or (w, h)
        d = []
        tracks = pipeline.step(frame)
        if bank is not None:
            bank.update(frame, tracks, n - 1)
        if team is not None:
            team.update(frame, tracks)
        for tr in tracks:
            b = tr.bbox
            d.append([tr.id, round(b.x1 / w, 4), round(b.y1 / h, 4),
                      round(b.x2 / w, 4), round(b.y2 / h, 4), round(tr.score, 3)])
        smp = {"t": round(t, 3), "d": d}
        if getattr(pipeline, "cut", False) is True:  # G4: bu örnek bir çekim kesmesiyle başlar
            smp["cut"] = 1
        cam = getattr(pipeline, "cam", None)
        if cam and not smp.get("cut"):  # 10b: kamera hareketi (kp_world: kare dışına çıkan parça)
            smp["cam"] = list(cam)
        if pose is not None:
            kp = {str(i): _kp_row(p, w, h)
                  for i, p in associate_poses(tracks, pose.estimate(frame)).items()}
            if kp:
                smp["kp"] = kp
        samples.append(smp)
        if on_sample:
            on_sample(n, samples)
        if progress:
            progress(n, total)
        if should_stop and should_stop():
            break
    return samples, size or (0, 0)


def make_doc(path: str, size, fps_src: float, duration: float, sample_fps: float,
             samples: list, emb: dict | None = None, team: dict | None = None,
             pose: bool = False, sig: str | None = None) -> dict:
    doc = {"version": POSE_SCHEMA_VERSION if pose else SCHEMA_VERSION,
            "video": {"path": path, "w": size[0], "h": size[1], "fps": round(fps_src, 3),
                      "duration": round(duration, 3)},
            "sample_fps": sample_fps, "samples": samples, "target": None, "views": []}
    if sig:  # A3: tarama önbelleği imzası (video yol+boyut+mtime, fps, modeller)
        from .cache import file_sig

        doc["sig"], doc["vsig"] = sig, file_sig(path)
    cuts = [s["t"] for s in samples if s.get("cut")]
    if cuts:
        doc["cuts"] = cuts  # G4: çekim kesmesi anları (sn); örneklerde `"cut": 1` de var
    if emb:
        doc["emb"] = emb  # {id: {"first": [int...], "last": [int...]}} (×100 tamsayı)
    if team:
        doc["team"] = team  # {"centers": [[int...]]×2, "labels": {id: 0|1}} (belirsiz iz yok)
    return doc


def save_doc(doc: dict, path: str | Path) -> None:
    Path(path).write_text(json.dumps(doc, separators=(",", ":"), ensure_ascii=False),
                          encoding="utf-8")


def views_path(track_path: str | Path) -> Path:
    """`x.mp4.track.json` → `x.mp4.views.json` (Lua `<video>.views.json` arar); diğer adlarda
    `<ad>.views.json`."""
    p = Path(track_path)
    if p.name.endswith(".track.json"):
        return p.with_name(p.name[: -len(".track.json")] + ".views.json")
    return p.with_name(p.stem + ".views.json")


def save_views(doc: dict, path: str | Path) -> None:
    """Oynatma için küçük dosya: `samples` olmadan yalnız hedef + görünüm anahtar kareleri."""
    small = {"version": SCHEMA_VERSION, "video": {"path": doc["video"].get("path", "")},
             "target": doc.get("target"), "views": doc.get("views", [])}
    if doc.get("vsig"):
        small["vsig"] = doc["vsig"]
    Path(path).write_text(json.dumps(small, separators=(",", ":"), ensure_ascii=False),
                          encoding="utf-8")


def view_at(views: list[list[float]], t: float) -> tuple[float, float, float]:
    """Anahtar kareler arası doğrusal (uçlarda sabit) — `track.lua::view_at` ile aynı formül."""
    if not views:
        return 0.0, 0.0, 0.0
    if t <= views[0][0]:
        return tuple(views[0][1:4])  # type: ignore[return-value]
    if t >= views[-1][0]:
        return tuple(views[-1][1:4])  # type: ignore[return-value]
    lo, hi = 0, len(views) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if views[mid][0] <= t:
            lo = mid
        else:
            hi = mid
    a, b = views[lo], views[hi]
    f = (t - a[0]) / (b[0] - a[0]) if b[0] > a[0] else 0.0
    return tuple(a[k] + (b[k] - a[k]) * f for k in (1, 2, 3))  # type: ignore[return-value]


def load_doc(path: str | Path) -> dict:
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    ver = doc.get("version") if isinstance(doc, dict) else None
    if ver not in SUPPORTED_VERSIONS:
        raise ValueError(f"desteklenmeyen track.json sürümü: {ver}")
    for k in ("video", "samples"):
        if k not in doc:
            raise ValueError(f"track.json'da '{k}' yok")
    doc.setdefault("target", None)
    doc.setdefault("views", [])
    return doc


def list_tracks(doc: dict) -> list[dict]:
    """Kimlik başına {id, first, last, n}; en çok görünenden aza."""
    info: dict[int, dict] = {}
    for s in doc["samples"]:
        for d in s["d"]:
            e = info.setdefault(d[0], {"id": d[0], "first": s["t"], "last": s["t"], "n": 0})
            e["last"], e["n"] = s["t"], e["n"] + 1
    return sorted(info.values(), key=lambda e: -e["n"])


def pick_target_id(doc: dict, t: float, nx: float, ny: float, tol: float = 0.03) -> int | None:
    """`t` anına en yakın örnekte (nx, ny) noktasındaki kişinin kimliği; yoksa None."""
    samples = doc["samples"]
    if not samples:
        return None
    s = min(samples, key=lambda x: abs(x["t"] - t))
    w, h = doc["video"]["w"] or 1, doc["video"]["h"] or 1
    tracks = [Track(d[0], BBox(d[1] * w, d[2] * h, d[3] * w, d[4] * h), d[5], 0, 0)
              for d in s["d"]]
    tr = pick_track(tracks, nx * w, ny * h, tol * h)
    return tr.id if tr else None


def _runs(idx: list[int]) -> list[list[int]]:
    """Ardışık tam sayı dizilerine böl."""
    out: list[list[int]] = []
    for i in idx:
        if out and out[-1][-1] == i - 1:
            out[-1].append(i)
        else:
            out.append([i])
    return out


def build_views(doc: dict, target_id: int | set[int], cfg: Config) -> list[list[float]]:
    """Hedefin izinden [t, zoom, pan_x, pan_y] anahtar kareleri (sabit duranlar seyreltilir)."""
    samples = doc["samples"]
    n = len(samples)
    ts = [s["t"] for s in samples]
    if n == 0:
        return []
    ids = {target_id} if isinstance(target_id, int) else set(target_id)
    boxes: dict[int, list[float]] = {}  # örnek → [cx, cy, w, h] (normalize)
    best: dict[int, float] = {}
    for i, s in enumerate(samples):
        for d in s["d"]:
            if d[0] in ids and d[5] >= best.get(i, -1.0):  # birden çok kimlik: en yüksek skor
                b = BBox(d[1], d[2], d[3], d[4])
                boxes[i], best[i] = [b.cx, b.cy, b.w, b.h], d[5]
    return _views_from_boxes(ts, boxes, cfg, cut_indices(doc))


def part_boxes(doc: dict, ids: set[int], part: int, cfg: Config) -> dict[int, list[float]]:
    """Seçili eklemin örnek başına [cx, cy, w, h] kutusu (normalize; kutu = kişi boyu × `kp_box`,
    piksel olarak kare). Güveni `kp_min_conf` altındaki eklem yok sayılır (kısa boşluklar
    `_views_from_boxes` içinde doldurulur). Birden çok kimlikte en güvenilir eklem."""
    w, h = doc["video"]["w"] or 1, doc["video"]["h"] or 1
    boxes: dict[int, list[float]] = {}
    best: dict[int, float] = {}
    for i, s in enumerate(doc["samples"]):
        kps = s.get("kp") or {}
        for d in s["d"]:
            row = kps.get(str(d[0]))
            if d[0] not in ids or not row:
                continue
            x, y, c = row[3 * part: 3 * part + 3]
            if c >= cfg.kp_min_conf and c >= best.get(i, -1.0):
                side = max(cfg.kp_box * (d[4] - d[2]) * h, 8.0)  # piksel
                boxes[i], best[i] = [x, y, side / w, side / h], c
    return boxes


def build_part_views(doc: dict, ids: int | set[int], part: int,
                     cfg: Config) -> list[list[float]]:
    """Kimlik(ler)in `part` eklemi için görünüm anahtar kareleri (sıfır-faz, boşluk dolduran)."""
    if not 0 <= part < N_KPT:
        raise ValueError(f"eklem indeksi 0..{N_KPT - 1} olmalı: {part}")
    ts = [s["t"] for s in doc["samples"]]
    ids = {ids} if isinstance(ids, int) else set(ids)
    return _views_from_boxes(ts, part_boxes(doc, ids, part, cfg), cfg, cut_indices(doc))


def build_preset_views(doc: dict, ids: int | set[int], preset: str,
                       cfg: Config) -> list[list[float]]:
    """Kimlik(ler)in hazır ayar (`parts.PRESETS`) grubunu izleyen görünüm anahtar kareleri."""
    from .offscreen import propagate_boxes, smooth_keypoints
    from .parts import preset_boxes

    ts = [s["t"] for s in doc["samples"]]
    ids = {ids} if isinstance(ids, int) else set(ids)
    part = preset != "person"
    kps = smooth_keypoints(doc, ids, cfg) if part and cfg.kp_filter else None  # 10b: eklem başına One-Euro
    boxes = preset_boxes(doc, ids, preset, cfg, kps)
    if part and cfg.kp_world and cfg.kp_hold_s > 0:  # 10b: kare dışına çıkan/kaybolan parçayı taşı
        boxes = propagate_boxes(doc, boxes, cfg)
    return _views_from_boxes(ts, boxes, cfg, cut_indices(doc))


def cut_indices(doc: dict) -> set[int]:
    """G4: çekim kesmesiyle başlayan örneklerin indeksleri (kesme i-1 ile i arasındadır)."""
    return {i for i, s in enumerate(doc["samples"]) if s.get("cut")}


def _views_from_boxes(ts: list[float], boxes: dict[int, list[float]], cfg: Config,
                      cuts: set[int] | None = None) -> list[list[float]]:
    """`cuts`: çekim kesmesi indeksleri; doldurma, yumuşatma ve zoom-out/in kesmeyi AŞMAZ."""
    n = len(ts)
    cuts = cuts or set()

    def cut_between(lo: int, hi: int) -> bool:  # lo..hi (dahil) aralığında kesme var mı
        return any(lo <= c <= hi for c in cuts)

    vis = sorted(boxes)
    # 1) kısa kayıpları doğrusal doldur (ileri-geri bilgi: non-causal)
    for a, b in zip(vis, vis[1:], strict=False):
        if b - a > 1 and ts[b] - ts[a] <= cfg.offline_interp_s and not cut_between(a + 1, b):
            for i in range(a + 1, b):
                f = (ts[i] - ts[a]) / max(ts[b] - ts[a], 1e-9)
                boxes[i] = [boxes[a][k] + (boxes[b][k] - boxes[a][k]) * f for k in range(4)]
    present = sorted(boxes)
    views: dict[int, View] = {}
    # 2) görünür parçalarda sıfır-faz yumuşatma → compute_view
    runs: list[list[int]] = []
    for run in _runs(present):  # kesmede böl: farklı sahneler birlikte yumuşatılmaz
        cur: list[int] = []
        for i in run:
            if cur and i in cuts:
                runs.append(cur)
                cur = []
            cur.append(i)
        runs.append(cur)
    for run in runs:
        sm = []
        for k in range(4):
            a = cfg.offline_alpha * (1.0 if k < 2 else 0.4)
            sm.append(smooth_zero_phase([boxes[i][k] for i in run], a))
        for j, i in enumerate(run):
            cx, cy, w, h = sm[0][j], sm[1][j], sm[2][j], sm[3][j]
            bb = BBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            views[i] = compute_view(bb, 1.0, 1.0, cfg.target_fill, cfg.min_zoom, cfg.max_zoom)
    # 3) uzun kayıplar: bekle → zoom-out, hedef dönmeden önce zoom-in
    hold, dur = max(cfg.lost_hold_s, 0.0), cfg.lost_zoomout_s
    i = 0
    while i < n:
        if i in views:
            i += 1
            continue
        j = i
        while j < n and j not in views:
            j += 1
        prev, nxt = (views[i - 1] if i > 0 else None), (views[j] if j < n else None)
        t0, t1 = ts[i], (ts[j] if j < n else None)
        for k in range(i, j):
            t = ts[k]
            # kesmeden önceki sahnenin görünümü kesmeden sonra, sonraki sahnenin görünümü
            # kesmeden önce kullanılmaz (aynı sahne içinde eskisi gibi)
            pv = prev if not cut_between(i, k) else None
            nv = nxt if not cut_between(k + 1, j) else None
            if pv is None:
                v = View()
            else:
                el = t - t0 - hold
                v = ease_view(pv, View(), (el / dur) if dur > 0 else (1.0 if el >= 0 else 0.0))
            if nv is not None:
                q = (t - (t1 - dur)) / dur if dur > 0 else 0.0
                v = ease_view(v, nv, q)
            views[k] = v
        i = j
    # 4) çıktı: sabit duran yinelenen anahtar kareleri at
    out: list[list[float]] = []
    keys = [[ts[k], round(views[k].zoom, 4), round(views[k].pan_x, 4), round(views[k].pan_y, 4)]
            for k in range(n)]
    for k, kf in enumerate(keys):
        if 0 < k < n - 1 and keys[k - 1][1:] == kf[1:] == keys[k + 1][1:]:
            continue
        out.append(kf)
    return out


def apply_target(doc: dict, target_id: int, cfg: Config, link: bool = True,
                 part: int | None = None, preset: str | None = None) -> dict:
    """Hedefi ayarla. `doc['emb']` varsa ve `link` ise Re-ID ile ID değişimlerini zincirler.
    `part` (eklem indeksi) verilirse görünüm kişiyi değil o eklemi izler (`kp` gerekir)."""
    if preset is not None and part is not None:
        raise ValueError("`part` ve `preset` birlikte verilemez")
    if (part is not None or preset not in (None, "person")) and not any(
            s.get("kp") for s in doc["samples"]):
        raise ValueError("track.json'da eklem verisi yok; `offline --pose` ile yeniden tara")
    linked: list[int] = []
    if link and doc.get("emb"):
        from .reid import link_ids

        linked = link_ids(doc, target_id, cfg)
    doc["target"] = {"id": target_id, **({"part": part} if part is not None else {}),
                     **({"preset": preset} if preset is not None else {}),
                     **({"linked": linked} if linked else {})}
    ids = {target_id, *linked}
    if preset is not None:
        doc["views"] = build_preset_views(doc, ids, preset, cfg)
    else:
        doc["views"] = (build_views(doc, ids, cfg) if part is None
                        else build_part_views(doc, ids, part, cfg))
    return doc
