# LOCKDOWN — Vault feature for LANShare (com.lanshare.app, full + lite)

Status: **PART 1 DONE** (14 passing JVM tests). **PART 2 core DONE 2026-11-13** (vault-side + Android bio layer, NOT compiled; see Part 2 status). **PART 3 core DONE 2026-11-13** (guards, NOT compiled; see Part 3 status). Part 2 UI first cut DONE 2026-11-13: main-menu Vault tile -> `VaultActivity` (create / password unlock / browse / add / mkdir / rename / export / delete / lock; FLAG_SECURE; NOT compiled). Still missing: fingerprint (androidx.biometric), recovery key / backup password / change password screens, auto-lock setting, lock from the file manager. Next: those + Part 4.
Base facts (from the repo): minSdk 24, target 34, Kotlin, `MANAGE_EXTERNAL_STORAGE` already granted, BouncyCastle 1.78.1 already bundled, no `androidx.biometric` yet. Hook points: `core/LocalFs.kt`, `core/Bin.kt`, `core/IndexManager.kt`, `core/ImgSearch.kt`, `core/Albums.kt`, `core/Cleanup.kt`, `core/DupFinder.kt`, `core/Gdrive*.kt`, `core/MiniHttp.kt`, `pe/FtpServer.kt`, `pe/PeService.kt`, `StorageTreemap.kt`, `ImageViewerActivity`, `PdfViewerActivity`, `OfficeViewerActivity`, `PlayerActivity`, `TextEditorActivity`.

## SCOPE (owner clarification)
- The vault locks/hides **local files on this phone only** (internal storage, SD, OTG via `LocalFs`).
- **Out of scope:** items on PCs or other devices (SMB/SFTP/FTP client browsing, Google Drive, peers, PC share). No lock/hide/encrypt action is offered there and their behaviour is unchanged; no remote-side changes of any kind.
- Still in scope (protects the local vault *from* other devices/apps): the phone's own servers (FTP server, HTTP/peer API) must never expose or modify `.lsv` — see Part 3 addendum rows 1–3, 5, 10.
- Part 3 table row "Gdrive*" and addendum row 15 reduce to: never auto-upload/copy `.lsv`; remote filesystems get no vault UI.

## Requirements (from owner)
1. Lock a file/folder with a **password** and/or **fingerprint**.
2. Locked items are **hidden from other apps**.
3. If the app is **uninstalled**, locked data must **survive** and be **openable by a reinstalled copy**.

## Hard truths that drive the design
- Anything in `Android/data/<pkg>` or app-private storage is **deleted on uninstall** → vault must live in shared storage outside those.
- Android **Keystore keys are deleted on uninstall** → the data key can NOT depend on Keystore. It must be derived from the **password**. Fingerprint is only a convenience layer and must be **re-enabled after reinstall** (after one password unlock). Unavoidable.
- Files in shared storage can be seen by any app with all-files access (or root/PC via USB). Hiding therefore = **encryption + opaque names**, not just a dot-folder. Others see random blobs, never names, sizes per file are only approximate, contents unreadable.
- **Forgotten password = data permanently lost.** No backdoor. Mitigation: optional printable 24-word recovery key (Part 2).
- Auto Backup/cloud: vault is outside app data, so not auto-backed-up; encrypted blobs are safe to copy by the user.

---
## PART 1 — Vault storage & cryptography (foundation, no UI)

**Goal:** a self-describing encrypted container on shared storage that any install of the app can open with the password.

**Location:** `/storage/emulated/0/.lsv/` (dot dir + `.nomedia`; name innocuous). Also supported on secondary volumes (SD/OTG) later via per-volume `.lsv`. Shared by `full` and `lite` builds (same format, independent of applicationId/signing key).

**Layout**
```
.lsv/
  .nomedia
  h0            header (primary)
  h1            header (copy; written first, then h0, for crash safety)
  i             encrypted index (tree: id -> real name, parent, type, size, mtime, mime)
  d/ab/<32-hex-id>   encrypted data blobs, 2-level sharded by id prefix
```
No real names, extensions or folder structure on disk. Random 128-bit IDs (SecureRandom).

