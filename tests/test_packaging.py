"""Parça 14: paketleme (gömülü Python yardımcıları, başlatıcılar, installer, IPC bekleme)."""
import importlib.util
import re
from pathlib import Path

from tracker.ipc import MpvIpcError, wait_for_ipc

ROOT = Path(__file__).resolve().parent.parent


def _load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


emb = _load("build_embedded")
pkg = _load("make_package")

PTH = "python311.zip\n.\n\n# Uncomment to run site.main() automatically\n#import site\n"


def test_patch_pth_enables_site_and_adds_app_root():
    out = emb.patch_pth(PTH)
    lines = out.splitlines()
    assert "import site" in lines and ".." in lines and "#import site" not in out
    assert lines.index("..") < lines.index("import site")  # yol girdileri önce
    assert emb.patch_pth(out) == out  # idempotent
    assert emb.patch_pth("python311.zip\r\n.\r\n").splitlines()[-1] == "import site"
    assert emb.patch_pth("import site\n").count("import site") == 1


def test_prune_removes_caches_tests_and_pip_only(tmp_path):
    sp = tmp_path / "site-packages"
    for d in ("numpy/core", "numpy/tests", "numpy/core/tests", "cv2/__pycache__", "pip",
              "pip-24.0.dist-info", "setuptools", "onnxruntime/capi"):
        (sp / d).mkdir(parents=True)
        (sp / d / "x.py").write_text("1")
    (sp / "numpy/core/m.cpython-311.pyc").write_bytes(b"")
    (sp / "onnxruntime/capi/onnxruntime.dll").write_bytes(b"MZ")
    dirs, files = emb.prune(sp)
    assert dirs >= 5 and files >= 1
    for gone in ("numpy/tests", "numpy/core/tests", "cv2/__pycache__", "pip", "setuptools",
                 "pip-24.0.dist-info"):
        assert not (sp / gone).exists(), gone
    assert (sp / "numpy/core/x.py").exists() and (sp / "onnxruntime/capi/onnxruntime.dll").exists()
    assert not (sp / "numpy/core/m.cpython-311.pyc").exists()
    assert emb.prune(tmp_path / "yok") == (0, 0)


def test_python_pin_matches_requirements_markers():
    assert re.fullmatch(r"3\.11\.\d+", emb.PY_VERSION)  # requirements cp311 tekerlekleri
    assert emb.PY_URL.endswith(f"python-{emb.PY_VERSION}-embed-amd64.zip")
    req = (ROOT / "requirements.txt").read_text(encoding="utf-8")
    assert "onnxruntime-directml" in req and 'sys_platform == "win32"' in req  # DirectML varsayılan


def test_make_package_copies_launchers_with_crlf(tmp_path, monkeypatch):
    monkeypatch.setattr(pkg, "OUT", tmp_path / "TakipliOynat")
    assert pkg.main() == 0
    out = tmp_path / "TakipliOynat"
    for name in ("Baslat.bat", "Offline.bat", "VERSION", "README.md", "requirements.txt",
                 "models/MODELLER.txt", "tracker/main.py", "mpv/portable_config/input.conf"):
        assert (out / name).is_file(), name
    raw = (out / "Baslat.bat").read_bytes()
    assert b"\r\n" in raw and b"\n" not in raw.replace(b"\r\n", b"")  # tüm satır sonları CRLF
    assert not list(out.rglob("__pycache__"))


def test_launchers_use_embedded_python_and_wait_for_mpv():
    live = (ROOT / "launcher" / "Baslat.bat").read_text(encoding="utf-8")
    assert "mpv\\mpv.exe" in live  # takip programını track.lua kendisi başlatır
    lua = (ROOT / "mpv" / "portable_config" / "scripts" / "track.lua").read_text(encoding="utf-8")
    assert "pythonw.exe" in lua and '"live", "--wait"' in lua and "APPDATA" in lua
    off = (ROOT / "launcher" / "Offline.bat").read_text(encoding="utf-8")
    assert "offline-view" in off and "--target-id" in off and "%~1.track.json" in off
    for t in (off,):
        assert t.isascii() or "chcp 65001" in t  # UTF-8 kod sayfası ya da ASCII
        assert "yolox_s" in t and "yolov8n" in t and "HASMODEL" in t  # model eksikse anlaşılır hata
        msg = t.split("HASMODEL")[-1].split("echo HATA")[1].split("\n")[0]
        assert "(" not in msg  # echo içindeki parantez if bloğunu bozar


def test_installer_references_existing_files_and_win10_directml():
    iss = (ROOT / "installer.iss").read_text(encoding="utf-8")
    assert "MinVersion=10.0.18362" in iss and "ArchitecturesAllowed=x64compatible" in iss
    for bat in re.findall(r'\{app\}\\(\w+\.bat)', iss):
        assert (ROOT / "launcher" / bat).is_file(), bat
    # kişisel kullanım: YOLOX-S ve pose modeli kurulumla gelir (model dosyaları dışlanmaz)
    assert "Excludes" not in iss
    assert "uninsneveruninstall" in iss
    assert 'MessagesFile: "compiler:Languages\\Turkish.isl"' in iss


