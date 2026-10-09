> **2026-10-09 image recognition search, half 2b-2 of 3 (finish, code complete):** robustness + housekeeping after a static review: `Engine.release()` frees the vision/OCR sessions after every scan, a broken model now aborts a scan after 5 consecutive non-image errors with a readable message (before: raw `OrtException` / everything marked failed), model download sends `Accept-Encoding: identity` (gzip made `Content-Length` mismatch), `uninstall` refused during a scan, tokenizer BPE merges all pairs like the reference, new `POST /api/imgs {op:"selftest"}` (tokenizer ids for "a photo of a cat", text-vector sanity, timings) and `msPerPic` in the status, "keep the phone charging" note in the card. Wake lock was already held for the whole process (`Core.holdAwake`) - nothing to add. **Still not compiled / not device-tested:** the only thing left is the first build + the checklist in **`HANDOVER_IMAGE_SEARCH.md`** "First device test - checklist".

> **2026-10-09 image recognition search, half 2b-1 of 3 (UI):** `ui.html` got `isView()` (drawer entry "Image search", reuses the `#gal` overlay): model download card, scan progress / stop / rescan, Visual and Text tabs, thumbnail results with score %, viewer swipe over results (`nativeImg` now uses `m.p` full paths), hold-select Copy / Share / Details, Settings row to delete the model. Still **not compiled / not device-tested**; remaining (housekeeping, first real test, finish) in **`HANDOVER_IMAGE_SEARCH.md`** "Half 2b-2".

> **2026-10-09 image recognition search, half 2a of 3 (engine):** new `core/ClipEngine.kt`: `ClipEngine` (one-time download of the Xenova CLIP ONNX files into `filesDir/models/clip/`, `install/uninstall/tryLoad/state`), `Impl : ImgSearch.Engine` (ONNX Runtime CLIP image + text towers, ML Kit OCR) and `ClipTokenizer` (CLIP BPE). `build.gradle.kts` got `onnxruntime-android` + ML Kit text-recognition; `Core.start` calls `ClipEngine.tryLoad`; `/api/imgs` gained `op:install|uninstall` and `model{}` in the status. **Not compiled / not tested.** Still TODO: the UI in `ui.html`, housekeeping, first device test. See **`HANDOVER_IMAGE_SEARCH.md`** ("Half 2b").

