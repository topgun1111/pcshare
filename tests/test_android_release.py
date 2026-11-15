"""Android Parça 15: ortak VERSION, imzalama ayarı sır sızdırmıyor, lisans bildirimi var."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
WORKFLOWS = ROOT / ".github" / "workflows"


def _text(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def test_version_is_x_y_z_and_gradle_reads_the_same_file():
    v = _text(ROOT / "VERSION").strip()
    assert re.fullmatch(r"\d+\.\d+\.\d+", v), v
    g = _text(ANDROID / "app" / "build.gradle.kts")
    assert '"../VERSION"' in g  # PC ve Android aynı VERSION dosyasını okur
    # versionCode = major*10000 + minor*100 + patch (core versionCode() ile aynı)
    assert "* 10000" in g and "* 100" in g
    major, minor, patch = (int(x) for x in v.split("."))
    assert major * 10000 + minor * 100 + patch >= 10000


def test_release_signing_comes_from_the_environment_only():
    g = _text(ANDROID / "app" / "build.gradle.kts")
    for key in ("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
        assert f'System.getenv("{key}")' in g
    assert not re.search(r"storePassword\s*=\s*\"", g)  # düz metin parola yok
    # anahtar dosyası depoda olmamalı
    assert not list(ANDROID.rglob("*.jks")) and not list(ANDROID.rglob("*.keystore"))


def test_android_workflow_release_step():
    y = _text(WORKFLOWS / "android.yml")
    for secret in ("KEYSTORE_B64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
        assert f"secrets.ANDROID_{secret}" in y
    assert re.search(r"assemble(\$\{\{ matrix\.gradle_task \}\})?Release", y)  # iki derleme (pose/sam) matrix ile
    assert "matrix.flavor" in y and "- flavor: sam" in y
    # AGPL modelli APK yalnız özel depoda Release'e eklenir
    assert "github.event.repository.private == true" in y
    assert 'rm -f "$KEYSTORE_PATH"' in y  # anahtar dosyası iş bitince silinir
    assert "android" not in _text(WORKFLOWS / "build.yml").lower()  # PC iş akışına dokunulmadı


def test_notices_cover_the_android_apk():
    n = _text(ROOT / "THIRD_PARTY_NOTICES.md")
    sec = n[n.index("## 5. Android"):]
    for name in ("ONNX Runtime", "QNN", "OpenCV", "Media3", "Compose", "AGPL-3.0"):
        assert name in sec, name


def test_android_strings_en_tr_match():
    def names(p):
        return set(re.findall(r'<string name="([^"]+)"', _text(p)))

    res = ANDROID / "app" / "src" / "main" / "res"
    assert names(res / "values" / "strings.xml") == names(res / "values-tr" / "strings.xml")
