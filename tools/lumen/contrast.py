#!/usr/bin/env python3
"""Lumen T1: contrast table (WCAG) for every theme in tokens.json (fresh slots derived as gen.py does) and for a grid of Custom-theme
inputs (derive.from_inputs). Exit 1 if any pair fails and --strict."""
import json, sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import derive
from derive import cr
T = json.loads((pathlib.Path(__file__).parent / "tokens.json").read_text(encoding="utf-8"))
PAIRS = [("ink","canvas",4.5),("ink","surface",4.5),("ink","surfaceHi",4.5),("inkMut","canvas",4.5),("inkMut","surface",4.5),
         ("inkMut","surfaceHi",4.5),("onPrimary","primary",4.5),("onPrimaryCont","primaryCont",4.5),("onErrorCont","errorCont",4.5),("primary","canvas",3.0),
         ("inkDim","canvas",3.0),("secondary","canvas",3.0),("secondary","surface",3.0),("tertiary","canvas",3.0),("tertiary","surface",3.0),
         ("focus","canvas",3.0),("focus","surface",3.0)]
def check(d):
    return [f"{a}/{b} {cr(d[a][:7], d[b][:7]):.2f}<{m}" for a, b, m in PAIRS if a in d and b in d and cr(d[a][:7], d[b][:7]) < m]
bad = 0
for name, d in T["themes"].items():
    full = dict(d)
    full.update(T["fresh"]["dark"] if name == "dark" else derive.fresh(d, T.get("freshOverride", {}).get(name)))
    fails = check(full)
    bad += len(fails)
    if fails: print(f"{name}: " + "; ".join(fails))
print(f"themes {len(T['themes'])}, failing pairs {bad}")
n = snapped = cbad = 0
for mode in ("light", "dark", "amoled"):
    for hue in range(0, 360, 15):
        for tint in (0, 2, 4, 6, 8):
            d, sn = derive.from_inputs(hue, tint, mode)
            f = check(d); n += 1; snapped += sn; cbad += len(f)
            if f: print(f"custom {mode} h{hue} t{tint}: " + "; ".join(f))
print(f"custom grid {n}, snapped {snapped}, failing pairs {cbad}")
sys.exit(1 if (bad or cbad) and "--strict" in sys.argv else 0)
