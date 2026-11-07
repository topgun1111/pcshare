# Lumen work plan: remaining work split into session parts

Companion to `LUMEN_DESIGN_SYSTEM.md` (spec + item list 19.1c). This file answers three questions between sessions: **what is done, what is next, what each next part contains.**
Created 2026-11-07. Item IDs (V / N / M / P / T / A) are the ones in 19.1c; every one of the 42 open items sits in exactly one part below.

**Status line (edit this first in every session):**
`Done: Parts 1-18 + Round 2 A / B / C(partial) + V1 + Parts 1-3, 4a, 4b, 4c (partly), 4d (partly), 4e and 4f of 8 | Next: owner compile + device pass (V2-V5, check 4f first); then leftovers | Open items: 9 (M1, M5 native exits elsewhere, P8, M2, P4 partly; N4 gGlow / gSkeleton / gScrim callers; 4f device check) | Last session: 2026-11-07`

---

## 1. Session rules (apply to every part)

1. Read `HANDOVER_READ_BEFORE_CODE.md` (Lumen chapter) and this file; pick the first part marked `NEXT`.
2. Tokens first: edit `tools/lumen/tokens.json`, then `python3 tools/lumen/gen.py`; never hand-edit the generated `/*LUMEN:BEGIN*/` block or `LumenTokens.kt`.
3. Before returning: `gen.py --check`, `contrast.py` (T1, must stay 0 failing), `lint.py` (counts must not rise), `parity.py` (0 differences), `node --check` on the page JS.
4. Update: this file (status line, part status, session log), 19.1c ticks (`[x]` + date), `HANDOVER_READ_BEFORE_CODE.md`, `CHANGELOG.md` (new top section, plain words).
5. Return only changed files in a `.tar.xz`, folder structure kept.
6. Kotlin: owner compiles. Say in the log whether the part touched Kotlin so the next compile is expected to be checked.

---

## 2. Done (history, do not redo)

| What | Date | Note |
|---|---|---|
| Parts 1-3: token file, radius, spacing and type scales | 2026-11-07 | `tokens.json`, `gen.py`, `--r-* --sp-* --fs-*` |
| Part 4: 42 themes in slots, Ember spec values, T1 contrast script | 2026-11-07 | 0 failing pairs |
| Parts 5-6: slots to native, native screens on `Lumen.of(ctx)` | 2026-11-07 | `LumenTokens.kt` generated |
| Part 7: page dialog, sheets, snackbar | 2026-11-07 | block `LUMEN-UI` |
| Part 8: native `Lm.kt` dialog + snackbar, all 89 `Toast.makeText` converted | 2026-11-07 | `NlSheets` still own look |
| Parts 9-10: Lumen icons (85 glyphs), folder / category tiles, launcher, notification, splash window | 2026-11-07 | |
| Part 11: buttons, chips, switches, fields | 2026-11-07 | block `LUMEN-UI2` |
| Part 12 (partly): rows, selection, dividers, ripple | 2026-11-07 | bars / drawer finished in Round 2 B |
| Part 13: gradients, elevation (page + native dialog button) | 2026-11-07 | |
| Parts 14-15: Aurora, glass G1-G3, fallback ladder, settings (page only) | 2026-11-07 | native glass = N1 |
| Part 16 (partly): motion tokens, enter animations, reduce motion, haptics, skeletons (page) | 2026-11-07 | exits etc. = M items |
| Part 17 (partly): derived slots, grouped picker, Custom theme, Windows radii | 2026-11-07 | presets keep their hex |
| Part 18 (partly): focus rings, roles, focus trap, frame test, lint | 2026-11-07 | |
| Round 2 A: brand category colours, main menu tiles, file list tiles (`NlFileIcons`) | 2026-11-07 | |
| Round 2 B: floating dock + More, native ripple, drawer rows | 2026-11-07 | |
| Round 2 C: native TalkBack for chrome, splash colours (v31), dialog field floating label, search pill, sliding segment, slider bubble | 2026-11-07 | |
| Part 4b: chrome glass stand-in by step, `shadowTint` slot + `LumenGrad`, radius flavour (`LumenRadius`), drawer storage card | 2026-11-07 | not compiled; see Part 4b left over |
| **V1: APK compiled by the owner, app runs fine** | 2026-11-07 | |

Open parts of the above are tracked in the parts below, not here.

---

