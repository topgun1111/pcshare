"""Kıyaslama (Parça 23): gerçek klipte izleme/görünüm kalitesini sayılarla ölç.

`analyze(doc, cfg)` IO'suz ve testli: `track.json` belgesinden ölçütler üretir —
  - izler: kimlik sayısı, kısa iz sayısı, olası kimlik değişimi (bir iz biterken yakında yenisi
    doğar = parçalanma/ID switch vekili; gerçek GT olmadan en iyi tahmin),
  - hedef: görünme/kayıp yüzdesi (hedef verilmezse en uzun iz),
  - görünüm: offline (sıfır-faz) ve canlı-benzetimi (nedensel `ViewSmoother`) titreşimi ve
    canlı ile offline görünüm arasındaki gecikme/sapma → A2 (otomatik geçiş) bu sayıları kullanır.
`run_bench` (cv2/ONNX gerekir) videoyu işler, süre/gecikme ölçer; `.json` verilirse tespit yok.
"""
from __future__ import annotations

import json
import math
import statistics
import time
from pathlib import Path

from .config import Config
from .geometry import BBox, compute_view
from .offline import build_views, list_tracks, view_at
from .smooth import ViewSmoother

SHORT_TRACK_S = 1.0  # bundan kısa izler "kırıntı" sayılır
SWITCH_GAP_S = 1.0  # iz bittikten sonra bu süre içinde doğan yakın iz = olası kimlik değişimi
SWITCH_DIST = 0.5  # merkez uzaklığı üst sınırı (bitenin kutu yüksekliği oranı)


def _pct(v: list[float], q: float) -> float:
    if not v:
        return 0.0
    s = sorted(v)
    return s[min(len(s) - 1, int(q * len(s)))]


def _spans(doc: dict) -> dict[int, dict]:
    """Kimlik başına ilk/son zaman, son/ilk kutu (normalize) ve örnek sayısı."""
    sp: dict[int, dict] = {}
    for s in doc["samples"]:
        for d in s["d"]:
            e = sp.setdefault(d[0], {"first": s["t"], "n": 0, "fbox": d[1:5]})
            e["last"], e["lbox"], e["n"] = s["t"], d[1:5], e["n"] + 1
    return sp


def likely_switches(doc: dict) -> int:
    """Biten bir izin son kutusuna yakın, kısa süre sonra doğan iz sayısı (tekrar sayılmaz)."""
    sp = _spans(doc)
    w, h = doc["video"].get("w") or 1, doc["video"].get("h") or 1
    births: set[int] = set()
    for a in sp.values():
        ax, ay = (a["lbox"][0] + a["lbox"][2]) / 2 * w, (a["lbox"][1] + a["lbox"][3]) / 2 * h
        ah = max((a["lbox"][3] - a["lbox"][1]) * h, 1.0)
        for bid, b in sp.items():
            if bid in births or not (0 < b["first"] - a["last"] <= SWITCH_GAP_S):
                continue
            bx, by = (b["fbox"][0] + b["fbox"][2]) / 2 * w, (b["fbox"][1] + b["fbox"][3]) / 2 * h
            if math.hypot(bx - ax, by - ay) <= SWITCH_DIST * ah:
                births.add(bid)
    return len(births)


def _rough(v: list[float], dt: float) -> float:
    """RMS ikinci türev (birim/sn²): titreşim/sarsıntı ölçüsü; düzgün hareket ≈ 0."""
    if len(v) < 3 or dt <= 0:
        return 0.0
    acc = [(v[i + 1] - 2 * v[i] + v[i - 1]) / dt**2 for i in range(1, len(v) - 1)]
    return math.sqrt(sum(a * a for a in acc) / len(acc))


def _view_stats(doc: dict, tid: int, cfg: Config) -> dict:
    samples, w, h = doc["samples"], doc["video"]["w"] or 1, doc["video"]["h"] or 1
    dt = 1.0 / (doc.get("sample_fps") or 10.0)
    ts = [s["t"] for s in samples]
    off = build_views(doc, tid, cfg)
    off_v = [view_at(off, t) for t in ts]
    sm, live_v, last = ViewSmoother(cfg.alpha_pos, cfg.alpha_zoom, cfg.dead_zone_pos,
                                    cfg.dead_zone_zoom), [], None
    for s in samples:  # canlı benzetimi: nedensel, kayıpta son görünümde bekler
        row = next((d for d in s["d"] if d[0] == tid), None)
        if row is not None:
            b = BBox(row[1] * w, row[2] * h, row[3] * w, row[4] * h)
            last = sm.update(compute_view(b, w, h, cfg.target_fill, cfg.min_zoom, cfg.max_zoom))
        live_v.append((last.zoom, last.pan_x, last.pan_y) if last else off_v[len(live_v)])
    gap = [math.hypot(a[1] - b[1], a[2] - b[2]) for a, b in zip(live_v, off_v, strict=True)]
    jo = _rough([v[1] for v in off_v], dt) + _rough([v[2] for v in off_v], dt)
    jl = _rough([v[1] for v in live_v], dt) + _rough([v[2] for v in live_v], dt)
    return {"offline_jitter": round(jo, 3), "live_jitter": round(jl, 3),
            "live_vs_offline_pan_dev": round(statistics.fmean(gap), 4) if gap else 0.0,
            "live_vs_offline_pan_dev_p95": round(_pct(gap, 0.95), 4)}


