"""EdgeTAM image encoder -> QDQ int8 (or 16-bit activations) for the Hexagon NPU, only where accuracy permits.

Usage: python scripts/quantize_edgetam.py ENCODER.onnx DST.onnx CALIB_DIR [--a16] [--min-cos 0.98]
  Only the image encoder is quantized (conv-heavy, the slowest graph). The decoder and the memory graphs stay fp16 on the HTP:
  attention/softmax and the pointer/memory features are accuracy-sensitive and small.
  Accuracy gate: up to 12 calibration frames run through the fp32 and the quantized encoder on CPU; for every output the mean
  cosine similarity must reach --min-cos (default 0.98). Below that the quantized file is deleted and the exit code is 3
  (CI keeps the fp16 encoder; this is not an error).
Needs: pip install onnxruntime onnx opencv-python-headless numpy (build machine only).
Preprocessing = the app (SamEngine.chw): resize to 1024x1024 (aspect ignored), RGB, /255, ImageNet mean/std, CHW.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

SIZE = 1024
EXTS = {".jpg", ".jpeg", ".png"}
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
GATE_FRAMES = 12


def normalize_rgb(rgb: np.ndarray) -> np.ndarray:
    """uint8 [H,W,3] RGB -> float32 [1,3,H,W], like the app."""
    x = (rgb.astype(np.float32) / 255.0 - MEAN) / STD
    return np.ascontiguousarray(x.transpose(2, 0, 1))[None]


def load_frame(path: Path) -> np.ndarray | None:
    import cv2

    img = cv2.imread(str(path))
    if img is None:
        return None
    img = cv2.resize(img, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
    return normalize_rgb(img[:, :, ::-1])


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    a, b = a.astype(np.float64).ravel(), b.astype(np.float64).ravel()
    d = float(np.linalg.norm(a) * np.linalg.norm(b))
    return float(a @ b / d) if d > 0 else 0.0


def cosine_gate(ref: list[list[np.ndarray]], got: list[list[np.ndarray]], min_cos: float) -> tuple[bool, list[float]]:
    """ref/got: per frame, the list of outputs. Returns (passed, mean cosine per output)."""
    n_out = len(ref[0])
    means = [float(np.mean([cosine(r[i], g[i]) for r, g in zip(ref, got)])) for i in range(n_out)]
    return all(m >= min_cos for m in means), means


def main(argv: list[str]) -> int:
    flags = [a for a in argv[1:] if a.startswith("--")]
    args = [a for a in argv[1:] if not a.startswith("--") and not a.replace(".", "").isdigit()]
    min_cos = 0.98
    if "--min-cos" in argv:
        min_cos = float(argv[argv.index("--min-cos") + 1])
    if len(args) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    src, dst, folder = Path(args[0]), Path(args[1]), Path(args[2])
    if not src.is_file() or not folder.is_dir():
        print(f"ERROR: encoder or calibration folder missing: {src} / {folder}", file=sys.stderr)
        return 1
    frames = sorted(p for p in folder.iterdir() if p.suffix.lower() in EXTS)
    if len(frames) < 10:
        print(f"ERROR: need at least 10 calibration frames, found {len(frames)}: {folder}", file=sys.stderr)
        return 1

    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
    from onnxruntime.quantization.execution_provider.qnn import get_qnn_qdq_config

    name = onnx.load(str(src), load_external_data=False).graph.input[0].name

    class Reader(CalibrationDataReader):
        def __init__(self) -> None:
            self._it = iter(frames)

        def get_next(self):
            for p in self._it:
                x = load_frame(p)
                if x is not None:
                    return {name: x}
            return None

    a16 = "--a16" in flags
    cfg = get_qnn_qdq_config(str(src), Reader(), activation_type=QuantType.QUInt16 if a16 else QuantType.QUInt8,
                             weight_type=QuantType.QUInt8)
    dst.parent.mkdir(parents=True, exist_ok=True)
    quantize(str(src), str(dst), cfg)

    ref_s = ort.InferenceSession(str(src), providers=["CPUExecutionProvider"])
    q_s = ort.InferenceSession(str(dst), providers=["CPUExecutionProvider"])
    ref, got = [], []
    for p in frames[:GATE_FRAMES]:
        x = load_frame(p)
        if x is not None:
            ref.append(ref_s.run(None, {name: x}))
            got.append(q_s.run(None, {name: x}))
    ok, means = cosine_gate(ref, got, min_cos)
    outs = [o.name for o in ref_s.get_outputs()]
    print("cosine per output:", ", ".join(f"{n}={m:.4f}" for n, m in zip(outs, means)), f"(need >= {min_cos})")
    if not ok:
        dst.unlink(missing_ok=True)
        print("accuracy gate FAILED: keeping the fp16 encoder", file=sys.stderr)
        return 3
    print(f"quantized encoder ready: {dst} ({dst.stat().st_size / 1e6:.1f} MB, {'16' if a16 else '8'}-bit activations)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
