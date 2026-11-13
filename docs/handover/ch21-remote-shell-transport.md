# Handover chapter file - ch21-remote-shell-transport

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

## 21. Remote shell transport + Upload & run (merged 2026-11-05 from chapter 21, state of 2026-10-31)

What this file covers: how the phone talks to the PC for the **Remote shell** screen, how it is secured, and what is still open. Screen features (key row, selection, copy / paste, themes, text size) are in `HANDOVER.md` chapter 19; this file is the transport + security + state. The old per-session history is gone on purpose.

### 1. What it is
A real `cmd` or PowerShell on the PC, shown on the phone with xterm.js. The PC runs `tools/phone_explorer.py` as Administrator (the shell has Administrator rights), the phone reaches it over LAN or Tailscale (port 8798, plain HTTP). Windows 10 1809+ (ConPTY through ctypes). Confirmed by the owner on 2026-10-31 over Tailscale: cmd prompt, `python` 3.14.4 REPL, Ctrl+C, pasting several lines.

### 2. Files
- PC: `tools/phone_explorer.py` - `TermSession`, `_ConPty` (Windows), `_PosixPty` (Linux, tests only), `POST /term`, `_auth()`, `fail_entry()`, `ensure_firewall()`, reaper `_term_reaper`.
- App: `app/.../pe/PePc.kt` (`term()`, `setLiveShell` / `endLiveShell`), `core/Routes.kt` (`pcterm`, `/xterm/*`), `MainActivity` (`onDestroy` ends the live shell), `assets/xterm/` (xterm 5.3.0 + fit 0.8.0, loaded when the screen opens).
- Screen: `assets/ui.html` `openRemoteShell()` (flow: `openPick` cmd / PowerShell -> `openConn` PC + password -> `start()`).
- Tools: `tools/term_test.py` (PC only: prints raw ConPTY output, use it when the phone terminal stays blank).

### 3. Protocol `POST /term` (form body)
Fields always: `act`, `sid` (8-64 chars `[A-Za-z0-9_-]`), `pass`.
- `open` + `shell` (cmd | ps), `cols` (20-500), `rows` (5-200): starts the shell, or re-attaches when the sid exists. Answer `{ok, existed, base, end, alive, seq}`.
- `read` + `o` (byte offset), `wait` (<= 25 s, the phone uses 20): `{data (base64), from, o, alive, code, reset}`. Same offset = same bytes. 4 MB buffer per session; `reset` when the phone fell behind it.
- `in` + `seq`, `data` (base64, <= 64 KB): keys. `seq` <= last applied is ignored (typed once). 409 when the shell ended.
- `resize` + `cols`, `rows`, `rs`: `rs` = the phone's increasing counter; an older `rs` than the last applied is ignored (`applied:false`), `rs` 0 always applies and restarts the counter. 500 `terminal resize failed` when Windows refuses (the PC then keeps its old size and counters).
- `close`: kills the shell and everything it started. Unknown sid = 200.
- `list` (no real sid needed, the phone sends `listlist`): `{runs:[{sid,name,args,alive,code,started}]}` = the Upload & run programs, newest first.
- `run` + `name`, `args`, `cols`, `rows`: starts `<home>\PhoneExplorer\runs\<sid>\<name>` (uploaded with `/put` first). Same sid again = same program (never started twice). Answer as `open` + `{name, path}`.
- `attach`: like `open` for a RunSession that exists; never starts anything (404 `terminal not found` otherwise).
- Errors: 403 remote commands off / no password / wrong password, 429 locked or too many sessions (`TERM_SESS_MAX` 3), 400 bad sid, 404 terminal not found (closed, reaped, or the PC program restarted).
- Shell: `cmd` = `%COMSPEC%`, `ps` = `powershell.exe -NoLogo`, start folder = the user's home. The PC stays awake while a session lives.

### 3b. Upload & run (new 2026-10-31)
- `POST /put` (PC `_do_put`, streamed, NOT the 256 KB form reader): headers `X-PE-Pass` / `X-PE-Name` (percent-encoded), `X-PE-Sid`, `X-PE-Off`, `X-PE-Total`; body = raw piece (<= `PUT_MAX` 8 MB, the phone sends 1 MB). Written at its offset into `<name>.part`; the piece that reaches `Total` truncates + renames. Every piece is safe to repeat. Answer `{ok, have, done, path}`.
- App: `Routes.kt` `pcput` (body is already in `bodyBytes`, <= 16 MB) -> `PePc.put()` (one try; the screen repeats failed pieces without a limit). `ui.html` `sendFile()` / `attachRun()` / `openRunSheet()` / `stopRun()`; the programs use the same `read` / `in` / `resize` / `close` loop as a shell (`RSH_S.run = true`).
- PC: `RunSession` (same interface as `TermSession`, lives in `_terms`). Windows: the program is started with `CREATE_NEW_CONSOLE` (its own window, like a double-click; GUI programs open their own window anyway). Its text reaches the phone through a helper process (`phone_explorer.py --conmirror <pid>`, `_conmirror_main`): it attaches to the program's console, sends the console screen text whenever it changes (`T<base64>`), types keys (`K<base64>` -> `WriteConsoleInputW`; Ctrl+C = `GenerateConsoleCtrlEvent`) and sends the last screen at the end (`Q`). `RunSession._show()` turns screen texts into terminal bytes (rewrite the last line when only it changed, else clear + redraw). No colours (text only). A program without a console (GUI exe) gets a `G` note.
- The program is NOT tied to the phone: leaving the screen / closing the app only detaches (`rshEnd` ignores `run` sessions, `rshSync` does not report them to `MainActivity`). Stop = `act=close` = `kill_pid_tree`. Ended programs stay in the list until removed (`close`) or until `RUN_SESS_MAX` (12) needs room (oldest ended first).
- Linux: the same class runs the program on a pty (tests only; `.py`, `.sh`). The Windows part (`_conmirror_main`, `CREATE_NEW_CONSOLE`) is NOT tested - first device test needed (see open items).