def test_build_workflow_orders_steps_and_checks_directml():
    wf = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    order = [wf.index(k) for k in ("make_package.py", "build_embedded.py", "fetch_deps.ps1",
                                   "fetch_model.ps1", "DmlExecutionProvider", "Compress-Archive",
                                   "iscc")]
    assert order == sorted(order)


def test_wait_for_ipc_retries_until_open_then_gives_up():
    calls, slept = [], []
    now = [0.0]

    def connect_ok_on_3rd():
        calls.append(1)
        if len(calls) < 3:
            raise MpvIpcError("yok")

    assert wait_for_ipc("p", 10, connect=connect_ok_on_3rd, sleep=lambda s: slept.append(s) or
                        now.__setitem__(0, now[0] + s), clock=lambda: now[0])
    assert len(calls) == 3 and slept == [0.5, 0.5]

    now[0], calls.clear()

    def never():
        calls.append(1)
        raise MpvIpcError("yok")

    assert not wait_for_ipc("p", 1.0, connect=never,
                            sleep=lambda s: now.__setitem__(0, now[0] + s), clock=lambda: now[0])
    assert len(calls) == 3  # t=0, 0.5, 1.0 → süre doldu
    assert wait_for_ipc("p", 0, connect=lambda: None)


def test_cli_missing_config_uses_defaults_and_live_wait_failure(tmp_path, monkeypatch, capsys):
    import tracker.ipc as ipc
    from tracker.main import main

    monkeypatch.setattr(ipc, "wait_for_ipc", lambda path, timeout, **kw: False)
    missing = tmp_path / "yeni" / "config.json"
    assert main(["--config", str(missing), "live", "--wait", "5"]) == 1  # config yok → hata değil
    assert "IPC kanalı açılmadı" in capsys.readouterr().err
    from tracker.config import Config

    Config(ipc_path="x").save(missing)  # üst klasör yoksa oluşturulur
    assert missing.is_file()


def test_config_save_creates_parent_dirs(tmp_path):
    from tracker.config import Config

    p = tmp_path / "a" / "b" / "c.json"
    Config(ipc_path="x", target_fill=0.4).save(p)
    assert Config.load(p).target_fill == 0.4


def test_installer_upgrades_in_place_and_ships_model_with_license():
    iss = (ROOT / "installer.iss").read_text(encoding="utf-8")
    # güncelleme: aynı AppId → eskinin üstüne, aynı klasör/yetki, çalışan uygulama kapatılır
    assert re.search(r"^AppId=\{\{[0-9A-F-]{36}\}", iss, re.M)
    for key in ("UsePreviousAppDir=yes", "UsePreviousPrivileges=yes", "CloseApplications=yes",
                "RestartApplications=no", "DisableDirPage=auto", "DisableProgramGroupPage=auto"):
        assert key in iss, key
    sect = iss.split("[InstallDelete]")[1].split("\n[")[0]
    # eski artıklar silinir…
    inst_delete = "\n".join(ln for ln in sect.splitlines() if not ln.startswith(";"))
    assert r'{app}\python"' in inst_delete and r'{app}\tracker"' in inst_delete
    # …ama kullanıcı verisi (models, %APPDATA%) kalır
    assert "models" not in inst_delete and "AppData" not in inst_delete
    assert "{userappdata}" not in iss
    # model kurulumla gelir: elle kopyalamak gerekmez
    fm = (ROOT / "scripts" / "fetch_model.ps1").read_text(encoding="utf-8")
    assert "yolox_s.onnx" in fm and "YOLOX_LICENSE.txt" in fm and "Megvii-BaseDetection" in fm
    assert "throw" in fm and "SHA256" in fm  # indirme bozuksa derleme durur; özet günlüğe yazılır
    wf = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    assert r"models\yolox_s.onnx" in wf and r"models\yolov8s-pose.onnx" in wf
    assert "export_pose.py" in wf and "tracker detect" in wf
    # kurulum sonrası otomatik başlatma seçeneği (sessiz kurulumda atlanır)
    assert "postinstall" in iss and "skipifsilent" in iss


def test_models_note_says_bundled_and_stays_ascii():
    m = (ROOT / "models" / "MODELLER.txt").read_text(encoding="ascii")
    assert "HAZIR GELIR" in m and "GEREKMEZ" in m


