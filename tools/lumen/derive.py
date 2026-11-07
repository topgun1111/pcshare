#!/usr/bin/env python3
"""Lumen Part 17: colour derivation (spec 4.1 / 14.1). Used by gen.py (fresh slots per preset), contrast.py (T1) and parity.py.
The same maths lives in ui.html between /*LUMEN-CUSTOM:BEGIN*/ and /*LUMEN-CUSTOM:END*/ (Custom theme); parity.py checks they agree.
Everything is plain HSL + WCAG contrast; no random numbers, so Python and JS give identical hex strings."""
import colorsys, math

def h2r(h):
    h = h.strip().lstrip("#")
    if len(h) in (3, 4): h = "".join(c * 2 for c in h)
    return [int(h[i:i + 2], 16) for i in (0, 2, 4)]

def r2h(r):
    return "#" + "".join("%02x" % max(0, min(255, int(math.floor(v + 0.5)))) for v in r)

def hsl(h, s, l):
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360.0, max(0.0, min(1.0, l)), max(0.0, min(1.0, s)))
    return r2h((r * 255, g * 255, b * 255))

def to_hsl(h):
    r, g, b = (v / 255.0 for v in h2r(h))
    hh, l, s = colorsys.rgb_to_hls(r, g, b)
    return hh * 360.0, s, l

def mix(a, b, t):
    ra, rb = h2r(a), h2r(b)
    return r2h([ra[i] + (rb[i] - ra[i]) * t for i in range(3)])

def lin(c):
    c /= 255.0
    return c / 12.92 if c <= .03928 else ((c + .055) / 1.055) ** 2.4

def lum(h):
    r, g, b = h2r(h)
    return .2126 * lin(r) + .7152 * lin(g) + .0722 * lin(b)

def cr(a, b):
    x, y = sorted((lum(a), lum(b)), reverse=True)
    return (x + .05) / (y + .05)

def is_dark(canvas):
    """Same test as gen.py: gamma-space luminance of the canvas under 0.4."""
    r, g, b = (v / 255.0 for v in h2r(canvas))
    return .2126 * r + .7152 * g + .0722 * b < 0.4

def snap(fg, bgs, minr, darker):
    """Move fg lightness in 1 % steps (darker or lighter) until it reaches minr on every bg. Returns (hex, snapped?)."""
    if all(cr(fg, b) >= minr for b in bgs): return fg, False
    h, s, l = to_hsl(fg)
    for _ in range(100):
        l += -.01 if darker else .01
        c = hsl(h, s, l)
        if all(cr(c, b) >= minr for b in bgs): return c, True
        if l <= 0 or l >= 1: break
    return ("#000000" if darker else "#ffffff"), True

def grad_ok(prim, lab):
    """P7: label must reach 4.5 on both ends of gPrimary (primary 94 % + white 6 %, primary 88 % + black 12 %)."""
    return all(cr(lab, e) >= 4.5 for e in (mix(prim, "#ffffff", .06), mix(prim, "#000000", .12)))

def snap_grad(fg, lab, darker):
    """Move primary in 1 % lightness steps (darker or lighter) until grad_ok. Returns (hex, snapped?); unchanged if it cannot."""
    if grad_ok(fg, lab): return fg, False
    h, s, l = to_hsl(fg)
    for _ in range(100):
        l += -.01 if darker else .01
        c = hsl(h, s, l)
        if grad_ok(c, lab): return c, True
        if l <= 0 or l >= 1: break
    return fg, False

def gradc_ok(pc, prim, lab):
    """P7: gPrimarySoft ends (primaryCont, primaryCont 90 % + primary 10 %) against onPrimaryCont."""
    return all(cr(lab, e) >= 4.5 for e in (pc, mix(pc, prim, .10)))

def snap_gradc(pc, prim, lab, darker):
    if gradc_ok(pc, prim, lab): return pc, False
    h, s, l = to_hsl(pc)
    for _ in range(100):
        l += -.01 if darker else .01
        c = hsl(h, s, l)
        if gradc_ok(c, prim, lab): return c, True
        if l <= 0 or l >= 1: break
    return pc, False

def neutral(hue, tint, l):
    """Grey of lightness l with the accent hue mixed in: chroma = tint % (0..8)."""
    k = 1 - abs(2 * l - 1)
    s = 0 if k < 1e-6 else min(1.0, (tint / 100.0) / k)
    return hsl(hue, s, l)

def hexa(h, a):
    return h + "%02x" % int(math.floor(a * 255 + 0.5))

