"""Parça 15: YOLOX (Apache-2.0) çözücüsü, model seçimi, lisans/paketleme denetimleri."""
import importlib.util
from pathlib import Path

import numpy as np
import pytest

import tracker
from tracker.config import Config
from tracker.detector import (
    FALLBACK_MODELS,
    Detector,
    LetterboxInfo,
    decode_yolox,
    detect_model_type,
    find_model,
    preprocess_yolox,
    yolox_grids,
)

ROOT = Path(__file__).resolve().parent.parent
SIZE = (640, 640)
INFO = LetterboxInfo(scale=1 / 3, pad_x=0, pad_y=140)  # 1920x1080 -> 640x640
FW, FH = 1920, 1080


def _load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def raw_output(rows, size=SIZE, nc=80):
    """rows: [(anchor_idx, cx, cy, w, h, obj, {cls: skor})] -> ham YOLOX çıktısı [1,N,5+nc].

    (cx, cy, w, h) letterbox pikselidir; ham biçime (ofset / log) çevrilir."""
    grid, stride = yolox_grids(size)
    out = np.zeros((grid.shape[0], 5 + nc), dtype=np.float32)
    for i, cx, cy, w, h, obj, sc in rows:
        out[i, 0:2] = (np.array([cx, cy]) / stride[i]) - grid[i]
        out[i, 2:4] = np.log(np.array([w, h]) / stride[i])
        out[i, 4] = obj
        for c, v in sc.items():
            out[i, 5 + c] = v
    return out[None]


def test_yolox_grid_counts_and_strides():
    grid, stride = yolox_grids(SIZE)
    assert grid.shape == (80 * 80 + 40 * 40 + 20 * 20, 2) == (8400, 2)
    assert stride[0, 0] == 8 and stride[6400, 0] == 16 and stride[-1, 0] == 32
    assert tuple(grid[1]) == (1.0, 0.0) and tuple(grid[80]) == (0.0, 1.0)  # (x, y)
    assert yolox_grids((416, 416))[0].shape[0] == 52 * 52 + 26 * 26 + 13 * 13


def test_preprocess_yolox_is_bgr_0_255_nchw():
    img = np.zeros((360, 640, 3), np.uint8)
    img[..., 0], img[..., 2] = 10, 200  # B=10, R=200
    x, info = preprocess_yolox(img, SIZE)
    assert x.shape == (1, 3, 640, 640) and x.dtype == np.float32
    cy = 320
    assert x[0, 0, cy, 320] == 10 and x[0, 2, cy, 320] == 200  # kanal sırası BGR, /255 yok
    assert info.scale == 1.0 and info.pad_y == 140


def test_decode_yolox_raw_maps_back_to_frame_pixels():
    cx, cy, w, h = 330.0, 330.0, 40.0, 120.0  # letterbox pikseli
    i = 5 * 80 + 41  # stride 8 ızgarasında bir hücre (sadece indeks; ofset ham değere gömülü)
    out = raw_output([(i, cx, cy, w, h, 0.9, {0: 0.8})])
    dets = decode_yolox(out, INFO, FW, FH, SIZE, conf_thres=0.5)
    assert len(dets) == 1
    d = dets[0]
    assert d.score == pytest.approx(0.72, abs=1e-5)  # obj * sınıf
    b = d.bbox
    assert (b.x1, b.y1, b.x2, b.y2) == pytest.approx(
        ((cx - w / 2) * 3, (cy - h / 2 - 140) * 3, (cx + w / 2) * 3, (cy + h / 2 - 140) * 3),
        abs=0.5,
    )


def test_decode_yolox_filters_class_threshold_and_nms():
    out = raw_output([
        (100, 200, 300, 40, 100, 0.9, {0: 0.9}),   # person, yüksek
        (101, 202, 301, 40, 100, 0.8, {0: 0.8}),   # aynı kişi (NMS elenir)
        (400, 500, 300, 40, 100, 0.9, {1: 0.95}),  # person değil
        (900, 100, 200, 40, 100, 0.3, {0: 0.5}),   # skor 0.15 < eşik
        (1200, 500, 200, 40, 100, 0.9, {0: 0.5}),  # ikinci kişi, skor 0.45
    ])
    dets = decode_yolox(out, INFO, FW, FH, SIZE, conf_thres=0.25, iou_thres=0.45)
    assert len(dets) == 2
    assert abs(dets[0].score - 0.81) < 1e-4 and abs(dets[1].score - 0.45) < 1e-4
    assert decode_yolox(out, INFO, FW, FH, SIZE, conf_thres=0.99) == []


def test_decode_yolox_already_decoded_and_shape_errors():
    n = yolox_grids(SIZE)[0].shape[0]
    out = np.zeros((1, n, 85), np.float32)
    out[0, 7, :4] = (330, 330, 40, 120)
    out[0, 7, 4], out[0, 7, 5] = 1.0, 0.9
    dets = decode_yolox(out, INFO, FW, FH, SIZE, 0.5, decoded=True)
    assert len(dets) == 1 and dets[0].bbox.x1 == pytest.approx((330 - 20) * 3, abs=0.5)
    with pytest.raises(ValueError, match="yolox_decoded"):  # N ızgarayla uyuşmuyor
        decode_yolox(np.zeros((1, 100, 85), np.float32) + 1, INFO, FW, FH, SIZE, 0.0)
    with pytest.raises(ValueError):
        decode_yolox(np.zeros((2, n, 85), np.float32), INFO, FW, FH, SIZE)
    with pytest.raises(ValueError):
        decode_yolox(np.zeros((1, n, 5), np.float32), INFO, FW, FH, SIZE)


