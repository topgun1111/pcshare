# LOCKDOWN — Vault feature for LANShare (com.lanshare.app, full + lite)

Status: **PART 1 DONE** (14 passing JVM tests). **PART 2 core DONE 2026-11-13** (vault-side + Android bio layer, NOT compiled; see Part 2 status). **PART 3 core DONE 2026-11-13** (guards, NOT compiled; see Part 3 status). Part 2 UI first cut DONE 2026-11-13: main-menu Vault tile -> `VaultActivity` (create / password unlock / browse / add / mkdir / rename / export / delete / lock; FLAG_SECURE; NOT compiled). **Part 2 UI second cut DONE 2026-11-13 (NOT compiled):** fingerprint (unlock button + auto prompt, offer after password unlock, on/off in Settings; `androidx.biometric:biometric:1.1.0` + `USE_BIOMETRIC` added), attempt throttle (`VaultPrefs` over SharedPreferences), Forgot password (recovery code, then forced new password via new `VaultStore.resetPassword`), Settings menu (change password, recovery code create/replace/remove, backup password set/remove, auto-lock delay). Min password now 8. `VaultActivity` now extends `FragmentActivity` (BiometricPrompt needs it). **Part 2 UI third cut DONE 2026-11-13 (NOT compiled):** Forgot password now offers Recovery code / Key file (.lsrk, file password, throttled) / Shares (any 2 of 3); Settings can save a `.lsrk` (SAF create-document) and split 2-of-3 shares (both replace the current recovery code); recovery code now asks to re-enter 3 random groups; `ACTION_SCREEN_OFF` receiver in `VaultActivity` calls `VaultSession.onScreenOff`. Still missing: strings EN+TR (UI text is English literals), lock from the file manager, app-wide screen-off receiver (today only while `VaultActivity` is alive), Part 4 items. First step next session: `./gradlew testDebugUnitTest assembleDebug`, fix compile errors. **Part 3/2 app-wide hooks DONE 2026-11-13 (NOT compiled):** new `VaultAppHooks.kt` (installed in `App`): screen-off receiver and background/foreground (no started LANShare screen; rotation ignored) for the whole app, `cacheDir/vt` wiped on lock + process start, MediaStore purge via `VaultSession.onStarted` -> `onOriginalRemoved` (rows 12 partly); `ClipSync` sends nothing while the vault is unlocked (row 14); `VaultActivity` lost its own screen-off/background calls. Test `VaultSessionTest` (3). **File-manager Lock DONE 2026-11-13 (NOT compiled):** selection-bar "Lock" (this phone only) -> `LSAndroid.vaultLock(json)` -> new `core/VaultLockJob.kt` (neutral job text, no names; originals kept on failure; vault locked -> opens `VaultActivity`). **Notif privacy DONE 2026-11-13 (NOT compiled):** `VISIBILITY_SECRET` on job + FTP foreground notifications while the vault is unlocked (`MainActivity` notify, `PeService.build`); `VaultGuardTest.fuzzVariants`. **Strings TR first cut DONE (NOT compiled):** `VaultTr.kt` (`vt()`/`vf()` EN->TR map, ~100 labels incl. fingerprint, recovery, settings and `%s` templates, in `VaultActivity`); remaining: lock-label edge cases, long recovery-code text, VaultLockJob/notification text. **Viewer first cut DONE (NOT compiled):** new `VaultViewActivity` (not exported, FLAG_SECURE, in-memory decrypt via `VaultStore.open`, images with downsample + pinch/pan, text <= 1 MB non-selectable, closes on lock); tap a file in `VaultActivity` opens it, long-press = menu (export). PDF / video / audio / Office viewers still open (need `VaultStream` DataSource or `cacheDir/vt`). Still open: remaining strings EN+TR, viewers via `VaultStream`, HTTP `/api` token/loopback + `tlsRequired` default (owner decisions), FTP/HTTP/intent fuzz suite.
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

---
## IMPLEMENTATION FOLLOW-UP — 2026-11-13

