"""Android golden fixtures must match the Python reference (refresh: scripts/gen_golden.py)."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "android" / "core" / "src" / "test" / "resources" / "golden"


def _gen():
    spec = importlib.util.spec_from_file_location("gen_golden", ROOT / "scripts" / "gen_golden.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_golden_fixtures_not_stale():
    built = _gen().build()
    assert built, "no fixtures"
    for name, text in built.items():
        f = GOLDEN / name
        assert f.is_file(), f"{name} missing: run python scripts/gen_golden.py"
        assert f.read_text(encoding="utf-8") == text, f"{name} stale: run gen_golden.py"
    extra = {p.name for p in GOLDEN.glob("*.json")} - set(built)
    assert not extra, f"orphan fixtures: {extra}"
