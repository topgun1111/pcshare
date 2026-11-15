import importlib.util
from pathlib import Path

import numpy as np

_spec = importlib.util.spec_from_file_location("quantize_edgetam", Path(__file__).resolve().parents[1] / "scripts" / "quantize_edgetam.py")
q = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(q)


def test_normalize_matches_the_app():
    rgb = np.zeros((4, 4, 3), dtype=np.uint8)
    rgb[..., 0] = 255
    x = q.normalize_rgb(rgb)
    assert x.shape == (1, 3, 4, 4)
    assert np.allclose(x[0, 0], (1.0 - 0.485) / 0.229, atol=1e-6)
    assert np.allclose(x[0, 1], (0.0 - 0.456) / 0.224, atol=1e-6)


def test_cosine_gate_passes_and_fails():
    a = [[np.arange(1, 9, dtype=np.float32), np.ones(5, dtype=np.float32)]]
    same = [[v * 1.0 for v in a[0]]]
    ok, means = q.cosine_gate(a, same, 0.98)
    assert ok and all(abs(m - 1.0) < 1e-9 for m in means)
    bad = [[np.arange(8, 0, -1, dtype=np.float32), np.ones(5, dtype=np.float32)]]
    ok, means = q.cosine_gate(a, bad, 0.98)
    assert not ok and means[1] > 0.99 and means[0] < 0.98
