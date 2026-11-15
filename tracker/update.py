"""Yardım → Güncellemeleri denetle (VLC tarzı): GitHub Release'te yeni sürüm var mı → indir → kur.

Kaynak: `https://api.github.com/repos/<sahip>/<depo>/releases/latest`; kurulum dosyası
`TakipliOynat-Setup-<sürüm>.exe` adlı varlık (build.yml `v*` etiketinde ekler). Depo adı
`config.json: update_repo`, yoksa uygulama klasöründeki `update.json` ({"repo": "sahip/depo"}; CI yazar).
Güvenlik: yalnız https + github.com/githubusercontent.com adresleri; boyut ve (API verirse) SHA-256
doğrulanır; kurulum sessiz (`/SILENT`), Inno Setup açık mpv/python'u kendisi kapatır.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

ASSET_RE = re.compile(r"^TakipliOynat-Setup-.+\.exe$", re.I)
ALLOWED_HOSTS = ("github.com", "githubusercontent.com")
API = "https://api.github.com/repos/{repo}/releases/latest"
REPO_RE = re.compile(r"^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")


def parse_version(s: str) -> tuple[int, int, int]:
    """'v1.3.0', '1.3', '1.3.0-rc1' → (1, 3, 0). Sayı yoksa (0, 0, 0)."""
    core = str(s).strip().lstrip("vV").split("-")[0].split("+")[0]
    nums = [int(n) for n in re.findall(r"\d+", core)][:3]
    return tuple(nums + [0] * (3 - len(nums)))  # type: ignore[return-value]


def is_newer(latest: str, current: str) -> bool:
    return parse_version(latest) > parse_version(current)


def resolve_repo(cfg_repo: str = "", app_root: Path | None = None) -> str:
    """config.json `update_repo` → yoksa `<uygulama>/update.json` → ''."""
    repo = (cfg_repo or "").strip()
    if not repo:
        root = app_root or Path(__file__).resolve().parent.parent
        try:
            repo = str(json.loads((root / "update.json").read_text(encoding="utf-8")).get("repo", ""))
        except (OSError, ValueError, AttributeError):
            repo = ""
    return repo.strip() if REPO_RE.match(repo.strip()) else ""


def safe_url(url: str) -> bool:
    u = urlparse(url)
    host = (u.hostname or "").lower()
    return u.scheme == "https" and any(host == h or host.endswith("." + h) for h in ALLOWED_HOSTS)


def pick_asset(release: dict) -> dict | None:
    for a in release.get("assets") or []:
        if ASSET_RE.match(str(a.get("name", ""))) and safe_url(str(a.get("browser_download_url", ""))):
            return a
    return None


def _http_json(url: str, timeout: float = 10.0) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "TakipliOynat-updater",
                                               "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 - https sabit
        return json.loads(r.read().decode("utf-8"))


def check(repo: str, current: str, fetch=_http_json) -> dict:
    """{state: uptodate|available|error, current, latest?, name?, url?, size?, sha256?, notes?, message?}"""
    if not repo:
        return {"state": "error", "current": current,
                "message": "güncelleme deposu ayarlı değil (config.json: update_repo = \"sahip/depo\")"}
    try:
        rel = fetch(API.format(repo=repo))
    except Exception as e:  # noqa: BLE001 - ağ/JSON/404: kullanıcıya düz mesaj
        return {"state": "error", "current": current, "message": f"sürüm bilgisi alınamadı: {e}"}
    latest = str(rel.get("tag_name", "")).lstrip("vV")
    if not latest or not is_newer(latest, current):
        return {"state": "uptodate", "current": current, "latest": latest or current}
    asset = pick_asset(rel)
    if asset is None:
        return {"state": "error", "current": current, "latest": latest,
                "message": f"{latest} yayınlanmış ama kurulum dosyası (TakipliOynat-Setup-*.exe) yok"}
    digest = str(asset.get("digest") or "")
    return {"state": "available", "current": current, "latest": latest, "name": asset["name"],
            "url": asset["browser_download_url"], "size": int(asset.get("size") or 0),
            "sha256": digest.split(":", 1)[1].lower() if digest.lower().startswith("sha256:") else "",
            "notes": str(rel.get("body") or "")[:600]}


def download(url: str, dest: Path, size: int = 0, sha256: str = "", progress=None,
             opener=urllib.request.urlopen) -> Path:
    """dest'e indirir (önce .part). Boyut/SHA-256 tutmazsa ValueError. progress(done, total)."""
    if not safe_url(url):
        raise ValueError("güvensiz indirme adresi")
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    h, done = hashlib.sha256(), 0
    req = urllib.request.Request(url, headers={"User-Agent": "TakipliOynat-updater"})
    with opener(req, timeout=30) as r, open(part, "wb") as f:  # noqa: S310
        total = size or int(r.headers.get("Content-Length") or 0)
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            h.update(chunk)
            done += len(chunk)
            if progress:
                progress(done, total)
    if size and done != size:
        part.unlink(missing_ok=True)
        raise ValueError(f"indirme eksik ({done}/{size} bayt)")
    if sha256 and h.hexdigest() != sha256.lower():
        part.unlink(missing_ok=True)
        raise ValueError("SHA-256 uyuşmuyor (dosya bozuk)")
    os.replace(part, dest)
    return dest


def launch_installer(path: Path) -> None:
    """Sessiz kurulum, ayrı süreç: Inno Setup açık mpv/python'u kapatır, üstüne kurar."""
    flags = 0
    if sys.platform == "win32":
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
    subprocess.Popen([str(path), "/SILENT", "/NORESTART"], creationflags=flags,  # noqa: S603
                     close_fds=True)


def install(info: dict, progress_file: Path | None = None, launch=launch_installer,
            dl=download, tmp: Path | None = None) -> dict:
    """check() sonucundan indir → doğrula → kurulumu başlat. İlerleme JSON dosyasına yazılır."""
    def write(state: str, **kw) -> dict:
        doc = {"state": state, **kw}
        if progress_file:
            try:
                progress_file.write_text(json.dumps(doc), encoding="utf-8")
            except OSError:
                pass
        return doc

    try:
        dest = (tmp or Path(tempfile.gettempdir()) / "TakipliOynat-update") / info["name"]
        last = [-1]

        def prog(done: int, total: int) -> None:
            pct = int(done * 100 / total) if total else 0
            if pct != last[0]:
                last[0] = pct
                write("downloading", pct=pct)

        write("downloading", pct=0)
        dl(info["url"], dest, int(info.get("size") or 0), str(info.get("sha256") or ""), prog)
        launch(dest)
        return write("launched", version=info.get("latest", ""))
    except Exception as e:  # noqa: BLE001
        return write("error", error=str(e))