class FakeInput:
    name, type, shape = "images", "tensor(float)", [1, 3, 640, 640]


class FakeSession:
    def __init__(self, out):
        self.out, self.last = out, None

    def get_inputs(self):
        return [FakeInput()]

    def get_providers(self):
        return ["CPUExecutionProvider"]

    def run(self, _names, feeds):
        self.last = feeds["images"]
        return [self.out]


def test_detector_yolox_end_to_end_with_fake_session():
    out = raw_output([(3000, 330, 330, 40, 120, 0.9, {0: 0.9})])
    sess = FakeSession(out)
    det = Detector("models/yolox_s.onnx", session=sess, conf_thres=0.3)
    assert det.model_type == "yolox"
    frame = np.full((FH, FW, 3), 50, np.uint8)
    dets = det.detect(frame)
    assert len(dets) == 1 and sess.last.max() > 1.0  # 0..255 ölçeği
    forced = Detector("x.onnx", session=FakeSession(out), model_type="yolox")
    assert forced.model_type == "yolox"
    assert Detector("m.onnx", session=FakeSession(out)).model_type == "yolov8"


def test_detect_model_type_and_config_defaults():
    assert detect_model_type("a/YOLOX_tiny.onnx") == "yolox"
    assert detect_model_type("yolov8n.onnx") == "yolov8"
    assert detect_model_type("yolov8n.onnx", "yolox") == "yolox"
    with pytest.raises(ValueError):
        detect_model_type("a.onnx", "ssd")
    cfg = Config()
    assert cfg.model_type == "auto" and cfg.yolox_decoded is False


def test_find_model_prefers_default_then_yolox_fallback(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    (tmp_path / "models").mkdir()
    assert find_model("models/yolov8n.onnx").name == "yolov8n.onnx"  # hiçbiri yok: varsayılan yol
    (tmp_path / "models" / "yolox_m.onnx").write_bytes(b"x")
    (tmp_path / "models" / "yolox_nano.onnx").write_bytes(b"x")
    assert find_model("models/yolov8n.onnx").name == "yolox_m.onnx"  # s yok → m
    (tmp_path / "models" / "yolov8n.onnx").write_bytes(b"x")
    assert find_model("models/yolov8n.onnx").name == "yolov8n.onnx"  # varsayılan varsa o
    assert find_model("models/ozel.onnx").name == "ozel.onnx"  # özel yol yedeğe düşmez
    assert [Path(m).stem for m in FALLBACK_MODELS][0] == "yolox_s"


def test_detector_missing_model_message_mentions_yolox(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    with pytest.raises(FileNotFoundError, match="yolox_s.onnx"):
        Detector(str(tmp_path / "yok.onnx"))


def test_verify_sha256_pins_and_reports(capsys):
    be = _load("build_embedded")
    import hashlib

    data = b"abc"
    good = hashlib.sha256(data).hexdigest()
    assert be.verify_sha256("f", data, None) == good  # sabitlenmemiş: yalnız yazdırır
    assert be.verify_sha256("f", data, good.upper()) == good
    assert good in capsys.readouterr().out
    with pytest.raises(SystemExit):
        be.verify_sha256("f", data, "0" * 64)


def test_version_files_agree_and_release_ready():
    assert (ROOT / "VERSION").read_text().strip() == tracker.__version__ == "1.2.0"


def test_notices_cover_every_bundled_component_and_model_license():
    n = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    for key in ("mpv", "GPL", "Python", "PSF", "NumPy", "BSD", "opencv", "Apache-2.0", "FFmpeg",
                "ONNX Runtime", "DirectML", "MIT", "YOLOX", "AGPL-3.0", "OSNet", "BUILD_INFO.txt",
                "kaynak"):
        assert key in n, key
    req = (ROOT / "requirements.txt").read_text()
    for pkg in ("numpy", "opencv-python-headless", "onnxruntime"):  # bildirimsiz bağımlılık olmasın
        assert pkg in req and pkg.split("-")[0].lower() in n.lower()
    iss = (ROOT / "installer.iss").read_text(encoding="utf-8")
    assert "Excludes" not in iss  # kişisel kullanım: YOLOX-S + pose modeli pakette
    assert "YOLOX-S" in n and "YOLOX_LICENSE.txt" in n  # pakette gelen model + Apache-2.0 metni


def test_package_ships_notices_and_workflow_checks_them(tmp_path, monkeypatch):
    pkg = _load("make_package")
    monkeypatch.setattr(pkg, "OUT", tmp_path / "TakipliOynat")
    assert pkg.main() == 0
    assert (tmp_path / "TakipliOynat" / "THIRD_PARTY_NOTICES.md").is_file()
    wf = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    for key in ("THIRD_PARTY_NOTICES.md", "BUILD_INFO.txt", "generate_release_notes"):
        assert key in wf, key
    fd = (ROOT / "scripts" / "fetch_deps.ps1").read_text(encoding="utf-8")
    assert "BUILD_INFO.txt" in fd and "SHA256" in fd and "$Tag" in fd
    m = (ROOT / "models" / "MODELLER.txt").read_text(encoding="ascii")
    assert "yolox_s.onnx" in m and "Apache-2.0" in m and "AGPL" in m


def test_every_module_has_no_ultralytics_import():
    for f in (ROOT / "tracker").glob("*.py"):
        src = f.read_text(encoding="utf-8")
        assert "import ultralytics" not in src and "from ultralytics" not in src, f.name
