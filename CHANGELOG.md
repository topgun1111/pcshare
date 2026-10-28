# LANShare change log

<!-- USER-FACING list. The app's update dialog shows it BEFORE the download button; CI publishes the TOP "## " section below as the GitHub release body. -->
<!-- RULE: every session that changes the app adds a NEW section at the TOP (below this note). Never edit older sections. Plain words, one line per change, no file names. -->
<!-- Section format: "## YYYY-MM-DD - short title", then any of "### Added", "### Removed", "### Changed", "### Fixed" with "- " bullets. -->
<!-- The developer-level detail goes to HANDOVER.md section 14; this file is only what the owner sees on the phone. -->

## 2026-10-28 - Long file names can be printed on the PC

### Fixed
- Files with a very long name (over 120 letters) were refused with "this file type cannot be printed". They print now. Run the new PC print file once.

## 2026-10-28 - Print log screen

### Added
- Settings > Print log: the print log can now be read, shared and copied inside the app, and cleared.
- "Fetch PC log" adds the PC's log to the phone's, sorted by time.
- Open folder shows the log file in the file manager.
- When a print finishes (or fails), the job card shows its job code with "Print log" and "Copy log" buttons.
- "Full detail" switch: off makes the log smaller (long texts are cut).

## 2026-10-28 - Printing on the phone and Save as PDF: more forgiving

### Added
- The print log now also records printing on this phone, the "LANShare - print on PC" print service and Save as PDF.
- Errors of these prints end with a short job code, e.g. "[log A0005]".

### Changed
- Printing on this phone: if one file cannot be read, the others still print and you are told which one was skipped.
- A broken PDF only skips itself; a page or picture that cannot be drawn is replaced by a short note instead of stopping the whole print.
- Save as PDF: one bad file or picture no longer stops the PDF, and half-written PDF files are removed.
- "Trim blank edges" prints the whole picture if cropping fails.

## 2026-10-27 - Wi-Fi printers: more forgiving

### Added
- The print log now also records everything the Wi-Fi printer says (status, paper, errors, job progress), so a printer problem can be found afterwards.
- Wi-Fi print errors end with a short job code, e.g. "[log A0003]".

### Changed
- After sending, the app asks the printer what became of the job. If the printer is printing or has finished it, a leftover "media needed" or "stopped" warning is shown as a note, not as a failed print.
- If the printer holds the job because of the paper choice, the app cancels it and sends it once more, letting the printer pick the paper.
- If the printer refuses a PDF, the app sends the pages as pictures instead (when the printer supports that).
- A printer that is asleep gets a second chance before "not reachable" is shown.

## 2026-10-27 - Printing: more forgiving + print log

### Added
- Print log: every print step is written to a log file on the phone (folder "LANShare Logs") and on the PC, including technical error details, so a printing problem can be found afterwards.
- Errors from the PC now end with a short job code, e.g. "[log J0007]", that points to the exact place in the PC log.

### Changed
- Printing to a PC tries again by itself after a short network drop (the document is never printed twice).
- If one file cannot be sent, the other files are still printed.
- New PC print file (run it once): if page layout fails it prints the file plain, if SumatraPDF fails it tries Windows' own PDF printing, it finds the printer even when the name is written a little differently, and it starts the Windows print spooler when it was stopped.

### Fixed
- A problem while watching the printer after a successful print no longer shows a false error (which could make the phone print the file again).

## 2026-10-27 - Build fix

### Fixed
- The app builds again (search index screen was missing a function).

## 2026-10-26 - Storage analyzer on other devices: Open

### Added
- Storage analyzer on another device (phone, SMB, FTP): touch and hold a file now offers "Open" first and "Open containing folder" second.

## 2026-10-26 - FTP tile removed from the main menu

### Removed
- The "FTP Servers" tile is gone from the main menu (FTP is still in the side menu, bottom row).

## 2026-10-26 - Storage analyzer: Open containing folder

### Fixed
- Storage analyzer: "Open containing folder" in the details window now really opens the folder (Folders tab, folder selected and shown), also when you came from the Files tab.

## 2026-10-26 - Test suite removed

### Removed
- All automatic component tests were removed from the project (the app itself is unchanged).

## 2026-10-26 - Search moved out of the menus

### Removed
- "Search everything" item removed from the side menu.
- "Search" tile removed from the main menu.

### Changed
- Universal Search is now opened only with the search button in the file manager (it searches the current folder; from the storage root it searches the whole storage).

### Added
- The update dialog now shows what was added, removed or changed (all versions you skipped, newest first) before you press download.
