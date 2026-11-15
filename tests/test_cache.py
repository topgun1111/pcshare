"""A3: tarama önbelleği (imza, önbellekten yükleme, uyuşmayan kesinti/görünüm, yavaşlatma, CLI)."""
import json
import os

import numpy as np
import pytest

from tracker.bytetrack import ByteTracker, Track
from tracker.cache import cached_doc, file_sig, scan_sig, views_match
from tracker.config import Config
from tracker.geometry import BBox
from tracker.offline import make_doc, save_doc, save_views
from tracker.scan import Scan, Selection, finish_cached, run_scan

CFG = Config()


def _video(tmp_path, data=b"x" * 100, name="v.mp4"):
    p = tmp_path / name
    p.write_bytes(data)
    return str(p)


def test_signature_changes_with_size_mtime_fps_model(tmp_path):
    v = _video(tmp_path)
    m = tmp_path / "m.onnx"
    m.write_bytes(b"1")
    base = scan_sig(v, 10.0, m)
    assert scan_sig(v, 10.0, m) == base  # kararlı
    assert scan_sig(v, 5.0, m) != base  # fps
    assert scan_sig(v, 10.0, m, "reid") != base
    assert scan_sig(v, 10.0, None) != base  # model yok
    m.write_bytes(b"22")
    assert scan_sig(v, 10.0, m) != base  # model boyutu
    s0 = file_sig(v)
    os.utime(v, ns=(1_000_000_000, 1_000_000_000))
    assert file_sig(v) != s0  # mtime
    s1 = file_sig(v)
    (tmp_path / "v.mp4").write_bytes(b"y" * 101)
    os.utime(v, ns=(1_000_000_000, 1_000_000_000))
    assert file_sig(v) != s1  # boyut


def _doc(v, sig, pose=False, n=3):
    samples = [{"t": i / 10, "d": [[1, 0.1, 0.2, 0.2, 0.8, 0.9]]} for i in range(n)]
    return make_doc(v, (1000, 500), 25.0, 0.3, 10.0, samples, pose=pose, sig=sig)


def test_cached_doc_matches_only_same_sig_and_pose(tmp_path):
    v = _video(tmp_path)
    out = str(tmp_path / "v.mp4.track.json")
    sig = scan_sig(v, 10.0)
    assert cached_doc(out, sig) is None  # dosya yok
    save_doc(_doc(v, sig), out)
    assert cached_doc(out, sig)["sig"] == sig
    assert cached_doc(out, sig + "x") is None  # imza farklı
    assert cached_doc(out, sig, need_pose=True) is None  # eklemsiz (sürüm 1)
    save_doc(_doc(v, sig, pose=True), out)
    assert cached_doc(out, sig, need_pose=True) is not None
    (tmp_path / "v.mp4.track.json").write_text("{bozuk")
    assert cached_doc(out, sig) is None
    save_doc(_doc(v, None), out)  # imzasız eski dosya: önbellek sayılmaz
    assert cached_doc(out, sig) is None


def test_views_carry_vsig_and_mismatch_is_detected(tmp_path):
    v = _video(tmp_path)
    doc = _doc(v, scan_sig(v, 10.0))
    doc["views"] = [[0.0, 1.0, 0.0, 0.0]]
    vp = tmp_path / "v.mp4.views.json"
    save_views(doc, vp)
    assert json.loads(vp.read_text())["vsig"] == file_sig(v)
    assert views_match(vp, v)
    w = _video(tmp_path, b"z" * 50, "other.mp4")
    assert not views_match(vp, w)  # başka video
    vp.write_text(json.dumps({"views": [[0, 0, 0, 0]]}))  # eski dosya (vsig yok)
    assert views_match(vp, w)


def test_export_rejects_views_of_another_video(tmp_path):
    from tracker.export import load_views

    v = _video(tmp_path)
    doc = _doc(v, scan_sig(v, 10.0))
    doc["views"] = [[0.0, 1.0, 0.0, 0.0]]
    vp = tmp_path / "v.mp4.views.json"
    save_views(doc, vp)
    assert load_views(vp, video=v) == [[0.0, 1.0, 0.0, 0.0]]
    with pytest.raises(ValueError, match="başka bir videoya"):
        load_views(vp, video=_video(tmp_path, b"q" * 7, "o.mp4"))


