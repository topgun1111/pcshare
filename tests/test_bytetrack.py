import numpy as np

from tracker.bytetrack import ByteTracker, iou_matrix, linear_assignment
from tracker.detector import Detection
from tracker.geometry import BBox
from tracker.pipeline import Pipeline


def det(x, y, w=40, h=100, s=0.9):
    return Detection(BBox.from_xywh(x, y, w, h), s)


def test_assignment_optimal_and_rectangular():
    c = np.array([[4, 1, 3], [2, 0, 5], [3, 2, 2]], float)
    assert sum(c[i, j] for i, j in linear_assignment(c)) == 5
    assert sorted(linear_assignment(np.array([[1.0, 9], [9, 1], [5, 5]])))[:2] == [(0, 0), (1, 1)]
    assert linear_assignment(np.zeros((0, 3))) == []


def test_iou():
    a = [BBox(0, 0, 10, 10)]
    assert iou_matrix(a, [BBox(0, 0, 10, 10)])[0, 0] == 1.0
    assert iou_matrix(a, [BBox(20, 20, 30, 30)])[0, 0] == 0.0


def test_id_persists_on_moving_box():
    t = ByteTracker()
    ids = {t.update([det(100 + 5 * i, 100)])[0].id for i in range(40)}
    assert ids == {1}


def test_two_objects_keep_ids():
    t = ByteTracker()
    for i in range(30):
        out = t.update([det(50 + 4 * i, 100), det(500 - 4 * i, 300)])
    assert sorted(x.id for x in out) == [1, 2]
    left = min(out, key=lambda x: x.bbox.cy)
    assert left.id == 1


def test_short_loss_keeps_id():
    t = ByteTracker()
    for i in range(10):
        t.update([det(100 + 5 * i, 100)])
    for _ in range(5):
        assert t.update([]) == []
    assert [x.id for x in t.lost_tracks] == [1]
    out = t.update([det(100 + 5 * 15, 100)])
    assert [x.id for x in out] == [1]
    assert out[0].lost == 0


def test_long_loss_drops_and_new_object_new_id():
    t = ByteTracker(max_lost=5)
    t.update([det(100, 100)])
    for _ in range(7):
        t.update([])
    assert t.lost_tracks == []
    assert t.update([det(100, 100)])[0].id == 2


def test_low_score_keeps_track_alive_but_never_spawns():
    t = ByteTracker()
    for i in range(5):
        t.update([det(100 + 3 * i, 100)])
    out = t.update([det(115, 100, s=0.3)])  # düşük skor → 2. aşama
    assert [x.id for x in out] == [1]
    t2 = ByteTracker()
    assert t2.update([det(100, 100, s=0.3)]) == []


def test_new_object_gets_new_id():
    t = ByteTracker()
    t.update([det(100, 100)])
    out = t.update([det(103, 100), det(600, 400)])
    assert sorted(x.id for x in out) == [1, 2]


class FakeDet:
    def __init__(self, seq):
        self.seq = iter(seq)

    def detect(self, frame):
        return next(self.seq)


def test_pipeline_step():
    p = Pipeline(FakeDet([[det(10, 10)], [det(12, 10)]]))
    assert p.step(None)[0].id == p.step(None)[0].id == 1
