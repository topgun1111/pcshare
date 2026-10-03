# LANShare → Android: Handover

> **The app is now fully native (no WebView, no print). Current status + remaining plan: see `NATIVE_HANDOVER.md`. Everything below that mentions `ui.html`, the web UI, `MainActivity`, `LSAndroid` or printing is historical.**

**Current state: pure Kotlin, fully native UI.** Chaquopy/Python and the HTML UI removed; `core/*.kt` serves the peer routes and `/api/dl` from `MiniHttp`. **Not yet compiled or device-tested** (no Android SDK was available): first Gradle build will likely show small compile errors in `core/*.kt` (likeliest: `Cfg` accessors, `Discovery.start()` function refs, smart-casts in `RemoteFs.callRaw`). Then run the test checklist below, incl. SMB and thumbnails.
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
