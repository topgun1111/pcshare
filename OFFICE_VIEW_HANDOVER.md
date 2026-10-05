# Office file VIEWING feature: handover (2026-10-05, updated same day: session 6)

Goal: tap a doc/docx/rtf/odt/xls/xlsx/csv/ods/ppt/pptx/odp in LANShare and read it in the app, with no external app.
Written so a fresh Claude session (or you) can continue in small, cheap steps. **Nothing here is compiled or device-tested** (no Android SDK in the sandbox).

## Findings about the current code (so you don't re-read it)
- File tap dispatch: `ui.html` line ~630 (`kind(i)` -> `viewPdf` / `playVid` / `viewImg`...). Office files currently fall to `openFile` -> `LSAndroid.open` -> system "Open with".
- Native PDF reader: `PdfViewerActivity` (PdfRenderer), started by `LSAndroid.viewPdf(json {name,url,size,key})` in `MainActivity.kt`. It downloads `url`, so it can show ANY URL that returns a PDF.
- PC converter already exists: `pcprint.py` v11 `POST /convert?name=` (office in, PDF out; MS Office COM or LibreOffice). Phone side: `Jobs.converterIp()` + `Jobs.officeToPdf(ip, file, name, out)`; used today by `PrintPreview`.
- Routes live in `core/Routes.kt` (`"pvinfo"`, `"pvpage"` ... pattern). Source files are read with `Jobs.ep(dev).open(path)` (works for local, other device, SMB, inside archives).

## Design (3 tiers, cheapest first)
| Tier | What | Needs | Status |
|---|---|---|---|
| 1 | PC converts to PDF, native PDF reader shows it | PC running pcprint.py v11 with Office/LibreOffice | **coded, untested** |
| 2 | Phone-only fallback: docx/xlsx/pptx/odt read directly (zip + XML) into simple HTML shown in a WebView activity (text, headings, lists, tables, embedded images; sheets as tables; slides as text + images) | nothing | planned |
| 3 | "Open with..." system chooser | an installed app | exists |

Rule: tier 1 first; if `officepdf` fails with a converter/network error, the UI falls to tier 2 for docx/xlsx/pptx/odt/ods/odp, else tier 3. Legacy doc/xls/ppt/rtf have no tier 2 (only tier 1 or 3).

## Progress
- [x] `core/OfficeView.kt` (new): extension set, `pdfFor(dev,path)` = read source, size limit 60 MB, convert on PC, cache in `cache/ov/<sha>.pdf` (LRU 8), one conversion per file at a time, `wipe()`.
- [x] `Routes.kt`: `GET /api/officepdf?dev&path` returns the PDF (`application/pdf`).
- [x] `ui.html`: `isOffice(i)` / `viewOffice(i)`; tap dispatch added right after the PDF case. Opens `LSAndroid.viewPdf` with the `/api/officepdf` URL (name = `<file>.pdf`, key prefixed `office|`); in a plain browser it opens the URL in a tab. JS syntax checked with node.
- [ ] Compile + device test (see checklist). Checked by reading (session 2): `Source` is an `InputStream` with `size` (so `.use { it.size }` is fine); `Jobs.officeToPdf` / `converterIp` are public. No kotlinc in the sandbox, so still not compiled.
- [x] Cache wipe at start: `Core.start` now deletes `cacheDir/ov` next to `pv` (that is how `PrintPreview` is wiped; `OfficeView.wipe()` itself stays unused).
- [x] Tier 2 step 1: `core/OfficeText.kt` (new), docx only (see below). Sample file: `app/src/test/resources/sample.docx`.
- [x] Tier 2 step 2 (session 3): xlsx/xlsm added to `core/OfficeText.kt` as `private object Xlsx` (see below). Sample: `app/src/test/resources/sample.xlsx` (made with xlsxwriter, so it has `sharedStrings.xml` like Excel files).
- [x] Tier 2 step 3 (session 4): pptx/pptm added to `core/OfficeText.kt` as `private object Pptx` (see below). Picture code moved out of `Docx` into a shared `OfficeText.imgTag(z, entryName)` (+ `IMG_PH`); `Docx.img` now just looks up the rel and calls it. Sample: `app/src/test/resources/sample.pptx` (python-pptx: 5 slides).
- [x] Tier 2 step 4 (session 5): odt/ods/odp (+ templates ott/ots/otp) added to `core/OfficeText.kt` as `private object Odf` (see below). Samples: `app/src/test/resources/sample.odt|ods|odp` (hand-written minimal ODF, no styles.xml).
- [x] Tier 2 wiring (session 6, not compiled): `OfficeView.htmlFor(dev,path)` (copies source to cache, `OfficeText.toHtml`), `OfficeView.converterOk()`; routes `GET /api/officehtml?dev&path` (text/html) and `GET /api/officeok` (`{pc:bool}`); new `OfficeViewerActivity.kt` (fetches the URL, WebView, JS off, pinch zoom, spinner, error text; `pending` json `{name,url}`), registered in the manifest; `LSAndroid.viewOffice(json)` in `MainActivity.Bridge`; `ui.html` `viewOffice`: for docx/xlsx/xlsm/pptx/pptm/odt/ods/odp/ott/ots/otp it asks `/api/officeok` -> PC reachable = PDF (tier 1), else phone reader (tier 2); doc/xls/ppt/rtf/csv always tier 1. JS syntax checked with node.
- [ ] Menu items "Open with" / "Share" in `OfficeViewerActivity`. [ ] Long-press menu "View as PDF". [ ] If a tier 1 conversion itself fails after `officeok` said yes, there is no auto fallback to tier 2 (the PDF reader shows the error).

