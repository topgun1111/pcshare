"""A1: hedefle aç + arka plan taraması: seçim (nokta/dikdörtgen) → {kimlik, eklem}, ilerleme
dosyası (`<çıktı>.scan.json`), kesinti noktası (`<çıktı>.partial.json`, sürdürülebilir), iptal
(`<çıktı>.scan.cancel` dosyası). Hedef, tarama seçilen ana ulaşınca çözülür."""
from __future__ import annotations

import json
import math
import os
import time
from dataclasses import dataclass
from pathlib import Path

from .cache import THROTTLE_SUFFIX
from .config import Config

LOOKAHEAD_S = 0.3  # seçimden bu kadar sonrasına varınca çöz (iz oturmuş olsun)
GRACE_S = 2.0  # bu süre içinde çözülemezse "hedefte kişi yok"
CHECKPOINT_EVERY = 100  # örnek


@dataclass(frozen=True)
class Selection:
    kind: str  # "point" | "rect"
    t: float
    x1: float  # 0..1 normalize; nokta için x1==x2, y1==y2
    y1: float
    x2: float
    y2: float

    @classmethod
    def point(cls, t, nx, ny) -> Selection:
        return cls("point", float(t), nx, ny, nx, ny)

    @classmethod
    def rect(cls, t, ax, ay, bx, by) -> Selection:
        return cls("rect", float(t), min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))


def selection_from_args(args) -> Selection | None:
    if getattr(args, "select_point", None):
        return Selection.point(*args.select_point)
    if getattr(args, "select_rect", None):
        return Selection.rect(*args.select_rect)
    return None


def _persons(sample: dict, w: int, h: int):
    """[(id, x1, y1, x2, y2)] piksel."""
    return [(d[0], d[1] * w, d[2] * h, d[3] * w, d[4] * h) for d in sample["d"]]


def resolve_selection(doc: dict, sel: Selection, cfg: Config) -> tuple[int, int | None] | None:
    """Seçimi (kişi kimliği, eklem|None) olarak çöz; bulunamazsa None.

    Nokta: eklem verisi varsa `kp_tol`×kişi boyu içindeki en yakın eklem → parça; değilse noktayı
    içeren kişi (`pick_tol` toleransıyla). Dikdörtgen: kendi alanının ≥%50'si kişi kutusunda
    olan kişi (en çok örtüşen); dikdörtgen kişi boyunun ≤%30'u ve içinde güvenilir eklem
    varsa parça."""
    from .offline import pick_target_id

    samples = doc["samples"]
    if not samples:
        return None
    s = min(samples, key=lambda x: abs(x["t"] - sel.t))
    w, h = doc["video"]["w"] or 1, doc["video"]["h"] or 1
    persons = {p[0]: p for p in _persons(s, w, h)}
    kp = s.get("kp", {})
    px1, py1, px2, py2 = sel.x1 * w, sel.y1 * h, sel.x2 * w, sel.y2 * h
    cx, cy = (px1 + px2) / 2, (py1 + py2) / 2

    def joint(pid: int, inside=None):
        row = kp.get(str(pid))
        if not row or pid not in persons:
            return None
        ph = persons[pid][4] - persons[pid][2]
        best = None
        for k in range(len(row) // 3):
            x, y, c = row[3 * k] * w, row[3 * k + 1] * h, row[3 * k + 2]
            if c < cfg.kp_min_conf:
                continue
            if inside is not None and not (
                    inside[0] <= x <= inside[2] and inside[1] <= y <= inside[3]):
                continue
            d = math.hypot(x - cx, y - cy)
            if inside is None and d > cfg.kp_tol * ph:
                continue
            if best is None or d < best[0]:
                best = (d, k)
        return best[1] if best else None

    if sel.kind == "point":
        near = [(joint(i), i) for i in persons]
        near = [(k, i) for k, i in near if k is not None]
        if near:  # en yakın eklem
            def dist(ki):
                row = kp[str(ki[1])]
                return math.hypot(row[3 * ki[0]] * w - cx, row[3 * ki[0] + 1] * h - cy)
            k, i = min(near, key=dist)
            return i, k
        pid = pick_target_id(doc, sel.t, sel.x1, sel.y1, cfg.pick_tol)
        return (pid, None) if pid is not None else None

    area = max((px2 - px1) * (py2 - py1), 1e-9)
    best = None
    for pid, a, b, c, d in persons.values():
        iw, ih = min(px2, c) - max(px1, a), min(py2, d) - max(py1, b)
        frac = max(iw, 0) * max(ih, 0) / area
        if frac >= 0.5 and (best is None or (frac, -(c - a) * (d - b)) > best[0]):
            best = ((frac, -(c - a) * (d - b)), pid)
    if best is None:
        pid = pick_target_id(doc, sel.t, (sel.x1 + sel.x2) / 2, (sel.y1 + sel.y2) / 2,
                             cfg.pick_tol)
        return (pid, None) if pid is not None else None
    pid = best[1]
    ph = persons[pid][4] - persons[pid][2]
    if (py2 - py1) <= 0.3 * ph:
        k = joint(pid, inside=(px1, py1, px2, py2))
        if k is not None:
            return pid, k
    return pid, None


def _atomic_write(path: Path, data: dict) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, path)


