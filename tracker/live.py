"""Canlı mod (Parça 5): mpv'den kare al → Pipeline → ViewSmoother → IPC `set_view`.

Mantık (`LiveController`) IO'dan ayrıdır ve testlidir; `run_live` döngüsü IO'yu yürütür.
Olaylar (Lua tıklama/aç-kapa/sıfırla) ayrı bir IPC bağlantısında iş parçacığıyla okunur: Windows
pipe'ında okuma bloklar ve aynı tutaçta eşzamanlı yazmayı da engeller; mpv çok istemciyi destekler.
"""
from __future__ import annotations

import queue
import threading
import time
from dataclasses import dataclass

from . import errlog
from .clicks import (
    ADDMODE_MSG,
    DOT_MSG,
    HUD_MSG,
    PART_MSG,
    RELEASE_MSG,
    REMOVE_MSG,
    RESET_MSG,
    SAVE_MSG,
    SETTING_MSG,
    SOURCE_MSG,
    STATUS_MSG,
    TOGGLE_MSG,
    USE_SCANNED_MSG,
    message_name,
    parse_click,
)
from .config import Config
from .frames import grab_frame
from .geometry import View, compute_view
from .ipc import MpvCommandError, MpvIpc, MpvIpcError
from .parts import CYCLE, LABELS_TR, PART_MARGIN, next_preset
from .quality import SOURCES, QualityWindow, scanned_agrees, views_ready
from .settings import SETTINGS, adjust, status_text
from .smooth import BoxFilter, ViewSmoother, ease_view
from .targets import TargetSet

CLOSED = {"event": "__closed__"}

_PROVIDER_SHORT = {"DmlExecutionProvider": "DirectML", "CUDAExecutionProvider": "CUDA",
                   "CPUExecutionProvider": "CPU", "TensorrtExecutionProvider": "TensorRT"}


def model_line(label: str, model_path, session=None, extra: str = "") -> str:
    """Sol üst bilgi bloğu için tek satır: `Etiket: model.onnx (ek) · DirectML`."""
    from pathlib import Path

    line = f"{label}: {Path(str(model_path)).name}" + (f" ({extra})" if extra else "")
    try:
        prov = session.get_providers()[0] if session is not None else ""
    except Exception:  # sahte/bozuk oturum bilgi satırını düşürmesin
        prov = ""
    return line + (f" · {_PROVIDER_SHORT.get(prov, prov)}" if prov else "")


class EventListener(threading.Thread):
    """Kendi bağlantısından mpv olaylarını `out` kuyruğuna aktarır; kopunca CLOSED koyar."""

    def __init__(self, path: str, out: queue.Queue):
        super().__init__(daemon=True)
        self.path, self.out = path, out
        self._stop_evt = threading.Event()

    def stop(self) -> None:
        self._stop_evt.set()

    def run(self) -> None:
        try:
            with MpvIpc(self.path) as conn:
                while not self._stop_evt.is_set():
                    ev = conn.poll_event(0.5)
                    if ev is not None:
                        self.out.put(ev)
        except MpvIpcError:
            pass
        self.out.put(CLOSED)


@dataclass(frozen=True)
class Output:
    view: View | None = None  # uygulanacak görünüm (None: değiştirme)
    osd: str | None = None


def _default_point_factory(frame, px: float, py: float, cfg: Config, box=None):
    from .point import WorldPointTracker  # cv2 yalnız nesne takibi başlayınca içe aktarılır

    return WorldPointTracker(frame, px, py, box=box, box_frac=cfg.point_box,
                             refine=cfg.point_refine, max_side=cfg.point_max_side,
                             min_score=cfg.point_min_score)


def _box_gap(t, px: float, py: float) -> float:
    """Noktanın kutuya uzaklığı (px); içindeyse 0."""
    b = t.bbox
    dx = max(b.x1 - px, 0.0, px - b.x2)
    dy = max(b.y1 - py, 0.0, py - b.y2)
    return (dx * dx + dy * dy) ** 0.5


def pick_track(tracks, px: float, py: float, tol: float = 0.0):
    """Tıklanan noktadaki izi seçer; yoksa None.

    1) Nokta bir veya daha fazla kutunun içindeyse: kutu boyutuna göre normalize merkez
       uzaklığı en küçük olan (iç içe/çakışan kutularda küçük kutuyu tıklamak mümkün kalır);
       eşitlikte alanı küçük olan.
    2) Hiçbirinin içinde değilse: kutu kenarına `tol` piksel içindeki en yakın iz (tol=0: yok).
    """
    hits = [t for t in tracks if t.bbox.contains(px, py)]
    if hits:
        def score(t):
            b = t.bbox
            nx = (px - b.cx) / max(b.w, 1e-6)
            ny = (py - b.cy) / max(b.h, 1e-6)
            return (nx * nx + ny * ny, b.w * b.h)

        return min(hits, key=score)
    if tol <= 0:
        return None
    near = [(g, t) for t in tracks if (g := _box_gap(t, px, py)) <= tol]
    return min(near, key=lambda gt: gt[0])[1] if near else None


