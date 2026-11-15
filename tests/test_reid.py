import json

import numpy as np
import pytest

from tracker.bytetrack import Track
from tracker.config import Config
from tracker.geometry import BBox
from tracker.live import LiveController
from tracker.offline import apply_target, build_views, make_doc, run_detection
from tracker.reid import (
    Embedder,
    Gallery,
    ReIdManager,
    TrackletBank,
    crop_ok,
    crop_person,
    l2norm,
    link_ids,
    preprocess_crop,
)

W, H = 200, 100
RED, BLUE, GREEN = (30, 30, 220), (220, 40, 30), (40, 200, 40)  # BGR


def _cfg(**kw):
    return Config(ipc_path="x", **kw)


def _frame(*items):
    """items: (x1, y1, x2, y2, bgr) kutuları boyanmış kare."""
    f = np.full((H, W, 3), 128, np.uint8)
    for x1, y1, x2, y2, c in items:
        f[y1:y2, x1:x2] = c
    return f


class ColorEmbedder:
    """Sahte Re-ID: kırpıntının ortalama rengi (L2-normalize)."""

    def __init__(self):
        self.calls = 0

    def embed(self, frame, boxes):
        self.calls += 1
        out = np.zeros((len(boxes), 3), np.float32)
        for i, b in enumerate(boxes):
            c = crop_person(frame, b)
            if c is not None:
                out[i] = c.reshape(-1, 3).mean(0)
        return l2norm(out)


def _tr(i, x1, y1, x2, y2, age=5):
    return Track(i, BBox(x1, y1, x2, y2), 0.9, age, 0)


def test_preprocess_crop_shape_and_norm():
    x = preprocess_crop(np.full((40, 20, 3), 255, np.uint8))
    assert x.shape == (3, 256, 128) and x.dtype == np.float32
    assert x[0, 0, 0] == pytest.approx((1 - 0.485) / 0.229, rel=1e-4)  # R kanalı ilk


def test_crop_person_bounds():
    f = _frame()
    assert crop_person(f, BBox(-5, -5, 10, 10)).shape == (10, 10, 3)
    assert crop_person(f, BBox(50, 50, 51, 80)) is None
    assert crop_person(f, BBox(300, 10, 320, 40)) is None


class FakeSession:
    def __init__(self, batch, dim=4):
        self.batch, self.dim, self.runs = batch, dim, []

        class _In:
            name, shape, type = "x", [batch, 3, 256, 128], "tensor(float)"

        self.inp = _In()

    def get_inputs(self):
        return [self.inp]

    def run(self, _o, feed):
        x = feed["x"]
        self.runs.append(x.shape[0])
        return [np.tile(np.arange(1, self.dim + 1, dtype=np.float32), (x.shape[0], 1))]


@pytest.mark.parametrize("batch", ["N", 1])
def test_embedder_batching_and_normalization(batch):
    sess = FakeSession(batch)
    e = Embedder(session=sess)
    out = e.embed(_frame(), [BBox(10, 10, 40, 80), BBox(300, 0, 310, 5), BBox(50, 10, 80, 90)])
    assert out.shape == (3, 4)
    assert np.linalg.norm(out[0]) == pytest.approx(1.0) and not out[1].any()  # kırpılamaz → 0
    assert sess.runs == ([2] if batch == "N" else [1, 1])
    assert Embedder(session=FakeSession("N")).embed(_frame(), []).shape == (0, 0)


def test_embedder_flattens_4d_output():
    class S4(FakeSession):
        def run(self, _o, feed):
            n = feed["x"].shape[0]
            return [np.ones((n, 6, 1, 1), np.float32)]

    out = Embedder(session=S4("N")).embed(_frame(), [BBox(10, 10, 40, 80)])
    assert out.shape == (1, 6) and np.linalg.norm(out[0]) == pytest.approx(1.0)


def test_embedder_rejects_fixed_batch_gt1_and_missing_model(tmp_path):
    with pytest.raises(ValueError):
        Embedder(session=FakeSession(4))
    with pytest.raises(FileNotFoundError):
        Embedder(tmp_path / "yok.onnx")


def test_gallery_dedupe_ema_cap_score():
    g = Gallery(size=3)
    a, b = l2norm(np.array([1.0, 0, 0])), l2norm(np.array([0, 1.0, 0]))
    g.add(a)
    g.add(l2norm(np.array([1.0, 0.05, 0])))  # çok benzer → birleşir
    assert len(g) == 1
    g.add(b)
    assert len(g) == 2 and g.score(b) == pytest.approx(1.0) and g.score(-a) <= 0
    for v in ([0, 0, 1.0], [-1.0, 0, 0], [0, -1.0, 0]):
        g.add(l2norm(np.array(v)))
    assert len(g) == 3  # sınır: en eskisi atılır
    assert Gallery().score(a) == -1.0


def test_crop_ok_gates():
    a = _tr(1, 20, 40, 40, 60)
    assert crop_ok(a, [a], W, H, 0.08, 0.2)
    assert not crop_ok(_tr(1, 20, 20, 40, 24), [], W, H, 0.08, 0.2)  # çok küçük
    assert not crop_ok(_tr(1, 0, 40, 20, 60), [], W, H, 0.08, 0.2)  # kenarda kesik
    assert not crop_ok(a, [a, _tr(2, 22, 22, 42, 72)], W, H, 0.08, 0.2)  # örtüşüyor