**Header (`h0/h1`)**: magic (random-looking, not "LANSHARE"), version, scrypt params (N=2^15 default, r=8, p=1; target ≈0.4–0.8 s on a mid phone, auto-calibrated at creation, stored), 32-byte salt, `wrappedMK` = AES-256-GCM(KEK, MasterKey), optional `wrappedMK_recovery` (second wrap under recovery-key-derived KEK), header MAC via GCM tag. Header is padded to fixed size so nothing leaks.

**Key hierarchy**
- `KEK = scrypt(password, salt)` (BouncyCastle `SCrypt`, already a dependency; works on API 24).
- `MK` = random 256-bit master key, never changes → **changing password only re-wraps MK**, never re-encrypts data.
- Sub-keys via HKDF-SHA256(MK, info): `K_index`, `K_data`. Per-file key = HKDF(K_data, fileId) so blobs are not interchangeable.

**Blob format**: `[ver][fileId][baseNonce]` + chunks of 64 KiB, each AES-256-GCM, nonce = baseNonce‖chunkCounter(8B), AAD = fileId‖counter‖isFinal (blocks truncation/reorder/swap). Chunked ⇒ **random access decrypt** (video seek, PDF paging) and constant memory for GB files.

**Index**: single encrypted file, rewritten atomically (`tmp` → fsync → rename), versioned with a monotonically increasing counter, plus `i.bak`. On open, if index missing/corrupt, rebuild is impossible (names encrypted) → hence the backup + transaction log `j` (small append-only encrypted journal replayed on start).

**Operations (internal API, `core/vault/`)**: `create(pw)`, `unlock(pw)`, `lock()` (zeroize MK), `changePassword`, `addFile(src, parent)`, `addFolder(src)` (recursive, resumable), `open(id): SeekableDecryptStream`, `export(id, dst)`, `delete(id)`, `list(parent)`, `move/rename` (index-only, no data rewrite), `wipe()`.

**Locking a plain item** = encrypt into vault → verify (re-read + tag check) → delete original (best-effort overwrite single pass for small files; note flash wear-leveling makes overwrite non-guaranteed) → remove from MediaStore (`ContentResolver.delete`/scan) so gallery drops it.

**Files to add:** `core/vault/VaultFormat.kt`, `VaultCrypto.kt`, `VaultStore.kt`, `VaultIndex.kt`, `VaultStream.kt`.
**Deps:** none new. Test dep only: `testImplementation junit:junit:4.13.2`.
**Done when:** unit tests pass: round-trip 0 B/1 B/64 KiB±1/2 GB file; tamper any byte → auth failure; kill mid-write → vault still opens; wrong password → clean error; reinstall simulation (delete app data, keep `.lsv`) → unlock with password works.

### PART 1 — STATUS: DONE (as built)
Files (all in `app/src/main/java/com/lanshare/app/core/vault/`, pure JVM, no `android.*`): `VaultFormat.kt` (constants, header, node, exceptions), `VaultCrypto.kt` (scrypt/HKDF/AES-GCM), `VaultStream.kt` (`VaultWriter`, seekable `VaultReader`), `VaultIndex.kt` (snapshot + journal), `VaultStore.kt` (public API). Tests: `app/src/test/java/com/lanshare/app/core/vault/VaultTest.kt` (14, pass; 2 GB streamed round-trip incl.).

**Public API (`VaultStore`)**: `create(volumeRoot, pw, logN=calibrate())`, `open(volumeRoot, pw)`, `openWithRecovery(volumeRoot, secret32)`, `exists(volumeRoot)`, `lock()`, `changePassword(old,new)`, `enableRecovery(): ByteArray(32)` (Part 2 turns it into 24 words), `list/get/mkdir/rename/move/delete/totalSize`, `addStream`, `lockFile`, `lockFolder(src,parent,removeOriginal,cancel,progress)`, `open(id): VaultReader` (seekable `InputStream`, `length`, `seek`), `export(id,dst)`, `verify`, `wipe`, hook `onOriginalRemoved: (File)->Unit` (Part 3/4 wires MediaScanner/MediaStore purge there).

