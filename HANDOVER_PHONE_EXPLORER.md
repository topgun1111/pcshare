# HANDOVER - Phone Explorer inside LANShare (phone storage in Windows Explorer, one tap)

Status 2026-10-18: **coded, NOT compiled / NOT run on a device or on Windows** (first GitHub Actions run = first compile; paste the first error).

## What it does
Drawer row **Phone Explorer** = one tap: `peOpenOnPc()` (ui.html) starts the phone's FTP share (if needed), broadcasts a search for PCs (`GET /api/pcfind` -> `PePc.find`, UDP 41235, answered by **phone_explorer.py** running on the PC), picks the PC (last used by name, one PC = at once, several = one-time picker) and calls `POST /api/pcphone?ip&hport&port&user&pin&name` -> `PePc.open` -> `POST http://PC:8798/open` -> phone_explorer.py runs `explorer.exe ftp://user:PIN@<phone ip>:2121/`. Nothing to type; the PC takes the phone's address from the connection and the app passes the PIN. pcprint.py is NOT involved.
The gear on the row opens the settings sheet (`openPhoneExplorer()`: Start/Stop, address, PIN, Copy link, New PIN, QR, Read-only, Start automatically, shared folders, port, **Open on PC**).

## Files
- `app/src/main/java/com/lanshare/app/pe/`: `FtpServer.kt` (pure JDK FTP server, port 2121, user `phone`, 6-digit PIN, LAN clients only), `PeDiscovery.kt` (UDP 41234, answers the old PC tool's search), `PePrefs.kt`, `PeService.kt` (foreground service, notification 21, locks, `PeBoot` receiver), `PePc.kt` (find / open PC).
- `MainActivity.Bridge`: `peState/peStart/peStop/peSet/peNewPin/peQr/peGrant`. `Routes.kt`: `pcfind`, `pcphone`. `ui.html`: `peOpenOnPc`, `pePickPc`, `openPhoneExplorer`, drawer row after "PC screen".
- Manifest: `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, service `.pe.PeService`, receiver `.pe.PeBoot`. Gradle: `com.google.zxing:core:3.5.3` (QR).
- PC: `phone_explorer.py` (separate file, Tkinter + tray): UDP responder 41235, HTTP `/open` on 8798 (LAN clients only, user/PIN validated), saves the phone in its list, opens Explorer. Keep it running (tray); allow it once in the Windows firewall.

## Notes / limits
- Needs All-files access (the sheet / first tap opens that setting). Android 11+ keeps Android/data and Android/obb closed. Router "client isolation" blocks everything.
- The share is independent of the LANShare service; the LANShare notification Stop still kills the whole app.
- Port change needs Stop + Start; read-only and folder list apply at once.
