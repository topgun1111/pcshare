## 16. Universal Search ("Everything for the phone") - multipart plan

> **2026-10-26 - TESTS REMOVED (owner decision):** all component tests and test workflows were deleted (`.github/workflows/usearch-test.yml`, `tools/*_selftest/`, every `*_test.*` / `*_smoke*` script). Anywhere below that says "self-test", "`tools/office_selftest`", "`usearch-test.yml`", "office-selftest" or "static_checks" is HISTORY: do NOT recreate or run them; the only CI check is the APK build. Treat the "Done when" lines that name a self-test as device-only checks.

> Paste this chapter into `HANDOVER.md` as chapter 16 (do not keep it as a separate top-level file), add a row to the status board (section 2) and one line per finished part to the changelog (section 14).
> Status of this chapter: **PLAN ONLY. Nothing is written, compiled or tested.** Created 2026-10-25.

### 16.0 How every session works (read first)

1. Obey the two standing rules at the top of HANDOVER.md (token economy; deliver the WHOLE app zip `pcshare-usearch-partN.zip`, root `pcshare/`, no `build/` `.gradle/`).
2. Do **one part per session**. Read only 16.0, 16.3 (decisions), the part's own block in 16.7, and the files that block lists under "Read". Use `grep` / `sed -n` ranges, never `cat` big files (`ui.html` is ~470 KB).
3. Pure logic goes in `core/usearch/` with **no `android.*` imports** (same rule as `core/office/`), so CI can run it on a plain JVM. Android glue lives in `Us*.kt` next to `NlSearch.kt` (plain Activity, no Compose, no AppCompat).
4. Code must compile on **Kotlin 1.9.24** (Android build) and **2.0.21** (self-test job). Gotchas: no `removeFirst()/removeLast()` on `java.util.List`, `org.json` drops `put(k, null)`, no `/*` inside KDoc.
5. At the end of the session: update the part's row in 16.6, write the part's "Handover note" (16.9 template) into 16.7, add the changelog line, bump nothing else. Be honest about what was compiled / run.
6. Every new feature gets a kill switch (16.3 D10). Never bump `ENGINE_ID` in `ClipEngine` (it discards the user's image index).

### 16.1 Goal and scope

**Goal.** One search box that answers instantly across this phone, like Everything on a PC: type, results appear as you type, no "search" button, no waiting for a folder walk.

**In scope (this phone's own storage, internal + SD / USB volumes from `Volumes.kt`):**
| # | Kind | How |
|---|---|---|
| 1 | File and folder **names** | always-on index, instant |
| 2 | **Text file contents** (txt, md, csv, json, xml, html, log, source code, subtitles...) | background content index |
| 3 | **Document contents** (docx xlsx pptx odt ods odp doc xls; PDF only if D6 says yes) | background, reuses `core/office` readers |
| 4 | **Images by meaning** ("dog on a beach") | existing CLIP index (`ImgSearch`) |
| 5 | **Text inside images** (OCR) | existing OCR text in `ImgSearch` |

**Out of scope for the first release (listed as Part 9 options):** other phones / PC / SMB / FTP search, archive contents, video / audio content, root storage.

### 16.2 What exists today (verified in the code of the 2026-10-25 zip)

- **Name search is a live walk, no index.** `LocalFs.search(v, q, limit=300, secs=15)`: recursive, lower-case substring, folders first. From the storage root it is "search everything": 20,000 results / 80 s. Every FS implements `Endpoint.search(v, q): SearchResult`. Route `GET /api/search?dev=&path=&q=` (`Routes.kt`).
- **Office content search is a live scan.** `LocalFs.searchOfficeContent` (`/api/search?content=1`): office files only, this phone only, 100 results / 20 s, skips files > 30 MB, needs >= 2 letters.
- **Image search** (`core/ImgSearch.kt`, `core/ClipEngine.kt`): own walk of image files, index in `<filesDir>/imgsearch/index.json` + `vec.bin` (path + mtime + size keyed, 512 float32 per picture), OCR text stored in the same index. `visual(q)` = CLIP text vector, cosine >= 0.20, top 60. `text(q, whole)` = Turkish-folded word / substring. Auto-scan: `ContentObserver` on `MediaStore.Images` (20 s debounce) + 10 min poll, battery < 20 % deferral. Folders: `img_incl` / `img_excl`. API `/api/imgs`. UI `isView()` in `ui.html` (`#gal` overlay). **Its first device test is still OPEN (11.1).**
- **OCR** (`OcrEngine`, `OcrTiled`, `PaddleOcr` + ML Kit, 11.2) already feeds the image index.
- UI today: path-bar search row (`NlSearch.kt`, twin of `#srow`), AI search overlay, per-FS search through the normal browser.
- Peers already expose `/p/imgsearch` (search runs ON the remote phone) - a pattern to copy later.
- Not present: any persistent name index, any text-content index, any unified result screen, any PDF text extraction (no PDF library by design; `PdfRenderer` only renders).

### 16.3 Decisions (defaults - the owner may overrule in any session; record changes here)

| ID | Decision | Default and why |
|---|---|---|
| D1 | Name index structure | Packed arrays in memory (folded name bytes, parent id, flags, size, mtime), persisted as one binary file `<filesDir>/usearch/names.bin` (`.tmp` then rename, manifest last, like `ImgSearch`). Target: 300k files ~ 20-30 MB heap (**estimate, measure in Part 1**) |
| D2 | Name matching | Linear scan of folded names per keystroke (cancel the previous scan). Target < 50 ms for 300k names (**estimate**). No SQL for names |
| D3 | Content store | One SQLite file `<filesDir>/usearch/content.db` (OS `android.database.sqlite`, no new dependency). Text is **pre-folded** (Turkish folding, see D5) before insert and before query, so the plain tokenizer is enough. Use FTS4; use FTS5 only if a runtime `CREATE VIRTUAL TABLE` try/catch proves it exists on the device (availability by Android version is not verified; minSdk is 24). OCR text goes into the same store (D7) |
| D4 | Result presentation | **Sectioned, not one mixed ranking**: Names, Contents, Pictures (text in image), Pictures (AI meaning). Scores of different engines are not comparable. A "Top" mixed list can come in Part 8 |
| D5 | Turkish handling | One shared `fold()` in `core/usearch/Fold.kt`: lower-case with `tr` rules, `ı -> i`, `İ -> i`, strip diacritics (ç ş ğ ö ü -> c s g o u) **only when the "Diacritics" toggle is off** (default off = Everything-like forgiving). Text-file reading detects UTF-8 / UTF-16 BOM / **Windows-1254** (old Turkish files) |
| D6 | PDF text | Not in v1 (no library, hand-written Flate + text-operator extractor is its own risky project). Part 6 ends with a go / no-go note; OCR of PDF pages is **not** planned (too slow) |
| D7 | Where OCR text lives | Keep `ImgSearch` as the owner of image vectors and OCR (do not rewrite it). `UsImageProvider` adapts it. Only if Part 7 measures a problem, mirror OCR text into `content.db` |
| D8 | Freshness | Own operations (copy / move / rename / delete / Bin / restore / extract / download / editors' Save) update the index **directly**. External changes: `ContentObserver` on `MediaStore.Files`, changed-directory walk (directory mtime) on start / resume, full deep refresh weekly while charging, manual "Rescan" button. Known limit: editing a file's content in place does not change its folder's mtime, so a plain external edit is caught by the MediaStore observer or the deep refresh, not by the folder walk |
| D9 | Privacy | Index is app-private (`filesDir`). Search **must respect**: Hide (paths in the hidden list, kept page-side as `HGAL` / `ls_galhid` per 6.4 - Part 3 must find how Kotlin reads it), Recycle bin (`Bin.kt`), `Android/data`, `Android/obb`, `.thumbnails`, cache dirs, user include / exclude folders (`us_incl` / `us_excl`, same idea as `img_incl` / `img_excl`) |
| D10 | Kill switches | `UniversalSearch.ENABLED` / `ls_us='0'` (whole feature), `us_content` (content indexing), `us_img` (image sections). Heavy indexing parts obey `us_charge` (only while charging) and the existing battery < 20 % deferral |

### 16.4 Architecture in words

```
 UsActivity (native screen, RecyclerView)  <--- also: /api/usearch for ui.html / PC browser
        |  query string
 core/usearch/QueryParser  -> Query(AST: terms, NOT, OR, phrases, filters, flags)
        |
 SearchService (core/usearch/) fans out, cancellable, streams sections
   |-- NameIndex   (names.bin, in memory)        -> Names section
   |-- ContentIndex(content.db, FTS)             -> Contents section
   |-- UsImageProvider -> ImgSearch.text / visual -> Pictures sections
        ^
 IndexManager (one scheduler, one progress card, pause / resume)
   Walker -> NameIndex (fast, first)
   then ContentExtractor queue (text -> office) throttled
   then ImgSearch auto-scan (OCR, CLIP) as today
   fed by: app-operation hooks, MediaStore.Files observer, dir-mtime delta, weekly deep refresh
```
Order of work in the background: names (minutes) -> text contents -> office contents -> OCR / CLIP (slowest, already existing).

### 16.5 Query language (Everything-style, built in Part 2)

- Space = AND. `|` = OR. `!word` = NOT. `"exact phrase"`. `*` and `?` wildcards. `re:` = regex.
- Filters: `ext:jpg;png`, `kind:image|video|audio|doc|text|archive|folder`, `size:>10mb`, `size:1mb..5mb`, `dm:today|yesterday|thisweek|2026-10`, `path:download`, `in:/DCIM` (limit to folder), `depth:<3`.
- Scope prefixes: `content:word` (text + documents), `ocr:word` (text inside pictures), `ai:dog on beach` (picture meaning). Plain words search **names** and, when nothing else is typed, also the other sections (a toggle "Search everywhere" controls this).
- Toggles in the UI: Match case, Whole word, Match path, Diacritics (D5), Regex.
- Sorting: name, path, size, date modified, best match. Columns: name, path, size, date.

### 16.6 Parts and status board

| Part | Title | Depends on | State |
|---|---|---|---|
| 0 | Gate: green build + image-search device check | - | open |
| 1 | Name index core (Walker, NameIndex, persistence, JVM test) | 0 | open |
| 2 | Query engine (parser, matcher, ranking, Fold) | 1 | open |
| 3 | Index manager and freshness (hooks, observers, rules, settings) | 1 | open |
| 4 | API + native screen v1 = **"Everything for names"** (first device milestone) | 2, 3 | open |
| 5 | Text-file content index | 3 | open |
| 6 | Document contents (office) + PDF go / no-go + open-at-match | 5 | open |
| 7 | Images: OCR text + AI meaning inside the unified search | 4 (needs 0) | open |
| 8 | Unified UI polish (sections, snippets, thumbnails, history, scope) | 4-7 | open |
| 9 | Optional: peers / PC / archives / shortcut tile | 8 | open |
| 10 | Hardening: benchmarks, device checklist, cleanup | all | open |

Parts 5, 6, 7 can be done in any order after Part 4. Parts 1-4 are the critical path (name search alone already replaces today's slow walk).

### 16.7 Part blocks

Each block: **Goal / Read / Do / Do not / Tests / Done when / Handover note** (the last line is filled by the session that finishes the part).

#### Part 0 - Gate (small, do first)
- **Goal:** do not build on a red base. Confirm latest CI run is green (APK + `office-selftest`), and run the image-search device checklist 11.1 steps 1-5 (compile, download, selftest, cosine, 5k-picture scan).
- **Read:** 11.1 only. **Do:** owner runs the checklist; the session records results in 11.1 and in this part. If model download or OCR fails, Part 7 is blocked, Parts 1-6 are not.
- **Done when:** results written down. **Handover note:** _pending_

#### Part 1 - Name index core
- **Goal:** a persistent, packed, in-memory index of every file and folder name on the phone.
- **Read:** `LocalFs.kt` (`search`, `isLink`, `real`, `vnorm`, `vjoin`), `Volumes.kt`, `Endpoint.kt` (`Item`), how `ImgSearch` persists (`.tmp` + manifest-last).
- **Do:** `core/usearch/NameIndex.kt` (add / remove / rename subtree / path of id / iterate), `Walker.kt` (iterative, skips per D9, never follows symlinks, reports progress, cancellable), `IndexStore.kt` (save / load `names.bin`, version number, tolerates truncated file), `Fold.kt` (D5). JVM self-test `tools/usearch_selftest/Main.kt` on a generated temp tree (100k names): build, save, load, rename a folder with children, delete, path rebuild, Turkish names. New CI job `usearch-selftest` copied from `office-selftest` (strict: counts `ok` lines, goes red on `FAIL` / crash).
- **Do not:** touch UI, `ImgSearch`, or `LocalFs.search` yet.
- **Tests:** JVM self-test in CI. Measure and write down: files / second of the walk and bytes per entry (on device in Part 4; in the sandbox use the generated tree only for correctness).
- **Done when:** self-test green in CI; handover lists the real `names.bin` format. **Handover note:** _pending_

#### Part 2 - Query engine
- **Goal:** turn a typed string into matches over `NameIndex`, instantly.
- **Read:** 16.5, Part 1 note, `ImgSearch.text()` folding regex for whole-word.
- **Do:** `QueryParser.kt` (AST per 16.5, graceful on half-typed input such as an open quote), `Matcher.kt` (substring / wildcard / regex / whole-word / case / diacritics flags, filters ext / kind / size / date / path / in / depth), `Ranker.kt` (exact name > name prefix > word start > substring; shallower path and newer date as tie-breakers), sort + paging, cancellation token checked every few thousand names. Extend the JVM self-test (parser table of 60+ query strings, matcher cases, timing on the 100k tree).
- **Do not:** content / AI scopes yet - the parser must already **accept** `content:`, `ocr:`, `ai:` and return them as separate sub-queries for later parts.
- **Done when:** self-test green; a table "query -> expected AST" saved as a fixture file. **Handover note:** _pending_

#### Part 3 - Index manager and freshness
- **Goal:** the index stays correct without the user ever pressing refresh.
- **Read:** `Jobs.kt` (where copy / move / delete / rename finish), `Bin.kt`, `LanShareService.kt`, `Core.start`, `ImgSearch.startAutoWatch`, `Cfg.kt`, how hidden paths are stored (6.4, `HGAL`).
- **Do:** `IndexManager.kt` (single daemon thread, states idle / scanning / paused, wake lock via `Core.holdAwake`, `us_charge`, battery < 20 % deferral, progress object); first scan after the first screen like `startAutoWatch`; hooks: after every successful file operation call `IndexManager.changed(paths)`; `ContentObserver` on `MediaStore.Files` (debounced); changed-directory delta walk on start and resume; weekly deep refresh when charging; settings keys `us_on`, `us_incl`, `us_excl`, `us_charge`; hidden-path and Bin filtering (find the cleanest way for Kotlin to read the hidden list, or have the page push it through the Bridge like palette pushes).
- **Do not:** build UI beyond a Settings > Search page skeleton (rows, no results screen).
- **Tests:** JVM test for delta logic (simulated directory mtimes). Device: add / rename / delete a file in the app and in another app, watch the index follow.
- **Done when:** compiles in CI; the hook list in the handover is complete (every operation in `Jobs` accounted for). **Handover note:** _pending_

#### Part 4 - API and native screen v1 ("Everything for names")  **[first device milestone]**
- **Goal:** usable instant name search on the phone.
- **Read:** `NlSearch.kt` (style and theme use), `StorageList.kt` / `NativeList.kt` (RecyclerView pattern), `NlDrawer.kt` + main menu code (entry points), `AppPal.kt`, `Thumbs.make`, `MainActivity.Bridge`.
- **Do:** `GET /api/usearch?q=&limit=&offset=&sort=` and `GET/POST /api/uindex` (status, rescan, pause) in `Routes.kt`; add `/api/usearch` to `InProc.ROUTES` only if profiling shows a need; `UsActivity` (search field with instant results, kind chips All / Files / Folders / Images / Docs / Video / Audio, sort menu, columns name + path + size + date, bold match highlight, result count + "indexing 63 %" banner, thumbnails for images / videos, tap = open with existing viewers, long-press multi-select = Copy / Share / Details / Open containing folder / Delete via existing actions); entry points: drawer item "Search everything", main-menu tile, and the path-bar search row gets a "search whole phone" action. Old per-folder search stays untouched as fallback.
- **Tests:** device checklist (16.11). Record real numbers: first scan time, files per second, heap, ms per query for the phone's file count.
- **Done when:** owner confirms on device: type -> results in well under half a second, results stay right after rename / delete. **Handover note:** _pending_

#### Part 5 - Text-file content index
- **Goal:** `content:word` and plain words find text inside text files.
- **Read:** `TextEditorActivity.kt` (how it reads / detects encoding), `CodeHl.kt` (extension list), `ImgSearch` for the throttle / checkpoint pattern.
- **Do:** `ContentStore.kt` (SQLite per D3, schema: doc id = NameIndex id, mtime, size, folded text; FTS table; upsert / delete by id), `TextExtractor.kt` (extension allow-list + binary sniff, size cap default 5 MB per file and a total budget setting, UTF-8 / UTF-16 BOM / Windows-1254 detection, strips markup for html / xml), queue in `IndexManager` (after names; skip unchanged mtime + size; checkpoints; resumable), query path `ContentIndex.search(q, flags)` returning doc id + snippet window + line number, "Contains" vs "Whole word" per D3 (state the real behaviour of the tokenizer used), Settings rows (content on / off, max file size, extensions list). JVM test for extractor encodings with fixture files (UTF-8, UTF-16, cp1254 Turkish sample, binary).
- **Do not:** office files.
- **Done when:** self-test green, device: search for a word that exists only inside a .txt in a deep folder. **Handover note:** _pending_

#### Part 6 - Document contents + open at match
- **Goal:** `content:` also covers Word / Excel / PowerPoint / ODF documents; results open at the match when possible.
- **Read:** `core/office/OfficeOpen.kt`, `OfficeExport.kt` (`containsOrFalse`, `isOffice`), `DocxText.kt`, `OoxmlPackage.kt`, 8.3 table.
- **Do:** `DocExtractor.kt` (plain text out of each format using the existing readers, per-file time cap, skip > 30 MB as today, never throw - one bad file must not stop the queue), store in `content.db` with a `kind` column, snippets with sheet name / slide number / page where the reader knows it, open-at-match: `TextEditorActivity` jump to line, office viewer opens the file (cell / slide jump only if the viewer already supports it - otherwise record as limit). **PDF go / no-go note:** after Part 5 and this part, write a short decision (D6): size of a minimal text extractor vs value; default stays "no".
- **Done when:** self-test uses the existing `tools/office_selftest` fixtures (`sample.docx` etc.); device: find a word inside a docx and an xlsx. **Handover note:** _pending_

#### Part 7 - Images in the unified search
- **Goal:** `ocr:` and `ai:` (and plain words when "Search everywhere" is on) return pictures; no rewrite of `ImgSearch`.
- **Read:** 11.1 / 11.2, `ImgSearch.kt` (`visual`, `text`, `isOut`, discovery), `ClipEngine.kt` (`ENGINE_ID` - do not bump).
- **Do:** `UsImageProvider.kt` (adapter: `ocrText(q, whole, dir)` and `meaning(q, dir)` mapped to result rows with score and "AI" / "OCR" badge; works when the model is missing = section shows "install model" card linking to the existing download); unify discovery: images come from `NameIndex` (extension filter) instead of `ImgSearch`'s own walk **only if** it can be done without discarding the existing index (path + mtime + size key stays); schedule OCR / CLIP after text indexing in the single `IndexManager` progress card; share include / exclude folders (`us_*` are the master, `img_*` kept in sync or merged - decide and record); AI threshold slider (default 0.20 as today); **idea to evaluate:** CLIP is English-trained, so Turkish queries ("kedi") match poorly - test on device; if poor, add a small bundled Turkish -> English word map (a few thousand common nouns) used only for the `ai:` query, with a toggle.
- **Do not:** change the CLIP preprocessing, the vector format or `ENGINE_ID`.
- **Done when:** device: `ocr:` finds a photographed receipt by a word on it; `ai:` finds a known photo; both results open in the existing image viewer with the result list. **Handover note:** _pending_

#### Part 8 - Unified UI polish
- **Goal:** one coherent results screen.
- **Do:** sections per D4 with section headers and counts, a "Search everywhere" toggle, content snippets with highlighted match and line number, image sections as a thumbnail grid, filter toggles from 16.5, scope chip "in this folder" (long-press a folder -> Search here), recent queries + saved searches, empty state that explains what is still being indexed, light / dark theme through `AppPal`. Optional: a mixed "Top" list if real use shows the need.
- **Done when:** owner review on device. **Handover note:** _pending_

#### Part 9 - Optional extras (each independent, pick by value)
Peers: `/p/usearch` mirroring `/p/imgsearch` (the search runs on the remote phone; results merged by device). PC web UI entry through `ui.html` using `/api/usearch`. Names inside archives (`ArchiveFs`). Home-screen shortcut / quick tile (`Shortcuts.kt`). Export results list. **Handover note:** _pending_

#### Part 10 - Hardening
Benchmarks on the owner's phone (files, scan time, heap, ms / query, battery for one night of indexing); stress cases (SD card removed while indexing, 1M files, huge folder, unreadable folders, very long names, emoji names); tune caps; remove unused code introduced here; update section 2 + 15 (source map) + 3; close this chapter's open items. **Handover note:** _pending_

### 16.8 Risks and unknowns (carry forward, remove when resolved)

1. Walk speed of `File.listFiles` on the owner's phone is unknown; if the first scan is too slow, evaluate `MediaStore.Files` as an accelerator (not as the source of truth, because it may not list every file type on every Android version).
2. FTS5 availability on older Android versions is not verified (D3); FTS4 is the safe default.
3. Index size / heap numbers in D1 and D2 are estimates.
4. Hidden-path list lives page-side (`ls_galhid`); the Kotlin side may not be able to read it without a new Bridge call (Part 3).
5. Image search has never had a device test (Part 0); `ClipTokenizer` was written from memory.
6. Foreground-service / battery policies of the owner's phone (aggressive task killers) may stop long indexing; resumable checkpoints are mandatory in every indexer.
7. Content search of very large text sets can make `content.db` big; the total-budget setting in Part 5 exists for this reason.
8. External in-place file edits are only caught by the MediaStore observer or the weekly deep refresh (D8).

### 16.9 Handover note template (copy into the part block when a part ends)

```
Part N - <title> - <date>
State: compiled by CI? run on JVM? device-tested?   (be honest, one line each)
Files added / changed: <paths>
Formats / schemas fixed in this part: <names.bin layout, tables, route shapes>
Settings keys / kill switches added: <...>
Numbers measured: <...>
Deviations from the plan (and why): <...>
Open problems for the next part: <...>
Next session start: Part N+1, read 16.0, 16.3, block N+1, plus: <extra files>
```

### 16.10 Session start prompt (the owner pastes this, with the zip attached)

> Project pcshare, universal search. Do Part N of HANDOVER.md chapter 16 only. Follow 16.0 (token economy, whole-app zip `pcshare-usearch-partN.zip`). Read only the files that part lists. Update the part's handover note, the 16.6 status row and the changelog before zipping. Ask nothing unless a decision in 16.3 blocks you.

### 16.11 Device checklist (grows with each part; stop at the first failure)

- Part 4: first scan finishes and shows progress; typing shows results while typing; rename / delete / move inside the app updates results at once; the same change made in another app shows up within a minute; hidden items and Recycle-bin items never appear; SD card volume searched if present; Turkish names (`ÇŞĞİıÖÜ`) with Diacritics on and off; `ext:`, `size:`, `dm:`, `!`, `|`, quotes, wildcard; select + Copy / Share / Open containing folder; rotation and dark theme.
- Part 5: word inside a deep `.txt`; a Windows-1254 file with Turkish letters; a 20 MB log is skipped per the cap; indexing survives killing the app (resumes).
- Part 6: word inside docx, xlsx, pptx, odt; a corrupt office file does not stop indexing.
- Part 7: `ocr:` on a photographed page; `ai:` on a known photo; model-missing card; results open in the viewer with swipe.
- Part 10: one night of indexing on battery and on charger, read the battery stats.
