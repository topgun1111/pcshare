# Image recognition search in LANShare (Android) - handover

Goal (user): "good image recognition search like in the web page, in my app". Reference = the PHP portal page `arama.php` + `clip_worker.js`
(CLIP ViT-B/32 image vectors + Tesseract OCR; query = CLIP text vector, cosine >= 0.20, top 60; "Metin Arama" = OCR word / substring search with Turkish folding).
The reference files are NOT in this repo: the user attached them in chat (the worker caches vectors by `ns::file` + mtime; model `Xenova/clip-vit-base-patch32`, quantized, 512-d, thumbnails as CLIP input, OCR input = full image downscaled to 1800 px, languages `tur+eng`).

## Split
- **Half 1 (DONE, not compiled / not device-tested):** model-independent backend (`core/ImgSearch.kt`, `/api/imgs`).
- **Half 2a (DONE 2026-10-09, not compiled / not device-tested):** neural engine + model download + OCR (`core/ClipEngine.kt`) - see "Half 2a - what exists".
- **Half 2b-1 (DONE 2026-10-09, not compiled / not device-tested):** UI in `assets/ui.html` - see "Half 2b-1 - what exists".
- **Half 2b-2 (code DONE 2026-10-09, still not compiled / not device-tested):** housekeeping, robustness fixes, built-in self-test. **What is left is only the first real build + device test** - start at "First device test - checklist".

## Half 1 - what exists
`app/src/main/java/com/lanshare/app/core/ImgSearch.kt` (new, ~270 lines) - object `ImgSearch`:
- `interface Engine { id; canOcr; embedImage(jpeg: ByteArray): FloatArray; embedText(q): FloatArray; ocr(file: File): String }` and `@Volatile var engine: Engine?` - **half 2 must set this** (e.g. in `Core.start`, after model files are present). Until then `start` answers 400 "image search engine is not installed yet", text search still works on stored text.
- File discovery: walks the storage root (`Core.local.root`), jpg/jpeg/png/webp/bmp/heic/heif, skips `Android/` and hidden dirs. Paths are virtual (`/DCIM/Camera/x.jpg`), same as `/api/thumb?dev=local&path=` and `/api/dl`.
- Incremental index keyed by path + mtime + size; vanished files are dropped; unreadable pictures are marked `failed` (retried only on change or `force`). Engine id change = whole index discarded.
- Persistence: `<filesDir>/imgsearch/index.json` (manifest) + `vec.bin` (float32 LE, 512 per entry, rows aligned with the manifest; written to `.tmp` then renamed, manifest last). Checkpoint every 100 pictures, so a killed scan resumes.
- Background thread `imgsearch` (daemon, min priority). CLIP input = `Thumbs.make(file)` (600 px JPEG, already disk-cached, EXIF-rotated) like the web page feeds its thumbnail. OCR failures never block visual search.
- `visual(q)`: dot product (vectors are L2-normalised) >= 0.20, top 60. `text(q, whole)`: `lowercase(tr)` + `ı -> i` folding, whole word = `(?<![\p{L}\p{N}])q(?![\p{L}\p{N}])`, substring otherwise, blanks match any blanks.

`core/Routes.kt`:
- `GET  /api/imgs` -> `{state: idle|running|error, engine, ocr, total, done, indexed, withText, failed, count, msg, error}` (poll while `running`).
- `POST /api/imgs {op:"start", ocr:true, force:false}` | `{op:"cancel"}` | `{op:"clear"}` -> status.
- `POST /api/imgs {op:"visual", q}` and `{op:"text", q, mode:"word"|"part"}` -> `{q, total, results:[{path, score}]}` (text: score 1).
- Not added to `InProc.ROUTES` on purpose (heavy; goes over HTTP).

## Half 2a - what exists
`core/ClipEngine.kt` (new, ~300 lines), `ImgSearch.kt` / `Routes.kt` / `Core.kt` / `app/build.gradle.kts` touched slightly.
- **Dependencies added:** `onnxruntime-android:1.18.0`, `com.google.mlkit:text-recognition:16.0.0` (bundled Latin model). Expect the APK to grow by tens of MB (ORT native libs per ABI + ML Kit model).
- **`object ClipEngine`:** `installed(ctx)`, `state()` -> `{installed, installing, msg, error, bytes, totalBytes}`, `tryLoad(ctx)` (called in `Core.start` after `local = LocalFs(...)`: sets `ImgSearch.engine` at once when the files exist), `install()` (background thread `clip-download`, downloads once into `<filesDir>/models/clip/`, progress via `state()`, then `tryLoad`), `uninstall()` (closes sessions, deletes files, `engine = null`).
  Files from `https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/`: `onnx/vision_model_quantized.onnx` -> `vision.onnx`, `onnx/text_model_quantized.onnx` -> `text.onnx`, `vocab.json`, `merges.txt` (~150 MB total, **size and file names not verified: no network in the sandbox**). Manual redirects, `.part` file then rename, no resume (a failed download restarts that file; finished files are kept).
