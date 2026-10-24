# LANShare - Office fidelity expansion: handover and part plan

Written 2026-10-24 against `pcshare.zip` (VERSION 2, HANDOVER.md of 2026-10-23). Temporary file: at Part 13 merge it into HANDOVER.md chapter 8 (as 8.9), add one line to section 14, delete this file.

## 0. Read this first (every session)

1. **Do your ONE part only.** Parts are sized for one free Claude account. Never start the next part in the same session. If the limit hits early, stop at the last checkpoint (each part lists them), update section 8 (status log) and zip.
2. **Token economy** (owner rule, HANDOVER.md top): never `cat` big files. Use `grep -n` and `sed -n 'A,Bp'`. Do NOT read HANDOVER.md whole (161 KB, about 40k tokens). This file plus the line ranges named in your part are enough.
3. **Delivery (owner rule, 2026-10-24): every part that contains code is ALWAYS delivered as the FULL app zip, with ALL app files.**
   - Zip the complete project, never only the changed files, even for a one-line fix. Include every source file, asset (fonts, `ui.html`), resource, Gradle file, `.github/workflows`, `tools/`, `pcprint.py`, `VERSION`, `HANDOVER.md` and this file, with the changed files already updated inside.
   - Name: `pcshare-office-partN.zip` (add a letter for a re-delivery, e.g. `partNb`). Root folder: `pcshare/`. Exclude only `build/`, `.gradle/` and `.git`.
   - **Handovers always go INSIDE the zip (owner rule, 2026-10-24).** Every session updates `HANDOVER_OFFICE_FIDELITY.md` (section 8 status row + notes) and, when code changed, adds its line to `HANDOVER.md` section 14, BEFORE zipping. Both files must be inside the zip at `pcshare/HANDOVER_OFFICE_FIDELITY.md` and `pcshare/HANDOVER.md` with the updated text; also hand over the updated `HANDOVER_OFFICE_FIDELITY.md` as a separate file for easy reading. Verify with `unzip -p <zip> pcshare/HANDOVER_OFFICE_FIDELITY.md | grep -n "| N |"` that the new status row is in the zip copy.
   - Before handing over, verify the zip: list it, check the file count is not lower than the zip you received (plus any new files) and that the changed files are inside. Say the file count and size in the reply.
   - This overrides any older "zip only changed files" habit. If a session cannot produce the full zip (e.g. the upload is missing), say so instead of sending a partial one.
4. **Honesty flags:** say what was compiled / run and what was not. The sandbox has no Android SDK; `core/office/*` is JVM-compilable (`kotlinc 2.0.21`, see HANDOVER.md 8.2 and `tools/office_selftest/`). Keep new parsing code free of `android.*` imports so it stays testable.
5. Kotlin gotchas that already bit this project: see HANDOVER.md section 4 "Gotcha list" (`grep -n "Gotcha list" HANDOVER.md`, read that one line).

## 1. Goal and what was decided

Make the Office viewer look like Microsoft's app. The owner approved ALL six recommendations. Recommendation order from the analysis: #1 + #3 first; #2 alone looks worse than today if fonts differ, so it comes after fonts.

| # | Recommendation | Parts |
|---|---|---|
| 1 | Real pages through the paired PC (`pcprint.py` v11 `/convert` -> PDF -> native PDF viewer) | 1, 2 |
| 3 | Bundle metric-compatible fonts (Carlito, Caladea, Liberation) | 3 |
| 2 | Print-layout mode on the phone (offline) | 4, 5 |
| 4 | Word content fidelity | 6, 7, 8 |
| 5 | Excel fidelity | 9, 10, 11 |
| 6 | Navigation (thumbnails, outline, sheet list, formula bar) | 12 |
| - | Integration, settings, docs | 13 |

## 2. What the code does today (verified in the zip)

- Word / Excel path: `core/office/DocxReader.kt` (22 KB) -> `DocxModel.kt` (88 lines) -> `DocxHtml.kt` (13.6 KB, CSS font stack `Calibri,Carlito,Arial,sans-serif`) -> HTML in a WebView inside `OfficeViewerActivity.kt` (23 KB, 363 lines). Excel: `XlsxReader.kt` (12 KB) -> `XlsxModel.kt` (70 lines) -> `XlsxHtml.kt` (124 lines). Also `.doc` `.xls` `.odt` `.ods` `.pptx` `.odp` readers.
- The viewer WebView has JS disabled, file access off, only `lsoffice://` navigation. Reading mode (`DocxReading`) is the current phone layout.
- Hand-off: ui.html -> `LSAndroid.viewOffice` -> `MainActivity.viewOffice(json)` -> downloads to `cache/office/` -> `OfficeViewerActivity`.
- PC conversion client already exists: `core/Jobs.kt` lines ~150-200: `converterIp()` (pings port 8799 `/ping`, needs `convert:true` and a non-null `office` engine) and `officeToPdf(ip, file, name, out)` (POST `/convert?name=`, 180 s timeout). Today only printing uses it (`PrintPreview.kt`). `pcprint.py` `convert()` at about line 2157, LibreOffice call at about line 973.
- PDF viewer: `PdfViewerActivity.kt` (64 KB, 1293 lines) on `PdfRenderer`, has page overview grid, page text (OCR), print, save pages.
- Fonts: `assets/fonts/` holds 51 files; Calibri / Cambria equivalents are NOT among them. `tnr-regular.ttf` / `tnr-bold.ttf` exist (check what they really are before reusing, `fc-scan` or `strings`). `Fnt.kt` only loads UI fonts for native views, not for the WebView.

### Known issue that blocks Part 2
Owner note (Oct 5): the PDF viewer showed only page 1, the rest white. Reading the code, these are the likely causes (hypotheses, not confirmed on a device):
- `request()` swallows render exceptions (`catch (_: Exception) { }`) and then adds the page to `failedPages`, which is never cleared: one failed render = that page is white for the whole session.
- In the pool task, if the page left `bound` / `ahead` before it ran, nothing is drawn and nothing is re-requested until the next scroll-idle `refreshVisible()`.
- Odd page boxes (Office / LibreOffice PDFs: rotated pages, huge MediaBox) can give a bad `renderSize`.
`renderPage` itself is correctly locked (`synchronized(rlock)`, one page open at a time), so that is probably NOT the cause.