## 3. The 8 parts (Parts 4 and 5 re-split into 4a-4d on 2026-11-07, 4e / 4f split off 4d leftovers on 2026-11-07; one part per session, in order)

| Part | Content (old L-numbers, item IDs) | Layer | Est. lines | Status |
|---|---|---|---|---|
| 1 | L2 + page side of L3: P8 P9 P5 P10 A3, M1 / M5 / M6 (page) | page | 300-450 | DONE (2026-11-07) |
| 2 | L4 + L5 + L6: M3 M4 M2, P2 P3 P4, P1 | page | 700-1000 | DONE (2026-11-07; M2, P4 partly) |
| 3 | L7: T1-T5, P7 (decisions first, see section 6) | page + tools | 300-450 | DONE (2026-11-07; defaults used: T1 apply only close presets, T3 OS font only when font is Default) |
| 4a | L8 + L9 + native M1 / M5: A1 A2, N2 N12 N1 (a), `Lm.kt` exits + Undo bar + merge | native + page | 700-1000 | DONE (2026-11-07; not compiled) |
| 4b | L10 + N8: N1 (b) N4 N6, N8 (chrome glass stand-in, gradients + shadow tint slot, radius flavour, drawer storage card) | native + page + tools | 600-900 | DONE (2026-11-07; not compiled) |
| 4c | L11 + N7: N3 N5 N13 P6, N7 (tiles, colour tables, bar variants + scroll-driven top bar) | native + page | 1000-1400 | PARTLY (2026-11-07, see 4c left over) |
| 4d | L12 + L14 + N1 (c): N9 N10 N11 M7, N1 (c) (list interactions, container transform, real backdrop blur for in-window chrome) | native + page | 1100-1500 | PARTLY (2026-11-07: N9 rows + grid, N10 flip + lift, M7 a skeleton; rest moved to 4e / 4f) |
| 4e | L12 leftovers: N9 gallery cells + drawer rows, N10 dock stagger + day-heading circle, N11 sticky heading strip | native + page | 250-400 | DONE (2026-11-07; not compiled) |
| 4f | L12 + L14 leftovers: N1 (c) real in-window blur, M7 (b) container transform | native | 600-1000 | DONE (2026-11-07; not compiled; decisions taken by default: blur only F0, or F1 on a non-low device out of battery saver; container transform = snapshot overlay) |
| owner | L1: V2 V3 V4 V5 device pass; send results any time, findings become tasks in Part 4a-4d | device | 0-100 | OPEN |

Part 1 left over: P8 `--t-*` / `--st-*` adoption and the 194 hex colours (kept: per-theme and brand values), 25 spacing nudges (negative margins), 13 infinite-loop durations. Native part of M1 / M5 was done in Part 4a. Part 2 left over: M2 old-screen shift / dim and dock slide (dock is native, Part 4c), P4 `.g0` has no caller yet (viewer counters / badges are native, Part 4c). Part 2 touched Kotlin: NO; headless Chromium smoke: dialog exit ok, no init errors. Part 1 touched Kotlin: NO. Page JS `node --check` ok (7 scripts), CSS braces balanced, `gen.py --check`, T1 0 failing, `parity.py` 0 differences; not opened in a browser or on a device.

Part 4b left over: (1) N1 (b) is a tint-raised stand-in, not real blur: a native view cannot blur what the page draws under it (blur-behind belongs to a Window), so real blur of the bars / dock / drawer needs a backdrop snapshot = new task **N1 (c), Part 4d**; the top / selection bar still sit on top of the page's own `#top` bar, so they only look different once that twin is transparent (N7, Part 4c). (2) `LumenGrad.glow / skeleton / scrimTop / scrimBottom` exist but have no caller yet (tiles + splash and viewer bars in Part 4c, skeleton rows in Part 4d). (3) N7 (Search / Viewer bar variants, Large collapsing bar, scroll-driven transparent small bar) moved to Part 4c: it needs a page twin that is transparent at scroll 0, a scroll event page to native and a layout change (`--topH`). Part 4b touched Kotlin: YES (not compiled). Page JS `node --check` ok (8 scripts), `gen.py --check`, T1 0 failing, `parity.py` 0 differences, lint unchanged (0 / 25 / 0 / 13 / 194).

