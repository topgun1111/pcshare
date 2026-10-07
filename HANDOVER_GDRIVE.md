# Handover: Google Drive in LANShare (pcshare)

Status: **All code written (2026-10-07), NOT yet compiled or run on a phone.** Gdrive.kt, GDriveFs.kt, Cfg, Routes, Jobs, MainActivity bridge (openBrowser) and ui.html (Settings > Cloud storage, drawer 'Cloud' button) are in place.
Next step: push -> GitHub Actions build -> fix any compile errors -> run the test checklist below (section 5). The TO DO list below is kept for reference; items 1-4 are done.

## Request
"Add cloud storage section to settings. Add support for Google Drive, login through default browser."

## Design decisions (already made)
- Google Drive appears as one more **device** (id `gdrive:main`, chip "Google Drive", via "Cloud"), next to phones and SMB PCs. It is an `Endpoint` like `SmbFs`, so browse/copy/move/paste/send/delete/zip/search all reuse the existing UI and `Jobs`.
- Login: OAuth 2.0 authorization code + **PKCE**, opened in the **default browser** (`LSAndroid.openBrowser(url)`). Redirect = `http://127.0.0.1:<port>/oauth/google` -> handled by the app's own MiniHttp server (`Gdrive.callback`). No manifest/activity change needed. Needs an OAuth client of type **Desktop app** (client ID + client secret).
- Client ID: user pastes it in Settings > Cloud storage (stored in lanshare.json under `gdrive`). Optional built-in constants `BUILT_IN_CLIENT_ID/SECRET` in `Gdrive.kt` skip that form.
- Scope: full `https://www.googleapis.com/auth/drive` (needed to browse existing files). Refresh token stored in `lanshare.json` (same place as SMB passwords).
- Delete = move to Drive **trash** (safer, reversible).
- Google-native files (Docs/Sheets/Slides/Drawings) are shown with `.docx/.xlsx/.pptx/.pdf` appended and are **exported** when opened/copied (Drive export limit 10 MB). Forms/Sites etc. are listed but not downloadable (walk marks them `skip`).
- Shortcuts: GF has both `id` (the entry itself - used for rename/trash/move) and `tid` (target - used for list/open).
- Duplicate names in one Drive folder get ` (2)`, ` (3)` suffixes (deterministic: order `folder,name,createdTime`).
- Names containing `/` or `\` are shown with `∕` / `＼`.
- HttpURLConnection has no PATCH -> `Gdrive.call("PATCH", ...)` sends POST + `X-HTTP-Method-Override: PATCH`. Redirects are disabled because resumable uploads answer `308`.

## DONE
`app/src/main/java/com/lanshare/app/core/Gdrive.kt` (complete, uncompiled):
- `status()`, `update(JSONObject)` ops: `client {id,secret}`, `login` (returns `{url}`), `cancel`, `logout`
- `callback(ex)` for `/oauth/google` (returns a small HTML page with an "Open LANShare" intent link)
- `token(force)`, `call(...)` (auth, 401 refresh, retry/back-off, error mapping via `fail`), `openStream(url, from)`
- `peer()`, `ep()`, `connected`, `email`, `ok`, `val fs: GDriveFs` (class not written yet)
- It already references things that do not exist yet: `Cfg.gdrive()/setGdrive()`, `GDriveFs` (with `clearCache()`).

## TO DO (in this order)

### 1. `Cfg.kt`
```kotlin
@Synchronized fun gdrive(): JSONObject = JSONObject(obj.optJSONObject("gdrive")?.toString() ?: "{}")
@Synchronized fun setGdrive(o: JSONObject) { obj.put("gdrive", o) }
```
Keys used: `client_id, client_secret, refresh, email`.

### 2. New `core/GDriveFs.kt` - `class GDriveFs : Endpoint` (id = `Gdrive.ID`, name "Google Drive")
Constants: `API = https://www.googleapis.com/drive/v3`, `UP = https://www.googleapis.com/upload/drive/v3`.
- `data class GF(id, tid, real, name, mime, size, mtime, parent)`; `dir` = mime `application/vnd.google-apps.folder`; `native` = Docs/Sheets/Slides/Drawings (export mime + ext map); `unsupported` = other `application/vnd.google-apps.*`.
- Real root id: `GET files/root?fields=id` (lazy). `ROOT` = GF with that id.
- `kids(parentTid)`: `files?q='ID' in parents and trashed=false&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime,shortcutDetails(targetId,targetMimeType))&pageSize=1000&orderBy=folder,name,createdTime&supportsAllDrives=true&includeItemsFromAllDrives=true`, paged; cached 30 s as `LinkedHashMap<displayName, GF>`. **Update the cache incrementally** after create/rename/move/remove/upload (do not just clear it) - Drive listings can lag and a mkdir right after a mkdir would otherwise create duplicate folders. `clearCache()` public.
- `resolve(path)`: walk segments with `kids()`, throw `NotFound("No such file or directory")`.
- `ls`: items (dir, size, mtime in **seconds**; parse RFC3339 with SimpleDateFormat UTC - minSdk 24, no java.time).
- `names`, `walk` (iterative, visited-set against shortcut loops, native = size 0, unsupported = `skip=true`).
- `open(v)`: normal file -> `GSource` (seekable, `Gdrive.openStream("$API/files/$tid?alt=media&supportsAllDrives=true", pos)`, 5 retries like `RemoteSource`, size 0 -> -1 at once); native -> `files/{tid}/export?mimeType=...` into a `BytesSource` (Source subclass with ByteArray); unsupported -> `Denied`.
- `write(v, input, size, cb)`: `ensureDir(parent)`; if same name exists (file) update it (`PATCH $UP/files/{id}?uploadType=resumable`), else create (`POST $UP/files?uploadType=resumable`, metadata `{name, parents}`; headers `X-Upload-Content-Type`, `X-Upload-Content-Length`); session URI = `Location` header. Upload in 4 MiB chunks with `PUT session` + `Content-Range: bytes a-b/total` (`Gdrive.call(..., auth=false, raw=true)`): 308 -> read `Range` header; 200/201 -> done; 5xx/IOException -> query `Content-Range: bytes */total`, resume (max ~5 tries); 404/410 -> "upload session expired". Size 0 -> single empty PUT. Call `cb(len)` once per acknowledged chunk, **outside** the try (it may throw `Cancelled`). Folder with that name -> `Exists`; native doc -> `Denied`.
- `mkdir(v)`: like `mkdirs` (create missing ancestors, tolerate existing folders, file in the way -> `Exists`).
- `remove(v, progress)`: root -> `Denied`; `PATCH files/{id}` `{"trashed":true}`; evict from cache; `progress?.invoke(name)`.
- `rename(v, newName)`: same validation as `LocalFs.rename`; name taken -> `Exists`; for native docs strip the added extension before sending.
- `move(v, toV)`: LocalFs semantics (destination is an existing folder -> move inside; else last segment = new name); `PATCH files/{id}?addParents=..&removeParents=..` (+ name if changed); fix cache.
- `stat(v)`: name/path/dir/size/mtime + `ctime` (createdTime), `readonly` (!capabilities.canEdit); folders: files/folders/total/partial via a time-capped (~6 s) walk.
- `space(v)`: `about?fields=storageQuota(limit,usage)`; no `limit` (unlimited) -> `null`.
- `search(v, q)`: use `files?q=name contains '<q>' and trashed=false` (escape `\` and `'`), fields incl. `parents`, max ~100 results / 15 s, then build each result's path through a memoised `folderInfo(id) -> (name, parentId)` chain up to the root id; keep only results under `v`; `Item.path` = full virtual path. Note: Drive's `contains` matches word beginnings, not any substring - mention in UI/notes if it matters.

### 3. Hooks
- `Jobs.kt` `ep()`: add `dev.startsWith("gdrive:") -> Gdrive.ep()` (before the `else`). Printing to Drive is not possible (`printTarget` already throws "device is offline" for unknown ids) - fine.
- `Routes.kt`:
  - `handle()`: add `p == "/oauth/google" -> Gdrive.callback(ex)` (before the `/api/` branch).
  - `"peers"`: after the SMB loop `Gdrive.peer()?.let { a.put(it) }`.
  - GET section: `"gdrive" -> if (ex.method == "GET") return ex.json(Gdrive.status())`.
  - POST section (after `val b = ex.bodyJson()`): `"gdrive" -> ex.json(Gdrive.update(b))`.
- `MainActivity.kt` Bridge:
```kotlin
@JavascriptInterface fun openBrowser(url: String): Boolean {
    if (!url.startsWith("https://accounts.google.com/")) return false
    runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    return true
}
```

### 4. `assets/ui.html`
- **Settings** (`openSettings()`, after the SMB row `rb`): a "Cloud storage" row (same `button.set` pattern) whose small text = `Google Drive · <email>` / `not connected` (from `GET /api/gdrive`), `onclick: close(); openCloud()`.
- New `openCloud()` sheet (copy the structure of `openSmb()`): row "Google Drive" + status; 
  - not configured (or "Change client ID"): short setup text + inputs Client ID / Client secret -> `POST /api/gdrive {op:'client', id, secret}`;
  - configured, not signed in: button **Sign in with Google** -> `POST {op:'login'}` -> `LSAndroid.openBrowser(r.url)` (fallback: copy link) -> poll `GET /api/gdrive` every 1.5 s (max 10 min) until `pending` is `ok` (toast "Google Drive connected", `S.sig='';pollPeers()`) or `err` (toast `error`);
  - signed in: **Open** (`close(); openLoc('gdrive:main','/')`) and **Sign out** (`POST {op:'logout'}`; if `S.dev==='gdrive:main'` -> `openDev('local')`; `S.sig='';pollPeers()`).
  - Optional: a "Cloud" button in the drawer footer next to SMB / Settings.
- `doPrint()` (~line 961): exclude cloud peers: `S.peers.filter(p=>!p.cloud&&p.smb).concat(S.peers.filter(p=>!p.cloud&&!p.smb))`.
- Peers with `cloud:true` have `ip:""` and `seen:0` - `peerSub()` already copes; check nothing else uses `p.ip`.

### 5. Test checklist (on a phone)
1. Settings > Cloud storage > paste client ID/secret > Sign in -> default browser opens -> allow -> "Signed in" page -> back in the app the chip "Google Drive" appears.
2. Browse My Drive, open a photo/video/PDF (streaming + seek), open a Google Doc (exports to .docx).
3. Copy phone -> Drive (upload, progress, cancel), Drive -> phone, rename, new folder, move, delete (check Drive trash), search, free-space pill.
4. Sign out, kill the app, restart: still signed in until sign-out; after revoking at myaccount.google.com/permissions the next call must show "sign in again".

## Google Cloud setup (user does this once)
1. console.cloud.google.com > new project > **APIs & Services > Library > Google Drive API > Enable**.
2. **OAuth consent screen**: External; add your Google account under *Test users*.
   - While the app is in **Testing**, refresh tokens expire after **7 days** (you must sign in again). Publish the app (In production) to avoid that; Google will show an "unverified app" warning because `drive` is a restricted scope - fine for personal use (Advanced > continue).
3. **Credentials > Create credentials > OAuth client ID > Application type: Desktop app**. Copy *Client ID* and *Client secret* into LANShare, or put them in `BUILT_IN_CLIENT_ID/SECRET` in `Gdrive.kt`.
(The redirect `http://127.0.0.1:<any port>` is allowed automatically for Desktop clients.)

## Other notes
- Existing project facts: Kotlin + WebView `ui.html`; local HTTP server `MiniHttp` + `Routes`; endpoints `LocalFs`, `RemoteFs`, `SmbFs`, wrapped by `ArcEp`; jobs in `Jobs.kt`. Earlier decision: keep PC printing (pcprint) as is.
- `.git` is included only because `app/build.gradle.kts` derives the version name from it; versionCode is time based.
- `app/lanshare.jks` (signing key) is inside the zip - do not publish the zip.
