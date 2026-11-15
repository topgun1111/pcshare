"""Android golden fixtures: Python reference → android/core/src/test/resources/golden/*.json.

Kotlin tests (`core`) must match these. `python scripts/gen_golden.py` rewrites them;
`tests/test_golden.py` fails when the committed files are stale.
"""
from __future__ import annotations

import json
import random
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tracker.bytetrack import ByteTracker, KalmanBox, linear_assignment  # noqa: E402
from tracker.config import Config  # noqa: E402
from tracker.cuts import CutDetector  # noqa: E402
from tracker.detector import LetterboxInfo, letterbox  # noqa: E402
from tracker.export import crop_rect  # noqa: E402
from tracker.geometry import BBox  # noqa: E402
from tracker.offline import (  # noqa: E402
    apply_target,
    build_part_views,
    build_views,
    make_doc,
    run_detection,
    save_views,
    view_at,
)
from tracker.offscreen import propagate_boxes, smooth_keypoints  # noqa: E402
from tracker.parts import preset_boxes  # noqa: E402
from tracker.pipeline import cam_row  # noqa: E402
from tracker.pose import Pose, decode_yolo_pose, nearest_keypoint  # noqa: E402
from tracker.scan import Selection, resolve_selection  # noqa: E402
from tracker.smooth import BoxFilter, Kalman1D, OneEuro  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "golden"

# Config keys shared with Android (same names/defaults, handover-android.md §5).
SHARED_KEYS = (
    "det_conf det_iou det_size live_det_conf offline_fps offline_interp_s offline_alpha "
    "target_fill min_zoom max_zoom alpha_pos alpha_zoom dead_zone_pos dead_zone_zoom "
    "lost_hold_s lost_zoomout_s kf_accel kf_meas gmc gmc_side cut_detect cut_hist cut_diff "
    "pick_tol pose_conf kp_min_conf kp_tol kp_box kp_hold_s kp_filter kp_world kp_infer"
).split()

VIEWS = [[0.0, 0.0, 0.0, 0.0], [1.0, 1.0, 0.1, -0.05], [2.5, 2.0, -0.2, 0.15], [4.0, 0.5, 0.0, 0.0]]


def _view_at() -> dict:
    ts = [-1.0, 0.0, 0.25, 1.0, 1.7, 2.5, 3.0, 4.0, 9.0]
    return {"views": VIEWS, "cases": [{"t": t, "v": list(view_at(VIEWS, t))} for t in ts],
            "empty": list(view_at([], 1.0))}


def _crop_rect() -> dict:
    views = [(0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (1.0, 0.4, -0.4), (2.0, -0.3, 0.2),
             (-1.0, 0.2, 0.2), (0.5, 0.123, -0.077), (3.0, 0.0, 0.0), (0.01, 0.5, 0.5)]
    sizes = [(640, 360), (1920, 1080), (1281, 721)]
    return {"cases": [{"view": list(v), "w": w, "h": h, "rect": list(crop_rect(v, w, h))}
                      for v in views for w, h in sizes]}


def _one_euro() -> dict:
    xs = [0.50, 0.51, 0.49, 0.50, 0.60, 0.75, 0.80, 0.80, 0.79, 0.81]
    ts = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.5, 0.7, 0.8, 1.0]  # t=0.5 repeats (dt = 0 → hold)
    out = []
    for kw in ({}, {"min_cutoff": 0.5, "beta": 5.0, "d_cutoff": 2.0}):
        f = OneEuro(**kw)
        out.append({"params": kw, "t": ts, "x": xs, "y": [f.update(x, t) for x, t in zip(xs, ts)]})
    return {"runs": out}


def _config() -> dict:
    d = asdict(Config())
    return {k: d[k] for k in SHARED_KEYS}


def _f(a) -> list:
    return [float(v) for v in a]


