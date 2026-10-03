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