Part 4c done (2026-11-07, native + page, Kotlin NOT compiled): **P6** `StorageTreemap.CAT_COL` is filled from `LumenCategory.of(ctx)` (`StorageTreemap.refreshCat(ctx)`, called in `StorageActivity.onCreate`; pie / list / chips share the array); `CodeHl.color(ctx, kind, dark)` takes token colours from the category table (keyword = pdf, string = xls, number = zip, type = doc, call = vid, attribute = ppt, meta = pic, comment stays neutral) and `TextEditorActivity` uses it. **N3** grid "no thumbnail" page = the Lumen category tile (`NlGridView.page()` calls `NlFileIcons.draw`, extension shown from 64 dp); `drawLegacy` now has no caller. **N5 (page part)** block `/*LUMEN-UI17*/`: Devices / FTP / Cloud / SMB / USB sheet header icons and server avatars (`.smi`, `.sma`) are category tone tiles (needs `:has()` + `color-mix`, plain icons otherwise). **N7 (scroll-driven part)** `NlChrome.onElev` (set by `NlChrome`, called by `NativeList` on scroll, on layout and on `hide()`): the small top bar (`NlBarView.setElevated`) is transparent with ink-coloured title / glyphs while the list is at its top and fills in over 160 ms once scrolled; only at glass F0 / F1 (the page twin `#top` is transparent only there), instant with Reduce motion.
Part 4c left over: Large (collapsing 112 to 64 dp) top bar (needs `--topH` layout change and page twin, not started), Search / Viewer bar variants (the viewers already use scrim gradients; `LumenGrad.scrimTop / scrimBottom` still have no caller), folder drawing in pickers / album covers (no native picker or album cover exists: the album cards are page-drawn), `NlDrawer` icons (already Lumen glyphs since Part 9), gallery (`NlGalView`) has no file tiles, dark viewer chrome / `StorageWiz` themes / search chips / Drive-FTP-SMB badges keep their own colours, `NlSplash`. **N13 decision: accepted for Android under 12** (window background `canvas` only; `NlSplash` is the instant-start snapshot, not a logo screen); revisit only if the owner asks for a logo frame. Part 4c touched Kotlin: YES (`StorageTreemap`, `StorageActivity`, `CodeHl`, `TextEditorActivity`, `NativeList`, `NlChrome`); not compiled, nothing seen on a device; page JS `node --check` ok, CSS braces balanced, `gen.py --check` ok, lint unchanged except hex 195 (+1).

Status values: `NEXT`, `TODO`, `IN PROGRESS`, `DONE (date)`, `PARTLY (date, what is left)`.

---

## 4. Item detail (written for the old L1-L14 split; the item text stays valid, the grouping is section 3)

### L1 Device verification (owner runs, Claude fixes what fails)
Goal: confirm the built Lumen on real devices before more native code is added. Almost no new code.
- [ ] **V2** Device pass. T5 parity screenshots (same theme, page vs native, 20 sample points); T6 glass ladder F0-F3 (Reduce transparency forces F3); T7 font scale 130 / 160 / 200 % on file browser, gallery, settings, dialogs (nothing clipped or unreachable); T8 gallery scroll with `lmFrameTest` (drops under 5 % at F0 mid device, under 10 % at F1 low device); T10 native theme-switch flash.
- [ ] **V3** TalkBack run on file browser, dialogs, top bar, dock, drawer, settings. Write findings as a list for L8.
- [ ] **V4** Check `LumenPrefs.glassHint()` guess (`isLowRamDevice`, `memoryClass <= 128`, <= 4 cores) on the reference devices; note what Auto picks.
- [ ] **V5** After any new dark preset: check the three static dark-theme selector lists (UI4 / UI5 / UI6, `data-cdark`).
Exit: results table in the session log; failures become fix tasks inside the part that owns the area.

### L2 Page cleanup and token adoption
Goal: finish T2 lint cleanliness on the page. Mechanical, low risk.
- [ ] **P8** Use `--t-*` style tokens and `--st-*` in page CSS. Current lint: raw durations 68, hex 201, spacing >= 3 px 27, radius 1, 16 `font:` shorthands with px. Target: durations to `--d-*`, spacing to `--sp-*`, radius 1 to `--r-*`, shorthands to `font:` from tokens; hex only inside per-theme rules and the token block.
- [ ] **P9** Delete unused `--hc1..7` theme rules (45 lines in `ui.html`, no `var(--hc*)` reader) and the unreferenced colour `brand_lite_bg` (`res/values/colors_lite.xml`).
- [ ] **P5** Grid fallback page uses the realistic icon: switch to category tiles; page list `.kd` tiles move from `--k-*-c / -b` to `--cat-*`.
- [ ] **P10** Selection-circle announce inside the gallery overlay (own count node, live region).
- [ ] **A3** Extend icon-button name table `LB` for every icon-only button added since; add a lint line that lists icon-only buttons without a name.
Files: `ui.html`, `tools/lumen/lint.py`, `res/values/colors_lite.xml`. Acceptance: `lint.py` counts drop to the targets above; no visual change except P5.

