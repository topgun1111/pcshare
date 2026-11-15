"""B3: vücut parçası ön ayarları — grup kutusu, PartTracker, canlı mesaj/sağ tık, menü bağları."""
import re
from pathlib import Path
from types import SimpleNamespace

import numpy as np

from tracker import parts
from tracker.config import Config
from tracker.geometry import BBox
from tracker.live import LiveController
from tracker.pose import PartTracker, Pose

ROOT = Path(__file__).resolve().parent.parent
PC = ROOT / "mpv" / "portable_config"
FRAME = np.zeros((240, 400, 3), np.uint8)
PERSON = BBox(150, 20, 250, 220)  # 200 px boy


def _pose(dx=0.0, conf=0.9, hide=()):
    """Ayakta duran kişi: tüm 17 eklem (kutu içinde); `hide` eklemleri güvensiz yapar."""
    k = np.zeros((17, 3), np.float32)
    xy = {0: (200, 40), 1: (195, 35), 2: (205, 35), 3: (190, 40), 4: (210, 40),
          5: (180, 70), 6: (220, 70), 7: (170, 110), 8: (230, 110), 9: (165, 150),
          10: (235, 150), 11: (185, 130), 12: (215, 130), 13: (185, 170), 14: (215, 170),
          15: (185, 210), 16: (215, 210)}
    for j, (x, y) in xy.items():
        k[j] = (x + dx, y, 0.1 if j in hide else conf)
    b = BBox(PERSON.x1 + dx, PERSON.y1, PERSON.x2 + dx, PERSON.y2)
    return Pose(b, 0.9, k)


class Est:
    def __init__(self, seq):
        self.seq, self.i = seq, 0

    def estimate(self, frame):
        r = self.seq[min(self.i, len(self.seq) - 1)]
        self.i += 1
        return r


class NullPipeline:
    detector = None

    def reset(self):
        pass

    def on_cut(self):
        pass


def test_group_extent_joint_union_min_size_region_and_person():
    p = _pose()
    cx, cy, bw, bh = parts.group_extent(p.kpts, "left_arm", p.bbox, 0.35, 0.10)
    assert (cx, cy) == (172.5, 110.0)  # omuz-dirsek-bilek x 165..180, y 70..150
    assert bw >= 15 + 2 * 0.02 * 200 - 1e-6 and bh == 80 + 2 * 0.02 * 200
    # tek eklemli parça en az kp_box×boy
    _, _, fw, fh = parts.group_extent(p.kpts, "left_foot", p.bbox, 0.35, 0.10)
    assert fw >= 20 and fh >= 20
    # güvenilir eklem yok → kişi kutusunun bölgesi (baş: üst %22)
    h = _pose(hide=range(17))
    cx, cy, bw, bh = parts.group_extent(h.kpts, "head", h.bbox, 0.35, 0.10)
    assert cx == 200 and bw == 100 and abs(bh - 0.22 * 200) < 1e-6 and abs(cy - (20 + 22)) < 1e-6
    # tüm kişi
    assert parts.group_extent(p.kpts, "person", p.bbox, 0.35, 0.10) == (200, 120, 100, 200)


def test_cycle_labels_and_presets_agree():
    assert set(parts.CYCLE) == set(parts.PRESETS) | {"auto"}
    assert set(parts.LABELS_TR) == set(parts.CYCLE)
    assert parts.next_preset("auto") == "person" and parts.next_preset("hands") == "auto"
    assert parts.next_preset("auto", -1) == "hands" and parts.next_preset("bilinmeyen") == "person"


def test_part_tracker_follows_group_switches_preset_without_new_inference():
    arm = (5, 7, 9)
    est = Est([[_pose(10)], [_pose(20, hide=arm)], [_pose(30, hide=arm)]])
    pt = PartTracker(est, _pose(0), "left_arm", kp_box=0.10, min_conf=0.35)
    assert pt.label == "Sol kol"
    b0 = pt.bbox
    assert abs(b0.cx - 172.5) < 1e-6
    b1 = pt.update(FRAME)
    assert b1.cx > b0.cx + 5  # kişi sağa kaydı, parça kutusu da
    calls = est.i
    pt.set_preset("legs")  # yeniden tarama / yeni çıkarım YOK
    assert est.i == calls and pt.label == "Bacaklar" and pt.bbox.cy > 150
    b2 = pt.update(FRAME)  # sol kol eklemleri gizli ama şimdi bacak izleniyor
    assert b2 is not None and b2.cy > 150
    pt.set_preset("left_arm")
    b3 = pt.update(FRAME)  # kol eklemleri gizli → kişi bölgesine düşer, hedef kaybolmaz
    assert b3 is not None and abs(b3.cy - (20 + 0.375 * 200)) < 1.0 and b3.cx > 220


