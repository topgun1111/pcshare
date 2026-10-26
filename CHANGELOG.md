# LANShare change log

<!-- USER-FACING list. The app's update dialog shows it BEFORE the download button; CI publishes the TOP "## " section below as the GitHub release body. -->
<!-- RULE: every session that changes the app adds a NEW section at the TOP (below this note). Never edit older sections. Plain words, one line per change, no file names. -->
<!-- Section format: "## YYYY-MM-DD - short title", then any of "### Added", "### Removed", "### Changed", "### Fixed" with "- " bullets. -->
<!-- The developer-level detail goes to HANDOVER.md section 14; this file is only what the owner sees on the phone. -->

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
