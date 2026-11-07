# Lumen audit (2026-11-07, after Part 4f): spec and plan documents compared with the code

Method: read `LUMEN_DESIGN_SYSTEM.md` (spec + 19.1a/b/c), `LUMEN_WORK_PLAN.md`, handover and changelog; compared with `tokens.json`, the generated files, `ui.html` and the native Kotlin by grep and reading. Nothing was run on a device; Kotlin was never compiled by me. "Wrong" = differs from the spec or contradicts another document.

## A. Done wrong (code differs from the spec or from itself)
1. **Ember values differ from spec 4.2-4.4.** Light primary is `#b84d00` (spec `#C25100`); warning light `#f9ab00` (spec `#A66300`), dark `#fdd663` (spec `#FFC15E`); success dark `#81c995` (spec `#7FD99A`). Decide: spec wins or spec text is corrected. T1 passes either way.
2. **Semantic set incomplete (4.4).** No `info` family, no `successCont` / `warningCont`, no `onSuccess` / `onError` / `onInfo`. Only `error`, `errorCont`, `onErrorCont`, `success`, `warning`, `onWarning` exist. Spec banners (12.15), the Warning hero (conflict dialog) and Info dialogs cannot be built from slots.
3. **Folder colour is not the theme accent (4.5).** Native and page folders use the fixed app-icon colour (`#D2590A` / Lite `#9B63D8`, `NlClassicFolder.DEFAULT`), spec says theme `primary`. Part 10 chose this on purpose; the spec text was never changed.
4. **Dock action colours are hard-coded Material colours** (`NlChrome.kt` ~l.412-428: blue, orange, amber, green, cyan, purple, pink ...). They are not slots, not the category table, and do not follow the theme or dark mode. The download cards (`NlDlView`, `0xFF2E3133`, `0xFFFFB77C`, `0xFFF0F0F0`) are fixed dark colours too.
5. **Viewers ignore the tokens (12.22, 4.5).** Image viewer / PDF viewer / player use literal black, white and alpha values; the player seek bar fill is `0xFF4F63FF` (a brand blue that exists nowhere in the palette, spec: `gPrimary`); `PlayerWidgets` has its own light / dark set; scrims are hand-made gradients, `LumenGrad.scrimTop / scrimBottom / glow` still have **no caller**. Red badge `0xFFFF3B30` in `NativeList.kt:100`.
6. **Thumbnail radius (8.2)** is a fixed 12 dp (15 dp bare) in `NlGridView` / `NlRowView`; the spec rule is by column count (r.m up to 3 columns, r.s at 4-5, r.xs at 6-8). No 88 % "selected thumbnail" shrink found in native.
7. **FAB** is a pill (Part 11); spec 8.2 says rounded square 56 dp with 20 dp radius, pill only for the extended FAB.
8. **Dialog hero tiles (12.12)** exist only for destructive dialogs (`Lm.kt`); Info, Warning (conflict) and Permission heroes are not built. Native dialogs still run on `AlertDialog` through `AppPal.dlg` / `Lm.restyle` (restyled, not the single component of 12.12; no Details row, no "Apply to all" switch row).
9. **Real glass (6.x) is only partly real:** native bar / dock / drawer blur uses `NlBackdrop` (4f, API 31+, not compiled, not measured); page glass is CSS. The WebView is skipped in the blur since the flicker fix, so on page screens the bars blur a flat canvas colour.
10. **Dead code:** `NlFileIcons.drawLegacy` and its ~139 hex colours have no caller (19.1b still says it is used inside the grid badge; 4c says no caller). Unused `LumenGrad.glow / scrimTop / scrimBottom`. Static `NlChrome.onElev` keeps the newest chrome alive.