def test_part_tracker_person_lost_gives_none_after_hold():
    clock_t = [0.0]

    def clock():
        clock_t[0] += 0.5
        return clock_t[0]

    pt = PartTracker(Est([[]]), _pose(), "head", hold_s=1.0, clock=clock)
    outs = [pt.update(FRAME) for _ in range(4)]
    assert outs[0] is not None and outs[-1] is None  # hold_s sonra kayıp


def _click(px, py):
    return {"event": "client-message",
            "args": ["track-point", str(px / 400), str(py / 240), "400", "240", "1"]}


def _part_msg(name):
    return {"event": "client-message", "args": ["track-part", name]}


def _ctl(est):
    fell_back = []

    class P:
        bbox = BBox(0, 0, 10, 10)

        def update(self, f):
            return self.bbox

    def factory(frame, px, py, cfg, box=None):
        fell_back.append((px, py))
        return P()

    c = LiveController(NullPipeline(), Config(ipc_path="x"), point_factory=factory, pose=est)
    return c, fell_back


def test_live_part_message_then_right_click_tracks_part_and_switches_in_place():
    est = Est([[_pose()]])
    c, fb = _ctl(est)
    out = c.handle_event(_part_msg("left_arm"))
    assert c.part == "left_arm" and "sağ tıkla" in out.osd
    c.handle_event(_click(200, 120))  # kişinin gövdesi (eklem değil): parça takibi
    out = c.step(FRAME, 1.0)
    assert out.osd == "Hedef: Sol kol" and not fb
    assert c.hud_text() == "Takip: Sol kol" and c.dot() is not None
    tracker = c.point
    runs = est.i
    out = c.handle_event(_part_msg("next"))  # sol kol → sağ kol, aynı izleyici
    assert out.osd == "Parça: Sağ kol" and c.point is tracker and est.i == runs
    assert tracker.label == "Sağ kol"
    c.handle_event(_part_msg("prev"))
    assert c.part == "left_arm"
    assert "Parça ayarı: Sol kol" in c.info_text([])
    c.handle_event(_part_msg("auto"))  # serbest: hedef korunur
    assert c.point is tracker and c.part == "auto"


def test_live_single_joint_target_converts_to_part_and_player_target_next_frame():
    c, _ = _ctl(Est([[_pose()]]))
    c.handle_event(_click(185, 210))  # sol ayak bileği eklemi
    assert c.step(FRAME, 1.0).osd == "Hedef: sol ayak bileği"
    out = c.handle_event(_part_msg("feet"))
    assert out.osd == "Parça: Ayaklar" and isinstance(c.point, PartTracker)
    assert c.point.bbox.cy > 190

    c2, _ = _ctl(Est([[_pose()]]))
    c2.enabled = True
    c2.target_id = 5
    c2.last_tracks = [SimpleNamespace(id=5, bbox=PERSON)]
    assert "Parça: Baş" == c2.handle_event(_part_msg("head")).osd
    out = c2.step(FRAME, 1.0)  # oyuncu hedefi → bu karede parçaya geçer
    assert out.osd == "Hedef: Baş" and isinstance(c2.point, PartTracker)
    assert c2.target_id is None


def test_live_part_without_pose_model_or_person_keeps_old_behavior():
    c, fb = _ctl(None)
    assert "Poz modeli yok" in c.handle_event(_part_msg("head")).osd and c.part == "auto"
    c.handle_event(_click(200, 120))
    assert c.step(FRAME, 1.0).osd == "Hedef: nesne" and fb

    c2, fb2 = _ctl(Est([[_pose()]]))
    c2.handle_event(_part_msg("torso"))
    c2.handle_event(_click(20, 20))  # kimsenin üstünde değil → nesne takibi
    assert c2.step(FRAME, 1.0).osd == "Hedef: nesne" and fb2
    assert c2.handle_event(_part_msg("yok")).osd is None and c2.part == "torso"


def test_lua_menu_and_hotkey_stay_in_sync_with_parts_module():
    lua = (PC / "scripts" / "track.lua").read_text(encoding="utf-8")
    arr = re.search(r"local PARTS = \{([^}]*)\}", lua).group(1)
    assert tuple(re.findall(r'"(\w+)"', arr)) == parts.CYCLE
    labels = dict(re.findall(r'(\w+) = "([^"]+)"', re.search(
        r"local PART_LABEL = \{([^}]*)\}", lua).group(1)))
    assert set(labels) == set(parts.CYCLE)
    menu = (PC / "script-modules" / "trackmenu.lua").read_text(encoding="utf-8")
    cmds = re.findall(r'cmd = "part_(\w+)"', menu)
    assert [c for c in cmds if c != "next"] == list(parts.CYCLE)
    for key, name in re.findall(r'cmd = "part_(\w+)", label = "([^"]+)"', menu):
        if key != "next":
            assert name == parts.LABELS_TR[key]
    assert "g script-binding track/part" in (PC / "input.conf").read_text(encoding="utf-8")
