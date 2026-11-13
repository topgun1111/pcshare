# Handover chapter file - ch17-google-drive

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

> **NEW - Google Drive (chapter 17) - read this before touching Drive / Google sign-in** (entry added 2026-10-28)
> - Goal: Google Drive as one more browsable place (copy / move / upload / download like FTP or SMB). Eleven parts, ALL CODED (re-split on 2026-10-28 from the first plan of six), one part per session; plan, decisions and the next-session prompt are in **chapter 17**.
> - **State:** Part 1 (sign-in + saved accounts: `core/GAuth.kt`, `core/Gdrive.kt`, `Cfg.gdrive()`, `/api/gdrive`, `MainActivity.gdStart`, `openGdrive()` in ui.html) Part 2 (HTTP layer `core/GdApi.kt`: `GdApi`, `GdTime`, `GdMime`, `GdCache`) Part 3 (listing: `core/GdriveFs.kt`) and Part 4 (read + wiring: `open` / `walk` / `search`, `GdSource`, `Jobs.ep("gd:")`, `Gdrive.peers()`, kind `Drive`) are coded, **never compiled, never run on a device**. Part 5 (`GdriveFs.write` + `mkdir`: upload and copy INTO Drive) and Part 6 (`rename` / `move` / `remove` = trash / `copyWithin` / `setMtime`) are coded too, never compiled. Part 7 (UI polish) is coded too. Parts 8-11 are coded too. **The plan is finished in code; next: build the APK (CI), fix compile errors, then run the checklists 17.7 - 17.7k on a device (17.7k = the full one).**
> - Google Cloud side is already set up (17.2).

## 17. Google Drive (multipart plan, 11 parts)

> Plan created 2026-10-28, **re-split the same day from 6 into 11 parts** (the old Parts 2-6 became the new Parts 2-11, mapping in 17.6) so each session stays small. Add one line per finished part to the changelog (section 14), keep the status row in section 2 and the table in 17.6 current.
> Status: **Parts 1-11 coded 2026-10-28 (not compiled, not run: no Android SDK in the session). Plan complete in code; build + device test open.**

