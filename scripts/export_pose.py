"""YOLO-pose ağırlığını ONNX'e çevirip hedef klasöre koyar (CI'da; kişisel kullanım, AGPL).

Kullanım: python scripts/export_pose.py dist/TakipliOynat/models [yolov8s-pose] [--fp16]
  --fp16: ayrıca `<ad>-fp16.onnx` yazar (iç hesap fp16, girdi/çıktı fp32; DirectML hızlanması için).
Gerekli: pip install ultralytics onnx [onnxconverter-common] (yalnız derleme makinesinde).
Örnek: ... models yolo11m-pose --fp16  (YOLO11-pose çıktısı da [1,56,N]; aynı çözücü)
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

MIN_BYTES = 5_000_000


def to_fp16(src: Path, dst: Path) -> None:
    import onnx
    from onnxconverter_common import float16

    model = onnx.load(str(src))
    onnx.save(float16.convert_float_to_float16(model, keep_io_types=True), str(dst))


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    target = Path(args[0] if args else "dist/TakipliOynat/models")
    name = args[1] if len(args) > 1 else "yolov8s-pose"
    from ultralytics import YOLO

    target.mkdir(parents=True, exist_ok=True)
    onnx_path = Path(YOLO(f"{name}.pt").export(format="onnx", imgsz=640, opset=12, simplify=False))
    if not onnx_path.is_file() or onnx_path.stat().st_size < MIN_BYTES:
        print(f"HATA: ONNX dışa aktarma başarısız/çok küçük: {onnx_path}", file=sys.stderr)
        return 1
    dst = target / f"{name}.onnx"
    shutil.copyfile(onnx_path, dst)
    print(f"Pose modeli hazır: {dst} ({dst.stat().st_size / 1e6:.1f} MB)")
    if "--fp16" in argv:
        d16 = target / f"{name}-fp16.onnx"
        to_fp16(dst, d16)
        if d16.stat().st_size < MIN_BYTES // 2:
            print(f"HATA: fp16 dönüşümü çok küçük: {d16}", file=sys.stderr)
            return 1
        print(f"fp16 hazır: {d16} ({d16.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
