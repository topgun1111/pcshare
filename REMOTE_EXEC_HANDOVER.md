# Remote shell (phone -> PC /exec) robustness - handover (2026-10-30)

Symptom: Remote shell `chkdsk` over Tailscale -> "Software caused connection abort".
Root cause: a command was held on ONE idle HTTP connection for minutes. Android destroys such sockets (ECONNABORTED) when Tailscale/VPN/Wi-Fi re-routes; NAT/AP idle timers kill them too; the finished output was then lost.

## DONE (files in this zip; merge over the repo, same paths)
### tools/phone_explorer.py  (tested on Linux: 17 checks pass)
- /exec now runs commands as JOBS. `POST /exec?id=<8-64 chars>&pass&cmd[&wait=<=25]` starts (idempotent: same id never reruns); `poll=1` only asks (404 if unknown - never starts from a poll, so e.g. a reboot command is not re-run after a PC restart). Long-poll <=20 s. Answer: `{done:false}` or `{done:true,out,err,code,truncated}`. Job limit 1800 s (old no-id request: 300 s, same JSON, so old phones still work). Max 8 jobs; finished jobs kept 15 min.
- `/exec?probe=1` -> `{"v":2,"exec":bool}` (phone detects the new PC program).
- Command may travel in POST body (form-encoded); body always drained (an unread body caused RST/abort). cmd cap 100000 chars.
- Fixes: stdin=DEVNULL (pause / chkdsk Y/N hung forever); kill whole process tree on timeout (a grandchild kept the pipe, no answer ever); reader threads with 5 s grace for background children; output decoded from bytes (UTF-8, UTF-16, then OEM code page; never raises; old text=True crashed on 'ü' with a Turkish code page); output memory capped; timeout returns partial output + note.
- Handler: catch-all 500 JSON (no silent socket close), `timeout` 15->60, app.after() wrapped, config re-read from disk per request (`live_cfg`: two running copies no longer disagree), atomic `save_config`, SetThreadExecutionState keeps the PC awake while a job runs.
- `ensure_firewall()` (admin, background): netsh rules "Phone Explorer TCP 8798" / "Phone Explorer UDP 41235", profile=any, remote = private ranges + 100.64.0.0/10 (Tailscale adapter is a Public profile; the prompt is often not ticked for it).
- Not changed on purpose: SO_REUSEADDR (two instances can bind on Windows; mitigated by live_cfg). A Windows Defender "Block" rule for python.exe would override our allow - check if still unreachable.

### app/.../pe/PePc.kt  (compiled with kotlinc 1.9.24 against stubs; JVM tests OK)
- `exec(ip,hport,pass,cmd,id="",poll=false)` = ONE step. v2 PC: POST body, 45 s read timeout, 3 tries (0.7/1.8 s pauses, new socket each) - safe because id is idempotent. v1 PC (no id, or probe lacks v): old single request, never resent after it was sent (would run twice); cmd >15000 encoded chars -> clear error.
- `target()` cleans the typed address (scheme, path, user@, trailing dot, host:port). `open()` uses it too + connect retry.
- Errors: network -> `IOException("PC not reachable (<plain reason>)")` (this prefix = safe to retry); PC refusal -> `PcError` (shown as is). `ExecResult.done` added. Reads up to 4 MB.
- JVM results: short cmd 1 step; 27 s cmd = 2 steps (poll works); wrong password message ok; old-style server works; closed port gives a readable message. The "proxy cuts connection mid-poll" test was not finished.

### app/.../core/Routes.kt
- `pcexec` route = one step; reads `pass`/`cmd` from JSON body (fallback: query), `id`, `poll=1`; returns `done`.

## NOT DONE (next session, in order)
1. **ui.html Remote shell (~line 5516 `exec`, `doRun` ~5560, connect `go.onclick` ~5545)** - NOT edited. Current UI still works (no id -> legacy path) but gets none of the benefit. Change `exec` into a loop:
   - `id` = 24 random hex chars; `st=0`; each step `api('POST','/api/pcexec?ip=..&hport=..&id='+id+(st?'&poll=1':''),{pass:pw,cmd:line},340000)`.
   - `r.done!==false` -> return r; else `st=1` and repeat until the total budget (command 1,860,000 ms; connect test `hostname` 40,000 ms).
   - On error: if message matches `/^PC not reachable|^timed out|paused/` -> sleep min(5000,1000*n), retry while the last success is < 30 s ago (before first answer) / < 150 s (while polling); then throw "Lost contact with the PC (...). The command may still be running there." Other errors (wrong password, turned off, job not found) throw at once. Keep the existing `wrong password|No password` -> openConn().
   - Connect dialog: for messages starting "PC not reachable" or 'timed out' show the message plus the existing hint.
2. CHANGELOG.md: new top section (`## 2026-10-30 - Remote shell keeps working on Tailscale`; Fixed: no more "connection abort" over Tailscale / Wi-Fi changes, commands up to 30 min, Turkish characters, commands waiting for input no longer hang, PC opens the firewall for Tailscale itself). Add developer line to HANDOVER.md section 14 and chapter 10 (protocol above).
3. Rebuild PhoneExplorer.exe (CI) AND the APK; restart/update the PC program (a running tray copy keeps the old code).
4. Device tests: `hostname`; `ping -n 40 127.0.0.1` over Tailscale while toggling Tailscale on the phone; `chkdsk`; `dir C:\ /s` (cap, 'ü'); `pause` returns; new APK + old PC program and old APK + new PC program; `netsh advfirewall firewall show rule name="Phone Explorer TCP 8798"`.
5. Optional: Cancel for a running job (not implemented).
