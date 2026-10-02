# LANShare → Android: Handover

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
