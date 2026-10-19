# HANDOVER - Word / Excel support (2026-10-19)


## STATUS SUMMARY (read this first)
This zip is the FULL app (all parts below included). Compile state: only `core/office` + `tools/office_selftest` were ever compiled (kotlinc 2.0.21, JVM) and self-tested, and only up to Part 6c. **Everything else is UNCOMPILED and NOT device-tested**: the Android side (`OfficeViewerActivity`, `MainActivity`, `Routes.kt`, `LocalFs.kt`, `ui.html`, manifest) and the Part 8 code (`OfficeExport.kt`, `OfficeThumb.kt`). First step next session: push -> let Actions build -> paste compile errors.

### Completed
| Part | What | State |
|---|---|---|
| 1 | Core + xlsx reader (`MiniXml`, `OoxmlPackage`, `NumFmt`, `XlsxModel`, `XlsxReader`) | done, compiled + JVM-tested |
| 2 | docx reader (`DocxModel`, `DocxReader`) | done, compiled + JVM-tested |
| 3a / 3b | `DocxHtml`, `XlsxHtml`, `OfficeViewerActivity` (WebView, find, zoom) | done, viewer UNCOMPILED |
| 4 | xlsx editing + creation (`XlsxEditor`, `XlsxWriter`) | done, compiled + JVM-tested |
| 5 | docx editing + creation (`DocxEditor`, `DocxWriter`) | done, compiled + JVM-tested |
| 6a / 6b / 6c | `.xls` (`OleFile`, `XlsReader`), `.doc` (`DocReader`), `.odt/.ods` (`OdfReader`), `OfficeOpen` dispatcher, password message | done, compiled + JVM-tested |
| 7 | UI integration: open + hand-off from file list, manifest VIEW + SEND filters, embedded-preview thumbnails route (`OfficeThumb`, `/api/thumb`), "Word document" / "Excel workbook" in + menu (`LSAndroid.newOffice`), viewer Edit actions (xlsx: edit cell, insert row; docx: find/replace, add paragraph; uploaded back to the source at once) | done except items below, UNCOMPILED |
| 8 (partial) | `OfficeExport` (CSV of a sheet, plain text, content match), `LocalFs.searchOfficeContent`, viewer "Print / save as PDF" and "Export sheet as CSV" | partial, UNCOMPILED |

### Missing / TODO
- **Part 7:** launcher `Shortcuts` (plan line unclear; `Shortcuts.kt` only publishes send-text targets); `ui.html` grid tiles do not yet request `/api/thumb` for office files (route only); `.ppt/.pptx` untouched.
- **Part 3 leftover:** `Routes.kt` `/api/office/*` so the PC web UI can preview office files.
- **Part 8:** Routes endpoint + `ui.html` toggle for office content search (`searchOfficeContent` is not called by anything; SMB/remote folders not covered); `pcprint.py` / `tools/phone_explorer.py` PC-side preview; big-file streaming; delete dead `DocxText.kt` and the docx branch in `TextEditorActivity`; more tests.
- **Known limits:** no undo for viewer edits; edits only when opened from the file list (not via VIEW/SEND from other apps); editors cover docx/xlsx only (doc/xls/odt/ods read-only); see "Known gaps" below for format limits (theme colours, shared formulas, locale).


## Goal
Replace the read-only "docx -> Markdown text" viewer (`core/DocxText.kt`, used by `TextEditorActivity`) with proper Office support:
view .docx / .xlsx (and .xlsm/.xltx, later .doc/.xls/.odt/.ods), edit text/cells in place without destroying the rest of the file,
create new files, wire into the file manager UI, search, print/export.

## Design decisions (keep these)
- **No new Gradle dependencies** (no Apache POI - too big/slow on Android, needs API 26+ for some parts). Own streaming parsers.
- Package `com.lanshare.app.core.office`. Pure Kotlin + `java.util.zip` only, **no `android.*` imports** in `core/office`, so it can be unit-run on a plain JVM.
- Own XML pull parser (`MiniXml`) instead of `android.util.Xml` -> JVM-testable, local names only (prefix stripped; use `rawAttr("r:id")` when needed).
- Files are opened from a **local File** (callers copy remote/SAF/SMB files into cache first, like PdfViewerActivity does).
- Editing strategy (Parts 4-5): **patch the XML in place** and re-zip, copying every other entry byte-for-byte (styles, images, charts, macros survive). Never regenerate a whole workbook/document from the model.
- Limits protect the phone: 64 MB per part, 5000 rows x 200 cols / 250k cells per sheet by default (`XSheet.truncated` tells the UI).

