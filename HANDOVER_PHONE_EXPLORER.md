# HANDOVER - Phone Explorer inside LANShare (phone storage in Windows Explorer)

Status 2026-10-18: **integrated, coded, NOT compiled / NOT run on a device** (first GitHub Actions run = first compile; paste the first error).
Full history of the stand-alone app: the separate PhoneExplorer zip (`HANDOVER_PHONE_EXPLORER.md` there).

## What it is
Drawer (side menu) row **Phone Explorer** -> bottom sheet: Start / Stop, address, PIN, Copy link (`ftp://phone:PIN@IP:PORT/` for Explorer's address bar),
New PIN, QR pairing code, Read-only, Start automatically, shared folders (whitelist), port. Status + connection count refresh every second.
Phone side = FTP server (port 2121, user `phone`, 6-digit PIN, LAN client IPs only) + UDP discovery (port 41234, `PHONEEXPLORER_DISCOVER`) so the PC tool finds the phone.

## Files
- `app/src/main/java/com/lanshare/app/pe/FtpServer.kt` - pure-JDK FTP server (unchanged from the stand-alone app, only the package).
- `pe/PeDiscovery.kt` - UDP responder (was `Discovery`; renamed, `core/Discovery.kt` is LANShare's own).
- `pe/PePrefs.kt` - PIN, port, read-only, auto-start, whitelist, pairing link, LAN IP (SharedPreferences `lanshare_pe`).
- `pe/PeService.kt` - foreground service (specialUse, notification id 21 with live speed + Stop; wake / Wi-Fi / multicast locks) + `PeBoot` receiver. The server binds on a worker thread; errors (e.g. port in use) go to `PeService.lastError`.
- `MainActivity.Bridge`: `peState()`, `peStart()`, `peStop()`, `peSet(json)`, `peNewPin()`, `peQr()`, `peGrant()`.
- `assets/ui.html`: `openPhoneExplorer()` (before `drawerRender`) + the drawer row after "PC screen" (only when the bridge has `peState`). The native drawer mirrors the DOM, so no `NlDrawer.kt` change was needed.
- `AndroidManifest.xml`: `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, service `.pe.PeService`, receiver `.pe.PeBoot`. `build.gradle.kts`: `com.google.zxing:core:3.5.3`.
- `tools/phone_explorer.py` (PC launcher, Tkinter, unchanged), `tools/ftp_smoke_test.py` (`python ftp_smoke_test.py PHONE_IP PIN`), workflow `build-phone-explorer-exe.yml` (PyInstaller exe, only when the .py changes).

## Notes / limits
- Needs All-files access (the app already asks at start); the sheet's button opens that setting if it is missing.
- Independent of the LANShare service: Stop in the sheet / notification stops only the FTP share (the LANShare notification "Stop" still kills the whole app).
- Android 11+ blocks Android/data and Android/obb for every app. Router "client isolation" blocks the PC completely.
- Port change needs Stop + Start; read-only and folder list apply at once.
- Open: the sheet could also show on the Main menu (`#home`) as a tile.