### L3 Motion A: exits, sheet swipe, snackbar
- [ ] **M1** Exit animations: dialog, sheet, popup menu close in 200 ms accelerate (today they close instantly). Page: `.lm-out` classes + remove after `transitionend`; native `Lm.kt` dialog dismiss animates scale/fade. Reduce motion = instant.
- [ ] **M6** Sheet swipe-to-dismiss on page `#sheet`: drag the handle / header down, release past 30 % or fling to close, rubber-band otherwise; scrim follows.
- [ ] **M5** Snackbar: merge repeated identical numbers ("3 files" to "4 files" instead of a new pill) and an Undo countdown bar under the pill for actions with Undo (`Lm.snack` 6-arg form + page `toast()`); keep the existing Print space undo bar (`.gpst`) untouched.
Files: `ui.html` (UI7 block), `Lm.kt`. Touches Kotlin: yes.

### L4 Motion B: list, wallpaper, screens
- [ ] **M3** List insert / remove collapse (height + opacity, 200 ms) for delete, move, rename out of view; skip with Reduce motion; do not animate inside virtualised long lists (use only for under about 200 rows).
- [ ] **M4** Aurora wallpaper drift (60 s loop of the four blobs, transform only) and drift off in battery saver / Reduce motion / F2-F3.
- [ ] **M2** Screen push of the main file screens with old-screen shift / dim; selection-start choreography (top bar slides to selection bar, dock slides up, rows tint in 20 ms steps).
Files: `ui.html`. Touches Kotlin: no (selection choreography may call existing native bar events).

### L5 Page controls and glass leftovers
- [ ] **P2** Floating label + clear for fields outside `dlg` (sheets, settings, rename); password show / hide eye button.
- [ ] **P3** Split button (main action + dropdown); glass buttons (G-tier fill for buttons on pictures); give `.seg` its first caller in Settings (for example Glass quality or theme mode).
- [ ] **P4** G0 on-picture pills (viewer counters, badges); grain on G2 / G3; top bar transparent-to-G1 on scroll; flatter wallpaper variant for list screens (lower blob alpha).
Files: `ui.html` (UI2, UI5, UI6, UI12 blocks). Touches Kotlin: no.

### L6 Page list interactions
- [ ] **P1** Swipe actions on rows (reveal Delete / Share, threshold + haptic), fast-scroll scrubber with date bubble, hold-menu lift (scale 1.02 + shadow E3 while the context menu is open). Page twin of N10 / N11; reuse the same thresholds so native matches later.
Files: `ui.html` (UI3 / new UI13 block). Touches Kotlin: no. Risk: gesture conflicts with the native list overlay; test on device.

### L7 Themes and contrast
- [ ] **T1** Regenerate the 42 presets from the three inputs (hue, tint, mode). Decision needed first: earlier call was to keep hand-picked hex (identity of well-known palettes). Default: produce a side-by-side report from `derive.from_inputs`, apply only to presets whose derived result passes T1 and is within a set colour distance; leave the rest.
- [ ] **T2** Lumen family as Light / Dark pairs with a separate Auto / Light / Dark choice; Custom theme gets follow-system mode.
- [ ] **T3** OS font option (Segoe-like / Ubuntu). It clashes with the user's own font choice: decide (suggested: only applies when font is "Default").
- [ ] **T4** High contrast as a theme: 3 dp focus ring and 7:1 text, not only the media query; add to `contrast.py` as a 7:1 test.
- [ ] **T5** Live preview card in the theme picker; `onWarning` slot (fixes the darker amber used in the print log); ubuntu / win11 own button gradients and shadows reconciled with Lumen.
- [ ] **P7** Contrast check of gradient ends against labels (T1 tests flat slots only) and of glass tint alpha per theme (floor); extend `contrast.py`.
Files: `tokens.json`, `derive.py`, `contrast.py`, `custom.js`, `gen.py`, `ui.html`. Touches Kotlin: only via regenerated `LumenTokens.kt` (new slot `onWarning`).