class LiveController:
    def __init__(
        self,
        pipeline,
        cfg: Config,
        smoother: ViewSmoother | None = None,
        box_filter: BoxFilter | None = None,
        reid=None,
        cfg_path: str | None = None,
        point_factory=None,
        pose=None,
        wholebody=None,
        cuts=None,
    ):
        self.cuts = cuts  # cuts.CutDetector | None (G4): her işlenen karede çekim kesmesi ara
        self.cut_count = 0
        self.pose = pose  # PoseEstimator | None: sağ tık eklem yakınındaysa eklem takibi
        self.wb = wholebody  # WholeBodyEstimator | None (O2): el/yüz/ayak parmağı noktaları
        self.pipeline, self.cfg, self.reid = pipeline, cfg, reid  # reid: ReIdManager | None
        # (kare, px, py, cfg) -> update(kare) -> BBox | None veren nesne; testte sahtesi verilir
        self._point_factory = point_factory or _default_point_factory
        self.point = None  # sağ tıkla seçilen sınıfsız nesne izleyici (tracker/point.py)
        self.add_mode = False  # T1: sonraki sağ tık mevcut hedefe EKLER (birleşik kadraj)
        self.part = "auto"  # B3: parts.CYCLE adı; "auto" = tıklanan eklem (eski davranış)
        self._part_req = False  # oyuncu (ByteTrack) hedefi var, parçaya geçiş sonraki karede
        self.cfg_path = cfg_path  # `track-save` ayarları buraya yazar
        self._id_mark = 0  # hedef son görüldüğünde izlerin en büyük ID'si (ID'ler artan)
        self.flt = box_filter or BoxFilter(cfg.kf_accel, cfg.kf_meas)
        self.smoother = smoother or ViewSmoother(
            cfg.alpha_pos, cfg.alpha_zoom, cfg.dead_zone_pos, cfg.dead_zone_zoom
        )
        self.enabled = False
        self.source = "auto"  # A2: auto | live | scanned (Lua menüsü `track-source` yollar)
        self.target_id: int | None = None
        self.last_tracks: list = []
        self._click = None
        self._last_t: float | None = None
        self._lost = 0  # üst üste hedefsiz kare sayısı
        self._lost_t0: float | None = None  # kaybın başladığı video zamanı
        self._lost_from: View | None = None  # kayıp anındaki görünüm (zoom-out başlangıcı)
        self._zooming_out = False
        self._view = View()  # en son uygulanması istenen görünüm
        self._dot: tuple[float, float] | None = None  # son görülen hedef merkezi (video-normalize)

    @property
    def has_target(self) -> bool:
        return self.target_id is not None or self.point is not None

    def dot(self) -> tuple[float, float] | None:
        """Ekranda beyaz nokta olarak gösterilecek konum; hedef yok/kayıpsa None."""
        return self._dot if self.enabled and self.has_target and self._lost_t0 is None else None

    def _clear_lost(self) -> None:
        self._lost, self._lost_t0, self._lost_from, self._zooming_out = 0, None, None, False

    def _reset_tracking(self) -> None:
        self.pipeline.reset()
        self.smoother.reset()
        self.flt.reset()
        self.target_id = None
        self.point = None
        self._click = None
        self._last_t = None
        self._clear_lost()
        self._view = View()
        self._id_mark = 0
        if self.reid:
            self.reid.reset()

    def _release(self) -> Output:
        """Hedefi bırak: görünüm 1x'e döner; takip açık kalır, yeni tıklama hedef seçer."""
        had = self.has_target
        self.target_id = None
        self.point = None
        self._clear_lost()
        self.smoother.reset()
        self.flt.reset()
        self._view = View()
        if self.reid:
            self.reid.reset()
        if not had:
            return Output(osd="Bırakılacak hedef yok")
        return Output(view=View(), osd="Hedef bırakıldı")

    def handle_event(self, ev: dict) -> Output:
        click = parse_click(ev)
        if click:
            self.enabled = True  # toggle olayı kaçmış olsa da tıklama takibi açar
            self._click = click
            return Output()
        name = message_name(ev)
        if name == TOGGLE_MSG:
            on = (ev.get("args") or [None, "off"])[1:2] == ["on"]
            self.enabled = on
            if not on:
                self._reset_tracking()
                return Output(view=View())
            return Output()
        if name == SOURCE_MSG:
            a = (ev.get("args") or [None, "auto"])[1:2]
            self.source = a[0] if a and a[0] in SOURCES else "auto"
            return Output()
        if name == RESET_MSG:
            self._reset_tracking()
            return Output(view=View())
        if name == RELEASE_MSG:
            self._click = None
            return self._release()
        if name == SETTING_MSG:
            return self._on_setting(ev.get("args") or [])
        if name == ADDMODE_MSG:
            a = (ev.get("args") or [None, "toggle"])[1:2]
            self.add_mode = (not self.add_mode) if a == ["toggle"] else a == ["on"]
            return Output(osd="Hedef ekleme: " + ("AÇIK (sağ tık ekler)" if self.add_mode
                                                  else "kapalı"))
        if name == REMOVE_MSG:
            return self._remove_target(None)
        if name == PART_MSG:
            return self._on_part(ev.get("args") or [])
        if name == STATUS_MSG:
            return Output(osd=status_text(self.cfg, self.target_id, self.point is not None))
        if name == SAVE_MSG:
            return self._on_save()
        return Output()

    def _remove_target(self, i: int | None) -> Output:
        """T1: kümeden bir hedefi çıkar (i=None: sonuncu). Tek hedef kalırsa küme açılır."""
        ts = self.point
        if not isinstance(ts, TargetSet):
            return self._release()
        t = ts.remove(len(ts) - 1 if i is None else i)
        label = getattr(t, "label", "hedef")
        if len(ts) == 0:
            return self._release()
        if len(ts) == 1:
            self.point = ts.trackers[0]
        self.flt.reset()
        return Output(osd=f"Hedef çıkarıldı: {label} ({len(ts)} kaldı)")

    def _on_part(self, args: list) -> Output:
        """B3: vücut parçası ayarı. Hedef zaten bir kişi/eklemse parça YENİDEN TARAMADAN değişir;
        hedef yoksa ayar saklanır ve sonraki sağ tık kişiyi bu parçayla izler."""
        a = str(args[1]) if len(args) > 1 else ""
        name = next_preset(self.part, -1 if a == "prev" else 1) if a in ("next", "prev") else a
        if name not in CYCLE:
            return Output()
        if name != "auto" and self.pose is None:
            return Output(osd="Poz modeli yok: vücut parçası takibi kapalı")
        self.part = name
        label = LABELS_TR[name]
        if name == "auto":
            return Output(osd="Parça: serbest (sağ tık bir eklemi izler)")
        from .pose import KeypointTracker, PartTracker

        pt = self.point
        if isinstance(pt, TargetSet):  # T1: kümedeki tüm parça izleyicileri aynı parçaya geçer
            n = 0
            for m in pt.trackers:
                if isinstance(m, PartTracker):
                    m.set_preset(name)
                    n += 1
            return Output(osd=f"Parça: {label}" + ("" if n else " (kümede parça hedefi yok)"))
        if isinstance(pt, PartTracker):
            pt.set_preset(name)
            self.flt.reset()
            return Output(osd=f"Parça: {label}")
        if isinstance(pt, KeypointTracker):  # tek eklem → aynı kişide parça
            cam, app = pt.cam, pt.app
            self.point = self._new_part(pt.last_pose, name, cam, app)
            self.flt.reset()
            return Output(osd=f"Parça: {label}")
        if self.target_id is not None:
            self._part_req = True  # oyuncu hedefi: kişi kutusu üstünden parçaya geç (sonraki kare)
            return Output(osd=f"Parça: {label}")
        return Output(osd=f"Parça: {label} — sağ tıkla bir kişi seç")

    def _on_setting(self, args: list) -> Output:
        try:
            key, direction = str(args[1]), int(float(args[2]))
        except (IndexError, ValueError):
            return Output()
        osd = adjust(self.cfg, key, max(-1, min(1, direction)))
        if osd is None:
            return Output()
        self.smoother.set_alpha(self.cfg.alpha_pos, self.cfg.alpha_zoom)
        return Output(osd=osd)

    def _on_save(self) -> Output:
        if not self.cfg_path:
            return Output(osd="Kaydedilemedi: config yolu yok")
        try:
            self.cfg.save(self.cfg_path)
        except OSError as e:
            return Output(osd=f"Kaydedilemedi: {e}")
        return Output(osd=f"Ayarlar kaydedildi: {self.cfg_path}")

    def hud_text(self) -> str:
        """Köşe etiketi (boş = gizle). Python → Lua `track-hud`."""
        if not self.enabled or not self.cfg.hud:
            return ""
        if not self.has_target:
            return "Takip: hedef seç (tıkla)"
        if self._lost_t0 is not None:
            return "Takip: hedef kayıp" + (" (uzaklaşıyor)" if self._zooming_out else "")
        if self.point is not None:
            return "Takip: " + getattr(self.point, "label", "nesne")
        return f"Takip: ID {self.target_id}"

    def info_text(self, static: list[str], perf: tuple[float, float] | None = None) -> str:
        """Sol üst bilgi bloğu (çok satırlı; boş = gizle). Python → Lua `track-hud` 3. argüman.
        static: açılışta bilinenler (model/cihaz/Re-ID...); perf: (kare/sn, gecikme ms)."""
        if not self.enabled or not self.cfg.hud:
            return ""
        lines = list(static)
        if perf is not None:
            lines.append(f"Hız: {perf[0]:.1f} kare/sn · gecikme {perf[1]:.0f} ms")
        if self.part != "auto":
            lines.append(f"Parça ayarı: {LABELS_TR[self.part]}")
        if self.point is not None:
            tgt = "nesne: " + getattr(self.point, "label", "nesne")
        elif self.target_id is not None:
            tgt = f"ID {self.target_id}"
        else:
            tgt = "yok"
        if self.has_target and self._lost_t0 is not None:
            tgt += " (kayıp)"
        lines.append(f"Hedef: {tgt}")
        if getattr(self.pipeline, "detector", None) is not None:
            lines.append(f"Kişi sayısı: {len(self.last_tracks)}")
        lines.append(" · ".join(
            f"{s.label.split(' (')[0]} {s.fmt(getattr(self.cfg, s.attrs[0]))}"
            for s in SETTINGS.values()))
        return "\n".join(lines)

    def needs_frame(self, t: float) -> bool:
        """Duraklatılmış (time-pos değişmiyor) ve bekleyen tıklama yoksa kare işlemeye gerek yok."""
        if not self.enabled:
            return False
        return self._click is not None or self._last_t is None or abs(t - self._last_t) > 1e-6

    def step(self, frame, t: float) -> Output:
        if not self.enabled:
            return Output()
        cut = False
        if self.cuts is not None and self._last_t is not None:
            try:
                cut = bool(self.cuts.step(frame))
            except Exception as e:  # algılama asla izlemeyi düşürmesin
                errlog.record("sahne kesimi", e)
                cut = False
        elif self.cuts is not None:
            self.cuts.step(frame)  # ilk kare: yalnız referans
        had = self.has_target
        if cut:
            self._on_cut()
        if self._part_req:
            self._part_req = False
            out = self._part_from_target(frame, t)
            if out is not None:
                return out
        out = self._step(frame, t)
        if cut and had and out.osd is None:
            out = Output(view=out.view, osd="Çekim kesildi: hedef aranıyor")
        return out

    def _on_cut(self) -> None:
        """Çekim kesmesi (G4): eski sahnenin izleri/kamera geçmişi/süzgeç hızı geçersiz. Kişi hedefi
        yeni kimliklerle Re-ID'den ya da kayıp akışından bulunur; nesne/eklem izleyiciler
        `on_cut` ile yeniden bulma moduna alınır."""
        self.cut_count += 1
        on_cut = getattr(self.pipeline, "on_cut", None)
        if on_cut is not None:
            on_cut()
        self.flt.reset()
        if self.point is not None and hasattr(self.point, "on_cut"):
            self.point.on_cut()

    def _step(self, frame, t: float) -> Output:
        fh, fw = frame.shape[:2]
        prev = self._last_t
        if prev is not None and (t < prev - 0.05 or t - prev > self.cfg.seek_jump):
            had = self.has_target
            click = self._click
            self._reset_tracking()
            self._click = click
            if self.cuts is not None:
                self.cuts.reset()
            if had:
                self._last_t = t
                return Output(view=View(), osd="Atlama: hedefi yeniden seç")
        self._last_t = t
        click = self._click
        # sağ tık / sol sürükleme: herhangi bir nesne
        if click is not None and click.kind in ("point", "rect"):
            self._click = None
            return self._start_point(frame, t, click)
        if self.point is not None and click is None:  # nesne izleniyor: detector gerekmez
            return self._step_point(frame, t, None)
        tracks = self.pipeline.step(frame)
        self.last_tracks = tracks
        osd = None
        if self._click is not None:
            click, self._click = self._click, None
            tol = self.cfg.pick_tol * fh
            picked = pick_track(tracks, click.px * fw / click.vw, click.py * fh / click.vh, tol)
            if picked is None:
                osd = "Burada kişi yok"  # mevcut hedef (varsa) korunur
            elif picked.id == self.target_id:  # hedefin üstüne tekrar tıklama: bırak
                out = self._release()
                return Output(view=out.view, osd=out.osd)
            else:
                if not self.has_target:
                    self.smoother.reset()  # yeni hedefe geçişte yumuşatıcı korunur: kayarak gider
                elif self._zooming_out:
                    self.smoother.seed(self._view)  # zoom-out'tan yeni hedefe sıçramadan geç
                self.point = None  # oyuncu seçildi: nesne izleyici bırakılır
                self.target_id = picked.id
                self._clear_lost()
                self.flt.reset()  # süzgeç eski hedefin hızını taşımasın
                if self.reid:
                    self.reid.reset()  # galeri eski kişiye aitti
                osd = f"Hedef: ID {picked.id}"
        if self.target_id is None:
            if self.point is not None:  # oyuncu bulunamadı: nesne takibi kesilmesin
                return self._step_point(frame, t, osd)
            return Output(osd=osd)
        tr = next((x for x in tracks if x.id == self.target_id), None)
        if tr is None and self.reid is not None:
            tr, osd = self._try_reid(frame, t, tracks, osd)
        if tr is None:
            return self._on_lost(t, osd)
        self._id_mark = max([self._id_mark, *(x.id for x in tracks)])
        if self.reid is not None:
            self.reid.observe(frame, t, tr, [x for x in tracks if x is not tr])
        return self._follow(tr.bbox, fw, fh, t, osd)

    def _follow(self, bbox, fw: int, fh: int, t: float, osd: str | None) -> Output:
        """Hedef kutusu görüldü (oyuncu ya da nesne): süzgeç → görünüm → yumuşatıcı."""
        if self._zooming_out:  # zoom-out sırasında bulundu: bulunduğumuz görünümden devam
            self.smoother.seed(self._view)
        self._clear_lost()
        vis = getattr(self.point, "visible", True) if self.point is not None else True
        self.flt.update(bbox.scaled(1 / fw, 1 / fh), t)  # kare boyutuna göre normalize
        # Artı işareti: kare alınıp işlenene kadar nesne ilerledi → hız * gecikme kadar ileri çiz
        # (yoksa hızlı nesnede işaret hep geride kalır). Sınırlı: kare başına en çok %3.
        vx, vy = self.flt.velocity
        lead = max(self.cfg.dot_lead_s, 0.0)
        dx = max(-0.03, min(0.03, vx * lead))
        dy = max(-0.03, min(0.03, vy * lead))
        # kare dışında nokta çizilmez
        self._dot = (bbox.cx / fw + dx, bbox.cy / fh + dy) if vis else None
        lead = self.flt.lookahead(self.cfg.lookahead_s, self.cfg.max_lead).scaled(fw, fh)
        raw = compute_view(lead, fw, fh, self.cfg.target_fill, self.cfg.min_zoom,
                           self.cfg.max_zoom)
        self._view = self.smoother.update(raw)
        return Output(view=self._view, osd=osd)

    def _start_point(self, frame, t: float, click) -> Output:
        """Sağ tık: tıklanan noktadaki HERHANGİ bir nesneyi takip etmeye başla (sınıf önemsiz).
        Zaten izlenen hedefin üstüne tekrar sağ tık hedefi bırakır."""
        fh, fw = frame.shape[:2]
        px, py = click.px * fw / click.vw, click.py * fh / click.vh
        box = None
        if click.kind == "rect" and click.box is not None:
            x1, y1, x2, y2 = click.box
            sx, sy = fw / click.vw, fh / click.vh
            box = (x1 * sx, y1 * sy, max(x2 * sx, x1 * sx + 4), max(y2 * sy, y1 * sy + 4))
            px, py = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
        if box is None and isinstance(self.point, TargetSet) and self._lost_t0 is None:
            i = self.point.member_at(px, py)  # T1: üyeye sağ tık = o hedefi çıkar
            if i is not None:
                out = self._remove_target(i)
                return Output(view=out.view, osd=out.osd)
        if (box is None and self.point is not None and self._lost_t0 is None
                and not isinstance(self.point, TargetSet) and self.point.bbox.contains(px, py)):
            out = self._release()
            return Output(view=out.view, osd=out.osd)
        cur = next((x for x in self.last_tracks if x.id == self.target_id), None)
        # izlenen oyuncuya tekrar sağ tık
        if box is None and cur is not None and cur.bbox.contains(px, py):
            out = self._release()
            return Output(view=out.view, osd=out.osd)
        try:
            pt = None
            if box is None and self.part != "auto":
                pt = self._try_part(frame, px, py)  # B3: kişiye tıklama = seçili vücut parçası
            if pt is None and box is None:
                pt = self._try_keypoint(frame, px, py)
            if pt is not None:
                pass
            elif box is not None:
                pt = self._point_factory(frame, px, py, self.cfg, box=box)
            else:
                pt = self._point_factory(frame, px, py, self.cfg)
        except Exception as e:  # cv2 yok / kare bozuk: takip döngüsü düşmesin
            errlog.record("nesne takibi başlatma", e)
            return Output(osd=f"Nesne takibi başlatılamadı: {e}")
        if self.add_mode and self.point is not None:  # T1: yeni hedefi kümeye ekle
            ts = self.point
            if not isinstance(ts, TargetSet):
                ts = TargetSet(ts, fill=self.cfg.target_fill, min_zoom=self.cfg.set_min_zoom,
                               hyst=self.cfg.set_hyst)
            ts.add(pt)
            self.point = ts
            self._clear_lost()
            self.flt.reset()
            return self._follow(ts.bbox, fw, fh, t,
                                f"Hedef eklendi: {getattr(pt, 'label', 'nesne')} ({len(ts)} hedef)")
        if not self.has_target:
            self.smoother.reset()  # ilk hedef: doğrudan oraya; sonrakilerde kayarak geçilir
        elif self._zooming_out:
            self.smoother.seed(self._view)
        self.target_id = None
        self.last_tracks = []
        self.pipeline.reset()  # oyuncu izleri artık gerekmez; sonraki sol tık taze başlar
        if self.reid:
            self.reid.reset()
        self.point = pt
        self._clear_lost()
        self.flt.reset()
        return self._follow(pt.bbox, fw, fh, t, "Hedef: " + getattr(pt, "label", "nesne"))

    def _cam_app(self, frame):
        """Eklem/parça izleyicileri için kamera telafisi (B2) ve Re-ID görünümü (G3)."""
        cam = None
        if self.cfg.kp_hold_s > 0 and self.cfg.kp_world:  # B2: kare dışına çıkan parçayı taşı
            try:
                from .point import CameraComp

                cam = CameraComp(frame)
            except Exception as e:  # cv2 yok: kamera telafisiz sürer
                errlog.record("kamera telafisi", e)
                cam = None
        app = None
        emb = getattr(self.reid, "emb", None)
        if emb is not None and self.cfg.kp_reid:  # G3: kalabalıkta kişiyi görünümle de bul
            from .reid import PartAppearance

            app = PartAppearance(emb, self.cfg)
        return cam, app

    def _try_keypoint(self, frame, px: float, py: float):
        """Pose modeli varsa ve tıklama bir eklem yakınındaysa o eklemin izleyicisi; yoksa None."""
        if self.pose is None:
            return None
        from .pose import KeypointTracker, nearest_keypoint

        try:
            poses = self.pose.estimate(frame)
        except Exception as e:  # model/kare hatası: eski nesne takibine düş
            errlog.record("poz tahmini", e)
            return None
        if self.wb is not None:  # O2: yalnız tıklanan kişiyi 133 noktaya genişlet
            poses = [self.wb.refine(frame, p) if p.bbox.contains(px, py) else p for p in poses]
        hit = nearest_keypoint(poses, px, py, self.cfg.kp_tol, self.cfg.kp_min_conf,
                               self.cfg.wb_tol, self.cfg.wb_min_conf)
        if hit is None:
            return None
        i, k = hit
        cam, app = self._cam_app(frame)
        fine = k >= 17  # tüm-vücut noktası: kendi güven eşiği, daha küçük takip kutusu
        return KeypointTracker(self.pose, poses[i], k,
                               self.cfg.kp_box * (0.5 if fine else 1.0),
                               self.cfg.wb_min_conf if fine else self.cfg.kp_min_conf,
                               refiner=self.wb if fine else None, hold=self.cfg.kp_hold,
                               hold_s=self.cfg.kp_hold_s, cam=cam,
                               smooth=self.cfg.kp_filter, reacq_single=self.cfg.kp_reacq_single,
                               appearance=app)

    def _new_part(self, pose, preset: str, cam, app):
        from .pose import PartTracker

        c = self.cfg
        return PartTracker(self.pose, pose, preset, kp_box=c.kp_box, min_conf=c.kp_min_conf,
                           margin=getattr(c, "part_margin", PART_MARGIN), hold=c.kp_hold,
                           hold_s=c.kp_hold_s, cam=cam, smooth=c.kp_filter,
                           reacq_single=c.kp_reacq_single, appearance=app)

    def _person_at(self, frame, px: float, py: float):
        """(pose) tıklama noktasını içeren en küçük kişi kutusu; poz yok/başarısız → None."""
        if self.pose is None:
            return None
        try:
            poses = self.pose.estimate(frame)
        except Exception as e:  # model/kare hatası: eski takibe düş
            errlog.record("poz tahmini", e)
            return None
        hits = [p for p in poses if p.bbox.contains(px, py)]
        def area(p):
            return (p.bbox.x2 - p.bbox.x1) * (p.bbox.y2 - p.bbox.y1)

        return min(hits, key=area) if hits else None

    def _try_part(self, frame, px: float, py: float):
        """B3: (px, py) bir kişinin üstündeyse o kişinin `self.part` parçasının izleyicisi."""
        p = self._person_at(frame, px, py)
        if p is None:
            return None
        cam, app = self._cam_app(frame)
        return self._new_part(p, self.part, cam, app)

    def _part_from_target(self, frame, t: float) -> Output | None:
        """Oyuncu (ByteTrack) hedefi varken parça seçildi: kutusunun merkezindeki kişiyi bul."""
        cur = next((x for x in self.last_tracks if x.id == self.target_id), None)
        if cur is None:
            return None
        fh, fw = frame.shape[:2]
        pt = self._try_part(frame, cur.bbox.cx, cur.bbox.cy)
        if pt is None:
            return None
        self.target_id = None
        self.last_tracks = []
        self.pipeline.reset()
        if self.reid:
            self.reid.reset()
        self.point = pt
        self._clear_lost()
        self.flt.reset()
        return self._follow(pt.bbox, fw, fh, t, "Hedef: " + pt.label)

    def _step_point(self, frame, t: float, osd: str | None) -> Output:
        fh, fw = frame.shape[:2]
        box = self.point.update(frame)
        if box is None:
            return self._on_lost(t, osd)
        return self._follow(box, fw, fh, t, osd)

    def _try_reid(self, frame, t: float, tracks, osd: str | None):
        """Hedef ID'si yok: kayıp sırasında doğan yeni izler arasında galeriye uyan varsa hedefi
        ona bağla (ByteTrack ID değiştirmişse)."""
        cands = [x for x in tracks
                 if x.id > self._id_mark and x.age >= self.cfg.reid_min_age]
        cand = self.reid.match(frame, t, cands, tracks)
        if cand is None:
            return None, osd
        self.target_id = cand.id
        self.flt.reset()  # konum sıçradı: eski hız/konum geçersiz
        return cand, f"Hedef yeniden bulundu: ID {cand.id}"

    def _on_lost(self, t: float, osd: str | None) -> Output:
        """Hedef bu karede yok: `lost_hold_s` son görünümde bekle, sonra `lost_zoomout_s` içinde
        1x'e kademeli zoom-out; hedef geri gelirse (aynı ID) takip kaldığı yerden sürer.
        Süre video zamanıyla ölçülür (duraklatma/atlama gerçek süreyi şişirmez)."""
        self._lost += 1
        if self._lost_t0 is None:
            self._lost_t0, self._lost_from = t, self._view
            osd = osd or "Hedef kayboldu"
        el = t - self._lost_t0 - max(self.cfg.lost_hold_s, 0.0)
        if el < 0.0:
            return Output(osd=osd)  # bekle
        self._zooming_out = True
        dur = self.cfg.lost_zoomout_s
        assert self._lost_from is not None
        self._view = ease_view(self._lost_from, View(), el / dur if dur > 0 else 1.0)
        return Output(view=self._view, osd=osd)