class _Det:
    """Minimal stand-in for tracker.detector.Detection (ByteTracker reads bbox + score only)."""

    def __init__(self, box, score):
        self.bbox, self.score = BBox(*box), score


def _assignment() -> dict:
    rng = random.Random(11)
    cases = []
    for n, m in [(3, 3), (2, 5), (5, 2), (4, 4), (1, 3), (6, 6), (0, 3)]:
        c = [[round(rng.uniform(0, 1), 6) for _ in range(m)] for _ in range(n)]
        pairs = linear_assignment(np.array(c, float).reshape(n, m))
        cases.append({"cost": c, "n": n, "m": m, "pairs": sorted([i, j] for i, j in pairs)})
    return {"cases": cases}


def _kalman_box() -> dict:
    kb = KalmanBox(BBox(100, 50, 140, 150))
    ops = [{"op": "predict"}, {"op": "update", "box": [103, 52, 144, 151]}, {"op": "predict"},
           {"op": "predict"}, {"op": "update", "box": [110, 55, 150, 158]},
           {"op": "compensate", "m": [[1.02, 0.01, 5.0], [-0.01, 1.02, -3.0], [0, 0, 1]]},
           {"op": "predict"}, {"op": "update", "box": [120, 60, 163, 165]}]
    out = []
    for o in ops:
        if o["op"] == "predict":
            kb.predict()
        elif o["op"] == "update":
            kb.update(BBox(*o["box"]))
        else:
            kb.compensate(np.array(o["m"], float))
        out.append({"x": _f(kb.x), "p": _f(np.diag(kb.P))})
    return {"init": [100, 50, 140, 150], "ops": ops, "out": out}


def _kalman_1d() -> dict:
    steps = [(0.1, 1.0), (0.1, 1.2), (0.0, 1.3), (0.2, 1.9), (0.1, 2.0), (-0.1, 5.0), (0.1, 2.4)]
    k = Kalman1D(1.0, 0.01, 0.5, 0.5)
    out = []
    for dt, z in steps:
        k.predict(dt)
        k.update(z)
        out.append(_f([k.p, k.v, k.p00, k.p01, k.p11]))
    return {"args": [1.0, 0.01, 0.5, 0.5], "steps": [list(s) for s in steps], "out": out}


def _box_filter() -> dict:
    ts = [0.0, 0.1, 0.2, 0.3, 0.2, 0.4, 2.0, 2.1, 2.2]  # 0.2 = backward jump, 2.0 = gap > max_gap
    boxes = [[0.40 + 0.02 * i, 0.30, 0.50 + 0.02 * i, 0.60] for i in range(len(ts))]
    runs = []
    for kw in ({}, {"accel": 1.0, "meas": 0.02, "size_ratio": 0.5, "max_gap": 0.25}):
        f = BoxFilter(**kw)
        rows = []
        for t, b in zip(ts, boxes, strict=True):
            fb = f.update(BBox(*b), t)
            rows.append({"box": _f([fb.x1, fb.y1, fb.x2, fb.y2]), "vel": _f(f.velocity),
                         "look": _f([getattr(f.lookahead(0.15, 0.15), a)
                                     for a in ("x1", "y1", "x2", "y2")])})
        runs.append({"params": kw, "rows": rows})
    return {"t": ts, "boxes": boxes, "runs": runs}


