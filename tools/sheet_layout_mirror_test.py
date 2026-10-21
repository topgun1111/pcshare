#!/usr/bin/env python3
"""Mirror test for core/SheetLayout.kt (Print space part 4).
A line-by-line Python port of the Kotlin model is checked
  (1) against pvSheets() extracted from assets/ui.html (single-sided FIT / FILL, 400 random cases) and
  (2) against hand-computed flip / duplex / pairs / copies cases.
Run:  python3 tools/sheet_layout_mirror_test.py   (needs node for part 1)
This proves the ALGORITHM; it does not prove the Kotlin compiles."""
import json, random, re, subprocess, sys, pathlib

PAPERS = {"A3": (842, 1191), "A4": (595, 842), "A5": (420, 595), "Letter": (612, 792), "Legal": (612, 1008)}
PRESETS = {1: (1, 1), 2: (1, 2), 4: (2, 2), 6: (2, 3), 9: (3, 3)}

def paper(n):
    for k, v in PAPERS.items():
        if k.lower() == (n or "").lower(): return v
    return PAPERS["A4"]

def clean(s):
    s = dict(s); c, r = s["grid"]
    s["grid"] = (min(max(c, 1), 8), min(max(r, 1), 8))
    s["margin"] = min(max(s["margin"], 0.0), 72.0); s["gap"] = min(max(s["gap"], 0.0), 40.0)
    return s

def spec(**k):
    d = dict(paper="A4", orient="AUTO", grid=(1, 1), margin=14.0, gap=8.0, fit="FIT", flip="LONG", autoRot=True, reverse=False)
    d.update(k); return d

def grid_for(s, portrait): c, r = s["grid"]; return (c, r) if portrait else (r, c)

def back_slot(slot, g, flip, portrait):
    c, r = slot % g[0], slot // g[0]
    if (flip == "LONG") == portrait: return r * g[0] + (g[0] - 1 - c)
    return (g[1] - 1 - r) * g[0] + c