**Decisions / deviations from the plan above**
- Header = fixed 512 B, random-padded; fields: magic, ver, scrypt logN/r/p, flags, salt, `wrappedMK`, recovery wrap. AAD of both wraps = magic..salt (flags excluded, so enabling recovery never invalidates the password wrap). `open` tries `h0` then `h1` (after a crash mid password change either may be current).
- scrypt: r=8, p=1, logN 15 (or 16 if 2^15 runs < 0.4 s on the device; 64 MiB cap); header logN > 18 rejected. Password is NFC-normalised UTF-8 (Turkish chars stable). Password change keeps salt/params (recovery wrap stays valid), only re-wraps MK.
- Recovery KEK = HKDF(32-byte secret) (no scrypt: secret is high-entropy).
- Blob: `[ver][fileId 16][baseNonce 4]` + 64 KiB GCM chunks; nonce = baseNonce‖ctr(8); AAD = fileId‖ctr‖isFinal. Plain size derived from file length (no size field). Empty file = one 16-byte final chunk. Truncation at a chunk boundary fails (final flag).
- Index = snapshot `i` (+`i.bak`, `i.tmp` → rename) + append-only encrypted journal `j` (ops PUT node / DEL id, one fsync per commit). Snapshot every 64 ops and on `lock()`; journal truncated only after the snapshot is in place; torn tail ignored. Index-first on delete; orphan blobs (crash between blob write and journal) are swept by `gc()` on open.
- `lockFile`: encrypt → fsync → re-read + all tags + SHA-256 compare → single-pass random overwrite (≤ 16 MiB, best effort) → delete → `onOriginalRemoved`. Verification failure drops the vault copy and leaves the original. `lockFolder` is resumable (merges folders by name, skips items already present with same name+size).
- Name collisions in a folder auto-suffix `name (2).ext`.
- Not in Part 1 (by design): `VaultGuard`, session/auto-lock, biometrics, UI, MediaStore purge, secondary volumes (API takes any volume root; per-volume discovery is Part 3/4).
- Verification limit: compiled and run with kotlinc 1.9.24 + JUnit on a desktop JVM; Android Gradle build and Android 7 `AES/GCM` + BC `SCrypt` behaviour not run on a device.

---
## PART 2 — Authentication: password, fingerprint, session

**Password**
- First use: create vault password (min 8 chars, strength meter, confirm field). Warn clearly: unrecoverable.
- Optional **recovery key**: 24 random words (BIP39-style list bundled), shown once, user prints/writes it; unlocks via second wrapped MK in header.
- Change password: verify old → re-wrap MK → write h1 then h0.
- Throttling: failed attempts counter with exponential delay (stored in app-private prefs; scrypt cost is the real offline defence — state this limit in doc: counter resets on reinstall).

**Fingerprint / biometrics** (add `androidx.biometric:biometric:1.1.0`, minSdk-compatible)
- Enable only **after a successful password unlock**.
- Create Keystore AES-256-GCM key `vault_bio`: `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`, StrongBox if available. On API 30+: `BIOMETRIC_STRONG`; on 24–29: `BiometricPrompt` + `CryptoObject` (Cipher).
- Store `bioWrappedMK = AES-GCM(vault_bio, MK)` + IV in app-private storage (intentionally lost on uninstall).
- Unlock flow: `BiometricPrompt(CryptoObject)` → decrypt `bioWrappedMK` → MK.
- Failure handling: key invalidated (new fingerprint enrolled / reinstall) → silently fall back to password; offer re-enable.
- Settings toggle: "Unlock with fingerprint". Biometric never *creates* or replaces the password.

