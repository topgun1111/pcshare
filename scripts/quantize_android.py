"""Pose ONNX -> QDQ-quantized ONNX for the Hexagon NPU (QNN HTP), calibrated on real frames.

Usage: python scripts/quantize_android.py SRC.onnx DST.onnx CALIB_DIR [--a16]
  CALIB_DIR: JPEG/PNG frames with people in motion (android/calib/, ~50-100 frames;
             your own footage is best).
  --a16: 16-bit activations / 8-bit weights (HTP supports it; keeps keypoint coordinates precise).
         Default is 8-bit activations (fastest, but pixel coordinates up to 640 get ~2.5 px steps).
Needs: pip install onnxruntime onnx opencv-python-headless numpy (build machine only).
Preprocessing is identical to the app (letterbox, gray 114, RGB, CHW, 0..1;
tracker/detector.py::letterbox).
fp16 needs no calibration: the app runs fp32 models with HTP fp16 precision
(`enable_htp_fp16_precision`).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

SIZE = 640
EXTS = {".jpg", ".jpeg", ".png"}
MIN_BYTES = 2_000_000


def preprocess(path: Path, size: int = SIZE) -> np.ndarray | None:
    import cv2

    img = cv2.imread(str(path))
    if img is None:
        return None
    h, w = img.shape[:2]
    r = min(size / h, size / w)
    nw, nh = max(1, round(w * r)), max(1, round(h * r))
    img = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    px, py = (size - nw) // 2, (size - nh) // 2
    canvas[py:py + nh, px:px + nw] = img
    rgb = canvas[:, :, ::-1].astype(np.float32) / 255.0
    return np.ascontiguousarray(rgb.transpose(2, 0, 1))[None]


def calib_frames(folder: Path) -> list[Path]:
    return sorted(p for p in folder.iterdir() if p.suffix.lower() in EXTS)


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    if len(args) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    src, dst, folder = Path(args[0]), Path(args[1]), Path(args[2])
    if not src.is_file() or not folder.is_dir():
        print(f"HATA: kaynak model ya da kalibrasyon klasoru yok: {src} / {folder}",
              file=sys.stderr)
        return 1
    frames = calib_frames(folder)
    if len(frames) < 10:
        print(f"HATA: kalibrasyon icin en az 10 kare gerekli, bulunan {len(frames)}: {folder}",
              file=sys.stderr)
        return 1

    import onnx
    from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
    from onnxruntime.quantization.execution_provider.qnn import get_qnn_qdq_config

    input_name = onnx.load(str(src), load_external_data=False).graph.input[0].name

    class Reader(CalibrationDataReader):
        def __init__(self) -> None:
            self._it = iter(frames)

        def get_next(self):
            for p in self._it:
                x = preprocess(p)
                if x is not None:
                    return {input_name: x}
            return None

    a16 = "--a16" in argv
    cfg = get_qnn_qdq_config(
        str(src),
        Reader(),
        activation_type=QuantType.QUInt16 if a16 else QuantType.QUInt8,
        weight_type=QuantType.QUInt8,
    )
    dst.parent.mkdir(parents=True, exist_ok=True)
    quantize(str(src), str(dst), cfg)
    if not dst.is_file() or dst.stat().st_size < MIN_BYTES // 2:
        print(f"HATA: nicemleme basarisiz/cok kucuk: {dst}", file=sys.stderr)
        return 1
    mb = dst.stat().st_size / 1e6
    bits = "16" if a16 else "8"
    print(f"QDQ model hazir: {dst} ({mb:.1f} MB, {len(frames)} kalibrasyon karesi, "
          f"{bits}-bit etkinlestirme)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