### 17.1 Summary
Google Drive becomes one more place next to this phone, other phones, SMB, FTP and SFTP: browse, search, download / open, upload, copy and move between Drive and any other place, rename, delete (to Drive's trash). It is an `Endpoint` (`GdriveFs`, ids `gd:xxxxxx`) so Jobs, the native list, viewers, thumbnails and "Send to" work without special cases, exactly like `FtpFs` / `SftpFs`.

### 17.2 Google Cloud setup (DONE by the owner 2026-10-28 - do not redo)
- Project: "My First Project", id `effortless-city-395520`. Google Drive API enabled (**owner must re-confirm: not yet seen on screen**). If `Gdrive.about()` says "has not been used ... disabled", enable it in APIs & Services > Library.
- OAuth consent screen (Google Auth Platform): app name "Pcshare", support / developer contact `ufukvatan@gmail.com`, user type External, **publishing status Testing** (Publish app stayed greyed out even after Branding was saved; skipped on purpose). Test users: the owner's Google account(s) must be listed under Audience > Test users. **In Testing, Google ends the grant every 7 days: the user must tap Reconnect (Part 7 turns an expired grant into that prompt).**
- Data access scope: `https://www.googleapis.com/auth/drive` (full Drive; restricted scope, fine for a personal unverified app; `drive.file` would only show files the app created).
- OAuth client: type **Android**, name "Landshare android", package `com.lanshare.app`, SHA-1 `D2:5D:56:99:07:8F:20:1D:6C:72:D5:83:FB:D5:66:BA:61:BA:94:CF` (certificate of `app/lanshare.jks`, alias `lanshare`). Client id `563276032827-vj6pdrrqeonrof44gpg79nrkgr2eqrfc.apps.googleusercontent.com` - **never used in code**, Google finds the client by package + SHA-1. No client secret exists for Android clients; nothing secret is in the repo.
- If CI signs with the `KEYSTORE_FILE` secret instead of `lanshare.jks`, that keystore's SHA-1 must be added as a second Android client (symptom: sign-in fails with "code 10").
- A new client can need minutes to hours before Google accepts it.

### 17.3 Decisions (do not revisit without a reason)
- **D1 Sign-in = Play Services Authorization API** (`com.google.android.gms:play-services-auth:21.2.0`, `Identity.getAuthorizationClient`), not AppAuth / browser PKCE: no redirect URI, no secret, silent re-authorise from background threads. Cost: needs Google Play services (the app already needs them for ML Kit OCR).
- **D2 No token is stored.** `GAuth.token(email)` caches an access token 50 min in memory and otherwise asks Play Services again. If Google wants the consent screen again, `GAuth.token` throws `Denied("... Main menu > Google Drive > Reconnect")` (a background job cannot show UI).
- **D3 Drive REST v3 over `HttpURLConnection`**, no Google client libraries (size, class clashes, the project avoids heavy libraries). `PATCH` is sent as `POST` + header `X-HTTP-Method-Override: PATCH` (HttpURLConnection has no PATCH).
- **D4 Paths are virtual** (`/Folder/file.txt`, root `/` = My Drive). Drive is id based and allows duplicate names, so `GdriveFs` keeps a path -> id cache in a process-wide object keyed by account id (NOT in the endpoint instance: `Jobs.ep()` builds a new endpoint on every call). A cache miss first asks Drive for `name = '...'` in the parent, then falls back to a listing. Duplicate names inside one folder are shown as `name (2)` ...
- **D5 Google-native files** (Docs / Sheets / Slides have no bytes): listed with `.docx` / `.xlsx` / `.pptx` added, downloaded through `files.export` into a temp file (Drive limit about 10 MB per export); drawings export as PDF; forms, sites and the like are hidden. Size shows 0 until downloaded. Shortcuts are listed as their target but skipped by `walk` (no loops).
- **D6 Delete = move to Drive's trash** (recoverable for 30 days), never permanent delete.
- **D7 Upload = resumable session**, 8 MiB chunks buffered in memory so a failed chunk is re-sent after asking the session how much arrived; an existing file with the same name is updated in place (new version), not duplicated.
- **D8 `kind` of a Drive place in `/api/peers` is `"Drive"`** (UI places that exclude FTP - print target pick, PC screen pick - must exclude it too: `ui.html` `p.kind!=='FTP'` at the PC-screen picker and the print picker).

### 17.4 Files
| File | Part | Role |
|---|---|---|
| `core/GdApi.kt` / `core/GdriveFs.kt` | 11 | `GdApi.url` adds `supportsAllDrives=true` to file calls; `GdriveFs`: virtual views `@shared` / `@drives` / `@trash`, `lq()`, `virtualRows()`, `restore()` |
| `core/GAuth.kt` | 1 | Authorization API wrapper: `begin(email)`, `finish(intent)`, `token(email)`, `forget` |
| `core/Gdrive.kt` | 1 | account registry (`add/remove/status/cfg/byEmail`), `about(token)`, `connect(token)`; Part 4 adds `peers()` |
| `core/Cfg.kt` | 1 | `gdrive()` / `setGdrive()` |
| `core/Routes.kt` | 1 | `GET /api/gdrive`, `POST /api/gdrive {remove:id}` |
| `MainActivity.kt` | 1 | `Bridge.gdriveConnect`, `gdStart`, `gdSave`, `gdJs`, request code 11 in `onActivityResult` |
| `assets/ui.html` | 1 | `openGdrive()` sheet, drawer row "Google Drive" (only when `LSAndroid.gdriveConnect` exists) |
| `core/GdApi.kt` | 2 | NEW: `GdApi` (HTTP helper), `GdTime`, `GdMime`, `GdCache` - see 17.5a |
| `core/GdriveFs.kt` | 3-6 | `GdriveFs : Endpoint` (3 listing, 4 open / walk / search: DONE; 5-6 writes), `GdSource` + `GdTempSource` (4, DONE) |
| `core/Jobs.kt` | 4-6 | `ep()` knows `gd:` and `printTarget` refuses `gd:` (4, DONE); `keepMt` condition includes `gd:`; same-server copy shortcut includes `gd:` |
| `core/StorageScan.kt` | 4 | remote analyzer: treat `gd:` like FTP (4 threads) (DONE) |

### 17.5 How Part 1 works
1. Drawer > Google Drive > **Add Google account** -> `LSAndroid.gdriveConnect("")` -> `MainActivity.gdStart` (worker thread) -> `GAuth.begin(null)`.
2. If Google already has consent for this app: a token comes back at once. Otherwise `begin` returns a `PendingIntent`; the activity shows Google's account chooser + consent (unverified app: "Advanced > Go to ... (unsafe)") with `startIntentSenderForResult` (code 11).
3. `onActivityResult` -> `GAuth.finish(data)` -> token -> `Gdrive.connect(token)` calls Drive `about` (email + name), saves `{id:"gd:xxxxxx", email, name}` under `"gdrive"` in `lanshare.json` (max 5 accounts, same email = update).
4. The native side tells the page with `lsGdriveDone(ok, message)`; the sheet redraws from `GET /api/gdrive`. **Reconnect** = same flow with the saved email (`setAccount`). **Remove** = `POST /api/gdrive {remove:id}` (3 s "Remove?" confirm like FTP); revoking on Google's side is done by the user at myaccount.google.com.

### 17.5a How Part 2 works (`core/GdApi.kt`, nothing calls it yet)
- **`GdApi(accountId, email)`** - one per call site, cheap (no state; the token cache lives in `GAuth`).
  - `send(method, url, body?, contentType?, headers, ok)` -> `Resp(code, body, headers)`: Bearer token from `GAuth.token(email)`; **401 -> one retry** with `GAuth.token(email, force=true)`, still 401 -> `Denied("... Reconnect")`; **back-off** (1, 2, 4, 8 s + jitter, `Retry-After` honoured, max 60 s, 5 tries) on 429, 500 / 502 / 503 / 504 and 403 with reason `rateLimitExceeded` / `userRateLimitExceeded` / `sharingRateLimitExceeded`; a network `IOException` is retried 3 times **only for GET / HEAD**; `ok` = extra status codes that are not errors (**308** for a resumable upload chunk in Part 5); `PATCH` goes out as POST + `X-HTTP-Method-Override` (D3). A `Thread.sleep` interrupt becomes `Cancelled`.
  - `json(method, path, query, body?, base)` -> `JSONObject` (`path` like `/files/ID`; `base = GdApi.UPLOAD` for upload endpoints). `pages(path, query, key, visit)` follows `nextPageToken` (put `nextPageToken` into `fields`; `visit` returns false to stop). `stream(url, range?)` -> checked, retried `HttpURLConnection` for downloads / exports (the caller must `disconnect()`).
  - `GdApi.errorFor(code, body)`: 404 -> `NotFound`, 403 `storageQuotaExceeded` -> `Full`, 403 API switched off -> `Denied` with the Cloud Console hint, other 403 -> `Denied`, 401 -> `Denied("... Reconnect")`, else `IOException`. Helpers: `enc`, `qs` (escapes a Drive `q` literal), `url`, `FILE_FIELDS`, `BASE`, `UPLOAD`.
- **`GdTime.parse(rfc3339)` -> epoch seconds** (offset and fraction handled, 0 on bad text) and **`format(sec)`** (for `modifiedTime` in Part 6); no `java.time` (minSdk 24). The date algorithm was checked against Python's `datetime` on 20,000 random times (a Python transliteration; the Kotlin itself is not compiled).
- **`GdMime`**: `FOLDER`, `SHORTCUT`, `export(mime)` -> (extension, export mime) for Docs `.docx` / Sheets `.xlsx` / Slides `.pptx` / Drawings `.pdf` (D5), `hidden(mime)` = other Google-native kinds (forms, sites, maps ...).
- **`GdCache`** (process-wide, per account id, max 30,000 entries then cleared): `get(acc, path)` (root = id `root`), `put`, `putChildren(acc, dir, kids)` (replaces what is known directly below `dir`, drops stale children + subtrees), `drop(acc, path)` (path + subtree), `clear(acc)`. Entry = `Ent(id, dir, mime)`.

### 17.5b How Part 3 works (`core/GdriveFs.kt`, not wired to `Jobs` yet)
- `GdriveFs(cfg)` takes the saved account JSON (`id`, `email`, `name`); one `GdApi` inside. Nothing creates it yet (Part 4: `Jobs.ep("gd:")`).
- **Path -> id** `resolve(v)`: `GdCache.get`, else resolve the parent (recursive), then ONE query `name = '...' and 'PARENT' in parents and trashed = false` (pageSize 2). Exactly one hit whose shown name equals the path segment = done. Otherwise (0 / 2+ hits, `name (2)`, native `.docx` name) the whole parent is listed (`listDir`), which fills the cache, and the cache is asked again; still missing = `NotFound`.
- **Listing** `fetchRows(folderId)`: `GdApi.pages`, `'ID' in parents and trashed = false`, `pageSize 1000`, `orderBy createdTime`, fields = `FILE_FIELDS` + `createdTime`. `row()`: trashed / hidden kinds / broken shortcuts dropped; shortcut = its target (id, mime, `link = true`); native docs get `.docx` / `.xlsx` / `.pptx` / `.pdf` and size 0; `/` and `\` in a Drive name become `_`. `dedup()`: the OLDEST file keeps the name, later ones become `name (2)`, `name (3)` (never a name that already exists in the folder). `ls` = dirs first, then name; `lsFast` = `ls` (default). Folder item counts (`n`) are not given (Drive has no cheap count).
- `invalidate(v)` = `GdCache.drop`. `names` always reads Drive (never the cache), returns an empty set on any `IOException`. `stat`: `files.get`, folder totals (`files` / `folders` / `total` / `partial`) bounded 20 s / 300 000 entries, shortcuts not followed, visited-id set. `space`: `about.storageQuota` (`limit` - `usage`); null for unlimited accounts or on error.
- `search`, `walk` (Part 4), `open` (Part 4), `write`, `mkdir` (Part 5), `remove`, `rename`, `move` (Part 6) throw `IOException("Google Drive: not available yet (Part N of the Drive plan)")`.

### 17.5c How Part 4 works (reading + wiring)
- `open(v)`: Drive file -> `files.get(size)` then `GdSource` (`alt=media`; `seek` / a dropped connection re-open with `Range: bytes=pos-`, a reply other than 206 is an error; `NotFound` / `Denied` / `Cancelled` are not retried). Google-native -> `files.export` into `cacheDir/gdrive/exp*.tmp`, `GdTempSource` (RandomAccessFile, seekable, deletes the file on close). Export limit about 10 MB (Google's error text is shown).
- `walkEach(v)`: a file gives one item `rel=""`; a folder gives `rel=""` dir first, then children parents-first using `listDir` (so later `open` calls hit `GdCache`). Shortcuts and a repeated folder id are `skip=true` items (a move therefore keeps their parent). Native files have size 0 here.
- `search(v, q)`: lowercase `contains` on the shown names, depth walk, 20 s / 300 hits then `partial=true`; items carry `path`.
- Wiring: see section 14 line of Part 4. The device list entry is `{id:"gd:...", name:"Drive: <email>", kind:"Drive", via:"Google Drive", ok:true, smb:true}`.

### 17.5d How Part 5 works (writing, in `GdriveFs`)
- `mkdir(v)` = `ensureDir`: `resolve`; on `NotFound` creates the parent first (recursive), then `POST /files` (folder mime, `parents`); idempotent, an existing FILE of that path -> `IOException("File exists ...")`. Root = id `root`. Result goes into `GdCache`.
- `write(v, input, size, cb)`: `ensureDir(parent)`; `existingFile` (cache first, else ONE `name = '...'` query, oldest plain file wins; a folder of that name -> "Is a directory", a Google-native file -> refused, shortcuts / hidden kinds ignored); `startSession` = `POST /upload/drive/v3/files?uploadType=resumable` (new) or `PATCH /files/ID?...` (update in place = new version, D7) with `X-Upload-Content-Length`, session URI = response header `location` (3 tries, safe because nothing is created yet). Stale cache id (`NotFound` at session start) -> drop and create instead.
- Upload loop: one 8 MiB buffer (`CHUNK`), `PUT session` with `Content-Range: bytes a-b/size`, `ok = {308}` (`GdApi.send`). 308 -> `Range` header says what Drive has (`received`); the rest of the buffer is re-sent; a result outside the buffer = "out of step" error (the input cannot go backwards). A network `IOException` -> wait 1, 2, 3 ... s, ask the session (`Content-Range: bytes */size`), continue; more than 5 failures (counter reset on progress) -> error. 200 / 201 = finished. Empty file: session + `PUT` with no `Content-Range`. A 404 on the session = "upload session expired" error. `cb(n)` = bytes Drive CONFIRMED (delta, like the other file systems), so a re-sent chunk is never counted twice; coarse (per 8 MiB) by design. `Cancelled` from `cb` propagates (the session is left to expire; deleting it is Part 9).
- Not done here: modified time of the uploaded file (Part 6 `setMtime`), mime type (Drive guesses from the name), `copyWithin` / `remove` / `rename` / `move` (Part 6), saving the session for resume after an app restart (Part 9).

### 17.5e How Part 6 works (changing things, in `GdriveFs`)
- `GdCache.Ent` got `link: Boolean = false` (set by `row()` for shortcuts: their cached id is the TARGET's). `changeable(v)` refuses the root and shortcuts for `remove` / `rename` / `move` (\"shortcuts cannot be renamed, moved or deleted here\"), so a change can never hit the original by mistake.
- `remove` = `PATCH {trashed:true}` (D6), NotFound = already gone, cache `drop`, `progress` gets the one name (a folder is trashed as a whole). `rename` = `PATCH {name}`; clash check through `names(parent)` -> `Exists`; a Google-native file is shown with `.docx` etc., so `realName` strips that extension before storing. `move` = `PATCH` with `addParents` / `removeParents` (only if the folder changes) and `name` (only if it changes); missing target folders are created (`ensureDir`), clash -> `Exists`, folder into itself refused.
- `copyWithin` = `POST /files/ID/copy` (name + parents), used by `Jobs` when source and destination are the same `gd:` account (`Jobs` condition now `ftp:` OR `gd:`). Returns false (normal download + upload) for folders, native docs, shortcuts and when the target already exists (the normal path then updates in place, D7).
- `setsMtime = true`, `setMtime` = `PATCH {modifiedTime: GdTime.format(sec)}`. `Jobs` `keepMt` now also true when source or destination is `gd:`, so copies to / from Drive keep the original date.
- Cache: `drop` of the old path (and the new one) after every change, then `put` of the new path for rename / move / copy.
- Not done: permanent delete / empty trash, renaming shortcut files themselves, `copyWithin` for folders (the normal walk copies them file by file).

### 17.5f How Part 7 works (UI polish)
- `ui.html`: the chip of the open device (`renderPeers`) and the rows of the Devices picker (`devPicker`) show the cloud icon (`ic('ftp')`, which is a cloud) for `kind==='Drive'`; the green pill with "Drive · Google Drive" already came from `connTxt`. `openDev` calls `/api/warm` for `gd:` too -> `GdriveFs.warmUp` (lists My Drive in the background, fills `GdCache`, never throws).
- Expired grant: when a folder load fails with "Google Drive needs you to sign in again" (`GAuth` / `GdApi` 401 text, keep it in sync), `loadMain` shows a dialog with a **Reconnect** button that opens the Google Drive sheet. Other errors still toast.
- Free-space line: no code needed, `/api/space` -> `GdriveFs.space` (`about.storageQuota`) already works for `gd:`; a Drive with unlimited quota shows none.
- Native-docs hint: one sentence in the Google Drive sheet text.
- Not done: a pill in the main-menu device list (it has none for any kind).

### 17.5g How Part 8 works
- Viewers (picture, video, PDF, Office, text), "Open with", share and Send to use `Jobs.ep(dev).open()` / `/api/dl` already, so Drive needed no code there (Google-native docs arrive as the exported .docx / .xlsx / .pptx, real size known after the export).
- `RemoteThumbs.make` asks `GdriveFs.thumb` first for `gd:` (files.get `thumbnailLink` + `videoMediaMetadata`, 400 px, via `GdApi.stream`); no preview = the old download path. Same disk cache.
- "Open in Drive" (selection bar, one item, Drive only): `GET /api/gdlink` -> `webViewLink` -> `LSAndroid.openLink` (only drive.google.com / docs.google.com https links).
- Not done: sharing settings / public links (creating permissions).

### 17.5h How Part 9 works (`GdriveFs`, `Partials`)
- `write` is now `upload(v, input, size, offset, tag, cb)`; `write` = offset 0, no tag. `writeResume` = the same with offset and the source tag (`size:mtime`, from `Jobs`).
- With a tag the session URI is saved in `Partials`: `gd|acc|path` = tag, `gdsess|acc|path` = session URI (new `Partials.tagOf`). Purge after 7 days = Drive's own session lifetime.
- `partial(v, tag)`: tag matches -> `PUT Content-Range: bytes */*` on the saved session -> 308 + `Range` = bytes Drive has; 200 / 404 / 410 -> entry dropped, 0. `Jobs` then seeks the source and calls `writeResume`; a lost session -> `BadReq("resume offset lost")` which `Jobs` treats as "start again".
- Cancel (`Cancelled`) -> `DELETE` on the session + entries dropped. Success -> entries dropped. "session expired" -> entries dropped. Any other error keeps them (Resume / copy again continues).
- Token expiry mid-job: `GdApi.send` already re-asks Play Services (401 retry); if Google wants consent again the job stops with the Reconnect message, the part is kept, Resume works after Reconnect.
- Not done: Resume offered for downloads (Drive -> phone restarts from 0).

### 17.5i How Part 10 works (`GdApi.GdGate`, `GdriveFs`)
- `GdGate` (object in `GdApi.kt`, per account id): every `send` / `stream` calls `GdGate.await` first, so requests START >= `gap` ms apart (40 ms). A rate-limit answer (429 / 403 rateLimit) calls `hit(wait)`: gap x2 (max 1 s) and ALL threads of the account wait until the back-off is over; 20 good answers in a row halve the gap. 5xx still sleep only the calling thread. `MAX_TRIES` 5 -> 6. Upload chunk bodies are not paced (only request starts).
- Listings: `GdriveFs.ls` -> `FtpCache.get` (fresh, 30 s default, the FTP TTL setting) -> `FtpCache.getStale` (memory, then `filesDir/ftpcache/<hash>.json`) shown at once + `refreshLater` (one thread per folder; `Core.onFtpChanged(id, path)` when the list differs) -> else `fresh()` (asks Drive). The Drive account id (`gd:...`) is the cache key, so FTP ids cannot clash. "Clear FTP cache" (Cleanup / `/api/ftpcache?clear=1`) also clears Drive lists - harmless, they refill.
- `forget(p)` replaces the bare `GdCache.drop` after every write (remove, rename, move, failed-target retry, `invalidate`): drops path ids, the list of `p` and below, and the list of its folder; sets `dirtyAt[id]` so a listing that was in flight during a write is not stored. New folder / upload done / copyWithin / setMtime drop the parent's list. `names()` (name clashes) still lists straight from Drive.
- `fetchRows` no longer sends `orderBy=createdTime` (slow on big folders); `Row.created` is read from `createdTime` and the rows are sorted oldest-first locally before `dedup`.
- `warmUp` fills the FtpCache for `/` (skipped when fresh). `Gdrive.remove(id)` clears both caches.
- Not done: path -> id cache is still memory only (first folder after a restart costs one name query per path part); no prefetch of sub-folders; search / walk do not fill the list cache.

### 17.5j How Part 11 works (`GdriveFs`, `GdApi.url`)
- `GdApi.url`: every `/files`, `/files/ID`, `/files/ID/copy` call gets `supportsAllDrives=true` (export excluded: that endpoint has no such parameter). List / lookup queries use `lq()` = `corpora=allDrives` + `includeItemsFromAllDrives=true` (children, name lookups, `existingFile`).
- The folder LIST of `/` (My Drive) also shows three virtual folders `[Shared with me]` (`sharedWithMe = true`), `[Shared drives]` (`/drives`, each drive = a folder whose id is the drive id) and `[Trash]` (`trashed = true and 'me' in owners`). Their Ent ids are `@shared` / `@drives` / `@trash`; `fetchRows` dispatches on them; they are added only by `listDir(..., virt = true)` (list + resolve), never in walk / search / totals / `names`.
- Views are read-only: `ensureDir` (so upload / mkdir / copy into) and `changeable` refuse them; items directly in `[Shared drives]` cannot be renamed / moved / trashed; items directly in `[Shared with me]` cannot be moved or trashed (their real parent is unknown). Moving an item OUT of `[Trash]` into a real folder = restore (`restore()`: `trashed=false`, `addParents` / `removeParents`). Deleting or renaming inside Trash is refused (D6). `link()` / `stat()` know the views.
- `search` from `/` also walks `[Shared with me]` and `[Shared drives]` after My Drive. Storage Analyzer works through the Part 4 wiring (`StorageScan`, 4 threads).

### 17.6 Parts
| Part | Content | State |
|---|---|---|
| 1 | Sign-in, saved accounts, Google Drive sheet, drawer row, dependency | coded 2026-10-28, not compiled |
| 2 | **HTTP layer**: `GdApi` (Bearer, one 401 retry, back-off, error mapping, paging, stream, PATCH override), `GdTime`, `GdMime`, `GdCache` (17.5a) | coded 2026-10-28, not compiled |
| 3 | **Read A - listing** (DONE, see 17.5b): `GdriveFs` skeleton (`Endpoint` members that come later throw a clear "not available yet" error); path -> id resolution (`GdCache`, then a `name = '...'` query in the parent, then a full listing), `ls` / `lsFast` (`GdApi.pages`, `FILE_FIELDS`, `trashed = false`), duplicate names shown as `name (2)` (D4), native docs listed with `.docx` / `.xlsx` / `.pptx` (D5, `GdMime`), hidden kinds skipped, shortcuts shown as their target, mtime via `GdTime`, `names`, `invalidate`, `stat` (folder totals bounded 20 s), `space` (`about.storageQuota`). Not wired to `Jobs` yet | coded 2026-10-28, not compiled |
| 4 | **Read B + wiring**: `open` (`GdSource`: `alt=media` with Range, re-open after a drop, retries like `RemoteSource`; native docs through `files.export` into a temp file), `walk` / `walkEach` (shortcuts skipped), `search` (bounded walk, 20 s, `partial`); wire `Jobs.ep("gd:")`, `Gdrive.peers()` + `kind:"Drive"` in `/api/peers`, UI `kind` filters (D8), `Jobs.printTarget` refuses `gd:`, `StorageScan` treats `gd:` like FTP (4 threads) | coded 2026-10-28, not compiled |
| 5 | **Write A**: `write` (resumable session D7, 8 MiB chunks, 308 through `GdApi.send(ok=...)`, resync after a failure, overwrite = update in place, empty file), `mkdir` (recursive, idempotent) - see 17.5d | coded 2026-10-28, not compiled |
| 6 | **Write B**: `rename`, `move` (`addParents` / `removeParents`), `remove` (trash, D6), `copyWithin` (`files.copy`), `setsMtime` / `setMtime` (`GdTime.format`), cache invalidation after every write - see 17.5e | coded 2026-10-28, not compiled |
| 7 | **UI polish**: "Drive" pill and cloud icon in device lists, `warmUp`, expired grant -> clear "Reconnect" message / button, free-space line, native-docs hint - see 17.5f | coded 2026-10-28, not compiled |
| 8 | **Viewers + sharing** (see 17.5g): viewers, "Open with", share and "Send to" already work through `Jobs.ep("gd:")` + `Source` (nothing to add); Drive's own thumbnails (`GdriveFs.thumb`, no download) and "Open in Drive" (`GdriveFs.link`, `/api/gdlink`, `LSAndroid.openLink`) | coded 2026-10-28, not compiled |
| 9 | **Robustness A** (see 17.5h): `resumable = true`, `partial()` / `writeResume()` with the upload session URI kept in `Partials` (survives an app restart), cancel deletes the session, token expiry mid-job keeps the part for Resume | coded 2026-10-28, not compiled |
| 10 | **Robustness B** (see 17.5i): shared per-account rate gate `GdGate`, listings in `FtpCache` (memory + disk, stale-while-revalidate), no server-side `orderBy` | coded 2026-10-28, not compiled |
| 11 | **Extras + verification** (see 17.5j): Shared with me / Shared drives / Trash as virtual folders at the top of the Drive (`supportsAllDrives`, `corpora=allDrives`), restore from Trash, search also covers shared areas, Storage Analyzer on Drive (already wired in Part 4, checked in 17.7k), full device checklist 17.7k. Server-side name search NOT done (17.8) | coded 2026-10-28, not compiled |

Mapping from the first plan of 6 parts: old 2 -> new 2, 3, 4; old 3 -> new 5, 6; old 4 -> new 7, 8; old 5 -> new 9, 10; old 6 -> new 11.

### 17.7 Part 1 checklist (device with Google Play services; stop at the first failure and paste the message)
1. CI APK build is green (`play-services-auth` resolves; `GAuth.kt` / `Gdrive.kt` / `MainActivity.kt` compile). Risk spots if not: `AuthorizationRequest.builder().setAccount(Account)`, `Identity.getAuthorizationClient(Context)` overload, `getAuthorizationResultFromIntent(Intent?)`, smart cast of `s.token` in `MainActivity.gdStart`.
2. Drawer shows **Google Drive**. Open it: "No account yet".
3. Add Google account -> account chooser -> "Google hasn't verified this app" -> Advanced -> continue -> allow Drive access. Back in the app: toast "Signed in: <email>" and one row with that email.
4. Failure texts and meaning: "code 10" = package / SHA-1 does not match the Android OAuth client (17.2); "Access blocked ... not completed the Google verification" = the Google account is not in Audience > Test users; "has not been used ... disabled" = enable the Drive API; "code 7" = no internet.
5. Reconnect on the row works without the account chooser (silent) or with consent; Remove asks "Remove?" and the row disappears; the account is gone from `lanshare.json` -> `gdrive`.
6. Force-close and reopen: the account is still listed.

### 17.7b Part 2 compile check (nothing in the UI uses `GdApi` yet, so this is a build check only)
1. CI APK build is green. Risk spots if not: destructuring `val (_, reason, _) = parseError(...)`; `Math.floorDiv(Int, Int)` / `Math.floorMod(Long, Long)` overloads in `GdTime`; `query + ("pageToken" to token)` smart cast in `GdApi.pages`; `String.format(Locale.US, ...)`.
2. First runtime use is Part 3 (`GdriveFs.ls`). If the first listing fails, the message comes from `GdApi.errorFor` (404 NotFound, 403 Denied with the Cloud Console hint, 401 "Reconnect").

### 17.7c Part 3 compile check (nothing creates `GdriveFs` yet, build check only)
1. CI APK build is green. Risk spots if not: `override fun write(...) = later(5)` (return type `Nothing` against `Unit`: if rejected, use a block body `{ later(5) }`), `private class Row` used by private functions of a public class, `catch (_: NotFound) { continue }` inside `for`/`while` labelled `loop@`, `splitExt` destructuring, `ArrayDeque` (Kotlin's, as in `SftpFs`).
2. First runtime use is Part 4 (`Jobs.ep`). Then: listing the root, a sub-folder, a folder with two files of the same name (`name (2)`), a Google Doc (shows `.docx`), a shortcut.

### 17.7e Part 5 check (device with a signed-in account; stop at the first failure and paste the message)
1. Copy a small photo from the phone into My Drive: it appears in Drive (web) with the right size. 2. Copy the same file again, same name: Drive shows ONE file with a new version, not a duplicate. 3. Copy a folder with sub-folders into Drive: the tree is there. 4. Copy an empty file. 5. Copy a file over 20 MB (3 chunks), switch Wi-Fi off for 5 s in the middle: it continues or fails with a clear message. 6. Copy a file onto a Google Docs file name (`x.docx`): clear refusal.

### 17.7d Part 4 check (needs a device with a signed-in account)
1. CI APK build is green. Risk spots if not: destructuring `val (r0, fid) = stack.removeLast()` of `ArrayDeque<Pair<String,String>>`; `continue` inside `catch` in `walkEach`; `Core.appCtx?.cacheDir` in `exportToTemp`.
2. Device list shows **Drive: <email>** (not in the print or PC-screen pickers). Tap it: My Drive lists; open a sub-folder; pull-to-refresh works.
3. Download a normal file to the phone (copy), open a photo / PDF from Drive in the viewer, seek in a video. Copy a Google Doc: arrives as `.docx`.
4. Copy a folder from Drive to the phone: structure kept, shortcut not copied (job shows it as skipped). Search in Drive (name part): hits with paths; a big Drive says partial.
5. Writing to Drive (paste into it, new folder, rename, delete) shows \"not available yet (Part 5 / 6)\" - expected.

### 17.8 Limits / not done (Parts 1-11)
- Everything in the plan is coded but never compiled or run. Shared items: no rename of shared drives, no move / trash of items sitting directly in "[Shared with me]" (open the real folder), no permanent delete or empty-trash (D6), Trash view lists only items YOU own and trashed yourself explicitly. Search walks My Drive + Shared with me + Shared drives (bounded 20 s / 300 hits); Drive's own `name contains` is NOT used (it matches word starts only), so no server-side search. A real My Drive folder called like a view gets "(2)".
- Needs Google Play services; no fallback sign-in without them.
- Testing mode: grant expires after 7 days (17.2).
- `GdApi.send` re-sends a failed POST only for status codes (429 / 5xx), never after a network error (could create twice); resumable chunk PUTs must be re-synced by the caller (Part 5).

- Check 17.7j (Part 10): 1. Open a big Drive folder (1000+ files), leave, reopen: the list shows at once. 2. Close the app, reopen, open the same folder: list at once, then (if Drive changed) it reloads by itself. 3. Add a file in drive.google.com, pull to refresh: it appears. 4. Rename / delete / new folder in the app: the list is right immediately, also after reopening the app. 5. Open a folder with thousands of thumbnails: no \"rate limit\" error; with a slow list it just gets slower. 6. Remove the account in the Google Drive sheet, add it again: no old lists.
- Check 17.7i (Part 9): 1. Copy a big file (500 MB+) phone -> Drive, switch Wi-Fi off mid-way, on again: it continues, no restart from 0. 2. Same, but force-stop the app during the copy; reopen > Resume: continues from the part Drive has. 3. Cancel a copy: no half file appears in Drive (check Drive's recent / storage). 4. Revoke the app at myaccount.google.com mid-copy: Reconnect message, then Resume works.
- Check 17.7h (Part 8): 1. Open a Drive folder with pictures / videos: thumbnails appear fast without a big download. 2. Open a picture, a video, a PDF, a docx from Drive in the viewers. 3. Select one file > Open with / Share works. 4. Select one item > "Open in Drive" opens it in the browser or Drive app. 5. Send a file from the phone share sheet into a Drive folder.
- Check 17.7g (Part 7): 1. Open a Drive place: cloud icon on the chip and in Devices, green "Drive · Google Drive" pill. 2. First folder opens fast right after tapping the place. 3. Revoke the app at myaccount.google.com, open the Drive: a "Reconnect" dialog appears and opens the Drive sheet. 4. Free space shows for the Drive. 5. The Google Drive sheet mentions Docs / Sheets / Slides.
- Check 17.7f (Part 6, after the APK builds): 1. Rename a file and a folder in Drive from the list: new name shows, old gone. 2. Rename to an existing name -> \"name already exists\". 3. Move a file into another Drive folder and into a new (not yet existing) one. 4. Delete a file: it is in Drive's trash (drive.google.com > Trash), not gone for good. 5. Copy a file inside the same Drive: instant, no progress through the phone. 6. Copy phone -> Drive: modified date in Drive = the phone file's date. 7. A shortcut: rename / delete shows the \"shortcuts cannot ...\" message and the original is untouched. 8. Rename a Google Doc (shown as x.docx) -> Drive name has no `.docx` doubled.

### 17.7k Full device checklist (after the APK builds; stop at the first failure and paste the message)
1. Sign in (17.7), open the Drive: cloud icon, `[Shared with me]`, `[Shared drives]`, `[Trash]` at the top of the list. 2. Open `[Shared with me]`: items shared by others; open a file, download it, open a shared folder and go deeper. 3. `[Shared drives]` (account with one): drive opens, upload a file into it, rename / delete it there. 4. Copy a shared file into My Drive (same account, instant) and to the phone. 5. Delete a file in My Drive, it appears in `[Trash]`; move it out of Trash into a folder: it is back in Drive. 6. Delete inside `[Trash]` -> message about drive.google.com. 7. Search from `/` finds a file that lives in `[Shared with me]`. 8. Storage Analyzer on the Drive: scan finishes, folders tab shows sizes, Open / Open containing folder work. 9. Run 17.7 - 17.7j quickly (sign-in, listing, download, upload + resume, rename / move, thumbnails, big folder cache).

### 17.9 Prompt for the next session (paste it)
"Read HANDOVER.md chapter 17 (17.1-17.6, 17.5j, 17.7k, 17.8). All 11 Drive parts are coded; paste the CI compile errors / the failing check of 17.7k and fix them in `core/GdApi.kt` / `core/GdriveFs.kt` / `core/GAuth.kt`. Honour 17.3. Update section 14 and deliver as `pcshare-gdrive-part12.zip`."
- 2026-10-29 - Fix: archive details showed "Archive details unavailable" / "unknown api" because `core/ZipInfo.kt`, `core/ArcInfo.kt` and the `arcinfo` GET route in `Routes.kt` were missing from the project (only `ui.html` had the client side). Re-created all three (ZipInfo: end record + ZIP64 + windowed central-directory read, AES / ZipCrypto detection, SFX stub offset; ArcInfo: ZIP via ranged reads, other formats from `ArcStore.index`). NOT compiled here - build and open a ZIP, a password ZIP, a .7z and a .tar.gz.
- 2026-10-29 - ZIP dialog shows "Original X -> zipped ~ Y (~N% smaller) . F files" before zipping (all selections, files too). `Jobs.zipScan` now returns {size, files, est, items[]} (est by file kind: STORE_EXT x1.0, text x0.25, bmp x0.15, wav x0.8, other x0.6, +120 B per file; items carry their own est); `/api/zipscan` returns that object; `zipDlg()` always scans and subtracts ticked "leave out" entries live. NOT compiled.

---