### 4. Phone side
- `PePc.term()` = ONE step: 3 tries, a new socket each, 45 s read timeout, 8 s connect. No answer -> `IOException("PC not reachable (...)")` (safe to repeat); a refusal from the PC -> `PcError(message)` shown as is. Every act is safe to repeat, see rules below.
- `Routes.kt` `pcterm`: JSON body `{pass, act, sid, shell, cols, rows, o, wait, seq, data, rs}` -> form. Only those fields are forwarded (a new field must be added to that list).
- `ui.html`: `loop()` long-polls `read` (gives up after 10 min without contact), `flush()` sends numbered key batches (kept and repeated up to 150 s), `syncSize()` keeps the PC's console size equal to xterm's (repeated up to 8 times on network errors; also checked after every read), `saveSess()` keeps `{ip, port, sid, sh, seq}` in `ls_rsh_sess`. Settings in `ls_rsh` (password only when Remember is ticked).
- Leaving the screen (back arrow, Android back, app closed) ends the shell: `rshEnd()` / `endLiveShell()` send `act=close`.

### 5. Lifecycle on the PC
NO time limits (changed 2026-10-31; `TERM_IDLE` and the reaper are gone). A shell or program lives until the phone closes / stops it or the PC program restarts; an ended session stays (output readable) until closed. Capacity only: a 4th shell closes the least recently used one (an ended one first); `RUN_SESS_MAX` programs. Phone side: `loop()` and `flush()` retry for ever while the network is away.

### 6. Design rules (why it looks like this)
1. Never keep one idle HTTP connection: Android kills such sockets when Tailscale / Wi-Fi re-routes ("Software caused connection abort"). Every call is short (long poll <= 25 s).
2. Every act is idempotent: `open` same sid = same shell, `read` same offset = same bytes, `in` same seq = typed once, `resize` numbered, `close` twice is fine. So a lost answer is simply asked again on a new socket.
3. Output is an offset-addressed byte buffer; the phone moves its offset only after xterm has digested the chunk (no freeze on `dir C:\ /s`).
4. ConPTY must have exactly xterm's size, otherwise its cursor-positioned output lands on wrong rows (garbled screen). Hence `syncSize()` + `rs`.
5. A network error shows "reconnecting..." and keeps the session; only the PC's own refusals end it.

