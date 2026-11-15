"""Bump the app version in every place that pins it (VERSION, tracker/__init__.py, tests/test_license.py).

Usage: python scripts/bump_version.py patch|minor|major|X.Y.Z
Then: git commit -am "Version X.Y.Z"; git tag vX.Y.Z; git push --tags   (a v* tag publishes a normal Release).
Every push to main already publishes a prerelease `vX.Y.Z-b<run number>` that installed apps see as an update; bump VERSION
when you want a new public number (or before 1000 builds of the same version: the build number is capped at 999).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def bump(cur: str, how: str) -> str:
    if re.fullmatch(r"\d+\.\d+\.\d+", how):
        return how
    major, minor, patch = (int(x) for x in cur.split("."))
    if how == "major":
        return f"{major + 1}.0.0"
    if how == "minor":
        return f"{major}.{minor + 1}.0"
    if how == "patch":
        return f"{major}.{minor}.{patch + 1}"
    raise SystemExit(f"unknown bump '{how}' (patch | minor | major | X.Y.Z)")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    vfile = ROOT / "VERSION"
    cur = vfile.read_text(encoding="utf-8").strip()
    new = bump(cur, argv[1])
    vfile.write_text(new + "\n", encoding="utf-8")
    for rel, pattern in (("tracker/__init__.py", r'__version__ = "{}"'), ("tests/test_license.py", r'== "{}"')):
        p = ROOT / rel
        if not p.is_file():
            continue
        s = p.read_text(encoding="utf-8")
        old = pattern.format(re.escape(cur))
        if not re.search(old, s):
            print(f"warning: {rel} does not pin {cur}; check it by hand")
            continue
        p.write_text(re.sub(old, pattern.format(new).replace("\\", ""), s), encoding="utf-8")
    print(f"{cur} -> {new}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