## The 8 parts
| # | Part | State |
|---|------|-------|
| 1 | **Core + Excel reader**: `MiniXml`, `OoxmlPackage` (zip + rels), `NumFmt` (number/date formats), `XlsxModel`, `XlsxReader` | **DONE (uncompiled)** |
| 2 | **Word reader**: `DocxModel` + `DocxReader` - paragraphs, runs (b/i/u/strike/color/size/highlight/sub/sup), styles & heading levels, numbering (bullets/numbers, levels), tables (merged cells, widths), hyperlinks, images (word/media, rels), headers/footers, footnotes, page breaks, text boxes (text only) | **DONE (uncompiled)** |
| 3 | **HTML renderers + viewer**: `DocxHtml`, `XlsxHtml` (sticky header/row numbers, column letters, frozen panes, merged cells, sheet tabs, cell styles), `OfficeViewerActivity` (WebView, find-in-page, zoom), image extraction as data: URIs/cache; `Routes.kt` endpoint `/api/office/*` so the PC web UI can preview too | **3a DocxHtml DONE (uncompiled)**; **3b DONE (uncompiled; API assumptions verified against Part 1 sources)**: `XlsxHtml`, `OfficeViewerActivity`; `Routes.kt` TODO |
| 4 | **Excel editing + creation**: `XlsxEditor` - set cell value/formula/type in sheet XML (inline strings, style kept), add/delete/rename sheet, insert/delete row, save via temp file + swap; `XlsxWriter` for brand-new workbooks (CSV -> xlsx) | **DONE (uncompiled)** |
| 5 | **Word editing + creation**: `DocxEditor` - replace paragraph text keeping run formatting of the first run, insert/delete paragraph, find & replace across split runs, table cell edit; `DocxWriter` for new documents (from Markdown/plain text); undo stack | **DONE (uncompiled)** |
| 6 | **Legacy + ODF**: `.xls` (BIFF8 via OLE compound file: SST, number formats, dates), `.doc` (text from WordDocument stream + piece table, read-only), `.odt` / `.ods` (content.xml) -> same models as Parts 1-2 | **Split 6a / 6b / 6c.** 6a DONE (uncompiled): `OleFile` + `XlsReader`. 6b DONE (compiled + JVM-tested): `DocReader`. 6c DONE (compiled + JVM-tested): `OdfReader` + `OfficeOpen` dispatcher, wired into `OfficeViewerActivity` |
| 7 | **UI integration**: `ui.html` (open .docx/.xlsx/.xls/.doc/.odt/.ods from the browser list, preview pane `PV_OFFICE`, "Edit" actions, file-type icons, "new Word/Excel file" in the + menu), `TextEditorActivity` hand-off (remove docx special-case), `AndroidManifest` intent filters (open-with / share-to), thumbnails for office files (`Thumbs.kt`), `Shortcuts` | **7 DONE (uncompiled)** except launcher `Shortcuts` (not done, scope unclear) |
| 8 | **Search, print, export, polish**: office text in the folder keyword search (`ImgSearch`/`NlSearch` family), CSV/PDF export of a sheet, print via existing `PrintPrep`, password-protected files message, big-file streaming, HANDOVER cleanup, `pcprint.py` / `tools/phone_explorer.py` PC-side preview, tests | **8 PARTIAL (uncompiled)**: `OfficeExport` (text, CSV), `LocalFs.searchOfficeContent`, viewer Print + CSV export done; Routes/UI hookup of content search, PC-side preview, `DocxText.kt` removal TODO |