def _scenario() -> list[dict]:
    rng = random.Random(5)
    # (x0, y0, vx, vy, w, h, first, last, score)
    objs = [(50, 100, 4, 0, 40, 100, 0, 44, 0.9), (400, 120, -4, 0, 42, 98, 0, 44, 0.85),
            (200, 300, 1, -1, 36, 90, 10, 44, 0.8), (600, 50, 0, 3, 30, 80, 0, 20, 0.95)]
    frames = []
    for i in range(45):
        dets = []
        for k, (x0, y0, vx, vy, w, h, a, b, sc) in enumerate(objs):
            if not a <= i <= b or (k == 1 and 25 <= i <= 28):  # object 1 vanishes 4 frames
                continue
            s = 0.3 if (k == 1 and 15 <= i <= 19) else sc  # object 1 low score (2nd stage)
            x, y = x0 + vx * i + rng.uniform(-1.5, 1.5), y0 + vy * i + rng.uniform(-1.5, 1.5)
            dets.append([round(v, 4) for v in (x, y, x + w, y + h)] + [s])
        dets.append([5.0, 5.0, 25.0, 45.0, 0.05])  # below low_thr: ignored
        m = None
        if 30 <= i <= 34:
            m = [[1, 0, -6.0], [0, 1, 2.0], [0, 0, 1]]
        elif i == 35:
            m = [[1.02, 0.01, 4.0], [-0.01, 1.02, -3.0], [0, 0, 1]]
        frames.append({"dets": dets, "motion": m, "clear": i == 38})
    return frames


def _bytetrack() -> dict:
    frames = _scenario()
    runs = []
    for kw in ({}, {"min_hits": 2, "max_lost": 4, "new_thr": 0.6, "second_iou": 0.4}):
        tr = ByteTracker(**kw)
        tr.set_next_id(5)
        out = []
        for f in frames:
            if f["clear"]:
                tr.clear()
            m = None if f["motion"] is None else np.array(f["motion"], float)
            res = tr.update([_Det(d[:4], d[4]) for d in f["dets"]], m)
            out.append({"tracks": [[t.id, *_f([t.bbox.x1, t.bbox.y1, t.bbox.x2, t.bbox.y2]),
                                    t.score, t.age, t.lost] for t in res],
                        "lost": [t.id for t in tr.lost_tracks], "n_boxes": len(tr.boxes)})
        runs.append({"params": kw, "next_id": 5, "out": out})
    return {"frames": frames, "runs": runs}


def _letterbox() -> dict:
    cases = []
    for (h, w), size in [((720, 1280), (640, 640)), ((1080, 1920), (640, 640)),
                         ((640, 640), (640, 640)), ((480, 360), (640, 640)),
                         ((1000, 333), (640, 640)), ((3, 1000), (640, 640)),
                         ((720, 1280), (384, 640)), ((721, 1281), (640, 640))]:
        img, info = letterbox(np.zeros((h, w, 3), np.uint8), size)
        r = min(size[0] / h, size[1] / w)
        cases.append({"h": h, "w": w, "size": list(size), "scale": info.scale,
                      "pad_x": info.pad_x, "pad_y": info.pad_y,
                      "nw": max(1, round(w * r)), "nh": max(1, round(h * r))})
    return {"cases": cases}


def _pose_tensor(info: LetterboxInfo) -> np.ndarray:
    """Synthetic YOLOv8-pose output [56, N] in letterbox space (float32, 3-decimal values)."""
    rng = random.Random(21)
    rows = []

    def person(cx, cy, w, h, score):  # letterbox-space box + a plausible skeleton
        kp = []
        for k in range(17):
            fx = (-0.15 if k % 2 else 0.15) if k < 11 else (-0.1 if k % 2 else 0.1)
            fy = -0.4 + 0.8 * k / 16
            kp += [cx + fx * w + rng.uniform(-2, 2), cy + fy * h, rng.uniform(0.05, 0.99)]
        return [cx, cy, w, h, score] + kp

    rows += [person(300, 300, 120, 300, 0.92), person(304, 298, 118, 296, 0.80),  # NMS pair
             person(520, 330, 100, 260, 0.75), person(100, 360, 90, 240, 0.60),
             person(600, 200, 80, 220, 0.40), person(400, 120, 60, 160, 0.30),
             person(330, 400, 0.4, 0.4, 0.99),  # degenerate (<1 px after mapping): dropped
             person(20, 330, 150, 250, 0.55)]  # crosses the left letterbox edge: clipped
    for _ in range(52):  # background: below any threshold
        rows.append(person(rng.uniform(0, 640), rng.uniform(80, 560), rng.uniform(20, 80),
                           rng.uniform(40, 200), rng.uniform(0.0, 0.2)))
    arr = np.round(np.array(rows, np.float64), 3).astype(np.float32)  # [N, 56]
    return arr.T.copy()


