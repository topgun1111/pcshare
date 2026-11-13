# LANShare - Project Handover (slim index)

> **ARCHIVE RULE (owner, 2026-11-06) - ALWAYS: (1) send back ONLY the files you changed or added, never the whole project; (2) keep the full folder structure (paths relative to the project root, e.g. `app/src/main/java/...`, `.github/workflows/...`); (3) always archive in `.tar.xz` format (`tar -cJf name.tar.xz <changed files>`). Never zip.**

> **WARNING (owner, 2026-11-05) - TOKEN ECONOMY: always watch token use, never waste tokens (short replies, read only what is needed, no recaps). Obey this ALWAYS, unless there is a serious danger of a code error.**

> **SLIM HANDOVER RULE (owner, 2026-11-13) - this file is small on purpose.** Read only sections 1-4 below. Open a chapter file from `docs/handover/` ONLY when the task touches that area, and then with grep / `sed -n` ranges, never whole. Do not read `LUMEN.md` unless the task is a Lumen part. Never open `docs/handover-full-2026-11-13.tar.xz` (backup of the old full file, git history also has it). Chapters 16, 17, 19b, 20 and 22 start with a state banner (Universal Search, Google Drive, PDF image viewer, Recycle bin, Lumen): read it before touching those areas.

> **READ FIRST - CHANGE LOG RULE (owner, 2026-10-26) - do this in EVERY session that changes the app, before you zip**
> - The app's **update dialog shows a change log BEFORE the download button** (what was added / removed / changed, for every version the user skipped). It comes from **`CHANGELOG.md`** in the repo root: CI (`build-apk.yml`, step *Release notes*) publishes the **TOP `## ` section** of that file as the GitHub release body, and `UpdateCheck.kt` (`changeView` / `notesText`) renders it (green **Added**, red **Removed**, accent for Changed / Fixed).
> - **Session checklist (never skip):** (1) add a NEW section at the TOP of `CHANGELOG.md` (below the note lines): `## YYYY-MM-DD - short title`, then `### Added` / `### Removed` / `### Changed` / `### Fixed` with `- ` bullets, plain words for the owner, one line per visible change, no file names; (2) only then zip. A session that changed the app but did not touch `CHANGELOG.md` is **not finished**.
> - Never edit older sections of `CHANGELOG.md`. Only the top section is published; if nobody adds a new top section, CI warns and the release says "Changes not listed for this build." (identical text to the previous release is treated the same, so a stale zip cannot re-publish old notes).
> - Docs-only or tooling-only sessions (nothing the user sees) need no new section.

> **READ FIRST - standing rule from the owner (2026-10-22)**
> - **Token economy.** All coding is done on *free* Claude accounts, so tokens are scarce. Do not waste them: read only the files you need (grep / `sed -n` ranges, never `cat` whole big files), no long explanations or repeated summaries, patch with small scripted edits instead of rewriting files, no unrequested extras, keep replies short.

One handover for the whole project (Android app + PC helper tools). It replaces the ten separate `HANDOVER*.md` files, which were merged on 2026-10-21; their full text stays in git history (last commit that still has them: `ce433bc`).

**How to use this file**
- Start at section 1 (what the project is) and section 2 (status board), then open the chapter file of the area you work on (index below). Every chapter has the same shape: *Summary, Files, How it works, Limits / not done, Test checklist*.
- Add new work to the **chapter file of its area** and the changes to **`CHANGELOG.md`** only (rule at the top; there is no changelog in this file or in `docs/handover/`). Do not add new top-level `HANDOVER_*.md` files and do not stack dated notes at the top of this file.
- Section 3 is the single prioritised list of open work; remove an item there when it is done.

**Chapter files** (all in `docs/handover/`; section numbers in the text refer to these)

