# Handover chapter file - ch09-printing

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

## 9. Printing and the PC print service

### 9.1 Overview
Three ways to print, all from the same print dialog in `ui.html` (`doPrint`, `printOptions` for PC, `wifiOptions` for Wi-Fi printers):
- **PC** (`pcprint.py`, Windows service on port **8799**, LAN / Tailscale clients only): the phone converts what it can, sends the file with option query parameters; the PC lays it out and prints (SumatraPDF / Office / LibreOffice).
- **Wi-Fi printers** (no PC): found with mDNS / DNS-SD (`_ipp._tcp`, `WifiPrinters.kt`, ids like `wifi:192.168.1.50:631/ipp/print`), printed over IPP (`Ipp.kt`, `WifiPrint.kt`).
- **This phone** through the Android print system (`PhonePrint.kt`: ONE print job for all selected files, PDFs keep their real page count). `PcPrintService` also makes LANShare appear as a printer ("LANShare - print on PC") in Android's own print dialog so "Print" in any app can pick it; the finished PDF is copied to `<storage>/LANShare Shared/`.

### 9.2 `pcprint.py` version history (PC side - run the newest file once, it restarts the old service by itself)
| Version | What |
|---|---|
| v9 | fitting options: `fit=fill`, `margin`, `scale`, `align`, `autorot`; `needs_fitting(o)`, `fit_cell()`, `neutral_fit()`; paper size from `paper` |
| v10 | office files (doc docx rtf odt xls xlsx csv ods ppt pptx odp) converted to PDF on the PC (Microsoft Office via COM, else LibreOffice headless) when layout / fitting options are asked for; `/ping` reports `office` |
| v11 | `POST /convert?name=` (office in, PDF out, nothing printed, one at a time; `/ping` `convert: true`) for real office previews |
| v12 | stability: print worker survives any error, `H.timeout = 60`, queue cap 50 (503), free-disk guard (507), unique upload names, picture batches under a lock (last index waits up to 6 s), hourly `cleanup_old` (3 days + 1 GB), one retry on a failing print command (never on timeout), atomic helper `.ps1`, `printer_info(max_age=6)` cache, `GET /jobs`, `/ping` `busy`, `POST /clear` (localhost); window: Test page, Clear queue, Queue folder, Open log |
| v13 | ignores a repeated `key` (no double prints) |
| v15 | `join=1` + batch fields join several files into ONE job; `skip=1` slot |
| v22 | queue screen: every job has id + name; `GET /jobs` adds `waiting:[{id,name,t,printer,copies}]`, `current_id`, `version`; `POST /cancel?id=N` (any LAN phone) cancels a WAITING job (the printing one answers `result:"printing"`) |
| v26 | text inbox (`/p/clip`, `/clip`, `--text` window), UDP beacon listener 48555 (see 10.1) |
| v28 | `GET /screen?pos=N[&lines=K]` for the PC-screen mirror (9.6); `/ping` `screen: true` |
| v32 | print queue restored (v22 had been lost in v28-v31): each queued job gets `qid` + `qt`; `GET /jobs` has `waiting:[{id,name,t,printer,copies}]`, `current_id`, `version`; `POST /cancel?id=N` -> `{ok, result: ok / printing / gone}` (`cancel_waiting()` removes the item from `PRINTQ.queue` under its mutex and answers the phone's request with an error); `/ping` `cancel: true` |

`/ping` reports capability flags (`office`, `convert`, `clip`, `screen`, `busy`); an older file answers 404 on a newer route and the app shows "Update pcprint.py". The note of the screen-mirror session says the v28 file in that zip had **no `waiting` list / `POST /cancel`** (v22 behaviour) although the app's queue screen expects it - verified and restored in v32 (2026-10-28).

### 9.3 What can be printed, and the layout options
- **Conversion on the phone** (`core/PrintPrep.kt`, called from `Jobs.printWork`): webp / heic / heif / avif / ico / wbmp / dng / jfif -> JPEG (`Thumbs.forPrint`); html / htm / xhtml / svg -> PDF (`android/print/WebPdf.kt`: WebView print adapter, no JS, no network; deliberately in package `android.print` because the callback constructors are package-private); ~100 code / config / text extensions -> `.txt`; **unknown / missing extensions are sniffed** (PDF, RTF, OOXML / OpenDocument zip entries, legacy OLE by stream name, decodable picture, HTML / SVG, UTF-16 / UTF-8 text). Video / audio / archives / binaries (`NO` set) and unknown files > 120 MB are refused up front. Office files need Word / Excel / PowerPoint or LibreOffice on the PC for layout options (no native docx renderer on the phone); without a converter the file is printed by its own app (printer + copies only) plus a note.
- **Options** (dialog `PRN_DEF` -> `Jobs.PRINT_KEYS` -> query string -> `pcprint.py parse_opts`): `fit` (shrink / fit / noscale / `fill` = cover and crop), `margin` (0-72 pt, `''` = old default per kind; sent as strings so `'0'` = "None" survives the drop-empty filter), `scale` (10-500 %, overrides `fit`), `align` (`''` centre / `top` / `topleft`), `autorot`, `paper`, duplex / colour (SumatraPDF). With none of the new options set everything behaves as before (old `fit` goes straight to SumatraPDF). Pictures: Scaling = Fit / Fill + Margins (custom scale, position, turn-pages are document-only). Text files: margin = page margin, custom scale = font size. When a paper size is chosen the finished PDF goes to Sumatra as `noscale`. Printers cannot print the last ~3-5 mm, so margin "None" is clipped by hardware. Not tested on a real printer / SumatraPDF (rendered with pdftoppm only).
- **Trim blank edges** (`fit=trim`, pictures only, 2026-10-10): the preview measures each picture's content box (`pvBBox`: outer lines of one flat colour equal to that edge are dropped, 1.5 % safety margin) and sends `crops` (fractions per file); the phone crops the real file before it goes out (`PrintPrep.cropBox` / `trim`, used in `Jobs` PC printing and `PdfPrint.load`; `PhonePrint.prepare`; `WifiPrint.work` / `mergeImages`), then the normal fit logic applies; `pcprint.py` needs no change (unknown `fit` = shrink).
- **One job, no doubles**: `Jobs.printWork` sends `join=1` + `batch`, `idx`, `n` when *Print all files as one job* is on (mixed files, not picture-sheet mode); a file that cannot be sent gets `skip=1` (`skipBatchSlot`); every file carries `key=k<batch>-<index>`. Tested against `pcprint.py` on Linux with a stub.

### 9.4 Previews (`core/PrintPreview.kt`, 2026-10-04)
The dialog's layout preview shows the real pages. Routes `GET /api/pvinfo?dev&path&to`, `/api/pvpage?id&n&w` (JPEG of one page, `PdfRenderer`), `/api/pvfile?id` (converted picture / text). PDF rendered natively; html / svg / unknown / webp-heic-avif converted by `PrintPrep` first; **office files converted by the PC** (`POST /convert`); text / code files drawn in the UI with the same wrapping as `txt_to_pdf` (page / line counts checked identical). Cache `cache/pv/<id>/` (6 documents, wiped at start). **Preview must stay identical to the PC side**: `pvFitK()` <-> `fit_cell()`, `pvSheets()` <-> `images_to_sheets()` (equal on 36 scale cases and 5 picture layouts). The rotate button right of the preview caption (`pvRotBtn`, `pvTurn`) turns the preview 90 deg clockwise - view only, not saved.
First check after a build: `WebPdf.kt` compiles and an `.html` and an `.svg` print.

### 9.5 Wi-Fi printers (IPP)
`Ipp.printerAttrs` asks for `pwg-raster-document-resolution-supported` / `-sheet-back` (`WifiPrinters.P.rasterDpi`, `.sheetBack`). Printers without PDF but with `image/pwg-raster` (e.g. Brother DCP-T830DW) get ALL pages as ONE raster job (`WifiPrint.pwgRaster` / `writeRasterPixels`) so two-sided printing works (before, the per-page JPEG fallback made every page its own sheet). Landscape pages (2-up) are turned 90 deg clockwise; back sides use the printer's sheet-back transform. **Not tested on the printer**: check with 4 numbered pages; if backs come out upside down flip the sheet-back mapping in `pwgRaster`. Printer list is `GET /api/wifiprinters` (50-300 ms scan; `WifiPrinters.start` runs at startup, 13).

### 9.6 Print queue screen and PC screen mirror
- **Print queue** (`pcprint.py` 22+): app `Jobs.pcQueue(dev)` / `pcCancel(dev, id)`, routes `GET /api/pcqueue?dev=` and `POST /api/pccancel?dev=&id=`; `openPrintQueue(pc)` (own `#pq` overlay): Printing now / Waiting (Cancel, second tap within 3 s confirms) / Recent (last 15, tick or cross + reason), refresh every 2 s; entry points: **Print queue ›** link in the PC print dialog and Settings > **Print queue** (last PC used). Queue logic tested on Linux with a stubbed print; Kotlin / Windows not run.
- **PC screen** (button next to Print queue, `pcprint.py` v28; app side **written, not compiled**): `GET /screen?pos=N[&lines=K]` -> `{ok, version, host, pos, reset, lines:[{t,k}], status:{dot green|amber, text, printer, problem, busy, queued}}`; `k` = `ok` (green "printed") / `fail` (red: FAILED / WARNING / CRASH / ERROR / cannot listen) / `dim` (grey: ready / stopped) / `""`. First call (`pos` missing / -1): last 150 lines + `reset:true`; later calls: only new complete lines; log rotation (500 KB) or a viewer > 256 KB behind = `reset:true` + tail. Stopped service = nothing answers ("PC not reachable"); old file = 404 ("update pcprint.py (needs version 28 or newer)"). Window and endpoint share `log_tag()` / `status_parts()` / `ips_label()`. Privacy: the log has file names and IPs - visible to anything that can reach port 8799 (as `/jobs`); read-only. App: `Jobs.pcScreen(dev, pos)` (404 -> `{ok:true, old:true}`), route `"pcscreen"` in `Routes.kt` (not in `InProc.ROUTES`), `openPcScreen(pc)` in ui.html (own `#pcs` overlay reusing the `#pq` look, polls 1 s, follows the newest line only while scrolled to the bottom, keeps 1000 lines; CSS `.pcb .pcst .pcd .pcl .pcln`). Not run: the Tk window after the refactor (`py_compile` only).
- Checklist: copy `pcprint.py` v28, run it; app -> print dialog -> **PC screen ›**: last ~150 lines at once, status line matches the PC window; print something -> new lines within ~1 s ("printed" green, failures red; scroll up = view stops jumping); stop the service -> "PC not reachable" after ~2 s, start again -> recovers; old pcprint -> "Update pcprint.py"; close the sheet -> polling stops.
- Ideas: SSE / long poll instead of 1 s polling; "Copy log"; "Clear queue" in the app (`POST /clear` is localhost-only).

### 9.7 Print space (drawer > Print space)

**Status (2026-10-23).** All parts written. Kotlin of Print space was never confirmed on a device in these notes (CI builds the app; check the Status board). JVM / node / Chromium tests exist for the maths and UI helpers (list at the end). Detailed per-part history (risk spots, per-part test lists, old plans) was removed from this file on 2026-10-23 - it is in the zips delivered before `pcshare-handover-part1.zip` (and in git).

**Goal.** Send pictures (and PDF pages) from the gallery / file browser / viewer / share sheet into a holding area, lay them out on one or both sides of sheets (grid or free placement), preview with the real renderer, print to PC, Wi-Fi IPP or this phone.

**Design decisions (still valid)**
- **Copies, not links.** Pictures are copied to `<root>/.LANShare Print/<id>/<name>` (`core/PrintSpace.kt`, manifest `items.json`, `.nomedia`). Cap 500 pictures, 99 copies each. Originals can change or vanish without affecting a pending print.
- **Reuse the print pipeline.** Stored files are normal local virtual paths; `doPrint(preset, cx)` and `printOptions` work on them. The layout editor renders ONE PDF and hands it to `doPrint`.
- **Screen = `#gal` overlay** (like image / video viewers), so it must stay in the `nlLay` holes and the lists that hide native list / chrome / search / swipe (6.1). One drawer `dItem` above *Recycle bin* opens it (`openPrintSpace()`); kill switch `localStorage ls_ps='0'` stops every way in (drawer, `psOK()`, `openPrintSpace()`, `psAdd()`); the native viewer / share entries have no switch.
- **Server is the source of truth for validity**, the page for geometry during a gesture (local copy, redraw of the touched element, ONE request when the finger lifts).
- **Both sides.** Each sheet has front and back lists; back slots are stored as READ; `backSlot` / `behind[]` gives the slot physically behind a front slot (flip long / short). Printers without duplex: print fronts, prompt, print backs.

**Source map**

| Area | Files |
|---|---|
| Store, undo, settings, PDF intake | `core/PrintSpace.kt` (`add`, `addStream`, `addPdf`, `adopt`, `park` / undo, `cfg`), `GET/POST /api/ps` |
| Layout model (pure Kotlin, no `android.*`) | `core/SheetLayout.kt` |
| Wire format | `core/PsLayoutJson.kt` (org.json only), `core/PsLayoutApi.kt` (android.*, `POST /api/pslayout`) |
| Renderer | `core/PsRenderGeom.kt` (pure), `core/PrintSpaceRender.kt` (`POST /api/psrender`, `GET /api/pspage`) |
| Recent prints (sessions) | `core/PrintSessions.kt`, `GET/POST /api/pssess`, `GET /api/pssprev` |
| Printing paths | `ui.html` `doPrint` / `printOptions` / `wifiOptions`, `PhonePrint.kt`, `pcprint.py` |
| UI | `ui.html`: `openPrintSpace()`, `psLayout()` (editor), `dFree()` (free stage), `psSettings()`, `printRecentPs()` |

#### Store and API (`/api/ps`)
- `GET` -> `{items:[{id,name,size,from,at,copies,rot,path}], count, bytes, max, undo?}`. `POST` ops: `add {dev,paths}` (-> `{added, skipped:[{name,why}]}`), `rm {ids}`, `clear`, `move {id,to}`, `set {id | ids, copies?, rot?, rotBy?}`, `undo`, `cfg`. **Route rule:** a route name must branch on `ex.method` (a missing guard once made every POST just return the list).
- **Intake**: gallery hold bar and file-browser hold menu *To Print space* (`psPic` / `psOK` / `psAdd`), image viewer *More > To Print space*, share-sheet alias `.PrintSpaceShareAlias` ("LANShare Print space", `image/*` and PDF, `PrintSpace.addStream`), pictures from other devices (`dev != local`: streamed from `Jobs.ep(dev)`, max 60 MB each, `from = "<dev>:<path>"`). Press and hold starts selecting, a tap opens the picture (tap ticks once a selection exists). Selection order = TAP order (`gi()` / `selItems()`), cells show the tap number (`gNum`).
- **PDF pages**: each page becomes an ordinary JPEG item `"<name> p<N>.jpg"` (`PdfRenderer`, 200 dpi, long side <= 3000 px, `MAX_PDF_PAGES` 100). One page = one picture, so copies / turn / thumbnails / undo / layout all work unchanged.
- **Undo for Remove / Remove all**: folders are MOVED to `.undo/<id>/` (`park()`), `undoList` is memory only, one step; the screen shows *N pictures removed* + Undo for 10 s.
- **Multi-select bar**: Print / Copies (1-99) / Rotate (90 from each picture's own rotation) / Remove. Tiles show the rotation by CSS.
- **Settings card** (`psSettings()`): *Remove pictures after* Never / 7 / 30 / 90 days, *Storage limit* none / 200 MB / 1 GB / 5 GB (`settings.json`, `sweep()` from `load()` / `json()`), *Recent prints kept* 5 / 12 / 25 / 50 and *size* 200 MB / 500 MB / 2 GB / none. The sweep can remove pictures a saved layout still names: the editor then shows empty slots.

#### Layout model (`SheetLayout.kt`, units = PDF points, origin top-left)
- **Spec**: `paper` (A3 / A4 / A5 / Letter / Legal, unknown = A4), `orient` AUTO / PORTRAIT / LANDSCAPE, `cols x rows` (presets 1x1, 1x2, 2x2, 2x3, 3x3, custom up to 8 per axis), `margin` (14 pt, 0-72), `gap` (8, 0-40), `fit` FIT / FILL, `flip` long / short, `autoRot`, `reverse`, plus `free`, `safe` (14, 0-72), `keep` (true), `keepGap` (6, 0-40) for free placement. Every field has a default and `clean()` clamps, so named-argument `Spec(...)` calls keep compiling.
- **Pic** = `{w, h}` as the picture LOOKS (EXIF applied) + `rot` + `copies`. **Plan** = `{portrait, sheets}`, `Sheet` = `front` / `back` slot lists (picture index or null) + `ff` / `fb` free lists.
- `autoFill(spec, pics, mode)`: FRONT_ONLY / BOTH_SIDES / PAIRS (0+1, 2+3 ...; copies of the front repeat the pair). AUTO orientation uses the same score rule as `pvSheets`. Editing ops are immutable: `assign`, `swap`, `placeBehind`, `addSheet`, `trim`, `reflow`, `removeSheet`, `slotBoxes`.
- `compose(spec, pics, plan)` -> `Layout{portrait, sw, sh, grid, pages, score}`; pages in PRINT order (sheet 0 front, sheet 0 back ...); back pages exist for EVERY sheet as soon as one sheet has a back picture.
- **Parity**: single-sided FIT / FILL equals `pvSheets()` (ui.html) and `images_to_sheets()` (`pcprint.py`) - `tools/sheet_layout_mirror_test.py`.

#### Wire format (`PsLayoutJson`, `POST /api/pslayout`)
- Request `{op, spec{...}, plan{portrait, sheets:[{f:[id|null], b:[id|null], ff?, fb?}]}, ...}`; the UI keeps spec + plan and sends both with every call. Answer `{ok, spec(cleaned), plan, layout{portrait, sw, sh, cols, rows, slots[{x,y,w,h}], behind[], pages[{sheet, side, cells[{slot,id,rot,dw,dh}], free[{id,cx,cy,dw,dh,rot}]}]}, placed{id:n}, sheets}`. A cell = picture drawn `dw x dh` pt UNTURNED, turned by `rot` about the slot centre, clipped to the slot box. Free cells: same but centred on `cx, cy`, no clip.
- Ops: `view`, `fill {mode}`, `assign`, `swap`, `behind`, `addsheet`, `rmsheet`, `trim`, `tofree`, `togrid`, `fset {sheet, side, items:[{id,cx,cy,w,rot,st?}]}`, `fadd {sheet, side, id, cx?, cy?}`. Request preamble (clean spec, picture list, plan or front fill) lives in `base()`; `resolve()` = same without the op (used by the renderer).
- `PsLayoutApi` reads sizes with `BitmapFactory` bounds + `ExifInterface` (cached per id; unreadable header = 4000 x 3000).

#### Layout editor (`psLayout()` in ui.html)
- **Entry**: *Layout* button in the Print space header (hidden while empty). State inside `psLayout`: `SP` spec, `PL` plan, `LY` layout, `PICS`, `PLACED`, `MODE`, `sheet`, `side`, `selPic`, `selSlot`; saved as `ls_ps_lay = {spec, plan, mode}` after every answer (a layout the server refuses is dropped).
- **Calls** are serialized (`call()` chain); the cleaned spec is adopted only when no newer call waits (quick +/- taps add up); a refused setting toasts and keeps the old one.
- **Settings card** (tune button): paper, per sheet 1 / 2 / 4 / 6 / 9 / Custom, orientation, fit / fill, flip, margin, gap, turn to fit, order, Auto-fill (front only / both sides / pairs), Remove empty sheets, Placement Grid | Free, saved layouts. Sheet strip with +/delete, Front / Back tabs, tray of pictures.
- **Grid gestures**: tap picture then slot = assign; tap filled slot then another = swap; hold 380 ms and drag tray -> slot (assign), slot -> slot (swap), slot -> tray (clear). Selection bar: copies +/-, Turn, Clear, *Back side*.
- **Preview / Print**: header buttons. `prep()` runs after every layout answer (350 ms debounce) and calls `POST /api/psrender {op:"prepare"}`; Preview shows `/api/pspage` JPEGs with a page strip; Print (`printNow()`) waits for queued calls, warns about pictures not on a sheet, then renders the PDF (`op:"pdf"` -> job -> normal progress bubble, Cancel works).
- **Rotate view** (`vr`, `vrBtn`, `vrWrap`): view-only turn of the big page.
- **Saved layouts**: *+ Save this layout* (name <= 24 chars, max 20, `localStorage` `ls_ps_presets`) keeps only `PSP_KEYS` (spec fields) + auto-fill mode; older presets without a new key keep the current value. Free placement is kept in presets too (item templates `tpl`, 12a).

#### Renderer (`PrintSpaceRender`, `PsRenderGeom`)
- Draws a layout exactly as the editor shows it: slot box clipped, turned about its centre, unturned picture `dw x dh`; free items after the cells, no slot clip. One picture decoded at a time (`needPx` / `sample`, power-of-two `inSampleSize`, <= 3000 px); default 200 dpi (96-300), `autoDpi` lowers it when the raw size estimate passes 400 MB.
- `prepare` -> `{ok, id, pages, sheets, sw, sh, duplex, list:[{sheet,side}]}` (id = hash of spec + composed layout; 12 prepared layouts in memory, LRU). `pspage?id&n&w` -> JPEG q85, `n` in print order, `w` 120-1100, two at a time. `pdf` -> `{job}`; one PDF, one page per layout page; one render at a time; request fields `only: front|back` and `reverse: true` for manual duplex; the renderer keeps only the newest 4 PDFs.
- Free items are clipped to the safe rectangle as a last resort (plans that never went through `setFree`).

#### Printing paths
- **Sides preset**: `doPrint(preset, cx)` reads `cx.opts` and returns the printer used; `printOptions` / `wifiOptions` start from `pre` (duplex = flip edge or `off`). PC: `pcprint.py` v28 (`duplex` off / long / short, `duplex_swap.txt`). Wi-Fi IPP: `sides=two-sided-long-edge` / `-short-edge`, raster `sheet-back` flips. This phone: `printHereMany` gets `duplex` -> `PrintAttributes.Builder.setDuplexMode`.
- **Manual two-sided** (printer without duplex): Print asks *Does the printer print on both sides by itself?*; "No" renders `front` then `back` (dialog in between, *Last sheet first* = `reverse`).
- **Printer margins**: Wi-Fi IPP asks `media-*-margin-supported` (`Ipp.printerAttrs`, `WifiPrinters.marginsOf` = per edge the smallest NON-ZERO value); chip *From printer...* in the safe-margin chips uses `/api/printer`; PC side already reported `hard`.

#### Free placement (spec.free)
- **Model**: `Free(pic, cx, cy, w, rot, st)` = fractions of the sheet (centre, width of the UNTURNED picture) + clockwise degrees + stretch `st` (see below); list order = stacking order; `Sheet.ff` / `fb`; the picture's own turn adds to the item's turn. `fset` replaces one side; `fadd` places a new item (server picks size / spot); `tofree` / `togrid` switch mode (*Auto-arrange* = fill then `tofree`).
- **Stage** (`dFree()`, `.lyfp`, items `.lyf`): thumbnails with CSS transforms (percent sizes: `w`, `w*a*sw/sh`), local copy `FRL` / `FEL`, one `fset` on pointer up (`fCommit`). Gesture modes: `m` move, `c` corner dot, `s` side dot, `r` rotate knob, `p` pinch, `bg`, `x` (ignore until all fingers up). Snapping: centre lines 1.2 %, angle 15 degrees within 3.
- **Handles on the selected picture**: four corner dots (resize with the OPPOSITE corner fixed, shape kept, grab offset kept), two **side dots** (mid left / right, pill shaped), and a round rotate knob (top, else bottom / left / right whichever stays on the page; left / right sit 46 px out so they clear the side dots).
- **Side dots (2026-10-23)**: `fSide` keeps the opposite side and the height, changes only the width. Stored as `st` = horizontal stretch (1 = own proportions, 0.2-5); `a` (height / width) already includes it (the server `dh / dw` carries it), so every helper that reads `f.a` keeps working. After a side drag `st = st0 * a0 / a`. Corner drag, pinch and `fClamp` shrink scale uniformly (stretch kept). `st` is sent in every `fset` / snapshot / template and read by `PsLayoutJson` (written only when != 1); Kotlin uses `stOf(f)` in `freeCell`, `freeBox`, `freePoly`, `freeHit`, `clampFree`. Renderers draw `dw x dh` separately, so no renderer change.
- **Control bar** (single picture; the owner removed several buttons on 2026-10-23 - see `dCtl` for the current set): Undo / Redo (stacks `FU` / `FR`, 30 deep, `fsnap`, `fpush`, `fundo`), Multi, Fit page, Fit width, Delete; helpers still in code: Align, Spread, numeric *Rot / size...* (`15, 60`), Center, To front, Copy. Front **ghost** on the Back tab (22 %, dashed, mirrored about the flip edge, not touchable).
- **Printer safe margin** (`spec.safe`): the TURNED box of every picture stays inside paper minus `safe` on all four sides (`fSafeR`, `fClamp`: shrink first with shape kept, then move; idempotent). Chips Off / 3 / 5 / 6 / 10 mm (0 / 9 / 14 / 18 / 28 pt) + current + *From printer...*. Red dashed frame + hatched strip (`fFrame`). Loaded items are pulled in with ONE quiet `fset` (not an undo step, max 2 in a row, `fixN`). Server enforces the same maths (`SheetLayout.clampFree` in `setFree` / `addFree` / `gridToFree`).
- **Keep apart** (`spec.keep`, default ON, `keepGap` chips 0 / 2 / 4 / 8 mm): exact oriented-box overlap test (`fHit`, separating axis, gap grown); move / corner / side / rotate / pinch stop at the neighbour (path sampled every ~2 px, `fStop` / `fMoveTo` slide along); neighbours that ALREADY overlapped never block (`kI`); buttons refuse with the toast "Pictures cannot overlap (Keep apart is on)" (`fkRevert`); tray add finds a free spot (`fSpot`, server `freeSpot`; none = "No room on this side for another picture"); turning the setting on offers *Spread them* (`fPack`, shelf packing, one common shrink factor); overlapping pictures get a red outline while the setting is on. Server: `setFree` refuses a NEW overlap.
- **Behaviour to know**: pictures cannot leave the paper in free mode even with margin *Off*; turning is only by the knob (the old corner dot did resize + turn).

#### Recent prints keep everything (sessions)
- A *session* = snapshot folder `<root>/.LANShare Print/.sessions/<sid>/` with `session.json` (spec, plan, mode, only, rev, printer `to` / `toName` / `opts`, names, pictures, pages, `hasPdf`, `bytes`, `ok`), `pics/<picid>/<name>` and the rendered PDF + page previews. Saved by `phAdd` -> `phSave` right after the render (fire and forget; `{sid, pending}` answer, built in a daemon thread in `<sid>.tmp`); `phMark` updates `ok`. `sid` validated `[0-9a-z]{6,24}`; a plan without pictures is refused.
- `GET /api/pssess` (list), `GET /api/pssprev?sid&n`, `POST /api/pssess {op: save | get | restore | pdf | mark | rm | clear | cfg}`. **Restore** puts pictures back under their OLD ids (`PrintSpace.adopt`) so the stored plan stays valid.
- UI `printRecentPs()`: rows with up to 3 lazy previews, title, printer / date / pictures / pages / size / two-sided, **Edit** (restore -> `ls_ps_lay`, printer + options restored, `openPrintSpace(); psLayout()`), **Print again** (only with `hasPdf`; missing PDF falls back to Edit), cross (second tap within 3 s). Old builds / errors fall back to `printRecent(true, true)` (history entries with `h.ps`).
- Not done: sessions for ordinary prints, de-duplication of copies between sessions (cap bounds the space), Chromium test of the list DOM.

#### Not done on purpose
Crop, per-slot rotation, snapping to neighbours, black-and-white / crop marks / headers, vector PDF pages (raster only), built-in layouts, history for *This phone*, a printer "test sheet" for duplex stacking, zoom / pan in Preview, JPEG pass-through in the PDF.

#### Device checklist (Print space, all open; stop at the first failure)
1. Drawer > Print space; add from gallery, file browser, viewer, share sheet (image and PDF); tap opens the picture, press and hold selects; remove + Undo; Copies / Rotate on a selection.
2. Layout 2 x 2 A4: auto-fill (front / both sides / pairs), drag / swap, Preview pages match the editor, Print to PC, Wi-Fi printer and this phone; two-sided preset and manual duplex (front, dialog, back, *Last sheet first*).
3. Free placement: add by tap and drag from the tray; move, pinch, four corner dots (opposite corner stays), two side dots (height unchanged, stops at frame / neighbours), rotate knob (snaps 15 degrees), Undo / Redo, Fit page / Fit width, Back tab ghost; margin chip 10 mm / Off; Keep apart stops at neighbours, *Spread them* offers on overlap; Preview and Print show stretched pictures.
4. Save layout and use it; Recent prints > Edit restores pictures, spec and printer; Print again; Select several prints and Remove; settings card limits (remove after N days, storage limit).
5. First compile / device run of everything marked "not compiled" in section 2: paste the first compiler error.

#### Tests
`SheetLayout.kt` + `tools/sheetlayout_selftest/Main.kt` and `tools/psrender_geom_selftest/Main.kt` (kotlinc, JVM, ALL OK); `tools/sheet_layout_mirror_test.py`, `tools/psclamp_14c_mirror_test.py` (Kotlin maths vs the real ui.html helpers, 19,968 checks); `node tools/psfree_12b_test.js`, `psfree_12c_test.js`, `psfree_14a_test.js` (100,023 checks), `psfree_14b_test.js` (4,356 checks), `psess_13c_test.js`; `tools/pslayout_ui_smoke_test.js` (79 checks, jsdom / Chromium), `tools/psscreen_ui_smoke_chromium.py` (14 checks). No automated test yet for the free-stage gestures (incl. side dots) or the Recent prints list DOM; `PsLayoutJson` has no own JVM cases.

---
