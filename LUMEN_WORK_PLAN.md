# Lumen work plan: remaining work split into session parts

Companion to `LUMEN_DESIGN_SYSTEM.md` (spec + item list 19.1c). This file answers three questions between sessions: **what is done, what is next, what each next part contains.**
Created 2026-11-07. Item IDs (V / N / M / P / T / A) are the ones in 19.1c; every one of the 42 open items sits in exactly one part below.

**Status line (edit this first in every session):**
`Done: Parts 1-18 + Round 2 A / B / C(partial) + V1 | Next: L1 | Open items: 42 | Last session: 2026-11-07`

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
| **V1: APK compiled by the owner, app runs fine** | 2026-11-07 | |

Open parts of the above are tracked in the parts below, not here.

---

## 3. Part overview (do in this order unless noted)

| Part | Title | Items | Layer | Est. lines | Depends on | Status |
|---|---|---|---|---|---|---|
| L1 | Device verification | V2 V3 V4 V5 | owner on device | 0-100 | none | NEXT |
| L2 | Page cleanup and token adoption | P8 P9 P5 P10 A3 | page | 200-300 | none | TODO |
| L3 | Motion A: exits, sheet swipe, snackbar | M1 M6 M5 | page + `Lm.kt` | 200-300 | none | TODO |
| L4 | Motion B: list, wallpaper, screens | M3 M4 M2 | page | 200-300 | L3 | TODO |
| L5 | Page controls and glass leftovers | P2 P3 P4 | page | 250-350 | none | TODO |
| L6 | Page list interactions | P1 | page | 250-350 | L4 (M3) | TODO |
| L7 | Themes and contrast | T1 T2 T3 T4 T5 P7 | page + tools | 300-450 | L5 | TODO |
| L8 | Native accessibility | A1 A2 | native + page | 120-200 | L1 (V3 findings) | TODO |
| L9 | Native sheets and dialogs | N2 N12 N1 (a) | native | 200-300 | L3 | TODO |
| L10 | Native chrome glass and gradients | N1 (b) N4 N6 | native | 200-300 | L9 | TODO |
| L11 | Native tiles, icons, colour tables | N3 N5 N13 P6 | native + page | 300-450 | L1 | TODO |
| L12 | Native list interactions | N9 N10 N11 M7 (a) | native | 250-350 | L6, L11 | TODO |
| L13 | Native bar variants and drawer header | N7 N8 | native + page | 150-250 | L10 | TODO |
| L14 | Container transform | M7 (b) | page + native | 80-150 | L12 | TODO |
| | **Total** | **42** | | **about 2,700-4,150** | | |

Status values: `NEXT`, `TODO`, `IN PROGRESS`, `DONE (date)`, `PARTLY (date, what is left)`.
Page parts (L2-L7) can run in any order and do not need a device. Native parts (L8-L14) should wait for L1 so device findings feed them.

---

## 4. Parts in detail

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

### L8 Native accessibility
- [ ] **A1** `contentDescription` / roles for native list rows (`NativeList`), drawer rows (`NlDrawer`), `Lm` dialogs and snackbar (live region). Use `ExploreByTouchHelper` virtual nodes where rows are drawn on a canvas (as `NlHitView` does).
- [ ] **A2** Chips: real 48 px hit box with ripple (today a click redirect on the `.chips` padding, no ripple).
Add the findings of V3 as extra tasks here.
Files: `NativeList.kt`, `NlDrawer.kt`, `Lm.kt`, `ui.html`. Touches Kotlin: yes.

### L9 Native sheets and dialogs
- [ ] **N2** `NlSheets`: Lumen look (surface slot, `r-xxl` top corners, edge hairline), motion tokens, drag handle, swipe-to-dismiss, scrim slot.
- [ ] **N12** Snackbar lift above the bottom bar / dock (`Lm.snack`: read dock height from the page via palette / bar event), system toast only where no Activity exists.
- [ ] **N1 (a)** Native glass for dialogs (`Lm.restyle`) and sheets (`NlSheets`): API 31+ blur via `setBackgroundBlurRadius` / `RenderEffect` with the F0-F3 ladder from `LumenPrefs`; fallback = opaque surface + hairline. Mark N1 done only after part (b) in L10.
Files: `NlSheets.kt`, `Lm.kt`, `LumenPrefs.kt`. Touches Kotlin: yes.