| File | Chapter | KB |
|---|---|---|
| `ch05-platform-core.md` | 5. Platform core (server, protocol, jobs, file systems) | 25 |
| `ch06-ui-layer.md` | 6. UI layer (ui.html, native list, main menu, gallery) | 21 |
| `ch07-viewers-editors.md` | 7. Viewers and editors (video, image, PDF, text) | 10 |
| `ch08-office-files.md` | 8. Office files (Word / Excel / PowerPoint) | 77 |
| `ch09-printing.md` | 9. Printing and the PC print service | 27 |
| `ch10-text-sharing-phone-explorer.md` | 10. Text sharing and Phone Explorer | 13 |
| `ch11-image-search-ocr.md` | 11. Image search and OCR | 11 |
| `ch12-storage-analyzer.md` | 12. Storage Analyzer (+ WizTree view) | 12 |
| `ch13-performance.md` | 13. Performance | 4 |
| `ch14-changelog.md` | 14. Old developer changelog (frozen archive, do not read or edit) | 111 |
| `ch15-appendix.md` | 15. Appendix | 4 |
| `ch16-universal-search.md` | 16. Universal Search ("Everything for the phone") - multipart plan | 62 |
| `ch17-google-drive.md` | 17. Google Drive (multipart plan, 11 parts) | 35 |
| `ch18-usb-otg.md` | 18. USB / OTG drives | 2 |
| `ch19a-remote-shell-terminal.md` | 19. Remote shell = real terminal (REMOTE_SHELL_PLAN.md, 5 parts, all coded 2026-10-30) | 11 |
| `ch19b-pdf-image-viewer.md` | 19. PDF image viewer (multipart plan, 4 parts) | 17 |
| `ch20-recycle-bin.md` | 20. Recycle bin folder view (multipart plan, 3 parts) | 10 |
| `ch21-remote-shell-transport.md` | 21. Remote shell transport + Upload & run (merged 2026-11-05 from chapter 21, state of 2026-10-31) | 13 |
| `ch22-lumen-completion-pass.md` | Lumen completion pass - 2026-11-08 | 40 |

---

## 1. Project at a glance

**What it is.** LANShare is an Android file manager that browses and moves files between this phone, other LANShare phones, SMB / FTP / SFTP servers and a Windows PC on the same network, and also prints, shares text, searches pictures with AI and analyses storage. Pure Kotlin; no Python any more.