def norm(r): return (((r % 360) + 360) % 360 // 90) * 90

def best_turn(p, cw, ch, auto):
    b = None
    for ex in ([0, 90] if auto else [0]):
        r = (norm(p["rot"]) + ex) % 360; t = r % 180 != 0
        w, h = max(1, p["w"]), max(1, p["h"]); dw, dh = (h, w) if t else (w, h)
        k = min(cw / dw, ch / dh)
        if b is None or k > b[1] * 1.0001: b = (r, k, dw, dh)
    return b

def cell_for(slot, idx, p, g, s, sw, sh):
    cw = max(1.0, (sw - 2 * s["margin"] - s["gap"] * (g[0] - 1)) / g[0]); ch = max(1.0, (sh - 2 * s["margin"] - s["gap"] * (g[1] - 1)) / g[1])
    r, fk, dw, dh = best_turn(p, cw, ch, s["autoRot"]); fill = s["fit"] == "FILL"
    kk = max(cw / dw, ch / dh) if fill else fk; col, row = slot % g[0], slot // g[0]
    return dict(slot=slot, pic=idx, rot=r, k=kk, fk=fk, w=cw if fill else dw * kk, h=ch if fill else dh * kk,
                cx=s["margin"] + col * (cw + s["gap"]) + cw / 2, cy=s["margin"] + row * (ch + s["gap"]) + ch / 2, cw=cw, ch=ch)

def compose(s, pics, plan):
    s = clean(s); pw, ph = paper(s["paper"]); portrait = plan["portrait"]
    sw, sh = (pw, ph) if portrait else (ph, pw); g = grid_for(s, portrait)
    any_back = any(e is not None for sh_ in plan["sheets"] for e in sh_["back"]); score = 0.0; pages = []
    for si, sheet in enumerate(plan["sheets"]):
        for side in ("front", "back"):
            if side == "back" and not any_back: continue
            cells = []
            for slot, e in enumerate(sheet[side]):
                if e is not None and 0 <= e < len(pics) and slot < g[0] * g[1]:
                    c = cell_for(slot, e, pics[e], g, s, sw, sh); score += c["fk"] ** 2 * max(1, pics[e]["w"]) * max(1, pics[e]["h"]); cells.append(c)
            pages.append(dict(sheet=si, side=side, cells=cells))
    return dict(portrait=portrait, sw=sw, sh=sh, pages=pages, score=score)

def chunks(l, n): return [l[i:i + n] for i in range(0, len(l), n)]
def pad(l, n): return [l[i] if i < len(l) else None for i in range(n)]
def expand(pics, rev):
    order = list(range(len(pics)))[::-1] if rev else list(range(len(pics))); out = []
    for i in order:
        for _ in range(min(max(pics[i]["copies"], 1), 99)):
            if len(out) < 5000: out.append(i)
    return out
def front_only(seq, per): return [dict(front=pad(c, per), back=[None] * per) for c in chunks(seq, per)]

def choose_orient(s, pics, seq):
    per = s["grid"][0] * s["grid"][1]
    a = compose(s, pics, dict(portrait=True, sheets=front_only(seq, per)))["score"]
    b = compose(s, pics, dict(portrait=False, sheets=front_only(seq, per)))["score"]
    return not (b > a * 1.0001)

def auto_fill(s, pics, mode):
    s = clean(s)
    if not pics: return dict(portrait=s["orient"] != "LANDSCAPE", sheets=[])
    seq = expand(pics, s["reverse"] and mode != "PAIRS")
    portrait = {"PORTRAIT": True, "LANDSCAPE": False}.get(s["orient"]) if s["orient"] != "AUTO" else choose_orient(s, pics, seq)
    g = grid_for(s, portrait); per = g[0] * g[1]
    if mode == "FRONT_ONLY": sheets = front_only(seq, per)
    elif mode == "BOTH_SIDES": sheets = [dict(front=pad(c[:per], per), back=pad(c[per:], per)) for c in chunks(seq, per * 2)]
    else:
        pairs = []
        for i in range(0, len(pics), 2):
            back = i + 1 if i + 1 < len(pics) else None
            for _ in range(min(max(pics[i]["copies"], 1), 99)):
                if len(pairs) < 5000: pairs.append((i, back))
        sheets = []
        for c in chunks(pairs, per):
            f, b = [None] * per, [None] * per
            for j, (pf, pb) in enumerate(c): f[j] = pf; b[back_slot(j, g, s["flip"], portrait)] = pb
            sheets.append(dict(front=f, back=b))
    return dict(portrait=portrait, sheets=sheets)

# ---------------------------------------------------------------- part 1: compare with pvSheets in ui.html
def js_pv(cases):
    src = pathlib.Path(__file__).resolve().parent.parent.joinpath("app/src/main/assets/ui.html").read_text(encoding="utf-8")
    a = re.search(r"const PV_PAPER=.*?;\n", src).group(0)
    g = re.search(r"const PV_GRID=.*?;\n", src).group(0)
    f = src[src.index("function pvSheets"):src.index("/* scale of one page in a cell")]
    js = a + g + f + "\nconst cases=JSON.parse(require('fs').readFileSync(0,'utf8'));console.log(JSON.stringify(cases.map(c=>pvSheets(c.dims,c.rots,c.st))));"
    return json.loads(subprocess.run(["node", "-e", js], input=json.dumps(cases), capture_output=True, text=True, check=True).stdout)

def part1():
    random.seed(7); cases = []; mine = []
    for _ in range(400):
        n = random.randint(1, 14); dims = [[random.randint(200, 6000), random.randint(200, 6000)] for _ in range(n)]
        rots = [random.choice([0, 90, 180, 270]) for _ in range(n)]
        nup = random.choice([1, 2, 4, 6, 9]); pap = random.choice(list(PAPERS)); fit = random.choice(["fit", "fill"])
        marg = random.choice(["", "0", "14", "36"]); rev = random.random() < .3; noauto = random.random() < .3
        cases.append(dict(dims=dims, rots=rots, st=dict(paper=pap, nup=nup, margin=marg, fit=fit, reverse=rev, noauto=noauto)))
        s = spec(paper=pap, grid=PRESETS[nup], margin=14.0 if marg == "" else float(marg), fit=fit.upper(), reverse=rev, autoRot=not noauto)
        pics = [dict(w=d[0], h=d[1], rot=r, copies=1) for d, r in zip(dims, rots)]
        mine.append(compose(s, pics, auto_fill(s, pics, "FRONT_ONLY")))
    ref = js_pv(cases); bad = 0
    for c, m, r in zip(cases, mine, ref):
        ok = abs(m["sw"] - r["sw"]) < 1e-6 and len(m["pages"]) == len(r["sheets"])
        if ok:
            for pg, sh in zip(m["pages"], r["sheets"]):
                if len(pg["cells"]) != len(sh): ok = False; break
                for a, b in zip(pg["cells"], sh):
                    if a["pic"] != b["i"] or a["rot"] != b["r"] or any(abs(a[x] - b[x]) > 1e-6 for x in ("k", "w", "h", "cx", "cy")): ok = False
        if not ok: bad += 1
    print(f"part 1: {len(cases)} cases vs pvSheets, {bad} differences"); return bad

# ---------------------------------------------------------------- part 2: own cases
def check(name, cond):
    print(("ok   " if cond else "FAIL ") + name); return 0 if cond else 1

def part2():
    bad = 0
    g = (2, 3)  # 2 cols x 3 rows, portrait
    bad += check("long edge portrait mirrors columns", [back_slot(i, g, "LONG", True) for i in range(6)] == [1, 0, 3, 2, 5, 4])
    bad += check("short edge portrait mirrors rows", [back_slot(i, g, "SHORT", True) for i in range(6)] == [4, 5, 2, 3, 0, 1])
    gl = (3, 2)
    bad += check("long edge landscape mirrors rows", [back_slot(i, gl, "LONG", False) for i in range(6)] == [3, 4, 5, 0, 1, 2])
    bad += check("short edge landscape mirrors columns", [back_slot(i, gl, "SHORT", False) for i in range(6)] == [2, 1, 0, 5, 4, 3])
    bad += check("1x1 maps to itself", back_slot(0, (1, 1), "LONG", True) == 0 and back_slot(0, (1, 1), "SHORT", True) == 0)
    p = [dict(w=1000, h=1500, rot=0, copies=1) for _ in range(7)]
    pl = auto_fill(spec(orient="PORTRAIT", grid=(2, 2)), p, "FRONT_ONLY")
    bad += check("7 pics 4-up front only = 2 sheets, 4 + 3", len(pl["sheets"]) == 2 and pl["sheets"][1]["front"] == [4, 5, 6, None])
    lay = compose(spec(orient="PORTRAIT", grid=(2, 2)), p, pl)
    bad += check("front only has no back pages", len(lay["pages"]) == 2 and all(x["side"] == "front" for x in lay["pages"]))
    pl = auto_fill(spec(orient="PORTRAIT", grid=(2, 2)), p, "BOTH_SIDES")
    bad += check("both sides: 7 pics = 1 sheet, front 4, back 3", len(pl["sheets"]) == 1 and pl["sheets"][0]["back"] == [4, 5, 6, None])
    lay = compose(spec(orient="PORTRAIT", grid=(2, 2)), p, pl)
    bad += check("both sides emits front + back page", [x["side"] for x in lay["pages"]] == ["front", "back"])
    pl = auto_fill(spec(orient="PORTRAIT", grid=(1, 2), flip="LONG"), p[:4], "PAIRS")
    bad += check("pairs long edge portrait 1x2: back behind front (same slot)", pl["sheets"][0]["front"] == [0, 2] and pl["sheets"][0]["back"] == [1, 3])
    pl = auto_fill(spec(orient="PORTRAIT", grid=(1, 2), flip="SHORT"), p[:4], "PAIRS")
    bad += check("pairs short edge portrait 1x2: back rows mirrored", pl["sheets"][0]["front"] == [0, 2] and pl["sheets"][0]["back"] == [3, 1])
    pl = auto_fill(spec(orient="PORTRAIT", grid=(2, 1), flip="LONG"), p[:4], "PAIRS")
    bad += check("pairs long edge portrait 2x1: back columns mirrored", pl["sheets"][0]["front"] == [0, 2] and pl["sheets"][0]["back"] == [3, 1])
    q = [dict(w=1000, h=1500, rot=0, copies=3), dict(w=1000, h=1500, rot=0, copies=1)]
    bad += check("copies expand in order", expand(q, False) == [0, 0, 0, 1] and expand(q, True) == [1, 0, 0, 0])
    big = [dict(w=3000, h=2000, rot=0, copies=1)]
    bad += check("auto orientation, auto-turn off: landscape photo -> landscape sheet", auto_fill(spec(autoRot=False), big, "FRONT_ONLY")["portrait"] is False)
    bad += check("auto orientation, auto-turn on: tie -> portrait (same as pvSheets)", auto_fill(spec(), big, "FRONT_ONLY")["portrait"] is True)
    bad += check("auto orientation: one portrait photo -> portrait sheet", auto_fill(spec(), [dict(w=2000, h=3000, rot=0, copies=1)], "FRONT_ONLY")["portrait"] is True)
    lay = compose(spec(orient="PORTRAIT"), [dict(w=2000, h=3000, rot=0, copies=1)], auto_fill(spec(orient="PORTRAIT"), [dict(w=2000, h=3000, rot=0, copies=1)], "FRONT_ONLY"))
    c = lay["pages"][0]["cells"][0]
    bad += check("A4 1-up 2:3 photo: 567 x 814 box fits, scale 0.2713", abs(c["w"] - 2000 * c["k"]) < 1e-6 and abs(c["h"] - 814.0) < 1e-6 and abs(c["k"] - 814 / 3000) < 1e-9)
    f = compose(spec(orient="PORTRAIT", fit="FILL"), [dict(w=2000, h=3000, rot=0, copies=1)], auto_fill(spec(orient="PORTRAIT", fit="FILL"), [dict(w=2000, h=3000, rot=0, copies=1)], "FRONT_ONLY"))["pages"][0]["cells"][0]
    bad += check("fill: box = cell 567 x 814, scale covers it", f["w"] == 567 and f["h"] == 814 and f["k"] >= 567 / 2000 - 1e-9)
    bad += check("empty list -> no sheets", auto_fill(spec(), [], "BOTH_SIDES")["sheets"] == [])
    return bad

if __name__ == "__main__":
    n = part1() + part2()
    print("ALL OK" if n == 0 else f"{n} FAILED"); sys.exit(1 if n else 0)