### L8 Native accessibility (Part 4a)
- [x] **A1** (2026-11-07, 4a) `contentDescription` / roles for native list rows (`NativeList`), drawer rows (`NlDrawer`), `Lm` dialogs and snackbar (live region). Use `ExploreByTouchHelper` virtual nodes where rows are drawn on a canvas (as `NlHitView` does).
- [x] **A2** (2026-11-07, 4a) Chips: real 48 px hit box with ripple (today a click redirect on the `.chips` padding, no ripple).
Add the findings of V3 as extra tasks here.
Files: `NativeList.kt`, `NlDrawer.kt`, `Lm.kt`, `ui.html`. Touches Kotlin: yes.

### L9 Native sheets and dialogs (Part 4a)
- [x] **N2** (2026-11-07, 4a) `NlSheets`: Lumen look (surface slot, `r-xxl` top corners, edge hairline), motion tokens, drag handle, swipe-to-dismiss, scrim slot.
- [x] **N12** (2026-11-07, 4a) Snackbar lift above the bottom bar / dock (`Lm.snack`: read dock height from the page via palette / bar event), system toast only where no Activity exists.
- [x] **N1 (a)** (2026-11-07, 4a) Native glass for dialogs (`Lm.restyle`) and sheets (`NlSheets`): API 31+ blur via `setBackgroundBlurRadius` / `RenderEffect` with the F0-F3 ladder from `LumenPrefs`; fallback = opaque surface + hairline. Mark N1 done only after part (b) in L10.
Files: `NlSheets.kt`, `Lm.kt`, `LumenPrefs.kt`. Touches Kotlin: yes.