## Test checklist (Tier 2, session 6)
1. PC off: tap sample.docx / xlsx / pptx / odt: the viewer shows the page (spinner first). 2. PC on with pcprint.py v11: the same files open as PDF (tier 1). 3. Tap a .doc with PC off: PDF reader shows the converter error (expected). 4. Corrupt docx: viewer shows the error text. 5. Likely compile suspects: `OfficeViewerActivity` (`return@runOnUiThread` inside lambda, `it.readBytes()` on InputStream needs Kotlin 1.3+), `htmlFor` (same `ep.open(..).use { it.size }` as `pdfFor`).

Known quirk (session 6 check): `/api/officeok` calls `Jobs.converterIp()`, which pings every known IP and can take up to ~3.5 s when no PC answers, so the first tap with the PC off waits that long with no spinner. Fix later: short cache of the answer, or a toast "checking PC...".

## Test checklist (Tier 1)
1. Gradle build: likely small errors in `OfficeView.kt` (`ep.open(..).use { it.size }`: confirm the source type has `size` and is `Closeable`; `Jobs.officeToPdf` visibility).
2. PC: run pcprint.py v11; `/ping` must show `convert: true` and an `office` engine.
3. Tap a .docx, .xlsx, .pptx on local storage, then on another device, then on SMB: reader opens the PDF.
4. Second tap of the same file is instant (cache hit). Changing the file (size change) reconverts.
5. PC off / old pcprint.py / no engine: the reader shows the error text from `Jobs.officeToPdf` (not a blank screen).
6. Risk: the first conversion can take 10 to 60 s. Check that `PdfViewerActivity`'s download has no short read timeout and shows a spinner. If it times out, make the route async (return 202 + poll, like the `pvinfo` pattern).
7. Risk: `ex.reply(..., f.readBytes())` loads the whole PDF in RAM. Fine for normal files; switch to streaming (`sendFile`) if big PDFs crash.

## Tier 2: `OfficeText.kt` (docx done, not compiled)
`OfficeText.toHtml(file, ext)` returns one self-contained HTML page (own CSS, light/dark, viewport meta, no JS). `OfficeText.canRead(name)` / `EXTS` say what is supported (only `docx` now; add each format to `EXTS` and to the `when` in `toHtml`).
- docx supported: headings (by style NAME from styles.xml, so localised ids work), Title/Subtitle, bold/italic/underline/strike/sup/sub, alignment, tabs, line/page breaks, bullet + numbered lists (numbering.xml; lists defined by the paragraph style, e.g. `List Bullet`, also work: python-docx makes those), numbered lists keep their count after interruptions (`start=`), tables incl. `colspan`, nested tables, png/jpg/gif/bmp images as data URIs (downscaled to ~1024 px / JPEG when > 250 KB; emf/wmf/other become `[image]`), output cut at 6M chars with a note.
- docx NOT handled (known gaps): text boxes and `mc:Fallback` content are skipped on purpose (they nest paragraphs / duplicate images), headers/footers/footnotes, hyperlinks (text shown, no link), row spans (`vMerge` shows an empty cell), colours/fonts/sizes, style inheritance (`basedOn`) for list/heading detection, tracked-change insertions are shown and deletions hidden.
- Uses `android.util.Xml.newPullParser()` (not namespace aware: tag names keep the `w:` / `a:` / `r:` prefix, as Word writes them). Needs no new dependency.
- Test idea (after the first build): open `sample.docx` (heading, bold/italic run, 2 bullets, 2 numbers, table with a merged top cell, a small red picture); expect h1, h2, `<ul>` with 2 `li`, `<ol>` with 2 `li`, `<td colspan="2">`, one `<img>`.

