"""Re-ID (Parça 11): kişi kırpıntısından embedding (OSNet ONNX) ve ID kurtarma.

- `Embedder`: BGR kare + kutular → L2-normalize embedding (N, D). OSNet (256x128 girdi) beklenir.
- `Gallery`: hedefin birkaç farklı görünümü (kosinüs benzerliği, yakın olanlar EMA ile birleşir).
- `ReIdManager` (canlı): hedef görünürken galeriyi doldurur; hedef kaybolunca kayıp sırasında
  doğan yeni izleri galeriyle karşılaştırıp eşik + marj ile hedefi yeni ID'ye bağlar.
- `TrackletBank` + `link_ids` (offline): her izin ilk/son görünümlerinin embedding'i `track.json`'a
  kompakt yazılır (`emb`); hedefin izi ID değişimlerinde zaman sırasıyla zincirlenir.
Saf mantık IO'suzdur ve sahte `embedder` ile testlidir (`embed(frame, boxes) -> (N, D)`).
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .config import Config
from .detector import resolve_model_path, select_providers
from .geometry import BBox

MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)


def l2norm(v: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    return v / np.maximum(n, 1e-12)


def preprocess_crop(crop_bgr: np.ndarray, size: tuple[int, int] = (256, 128)) -> np.ndarray:
    """BGR kırpıntı → (3, H, W) float32, RGB, ImageNet ortalama/std (OSNet/torchreid ile aynı)."""
    h, w = size
    x = cv2.resize(crop_bgr, (w, h), interpolation=cv2.INTER_LINEAR)[:, :, ::-1]
    x = (x.astype(np.float32) / 255.0 - MEAN) / STD
    return np.ascontiguousarray(x.transpose(2, 0, 1))


def crop_person(frame: np.ndarray, b: BBox) -> np.ndarray | None:
    h, w = frame.shape[:2]
    x1, y1 = max(int(b.x1), 0), max(int(b.y1), 0)
    x2, y2 = min(int(np.ceil(b.x2)), w), min(int(np.ceil(b.y2)), h)
    if x2 - x1 < 2 or y2 - y1 < 2:
        return None
    return frame[y1:y2, x1:x2]


class Embedder:
    """OSNet ONNX oturumu. `session` verilirse model yüklenmez (test)."""

    def __init__(self, model_path: str | Path = "models/osnet_x0_25.onnx", device: str = "auto",
                 session: Any = None):
        if session is None:
            session = self._open(resolve_model_path(model_path), device)
        self.session = session
        inp = session.get_inputs()[0]
        self.input_name: str = inp.name
        shape = list(getattr(inp, "shape", []) or [])
        h = shape[2] if len(shape) == 4 and isinstance(shape[2], int) else 256
        w = shape[3] if len(shape) == 4 and isinstance(shape[3], int) else 128
        self.size = (int(h), int(w))
        nb = shape[0] if shape else None
        if isinstance(nb, int) and nb != 1:
            raise ValueError(f"Re-ID modeli sabit batch={nb}; batch=1 veya dinamik batch gerekli")
        self.batched = not isinstance(nb, int)  # sabit batch=1: kırpıntıları tek tek çalıştır
        self.dim: int | None = None

    @staticmethod
    def _open(path: Path, device: str) -> Any:
        if not path.is_file():
            raise FileNotFoundError(
                f"Re-ID modeli bulunamadı: {path}\n"
                "OSNet'i ONNX olarak 'models/' klasörüne koy (ör. osnet_x0_25.onnx; "
                "torchreid ile dışa aktarılır, girdi 1x3x256x128 veya dinamik batch).")
        import onnxruntime as ort

        providers = select_providers(ort.get_available_providers(), device)
        return ort.InferenceSession(str(path), providers=providers)

    def embed(self, frame: np.ndarray, boxes: list[BBox]) -> np.ndarray:
        """(len(boxes), D) L2-normalize; kırpılamayan kutu için sıfır satırı (benzerlik 0)."""
        xs, idx = [], []
        for i, b in enumerate(boxes):
            c = crop_person(frame, b)
            if c is not None:
                xs.append(preprocess_crop(c, self.size))
                idx.append(i)
        rows: list[np.ndarray] = []
        if xs:
            if self.batched:
                rows = list(self.session.run(None, {self.input_name: np.stack(xs)})[0])
            else:
                rows = [self.session.run(None, {self.input_name: x[None]})[0][0] for x in xs]
            self.dim = int(np.asarray(rows[0]).size)  # [D] ya da [D,1,1] → D
        if self.dim is None:
            return np.zeros((len(boxes), 0), np.float32)
        out = np.zeros((len(boxes), self.dim), np.float32)
        for i, r in zip(idx, rows, strict=True):
            out[i] = np.asarray(r, np.float32).reshape(-1)
        return l2norm(out)


def _iou(a: BBox, b: BBox) -> float:
    iw = max(min(a.x2, b.x2) - max(a.x1, b.x1), 0.0)
    ih = max(min(a.y2, b.y2) - max(a.y1, b.y1), 0.0)
    inter = iw * ih
    union = a.w * a.h + b.w * b.h - inter
    return inter / union if union > 0 else 0.0


def crop_ok(tr, others, fw: int, fh: int, min_h: float, max_iou: float) -> bool:
    """Embedding'e uygun kırpıntı mı: yeterince büyük, kare kenarında kesilmemiş, başkasıyla
    örtüşmüyor (örtüşme embedding'i başka kişiyle kirletir)."""
    b = tr.bbox
    if b.h < min_h * fh or b.w < 2:
        return False
    if b.x1 < 2 or b.y1 < 2 or b.x2 > fw - 2 or b.y2 > fh - 2:
        return False
    return all(_iou(b, o.bbox) < max_iou for o in others if o is not tr)


class Gallery:
    """Hedefin birkaç farklı görünümü; çok benzer olan mevcut girdiye EMA ile karışır."""

    def __init__(self, size: int = 8, dup: float = 0.95):
        self.size, self.dup = size, dup
        self.vecs: list[np.ndarray] = []

    def __len__(self) -> int:
        return len(self.vecs)

    def clear(self) -> None:
        self.vecs = []

    def score(self, e: np.ndarray) -> float:
        return max((float(v @ e) for v in self.vecs), default=-1.0)

    def add(self, e: np.ndarray) -> None:
        if self.vecs:
            sims = [float(v @ e) for v in self.vecs]
            i = int(np.argmax(sims))
            if sims[i] >= self.dup:
                self.vecs[i] = l2norm(0.8 * self.vecs[i] + 0.2 * e)
                return
        self.vecs.append(e)
        if len(self.vecs) > self.size:
            self.vecs.pop(0)


class PartAppearance:
    """Eklem (vücut parçası) takibi için kişi görünümü: izlenen kişinin galerisi tutulur; parça
    kareden çıkıp kalabalıkta geri dönünce aday kişiler galeriyle karşılaştırılır (B2 + Re-ID)."""

    def __init__(self, embedder, cfg: Config):
        self.emb, self.cfg = embedder, cfg
        self.gallery = Gallery(cfg.reid_gallery)
        self._t: float | None = None

    def observe(self, frame: np.ndarray, person: BBox, others: list[BBox], t: float) -> None:
        """Hedef kişi görünürken (en sık `reid_every_s`) temiz kırpıntıyı galeriye ekler."""
        if self._t is not None and t - self._t < self.cfg.reid_every_s:
            return
        fh, fw = frame.shape[:2]
        wrap = type("B", (), {"bbox": person})
        if not crop_ok(wrap, [type("B", (), {"bbox": o}) for o in others], fw, fh,
                       self.cfg.reid_min_h, self.cfg.reid_max_iou):
            return
        self._t = t
        try:
            e = self.emb.embed(frame, [person])[0]
        except Exception:  # model/kare hatası: galeri büyümez, takip sürer
            return
        if float(e @ e) > 0.5:  # sıfır satırı (kırpılamadı) değil
            self.gallery.add(e)

    def pick(self, frame: np.ndarray, boxes: list[BBox]) -> int | None:
        """Galeriye eşik+marjla en çok benzeyen adayın indeksi; güvenilir değilse None."""
        if not boxes or len(self.gallery) == 0:
            return None
        try:
            embs = self.emb.embed(frame, boxes)
        except Exception:
            return None
        sc = [self.gallery.score(e) if float(e @ e) > 0.5 else -1.0 for e in embs]
        order = sorted(range(len(sc)), key=lambda i: -sc[i])
        top = order[0]
        if sc[top] < self.cfg.reid_thres:
            return None
        if len(order) > 1 and sc[top] - sc[order[1]] < self.cfg.reid_margin:
            return None
        return top

    def clear(self) -> None:
        self.gallery.clear()
        self._t = None


class ReIdManager:
    def __init__(self, embedder, cfg: Config, team=None, numbers=None):
        """`team`: team.TeamClassifier | None; `numbers`: number.NumberReader | None (ikisi de
        yalnızca aday *eler*: takım/numara bilinip hedeften farklıysa; belirsizse elemez)."""
        self.emb, self.cfg = embedder, cfg
        self.team, self.numbers = team, numbers
        self.gallery = Gallery(cfg.reid_gallery)
        self._t_obs: float | None = None
        self._t_match: float | None = None
        self._t_team: float | None = None
        self._tfeat: np.ndarray | None = None  # hedefin takım rengi özelliği (EMA)
        self._nvote = None
        if numbers is not None:
            from .number import NumberVoter  # döngüsel içe aktarmayı önler (number → reid)

            self._nvote = NumberVoter()

    def reset(self) -> None:
        self.gallery.clear()
        self._t_obs = self._t_match = self._t_team = None
        self._tfeat = None  # takım modeli sahnenin özelliği: korunur
        if self._nvote:
            self._nvote.reset()

    def _due(self, last: float | None, t: float) -> bool:
        return last is None or t < last or t - last >= self.cfg.reid_every_s

    def observe(self, frame, t: float, target, others) -> None:
        """Hedef görünürken (hız sınırlı) galeriyi besle."""
        from .team import team_feature

        fh, fw = frame.shape[:2]
        if self.team is not None and self._due(self._t_team, t):
            self._t_team = t
            for tr in (target, *others):
                if tr.bbox.h < self.cfg.reid_min_h * fh:
                    continue
                f = team_feature(frame, tr.bbox, self.cfg.team_min_px)
                if f is None:
                    continue
                self.team.add(f)
                if tr is target and crop_ok(tr, others, fw, fh, self.cfg.reid_min_h,
                                            self.cfg.reid_max_iou):
                    self._tfeat = f if self._tfeat is None else l2norm(0.8 * self._tfeat + 0.2 * f)
        if not self._due(self._t_obs, t):
            return
        if not crop_ok(target, others, fw, fh, self.cfg.reid_min_h, self.cfg.reid_max_iou):
            return
        self._t_obs = t
        self.gallery.add(self.emb.embed(frame, [target.bbox])[0])
        if self.numbers is not None:
            n, c = self.numbers.read(frame, [target.bbox])[0]
            self._nvote.add(n, c)

    def _team_filter(self, frame, cands):
        tl = self.team.label(self._tfeat) if self.team is not None else None
        if tl is None:
            return cands
        from .team import team_feature

        keep = []
        for c in cands:
            cl = self.team.label(team_feature(frame, c.bbox, self.cfg.team_min_px))
            if cl is None or cl == tl:  # belirsiz → elenmez
                keep.append(c)
        return keep

    def _number_filter(self, frame, cands):
        tn = (self._nvote.value(self.cfg.number_min_votes, self.cfg.number_share)
              if self.numbers is not None else None)
        if tn is None or not cands:
            return cands
        reads = self.numbers.read(frame, [c.bbox for c in cands])
        return [c for c, (n, _) in zip(cands, reads, strict=True) if n is None or n == tn]

    def match(self, frame, t: float, candidates, all_tracks):
        """Aday izlerden galeriye en çok benzeyeni döndür (eşik + ikinciye marj); yoksa None."""
        if not candidates or not len(self.gallery) or not self._due(self._t_match, t):
            return None
        fh, fw = frame.shape[:2]
        ok = [c for c in candidates
              if crop_ok(c, all_tracks, fw, fh, self.cfg.reid_min_h, self.cfg.reid_max_iou)]
        if not ok:
            return None
        self._t_match = t
        ok = self._number_filter(frame, self._team_filter(frame, ok))
        if not ok:
            return None
        embs = self.emb.embed(frame, [c.bbox for c in ok])
        scores = sorted(((self.gallery.score(e), c) for e, c in zip(embs, ok, strict=True)),
                        key=lambda sc: -sc[0])
        best = scores[0]
        second = scores[1][0] if len(scores) > 1 else -1.0
        if best[0] >= self.cfg.reid_thres and best[0] - second >= self.cfg.reid_margin:
            return best[1]
        return None


# --- offline -------------------------------------------------------------------------------
class TrackletBank:
    """Her izin ilk `k` ve son `k` (uygun) görünümünün ortalama embedding'ini toplar."""

    def __init__(self, embedder, cfg: Config, k: int = 3, last_every: int = 5):
        self.emb, self.cfg, self.k, self.every = embedder, cfg, k, last_every
        self.st: dict[int, dict] = {}

    def update(self, frame, tracks, idx: int) -> None:
        fh, fw = frame.shape[:2]
        want: list[tuple[Any, str]] = []
        for tr in tracks:
            if not crop_ok(tr, tracks, fw, fh, self.cfg.reid_min_h, self.cfg.reid_max_iou):
                continue
            s = self.st.setdefault(tr.id, {"first": [], "last": [], "fi": -99})
            if len(s["first"]) < self.k and idx - s["fi"] >= 2:
                want.append((tr, "first"))
                s["fi"] = idx
            elif idx % self.every == tr.id % self.every:  # izler farklı örneklerde (yük dağılır)
                want.append((tr, "last"))
        if not want:
            return
        embs = self.emb.embed(frame, [tr.bbox for tr, _ in want])
        for e, (tr, kind) in zip(embs, want, strict=True):
            if not e.size or not np.any(e):
                continue
            s = self.st[tr.id]
            if kind == "first":
                s["first"].append(e)
            s["last"].append(e)
            del s["last"][: -self.k]

    def result(self) -> dict[str, dict[str, list[int]]]:
        """{id: {"first": [...], "last": [...]}}; değerler ×100 yuvarlanmış tamsayı (kompakt)."""
        def q(vs):
            m = l2norm(np.mean(vs, axis=0))
            return [int(round(float(x) * 100)) for x in m]

        return {str(i): {"first": q(s["first"] or s["last"]), "last": q(s["last"])}
                for i, s in self.st.items() if s["last"]}


def _unq(v: list[int]) -> np.ndarray:
    return l2norm(np.asarray(v, np.float32))


def link_ids(doc: dict, target_id: int, cfg: Config) -> list[int]:
    """`doc['emb']` ile hedef izin öncesine/sonrasına ID değişimiyle bağlanan izleri zincirle.

    Aday: zaman olarak ayrık (sonraki iz hedef izin bittiği andan sonra doğar, en fazla
    `reid_link_gap_s`), son↔ilk embedding kosinüsü ≥ `reid_thres`, ikinci adaya marj ≥
    `reid_margin`. Zincir ucu uca sürer. Uzamsal makullük denetlenmez (kısa boşlukta zayıf yer).
    """
    from .offline import list_tracks

    emb = doc.get("emb") or {}
    lab = (doc.get("team") or {}).get("labels") or {}
    spans = {e["id"]: e for e in list_tracks(doc) if e["n"] >= cfg.reid_min_samples}
    vec = {int(k): (_unq(v["first"]), _unq(v["last"])) for k, v in emb.items()
           if int(k) in spans}
    if target_id not in vec:
        return []
    chain = {target_id}

    def extend(cur: int, forward: bool) -> int | None:
        c = spans[cur]
        scored = []
        for j, sj in spans.items():
            if j in chain or j not in vec:
                continue
            tl, tj = lab.get(str(target_id)), lab.get(str(j))
            if tl is not None and tj is not None and tl != tj:
                continue  # farklı takım
            gap = (sj["first"] - c["last"]) if forward else (c["first"] - sj["last"])
            if not 0 <= gap <= cfg.reid_link_gap_s:
                continue
            a, b = (vec[cur][1], vec[j][0]) if forward else (vec[j][1], vec[cur][0])
            scored.append((float(a @ b), j))
        scored.sort(reverse=True)
        if not scored or scored[0][0] < cfg.reid_thres:
            return None
        if len(scored) > 1 and scored[0][0] - scored[1][0] < cfg.reid_margin:
            return None
        return scored[0][1]

    for forward in (True, False):
        cur = target_id
        while (nxt := extend(cur, forward)) is not None:
            chain.add(nxt)
            cur = nxt
    return sorted(chain - {target_id})
