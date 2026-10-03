# LANShare: Python (Chaquopy) -> pure Kotlin — Handover

**Goal:** drop Chaquopy/Python entirely; the Android app serves the same web UI from a Kotlin HTTP server.
**Status: backend fully ported (SMB via smbj + thumbnails written), still NOT YET COMPILED** (no Kotlin toolchain was available when this was written —
expect a handful of small compile errors on the first Gradle build; fix them first, see "First steps").
`lanshare.py` and Chaquopy are still in the project and still the active runtime: the Kotlin core is behind a switch.

## The switch
`core/Core.kt` → `val KOTLIN_CORE: Boolean = false`. `LanShareService` and `MainActivity` already branch on it
(start server / `rescan()` / read URL+error). With `false` the app behaves exactly as before.
Flip to `true` to run the Kotlin core on a device (SMB shares and thumbnails will not work yet).

## What was done (all in `app/src/main/java/com/lanshare/app/core/`)
| Kotlin | Replaces (lanshare.py) | Notes |
|---|---|---|
| `assets/ui.html` | `PAGE = r"""…"""` | Extracted verbatim. Only change: two "Pydroid" texts reworded. |
| `Util.kt` | `vnorm/vjoin/unique_name/mime_for/safe_inline`, errors | `NotFound`=404, `Denied`=403, `Exists`=409, `BadReq`=400, `Cancelled`. `Source` = seekable `InputStream` with `size`. |
| `Cfg.kt` | `load_cfg/save_cfg/CFG` | Same `<filesDir>/lanshare.json` → device id, name and SMB list survive the switch. |
| `Endpoint.kt` | the Local/Remote/Smb duck-typed interface | `Endpoint` + `Item/WalkItem/SearchResult` (JSON identical to Python). |
| `LocalFs.kt` | `Local` | `.lspart` temp file on write, symlinks skipped in walk, same root-escape check. |
| `Net.kt` | `get_ifaces`, `arp_neighbors`, `conn_to`, `hello_url` | `NetworkInterface` instead of ioctl. Own tiny HTTP/1.1 **client** (`Http`, source-IP binding so traffic avoids VPN). |
| `RemoteFs.kt` | `Remote`, `RemoteFile`, `Stream` | Multi-IP retry, Range resume (5 tries) — same behaviour. |
| `Discovery.kt` | `Discovery`, `Peer` table | UDP beacon/listen, unicast sweep, TCP scan, live checks, `resumed()` after doze, `addIp`. |
| `Jobs.kt` | `start_job`, `JOBS`, `CLIP` | Copy/cut/send, progress, cancel, 4 retries per file. |
| `MiniHttp.kt` | `ThreadingHTTPServer` + `H` plumbing | Own server (no dependency): keep-alive, Content-Length bodies, thread per connection. |
| `Routes.kt` | `H.route/peer/api/send_file` | Every `/p/*` and `/api/*` route incl. Range downloads and SMB config add/remove. |
| `Core.kt` | `android_main.py`, `main()`, `hold_awake`, `storage_ok`, `phone_model` | `Core.start/rescan/url/error`; wake+wifi+multicast locks. |
| `SmbFs.kt` | SMB helpers | Config helpers (`Smb.cfg/split/peers/status`) ported. **`SmbFs.create()` is a stub that throws.** |
| `Thumbs.kt` | `make_thumb` | **Stub that throws** (→ HTTP 500 on `/api/thumb`). |

**Wire protocol is unchanged** — a Kotlin build and an old Python build can see and copy to each other
(use this for testing: Python phone ⇄ Kotlin phone).

## DONE (untested): SMB — `SmbFs.kt` (smbj 0.13.0, cached connection per share, BC provider swap)
Spec kept below for reference.

## (spec) SMB
Implement `SmbFs.create(cfg): Endpoint` (same 11 methods as `LocalFs`) in `SmbFs.kt`.
- Library: `com.hierynomus:smbj` (pure Java, SMB2/3). Add to `app/build.gradle.kts`. NTLM needs MD4/RC4 —
  add `org.bouncycastle:bcprov-jdk18on` and register it (`Security.addProvider`), because Android's BC build lacks them
  (this replaces the Python `_patch_rc4` workaround).
