"""The five EdgeTAM graph names and the SamEngine API must agree between the export script, both Android flavors and CI."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRAPHS = ["sam-encoder", "sam-decoder", "sam-decoder-trk", "sam-memenc", "sam-memattn"]
SAM = (ROOT / "android/app/src/sam/kotlin/com/takipli/oynat/SamEngine.kt").read_text(encoding="utf-8")
POSE = (ROOT / "android/app/src/pose/kotlin/com/takipli/oynat/SamEngine.kt").read_text(encoding="utf-8")
EXPORT = (ROOT / "scripts/export_sam2.py").read_text(encoding="utf-8")


def test_graph_names_match_between_kotlin_and_export_script():
    kotlin = set(re.findall(r'"(sam-[a-z-]+)\.onnx"', SAM))
    assert kotlin == set(GRAPHS)
    for g in GRAPHS:
        assert f"{g}.onnx" in EXPORT


def test_onnx_tensor_names_used_by_kotlin_exist_in_the_export():
    for n in ("image_embed", "image_feat", "high_res_feats_0", "high_res_feats_1", "point_coords", "point_labels",
              "low_res_masks", "iou_predictions", "obj_ptrs", "object_score", "low_res_mask", "mem_feat", "mem_feats", "ptrs"):
        assert f'"{n}"' in SAM, n
        assert n in EXPORT, n


def test_pose_stub_has_the_same_public_api():
    for fn in ("hasModels", "hasMemory", "npuEnabled", "setNpuEnabled", "track", "bench"):
        assert re.search(rf"fun {fn}\(", SAM) and re.search(rf"fun {fn}\(", POSE), fn


def test_ci_exports_memory_graphs_and_gates_int8():
    y = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
    assert "--no-memory" in y and "quantize_edgetam.py" in y
    for g in GRAPHS[2:]:
        assert g in y


def test_decoder_slot_counts_match_the_kotlin_prompts():
    # sam-decoder: 3 prompt slots (box, box, point); sam-decoder-trk: 1 slot (label -1 = no prompt)
    assert "[2.0, 3.0, 1.0][:n]" in EXPORT and "((dec, True, 3), (dtr, False, 1))" in EXPORT
    assert "floatArrayOf(0f, 0f), floatArrayOf(-1f)" in SAM and "val c = FloatArray(6)" in SAM