def fresh(d, over=None):
    """The 8 slots a preset does not list, derived from its own slots."""
    dark = is_dark(d["canvas"])
    ph, ps, _ = to_hsl(d["primary"])
    sat = 0.08 if ps < .1 else 0.28
    L = .78 if dark else .40
    o = {}
    o["surfaceLo"] = mix(d["canvas"], "#000000", .35) if dark else mix(d["canvas"], d["ink"], .06)
    o["edgeHi"] = "#ffffff29" if dark else "#ffffffb3"
    o["inkDim"] = snap(mix(d["inkMut"], d["canvas"], .45), [d["canvas"]], 3.0, not dark)[0]
    o["secondary"] = snap(hsl(ph + 40, sat, L), [d["canvas"], d["surface"]], 3.0, not dark)[0]
    o["tertiary"] = snap(hsl(ph + 150, sat, L), [d["canvas"], d["surface"]], 3.0, not dark)[0]
    o["scrim"] = "#0000008f" if dark else hexa(d["ink"], .32)
    o["glassTint"] = mix(d["canvas"], d["surface"], .5) if dark else d["canvas"]
    f = "#8ab4ff" if dark else "#0b5fff"
    for c in (f, "#0b5fff", "#8ab4ff", "#ffb000", "#ffffff", "#000000"):
        if cr(c, d["canvas"]) >= 3.0 and cr(c, d["surface"]) >= 3.0:
            f = c
            break
    o["focus"] = f
    w = d.get("warning", "#f9ab00")
    o["onWarning"] = "#1f1400" if cr("#1f1400", w) >= cr("#ffffff", w) else "#ffffff"
    on = lambda c: max(("#1f1400", "#ffffff", "#000000"), key=lambda k: cr(k, c))   # best of dark brown / white / black
    er, su = d.get("error", "#f2b8b5" if dark else "#b3261e"), d.get("success", "#7fd99a" if dark else "#1e8e3e")
    o["onError"], o["onSuccess"] = on(er), on(su)
    o["info"] = snap("#a8c7fa" if dark else "#0b57d0", [d["canvas"], d["surface"]], 4.5, not dark)[0]   # Part 4g (A2): info family, blue, 4.5:1 on canvas and surface
    o["onInfo"] = on(o["info"])
    o["successCont"] = mix(d["surface"], su, .22 if dark else .14)
    o["warningCont"] = mix(d["surface"], w, .22 if dark else .16)
    o["shadowTint"] = mix(d["primary"], "#000000", .65)   # Part 4b (N4): 35 % primary over black, the tint of every elevation shadow (page --shT used to compute this in CSS)
    if over: o.update(over)
    return o

SEM_LIGHT = dict(errorCont="#f9dedc", onErrorCont="#410e0b", error="#b3261e", success="#1e8e3e", warning="#f9ab00")
SEM_DARK = dict(errorCont="#8c1d18", onErrorCont="#f9dedc", error="#f2b8b5", success="#7fd99a", warning="#ffc15e")

def from_inputs(hue, tint, mode):
    """Spec 4.1: accent hue 0..360, neutral tint 0..8, mode light | dark | amoled -> (all slots, snapped?).
    snapped = some tone had to move to pass contrast (the Custom screen shows a notice)."""
    hue = float(hue) % 360
    tint = max(0.0, min(8.0, float(tint)))
    dark = mode in ("dark", "amoled")
    N = lambda l: neutral(hue, tint, l)
    A = lambda l, s=.9: hsl(hue, s, l)
    sn = False
    d = {}
    if not dark:
        d["canvas"], d["surface"], d["surfaceHi"] = N(.975), "#ffffff", N(.94)
        d["surfaceLo"], d["edge"], d["ink"] = N(.91), N(.88), N(.10)
    else:
        d["canvas"] = "#000000" if mode == "amoled" else N(.075)
        d["surface"] = N(.04) if mode == "amoled" else N(.11)
        d["surfaceHi"] = N(.10) if mode == "amoled" else N(.15)
        d["surfaceLo"] = "#000000" if mode == "amoled" else N(.045)
        d["edge"], d["ink"] = N(.21), N(.92)
    bgs = [d["canvas"], d["surface"], d["surfaceHi"]]
    d["inkMut"], x = snap(N(.33) if not dark else N(.78), bgs, 4.5, not dark); sn |= x
    d["inkDim"], x = snap(N(.52) if not dark else N(.55), [d["canvas"]], 3.0, not dark); sn |= x
    if not dark:
        d["onPrimary"] = "#ffffff"
        d["primary"], x = snap(A(.38), [d["onPrimary"]], 4.5, True); sn |= x
        d["primaryCont"] = A(.88)
        d["onPrimaryCont"], x = snap(A(.10), [d["primaryCont"]], 4.5, True); sn |= x
    else:
        d["onPrimary"] = A(.12)
        d["primary"], x = snap(A(.77), [d["onPrimary"]], 4.5, False); sn |= x
        d["onPrimaryCont"] = A(.90)
        d["primaryCont"], x = snap(A(.26), [d["onPrimaryCont"]], 4.5, True); sn |= x
    p, x = snap(d["primary"], [d["canvas"]], 3.0, not dark); sn |= x
    d["primary"] = p
    d["primary"], _ = snap_grad(d["primary"], d["onPrimary"], not dark)   # silent: a one-step nudge, no notice
    d["primaryCont"], _ = snap_gradc(d["primaryCont"], d["primary"], d["onPrimaryCont"], dark)
    if cr(d["onPrimary"], d["primary"]) < 4.5:
        d["onPrimary"] = "#ffffff" if lum(d["primary"]) < .18 else "#000000"
    d.update(SEM_DARK if dark else SEM_LIGHT)
    d.update(fresh(d))
    d["scrim"] = "#0000008f" if dark else hexa(N(.06), .32)
    return d, sn
