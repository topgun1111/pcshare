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

## Known gaps / ideas
- Long press no longer pans the picture while the finger is still down (`held` flag in `ZoomImageView`). No long-press-and-drag to extend (use the handles); no double-tap word/line select (double tap is zoom).
- Very small text in 12 MP+ photos is recognised at the viewer's decoded size (<= 3072/4096 px); a full-resolution region pass (crop the cached `cacheDir/img/<pos>.bin` around the zoomed area) would help - not built.
- Bar has Copy / Select all / Search / Share / X (Search = browser web search of the selection, max 200 chars). No "translate" yet; `selBar` is a plain `LinearLayout`, add buttons there.
- OCR result is kept per page only while the viewer is open (not persisted).
- 2026-10-10 (session 3, Part 6 polish): `ZoomImageView.held` (no pan after a selecting long press); **Search** button on the selection bar (`searchOcr()`). `ImageViewerActivity.kt` 635 -> ~648 lines. Still **not compiled / not device-tested**; Part 4 (GitHub build) and Part 5 (device checklist) need the user's side. Not built: full-resolution region OCR for small text, persisting results.
