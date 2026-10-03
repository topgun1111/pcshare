# Native conversion – status (v: all screens native, printing removed)

**Nothing has been built with Gradle or run on a device** (no Android SDK/Google Maven in the authoring sessions). A syntax + type pass of all `.kt` files with `kotlinc` against `android.jar` shows no errors except unresolved androidx / media3 / smbj / junrar symbols (libraries not available offline). Step 0 of any session with an SDK: `./gradlew assembleDebug`, fix what the compiler reports, run the checklist below.

**Deleted from the project (must stay deleted):** `MainActivity.kt`, `PhonePrint.kt`, `assets/ui.html` (+ empty `assets/` folder). No remaining references in `.kt`/manifest (re-grepped).

## What the app is now
- **No WebView, no HTML.** `assets/ui.html`, `MainActivity` (WebView + JS bridge) and `PhonePrint` are deleted. **Printing is not part of the app** (PC print via pcprint.py and phone print were removed: `Jobs.startPrint/printerStatus`, `/api/print|printer`).
- Screens (all plain `android.app.Activity`, programmatic Views): `LauncherActivity` (permissions, service, share-sheet intake) → `BrowserActivity` (main screen) · `SettingsActivity` · viewers: `PlayerActivity` (video **and audio**), `ImageViewerActivity` (pictures, **animated GIF on API 28+**), `PdfViewerActivity`, `TextViewerActivity` (new).
- `core/*` = in-process server + file layer, unchanged protocol: `/p/*` peer routes for other LANShare devices; **`/api/dl` is the only local route left** (viewers stream through it with Range support; it now answers 127.0.0.1/::1 only — previously the whole `/api/*` UI API was reachable from the LAN). Dead code removed: `Core.page`, all other `/api/*` routes, print jobs.

## Added in this batch
- **Upload**: main menu ⋮ → "Upload files here…" (SAF picker, multi-select) → `Jobs.startUpload` (works for this phone / peers / SMB; unknown sizes are staged in cache; cancel removes the half-uploaded remote file). Progress/Cancel in the normal job bar.
- **Save to phone**: selection menu on a peer/SMB device → copies into `/Download` (`Jobs.start(dev, …, "local", "/Download", …)`).
- **Audio**: tap an audio file → playlist of the folder's audio in `PlayerActivity`; music screen (note + title), controls stay visible, keeps playing when the activity is stopped (process kept alive by `LanShareService`). No MediaSession/notification controls (would need a `MediaSessionService`).
- **GIF**: `ImageDecoder` → `AnimatedImageDrawable` (no pinch-zoom while animating; below API 28 the first frame is shown). SVG opens in another app (`.svg` removed from the text-extension set so it gets `image/svg+xml`).
- **Text viewer**: source/text files (`TEXT_EXT`, dot-files) open natively (first 1 MB, UTF-8/UTF-16 BOM/windows-1254, selectable, 3 font sizes, wrap toggle). "Open with…" still hands a file to other apps.
- **Remote thumbnails** (peers/SMB): JPEG → embedded EXIF thumbnail (header read only); other pictures ≤ 4 MB → fetched + sampled; videos → `VideoThumbs.make(Source)` with ranged reads. 2-thread pool, memory cache only. Not inside archives.

## Still not done
- **Tablet dual-pane** (two browsers side by side). Grid already scales its column count with the width.
- Disk cache for remote thumbnails; background-audio notification/MediaSession; SVG rendering; pinch-zoom on animated GIF.
- Folder item counts for peers/SMB (`Endpoint.ls` has no counts API).

## Test checklist (first device run)
1. Cold start (permission screens), share-sheet send, rotate during a job.
2. Browse phone / peer / SMB; copy, cut, paste across devices; cancel; search; pull-to-refresh; add/edit/remove SMB.
3. **Upload** 2 files + 1 file of unknown size to phone, peer, SMB; cancel mid-way. **Save to phone** a folder from a peer.
4. mp3/flac/m4a playlist (screen off, back to browser, Home); video with subtitles; GIF; HEIC; PDF; .txt/.php/.json; .svg (external app).
5. zip open/cancel, rar, zip inside a peer, Extract, Zip, Details, storage-permission banner.
6. Thumbnails on a peer photo folder (EXIF thumbs appear fast), SMB video folder (watch network use).

## Conventions
- User prefs: dense output, no preamble/closers, apply fixes directly, deliver **only modified files as a zip** (list deleted files separately), assume expert context.
- `org.json` drops keys on `put(k, null)` → `JSONObject.NULL`. Never `removeFirst()/removeLast()` on `java.util.List` (Android < 15).
- Virtual paths: `/` root; `vnorm/vjoin/vdir/vbase` in `core/Util.kt`; SMB root `/` lists shares (cannot paste/mkdir/upload there).
- `Result<T>` for background results; annotate (`val r: Result<String> = try {…}`) when adding new ones.
