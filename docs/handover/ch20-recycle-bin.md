# Handover chapter file - ch20-recycle-bin

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

> **NEW - Recycle bin folder view (chapter 20) - read this before touching `Bin.kt` / `openBin()`** (entry added 2026-11-05)
> - Goal (owner): the Recycle bin opens like a normal file browser (folders you can enter, same icons / thumbnails / viewers / Copy / sort / view modes), not a flat list. Four parts, one per session; plan + decisions + next prompt in **chapter 20**.
> - **State:** Parts 1-3 coded 2026-11-05, not compiled (Part 3 = `ui.html`: read-only actions inside deleted folders, progress card, restore toast). Next: Part 4 (polish + checks).

## 20. Recycle bin folder view (multipart plan, 3 parts)

**Summary.** Owner request (2026-11-05): "Folder view for recycle bin. Files and folders must keep their icons and all features must act exactly as in their original locations. Full file browser look." Today `openBin()` is a flat sheet of text rows (5.8). Target: the bin is a place in the normal file browser; you can open deleted folders, see icons / thumbnails, open files in the viewers, Copy / Download out, sort, change view, search.

**Decision (why this works with little code).** The bin stays physically `<root>/.LANShare Bin/<id>/<name>` (+ `.meta`). The browser uses the virtual local path `/.LANShare Bin/<shown name>/<sub path>`; `LocalFs.real` / `lreal` map it through `Bin.physical` to `/.LANShare Bin/<id>/<name>/<sub path>`. So EVERY existing route (`/api/ls`, `thumb`, file download, stat, viewers, copy / move out, folder stats) works unchanged on deleted items. Only the bin folder's own listing is special (`Bin.rootItems`: shown names, deletion time as the date, `n` for folders, `bid` = bin id).
- **Shown name:** the original name; when several deleted items share a name every one of them gets `name (xxxx).ext` (xxxx = last 4 chars of the id). A path with an ambiguous plain name resolves to nothing (never to the wrong item).
- **Physical form still works:** a second segment that is an existing id folder (or starts with ".") is left alone.
- **Deletes inside the bin stay permanent** (`Bin.inBin` / `canTrash`, unchanged). Restore only for top-level rows (`bid`); a file inside a deleted folder is copied out with normal Copy.
- USB drives are not touched (`id == "local"` only).

**Parts**
1. **Part 1 - core (DONE 2026-11-05, not compiled):** `Bin.kt`, `LocalFs.kt`, `Endpoint.kt` as in section 14. Result: `GET /api/ls?dev=local&path=/.LANShare%20Bin` returns the deleted items; `...path=/.LANShare%20Bin/<shown>/sub` lists inside a deleted folder.
2. **Part 2 - browse + restore / purge (DONE 2026-11-05, not compiled, `ui.html` only):** bin opens as a folder, crumb "Recycle bin", Restore / Copy / Details / Delete forever on root rows, "Empty bin" FAB. See 20.2.
3. **Part 3 - inside deleted folders + progress (DONE 2026-11-05, not compiled, `ui.html` only, see 20.3):** inside a deleted folder hide Cut / Rename / Paste / New folder / Zip / Hide in `renderBar` (`inBin() && !binRoot()`), Delete = permanent with a clear dialog ("This can't be undone", from `doDelete` / `Jobs`); progress card (`binProgress()`) shown for `binDo` instead of toast polling; `NlModel.kt` check that Kotlin-built rows pass `bid` and show `shown` names; Back / edge swipe from bin root; restore toast with "Open folder" (`openLoc` to the restored parent).
4. **Part 4 - polish and checks:** sort "Deleted date" in the bin root (default newest first), view modes (list / grid / large), search inside the bin (`Core.local.search` on virtual paths? verify), "left: Nd" in the row subtitle (`days` from `GET /api/bin`), long-name / duplicate-name test, JVM test of `Bin.physical` (dup names, `..`, physical ids), device checklist, remove the old sheet (`openBinSheet` body, CSS `.bn .bh .bs .bt .bpg .bl .br`) once confirmed.

**Limits / not done (Part 1).** Not compiled; `Bin.physical` runs on every local path (a `startsWith` check, then a 3 s cached `list()` only for bin paths). `Cfg` root-delete flow (`RootShell`) untouched. `IndexManager` never indexes the bin (unchanged).

**Test checklist (Part 1, needs a build):** delete a folder with pictures; `GET /api/ls?dev=local&path=/.LANShare%20Bin` shows it by its name; `path=/.LANShare%20Bin/<name>` lists the pictures; `GET /api/thumb` with that path returns a picture; delete two files with the same name: both show `name (xxxx).ext`; the plain name alone gives 404; Restore from the old sheet still works.

### 20.2 Part 2 as built (`assets/ui.html` only; never compiled / device-tested - `node --check` of all scripts OK)
- Helpers before `openBinSheet`: `BINP`, `inBin()`, `binRoot()`, `openBin()` (= `openLoc('local', BINP)`, so drawer row, Free up space and Undo now navigate), `binLbl(segs)` (first crumb text "Recycle bin"; used by `render()` crumbs and `nlHeadPush` `c`), `binDo(op, ids)` (`POST /api/bin` restore / purge / empty, polls `GET /api/bin?items=0`, toast, `load()`), `binEmpty()` (confirm), `binBar(btn, it)`.
- `renderBar`: in the bin root (`binRoot()`) the selection bar is Restore (pri) / Copy / Details (1 item) / Delete forever (ids from `i.bid`); the old action list is skipped there. `render()`: FAB = "Empty bin" in the root (`binRoot()`), hidden inside deleted folders; empty text "The recycle bin is empty".
- The old sheet is kept as `openBinSheet()` (unused; removed in Part 4). Still not done: restrictions inside deleted folders (Part 3), progress card, sort by deletion date, row subtitle with days left.
- Check on the phone: Drawer > Recycle bin opens the folder view; crumb shows "Recycle bin"; enter a deleted folder, thumbnails / viewers work; long-press > Restore; Delete forever; Empty bin; native list head crumb text.

