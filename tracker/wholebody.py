"""Tüm-vücut poz (O2): RTMPose-wholebody ile el, yüz ve ayak parmağı noktaları (133 nokta).

YOLOv8-pose (pose.py) 17 COCO noktası verir; el/yüz/ayak parmağı yoktur. Bu modül yalnız SEÇİLİ
kişinin kutusunu kırpıp RTMPose-wholebody'ye (üstten alta, SimCC çıktı) verir; her karede tüm
kişilere değil, izlenen tek kişiye çalışır. Model: `models/rtmpose-wholebody.onnx` (OpenMMLab
RTMPose-m, COCO-WholeBody 133 nokta; Apache-2.0 kod, ağırlık eğitim verisi koşullarına tabi:
kişisel kullanım). Saf fonksiyonlar (`crop_params`, `decode_simcc`, `to_frame`) model olmadan
test edilir.

Nokta sırası (COCO-WholeBody): 0-16 vücut (= `pose.KEYPOINT_NAMES`), 17-22 ayak (sol başparmak,
sol küçük parmak, sol topuk, sağ ...), 23-90 yüz (68), 91-111 sol el (21), 112-132 sağ el (21).
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np

from .detector import Detector, resolve_model_path
from .geometry import BBox
from .pose import KEYPOINT_NAMES, N_KPT, Pose

N_WB = 133
FEET = range(17, 23)
FACE = range(23, 91)
LHAND = range(91, 112)
RHAND = range(112, 133)

_FINGERS = ("başparmak", "işaret", "orta", "yüzük", "serçe")


def _hand_names(side: str) -> list[str]:
    out = [f"{side} el bilek"]
    for f in _FINGERS:
        out += [f"{side} el {f} {i}" for i in range(1, 5)]  # 1 = taban, 4 = uç
    return out


def _face_names() -> list[str]:
    groups = (("çene", 17), ("kaş", 10), ("burun", 9), ("göz", 12), ("ağız", 20))  # 17+10+9+12+20
    out: list[str] = []
    for g, n in groups:
        out += [f"yüz {g} {i}" for i in range(1, n + 1)]
    return out


WB_NAMES: tuple[str, ...] = (
    *KEYPOINT_NAMES,
    "sol ayak başparmağı", "sol ayak küçük parmağı", "sol topuk",
    "sağ ayak başparmağı", "sağ ayak küçük parmağı", "sağ topuk",
    *_face_names(), *_hand_names("sol"), *_hand_names("sağ"),
)
assert len(WB_NAMES) == N_WB


def parent_joint(kp: int) -> int:
    """Bir tüm-vücut noktasının YOLO-pose (17 nokta) karşılığı: kişiyi 17 noktalı pozlarla eşlerken
    kullanılır (ayak parmağı → ayak bileği, yüz → burun, el → bilek)."""
    if kp < N_KPT:
        return kp
    if kp in FACE:
        return 0
    if kp in LHAND:
        return 9
    if kp in RHAND:
        return 10
    return 15 if kp < 20 else 16  # 17-19 sol ayak, 20-22 sağ ayak


def crop_params(bbox: BBox, size: tuple[int, int], padding: float = 1.25
                ) -> tuple[float, float, float, float]:
    """Kişi kutusu → (merkez x, merkez y, kırpma genişliği, kırpma yüksekliği); oran girdiyle aynı.

    `size` = (yükseklik, genişlik) model girdisi; kutu `padding` ile büyütülür, kısa kenar uzatılır.
    """
    ih, iw = size
    w, h = max(bbox.x2 - bbox.x1, 1.0), max(bbox.y2 - bbox.y1, 1.0)
    if w * ih > h * iw:
        h = w * ih / iw
    else:
        w = h * iw / ih
    return bbox.cx, bbox.cy, w * padding, h * padding


_MEAN = np.array([123.675, 116.28, 103.53], np.float32)
_STD = np.array([58.395, 57.12, 57.375], np.float32)


def preprocess_crop(frame_bgr: np.ndarray, bbox: BBox, size: tuple[int, int], fp16: bool = False
                    ) -> tuple[np.ndarray, tuple[float, float, float, float]]:
    """Kişi kırpıntısı → [1,3,H,W] (RGB, ortalama/std normalize) ve `crop_params` (geri eşleme)."""
    import cv2

    ih, iw = size
    cx, cy, sw, sh = crop_params(bbox, size)
    sx, sy = iw / sw, ih / sh
    m = np.array([[sx, 0.0, -(cx - sw / 2) * sx], [0.0, sy, -(cy - sh / 2) * sy]], np.float32)
    crop = cv2.warpAffine(frame_bgr, m, (iw, ih), flags=cv2.INTER_LINEAR,
                          borderMode=cv2.BORDER_CONSTANT, borderValue=(114, 114, 114))
    x = (crop[:, :, ::-1].astype(np.float32) - _MEAN) / _STD
    x = np.ascontiguousarray(x.transpose(2, 0, 1)[None])
    return (x.astype(np.float16) if fp16 else x), (cx, cy, sw, sh)


def decode_simcc(simcc_x: np.ndarray, simcc_y: np.ndarray, split: float = 2.0
                 ) -> tuple[np.ndarray, np.ndarray]:
    """SimCC çıktısı (`[1,K,Wx]`, `[1,K,Hy]`) → (konum [K,2] model girdisi pikseli, güven [K]).

    Konum = en yüksek bölme / `split`; güven = x ve y tepe değerinin küçüğü (mmpose ile aynı).
    """
    sx, sy = np.asarray(simcc_x, np.float32), np.asarray(simcc_y, np.float32)
    if sx.ndim == 3:
        sx, sy = sx[0], sy[0]
    if sx.ndim != 2 or sy.ndim != 2 or sx.shape[0] != sy.shape[0]:
        raise ValueError(f"SimCC çıktısı [K,*] bekleniyor: {sx.shape}, {sy.shape}")
    ax, ay = sx.argmax(axis=1), sy.argmax(axis=1)
    conf = np.minimum(sx.max(axis=1), sy.max(axis=1))
    locs = np.stack([ax, ay], axis=1).astype(np.float32) / split
    locs[conf <= 0] = -1.0
    return locs, np.clip(conf, 0.0, 1.0)


def to_frame(locs: np.ndarray, crop: tuple[float, float, float, float], size: tuple[int, int]
             ) -> np.ndarray:
    """Model girdisi pikseli → kare pikseli (`preprocess_crop`'un tersi)."""
    ih, iw = size
    cx, cy, sw, sh = crop
    out = np.empty_like(locs, dtype=np.float32)
    out[:, 0] = locs[:, 0] / iw * sw + cx - sw / 2
    out[:, 1] = locs[:, 1] / ih * sh + cy - sh / 2
    return out


def split_outputs(outs: list[np.ndarray], size: tuple[int, int]) -> tuple[np.ndarray, np.ndarray]:
    """Model çıktılarından (simcc_x, simcc_y): x'in son boyutu girdi genişliğiyle, y'ninki
    yüksekliğiyle aynı oranda büyür (genelde ×2). Sıra belirsizse bu oran bakar."""
    ih, iw = size
    a, b = outs[0], outs[1]
    if a.shape[-1] * ih == b.shape[-1] * iw:
        return a, b
    if b.shape[-1] * ih == a.shape[-1] * iw:
        return b, a
    raise ValueError(f"SimCC çıktı boyutları girdiyle uyuşmuyor: {a.shape}, {b.shape}, "
                     f"girdi {size}")


class WholeBodyEstimator:
    """ONNX Runtime ile RTMPose-wholebody. `session` verilirse model yüklenmez (test)."""

    def __init__(self, model_path: str | Path = "models/rtmpose-wholebody.onnx",
                 device: str = "auto", input_size: tuple[int, int] = (256, 192),
                 session: Any = None) -> None:
        if session is None:
            session = Detector._open_session(resolve_model_path(model_path), device)
        self.session = session
        inp = session.get_inputs()[0]
        self.input_name: str = inp.name
        self.fp16 = "float16" in str(getattr(inp, "type", ""))
        shape = list(getattr(inp, "shape", []) or [])
        h = shape[2] if len(shape) == 4 and isinstance(shape[2], int) else input_size[0]
        w = shape[3] if len(shape) == 4 and isinstance(shape[3], int) else input_size[1]
        self.size: tuple[int, int] = (int(h), int(w))
        self.runs = 0

    def keypoints(self, frame_bgr: np.ndarray, bbox: BBox) -> np.ndarray:
        """133 noktanın [133,3] (x, y kare pikseli, güven) dizisi."""
        x, crop = preprocess_crop(frame_bgr, bbox, self.size, self.fp16)
        outs = self.session.run(None, {self.input_name: x})
        self.runs += 1
        sx, sy = split_outputs([np.asarray(o) for o in outs[:2]], self.size)
        split = np.asarray(sx).shape[-1] / self.size[1]
        locs, conf = decode_simcc(sx, sy, split)
        if len(locs) != N_WB:
            raise ValueError(f"RTMPose-wholebody {N_WB} nokta vermeli, {len(locs)} geldi")
        pts = to_frame(locs, crop, self.size)
        return np.concatenate([pts, conf[:, None]], axis=1).astype(np.float32)

    def refine(self, frame_bgr: np.ndarray, pose: Pose) -> Pose:
        """17 noktalı pozu 133 noktalıya genişletir: ilk 17 nokta YOLO-pose'tan aynen kalır
        (vücut takibi değişmez), 17-132 RTMPose'tan gelir. Model hata verirse poz aynen döner."""
        try:
            kp = self.keypoints(frame_bgr, pose.bbox)
        except Exception:
            return pose
        full = kp.copy()
        full[:N_KPT] = pose.kpts[:N_KPT]
        return Pose(pose.bbox, pose.score, full)