## Tier 2: xlsx in `OfficeText.kt` (session 3, not compiled)
`EXTS` is now `docx, xlsx, xlsm`; `toHtml` dispatches to `Xlsx.toHtml(file)`. HEAD CSS got `.rn` (row number cell), `.r` (right align), `.tabs`.
- Output: a link line to the sheets (`#s1`, `#s2`..., only when > 1 sheet), then per sheet an `<h2>` + one `<table>` with a row-number column. Hidden/veryHidden sheets and chartsheets are skipped. Sheet order/names come from `xl/workbook.xml` + `xl/_rels/workbook.xml.rels` (fallback: `xl/worksheets/sheet*.xml`).
- Cell types: shared strings (rich text runs joined, phonetic `rPh` ignored), `inlineStr`, `str`, bool (TRUE/FALSE), errors, numbers (right aligned; 15 significant digits like Excel, so 0.30000000000000004 shows 0.3), cached formula values (`<v>`). Dates: a cell is a date when its style (`s=` -> cellXfs -> numFmtId) is built-in 14-22 / 45-47 or a custom format with d/m/y/h/s outside quotes/brackets; shown as `yyyy-MM-dd` (+ `HH:mm[:ss]`). Serials before 1900-03-01 stay numbers. Own civil-date code (minSdk 24, no java.time).
- Layout: gaps inside a row become empty cells; gaps between rows keep at most 2 blank rows; newline in a cell -> `<br>`. Caps: 2000 rows x 50 cols per sheet (a note says so), 6M chars total.
- NOT handled (known gaps): merged cells (`mergeCells` ignored, each cell shown alone), number formats other than dates (percent, currency, thousands separators and fixed decimals show the raw number), column widths, colours/fonts/bold, hyperlinks, images/charts, comments, rich-text styling, 1904 date system, `[h]:mm` elapsed-time formats, hidden rows/columns (shown).
- Test idea (after first build): open `sample.xlsx`. Expect tabs line `Data Second Empty` (no `Hidden`); table 1: header row, row 2 = `Apple | 3 | 0.3 | TRUE | 2026-10-05 | 2026-10-05 14:30`, row 3 = `Çay & <b>şeker</b>` shown escaped (not bold) | 1234567 | 12.5 | FALSE, row 4 = text in A and C with an empty B, row 5 two lines, then 2 blank rows (6, 7), then row 9; table 2: only B2 (A2 empty); `Empty` shows "(empty sheet)".

