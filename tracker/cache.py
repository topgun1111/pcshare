"""A3: tarama önbelleği — video imzası (yol+boyut+mtime), tarama imzası (+örnek fps+model).
İmza `track.json` (`sig`, `vsig`), `.partial.json` ve `.views.json` (`vsig`) içine yazılır;
eşleşen `track.json` yeniden taranmadan kullanılır; uyuşmayan kesinti/görünüm yok sayılır."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

THROTTLE_SUFFIX = ".scan.slow"  # varsa tarama örnek başına uyur (oynatma açık)


def file_sig(path: str | os.PathLike) -> str:
    """Video kimliği: tam yol + boyut + mtime (ns). Dosya yoksa yalnız yol."""
    p = os.path.abspath(path)
    try:
        st = os.stat(p)
        raw = f"{os.path.normcase(p)}|{st.st_size}|{st.st_mtime_ns}"
    except OSError:
        raw = os.path.normcase(p)
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]


def model_tag(path: str | os.PathLike | None) -> str:
    """Model kimliği: ad + boyut (dosya yoksa yalnız ad); None → boş."""
    if not path:
        return ""
    p = Path(path)
    try:
        return f"{p.name}:{p.stat().st_size}"
    except OSError:
        return p.name


def scan_sig(video: str, fps: float, *models: str | os.PathLike | None) -> str:
    """Tarama imzası: video + örnek fps + model kimlikleri (person, poz...)."""
    raw = "|".join([file_sig(video), f"{fps:g}", *(model_tag(m) for m in models if m)])
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]


def cached_doc(path: str | os.PathLike, sig: str, need_pose: bool = False) -> dict | None:
    """`path` geçerli bir track.json ve imzası `sig` ise belge, değilse None."""
    from .offline import load_doc

    try:
        doc = load_doc(path)
    except (OSError, ValueError):
        return None
    if doc.get("sig") != sig or not doc.get("samples"):
        return None
    if need_pose and doc.get("version", 1) < 2:
        return None
    return doc


def views_match(path: str | os.PathLike, video: str) -> bool:
    """`.views.json` bu videoya mı ait? (`vsig` yoksa eski dosya: kabul)."""
    try:
        d = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return True
    v = d.get("vsig") if isinstance(d, dict) else None
    return v is None or v == file_sig(video)
