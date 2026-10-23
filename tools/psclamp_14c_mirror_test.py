#!/usr/bin/env python3
"""Part 14c: SheetLayout.clampFree / freeHit / freeSpot / packFree (Kotlin, core/SheetLayout.kt) are transliterated here line by line and compared with the
REAL ui.html helpers (fClamp / fHit / fSpot / fPack, run by tools/psclamp_14c_js.js in node) on random inputs, so the page and the server stay equal.
Needs python3 + node. This checks the MATHS of the Kotlin code, not that it compiles (kotlinc was not available).   Run: python3 tools/psclamp_14c_mirror_test.py"""
import json, math, os, random, subprocess, sys
FMIN, FMAX = 0.03, 3.0
def safe_rect(safe, sw, sh):
    s = max(0.0, safe); px = min(0.45, s / sw); py = min(0.45, s / sh)
    return [px, 1 - px, py, 1 - py]
def box(f, p, sw, sh):
    t = math.radians(p['rot'] % 360 + f['rot']); c = abs(math.cos(t)); s2 = abs(math.sin(t))
    dw = f['w'] * sw; dh = dw * p['h'] / p['w']
    return [(c * dw + s2 * dh) / sw, (s2 * dw + c * dh) / sh]
def clamp(f0, p, sw, sh, safe):
    r = safe_rect(safe, sw, sh); f = dict(f0, w=min(FMAX, max(FMIN, f0['w'])))
    b = box(f, p, sw, sh)
    k = min(1.0, (r[1] - r[0]) / max(b[0], 1e-9), (r[3] - r[2]) / max(b[1], 1e-9))
    if k < 1: f['w'] = max(FMIN, f['w'] * k); b = box(f, p, sw, sh)
    f['cx'] = (r[0] + r[1]) / 2 if b[0] >= r[1] - r[0] else min(r[1] - b[0] / 2, max(r[0] + b[0] / 2, f['cx']))
    f['cy'] = (r[2] + r[3]) / 2 if b[1] >= r[3] - r[2] else min(r[3] - b[1] / 2, max(r[2] + b[1] / 2, f['cy']))
    return f
def poly(f, p, sw, sh):
    th = math.radians(p['rot'] % 360 + f['rot']); c = math.cos(th); s = math.sin(th)
    dw = f['w'] * sw; dh = dw * p['h'] / p['w']; out = []
    for i in range(4):
        x = (1 if i in (1, 2) else -1) * dw / 2; y = (1 if i >= 2 else -1) * dh / 2
        out.append((f['cx'] * sw + x * c - y * s, f['cy'] * sh + x * s + y * c))
    return out
def separate(A, B):
    for P in (A, B):
        for i in range(2):
            p, q = P[i], P[i + 1]; ex, ey = q[0] - p[0], q[1] - p[1]; l = math.hypot(ex, ey) or 1.0; nx, ny = -ey / l, ex / l
            va = [c[0] * nx + c[1] * ny for c in A]; vb = [c[0] * nx + c[1] * ny for c in B]
            if min(vb) - max(va) >= -1e-6 or min(va) - max(vb) >= -1e-6: return True
    return False
def dseg(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]; l2 = dx * dx + dy * dy
    t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2)) if l2 else 0.0
    return math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)
def pdist(A, B):
    d = 1e18
    for i in range(4):
        for j in range(4): d = min(d, dseg(A[i], B[j], B[(j + 1) % 4]), dseg(B[j], A[i], A[(i + 1) % 4]))
    return d
def hit(f, pf, g, pg, sw, sh, gap):
    d1 = math.hypot(f['w'] * sw, f['w'] * pf['h'] / pf['w'] * sw) / 2; d2 = math.hypot(g['w'] * sw, g['w'] * pg['h'] / pg['w'] * sw) / 2
    if math.hypot((f['cx'] - g['cx']) * sw, (f['cy'] - g['cy']) * sh) > d1 + d2 + gap + 1e-6: return False
    A, B = poly(f, pf, sw, sh), poly(g, pg, sw, sh)
    return (not separate(A, B)) or pdist(A, B) < gap - 1e-6
def spot(others, f, p, pics, sw, sh, safe, gap, tx, ty):
    r = safe_rect(safe, sw, sh); k = 1.0
    while k >= 0.06:
        g0 = dict(f, w=min(FMAX, max(FMIN, f['w'] * k))); b = box(g0, p, sw, sh); hx, hy = b[0] / 2, b[1] / 2
        if not (hx * 2 > r[1] - r[0] + 1e-9 or hy * 2 > r[3] - r[2] + 1e-9):
            x0, x1, y0, y1 = r[0] + hx, r[1] - hx, r[2] + hy, r[3] - hy
            nx = max(1, math.ceil((x1 - x0) / 0.02)); ny = max(1, math.ceil((y1 - y0) / 0.02)); C = []
            for i in range(nx + 1):
                for j in range(ny + 1):
                    cx = x0 + (x1 - x0) * i / nx; cy = y0 + (y1 - y0) * j / ny; C.append((cx, cy, (cx - tx) ** 2 + (cy - ty) ** 2))
            C.sort(key=lambda c: c[2])   # stable, like sortBy / Array.sort
            for c in C:
                g = dict(g0, cx=c[0], cy=c[1])
                if not any(hit(g, p, o, pics[o['pic']], sw, sh, gap) for o in others): return g
        k *= 0.85
    return None
