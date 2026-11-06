#!/usr/bin/env python3
"""Lumen T1: contrast table for every theme in tokens.json (WCAG). Exit 1 if any pair fails and --strict."""
import json, sys, pathlib
T = json.loads((pathlib.Path(__file__).parent / "tokens.json").read_text(encoding="utf-8"))
PAIRS = [("ink","canvas",4.5),("ink","surface",4.5),("ink","surfaceHi",4.5),("inkMut","canvas",4.5),("inkMut","surface",4.5),
         ("inkMut","surfaceHi",4.5),("onPrimary","primary",4.5),("onPrimaryCont","primaryCont",4.5),("onErrorCont","errorCont",4.5),("primary","canvas",3.0)]
def rgb(h):
    h = h.strip().lstrip("#")
    if len(h) in (3, 4): h = "".join(c * 2 for c in h)
    return [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
def lin(c): return c / 12.92 if c <= .03928 else ((c + .055) / 1.055) ** 2.4
def L(h): r, g, b = (lin(c) for c in rgb(h)); return .2126 * r + .7152 * g + .0722 * b
def cr(a, b): x, y = sorted((L(a), L(b)), reverse=True); return (x + .05) / (y + .05)
bad = 0
for name, d in T["themes"].items():
    fails = [f"{a}/{b} {cr(d[a], d[b]):.2f}<{m}" for a, b, m in PAIRS if a in d and b in d and cr(d[a], d[b]) < m]
    bad += len(fails)
    if fails: print(f"{name}: " + "; ".join(fails))
print(f"themes {len(T['themes'])}, failing pairs {bad}")
sys.exit(1 if bad and "--strict" in sys.argv else 0)
