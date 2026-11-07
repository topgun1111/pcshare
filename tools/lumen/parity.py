#!/usr/bin/env python3
"""Lumen Part 17: derive.py (Python) and custom.js (page) must give identical hex for the same inputs. Needs node. Exit 1 on any difference."""
import json, subprocess, sys, pathlib
here = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(here))
import derive
T = json.loads((here / "tokens.json").read_text(encoding="utf-8"))
grid = [(h, t, m) for m in ("light", "dark", "amoled") for h in range(0, 360, 7) for t in (0, 1.5, 4, 8)]
js = (here / "custom.js").read_text(encoding="utf-8") + """
var G=%s,P=%s,out={c:[],p:{}};
G.forEach(function(g){var r=lmFromInputs(g[0],g[1],g[2]);out.c.push([r.d,r.sn])});
Object.keys(P).forEach(function(n){out.p[n]=lmFresh(P[n])});
console.log(JSON.stringify(out));""" % (json.dumps(grid), json.dumps(T["themes"]))
r = subprocess.run(["node", "-e", js], capture_output=True, text=True)
if r.returncode: print(r.stderr[:500]); sys.exit(1)
o = json.loads(r.stdout)
bad = 0
for g, (d, sn) in zip(grid, o["c"]):
    pd, psn = derive.from_inputs(*g)
    if pd != d or psn != sn:
        bad += 1
        if bad < 6: print("custom differs", g, {k: (pd[k], d.get(k)) for k in pd if pd[k] != d.get(k)})
for n, d in T["themes"].items():
    pf = derive.fresh(d)
    if pf != o["p"][n]:
        bad += 1
        if bad < 6: print("fresh differs", n, pf, o["p"][n])
print(f"parity: {len(grid)} custom inputs + {len(T['themes'])} presets, {bad} differences")
sys.exit(1 if bad else 0)