### L10 Native chrome glass and gradients
- [ ] **N1 (b)** G1 on top bar, selection bar, bottom bar / dock and drawer (blur on API 31+, tint-raised stand-in below).
- [ ] **N4** Native chrome FAB / buttons on `gPrimary`; `gSurface` on cards; `gChrome` everywhere; `gSuccess`; `gGlow`; `gSkeleton`; `gScrimTop / gScrimBottom`; `shadowTint` as a real slot in `tokens.json` (generated into `LumenTokens.kt`).
- [ ] **N6** Native radius flavour: win11 `--r-l` 12 / `--r-xxl` 24; native dialogs keep 32 dp.
Files: `NlChrome.kt`, `NlDrawer.kt`, `Lm.kt`, `tokens.json`, `gen.py`. Touches Kotlin: yes.

### L11 Native tiles, icons, colour tables
- [ ] **N3** Category tiles in grid / gallery (`NlGridView`, `NlGalView`, 64-96 dp "Grid file"); realistic Word / Excel / PowerPoint tiles move from `drawLegacy` to the realistic-tile set.
- [ ] **N5** Folder drawing in pickers, album covers and other native places; drawer icons (`NlDrawer`); tiles for Devices / FTP / Cloud sheets.
- [ ] **N13** Splash: logo screen on Android under 12 (window background only today): add a short `NlSplash` logo frame or accept and document.
- [ ] **P6** One colour table (4.5) for Storage Analyzer treemap / pie (`StorageTreemap.CAT_COL`, `StorageWiz`, `StoragePie`), search chips, Drive / FTP / SMB badges, `CodeHl`, dark viewer chrome and text-editor palette: read `LumenCategory` / `Lumen` slots instead of own constants.
Files: `NlFileIcons.kt`, `NativeList.kt`, `NlSheets.kt`, `NlDrawer.kt`, `StorageTreemap.kt`, `StorageWiz.kt`, `StoragePie.kt`, `CodeHl.kt`, `TextEditorActivity.kt`, `NlSplash.kt`. Touches Kotlin: yes.

### L12 Native list interactions
- [ ] **N9** Native ripple inside list rows, grid / gallery views, drawer rows (today they keep the `--hov` press fill); reuse `NlHitView.ripple()`.
- [ ] **N10** Selection check-circle flip (160 ms), partial / bar state on the day-heading select circle, hold-menu lift (1.02), 20 ms action stagger on the dock.
- [ ] **N11** Sticky heading strip and fast-scroll scrubber in the native list (twin of P1; same thresholds).
- [ ] **M7 (a)** Skeletons in native lists (shimmer rows with `gSkeleton`, shown while the folder loads).
Files: `NativeList.kt`, `NlChrome.kt`, `NlDrawer.kt`. Touches Kotlin: yes. Risk: highest, list drawing is performance-critical; check T8 after.

### L13 Native bar variants and drawer header
- [ ] **N7** `NlChrome` Search and Viewer bar variants; Large (collapsing) top bar and scroll-driven transparent small bar (needs a page twin that is transparent at scroll 0, and a scroll event page to native).
- [ ] **N8** Drawer storage-card header with ring chart (used / free arc in `primary`, category arcs optional), reading the storage numbers the page already has.
Files: `NlChrome.kt`, `NlDrawer.kt`, `ui.html`. Touches Kotlin: yes.

### L14 Container transform
- [ ] **M7 (b)** True shared-element container transform for viewer / player launch: image stays continuous from the tapped thumbnail, chrome fades in after 120 ms (today a platform scale-up stand-in). Needs the thumbnail rect from the page and an Activity transition or a snapshot overlay.
Files: `ImageViewerActivity.kt`, `PlayerActivity.kt`, `MainActivity.kt`, `ui.html`. Touches Kotlin: yes.

---

## 5. Session log (add one row per session, newest last)

| Date | Part | Result | Tick in 19.1c | Left over / new tasks | Kotlin touched? | Owner compile OK? |
|---|---|---|---|---|---|---|
| 2026-11-07 | (setup) | Plan created; V1 ticked | V1 | none | no | n/a |
| 2026-11-07 | (fix) | CI compile error `NlChrome.kt:469` `Lumen.mix` to `LumenCategory.mix` | none | none | yes (1 line) | check next CI run |
| | | | | | | |

## 6. Next session

- **Start with:** L1 (owner: run V2-V5 on device and send results) or, if no device time, L2 (page cleanup).
- **Carry-over from last session:** none.
- **Decisions waiting for the owner:** T1 regenerate presets or keep hex (L7); T3 OS font vs own font choice (L7); N13 older-Android splash logo or accept (L11).
