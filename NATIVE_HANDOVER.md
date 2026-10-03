# Native conversion – handover for the next session

Read `HANDOVER.md` first (architecture, wire protocol, gotchas). This file = status + plan for finishing the WebView → native conversion.
**Nothing below has been compiled or run on a device** (no Android SDK in the authoring sessions; only `kotlinc` parse-checks). Step 0 of any session with an SDK: `./gradlew assembleDebug`, fix compile errors, run the checklist.

## Architecture (unchanged)
- `core/*.kt` = in-process server + file layer. `Core` (start/config), `Endpoint` (interface for phone `LocalFs`, peers `RemoteFs`, SMB `SmbFs`; `Jobs.ep(dev)` wraps in `ArcEp` for archives), `Jobs` (copy/move/delete/zip/print jobs in `Jobs.all`), `Clip` (shared clipboard), `Discovery` (`Core.disc.list()`), `Smb` (config helpers: `peers/status/add/remove/cfg`), `Cfg`.
- UI today: `assets/ui.html` in a WebView (`MainActivity`) + native screens: `BrowserActivity` (file browser), `PlayerActivity`, `ImageViewerActivity`, `PdfViewerActivity`.
- Native code talks to core **in-process** (no HTTP) except viewers, which take `http://127.0.0.1:<port>/api/dl?dev=..&path=..` URLs (Range support; works for phone/peer/SMB).

## DONE natively (BrowserActivity, Batch 8 + 9)
Phone browsing, list/grid, sort, hidden toggle, breadcrumbs, thumbnails (phone only), long-press multi-select, share, delete (`Jobs.startDelete`), rename, new folder, copy/cut/paste across devices (`Clip` + `Jobs.start`) with native progress/speed/ETA/Cancel bar, recursive search (`Endpoint.search`), pull-to-refresh (SwipeRefreshLayout), device chip bar (phone, peers, SMB), add SMB share / add peer by IP / rescan / remove SMB, "Send to device…", open peer/SMB files in other apps via `cache/open/f<n>/`, native viewers for video/image/PDF. Details in HANDOVER.md "Batch 8/9".

