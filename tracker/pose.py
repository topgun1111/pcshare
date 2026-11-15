"""Vücut parçası takibi: YOLOv8-pose (ONNX) çıktısından 17 COCO eklem noktası.

Sağ tık bir eklemin (ayak bileği, diz, bilek...) yakınına yapılırsa o eklem izlenir; uzaktaysa
eski nesne takibi (point.py) çalışır. Model: `models/yolov8s-pose.onnx` (Ultralytics, AGPL-3.0:
yalnız kişisel kullanım). Çıktı `[1, 56, N]`: cx, cy, w, h, skor + 17 x (x, y, güven).
Saf fonksiyonlar (`decode_yolo_pose`, `nearest_keypoint`) model olmadan test edilir.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from .detector import (
    _MAX_CANDIDATES,
    Detection,
    Detector,
    LetterboxInfo,
    nms,
    preprocess,
    resolve_model_path,
)
from .geometry import BBox
from .smooth import OneEuro

KEYPOINT_NAMES = (
    "burun", "sol göz", "sağ göz", "sol kulak", "sağ kulak", "sol omuz", "sağ omuz",
    "sol dirsek", "sağ dirsek", "sol bilek", "sağ bilek", "sol kalça", "sağ kalça",
    "sol diz", "sağ diz", "sol ayak bileği", "sağ ayak bileği",
)
N_KPT = len(KEYPOINT_NAMES)


@dataclass(frozen=True)
class Pose:
    bbox: BBox
    score: float
    kpts: np.ndarray  # [17, 3]: x, y (kare pikseli), güven


def decode_yolo_pose(
    output: np.ndarray,
    info: LetterboxInfo,
    frame_w: int,
    frame_h: int,
    conf_thres: float = 0.25,
    iou_thres: float = 0.45,
    max_det: int = 20,
) -> list[Pose]:
    """YOLOv8-pose ham çıktısı (`[1,56,N]` ya da `[1,N,56]`) -> `Pose` listesi (kare pikseli)."""
    out = np.asarray(output)
    if out.ndim == 3:
        if out.shape[0] != 1:
            raise ValueError(f"Yalnızca batch=1 desteklenir, çıktı şekli {out.shape}")
        out = out[0]
    if out.ndim != 2:
        raise ValueError(f"Beklenmeyen çıktı şekli: {np.asarray(output).shape}")
    if out.shape[0] == 5 + 3 * N_KPT and out.shape[1] != out.shape[0]:  # [56, N] -> [N, 56]
        out = out.T
    if out.shape[1] != 5 + 3 * N_KPT:
        raise ValueError(f"Pose çıktısı [N, {5 + 3 * N_KPT}] bekleniyor, şekil {out.shape}")
    out = out.astype(np.float32, copy=False)
    scores = out[:, 4]
    idx = np.flatnonzero(scores >= conf_thres)
    if idx.size == 0:
        return []
    if idx.size > _MAX_CANDIDATES:
        idx = idx[np.argsort(-scores[idx])[:_MAX_CANDIDATES]]
    rows, scores = out[idx], scores[idx]

    cx, cy, w, h = rows[:, 0], rows[:, 1], rows[:, 2], rows[:, 3]
    boxes = np.stack([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], axis=1)
    boxes[:, [0, 2]] = np.clip((boxes[:, [0, 2]] - info.pad_x) / info.scale, 0, frame_w)
    boxes[:, [1, 3]] = np.clip((boxes[:, [1, 3]] - info.pad_y) / info.scale, 0, frame_h)
    kp = rows[:, 5:].reshape(-1, N_KPT, 3).copy()
    kp[:, :, 0] = (kp[:, :, 0] - info.pad_x) / info.scale
    kp[:, :, 1] = (kp[:, :, 1] - info.pad_y) / info.scale

    valid = ((boxes[:, 2] - boxes[:, 0]) >= 1.0) & ((boxes[:, 3] - boxes[:, 1]) >= 1.0)
    boxes, scores, kp = boxes[valid], scores[valid], kp[valid]
    keep = nms(boxes, scores, iou_thres)[:max_det]
    return [Pose(BBox(*(float(v) for v in boxes[i])), float(scores[i]), kp[i]) for i in keep]


def nearest_keypoint(
    poses: list[Pose], px: float, py: float, tol: float = 0.12, min_conf: float = 0.35,
    fine_tol: float = 0.05, fine_conf: float | None = None,
) -> tuple[int, int] | None:
    """(px, py) noktasına en yakın güvenilir eklem: (poz indeksi, eklem indeksi).

    Uzaklık sınırı kişi boyunun `tol` oranıdır; hiçbiri yeterince yakın değilse None.
    O2: poz 17'den çok nokta taşıyorsa (tüm-vücut: el/yüz/ayak parmağı) 17'den sonraki küçük
    noktalar için `fine_tol` (daha dar) ve `fine_conf` (None = `min_conf`) kullanılır.
    """
    best: tuple[float, int, int] | None = None
    for i, p in enumerate(poses):
        height = max(p.bbox.y2 - p.bbox.y1, 1.0)
        for k in range(len(p.kpts)):
            fine = k >= N_KPT
            limit = (fine_tol if fine else tol) * height
            x, y, c = p.kpts[k]
            if c < (min_conf if not fine or fine_conf is None else fine_conf):
                continue
            d = float(np.hypot(x - px, y - py))
            if d <= limit and (best is None or d < best[0]):
                best = (d, i, k)
    return None if best is None else (best[1], best[2])


def _iou(a: BBox, b: BBox) -> float:
    iw = min(a.x2, b.x2) - max(a.x1, b.x1)
    ih = min(a.y2, b.y2) - max(a.y1, b.y1)
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    ua = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
    return inter / max(ua, 1e-9)


class PoseEstimator:
    """ONNX Runtime ile YOLOv8-pose. `session` verilirse model yüklenmez (test)."""

    def __init__(self, model_path: str | Path = "models/yolov8s-pose.onnx", conf_thres: float = 0.3,
                 iou_thres: float = 0.45, input_size: int = 640, device: str = "auto",
                 session: Any = None) -> None:
        self.conf_thres, self.iou_thres = conf_thres, iou_thres
        if session is None:
            session = Detector._open_session(resolve_model_path(model_path), device)
        self.session = session
        inp = session.get_inputs()[0]
        self.input_name: str = inp.name
        self.fp16 = "float16" in str(getattr(inp, "type", ""))
        shape = list(getattr(inp, "shape", []) or [])
        h = shape[2] if len(shape) == 4 and isinstance(shape[2], int) else input_size
        w = shape[3] if len(shape) == 4 and isinstance(shape[3], int) else input_size
        self.size: tuple[int, int] = (int(h), int(w))

    def estimate(self, frame_bgr: np.ndarray) -> list[Pose]:
        h, w = frame_bgr.shape[:2]
        x, info = preprocess(frame_bgr, self.size, self.fp16)
        out = self.session.run(None, {self.input_name: x})[0]
        return decode_yolo_pose(out, info, w, h, self.conf_thres, self.iou_thres)


class PoseDetector:
    """Tek model: poz modeli hem kişi dedektörü (`detect`) hem eklem kaynağı (`estimate`) olur (G2).

    `detect(kare)` pozdan kişi kutuları döner (`Detector` ile aynı arayüz → `Pipeline`); aynı kare
    nesnesi için `estimate(kare)` ikinci bir çıkarım yapmadan önbellekteki pozları verir (offline
    `--pose` taramasında kare başına tek çıkarım; canlıda tıklama anında ek çıkarım yok).
    Çıkarım eşiği `min(det_conf, pose_conf)`; `detect` ≥ det_conf (ByteTrack düşük skor aşaması),
    `estimate` ≥ pose_conf döndürür.
    """

    model_type = "yolo-pose"

    def __init__(self, model_path: str | Path = "models/yolov8s-pose.onnx", det_conf: float = 0.1,
                 pose_conf: float = 0.3, iou_thres: float = 0.45, input_size: int = 640,
                 device: str = "auto", session: Any = None, estimator: PoseEstimator | None = None
                 ) -> None:
        self.det_conf, self.pose_conf = det_conf, pose_conf
        self.est = estimator or PoseEstimator(model_path, min(det_conf, pose_conf), iou_thres,
                                              input_size, device, session)
        self.est.conf_thres = min(det_conf, pose_conf)
        self._frame: np.ndarray | None = None
        self._poses: list[Pose] = []
        self.runs = 0  # model çıkarımı sayısı (test/bench)

    @property
    def session(self) -> Any:
        return self.est.session

    @property
    def size(self) -> tuple[int, int]:
        return self.est.size

    @property
    def providers(self) -> list[str]:
        return list(self.est.session.get_providers())

    def _infer(self, frame_bgr: np.ndarray) -> list[Pose]:
        if self._frame is frame_bgr:  # aynı kare nesnesi: önbellek
            return self._poses
        self._poses = self.est.estimate(frame_bgr)
        self._frame = frame_bgr
        self.runs += 1
        return self._poses

    def detect(self, frame_bgr: np.ndarray) -> list[Detection]:
        poses = self._infer(frame_bgr)
        return [Detection(p.bbox, p.score) for p in poses if p.score >= self.det_conf]

    def estimate(self, frame_bgr: np.ndarray) -> list[Pose]:
        return [p for p in self._infer(frame_bgr) if p.score >= self.pose_conf]


class KeypointTracker:
    """Seçilen kişinin seçilen eklemini her karede pozdan okur (point.py ile aynı arayüz).

    Kişi, önceki kutuya en çok benzeyen (IoU / merkez yakınlığı) pozla eşlenir. Eklem güveni
    düşükse ya da kişi bulunamazsa `update` None döner (denetleyici "hedef kayıp" akışına girer).

    B2 (`hold_s` > 0 ile açılır; 0 = eski davranış): kişi kareden çıkınca/kapanınca eklemin
    (kamera telafili `cam`) dünya konumu sönümlü hızla ilerletilir; `hold_s` sn boyunca bu
    öngörülen kutu döner (`visible` = öngörülen konum karenin içinde mi), sonra None döner ama
    izleyici yaşar ve kişi geri girince yeniden bağlar: öngörülen konuma yakın (ya da konumu
    kutusunda içeren) ve boyu benzer poz; `reacq_single` ile uzun kayıptan sonra uygun tek kişi
    de bağlanır (ters taraftan girişte; kalabalıkta kimlik karışabilir). `smooth` eklem konumuna
    One-Euro (kişi boyuna normalize) uygular.
    """

    visible = True

    def __init__(self, estimator: PoseEstimator, pose: Pose, kp: int, box_frac: float = 0.10,
                 min_conf: float = 0.35, hold: int = 0, *, hold_s: float = 0.0, cam=None,
                 smooth: bool = False, reacq_single: bool = True, appearance=None,
                 refiner=None, clock=time.monotonic) -> None:
        self.est, self.kp, self.box_frac, self.min_conf = estimator, kp, box_frac, min_conf
        self.hold, self._misses = hold, 0  # hold: tolere edilen ardışık ıska
        self.hold_s, self.cam, self.reacq_single, self.clock = hold_s, cam, reacq_single, clock
        self.app = appearance  # reid.PartAppearance | None (G3): kalabalıkta kişiyi görünümle bul
        self._frame: np.ndarray | None = None
        # O2: kp >= 17 tüm-vücut noktasıdır; kişi hâlâ 17 noktalı pozla eşlenir (`_ck` = üst eklem)
        # ve yalnız eşlenen kişi `refiner` (wholebody.WholeBodyEstimator) ile 133 noktaya genişler
        self.refiner = refiner
        if kp < N_KPT:
            self.label, self._ck = KEYPOINT_NAMES[kp], kp
        else:
            from .wholebody import WB_NAMES, parent_joint

            self.label, self._ck = WB_NAMES[kp], parent_joint(kp)
        self._person = pose.bbox
        self.last_pose = pose  # B3: parça ayarına geçişte (`PartTracker`) yeniden kullanılır
        self._pos = (float(pose.kpts[kp][0]), float(pose.kpts[kp][1]))
        self._h = max(pose.bbox.y2 - pose.bbox.y1, 1.0)
        self._w = self._to_w(*self._pos)  # dünya konumu (cam yoksa kare konumu)
        self._wv = (0.0, 0.0)  # dünya hızı (px/adım)
        self._blind_t: float | None = None  # kişi bulunamamaya başladığı an
        self._cut = False  # G4: kesmeden sonra henüz yeniden bağlanmadı
        self._fs = (OneEuro(), OneEuro()) if smooth else None
        self._fw = self._fh = 0

    @property
    def bbox(self) -> BBox:
        half = max(self.box_frac * self._h, 8.0) / 2
        return BBox(self._pos[0] - half, self._pos[1] - half,
                    self._pos[0] + half, self._pos[1] + half)

    def _to_w(self, x: float, y: float) -> tuple[float, float]:
        return self.cam.to_world(x, y) if self.cam is not None else (x, y)

    def _to_f(self, wx: float, wy: float) -> tuple[float, float]:
        return self.cam.to_frame(wx, wy) if self.cam is not None else (wx, wy)

    def _predicted(self) -> tuple[float, float]:
        """Eklemin bu kare için kamera telafili konumu (kişi görünürken son konum + kamera)."""
        return self._to_f(*self._w) if self.cam is not None else self._pos

    def update(self, frame_bgr: np.ndarray) -> BBox | None:
        self._fh, self._fw = frame_bgr.shape[:2]
        self._frame = frame_bgr
        if self.cam is not None:
            self.cam.update(frame_bgr)
        poses = self.est.estimate(frame_bgr)
        b2 = self.hold_s > 0.0
        best = self._reacquire(poses) if b2 and self._blind_t is not None else self._match(poses)
        if best is None:  # kişi bu karede bulunamadı
            return self._blind() if b2 else self._held()
        if self.app is not None and self._blind_t is None:
            self.app.observe(frame_bgr, best.bbox, [p.bbox for p in poses if p is not best],
                             self.clock())
        self._blind_t = None
        self._cut = False
        self._h = max(best.bbox.y2 - best.bbox.y1, 1.0)
        prev, self._person = self._person, best.bbox
        if self.kp >= N_KPT and self.refiner is not None and len(best.kpts) <= self.kp:
            best = self.refiner.refine(frame_bgr, best)  # yalnız eşleşen kişi: kare başına 1
        self.last_pose = best
        read = self._read(best)
        if read is None:
            # Eklem görünmüyor ama kişi var: eklemi kişinin kayması kadar taşı (tek karelik
            # güven düşüşü hedefi düşürmesin; artı işareti de sönmesin)
            x, y = self._pos[0] + best.bbox.cx - prev.cx, self._pos[1] + best.bbox.cy - prev.cy
            self._set(x, y, observed=False)
            return self._held()
        self._misses = 0
        self._set(read[0], read[1], observed=True)
        return self.bbox

    def _read(self, best: Pose) -> tuple[float, float] | None:
        """Eşleşen kişinin izlenen noktası (kare pikseli) ya da None (güvenilir değil)."""
        if len(best.kpts) <= self.kp or best.kpts[self.kp][2] < self.min_conf:
            return None
        return float(best.kpts[self.kp][0]), float(best.kpts[self.kp][1])

    def _set(self, x: float, y: float, observed: bool) -> None:
        if self._fs is not None and observed:
            t = self.clock()
            x = self._fs[0].update(x / self._h, t) * self._h
            y = self._fs[1].update(y / self._h, t) * self._h
        self._pos = (x, y)
        self.visible = True
        w = self._to_w(x, y)
        if observed:
            self._wv = (self._wv[0] + 0.3 * ((w[0] - self._w[0]) - self._wv[0]),
                        self._wv[1] + 0.3 * ((w[1] - self._w[1]) - self._wv[1]))
        self._w = w

    def _match(self, poses: list[Pose]) -> Pose | None:
        prev = self._person
        ph = max(prev.y2 - prev.y1, 1.0)
        pred = self._pos if self.hold_s > 0.0 else None
        best, best_s = None, 0.0
        for p in poses:
            d = float(np.hypot(p.bbox.cx - prev.cx, p.bbox.cy - prev.cy)) / ph
            s = _iou(prev, p.bbox) + max(0.0, 1.0 - d)  # yakın + örtüşen
            if pred is not None and p.bbox.contains(*pred):
                s += 0.5  # eklem-kutu içinde (B2): kalabalıkta doğru kişiyi seç
            if (_iou(prev, p.bbox) > 0.1 or d < 0.6) and s > best_s:
                best, best_s = p, s
        return best

    def _reacquire(self, poses: list[Pose]) -> Pose | None:
        """Kayıptan sonra: öngörülen konuma en yakın benzer boylu poz; yoksa (uzun kayıpta)
        uygun tek kişi."""
        px, py = self._predicted()
        best, best_d = None, 0.0
        cand = []
        for p in poses:
            ph = max(p.bbox.y2 - p.bbox.y1, 1.0)
            if not 0.5 <= ph / self._h <= 2.0:
                continue  # boy tutarsız: başka kişi
            cand.append(p)
        if self.app is not None and self._frame is not None and cand:
            i = self.app.pick(self._frame, [p.bbox for p in cand])  # G3: görünümle kurtarma
            if i is not None:
                return cand[i]
        for p in [] if self._cut else cand:  # kesmeden sonra yakınlık anlamsız
            ph = max(p.bbox.y2 - p.bbox.y1, 1.0)
            kx, ky, kc = p.kpts[self._ck]
            ref = (float(kx), float(ky)) if kc >= self.min_conf else (p.bbox.cx, p.bbox.cy)
            d = float(np.hypot(ref[0] - px, ref[1] - py)) / ph
            if (d <= 0.6 or p.bbox.contains(px, py)) and (best is None or d < best_d):
                best, best_d = p, d
        if best is None and self.reacq_single and len(cand) == 1 and len(poses) == 1:
            if self.clock() - (self._blind_t or 0.0) > self.hold_s:
                best = cand[0]
        return best

    def on_cut(self) -> None:
        """Çekim kesmesi (G4): öngörülen konum anlamsız; kamera geçmişi sıfırlanır ve kişi yalnız
        görünümle (Re-ID) ya da tek uygun kişi kuralıyla yeniden bağlanır (yakınlıkla DEĞİL)."""
        if self.hold_s <= 0.0:
            return
        if self.cam is not None:
            self.cam.reset()
        self._w = self._to_w(*self._pos)
        self._wv = (0.0, 0.0)
        self._cut = True
        if self._blind_t is None:
            self._blind_t = self.clock()

    def _blind(self) -> BBox | None:
        now = self.clock()
        if self._blind_t is None:
            self._blind_t = now
        self._wv = (self._wv[0] * 0.9, self._wv[1] * 0.9)
        self._w = (self._w[0] + self._wv[0], self._w[1] + self._wv[1])
        self._pos = self._to_f(*self._w)
        self.visible = 0 <= self._pos[0] <= self._fw and 0 <= self._pos[1] <= self._fh
        return self.bbox if now - self._blind_t <= self.hold_s else None

    def _held(self) -> BBox | None:
        self._misses += 1
        return self.bbox if self._misses <= self.hold else None


class PartTracker(KeypointTracker):
    """B3: seçilen kişinin bir VÜCUT PARÇASINI (eklem grubu: baş, kol, bacak…) izler.

    Takip kutusu `parts.group_extent`: güvenilir eklemlerin birleşimi (yoksa kişi kutusunun ilgili
    bölgesi). Eşleme, kayıp/kapanma, yeniden bağlama, kamera telafisi ve süzgeç `KeypointTracker`
    ile aynıdır; yalnız okunan nokta grubun merkezi ve kutu grubun boyudur. `set_preset` yeniden
    tarama/yeniden tıklama gerektirmeden parçayı değiştirir (kişi eşlemesi korunur).
    """

    def __init__(self, estimator: PoseEstimator, pose: Pose, preset: str, *,
                 kp_box: float = 0.10, min_conf: float = 0.35, margin: float = 0.02, **kw) -> None:
        from .parts import LABELS_TR, check_preset, group_extent

        self._ge, self._labels = group_extent, LABELS_TR
        self.preset, self.margin, self.kp_box = check_preset(preset), margin, kp_box
        super().__init__(estimator, pose, 0, kp_box, min_conf, **kw)
        self._ext = (1.0, 1.0)
        self.label = LABELS_TR[preset]
        self._reset_to(pose)

    def _reset_to(self, pose: Pose) -> None:
        cx, cy, bw, bh = self._ge(pose.kpts, self.preset, pose.bbox, self.min_conf, self.kp_box,
                                  self.margin)
        self._ext = (bw, bh)
        self._pos = (cx, cy)
        self._w = self._to_w(cx, cy)
        self._wv = (0.0, 0.0)
        if self._fs is not None:  # süzgeç yeni parçanın konumundan başlasın
            self._fs = (OneEuro(), OneEuro())

    def set_preset(self, preset: str) -> None:
        """Parçayı değiştir; kişi eşlemesi, kamera geçmişi ve Re-ID galerisi korunur."""
        from .parts import check_preset

        self.preset = check_preset(preset)
        self.label = self._labels[preset]
        self._reset_to(self.last_pose)

    @property
    def bbox(self) -> BBox:
        bw, bh = self._ext
        return BBox(self._pos[0] - bw / 2, self._pos[1] - bh / 2,
                    self._pos[0] + bw / 2, self._pos[1] + bh / 2)

    def _read(self, best: Pose) -> tuple[float, float] | None:
        cx, cy, bw, bh = self._ge(best.kpts, self.preset, best.bbox, self.min_conf, self.kp_box,
                                  self.margin)
        self._ext = (0.6 * self._ext[0] + 0.4 * bw, 0.6 * self._ext[1] + 0.4 * bh)
        return cx, cy  # eklem yoksa bile kişi bölgesi: kişi görünürken parça hep okunur
