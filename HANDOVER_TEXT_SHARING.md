# HANDOVER – Text sharing (replace WhatsApp for text/links)

**Status 2026-10-15: code complete (phone + PC), Kotlin NOT compiled, NOT device-tested** (no Android SDK / kotlinc in the authoring environment). `ui.html` JS syntax-checked with node; all XML parsed. Branch `feat/text-inbox` of `topgun1111/pcshare`, **nothing pushed yet**. CI (`build-apk.yml`) only builds on push to `main`/`master` or manual dispatch (dispatch creates a release + tag), so compile errors surface only after merge or a dispatch.

First step next session: copy the zip contents over the repo (paths are repo-relative) or `git apply text-sharing.patch`, push the branch, open the PR, then fix compiler errors from the Actions log.

## What already existed (do not rebuild)
- `/p/clip` (receive) + `/api/sendclip` (send) + `RemoteFs.pushClip` + `ClipPush.kt` (on-screen box + heads-up notification, links auto-open) + share-sheet alias `.SendShareAlias` ("LANShare Send": sends the clipboard to a device picked in a WebView picker).

## What was added
| Piece | Files | Behaviour |
|---|---|---|
| Inbox store | `core/Notes.kt` | `<filesDir>/inbox.json`, max 300 items / 2 MB, `add/all/remove/clear`, `listeners`, `visible` flag. Fed by `ClipPush.received` (in) and `Routes` `sendclip` + `TextSend.send` (out). |
| Inbox screen | `InboxActivity.kt`, Bridge `openInbox()`, drawer item in `assets/ui.html` | Native chat-like list; tap = copy, long-press = copy / open link / share / delete; type or paste + Send; "To:" line picks the DEFAULT device; ⋮ menu: Auto-copy, Clipboard sync, Clear. |
| Default target | `Cfg.defTarget()` (`def_target`), `TextSend.target()` | Default if online; else (no default set) the only online device; else null → picker. |
| Share-sheet fast path | `ClipSendActivity.kt` (+ manifest filter SEND text/plain, label "LANShare Text") | Invisible activity, sends shared text to target immediately; picker dialog only if several online and no default. |
| Direct Share | `Shortcuts.kt`, `res/xml/shortcuts.xml`, manifest `<meta-data android:app.shortcuts>` on MainActivity | One dynamic shortcut per online device (default ranked first), category `com.lanshare.app.category.SEND_TEXT`, share-target class `ClipSendActivity`. Re-synced every ~10 s from `LanShareService.noteTick` and on inbox open. Same shortcuts show on launcher long-press and send the clipboard. |
| Quick Settings tile | `SendClipTile.kt` + manifest service | Tap → `ClipSendActivity` (needs window focus to read the clipboard on Android 10+) → default device. Subtitle = default device name (API 29+). `startActivityAndCollapse(PendingIntent)` on API 34+. |
| Notification quick-reply | `QuickSendReceiver.kt`, `LanShareService.buildNote` | Foreground notification now has: inline "Send text" (RemoteInput) → default device, "Clipboard", "Stop". `LanShareService.refresh()` re-posts to clear the input state. |
| Auto-copy (receive) | `Cfg.autoCopy()`, `ClipPush.received` | Incoming text is written to the clipboard in the background (allowed on Android 10+). |
| Clipboard sync (send) | `ClipSync.kt`, `App.kt` (new Application class, manifest `android:name=".App"`) | Opt-in. While ≥1 LANShare screen is visible, copied text is pushed silently (`quiet:true`) to the default device. Skips IS_SENSITIVE clips, blank/>100 KB, echoes of received text (`mark`). **Foreground only** (Android 10+ clipboard-read rule). Receiver honours `quiet` only if ITS Auto-copy is on, else shows it normally (prevents silent clipboard hijack by any LAN device). |

Protocol change: `/p/clip` body gains optional `"quiet": true` (old receivers ignore it).