def test_partial_needs_matching_sig(tmp_path):
    out = str(tmp_path / "v.track.json")
    a = Scan(out, CFG, None, (1000, 500), 10.0, 10, sig="AAA")
    a._partial_doc([{"t": 0.0, "d": []}])
    a.part.write_text(json.dumps(a._partial_doc([{"t": 0.0, "d": []}])))
    assert len(a.load_partial()) == 1
    assert Scan(out, CFG, None, (1000, 500), 10.0, 10, sig="BBB").load_partial() == []
    old = {"fps": 10.0, "size": [1000, 500], "samples": [{"t": 0.0, "d": []}]}
    a.part.write_text(json.dumps(old))  # imzasız eski kesinti noktası
    assert a.load_partial() == []
    assert len(Scan(out, CFG, None, (1000, 500), 10.0, 10).load_partial()) == 1  # imza istenmezse


def test_finish_cached_writes_done_and_resolves_target(tmp_path):
    v = _video(tmp_path)
    out = str(tmp_path / "v.mp4.track.json")
    sig = scan_sig(v, 10.0)
    doc = _doc(v, sig, n=30)
    sel = Selection.point(1.0, 0.15, 0.5)
    sc = finish_cached(out, doc, CFG, sel, 10.0, sig)
    assert sc.target == {"id": 1}
    prog = json.loads((tmp_path / "v.mp4.track.json.scan.json").read_text())
    assert prog["state"] == "done" and prog["pct"] == 100 and prog["target"] == {"id": 1}


class _Src:
    w, h, start = 1000, 500, 0.0

    def __iter__(self):
        for k in range(6):
            yield k / 10, np.zeros((500, 1000, 3), np.uint8)


class _Pipe:
    tracker = ByteTracker()

    def step(self, frame):
        return [Track(1, BBox(100, 100, 200, 400), 0.9, 1, 0)]


def test_scan_sleeps_only_while_slow_flag_exists(tmp_path, monkeypatch):
    import tracker.scan as scan_mod

    slept = []
    monkeypatch.setattr(scan_mod.time, "sleep", slept.append)
    out = str(tmp_path / "v.track.json")
    run_scan(_Src(), _Pipe(), CFG, out, None, 10.0, 6)
    assert slept == []  # bayrak yok
    (tmp_path / "v.track.json.scan.slow").write_text("1")
    cfg = Config(scan_throttle_s=0.2)
    run_scan(_Src(), _Pipe(), cfg, out, None, 10.0, 6)
    assert slept == [0.2] * 6
    assert not (tmp_path / "v.track.json.scan.slow").exists()  # bitince bayrak silinir
    (tmp_path / "v.track.json.scan.slow").write_text("1")
    slept.clear()
    run_scan(_Src(), _Pipe(), Config(scan_throttle_s=0.0), out, None, 10.0, 6)
    assert slept == []  # 0 = kapalı


def test_cli_rescan_flag():
    from tracker.main import build_parser

    assert build_parser().parse_args(["offline", "v.mp4", "--rescan"]).rescan is True
    assert build_parser().parse_args(["offline", "v.mp4"]).rescan is False


def test_offline_cli_uses_cache_without_models(tmp_path, capsys, monkeypatch):
    """Eşleşen track.json varsa model/dedektör yüklenmeden hedef çözülür, görünüm yazılır."""
    import cv2

    import tracker.detector as detector
    from tracker.main import main

    v = str(tmp_path / "c.avi")
    vw = cv2.VideoWriter(v, cv2.VideoWriter_fourcc(*"MJPG"), 10.0, (64, 48))
    for _ in range(30):
        vw.write(np.zeros((48, 64, 3), np.uint8))
    vw.release()
    out = f"{v}.track.json"
    sig = scan_sig(v, 10.0, detector.resolve_model_path(CFG.model_path))
    samples = [{"t": i / 10, "d": [[1, 0.1, 0.2, 0.2, 0.8, 0.9]]} for i in range(30)]
    save_doc(make_doc(v, (64, 48), 10.0, 3.0, 10.0, samples, sig=sig), out)

    def boom(*a, **k):
        raise AssertionError("dedektör yüklenmemeli")

    monkeypatch.setattr(detector, "Detector", boom)
    cfg = tmp_path / "cfg.json"
    cfg.write_text("{}")
    rc = main(["--config", str(cfg), "offline", v, "--fps", "10", "--select-point", "1.0",
               "0.15", "0.5"])
    assert rc == 0
    assert "önbellekten" in capsys.readouterr().out
    assert json.loads((tmp_path / "c.avi.views.json").read_text())["target"]["id"] == 1
    prog = json.loads((tmp_path / "c.avi.track.json.scan.json").read_text())
    assert prog["state"] == "done" and prog["target"] == {"id": 1}
    # --rescan önbelleği atlar → dedektör yüklenmeye çalışılır
    with pytest.raises(AssertionError, match="dedektör"):
        main(["--config", str(cfg), "offline", v, "--fps", "10", "--rescan"])
