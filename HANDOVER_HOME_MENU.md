# HANDOVER - Start menu (home screen)

Status 2026-10-10: **design approved; first version coded (`#home` overlay in `ui.html`, not compiled / not run on a device).** Original note: do it in a later session (one small part per session, free-account limits).

## Design (approved)
- Design artifact (claude.ai, private to the user): https://claude.ai/artifact/VsN5t5zJd7xkHJmvMj7kT7 - the source is also in this zip: `design/HomeMenu.dc.html` (+ `design/HomeMenu.canvas.json`); open the .html in any browser to see it (the `support.js` line is only needed by the Claude canvas).
- Reference look: *File Manager +* home (grid of big rounded icon tiles with a title and a small grey subtitle), phone artboard 390 x 844.
- **Top bar**: height 56, background `#212121`, hamburger button (opens the existing drawer), title "LANShare" (20 px, semibold, white), three-dot button (existing overflow menu).
- **Body**: background `#FAFAFA`, grid 3 columns, column gap 8, row gap 30, padding 32 / 14. Tile = rounded square 96 x 96, radius 28, white, 1.5 px border `#C9C9C9`, icon 52 px centred; under it the title (15 px, semibold, `#161616`) and a subtitle (12 px, `#5F5F5F`). Touch target = whole tile (>= 44 px).
- **Tiles, in order** (icon colour; subtitle):
  1. **Gallery** - purple `#6A1B9A` picture frame with a hill and sun; "Photos and videos"
  2. **Printers** - blue-grey `#546E7A` printer; "On this Wi-Fi"
  3. **Downloads** - amber `#E0A44F` folder with a white circle and a blue `#1E88E5` down arrow; "Received files"
  4. **Storage Analyzer** - grey `#616161` pie with a light `#D5D5D5` wedge; "What uses space" - **the user will code the analyzer later: keep the tile, show it disabled / "Coming soon" for now** so the layout does not change.
  5. **SMB Shares** - teal `#00897B` two stacked server bars with dots; "Network folders"
- Subtitles are descriptive on purpose (no invented numbers). Later they can show live counts (e.g. number of pictures, free space) like File Manager + does.
- The exact SVG paths for the five icons are in `design/HomeMenu.dc.html` (48 x 48 viewBox) - copy them into Android `VectorDrawable`s or inline SVG.

## What already exists in the app (found by reading, nothing run)
App = Kotlin shell (`MainActivity`, a `WebView` + native lists) around `app/src/main/assets/ui.html` (~330 KB, owns screens and logic). `MainActivity.Bridge` is the JS <-> Kotlin interface; `onNewIntent` -> `handleShare` handles the share-into-app intents (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
- **Gallery**: overlay `#gal` in `ui.html` (`galView`, `galPick`, `galClose`, `mkAlbum` / `mkAlbumX`, `galFetch`); `isView()` = the "Image search" screen reuses it. Find the function the drawer's gallery entry calls (`grep "#gal"`, drawer builder `drawerRender` ~line 2553) and call that from the tile.
- **Printers**: `pickPrinter(here, wifi, peers)` (~line 1695), `warmWifi` / `drawerPrinters` (~line 2544-2546), endpoint `/api/wifiprinters`.
- **SMB Shares**: `showFound()` (~1577, `/api/smbscan`), saved shares via `/api/smb`, an SMB device id looks like `smb:...`; `openDev(id)` (~641) opens a device.
- **Downloads**: a quick folder named "Download" (used in the drawer's Quick folders); open it with `openDev('local')`-style navigation to that path - check how the drawer row does it.
- **Drawer**: `drawerOpen/drawerClose/drawerRender` in `ui.html`, mirrored natively by `NlDrawer.kt` (draw lists from the DOM; kill switch `NlDrawer.ENABLED`).
- **Storage analysis**: nothing yet (the duplicate finder `DupFinder` is the nearest thing).

## Plan (decided, not started)
1. **Home screen as a page inside `ui.html`** (a `#home` overlay, same technique as `#gal`): the tiles call the existing functions above, so no new Kotlin screens are needed for Gallery / Printers / Downloads / SMB. Add a "Home" row at the top of the drawer and make the Android Back button return to it. (Alternative, more work: a native `HomeActivity`; only if the WebView version feels slow at start.)
2. **Storage Analyzer tile**: disabled, label "Coming soon" (opacity ~0.5, no click). Later: its own screen (user will code it).
3. **When to show**: first screen at a normal launch. NOT when the app was started by a share intent (`handleShare`) - go straight to the send dialog as today. (Question asked, the user has not answered yet: every start, or only fresh launches.) Default if no answer: show it on every fresh launch, never on a share intent; maybe a Settings switch "Start with: Home / last folder".
4. Later polish: live subtitles, long-press to reorder / hide tiles, more tiles (Documents, Apps, New files, Recent, Remote, Trash like File Manager +).

## Checklist after it exists
Launch -> menu shows 5 tiles; each tile opens its screen and Back returns to the menu; share a file into the app -> goes to the send dialog (no menu); rotate / small text size (Settings -> display size) -> tiles still fit (use `UiScale`); dark system theme looks fine.

## Log
- 2026-10-10: design made and approved; Storage Analyzer deferred; this file written. No code changed.
- 2026-10-10 (code): `#home` overlay in `ui.html` (CSS block `start menu`, `homeShow/homeHide/homeOn`, `HM_SVG`). Tiles: Gallery -> `openLoc('local','/Pictures')`, Downloads -> `openLoc('local','/Download')`, Printers -> `showPrinters()`, SMB -> `openSmb()`, Storage Analyzer disabled ("Coming soon", opacity .5). `openLoc` / `openDev` call `homeHide()`. Drawer got a "Start menu" row on top. `lsBack`: home is the bottom of the stack (Back there leaves the app); Back at the root of main storage returns to home. `#home` added to the lists that hide the native list / chrome / search / folder swipe (`nlLay`, search push, `nlChPush`, swipe `blocked`). Shown only on a fresh launch: `MainActivity.showHome` (= `b == null` and the intent has no SEND / VIEW / PRINT action), read by `Bridge.homeStart()`. Three-dot button opens Settings (there is no separate overflow menu). Dark theme uses its own `--hm*` vars. JS checked with `node --check`; the Kotlin change is 3 lines, **not compiled**. Open: the native splash still draws the last folder briefly before the menu; "every start vs fresh launch only" is still unanswered (current: fresh launch only).
