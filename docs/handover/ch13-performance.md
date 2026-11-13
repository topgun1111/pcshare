# Handover chapter file - ch13-performance

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

## 13. Performance

*Source: Settings > Debug: performance (branch `claude/debug-perf-tab`), reports taken 2026-10-20 on the user's phone. One device, one cold start per build - indicative; force-close and reopen the app before each startup report.*

### 13.1 Cold start (onCreate -> first screen)
| Build | Total | WebView + setup | Server start wait | Page load (ui.html) | Page done -> first screen |
|---|---|---|---|---|---|
| Baseline | 533 ms | 117 | **258** | 102 | 56 |
| Server started early (run 2) | 497 ms | 127 | 46 | **247** | 77 |
| + image engine deferred (run 3, merged) | **335 ms** | 95 | **38** | 157 | 45 |

**Net 533 -> 335 ms (-198 ms, ~-37 %).** `core.url` (server ready) is published 106 ms after process start (baseline `coreReady` 447 ms); 0 long tasks (run 2 had one 65 ms task during page load); `GET /api/folderstat` max 467 ms (baseline 965, run 2 2155); the image engine finishes loading at 981 ms (`core.clipLoaded`), ~590 ms after the first screen, off the critical path.
Marks of the current build (ms since process start): UI thread onCreate 54 -> fontInit 56 -> **webCreated 138 (WebView construction = 82 ms)** -> nativeViews 145 -> contentSet 145 -> permissionsAsked 147 -> loadStart 149. Server thread: threadStart 54 -> core.begin 56 -> cfgLoaded 85 (**29 ms** `Cfg.load`) -> locks 94 -> cleanup 95 -> pageAndFs 99 -> portBound 102 -> discoveryStarted 106 -> **url 106**. Page: loadStart 149 -> coreReady 187 (poll granularity) -> pageFinished 344 (157 ms) -> firstScreen 389.

### 13.2 Findings
- The server is **no longer on the critical path** (ready at 106 ms, before the WebView finishes building at 138 ms). Remaining early cost is WebView construction (82 of the 95 ms).
- Moving the heavy image-search work after `url` first hurt page load (247 vs 102 ms) because model load and watcher ran concurrently with the page load; deferring them until after the first screen fixed most of it (inferred from timings, consistent with run 3).
- Page load is still above baseline (157 vs 102 ms; main script starts at 116 vs 39 ms; DOM ready 152 vs 90 ms). **Unexplained.** Candidates: `WifiPrinters.start` and Discovery threads starting right after `url`; WebView renderer start; run-to-run variance.
- **No jank**: 0 long tasks; frame sample ~90 fps, 0 frames over 33 ms, worst 22 ms (WebView only; the native list is not counted). The UI page is cheap (main script 31-53 ms; ui.html 468 KB, 224 DOM nodes, 10 MB JS heap). Steady state is tiny (~1 s of main-thread time in 387 s: polling intervals, `nlLay`, `pollPeers`).
- Two slow background scans (do not block the UI thread): `GET /api/folderstat` (3 calls, avg 220-850 ms) and `GET /api/wifiprinters` (50-300 ms). Wasteful but cheap: `nlLay` / `nlChPush` / `nlSrPush` ~460 calls each in 8 s; `/api/peers` polled every few seconds.

### 13.3 Design questions answered
| Idea | Verdict |
|---|---|
| Rewrite ui.html fully native | At most ~100 ms of the now 335 ms; huge effort and regression risk. **Not worth it.** (An earlier estimate for a 100 % native UI: ~6,000-9,000 Kotlin lines, 1-2 weeks, no gain for storage reads / SMB / peers.) |
| Minify ui.html | Well under 20 ms. Low value. |
| ui.html growing to 1 MB | Main script run would grow from ~31-53 ms to ~70-120 ms; maintainability is the bigger problem. |
| Split ui.html into ten files | No speed gain; only maintainability. Safe if plain `<script src>` in the same order, startup block last, the server serves the files, debug wrappers installed after all files load. **Not yet checked**: how ui.html is served and whether Kotlin reads / patches it as text. |

### 13.4 Done, and what is open
Done (branches, each stacked on the previous; `claude/defer-image-engine` contains all, merged as PR #66): `claude/debug-perf-tab` (temporary tab, all code tagged `PERF-DEBUG`, `core/PerfMarks.kt`), `claude/faster-server-start` (`Core.start` from the top of `MainActivity.onCreate`, `url` published before the slower setup: server wait 258 -> 38 ms), `claude/startup-marks` (finer marks), `claude/defer-image-engine` (engine + watcher after the first screen: page load 247 -> 157 ms; image search may say "model not loaded" for the first second after launch). `claude/swipe-light-transition` = old, empty diff, close its PR.
Open (ranked) is in section 3 B: (4) why page load is 157 ms, (5) WebView construction, (6) delay the background scans, (7) fewer layout pushes, (8) `Cfg.load` 29 ms (harmless), (9) optional minify / dead code.
**Re-measuring**: Settings > Debug: performance > Refresh / Reset counters / **Record 5 s** (then scroll, swipe, open screens) / Copy. Compare TOTAL onCreate -> first screen, "Page load", "main script starts at", long-task count, API averages.

---