## B. Not done (spec items with no code)
- Large collapsing top bar (12.7), Search and Viewer bar variants; viewer auto-hide after 3 s with 200 ms fade (12.7 / 12.22).
- Banners (12.15), designed empty / error / permission states with glyph + glow (12.17: the page still says "Nothing here"), tooltips and coach marks (12.21), tabs with sliding 3 dp indicator and fade-through (12.10), pull-to-refresh as 40 dp glass circle (12.18; still `SwipeRefreshLayout`), scrollbar fade spec (12.19) only partly.
- Job cards (12.16): `JobOverlay` has no `gPrimary` / `gSuccess` bars, ring chart only in the drawer card.
- Breakpoints (9.4): drawer permanent at 900 dp+, 24 / 32 dp margins, foldable reflow; dialog widths per 9.2; sheets Full size / modal-center only on the page.
- `--t-*` text styles used 10 times, `--st-*` never (P8); 194 raw hex in page CSS (kept on purpose); native `LumenTypography` does not exist (native sizes are hand-set sp).
- Haptic map (13.5): pull-to-refresh tick and success "double soft tick" not checked in native.
- Gallery cells (`NlGalView`) and page-started viewers have no container transform; no return transition.
- Round 2 items 7 and 8 and P4 `.g0` callers, M2 old-screen shift / dim, native N5 pickers / album covers, P6 `StorageWiz`, search chips, Drive / FTP / SMB badges.
- Verification debt V2-V5 (device pass, TalkBack, glass hint guess, dark selector lists) and every 4a-4f Kotlin change except what CI compiled once.

## C. Documents contradict each other (fixed here unless noted)
- 19.1c still showed V1 open, N1 `[~]`, M7 `[~]` after the work was done: ticked now.
- 19.1a "Status and decisions" still starts "Done: Parts 1-11 and 17 ... Next: device verification" and has no rows for Parts 4a-4f; the plan file is the only current status. (Not rewritten.)
- 19.1 table says "18 parts"; the plan says 8 parts + Round 2. Two numbering systems (Part 1-18 / Round 2 A-C / plan Parts 1-4f) are used in the same documents.
- 19.1b says `drawLegacy` is used, 4c says it is not (see A10).
- 19.1b / plan keep "Part 4c left over" items (Large bar, Search / Viewer variants, `NlSplash`) that 19.1c lists as N7 / N13 with different wording.
- `LUMEN_WORK_PLAN.md` status line counts "Open items: 9" but names more than 9.

## D. Suggested next order
1. Owner device pass V2-V5 + 4f check (blur flicker, transition) before more native code.
2. Decide A1 (spec vs code values) and A3 (folder colour): both are one-line documents or token edits.
3. Semantic slots (A2) -> unlocks hero tiles (A8), banners and the conflict dialog.
4. Move dock / download-card / viewer colours onto slots and the category table (A4, A5); give `LumenGrad.scrim*` its callers.
5. Remove dead `drawLegacy`.

## E. Fixed after this audit (2026-11-08, page only, no Kotlin)
- Picker swatches for Light / Dark / Follow system used old spec hex (`#c25100`, `#18120d`); now the token values (`#b84d00`, `#17110c`, canvas / ink from `tokens.json`).
- Found, not changed: 16 presets have no Light / Dark partner (mode switch leaves them as they are): solar, nord, dracula, cyber, matcha, coffee, midnight, tokyo, rosepine, catppuccin, vivaldi, opensuse, ubuntuorange, kali, popos, garuda. `drawLegacy` removal skipped: Kotlin cannot be compiled here.
- A2 done on the colour side (4g, `tokens.json` / `derive.py` / `custom.js` / `gen.py`, Kotlin regenerated, not compiled): new slots `info`, `onInfo`, `onError`, `onSuccess`, `successCont`, `warningCont`, derived for all 42 presets and Custom (best of dark brown / white / black for the `on*` colours; info is blue at 4.5:1 on canvas and surface; containers are surface mixed with the colour). `contrast.py` has 7 new pairs, 0 failing; parity 0 differences. Still unbuilt: the dialog heroes, banners and conflict dialog that use them (A8). Section C items for 19.1a status and the open-items count fixed.
