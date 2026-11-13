# Handover chapter file - ch11-image-search-ocr

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

## 11. Image search and OCR

### 11.1 Image recognition search ("AI search")
**Goal**: the web portal's search on the phone: CLIP ViT-B/32 image vectors (visual search: CLIP text vector, cosine >= 0.20, top 60) and OCR text search ("Metin Arama": word / substring with Turkish folding). Reference files (`arama.php`, `clip_worker.js`) are not in the repo. **State**: code complete; **the first real device test is still open** (checklist below). Settings > Image search; drawer entry "Image search".

**Backend (`core/ImgSearch.kt`)**
- `interface Engine { id; canOcr; embedImage(jpeg); embedText(q); ocr(file); release() }`, `@Volatile var engine`; until set, `start` answers 400 "image search engine is not installed yet" (text search still works on stored text).
- Discovery walks the storage root (jpg / jpeg / png / webp / bmp / heic / heif, skips `Android/` and hidden dirs), virtual paths like `/DCIM/Camera/x.jpg`. Incremental index keyed by path + mtime + size; vanished files dropped; unreadable pictures `failed` (retried on change or `force`); engine id change = index discarded. Stored in `<filesDir>/imgsearch/index.json` + `vec.bin` (float32 LE, 512 per entry; `.tmp` then rename, manifest last); checkpoint every 100 pictures; daemon thread `imgsearch`. CLIP input = `Thumbs.make(file)` (600 px, EXIF-rotated).
- `visual(q)`: dot product >= 0.20, top 60. `text(q, whole)`: lower-case(tr) + `ı -> i`; whole word = `(?<![\p{L}\p{N}])q(?![\p{L}\p{N}])`, else substring.
- Robustness: `Engine.release()` after every scan; `IOException` / `OutOfMemoryError` = bad picture, any other exception (ONNX `OrtException`) is logged and 5 in a row abort the scan ("Remove the model in Settings and download it again"); `uninstall` refused during a scan; model download sends `Accept-Encoding: identity`.
- **Auto-scan**: `startAutoWatch(ctx)` (after the first screen, 13) = `ContentObserver` on `MediaStore.Images` (20 s debounce) + 10 min poll; only after a first scan, engine installed, permission ok, not during a scan; deferred 15 min when battery < 20 % and not charging; OCR only if the index already has text. Settings `img_auto` (default on), `img_auto_chg`; `POST /api/imgs {op:"auto", on, charging}`.
- **Folders**: Settings > Image search folders (`openExcluded()`): **Only these folders** (empty = whole phone) and **Skip these folders** (wins); `lanshare.json` `img_incl` / `img_excl`; `isOut()` drops out-of-scope pictures from index and results. **Album AI search**: `isView(sdir, sname)` sends `dir`; `visual/text(..., dir)` keep pictures in `dir` or below.
- **API**: `GET /api/imgs` -> `{state idle|running|error, engine, ocr, total, done, indexed, withText, failed, count, msg, error, model{installed, installing, msg, error, bytes, totalBytes}, msPerPic, auto, included[], excluded[]}`; `POST /api/imgs {op: start{ocr,force} | cancel | clear | install | uninstall | selftest | visual{q,dir?} | text{q,mode:word|part,dir?} | auto | included | excluded}`. Not in `InProc.ROUTES`.

