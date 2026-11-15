"""Parça 12: takım rengi (team.py) ve forma numarası (number.py)."""
import json

import numpy as np
import pytest
from test_reid import (  # aynı sahte yardımcılar
    BLUE,
    GREEN,
    RED,
    SeqPipe,
    _bank_doc,
    _cfg,
    _frame,
    _tr,
)

from tracker.config import Config
from tracker.geometry import BBox
from tracker.live import LiveController
from tracker.number import NumberReader, NumberVoter
from tracker.offline import make_doc, run_detection
from tracker.reid import ReIdManager, link_ids
from tracker.team import TeamBank, TeamClassifier, assign, kmeans2, team_feature

WHITE = (240, 240, 240)
B_RED, B_BLUE = BBox(20, 40, 40, 60), BBox(150, 40, 170, 60)


def _tcfg(**kw):
    return Config(ipc_path="x", team_min_px=30, team_min_samples=6, reid_every_s=0.0, **kw)


def test_team_feature_separates_colors_and_ignores_grass():
    f = _frame((20, 40, 40, 60, RED), (150, 40, 170, 60, BLUE), (90, 40, 110, 60, WHITE))
    r, b, w = (team_feature(f, bb, 30) for bb in (B_RED, B_BLUE, BBox(90, 40, 110, 60)))
    assert r.shape == (15,) and np.linalg.norm(r) == pytest.approx(1.0)
    assert float(r @ b) < 0.3 and float(r @ w) < 0.3 and float(b @ w) < 0.3
    # çim yeşili tamamen maskelenir → geçerli piksel yok → None
    assert team_feature(_frame((20, 40, 40, 60, GREEN)), B_RED, 30) is None
    assert team_feature(f, BBox(0, 0, 1, 1), 30) is None  # kırpılamaz


def test_kmeans2_and_assign_margin():
    rng = np.random.default_rng(0)
    a = np.abs(rng.normal([1, 0, 0], 0.03, (20, 3)))
    b = np.abs(rng.normal([0, 1, 0], 0.03, (20, 3)))
    c = kmeans2(np.vstack([a, b]))
    assert c is not None
    ka, kb = assign(c, a[0], 0.15), assign(c, b[0], 0.15)
    assert ka is not None and kb is not None and ka != kb
    assert assign(c, (c[0] + c[1]) / 2, 0.15) is None  # tam ortada → belirsiz
    assert kmeans2(a) is None  # tek küme (merkezler yakın)
    assert kmeans2(a[:1]) is None


def test_team_classifier_fits_after_min_samples():
    cfg = _tcfg()
    tc = TeamClassifier(cfg)
    f = _frame((20, 40, 40, 60, RED), (150, 40, 170, 60, BLUE))
    fr, fb = team_feature(f, B_RED, 30), team_feature(f, B_BLUE, 30)
    assert tc.label(fr) is None  # model yok
    for _ in range(5):
        tc.add(fr)
        tc.add(fb)
    assert tc.centers is not None
    assert tc.label(fr) != tc.label(fb) and tc.label(None) is None


def _team_doc(cfg, two_teams=True):
    seq, frames = [], []
    for k in range(60):
        seq.append([_tr(1, 20, 40, 40, 60), _tr(2, 150, 40, 170, 60), _tr(3, 90, 40, 110, 60)])
        third = RED if two_teams else BLUE
        frames.append((0.1 * k, _frame((20, 40, 40, 60, RED), (150, 40, 170, 60, BLUE),
                                       (90, 40, 110, 60, third))))
    tb = TeamBank(cfg)
    samples, size = run_detection(iter(frames), SeqPipe(seq), team=tb)
    return make_doc("v.mp4", size, 25.0, 6.0, 10.0, samples, team=tb.result()), tb


def test_team_bank_labels_and_json_safe():
    doc, _ = _team_doc(_tcfg())
    lab = doc["team"]["labels"]
    assert lab["1"] == lab["3"] != lab["2"] and len(doc["team"]["centers"]) == 2
    json.dumps(doc)
    one, _ = _team_doc(_tcfg(), two_teams=False)  # iki iz aynı renk + RED → hâlâ 2 küme
    assert "team" in one
    assert TeamBank(_tcfg()).result() is None  # veri yok


def test_link_ids_skips_other_team():
    cfg = _cfg(reid_link_gap_s=5.0)
    doc = _bank_doc(cfg)
    assert link_ids(doc, 1, cfg) == [2]
    doc["team"] = {"centers": [], "labels": {"1": 0, "2": 1}}
    assert link_ids(doc, 1, cfg) == []  # farklı takım → bağlanmaz
    doc["team"]["labels"] = {"1": 0, "2": 0}
    assert link_ids(doc, 1, cfg) == [2]
    doc["team"]["labels"] = {"1": 0}  # 2'nin etiketi belirsiz → elenmez
    assert link_ids(doc, 1, cfg) == [2]


