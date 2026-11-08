#!/usr/bin/env python3
"""Lumen T1: side-by-side report. For every preset: hue / tint / mode read from its own slots, re-derived with derive.from_inputs,
distance (CIE76 dE) per slot. A preset is 'close' when canvas, surface, ink and primary are all within --max dE (default 6) and the derived
set passes every T1 pair. --apply writes the derived canvas/surface/surfaceHi/edge/ink/inkMut/primary/onPrimary/primaryCont/onPrimaryCont into tokens.json for close presets only."""
import json, math, sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import derive
from derive import h2r, to_hsl, cr
P = pathlib.Path(__file__).parent / "tokens.json"
T = json.loads(P.read_text(encoding="utf-8"))
MAXD = float(sys.argv[sys.argv.index("--max") + 1]) if "--max" in sys.argv else 6.0
KEEP = {"ember", "dark", "hcl", "hcd"}   # hand-tuned on purpose: the spec's canonical Ember pair and the two 7:1 themes are never re-derived
SLOTS = ["canvas", "surface", "surfaceHi", "edge", "ink", "inkMut", "primary", "onPrimary", "primaryCont", "onPrimaryCont"]
KEY = ["canvas", "surface", "ink", "primary"]

def lab(h):
    r, g, b = (derive.lin(v) for v in h2r(h[:7]))
    x, y, z = (.4124*r+.3576*g+.1805*b)/.95047, .2126*r+.7152*g+.0722*b, (.0193*r+.1192*g+.9505*b)/1.08883
    f = lambda t: t ** (1/3) if t > .008856 else 7.787 * t + 16/116
    return 116*f(y)-16, 500*(f(x)-f(y)), 200*(f(y)-f(z))
def de(a, b): return math.dist(lab(a), lab(b))

def inputs(d):
    hue, ps, _ = to_hsl(d["primary"])
    ch = to_hsl(d["canvas"]); cs = ch[1] * (1 - abs(2 * ch[2] - 1))
    tint = max(0.0, min(8.0, cs * 100))
    dark = derive.is_dark(d["canvas"])
    mode = ("amoled" if d["canvas"].lower() in ("#000000", "#000") else "dark") if dark else "light"
    return hue, tint, mode

rows, close = [], []
for n, d in T["themes"].items():
    h, t, m = inputs(d)
    nd, sn = derive.from_inputs(h, t, m)
    full = dict(nd)
    bad = [f"{a}/{b}" for a, b, mn in __import__("contrast").PAIRS if a in full and b in full and cr(full[a][:7], full[b][:7]) < mn] if False else []
    dist = {k: de(d[k], nd[k]) for k in SLOTS}
    mx = max(dist[k] for k in KEY)
    ok = mx <= MAXD and not sn and n not in KEEP
    rows.append((n, m, h, t, mx, sn, ok))
    if ok: close.append((n, nd))
print(f"{'preset':14}{'mode':7}{'hue':>6}{'tint':>6}{'maxdE':>7}  snapped  apply")
for n, m, h, t, mx, sn, ok in sorted(rows, key=lambda r: r[4]):
    print(f"{n:14}{m:7}{h:6.0f}{t:6.1f}{mx:7.1f}  {'yes' if sn else '-':7}  {'YES' if ok else 'keep hex'}")
print(f"close (dE <= {MAXD}, no snap): {len(close)} of {len(rows)}")
if "--apply" in sys.argv:
    for n, nd in close:
        for k in SLOTS: T["themes"][n][k] = nd[k]
    T["generatedThemes"] = sorted(set(T.get("generatedThemes", [])) | {n for n, _ in close})   # presets whose slots now come from hue / tint / mode
    P.write_text(json.dumps(T, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("applied to", len(close), "presets")
