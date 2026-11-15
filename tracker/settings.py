"""Canlı ayarlar (Parça 13): mpv tuşlarıyla (track.lua → `track-setting`) anında değişen değerler.

`adjust(cfg, anahtar, yön)` config'i yerinde değiştirir, sınırlara kırpar ve OSD metni döndürür.
yön: +1 / -1 (bir adım), 0 = varsayılana dön. Değerler her karede cfg'den okunur
(`target_fill`, `lookahead_s`); yumuşatıcı katsayıları `LiveController` içinde yeniden kurulur.
"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from .config import Config


@dataclass(frozen=True)
class Setting:
    key: str
    label: str
    attrs: tuple[str, ...]  # ilki gösterilen/sınırlanan; mul ise diğerleri aynı oranda ölçeklenir
    step: float  # mul=False: toplanır; mul=True: çarpan (yön üssü)
    lo: float
    hi: float
    mul: bool
    fmt: Callable[[float], str]


SETTINGS: dict[str, Setting] = {s.key: s for s in (
    Setting("fill", "Doluluk (hedef kareyi ne kadar doldurur)", ("target_fill",),
            0.05, 0.10, 0.80, False, lambda v: f"%{v * 100:.0f}"),
    Setting("speed", "Tepki hızı (yüksek = hızlı takip, düşük = akıcı)",
            ("alpha_pos", "alpha_zoom"), 1.25, 0.03, 0.60, True, lambda v: f"{v:.2f}"),
    Setting("lead", "Öngörü (hız yönünde ileri bakış)", ("lookahead_s",),
            0.05, 0.0, 0.60, False, lambda v: f"{v:.2f} sn"),
)}


def _show(cfg: Config, s: Setting) -> str:
    return f"{s.label.split(' (')[0]}: {s.fmt(getattr(cfg, s.attrs[0]))}"


def reset_all(cfg: Config) -> str:
    d = Config(ipc_path=cfg.ipc_path)
    for s in SETTINGS.values():
        for a in s.attrs:
            setattr(cfg, a, getattr(d, a))
    return "Ayarlar varsayılana döndü"


def adjust(cfg: Config, key: str, direction: int) -> str | None:
    """Ayarı değiştir; OSD metni döndür (bilinmeyen anahtar → None)."""
    if key == "all" and direction == 0:
        return reset_all(cfg)
    s = SETTINGS.get(key)
    if s is None:
        return None
    cur = getattr(cfg, s.attrs[0])
    if direction == 0:
        d = Config(ipc_path=cfg.ipc_path)
        for a in s.attrs:
            setattr(cfg, a, getattr(d, a))
        return _show(cfg, s) + " (varsayılan)"
    new = cur * s.step ** direction if s.mul else cur + s.step * direction
    new = round(min(max(new, s.lo), s.hi), 4)
    if new == round(cur, 4):
        return _show(cfg, s) + (" (üst sınır)" if direction > 0 else " (alt sınır)")
    if s.mul and cur > 0:
        f = new / cur
        for a in s.attrs[1:]:
            setattr(cfg, a, min(round(getattr(cfg, a) * f, 4), 1.0))
    setattr(cfg, s.attrs[0], new)
    return _show(cfg, s)


def status_text(cfg: Config, target_id: int | None, point: bool = False) -> str:
    """Tüm canlı ayarlar + hedef (`h` tuşu). point: sağ tıkla seçilen nesne izleniyor."""
    parts = [_show(cfg, s) for s in SETTINGS.values()]
    if point:
        parts.append("Hedef: nesne (sağ tık noktası)")
    else:
        parts.append("Hedef: " + ("yok" if target_id is None else f"ID {target_id}"))
    return "\n".join(parts)