**Session/auto-lock**
- MK held only in memory in a `VaultSession` singleton (`ByteArray`, zeroized on lock).
- Lock on: app backgrounded > N sec (default 30, options: immediately / 1 min / 5 min / never until exit), screen off, user tap "Lock now", process death.
- On lock: close viewers (broadcast), wipe decrypt cache dir (Part 3), clear in-memory thumbnails.
- Optional: per-vault only (single vault). Per-folder passwords are out of scope (would need multiple MKs; note for v2).

**Files to add:** `core/vault/VaultSession.kt`, `VaultBio.kt`, `VaultRecovery.kt`, `ui/VaultUnlockActivity.kt` (Activity-based, matching existing native chrome style).
**Done when:** password + fingerprint unlock work; fingerprint add/remove invalidates and falls back; reinstall → password unlock → re-enable fingerprint; recovery key unlocks; lock-on-background verified; attempt delay works.

### RECOVERY OPTIONS (owner: password must NOT be the only way in)
All options unwrap the same never-changing MK, so adding/removing any of them never re-encrypts data. Each is optional, set up from Settings > Vault > Recovery, offered in the create wizard. Rule: the app lets the user remove one only while at least one other non-biometric way remains.

| # | Option | How it works | Survives uninstall | Strength | Status |
|---|---|---|---|---|---|
| 1 | Main password | scrypt KEK wraps MK | yes | user-chosen | Part 1 |
| 2 | **Recovery code** (56 chars Base32, 14 groups of 4, 4 check chars; replaces the planned 24 words: no word list available offline; swap later if wanted) | 32-byte secret, HKDF KEK wraps MK (header slot, `F_RECOVERY`) | yes | 256-bit | `VaultStore.enableRecovery`, `VaultRecovery.toCode/fromCode` |
| 3 | **Backup password** (second independent password; give to a trusted person or keep in a password manager) | own salt, same scrypt params, own header slot (`F_BACKUP`); tried automatically by `open()` | yes | user-chosen | `VaultStore.setBackupPassword/removeBackupPassword` |
| 4 | **Recovery key file** `.lsrk` (save to USB/OTG, PC, cloud, email to self) | recovery secret sealed under a file password (scrypt, GCM); useless without that password | yes | password-bound | `VaultRecovery.toKeyFile/fromKeyFile` |
| 5 | **Split recovery (Shamir k-of-n, default 2-of-3)** (family / cloud / safe; one lost or stolen share reveals nothing) | recovery secret split over GF(256); any k shares rebuild it, then option 2 slot opens the vault | yes | information-theoretic | `VaultRecovery.split/combine/shareToCode` |
| 6 | Fingerprint | Keystore key wraps MK copy in app-private file | **no** (by design; re-enable after any unlock) | device-bound | `VaultBio` (UI pending) |
| 7 | Vault backup copy (`.lsv` copied by user) | ciphertext only; with options 1-5 it opens on any phone | n/a | n/a | Part 4 "Export vault backup" |