def _mgr(**kw):
    return ReIdManager(ColorEmbedder(), _cfg(**kw))


def test_manager_match_threshold_margin_and_rate_limit():
    m = _mgr()
    red = _tr(1, 20, 40, 40, 60)
    f = _frame((20, 40, 40, 60, RED), (100, 40, 120, 60, RED), (150, 40, 170, 60, BLUE))
    m.observe(f, 0.0, red, [])
    assert len(m.gallery) == 1
    m.observe(f, 0.1, red, [])  # hız sınırı: 0.3 sn dolmadı
    assert m.emb.calls == 1
    cr, cb = _tr(5, 100, 40, 120, 60), _tr(6, 150, 40, 170, 60)
    all_t = [cr, cb]
    assert m.match(f, 1.0, [cb], all_t) is None  # mavi eşiği geçemez
    assert m.match(f, 1.1, [cr, cb], all_t) is None  # hız sınırı
    assert m.match(f, 1.5, [cr, cb], all_t) is cr  # kırmızı eşleşir
    # iki eş aday: marj yok → belirsiz, bağlama
    f2 = _frame((100, 40, 120, 60, RED), (150, 40, 170, 60, RED))
    assert m.match(f2, 2.5, [cr, cb], all_t) is None
    assert m.match(f, 4.0, [], all_t) is None
    m.reset()
    assert len(m.gallery) == 0 and m.match(f, 9.0, [cr], all_t) is None


# --- canlı denetleyici ---
class SeqPipe:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def step(self, frame):
        out = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return out

    def reset(self):
        pass


def _click(px, py):
    return {"event": "client-message",
            "args": ["track-click", str(px / W), str(py / H), str(W), str(H), "1.0"]}


def _live(seq, reid=True, **kw):
    cfg = _cfg(lost_hold_s=0.6, lost_zoomout_s=1.0, **kw)
    c = LiveController(SeqPipe(seq), cfg, reid=ReIdManager(ColorEmbedder(), cfg) if reid else None)
    c.handle_event(_click(30, 45))
    return c


F_OLD = _frame((20, 40, 40, 60, RED), (150, 40, 170, 60, BLUE))
F_NEW = _frame((100, 40, 120, 60, RED), (150, 40, 170, 60, BLUE))


def _script(n_vis=6, n_lost=3, n_new=8):
    t1, t2 = _tr(1, 20, 40, 40, 60), _tr(2, 150, 40, 170, 60)
    seq = [[t1, t2]] * n_vis + [[t2]] * n_lost
    seq += [[_tr(5, 100, 40, 120, 60), t2]] * n_new  # hedef ID 1 öldü, kırmızı yeni ID 5
    return seq


def test_live_reid_rebinds_target_to_new_id():
    c = _live(_script())
    outs = []
    for k in range(17):
        f = F_OLD if k < 6 else F_NEW
        outs.append(c.step(f, 0.1 * (k + 1)))
    assert c.target_id == 5
    assert any(o.osd == "Hedef yeniden bulundu: ID 5" for o in outs)
    assert c._lost_t0 is None  # kayıp bitti, takip sürüyor


def test_live_without_reid_never_rebinds():
    c = _live(_script(), reid=False)
    for k in range(17):
        c.step(F_OLD if k < 6 else F_NEW, 0.1 * (k + 1))
    assert c.target_id == 1


def test_live_reid_ignores_wrong_look_and_old_ids():
    t1, t2 = _tr(1, 20, 40, 40, 60), _tr(2, 150, 40, 170, 60)
    f_green = _frame((100, 40, 120, 60, GREEN), (150, 40, 170, 60, BLUE))
    seq = [[t1, t2]] * 6 + [[t2]] * 2 + [[_tr(5, 100, 40, 120, 60), t2]] * 8
    c = _live(seq)
    for k in range(16):
        c.step(F_OLD if k < 6 else (F_OLD if k < 8 else f_green), 0.1 * (k + 1))
    assert c.target_id == 1  # yeşil farklı görünüm → bağlanmaz
    # eski (≤ mark) ID'ye sahip, benzer görünümlü başka kişi aday değil
    t3 = _tr(1, 20, 40, 40, 60)
    c2 = _live([[t3, t2]] * 6 + [[t2]] * 3 + [[t2, _tr(2, 100, 40, 120, 60)]] * 6)
    # (ID 2 zaten mark içinde; kırmızı görünse bile aday olmaz)
    for k in range(15):
        c2.step(F_OLD if k < 6 else F_NEW, 0.1 * (k + 1))
    assert c2.target_id == 1