- Port semantics from `Smb` in lanshare.py (lines ~566-730): virtual `/` = share root; write to `<name>.lspart` then rename;
  `remove` refuses the root; `search` is iterative, 300 results / 15 s cap; `walk` lists dirs before their files;
  `rename` rejects `/ \ . ..` and existing names (`Exists`).
- Map errors: STATUS_OBJECT_NAME_NOT_FOUND/PATH_NOT_FOUND → `NotFound`; ACCESS_DENIED/LOGON_FAILURE → `Denied("Access denied - check the username and password")`;
  BAD_NETWORK_NAME → `NotFound("Share not found on that server - check the share name")`; collision → `Exists`.
- Update `Smb.state[id]` (true/false) after every operation — the UI's status dot reads it (see `_run` in Python).
- Hosts: IP with optional `:port` (`Smb.split` already handles it); no NetBIOS names; SMB1 not supported.
- Password is stored in plain text in `lanshare.json` (as before).

## DONE (untested): thumbnails — `Thumbs.kt` (BitmapFactory + androidx ExifInterface, cache under `<filesDir>/thumbcache`)
Spec kept below for reference.

## (spec) thumbnails
`Thumbs.make(File): ByteArray` → JPEG ≤ 400 px, quality 70. `BitmapFactory` with `inSampleSize` (decode ≈ 800 px),
rotate via `ExifInterface` (`androidx.exifinterface:exifinterface`), flatten alpha on white, scale to 400.
Cache at `<filesDir>/thumbcache/<key[0:2]>/<key>.jpg`, key = SHA-1 of `"400|path|mtimeNs|size"`;
prune when > 30 000 files (drop oldest 10 000) every 500 writes. Non-image/undecodable files should fail with a normal
exception (UI falls back to an icon).

## Next: cut-over cleanup (do last, after the test checklist passes with `KOTLIN_CORE = true`)
1. Delete `app/src/main/python/` (lanshare.py, android_main.py, jnius.py).
2. `app/build.gradle.kts`: remove `id("com.chaquo.python")` and the `chaquopy { … }` block; `build.gradle.kts` (root): remove the Chaquopy plugin line.
   (`abiFilters` can go too — no native libs left; the APK shrinks a lot.)
3. `LanShareService.kt` / `MainActivity.kt`: remove the `Python`/`AndroidPlatform` imports and the `else` branches; delete `KOTLIN_CORE`.
4. `.github/workflows/build-apk.yml`: remove the `actions/setup-python` step.
5. Delete the `KOTLIN_CORE` section from this file; fold what remains into `HANDOVER.md`.

## First steps for the next session
1. Build (`gradle :app:assembleDebug`) and fix compile errors in `core/*.kt` — nothing here has been through a compiler.
   Likeliest spots: `Cfg` accessor annotations, `Discovery.start()` function references, nullable smart-casts in `RemoteFs.callRaw`.
2. Set `KOTLIN_CORE = true`, install on two phones (or Kotlin ⇄ old Python build), run the checklist in `HANDOVER.md` ("Test checklist").
3. Test SMB (add a share, browse, copy both ways, rename, delete) and thumbnails, then do the cleanup.

## Behaviour differences / gotchas to keep in mind
- `org.json` **drops a key** on `put(k, null)` → real nulls must be `JSONObject.NULL` (done for `used`, `storage_ok`, `error`, clipboard).
- Query parsing mimics Python `parse_qs`: `+`/`%XX` decoded, **blank values dropped**, first value wins.
- `MiniHttp` does not support chunked request bodies, `Expect: 100-continue` or `HEAD` (the Python server had none of these either).
- Large uploads: an error mid-upload closes the connection instead of draining the body (same as Python's `close_connection`).
- Android < 15: never call `removeFirst()/removeLast()` on a `java.util.List`; the code only uses them on `kotlin.collections.ArrayDeque` (safe).
- `storageOk` is evaluated live on every call (Python evaluated once at start).
- `File.renameTo` is used for rename/move, with copy+delete fallback across volumes (`LocalFs.move`).
- `tcpProbe` treats "connection refused" as "host up, try next port" and anything else as "nobody home" — matches Python's `errno` logic.