### 20.3 Part 3 as built (`assets/ui.html` only; `node --check` of all scripts OK, never device-tested)
- `renderBar`: `inBin()&&!binRoot()` -> new `binInBar(btn,it)`: Copy (pri), Details (1), Open with (1 file), Share (files), Delete forever. No Cut / Rename / Zip / Paste / Hide / Style / Print space. The "Paste here" button of the clip bar is hidden in the bin; `doPaste` refuses in the bin (toast). FAB already hidden inside deleted folders (Part 2) so there is no New folder.
- `doDelete`: inside the bin it asks "Delete N items forever? ... This can't be undone." and posts `/api/rm` with `perm:true`, then `watchJob`/`track` and `load()` (same job card as other deletes).
- `binDo`: toast polling replaced by the `binProgress()` card appended to `#dlw` (a native-list hole already) inside a `.bpc` wrapper (dark card CSS next to `.bpr`); card removed when the task ends. Restore of one item ends with the toast "Restored N item to <folder> - tap to open"; tapping it (6 s) runs `openLoc('local', parent)`.
- Not touched: `NlModel.kt` (native rows take names from `Item.name`, which is already the shown name; selection reads `S.items`, so `bid` is available to `binBar` - verify on the phone). Back / edge swipe from the bin root is the normal folder Back (no special code added).
- Check on the phone: select a file inside a deleted folder -> only Copy / Details / Open with / Share / Delete forever; Delete forever dialog; Restore 2+ big items -> progress card with Cancel; restore one item -> toast, tap opens its folder; Copy a file out of the bin and Paste elsewhere.

**Next-session prompt (Part 4):** "Read HANDOVER.md chapter 20 (20.3, Parts list). Do Part 4 (polish and checks). Short replies, patch scripts, full app zip + handover updated."

### 20.4 Part 4 as built (2026-11-05; not compiled, `node --check` of ui.html scripts OK; `tools/bin_selftest/BinSelfTest.kt` written, not run)
- Sort: bin root defaults to Date, newest first (`applyPrefs`, only while the user has no saved view for the bin). View modes / sort menu are the normal ones (already worked on the virtual paths).
- Row subtitle in the bin root: "date, time - Nd left" (`Bin.leftDays`, KEEP_DAYS 30); `ui.html` row renderer + `NlModel.kt` (rows with `bid`). Compact view unchanged.
- Search: `LocalFs.search` at the bin root searches the SHOWN names and goes into deleted folders (virtual paths); it no longer walks the id folders / `.meta`. Inside a deleted folder search worked already (`real` maps the path).
- `Bin.physical` split: pure `Bin.resolve(v, entries, physicalSeg)` (+ rejects `..` in the sub path). `tools/bin_selftest/BinSelfTest.kt` covers duplicate names, plain ambiguous name, folder sub paths, `..`, physical ids, paths outside the bin.
- Old `openBinSheet()` and its CSS (`.bn .bh .bs .bt .bpg .bl .br`) removed; `binProgress()` / `BINTOK` stay (used by `binDo`).
- Device checklist: bin root sorted newest first; "Nd left" shown; search a word -> deleted names + files inside deleted folders; two same-named deletes show `name (xxxx)`; Restore / Delete forever / Empty bin still work; switch to grid / compact.

### 20.5 Undo countdown only after "Delete permanently" (2026-11-06; not compiled, `node --check` of ui.html scripts OK, never device-tested)
- **Behaviour**: "Move to bin" = no countdown (restore from the Recycle bin, 30 days). "Delete permanently" on this phone = the round Restore countdown (5..1, `#ubn`); tap = restore to the original place, end = purge for good. Other devices (SMB, USB, archives, Drive) and deletes inside the bin stay immediate, no countdown.
- **Core** (`core/Jobs.kt`): `Job.grace` (set from `perm` in `deleteWork`) -> job JSON `grace:true` next to `bin`. `removeUser`: local + perm + `onTrash != null` -> `Bin.trash` first (ids reported through `onTrash`); if the move throws (not Cancelled) it falls back to `e.remove`, so a refused move behaves as before. Callers without `onTrash` (viewers, `/api/file` rm, `AlbumMerge`) are unchanged.
- **UI** (`ui.html`): `track()` calls `ubShow(j.bin)` only when `j.grace`; `ubHide(true)` (countdown ran out) -> `ubPurge(ids)` = `POST /api/bin {op:'purge', ids}`, retried 4x while the bin says "busy"; `ubRestore` calls `ubHide()` without purge. A second permanent delete adds ids and restarts the 5 s.
- **Known edge**: app or page closed during the 5 s = nothing purges, the items stay in the bin (visible there, 30 days). Not done: purge on next start for items flagged grace.

---