## Tier 2: pptx in `OfficeText.kt` (session 4, not compiled)
`EXTS` is now `docx, xlsx, xlsm, pptx, pptm`; `toHtml` dispatches to `Pptx.toHtml(file)`. HEAD CSS got `.sl` (slide card), `.sn` (slide number line), `.nt` (notes box).
- Output: one `<section class="sl">` per slide: `Slide N` line (+ `(hidden)` when `show="0"`), `<h2>` = title placeholder text, then shapes in file order, then speaker notes. Slide order = `p:sldIdLst` in `ppt/presentation.xml` via its rels (fallback: `slideN.xml` sorted by N). Cap 300 slides / 6M chars (note says so).
- Text: `a:p` / `a:r` / `a:t` / `a:fld` / `a:br`; bold/italic/underline/strike/sup/sub from `a:rPr`. Bullets: paragraph with `a:buChar` -> `ul`, `a:buAutoNum` -> `ol`, `a:buNone` -> plain. Heuristic: a paragraph in a body/obj placeholder (`<p:ph idx=..>` without type counts as body) is a bullet unless `a:buNone` (real inheritance from layout/master is NOT read). `a:lvl` > 0 gives a left margin on the `li`. Subtitle placeholder -> `<div class="sub">`. dt / ftr / sldNum / hdr placeholders are dropped.
- Tables: `a:tbl` -> `<table>` with `gridSpan` -> colspan, `rowSpan` -> rowspan, cells with `hMerge`/`vMerge` omitted. Pictures: `p:pic` + `a:blip r:embed` -> `imgTag` (png/jpg/gif/bmp; others `[image]`). Charts / SmartArt show `[chart]` / `[diagram]`. `mc:Fallback` subtrees are skipped (depth counter), like in docx.
- Notes: `notesSlide` found through the slide rels, text of its body placeholder only.
- NOT handled (known gaps): slide master/layout content (backgrounds, logos), picture crop/position, positions of shapes (reading order = file order, not top-left to bottom-right), WordArt, grouped-shape layout (children flow in order), real charts (values), SmartArt text, comments, animations, bullet inheritance (see above), numbered-list start values, colours/fonts/sizes, hyperlinks (text shown, no link), embedded video/audio, math (`a14:m`).
- Test idea (after first build): open `sample.pptx`. Expect 5 sections: (1) h2 `Sample Deck` + sub `Subtitle line`; (2) h2 `Bullets & <tags>` (escaped), `<ul>` with 3 `li` (second bold, third has margin-left) and a Notes box `Speaker note one`; (3) h2 `Table`, table with `td colspan="2"` `Merged head`, then `A1 | B1`, `Çay | 42`; (4) no h2, one `<img>` then `<p>Plain text box</p>`; (5) `Slide 5 (hidden)` + h2 `Hidden one`.
- Tag names rely on the usual `p:` / `a:` / `r:` / `mc:` prefixes (PowerPoint, python-pptx, LibreOffice all write them); a generator with other prefixes would show empty slides.