### 7. Security
- Gate for `/term` and `/exec`: the **Remote commands** ON / OFF button in the PC window (default OFF) + a password (PBKDF2-SHA256, 200,000 rounds, constant-time compare). 5 wrong passwords in a row lock that client address for 60 s (`fail_entry`, list capped at 512 addresses).
- Plain HTTP: only Tailscale encrypts it. On a LAN the password and all typed / printed text can be read by others on that network.
- Firewall (`ensure_firewall`): rules "Phone Explorer TCP 8798" / "UDP 41235", profile any, remote = private ranges + 100.64.0.0/10. Not built: a Tailscale-only option. A Windows Defender "Block" rule for python.exe would override the allow rule.
- `/put` and `act=run` use the same gate (button, password, 5-wrong lock). Uploaded files stay in `<home>\PhoneExplorer\runs\<sid>\` (never deleted by the program); an uploaded program runs with Phone Explorer's own (Administrator) rights, so the password and the button matter even more.
- A strong password and the button OFF when unused are the real protection.

### 8. Legacy `/exec` (PC only; no app version since 2026-10-31 uses it)
Kept so OLDER APKs keep working; same ON / OFF button, password and lock. Jobs: `POST /exec?id=<8-64>&pass&cmd[&wait<=25]` starts (same id never reruns), `poll=1` only asks (404 unknown id), `o` / `e` offsets = live output. `/exec?probe=1` -> `{"v":3,"term":1,"exec":bool}`. No id = old single request (waits up to 300 s). Job limit 1800 s, 8 jobs, finished kept 15 min, output cap 64 KB (512 KB live), stdin closed after 600 s idle, process tree killed on timeout. Remove it (and the `probe` answer) when no old APK is in use.

### 9. Tests
- `tools/run_test.py` (Linux or Windows, no phone): starts the HTTP part on port 18798 with a temporary password and checks /put (7-byte pieces, wrong password), act=run / in / read / list / attach / close, no idle limit, the 3-shell capacity rule and `RunSession._show`. The Windows-only part (console window + `--conmirror` helper) is not covered.
- Linux: `import phone_explorer` works without tkinter (`tk = None`); `TermSession` runs on a POSIX pty. Checked: open / same sid / close, reaper, resize ordering and refused resize, `fail_entry` bound.
- `ui.html`: `node --check` on the inline scripts. Kotlin is only compiled by CI.
- Windows device: `ping -t 127.0.0.1`, press Back, `tasklist | findstr /i ping.exe` must be empty; same after closing the app from Recents.

### 10. Open items
1. Device re-test of the size sync (paste a 30-line block, open / close the keyboard while it prints: nothing may overlap). Needs the new PC program AND the new APK.
2. Soft keyboard: once the input stopped (suggestion strip stuck on a half word) until the keyboard was re-opened; cause unknown, look at `hookIme()` in `ui.html`.
3. (done) no time limits.
3b. Upload & run, first device test: console programs (`python x.py` with `input()`, a `.bat` with `pause`, `ping -t`), a GUI exe, Ctrl+C, Windows Terminal as default terminal app (the helper attaches to the console of the new window; not verified there), Turkish text, a 100 MB file over Tailscale, Stop, reopen from the list. The program runs elevated like Phone Explorer itself (not as a normal user).
4. Tailscale-only firewall option; no HTTPS.
5. Remove legacy `/exec` when old APKs are gone.
6. Not checked: programs that print in the OEM code page (cp857) - if Turkish text is wrong, send `chcp 65001` on open.

### Lumen Part 19 (plan Part 3, 2026-11-07)
- Pairs live in `tokens.json` `pairs`; mode `ls_tmode` (theme | auto | light | dark); resolver `lmResolve` / `lmThemeApply` in `custom.js`. `S.theme` stays the chosen preset, `data-theme` is the resolved one.
- Preview card uses `[data-pv=name]` twin selectors generated by `gen.py`. `onWarning` is a new slot (derive.fresh + `lmFresh`).
- `contrast.py` now also runs P7 (gradient ends, glass floor 70 %) and T4 (7:1 for hcl / hcd). `preset_report.py` = T1 report (`--apply`, `--max`).
- Gradient snaps in `derive.py` / `custom.js` are silent (no "adjusted" notice). Block `LUMEN-UI15` holds the new CSS.
- 2026-11-08: **Lumen audit fixes (tar.xz rounds 1-3).** Page: picker swatches for Light / Dark / Follow system use the token values. Tokens: new slots `info`, `onInfo`, `onError`, `onSuccess`, `successCont`, `warningCont` (`derive.fresh` + `lmFresh` + `tokens.json` fresh sets; `LumenTokens.kt` regenerated; `contrast.py` 7 new pairs, 0 failing; parity 0). Kotlin, NOT compiled: `NlFileIcons.kt` lost `drawLegacy`, its ~25 helper functions and the colour constants only they used (file 477 to 156 lines; `draw`, `catOf`, `glyphOf`, `extOf` and the extension sets untouched; braces balanced); `NlChrome.release()` clears the static `onElev` callback, called from `MainActivity.onDestroy`. Owner check next compile: `NlFileIcons.kt` builds, list / grid / search file tiles look the same.
- 2026-11-08: **Lumen md files merged into one: `LUMEN.md`** (Part 1 original plan, Part 2 done, Part 3 missing). `LUMEN_DESIGN_SYSTEM.md`, `LUMEN_WORK_PLAN.md`, `LUMEN_AUDIT.md` are deleted; every reference above now reads `LUMEN.md`. Update LUMEN.md after every change.

### Lumen native interface pass (2026-11-07)
- `LumenNativeTheme.kt` applies shared native Activity chrome after each Activity resumes: Lumen canvas for plain root backgrounds, theme-aware status/navigation bars, default monochrome TextView normalization, EditText ink/hint/primary tint, and minimum 48 dp standard Button targets.
- Registered centrally in `App.kt` with `ActivityLifecycleCallbacks`; it intentionally does not recolour custom semantic controls or custom-drawn feature surfaces.
- This is a conservative consistency layer. It does NOT close the remaining Lumen items in `LUMEN.md` (collapsing/search/viewer bars, dedicated empty/error/permission states, tooltips, tab motion, typography scale, full viewer token migration, V2-V5 device/accessibility verification).
- Compile and test before release. In particular inspect light/dark screens, image/video/PDF viewers, storage analyzer, text editor, dialogs, and keyboard focus after the lifecycle pass.
