# LANShare printing: done and to do (handover for a new chat)

Date: 2026-10-10. Project: `pcshare` (LANShare Android app + `pcprint.py` print service on the PC).
To continue: attach this file, the latest `pcshare.zip` (or the original plus the change zips below, newest last; paths inside start with `pcshare/`), and say which batch to do ("E", "F" ...).
Style wanted: no questions, plan + implementation in one go, only changed files in a zip with the folder structure kept, terse replies, say honestly what was not tested.

## Your requirements (keep for every change)
- Many files over Tailscale / SMB must never print extra copies.
- Wi-Fi printers: 4 pictures on one paper (2 per side) only worked with raw / PWG raster in the phone's Wi-Fi path (`WifiPrint.kt`). Do not break it.
- Fitting images and documents into pages, and several pictures per sheet, are very important.
- Safety is not important. The PC path already builds multi-page PDFs itself (SumatraPDF + driver), so no raster there unless a real print fails.

## Environment notes
- No Android SDK / Kotlin compiler in the chat sandbox: Kotlin is only read-checked, `ui.html` only syntax-checked with node (extract the longest `<script>`, `node --check`). The GitHub workflow builds the APK; compile errors from batches 13, 15-18 and D may show up there.
- `pcprint.py` logic is tested on Linux with printing stubbed (`run_retry` / `send_pdf` replaced; pypdf + Pillow available). Windows-only parts (PowerShell incl. the new hard-margin read, SumatraPDF, spooler checks, Office conversion) were never run. Nothing was tested on a real printer.