**Engine (`core/ClipEngine.kt`)**
- Dependencies `onnxruntime-android:1.18.0` + ML Kit text recognition; APK ~110 MB without an ABI filter, `abiFilters += "arm64-v8a"` fixes it. ML Kit Translate was removed 2026-10-10 (APK 25 -> 45 MB).
- `install()` (thread `clip-download`) downloads once into `<filesDir>/models/clip/`: `vision.onnx`, `text.onnx` (quantized), `vocab.json`, `merges.txt` from `huggingface.co/Xenova/clip-vit-base-patch32` (~150 MB, manual redirects, `.part` then rename, no resume; **file names / sizes unverified**). `tryLoad(ctx)` in `Core.start`.
- `Impl` id `clip-vit-b32-q8-v1` (**bump when preprocessing changes**): lazy ORT sessions (2 threads), output `image_embeds` / `text_embeds` else first `[1][512]`, L2-normalised; image preprocessing = shortest side 224, centre crop, mean / std, NCHW. `ClipTokenizer` = CLIP byte-level BPE, `[49406 ... 49407]`, max 77; written from memory, **not run against the real vocab**.
- **OCR for the text index**: `Impl.ocr()` uses `TiledOcr(AutoOcr)` (11.2), 150 s per picture; gallery-only `TiledOcr.skipTilesIfEmpty`. Pictures indexed earlier keep their old text until **Rescan all**.
- Diagnostics: `{op:"selftest"}` -> `catIds` / `catIdsOk` (must equal `[49406,320,1125,539,320,2368,49407]`), `selfDot` (~1.0), `catVsDog`, `catVsKitten`, `textMs`; the scan logs ms per picture every 100 pictures.

**UI (`isView()` in ui.html)**: `#gal` overlay; card with Visual / "Text in pictures" tabs, Whole word / Contains chips, result grid with score % badge; card states from `GET /api/imgs` (poll 1 s): model missing -> Download (~150 MB); ready -> Scan pictures / Scan new pictures / Rescan all (`clear` then `start force`); running -> bar + Stop; first scan starts after the download. Results open in the viewer (`GCX={dev:'local',path:'/',list}`, items carry full path in `p`); hold-select: Copy, Share, Details. Settings row "Image search model" (uninstall keeps the index). The scan holds a wake lock (`Core.holdAwake`).

**First device test (all OPEN; stop at the first failure)**: (1) CI compiles (likely nits: ORT Java API `OrtSession.Result`, `Tasks.await`, private `Impl`). (2) Download works (404 = fix `FILES`, see 5.11). (3) Self-test passes. (4) Same picture in browser vs phone: cosine > 0.98. (5) Scan ~5k pictures: `msPerPic`, heap. (6) OCR on a Turkish text picture (ç ğ ı ö ş ü); plan B tess-two `tur+eng`. (7) UI end to end incl. viewer swipe and Settings delete model. (8) Then mark this chapter done in section 2.
**Weak points**: per-entry `FloatArray` ~2 KB (50k pictures = ~100 MB heap; switch to one flat array if needed); visual search scans all vectors per query (fine to ~100k); this phone's pictures only; threshold 0.20 / top 60 copied from the web page.

