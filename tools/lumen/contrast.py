#!/usr/bin/env python3
"""Fail T1 for any unreadable supported preset, custom palette, or material."""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import tempfile

import derive
import gen
from derive import cr

T = gen.T
PAIRS = [("ink","canvas",4.5),("ink","surface",4.5),("ink","surfaceHi",4.5),("inkMut","canvas",4.5),("inkMut","surface",4.5),
         ("inkMut","surfaceHi",4.5),("onPrimary","primary",4.5),("onPrimaryCont","primaryCont",4.5),("onErrorCont","errorCont",4.5),("primary","canvas",3.0),
         ("inkDim","canvas",3.0),("secondary","canvas",3.0),("secondary","surface",3.0),("tertiary","canvas",3.0),("tertiary","surface",3.0),
         ("focus","canvas",3.0),("focus","surface",3.0),("onWarning","warning",4.5),
         ("onError","error",4.5),("onSuccess","success",4.5),("onInfo","info",4.5),("info","canvas",4.5),("info","surface",4.5),("ink","successCont",4.5),("ink","warningCont",4.5)]
HC = [("ink","canvas",7.0),("ink","surface",7.0),("ink","surfaceHi",7.0),("inkMut","canvas",7.0),("inkMut","surface",7.0),("inkMut","surfaceHi",7.0),
      ("onPrimary","primary",7.0),("onPrimaryCont","primaryCont",7.0),("onErrorCont","errorCont",7.0),("primary","canvas",7.0),("primary","surface",7.0),
      ("error","canvas",7.0),("success","canvas",7.0),("onWarning","warning",7.0),("focus","canvas",3.0),("focus","surface",3.0),("edge","canvas",3.0)]


def pairs(d, checks):
    for fg, bg, minimum in checks:
        if cr(d[fg][:7], d[bg][:7]) < minimum:
            yield f"{fg}/{bg} {cr(d[fg][:7], d[bg][:7]):.2f} < {minimum}"


def track(stops):
    for a, b in zip(stops, stops[1:]):
        for step in range(33):
            yield derive.mix(a, b, step / 32)


def gradient_errors(d, minimum):
    recipes = [
        ("primary", "onPrimary", (d["gradFrom"], d["primary"], d["gradTo"])),
        ("tonal", "onPrimaryCont", (d["tonalFrom"], d["primaryCont"], d["tonalTo"])),
        ("danger", "onError", (d["dangerFrom"], d["error"], d["dangerTo"])),
        ("success", "onSuccess", (d["successFrom"], d["success"], d["successTo"])),
        ("surface", "ink", (d["surfaceHi"], d["surface"])),
        ("surface", "inkMut", (d["surfaceHi"], d["surface"])),
        ("selection", "onSelection", (d["selection"], d["selectionTo"])),
    ]
    for name, fg, stops in recipes:
        # Test the interpolation and the solid fallbacks as well as the ends.
        if not all(derive.text_on(stops[0], d[fg], minimum)(bg) for bg in track(stops)):
            yield f"{name} gradient / {fg} below {minimum}"
    for base in ("canvas", "surface", "surfaceHi", "surfaceLo"):
        if min(cr(d[base], bg) for bg in track((d["selection"], d["selectionTo"]))) < 1.5:
            yield f"selected colour / {base} below 1.5"


def glass_errors(d, minimum, levels):
    floor = int(d["glassFloor"].removesuffix("%")) / 100
    if not 0 <= floor <= 1:
        yield "invalid glass floor"
        return
    tint = d["glassColor"]
    fg = [d["ink"], d["inkMut"]]
    # F0 and F1 share the tint. F2, fling and modal demotion increase
    # opacity. F3 and unsupported-browser paths use the solid surface.
    for name, alpha in levels.items():
        if not derive.glass_passes(tint, max(alpha, floor), fg, minimum):
            yield f"{name} glass / ink or inkMut below {minimum}"
    argb_tint = d["glassSafe"]
    alpha = int(argb_tint[7:], 16) / 255
    # Native 8-bit alpha. Include both modal defaults and the generated floor.
    dark = derive.is_dark(d["canvas"])
    for name, nominal in (("native G1", .62 if dark else .72), ("native G2", .70 if dark else .78),
                          ("native G3", .76 if dark else .84), ("native F2", .92 if dark else .94)):
        if not derive.glass_passes(argb_tint[:7], max(nominal, alpha), fg, minimum):
            yield f"{name} glass / ink or inkMut below {minimum}"
    if not all(cr(f, d["surface"]) >= minimum for f in fg):
        yield f"F3 solid / ink or inkMut below {minimum}"