## 3. Part plan (summary)

Budget rule per part: read at most about 25 KB of source (about 7k tokens), write at most about 20 KB of new code, touch at most 4 files. Estimates only; free-account limits vary and are not published as fixed numbers.

| Part | Content | Main files | Depends on |
|---|---|---|---|
| 1 | PDF viewer: white-pages diagnosis + fix | `PdfViewerActivity.kt` | - |
| 2 | Exact pages via PC: convert, cache, open in PDF viewer, fallback to HTML | `OfficeViewerActivity.kt`, `Jobs.kt`, `MainActivity.viewOffice`, `Cfg` | 1 |
| 3 | Bundled fonts + font map + WebView font serving | `assets/fonts/`, new `core/office/FontMap.kt`, `DocxHtml` / `XlsxHtml` CSS, `OfficeViewerActivity` | - |
| 4 | Word page model: sections, page size, margins, columns, per-section header / footer, first page, page-number fields | `DocxModel.kt`, `DocxReader.kt` | - |
| 5 | Print-layout renderer (spike first, then build) | `DocxHtml.kt`, `OfficeViewerActivity.kt` | 3, 4 |
| 6 | Floating / anchored images, text boxes, basic shapes | `DocxReader.kt`, `DocxModel.kt`, `DocxHtml.kt` | 4 |
| 7 | Table styles + per-cell borders, tab stops + leaders, theme colours + theme fonts (new `ThemeColors.kt`, shared with Excel) | `DocxReader.kt`, `DocxHtml.kt`, new `core/office/Theme.kt` | 6 |
| 8 | Comments (side bubbles), tracked changes markup, TOC + field results | `DocxReader.kt`, `DocxHtml.kt`, viewer menu | 7 |
| 9 | Excel A: theme colours, conditional formatting (colour scales, data bars, cell rules), cell comments, gridline toggle, digit-width column widths | `XlsxReader.kt`, `XlsxModel.kt`, `XlsxHtml.kt` | 7 (Theme.kt) |
| 10 | Excel B: charts, part 1 (parse chart XML; column / bar / line to inline SVG, title, axes, legend) | new `core/office/ChartModel.kt`, `ChartSvg.kt`, `XlsxReader.kt` | 9 |
| 11 | Excel C: charts part 2 (pie, area, scatter) + sheet images + simple shapes | `ChartSvg.kt`, `XlsxReader.kt`, `XlsxHtml.kt` | 10 |
| 12 | Navigation: Word outline panel + page thumbnail strip (print-layout mode), Excel sheet list + formula bar | `OfficeViewerActivity.kt`, `XlsxHtml.kt` | 5, 9 |
| 13 | Integration: view-mode setting (Auto / Exact pages / Simple), regression run of all fixtures, docs merge, cleanup | viewer, `Cfg`, HANDOVER.md | all |

**Order and parallelism.** Default is strictly sequential, because every session delivers the whole app zip and two accounts working from the same zip would overwrite each other. Parallel lanes are only safe with git branches / PRs (the repo and CI exist): Lane W = parts 4-8, Lane X = parts 9-11 (they touch different files; Part 9 needs `Theme.kt` from Part 7, so start Lane X after Part 7 or let Part 9 create `Theme.kt` and have Part 7 reuse it).

**Suggested weekends**
- Weekend 1: parts 1, 2, 3 (biggest visible jump: exact pages + matching fonts).
- Weekend 2: parts 4, 5 (+ 9 if you use a second lane).
- Weekend 3: parts 6, 7, 8.
- Weekend 4: parts 10, 11, 12, 13.

## 4. Part briefs

Each brief: goal, read, do, checkpoints, done when, device test. "Read" lists the only ranges to open.

### Part 1 - PDF white pages
- **Read:** `PdfViewerActivity.kt` lines 333-480 (`begin`, `pull`, `openDoc`), 594-676 (`renderSize`, `request`, `renderPage`).
- **Do:** (a) log the real exception of a failed render (Toast in debug builds + `Log.w`, and a counter in the page-text dialog title is NOT needed); (b) stop making `failedPages` permanent: retry once at half size, then allow a retry after 3 s or on tap ("Tap to retry" label drawn on the white page); (c) when the pool task finds the page no longer bound, do not leave `inflight` set: re-request if the page is bound again; (d) clamp `renderSize` for pages with zero / huge / rotated sizes; (e) fix whatever the log shows if the owner pastes one.
- **Checkpoints:** a = logging + retry, b = size clamp.
- **Done when:** a 20+ page PDF made by Word and one by LibreOffice both show every page while fast-scrolling with the scroller, and the overview grid shows all thumbnails.
- **Device test:** open the two PDFs; fling to the end and back; rotate the phone; open a 100-page PDF.

### Part 2 - Exact pages through the PC
- **Read:** `OfficeViewerActivity.kt` (whole, 23 KB; the part that matters is `onCreate` and the menu), `Jobs.kt` 150-200, `MainActivity.kt`: `grep -n "viewOffice" MainActivity.kt` then about 40 lines around it, `PdfViewerActivity.kt` `begin()` for the intent extras it accepts (`sed -n '333,360p'`).
- **Do:**
  1. New `core/office/OfficeExactCache.kt` (or inside `OfficeViewerActivity`): cache dir `cache/office-pdf/`, key = path + size + mtime, 12 h expiry, 300 MB cap, oldest first (same pattern as `cache/pdfc`).
  2. On open of any office extension: if setting is Auto or Exact, run `Jobs.converterIp()` on a background thread (it already pings in about 3.5 s), then `officeToPdf`. Show the existing progress UI with the text "Preparing exact pages on <PC name>...". On success start `PdfViewerActivity` with the cached PDF (title = original file name) and finish the office viewer. On any failure, fall back to the current HTML view and show ONE short toast with the real reason (`converterIp()` already returns readable messages).
  3. Menu items: "Show exact pages (PC)" in the HTML view; in the PDF view opened this way, add "Simple view" that reopens the HTML view of the same file with a flag that disables auto-conversion.
  4. Editing stays in the HTML view. After an edit the cached PDF is invalid (key changes with mtime, so this is automatic).
  5. `Cfg` key `office_view` = `auto` | `exact` | `simple` (default `auto`). UI for it comes in Part 13; for now a hidden default is fine.