### Completed in this pass
- Recovery replacement is now transactional in the UI: a candidate recovery secret is generated without changing the header; the header slot is updated only after the recovery code is re-entered, the `.lsrk` file is saved, or the user confirms all 3 shares. Cancelled key-file creation leaves the previous recovery slot intact. Pending recovery bytes are zeroized on lock / activity destruction. The create flow now offers recovery setup.
- `VaultGuard` now matches `cacheDir/vt` with a path-component boundary (not `vtfoo` / `vt-backup`), and `LocalFs.lreal` rejects last-component symlinks whose canonical target enters `.lsv` or the vault plaintext cache. Listings hide such symlink aliases. Vault lock jobs preserve final-component links and leave symlinks untouched; `VaultStore.lockFile` rejects a symlink source and recursive folder locking skips symlink children instead of following them.
- `Bin.trash` / `canTrash` explicitly deny vault paths. `ArcEdit` and `ZipEdit` reject vault/cache archive source files at their mutation entry points.
- Transfer notifications use neutral text while the vault is unlocked; no last-transfer filename is shown in Phone Explorer notifications. Main job notifications also suppress task labels/details while unlocked.
- Original-file removal now attempts MediaStore row deletion for each external volume, then requests a media scan as a fallback.
- Added tests for transactional recovery replacement, malformed / duplicate Shamir shares, and cache-path prefix collisions.
- `VaultStore.changePassword` now zeroizes the decrypted verification copy of the master key.

### Verification performed
- Pure JVM vault sources (`VaultFormat`, `VaultCrypto`, `VaultStream`, `VaultIndex`, `VaultStore`, `VaultRecovery`, `VaultGuard`) compiled with Kotlin compiler and Bouncy Castle 1.80.
- A JVM smoke test passed: encrypted file round-trip + integrity verification, recovery-code round-trip, 2-of-3 Shamir reconstruction, wrong/uncommitted recovery rejection, committed recovery replacement, and cache-path boundary checks.
- **Android build NOT run:** this delivered source tree has no Gradle wrapper, no installed Gradle executable, and no Android SDK / `android.jar` in the build environment. Android UI, manifest, FTP and service wiring still require `./gradlew testDebugUnitTest assembleDebug` in the owner's Android/CI environment.

### Still open (must not be represented as complete)
- Android compile and full + lite APK build/device verification.
- PDF, video/audio and Office viewers opened directly from the vault; current `VaultViewActivity` handles images and small text in memory, while other types still require explicit export.
- HTTP `/api/*` loopback binding / token policy: requires owner decision because it can change PC browser access.
- FTP `tlsRequired` default and visible warning: requires owner decision because it can break plain-FTP clients.
- Full FTP / HTTP / intent fuzz suite and device isolation matrix (Gallery, second file manager, PC share, CLIP, recents, screenshots).
- Secondary-volume discovery, storage-analyzer opaque vault slice, and root-device limitations remain as previously scoped.