## PC side (added later the same day) - pcprint.py v26 + `core/PcText.kt`
**PC (`pcprint.py`, service on port 8799):**
- `POST /p/clip` (also `/clip`): same body as between phones (`text`, `from`, `quiet`, now also `id` + `port` of the sender). Stored in `%APPDATA%\LANSharePrint\inbox.json` (max 300 items / 2 MB), optional copy to the clipboard (PowerShell `Set-Clipboard`; setting "Copy received text automatically", default off, also the opt-in for quiet pushes), and the **LANShare Text** window opens (setting "Open this window on new text", default on, at most every 5 s).
- Localhost only (the window): `GET /clip?since=N` (items + devices + settings), `POST /clip/send {to,text}`, `/clip/delete {id}` (no id = clear), `/clip/settings {autocopy,popup}`. Other LAN devices cannot read the inbox.
- Devices to send to: the service listens for the phones' UDP beacons (port 48555, `SO_REUSEADDR`) and keeps the ones heard in the last 90 s; a phone that sends text here also registers itself. `/clip/send` only talks to devices in that list (no arbitrary hosts).
- Window: `pcprint.py --text` or the new **Text** button in the main window (single instance, named mutex): list of texts (double-click = copy), Copy / Open link / Delete / Clear all, device drop-down + box + Send (Ctrl+Enter), the two switches.
- `/ping` now has `"clip": true`. Firewall: setup adds a UDP rule `LANSharePrint-Beacons` (48555, LocalSubnet + tailnet) - **run the new file once** so Windows asks (without it only phones that already sent text can be answered).
- Tested on Linux with a stub phone: phone->PC, beacon discovery, PC->phone, delete / settings, bad bodies (400), empty text. The Tk window was only run against a mocked tkinter (builds, polls, fills the list); Windows clipboard / popup / firewall not run.

**Phone (`core/PcText.kt`, `TextSend`):** a PC is not a LANShare peer, so every ~30 s (on demand from `TextSend.online()`) the addresses already known (devices, saved SMB hosts, pins, ARP neighbours) get `GET :8799/ping`; a reply with `clip:true` makes that host a text target named "<PC> (PC)", id `pc:<ip>`, port 8799. It shows up wherever phones do (inbox target line / picker, default device, Direct Share, tile, share sheet "LANShare Text") because `TextSend.online()` / `find()` merge both lists; `RemoteFs.pushClip` posts to its `/p/clip` unchanged. A PC's texts to the phone arrive through the normal `/p/clip` (inbox + notification). `RemoteFs.pushClip` now also sends `id` and `port`. Old pcprint.py (404) -> "pcprint.py ... is older than v26".

## Files
New: `core/PcText.kt` (PC targets), `App.kt ClipSendActivity.kt ClipSync.kt InboxActivity.kt QuickSendReceiver.kt SendClipTile.kt Shortcuts.kt TextSend.kt core/Notes.kt res/xml/shortcuts.xml`
Modified: `AndroidManifest.xml assets/ui.html ClipPush.kt LanShareService.kt MainActivity.kt core/Cfg.kt core/RemoteFs.kt core/Routes.kt res/values/themes.xml pcprint.py` (+ `ClipSendActivity.kt TextSend.kt` use `TextSend.find`) (+ this file)

## Known risks / check list (compile + device)
1. Compile: `LanShareService` companion accesses private `ui`/`repost()`; outer class accesses private `inst` of its companion; `Intent.EXTRA_SHORTCUT_ID` (API 29 constant) used with minSdk 24; `ShortcutInfoCompat`/`IconCompat` from core-ktx 1.13.1; `Tile.subtitle` guarded by API 29.
2. `ClipSendActivity` is `noHistory` + translucent theme `Theme.LANShare.Clear`; verify it gets window focus (clipboard read) from the tile, the notification button and the share sheet.
3. Direct Share: verify the share-target is picked up (shortcut category must match `shortcuts.xml`); if `targetClass` rejects the exported activity, point it at `MainActivity` and branch on `EXTRA_SHORTCUT_ID` in `handleShare`.
4. Share sheet now lists three LANShare entries for text: "LANShare" (print, `*/*`), "LANShare Send" (alias, sends clipboard via picker), "LANShare Text" (new, instant). Consider removing the alias.
5. Dynamic-shortcut rate limiting in the background is swallowed (`Shortcuts.sync` resets `last` and retries).
6. Cold start from the tile: `ClipSendActivity` polls up to ~8 s for discovery (`ensureRunning` starts the service).
7. Device checklist: inbox send/receive both ways; default device persists; tile; notification inline reply; share text from Chrome → "LANShare Text" and → device name (Direct Share); Auto-copy with app closed; Clipboard sync on (app open) copies on the other phone only when its Auto-copy is on; password-manager copy NOT synced; inbox survives app restart.

## Not done / ideas
- Device checklist for the PC: phone -> PC (window opens, text listed, Copy works, link opens), PC -> phone (device appears within ~2 s of opening LANShare on the phone; text lands in the inbox), auto-copy on/off, Windows asks for the UDP firewall rule once. The phone must be on the same subnet as the PC (or both on the tailnet) to find it.
- Long-press a device chip to set default (default is currently set only in the inbox screen).
- Unread badge, file attachments in the inbox, Settings row in `ui.html` for the switches.
- Commit/PR conventions: extensive commit message + PR description (what and why); end commits with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_012SjHbGkm7G5ysSv32XutGF`; PR body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)` + the session URL. Give the PR/compare link after pushing: `https://github.com/topgun1111/pcshare/compare/main...feat/text-inbox`.
