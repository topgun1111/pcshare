"""RTMPose-wholebody (OpenMMLab RTMPose-m, 133 nokta) ONNX modelini indirip hedef klasöre koyar
(CI'da; isteğe bağlı model, O2).

Kullanım: python scripts/export_wholebody.py dist/TakipliOynat/models [adres]
Yalnız standart kütüphane + (doğrulama için) onnxruntime/numpy. Torch GEREKMEZ: OpenMMLab hazır
`onnx_sdk` arşivini yayımlar; içindeki `end2end.onnx` girdi [1,3,256,192] (RGB, ortalama/std
tracker/wholebody.py'de uygulanır), çıktı SimCC (simcc_x [1,133,384], simcc_y [1,133,512]).
Erişilemezse/biçim uyuşmazsa betik hata verir; CI adımı sürüme engel olmaz (`wholebody=auto`
yalnız dosya varsa açılır). Ağırlık COCO-WholeBody/UBody eğitim verisi koşullarına tabidir:
kişisel kullanım (bkz. THIRD_PARTY_NOTICES.md).
"""
from __future__ import annotations

import io
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

URL = ("https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/"
       "rtmpose-m_simcc-ucoco-dw-ucoco_270e-256x192-c8b76419_20230728.zip")
NAME = "rtmpose-wholebody.onnx"
MIN_BYTES = 20_000_000


def find_onnx(names: list[str]) -> str | None:
    """Arşivdeki model dosyası: `end2end.onnx` (yoksa tek .onnx)."""
    for n in names:
        if n.replace("\\", "/").rsplit("/", 1)[-1] == "end2end.onnx":
            return n
    onnx = [n for n in names if n.lower().endswith(".onnx")]
    return onnx[0] if len(onnx) == 1 else None


def extract_model(data: bytes, dst: Path) -> int:
    """Arşivden modeli `dst`'ye çıkarır; boyut döner."""
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        member = find_onnx(z.namelist())
        if member is None:
            raise ValueError("arşivde end2end.onnx yok")
        with z.open(member) as src, open(dst, "wb") as out:
            shutil.copyfileobj(src, out)
    return dst.stat().st_size


def main(argv: list[str]) -> int:
    target = Path(argv[1] if len(argv) > 1 else "dist/TakipliOynat/models")
    url = argv[2] if len(argv) > 2 else URL
    target.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "takipli-oynat-ci"})
    with urllib.request.urlopen(req, timeout=120) as r:  # noqa: S310 (sabit https adres)
        data = r.read()
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / NAME
        size = extract_model(data, out)
        if size < MIN_BYTES:
            print(f"HATA: model çok küçük ({size} bayt): indirme bozuk ya da adres değişmiş",
                  file=sys.stderr)
            return 1
        import numpy as np
        import onnxruntime as ort

        sess = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"])
        shp = sess.get_inputs()[0].shape
        h, w = (shp[2], shp[3]) if len(shp) == 4 and isinstance(shp[2], int) else (256, 192)
        ys = sess.run(None, {sess.get_inputs()[0].name: np.zeros((1, 3, h, w), np.float32)})
        if len(ys) < 2 or ys[0].shape[1] != 133 or ys[1].shape[1] != 133:
            print(f"HATA: SimCC [1,133,*] x2 bekleniyordu, çıktılar: {[y.shape for y in ys]}",
                  file=sys.stderr)
            return 1
        dst = target / NAME
        shutil.copyfile(out, dst)
    print(f"Tüm-vücut modeli hazır: {dst} ({dst.stat().st_size / 1e6:.1f} MB, girdi {h}x{w}, "
          f"çıktı {[y.shape for y in ys]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