### Pass 2026-11-13b - audio / video from the vault (NOT compiled)
- New `VaultPlay.kt`: `VaultDataSource` (media3 `BaseDataSource`, URI `lsvault://<nodeId>`, one `VaultReader` per `open()`, random access, GCM checked per chunk, nothing on disk / no `cacheDir/vt` / no FileProvider) + `VaultPlayActivity` (not exported, FLAG_SECURE, ExoPlayer + `PlayerView`, playlist = media siblings of the same vault folder, pauses in `onStop` (no background audio), finishes on `VaultSession.onLock`). `VaultViewActivity.isMedia`; `VaultActivity` routes media there; manifest entry added. `VaultLockJob` job / error / problem text now through `vt()/vf()` + TR entries (VaultTr).
- Not verified: no Android SDK in the session. Check first: `@UnstableApi` opt-in warnings, `DataSource.Factory { }` SAM, `.mkv`/`.mov` playback on device, seek on a >1 GB item, lock while playing, rotation.
### Pass 2026-11-13c - PDF from the vault + read-pattern tests (NOT compiled)
- New `VaultPdf.kt` `VaultPdfActivity` (API 26+): `StorageManager.openProxyFileDescriptor` over a `VaultReader` (callbacks on one `HandlerThread`) -> `PdfRenderer`; lazy `RecyclerView` pages, bitmaps in memory only, single render executor, FLAG_SECURE, not exported, closes on lock. The normal `PdfViewerActivity` is deliberately NOT used (it keeps a decrypted page-image cache on disk). `VaultViewActivity.isPdf`; `VaultActivity` routes PDFs there on API >= 26 (below: export menu). No zoom yet (fit-width only); password-protected PDFs are refused.
- New `VaultReadPatternTest` (3): random-offset reads vs plaintext, two independent readers of one item, open after lock fails.
- TR audit: every `vt()/vf()` key in the vault files has a TR entry.
- **Office from the vault: deliberately NOT done.** `OfficeViewerActivity` is File-based end to end (path extra, undo snapshots in `cacheDir/office-undo`, save-copy to `LANShare Shared`, thumbnails); feeding it a decrypted temp file would write plaintext outside the vault and `VaultIntents.refuse` rejects `cacheDir/vt` paths on purpose. Office items stay "Export to Download". A real fix = a read-only `VaultOfficeActivity` over `core/office` readers from an in-memory byte array (new work, needs a decision).
- Check on device: PDF with 1 / 300 pages, rotation, lock while open, >100 MB PDF, Android 8 and 14.
### Pass 2026-11-13d - the app's own photo viewer / editor / video player in vault mode + gallery (NOT compiled)
- Owner request: mostly photos and videos, "same photo / video player and features inside the vault". Done by a **vault mode** in the real `ImageViewerActivity`, `ImageEditActivity` and `PlayerActivity` (not new look-alikes). Entry = the existing `pending` JSON plus `"vault": true`, items `lsvault://<nodeId>` (never a path), built by `VaultMode.imagesJson / videosJson` (new `VaultMode.kt`, also holds `VaultThumbs` and the shared `secure()` FLAG_SECURE + `bindLock()` finish-on-lock helpers).
- **Viewer:** `fetch()` (cacheDir/img copy) is bypassed; `decode(File)` became `decodeSrc(open: () -> InputStream)` so bounds / pixels / EXIF read straight from `VaultReader`. Rotate = view only (`saveTurn` skipped). Share, Print and Print-space hidden; OCR recognised-text disk cache off (`ocrFile` throws); HTTP details skipped; Delete = `VaultStore.delete` (permanent, own dialog).
- **Editor:** gets a short-lived decrypted copy in `cacheDir/vt/edit-*` (deleted by the editor's `onDestroy`, wiped on lock / process start by `VaultAppHooks`), saves INTO the vault: new item first, then delete old + rename new to the old name (a failure never loses the original); "Save as copy" -> `name (edited).ext`. Result carries the new node id (`R_VID`) back to the viewer.
- **Player:** `DefaultDataSource.Factory` base = `VaultDataSource`; subtitles = sibling `.srt/.vtt/.ass/.ssa` with the same name start, decoded in memory (UTF-8 / UTF-16 / cp1254 logic kept) into a `data:` URI (no cacheDir/subs); share hidden, PiP off, `fetchMeta` / HTTP details off, delete = vault delete. Resume positions are kept under the opaque key `lsvault:<id>` (SharedPreferences; contains an id, never a name).
- **Gallery:** `VaultActivity` browser is a `RecyclerView` grid (3 columns, 5 wide): photos / videos = square thumbnails from `VaultThumbs` (downsampled decode / `MediaMetadataRetriever` over a `MediaDataSource` on `VaultReader`, `LruCache` 24 MB in RAM, evicted on lock, nothing on disk); folders and other files stay full-width rows; scroll position survives opening a viewer. Audio -> `VaultPlayActivity`, PDF -> `VaultPdfActivity`, small text -> `VaultViewActivity`.
- **Known gaps (honest list):** no multi-select / bulk export / bulk delete in the gallery; photo rotate is not saved (edit > rotate is); ML Kit OCR hands the in-memory bitmap to Play services (not persisted); resume-position key holds a node id; vault edit needs the picture decrypted to cacheDir/vt for the editing time; nothing here was compiled or run.
- **Check on device first:** open photo / next / pinch; edit > Replace and > Save as copy (ids change, list refreshes); delete from viewer and player; subtitles (UTF-8 and a cp1254 file); seek in a 1 GB video; lock while viewer / editor / player is open (all must close, `cacheDir/vt` empty after); Recents shows blank; thumbnails for HEIC / big videos; share / print / PiP buttons gone.

- **Still open:** Office-from-vault (above), gallery multi-select + bulk export / delete, device fuzz / isolation matrix, pinch zoom in `VaultPdfActivity`, owner decisions (HTTP `/api` loopback/token, FTP `tlsRequired`), Android compile.

### Pass 2026-11-14 - VAULT MODE of the file manager, parts 1 + 2 of 5 (NOT compiled, NOT device-tested)
- Owner request: the Vault tile = another undeletable protected place in the Main menu; opening it shows the NORMAL file manager in vault mode (only vault content, all file operations, vault limits obeyed); a button to import files / folders from normal mode; later: folders with only videos / only photos act like the app's video / photo gallery; keep every other existing vault feature. Split into 5 parts. Plan: **1** vault mode (done) - **2** import (done) - **3** gallery behaviour for all-photo / all-video folders, thumbnails in RAM only, and routing a tapped file to the existing vault viewers - **4** PDF / text / Office / editors inside vault mode with no plain-text temp files - **5** hardening, Turkish strings, tests.
- **Part 1.** New `core/VaultFs.kt`: an `Endpoint` (id `vault`) on top of `VaultStore` (ls / search / names / walk / open as seekable `VaultSource` / write / mkdir / remove / rename / move / stat / space / setMtime). Reads and writes stay encrypted at rest; delete is permanent (no recycle bin). `Jobs.ep("vault")` returns it WITHOUT `ArcEp` (archives are never opened inside the vault = no plain-text temp files). `VaultStore` gained `allNodes()` and `setMtime()`. A copy / cut / paste job between the phone and the vault therefore works unchanged.
- **Access rules (Routes):** `dev=vault` is served only to the app's own screen (same loopback + per-run token as Root) and only while `VaultSession` is unlocked ("Vault is locked" otherwise). Query routes allowed for the vault: `ls, search, stat, space, dl, counts, warm` (`VAULT_ROUTES`); everything else (thumb, vthumb, previews, office, archive info ...) answers 400 so no cache is written. `dl` sends `Cache-Control: no-store`. Job routes reach it through `Jobs.ep`, which refuses untrusted callers.
- **UI (ui.html):** Vault tile -> `vaultOpen()`: unlocked = `vaultEnter()`; otherwise `LSAndroid.openVaultUnlock()` opens `VaultActivity` with extra `fm`, which closes itself once unlocked, and a 700 ms poll then enters vault mode. Poll also leaves vault mode ("Vault locked") when the vault locks. `LSAndroid.vaultMode(on)` sets FLAG_SECURE on `MainActivity` while in vault mode. In vault mode the selection bar hides Print, To Print space, Open with, Share, Zip, Extract, Duplicates, Style, AI search, Merge and Hide. "New" opens `doVaultNew()`: Import, New folder, Lock vault, Vault settings (opens the existing native Vault screen, so password / fingerprint / recovery / auto-lock / export stay where they were). New bridge methods in `MainActivity`: `vaultState()`, `openVaultUnlock()`, `vaultMode()`, `vaultLockNow()`.
- **Part 2 (import).** "Import files and folders from the phone" (`vaultImportStart`) switches to the normal file manager with `S.vimp`; the selection bar then shows Import (copy) and Move in (cut). Both use the ordinary clipboard + paste job (phone -> vault endpoint): folder structure kept, name conflicts asked like any paste. Move in removes originals with `LocalFs.remove` (permanent, NOT the recycle bin, so no plain-text copy stays in the bin); it does not do the small-file overwrite that `VaultStore.lockFile` does.
- **Also added (earlier the same day):** `VaultActivity` "Add folder" (system folder picker, recursive, merges by name, skips same name + size) and a file-manager "To vault" button (`LSAndroid.vaultCopy`, `VaultLockJob.start(paths, removeOriginal = false)`). The old file-manager "Lock" button is unchanged.
- **Old vault code that is still the only user of its pieces:** the vault viewers (`VaultViewActivity`, `VaultPlay`, `VaultPdf`, vault flags in `ImageViewerActivity` / `ImageEditActivity` / `PlayerActivity`) and `VaultThumbs` are NOT wired into vault mode yet - a tap in vault mode is untested and may go through the normal `/api/dl` route. Parts 3 and 4 connect them.
- **Known gaps / open decisions:** vault-mode thumbnails are plain icons until part 3; files typed `.zip` etc. cannot be browsed in vault mode; the decrypted bytes cross the loopback `/api/dl` route in vault mode (token protected, same open decision as the HTTP `/api` item above); `Details` for a file shows size / dates only (no media info); `setMtime` keeps the original date of imported files.
- **Check on device first:** Vault tile while locked / unlocked / no vault yet; unlock screen closing itself; lock while a folder is open (back to Main menu); copy / move between phone and vault both ways; rename / delete / new folder / search in vault mode; Import and Move in with a big nested folder and a name conflict; screenshot and recent-apps blocked in vault mode; Share / Open with absent in the selection bar.

### Pass 2026-11-14b - VAULT MODE of the file manager, part 3 of 5 (NOT compiled, NOT device-tested)
- **Tap routing.** New bridge `LSAndroid.vaultOpenFile({dir, name, order})` -> `VaultMode.route()`: photo -> the app's own `ImageViewerActivity` in vault mode (swipe set = the folder's photos in the order the file manager shows them), video -> `PlayerActivity` vault mode (playlist + sibling subtitles), audio -> `VaultPlayActivity`, PDF (API 26+) -> `VaultPdfActivity`, text <= 1 MB -> `VaultViewActivity`. Everything else answers "other" and the UI toasts "can't be opened inside the vault yet" (part 4). ui.html: `openFile / viewImg / playVid / viewPdf / nativeOffice` are vault aware (`vaultTapOpen`); `isEd` is off and `isArcF` is false in vault mode (no text editor, no archive entry = no plain-text temp files). Nothing goes through `/api/dl` for a tap any more. `MainActivity.onResume` re-lists the vault folder when a vault viewer set `VaultMode.dirty` (delete / edit-save).
- **Thumbnails, RAM only.** `NativeList.loadTh` has a `dev == "vault"` branch: `VaultFs().nodeAtOrNull(path)` -> new blocking `VaultThumbs.make()` (downsampled decode / first video frame via `MediaDataSource` over `VaultReader`) -> the list's existing in-memory `LruCache`. `RemoteThumbs / Thumbs / VideoThumbs` (disk caches) are never used for the vault. `NativeList.dropVaultThumbs` (on `VaultSession.onLock`) evicts every `vault|` key + failed marks. HTML rows / video-gallery cells skip thumbnail fetching in vault mode (no `ls_th` localStorage writes, `/api/vthumb` stays blocked).
- **Gallery behaviour.** `vaultPure()` = 'img' / 'vid' when every file of the open vault folder is a photo / a video (sub-folders allowed, not while searching). Only photos -> grid view + large thumbnails (unless the user picked a view for that folder this session); only videos -> the video gallery cells (`galOn()` replaces `S.gal` in `nlCfg` and the `GV` filter; native `NlGalView` rows). Mixed folders keep the normal list.
- **Folder names no longer reach disk.** `setPref` in vault mode keeps the view choice in memory (`VPF` + `PF`), no `ls_g / ls_pf` localStorage or `NPS` write; cleared in `vaultLeave`.
- New in `core/VaultFs.kt`: `nodeAtOrNull(path)`, `childrenOf(path)`. Changed: `VaultMode.kt` (`VaultThumbs.make`, `route`), `NativeList.kt`, `MainActivity.kt`, `assets/ui.html`.
- **Known gaps:** native-list tap on a thumbnail still launches viewers without the hero grow animation source bitmap (plain `growFrom`); video thumbnails carry no duration pill; vault paths still enter other web-side memory structures (`LSC` evicted on leave, folder-style / recents not audited -> part 5); no multi-select bulk export beyond the existing paste job; Office / editors = part 4.
- **Check on device first:** photo-only folder opens as large grid with thumbnails; video-only folder shows gallery cells; mixed folder = list with thumbnails; tap photo (swipe stays inside the folder's photos), video (next / subtitles), audio, PDF, small .txt, .docx (toast); lock while a viewer is open (closes) and while the list is open (back to Main menu, `cacheDir` has no thumbnails); delete from the viewer -> list refreshes; thumbnails of HEIC / big videos; `ls_pf` in WebView storage has no `vault|` key.

### Pass 2026-11-14c - VAULT MODE of the file manager, part 4 of 5 (NOT compiled, NOT device-tested)
- **Office (read-only): new `VaultOffice.kt` `VaultOfficeActivity`** (docx / docm / dotx / xlsx / xlsm / xltx / odt / ods / doc / xls / pptx / odp, <= 60 MB; not exported; FLAG_SECURE; closes on lock). The office readers are File based (ZipFile / OLE), so the item is decrypted to ONE file `cacheDir/vt/ofc-<random>/d.<ext>` (generic name, app-private), deleted in `onDestroy`, wiped on lock and on process start (`VaultAppHooks`). Deliberately NOT `OfficeViewerActivity`: this one writes no undo copies, thumbnails, recent-files entry, PrintLog line (it logged the file name), no PC "exact pages" conversion, no CSV / print export. WebView: JS off, http(s) blocked (403), no file access, no fonts over the network, sheet tabs via `lsoffice://sheet/<i>`. Password-protected files -> message. Fonts fall back to phone fonts.
- **Text editor: new `VaultText.kt` `VaultTextActivity`** (<= 1 MB, strict UTF-8 only; other encodings refused so nothing is corrupted). In-memory `EditText` with copy / cut / paste / selection menu, suggestions and personalised keyboard learning off. Save = new item first, delete old, rename new to the old name (a failure keeps the original), `VaultMode.dirty` re-lists the file manager. Back with changes asks Save / Discard / Cancel. Lock closes it; unsaved text is dropped.
- **Routing:** `VaultMode.route` sends text -> `VaultTextActivity`, Office types -> `VaultOfficeActivity`; `VaultActivity.openNode` also opens Office items there. Photos / videos / audio / PDF unchanged from part 3 (photo editor = `ImageEditActivity` vault mode, short `cacheDir/vt/edit-*` copy, from pass d). Other types still toast "can't be opened inside the vault yet".
- Manifest: 2 activities. TR strings added to `VaultTr.kt`.
- **Known gaps:** no Office editing in the vault (docx / xlsx editors need File I/O: would be a copy in `cacheDir/vt` + re-encrypt on save, not done); Office viewer has no search / reading mode / outline; legacy .ppt unsupported; large sheets render only the first sheet until a tab is tapped; no "new text file" in vault mode; the decrypted Office copy exists for the whole viewing time (private `cacheDir`, but plaintext on disk).
- **Check on device first:** open .docx with pictures, .xlsx (tabs), .odt, .doc, .pptx, password-protected .docx; `cacheDir/vt` empty after back / lock / kill; edit + Save + Back on a .txt (name, date, folder unchanged; no duplicate item); non-UTF-8 .txt refused; paste / long-press does nothing in the editor; screenshot blocked; lock while editor open.

### Pass 2026-11-14d - VAULT MODE of the file manager, part 5 of 5: hardening, strings, tests (NOT compiled, NOT run)
- **Leak audit of the web side (ui.html):** vault folder names reach no persistent store: view prefs (part 3, memory only), last folder (not persisted anywhere), thumbnails (`ls_th` skipped), folder styles (Style button hidden in vault mode), **quick folders ("Add current folder") now refuse vault folders** (they were saved in plain text in `ls_qf`). `LSC` entries `vault|*` are dropped on leave. The web UI has no Turkish layer at all (whole app UI is English), so Turkish stays limited to the native vault screens (`VaultTr.kt`): coverage script over every `vt()/vf()` literal in the sources = 0 missing.
- **New test `VaultModeHardeningTest.kt` (6, JVM, reads sources as text, run from the module dir):** every `vt()/vf()` literal has a TR entry; `cacheDir/vt/ofc-*` / `edit-*` are vault paths and `vtfoo` is not; `VaultOffice.kt` does not use PrintLog / OfficeExact / Recent / FileProvider / external storage and deletes its temp dir; `VaultText.kt` has no clipboard / file / cache use and keeps the IME + selection-menu lockdown; vault rows in `NativeList` never reach `RemoteThumbs / Thumbs / VideoThumbs`; ui.html keeps vault view prefs and quick folders out of storage.
- **Whole vault-mode plan status (parts 1-5): code complete, nothing compiled.** First step: `./gradlew testDebugUnitTest assembleDebug` (full + lite), fix compile errors (suspects: `@UnstableApi` opt-ins, `DataSource.Factory` SAM, `VaultMode.route` generics, `Dlg` builder calls in `VaultText`).
- **Still open (owner decisions / device work):** HTTP `/api` loopback bind + token and FTP `tlsRequired` default (can break PC browser / plain FTP clients); decrypted bytes cross the loopback `/api/dl` in vault mode for the file manager's own list operations (token protected); Office editing, `.ppt`, archives inside the vault; FTP / HTTP / intent fuzz suite and the device isolation matrix (second file manager, Gallery, FTP, PC share, CLIP, recents, screenshots) on Android 7 / 10 / 12 / 14; secondary-volume discovery; root / forensic limits as scoped.

### Pass 2026-11-14e - reinstall message + gallery multi-select check (NOT compiled, NOT device-tested)
- **"Existing vault found" (Part 4 reinstall behaviour) DONE.** `VaultPrefs.known` (true after any unlock / create / recovery unlock, or when the app already has older vault UI state). Vault folder on disk + `known == false` (fresh install / cleared data) -> the unlock screen says "Existing vault found. Enter its password to open it." plus a line that fingerprint can be turned on again after opening (the existing post-unlock fingerprint offer still runs). EN + TR strings in `VaultTr.kt`. Changed: `VaultPrefs.kt`, `VaultActivity.kt`, `VaultTr.kt`. Upgrading users with no stored vault UI state see the message once.
- **Gallery multi-select: nothing to build, doc was stale.** Browsing moved into the file manager's vault mode (pass 2026-11-14): long-press select works in list, grid and video-cell views (`ui.html` S.sel paths + `NativeList`), "select all" works, and the selection bar offers Restore / Copy / Cut / Paste / Rename / Details / Delete in vault mode. The old `VaultActivity` gallery no longer exists. Remove "multi-select" from the open lists above.
- Check on device: uninstall + reinstall (or clear data) -> Vault tile shows the new text; after one unlock it is not shown again; long-press a photo in a photo-only folder, select several, Restore / Delete.

### Pass 2026-11-14f - Lock button in the vault top bar (NOT compiled, NOT device-tested)
- `ui.html`: new top-bar button `#vlock` (padlock icon, left of the gear), shown only in vault mode (toggled in `vaultSecure`). Tap = `LSAndroid.vaultLockNow()` + `vaultLeave('Vault locked')`: the vault locks at once (viewers close, thumbnails and `cacheDir/vt` are wiped by the existing lock hooks) and the app returns to the Main menu without waiting for the 700 ms poll. Other "Lock vault" entry (New sheet) unchanged.
- Check on device: button hidden outside the vault, visible in every vault folder (list, photo grid, video cells); tap = toast "Vault locked" + Main menu; Vault tile asks for the password again; button not shown after leaving the vault by Back.

