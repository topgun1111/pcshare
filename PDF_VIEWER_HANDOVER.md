# PDF / Office viewer: "first page only, rest white" (handover, 2026-10-05)

Bug (as reported): in the in-app viewer only page 1 is drawn, the other pages stay white. Scrolling down and back to page 1 makes page 1 white too. Office files (they open as a converted PDF, tier 1) do the same.
**Nothing here is compiled or device-tested** (no Android SDK in the sandbox). Work is split in two halves; Part A is coded, Part B is for the next session.

## Why the pages go white (found by reading `PdfViewerActivity.kt`)
- A white page = `PdfPageView.bmp == null` (it draws white). Pages get their bitmap only through `request()` -> `renderPage()`.
- Old code: ANY exception or OutOfMemoryError while rendering did `failedPages.add(pos)` and that page was **never retried**, and the OOM path also did `cache.evictAll()`. So one failure on page 2 evicted page 1's bitmap, page 1 could not be re-rendered either, and everything stayed white. That matches the symptom exactly.
- Probable trigger (not proven): too much bitmap memory. A page bitmap was up to 5M px (~20 MB), the cache held ~1/3 of the heap class, and `setItemViewCacheSize(2)` kept extra detached holders with their own bitmap references.
- Office conversion is not a second bug: tier 1 just feeds the converted PDF into the same activity. Tier 2 (WebView) is a different code path and was NOT touched.

## Part A: DONE (only `PdfViewerActivity.kt` changed)
1. `failedPages` replaced by `failCount` + `lastErr`: a failed page is retried 3 times (400 ms, 800 ms), and scrolling away and back gives one more try.
2. A page that still fails now **shows the reason** on the page ("Page could not be drawn" + exception name/message, or "out of memory (WxH)"), and logs it with tag `PdfViewer`.
3. OOM path: evict, retry at 2/3 size instead of 1/2.
4. Smaller bitmaps: `maxPx` 5M -> 4M (normal), 9M -> 6M (memoryClass >= 192). Cache = at least 2 pages, or 1/4 of the heap class.
5. `setItemViewCacheSize(0)`: off-screen pages are recycled at once (bitmap reference dropped) and re-bound from the cache on return.

## What to do first (build + test, 10 minutes)
1. Push, let GitHub Actions build, paste compile errors as text if any. Likely suspects in this change: `tp` (TextPaint using the constructor param `c`), `holder(pos)?.v?.let { it.err = null; it.bmp = rb }`, the unused-value warning on `y +=`.
2. Open a multi-page PDF (10+ pages), scroll down and back. Expected: every page draws.
3. If a page still shows the error text, **send me the exact second line** (it names the cause). Decision table:
   - `out of memory (WxH)`: lower `maxPx` again (3M) or render at `viewW` only and tile when zoomed; check `memoryClass` of the device.
   - `IllegalStateException: Current page not closed`: two threads touch the renderer; look for any `openPage` outside `synchronized(rlock)` (the size loop in `openDoc` is the only unlocked one and runs before the renderer is shared).
   - `IllegalArgumentException` / `Page ... out of range` / `document is closed`: look at `begin()` / `closeDoc()` ordering (onNewIntent re-entry).
   - Nothing shown and still plain white: the failure is not in `renderPage` (then suspect `bound` gating: `onViewRecycled` removing a position that was re-bound; log `bound`/`inflight` in `request`).
   - First page only and the PDF is from `/api/dl` or `/api/officepdf`: compare the file size in `cache/pdf/doc.pdf` with the source (truncated download breaks pages > 1). `adb shell run-as <pkg> ls -l cache/pdf`.

## Part B: NOT done (next session)
- Re-test Office tier 1 (converted PDF) after Part A; it should be fixed by the same change.
- Everything in `OFFICE_VIEW_HANDOVER.md` is still open: first real compile of `OfficeView.kt`, `OfficeText.kt`, `OfficeViewerActivity.kt`, device checklist, and the known quirk (`/api/officeok` waits up to ~3.5 s with the PC off, no spinner).
- If Office tier 2 (WebView page) also shows blank content on device: check `OfficeViewerActivity` (`loadDataWithBaseURL`, huge data-URI images, JS off) separately; it is independent of the PDF bug.
- Optional hardening: tile/crop rendering when zoomed (render only the visible rect with a transform Matrix instead of whole-page bitmaps), and a "Open with..." button on the failed page.

## Part A2 (session 2, 2026-10-05): render gate + download check, still NOT compiled
Found by re-reading `PdfViewerActivity.kt` (no device logs yet, so these are fixes for real weak spots, not a proven root cause):
1. **`bound` was a Set, now a per-position counter** (`bAdd` / `bDel` / `bHas`, guarded by `synchronized(bound)`). Risk before: after `notifyDataSetChanged()` (night mode, rotation) RecyclerView recycles the OLD invalid holder AFTER the new holder was bound to the same position; `onViewRecycled` then removed the position from the set, the render task saw "not on screen", skipped, and the page stayed white with no retry. `onBindViewHolder` now releases `h.pos` first if the holder is re-bound without a recycle; `bound.clear()` calls were removed on purpose (bind/recycle pairs keep the counts right).
2. **Skipped task re-asks**: if the pool task skipped a page (gate false) but the page is bound when the result is posted, `request(pos)` is called again.
3. **Truncated download is now an error**: when the server declares a Content-Length and fewer bytes arrive, the reader shows `Download incomplete (x of y bytes)` instead of opening a cut file (a cut file would draw page 1 and fail the rest). Also logs `downloaded N bytes (declared M)` with tag `PdfViewer`.
Compile suspects added: `bDel` (`if` as last expression of a `synchronized` lambda: uses `put`/`remove` on both branches on purpose), `ui.post { ... else if (!tried && bHas(pos)) request(pos) }`.
Next: push, build, paste compile errors; open a 10+ page PDF; if still white, send `adb logcat -s PdfViewer` (look for `downloaded`, `render failed`, `OOM`) and the second line of the on-page error.

## Files changed in this zip
`app/src/main/java/com/lanshare/app/PdfViewerActivity.kt` (Part A), `PDF_VIEWER_HANDOVER.md` (this file). Everything else is the project as uploaded.
