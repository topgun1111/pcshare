import numpy as np

from tracker.detector import (
    Detection,
    Detector,
    LetterboxInfo,
    decode_yolo,
    letterbox,
    nms,
    preprocess,
    resolve_model_path,
    select_providers,
)

FW, FH = 1920, 1080
INFO = LetterboxInfo(scale=1 / 3, pad_x=0, pad_y=140)  # 1920x1080 -> 640x640


def to_lb(x1, y1, x2, y2, info=INFO):
    """Orijinal kare kutusu -> letterbox (cx, cy, w, h)."""
    s = info.scale
    return (
        (x1 + x2) / 2 * s + info.pad_x,
        (y1 + y2) / 2 * s + info.pad_y,
        (x2 - x1) * s,
        (y2 - y1) * s,
    )


def make_output(rows, n=200, nc=80, transposed=True):
    """rows: [(cx, cy, w, h, {cls: skor})] -> [1,4+nc,N] (veya [1,N,4+nc])."""
    out = np.zeros((n, 4 + nc), dtype=np.float32)
    for i, (cx, cy, w, h, sc) in enumerate(rows):
        out[i, :4] = (cx, cy, w, h)
        for c, v in sc.items():
            out[i, 4 + c] = v
    return out.T[None] if transposed else out[None]


def close(a, b, tol=1.5):
    return abs(a - b) <= tol


# --- letterbox / preprocess ---


def test_letterbox_geometry():
    frame = np.zeros((FH, FW, 3), dtype=np.uint8)
    img, info = letterbox(frame, (640, 640))
    assert img.shape == (640, 640, 3)
    assert info.pad_x == 0 and info.pad_y == 140
    assert abs(info.scale - 1 / 3) < 1e-9


def test_letterbox_pads_with_gray_and_keeps_content():
    frame = np.zeros((100, 200, 3), dtype=np.uint8)
    img, info = letterbox(frame, (200, 200))
    assert (info.pad_x, info.pad_y) == (0, 50)
    assert img[0, 0, 0] == 114 and img[199, 199, 2] == 114  # üst/alt bant gri
    assert img[100, 100, 0] == 0  # orta: içerik


def test_preprocess_shape_range_and_channel_order():
    frame = np.zeros((FH, FW, 3), dtype=np.uint8)
    frame[:, :, 0] = 255  # BGR: yalnızca mavi kanal
    x, _ = preprocess(frame, (640, 640))
    assert x.shape == (1, 3, 640, 640) and x.dtype == np.float32
    assert 0.0 <= x.min() and x.max() <= 1.0
    # RGB'ye çevrildiği için mavi 3. kanala (index 2) gitmeli
    assert x[0, 2, 320, 320] == 1.0 and x[0, 0, 320, 320] == 0.0


def test_preprocess_fp16():
    x, _ = preprocess(np.zeros((64, 64, 3), dtype=np.uint8), (64, 64), fp16=True)
    assert x.dtype == np.float16


# --- nms ---


def test_nms_suppresses_overlap_keeps_disjoint():
    boxes = np.array([[0, 0, 10, 10], [1, 1, 11, 11], [50, 50, 60, 60]], dtype=np.float32)
    scores = np.array([0.6, 0.9, 0.5], dtype=np.float32)
    assert nms(boxes, scores, 0.45) == [1, 2]


def test_nms_empty():
    assert nms(np.zeros((0, 4), dtype=np.float32), np.zeros(0, dtype=np.float32), 0.5) == []


# --- decode ---


def test_decode_maps_back_to_original_pixels():
    gt = (900, 400, 960, 600)  # 60x200 px oyuncu
    out = make_output([(*to_lb(*gt), {0: 0.9})])
    dets = decode_yolo(out, INFO, FW, FH)
    assert len(dets) == 1
    b = dets[0].bbox
    assert close(b.x1, gt[0]) and close(b.y1, gt[1]) and close(b.x2, gt[2]) and close(b.y2, gt[3])
    assert abs(dets[0].score - 0.9) < 1e-6 and dets[0].cls == 0


def test_decode_with_padding_on_x_axis():
    info = LetterboxInfo(scale=0.5, pad_x=100, pad_y=0)
    gt = (200, 100, 260, 300)
    out = make_output([(*to_lb(*gt, info), {0: 0.8})])
    b = decode_yolo(out, info, 800, 600)[0].bbox
    assert close(b.x1, 200) and close(b.x2, 260) and close(b.y1, 100) and close(b.y2, 300)


def test_decode_transposed_and_plain_layouts_agree():
    rows = [(*to_lb(100, 100, 160, 300), {0: 0.7}), (*to_lb(800, 500, 860, 700), {0: 0.6})]
    a = decode_yolo(make_output(rows, transposed=True), INFO, FW, FH)
    b = decode_yolo(make_output(rows, transposed=False), INFO, FW, FH)
    assert [d.bbox for d in a] == [d.bbox for d in b] and len(a) == 2


def test_decode_ignores_non_person_classes():
    rows = [
        (*to_lb(100, 100, 160, 300), {2: 0.95}),  # araba
        (*to_lb(800, 500, 860, 700), {0: 0.5}),
    ]
    dets = decode_yolo(make_output(rows), INFO, FW, FH)
    assert len(dets) == 1 and dets[0].score == np.float32(0.5)


def test_decode_conf_threshold():
    out = make_output([(*to_lb(100, 100, 160, 300), {0: 0.2})])
    assert decode_yolo(out, INFO, FW, FH, conf_thres=0.25) == []
    assert len(decode_yolo(out, INFO, FW, FH, conf_thres=0.1)) == 1