Rejected (and why): security questions / password hint in the header (low entropy or plaintext leak, readable by any app with all-files access); cloud account reset or "email me a link" (needs a server, a backdoor by definition); PIN-only (weaker than scrypt'd password); biometric-only recovery (Keystore dies on uninstall).
Wizard flow: create password, then "Add a safety net" with Recovery code (recommended) / Backup password / Key file / Split. Show each secret once, require the user to re-enter 3 random groups of the code before saving. Warn: anyone holding a recovery secret or backup password has full access, so store apart from the phone.
Recovery unlock screen ("Forgot password"): tabs Recovery code | Backup password (just the normal field) | Key file (+ file password) | Shares (enter k codes). After success: force setting a new main password.
Throttle: `VaultThrottle` applies to password/backup-password/key-file attempts only (counter in app prefs; reinstall resets it; scrypt is the real offline defence). Recovery code and shares are 256-bit, no throttle needed.

### PART 2 - STATUS (core done, UI pending)
New/changed in `core/vault/`: `VaultFormat.kt` (header slot for backup password, offsets 166..258, still 512 B, old vaults read fine: unused bytes are ignored when flag 2 is off), `VaultStore.kt` (`setBackupPassword`, `removeBackupPassword`, `removeRecovery`, `hasRecovery`, `hasBackupPassword`, `masterKeyCopy`, static `openWithMasterKey`; `open()` also tries the backup slot; password change keeps every slot valid), NEW `VaultRecovery.kt` (code, key file, Shamir, `VaultThrottle`), `VaultSession.kt` (in-memory session, auto-lock, lock listeners), `VaultBio.kt` (Keystore + wrapped MK, Android). Tests: `VaultRecoveryTest.kt` (5).
**Not verified:** no Kotlin compiler in the session, so nothing here was compiled or run. First step next session: `./gradlew testDebugUnitTest`, fix any compile errors. `VaultBio` needs `androidx.biometric:biometric:1.1.0` + `USE_BIOMETRIC` (not added yet, no gradle edit made).
**Still to do in Part 2:** `ui/VaultUnlockActivity.kt` (password, fingerprint button, forgot-password tabs above), settings screens, strings EN+TR, wire `VaultSession.onBackground/onForeground/onScreenOff` from the activity lifecycle, `VaultThrottle.Store` over SharedPreferences, enable-fingerprint after password unlock.

---
## PART 3 — Hiding & isolation (other apps AND the app's own subsystems)

**Against other apps**
- Blobs encrypted, names random, `.nomedia` present → invisible to Gallery/MediaStore, unreadable to file managers with all-files access.
- Immediately after adding, purge original from MediaStore; trigger `MediaScannerConnection` for the old path so it disappears.
- Never create plain temp copies in shared storage. Temp decrypt files (needed only for Office/WebView viewers) go in `cacheDir/vt/`, random names, deleted on close, on lock, and on every app start (sweep).
- No `FileProvider`/share URIs for vault items. "Share" from vault = explicit export confirmation to a temp, time-limited URI, auto-wiped.
- `FLAG_SECURE` on vault screens and viewers opened from vault (blocks screenshots + hides in Recents).
- Vault entries never appear in "Recent files", notifications, clipboard, or share-sheet history.
- App launcher: no change (hiding the *app* is out of scope).

**Against LANShare's own features** (audit list — each must exclude `.lsv` and never see decrypted content unless explicitly unlocked and requested)
| Subsystem | Rule |
|---|---|
| `LocalFs` / `NativeList` / `NlSearch` | skip `.lsv` path always; vault browsed only through `VaultStore` |
| `IndexManager`, `UsIndexActivity`, `ImgSearch` (CLIP) | never index vault; no embeddings of vault images |
| `Albums`, `AlbumMerge`, `PcPictures`, `Media` | exclude |
| `Bin` (recycle bin) | vault deletes are permanent (no plaintext in bin) |
| `Cleanup`, `DupFinder`, `StorageTreemap`, `StoragePie` | exclude; show one opaque "Vault" slice with total size |
| `pe/FtpServer`, `PeService`, `MiniHttp` (PC share) | hard-block `.lsv` path prefix server-side (reject 550/403), not just hide in listings |
| `Gdrive*` sync/upload | block unless user explicitly exports |
| Viewers (`ImageViewerActivity`, `PdfViewerActivity`, `PlayerActivity`, `TextEditorActivity`, `OfficeViewerActivity`) | read from `VaultStream` (in-memory/seekable); ExoPlayer via custom `DataSource` over `VaultStream`; Text editor saves back through vault (re-encrypt); Office → cache temp rule above |
| Thumbnails | memory-only LRU, cleared on lock; no disk thumb cache for vault |
| Logs (`PlainLog`) | never log names/paths of vault items |

**Path guard:** one function `VaultGuard.isVaultPath(path)` (canonical path compare, handles symlinks/`..`/case) called from every file-listing and server entry point; unit-tested.

**Done when:** with a second app holding all-files access (test with any file manager): vault shows only `.lsv/d/..` blobs, nothing openable; Gallery shows nothing; FTP/PC share cannot list or fetch it; CLIP search never returns vault images; no plaintext left in `cacheDir` after lock (verified by sweep test).

### Part 3 addendum — Network & IPC attack surface (audited from the code)

Findings and required fixes. Severity: C=critical, H=high, M=medium.

| # | Where | Finding | Required fix |
|---|---|---|---|
| 1 C | `core/Core.kt` + `MiniHttp.bind()` | Main HTTP server binds `InetSocketAddress(port)` = **all interfaces**, not loopback. `Routes.handle` only gates `dev=root` behind a token; `/api/*` (list/read/write/delete via `L.open(path)`) is otherwise reachable by **any LAN device and any app on the phone** (`127.0.0.1:port`). Vault blobs, headers (`h0` = offline brute-force target) and index could be read, overwritten or deleted. | (a) `VaultGuard` check inside `LocalFs`/`L.open` itself, so no route can bypass it (not per-route). (b) Rec: require the per-run token for all `/api/*` from non-peer clients; bind loopback unless LAN features are on. (c) Reject any path whose canonical form is under `.lsv`, for read **and** write/delete/rename/mkdir/copy/move. |
| 2 C | `pe/FtpServer.kt` / `PeService` | FTP root = `getExternalStorageDirectory()`; whitelist empty ⇒ **everything exposed, writable** (STOR/APPE/DELE/RNTO/MKD/RMD). `.lsv` could be downloaded (offline attack) or destroyed. | Hard-deny `.lsv` in `canRead/canWrite/canModify`, `toFile`, LIST/NLST/MLSD output, RNFR/RNTO targets, SIZE/MDTM/MFMT/MFF/HASH/XCRC/XMD5/XSHA1/XSHA256 (hash commands leak existence). Return generic `550 No such file`. Also forbid renaming something *into* `.lsv`. |
| 3 H | FTP path resolve | `resolve()` removes `..` lexically only; no canonical/symlink check; on FAT/exFAT SD cards paths are **case-insensitive** (`.LSV`). | `VaultGuard` uses `canonicalFile` + lowercase compare + normalizes unicode; re-check **after** `toFile()`; refuse symlinks that resolve into vault. |
| 4 H | FTP login | Password = 6-digit PIN from `kotlin.random.Random` (not `SecureRandom`, 900k space); `failedLogins` is **per connection** — reconnect resets it, parallel connections bypass the 700 ms sleep; compare is `==` (not constant-time); plain FTP if TLS off/cert fails (silent fallback to plain). | `SecureRandom`; **global** lockout per source IP + global backoff (e.g. 5 fails ⇒ 5 min, doubling); `MessageDigest.isEqual`; vault is blocked over FTP regardless of login; show warning when TLS is not active; default `tlsRequired = true`; PIN ≥ 8 chars option. |
| 5 M | FTP PASV/EPSV | Data sockets `ServerSocket(0)` bound to all interfaces; accept is not verified against the control-connection IP ⇒ another LAN host can race the connection and steal/inject a transfer (passive-port hijack). | On accept, close if `remoteAddress != ctrl.inetAddress`; bind PASV socket to the control socket's local address; short accept timeout. |
| 6 M | FTP `FEAT`/errors | Info leaks (hash cmds, error texts with paths). | Generic errors; no path echo for denied items. |
| 7 H | Manifest | `MainActivity` and `OfficeViewerActivity` are `exported=true` with `VIEW`/`SEND` for `content://`, `file://`, `*/*`. Any app can push an intent at them; `file://` + MANAGE_EXTERNAL_STORAGE lets a malicious app make LANShare open/copy a path **into** or **out of** places on its behalf; a crafted intent could point at `.lsv` or at vault-cache temp files. | Intent entry points must run `VaultGuard` on every incoming URI/path and refuse `.lsv` + `cacheDir/vt`; never auto-open vault-related paths from an external intent; viewers opened from the vault are `exported=false` and require an in-process session flag; ignore incoming intents while vault is locked/unlock screen shown. |
| 8 M | `FileProvider` (exported=false, grants URI perms) | Its path config may map broad roots (storage root) → vault blobs shareable via granted URIs. | Audit `res/xml/file_paths.xml`; exclude `.lsv` and `cacheDir/vt`; never grant URIs for vault items except time-limited export. |
| 9 M | `PcPrintService` (exported=true) | System print-service; fine, but must not accept paths. | Verify it only takes system print jobs; no file paths from intents. |
| 10 M | Peer routes `/p/*`, `Discovery`, `PeDiscovery` (UDP/mDNS) | Advertises device and serves peer API to LAN. | Same `VaultGuard` in `peer()` handlers; vault never advertised/listed; discovery payload carries no vault state. |
| 11 M | `RootShell` / terminal (`xterm`) / `Cfg.rootEnabled` | A shell route can read `.lsv` or `cacheDir/vt` and the in-memory key is not reachable, but plaintext temp files are. | Vault cache lives in app-private `cacheDir` only; terminal/root shell blocked from vault paths is impossible to enforce → document: root = out of scope; keep plaintext off disk wherever possible (stream, not temp). |
| 12 M | Logs, crash dumps, `PlainLog` | Could leak paths/names. | No vault names/paths in logs, toasts, notifications, `Jobs` labels, FTP "last transfer" label (`lastTransfer` shows file names in notification!). |
| 13 M | Notifications / `Jobs`/`JobOverlay` | Lock-progress text with file names on lock screen. | Neutral text ("Encrypting 12 items"), `VISIBILITY_SECRET`. |
| 14 M | Clipboard, `ClipPush`, `ClipSendActivity`, `TextSend`, `Inbox` | Vault text/paths could leak through clipboard sync to PC. | Disable clip sync while vault viewer is foreground; never put vault content on clipboard. |
| 15 M | Gdrive upload, `Otg`, SMB/SFTP/FTP client (`core/*Fs.kt`), Archive (`ArcEdit`) | Copy/move between filesystems could carry `.lsv` blobs to remote/other storage or compress them. | Block `.lsv` as copy/zip source unless user picks explicit "Export vault backup" (ciphertext only, safe). |

**Additional rules**
- Single enforcement point: `VaultGuard` called from `LocalFs` core (lowest layer), FTP server, HTTP routes, intents. Never rely on UI-level hiding.
- Optional server-side defence in depth: when vault is **created**, set header `h0` readable only via app (cannot enforce on shared storage — so keep scrypt cost high; and enforce min password length 10 / zxcvbn-style check because the header can be copied by any all-files app).
- Offline brute-force is the main residual risk (any app with all-files access can copy `.lsv`). Mitigations: scrypt N≥2^16 (calibrated ≥0.5 s), 10+ char password enforcement, optional **secret key file stored in app-private storage is NOT used** (would break reinstall). State this trade-off to the owner.
- Add automated tests: FTP + HTTP + intent fuzz suite that tries `.lsv`, `.LSV`, `%2e`, `..`, symlinks, `./.lsv`, unicode variants against every command/route; all must return not-found.

### PART 3 - STATUS (guards done, NOT compiled)
New: `core/vault/VaultGuard.kt` (`isVaultName` lexical: case/NFKC/`%2e`/`%252e`/backslash/trailing dot-space; `isVaultPath` + canonical/symlink, fail-closed; `visible(name)`; `cacheRoot` = `cacheDir`, set in `MainActivity.onCreate`), `core/vault/VaultIntents.kt` (`uri()` = FileProvider guard, `refuse(Uri|Intent)`). Test: `VaultGuardTest.kt` (2).
Wired: `LocalFs.real()/lreal()` throw Denied("not found") for vault paths (covers every HTTP `/api` + `/p` route that uses `L.open/real`: addendum row 1(a,c), 10) and every listing/walk/tree/search hides `.lsv`; `Walker`, `Delta`, `StorageScan`, `Albums`, `ImgSearch`, `Cleanup` skip `.lsv`; FTP: `canRead/canWrite/canModify` deny vault (LIST/NLST/MLSD/SIZE/MDTM/hash/RNFR/RNTO/STOR/DELE/MKD all go through them; generic 550), per-IP global login lockout (`FtpLockout`: 5 fails = 5 min, doubling, max 1 h), constant-time PIN compare, PASV/EPSV bound to the control address + strangers dropped (rows 2-5), PIN from `SecureRandom`; `MainActivity.handleShare` + `OfficeViewerActivity.onCreate` refuse intents pointing at the vault (row 7); all 13 `FileProvider.getUriForFile` calls now go through `VaultIntents.uri` (row 8).
**NOT done (Part 4 / later):** HTTP loopback bind / token for `/api/*` (row 1b; would break PC browser access, owner decision); `tlsRequired` default + TLS warning (row 4); in-process flag so vault-opened viewers may use `cacheDir/vt` (`VaultIntents.refuse` currently refuses `path` extras under `vt`); `FLAG_SECURE`, MediaStore purge hook, thumbnail/viewer `VaultStream` wiring, notification neutral text (rows 12-14), `Bin` (vault delete permanent), `Gdrive*`/`ArcEdit` copy source block (row 15; remote FS never see `.lsv` because `LocalFs` refuses it), FTP/HTTP/intent fuzz suite on a device, `file_paths.xml` cannot exclude `.lsv` (guarded in code instead). Compile first: `./gradlew testDebugUnitTest`.

---
## PART 4 — UI, migration, packaging, testing

**UI (native chrome, matching existing style)**
- Drawer entry "Vault" → unlock screen (password field + fingerprint button).
- Vault browser: reuse `NativeList`/RecyclerView adapter with a `VaultFs` data source (list, sort, select, rename, move, delete, new folder, export).
- File browser long-press / selection toolbar: **"Lock"** (move into vault; for folders recursive with `Jobs`/`JobOverlay` progress, cancel/resume) and in vault: **"Unlock/Restore"** (decrypt back to chosen location).
- Settings → Vault: change password, fingerprint toggle, auto-lock delay, recovery key (create/regenerate), "Wipe vault" (typed confirmation).
- Create-vault wizard with the unrecoverable-password warning and recovery-key offer.
- Reinstall behaviour: on start, if `.lsv/h0` exists but no app prefs → show "Existing vault found — enter your password" (and fingerprint re-enable prompt afterwards).
- Strings in EN + TR (owner is Turkish).

**Migration / versions**
- Header `version` field + index version; reader must accept all older versions; writer upgrades lazily.
- Corrupt `h0` → fall back `h1`; both bad → clear error with recovery-key option.

**Build/packaging**
- Add `androidx.biometric` only; permissions: add `USE_BIOMETRIC` (no others; `MANAGE_EXTERNAL_STORAGE` already present).
- ProGuard/R8: keep BouncyCastle SCrypt classes if shrinking is on.
- `full` and `lite` builds both include the feature.
- Delivery rule: zip contains **only changed/new files**, folder structure preserved.

**Test matrix (manual + unit)**
- Sizes: empty, tiny, 64 KiB±1, 1 GB; many small files (10k); deep folders; unicode/Turkish names (ş ğ ı İ); long names.
- Interrupt: kill app mid-lock, mid-index-write, low storage, SD removal.
- Reinstall: uninstall → install (also full↔lite switch) → password unlock → all files intact; fingerprint re-enable.
- Biometric: enroll new finger → fallback; no biometrics enrolled → option hidden.
- Isolation checklist from Part 3 (second file manager, Gallery, FTP, PC share, CLIP search, recents, screenshots).
- Android 7, 10, 12, 14 devices/emulators.

**Implementation order & checkpoints**
1. Part 1 (+tests) → 2. Part 2 → 3. Part 3 guards → 4. Part 4 UI. Ship after each part builds clean; report only files changed.

## Out of scope (v1)
Per-folder separate passwords; hiding the app icon; cloud sync of the vault; protection against root/forensic acquisition of an *unlocked, running* device; guaranteed secure erase on flash.
