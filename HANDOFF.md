# LANShare Android — handoff (≈85% done, code-complete, never compiled)

Architecture: Chaquopy 17 embeds CPython 3.12 → `lanshare.py` runs as HTTP server + UDP discovery in a foreground service; `MainActivity` WebView loads `http://127.0.0.1:<port>/`.

## Done (session 1)
- `lanshare.py` `serve()`, `LANSHARE_NAME`, `bridge.py`, Gradle (AGP 8.7.3, Kotlin 2.0.21, Chaquopy 17.0.0, minSdk 24, no wrapper — CI uses Gradle 8.9), manifest/permissions/FGS, WebView dialogs + window.open, CI workflow.

## Done (session 2) — written but UNCOMPILED (no SDK in the sandbox)
- **Download/open**: `MainActivity.download()` streams `/api/dl` (127.0.0.1 only) → MediaStore Downloads (API 29+) or public Downloads + FileProvider (API 24–28); filename from Content-Disposition; ACTION_VIEW for image/video/audio/pdf/text, others just toast "Saved to Downloads".
- **Restart after All-files access**: `onResume` → `bridge.stop()` + `start()` → reload WebView. `Discovery` is now stoppable (`alive` flag) so restart doesn't leak beacon threads (smoke-tested on Linux).
- **Network fallback**: `ServerService.linkSpec()` reads LinkProperties (IPv4 + prefix) and passes it to Python (`bridge.set_ifaces`, `lanshare.EXTRA_IFACES`, merged in `get_ifaces()`); refreshed from a NetworkCallback on any network change.
- **Notification "Stop"** action → broadcasts `ACTION_STOPPED`, service stops, activity finishes.
- **Icon**: adaptive (+monochrome) in `mipmap-anydpi-v26`, layer-list fallback in `mipmap/`; old placeholder removed.
- **Signing**: secret `KEYSTORE_FILE` = base64 of the .jks (workflow decodes it), plus `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them, CI generates a debug keystore and signs with it.
- **Insets**: edge-to-edge; WebView padded by system bars/cutout/IME; window + status/nav bar colors match page `--bg` (light/dark).
- Verified: Chaquopy 17.0.0 exists, supports AGP 7.3–9.2, Python 3.10–3.14, minSdk 24.

## TODO (session 3)
1. **First CI run** — expect compile errors in the new Kotlin (nothing has been compiled). Paste the Gradle log and fix.
2. Real-device test: download/open on Android 9 and 13+, All-files grant → restart, Stop action.
3. Discovery on Android 10+: if peers don't appear, check whether `get_ifaces()` ioctl returns anything; the hotspot (tethering) interface may not show in LinkProperties — then add a `NetworkInterface.getNetworkInterfaces()` pass in `linkSpec()`.
4. Test hotspot host ↔ client, 2 devices.
5. Optional: Python 3.13 (Chaquopy recommends it for 16 KB-page devices); needs `version = "3.13"` + CI python 3.13.
6. Optional: replace `stat_sys_upload` notification icon with a custom monochrome one.

## Push
`git init && git add . && git commit -m init && git remote add origin <url> && git push -u origin main` → Actions → artifact `LANShare-apk`.
