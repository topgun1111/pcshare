"""Part 9 cross-check: a scan in the Android scan format (golden/scan.json) loads in the PC `offline` code."""
import json
from pathlib import Path

from tracker.offline import list_tracks, load_doc, make_doc, save_doc

GOLDEN = Path(__file__).resolve().parent.parent / "android" / "core" / "src" / "test" / "resources" / "golden"


def test_android_scan_shape_loads_on_pc(tmp_path):
    fx = json.loads((GOLDEN / "scan.json").read_text(encoding="utf-8"))
    doc = make_doc("clip.mp4", (fx["w"], fx["h"]), 30.0, 3.0, 10.0, fx["samples"], pose=True, sig="abc123")
    path = tmp_path / "clip.mp4.track.json"
    save_doc(doc, path)
    back = load_doc(path)
    assert back["version"] == 2 and back["cuts"] == fx["cuts"]
    tracks = list_tracks(back)
    assert {t["id"] for t in tracks} == {1, 2, 3, 4}  # cut at 2.41 s: IDs 3, 4 are new, never reused
    kp_rows = [row for s in back["samples"] for row in s.get("kp", {}).values()]
    assert kp_rows and all(len(r) == 51 for r in kp_rows)