- **Checkpoints:** a = cache + convert call works from a debug entry, b = auto-open + fallback, c = menu items.
- **Done when:** with `pcprint.py` v11+ running, opening a .docx shows real pages in the PDF viewer; with the PC off, the HTML view opens within about 4 s and a toast says why.
- **Risks:** the 180 s timeout and the PC's "one conversion at a time" rule: a second open while converting must wait or show "PC is busy". Big xlsx print areas can give hundreds of pages. `.pptx` works through the same route and is a bonus.

### Part 3 - Fonts
- **Read:** `DocxHtml.kt` lines 1-30 (CSS), `XlsxHtml.kt` CSS lines, `OfficeViewerActivity.kt` WebView setup (`grep -n "WebViewClient\|loadDataWithBaseURL\|settings" OfficeViewerActivity.kt`).
- **Do:**
  1. Add to `app/src/main/assets/fonts/office/`: Carlito (Regular, Bold, Italic, BoldItalic), Caladea (same four), Liberation Sans, Serif, Mono (same four each). About 3 MB total. Source: the projects' official GitHub repos (the sandbox allows github.com); keep each licence file in `assets/fonts/office/LICENSES/` (these fonts are open licence, but check the file you actually download). If the sandbox cannot reach them, stop and ask the owner to drop the TTFs in the folder.
  2. New `core/office/FontMap.kt` (JVM-testable, no `android.*`): maps a requested family (case-insensitive, strip quotes) to a bundled family + CSS fallback stack: Calibri -> Carlito, Cambria -> Caladea, Arial / Helvetica -> Liberation Sans, Times New Roman -> Liberation Serif, Courier New -> Liberation Mono; unknown fonts keep their name first, then a generic. Add a tiny self-test in `tools/office_selftest/Main.kt`.
  3. Serve the fonts to the WebView. Try first: `@font-face { src: url(lsoffice://font/<file>.ttf) }` answered by `WebViewClient.shouldInterceptRequest` from assets, with `Access-Control-Allow-Origin: *`. Because the page origin and the custom scheme differ, font loading can be blocked by CORS: **test this first, it is the main risk.** Fallback if it fails: base64 `data:` URIs inside the page CSS, only for the families the document really uses (so the HTML grows by about 0.3-0.6 MB per used family, not 3 MB).
  4. Make `DocxHtml` / `XlsxHtml` emit `font-family` through `FontMap` for every run / cell font, and the body default.
- **Checkpoints:** a = fonts + FontMap + self-test, b = serving to WebView works for one family, c = all families + Excel.
- **Done when:** a Word file written in Calibri / Cambria / Arial / Times shows line breaks matching the PC render (compare with the PDF of the same file from Part 2).
- **Device test:** same docx in "Exact pages" and in "Simple view": same words at the start and end of the first 10 lines; APK size grows by about 3 MB.

### Part 4 - Word page model (no UI)
- **Read:** `DocxModel.kt` (whole), `DocxReader.kt`: `grep -n "sectPr\|header\|footer\|footnote" DocxReader.kt` then the matching 60 lines.
- **Do:** add `DSection(pgW, pgH, orient, marT, marB, marL, marR, hdrDist, ftrDist, cols, colGap, titlePg, evenOdd, header default / first / even, footer default / first / even, startsAtBlockIndex)`. Parse every `w:sectPr` (paragraph-level ones end a section, the body-level one is the last). Today only the first section's default header / footer is kept: keep that API working (`DDoc.header` / `footer` stay). Parse `w:fldSimple` and `w:fldChar` instrText for `PAGE`, `NUMPAGES`, `DATE`, `TITLE` into a `DRun` kind FIELD (cached result kept as text). Twips -> points.
- **Checkpoints:** a = sections + page geometry, b = headers / footers per section, c = fields.
- **Done when:** the JVM self-test prints correct page size / margins / column count / header text for a new fixture `tools/office_selftest/sample_sections.docx` (create it: A4 portrait section, then US Letter landscape section with 2 columns, different first page, page-number field in the footer). Existing fixtures still pass.

### Part 5 - Print-layout mode
Step 5a is a **spike, one hour of work, decide before building.**
- **Read:** `DocxHtml.kt` (whole), `OfficeViewerActivity.kt` menu + reading-mode code (`grep -n "read\|Reading" OfficeViewerActivity.kt`).
- **Spike 5a:** WebView has no print pagination, so test the CSS multi-column trick: one container with `height` = page content height, `column-width` = content width, `column-gap` = left + right margins, `column-fill: auto`. The browser then breaks lines and tables across "pages" itself. Page numbers and headers / footers are added per page by a small script that counts `scrollWidth / pageStride` (JS enabled ONLY for this internal page, no network, no file access; keep `lsoffice://`-only navigation). Check on one 10-page docx: do page breaks (`w:br type=page`, `pageBreakBefore`) work, do tables split, does `scale(screenWidth / pageWidth)` + pinch zoom stay sharp.
- **If the spike works (5b):** pages laid out as white sheets with a grey gap, scaled to the screen width; vertical stacking may need one wrapper per page row, or horizontal snap paging if stacking is not possible: choose whichever the spike proves. Different first page / even-odd, per-section size, header / footer with `PAGE` / `NUMPAGES` fields from Part 4. Reading mode stays as the "Mobile view" equivalent; a menu toggle switches.
- **If the spike fails:** fall back to "paper mode": one continuous white sheet at real page width and margins, dashed page-boundary lines every page height, header / footer drawn at the top of the first page and bottom of the last. State clearly in section 8 that it is an approximation.
- **Checkpoints:** a = spike verdict written in section 8, b = single-section docs, c = multi-section + headers / footers.
- **Done when:** a 10-page docx with a header / footer and page numbers shows the same page count (+/- 1) as the PDF from Part 2, with Part 3 fonts.
- **Honest limit:** line-perfect equality with Word is not promised; Exact pages (Part 2) remains the fidelity reference.