def _pose() -> dict:
    img, info = letterbox(np.zeros((720, 1280, 3), np.uint8), (640, 640))
    fw, fh = 1280, 720
    t = _pose_tensor(info)  # [56, N]
    cases, poses_for_pick = [], None
    for kw in ({}, {"conf_thres": 0.5}, {"conf_thres": 0.1, "iou_thres": 0.9},
               {"conf_thres": 0.1, "max_det": 2}):
        poses = decode_yolo_pose(t[None], info, fw, fh, **kw)
        cases.append({"params": kw, "poses": [
            {"box": _f([p.bbox.x1, p.bbox.y1, p.bbox.x2, p.bbox.y2]), "score": p.score,
             "kpts": _f(p.kpts.reshape(-1))} for p in poses]})
        if not kw:
            poses_for_pick = poses
    k = next(kp for kp in poses_for_pick[0].kpts[5:] if kp[2] >= 0.35)
    picks = []
    queries = [(float(k[0]) + 1.0, float(k[1]) + 1.0, {}), (0, 0, {}), (300, 300, {}),
               (640, 300, {}), (640, 300, {"tol": 1.0}),
               (640, 300, {"tol": 1.0, "min_conf": 0.9}), (900, 400, {"tol": 0.3})]
    for px, py, kw in queries:
        r = nearest_keypoint(poses_for_pick, px, py, **kw)
        picks.append({"x": px, "y": py, "params": kw, "result": None if r is None else list(r)})
    return {"info": {"scale": info.scale, "pad_x": info.pad_x, "pad_y": info.pad_y},
            "frame": [fw, fh], "dim": list(t.shape), "data": _f(t.reshape(-1)),
            "cases": cases, "picks": picks}


def _cuts() -> dict:
    """Cut detector on small synthetic BGR frames.

    Width 32 keeps the fixture small; the frames are already thumbnail-sized.
    """
    rng = np.random.default_rng(7)
    w, h = 32, 18
    def chan(lo, hi):
        return rng.integers(lo, hi, (h, w))

    # BGR: dark blue, then bright yellow
    scene_a = np.stack([chan(150, 256), chan(0, 80), chan(0, 80)], axis=2).astype(np.uint8)
    scene_b = np.stack([chan(0, 60), chan(180, 256), chan(180, 256)], axis=2).astype(np.uint8)
    noise = rng.integers(-6, 7, scene_a.shape)
    noisy = np.clip(scene_a.astype(int) + noise, 0, 255).astype(np.uint8)
    frames = [scene_a, noisy, np.roll(scene_a, 3, axis=1), scene_b, scene_b, scene_a]
    cam_ok = [None, None, True, None, False, False]
    det = CutDetector(0.35, 0.18, 0.7, width=w)
    steps = []
    for f, ok in zip(frames, cam_ok):
        cut = det.step(f, ok)
        argb = [(255 << 24) | (int(p[2]) << 16) | (int(p[1]) << 8) | int(p[0])
                for p in f.reshape(-1, 3)]
        argb = [v - (1 << 32) if v >= (1 << 31) else v for v in argb]
        steps.append({"argb": argb, "cam_ok": ok, "cut": bool(cut),
                      "hist": round(det.last_hist, 6), "diff": round(det.last_diff, 6)})
    return {"w": w, "h": h, "hist_thr": 0.35, "diff_thr": 0.18, "relax": 0.7, "steps": steps}