def analyze(doc: dict, cfg: Config, target_id: int | None = None) -> dict:
    samples = doc["samples"]
    dt = 1.0 / (doc.get("sample_fps") or 10.0)
    tracks = list_tracks(doc)
    lens = [t["n"] * dt for t in tracks]
    rep: dict = {
        "samples": len(samples), "duration_s": doc["video"].get("duration", 0.0),
        "ids": len(tracks), "short_tracks": sum(1 for x in lens if x < SHORT_TRACK_S),
        "median_track_s": round(statistics.median(lens), 2) if lens else 0.0,
        "likely_id_switches": likely_switches(doc),
        "persons_per_frame": round(statistics.fmean(len(s["d"]) for s in samples), 2)
        if samples else 0.0,
        "target": None,
    }
    if not tracks:
        return rep
    tid = target_id if target_id is not None else tracks[0]["id"]
    info = next((t for t in tracks if t["id"] == tid), None)
    if info is None:
        raise ValueError(f"hedef ID {tid} izlerde yok")
    span = [s for s in samples if info["first"] <= s["t"] <= info["last"]]
    seen = sum(1 for s in span if any(d[0] == tid for d in s["d"]))
    rep["target"] = {"id": tid, "first_s": info["first"], "last_s": info["last"],
                     "seen_pct": round(100 * seen / max(len(span), 1), 1),
                     "lost_pct": round(100 * (1 - seen / max(len(span), 1)), 1),
                     "coverage_pct": round(100 * seen / max(len(samples), 1), 1),
                     **_view_stats(doc, tid, cfg)}
    return rep


class TimedPipeline:
    """Pipeline sarmalayıcı: her `step` süresini (ms) biriktirir."""

    def __init__(self, inner):
        self.inner, self.ms = inner, []

    def step(self, frame):
        t0 = time.perf_counter()
        out = self.inner.step(frame)
        self.ms.append((time.perf_counter() - t0) * 1000)
        return out


def timing(ms: list[float], wall_s: float) -> dict:
    if not ms:
        return {}
    return {"frames": len(ms), "mean_ms": round(statistics.fmean(ms), 1),
            "p50_ms": round(_pct(ms, 0.5), 1), "p95_ms": round(_pct(ms, 0.95), 1),
            "max_ms": round(max(ms), 1), "fps": round(len(ms) / wall_s, 1) if wall_s else 0.0}


def format_report(rep: dict) -> str:
    lines = []
    for k, v in rep.items():
        if isinstance(v, dict):
            lines.append(f"{k}:")
            lines += [f"  {a}: {b}" for a, b in v.items()]
        elif v is not None:
            lines.append(f"{k}: {v}")
    return "\n".join(lines)


def run_bench(args, cfg: Config) -> dict:
    """`args.video` .json ise o belge çözümlenir; aksi halde video işlenir (+ süre ölçümü)."""
    from .offline import load_doc

    if str(args.video).lower().endswith(".json"):
        return analyze(load_doc(args.video), cfg, args.target_id)
    from .bytetrack import ByteTracker
    from .detector import Detector
    from .offline import VideoSource, make_doc, run_detection
    from .pipeline import Pipeline

    fps = args.fps or cfg.offline_fps
    src = VideoSource(args.video, fps)
    det = Detector(args.model or cfg.model_path, conf_thres=cfg.live_det_conf,
                   iou_thres=cfg.det_iou, input_size=cfg.det_size,
                   device=args.device or cfg.device, model_type=cfg.model_type,
                   yolox_decoded=cfg.yolox_decoded)
    pipe = TimedPipeline(Pipeline(det, ByteTracker(low_thr=cfg.live_det_conf)))
    limit = args.max_seconds
    frames = (f for f in src if not limit or f[0] <= limit)
    first = next(_probe(args.video), None)
    for _ in range(3 if first is not None else 0):  # ısınma: DirectML ilk derlemesi ölçümü bozmasın
        det.detect(first)
    t0 = time.perf_counter()
    samples, size = run_detection(frames, pipe)
    wall = time.perf_counter() - t0
    doc = make_doc(args.video, size, src.src_fps, min(src.duration, limit or src.duration),
                   fps, samples)
    rep = {"video": args.video, "provider": det.providers[0], "input": f"{size[0]}x{size[1]}",
           "sample_fps": fps, "speed": timing(pipe.ms, wall), **analyze(doc, cfg, args.target_id)}
    return rep


def _probe(path: str):
    import cv2

    cap = cv2.VideoCapture(path)
    ok, fr = cap.read()
    cap.release()
    if ok:
        yield fr


def save_report(rep: dict, path: str | Path) -> None:
    Path(path).write_text(json.dumps(rep, indent=1, ensure_ascii=False), encoding="utf-8")