## NOT native yet (remaining work, suggested order)
1. **Compile + device test** everything (checklist in HANDOVER.md Batch 8 + test: copy phone→peer, peer→SMB, cut, cancel, search on SMB root, pull-to-refresh, add/remove SMB, rotate during a job).
2. ~~Native start screen~~ **DONE (Batch 10, uncompiled)**: `LauncherActivity` = launcher; asks permissions, starts `LanShareService`, waits for `Core.url`/`Core.error` (40 s), then opens `BrowserActivity` (CLEAR_TOP|SINGLE_TOP). Waits while a permission screen is on top (`active`). `MainActivity` no longer asks permissions / is not exported.
3. ~~Share-sheet receive~~ **DONE (Batch 10, uncompiled)**: `LauncherActivity.handleShare` copies to `<storage>/LANShare Shared/`, passes names as extra `share` (JSON array) → `BrowserActivity.handleShareExtra/sendShared` (device dialog → `Jobs.start("local", ..., INBOX, false, "Sending")`). `MainActivity.handleShare/runShare` are now dead code (delete in cleanup).
4. **Archives** (`!` paths): `ArcEp` already serves them via `Jobs.ep(dev)`. Native: tap zip/rar → `navigate(path + "!")`; show `ArcProg.current()` (poll like `/api/arcjob`) with Cancel (`Jobs.all[id].cancel`, `Cancelled` → go back); "Extract" action = `Jobs.start(dev, paths.map{ it + "!" }, dev, dir, false, "Extracting")` (see Routes `"extract"`). Read-only: hide cut/delete/rename/new-folder inside archives. Remove the `contains('!')` guards in `BrowserActivity.onCreate`.
5. **Zip action**: selection menu → name dialog → `Jobs.startZip(dev, paths, dir, name)`; hide inside archives.
6. **Print – DECISION: stays web.** Do not port. Selection menu → "Print…" (`BrowserActivity.printSelected`) starts `MainActivity` with extra `print` = `{dev,path,names}`; `ui.html` `PRINT_REQ`/`printBoot()` loads that folder, selects the names, runs the unchanged `doPrint()`; dialog cancelled (selection intact, no error toast) → `LSAndroid.closeHost()`. Printed/phone-print → stays in web UI (job progress, `PhonePrint` needs the host Activity alive). Split-screen disabled in print mode. `doPrint/printOptions/pvSheets` untouched (pvSheets must stay identical to `images_to_sheets()` in pcprint.py). Cleanup (step 11) must therefore KEEP `ui.html`, `MainActivity` WebView + `Bridge` print/closeHost/printRequest, `MiniHttp`, `Routes` `/api/print|printer|ls|dl|info|clip|paste|rm|stat|space|zip?`
7. **Settings screen**: rename device (`Cfg.name`, `Cfg.nameCustom`, `Cfg.save()`, `Core.disc.announce()`), edit SMB share (currently add/remove only), diagnostics (`Core.disc.ifaces/list`), About/version.
8. **Web-UI-only features to port**: favorites/recent (check `ui.html` for storage – localStorage keys `LSC`, favorites), dual-pane/tablet layout, storage-permission banner (`Core.storageOk == false` → button to `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`), file details dialog (`Endpoint.stat(v)` returns JSON: times, flags, folder totals, media info), rename/info for devices.
9. **Viewers gaps**: `.gif/.svg/.heic(<API28)` still use the HTML viewer → add a simple native path (Movie/AnimatedImageDrawable for gif on API 28+, `androidx.core`/Glide-free decode otherwise; svg via external app); audio: add native audio playback (reuse `PlayerActivity` with audio-only UI or a `MediaSession` service for background play).
10. **Remote thumbnails** (peer/SMB): only if wanted; would need ranged reads (`Endpoint.open` + `Thumbs`/`VideoThumbs.make(Source)` works on any `Source`) with disk cache. Folder item counts for peers/SMB likewise (`Endpoint.ls` only; no counts API).
11. **Cleanup when done**: delete `ui.html`, WebView/JS bridge in `MainActivity`, `Routes.kt` `/api/*` routes that only the web UI used (keep `/p/*` peer routes, `/api/dl`, `/api/thumb`, `/api/vthumb` if viewers still use URLs), `Core.page`.

## Conventions for the next session
- User prefs: maximum info density, no preamble/closers, apply fixes directly to files, deliver **only modified files as a zip**, bullets over prose, assume expert context, pick the most reasonable interpretation instead of asking.
- Code style in `BrowserActivity`: plain `android.app.Activity`, programmatic Views (no XML, no AppCompat), RecyclerView, `io` thread pool + `ui` Handler, static `LruCache`s keyed `"<dev>|<path>"`, selection keyed by full path, `Result<T>` for background results.
- `org.json` drops keys on `put(k, null)` – use `JSONObject.NULL`. Never call `removeFirst()/removeLast()` on `java.util.List` (Android < 15).
- Virtual paths: `/` root; `vnorm/vjoin/vdir/vbase` in `core/Util.kt`; SMB root `/` lists shares (cannot paste/mkdir there).
- Likely compile-risk spots in `BrowserActivity.kt`: smart-casts on captured vars, `Job` name resolution (`core.Job`), `Result.success(Jobs.start(..))` inference, `PopupMenu` anchors, `progressTintList` (API 21 ok).

## Batch 10 status (end of session)
- Added `LauncherActivity` (launcher + permissions + share intake), `BrowserActivity` Print… entry + share picker, `ui.html` print-mode hook (`PRINT_REQ`/`printBoot`), `MainActivity` bridge `printRequest/closeHost`. Uncompiled, untested on device.
- Next, in order: (1) compile + fix; (2) native Settings (step 7); (3) archives + zip/extract (steps 4-5); (4) upload picker, download-to-phone, audio/text viewers, split-screen drop.
- Printing stays web (see step 6). Device checks: share-sheet send, print cancel, PC print, phone print, cold start with permission screens.
