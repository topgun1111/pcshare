"""track.lua'dan gelen `script-message track-click|track-point ...` olaylarını ayrıştırır."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

CLICK_MSG = "track-click"  # sol tık: tıklanan OYUNCU (YOLO + ByteTrack kimliği)
POINT_MSG = "track-point"  # sağ tık: tıklanan NOKTA/nesne (sınıfsız, tracker/point.py)
RECT_MSG = "track-rect"  # sol tuşla çizilen dikdörtgen: nokta takibi (kamera telafili)
_KINDS = {CLICK_MSG: "person", POINT_MSG: "point", RECT_MSG: "rect"}
RESET_MSG = "track-reset"
RELEASE_MSG = "track-release"
TOGGLE_MSG = "track-toggle"
SETTING_MSG = "track-setting"  # track-setting <fill|speed|lead|all> <+1|-1|0>
SAVE_MSG = "track-save"
STATUS_MSG = "track-status"
ADDMODE_MSG = "track-addmode"  # Lua → Python (T1): on|off; sağ tık hedef EKLER
REMOVE_MSG = "track-remove"  # Lua → Python (T1): son eklenen hedefi çıkar
PART_MSG = "track-part"  # Lua → Python (B3): track-part <parça|next|prev> (parts.CYCLE adları)
DOT_MSG = "track-dot"  # Python → Lua: takip noktası (nx ny, video-normalize) ya da "off"
SOURCE_MSG = "track-source"  # Lua → Python (A2): kaynak modu auto|live|scanned
USE_SCANNED_MSG = "track-use-scanned"  # Python → Lua (A2): taranmış ize geç <sebep>
LOADING_MSG = "track-loading"  # Python → Lua: modeller yükleniyor ("1") / hazır ("0")
HUD_MSG = "track-hud"  # Python → Lua (script-message-to track track-hud <metin>)


@dataclass(frozen=True)
class ClickEvent:
    nx: float  # video içinde normalize x (0..1)
    ny: float
    vw: int  # video piksel genişliği
    vh: int
    time_pos: float  # tıklama anındaki oynatma zamanı (sn)
    kind: str = "person"  # "person" (track-click) | "point" (track-point) | "rect" (track-rect)
    nx2: float | None = None  # yalnız "rect": karşı köşe (video-normalize); (nx,ny) ilk köşe
    ny2: float | None = None

    @property
    def box(self) -> tuple[float, float, float, float] | None:
        """"rect" için piksel (x1, y1, x2, y2); sıralı."""
        if self.nx2 is None or self.ny2 is None:
            return None
        xs, ys = sorted((self.px, self.nx2 * self.vw)), sorted((self.py, self.ny2 * self.vh))
        return xs[0], ys[0], xs[1], ys[1]

    @property
    def px(self) -> float:
        return self.nx * self.vw

    @property
    def py(self) -> float:
        return self.ny * self.vh


def parse_click(event: dict[str, Any]) -> ClickEvent | None:
    """client-message olayı bir tıklama değilse veya bozuksa None döner."""
    if event.get("event") != "client-message":
        return None
    args = event.get("args") or []
    if not args or args[0] not in _KINDS:
        return None
    rect = args[0] == RECT_MSG
    if len(args) != (8 if rect else 6):
        return None
    try:
        nx, ny = float(args[1]), float(args[2])
        o = 2 if rect else 0  # rect: nx ny nx2 ny2 vw vh t
        nx2, ny2 = (float(args[3]), float(args[4])) if rect else (None, None)
        vw, vh = int(float(args[3 + o])), int(float(args[4 + o]))
        t = float(args[5 + o])
    except (TypeError, ValueError):
        return None
    pts = [nx, ny] + ([nx2, ny2] if rect else [])
    if not all(0.0 <= v <= 1.0 for v in pts) or vw <= 0 or vh <= 0:
        return None
    return ClickEvent(nx, ny, vw, vh, t, _KINDS[args[0]], nx2, ny2)


def message_name(event: dict[str, Any]) -> str | None:
    """client-message olayının ilk argümanı (track-reset vb.)."""
    if event.get("event") != "client-message":
        return None
    args = event.get("args") or []
    return args[0] if args else None