def run_live(
    mpv,
    events: queue.Queue,
    pipeline,
    cfg: Config,
    *,
    grab=grab_frame,
    stop: threading.Event | None = None,
    log=print,
    clock=time.monotonic,
    sleep=time.sleep,
    stats_every: float = 0.0,
    reid=None,
    cfg_path: str | None = None,
    pose=None,
    info: list[str] | None = None,
    wholebody=None,
    cuts=None,
) -> None:
    """info: sol üst bilgi bloğundaki sabit satırlar (model/cihaz...). None = blok gönderilmez."""
    errlog.get_video = lambda: mpv.get_property("path")
    ctl = LiveController(pipeline, cfg, reid=reid, cfg_path=cfg_path, pose=pose,
                         wholebody=wholebody, cuts=cuts)
    last_hud: tuple[str, bool, str] = ("", False, "")
    perf: tuple[float, float] | None = None  # (kare/sn, gecikme ms): ~1 sn'lik pencere
    pf_n, pf_lag, pf_t = 0, 0.0, clock()
    min_dt = 1.0 / max(cfg.live_fps, 1.0)
    last_view: View | None = None
    n, lag_sum, t_stat = 0, 0.0, clock()
    qual, sw_last, views_cache = QualityWindow(cfg.auto_window_s), -1e9, (0.0, None)

    last_dot: tuple[str, str] = ("", "")
    dot_on: bool | None = None

    def sync_dot() -> None:
        # Beyaz takip noktası (Lua `track-dot`); yalnız konum değişince gönderilir
        nonlocal last_dot, dot_on
        d = ctl.dot()
        msg = ("off", "") if d is None else (f"{d[0]:.4f}", f"{d[1]:.4f}")
        if msg == last_dot:
            return
        last_dot = msg
        if (d is not None) != dot_on:  # yalnız göster/gizle geçişleri (konum değişimi değil)
            dot_on = d is not None
            log("artı işareti gösteriliyor" if dot_on else
                f"artı işareti gizlendi (hedef {'kayıp' if ctl.has_target else 'yok'})")
        try:
            mpv.command("script-message-to", "track", DOT_MSG, *(m for m in msg if m))
        except MpvCommandError as e:
            log(f"nokta gönderilemedi: {e}")

    def sync_hud() -> None:
        # Lua'ya (etiket metni, hedef kilitli mi) gider; yalnız ikisinden biri değişince.
        # "Kilitli" ekran üstü zoom +/- düğmesinin ne yapacağını belirler (doluluk / video zoom).
        nonlocal last_hud
        state = (ctl.hud_text(), ctl.has_target and ctl.enabled,
                 "" if info is None else ctl.info_text(info, perf))
        if state == last_hud:
            return
        last_hud = state
        args = [state[0], "1" if state[1] else "0"]
        if info is not None:
            args.append(state[2])
        try:
            mpv.command("script-message-to", "track", HUD_MSG, *args)
        except MpvCommandError as e:
            log(f"HUD gönderilemedi: {e}")

    def emit(out: Output) -> None:
        nonlocal last_view
        if out.osd:
            log(out.osd)
            try:
                mpv.command("show-text", out.osd, 1500)
            except MpvCommandError as e:  # OSD hatası döngüyü durdurmasın
                log(f"OSD gösterilemedi: {e}")
                errlog.record("OSD", e)
        if out.view is not None and out.view != last_view:
            try:
                mpv.set_view(out.view)
                last_view = out.view
            except MpvCommandError as e:  # mpv reddetti: sonraki karede yeniden dene
                log(f"görünüm uygulanamadı: {e}")
                errlog.record("görünüm", e)
        sync_hud()
        sync_dot()

    log("Canlı mod hazır (Ctrl+C ile çık). mpv'de herhangi bir nesneye sağ tıkla: takip "
        "kendiliğinden açılır (oyuncu kimliği için sol tık).")
    try:
        while not (stop is not None and stop.is_set()):
            t0 = clock()
            timeout = 0.0 if ctl.enabled else 0.1  # kapalıyken olayı bekleyerek boşta kal
            while True:
                try:
                    ev = events.get(timeout=timeout)
                except queue.Empty:
                    break
                timeout = 0.0
                if ev.get("event") == CLOSED["event"]:
                    log("mpv bağlantısı kapandı.")
                    return
                emit(ctl.handle_event(ev))
            if not ctl.enabled:
                continue
            try:
                t = mpv.get_property("time-pos")
                if t is None or not ctl.needs_frame(float(t)):
                    sleep(0.05)
                    continue
                frame = grab(mpv)
            except MpvCommandError:  # video yok / property unavailable
                sleep(0.2)
                continue
            emit(ctl.step(frame, float(t)))
            n += 1
            lag_sum += clock() - t0
            # A2: canlı kötüyse (ve taranmış iz hazır + aynı hedefse) taranmış ize geç
            if not ctl.has_target or ctl.source != "auto":
                qual.reset()
            else:
                qual.add(clock(), clock() - t0, ctl._lost_t0 is not None)
                why = qual.verdict(cfg)
                if why and clock() - sw_last >= 10.0:
                    if clock() - views_cache[0] >= 2.0:
                        try:
                            views_cache = (clock(), views_ready(mpv.get_property("path")))
                        except MpvCommandError:
                            views_cache = (clock(), None)
                    vw = views_cache[1]
                    if vw and scanned_agrees(vw, float(t), ctl.dot()):
                        sw_last = clock()
                        qual.reset()
                        log(f"taranmış ize geçiliyor ({why})")
                        try:
                            mpv.command("script-message-to", "track", USE_SCANNED_MSG, why)
                        except MpvCommandError as e:
                            log(f"geçiş gönderilemedi: {e}")
            pf_n, pf_lag = pf_n + 1, pf_lag + (clock() - t0)
            if clock() - pf_t >= 1.0:  # bilgi bloğu: ~1 sn'de bir güncellenen hız/gecikme
                perf = (pf_n / (clock() - pf_t), pf_lag / pf_n * 1000)
                pf_n, pf_lag, pf_t = 0, 0.0, clock()
                sync_hud()
            if stats_every and clock() - t_stat >= stats_every:
                dt = clock() - t_stat
                log(f"{n / dt:.1f} kare/sn, gecikme {lag_sum / n * 1000:.0f} ms, "
                    f"hedef={'nesne' if ctl.point is not None else ctl.target_id}, "
                    f"izler={len(ctl.last_tracks)}")
                n, lag_sum, t_stat = 0, 0.0, clock()
            rest = min_dt - (clock() - t0)
            if rest > 0:
                sleep(rest)
    except MpvIpcError as e:
        log(f"mpv bağlantısı kesildi: {e}")
        errlog.record("mpv bağlantısı", e)