def css_levels(page):
    values = []
    for block in re.findall(r"\{([^{}]*--gA1:[^{}]*)\}", page):
        found = {k: int(v) / 100 for k, v in re.findall(r"--(g[A-B]\d):([0-9]+)%", block)}
        if set(found) == {"gA1", "gA2", "gA3", "gB1"} and found not in values:
            values.append(found)
    if len(values) != 2:
        raise ValueError("expected light and dark G1–G3 CSS tint recipes")
    for key in values[0]:
        raw = f"var(--{key}),transparent"
        safe = f"var(--glassColor) max(var(--{key}),var(--glassFloor)),transparent"
        if raw in page or safe not in page:
            raise ValueError(f"unsafe or missing floor for {key}")
    recipes = {}
    for mode, levels in zip(("light", "dark"), values):
        recipes[mode] = {**levels, "F2": .94 if mode == "light" else .92, "fling/demotion": .94}
    return recipes


def category_errors():
    c = T["category"]
    for name, mode, mono in [("light", "light", None), ("dark", "dark", None)] + [
            (name, "dark" if derive.is_dark(T["themes"][name]["canvas"]) else "light", T["themes"][name])
            for name in c["mono"]]:
        for category, (base, _, deep) in gen.cat_tones(mode, mono).items():
            glyph = derive.WHITE if mode == "light" else deep
            ends = (derive.mix(base, derive.WHITE, c["tileTop"]), derive.mix(base, derive.BLACK, c["tileBottom"]))
            if min(cr(glyph, bg) for bg in track(ends)) < 3:
                yield f"{name} {category} glyph / category gradient below 3"


def fixture_errors(levels):
    # Hold the real names and colours from the ledger: the original Vivaldi
    # G1 and selected background must fail, while their replacement must pass.
    fixed = gen.palette("vivaldi")
    old_glass = {**fixed, "glassFloor": "58%", "glassSafe": derive.hexa(fixed["glassTint"], .58)}
    old_selection = {**fixed, "selection": "#252430", "selectionTo": "#252430", "onSelection": "#ffffff"}
    bad_gradient = {**fixed, "dangerTo": fixed["onError"]}
    cases = [
        ("unsafe glass", list(glass_errors(old_glass, 4.5, levels["dark"])), "gB1 glass"),
        ("weak selection", list(gradient_errors(old_selection, 4.5)), "selected colour"),
        ("unsafe gradient", list(gradient_errors(bad_gradient, 4.5)), "danger gradient"),
        ("fixed glass", list(glass_errors(fixed, 4.5, levels["dark"])), None),
        ("fixed gradients", list(gradient_errors(fixed, 4.5)), None),
    ]
    for name, errors, expected in cases:
        passed = any(expected in error for error in errors) if expected else not errors
        if not passed:
            yield f"contrast fixture {name} produced {errors}"


