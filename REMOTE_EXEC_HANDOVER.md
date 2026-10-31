# Remote shell (phone -> PC) - current state (rewritten 2026-10-31)

What this file covers: how the phone talks to the PC for the **Remote shell** screen, how it is secured, and what is still open. Screen features (key row, selection, copy / paste, themes, text size) are in `HANDOVER.md` chapter 19; this file is the transport + security + state. The old per-session history is gone on purpose.

## 1. What it is
A real `cmd` or PowerShell on the PC, shown on the phone with xterm.js. The PC runs `tools/phone_explorer.py` as Administrator (the shell has Administrator rights), the phone reaches it over LAN or Tailscale (port 8798, plain HTTP). Windows 10 1809+ (ConPTY through ctypes). Confirmed by the owner on 2026-10-31 over Tailscale: cmd prompt, `python` 3.14.4 REPL, Ctrl+C, pasting several lines.

## 2. Files
- PC: `tools/phone_explorer.py` - `TermSession`, `_ConPty` (Windows), `_PosixPty` (Linux, tests only), `POST /term`, `_auth()`, `fail_entry()`, `ensure_firewall()`, reaper `_term_reaper`.
- App: `app/.../pe/PePc.kt` (`term()`, `setLiveShell` / `endLiveShell`), `core/Routes.kt` (`pcterm`, `/xterm/*`), `MainActivity` (`onDestroy` ends the live shell), `assets/xterm/` (xterm 5.3.0 + fit 0.8.0, loaded when the screen opens).
- Screen: `assets/ui.html` `openRemoteShell()` (flow: `openPick` cmd / PowerShell -> `openConn` PC + password -> `start()`).
- Tools: `tools/term_test.py` (PC only: prints raw ConPTY output, use it when the phone terminal stays blank).

## 3. Protocol `POST /term` (form body)
Fields always: `act`, `sid` (8-64 chars `[A-Za-z0-9_-]`), `pass`.
- `open` + `shell` (cmd | ps), `cols` (20-500), `rows` (5-200): starts the shell, or re-attaches when the sid exists. Answer `{ok, existed, base, end, alive, seq}`.
- `read` + `o` (byte offset), `wait` (<= 25 s, the phone uses 20): `{data (base64), from, o, alive, code, reset}`. Same offset = same bytes. 4 MB buffer per session; `reset` when the phone fell behind it.
- `in` + `seq`, `data` (base64, <= 64 KB): keys. `seq` <= last applied is ignored (typed once). 409 when the shell ended.
- `resize` + `cols`, `rows`, `rs`: `rs` = the phone's increasing counter; an older `rs` than the last applied is ignored (`applied:false`), `rs` 0 always applies and restarts the counter. 500 `terminal resize failed` when Windows refuses (the PC then keeps its old size and counters).
- `close`: kills the shell and everything it started. Unknown sid = 200.
- Errors: 403 remote commands off / no password / wrong password, 429 locked or too many sessions (`TERM_SESS_MAX` 3), 400 bad sid, 404 terminal not found (closed, reaped, or the PC program restarted).
- Shell: `cmd` = `%COMSPEC%`, `ps` = `powershell.exe -NoLogo`, start folder = the user's home. The PC stays awake while a session lives.

## 4. Phone side
- `PePc.term()` = ONE step: 3 tries, a new socket each, 45 s read timeout, 8 s connect. No answer -> `IOException("PC not reachable (...)")` (safe to repeat); a refusal from the PC -> `PcError(message)` shown as is. Every act is safe to repeat, see rules below.
- `Routes.kt` `pcterm`: JSON body `{pass, act, sid, shell, cols, rows, o, wait, seq, data, rs}` -> form. Only those fields are forwarded (a new field must be added to that list).
- `ui.html`: `loop()` long-polls `read` (gives up after 10 min without contact), `flush()` sends numbered key batches (kept and repeated up to 150 s), `syncSize()` keeps the PC's console size equal to xterm's (repeated up to 8 times on network errors; also checked after every read), `saveSess()` keeps `{ip, port, sid, sh, seq}` in `ls_rsh_sess`. Settings in `ls_rsh` (password only when Remember is ticked).
- Leaving the screen (back arrow, Android back, app closed) ends the shell: `rshEnd()` / `endLiveShell()` send `act=close`.

