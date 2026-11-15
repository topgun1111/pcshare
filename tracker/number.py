"""Forma numarası (Parça 12, isteğe bağlı): küçük ONNX sınıflandırıcı + zamansal oylama.

Ağır OCR bağımlılığı (PaddleOCR/EasyOCR/tesseract) eklenmez. Model sözleşmesi (kendi modelini
bu biçimde dışa aktar): girdi NCHW float32 [N, C, H, W] (C=1 gri ya da 3 RGB, 0–1), çıktı
[N, 101] logit: 0–99 numara, 100 = "numara yok". Model dağıtılmaz; yoksa açıklayıcı hata.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .detector import resolve_model_path, select_providers
from .geometry import BBox
from .reid import crop_person
from .team import torso

NONE_CLASS = 100


class NumberReader:
    def __init__(self, model_path: str | Path = "models/jersey_number.onnx",
                 device: str = "auto", conf: float = 0.6, session: Any = None):
        if session is None:
            session = self._open(resolve_model_path(model_path), device)
        self.session, self.conf = session, conf
        inp = session.get_inputs()[0]
        self.input_name: str = inp.name
        shape = list(getattr(inp, "shape", []) or [])
        self.channels = shape[1] if len(shape) == 4 and shape[1] in (1, 3) else 3
        self.size = (int(shape[2]) if len(shape) == 4 and isinstance(shape[2], int) else 64,
                     int(shape[3]) if len(shape) == 4 and isinstance(shape[3], int) else 64)

    @staticmethod
    def _open(path: Path, device: str) -> Any:
        if not path.is_file():
            raise FileNotFoundError(
                f"Forma numarası modeli bulunamadı: {path}\n"
                "Girdi [N,C,H,W] (C=1/3), çıktı [N,101] (0-99 numara, 100 = yok) olan bir "
                "ONNX sınıflandırıcıyı 'models/' klasörüne koy (bkz. tracker/number.py).")
        import onnxruntime as ort

        return ort.InferenceSession(str(path), providers=select_providers(
            ort.get_available_providers(), device))

    def _prep(self, crop: np.ndarray) -> np.ndarray | None:
        t = torso(crop, 0.12, 0.55, 0.10, 0.90)
        if t is None or t.shape[0] < 4 or t.shape[1] < 4:
            return None
        h, w = self.size
        x = cv2.resize(t, (w, h), interpolation=cv2.INTER_AREA)
        if self.channels == 1:
            x = cv2.cvtColor(x, cv2.COLOR_BGR2GRAY)[None]
        else:
            x = x[:, :, ::-1].transpose(2, 0, 1)
        return np.ascontiguousarray(x.astype(np.float32) / 255.0)

    def read(self, frame: np.ndarray, boxes: list[BBox]) -> list[tuple[int | None, float]]:
        """Her kutu için (numara | None, güven). Okunamayan / güveni düşük → (None, güven)."""
        out: list[tuple[int | None, float]] = []
        for b in boxes:
            c = crop_person(frame, b)
            x = self._prep(c) if c is not None else None
            if x is None:
                out.append((None, 0.0))
                continue
            lg = np.asarray(self.session.run(None, {self.input_name: x[None]})[0],
                            np.float64).reshape(-1)
            if lg.size != NONE_CLASS + 1:
                raise ValueError(f"numara modeli {lg.size} sınıf verdi; 101 bekleniyor")
            p = np.exp(lg - lg.max())
            p /= p.sum()
            k = int(p.argmax())
            out.append((None if k == NONE_CLASS or p[k] < self.conf else k, float(p[k])))
        return out


class NumberVoter:
    """Hedefin numarası için güven ağırlıklı zamansal oylama."""

    def __init__(self) -> None:
        self.votes: dict[int, float] = {}
        self.n: dict[int, int] = {}

    def reset(self) -> None:
        self.votes, self.n = {}, {}

    def add(self, num: int | None, conf: float) -> None:
        if num is not None:
            self.votes[num] = self.votes.get(num, 0.0) + conf
            self.n[num] = self.n.get(num, 0) + 1

    def value(self, min_votes: int, share: float) -> int | None:
        if not self.votes:
            return None
        k = max(self.votes, key=lambda x: self.votes[x])
        if self.n[k] < min_votes or self.votes[k] < share * sum(self.votes.values()):
            return None
        return k