def _scan() -> dict:
    """Part 9: `offline.run_detection` (real ByteTracker + `associate_poses` + `_kp_row`) on synthetic poses.

    The pipeline stub mirrors `Pipeline.step`: on a cut the tracks are cleared and no motion is applied. Poses
    below `live_det_conf` are ignored; poses between it and `pose_conf` are tracked but get no `kp`.
    """
    cfg = Config()
    w, h = 640, 360
    rng = random.Random(21)
    # (x0, y0, vx, vy, bw, bh, first, last, score)
    people = [(60, 80, 5, 0, 50, 140, 0, 29, 0.9), (400, 90, -4, 1, 48, 150, 0, 29, 0.8),
              (250, 60, 2, 2, 46, 130, 8, 29, 0.2)]
    steps = []
    for i in range(30):
        poses = []
        for k, (x0, y0, vx, vy, bw, bh, a, b, sc) in enumerate(people):
            if not a <= i <= b or (k == 1 and 14 <= i <= 16):  # person 1 vanishes 3 samples
                continue
            x = round(x0 + vx * i + rng.uniform(-1, 1), 3)
            y = round(y0 + vy * i + rng.uniform(-1, 1), 3)
            kp = []
            for j in range(17):
                conf = 0.2 if j in (15, 16) and i % 4 == 0 else 0.9  # ankles flicker below kp_min_conf
                kp += [round(x + bw * (0.2 + 0.035 * j), 3), round(y + bh * (0.05 + 0.055 * j), 3), conf]
            poses.append({"bbox": [x, y, x + bw, y + bh], "score": sc, "kpts": kp})
        poses.append({"bbox": [5.0, 5.0, 30.0, 60.0], "score": 0.05, "kpts": [0.0] * 51})  # ignored
        motion = None
        if 20 <= i <= 23:
            motion = [[1, 0, -5.0], [0, 1, 2.0], [0, 0, 1]]
        steps.append({"t": round(i * 0.1 + 0.0004 * i, 4), "poses": poses, "motion": motion, "cut": i == 24})

    def to_pose(d):
        return Pose(BBox(*d["bbox"]), d["score"], np.array(d["kpts"], float).reshape(17, 3))

    class _FakePipeline:
        def __init__(self):
            self.tracker, self.i, self.cut, self.cam = ByteTracker(low_thr=cfg.live_det_conf), 0, False, None

        def step(self, frame):
            st = steps[self.i]
            self.i += 1
            self.cut = bool(st["cut"])
            m = None if (self.cut or st["motion"] is None) else np.array(st["motion"], float)
            self.cam = None if m is None else cam_row(m, h)  # 10b: like Pipeline.step (motion ok)
            if self.cut:
                self.tracker.clear()
            dets = [_Det(p["bbox"], p["score"]) for p in st["poses"] if p["score"] >= cfg.live_det_conf]
            return self.tracker.update(dets, m)

    class _FakePose:
        def __init__(self):
            self.i = 0

        def estimate(self, frame):
            st = steps[self.i]
            self.i += 1
            return [to_pose(p) for p in st["poses"] if p["score"] >= cfg.pose_conf]

    frame = np.zeros((h, w, 3), np.uint8)
    samples, size = run_detection(((st["t"], frame) for st in steps), _FakePipeline(), pose=_FakePose())
    doc = make_doc("clip.mp4", size, 30.0, 3.0, 10.0, samples, pose=True)
    return {"w": w, "h": h, "steps": steps, "samples": samples, "cuts": doc["cuts"], "version": doc["version"]}