def pack(L, pics, sw, sh, safe, gap):
    if not L: return []
    r = safe_rect(safe, sw, sh); s = 1.0
    while s > 0.03:
        ws = [max(FMIN, f['w'] * s) for f in L]; bw = []; bh = []
        for i, f in enumerate(L):
            b = box(dict(f, w=ws[i]), pics[f['pic']], sw, sh); bw.append(b[0] * sw); bh.append(b[1] * sh)
        order = sorted(range(len(L)), key=lambda i: -bh[i]); pos = [None] * len(L)
        xl, xr, yt, yb = r[0] * sw, r[1] * sw, r[2] * sh, r[3] * sh; x, y, rh, ok = xl, yt, 0.0, True
        for i in order:
            if bw[i] > xr - xl + 1e-6: ok = False; break
            if x + bw[i] > xr + 1e-6: x = xl; y += rh + gap; rh = 0.0
            if y + bh[i] > yb + 1e-6: ok = False; break
            pos[i] = dict(L[i], w=ws[i], cx=(x + bw[i] / 2) / sw, cy=(y + bh[i] / 2) / sh); x += bw[i] + gap; rh = max(rh, bh[i])
        if ok: return pos
        s *= 0.93
    return None
random.seed(1414); bad = 0; n = 0
def ok(c, m):
    global bad, n
    n += 1
    if not c:
        bad += 1
        if bad < 25: print('FAIL', m)
for paper in [(595.0, 842.0), (842.0, 595.0), (420.0, 595.0), (612.0, 792.0)]:
    sw, sh = paper
    for safe, gap in [(0.0, 0.0), (14.0, 6.0), (28.0, 11.0), (72.0, 23.0)]:
        pics = [dict(w=random.choice([600, 1000, 1500, 3000]), h=random.choice([600, 1000, 1500, 4000]), rot=random.choice([0, 90, 180, 270])) for _ in range(6)]
        rf = lambda: dict(pic=random.randrange(6), cx=random.uniform(-0.3, 1.3), cy=random.uniform(-0.3, 1.3), w=random.uniform(0.03, 3.0), rot=random.choice([0, 15, 30, 45, 90, 123.5, 200, 359]) if random.random() < .7 else random.uniform(0, 360))
        small = lambda: dict(rf(), w=random.uniform(0.05, 0.4))
        cases = []; exp = []
        for _ in range(300):
            f = rf(); cases.append(dict(t='clamp', f=f)); exp.append(('clamp', f))
        for _ in range(300):
            f, g = small(), small(); g['cx'] = f['cx'] + random.uniform(-0.3, 0.3); g['cy'] = f['cy'] + random.uniform(-0.3, 0.3); cases.append(dict(t='hit', f=f, g=g)); exp.append(('hit', f, g))
        for _ in range(12):
            oth = [small() for _ in range(random.randrange(0, 5))]; oth = [clamp(o, pics[o['pic']], sw, sh, safe) for o in oth]; f = small()
            tx, ty = random.random(), random.random(); cases.append(dict(t='spot', f=f, others=oth, tx=tx, ty=ty)); exp.append(('spot', f, oth, tx, ty))
        for _ in range(12):
            it = [small() for _ in range(random.randrange(2, 8))]; cases.append(dict(t='pack', items=it)); exp.append(('pack', it))
        r = subprocess.run(['node', os.path.join(os.path.dirname(__file__), 'psclamp_14c_js.js')], input=json.dumps(dict(sw=sw, sh=sh, safe=safe, gap=gap, pics=pics, cases=cases)), capture_output=True, text=True)
        if r.returncode: print(r.stderr); sys.exit(2)
        got = json.loads(r.stdout)
        for e, g in zip(exp, got):
            if e[0] == 'clamp':
                x = clamp(e[1], pics[e[1]['pic']], sw, sh, safe)
                ok(abs(x['cx'] - g['cx']) < 1e-9 and abs(x['cy'] - g['cy']) < 1e-9 and abs(x['w'] - g['w']) < 1e-9, 'clamp %s %s' % (e[1], g))
                y = clamp(x, pics[x['pic']], sw, sh, safe); ok(abs(y['cx'] - x['cx']) < 1e-9 and abs(y['cy'] - x['cy']) < 1e-9 and abs(y['w'] - x['w']) < 1e-9, 'clamp idempotent')
                b = box(x, pics[x['pic']], sw, sh); R = safe_rect(safe, sw, sh)
                ok(x['cx'] - b[0] / 2 >= R[0] - 1e-9 and x['cx'] + b[0] / 2 <= R[1] + 1e-9 and x['cy'] - b[1] / 2 >= R[2] - 1e-9 and x['cy'] + b[1] / 2 <= R[3] + 1e-9 or x['w'] <= FMIN + 1e-12, 'clamp inside the safe rectangle')
            elif e[0] == 'hit':
                ok(hit(e[1], pics[e[1]['pic']], e[2], pics[e[2]['pic']], sw, sh, gap) == g['hit'], 'hit %s %s' % (e[1], e[2]))
            elif e[0] == 'spot':
                x = spot(e[2], e[1], pics[e[1]['pic']], pics, sw, sh, safe, gap, e[3], e[4])
                ok((x is None) == (g is None) and (x is None or (abs(x['cx'] - g['cx']) < 1e-9 and abs(x['cy'] - g['cy']) < 1e-9 and abs(x['w'] - g['w']) < 1e-9)), 'spot %s vs %s' % (x, g))
                if x is not None: ok(not any(hit(x, pics[x['pic']], o, pics[o['pic']], sw, sh, gap) for o in e[2]), 'spot is free')
            else:
                x = pack(e[1], pics, sw, sh, safe, gap)
                ok((x is None) == (g is None), 'pack none-ness')
                if x is not None and g is not None: ok(all(abs(a['cx'] - b['cx']) < 1e-9 and abs(a['cy'] - b['cy']) < 1e-9 and abs(a['w'] - b['w']) < 1e-9 for a, b in zip(x, g)), 'pack equal')
print('ALL OK (%d checks)' % n if not bad else '%d FAILED of %d' % (bad, n)); sys.exit(1 if bad else 0)
