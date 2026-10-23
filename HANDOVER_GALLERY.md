# HANDOVER — "Other phones" gallery

Goal: on the local Gallery / Videos main page a button lists the other phones; opening one shows THAT phone's gallery
exactly like the local one (album cards, covers, counts, grid). Remote view is read-only at first
(view, open, copy to this phone; no move / hide / delete / merge).

## Parts
1. DONE  (delivered as full-project zip pcshare-gallery-part1-full.zip) Phone side + forwarding routes (Routes.kt only)
2. DONE  (delivered as full-project zip pcshare-gallery-part2-full.zip) Gallery main page: "Other phones" button, phone list, remote album cards (ui.html only; the album home is HTML, the native list is skipped for it)
3. DONE  (delivered as full-project zip pcshare-gallery-part3-full.zip) Inside a remote album: photos / videos grid, image + video viewer, copy to this phone, editing actions hidden

## Phase 2: AI search + Print in a remote phone's gallery (started 2026-10-23)
Goal: inside "Other phones" gallery (remote album home and remote album) the user can (a) AI search that phone's pictures, (b) print selected pictures.
4. DONE  (pcshare-gallery-ai-print-part1.zip) Backend only, Routes.kt: peer `imgsearch`, local `/api/rimgs`
5. DONE  (pcshare-gallery-ai-print-part2.zip) ui.html: search button/box in the remote album home + remote album (Visual / Text-in-pictures tabs, Whole word / Contains), result grid like the local Image search view (`isView()`), thumbs via `/api/rthumb`, viewer via `GCX.dev`, same selection bar as part 3; per-album search passes `dir=<album path>`; show the 404 text "Update LANShare on <name>" as a note
6. DONE  (pcshare-gallery-ai-print-part3.zip) ui.html: Print button in the remote selection bar (remote album AND `risView` results; add next to Share): `POST /api/print {dev:<phone id>, paths:[...], to, opts}` already supports remote sources through `Jobs.startPrint`; reuse the local print dialog (printer picker + layout opts + preview); check `PrintPreview` / `PrintPrep` work for a remote dev (they read through `Jobs.ep(dev)`), otherwise copy to a temp then print

### Part 4 (what was added, core/Routes.kt only)
- Peer route (in `peer()`): `GET /p/imgsearch?mode=status|visual|text&q=&whole=0|1&dir=` -> `ImgSearch.status()` / `visual(q, dir=)` / `text(q, whole, dir)`, same JSON as local `POST /api/imgs` (`{q,total,results:[{path,score,...}]}`); paths are virtual (`/DCIM/Camera/x.jpg`) = valid for `/api/rthumb`, `/api/dl?dev=`
- Local UI route (in `api()`): `GET /api/rimgs?dev=<phone id>&mode=status|visual|text&q=&whole=&dir=` -> forwards to that phone (90 s timeout, model load can be slow); older build = 404 text "Update LANShare on <name>: its version cannot search its pictures yet"
- `mode=status` tells the UI whether that phone has the model installed / pictures indexed (`model.installed`, `count`) so the search box can say "Install image search on <name> first" (visual on a phone without the engine answers 400 "image search engine is not installed yet"; text search needs only the index)
- Search runs ON the remote phone (its index, its model); nothing is indexed on this phone
- NOT compiled here (no Gradle in the sandbox): build with the GitHub workflow and check Routes.kt compiles

### Part 5 (what was added, ui.html only)
- `risView(D,dn,sdir,sname)` (before the gallery section) = AI search screen for ANOTHER phone, in the `#gal` overlay: Visual / Text in pictures tabs, Whole word / Contains chips (prefs shared with local `ls_ismode`, `ls_iswhole`), result grid (thumbs `/api/rthumb?dev=&path=`, score badge in visual mode), tap = existing viewer (`GCX={dev:D,...}`), hold = select
- Calls `/api/rimgs?dev=&mode=status|visual|text&q=&whole=&dir=` (helper `R()`); status card says when that phone has no model / no scan yet; old build -> its 404 text is shown
- Selection bar = same as remote album (part 3): To this phone, Copy, Share, Details. No Find similar / Print yet (Print = part 6)
- Entry points: chip "Search" in `galPhoneBar()` (remote Photos home only) = whole phone; search icon in the header of a remote photo album (`galView`, `rem&&!a.v`) = that album (`dir=a.path`)
- Videos are not searched. Scanning / model download is done on the other phone (not triggered from here)
- Not run on a device; JS checked with node --check only

### Part 6 (what was added, ui.html only)
- Print button in the remote album selection bar (`galView`, `rem`, photos only) and in `risView` results: `doPrint(null,{items,dev:D,...})`; `doPrint` already reads `cx.dev` for `/api/dl?dev=` urls, previews and `POST /api/print {dev:D,paths,...}`
- Backend needed no change: `Jobs.startPrint` / `PdfPrint` / `WifiPrint` / `printWork` read the files through `Jobs.ep(srcId)` (a `RemoteFs` for a peer phone), so the pictures are pulled from the peer while printing
- Print history (`phAdd`) stores `dev:D`, so re-print works from the history
- Not run on a device; JS checked with node --check only. Check on device: this-phone printing (`printHereMany` urls), Wi-Fi printer, PC, Save as PDF, sheet layouts / preview with pictures of another phone
- Whole task (AI search + print for remote galleries) = all 3 parts done, untested on device