- **`Impl : ImgSearch.Engine`** (id `clip-vit-b32-q8-v1`, bump it if preprocessing changes): ORT sessions are created lazily (vision on first picture, text on first query; 2 intra-op threads). Input names are read from the session (`pixel_values`, `input_ids`, `attention_mask` if present); output taken by name `image_embeds` / `text_embeds`, else the first `[1][512]` output, then L2-normalised. Image preprocessing as in the plan (shortest side 224 with repeated halving + bilinear, centre crop, mean/std, NCHW). Text: **no padding** (like the web page's single query) unless the model has a fixed length, then padded with `<eot>`.
- **`class ClipTokenizer`:** CLIP byte-level BPE (GPT-2 byte->unicode, `</w>`, merges by line rank, regex split), `[49406 ... 49407]`, max 77. Written from memory of the OpenAI `SimpleTokenizer`; **not run against real vocab files**.
- **OCR:** ML Kit `TextRecognition` (Latin, bundled), picture decoded at <= 1800 px longest side with EXIF rotation (no JPEG round trip), `Tasks.await` on the scan thread. `canOcr = true`. Turkish letters (ç ğ ı ö ş ü) still to be checked on a device; if poor, switch to tess-two `tur+eng` (plan B in the old notes).
- **API additions:** `GET /api/imgs` now also returns `model{...}`; `POST /api/imgs {op:"install"}` starts the model download, `{op:"uninstall"}` removes it. `start` still answers 400 "image search engine is not installed yet" until the engine exists.

## Half 2b-1 - what exists (`assets/ui.html`)
- `isView()` - image search screen. It **reuses the `#gal` overlay** (so back button, list hiding and `galClose` work unchanged). Entry: drawer, Folders tab, under "Recycle bin" ("Image search"). Header + status line, one card, Visual / "Text in pictures" tabs, input + Search, chips "Whole word" / "Contains" (text mode only), hint "Works best with English words", square-thumbnail result grid (`/api/thumb?dev=local&path=`, lazy), score % badge (`.gp`) on visual results.
- Card states (from `GET /api/imgs`, polled every 1 s while `state=running` or `model.installing`): model missing -> "One-time download needed" (~150 MB) + Download (`op:install`); downloading -> bar + MB; ready -> "Scan pictures" / "Scan new pictures" (`op:start {ocr:true}`), "Rescan all" (`op:clear` then `start force`), running -> bar + Stop (`op:cancel`). When the model download finishes and nothing is indexed, the first scan starts by itself.
- Results feed the viewer: `GCX={dev:'local',path:'/',list}`; result items carry a full path in `p`, `nativeImg` now builds its URL from `m.p||jn(cxp(),m.name)` and finds the start picture by `p`. Swiping works in the native viewer (`LSAndroid.viewImages`); the web fallback `openMedia` was NOT adapted (it uses `jn(cxp(),name)`) - only matters outside the Android app.
- Hold-to-select in the result grid: bottom bar with Copy, Share, Details (no Move / Delete / Print on purpose).
- Settings sheet: new row "Image search model" (tap -> confirm -> `op:uninstall`; index is kept).
- CSS: `.isb .isc .ist .isq .isp .isr .gp` under `#gal`.
- JS syntax checked with node only; never run.

## Half 2b-2 - what was done (2026-10-09, static review only: no SDK / no network here)
1. **Awake during the scan - verified by reading, nothing to add:** `Core.start` -> `holdAwake` takes a PARTIAL_WAKE_LOCK (+ Wi-Fi, multicast) once and never releases it; `LanShareService` is a foreground service (`START_STICKY`), so the process and the `imgsearch` thread keep running with the screen off. UI card now says "keep the phone charging" before the first scan.
2. **Memory after a scan:** new `Engine.release()` (default no-op). `ImgSearch` calls it in the scan thread's `finally`; `ClipEngine.Impl.release()` closes the vision session + ML Kit recogniser (text tower stays, queries keep working; sessions are re-created lazily on the next scan).
3. **A broken model no longer silently "fails" every picture:** `scan` now treats `IOException` / `OutOfMemoryError` as "this picture is bad" (marked `failed`) but any other exception (ONNX `OrtException` etc.) is logged (`ImgSearch` tag) and, after **5 in a row**, aborts the scan with a readable error ("Remove the model in Settings and download it again"). Before, one `OrtException` killed the scan with a raw message.
4. **Download fix:** `HttpURLConnection` transparently gunzips, so `Content-Length` (compressed) no longer matched the bytes read and `vocab.json` / `merges.txt` could fail as "incomplete download". The request now sends `Accept-Encoding: identity`.
5. **Uninstall guard:** `ClipEngine.uninstall` refuses while a scan runs (it would have closed sessions under the scan thread).
6. **Tokenizer:** `bpe` now merges ALL occurrences of the best pair per round (identical to OpenAI's `SimpleTokenizer`); before it merged one occurrence per round (same result almost always, not provably).
7. **Diagnostics for the device test:** `POST /api/imgs {op:"selftest"}` -> `ClipEngine.selfTest()` returns `catIds` / `catIdsOk` (must equal `[49406,320,1125,539,320,2368,49407]`), `selfDot` (~1.0), `catVsDog`, `catVsKitten` (kitten must be clearly higher), `textMs`, `tokenizerLoadMs`. `GET /api/imgs` also returns `msPerPic` (average of the last scan) and the scan logs `done/total, ms per picture` to logcat every 100 pictures.
8. JS of `ui.html` re-checked with `node --check` (still never run in a browser/WebView).

## First device test - checklist (everything below is still OPEN)
Run in this order; stop at the first failure and fix it before going on.
1. **CI compiles.** Most likely nits: ORT Java API (`OrtSession.Result.get(name)` returns `Optional<OnnxValue>`, iterating `Result`, `inputInfo[...]?.info as? TensorInfo`), `Tasks.await` (comes with ML Kit via play-services-tasks - add it explicitly if unresolved), `Impl` being a `private class` used inside the object, `use {}` on `OrtSession.Result`.
2. Settings -> Image search -> **Download** (~150 MB; the four file names under `onnx/` + `vocab.json` + `merges.txt` are from memory of the Xenova repo, unverified: an HTTP 404 in the card = fix `FILES`).
3. **Self-test:** `curl -X POST http://<phone>:<port>/api/imgs -d '{"op":"selftest"}'` (or `fetch` in the WebView). `catIdsOk` must be `true`; if not, the tokenizer is wrong (compare with the web page's `tokenizer`). `selfDot` ~1.0 and `catVsKitten` > `catVsDog` prove the text tower runs.
4. **Same picture, browser vs. phone:** cosine of the web page's vector and the phone's must be > 0.98 (otherwise preprocessing differs: resize filter, crop, or the thumbnail input). Bump `ENGINE_ID` after any preprocessing change.
5. **Scan speed / memory:** `msPerPic` + logcat; scan ~5k pictures, watch heap (see the flat-array note below).
6. **OCR on a Turkish text picture** (ç ğ ı ö ş ü). If ML Kit Latin is poor: tess-two `tur+eng` (plan B) - `canOcr` stays, only `Impl.ocr` changes.
7. **UI end to end:** model card -> download -> first scan starts by itself -> Visual search -> Text search (whole word / contains) -> tap result -> swipe in viewer -> hold-select -> Copy / Share / Details -> Settings "Image search model" delete.
8. When 1-7 pass: set the 2b-2 line above to DONE and drop the "not compiled" wording from the top card in `HANDOVER.md`.

## Known weak points / decisions to revisit
- Per-entry `FloatArray` in memory: ~2 KB/picture -> 50k pictures = ~100 MB heap. If too heavy, switch to one flat `FloatArray`/memory-mapped `vec.bin` (the file layout already allows it).
- Visual search scans all vectors per query (fine up to ~100k).
- Only this phone's pictures (no peers / SMB) - same as the web page which only searched one folder.
- Threshold 0.20 and top 60 copied from the web page; tune after seeing real scores from ONNX.
- **Nothing in half 1 or half 2a was compiled** (no Android SDK here). Likely nits: `Locale("tr","TR")` deprecation (fine), `synchronized` + `return` in `ensureLoaded` (block body used on purpose), `jarr(...)` takes `Collection<JSONObject>`.