class ConstEmbedder:
    """Re-ID ayırt edemez: her kırpıntı aynı vektör (yalnız takım/numara ayırt eder)."""

    def embed(self, frame, boxes):
        return np.ones((len(boxes), 3), np.float32) / np.sqrt(3)


class FakeNumbers:
    """Kutunun x1'ine göre numara: 20 → 7, 60 → 12, diğer → None."""

    def read(self, frame, boxes):
        return [({20: (7, 0.9), 60: (12, 0.9)}.get(int(b.x1), (None, 0.0))) for b in boxes]


F_A = _frame((20, 40, 40, 60, RED), (150, 40, 170, 60, BLUE))
# yeni kişiler: ID 5 mavi (x=60), ID 6 kırmızı (x=100)
F_B = _frame((60, 40, 80, 60, BLUE), (100, 40, 120, 60, RED), (150, 40, 170, 60, BLUE))


def _scene(team=False, numbers=False):
    cfg = _tcfg(lost_hold_s=0.6, lost_zoomout_s=1.0, number_min_votes=3)
    t1, t2 = _tr(1, 20, 40, 40, 60), _tr(2, 150, 40, 170, 60)
    t5, t6 = _tr(5, 60, 40, 80, 60), _tr(6, 100, 40, 120, 60)
    seq = [[t1, t2]] * 6 + [[t2]] * 3 + [[t5, t6, t2]] * 8
    mgr = ReIdManager(ConstEmbedder(), cfg, team=TeamClassifier(cfg) if team else None,
                      numbers=FakeNumbers() if numbers else None)
    c = LiveController(SeqPipe(seq), cfg, reid=mgr)
    c.handle_event({"event": "client-message", "args": [
        "track-click", str(30 / 200), str(45 / 100), "200", "100", "1.0"]})
    for k in range(17):
        c.step(F_A if k < 9 else F_B, 0.1 * (k + 1))
    return c


def test_live_without_team_or_number_ambiguous_candidates_do_not_bind():
    assert _scene().target_id == 1  # 5 ve 6 eşit skor → marj yok → bağlanmaz


def test_live_team_filter_picks_same_team_candidate():
    assert _scene(team=True).target_id == 6  # mavi ID 5 elendi, kırmızı ID 6 kaldı


def test_live_number_filter_drops_mismatching_number():
    assert _scene(numbers=True).target_id == 6  # ID 5 numarası 12 ≠ 7; ID 6 bilinmiyor → kalır


def test_live_new_target_resets_team_feature_and_votes():
    c = _scene(team=True, numbers=True)
    assert c.reid._tfeat is not None
    c.reid.reset()
    assert c.reid._tfeat is None and c.reid.team.centers is not None  # model korunur
    assert c.reid._nvote.value(1, 0.0) is None


class _In:
    name, shape = "x", [1, 1, 32, 48]


class NumSession:
    def __init__(self, cls, strength=8.0, size=101):
        self.cls, self.strength, self.size = cls, strength, size

    def get_inputs(self):
        return [_In()]

    def run(self, _o, feed):
        assert feed["x"].shape == (1, 1, 32, 48) and feed["x"].dtype == np.float32
        lg = np.zeros((1, self.size), np.float32)
        lg[0, self.cls] = self.strength
        return [lg]


def test_number_reader_classes_confidence_and_errors(tmp_path):
    f = _frame((20, 40, 40, 60, RED))
    assert NumberReader(session=NumSession(7)).read(f, [B_RED])[0][0] == 7
    assert NumberReader(session=NumSession(100)).read(f, [B_RED])[0][0] is None  # "yok" sınıfı
    n, c = NumberReader(session=NumSession(7, 0.5), conf=0.9).read(f, [B_RED])[0]
    assert n is None and 0 < c < 0.9  # güven düşük
    assert NumberReader(session=NumSession(7)).read(f, [BBox(0, 0, 1, 1)]) == [(None, 0.0)]
    with pytest.raises(ValueError):
        NumberReader(session=NumSession(7, size=10)).read(f, [B_RED])
    with pytest.raises(FileNotFoundError):
        NumberReader(tmp_path / "yok.onnx")


def test_number_voter():
    v = NumberVoter()
    assert v.value(1, 0.5) is None
    for n, c in [(7, 0.9), (7, 0.8), (1, 0.7), (7, 0.9), (None, 0.2)]:
        v.add(n, c)
    assert v.value(3, 0.6) == 7
    assert v.value(4, 0.6) is None  # yetersiz oy
    v.add(1, 5.0)
    assert v.value(3, 0.6) is None  # pay düştü
    v.reset()
    assert v.value(1, 0.0) is None


def test_cli_flags_and_reid_requirement(capsys):
    from tracker.main import build_parser, main

    p = build_parser()
    assert p.parse_args(["live", "--reid", "--team", "--number"]).team
    assert p.parse_args(["offline", "v.mp4", "--team"]).team
    assert main(["live", "--team"]) == 2
    assert "--reid" in capsys.readouterr().out
