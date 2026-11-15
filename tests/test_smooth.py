import random

import pytest

from tracker.geometry import BBox, View
from tracker.smooth import BoxFilter, DeadZone, Ema, Kalman1D, ViewSmoother


def test_ema_first_value_passthrough_then_blend():
    e = Ema(0.5)
    assert e.update(10) == 10
    assert e.update(20) == 15


def test_ema_invalid_alpha():
    with pytest.raises(ValueError):
        Ema(0)


def test_deadzone_holds_small_moves():
    d = DeadZone(0.1)
    d.update(0.0)
    assert d.update(0.05) == 0.0
    assert d.update(0.3) == pytest.approx(0.2)


def test_view_smoother_converges():
    s = ViewSmoother(alpha_pos=0.5, alpha_zoom=0.5, dead_zone_pos=0, dead_zone_zoom=0)
    s.update(View(0, 0, 0))
    out = View()
    for _ in range(30):
        out = s.update(View(1.0, 0.2, -0.2))
    assert out.zoom == pytest.approx(1.0, abs=1e-3)
    assert out.pan_x == pytest.approx(0.2, abs=1e-3)


def _box(cx, cy, w=0.1, h=0.2):
    return BBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)


def test_kalman1d_learns_constant_velocity():
    k = Kalman1D(0.1, meas=0.005, accel=0.3)
    for i in range(1, 31):
        k.predict(0.1)
        k.update(0.1 + 0.2 * 0.1 * i)
    assert k.v == pytest.approx(0.2, abs=0.02)
    assert k.p == pytest.approx(0.1 + 0.2 * 3.0, abs=0.01)


def test_kalman1d_stationary_has_zero_velocity():
    k = Kalman1D(0.5, meas=0.01, accel=0.3)
    for _ in range(30):
        k.predict(0.1)
        k.update(0.5)
    assert abs(k.v) < 0.01 and k.p == pytest.approx(0.5, abs=1e-3)


def test_kalman1d_reduces_noise():
    rnd = random.Random(1)
    k = Kalman1D(0.5, meas=0.02, accel=0.2)
    raw_err, flt_err = [], []
    for i in range(200):
        z = 0.5 + rnd.gauss(0, 0.02)
        k.predict(0.1)
        k.update(z)
        if i > 20:
            raw_err.append((z - 0.5) ** 2)
            flt_err.append((k.p - 0.5) ** 2)
    assert sum(flt_err) < 0.5 * sum(raw_err)


def test_kalman1d_predict_nonpositive_dt_is_noop():
    k = Kalman1D(0.3, meas=0.01, accel=0.3)
    k.predict(0.0)
    k.predict(-1.0)
    assert k.p == 0.3 and k.p00 == pytest.approx(0.01**2)


def test_box_filter_first_update_passthrough_and_lookahead_zero_speed():
    f = BoxFilter()
    out = f.update(_box(0.4, 0.5), 1.0)
    assert out.cx == pytest.approx(0.4) and out.w == pytest.approx(0.1)
    la = f.lookahead(0.3, 0.15)
    assert la.cx == pytest.approx(0.4) and la.cy == pytest.approx(0.5)


def test_box_filter_lookahead_leads_motion_and_clamps():
    f = BoxFilter(accel=0.5, meas=0.005)
    for i in range(30):  # sağa 0.2/sn, aşağı yok
        f.update(_box(0.2 + 0.02 * i, 0.5), i * 0.1)
    vx, vy = f.velocity
    assert vx == pytest.approx(0.2, abs=0.03) and abs(vy) < 0.02
    plain = f.lookahead(0.0, 0.15)
    lead = f.lookahead(0.5, 0.15)
    assert lead.cx - plain.cx == pytest.approx(vx * 0.5, abs=1e-6)
    assert lead.cy == pytest.approx(plain.cy)
    assert f.lookahead(10.0, 0.05).cx - plain.cx == pytest.approx(0.05)  # üst sınır
    assert lead.w == pytest.approx(plain.w)  # boyut öteleme almaz


def test_box_filter_restart_on_gap_and_backward_time():
    f = BoxFilter(max_gap=1.0)
    for i in range(10):
        f.update(_box(0.2 + 0.03 * i, 0.5), i * 0.1)
    assert f.velocity[0] > 0.1
    out = f.update(_box(0.9, 0.5), 5.0)  # uzun boşluk: ölçümden yeniden başla
    assert out.cx == pytest.approx(0.9) and f.velocity == (0.0, 0.0)
    f.update(_box(0.9, 0.5), 5.1)
    out = f.update(_box(0.1, 0.5), 2.0)  # geri atlama
    assert out.cx == pytest.approx(0.1) and f.velocity == (0.0, 0.0)


def test_box_filter_lookahead_requires_update_and_reset():
    f = BoxFilter()
    with pytest.raises(ValueError):
        f.lookahead(0.1, 0.1)
    f.update(_box(0.5, 0.5), 0.0)
    assert f.active
    f.reset()
    assert not f.active and f.velocity == (0.0, 0.0)