### 11.2 OCR - select text in pictures (image viewer)
**What it does**: viewer **T** button shows a faint box around every word; tap = word, double tap = line, triple tap = paragraph, long press also works; two blue handles with a magnifier (`android.widget.Magnifier`, Android 9+, try / catch); floating toolbar Copy / Search / Share; bottom bar Copy (all) / Select all / Share / Links / Save / X. Pinch, pan, double-tap zoom and page swipe keep working (the overlay claims only touches on a handle or word). Offline after first use. Built on ML Kit (no stable Google Lens API); engine separate from the selection UI; viewer is native Kotlin (ViewPager2 + `ZoomImageView`), so the overlay is a native `View`.
**Contract (do not break without updating all parts)**
```kotlin
data class OcrWord(val text: String, val box: RectF, val quad: FloatArray? = null)   // box in bitmap px; quad = 8 floats clockwise from top-left, only when tilted > ~2 deg
data class OcrLine(val text: String, val box: RectF, val words: List<OcrWord>)
interface OcrEngine { fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit); fun close() }   // callbacks on the main thread
class OcrOverlayView(c: Context) : View(c) { setImageMatrix(m); setLines(l); selectWordAt(x,y); selectAll(); clearSelection();
    selectedText(); allText(); hasLines(); hasSelection(); var onSelection; var onAction;  /* + hitsWord, tapSelect, touchHint */ }
// ZoomImageView: var onTap, onHold, onMatrixChanged, onTextHit, onTextTap, onTouchAt; fun matrixNow()
```
**Files**
| File | Role |
|---|---|
| `OcrEngine.kt` | contract types + `MlKitOcr` (Play Services module check, download / poll up to 3 min, Block / Line / Element boxes, `quadOf`). Latin model comes from Play Services (`play-services-mlkit-text-recognition:19.0.1`, unbundled; comments saying "bundled" are wrong). Keep ONE copy (it went missing from a zip once) |
| `OcrTiled.kt` | `TiledOcr(base)`: long side >= 2400 px = whole-picture pass + overlapping 1792 px tiles at 1:1 (a tile keeps words centred in its core; tile wins on >= 40 % overlap; `joinRows`); long side < 900 px enlarged 2x; any merge error -> whole-picture result. Constants `BIG, SMALL, TILE, OVER, EDGE, BIGTEXT` |
| `OcrHiRes.kt` | `HiRes`: `BitmapRegionDecoder` on `cacheDir/img/<pos>.bin`, tiles cut from the original when it is >= 1.25x the viewer bitmap (virtual size capped 6144 px), boxes scaled back |
| `PaddleOcr.kt` (**not in the APK**) | `PaddleModels` downloads PP-OCR det + latin rec + dict from `huggingface.co/monkt/paddleocr-onnx` into `filesDir/models/ocr` (Wi-Fi only, on first OCR call); `AutoOcr` = PaddleOCR when the files exist, else ML Kit (2 failures -> files deleted). Viewers and `ClipEngine` create `AutoOcr`. Boxes are axis-aligned; check rec input height, Turkish letters, speed |
| `OcrOverlayView.kt` | transparent view above `ZoomImageView`: word list in reading order, selection = word range, outlines near the finger (all boxes flash ~1.5 s when the layer opens), blue bar per line, handles + magnifier, toolbar; tilted words use polygons |
| `OcrSmart.kt` | Links (link / e-mail / phone pickers), Save menu (text `.txt`, word list `.txt`, copy word list; to `Downloads/picture-text-<timestamp>.txt`), `fold` (case / accents / Turkish i). The unused-code scan lists this file as dead (81 lines): verify before deleting |
| `OcrPrefs.kt` | pref "viewer"/"ocr_auto": 0 always, 1 only while charging, 2 off; `LSAndroid.ocrGet()/ocrSet(m)`; Settings > Text in pictures (`openOcrSettings()`) |

**Viewer (`ImageViewerActivity`)**: automatic reading 0.7 s after a page is shown (`autoOcr()`: current, next, previous; not during the slideshow; follows `OcrPrefs`). **T button states** (`updateOcrBar`): bright = text found / layer on, 0.35 = read, no text, half = reading, 0.8 = not read yet; T shows the layer at once if read, else when done. Long press on the bare picture = T (`holdOcr`). **Disk cache** `cacheDir/ocr/<uuid>.json` (lines, word boxes, `"q"`; valid only for the same bitmap size; `OCR_VER` = **3**; 30 days + 8 MB cap, `pruneOcr`). A failed page is skipped by auto mode, 2 failures in a row stop it; pressing T retries. **Long press on T** = menu: *Find text in these pictures* (`findText` over saved cache JSONs, only pictures already read) / background reading mode. Edit -> replace original drops that page's layer. The same OCR serves the PDF viewer (7.4) and the search index (11.1).
**History**: s9 = build green + device test OK for the basic version; later (tilted text `quad`, full-resolution tiles, cache v3, T long-press menu, Find text, Save menu, Settings page, PaddleOCR + `AutoOcr`) not device-confirmed. Translate was removed 2026-10-10.
**Device checklist**: printed page: T -> boxes; tap / double / triple tap; drag handles (magnifier); Copy -> paste; pinch-zoom keeps handles; next page restores its layer; Turkish `ç ğ ı İ ö ş ü`; first use with no model -> download, then airplane mode works; tilted text (15-30 deg); 12 MP page with small print, no doubled words at tile borders; EXIF-rotated photo keeps boxes; edit -> replace original drops the layer; rotation / split screen keeps alignment.
**Known gaps**: no long-press-and-drag extend (use handles); toolbar / paragraph logic use bounding boxes for strongly rotated text; idea: Settings switch for background reading beyond the three modes.

---