## Part 1 (what was added, core/Routes.kt)
Peer routes (what other phones call, /p/<route>), in `peer()`:
- `albums?kind=vid|img`  -> `Albums.json(...)` of this phone  ({albums:[{path,name,n,cover,size,mtime}],video})
- `thumb?path=&big=1`    -> JPEG made HERE (Thumbs.make / makeCover); `vthumb?path=` -> video JPEG + X-Duration header
Local UI routes (/api/..), in `api()`, next to "albums":
- `/api/ralbums?dev=<phone id>[&kind=vid]` -> the other phone's albums JSON (404 text "Update LANShare on <name>..." for an older build)
- `/api/rthumb?dev=&path=[&big=1][&video=1]` -> thumbnail of a file on that phone; asks the phone's new route,
  falls back to RemoteThumbs.make (pull whole file) when the phone is an older build
- helper `remotePhone(dev)` (BadReq when dev is not a LANShare phone)
Album paths and cover paths are relative to that phone's storage root (e.g. "/DCIM/Camera", "/DCIM/Camera/x.jpg"),
the same virtual paths the file browser uses for that dev, so /api/ls, /api/dl, /api/file?dev=<id> work on them.
NOT compiled here (no Gradle in the sandbox): build with the GitHub workflow and check Routes.kt compiles.
Both phones need the new build for fast thumbs and for the albums route.

## Part 2 (what was added, ui.html only)
- `galRoot()` is now device independent (path /@gallery or /@videos on ANY dev); `openLoc(dev,'/@gallery')` opens a phone's gallery
- `galFetch()` caches per kind AND dev (`GALPK='img|<dev>'`, `GALA.dev`, `GALE` = last error text); remote -> `/api/ralbums?dev=..[&kind=vid]`
- `mkAlbumR(a)` = remote album card (cover `/api/rthumb?dev=&path=&big=1[&video=1]`, thumb kind 'img' so thMake just loads the JPEG); read-only: no hold menu / hide / merge
  TEMPORARY (part 3): tap on a remote album = `openLoc(dev,a.path)` (normal file-browser folder of that phone)
- `galPhoneBar()` at the top of the album home: chip "Other phones" (local) / "<phone name>" (remote) -> `galPhones()` sheet (This phone + online/offline LANShare phones, `p.kind==='LANShare'`);
  remote also gets a Photos/Videos switch chip. While loading / on error a note is shown instead of albums
- Hidden-albums (`ls_galhid`) are NOT applied to remote albums (per-dev hide not needed while read-only); "Hide small albums" toggle is shared
- Not run on a device (no Gradle / phone here); JS syntax checked with node --check only

## Part 3 (what was added, ui.html only)
- `galView(a)` takes `a.dev` (default local): `D`/`rem` inside; list = /api/ls?dev=D, `GCX.dev=D` so the existing image / video viewers (cxd()) stream /api/dl?dev=D
- remote grid thumbs: `/api/rthumb?dev=&path=[&big=1][&video=1]` (thumb kind 'img'; no duration badge on remote videos)
- remote selection bar: To this phone (album picker of THIS phone -> clip copy from D -> /api/paste dev local), Copy (clipboard), Share, Details. No move / delete / hide / print / paste bar
- `mkAlbumR` tap -> `galView(Object.assign({},a,{dev}))`; `galForceLocal()` drops the local album cache after a copy
- Not run on a device; JS checked with node --check only. Ideas left: duration badge for remote videos, per-dev hidden albums, remote Print

## Hooks for part 2 (kept for reference, now done) (ui.html, line numbers of the pcshare.zip of 2026-10-23, will drift)
- ~1420  `galRoot=()=>S.dev==='local'&&/^\/@(gallery|videos)$/...`  -> allow any LANShare dev (S.dev = phone id)
- ~1741  gallery loader `api('GET','/api/albums'+(gk==='vid'?'?kind=vid':''))`, cache vars GALA/GALT/GALP/GALPK
          -> for a remote dev call `/api/ralbums?dev=`+enc(S.dev)+kind; cache must be keyed per dev
- ~1769  `gm=!AR&&!S.q&&!S.newest&&galRoot()` and ~1824 `if(GALM){...albums...}` render the album cards
- album cover <img> url (find it near ~1824): local uses /api/thumb?dev=local&...&big=1 -> remote: /api/rthumb?dev=..&big=1
- hidden albums are stored per browser (`ls_galhide`): key them per dev too
- ~1135  comment says gallery = albums from MediaStore only (no file-manager listing)
- "Other phones" list: `/api/peers` (kind "LANShare", ok true; skip own id)
- Check whether the NATIVE list (NlModel.kt "gallery split", NlSheets `gal`) also shows the album home; if so hook it there too.

## Hooks for part 3
- Replace the temporary tap in `mkAlbumR` by a remote `galView`-like grid (galView uses /api/ls + /api/dl?dev=local ... : make dev a parameter); `GCX` already carries dev for the viewers
- Opening an album = the normal folder listing of that dev at the album path, shown as the gallery grid (same as local album)
- Viewer: images through /api/dl?dev=<id>&path=..; videos via the existing remote player path
- Hide / move / delete / merge / "move to album" buttons off when S.dev!=='local'; keep "copy to this phone"

## Rules from the user
- Keep shell output short (cut -c1-200); never print the base64 font line of ui.html
- Deliver the FULL app files (whole project folder, all files) as one zip every time, not changed files only; keep this handover inside it and update it