### Part 6 - Floating images, text boxes, shapes
- **Read:** `DocxReader.kt` drawing / image code: `grep -n "drawing\|anchor\|inline\|pict\|txbx\|AlternateContent" DocxReader.kt` + about 80 lines; `DocxModel.kt` `DImage`; `DocxHtml.kt` image output (about line 62-112).
- **Do:** parse `wp:anchor` (position H / V relative to column / page / paragraph, offsets in EMU, `wrapSquare / wrapTight / wrapTopAndBottom / wrapNone`, behindDoc, size, `distT/B/L/R`). Render: `wrapSquare` and `wrapTight` -> `float:left/right` with margins (approximation), `wrapTopAndBottom` -> block, `wrapNone` -> `position:absolute` with `z-index` (behindDoc negative). Text boxes (`wps:txbx` / `v:textbox`) become a positioned box containing their paragraphs instead of flattened extra paragraphs (the current behaviour). Basic shapes (`wps:wsp` preset rect, roundRect, ellipse, line, with fill + outline) -> inline SVG / CSS box.
- **Checkpoints:** a = anchored pictures, b = text boxes, c = basic shapes.
- **Done when:** new fixture `sample_float.docx` (picture right with square wrap, a behind-text watermark picture, one text box, one rectangle) renders in recognisable positions in both Simple (floats approximated) and Print-layout mode.

### Part 7 - Tables, tabs, theme
- **Read:** `DocxReader.kt` table + paragraph-property parts (`grep -n "tbl\|tcPr\|tab\|theme" DocxReader.kt`), `DocxHtml.kt` `table()` (lines 132-168), `core/office/OoxmlPackage.kt` (open `theme1.xml`).
- **Do:** new `core/office/Theme.kt` (JVM): parse `ppt/theme/theme1.xml` / `word/theme/theme1.xml` / `xl/theme/theme1.xml` -> 12 scheme colours + major / minor fonts; `resolve(themeColor, tint, shade)` as Word / Excel do (tint / shade maths on HSL luminance; `lumMod / lumOff` for DrawingML). Use it for run colours, shading, borders, and for theme fonts (`asciiTheme="minorHAnsi"` -> theme minor font -> `FontMap`). Table styles: parse `w:tblStyle` chain from `styles.xml` (whole-table, first row, banded rows, first column), conditional formatting flags from `w:tblLook`, per-cell `tcBorders`, `tblBorders`, `insideH/V`, cell margins, vertical merge + grid span. Tabs: `w:tabs` with `w:leader` (dot, hyphen, underscore) and `w:br`-less tab runs -> inline-block with `border-bottom: 1px dotted` leader spans (right-aligned tab stops via flex / `text-align` trick); decimal tabs approximated as right.
- **Checkpoints:** a = Theme.kt + JVM test, b = table styles + borders, c = tab leaders.
- **Done when:** new fixture `sample_styles.docx` (styled table with banded rows, theme-coloured heading, TOC-style dotted leader line) matches the Exact PDF in colours and borders.

### Part 8 - Comments, tracked changes, fields
- **Read:** `DocxReader.kt` `ins` / `del` handling (`grep -n '"ins"\|"del"\|comment' DocxReader.kt`), `OfficeViewerActivity.kt` menu code.
- **Do:** read `word/comments.xml` (+ range markers `commentRangeStart/End`, `commentReference`); show a margin dot / highlight with a bottom-sheet or side bubble listing author, date, text. Tracked changes: keep inserted text underlined in the author colour and deleted text struck through (today `del` is dropped and `ins` is plain); menu toggle "Show markup / Final / Original". TOC and other complex fields: show the cached result with dotted leaders (Part 7) and keep page numbers as stored; internal hyperlinks `w:anchor` scroll to bookmarks.
- **Checkpoints:** a = comments, b = tracked-change markup + toggle, c = TOC / bookmarks links.
- **Done when:** fixture `sample_review.docx` (2 comments, 1 insertion, 1 deletion, a TOC) shows all of them and the toggle switches Final / Original correctly.

### Part 9 - Excel A
- **Read:** `XlsxReader.kt` (12 KB), `XlsxModel.kt`, `XlsxHtml.kt` (124 lines), `NumFmt.kt` only if number formats break.
- **Do:** theme + tint colours for fonts / fills / borders via `Theme.kt` (Part 7). Conditional formatting: parse `conditionalFormatting` with `cellIs`, `expression` (simple comparisons only), `top10`, `colorScale` (2 / 3 colour), `dataBar` (min / max, positive only; negative bars later), `iconSet` NOT in this part. Evaluate against cell values on the Kotlin side so no JS is needed; emit inline styles. Cell comments from `xl/comments1.xml` (red corner mark + sheet at the bottom or tap-to-show). Gridline toggle (read `showGridLines`, plus a menu switch). Column width from the font's digit width (Excel: `width * maxDigitWidth + 5` px with the default font's digit width; Carlito 11 pt digit = 7 px).
- **Checkpoints:** a = theme colours, b = conditional formatting, c = comments + gridlines + widths.
- **Done when:** fixture `sample_cf.xlsx` (colour scale, data bar, cell rule, comment, theme-coloured fills) matches the Exact PDF; existing xlsx fixtures unchanged.

### Part 10 - Excel charts 1
- **Read:** `XlsxReader.kt` drawing / relationship code (`grep -n "drawing\|rels" XlsxReader.kt`), `OoxmlPackage.kt`.
- **Do:** new `ChartModel.kt` (type, series with category + value refs and cached values, title, axis titles, legend position, colours, gap width) and parser for `xl/drawings/drawingN.xml` anchors (`twoCellAnchor` / `oneCellAnchor`: from / to cell + offsets) -> `xl/charts/chartN.xml`. `ChartSvg.kt`: render clustered / stacked column and bar, line (with markers), to inline SVG: axes with nice tick steps, gridlines, labels, legend, title, series colours from theme / accent cycle. Use the cached values in the chart XML (no formula engine). Place the SVG absolutely over the grid at the anchor cell rectangle.
- **Checkpoints:** a = model + parser JVM test, b = column / bar SVG, c = line + placement.
- **Done when:** fixture `sample_chart.xlsx` (one column chart, one line chart) shows both in place.