### L10 Native chrome glass and gradients (Part 4b, DONE 2026-11-07)
- [~] **N1 (b)** (2026-11-07, 4b) G1 chrome by glass step through `LumenGlass.chromeFill` (F0 88 / 84 %, F1 92 / 88 %, F2 94 / 92 %, F3 opaque, light / dark): top + selection bar (`NlBarView`, plus 0.5 dp edge hairline, 1 dp at F3), floating dock (`NlDockView`, never under 92 %, edgeHi rim, flat at F3), drawer panel (`NlDrawer`; page hides its own `#dwr` with `html.nldr`). **Stand-in only, no blur**; real blur = N1 (c) in Part 4d.
- [~] **N4** (2026-11-07, 4b) `shadowTint` is a real slot (`tokens.json` fresh sets, `derive.py`, `custom.js`, generated into `LumenTokens.kt`; page `--shT` reads it); new `LumenGrad.kt`: gPrimary, gFab, gDanger, gSuccess, gSurface, gGlow, gSkeleton, gScrimTop / gScrimBottom. Callers: FAB (gFab + press ripple), dock Primary pill (gPrimary), snackbar pill (gSurface) + progress fill (gPrimary, gSuccess at 100 %), gChrome only at F0-F2, tinted E3 shadow on FAB / dock / snackbar / drawer. No caller yet: gGlow, gSkeleton, gScrim* (Parts 4c / 4d).
- [x] **N6** (2026-11-07, 4b) `LumenRadius` (page sends computed `--r-xs..xxl` as `rad` in `nlPal()`): FAB, dock pills + clipboard strip use r-l, snackbar r-xl, View options sheet r-xxl (was 28 dp, now equal to the page's `#sheet`: 32 dp, Windows 11 = 24); native dialogs keep 32 dp, the Sort dialog keeps 28 dp.
Files: `NlChrome.kt`, `NlDrawer.kt`, `NlSheets.kt`, `LumenGlass.kt`, new `LumenGrad.kt`, `LumenTokens.kt` (generated), `tokens.json`, `derive.py`, `custom.js`, `ui.html`. Touches Kotlin: yes.

### L11 Native tiles, icons, colour tables (Part 4c)
- [ ] **N3** Category tiles in grid / gallery (`NlGridView`, `NlGalView`, 64-96 dp "Grid file"); realistic Word / Excel / PowerPoint tiles move from `drawLegacy` to the realistic-tile set.
- [ ] **N5** Folder drawing in pickers, album covers and other native places; drawer icons (`NlDrawer`); tiles for Devices / FTP / Cloud sheets.
- [ ] **N13** Splash: logo screen on Android under 12 (window background only today): add a short `NlSplash` logo frame or accept and document.
- [ ] **P6** One colour table (4.5) for Storage Analyzer treemap / pie (`StorageTreemap.CAT_COL`, `StorageWiz`, `StoragePie`), search chips, Drive / FTP / SMB badges, `CodeHl`, dark viewer chrome and text-editor palette: read `LumenCategory` / `Lumen` slots instead of own constants.
Also uses the 4b `LumenGrad.glow` (press glow on Home tiles) and `scrimTop / scrimBottom` (viewer bars).
Files: `NlFileIcons.kt`, `NativeList.kt`, `NlSheets.kt`, `NlDrawer.kt`, `StorageTreemap.kt`, `StorageWiz.kt`, `StoragePie.kt`, `CodeHl.kt`, `TextEditorActivity.kt`, `NlSplash.kt`. Touches Kotlin: yes.

### L12 Native list interactions (Part 4d, finished in 4e; N1 (c) = 4f)
- [x] **N9** (2026-11-07, 4e) Native ripple inside list rows, grid / gallery views, drawer rows (today they keep the `--hov` press fill); reuse `NlHitView.ripple()`.
- [x] **N10** (2026-11-07, 4e) Selection check-circle flip (160 ms), partial / bar state on the day-heading select circle, hold-menu lift (1.02), 20 ms action stagger on the dock.
- [x] **N11** (2026-11-07, 4e) Sticky heading strip and fast-scroll scrubber in the native list (twin of P1; same thresholds).
- [x] **M7 (a)** (2026-11-07, 4d) Skeletons in native lists (shimmer rows with `LumenGrad.skeleton` = `gSkeleton`, shown while the folder loads).
- [x] **N1 (c)** (2026-11-07, 4f) Real backdrop blur for in-window chrome (dock, drawer, top / selection bar): draw the page + list under the view into a small bitmap (1/8 scale), blur with `RenderEffect` on API 31+ (F0 radius 24 dp, F1 12 dp), cache until the list scrolls; F2 / F3 keep the 4b stand-in. Performance-critical: check T8 after.
Files: `NativeList.kt`, `NlChrome.kt`, `NlDrawer.kt`. Touches Kotlin: yes. Risk: highest, list drawing is performance-critical; check T8 after.

### L13 Native bar variants and drawer header (N7 = Part 4c, N8 = Part 4b DONE)
- [ ] **N7 (Part 4c)** `NlChrome` Search and Viewer bar variants; Large (collapsing) top bar and scroll-driven transparent small bar (needs a page twin that is transparent at scroll 0, and a scroll event page to native).
- [x] **N8** (2026-11-07, 4b) Drawer storage card (`dStorCard()` in `ui.html`, CSS block `LUMEN-UI16`): 56 px ring of two `<path>` arcs (used part in `primary`, `error` under 5 % free), "X free" + "used of total" for this phone from the `SP['local|/']` cache (fetched once when missing); tap opens the Storage analyzer. Painted by the existing drawer mirror, no Kotlin. Category arcs left out.
Files: `NlChrome.kt`, `NlDrawer.kt`, `ui.html`. Touches Kotlin: yes.

### L14 Container transform (Part 4d)
- [x] **M7 (b)** (2026-11-07, 4f) True shared-element container transform for viewer / player launch: image stays continuous from the tapped thumbnail, chrome fades in after 120 ms (today a platform scale-up stand-in). Needs the thumbnail rect from the page and an Activity transition or a snapshot overlay.
Files: `ImageViewerActivity.kt`, `PlayerActivity.kt`, `MainActivity.kt`, `ui.html`. Touches Kotlin: yes.

---

## 5. Session log (add one row per session, newest last)

| Date | Part | Result | Tick in 19.1c | Left over / new tasks | Kotlin touched? | Owner compile OK? |
|---|---|---|---|---|---|---|
| 2026-11-07 | (setup) | Plan created; V1 ticked | V1 | none | no | n/a |
| 2026-11-07 | (fix) | CI compile error `NlChrome.kt:469` `Lumen.mix` to `LumenCategory.mix` | none | none | yes (1 line) | check next CI run |
| 2026-11-07 | Part 1 (5-part plan) | P8 (mostly) P9 P5 P10 A3 M6 done; M1 M5 page side done | P9 P5 P10 A3 M6 x; P8 M1 M5 ~ | P8 `--t-*` use, native M1 / M5 | no | n/a |
| 2026-11-07 | Part 2 | M3 M4 P1 P2 P3 done; M2 P4 partly | M3 M4 P1 P2 P3 x; M2 P4 ~ | M2 shift / dim, `.g0` callers | no | n/a |
| 2026-11-07 | Part 3 | T1 (2 of 42 presets re-derived, report tool `preset_report.py`), T2 pairs + Auto/Light/Dark + Custom Auto, T3 switch, T4 hcl/hcd rings + 7:1 test, T5 preview card + `onWarning` + Ubuntu/Win11 buttons on slots, P7 gradient + glass floor tests (fixes: 6 presets, 4 glass tints, custom snap) | T1-T5 P7 x | `onWarning` in print log done; native use of `onWarning` elsewhere | yes (`LumenTokens.kt` new slot, `PrintLogActivity.kt`) | check next CI run |
| 2026-11-07 | Part 4a | A1: TalkBack text + selected state for list rows / grid cards (`NlA11y`), virtual buttons for gallery cells and drawer hit rects, snackbar live region + dialog pane title; A2: chips 48 px box (`lmChipAt`) with ripple; N2: `NlSheets` glass panel, r-xxl top corners, drag handle, swipe-to-dismiss, slide in / out, scrim 32 / 56 %; N1 (a): `LumenGlass` (F0-F3, API 31 blur-behind, page reports step through `glassStepSet`); N12: snackbar lifts above the dock (`Lm.setLift`); M1 native: snackbar exit 140 ms, dialog + sheet window exit styles; M5 native: Undo countdown bar + number merge | A1 A2 N1a N2 N12 M1n M5n x | N1 (b) chrome glass (4b); `NlSheets` Sort dialog keeps its own row layout | yes (`Lm.kt`, `NlSheets.kt`, `NlDrawer.kt`, `NativeList.kt`, `NlChrome.kt`, `MainActivity.kt`, new `LumenGlass.kt`, `NlA11y.kt`, `res/anim`, `res/values/lm_anim.xml`) | check next CI run |
| 2026-11-07 | (fix) | Selection-bar More popup stuck: native list swallowed taps outside the menu (`nlLay` now sends all touches to the page while `.dsc` is open, dims the list 18 %) | none | none | no | n/a |
| 2026-11-07 | Part 4b | N1 (b) stand-in: `LumenGlass.chromeFill` / `chromeGradient`, bar / dock / drawer by glass step; N4: `shadowTint` slot, `LumenGrad.kt`, gFab / gPrimary / gSuccess / gSurface callers; N6: `LumenRadius`; N8: drawer storage card + ring | N6 N8 x; N1b N4 ~ | real blur N1 (c) (4d); gGlow / gSkeleton / gScrim callers (4c / 4d); N7 moved to 4c | yes (`NlChrome.kt`, `NlDrawer.kt`, `NlSheets.kt`, `LumenGlass.kt`, new `LumenGrad.kt`, `LumenTokens.kt` generated) | check next CI run |
| 2026-11-07 | Part 4d (partly) | N9: `NlRpl` ripple in `NlRowView` + `NlGridView` (hover fill replaced); N10: `NlFlip` 160 ms check-circle flip in rows, hold lift pulse 1.02; M7 a: `NlSkelView` shown 180 ms after a tapped folder starts building, off in `applyItems` / `hide()` / 4 s; all in new `NlListFx.kt` + `NativeList.kt` | N9 N10 M7 ~ | N1 (c) real blur (owner decision still open), M7 b container transform, N11 sticky heading strip, N9 gallery cells + drawer rows, N10 day-heading partial state + dock stagger | yes (`NlListFx.kt` new, `NativeList.kt`) | check next CI run |
| 2026-11-07 | Part 4e | N9: `NlRpl` ripple on gallery cells (`NlGalView`, per-cell, replaces the 15 % black press fill) and drawer rows (`DrView`, hit rect, circle for the close button; `NlRpl.release()` added); N10: dock action cells stagger in (`NlDockView.enter()`, 20 ms apart, 6 dp rise, off with Reduce motion), page day / month heading circle squeezes open on `.part` / `.sel` (CSS `LUMEN-UI18`); N11: `NlStickyView` heading pill (group of the first visible row, 900 ms after scroll stops, only in lists over 1.5 screens, not over gallery cells, never takes touches) | N9 N10 N11 x | N1 (c), M7 (b) = Part 4f | yes (`NlListFx.kt`, `NativeList.kt`, `NlDrawer.kt`, `NlChrome.kt`) | check next CI run |
| 2026-11-07 | Part 4f | N1 (c): new `NlBackdrop.kt` (siblings under the chrome re-recorded into a 1/4-scale `RenderNode` + `RenderEffect` blur, drawn in the view's own onDraw, API 31+; F0 24 dp, F1 12 dp; F1 only on a non-low device out of battery saver; any failure = 4b stand-in) used by `NlBarView`, `NlDockView`, `DrPanel` (drawer); `LumenGlass.blurFill` (F0 72 / 66 %, F1 78 / 72 % light / dark; dock never under 82 %); repaint on list scroll and page push. M7 (b): new `NlHero.kt` snapshot overlay (PixelCopy of the list window, viewer starts with no platform animation, hero = the SAME thumbnail bitmap grows from its centre-cropped rounded rect to the picture fitted in the screen in 400 ms emphasized, snapshot fades to black, bars fade in after 120 ms, overlay lifts when the picture is ready, max 1.5 s); `NlRowView` / `NlGridView` remember `thumbR` / `thumbRad`; `NativeList.launchHero` for video and picture rows | N1c M7b x | gallery cells (`NlGalView`) and page-started viewers keep the Part 16 scale-up; PDF unchanged; blur is repainted on list scroll / page push only (page animations under the bar do not repaint it) | yes (`NlBackdrop.kt` new, `NlHero.kt` new, `NlChrome.kt`, `NlDrawer.kt`, `NativeList.kt`, `LumenGlass.kt`, `LumenPrefs.kt`, `ImageViewerActivity.kt`, `PlayerActivity.kt`) | check next CI run |
| 2026-11-07 | (review 4a-4f) | Audit fixes: sheet fling velocity read raw coordinates (a fling never closed the sheet), TalkBack bounds never empty (NlHitView, NlGalView), ripple stops redrawing while a finger is held, skeleton hide timer, blur repaint only when active, no nested blur | none | `NlChrome.onElev` is a static callback to the newest chrome (cleared only by the next chrome); device pass still open | yes (`NlSheets.kt`, `NlChrome.kt`, `NativeList.kt`, `NlListFx.kt`, `NlBackdrop.kt`) | check next CI run |
| | | | | | | |

## 6. Next session

- **Start with:** owner compile of 4f, then the device pass. Check first: Settings > Glass quality Full (F0) and Auto on a normal phone: top bar, dock and drawer blur the list under them while scrolling (Android 12+ only); Light / Reduce transparency = flat as in 4b; scroll speed unchanged (T8 `lmFrameTest`); open a picture and a video from a list row or grid card: the thumbnail grows into the viewer, bars fade in after a moment, no flash when it hands over; Reduce motion = old scale-up. If the blur stutters set `NlBackdrop.ENABLED = false`. Older note (4f), kept for history: Older note, Part 4c (N7 bar variants: Search / Viewer, Large collapsing bar, scroll-driven transparent small bar needs a transparent `#top` twin + scroll event + `--topH`; N3 grid / gallery category tiles; N5 folder drawing in pickers / album covers / drawer icons / Devices-FTP-Cloud tiles; N13 older-Android splash; P6 one colour table for Storage / search chips / badges / CodeHl / editor). Part 4b check first on a device: Settings > Glass quality F0-F3 (top bar hairline, dock and drawer more or less see-through, flat at the lowest step), FAB gradient + press ripple, snackbar progress, Windows 11 theme corners (dock pills, snackbar, View options sheet), open the side menu: storage card on top (ring + free space, tap = Storage analyzer), drawer panel must show its rows (if it is empty set `localStorage ls_nld='0'`), long-press a folder > More > tap outside closes it. Owner can run V2-V5 any time in parallel.
- **Check first (Parts 1-2):** swipe a row left / right, drag the scrubber, open Settings -> Glass quality (segment), delete a file (row collapses); open the page once in a browser / WebView: dialog + sheet + popup exits (`/*LUMEN-UI13*/` restores `display:flex` for 170 ms on close, cancels when content is replaced), sheet swipe on a bottom sheet, Undo bar under the snackbar.
- **Decisions waiting for the owner:** T1 regenerate presets or keep hex (Part 3); T3 OS font vs own font choice (Part 3); N13 older-Android splash logo or accept (Part 4c); N1 (c): decided by default in 4f (real blur at F0, F1 only on non-low devices; F2 / F3 keep the stand-in); revisit if T8 fails. M7 (b): snapshot overlay chosen.