**Architecture (one picture in words)**
- **Kotlin core** (`core/*.kt`): an HTTP server (`MiniHttp`, a port from the 8765-8784 range in the Python era - check `Core`) with routes in `Routes.kt`, a job engine (`Jobs`), file systems behind one interface (`Endpoint`: `LocalFs`, `RemoteFs`, `SmbFs`, `FtpFs`, `SftpFs`, `RootFs`, `ArchiveFs`), UDP/TCP discovery (`Discovery`), config in `<filesDir>/lanshare.json` (`Cfg`).
- **UI** = `app/src/main/assets/ui.html` (one large page, ~470 KB) in a `WebView`, plus native Kotlin views painted on top for speed (native file list, path bar, chrome, drawer). `MainActivity.Bridge` is the JS <-> Kotlin interface (`LSAndroid.*`). Quick local GETs are answered in-process (`InProc`) instead of over TCP.
- **Native screens** (plain `Activity`, no Compose, no AppCompat): video player, image viewer / editor, PDF viewer, text editor, Office viewer, Storage Analyzer, Inbox (text sharing).
- **Foreground service** `LanShareService` keeps the process, server and discovery alive; wake / Wi-Fi / multicast locks are held once in `Core.start` (`holdAwake`).
- **PC side** (Windows, Python): `pcprint.py` (print + text inbox service on port 8799, with a small Tk window) and `tools/phone_explorer.py` (opens the phone's FTP share in Explorer; built to an .exe by `build-phone-explorer-exe.yml`).

**Stack and build**
- Gradle (AGP 8.5.2, Kotlin 1.9.24), `minSdk 24`, `targetSdk 34`, ABI filter `arm64-v8a` only (ONNX Runtime + ML Kit took the APK from ~8 MB to ~110 MB before the filter).
- **Editions (2026-10-29):** CI builds TWO APKs from one commit IN PARALLEL (`build-apk.yml`: job `prep` = version + shared versionCode, matrix job `build` over `edition: [full, lite]` = two runners, job `release` = downloads both artifacts, one GitHub release with both APKs; `fail-fast: false`, release only if both pass) and attaches both to the release: `LANShare-v<ver>.apk` (full) and `LANShare-v<ver>-lite.apk` (`gradle -Pedition=lite`). Lite has its own applicationId `com.lanshare.app.lite` (`applicationIdSuffix`, label "LANShare Lite" via manifest placeholder `appLabel`) so BOTH can be installed side by side; data / settings / Drive sign-in are NOT shared; both use the same ports (2121 / 41234 / 41235 / MiniHttp), so run one at a time. Same signing key and the SAME versionCode (computed once in the *Sürüm* step, passed as `VERSION_CODE`); because the applicationId differs they do NOT replace each other (the shared versionCode only keeps the numbers in step); lite adds `-lite` to versionName. Lite leaves out `liteExclude` (top of `app/build.gradle.kts`: `fonts/office/**`, `fonts/ff_*.ttf`, `fonts/tnr-*`, `fonts/urbanist-*`; Figtree stays) by deleting those files from the merged assets (`merge*Assets` `doLast`; the source tree is untouched) and `BuildConfig.LITE` is true: `Fnt.LIST` = Figtree + System only, `OfficeViewerActivity.fontUrl` = null (no `@font-face`). `UpdateCheck.pickApk` downloads only the asset of its own edition (`-lite.apk` suffix). CI fails if a lite APK still holds `assets/fonts/office/` or `ff_*`, and prints both sizes in the run summary. To trim more in lite: add patterns to `liteExclude` and guard the feature with `BuildConfig.LITE`. ONNX runtime (image search + PaddleOCR) is ALSO out of lite (2026-10-29): `compileOnly` in lite, so only `ClipEngine.kt` / `PaddleOcr.kt` (the only files importing `ai.onnxruntime`) compile against it; every path that could create `ClipEngine.Impl` / `PaddleOcr` is guarded by `BuildConfig.LITE` (`ClipEngine.tryLoad/install/uninstall/installed`, `PaddleModels.installed/installAsync`, `AutoOcr.recognize` = ML Kit only); `ClipEngine.state()` carries `lite:true` so ui.html shows a note instead of the Download button. CI fails if the lite APK holds `libonnxruntime`. NEW code touching `ClipEngine.Impl`, `PaddleOcr`, `OrtSession` must be guarded the same way, else lite throws NoClassDefFoundError at runtime. Not compiled / not device-tested.
- CI `.github/workflows/build-apk.yml`: builds on push to `main` / `master` and on manual dispatch (dispatch also creates a release + tag). Version = `VERSION` (major, now `2`) + next free tag number; `versionCode` = minutes since 2026-01-01 UTC; signed release only when the repo secret `KEYSTORE_B64` (+ `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) exists. Keystore: `keytool -genkey -v -keystore release.jks -alias lanshare -keyalg RSA -keysize 2048 -validity 10000`, then `base64 -w0 release.jks` into the secret.
- Dependencies worth knowing: ONNX Runtime Android (CLIP + PaddleOCR), ML Kit text recognition (unbundled Play Services artifact), Media3 / ExoPlayer 1.3.1 (player), `viewpager2`, `recyclerview` 1.3.2, `swiperefreshlayout`, smbj (SMB), commons-compress 1.21 (kept at 1.21 for minSdk 24) + xz 1.9 (archives), zxing core 3.5.3 (QR), core-ktx 1.13.1. **No** Apache POI / PDF library / Compose by design.
- Language: UI text is English with a few Turkish strings (e.g. the update dialog "Bu sürümü atla"). Older handovers were partly Turkish; this one is English.

**Wire protocol** is unchanged from the Python era: old Python builds and Kotlin builds interoperate (`/p/*` routes between devices, UDP beacon port 48555).

---

## 2. Status board

> **Read this first.** The status flags below are copied from the source handovers (dated 2026-10-04 ... 2026-10-19), where almost every Android-side change is marked *"not compiled / not device-tested"* because the authoring sandbox had no Android SDK. Since then the app **has been built by CI and run on a phone**: the performance report of 2026-10-20 was taken on a device and PR #66 is merged to `main` (HEAD `ce433bc`). So "not compiled" below really means *"never confirmed in the notes"*. Re-confirm by running the test checklist of the chapter before relying on a feature, and update the cell.

Legend: **C** = compiled by CI as recorded, **D** = device-tested as recorded, **JVM** = unit-run on a plain JVM / Python, `-` = not recorded.

| Area | Chapter | State as recorded | Notes |
|---|---|---|---|
| Kotlin core, routes, jobs, in-process API | 5 | built and in daily use | gotchas in 5.1 |
| Google Drive: sign-in + saved accounts (Part 1 of 11), HTTP layer `GdApi` (Part 2) and listing `GdriveFs` (Part 3 of 11), write + mkdir (Part 5 of 11: copy into Drive), read + wiring (Part 4 of 11: Drive accounts show in the device list, browse / search / download) | 17 | Parts 1-11 coded 2026-10-28, not compiled, not device-tested | needs Google Cloud setup (17.2, done) + a device with Google Play services; writes arrive in Parts 5-6 |
| Resumable transfers (`.lspart`) | 5.4 | LocalFs logic JVM-tested; SMB / Remote / Routes / Jobs wiring not compiled | 2026-10-18 |
| Root support (`su`) | 5.6 | not compiled, not device-tested | needs a rooted phone |
| Archives read (7z, tar*, gz, bz2, xz, zip family) | 5.7 | not compiled | RAR5 / encrypted 7z unsupported |
| Archive editing (zip family, 7z, tar*) | 5.7 | `ZipEdit` JVM-tested; `ArcEdit` not compiled | |
| Recycle bin, progress card + bubble | 5.8, 5.9 | UI checked in Chromium with mocks; Kotlin not compiled | |
| Native list / chrome / drawer / path bar | 6.2 | not compiled in the notes, but shipping | switches `NativeList.HEAD`, `NlChrome.ENABLED`, `NlDrawer.ENABLED` |
| Main menu (home), gallery, view options, folder style, Hide | 6.3-6.8 | ui.html JS syntax-checked; Kotlin not compiled | |
| Video player, image viewer, image edit, PDF viewer | 7 | not compiled / not device-tested in the notes | |
| Office viewer / editors (docx xlsx xls doc odt ods pptx odp) | 8 | `core/office` JVM-compiled and self-tested (to part 6c / 10c); Android side never confirmed | |
| Printing (PC, Wi-Fi IPP, this phone) and `pcprint.py` | 9 | `pcprint.py` tested on Linux with stubs, Windows paths not run; app side not confirmed | |
| Text sharing (phone + PC) | 10.1 | code complete, not compiled, not device-tested | PR branch `feat/text-inbox` |
| Phone Explorer | 10.2 | coded, not compiled, not run on Windows | |
| Image search (CLIP) | 11.1 | code complete; first device test open | |
| OCR (ML Kit + PaddleOCR) | 11.2 | session 9: build green and device test OK for the basic version; later work not compiled | |
| Storage Analyzer | 12 | all 81 sources compiled against android-34 (0 errors in the storage files); mirror test 4000 / 4000; device test open | WizTree view uncompiled |
| Performance (cold start 533 -> 335 ms) | 13 | measured on a phone 2026-10-20, merged | debug tab still in |
| Universal Search (Everything for the phone) | 16 | Parts 1-4 coded 2026-10-25, not compiled, not run; CI job `usearch-selftest` (minimum 190 ok) first run pending; Parts 0, 5-10 open | `core/usearch/`, `core/IndexManager.kt` |

---

## 3. Open work (prioritised)

**U. Universal Search** - plan in chapter 16. Parts 1, 2, 3 and 4 are coded but never compiled / run: read the result of CI job `usearch-selftest` AND the APK build (Part 3 touched `LocalFs`, `Bin`, `Jobs`, `Core`, `Cfg`, `Routes`, `MainActivity`, `ui.html`), fix, record the measured numbers (the `info` lines) in the Part 1 / 2 / 3 notes, then do Part 4. Part 0 (green CI + image-search device check 11.1) is still open.

**G. Google Drive** - plan in chapter 17. Parts 1-11 all coded, never compiled. Do next: build (CI), then the device checklist 17.7k.

**0. Print space** - feature complete in code (grid + free placement, safe margins, Keep apart, side / corner handles, Recent prints sessions, PDF pages). Left: build (`./gradlew assembleDebug`, paste the first compiler error), then the device checklist at the end of 9.7. Newest code, most likely to hold compile errors: `PrintSessions.kt`, `PrintSpace.adopt` / `add` / `addStream` / `addPdf`, `SheetLayout` free + `clampFree` / `freeHit` / `packFree`, `ImageViewerActivity.addToPrintSpace`, `MainActivity.shareToPrintSpace`, `PrintSpaceShareAlias` in the manifest. Also open: Chromium gesture test of the free stage, JVM test of `PsLayoutJson`, Chromium test of the Recent prints list.

**A. Verification debt (do first, cheap, high value)**
1. Run the **checklist of each chapter** on a device and update section 2. Highest risk (never confirmed): Office Android side (8), Storage Analyzer device test (12), Image search first device test (11.1), resumable transfers over SMB / remote (5.4), Root (5.6), text sharing (10.1), Phone Explorer (10.2).
2. Known compile-risk spots are listed per chapter ("Risk spots"). If CI is green they are moot; if not, paste the first error.

**B. Performance (section 13 has the numbers)**
3. Find why page load is still 157 ms (baseline 102): add marks around `WifiPrinters.start` / Discovery; try deferring `WifiPrinters.start`; take two more cold-start reports to separate variance.
4. Delay `/api/folderstat` and `/api/wifiprinters` until after the first screen; cache the printer result.
5. Reduce repeated native layout pushes (`nlLay` / `nlChPush` / `nlSrPush` ~460 calls each in 8 s); poll `/api/peers` less when the Main menu / drawer is closed.
6. Cut WebView construction (82 ms): androidx.webkit async start-up or build the WebView first. Gain <= 80 ms, uncertain.
7. Merge `claude/defer-image-engine` is done (PR #66). Close the old PR for `claude/swipe-light-transition` (empty diff). Remove the PERF-DEBUG code (`core/PerfMarks.kt`, Settings > Debug: performance) when tuning is finished.

**C. Features that were left open on purpose**
8. **ui.html view options**: send `md` + `gc`, persist `cols` / `thumb m` / `ff` in `ls_g` / `ls_pf` (note of 2026-10-10 says the page side was missing; later entries say it was added - verify in code).
9. Resume: hash check of a kept partial, Root / archive sources, purge of the receiver's partial after a user Cancel (only the 7-day purge today).
10. Root: Storage Analyzer scan as root, faster root video thumbnails, recycle bin for root deletes.
11. Office: office thumbnails and content search for remote / SMB; `.ppt` binary; pptx / odp shapes and layout; big-file streaming (search skips > 30 MB, `/api/office` refuses > 60 MB); formula adjustment after row insert / delete; theme colours.
12. Text sharing: long-press a device chip to set the default, unread badge, attachments in the inbox, Settings row for the switches; consider removing the "LANShare Send" share alias (three LANShare entries in the share sheet today).
13. Image search: tune threshold 0.20 / top 60 after real scores; flat / memory-mapped `vec.bin` above ~50k pictures; peers / SMB pictures.
14. OCR: Turkish letters check (device test reported no problem); `OcrSmart.kt` is dead code (see 15.2).
15. Storage Analyzer: verify `Bin.trash` of a folder with an unreadable child; `loadSaved` time for a 400,000-node tree; `StorageVolume.getPath()` on the Vivo phone; WizTree "Allocated" size, sort options in File View, light theme.
16. Home screen: "every start vs fresh launch only" is still unanswered (current: fresh launch only, never on a share intent); native splash briefly draws the last folder before the menu.
17. PC: `pcprint.py` older than v22 / v28 / v26 lacks print queue / screen mirror / text inbox: the app shows "update pcprint.py". Run the newest file once on the PC (it restarts the old service by itself).

**D. Housekeeping**
18. Delete dead code (list in 15.2, ~200 KB APK + clarity). Stale `text-sharing.patch`, one-off `tools/patch_pcprint.py`.
19. Source comments still point at the old handover names (`HANDOVER_OCR.md`, `HANDOVER_IMAGE_SEARCH.md`, `HANDOVER_HOME_MENU.md`, `HANDOVER_ZIP_EDIT_AND_WIZTREE.md`, `HANDOVER_OFFICE.md`, `HANDOVER_STORAGE_ANALYZER.md`, `HANDOVER_TEXT_SHARING.md`). Update them to "HANDOVER.md section N" when touching those files.
20. `HANDOVER_NATIVE_LIST.md` (native list phases 1-11, switches, sections 2b-2p) is referenced everywhere but was **never in the repo**. If you still have it, add it as chapter 6.2 detail; otherwise 6.2 is the only record.

---

## 4. Working conventions

- **Delivery**: the project was developed in short chat sessions with no Android SDK, so every change is delivered as files to copy over the repo (paths are repo-relative). Storage Analyzer parts were delivered as the FULL app zip without `.git`, `build/`, `.gradle/`; for other work the user prefers a **minimal zip with only the changed files**. Ask if unsure.
- **Change log (every session)**: new top section in `CHANGELOG.md` for anything the user can see - see the READ FIRST rule at the top of this file. It is what the update dialog shows before the download.
- **Honesty flags**: every note says what was compiled / run and what was not. Keep doing that. "JS syntax-checked with node", "braces balanced by script", "mirror test" are weaker than a build.
- **Build loop**: push -> Actions build -> paste the compiler errors -> fix. CI only builds on `main` / `master` push or manual dispatch, so a feature branch shows errors only after merge or a dispatch.
- **Branch / PR**: extensive commit message and PR description (what and why). End commits with the `Co-Authored-By` / `Claude-Session` trailers of the current session and the PR body with the "Generated with Claude Code" line + session URL (use the ones the session provides). Give the compare link after pushing: `https://github.com/topgun1111/pcshare/compare/main...<branch>`.
- **Session size**: work in small parts (one part per session for big features), attach only the files named for the part, update this file at the end.
- **JVM-testable core**: `core/office/*` has no `android.*` imports on purpose; compile with `kotlinc 2.0.21` (see 8.2). Algorithms with a Python mirror: `tools/storage_scan_mirror_test.py`.
- **Kill switches / flags** (use them to bisect a bad build): `InProc.enabled`, `NativeList.HEAD` / `ls_nlh='0'`, `NlChrome.ENABLED` / `ls_nlc='0'`, `NlDrawer.ENABLED` / `ls_nld='0'`, `TiledOcr.skipTilesIfEmpty`, `ENGINE_ID` (bump to discard the image index).
- **Gotcha list for Kotlin**: `org.json` drops a key on `put(k, null)` (use `JSONObject.NULL`); never call `removeFirst()/removeLast()` on a `java.util.List` below Android 15 (only on `kotlin.collections.ArrayDeque`); KDoc must not contain `/*` (it nests - an `_rels/*.rels` comment once broke a whole file).
- **PC helper build**: none. The exe workflow was removed (2026-10-30); `tools/phone_explorer.py` and `pcprint.py` are run directly with Python on the PC.

---
