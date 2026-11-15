"""G2: tek model (poz) hem kişi dedektörü hem eklem kaynağı; pose-bench; export betiği."""
import json
import types
from pathlib import Path

import numpy as np

from tracker.bytetrack import ByteTracker
from tracker.config import Config
from tracker.main import build_parser, pose_bench_cmd
from tracker.offline import run_detection
from tracker.pipeline import Pipeline
from tracker.pose import PoseDetector, PoseEstimator

ROOT = Path(__file__).resolve().parent.parent


def _row(cx, cy, w, h, score):
    r = np.zeros(56, np.float32)
    r[:5] = (cx, cy, w, h, score)
    for k in range(17):
        r[5 + 3 * k: 8 + 3 * k] = (cx, cy - h / 2 + k * h / 17, 0.9)
    return r


class FakeSession:
    """Sabit [1,56,N] çıktı; kaç kez çalıştığını sayar. 640x640 girdi (kare 640x480 → pad_y 80)."""

    def __init__(self, rows):
        self.out = np.stack(rows, axis=1)[None].astype(np.float32)
        self.runs = 0

    def get_inputs(self):
        return [types.SimpleNamespace(name="images", shape=[1, 3, 640, 640], type="tensor(float)")]

    def get_providers(self):
        return ["CPUExecutionProvider"]

    def run(self, _names, _feed):
        self.runs += 1
        return [self.out]


def _det(rows, **kw):
    sess = FakeSession(rows)
    est = PoseEstimator(session=sess)
    return PoseDetector(estimator=est, **kw), sess


FRAME = np.zeros((480, 640, 3), np.uint8)
ROWS = [_row(200, 360, 100, 300, 0.9), _row(450, 360, 100, 300, 0.2)]  # güçlü + zayıf kişi


def test_detect_and_estimate_share_one_inference_per_frame():
    d, sess = _det(ROWS, det_conf=0.1, pose_conf=0.3)
    dets = d.detect(FRAME)
    assert len(dets) == 2 and dets[0].score >= 0.2 - 1e-6 and sess.runs == 1
    poses = d.estimate(FRAME)  # aynı kare nesnesi → önbellek, ek çıkarım yok
    assert sess.runs == 1 and d.runs == 1
    assert len(poses) == 1 and poses[0].score >= 0.3  # pose_conf süzer, detect süzmez
    d.estimate(FRAME.copy())  # farklı kare nesnesi → yeniden çıkarım
    assert sess.runs == 2
    assert d.model_type == "yolo-pose" and d.size == (640, 640) and d.providers


def test_pipeline_with_pose_detector_tracks_and_offline_pose_costs_one_run_per_frame():
    d, sess = _det(ROWS, det_conf=0.1, pose_conf=0.3)
    pipe = Pipeline(d, ByteTracker(low_thr=0.1))
    frames = ((i / 10, FRAME.copy()) for i in range(5))
    samples, size = run_detection(frames, pipe, pose=d)
    assert size == (640, 480) and len(samples) == 5
    assert sess.runs == 5  # ayrı dedektör+poz olsaydı 10
    assert all(s["d"] for s in samples) and any("kp" in s for s in samples)


def test_config_flag_and_cli_flags():
    assert Config().single_pose is False  # ölçülmediği için varsayılan kapalı
    p = build_parser()
    assert p.parse_args(["live", "--single-pose"]).single_pose is True
    assert p.parse_args(["offline", "v.mp4", "--single-pose"]).single_pose is True
    a = p.parse_args(["pose-bench", "k.mp4", "--model", "a.onnx", "--model", "b.onnx",
                      "--frames", "5"])
    assert a.model == ["a.onnx", "b.onnx"] and a.frames == 5


def test_pose_bench_reports_each_model(monkeypatch, capsys):
    import tracker.pose as pose_mod

    class FakeEst:
        def __init__(self, model, conf_thres=0.3, device="auto"):
            self.session, self.fp16, self.size = FakeSession(ROWS), "fp16" in model, (640, 640)

        def estimate(self, f):
            return [object()]

    monkeypatch.setattr(pose_mod, "PoseEstimator", FakeEst)
    import tracker.main as m

    monkeypatch.setattr(m, "_bench_frames", lambda src, n: [FRAME] * n)
    args = types.SimpleNamespace(source="x", model=["a-pose.onnx", "a-pose-fp16.onnx"],
                                 device=None, frames=4)
    assert pose_bench_cmd(Config(), args) == 0
    out = capsys.readouterr().out
    assert "4 kare, 640x480" in out and "a-pose.onnx" in out and "fp32 girdi" in out
    assert "a-pose-fp16.onnx" in out and "fp16 girdi" in out and "CPUExecutionProvider" in out


def test_g2_files_manifest_workflow_and_notices():
    man = json.loads((ROOT / "models" / "manifest.json").read_text(encoding="utf-8"))
    opt = {e["path"]: e for e in man["files"] if e.get("optional")}
    for n in ("models/yolo11m-pose.onnx", "models/yolo11m-pose-fp16.onnx"):
        assert n in opt and opt[n]["license"] == "AGPL-3.0"
    wf = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    assert "yolo11m-pose --fp16" in wf and wf.index("yolo11m-pose --fp16") < wf.index(
        "verify_models.py")
    ex = (ROOT / "scripts" / "export_pose.py").read_text(encoding="utf-8")
    assert "convert_float_to_float16" in ex and "keep_io_types=True" in ex
    n = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    assert "yolo11m-pose" in n
    m = (ROOT / "models" / "MODELLER.txt").read_text(encoding="ascii")
    assert "single_pose" in m and "pose-bench" in m