def browser_errors(page, themes):
    chrome = next((path for name in ("google-chrome", "chromium", "chromium-browser")
                   if (path := shutil.which(name))), None)
    if chrome is None:
        yield "Chromium or Google Chrome is required for --strict rendered contrast checks"
        return
    # No native bridge or app server is required to exercise the CSS cascade.
    markup = re.sub(r"<script\b[^>]*>.*?</script>", "", page, flags=re.S | re.I)
    styles = "".join(re.findall(r"<style\b[^>]*>.*?</style>", markup, re.S | re.I))
    fixtures = """<div class="g1"></div><div class="g2"></div><div class="g3"></div>
    <div id="dlg"><div class="dcard"></div></div><div id="sheet"><div class="card"></div></div>
    <div id="dock"></div><div id="gal"><div class="gb"></div></div>
    <div id="toast"></div><div id="jp"></div><div class="dmn"></div>
    <div class="row sel"></div><button class="chip on"></button>
    <div class="seg"><button class="on"></button></div>
    <button class="tbtn fill"></button><button class="tbtn tonal"></button>
    <button class="tbtn dngf"></button><pre id="contrast-results"></pre>"""
    script = "\n".join((gen.here / name).read_text(encoding="utf-8") for name in
                       ("custom.js", "test_large_text_browser.js", "test_sort_browser.js", "test_glass_browser.js", "test_contrast_browser.js"))
    start, end = page.index("function openSort(){"), page.index("function openView(){")
    sort_source = page[start:end]
    start, end = page.index("function glassPick(){"), page.index("function glassApply(){")
    glass_source = page[start:end]
    with tempfile.TemporaryDirectory() as directory:
        fixture = pathlib.Path(directory) / "contrast.html"
        for scheme in ("light", "dark"):
            # Exercise the exact dark-media declarations even on headless
            # runners whose OS preference is permanently set to light.
            css = re.sub(r"@media\s*\(prefers-color-scheme:\s*dark\)",
                         "@media " + ("all" if scheme == "dark" else "not all"), styles)
            document = ("<!doctype html><meta charset=utf-8>" + css
                        + "<style>*{transition:none!important;animation:none!important}</style>" + fixtures
                        + '<script type="application/json" id="contrast-themes">' + json.dumps(themes)
                        + '</script><script type="text/plain" id="sort-source">' + sort_source
                        + '</script><script type="text/plain" id="glass-source">' + glass_source
                        + "</script><script>" + script + "</script>")
            fixture.write_text(document, encoding="utf-8")
            args = [chrome, "--no-sandbox", "--headless", "--disable-gpu", "--no-first-run",
                    f"--user-data-dir={directory}/chrome-{scheme}", "--dump-dom"]
            proc = subprocess.run([*args, fixture.as_uri() + "#" + scheme], capture_output=True, text=True, timeout=50)
            match = re.search(r'<pre id="contrast-results">([^<]*)</pre>', proc.stdout)
            if proc.returncode or not match:
                yield f"Chromium {scheme} contrast fixture did not report a result"
            elif match[1] != "PASS":
                yield f"Chromium {scheme}: {match[1]}"



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    page = gen.ui.read_text(encoding="utf-8")
    try:
        levels = css_levels(page)
    except ValueError as error:
        print(error)
        return int(args.strict)

    errors = list(category_errors()) + list(fixture_errors(levels))
    palettes = [(n, gen.palette(n), 7 if n in ("hcl", "hcd") else 4.5) for n in T["themes"]]
    # Auto resolves to one of the same published light/dark palettes; require
    # the actual emitted :root and media selectors to contain the tested data.
    for selector, name in ((":root{", "ember"), (":root[data-theme=auto]{", "dark")):
        matched = next((line for line in gen.build().splitlines() if selector in line), "")
        if gen.decl(gen.palette(name)) not in matched or matched not in page:
            errors.append(f"Auto {name} selector does not use the verified palette")
    for name, d, _ in palettes:
        if name != "ember" and f":root[data-theme={name}],[data-pv={name}]{{" + gen.decl(d) not in page:
            errors.append(f"{name} selector does not use the verified palette")
    custom = [
        (f"custom {mode} h{hue} t{tint}", derive.from_inputs(hue, tint, mode)[0], 4.5)
        for mode in ("light", "dark", "amoled")
        for hue in range(0, 360, 5)
        for tint in (0, 1.5, 4, 6, 8)
    ]
    for name, d, minimum in palettes + custom:
        mode = "dark" if derive.is_dark(d["canvas"]) else "light"
        checks = list(pairs(d, PAIRS))
        if minimum == 7:
            checks.extend(pairs(d, HC))
        checks.extend(gradient_errors(d, minimum))
        checks.extend(glass_errors(d, minimum, levels[mode]))
        if checks:
            errors.append(f"{name}: " + "; ".join(checks))
    if args.strict:
        errors.extend(browser_errors(page, [*T["themes"], "auto"]))
    for error in errors:
        print(error)
    print(f"T1: {len(palettes)} presets, two Auto variants, {len(custom)} Custom palettes, "
          f"category tiles and contrast fixtures; {len(errors)} failing groups")
    return int(bool(errors) and args.strict)


if __name__ == "__main__":
    raise SystemExit(main())
