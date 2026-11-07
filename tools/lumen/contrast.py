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
         ("focus","canvas",3.0),("focus","surface",3.0),("onWarning","warning",4.5),
         ("onError","error",4.5),("onSuccess","success",4.5),("onInfo","info",4.5),("info","canvas",4.5),("info","surface",4.5),("ink","successCont",4.5),("ink","warningCont",4.5)]
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
# T1b: category tiles (spec 4.5 / 5.3). Glyph (white on light tiles, deep tone on dark tiles) against both gradient ends must reach 3:1 (graphic object).
C = T["category"]; kbad = 0
def tile_ends(b): return derive.mix(b, "#ffffff", C["tileTop"]), derive.mix(b, "#000000", C["tileBottom"])
def cat_pairs(label, mode, mono=None):
    n = 0
    for i, k in enumerate(C["list"]):
        b = derive.mix(mono["ink"][:7], mono["canvas"][:7], i * 0.045) if mono else C["list"][k][mode]
        g = "#ffffff" if mode == "light" else derive.mix(b, "#000000", .80)
        for e in tile_ends(b):
            r = cr(g, e)
            if r < 3.0: print(f"category {label} {k} glyph/tile {r:.2f}<3.0"); n += 1
    return n
kbad += cat_pairs("light", "light") + cat_pairs("dark", "dark")
for name in C["mono"]:
    d = T["themes"][name]; kbad += cat_pairs(name, "dark" if derive.is_dark(d["canvas"]) else "light", d)
print(f"category tiles {len(C['list'])} x 2 modes + {len(C['mono'])} mono themes, failing pairs {kbad}")

# ---- P7: gradient ends against their labels, and glass tint alpha floor ----------------------------------------------------------------
# gPrimary = primary 94 % + white 6 % -> primary 88 % + black 12 % (label onPrimary); gPrimarySoft = primaryCont -> primaryCont 90 % + primary 10 % (label onPrimaryCont).
GLASS_MIN = 70   # lowest tint alpha (%) of the text-bearing glass steps (--gA1 / --gA2 in ui.html; the 58 % --gB1 is for borders / highlights only)
def grad_check(d):
    out = []
    for lab, bg, ends in (("onPrimary", "primary", (derive.mix(d["primary"][:7], "#ffffff", .06), derive.mix(d["primary"][:7], "#000000", .12))),
                          ("onPrimaryCont", "primaryCont", (d["primaryCont"][:7], derive.mix(d["primaryCont"][:7], d["primary"][:7], .10)))):
        for e in ends:
            r = cr(d[lab][:7], e)
            if r < 4.5: out.append(f"grad {bg} end {e} vs {lab} {r:.2f}<4.5")
    return out
def glass_floor(d):
    """Smallest tint alpha (%) at which ink stays >= 4.5 on tint-over-backdrop for the worst backdrop (pure black or white)."""
    t = d["glassTint"][:7]
    for a in range(30, 101):
        if all(cr(d["ink"][:7], derive.mix(bk, t, a / 100.0)) >= 4.5 for bk in ("#000000", "#ffffff")): return a
    return 101
gbad = fl = 0; worst = (0, "")
for name, d in T["themes"].items():
    full = dict(d); full.update(T["fresh"]["dark"] if name == "dark" else derive.fresh(d, T.get("freshOverride", {}).get(name)))
    g = grad_check(full); gbad += len(g)
    if g: print(f"{name}: " + "; ".join(g))
    f = glass_floor(full)
    if f > worst[0]: worst = (f, name)
    if f > GLASS_MIN: fl += 1; print(f"{name}: glass tint floor {f}% > {GLASS_MIN}% (ink on tint over pure black / white backdrop)")
for mode in ("light", "dark", "amoled"):
    for hue in range(0, 360, 30):
        for tint in (0, 4, 8):
            d, _ = derive.from_inputs(hue, tint, mode); gbad += len(grad_check(d))
print(f"P7 gradient ends failing {gbad}; glass tint floor above {GLASS_MIN}%: {fl} themes (highest floor {worst[0]}% {worst[1]})")
# ---- T4: high contrast themes (hcl / hcd) must reach 7:1 for text, 3:1 for graphics and the 3 dp focus ring ------------------------------
HC = [("ink","canvas",7.0),("ink","surface",7.0),("ink","surfaceHi",7.0),("inkMut","canvas",7.0),("inkMut","surface",7.0),("inkMut","surfaceHi",7.0),
      ("onPrimary","primary",7.0),("onPrimaryCont","primaryCont",7.0),("onErrorCont","errorCont",7.0),("primary","canvas",7.0),("primary","surface",7.0),
      ("error","canvas",7.0),("success","canvas",7.0),("onWarning","warning",7.0),("focus","canvas",3.0),("focus","surface",3.0),("edge","canvas",3.0)]
hbad = 0
for name in T["groups"]["Contrast"]:
    if name not in ("hcl", "hcd"): continue
    d = dict(T["themes"][name]); d.update(derive.fresh(d, T.get("freshOverride", {}).get(name)))
    for a, b, m in HC:
        r = cr(d[a][:7], d[b][:7])
        if r < m: hbad += 1; print(f"{name}: HC {a}/{b} {r:.2f}<{m}")
print(f"T4 high contrast 7:1 test (hcl, hcd) failing pairs {hbad}")
sys.exit(1 if (bad or cbad or kbad or gbad or hbad) and "--strict" in sys.argv else 0)
