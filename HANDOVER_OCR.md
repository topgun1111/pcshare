# HANDOVER - Select text in pictures (OCR) - image viewer

**Status 2026-10-10: code complete, NOT compiled, NOT device-tested** (no Android SDK in the authoring environment; braces/parens balance checked only). First step next session: push, let `.github/workflows/build-apk.yml` build, paste compiler errors.

## What it does
- Image viewer top bar: new **T** button (between slideshow and edit). Press = recognise the current picture, show a faint box around every word. Press again (or the bar's X) = hide.
- **Long press** a word = selects it. Two blue **handles** (circle under the word) drag to extend / shrink the selection; crossing handles swap roles. Tap on the picture clears the selection (instead of hiding the bars while a selection exists).
- Bottom bar (visible while the text layer is on): **Copy** (selection, else "Copy all"), **Select all**, **Share** (text/plain chooser), **X**.
- Pinch / pan / double-tap / page swipe keep working: the overlay only claims touches that start on a handle.
- Offline after first use; the Latin model comes from Google Play Services (same artifact as the image-search OCR). Missing model -> `MlKitOcr` asks Play Services to download it and polls up to 3 min, toast "Downloading text model...".

## Decisions (from the planning chat)
- Build on **ML Kit**, not Google Lens (no stable in-app API, leaves the app). OCR engine kept **separate** from the selection UI so the engine can be swapped (e.g. bundled model, Tesseract, cloud) without touching the viewer.
- Image viewer is **native Kotlin** (ViewPager2 + `ZoomImageView` : `ImageView` with `ScaleType.MATRIX` showing a decoded `Bitmap`), **not** an HTML `<img>` in a WebView, so the overlay is a native `View` on top, not JS.
- `ClipEngine.kt` (search index OCR) has a comment "bundled Latin model"; the dependency is actually the **unbundled** Play Services artifact (`play-services-mlkit-text-recognition:19.0.1`, model downloaded on first use).
- Weekend work is done in short sessions on free Claude accounts: keep each part small, attach only the files named for it, and update this file at the end of every session.

## Three-part plan (status)
| Part | Scope | Files | Status |
|---|---|---|---|
| **1 Engine** | ML Kit text with geometry, model availability / download, no UI | `OcrEngine.kt` (112 lines) | written, **not compiled** |
| **2 Selection UI** | overlay: word boxes, long-press word select, drag handles, selected text, select all | `OcrOverlayView.kt` (166 lines) | written, **not compiled** |
| **3 Viewer wiring** | T button, per-page result cache, text layer per page, bottom action bar (Copy / Select all / Share / Close), `ZoomImageView` hooks | `ImageViewerActivity.kt` (+117 lines) | written, **not compiled** |
Follow-ups (not part of the three): **4 build + compile fixes**, **5 device test (checklist below)**, **6 polish / gaps**.

### Contract between the parts (do not break without updating all three)
```kotlin
data class OcrWord(val text: String, val box: RectF)                 // box in bitmap pixels
data class OcrLine(val text: String, val box: RectF, val words: List<OcrWord>)
interface OcrEngine { fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit); fun close() }   // callbacks on main thread
class OcrOverlayView(c: Context) : View(c) {
    fun setImageMatrix(m: Matrix); fun setLines(l: List<OcrLine>?)
    fun selectWordAt(x: Float, y: Float): Boolean; fun selectAll(); fun clearSelection()
    fun selectedText(): String?; fun allText(): String?; fun hasLines(): Boolean; fun hasSelection(): Boolean
    var onSelection: ((String?) -> Unit)?
}
// ZoomImageView: var onTap, var onHold: ((Float, Float) -> Boolean)?, var onMatrixChanged: ((Matrix) -> Unit)?, fun matrixNow(): Matrix
```

### Per-part session recipe (what to attach to a fresh chat)
- **Part 1 only**: `build.gradle.kts` + `OcrEngine.kt` + `core/ClipEngine.kt` lines 320-375 (reference for the Play Services module check).
- **Part 2 only**: `OcrOverlayView.kt` + the `ZoomImageView` class (bottom of `ImageViewerActivity.kt`, from line ~532).
- **Part 3 only**: `ImageViewerActivity.kt` + `OcrEngine.kt` + `OcrOverlayView.kt`.
- **Compile fixes**: the files named in the error log + the log itself. Ask for patches of changed files only, as a zip.
- Prompt skeleton: "Project: LANShare (Android, Kotlin, no Compose). Task: <part>. Contract: <paste block above>. Do not change other files. Output: the full changed files. Update HANDOVER_OCR.md status table."

## Session log
- 2026-10-10 (session 1): inspected the ZIP (74 files, 15,767 lines Kotlin), confirmed native viewer, wrote parts 1-3 in one go, updated `HANDOVER.md` + this file. Nothing compiled (no Android SDK in the authoring sandbox).
- Next: push -> GitHub Actions build -> paste errors (Part 4) -> device checklist (Part 5).

## Files
| File | Role |
|---|---|
| `OcrEngine.kt` (new, 112 lines) | `OcrWord`, `OcrLine`, `interface OcrEngine`, `MlKitOcr` (availability check -> download/poll -> `process(InputImage.fromBitmap(bm,0))` -> Block/Line/Element mapped to boxes). Main-thread callbacks. `onStatus` for toasts. Independent of `core/ClipEngine.kt` (that one only returns a flat string for the search index). |
| `OcrOverlayView.kt` (new, 166 lines) | Transparent view above `ZoomImageView`. Flat word list in reading order, selection = inclusive word range `[a..b]`, draws via the image matrix, handle hit-test / drag, `selectWordAt`, `selectAll`, `selectedText`, `allText`, `onSelection`. |
| `ImageViewerActivity.kt` (edited, 518 -> 635 lines) | `Pg` got `ov` + `applyOcr`; adapter creates the overlay above the image; T button, `selBar`, `toggleOcr/showOcr/closeOcr/updateOcrBar/copyOcr/shareOcr`; per-page caches `ocrRes` (lines) and `ocrOn` (pages with layer on), cleared in `begin()` and after "replace original" in the editor; `ocr.close()` in `onDestroy`. `ZoomImageView` got `onHold(x,y)`, `onMatrixChanged(m)`, `matrixNow()`, long-press hook (`GestureDetector.onLongPress`). |
| `HANDOVER.md` | one entry added at the top. |
No gradle / manifest changes (ML Kit + play-services-base were already dependencies).

## Design notes (why)
- OCR runs on the bitmap the viewer already shows (`cache.get(pos)`, EXIF-rotated, long side <= 3072/4096) so box coordinates map to the screen with the image view's own matrix; no rotation / sample-size bookkeeping. Same decode is deterministic, so an evicted-and-reloaded page keeps valid boxes.
- Long press is detected by `ZoomImageView` (it owns the gesture stream), not by the overlay, so the overlay never steals pinch / pan / swipe.
- Words are ML Kit `Element`s (whitespace-separated); text is rebuilt as words joined by space, lines by `\n`.
- Boxes are axis-aligned `boundingBox`es; strongly rotated text selects fine but the boxes are loose.
- Not Google Lens: no stable in-app API; ML Kit keeps everything inside the app.

## To verify on a device (checklist)
1. Build compiles (likely trouble spots: `Result.onSuccess/onFailure` lambdas, `ModuleInstallClient` import, `RectF(Rect)`).
2. Photo of a printed page: T -> boxes appear; long press a word -> highlight + two handles; drag handles across lines; Copy -> paste elsewhere; text order / line breaks correct.
3. Pinch-zoom with selection on: handles and highlight follow the picture; zoomed pan still works; swiping to the next page works and the bar hides; coming back restores the layer.
4. Turkish text: check `ç ğ ı İ ö ş ü` come out correctly (Latin recogniser; flagged unverified in `ClipEngine.kt` too).
5. First use with no model: toast "Downloading text model...", then it continues by itself; airplane mode afterwards still works.
6. Edit -> replace original -> text layer is dropped for that page.
7. Rotation / split-screen: layer stays aligned (`onSizeChanged` -> `reset()` -> `onMatrixChanged`).

## Automatic background reading (session 4, "Galaxy-like" step 1)
- The viewer reads pictures by itself, no button press needed: 0.7 s after a page is shown (and when a page finishes loading) `autoOcr()` reads the current picture, then the next, then the previous one, one at a time (`ocrWork`). Skipped during the slideshow.
- **T button state** (`updateOcrBar`): bright = text found or layer on, dim (0.35) = read, no text, half = reading now, 0.8 = not read yet. Press = show the layer instantly if already read; if still reading it shows when done (`ocrWant`, no delay).
- **Disk cache** `cacheDir/ocr/<uuid>.json` (`ocrFile/ocrSave/ocrLoad`): lines + word boxes, key = url query + size, valid only if the decoded bitmap has the same width/height; files older than 30 days are deleted at start (`pruneOcr`). Re-opening a picture shows its text at once.
- Failures: a page that fails is skipped by auto mode (`ocrBad`); after 2 failures in a row auto mode stops (`ocrFails`, e.g. no Play Services / model missing); pressing T retries. Toasts ("Downloading text model...", "Reading text...") only while the user waits after pressing T.
- Not compiled / not device-tested. Check: open a photo of text, wait 1-2 s, T turns bright; press T = boxes appear immediately; swipe to the next photo of text = also ready; airplane mode + a photo already read = still instant.

## Roadmap: making it feel like Samsung Gallery (options, in order of value)
Do these only after the first build is green and the basic version works on the phone. One option per session.
1. **Automatic, instant text** - DONE (session 4, see section above; not compiled).
2. **Better accuracy** - PARTLY DONE (session 5, `OcrTiled.kt`, not compiled; see section below). Not done: Turkish-letter fixes (needs a device test first). Original idea: read the full-resolution picture or tiles of it (small print, document photos) instead of the <=3072/4096 px viewer bitmap; if Turkish `ı İ ğ ş ç` come out wrong with the Latin model, try Tesseract with Turkish data or a small correction pass. Note: boxes must then be mapped back to the viewer bitmap.
3. **Native-feeling selection** - DONE (session 6, see section below; not compiled). Original idea: tap a word directly while the layer is on (no long press); double-tap = word, triple-tap = line; draw boxes only around the touched area instead of every word; magnifier while dragging the handles; floating toolbar above the selection (Copy / Search / Share / Translate).
4. **Translate** - DONE (session 6, see section below; not compiled). Original idea: offline Turkish <-> English button with ML Kit Translate (new dependency + model download); useful for the English-teacher use case.
5. **Smart actions** - DONE (session 8, `OcrSmart.kt`, not compiled): bottom-bar **Links** button (visible only when the selection / all text contains a link, e-mail or phone number) opens a picker -> browser / mail app / dialer; **Save** button writes the selection (else all text) to `Downloads/picture-text-<timestamp>.txt` (MediaStore on Android 10+, app files dir below).
6. **Tilted text** - use ML Kit corner points (`cornerPoints`) so boxes follow rotated text instead of loose axis-aligned rectangles (needs `OcrWord` to carry a polygon; change the contract in all three parts).
Extra ideas: Settings switch for background reading (on / only while charging / off); persist nothing else.

## Accuracy: `TiledOcr` (session 5, Galaxy-like step 2)
- New `OcrTiled.kt`: `class TiledOcr(base: OcrEngine) : OcrEngine` - same contract, wraps `MlKitOcr` (`ImageViewerActivity.ocr` is now `TiledOcr(MlKitOcr(..))`, type `OcrEngine`; toasts still come from the inner engine).
- **Long side >= 2400 px**: whole-picture pass first (large text, reading order), then overlapping 1792 px tiles (overlap ~256) read one after the other at 1:1. A tile keeps only words whose centre is in its own *core* (middle of each overlap) and that do not touch a cut edge. Tile reading wins where it found the same word (>= 40 % overlap of the smaller box); whole-picture lines >= 96 px tall (big text) are left to the whole pass; pieces of one row (tile borders, left-overs) are joined (`joinRows`: vertical overlap >= 60 %, gap <= 0.8 x line height); order = after the nearest whole-picture line (vertical distance counts 16x), then top / left. Any error in the merge -> whole-picture result only. A tile that cannot be cut (memory) is skipped.
- **Long side < 900 px**: enlarged 2x, boxes scaled back. In between: unchanged.
- Cost: up to 4-9 extra ML Kit calls per big picture (~2-4 s in the background; the T press waits for it when pressed early).
- Disk cache now stores `"v": 2` (`OCR_VER` in `ImageViewerActivity`); results of another version are read again.
- To test: photo of a printed page with small print taken at 12 MP: compare with before (small words appear, no doubled words at the tile borders, line order sane, big headline still one piece). Tuning constants are at the bottom of `OcrTiled.kt` (`BIG, SMALL, TILE, OVER, EDGE, BIGTEXT`).
- Turkish `ı İ ğ ş ç`: unchanged (Latin model). If the device test shows errors: next step = Tesseract `tur` data behind the same `OcrEngine` interface, or a post-correction pass.

## Native selection + Translate (session 6, roadmap 3 + 4)
**WARNING (updated, session 7):** `OcrEngine.kt` and `OcrOverlayView.kt` were NOT in the zip (only `OcrTiled.kt` was). Session 6 rewrote the overlay; the re-uploaded zip (session 7) still had no `OcrEngine.kt`, so session 7 **wrote `OcrEngine.kt` from the contract** (`OcrWord`, `OcrLine`, `OcrEngine`, `MlKitOcr`: Play Services module check/download poll up to 3 min, `process`, Element boxes, single worker thread, main-thread callbacks). If you have your own original, keep yours and delete this one (duplicate classes will not compile). Original wording:  `OcrOverlayView.kt` was therefore **rewritten from the contract** and is a full replacement: if your repo already has an `OcrOverlayView.kt`, this one overwrites it (public API identical + 4 additions below). `OcrEngine.kt` was not touched - keep yours.
- **Tap a word** (layer on) = selects it at once; **double tap** = its line; **triple tap** = its paragraph (lines with small gaps / similar height / overlapping horizontally, `paragraph()`). Long press still works. Tap on empty space clears the selection / hides bars; double tap on empty space still zooms (double tap on a word does not).
- Routing: `ZoomImageView` got `onTextHit(x,y)`, `onTextTap(x,y,count)`, `onTouchAt(x,y)`; it decides at ACTION_DOWN whether the touch starts on a word (before the GestureDetector sees a 2nd tap), counts taps itself (450 ms, 6x touch-slop), and swallows the delayed single-tap (`textTapUsed`).
- **Outlines only near the finger**: all word boxes flash ~1.5 s when the layer opens, then only words within 120 dp of the finger are outlined (fading, ~2 s). Selection = one rounded blue bar per line.
- **Handles** (circle under start / end word, 30 dp reach) + **magnifier** while dragging (`android.widget.Magnifier`, Android 9+, wrapped in try/catch; none on Android 7-8). Finger aims 26 dp above the touch point so the word stays visible.
- **Floating toolbar** drawn by the overlay above the selection (below it when no room): **Copy / Search / Translate / Share**, hidden while dragging a handle. Callback `onAction(id)`. The bottom bar is now Copy(all) / Select all / **Translate** / Share / X (Search moved to the floating toolbar; Search with no selection no longer exists).
- Overlay contract additions: `hitsWord(x,y)`, `tapSelect(x,y,count)`, `touchHint(x,y)`, `var onAction`.
- **Translate** (`OcrTranslate.kt`, new deps `com.google.mlkit:translate:17.0.3` + `language-id:17.0.6`): language detected on device; Turkish -> English, anything else (or undetermined = English) -> Turkish. Result in a dialog (selectable text) with Copy / **Swap** (re-translates the same text with the reverse pair, use it when detection was wrong) / Close. Wrapped lines are joined into running text before translating (`OcrTranslate.prep`: a line break stays only after . ! ? : ;). Max 4000 chars. Each language pack (~30 MB) downloads once (needs internet), then offline. APK grows a few MB (arm64 only).
- Not compiled (no Android SDK; kotlinc 2.0 parse check of the 3 changed Kotlin files shows no syntax errors, type check impossible without android.jar / ML Kit / `OcrEngine.kt`). Likely compile trouble spots: `Magnifier` call, `return@run` in `translateOcr`, ML Kit `Task` listeners in `OcrTranslate`.
- To test on the phone: (1) photo of text, T -> boxes flash then fade; (2) tap a word = blue, toolbar above; double tap = line; triple = paragraph; (3) drag a handle: magnifier shows, selection follows, toolbar returns on release; (4) pinch/pan with a selection: toolbar + handles follow; (5) Translate on a Turkish and an English photo; first use downloads the pack (needs internet); Swap works; airplane mode afterwards still translates; (6) tap on empty space clears, second tap hides bars; double tap on empty space zooms.

## Known gaps / ideas
- Long press no longer pans the picture while the finger is still down (`held` flag in `ZoomImageView`). No long-press-and-drag to extend (use the handles); no double-tap word/line select (double tap is zoom).
- Very small text in 12 MP+ photos is recognised at the viewer's decoded size (<= 3072/4096 px); a full-resolution region pass (crop the cached `cacheDir/img/<pos>.bin` around the zoomed area) would help - not built.
- Bar has Copy / Select all / Search / Share / X (Search = browser web search of the selection, max 200 chars). No "translate" yet; `selBar` is a plain `LinearLayout`, add buttons there.
- OCR result is kept per page only while the viewer is open (not persisted).
- 2026-10-10 (session 3, Part 6 polish): `ZoomImageView.held` (no pan after a selecting long press); **Search** button on the selection bar (`searchOcr()`). `ImageViewerActivity.kt` 635 -> ~648 lines. Still **not compiled / not device-tested**; Part 4 (GitHub build) and Part 5 (device checklist) need the user's side. Not built: full-resolution region OCR for small text, persisting results.
- 2026-10-10 (session 4): automatic background OCR + disk cache + T button states (see section above). `ImageViewerActivity.kt` ~757 lines. Not compiled.
- 2026-10-10 (session 7): re-checked the new zip (identical to session 6 output, no compiler log yet); added the missing `OcrEngine.kt`. Next: push -> build -> paste errors.
- 2026-10-10 (session 6): roadmap 3 (tap / double / triple-tap select, finger-local outlines, magnifier, floating toolbar) + 4 (offline Translate). `OcrOverlayView.kt` rewritten (it was missing from the zip), `OcrTranslate.kt` new, `ImageViewerActivity.kt` + `build.gradle.kts` edited. Not compiled.
- 2026-10-10 (session 5): `TiledOcr` (tiles for big pictures, 2x for small ones), cache version 2. Not compiled.
- 2026-10-10 (session 8): roadmap 5 (smart actions) added: `OcrSmart.kt` new, `ImageViewerActivity.kt` +~25 lines (`smartBtn`, `smartOcr()`, `saveOcr()`). Remaining: 6 (tilted text via `cornerPoints`, changes the contract in all parts - do it only after the first green build) and Parts 4-5 (compile + device test, user's side: no Android SDK here).