def _target_views() -> dict:
    """Part 10/10b: views for person / joint / preset targets (Python `offline` + `parts` + `offscreen`) on the scan
    fixture doc, variants where a person is gone for a while (hold -> zoom-out -> zoom-in; propagation with and
    without camera motion, cut), and tap / rectangle resolution. Cfgs `default`/`custom` pin the Part 10a
    behaviour (10b switches off); `b`/`b_custom` use the 10b defaults."""
    base = _scan()
    video = {"w": base["w"], "h": base["h"]}
    removed = {"gap": [6, 18, 2], "exit": [2, 18, 1], "exit2": [18, 24, 1]}  # [from, to), track id gone

    def without(frm, to, tid):
        smp = json.loads(json.dumps(base["samples"]))
        for q in smp[frm:to]:
            q["d"] = [d for d in q["d"] if d[0] != tid]
            q["kp"] = {k: v for k, v in (q.get("kp") or {}).items() if k != str(tid)}
            if not q["kp"]:
                q.pop("kp")
        return {"video": video, "samples": smp}

    docs = {"base": {"video": video, "samples": base["samples"]},
            **{name: without(*r) for name, r in removed.items()}}
    legacy = {"kp_filter": False, "kp_world": False, "kp_infer": False}  # Part 10a behaviour
    custom = {"target_fill": 0.8, "max_zoom": 3.5, "lost_hold_s": 0.5, "lost_zoomout_s": 1.0,
              "offline_alpha": 0.5, "kp_box": 0.15}
    cfgs = {"default": dict(legacy), "custom": {**custom, **legacy}, "b": {},
            "b_custom": {**custom, "kp_hold_s": 2.5, "kp_infer": True}}
    cases = []

    def add(doc, cfg, kind, tid, arg=None):
        c = Config(**cfgs[cfg])
        d = docs[doc]
        if kind == "person":
            views = build_views(d, tid, c)
        elif kind == "joint":
            views = build_part_views(d, tid, arg, c)
        else:
            views = apply_target({**d, "target": None, "views": []}, tid, c, link=False, preset=arg)["views"]
        cases.append({"doc": doc, "cfg": cfg, "kind": kind, "id": tid, "arg": arg, "views": views})

    for cfg in ("default", "custom"):
        add("base", cfg, "person", 1)
        add("gap", cfg, "person", 2)
        add("base", cfg, "joint", 2, 15)
        add("base", cfg, "preset", 2, "left_leg")
        add("gap", cfg, "preset", 2, "left_leg")
        add("base", cfg, "preset", 1, "head")
        add("base", cfg, "preset", 1, "feet")
        add("base", cfg, "preset", 2, "torso")
        add("gap", cfg, "preset", 2, "person")
        add("base", cfg, "preset", 1, "hands")
    for cfg in ("b", "b_custom"):  # 10b: per-joint One-Euro, ankle inference, off-screen propagation
        add("base", cfg, "preset", 1, "feet")
        add("base", cfg, "preset", 2, "left_leg")
        add("base", cfg, "preset", 1, "right_foot")
        add("base", cfg, "preset", 2, "hands")
        add("gap", cfg, "preset", 2, "left_leg")
        add("exit", cfg, "preset", 1, "left_foot")
        add("exit", cfg, "preset", 1, "legs")
        add("exit2", cfg, "preset", 1, "right_leg")
        add("exit", cfg, "preset", 1, "person")
    # finer-grained references (localise a failing view): smoothed rows and propagated boxes
    ids = [1, 2]
    smooth = {"doc": "base", "cfg": "b", "ids": ids,
              "rows": smooth_keypoints(docs["base"], set(ids), Config(**cfgs["b"]))}
    prop = []
    for dname, cname, tid, preset in (("exit", "b", 1, "left_foot"), ("exit", "b_custom", 1, "left_foot"),
                                      ("exit2", "b_custom", 1, "right_leg")):
        c = Config(**cfgs[cname])
        bx = preset_boxes(docs[dname], {tid}, preset, c)
        prop.append({"doc": dname, "cfg": cname, "id": tid, "preset": preset,
                     "in": {str(k): v for k, v in bx.items()},
                     "out": {str(k): v for k, v in propagate_boxes(docs[dname], bx, c).items()}})
    taps = []
    s3 = base["samples"][3]
    wr = s3["kp"]["1"]
    pts = [(s3["t"], wr[27], wr[28]), (s3["t"], wr[27] + 0.01, wr[28] - 0.01), (s3["t"], 0.02, 0.95),
           (s3["t"], s3["d"][1][1] + 0.002, s3["d"][1][2] + 0.002), (base["samples"][20]["t"], 0.5, 0.5)]
    for tt, nx, ny in pts:
        r = resolve_selection(docs["base"], Selection.point(tt, nx, ny), Config())
        taps.append({"t": tt, "x": nx, "y": ny, "result": None if r is None else list(r)})
    # rectangle selection (10b): mostly over one person, tiny around an ankle, straddling, empty, later sample
    p1, p2 = s3["d"][0], s3["d"][1]
    k2 = s3["kp"]["2"]
    rects = []
    for tt, (ax, ay, bx, by) in (
            (s3["t"], (p1[1] - 0.01, p1[2] - 0.01, p1[3] + 0.01, p1[4] + 0.01)),
            (s3["t"], (k2[45] - 0.01, k2[46] - 0.01, k2[45] + 0.01, k2[46] + 0.01)),
            (s3["t"], (p1[1] + 0.02, p1[2] + 0.1, p2[3] - 0.02, p2[2] + 0.4)),
            (s3["t"], (0.0, 0.0, 0.03, 0.03)),
            (s3["t"], (p2[1] + 0.001, p2[2] + 0.001, p2[1] + 0.004, p2[2] + 0.004)),
            (base["samples"][12]["t"], (0.0, 0.0, 1.0, 1.0))):
        r = resolve_selection(docs["base"], Selection.rect(tt, ax, ay, bx, by), Config())
        rects.append({"t": tt, "rect": [ax, ay, bx, by], "result": None if r is None else list(r)})
    return {"docs": {"removed": removed}, "cfgs": cfgs, "cases": cases, "taps": taps, "rects": rects,
            "smooth": smooth, "propagate": prop}