## Tier 2: odf in `OfficeText.kt` (session 5, not compiled)
`EXTS` is now `docx, xlsx, xlsm, pptx, pptm, odt, ods, odp, ott, ots, otp`; `toHtml` dispatches to `Odf.toHtml(file, ext)`. No new CSS (reuses `.tw`, `.sl`, `.sn`, `.nt`, `.sub`, `.r`, `.note`, `.ph`).
- One streaming pass over `content.xml` (names keep the `text:` / `table:` / `draw:` prefixes). A pre-pass reads styles (`styles.xml` fully, `content.xml` up to `office:body`): `style:text-properties` -> bold/italic/underline/strike/sup/sub bit mask (follows `style:parent-style-name`, max 6 levels), `text:list-style` -> which "name|level" are numbered (`list-level-style-number`).
- odt: `text:h` (outline-level -> h1..h6), `text:p`, `text:span` styles, `text:s` / `text:tab` / `text:line-break`, lists (`ul` / `ol`; nested list without style name inherits the parent's list style), tables (colspan, rowspan, covered cells omitted, nested tables), `draw:image` via `imgTag` (`Pictures/...`).
- ods: each top-level table = `<h2>` sheet name + table; cell text = the displayed `text:p` (so number/percent/currency/date formats are already formatted, unlike xlsx); `float/percentage/currency` cells right aligned; repeated empty cells/rows are capped (`number-columns-repeated` 16384 does not blow up); empty rows are skipped but at most 2 blank rows kept between content rows; caps 2000 rows x 50 cols (note says so); an all-empty sheet shows `(empty sheet)`.
- odp: one `<section class="sl">` per `draw:page` (`Slide N`), frame class `title` -> `<h2>`, `subtitle` -> `.sub`, header/footer/date-time/page-number frames dropped, `presentation:notes` text -> `.nt` box. Cap 300 slides.
- Skipped on purpose: footnotes/endnotes (`text:note`), comments, tracked-change deleted text, forms, styles blocks.
- NOT handled (known gaps): hidden slides/sheets, merged-cell text in ods beyond spans, charts/embedded objects (`draw:object`: nothing shown unless it has a preview `draw:image`), svg/wmf/emf pictures (`[image]`), shape positions (file order), headers/footers of odt pages, hyperlinks (text only), colours/fonts/sizes, paragraph-level alignment, text boxes in odt appear before their anchor paragraph, `text:h` numbering, ods column widths, `table:display="false"`.
- Test idea (after first build): `sample.odt` -> h1 `Title & bold` (escaped, `<b>bold</b>`), p with `<i>italic</i>`, 3 nbsp, `<br>`, `<ul>` 2 li, `<ol>` 2 li, table with `colspan="2"` `Merged`, then `A | Çay`, p `After` and NO `FOOTNOTE`. `sample.ods` -> `<h2>Data</h2>`, rows `Name | Qty`, `Apple | 3,00` (right aligned), no 16000 empty cells, no blank rows after; `<h2>Empty</h2>` + `(empty sheet)`. `sample.odp` -> 2 sections: (1) h2 `Sample Deck`, `<ul>` `Point one`, bold `Point two`, Notes `Speaker note`, no `FOOTER`; (2) h2 `Second`.
- Likely compile suspects: `when (n)` branches that are `if` without else (statement position, should be fine), `break@loop` inside `when` in `while`, the `private fun XmlPullParser.iv` extension inside the object, `Pair<Boolean, String>` row cells, `"&nbsp;".repeat(Int)`.

## Tier 2 plan (next sessions, each step small)
1. (DONE for docx, xlsx, pptx and odf, see above; kept for reference) `core/OfficeText.kt`: `fun toHtml(file: File, ext: String): String` using `java.util.zip.ZipFile` + `XmlPullParser` (no new dependency).
   - docx: `word/document.xml` -> `w:p` (style Heading1-6 -> h1-h6), `w:r/w:t`, bold/italic (`w:b`, `w:i`), lists (`w:numPr`), tables (`w:tbl/w:tr/w:tc`), images (`w:drawing` -> rel id -> `word/media/*` -> data URI, downscaled).
   - xlsx: `xl/sharedStrings.xml` + `xl/worksheets/sheetN.xml` (cells `c r= t=`), sheet names from `xl/workbook.xml`; cap 2000 rows x 50 cols per sheet with a "truncated" note.
   - pptx: `ppt/slides/slideN.xml` text (`a:t`), notes optional, images via rels.
   - odt/ods/odp: `content.xml` (`text:p`, `text:h`, `table:table-cell`).
2. Route `GET /api/officehtml?dev&path` (cache like tier 1).
3. `OfficeViewerActivity` (WebView, `loadDataWithBaseURL`, JS off, pinch zoom, "Open with" and "Share" in the menu) + `LSAndroid.viewOffice(json)` in `MainActivity.kt`.
4. UI fallback: in `viewOffice`, if `officepdf` fails, call tier 2 (needs a quick pre-check route `/api/officeok` that tests `converterIp()`, so the UI picks the tier before opening a reader).
5. Tests with small sample files per format (keep them in `app/src/test/resources`).

## Free-limit-friendly workflow for the next sessions
- Attach only this handover + the 3 touched files (`OfficeView.kt`, `Routes.kt`, `ui.html` is large: attach only the lines around 630 and 690) instead of the whole zip.
- One step per chat: (a) compile errors from the GitHub Actions log pasted as text (do this FIRST: nothing from Tier 1 or `OfficeText.kt` (docx + xlsx + pptx + odf) has been built yet; likely suspects in `Odf`: see its section above; in `Xlsx`: `when`/`break@loop` flow, `Math.floorDiv` Long overloads, `String.format` with Long args; in `Pptx`: local `fun endPara()` capturing several `var`s, `when` branches that are assignments, `continue` inside `when` inside `while`, `rels[... ?: continue]`; in the shared `imgTag`: nothing new, it is the old docx code moved), (b) ~~Tier 2 step 1 docx~~ done, (c) ~~xlsx~~ done (session 3), (d) ~~pptx~~ done (session 4), ~~(d2) odt/ods/odp~~ done (session 5), (e) route `/api/officehtml` + `OfficeViewerActivity` + `LSAndroid.viewOffice`, then the UI fallback in `viewOffice`. **(e) done in session 6; the next step is only: build, fix compile errors, device test.**
- Do not ask Claude to re-read `BrowserActivity.kt` / `ui.html` fully; they are 62 KB / 177 KB.
