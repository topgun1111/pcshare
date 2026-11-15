"""Yardım → Güncellemeleri denetle: sürüm karşılaştırma, varlık seçimi, indirme doğrulama, akış."""
import hashlib
import io
import json

import pytest

from tracker import update
from tracker.config import Config


def rel(tag="v1.3.0", name="TakipliOynat-Setup-1.3.0.exe", url=None, digest=None):
    a = {"name": name, "size": 6, "digest": digest,
         "browser_download_url": url or f"https://github.com/o/r/releases/download/{tag}/{name}"}
    return {"tag_name": tag, "body": "notlar", "assets": [a, {"name": "x.zip", "browser_download_url": "https://github.com/x.zip"}]}


def test_version_compare():
    assert update.parse_version("v1.10.0") > update.parse_version("1.9.9")
    assert update.parse_version("1.3") == (1, 3, 0)
    assert update.is_newer("1.3.0", "1.2.0") and not update.is_newer("1.2.0", "1.2.0")
    assert not update.is_newer("1.2.0-rc1", "1.2.0")
    assert update.parse_version("saçma") == (0, 0, 0)


def test_resolve_repo(tmp_path):
    assert update.resolve_repo("a/b", tmp_path) == "a/b"
    assert update.resolve_repo("", tmp_path) == ""
    (tmp_path / "update.json").write_text(json.dumps({"repo": "me/app"}), encoding="utf-8")
    assert update.resolve_repo("", tmp_path) == "me/app"
    assert update.resolve_repo("bad repo", tmp_path / "yok") == ""  # geçersiz biçim reddedilir
    assert Config().update_repo == ""


def test_check_states():
    assert update.check("", "1.2.0")["state"] == "error"
    assert update.check("o/r", "1.2.0", fetch=lambda u: rel("v1.2.0"))["state"] == "uptodate"
    assert update.check("o/r", "1.2.0", fetch=lambda u: rel("v1.1.0"))["state"] == "uptodate"
    d = update.check("o/r", "1.2.0", fetch=lambda u: rel(digest="sha256:ABCD"))
    assert d["state"] == "available" and d["latest"] == "1.3.0" and d["sha256"] == "abcd"
    assert d["name"].endswith(".exe") and d["size"] == 6
    # kurulum dosyası yok / güvensiz adres → hata, asla indirilmez
    r = rel()
    r["assets"] = [{"name": "TakipliOynat-Setup-1.3.0.exe", "browser_download_url": "http://evil.example/a.exe"}]
    assert update.check("o/r", "1.2.0", fetch=lambda u: r)["state"] == "error"

    def boom(u):
        raise OSError("ağ yok")

    e = update.check("o/r", "1.2.0", fetch=boom)
    assert e["state"] == "error" and "ağ yok" in e["message"]


class FakeResp(io.BytesIO):
    headers = {}

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def test_download_verifies_size_and_hash(tmp_path):
    data = b"abcdef"
    ok = lambda req, timeout=0: FakeResp(data)  # noqa: E731
    got = []
    dest = update.download("https://github.com/o/r/a.exe", tmp_path / "a.exe", 6,
                           hashlib.sha256(data).hexdigest(), lambda d, t: got.append((d, t)), opener=ok)
    assert dest.read_bytes() == data and got[-1] == (6, 6) and not (tmp_path / "a.exe.part").exists()
    with pytest.raises(ValueError, match="SHA-256"):
        update.download("https://github.com/o/r/a.exe", tmp_path / "b.exe", 6, "00" * 32, opener=ok)
    assert not (tmp_path / "b.exe").exists() and not (tmp_path / "b.exe.part").exists()
    with pytest.raises(ValueError, match="eksik"):
        update.download("https://github.com/o/r/a.exe", tmp_path / "c.exe", 99, opener=ok)
    with pytest.raises(ValueError, match="güvensiz"):
        update.download("http://github.com/a.exe", tmp_path / "d.exe", opener=ok)
    with pytest.raises(ValueError, match="güvensiz"):
        update.download("https://evilgithub.com.example.org/a.exe", tmp_path / "d.exe", opener=ok)


def test_install_flow_writes_progress_and_launches(tmp_path):
    info = {"name": "S.exe", "url": "https://github.com/o/r/S.exe", "size": 3, "sha256": "", "latest": "1.3.0"}
    launched = []

    def dl(url, dest, size, sha, prog):
        prog(1, 3)
        prog(3, 3)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"x")
        return dest

    pf = tmp_path / "u.json"
    res = update.install(info, pf, launch=launched.append, dl=dl, tmp=tmp_path / "dl")
    assert res["state"] == "launched" and launched == [tmp_path / "dl" / "S.exe"]
    assert json.loads(pf.read_text(encoding="utf-8"))["state"] == "launched"

    def bad(*a):
        raise ValueError("SHA-256 uyuşmuyor")

    res = update.install(info, pf, launch=launched.append, dl=bad, tmp=tmp_path / "dl")
    assert res["state"] == "error" and len(launched) == 1  # doğrulama başarısızsa kurulum başlamaz


def test_menu_wiring():
    from pathlib import Path

    root = Path(__file__).resolve().parent.parent / "mpv/portable_config"
    lua = (root / "scripts/track.lua").read_text(encoding="utf-8")
    menu = (root / "script-modules/trackmenu.lua").read_text(encoding="utf-8")
    assert 'cmd = "update"' in menu and "FILES.update = function" in lua
    assert '"update", "check"' in lua and '"update", "install"' in lua