def _docs() -> dict:
    kp = [round(0.01 * i, 4) if j < 2 else 0.9 for i in range(17) for j in range(3)]
    samples = [
        {"t": 0.0, "d": [[1, 0.1, 0.2, 0.3, 0.8, 0.91]]},
        {"t": 0.1, "d": [[1, 0.11, 0.2, 0.31, 0.8, 0.9], [2, 0.5, 0.2, 0.6, 0.7, 0.4]],
         "kp": {"1": kp}, "cut": 1},
    ]
    doc = make_doc("clip.mp4", (1280, 720), 29.97, 12.5, 10.0, samples, pose=True)
    doc["target"] = {"id": 1, "part": 15}
    doc["views"] = [[0.0, 1.0, 0.1, 0.2], [0.1, 1.2, 0.12, 0.2]]
    v1 = make_doc("clip.mp4", (1280, 720), 29.97, 12.5, 10.0, samples[:1])
    v1["target"] = {"id": 1}
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "v.json"
        save_views(doc, p)
        views_doc = json.loads(p.read_text(encoding="utf-8"))
    return {"track_v2": doc, "track_v1": v1, "views": views_doc}


def build() -> dict[str, str]:
    parts = {"view_at": _view_at(), "crop_rect": _crop_rect(), "one_euro": _one_euro(),
             "config": _config(), "assignment": _assignment(), "kalman_box": _kalman_box(),
             "kalman_1d": _kalman_1d(), "box_filter": _box_filter(), "bytetrack": _bytetrack(),
             "letterbox": _letterbox(), "pose": _pose(), "cuts": _cuts(), "scan": _scan(), "target_views": _target_views(),
             **_docs()}
    return {f"{k}.json": json.dumps(v, indent=1, sort_keys=True) + "\n" for k, v in parts.items()}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, text in build().items():
        (OUT / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"{len(build())} fixtures → {OUT}")


if __name__ == "__main__":
    main()