## Part 1 - what exists (all new files in `app/src/main/java/com/lanshare/app/core/office/`)
- `MiniXml.kt` - pull parser: `next()` -> START/END/TEXT/EOF, `attr(local)`, `rawAttr(raw)`, `readText()`, `skip()`, `isEmptyElement()`, entity + `_xHHHH_` OOXML unescape, UTF-8/16 BOM decode. CDATA, comments, PIs, DOCTYPE handled.
- `OoxmlPackage.kt` - `ZipFile`-backed, case-insensitive part lookup, size cap, `rels(part)` with target resolution (`../`, absolute `/xl/..`, external links flagged), `read(name)`, `xml(name)`.
- `NumFmt.kt` - built-in ids 0-49 + custom codes: General, 0 / 0.00 / #,##0 (thousands, trailing-comma scaling), %, E+00, literals/quotes/`\x`/`_x`, `[$USD]`, `[Red]` ignored, 2-3 sections (neg/zero), dates & times (y m d h m s, mmm/mmmm, ddd/dddd, AM/PM, `[h]:mm`, fractional seconds), 1900 (with the fake 1900-02-29) and 1904 systems, `isDate()` classification.
- `XlsxModel.kt` - `XCell(row, col, text, raw, kind, formula, style)`, `XSheet` (sparse rows, merges, col widths, hidden rows/cols, frozen panes, `truncated`), `XStyles` (xfs/fonts/fills/numFmts), `XRef` (A1 <-> col,row, ranges).
- `XlsxReader.kt` - `XlsxReader(file).use { it.sheets; it.readSheet(i, maxRows, maxCols, maxCells) }`: workbook.xml sheets (hidden / veryHidden), shared strings (rich text joined, phonetic skipped), inline strings, str/bool/error/date cell types, formatted display text, formulas kept as text, styles.
- `tools/office_selftest/Main.kt` + `sample.xlsx` - JVM self-test (format cases + parser + dumps a workbook):
  `kotlinc app/src/main/java/com/lanshare/app/core/office/*.kt tools/office_selftest/Main.kt -include-runtime -d /tmp/ost.jar && java -jar /tmp/ost.jar tools/office_selftest/sample.xlsx`
  Expected on sample.xlsx: sheet "Data": A1=Name B1=Date C1=Total; A2="Rich text" B2=2023-03-15; C2=1,234.50 {=SUM(D2:D3)}; A3=inline, C3=TRUE; frozen 1/1; 1 merge; sheet "Hidden" hidden=true.

## Part 2 - what exists (`core/office/`)
- `DocxModel.kt` - `DDoc(body, header, footer, footnotes, endnotes, defaultSize, truncated, plainText())`; blocks `DPara` (runs, heading 1-9, align, list `label`/`bullet`/`level`, indent twips) and `DTable` (grid, rows, `DCell` span / vMerge 0-1-2 / fill); `DRun(kind TEXT|TAB|BREAK|PAGE|IMAGE|NOTE, bold/italic/underline/strike/caps/color/highlight/size(half-pt)/script, link, image: DImage(part, widthEmu, heightEmu, alt))`.
- `DocxReader.kt` - `DocxReader(file).use { it.read(maxParagraphs = 20000) }`. Style chain (basedOn) + docDefaults resolved into run/paragraph props; heading level from style name `heading N` or outlineLvl; numbering labels computed (decimal/letters/roman/bullet, per-level counters, `%1.%2` texts); hyperlinks (external rel or `#anchor`); `mc:AlternateContent` uses Choice only; `w:sdt` unwrapped; text boxes emitted as extra paragraphs after the host paragraph; `ins` kept, `del` dropped; default header/footer of the first section only.
- Images: `DImage.part` is the package path (e.g. `word/media/image1.png`) - Part 3 reads bytes via `OoxmlPackage.read`. Size: EMU (VML sizes converted).
- Self-test: `java -jar /tmp/ost.jar tools/office_selftest/sample.docx` should print: `P[h1] Title` / `P<center> *Bold and plain<fn1>` / `P[1.] First` / `P[2.] Second` / `P link{https://example.com/}` / `TABLE rows=2 grid=[2000, 2000] borders=true` (cell span=2 "Merged"; cells A, B) / `footnotes=[1] defSize=22 trunc=false` / `fn1: Note text`.
- Part 2 known gaps: not compiled; numbering `lvlOverride/startOverride` ignored; table-style borders/shading only via `borders` flag (no conditional formatting); theme colours unresolved (`color` only when `w:val` is RRGGBB); section-specific / first-page / even headers ignored; complex fields (`fldChar`) show cached result only; tracked-change deletions dropped; no tab stops / paragraph spacing / line height yet (add in Part 3 if the renderer needs them).

