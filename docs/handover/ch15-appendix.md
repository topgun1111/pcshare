# Handover chapter file - ch15-appendix

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

## 15. Appendix

### 15.1 Source map (as of `ce433bc`)
```
app/src/main/AndroidManifest.xml            assets/ui.html (+ assets/fonts/*)
java/android/print/WebPdf.kt                (package-private WebView print callbacks)
java/com/lanshare/app/                      screens + native views
  MainActivity (Bridge, intents)  LanShareService  App  UiScale  Fnt  AppPal  UpdateCheck  ModelLinks  Shortcuts
  NativeList NlModel NlHead NlChrome NlDrawer NlSearch NlSheets NlSplash NlFileIcons          native list family (6.2)
  PlayerActivity PlayerWidgets  ImageViewerActivity ImageEditActivity  PdfViewerActivity  TextEditorActivity CodeHl MdRender   (7)
  OfficeViewerActivity OfficeShortcutActivity                                                (8)
  PhonePrint PcPrintService                                                                  (9)
  InboxActivity ClipPush ClipSendActivity ClipSync QuickSendReceiver SendClipTile TextSend    (10.1)
  OcrEngine OcrTiled OcrHiRes OcrOverlayView OcrSmart OcrPrefs PaddleOcr                      (11.2)
  StorageActivity StorageList StorageTreemap StorageActions StoragePie StorageWiz             (12)
  ops/JobView JobOverlay                                                                      (5.3)
  pe/FtpServer PeDiscovery PePc PePrefs PeService PeTls                                       (10.2)
  core/ SheetLayout PsLayoutJson PsLayoutApi PsRenderGeom PrintSpace PrintSpaceRender Core Cfg Routes InProc MiniHttp Net Util Discovery Endpoint Jobs Partials Bin Albums Media Thumbs VideoThumbs RemoteThumbs
        LocalFs RemoteFs SmbFs FtpFs FtpCache SftpFs RootFs RootShell ArchiveFs ArcEdit ZipEdit Zip64Writer DupFinder
        PrintPrep PrintPreview PdfPrint WifiPrint WifiPrinters Ipp  PcText Notes  ImgSearch ClipEngine
        StorageScan StorageStore Volumes  OfficeWeb  PerfMarks  DocxText (unused)
  core/office/ MiniXml OoxmlPackage NumFmt XlsxModel/Reader/Html/Editor/Writer DocxModel/Reader/Html/Editor/Writer DocReader XlsReader OleFile
               OdfReader OdpReader PptxReader OfficeOpen OfficeExport OfficeThumb
tools/  sheet_layout_mirror_test.py sheetlayout_selftest/ psrender_geom_selftest/ phone_explorer.py ftp_smoke_test.py ftps_smoke_test.py storage_scan_mirror_test.py office_selftest/ patch_pcprint.py (one-off)
pcprint.py   VERSION   build.gradle.kts / settings.gradle.kts / gradle.properties   .github/workflows/build-apk.yml
```

### 15.2 Unused files and code (scan of 2026-10-20, nothing deleted; dead code costs APK size only, no runtime cost)
- **Fonts** never referenced: `tnr-regular.ttf`, `tnr-bold.ttf`, `urbanist-medium.ttf`, `urbanist-semibold.ttf` (196 KB).
- **Stale files**: `text-sharing.patch` (already applied in code, no longer applies cleanly), `tools/patch_pcprint.py` (one-off, referenced nowhere).
- **Dead Kotlin**: `OcrSmart.kt` (81 lines - but see 11.2: notes say Find text / word list use `OcrSmart.fold` / `words`; verify), `core/DocxText.kt` (116 lines; the Office Part 9 note says it was removed, the file is still in the repo), and unused functions `OcrOverlayView.hitsWord / tapSelect / touchHint`, `StorageActivity.sysDialog` (verify: 12.6 says it is called from the System / other note), `ImgSearch.autoEnabled`, `Jobs.pcOpenPhone`, `Thumbs.makeSized`, `MiniXml.attrCount`.
- **Dead JS / CSS in ui.html**: `galHideSet()`, `#peers` rules (element hidden, `renderPeers` still builds it), `#dtabs` rules.
- The temporary **PERF-DEBUG** code (13.4) is meant to be deleted after tuning.

### 15.3 Documentation gaps (nothing written about these - add a section when you touch them)
`HANDOVER_NATIVE_LIST.md` (missing, 6.2); FTP / SFTP clients (5.5); `PeTls` / FTPS option (10.2); `TextEditorActivity`, `CodeHl`, `MdRender` (7.5); `NlSplash` internals; `PcPrintService`, `PhonePrint` details; `DupFinder`; `Albums` / `Media` / `Net` internals; the `UiScale` / `Fnt` implementation; `Discovery` protocol details; `Cfg` key list. Docs that exist only outside the repo: the design artifact for the Main menu, `arama.php` + `clip_worker.js` (image-search reference), the `progress-overlay-handover-v4` package (5.3).

### 15.4 Where the old files went
`HANDOVER.md` (batches, dated notes) -> sections 5, 6, 9 and the changelog; `HANDOVER_HOME_MENU.md` -> 6.3; `HANDOVER_IMAGE_SEARCH.md` -> 11.1; `HANDOVER_OCR.md` -> 11.2; `HANDOVER_OFFICE.md` -> 8; `HANDOVER_PHONE_EXPLORER.md` -> 10.2; `HANDOVER_SCREEN_MIRROR.md` -> 9.6; `HANDOVER_STORAGE_ANALYZER.md` -> 12; `HANDOVER_TEXT_SHARING.md` -> 10.1; `HANDOVER_ZIP_EDIT_AND_WIZTREE.md` -> 5.7 and 12.9; `PERFORMANCE_FINDINGS.md` (an attachment, not in the repo) -> 13.
- **2026-10-22** - FTP speed-up part 1 of 5: listing cache + invalidation, single-flight, FEAT-driven MLSD/LIST choice, OPTS MLST, SYST + symlink memory, TCP_NODELAY, `fresh=1` on `ls` (`FtpFs.kt`, `FtpCache.kt`, `Endpoint.kt`, `Routes.kt`; not compiled / run on device) [5.5].

---