## 5. Lifecycle on the PC
Phone ends it -> `close`. Phone vanished (app killed, no network) -> nothing asks for `TERM_IDLE` = 300 s -> the reaper (checks every 10 s) closes it; an ended shell is dropped 60 s after the last request. Trade-off: a phone away for more than 5 minutes loses the shell and what runs in it.

## 6. Design rules (why it looks like this)
1. Never keep one idle HTTP connection: Android kills such sockets when Tailscale / Wi-Fi re-routes ("Software caused connection abort"). Every call is short (long poll <= 25 s).
2. Every act is idempotent: `open` same sid = same shell, `read` same offset = same bytes, `in` same seq = typed once, `resize` numbered, `close` twice is fine. So a lost answer is simply asked again on a new socket.
3. Output is an offset-addressed byte buffer; the phone moves its offset only after xterm has digested the chunk (no freeze on `dir C:\ /s`).
4. ConPTY must have exactly xterm's size, otherwise its cursor-positioned output lands on wrong rows (garbled screen). Hence `syncSize()` + `rs`.
5. A network error shows "reconnecting..." and keeps the session; only the PC's own refusals end it.

## 7. Security
- Gate for `/term` and `/exec`: the **Remote commands** ON / OFF button in the PC window (default OFF) + a password (PBKDF2-SHA256, 200,000 rounds, constant-time compare). 5 wrong passwords in a row lock that client address for 60 s (`fail_entry`, list capped at 512 addresses).
- Plain HTTP: only Tailscale encrypts it. On a LAN the password and all typed / printed text can be read by others on that network.
- Firewall (`ensure_firewall`): rules "Phone Explorer TCP 8798" / "UDP 41235", profile any, remote = private ranges + 100.64.0.0/10. Not built: a Tailscale-only option. A Windows Defender "Block" rule for python.exe would override the allow rule.
- A strong password and the button OFF when unused are the real protection.

## 8. Legacy `/exec` (PC only; no app version since 2026-10-31 uses it)
Kept so OLDER APKs keep working; same ON / OFF button, password and lock. Jobs: `POST /exec?id=<8-64>&pass&cmd[&wait<=25]` starts (same id never reruns), `poll=1` only asks (404 unknown id), `o` / `e` offsets = live output. `/exec?probe=1` -> `{"v":3,"term":1,"exec":bool}`. No id = old single request (waits up to 300 s). Job limit 1800 s, 8 jobs, finished kept 15 min, output cap 64 KB (512 KB live), stdin closed after 600 s idle, process tree killed on timeout. Remove it (and the `probe` answer) when no old APK is in use.

## 9. Tests
- Linux: `import phone_explorer` works without tkinter (`tk = None`); `TermSession` runs on a POSIX pty. Checked: open / same sid / close, reaper, resize ordering and refused resize, `fail_entry` bound.
- `ui.html`: `node --check` on the inline scripts. Kotlin is only compiled by CI.
- Windows device: `ping -t 127.0.0.1`, press Back, `tasklist | findstr /i ping.exe` must be empty; same after closing the app from Recents.

## 10. Open items
1. Device re-test of the size sync (paste a 30-line block, open / close the keyboard while it prints: nothing may overlap). Needs the new PC program AND the new APK.
2. Soft keyboard: once the input stopped (suggestion strip stuck on a half word) until the keyboard was re-opened; cause unknown, look at `hookIme()` in `ui.html`.
3. `TERM_IDLE` 300 s may be too short for long jobs; a setting would help.
4. Tailscale-only firewall option; no HTTPS.
5. Remove legacy `/exec` when old APKs are gone.
6. Not checked: programs that print in the OEM code page (cp857) - if Turkish text is wrong, send `chcp 65001` on open.