## Part 3a - DocxHtml (`core/office/DocxHtml.kt`)
`DocxHtml.render(doc, image = { DImage -> src? }, title)` -> full HTML string. `image` returns a data: URI/file URL (null -> `[alt]` placeholder); the viewer supplies it via `OoxmlPackage.read(part)`. Handles headings, align, list labels (hanging indent), merged runs, b/i/u/strike/caps/colour/highlight/size/sub/sup, links (http/https/mailto/tel/# only), tabs, breaks, images, footnote refs + footnote list, tables (colspan, vMerge->rowspan, fill, borders, header rows), header/footer. Self-test now also writes `<file>.docx.html`.
## Part 3b first cut
- `XlsxHtml.render(sheet, tabs, activeIndex)` - sticky column letters + row numbers, sparse rows (empty runs collapsed into colspan), numeric-looking text right-aligned, tab bar (`lsoffice://sheet/N`). **Part 1 API assumptions verified by reading the sources.** Now also renders: `XSheet.rows` is `Map<Int, List<XCell>>`, `XSheet.maxRow/maxCol` 0-based, `XCell.col` 0-based Int, `XCell.text: String`, `XRef.colName(Int)` 0-based, `XlsxReader.sheets` items have `.index/.name/.hidden`, `readSheet(i)` has default limits. merges (colspan/rowspan), column widths, hidden rows/cols. Missing: frozen panes (`frozenRows/Cols`), cell styles/theme colours (`XCell.style`).
- `OfficeViewerActivity` (package `com.lanshare.app`, plain `Activity`; move/adjust package to match `PdfViewerActivity`): extra `path` = local file; docx via `DocxHtml` with images inlined as data: URIs (<3 MB each), xlsx via `XlsxHtml`; JS disabled, file access off, only `lsoffice://` navigation handled; find-in-page SearchView in action bar (needs a theme WITH ActionBar, else no menu); pinch zoom. Errors incl. password-protected message.
- Manifest entry added (`Theme.LANShare`, check it has an ActionBar for the Find menu). Nothing launches the activity yet (Part 7). `Routes.kt` `/api/office/*` still TODO.
- Next: push -> Actions build -> paste compile errors; then 3b leftovers (frozen panes, cell styles, `/api/office/*` in Routes.kt), then Part 4 (Excel editing).

## Part 4 - what exists (`core/office/XlsxEditor.kt`, `XlsxWriter.kt`; no `android.*`)
- `XlsxEditor` (object, each call = load -> patch -> temp file -> sanity open -> swap, `.bak~` kept until swap worked; throws, original untouched on error): `setCells(file, sheet, List<CellEdit(row, col, value, asText)>)`, `setCell`, `insertRows(file, sheet, at, count)`, `deleteRows`, `renameSheet`, `addSheet(file, name): Int`, `deleteSheet`. Rows / cols / sheet are 0-based; sheet = index from `XlsxReader.sheets`.
- Typing rules (`XValue.kind`): `=...` formula, number (<=15 chars, `007` stays text), true/false bool, else text; empty = clear content, cell style (`s`) kept; `asText` forces text. Text = **inline strings** (no sharedStrings change; reader handles them, Excel converts on save). Numbers use `.`; "1,5" stays text.
- Only touched parts are rewritten; every other zip entry is copied. Any edit drops `calcChain.xml` (+ rel + content type) and sets `calcPr fullCalcOnLoad`. `<dimension>` is removed (optional element). Row / cell order kept sorted.
- `deleteSheet`: refuses the last visible sheet, removes rel, content type, part, sheet rels, `definedName`s with that `localSheetId` (higher ones renumbered), `activeTab` / `firstSheet`. `addSheet` / `rename`: name 1-31 chars, no `[]:*?/\\`, unique case-insensitive.
- `insertRows` / `deleteRows` shift row + cell refs and merges. **Not adjusted (known limits):** formulas anywhere, defined names, conditional formatting / data validation ranges, hyperlinks, drawings, table parts; sheet rename does not rewrite formulas that use the old name.
- `XlsxWriter`: `createEmpty`, `fromCsv(out, csv, sheetName, delimiter?, headerBold)` (auto delimiter `, ; tab`, quotes, BOM), `write(out, List<WSheet>)`, `parseCsv`. Styles: xf 0 default, xf 1 bold header; header row frozen when more than 1 row; column widths guessed. Dates are not detected (stay text).
- Self-test: `java -jar /tmp/ost.jar --edit` (needs the kotlinc build above) round-trips create -> edit -> insert / delete rows -> add / rename / delete sheet and prints OK / FAIL. **Not run yet (no kotlinc in the authoring sandbox).**
- Risk spots for the compiler: `when` with `d ->` (variable as branch condition) in `XlsxWriter.parseCsv`, `Regex.replace` lambdas with `return@replace` in `shiftMerges`, the `Session` inner helpers. Still unused by any UI (Part 7).
- Next: Part 5 (`DocxEditor` / `DocxWriter`), same patch-in-place pattern; reuse `XValue.esc`.

## Part 5 - what exists (`core/office/DocxEditor.kt`, `DocxWriter.kt`; no `android.*`)
- `DocxEditor` (object, same patch-in-place + temp file + sanity open (incl. `DocxReader`) + swap as Part 4; only `word/document.xml` / header / footer / footnote parts are rewritten): paragraph index = outermost `<w:p>` of the body in document order, table-cell paragraphs included, text-box paragraphs excluded. `paragraphCount`, `paragraphText`, `setParagraphText(file, i, text)` (keeps the paragraph's pPr + first text run's rPr; `\t` tab, `\n` break), `insertParagraph(file, at, text, like = -1)` (copies pPr of `like` / previous paragraph, `at == count` appends before `sectPr`), `deleteParagraph` (only paragraph of a table cell or a section-break paragraph is emptied instead), `cellText` / `setCellText(file, table, row, col, text)` (0-based, outermost tables, extra cell paragraphs removed, nested-table cells refused), `replaceAll(file, find, replace, matchCase = true, wholeWord = false): Int` (body + headers + footers + footnotes/endnotes, matches across split runs, replacement goes into the run where the match starts, file untouched when 0).
- **Undo**: before every successful edit the old file is kept as `<name>.undo1~` (older ones shift up to `.undo10~`). `undo(file): Boolean`, `undoDepth`, `clearUndo` (UI should call it when the editor closes).
- Refused with a message (file untouched): `setParagraphText` / `setCellText` / `deleteParagraph`-emptying on paragraphs with images, drawings, fields (`fldChar`, `fldSimple`, `instrText`). Text replace drops hyperlinks / bookmarks inside the edited paragraph. `replaceAll` keeps them (only `<w:t>` texts change). No tracked changes. Only the `w:` prefix of the main namespace is detected (any prefix name works), main part must be `word/document.xml`.
- `DocxWriter`: `createEmpty`, `fromText(out, text)` (one line = one paragraph), `fromMarkdown(out, md)`: `#`-`######`, bullets / numbered lists (2-space indent = level, each numbered list restarts at 1), `>` quote, ``` fences (Consolas), `---` rule, pipe tables (header row shaded + repeated), inline bold / italic (`*`, `_`) / `~~strike~~` / `` `code` `` / `[t](http|mailto|tel)` links as real hyperlinks. A4, Calibri 11, styles `Heading1-6`, `ListParagraph`, `Hyperlink`; lines are NOT joined (notes style). No images yet.
- Self-test: `java -jar /tmp/ost.jar --docx` (kotlinc build as above) creates -> edits -> undoes and prints OK / FAIL. **Not run (no kotlinc in the authoring sandbox); bracket balance checked by script only.**
- Risk spots for the compiler: `Doc.use { }` on a private `AutoCloseable` class, `Regex.split` with zero-width lookarounds in `DocxWriter.run`, `StringBuilder.append(xml, last, start)` in `replaceIn`, the `when` without subject in `DocxWriter.runsOf`. Unused by any UI (Part 7).
- Next: Part 6 (legacy `.xls` / `.doc` + ODF `.odt` / `.ods` readers into the Part 1-2 models).

## Part 6a - what exists (`core/office/OleFile.kt`, `XlsReader.kt`; no `android.*`)
- `OleFile(file)`: OLE2/CFB reader (header, FAT + DIFAT, mini-FAT/mini-stream, directory). `open(name): ByteArray?` (stream by name, case-insens.), `has(name)`, `entries`, `OleFile.isOle(File)`. Reused by 6b (`.doc`: streams `WordDocument`, `0Table`/`1Table`). `EncryptedPackage` stream = password-protected xlsx/docx.
- `XlsReader(file).use { it.sheets; it.readSheet(i, maxRows, maxCols, maxCells) }` - **same API and models as `XlsxReader`** (`XSheet`, `XCell`, `XStyles`, `XSheetInfo` with `part = ""`), so `XlsxHtml.render(sheet, tabs, i)` works unchanged. Handles: BIFF8 only, worksheets (hidden / very hidden), SST + CONTINUE, LABEL/LABELSST/NUMBER/RK/MULRK/BOOLERR/FORMULA cached value (+STRING), FORMAT + built-in ids -> `NumFmt` (dates, 1904), XF (font, fill colour, halign, wrap, valign), PALETTE, MERGEDCELLS, COLINFO, hidden rows, frozen PANE (WINDOW2), nested BOF (charts) skipped.
- Gaps: formula text not decoded (`XCell.formula == null`, cached value shown); BIFF5 / Excel 95 throws "BIFF5 not supported"; FILEPASS throws `IllegalStateException("File is password protected")` (the viewer's catch already prints a message; add a `password` match); no rich text, comments, BLANK/MULBLANK style-only cells; fills = one entry per XF (`XXf.fillId` = xf index).
- Self-test: `java -jar /tmp/ost.jar tools/office_selftest/sample.xls` (branch added to `Main.kt`; `sample.xls` made with `soffice --convert-to xls` from sample.xlsx). Expect the same cells as the xlsx run (Name/Date/Total, 1,234.50, TRUE, 1 merge, frozen 1/1, sheet "Hidden" hidden). **Not run: no kotlinc in the authoring sandbox**; brackets balanced by script.
- Risk spots for the compiler: `continue` inside `when` inside `while` (init loop), local funs `put` / `num` mutating captured `var`s in `readSheet`, `arrayOf(null, "left", ...)` type inference for `hal` / `val_`.
- **Next: 6b** `DocReader` (FIB, piece table in 1Table/0Table, text -> `DDoc` paragraphs split on `\r`, special chars 0x07 cell mark / 0x0C page / 0x13-0x15 fields, read-only). **6c** `OdfReader` (`.odt` -> `DDoc`, `.ods` -> `XSheet`; content.xml via `OoxmlPackage` + `MiniXml`) and an `OfficeOpen` dispatcher by magic bytes (zip+`mimetype`/`xl/`/`word/`, OLE + `Workbook`/`WordDocument`) so `OfficeViewerActivity` / Part 7 need no per-format code; wire `.xls` into the viewer there.

## Part 6b - what exists (`core/office/DocReader.kt`; no `android.*`) - COMPILED + JVM-tested
- `DocReader(file).use { it.read(maxParagraphs = 20000): DDoc }` - same model as `DocxReader`, so `DocxHtml.render(doc, ...)` works unchanged. Read-only.
- FIB (nFib >= 0xC1, i.e. Word 97-2003; Word 6/95 throws), `fEncrypted` -> `IllegalStateException("File is password protected")` (same text as `XlsReader`), table stream by `fWhichTblStm`. Text = piece table (Clx pair 33): compressed pieces cp1252, others UTF-16LE; fallback one run from `fcMin` when no Clx. Main text only (`ccpText`).
- Characters: `\r` paragraph end, `\a` (0x07) cell / row end, 0x09 tab, 0x0B line break, 0x0C page break, 0x1E -> `-`, 0x13/0x14/0x15 fields (instruction dropped, result kept; `HYPERLINK "url"` -> `DRun.link`, `\l` -> `#bookmark`), other control chars dropped.
- Tables: PlcfBtePapx (pair 13) -> paragraph FKPs -> sprmPFInTable (0x2416) / sprmPFTtp (0x2417) per paragraph CP range; row ends from fTtp, cells from 0x07, several paragraphs per cell kept. Without PAPX a guess from the 0x07 marks is used. Column widths NOT read: `grid` = equal 1400-twip columns, no merges, `borders = true`.
- Not read: character formatting (no bold / size / colour), styles (no headings, no list labels), images, headers / footers, footnotes, text boxes, comments. Add by reading STSH + CHPX FKPs (pair 12) if wanted.
- Self-test: `java -jar /tmp/ost.jar tools/office_selftest/sample.doc` (`sample.doc` made with `soffice --convert-to doc` from sample.docx). Expect: `P Title` / `P Bold and plain` / `P First` / `P Second` / `P link{https://example.com/}` / `TABLE rows=2 grid=2` (row 1: one cell "Merged"; row 2: A, B). Also checked a table with empty cells + a 2-paragraph cell + Turkish letters (all correct).
- **Fixed in Part 1 while compiling:** `OoxmlPackage.kt` KDoc contained `_rels/*.rels` -> Kotlin nested-comment "Unclosed comment" broke the whole file (and everything using it). Now `_rels/NAME.rels`. With this the whole `core/office/` package + `Main.kt` compiles cleanly; `--edit`, `--docx` self-tests print no FAIL.
- **Open bug (Part 5, not touched):** `java -jar /tmp/ost.jar --docx` prints `FAIL replace split runs` (`DocxEditor.replaceAll` across split runs); everything else OK. First thing for a later session.
- Compile it yourself: kotlinc 2.0.21 from `github.com/JetBrains/kotlin/releases` works in the sandbox (see command under Part 1).
- **Next: 6c** `OdfReader` (`.odt` -> `DDoc`, `.ods` -> `XSheet`) + `OfficeOpen` dispatcher by magic bytes (zip + `word/` / `xl/` / `mimetype`; OLE + `WordDocument` -> `DocReader`, `Workbook` -> `XlsReader`, `EncryptedPackage` -> password message); wire `.doc` / `.xls` / ODF into `OfficeViewerActivity` there.

## Part 6c - what exists (`core/office/OdfReader.kt`, `OfficeOpen.kt`; no `android.*`) - COMPILED + JVM-tested
- `OdfReader(file)`: `.odt` -> `read(): DDoc` (same model as DocxReader), `.ods` -> `sheets` / `readSheet(i, ...)` (same `XSheet` as XlsxReader). Read-only. Encrypted (manifest `encryption-data`) -> `IllegalStateException("File is password protected")`; `.odp` refused.
- odt: styles from styles.xml + content.xml (parent chain): bold/italic/underline/strike/caps/colour/size(pt)/sub-sup/background -> nearest highlight name; paragraph align / margins / page-break-before; `text:h` outline level; lists with number/bullet labels per level; spans, links, `text:s`/tab/line-break; images (`Pictures/..`, `DImage.part`), text boxes (text only), footnotes/endnotes; tables with col widths, header rows, colspan/rowspan (-> `span` / `vMerge` 1-2), cell fill. Not read: headers/footers, tracked changes, percent font sizes, fields beyond their cached text.
- ods: formatted text from `text:p` (as saved by the producer), kinds NUMBER/DATE/BOOL/TEXT, formula converted `of:=SUM([.A1:.A3])` -> `=SUM(A1:A3)`, repeated rows/cols (capped), merges, col widths, hidden cols/rows/sheets, frozen panes from settings.xml (**frozen not verified: LibreOffice headless export wrote no split info**).
- `OfficeOpen(file)`: detects by content (zip: `mimetype` -> ODF, `xl/workbook.xml` -> xlsx, `word/document.xml` -> docx; OLE: `Workbook` -> xls, `WordDocument` -> doc, `EncryptedPackage` -> password message). API: `format`, `isSheet`, `editable`, `sheets`, `readSheet(i)`, `readDoc()`, `image(part)`. `OfficeViewerActivity` now uses it for all six formats.
- Self-test: `java -jar /tmp/ost.jar tools/office_selftest/sample.odt|sample.ods` (made with soffice from sample.docx/xlsx). odt expect: h1 Title / Bold and plain+fn / 1. First / 2. Second / link / table (Merged span=2; A, B). ods expect: sheet Data cells as xlsx, 1 merge (2x2 at A5-ish), sheet Hidden hidden.
- Not compiled against android.jar: `OfficeViewerActivity` edit (only `core/office` compiled on JVM).
- **Next: Part 7** UI integration (ui.html, TextEditorActivity hand-off, manifest intent filters incl. .doc/.xls/.odt/.ods).

## Known gaps / things to double-check in Part 1
- Never compiled. Likely trouble spots: `when` branches with `;`-free formatting in `NumFmt.fmtDate`, `break`/`continue` inside `when` in `XlsxReader.readSheet`, unused-variable warnings.
- Conditional sections like `[>100]0.0;0.00` are not evaluated (first/second/third section rule only). Fractions (`# ?/?`) approximate to decimals. Locale: always English month/day names and `.`/`,` separators.
- Shared formulas: only the master cell carries the formula text; followers show value only (`formula = null`).
- Theme/indexed colours are not resolved (only `rgb`), so fills/fonts with theme colours come out `null` -> Part 3 should map the default Office theme palette.
- Date detection uses the number format only (as Excel does); cells typed `d` (ISO text) are shown as-is.
- Encrypted workbooks (OLE container instead of zip) throw in `ZipFile` -> UI must catch and say "password protected" (Part 8).
- Existing `DocxText.kt` is untouched and still used by `TextEditorActivity`; remove it only after Part 3/7 replace it.

## Existing code to reuse / respect
- `core/ZipEdit.kt` - in-place zip rewrite with rollback; model for Parts 4-5 save logic (docx/xlsx are zips; its extension list already includes them).
- `core/PrintPrep.kt` / `PrintPreview.kt` / `WifiPrint.kt` already classify office files for printing - Part 8 should not duplicate.
- `TextEditorActivity.kt` lines ~448-761: docx special-case (`DOCX_MAX`, read-only banner).
- `assets/ui.html` line ~1638 `ED_MORE` (editable ext list, contains `docx`) and ~2473 `PV_OFFICE`.
- Handover style of this repo: short, factual, "derlenmedi" flags. Older handovers are Turkish; this one is English.

## Part 7 - what exists (uncompiled, not device-tested)
- `ui.html`: new ext kind `office` (doc docx odt xls xlsx ods); both click dispatchers call `nativeOffice(i)` -> `LSAndroid.viewOffice`; falls back to the old path if the bridge is missing. `docx` removed from `ED_MORE`, so `TextEditorActivity` no longer receives Word files (its docx code is now dead; `DocxText.kt` can go in Part 8).
- `MainActivity.viewOffice(json)`: downloads `url` to `cache/office/` (60 MB cap, files >1 h old purged), starts `OfficeViewerActivity` with `path`.
- `OfficeViewerActivity`: if no `path` extra, copies `intent.data` / `EXTRA_STREAM` to cache. Manifest: `exported=true` + VIEW filter (content/file, six office MIME types).
- TODO: `Thumbs.kt` office thumbnails, `Shortcuts`, "New Word/Excel" in + menu, Edit action wired to Part 4-5 editors, SEND filter, `.ppt/.pptx` untouched.

## Part 8 - what exists (partial, UNCOMPILED: no kotlinc in the authoring sandbox)
- `core/office/OfficeExport.kt` (no `android.*`): `sheetToCsv(XSheet)` (RFC 4180, UTF-8 BOM, CRLF, hidden rows/cols skipped, shown text), `csvName`, `text(file)` (docx/doc/odt body+header+footer; sheets as `## name` + tab rows; 400k char cap), `textOrNull`, `containsOrFalse(file, q)` (false for encrypted/corrupt/>30 MB), `isOffice(name)`.
- `LocalFs.searchOfficeContent(v, q)`: recursive content search over office files (100 results, 20 s). **Not yet called by anything**: needs a `Routes.kt` endpoint (e.g. `/api/search?content=1`) + a ui.html toggle; SMB/remote folders not covered.
- `OfficeViewerActivity`: overflow menu "Print / save as PDF" (`WebView.createPrintDocumentAdapter`, so it goes through the system print dialog / PcPrintService) and "Export sheet as CSV" (`ACTION_CREATE_DOCUMENT`, request code 77, current sheet only). Menu is rebuilt with `invalidateOptionsMenu()` after load.
- Self-test: `java -jar /tmp/ost.jar --export tools/office_selftest/sample.xlsx` prints text, CSV and contains() results (also works on .xls/.ods/.docx/.doc/.odt).
- Password-protected message already existed (Part 6c `OfficeOpen`); no change.
- **TODO (Part 8 rest):** Routes/UI for content search; `pcprint.py` / `tools/phone_explorer.py` preview; big-file streaming; delete dead `DocxText.kt` + its `TextEditorActivity` docx branch; Part 7 leftovers (thumbnails, Shortcuts, "New Word/Excel", Edit action, SEND filter).

## Part 7 - completed (UNCOMPILED, not device-tested)
- **SEND filter**: `OfficeViewerActivity` also accepts `ACTION_SEND` for the six office MIME types (reads `EXTRA_STREAM`, already handled).
- **Thumbnails**: `core/office/OfficeThumb.kt` returns the preview image embedded by the producing app (`docProps/thumbnail.*`, `Thumbnails/thumbnail.png`). `Routes.kt` `/api/thumb` serves it for office files, 404 when absent (files made by this app, .doc/.xls). **`ui.html` grid tiles do not request thumbs for office files yet** (only the route exists).
- **New Word / Excel**: `ui.html` + menu entries "Word document" / "Excel workbook" -> `doNewOffice` -> `LSAndroid.newOffice({dev,dir,kind})` -> `MainActivity.newOffice` (free name "New document (2).docx", `DocxWriter/XlsxWriter.createEmpty`, written with `Jobs.ep(dev).write`, then `lsRefresh()`).
- **Edit action** (docx/xlsx opened from the file list; `viewOffice` now passes `dev` + `rpath` to the viewer): xlsx = "Edit cell..." (`XlsxEditor.setCell`) and "Insert row..."; docx = "Find and replace..." (`DocxEditor.replaceAll`, case-insensitive) and "Add paragraph...". Each edit runs on the cache copy, is uploaded back at once (`Jobs.ep(dev).write`), then the viewer reloads (same sheet). No undo; no edit when opened via VIEW/SEND from another app (no source path).
- **Not done**: launcher `Shortcuts` (the part 7 plan line is ambiguous; `Shortcuts.kt` today only publishes send-text targets), `.ppt/.pptx`.