### Part 11 - Excel charts 2 + images
- **Read:** `ChartSvg.kt` (your own from Part 10), `XlsxHtml.kt`.
- **Do:** pie / doughnut (with percent labels), area (stacked), scatter; secondary axis only if budget remains. Sheet pictures from the drawing part (`xdr:pic`) as `<img>` at the anchor; simple shapes / text boxes (`xdr:sp`) as positioned boxes (reuse Part 6 shape rendering if present, else rect + text only).
- **Checkpoints:** a = pie / doughnut, b = area + scatter, c = pictures + shapes.
- **Done when:** extend `sample_chart.xlsx` or add `sample_chart2.xlsx` with each type; pictures show.

### Part 12 - Navigation
- **Read:** `OfficeViewerActivity.kt` (your Parts 2 / 5 changes), `XlsxHtml.kt` tab bar part (`lsoffice://sheet/N`).
- **Do:** Word: outline panel (headings from the style chain, tap scrolls; needs ids on heading elements emitted by `DocxHtml`), page thumbnail strip for Print-layout mode (draw page boxes at small scale from the same pagination; in Exact PDF mode the PDF viewer's overview grid already is the thumbnail view, so do nothing there). Excel: sheet list sheet (bottom sheet, with hidden-sheet toggle) next to the existing tab bar, and a formula bar showing the selected cell's formula / value (cell tap needs JS or per-cell `lsoffice://cell/R/C` links: use links, JS stays off in Simple mode).
- **Checkpoints:** a = outline, b = Excel formula bar + sheet list, c = thumbnail strip.
- **Done when:** tapping a heading in the outline scrolls to it; tapping a cell shows its formula; the strip highlights the current page.

### Part 13 - Integration
- **Do:** Settings row "Office view: Auto / Exact pages (PC) / Simple" (writes `office_view`); run every fixture in `tools/office_selftest/` on the JVM and record results; walk the device checklists of parts 1-12 and update the status log; merge this file into HANDOVER.md chapter 8 (8.9) and section 3 / section 14; delete obsolete notes ("no pcprint.py / phone_explorer.py office preview" in 8 limits); update the Known limits list in 8.
- **Done when:** HANDOVER.md chapter 8 describes the new pipeline and this file is deleted.

## 5. Paste-ready session prompt (replace N)

> Read `HANDOVER_OFFICE_FIDELITY.md` sections 0, 2 and "Part N" only. Do Part N and nothing else. Follow the token-economy rules: no whole-file reads, small scripted edits. Compile what can be compiled on a JVM and say what could not be built. Update section 8 of that file, then deliver the FULL app zip with ALL app files (never only changed files), with the updated handover files inside it as `pcshare-office-partN.zip`, and tell me its file count and size.

Attach: the latest FULL zip from the previous part. Nothing else.

## 6. Risks to keep in mind

- **Fonts through a custom scheme (Part 3)** may be blocked by CORS in WebView; base64 fallback is planned.
- **Pagination (Part 5)** may only be approximate; that is why Exact pages is Part 2 and stays the reference.
- **PC must be on** for Exact pages; the app must always have a working offline fallback (Parts 3-8).
- **`.docx` from Word with `mc:AlternateContent`**: only the Choice branch is read today; floating shapes often live there (Part 6).
- **Not compiled on Android** is the normal state of every part here (no SDK in the sandbox): budget one CI build + fix session after Parts 2, 5 and 10 (paste the first compiler error, small session).
- Memory / size: charts and big sheets must not blow the 60 MB / 3 MB-per-image limits already in the viewer.

## 7. Out of scope here

`.ppt` binary, PowerPoint layout fidelity (works through Exact pages only), editing in Print-layout mode, Excel pivot tables / slicers / sparklines / icon sets, equations (OMML), SmartArt.

## 8. Status log (each session fills its own row and one line)

| Part | Date | Built? | Compiled / tested | Notes / next |
|---|---|---|---|---|
| 1 | 2026-10-24 | Yes (code) | Brace/paren balanced; kotlinc 2.0.21 syntax pass on `PdfViewerActivity.kt` found no syntax errors (all remaining errors are unresolved `android.*`, no SDK in the sandbox). NOT built by CI, NOT device-tested | Cause not confirmed on a device. Fixed the likely causes (below). Next: Part 2. If the white pages still happen, run a debug build and paste `adb logcat -s PdfViewer` |
| 2 | 2026-10-24 | Yes (code) | Braces balanced; kotlinc syntax pass on the 3 touched files found no syntax errors (other errors = missing Android SDK). NOT built by CI, NOT device-tested | Needs: CI build, then the device test below. Next: Part 3 (fonts). Part 1 device result still unreported |
| 3 | 2026-10-24 | Yes (code) | Office core (`core/office/*.kt` + `tools/office_selftest/Main.kt`) compiled with kotlinc 2.0.21 (via the Gradle embeddable jar) with NO errors; `--fonts` self-test: all checks OK (FontMap, 20 font files are real TTFs, licences, docx theme / style / explicit fonts, xlsx and xls names, HTML output); older self-tests (`--docx`, `--edit`, default) show no FAIL. Headless Chromium check: with the page at `https://appassets.androidplatform.net/` and fonts served from `/office-fonts/`, only the used faces download and apply. `OfficeViewerActivity.kt` NOT compiled (no Android SDK), NOT built by CI, NOT device-tested | Needs: CI build, then the device test below. Next: Part 4 (Word page model). Parts 1 and 2 device results still unreported |
| 4 | 2026-10-24 | Yes (code, no UI) | Office core (`core/office/*.kt` + `tools/office_selftest/Main.kt`) compiled with kotlinc 2.0.21 with NO errors (compiler downloaded from the GitHub release this time, GitHub was reachable). New `--sections` self-test: 43 checks OK, 0 FAIL; `--docx`, `--edit`, `--fonts` still 0 FAIL. No Android file was touched, so nothing here needs a CI build of its own | Next: Part 5 (print-layout spike). Parts 1-3 device results still unreported |
| 5 | 2026-10-24 | Yes (code) | Office core compiled with kotlinc 2.0.21, no errors; self-tests `--docx --edit --fonts --sections` and the default run: 0 FAIL; new `--printtest`: 11 checks OK. **Spike verdict: WORKS** (headless Chromium 153, real Carlito fonts): fixture `sample_pages.docx` (A4 section with different first page + PAGE / NUMPAGES footer, table, page break, then Letter landscape 2-column section) = **8 pages, same as LibreOffice's PDF (8)**; page numbers 1-8 and "Page n of 8" right; first-page header / footer right; table rows split like LibreOffice; breaks differ by a few lines per page. `OfficeViewerActivity.kt` NOT compiled (no Android SDK; brace-balanced, kotlinc shows only missing-SDK errors), NOT built by CI, NOT device-tested | Needs: CI build, then device test (below). Next: Part 6. Parts 1-3 device results still unreported |
| 6 | | | | |
| 7 | | | | |
| 8 | | | | |
| 9 | | | | |
| 10 | | | | |
| 11 | | | | |
| 12 | | | | |
| 13 | | | | |

### Part 1 notes (2026-10-24) - what changed in `PdfViewerActivity.kt`
- **Failed pages are no longer permanent.** `failedPages` is now `page -> time of failure` plus `failCount`. A failed page is retried after 3 s (when it is on screen), up to 3 times; after that the blank page shows "Page N can't be shown (reason) / Tap to retry" and a tap on the viewer retries every visible failed page (`retryFailedVisible`). `showPages` (rotation / width change) and `begin` clear the failure state (before, rotation kept it).
- **Real reason is kept.** Render exceptions are logged (`Log.w`, tag `PdfViewer`: page, size, exception) and shown in a Toast in debuggable builds. Sizes are tried at full, 1/2, 1/4 before a page counts as failed (OOM also clears the bitmap cache).
- **Race fix (most likely cause of "page 1 only").** `onViewRecycled` could remove a page from `bound` AFTER a new holder had bound the same page, so the render task skipped it and nothing asked again (white until the next scroll stop). Now a recycled holder only removes the mark when no other holder owns that page, and a skipped task re-requests (and repairs `bound`) when the page is visible.
- **Bad page boxes.** NaN / zero sizes in `openDoc` fall back to A4 (and are logged); `renderSize` treats a bad ratio as A4.
- **Overview grid.** `requestThumb` logs failures and retries once at half size (thumbnails no longer stay blank after one exception).
- Unchanged on purpose: `renderPage` locking (`rlock`) was already correct.
- **Device test still open (from the Part 1 brief):** a 20+ page PDF from Word and one from LibreOffice, fling to the end and back with the scroller, rotate, overview grid shows all thumbnails, a 100-page PDF.

### Part 2 notes (2026-10-24) - Exact pages through the PC
- **New `core/OfficeExact.kt`.** `eligible(name)` (doc docx rtf odt xls xlsx ods ppt pptx odp; not csv), `mode(ctx)` / `setMode`, `cached`, `convert(ctx, src, quick, status)`, `md5`. Cache `cache/office-pdf/<md5 of the file CONTENT>.pdf`, 12 h, 300 MB cap, oldest first. Keyed by content (not path / time stamp) because the viewer re-downloads remote files on every open, and an edited file converts again by itself. One conversion at a time on the phone (`lock`); pcprint.py also serialises (`CONVERT_LOCK`), so a busy PC just makes the phone wait (phone read timeout 180 s).
- **Setting** is NOT in `Cfg` (deviation from the Part 2 brief, simpler): SharedPreferences `office_view`, key `mode` = `auto` (default) | `exact` | `simple`; `OfficeExact.setMode(ctx, ...)`. `auto` and `exact` behave the same today; Part 13 adds the Settings row.
- **`OfficeViewerActivity`:** `onCreate` -> unless the intent has `simple=true` or mode is `simple`, and the file is eligible, `exactOrSimple(f, auto=true)`: shows "Preparing exact pages..." (Back = cancel), uses the cache or converts on a background thread, then sets `PdfViewerActivity.pending` ({name, file, size, key `office|<md5>`, office:{path, dev, rpath}}), starts the PDF viewer and finishes. On ANY failure: one toast with the real reason ("Simple view - ..." when automatic) and the old HTML view loads. After a "no PC" failure, automatic opens skip the PC search for 60 s (`failWhy` / `failAt`) so each file does not wait 3.5 s again. Menu item **Show exact pages (PC)** (id 10) forces a try.
- **`PdfViewerActivity`:** `begin()` accepts a `file` (absolute path that must be inside `cacheDir`) instead of a localhost `url`; it skips the download and uses the file directly (not cached / not deleted). When the JSON has `office`, a small **Aa** button (top bar, `simpleBtn`) reopens the original in the HTML view with `simple=true` (keeps `dev` / `rpath`, so editing works there) and closes the PDF.
- **Editing:** unchanged, HTML view only. After an edit the content md5 differs, so the next open converts the edited file.
- **Not done / by design:** no progress percentage during the upload / conversion; opening from outside the app (VIEW / SEND intents) also tries exact pages in auto mode; `.pptx` / `.odp` go through the same route (bonus, untested).
- **Device test (open):** (1) PC with pcprint.py v11+ and Word or LibreOffice on the same Wi-Fi: open a .docx and a .xlsx from the file list -> real pages in the PDF viewer, **Aa** opens the simple view with Find and Edit working; open the same file again -> instant (cache). (2) PC off: the simple view opens after about 4 s with a toast that says why; a second file opens without the 3.5 s wait. (3) Menu **Show exact pages (PC)** from the simple view. (4) Edit a docx in the simple view, reopen -> the PDF shows the edit. (5) Old pcprint.py -> toast "update pcprint.py".

### Part 3 notes (2026-10-24) - Fonts
- **Fonts bundled (deviation from the brief: size and source).** 20 TTFs in `assets/fonts/office/`: Carlito, Caladea (4 styles each), Liberation Sans / Serif / Mono (4 each), plus `LICENSES/` (Carlito-OFL, Caladea-OFL, Liberation-OFL; all SIL OFL 1.1). The sandbox could NOT reach GitHub or npm (proxy 403), so the files come from the sandbox's installed Debian packages (fonts-crosextra-carlito 20230309-2 (Carlito 1.104), fonts-crosextra-caladea 20200211-2 (Caladea 1.001), fonts-liberation 1:2.1.5-3 (Liberation 2.1.5)), copied **unmodified**. Size is **7.2 MB raw, not about 3 MB**: they are the full upstream fonts; the APK deflates TTFs, so expect roughly +3.5-4 MB. They were NOT subset on purpose: OFL Reserved Font Names (Carlito, Liberation) forbid a modified font under the same name. If the size matters, the safe cuts are the 4 Mono files (1.2 MB) and the BoldItalic files (fonts are loaded lazily, so unused ones cost only APK size).
- **Fonts were never read before.** `DocxReader` / `XlsxReader` ignored font names; only the CSS stack `Calibri,Carlito,Arial` existed (and no Carlito on the phone, so every document showed in the phone's default font). Now: `DRun.font`, `DDoc.defaultFont` (rPrDefault), run + style + theme resolution (`w:rFonts` ascii / hAnsi first, else asciiTheme / hAnsiTheme -> `majorFont` / `minorFont` Latin typeface from `word/theme/theme1.xml`). `DRun.sameStyle` now compares the font (adjacent runs with different fonts are no longer merged). Excel: `XFont.name` (new last constructor parameter with default null, so other callers compile); xlsx `<name val>` and BIFF8 FONT record (`XlsReader`). NOT done: `.doc` (binary Word), `.odt` / `.ods`, PowerPoint run fonts, East-Asian / complex-script fonts (`eastAsia`, `cs`).
- **`core/office/FontMap.kt`** (no `android.*`): `stack(name)` -> e.g. `Carlito,Calibri,Arial,sans-serif`; unknown name -> `'Name',serif|monospace|sans-serif` (guess by name); `clean()` strips quotes / comma lists / unsafe characters (the value goes inside `style="..."`); `fontFaceCss(urlBase)` = 20 `@font-face` rules (a face downloads only when text uses it); `assetPath(file)` = whitelist for the server side. Aliases: Calibri, Calibri Light -> Carlito; Cambria -> Caladea; Arial, Helvetica -> Liberation Sans; Times New Roman, Times -> Liberation Serif; Courier New, Courier -> Liberation Mono. New Office default fonts (Aptos etc.) have no twin and use the generic fallback.
- **Serving to the WebView (the CORS risk): solved by not using a custom scheme.** `lsoffice://` fonts would hit Chromium's rule that CORS-mode requests (fonts) only work for http / https / data. Instead the page is loaded with `loadDataWithBaseURL("https://appassets.androidplatform.net/", ...)` (`FontMap.BASE`) and `OfficeViewerActivity`'s `WebViewClient.shouldInterceptRequest` answers `https://appassets.androidplatform.net/office-fonts/<file>.ttf` from assets (same origin as the page -> no CORS check; the header `Access-Control-Allow-Origin: *` is sent anyway). Other requests are untouched. In-page `#anchor` links to the same base are allowed in `shouldOverrideUrlLoading`. The "Preparing..." status page still uses `about:blank`. **If a device shows the fallback font:** (a) `adb logcat | grep -i chromium` for a blocked font request; (b) the planned fallback is base64 `data:` URIs in the CSS for the families the document uses (not built).
- **`DocxHtml.render(..., fontBase)` / `XlsxHtml.render(..., fontBase)`**: new optional last parameter, default null = no `@font-face` (used by `core/OfficeWeb.kt`, the browser view, unchanged: it still gets the stack, so a PC with Carlito / Liberation shows them). Body font = default font of the document through FontMap; a run / cell gets its own `font-family` only when it differs from the default. **Reading mode keeps its own reading font** (`Noto Serif`): run fonts are ignored there; Part 5 (print layout) is where document fonts really show in Word files.
- **Self-test:** `java -cp <classes>:kotlin-stdlib MainKt --fonts tools/office_selftest app/src/main/assets/fonts/office` (new fixtures `sample_fonts.docx` with theme + style + explicit fonts, `sample_fonts.xlsx` made by openpyxl). Compile tip for this sandbox (no `kotlinc` binary): `java -cp <gradle lib>/kotlin-compiler-embeddable-2.0.21.jar:kotlin-stdlib:kotlin-script-runtime:kotlin-reflect:kotlin-daemon-embeddable:trove4j:annotations:kotlinx-coroutines-core-jvm org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp kotlin-stdlib.jar <sources> -d <outdir>` (`-include-runtime` fails there; run with stdlib on the classpath).
- **Device test (open):** (1) install, open a .docx written in Calibri in **Simple view** (the **Aa** button or menu): text is Carlito, not the phone font; a Cambria heading is Caladea; Arial / Times / Courier parts are Liberation Sans / Serif / Mono. (2) Compare with the "Exact pages" PDF of the same file: same words at the start and end of the first 10 lines (in Print layout; reading mode reflows by design). (3) An .xlsx with Calibri and an Arial column: fonts differ per column. (4) APK size grew by about 3.5-4 MB. (5) Tapping a footnote number in a docx still jumps (anchors on the new base URL) and sheet tabs still switch.

### Part 4 notes (2026-10-24) - Word page model (no UI)
- **New `DSection`** (in `DocxModel.kt`, all lengths in POINTS = twips / 20): `pgW pgH orient marT marB marL marR gutter hdrDist ftrDist cols colGap colSep colWidths titlePg evenOdd breakType pgNumStart pgNumFmt`, six header / footer lists (`headerDefault / headerFirst / headerEven`, `footerDefault / footerFirst / footerEven`), `startsAtBlockIndex` (index into `DDoc.body`). Extras beyond the brief, cheap and needed by Part 5: `gutter`, `colSep`, `colWidths` (only when `equalWidth=0`), `breakType` (w:type), `pgNumStart` / `pgNumFmt` (w:pgNumType), `contentW` / `contentH`, `headerFor(firstOfSection, pageNo)` / `footerFor(...)` (titlePg -> first, evenOdd + even page -> even, else default). Missing values use Word's defaults (Letter, 1 inch margins, header / footer 0.5 inch, gap 0.5 inch). `orient` = the `w:orient` attribute, else derived from width > height.
- **`DDoc.sections`** (always >= 1 for .docx; EMPTY for .doc / .odt / .pptx / ...: use `DDoc.sectionAt(blockIndex)`, which returns a default Letter section then) and `DDoc.evenOdd` (`w:evenAndOddHeaders` from `word/settings.xml`, copied into every section).
- **Parsing (`DocxReader`).** A paragraph-level `w:sectPr` (inside `w:pPr`) ends a section; the body-level one is the last. A section starts at the body block index after the previous one ended (text-box paragraphs emitted after the host paragraph stay in the host's section). `sectPr` inside tables, headers, notes or text boxes is ignored. Header / footer parts are read once per relationship id; **a section without its own reference inherits the previous section's** (per type, as Word does); section 1 without one = empty.
- **Compatibility.** `DDoc.header` / `DDoc.footer` still exist: now "default header / footer of the first section that has one" (before: first default reference found anywhere; same result for normal files). The HTML view is unchanged apart from fields (below).
- **Fields.** `DRun.FIELD` (= 6) with `DRun.field` = `PAGE` | `NUMPAGES` | `DATE` | `TITLE` and `text` = the cached result, style copied from the first result run. Both `w:fldSimple` and `w:fldChar` / `w:instrText` (begin / separate / end, nesting handled) are recognised; the result runs are replaced by ONE FIELD run. Any other field type (SEQ, HYPERLINK, TOC, PAGEREF ...) is untouched: its cached result stays ordinary TEXT runs as before. `DPara.text` / `plainText` include the FIELD text; `DocxHtml` prints the cached result like text (Part 5 must replace PAGE / NUMPAGES per page itself). A field state is per paragraph (text boxes inside a field do not disturb it); a field that never ends keeps its plain runs.
- **Fixture / test.** `tools/office_selftest/sample_sections.docx`: 3 sections (A4 portrait; Letter landscape, 2 columns with separator, different first page, own footer with PAGE + DATE; Letter portrait with no references = full inheritance), `evenAndOddHeaders` on, header with a TITLE field, footer with a PAGE `fldSimple` and a complex NUMPAGES, plus an unrecognised complex SEQ field. Run: `kotlinc app/src/main/java/com/lanshare/app/core/office/*.kt tools/office_selftest/Main.kt -include-runtime -d /tmp/ost.jar` then `java -jar /tmp/ost.jar --sections tools/office_selftest`. Kotlin compiler for the sandbox: `https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip` (works now; earlier sessions reported a 403).
- **Known limits.** Not parsed: `w:vAlign`, line numbering, page borders, `w:docGrid`, mirrored margins (`w:mirrorMargins`), `gutterAtTop`, header / footer of `.doc` / `.odt`. Units other than twips (strict-OOXML "1in") are ignored (default kept). No Android code and no UI changed, so there is nothing new to test on a device in this part.

### Part 5 notes (2026-10-24) - Print layout
- **Spike 5a (done, verdict above).** Multi-column trick works: each CSS column of a fixed-height container = one page, the browser breaks lines / paragraphs / table rows itself. Pages are stacked VERTICALLY (no horizontal paging needed): each white sheet shows its own column of a CLONE of the flow (translated by -page x stride inside an overflow:hidden box); clones exist only for sheets near the screen (IntersectionObserver, rootMargin 60 percent), others are empty shells. Page count = `ceil((scrollWidth - 1) / stride)`. Pinch zoom stays sharp (viewport meta `width` = widest page; WebView re-rasterises).
- **Files.** `DocxHtml.renderPrint(doc, image, title, fontBase)` (new, plus a thread-local print flag read by the shared `para()` / `table()`), new `DocxPrintJs.kt` (the one script; no `$` in it), `DocxModel.kt` (`DPara.spBefore / spAfter / lineMul / lineExact / lineAtLeast`), `DocxReader.kt` (parses `w:spacing`, now also `docDefaults/pPrDefault`), `FontMap.lineFactor()`, `OfficeViewerActivity.kt`. Self-test `--print X.docx out.html`, `--printtest`; browser check `tools/office_selftest/print_layout_chromium.py`; fixture `tools/office_selftest/sample_pages.docx`.
- **Viewer.** Menu item "Print layout (pages)" (pref `office_read` / `print`, **default OFF** until device-tested; reading mode and print layout exclude each other). All loads go through `show()`: JavaScript is enabled ONLY for the print page of a Word-type document; any http(s) request to a host other than the font host is answered 403 (no network from the page); in-page `#links` (footnotes) are mapped to the right page by the script. If the script throws, the same HTML shows as continuous white sheets ("paper mode").
- **Layout rules.** Flows = consecutive sections with identical geometry and `continuous` break share one flow; every other section starts a new page with its own size / margins / columns. Headers / footers: first-page, even / odd, `PAGE` (with `pgNumStart` / `pgNumFmt`) and `NUMPAGES` fields replaced per page. Paragraph spacing: before / after in points (max of adjoining, as Word), line spacing = multiple x the font's natural height (`FontMap.lineFactor`), headings keep-with-next, `pageBreakBefore` and page-break runs = column break, table rows do not split. Formats without sections (.doc, .odt) use A4 with 2.5 cm margins.
- **Honest limits.** Header taller than the top margin overlaps the text (Word pushes the text down); evenPage / oddPage section breaks add no blank page; unequal column widths become equal; footnotes collected after the last paragraph; body fields PAGE not updated; floating images / text boxes (Part 6) and tab stops / table styles (Part 7) not yet; line-perfect equality with Word is not promised (Exact pages stays the reference). Long documents (100+ pages): each live sheet holds a clone of its whole flow, so memory / layout time grows with length; if slow on the phone, the fix is to split the flow into per-page chunks (not done).
- **Device test still open.** Print layout ON, a 10+ page Word file: sheets fit the screen width, fling scroll is smooth, pinch zoom sharp, page numbers / header / footer right, a landscape section, footnote link jumps to its page, rotate screen, memory with a 60+ page file. Then compare the page count with Exact pages.