def test_live_rebind_during_zoomout_is_smooth():
    c = _live(_script(n_vis=6, n_lost=12, n_new=6))
    prev, jump, rebound = None, None, False
    for k in range(24):
        before = c._view
        out = c.step(F_OLD if k < 6 else F_NEW, 0.1 * (k + 1))
        if out.osd and "yeniden" in out.osd:
            rebound, prev, jump = True, before, c._view
            break
    assert rebound and c.target_id == 5
    assert 0.0 < prev.zoom  # zoom-out henüz bitmemişti
    assert abs(jump.zoom - prev.zoom) < 0.3  # sıçrama yok (yumuşatıcı mevcut görünümden sürer)


def test_click_new_target_resets_gallery():
    c = _live(_script())
    for k in range(3):
        c.step(F_OLD, 0.1 * (k + 1))
    assert len(c.reid.gallery) >= 1
    c.handle_event({"event": "client-message", "args": ["track-release"]})
    assert len(c.reid.gallery) == 0


# --- offline ---
def _bank_doc(cfg):
    """3 iz: A (0..2 sn, kırmızı), B (4..6 sn, kırmızı; ID değişimi), C (4..6 sn, mavi)."""
    seq, frames = [], []
    for k in range(70):
        t = 0.1 * k
        tr = []
        if t < 2.0:
            tr.append(_tr(1, 20, 40, 40, 60))
        if 4.0 <= t < 6.0:
            tr.append(_tr(2, 100, 40, 120, 60))
        tr.append(_tr(3, 150, 40, 170, 60))
        seq.append(tr)
        items = [(150, 40, 170, 60, BLUE)]
        items += [(20, 40, 40, 60, RED)] if t < 2.0 else []
        items += [(100, 40, 120, 60, RED)] if 4.0 <= t < 6.0 else []
        frames.append((round(t, 2), _frame(*items)))
    bank = TrackletBank(ColorEmbedder(), cfg)
    samples, size = run_detection(iter(frames), SeqPipe(seq), bank=bank)
    return make_doc("v.mp4", size, 25.0, 7.0, 10.0, samples, emb=bank.result())


def test_bank_result_is_compact_ints_and_json_safe():
    cfg = _cfg()
    doc = _bank_doc(cfg)
    assert set(doc["emb"]) == {"1", "2", "3"}
    assert all(isinstance(x, int) for x in doc["emb"]["1"]["first"])
    json.dumps(doc)


def test_link_ids_chains_same_look_and_skips_ambiguous():
    cfg = _cfg(reid_link_gap_s=5.0)
    doc = _bank_doc(cfg)
    assert link_ids(doc, 1, cfg) == [2]  # kırmızı → kırmızı (mavi C zamanca çakışır/benzemez)
    assert link_ids(doc, 2, cfg) == [1]  # geriye de bağlanır
    assert link_ids(doc, 3, cfg) == []
    assert link_ids(doc, 99, cfg) == []
    assert link_ids(doc, 1, _cfg(reid_link_gap_s=1.0)) == []  # boşluk 2 sn > 1 sn
    assert link_ids({**doc, "emb": {}}, 1, cfg) == []


def test_apply_target_links_and_view_covers_both_ids():
    cfg = _cfg(reid_link_gap_s=5.0, lost_hold_s=0.3, lost_zoomout_s=0.5)
    doc = _bank_doc(cfg)
    plain = apply_target(json.loads(json.dumps(doc)), 1, cfg, link=False)
    linked = apply_target(json.loads(json.dumps(doc)), 1, cfg)
    assert linked["target"] == {"id": 1, "linked": [2]} and plain["target"] == {"id": 1}
    # 4–6 sn arasında bağlı sürümde hedef görünür (zoom>0 ve pan sağa), bağsızda 1x
    def zoom(d, t):
        return float(np.interp(t, [k[0] for k in d["views"]], [k[1] for k in d["views"]]))
    assert zoom(linked, 5.0) > 0.5 > zoom(plain, 5.0) + 0.4
    assert build_views(doc, {1, 2}, cfg) == linked["views"]


def test_multi_id_same_sample_prefers_higher_score():
    s = [{"t": 0.0, "d": [[1, 0.1, 0.4, 0.2, 0.6, 0.4], [2, 0.7, 0.4, 0.8, 0.6, 0.9]]},
         {"t": 0.1, "d": [[1, 0.1, 0.4, 0.2, 0.6, 0.4], [2, 0.7, 0.4, 0.8, 0.6, 0.9]]}]
    doc = make_doc("v", (W, H), 25.0, 1.0, 10.0, s)
    kfs = build_views(doc, {1, 2}, _cfg())
    assert kfs[0][2] < 0  # kutu x≈0.75 → pan = 0.5 - 0.75 < 0 (yüksek skorlu ID 2)


def test_cli_offline_view_no_link_flag(tmp_path, capsys):
    from tracker.main import main
    from tracker.offline import load_doc, save_doc

    p = tmp_path / "v.mp4.track.json"
    save_doc(_bank_doc(_cfg()), p)
    assert main(["offline-view", str(p), "--target-id", "1"]) == 0
    assert "Re-ID ile bağlanan" in capsys.readouterr().out
    assert load_doc(p)["target"]["linked"] == [2]
    assert main(["offline-view", str(p), "--target-id", "1", "--no-link"]) == 0
    assert load_doc(p)["target"] == {"id": 1}