## DONE
| Version / batch | Zip | What |
|---|---|---|
| v13 | `pcprint-v13-changes.zip` | Duplicate protection: app sends `key` per file, PC remembers keys 15 min (`SEEN`), `FINISHED_BATCHES`, `run_retry` only if the command failed within ~4 s and the spool did not grow. |
| v14 | `pcprint-v14-changes.zip` | N-up picks portrait / landscape sheet by best fit (`best_sheet`); CropBox respected (`clip_to_crop`). |
| v15 | `pcprint-v15-changes.zip` | "Print all files as one job" (`join`, `JoinJob`, `join_to_pdf`, `skip=1`). |
| v16 | `pcprint-v16-changes.zip` | Pillow shrink to ~300 dpi per cell, EXIF turn, auto-install of pypdf + Pillow (`ensure_packages`), phone shrinks big JPEGs before upload. |
| A (HANDOVER 17) | `print-batchA-changes.zip` | `PhonePrint.onLayout` re-lays out only when needed, "Preparing..." toast, 4 parallel downloads, unprintable files in folders skipped with a note. |
| B (HANDOVER 18) | `print-batchB-changes.zip` | "This phone" printing through `PrintPrep` (HEIC/AVIF, HTML/SVG, md, Office via PC `/convert`), full picture-sheet dialog on the phone. |
| C = pcprint.py v17 | `print-batchC-changes.zip` | **Hard margins:** `PS_STATE` returns `hx/hy`, `hard_margin()`, `eff_margin()` (chosen margin never below the printer's hard margin; used in `process_pdf`, `images_to_sheets`, `img_to_pdf`, `txt_to_pdf`). **Multi-page TIFF:** `tiff_frames()` + `merge_pdfs()`, every frame prints (single, joined, picture sheets; max 300 frames). **Duplex swap:** file `%APPDATA%\LANSharePrint\duplex_swap.txt` (printer names, `*` = all) swaps long/short edge for landscape sheets (`swap_duplex` in `send_pdf`); off unless the file exists. |
| D | `print-batchD-changes.zip` | **Retry failed only:** `Job.retry` / `PrintReq`, `Jobs.retryPrint`, route `POST /api/printretry?id=`, `/api/job` returns `retry: N`, "Retry failed (N)" button in `lvEnd` (picture-sheet jobs retry all files; files printed with a printer warning are not retried; fresh batch id + keys). **Per-printer options:** `printOptsBy['<pc id>|<printer>']`, `printOptsLast`. **Recent prints:** `printHist` (12, localStorage), "Recent prints" button in the printer picker, tap = print again with the same options (`phAdd/phMark/phGo/printRecent`). **Shared files:** deleted after a fully successful print from Android's print dialog (`POST /api/sharedclean`, `Jobs.cleanShared`, names only). |

`HANDOVER.md` in the project has an entry for each batch 15-18, C and D.

Key places: `pcprint.py` (VERSION 17): `parse_opts`, `eff_margin`, `process_pdf`, `images_to_sheets`, `tiff_frames`, `join_to_pdf`, `send_pdf`, `swap_duplex`, `print_file`, `print_one`, handler `H.do_POST`. App: `core/Jobs.kt` (`startPrint`, `printWork`, `retryPrint`, `cleanShared`), `core/Routes.kt`, `PhonePrint.kt`, `core/PrintPrep.kt`, `assets/ui.html` (`printOptions`, `doPrint`, `lvEnd`, `printRecent`, `pickPrinter`).

## TO DO (batches, in suggested order)
### Batch E - Android print service (`PcPrintService.kt`)
- `job.complete()` is called as soon as the PDF is copied: use `job.block("Waiting in LANShare")` or say "Sent to LANShare".
- Needs "All files access" only to hand the PDF over: write to the app cache and pass an internal path / `content://` instead.
- Say in the printer description that on Android 10+ the app opens via the notification.
- Optionally list each PC / Wi-Fi printer as its own printer entry (removes one dialog).

### Batch F - Wi-Fi printer feedback (`Ipp.kt`, `WifiPrint.kt`)
- Real IPP job status (`Get-Job-Attributes`) instead of "done after 2.5 s"; ink / toner levels (`marker-levels`, `marker-names` in `Ipp.printerAttrs`) in "Checking printer...".
- Retry / resume for the 120 s file POSTs.
- Plain-language errors with a "Details" expander (currently developer-style: "port 8799... firewall...").

### Batch G - bigger rework (only if wanted)
- PDF page ranges on the phone as vectors (PdfBox-Android or pdfium) instead of 200 dpi bitmaps; per-page auto-rotate; optional enlarge-to-fit (`drawPdf` uses `min(..., 1f)`).
- Text printing options on the phone (font size, line numbers, header/footer, truncation warning for files over 4 MB); CSV as a table.
- Raster / IPP path on the PC (PWG raster over IPP, port of `WifiPrint.pwgRaster`): only if a real print through Windows + SumatraPDF fails.
- Phone-side n-up for PDFs ("This phone" prints one PDF page per sheet).
- Wi-Fi printers from the phone do not get the "one job" join (own picture-merging code in `WifiPrint`).

### Small leftovers
- Duplex swap has no app switch (file only). Add a per-printer toggle if the duplex test needs it.
- Live preview in the app does not know the printer's hard margin (margin "None" can differ slightly).
- Without SumatraPDF and with no layout / fitting option, a multi-page TIFF still prints only its first frame (old PowerShell path).
- Recent prints do not cover "This phone", shared-file prints, or jobs over 150 paths; the PC `/jobs` endpoint is still unused.

## TO TEST (on your side)
1. Build the APK on GitHub; send the log if it does not compile (Kotlin was never compiled here; batches 13, 15-18, D at risk).
2. Retry: print several files where one fails (e.g. an unsupported type inside a selection) -> "Retry failed (N)" on the finished card prints only the failed ones, once.
3. Per-printer options: set 2-up + duplex for printer A, other settings for printer B on the same PC, reopen the dialog and switch between them.
4. Recent prints: print, then Print on... -> Recent prints -> tap an entry: same printer, same options, no dialog.
5. Print a document from another app via Android's print dialog -> after a successful print its copy in `LANShare Shared` is gone; after a failed one it stays.
6. Margin "None" on a real printer: content stays inside the printer's edge limit (log shows "margin 0pt raised to the printer's hard margin ...").
7. Multi-page TIFF: all frames print, alone, joined, and 4-up.
8. 2 pictures per side with duplex, portrait photos (landscape sheet) on the PC path: are the back sides upside down? If yes -> create `duplex_swap.txt` with the printer name.
9. A joined job: PDF + pictures + text at 4-up, one sheet count and one print job in the Windows queue.
10. First run on a PC without pypdf / Pillow: the log shows the automatic install.
11. A dozen files over Tailscale: exactly one copy of each; press Print again quickly: nothing extra prints.
12. "This phone": HEIC photo, .html, .md, a .docx (needs a PC with pcprint.py v11+ and Word or LibreOffice), and a pictures-only selection with 4 per sheet.

## Files touched so far
`pcprint.py`, `app/src/main/java/com/lanshare/app/PhonePrint.kt`, `.../core/Jobs.kt`, `.../core/Routes.kt`, `.../core/PrintPrep.kt`, `.../core/Thumbs.kt`, `app/src/main/assets/ui.html`, `HANDOVER.md`.
