# LANShare change log

<!-- USER-FACING list. The app's update dialog shows it BEFORE the download button; CI publishes the TOP "## " section below as the GitHub release body. -->
<!-- RULE: every session that changes the app adds a NEW section at the TOP (below this note). Never edit older sections. Plain words, one line per change, no file names. -->
<!-- Section format: "## YYYY-MM-DD - short title", then any of "### Added", "### Removed", "### Changed", "### Fixed" with "- " bullets. -->
<!-- The developer-level detail goes to HANDOVER.md section 14; this file is only what the owner sees on the phone. -->

## 2026-10-28 - Export / import settings in Settings

### Fixed
- Settings has the Export / import settings item again: save your settings to a file, or load them from one. Passwords and the Phone Explorer PIN are only included if you tick the box.

## 2026-10-28 - Logs in plain words

### Changed
- The log screen is now called Logs and opens in a Simple view: short plain sentences ("Printing finished", "The printer has a problem"), a count of problems at the top, and a "What to try" tip under each problem. Technical view (tap the top button) keeps all the old details.
- The Logs screen now fetches the PC log by itself when it opens and shows it first ("PC log"), followed by the phone's own log. Refresh also refreshes the PC part.
- PC screen: the Switch button is gone. A new Live | Logs tab row sits under the title; Logs opens the Logs screen for that PC.

## 2026-10-28 - Print queue works again

### Fixed
- Print queue: the screen now lists the jobs waiting on the PC and Cancel works. Copy the new pcprint.py (version 32) to the PC and run it once.

## 2026-10-28 - Print log screen reachable

### Added
- Settings > Print log opens the print log: readable lines, Full detail switch, Fetch PC log, Share, Copy, Open folder, Clear.
- The PC screen has a Log button that opens the print log with that PC ready for Fetch PC log.

## 2026-10-28 - Faster Office previews

### Changed
- Word, Excel and PowerPoint previews on the PC are much quicker: the PC keeps these apps ready in the background (hidden) instead of starting them for every file.
- Opening the same document again shows its preview straight away.

## 2026-10-28 - Print space: tap opens, hold selects

### Changed
- Print space: tap a picture to open it (swipe through the held pictures); press and hold to start selecting. While selecting, tap ticks or unticks.

## 2026-10-28 - Select several recent prints

### Added
- Print space > Recent prints: new Select button. Tick several prints (or All) and remove them in one go.

## 2026-10-28 - Two on the front, one on the back

### Added
- Print space > Layout > Auto-fill: new "2 on front + 1 on back" choice (the number follows Pictures per sheet). Each sheet gets the grid on the front and one picture on the whole back. Works in Grid and Free placement, Preview and Print.

## 2026-10-28 - Settings backup to Google Drive

### Added
- Google Drive screen: Back up settings saves your settings to My Drive (LANShare-settings.json), and Restore settings brings them back. Passwords and the Phone Explorer PIN are not uploaded.

### Fixed
- Add Google account now always asks which account to use, instead of signing in with the previous one.

## 2026-10-28 - FTP and Cloud servers on the Main menu

### Added
- Main menu: two new icons, FTP servers and Cloud servers. Tap one to see your saved servers or accounts and open them; the last row adds or edits them.

### Removed
- Main menu: the Printers and Favorite folders icons (Printers and Quick folders are still in the side menu).
- FTP servers and Google Drive accounts no longer appear in the Devices list (Main menu, side menu, device picker). The side menu's FTP button and Google Drive row are gone too.

### Changed
- Swiping left or right between devices skips FTP servers and Google Drive accounts.

## 2026-10-28 - Build fix

### Fixed
- The app builds again (print log and settings export/import code was missing a few pieces).

## 2026-10-28 - Google Drive: shared files and Trash

### Added
- At the top of your Google Drive: "Shared with me", "Shared drives" and "Trash". Open, download and copy shared files; upload into shared drives; search also looks in shared places.
- Move an item out of "Trash" to restore it.

## 2026-10-28 - Google Drive: faster big folders

### Changed
- Google Drive folders open at once from the last visit and update themselves in the background. Big folders load faster, and Drive's "too many requests" limit no longer stops thumbnails or copies.

## 2026-10-28 - Google Drive: polish

### Changed
- Google Drive places show a cloud icon, open faster, and ask you to reconnect when Google sign-in has expired.

## 2026-10-28 - Google Drive: rename, move, delete

### Added
- In Google Drive you can now rename, move and delete files and folders. Deleted items go to Drive's trash, so you can get them back for 30 days.
- Copying inside the same Drive account is instant, and files copied to or from Drive keep their original date.

## 2026-10-28 - Google Drive: upload

### Added
- You can now copy files and folders from the phone or other places INTO Google Drive. Big files are sent in pieces and continue after a short network break. Copying a file that already exists in Drive adds a new version instead of a second copy.

## 2026-10-28 - Google Drive: browse and download

### Added
- Your Google Drive accounts now appear in the device list. Open one to browse My Drive, search by name, open files and copy files or folders from Drive to the phone or other places. Google Docs, Sheets and Slides come as Word, Excel and PowerPoint files. Putting files into Drive follows in the next versions.

## 2026-10-28 - Google Drive sign-in (first step)

### Added
- Main menu > Google Drive: sign in with your Google account. You can add, reconnect and remove accounts. Browsing your Drive files follows in the next versions.

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