def test_models_manifest_matches_workflow_installer_and_verifier(tmp_path):
    """I1: zorunlu dosya listesi tek yerde (manifest); workflow/installer/doğrulayıcı uyumlu."""
    vm = _load("verify_models")
    files = vm.load_manifest()
    paths = {e["path"] for e in files}
    assert {"models/yolox_s.onnx", "models/yolov8s-pose.onnx", "ffmpeg/ffmpeg.exe"} <= paths
    wf = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    for e in files:
        # her zorunlu dosya workflow'da duman testinde ya da manifest denetiminde geçmeli
        assert e["path"].split("/")[-1] in wf or "verify_models.py" in wf
        assert e["min_bytes"] > 0 and e["license"]
    order = [wf.index(k) for k in ("fetch_deps.ps1", "fetch_model.ps1", "export_pose.py",
                                   "fetch_ffmpeg.ps1", "verify_models.py", "DmlExecutionProvider",
                                   "Compress-Archive", "iscc")]
    assert order == sorted(order)  # tüm bileşenler paketlenmeden doğrulama/zip/installer yok
    assert "libx264" in wf and "[1,56,N]" in wf
    iss = (ROOT / "installer.iss").read_text(encoding="utf-8")
    assert r'{app}\ffmpeg' in iss  # güncellemede eski ffmpeg yenilenir
    ff = (ROOT / "scripts" / "fetch_ffmpeg.ps1").read_text(encoding="utf-8")
    assert "libx264" in ff and "throw" in ff and "BUILD_INFO.txt" in ff and "SHA256" in ff
    assert "-gpl" in ff  # LGPL derlemesinde libx264 yok


def test_verify_models_detects_missing_small_bad_hash_and_missing_license(tmp_path):
    vm = _load("verify_models")
    files = [
        {"path": "models/a.onnx", "min_bytes": 10, "license": "X",
         "license_file": "models/A_LIC.txt", "sha256": None},
        {"path": "bin/f.exe", "min_bytes": 5, "license": "Y", "license_file": None, "sha256": None},
    ]
    errs, sums = vm.check(tmp_path, files)
    assert len(errs) == 2 and all(e.startswith("EKSİK") for e in errs) and not sums
    (tmp_path / "models").mkdir()
    (tmp_path / "bin").mkdir()
    (tmp_path / "models" / "a.onnx").write_bytes(b"x" * 4)  # çok küçük
    (tmp_path / "bin" / "f.exe").write_bytes(b"12345")
    errs, sums = vm.check(tmp_path, files)
    assert len(errs) == 1 and errs[0].startswith("ÇOK KÜÇÜK") and list(sums) == ["bin/f.exe"]
    (tmp_path / "models" / "a.onnx").write_bytes(b"y" * 20)
    errs, _ = vm.check(tmp_path, files)
    assert len(errs) == 1 and errs[0].startswith("LİSANS DOSYASI EKSİK")
    (tmp_path / "models" / "A_LIC.txt").write_text("lisans")
    assert vm.check(tmp_path, files)[0] == []
    files[1]["sha256"] = "0" * 64  # sabitlenmiş özet uyuşmuyor → derleme durmalı
    errs, _ = vm.check(tmp_path, files)
    assert len(errs) == 1 and errs[0].startswith("ÖZET UYUŞMUYOR")
    # büyük/küçük harf fark etmez
    files[1]["sha256"] = vm.sha256_of(tmp_path / "bin" / "f.exe").upper()
    assert vm.check(tmp_path, files)[0] == []


def test_verify_models_cli_writes_sums_and_fails_on_incomplete_package(tmp_path, capsys):
    vm = _load("verify_models")
    assert vm.main(["verify_models.py", str(tmp_path)]) == 1  # boş paket: hepsi eksik
    err = capsys.readouterr().err
    assert "models/yolox_s.onnx" in err and "ffmpeg/ffmpeg.exe" in err
    for e in vm.load_manifest():  # sahte ama yeterince büyük dosyalar + lisanslar
        p = tmp_path / e["path"]
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"\0" * e["min_bytes"])
        if e["license_file"]:
            lp = tmp_path / e["license_file"]
            lp.parent.mkdir(parents=True, exist_ok=True)
            lp.write_text("lisans")
    assert vm.main(["verify_models.py", str(tmp_path), "--write-sums"]) == 0
    sums = (tmp_path / "models" / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
    assert len(sums) == len(vm.load_manifest()) and all(len(ln.split("  ")[0]) == 64 for ln in sums)


def test_make_package_ships_manifest_and_notices_cover_ffmpeg(tmp_path, monkeypatch):
    monkeypatch.setattr(pkg, "OUT", tmp_path / "TakipliOynat")
    assert pkg.main() == 0
    assert (tmp_path / "TakipliOynat" / "models" / "manifest.json").is_file()
    n = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    for key in ("ffmpeg", "libx264", "yolov8s-pose", "FFMPEG_LICENSE.txt", "manifest.json", "x264"):
        assert key in n, key
    m = (ROOT / "models" / "MODELLER.txt").read_text(encoding="ascii")
    assert "ffmpeg" in m and "manifest.json" in m and "SHA256SUMS" in m


def test_verify_models_optional_file_missing_is_warning_but_present_is_checked(tmp_path, capsys):
    vm = _load("verify_models")
    files = [{"path": "models/o.onnx", "min_bytes": 10, "license": "X", "license_file": None,
              "sha256": None, "optional": True}]
    assert vm.check(tmp_path, files) == ([], {})
    assert "UYARI" in capsys.readouterr().out
    (tmp_path / "models").mkdir()
    (tmp_path / "models" / "o.onnx").write_bytes(b"x")  # var ama bozuk → hata
    assert vm.check(tmp_path, files)[0][0].startswith("ÇOK KÜÇÜK")