> **2026-10-09 image recognition search, half 1 of 2:** new `core/ImgSearch.kt` (incremental on-device index of this phone's pictures, `vec.bin` + `index.json`, visual cosine search + OCR text search, progress/cancel) and `/api/imgs` routes in `Routes.kt`; the neural part plugs in through `ImgSearch.Engine` (CLIP via ONNX Runtime, OCR, UI = half 2). **Not compiled / not tested.** Plan and exact TODO list: **`HANDOVER_IMAGE_SEARCH.md`**.

> **2026-10-09 progress card:** every background job (copy / move / send / extract / zip / delete / print) now has one detail card (`lvMake/lvFeed/lvEnd` in ui.html, CSS `.pj`): verb + %, `from > to`, current file with its own bar, Transferred / Speed / Time left / Elapsed / Files n of m / Problems, live problem list, result summary (size, time, average speed). Opens by itself after 2 s (never over another sheet), "Minimize" leaves the bottom bar, tapping the bar reopens the card. The merge-copy log + "replace this file?" question live in the same card. `Job` (Jobs.kt) got `cur/curDone/curTotal/files/filesTotal/failed/dest/problems` + `begin()`, sent by `toJson`; hooked into `work`/`copyFile`, `deleteWork`, `zipWork`, `printWork`. Wi-Fi print and the duplicate finder keep their own dialogs. UI checked in Chromium with mocked jobs; **Kotlin not compiled / not tested on a device.**

> **2026-10-09 recycle bin redesign:** `openBin()` (ui.html) now has a fixed top bar with **Empty bin** at the top, tap-to-select rows (Select all / Restore / Delete / Clear), rows drawn 80 at a time while scrolling, and a progress bar with Cancel. Restore / delete / empty run as ONE background task (`Bin.start` -> `Bin.Task`, `POST /api/bin {op:restore|purge|empty|cancel, ids}` returns at once; `GET /api/bin?items=0` = progress only). `Bin.purge` first renames the entry to `.del-<id>` (atomic, vanishes from the list instantly) and deletes it afterwards; leftovers are swept at start (`sweepDeleted`). `Bin.list/restore/purge/empty` no longer hold the global lock, so a long delete cannot freeze the list. UI checked in Chromium with a mocked API (400 items); **Kotlin not compiled / not tested on a device.**

> **2026-10-08 image edit:** image viewer top bar has a new pencil button -> `ImageEditActivity.kt` (rotate left/right, drag-to-crop, resize by px or 100/75/50/25 %). Preview is a 2048 px bitmap; on Save the operations are applied once to the original file at full resolution (`ImgEdit.render`, EXIF orientation -> rotation -> crop -> scale, one Canvas pass). Save as copy (`name (edited).ext` next to the original; for peers / SMB / archive entries into `Pictures/LANShare Edited`) or replace the original (this phone, jpg/png/webp only); the viewer reloads the page after a replace. **Not compiled / not tested on a device.**

> **2026-10-08 gallery move:** hold bar "Cut" replaced by "Move": `galPick(a)` sheet lists the other albums (same kind) + "New album" (mkdir under /Pictures or /Movies), then clip(cut) + /api/paste into it and the album reloads.

> **2026-10-08:** Print hidden in the video gallery (Movies albums, `a.v`) hold bar.

> **2026-10-08 narrow list rows:** `NlRowView` second line (count left, date right) no longer overlaps in split-screen: date loses its time, then is ellipsized, then hidden; left text ellipsized; compact view caps the right text at 45%. Native only.

> **2026-10-08 cleanup:** removed orphan `BrowserActivity.kt`, unused JS `doSend` / `printInfo`, their dead CSS (`.dprh .dprg .pdw .pdot .poff`), `MainActivity.toastUi`, `Jobs` `bid`, color `brand_dark`, 16 unused imports. Not compiled.

> **2026-10-08 gallery hold theme:** selected thumbnails shrink into a rounded tile with an accent check (top-left), rounded selection header, floating rounded action bar with tonal icon buttons (Delete tinted red), slide animations. CSS only (`#gal .gb`, `.gh.sl`, `.gc.sel`).

> **2026-10-08 gallery share:** gallery hold bar: "Open with" replaced by "Share" (any number of files). New `LSAndroid.shareFiles({items:[{name,url,size}]})` -> `MainActivity.shareFilesImpl` fetches all into `cache/open` (one progress notification, cancellable) then `shareFileList` opens the system chooser (ACTION_SEND / SEND_MULTIPLE via FileProvider). Not compiled / not tested on a device.

> **2026-10-08 gallery hold bar:** Download removed, Print added (`doPrint(preset,cx)` now takes an explicit `{items,path,done}` context; without it it still uses the file browser selection).

> **2026-10-08 gallery hold menu:** inside an album (`galView`, `#gal`) press-and-hold (`holdMenu`) on a thumbnail now selects it like in the file browser: header turns into a count bar (close / select all), tap toggles while selecting, bottom bar `#gal .gb` shows Copy, Cut, Download, Details + Open with (1 item), Delete (-> Recycle bin). Selection `GS` is local to the album; back clears it first (`o._sel` in `lsBack`). `doDetails(it,pth)` takes an explicit item. Not tested on a device.

> **2026-10-08 gallery: hide small albums:** albums with <= 1 item and Android resource folders (mipmap-*, drawable-* ...) are hidden in the gallery by default (`galJunk`, key `ls_galhide`); a button under the albums shows / hides them again.

> **2026-10-08 gallery album view:** in the Pictures gallery an album card no longer opens the folder in the file browser: `galView(a)` (ui.html, `#gal` overlay) shows that album's pictures + videos as one square-thumbnail grid (newest first, chunked, same thumb cache as the lists), back closes it (`lsBack`). Tapping a thumbnail opens the viewer / player with the album as its list: `GCX` (+ `cxd/cxp/cxl`) makes `nativeImg`, `nativePlay`, `mediaList`, `openMedia` read the album instead of `S.dev/S.path/shown()`. `#gal` also hides the native chrome like `#pv`. Not tested on a device.

> **2026-10-07 Wi-Fi print: PWG raster:** printers without PDF but with `image/pwg-raster` (e.g. Brother DCP-T830DW) now get ALL pages as ONE raster job (`WifiPrint.pwgRaster` / `writeRasterPixels`), so two-sided printing works; before, the per-page JPEG fallback made every page its own job (= own sheet of paper). Landscape pages (2-up) are turned 90° clockwise; back sides get the printer's `pwg-raster-document-sheet-back` transform. `Ipp.printerAttrs` also asks for `pwg-raster-document-resolution-supported` / `-sheet-back` (`WifiPrinters.P.rasterDpi`, `.sheetBack`). **Not compiled / not tested on the printer** - check with 4 numbered pages: if backs come out upside down, the sheet-back mapping in `pwgRaster` is the place to flip.

> **2026-10-06 native side drawer (phase 11):** new `NlDrawer.kt` paints the drawer (tabs, rows, footer) natively on top of the unchanged HTML drawer: `ui.html` mirrors the DOM into flat draw lists (`nlDrFlat`/`nlDrBuild`), Kotlin paints them in a RecyclerView, taps return as `nlDr(...)` and click the same HTML element. Switches: `NlDrawer.ENABLED` / `localStorage ls_nld='0'`. NOT compiled / NOT device-tested. Details: **`HANDOVER_NATIVE_LIST.md` section 2p**.

> **2026-10-06 native app chrome (phase 10):** new `NlChrome.kt` draws the top bar / selection bar, New-folder button, bottom dock + clipboard strip, snackbar and download cards natively on top of their unchanged HTML twins (`nlChrome` bridge, `nlChPush()`/`nlCh()` in ui.html; taps click the HTML element). Switch: `NlChrome.ENABLED` / `ls_nlc='0'`. Still **not compiled / not device-tested**; see `HANDOVER_NATIVE_LIST.md` §2o.

> **2026-10-05 native path bar + tool row (phase 7a):** new `NlHead.kt` draws `#pathrow` / `#toolrow` natively on top of the unchanged HTML rows (`nlHead` bridge, layout JSON `hd`, `nlOn crumb/newb/sort`, instant update on folder entry). Switch: `NativeList.HEAD` / `ls_nlh='0'`. Still **not compiled / not device-tested**; see `HANDOVER_NATIVE_LIST.md` §2l.

> **2026-10-05 native list phase 6a:** folder entry no longer waits for the page: new `NlModel.kt` builds the next folder's rows in Kotlin (list, sort, dups, texts), `NativeList.tapRow` shows them at once, the page revalidates; JS `nlStash` removed. Still **not compiled / not device-tested**; see `HANDOVER_NATIVE_LIST.md` §2k.

> **2026-10-05 native list phase 3b:** video gallery (`.gal`) now drawn natively too (`NlGalView`, rows flagged `v:1`, `nlUse` no longer excludes `GV`). Still **not compiled / not device-tested**; see `HANDOVER_NATIVE_LIST.md` §2e.

> **2026-10-05 native list phase 3a:** grid view now drawn natively too (`NlGridView`, `GridLayoutManager` 2/4 columns, `nlUse` accepts `S.view==='grid'`, palette `bg`). Still **not compiled / not device-tested**; see `HANDOVER_NATIVE_LIST.md` §2d.

> **2026-10-05 native list phase 2b:** large-thumbnail list (`S.thumb==='l'`, row 84) now drawn natively too (`NlRowView.large`, layout JSON `lg`, `nlUse` no longer excludes big thumbs). Still **not compiled / not device-tested**; see `HANDOVER_NATIVE_LIST.md` §2c.

> **2026-10-05 native list phase 2a:** compact view now drawn natively too (`NlRowView.compact`, `nlUse` accepts `S.view==='compact'`, layout JSON `cp`). Still **not compiled / not device-tested**; see `HANDOVER_NATIVE_LIST.md` §2b.

> **2026-10-05 native file list (Option A), phase 1:** new `NativeList.kt` + `Bridge.nl*` + `ui.html` hooks draw the folder rows with a RecyclerView laid over the WebView (look copied from the CSS, page stays HTML). Details, switches and next steps: **`HANDOVER_NATIVE_LIST.md`**. **Not compiled / not device-tested.**

> **2026-10-04 print: real previews for all formats:** the print dialog's layout preview now shows the real pages. New `core/PrintPreview.kt` + routes `GET /api/pvinfo?dev&path&to`, `/api/pvpage?id&n&w` (JPEG of one page, `PdfRenderer`), `/api/pvfile?id` (converted picture / text). PDF: rendered natively on the phone. html/svg/unknown/webp-heic-avif: converted by `PrintPrep` first. **Office files: the PC converts them** - `pcprint.py` v11 has a new `POST /convert?name=` (office file in, PDF out, nothing printed, one conversion at a time; `/ping` reports `convert: true`), so office previews need the new pcprint.py + Word/Excel/PowerPoint or LibreOffice; without them the old sample layout is shown with the reason in the caption. Text/code files are drawn in the UI with the same wrapping as `txt_to_pdf` (checked: identical page/line counts), so margin and custom scale (= font size) change the preview exactly like the print. Cache: `cache/pv/<id>/` (6 documents, wiped at start). **Not compiled / not device-tested.**

> **2026-10-04 print: all formats:** every file type is now printable with the full layout options; `pcprint.py` is unchanged. New `core/PrintPrep.kt` (called from `Jobs.printWork`) converts on the phone: webp/heic/heif/avif/ico/wbmp/dng/jfif -> JPEG (`Thumbs.forPrint`), html/htm/xhtml/svg -> PDF (`android/print/WebPdf.kt`: WebView print adapter, no JS, no network; deliberately in package `android.print` because the callback constructors are package-private), ~100 code/config/text extensions -> `.txt`, and **unknown / missing extensions are sniffed** (PDF, RTF, OOXML/OpenDocument zip entries, legacy OLE doc/xls/ppt by stream name, decodable picture, HTML/SVG, UTF-16/UTF-8 text) and sent under the right name. Video/audio/archives/binaries (`NO` set) and unknown files > 120 MB are refused up front with a clear message. `ui.html`: `PV_IMG`/`PV_TXT` extended, `PV_WEB` added, unknown types show the sample layout. Office files still need Word/Excel/PowerPoint or LibreOffice on the PC for layout options (no native docx renderer on the phone). **Not compiled / not device-tested:** first thing to check is that `WebPdf.kt` compiles (package-private callback constructors) and that an .html and an .svg print.

> **2026-10-04 update:** the native file-browser screen (`BrowserActivity`, "Native" drawer button, `LSAndroid.nativeBrowser`, `swiperefreshlayout` dep, FileProvider `root-path`) was REMOVED. The WebView UI is the only browser again; the Batch 8/9 sections below that describe `BrowserActivity` are historical. `InProc` now also answers `/api/peers`, `/api/smb` (GET) and `/api/arcjob` in-process.

> **2026-10-04 office files:** pcprint.py v10 converts doc/docx/rtf/odt/xls/xlsx/csv/ods/ppt/pptx/odp to PDF on the PC (Microsoft Office via COM, else LibreOffice headless) when layout/fitting options (or duplex/colour with SumatraPDF) are asked for; then the normal PDF pipeline applies. No converter or a failure = old behaviour (printed by its own app, printer + copies only) plus a note. `/ping` now reports `office` (engine name); `Jobs.printerStatus` forwards it and ui.html shows a sample layout preview for office files only when it is set. Needs the new pcprint.py run once on the PC.

> **2026-10-04 print types:** printing to the PC now also handles webp/heic/heif pictures (converted to JPEG on the phone: `Thumbs.forPrint`, `Jobs.printWork`) and code/config text files (sent as `.txt`), with full layout options + preview. pcprint.py is unchanged. Office files (doc/docx/xls/xlsx/ppt/pptx/rtf/odt/ods/odp/csv) still print through the PC's own app: printer and copies only. The preview now uses the first previewable file of the selection.

# LANShare → Android: Handover

**Current state: pure Kotlin.** Chaquopy/Python removed; `core/*.kt` serves `assets/ui.html` from `MiniHttp`. **Not yet compiled or device-tested** (no Android SDK was available): first Gradle build will likely show small compile errors in `core/*.kt` (likeliest: `Cfg` accessors, `Discovery.start()` function refs, smart-casts in `RemoteFs.callRaw`). Then run the test checklist below, incl. SMB and thumbnails.
Wire protocol is unchanged — old Python builds and Kotlin builds interoperate.

## Behaviour differences / gotchas to keep in mind
- `org.json` **drops a key** on `put(k, null)` → real nulls must be `JSONObject.NULL` (done for `used`, `storage_ok`, `error`, clipboard).
- Query parsing mimics Python `parse_qs`: `+`/`%XX` decoded, **blank values dropped**, first value wins.
- `MiniHttp` does not support chunked request bodies, `Expect: 100-continue` or `HEAD` (the Python server had none of these either).
- Large uploads: an error mid-upload closes the connection instead of draining the body (same as Python's `close_connection`).
- Android < 15: never call `removeFirst()/removeLast()` on a `java.util.List`; the code only uses them on `kotlin.collections.ArrayDeque` (safe).
- `storageOk` is evaluated live on every call (Python evaluated once at start).
- `File.renameTo` is used for rename/move, with copy+delete fallback across volumes (`LocalFs.move`).
- `tcpProbe` treats "connection refused" as "host up, try next port" and anything else as "nobody home" — matches Python's `errno` logic.

---
## Historical notes (Chaquopy/Python era — superseded)

**Approach:** Chaquopy (embedded Python 3.11) runs the unchanged `lanshare.py` server inside a foreground
service; a thin Kotlin WebView loads its UI. Python stdlib only → no pip deps.

## Done (~20%)
- Gradle project (AGP 8.5.2, Kotlin 1.9.24, Chaquopy 16.0.0, minSdk 24, target 34), ABIs arm64/armv7/x86_64
- `app/src/main/python/lanshare.py` — original script, **unmodified**
- `python/jnius.py` — shim mapping `autoclass` → Chaquopy `jclass` (so wake/wifi/multicast locks, `phone_model`, `storage_ok` work)
- `python/android_main.py` — sets `LANSHARE_CFG`/HOME to app files dir, patches `webbrowser.open` to capture the UI URL, calls `main()`
- `LanShareService.kt` — foreground service (dataSync), starts Python thread, persistent notification
- `MainActivity.kt` — requests notification + All-files-access, starts service, polls `get_url()`, loads WebView, back = webview back
- Manifest: INTERNET, WIFI/multicast, WAKE_LOCK, FGS, MANAGE_EXTERNAL_STORAGE, cleartext allowed

## Done in batch 2 (~40% total)
- WebView file picker (`onShowFileChooser`, multi-select via SAF) → "send file" works
- Downloads: `DownloadListener` + `/api/dl` navigation interception → saved to /Download (server filename, auto-suffix on clash), toast on finish
- Battery-optimisation exemption prompt; notification **Stop** action (kills process/server)
- `STORAGE_MSG` reworded for this app (only change to lanshare.py)

## Done in batch 3 (~60% total)
- Adaptive launcher icon (vector share-network glyph on brand blue) + legacy fallback, monochrome notification icon, branded theme/status bar
- Loading screen while Python starts; 40 s timeout shows a readable error page; Python startup exceptions captured via `android_main.get_error()`
- Release signing: `app/build.gradle.kts` reads env `KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`
- Workflow: builds debug APK always; builds **signed release** if repo secret `KEYSTORE_B64` (+ `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) exists; pushing a tag `v*` attaches APKs to a GitHub Release
  - Make keystore: `keytool -genkey -v -keystore release.jks -alias lanshare -keyalg RSA -keysize 2048 -validity 10000` then `base64 -w0 release.jks` → secret

## Done in batch 4 (~80% total)
- Share-sheet target (ACTION_SEND / SEND_MULTIPLE, any type): files are copied to `<storage>/LANShare Shared/`; toast tells user to select + Send (UI unchanged; no lanshare.py edit)
- Network change handling: `ConnectivityManager` default-network callback in the service → debounced `android_main.rescan()` → `DISC.scan_now()`
- WebView `onRenderProcessGone` → `recreate()`; Activity is `singleTop` so shares arrive via `onNewIntent`

## Not yet done / next steps (items 2,3,6,7,8,9 are now DONE except Play-store note) (items 2,3,6,partial 4 above are now DONE) (priority order)
1. **Push to GitHub** – `.github/workflows/build.yml` builds a debug APK (Actions tab → run → Artifacts → `LANShare-debug-apk`). Uses Gradle 8.7 via setup-gradle, so no wrapper needed. Test on two real devices (LAN discovery).
2. **File upload from WebView**: add `WebChromeClient.onShowFileChooser` (the UI's "send file" `<input type=file>` won't open without it).
3. **Downloads**: add `setDownloadListener` → `DownloadManager` (or let the `/api/dl` response save to Downloads).
4. **Check `STORAGE_MSG`** in lanshare.py (mentions Pydroid) → reword for this app; `storage_ok()` should now return True after All-files grant.
5. Verify UDP beacon (port 48555) + TCP scan across two devices; multicast lock is acquired in `hold_awake()`.
6. Battery: prompt for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; handle service stop (stop action in notification, `srv.shutdown()` hook).
7. Port: lanshare tries 8765–8784; URL is read back via `android_main.get_url()` so any port works.
8. Handle Activity restarts: if service already running, `get_url()` still returns the URL (module-level state persists).
9. App icon, name, splash; release signing; Play Store note: `MANAGE_EXTERNAL_STORAGE` needs Play justification (fine for sideloading).
10. Security: app has no PIN/pairing by design — consider an opt-in toggle before publishing.

## Notes
- Config stored at `<filesDir>/lanshare.json`.
- `getprop` via subprocess works on Android; `socket.gethostname()` fine.
- `MainActivity` extends `android.app.Activity` (no AppCompat dependency needed).

## Remaining ideas (batch 4/5)
- Make share-sheet open the Send picker directly in the UI (needs a small JS/route hook in lanshare.py; currently manual select)
- Opt-in PIN / "trusted networks only" toggle in lanshare.py + settings screen
- Pull-to-refresh, landscape/tablet polish
- Tests on 2+ real devices (hotspot + router Wi-Fi), Android 14 FGS behaviour

## Batch 5 (final) – v1.0
- Share sheet now opens the app's **"Send to…" device picker** directly (JS hook calls the UI's `pickDevice()` / `/api/send`; files staged in `<storage>/LANShare Shared/`). No lanshare.py change.
- Version 1.0 (versionCode 2).
- Intentionally NOT done (optional later): server PIN/trusted-network toggle (design of lanshare.py is no-PIN; use only on trusted Wi-Fi), pull-to-refresh, tablet layout.
- **Test checklist:** (1) install on 2 phones, grant All-files + notifications; (2) both appear in device list within ~5 s; (3) browse, copy/cut/paste, send both ways; (4) upload via picker, download to /Download; (5) hotspot host + client; (6) toggle Wi-Fi → devices reappear; (7) share a file from Gallery → picker → delivered in `LANShare Received` on target; (8) screen off 10 min → still reachable.
- If a build fails: paste the Actions log; most likely culprits are Chaquopy/AGP version pairing or Kotlin API nits.

## Batch 6 – Tailscale backup IPs replaced by SMB shares
- Removed: Tailscale / backup-IP settings screen, `/api/backups`, backup loop and VPN-address handling in `lanshare.py`. Old `backup_ips` is dropped from the config on load. ("+ IP" for adding a LAN device by IP is unchanged.)
- Added: **Settings → SMB shares**. Enter server (IP, optional `:port`), share name, username/password (empty user = guest), optional display name. The connection is tested before saving.
- Each saved share shows up as a chip in the device bar and works like any other device: browse, search, download, upload, copy/cut/paste, rename, delete, and "Send to…" (lands in the share root).
- Backend: `Smb` class in `lanshare.py` (same interface as `Local`/`Remote`) on top of `smbclient` from the `smbprotocol` package (SMB2/3, signing/encryption). Added to Chaquopy pip in `app/build.gradle.kts`. Config: `CFG["smb"]` = list of `{id, host, share, user, password, name}` in `lanshare.json` (password stored in plain text in the app's private storage).
- API: `GET /api/smb` (list), `POST /api/smb` `{host, share, user, password, name}` to add, `{remove: id}` to delete. `/api/peers` also returns the shares (`smb: true`).
- Notes: use the PC's IP address (Android usually can't resolve Windows/NetBIOS names); SMB1-only servers are not supported; no thumbnails for SMB files.
- RC4 fix: Android's OpenSSL has RC4 disabled, which broke NTLM login ("cipher RC4 ... not supported"); `_patch_rc4()` swaps in a pure-Python RC4 in pyspnego.
- **Not tested on a real device/server yet** (tested against a fake `smbclient`): first thing to check is that the Actions build can resolve `smbprotocol` (needs the `cryptography` wheel from Chaquopy's repo).

## Batch 7 – big archives on other devices + "Zip"
- **Problem fixed:** tapping a big .zip on another LANShare phone / SMB share used to download the *whole* archive into the cache inside the `/api/ls` request, with nothing on screen. Now `ArcStore.index` reads only the ZIP's end record + central directory with ranged reads (`RangeZip`, works on any seekable `Source`: `RemoteSource`, `SmbSource`), so listing is instant. Odd ZIP layouts (`ZipFormat` error) fall back to the old download path.
- Stored (uncompressed) entries are served in place through `WindowSource` (instant start, seeking works); deflated entries are inflated into the cache with a progress job. RAR still needs the whole file (junrar) and is downloaded.
- **Progress + Cancel:** long steps register a `Job` through `ArcProg` (also in `Jobs.all`); the UI polls `GET /api/arcjob` while a path inside an archive is open and shows the usual progress toast with Cancel (`/api/jobcancel`). Cancelling a listing returns HTTP 499 `cancelled`; the UI goes back to the folder holding the archive.
- Copy/extract of ≥ 8 files out of a ZIP on another device first fetches the archive once (`Jobs.pinArchives`, shown on the job's own bar); after that all entries come from the local copy.
- **Zip feature:** selection bar → **Zip** (not shown inside archives). `POST /api/zip {dev, paths, dir, name}` → `Jobs.startZip/zipWork`: walks the selection, streams every file through `Zip64Writer` (own streaming writer: data descriptors, ZIP64 headers/end records only where needed, so small ZIPs stay classic; already-compressed types are stored as level-0 deflate), writes `<name>.zip` (made unique) into the open folder. On this device it is written straight into the folder (`.lspart` → rename); for another device / SMB share it is built in the cache and uploaded. Cancel removes the partial file. No size or entry-count limit (only free space on the target).
- **Not compiled / not device-tested** (no Android SDK here). The ZIP parsing logic was checked against Python's `zipfile` (deflate, stored, comments, ZIP64 end record, cp1254 names).

- **Video player (native):** `PlayerActivity.kt` (Media3 / ExoPlayer 1.3.1, `media3-exoplayer` + `media3-ui`). Tapping a video in the Android app calls `LSAndroid.play(json)` (`MainActivity.Bridge.play`) with the folder's videos as a playlist (`{start, items:[{name,url,key,subs:[{name,url}]}]}`, URLs are the local server's `/api/dl`, which already does Range requests, so local / peer / SMB files stream without downloading). Handover goes through `PlayerActivity.pending` (no Binder size limit); only `http://127.0.0.1` / `localhost` URLs are accepted. Features: MKV/AVI/MOV/WebM/MP4/FLV/MPEG, audio + subtitle track menus, speed, next/previous, resume position (SharedPreferences `ls_player`), PiP (Home while playing, or the button), double-tap +-10 s, swipe left = brightness / right = volume, Fit/Zoom/Stretch, rotate button, auto landscape for wide videos. Sidecar subtitles (same base name, .srt/.vtt/.ass/.ssa) are fetched, converted to UTF-8 (windows-1254 fallback) into `cache/subs` and attached. Browsers (no `LSAndroid.play`) keep the HTML5 viewer (`openMedia`); inside that viewer a play button / the error box offer "Play in player". Manifest: `PlayerActivity` is its own task (`singleTask`, empty affinity) so PiP works; theme `Theme.LANShare.Player`. No background-audio service (audio stops when the screen is off or the PiP window is closed).

- **Image viewer (native):** `ImageViewerActivity.kt` (+ `androidx.viewpager2`). Tapping a picture in the Android app calls `LSAndroid.viewImages(json)` (`{start, items:[{name,url,size}]}`, handover via `ImageViewerActivity.pending`, only `http://127.0.0.1`/`localhost` URLs accepted) with the folder's pictures in the current sort order. Each picture is fetched from `/api/dl` into `cache/img/<n>.bin`, decoded with `inSampleSize` (longest side <= 3072/4096 px depending on the device's memory class, never above the 4096 GPU texture limit), EXIF-rotated, kept in an LruCache; the neighbours are preloaded. `ZoomImageView`: pinch, double-tap (fit <-> 2.5x), drag-pan, and at the edge of a zoomed picture the drag becomes a page swipe. Top bar: back, name + position + original resolution + size, slideshow (4 s, stops on manual swipe), share (copy in `cache/open` through the existing FileProvider). Tap hides the bars. `.gif` / `.svg` (and `.heic` below Android 9, `LSAndroid.sdk()`) still go to the HTML viewer (`openMedia`). Zoom works on the decoded (downsampled) bitmap, so originals above ~4096 px are not shown at full pixel resolution.

## Batch 9 – native browser: copy/move/paste, search, pull-to-refresh, peers + SMB (2026-10-03)
**NOT compiled / NOT device-tested** (syntax-checked with kotlinc only). Changed: `BrowserActivity.kt` (rewritten), `core/SmbFs.kt` (`Smb.add/remove`), `core/Routes.kt` (`smbUpdate` uses them), `MainActivity.kt` (`nativeBrowser(path, dev)`), `ui.html` (passes `S.dev`), `app/build.gradle.kts` (+`swiperefreshlayout:1.1.0`).
- **Devices:** chip bar = This phone · LANShare peers (`Core.disc.list()`) · SMB shares (`Smb.peers()`), refreshed every 3 s in-process; `＋` chip → add SMB share (tested via `Smb.add`), add device by IP, rescan; long-press an SMB chip → remove. Listings via `Jobs.ep(dev).ls`, free space via `Endpoint.space`, last folder remembered per device, cache key `<dev>|<path>`. Counts/prefetch/thumbnails stay phone-only.
- **Copy / Cut / Paste:** selection bar Copy · Cut · Delete · ⋮ (Share, Send to device…, Rename, Open with…). Uses the shared `Clip` (web UI sees the same clipboard), paste bar at the bottom works across devices (`Jobs.start`), delete via `Jobs.startDelete`. Native job bar polls `Jobs.all` every 400 ms: progress, speed, ETA, Cancel; errors in a dialog; listing reloads when jobs end.
- **Search:** 🔍 → `Endpoint.search(cur, q)` (recursive, phone/peer/SMB), results show parent folder, folders navigate, files open in viewers; selection/clipboard work on results (selection keyed by full path).
- **Pull-to-refresh:** `SwipeRefreshLayout` around the list (drops cache, rescans peers at root).
- **Files for other apps from peer/SMB** (Open with, Share): fetched into `cache/open/f<n>/` with a progress dialog + Cancel, then FileProvider.
- Still web-only: archives (`!` paths), zip, print, settings.

## Batch 8 – speed work + native file browser TRIAL (session of 2026-10-03)
**Status: NOT compiled, NOT device-tested** (no Android SDK in the authoring environment). First Gradle build may show small compile errors in `BrowserActivity.kt`; send them to the assistant to fix.

### Goal / decision log
- User: "app works perfectly except sluggish feeling". Architecture stays: WebView UI (`assets/ui.html`) + Kotlin core server + native viewers (player/image/PDF).
- Analysis given: a 100% native UI would be ~6,000–9,000 new Kotlin lines (Compose estimate), ~1–2 weeks, would NOT speed up storage reads / SMB / peers, and would split design work in two (HTML + Kotlin). Decision: **trial a native file-browser screen only**, keep the rest in HTML. If the trial feels clearly better, extend native further; if not, delete `BrowserActivity` (see "Rollback").

### ui.html performance edits (WebView path, already in this tree)
- `prefetch(dev,path)` / `idlePre()`: warms `LSC` listing cache on `pointerdown` of a folder row and, after a load, for the first 4 non-hidden sub-folders (local device only, skipped with Save-Data).
- `body{touch-action:manipulation}` (no 300 ms tap delay); removed `transition:background` on `.row`.
- Selection toggle (`r.onclick` / gallery `c.onclick`) no longer calls `render()`; toggles the `sel` class and calls `renderBar()` only.
- Thumbnails: `thBusy<4` (was 2); image thumbs use the server's `/api/thumb` result directly (`{u:'/api/thumb?...'}`) instead of canvas re-encode + base64; `thPut` defers `localStorage.setItem` to `requestIdleCallback`. Video thumbs still go through base64.

### Native browser trial: `BrowserActivity.kt` (new, ~660 lines, package `com.lanshare.app`)
- Plain `android.app.Activity` (no AppCompat), programmatic Views, `RecyclerView` (explicit dep `androidx.recyclerview:recyclerview:1.3.2` in `app/build.gradle.kts`). Light/dark from system uiMode; accent `#0D8F7E`, bar `#1C1C1E`.
- **Scope: this phone's storage only** (`Core.local`, `LocalFs`). Listings call `Core.local.ls(path, false)` directly, then `Core.local.counts(path)` for folder item counts (same two-step as the web UI). No HTTP for listings.
- Speed features: static `LruCache` of listings (80) survives rotation/reopen; cached list drawn instantly then silently revalidated (`same()` skips redraw if unchanged); touch-down prefetch + idle prefetch of first 4 sub-folders; scroll position restored per folder (`states`); thumbnail `LruCache` (1/8 of heap) + 3-thread pool; thumbs from `Thumbs.make(File)` (disk-cached JPEG) and `VideoThumbs.make(Core.local.open(p))`; failed thumbs remembered.
- Features: list/grid toggle, sort name(natural)/date/size + asc/desc, hidden-files toggle, breadcrumbs, free-space in subtitle, long-press multi-select, share, delete (confirm), rename, new folder, select all, Open-with chooser, back = up one level / clear selection. Prefs in SharedPreferences `ls_native` (view, sort, asc, hidden).
- Opening: folder → navigate; video → `PlayerActivity.pending` (same JSON as `nativePlay` incl. sidecar subs + resume key `local|<dir>/<name>|<size>`); picture → `ImageViewerActivity.pending` (svg/gif excluded, heic only API 28+); PDF → `PdfViewerActivity.pending`; everything else → `ACTION_VIEW` via FileProvider **in place** (text/source extensions → `text/plain`). Viewer URLs are `Core.url + /api/dl?dev=local&path=…` (viewers only accept 127.0.0.1/localhost).
- **Entry point:** `MainActivity.Bridge.nativeBrowser(path)` (`@JavascriptInterface`, starts `BrowserActivity` with extra `path`). `ui.html` drawer → Folders tab footer has a new **Native** button (only when `LSAndroid.nativeBrowser` exists) that passes the current local path (or `/`). Menu ⋮ → "Full app (web UI)" just `finish()`es back.
- Manifest: `<activity .BrowserActivity exported=false launchMode=singleTop>` (default `Theme.LANShare`). `file_paths.xml` gained `<root-path name="root" path=""/>` so files can be shared/opened without copying to cache.
- **Not in the trial (still web UI only):** peers/other devices, SMB, archives (`!` paths), copy/cut/paste/move, send/receive jobs & progress, search, favorites/recent, dual-pane, storage-permission banner, pull-to-refresh (menu → Refresh instead), long-press context actions beyond the ones above.

### Likely compile-risk spots (check first)
- `BrowserActivity.kt`: use of `Core.local.counts()` / `ls(v, counts)` (public in `LocalFs`), `Thumbs.make(File)`, `VideoThumbs.make(Source)` returns `Pair<ByteArray, Long>`, `Core.local.open(v)` returns `Source`; `bindingAdapterPosition` needs recyclerview ≥1.2 (we pin 1.3.2); `getDrawable(Int)` on Activity (API 21+ OK); `PopupMenu` anchor views are `TextView`s.
- Name clashes: `core.*` is star-imported; `Item` is `core.Item` (not the private `Item` classes inside Player/Image viewers).
- `FileProvider` root-path exposes the whole filesystem to *our own* grant-URI flow only (provider is `exported=false`, grantUriPermissions).

### Test checklist for the trial
1. Open web UI → drawer (≡) → Folders tab → footer **Native**. Browse into a large photo folder: thumbnails appear, scrolling smooth, back restores scroll.
2. Re-enter a visited folder: should appear instantly (no "Loading…").
3. Tap video / picture / PDF / .txt / .apk: each opens in the right viewer/app. Check sidecar subtitles and image swipe order = current sort.
4. Long-press → select several → Share, Rename (1 item), Delete, Select all, back clears selection.
5. New folder, sort menu, hidden-files toggle, list↔grid, rotate device (state kept), dark mode.
6. Compare feel against the web UI on the same big folder.

### Rollback
Delete `BrowserActivity.kt`, the `<activity .BrowserActivity>` line, the `nativeBrowser` bridge method, the drawer "Native" button block in `ui.html` (search `LSAndroid.nativeBrowser`). The recyclerview dep and root-path entry are harmless to keep.

### Next steps (if the trial is liked)
1. Fix first-build compile errors; device-test the checklist above.
2. Add pull-to-refresh (`androidx.swiperefreshlayout`), search (`LocalFs.search`), storage-permission banner (`Core.storageOk`).
3. Add copy/move/paste by calling the existing `Jobs` API (see `Routes.kt` `"copy"/"move"` handling) with a native clipboard + progress notification.
4. Peers/SMB: wrap `Jobs.ep(dev)` (`Endpoint.ls`) instead of `Core.local`; `RemoteFs`/`SmbFs` listings are slower, keep the cache + "stale while revalidate" pattern.
5. Optionally make native the default start screen (launcher → `BrowserActivity`) and keep the web UI behind "Full app".

## Batch 10 – print fitting options (2026-10-04)
- **New print options** (print dialog → `PRN_DEF` in `ui.html` → `Jobs.PRINT_KEYS` → query string → `pcprint.py parse_opts`): `fit` now also accepts `fill` (cover the area, crop the rest); `margin` (points 0–72, `''` = old default per file kind), `scale` (custom %, 10–500, overrides `fit`), `align` (`''` centre / `top` / `topleft`), `autorot` (turn a page 90° when it fits the sheet better; sheets keep the orientation of the first page). Margin values are sent as strings so `'0'` (= "None") survives the "drop empty/0" filter in the Print button.
- **pcprint.py (VERSION 9):** `needs_fitting(o)` / `fit_cell()` / `neutral_fit()`. With none of the new options set everything behaves as before (old `fit` = shrink / fit / noscale still goes straight to SumatraPDF). When a new option is set, PDFs are re-placed on the sheet by `process_pdf` (paper size from `paper`, else the page's own size); pictures and text are fitted while they are converted (`img_to_pdf`, `txt_to_pdf`, `images_to_sheets`) and then not fitted again. When a paper size is chosen the finished PDF is sent to Sumatra as `noscale`. `fill` + margin: overflow is cut off with a white frame over the margins (single page per sheet); with several pages per sheet a page is never larger than its cell.
- **Preview must stay identical** to the PC side: `pvFitK()` ⇔ `fit_cell()`, `pvSheets()` ⇔ `images_to_sheets()` (checked for equal results on 36 scale cases and 5 picture layouts).
- **Pictures** (`imgMode`): Scaling = Fit whole picture / Fill page (crop), plus Margins; custom scale, position and turn-pages are document-only. **Text files:** margin = page margin, custom scale = font size.
- **Not tested on a real printer / SumatraPDF** (rendered with pdftoppm only). Printers cannot print the last ~3–5 mm, so margin "None" is clipped by the printer hardware.

## Batch 11 – faster file browsing without touching the UI (2026-10-04)
**NOT compiled / NOT device-tested** (no Android SDK here; the GitHub build will show any compile error). `ui.html` is unchanged.
- **`core/InProc.kt` (new) + `MainActivity` `shouldInterceptRequest`:** the WebView's quick local GETs `/api/ls`, `/api/space`, `/api/stat` (only `dev=local`, no `!` archive paths), `/api/job`, `/api/clip`, `/api/info` are answered inside the app by running the same `Routes.handle` against an in-memory `Exchange`, so no localhost TCP/accept/thread/HTTP parse; answers are byte-identical. Everything else (POSTs, `/api/dl`, thumbnails, other devices, SMB, archives, search, any 5xx) returns `null` and goes the old HTTP way. `WebViewClient` cannot see POST bodies, so POST routes always stay on HTTP. Kill switch: `InProc.enabled = false`. Slow answers (>300 ms) are logged to logcat as `LANShare: slow in-process ...`.
- **`LocalFs.ls`:** folders with ≥150 entries are stat'ed by 4 parallel threads (`statPool`) instead of one by one; same Items, same order as `listFiles()`. Also helps `BrowserActivity`.
- **`Routes` `ls`:** names are lower-cased once for sorting instead of on every comparison.
- Not done on purpose (risk / no way to measure here): in-process thumbnails (heavy decode would block WebView's intercept threads), counts, search, peers/SMB; a native listing cache (would show stale lists after changes made by other apps).
- If it still feels slow after this, the remaining cost is probably DOM rendering of big folders in the WebView, or Android's storage layer itself - capture `adb logcat -s LANShare` while browsing a big folder and send it.