def test_decode_nms_merges_duplicates_and_sorts_by_score():
    rows = [
        (*to_lb(100, 100, 160, 300), {0: 0.6}),
        (*to_lb(102, 102, 162, 302), {0: 0.9}),  # aynı kişi
        (*to_lb(900, 400, 960, 600), {0: 0.7}),
    ]
    dets = decode_yolo(make_output(rows), INFO, FW, FH)
    assert [round(d.score, 1) for d in dets] == [0.9, 0.7]


def test_decode_clips_to_frame_and_drops_degenerate():
    rows = [
        (*to_lb(-50, -20, 40, 100), {0: 0.9}),  # sol-üst taşıyor
        (*to_lb(500, 500, 500.2, 500.2), {0: 0.9}),  # ~0 px
    ]
    dets = decode_yolo(make_output(rows), INFO, FW, FH)
    assert len(dets) == 1
    b = dets[0].bbox
    assert b.x1 == 0.0 and b.y1 == 0.0 and close(b.x2, 40) and close(b.y2, 100)


def test_decode_max_det():
    rows = [(*to_lb(i * 70, 100, i * 70 + 50, 300), {0: 0.5 + i / 100}) for i in range(10)]
    assert len(decode_yolo(make_output(rows), INFO, FW, FH, max_det=3)) == 3


def test_decode_rejects_bad_shapes():
    for bad in (np.zeros((2, 84, 10), dtype=np.float32), np.zeros(5, dtype=np.float32)):
        try:
            decode_yolo(bad, INFO, FW, FH)
        except ValueError:
            continue
        raise AssertionError("ValueError bekleniyordu")


def test_decode_fp16_output():
    out = make_output([(*to_lb(900, 400, 960, 600), {0: 0.9})]).astype(np.float16)
    assert len(decode_yolo(out, INFO, FW, FH)) == 1


# --- providers ---


def test_select_providers_auto_prefers_dml():
    avail = ["CUDAExecutionProvider", "DmlExecutionProvider", "CPUExecutionProvider"]
    assert select_providers(avail, "auto") == [
        "DmlExecutionProvider",
        "CUDAExecutionProvider",
        "CPUExecutionProvider",
    ]


def test_select_providers_fallbacks():
    assert select_providers(["CPUExecutionProvider"], "auto") == ["CPUExecutionProvider"]
    assert select_providers(["CPUExecutionProvider"], "dml") == ["CPUExecutionProvider"]
    assert select_providers(["DmlExecutionProvider", "CPUExecutionProvider"], "cpu") == [
        "CPUExecutionProvider"
    ]
    assert select_providers([], "cpu") == ["CPUExecutionProvider"]


def test_select_providers_unknown_device():
    try:
        select_providers(["CPUExecutionProvider"], "tpu")
    except ValueError:
        return
    raise AssertionError("ValueError bekleniyordu")


# --- Detector (sahte oturum) ---


class _Inp:
    def __init__(self, shape, type_="tensor(float)"):
        self.name = "images"
        self.shape = shape
        self.type = type_


class FakeSession:
    def __init__(self, output, shape=(1, 3, 640, 640), type_="tensor(float)"):
        self._out, self._inp, self.last = output, _Inp(list(shape), type_), None

    def get_inputs(self):
        return [self._inp]

    def run(self, names, feeds):
        self.last = feeds["images"]
        return [self._out]

    def get_providers(self):
        return ["CPUExecutionProvider"]


def test_detector_end_to_end_with_fake_session():
    gt = (900, 400, 960, 600)
    sess = FakeSession(make_output([(*to_lb(*gt), {0: 0.9})]))
    det = Detector(session=sess)
    frame = np.zeros((FH, FW, 3), dtype=np.uint8)
    dets = det.detect(frame)
    assert sess.last.shape == (1, 3, 640, 640) and sess.last.dtype == np.float32
    assert len(dets) == 1 and isinstance(dets[0], Detection)
    assert close(dets[0].bbox.x1, gt[0]) and close(dets[0].bbox.y2, gt[3])
    assert det.providers == ["CPUExecutionProvider"]


def test_detector_uses_static_model_input_size_and_fp16():
    sess = FakeSession(make_output([]), shape=(1, 3, 480, 800), type_="tensor(float16)")
    det = Detector(session=sess, input_size=640)
    assert det.size == (480, 800) and det.fp16
    assert det.detect(np.zeros((FH, FW, 3), dtype=np.uint8)) == []
    assert sess.last.shape == (1, 3, 480, 800) and sess.last.dtype == np.float16


def test_detector_dynamic_input_uses_default_size():
    sess = FakeSession(make_output([]), shape=("batch", 3, "h", "w"))
    assert Detector(session=sess, input_size=416).size == (416, 416)


def test_detector_rejects_non_color_frame():
    det = Detector(session=FakeSession(make_output([])))
    try:
        det.detect(np.zeros((100, 100), dtype=np.uint8))
    except ValueError:
        return
    raise AssertionError("ValueError bekleniyordu")


def test_detector_missing_model_message():
    try:
        Detector("models/yok-boyle-model.onnx")
    except FileNotFoundError as e:
        assert "Model bulunamadı" in str(e)
        return
    raise AssertionError("FileNotFoundError bekleniyordu")


def test_resolve_model_path_absolute_untouched(tmp_path):
    p = tmp_path / "m.onnx"
    assert resolve_model_path(p) == p