class Scan:
    """Tarama yardımcısı: ilerleme + kesinti noktası + iptal + hedef çözümü."""

    def __init__(self, out: str, cfg: Config, sel: Selection | None, size, fps: float,
                 total: int | None, clock=time.monotonic, sig: str | None = None):
        self.out = Path(out)
        self.prog = self.out.with_name(self.out.name + ".scan.json")
        self.part = self.out.with_name(self.out.name + ".partial.json")
        self.cancel_file = self.out.with_name(self.out.name + ".scan.cancel")
        self.cfg, self.sel, self.size, self.fps, self.total = cfg, sel, size, fps, total
        self.clock, self.target, self.error = clock, None, None
        self._last = -1e9
        self.cancelled = False
        self.sig = sig  # A3: farklı video/model/fps'in kesinti noktası sürdürülmez
        self.slow_file = self.out.with_name(self.out.name + THROTTLE_SUFFIX)

    def _partial_doc(self, samples) -> dict:
        d = {"fps": self.fps, "size": list(self.size), "samples": samples}
        if self.sig:
            d["sig"] = self.sig
        return d

    def load_partial(self) -> list:
        """Önceki kesinti noktasının örnekleri (video/fps uyuşmazsa boş)."""
        try:
            d = json.loads(self.part.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return []
        ok = d.get("fps") == self.fps and tuple(d.get("size", ())) == tuple(self.size)
        if self.sig and d.get("sig") != self.sig:
            ok = False
        return d.get("samples", []) if ok else []

    def _doc(self, samples):
        return {"samples": samples, "video": {"w": self.size[0], "h": self.size[1]}}

    def _resolve(self, samples, final=False) -> None:
        if self.sel is None or self.target is not None or not samples:
            return
        t = samples[-1]["t"]
        if not final and t < self.sel.t + LOOKAHEAD_S:
            return
        r = resolve_selection(self._doc(samples), self.sel, self.cfg)
        if r is not None:
            self.target = {"id": r[0], **({"part": r[1]} if r[1] is not None else {})}
            self.error = None
        elif final or t >= self.sel.t + GRACE_S:
            self.error = "seçilen yerde kişi bulunamadı"

    def write(self, state: str, n: int, samples) -> None:
        t = samples[-1]["t"] if samples else 0.0
        pct = round(100 * n / self.total) if self.total else None
        _atomic_write(self.prog, {"state": state, "n": n, "total": self.total, "pct": pct,
                                  "t": t, "target": self.target, "error": self.error,
                                  "fps": self.fps})

    def on_sample(self, n: int, samples: list) -> None:
        if self.sel is not None and self.target is None:
            self._resolve(samples)
        if n % CHECKPOINT_EVERY == 0:
            _atomic_write(self.part, self._partial_doc(samples))
        now = self.clock()
        if now - self._last >= 1.0:
            self._last = now
            self.write("running", n, samples)
        if self.cfg.scan_throttle_s > 0 and self.slow_file.exists():  # oynatma açık: yer aç
            time.sleep(self.cfg.scan_throttle_s)

    def should_stop(self) -> bool:
        if self.cancel_file.exists():
            self.cancelled = True
        return self.cancelled

    def finish(self, n: int, samples: list) -> None:
        if self.cancelled:  # kesinti noktası kalır; --resume ile sürer
            _atomic_write(self.part, self._partial_doc(samples))
            self.write("cancelled", n, samples)
            return
        self._resolve(samples, final=True)
        self.write("done", n, samples)
        for p in (self.part, self.cancel_file, self.slow_file):
            try:
                p.unlink()
            except OSError:
                pass


def run_scan(src, pipe, cfg: Config, out: str, sel: Selection | None, fps: float,
             total: int | None, resume: bool = False, pose=None, progress=None,
             sig: str | None = None):
    """Taramayı yürüt → (örnekler, boyut, Scan). `resume`: kesinti noktasından sürdür."""
    from .offline import run_detection

    sc = Scan(out, cfg, sel, (src.w, src.h), fps, total, sig=sig)
    done = sc.load_partial() if resume else []
    if done:
        src.start = done[-1]["t"]
        ids = [d[0] for s in done for d in s["d"]]
        if hasattr(pipe, "tracker"):
            pipe.tracker.set_next_id(max(ids, default=0) + 1)
        sc.on_sample(len(done), done)  # hedef önceden çözülebiliyorsa çöz
    sc.cancel_file.unlink(missing_ok=True)
    samples, size = run_detection(src, pipe, progress, total, pose=pose, samples=done,
                                  on_sample=sc.on_sample, should_stop=sc.should_stop)
    sc.finish(len(samples), samples)
    return samples, (size if size[0] else (src.w, src.h)), sc


def finish_cached(out: str, doc: dict, cfg: Config, sel: Selection | None, fps: float,
                  sig: str | None = None) -> Scan:
    """A3: önbellekten gelen belge için ilerleme dosyasını `done` yap, hedefi çöz (tarama yok)."""
    v = doc["video"]
    sc = Scan(out, cfg, sel, (v.get("w", 0), v.get("h", 0)), fps, len(doc["samples"]), sig=sig)
    sc._resolve(doc["samples"], final=True)
    sc.write("done", len(doc["samples"]), doc["samples"])
    sc.slow_file.unlink(missing_ok=True)
    return sc
