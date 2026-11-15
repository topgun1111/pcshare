"""Android Part 10 reference: presets, group boxes, fallback chain, `apply_target(preset=...)`."""
import pytest

from tracker.config import Config
from tracker.offline import apply_target, make_doc, part_boxes
from tracker.parts import PRESETS, group_box, preset_boxes


def _doc(conf=0.9):
    row = [v for j in range(17) for v in (0.30 + 0.01 * j, 0.20 + 0.04 * j, conf)]
    s = [{"t": i * 0.1, "d": [[1, 0.25, 0.1, 0.5, 0.9, 0.9]], "kp": {"1": row}} for i in range(5)]
    return make_doc("v.mp4", (1000, 500), 30.0, 0.5, 10.0, s, pose=True)


def test_single_joint_group_matches_legacy_joint_box():
    cfg, doc = Config(), _doc()
    legacy = part_boxes(doc, {1}, 15, cfg)[0]
    row = doc["samples"][0]["kp"]["1"]
    box, q = group_box((15,), row, 1000, 500, 0.8 * 500, cfg.kp_min_conf, cfg.kp_box, margin=0.0)
    assert box == pytest.approx(legacy) and q == pytest.approx(0.9)


def test_group_is_union_with_margin_and_min_side():
    cfg, doc = Config(), _doc()
    b = preset_boxes(doc, {1}, "left_leg", cfg)[0]
    row = doc["samples"][0]["kp"]["1"]
    xs = [row[3 * j] * 1000 for j in (11, 13, 15)]
    ys = [row[3 * j + 1] * 500 for j in (11, 13, 15)]
    assert b[0] == pytest.approx((max(xs) + min(xs)) / 2 / 1000)
    assert b[3] == pytest.approx((max(ys) - min(ys) + 2 * 0.02 * 500) / 500)  # taller than kp_box minimum


def test_low_confidence_falls_back_to_person_slice_then_person_box():
    cfg, doc = Config(), _doc(conf=0.1)
    b = preset_boxes(doc, {1}, "head", cfg)[0]
    assert b[1] == pytest.approx(0.1 + 0.8 * 0.11) and b[2] == pytest.approx(0.25)
    p = preset_boxes(doc, {1}, "person", cfg)[0]
    assert p == pytest.approx([0.375, 0.5, 0.25, 0.8])


def test_apply_target_preset_and_validation():
    cfg, doc = Config(), _doc()
    apply_target(doc, 1, cfg, link=False, preset="feet")
    assert doc["target"] == {"id": 1, "preset": "feet"} and doc["views"]
    with pytest.raises(ValueError):
        apply_target(doc, 1, cfg, link=False, preset="elbow")
    with pytest.raises(ValueError):
        apply_target(doc, 1, cfg, link=False, part=15, preset="feet")
    assert len(PRESETS) == 12
