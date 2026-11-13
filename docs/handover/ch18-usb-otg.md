# Handover chapter file - ch18-usb-otg

> Part of `HANDOVER_READ_BEFORE_CODE.md` (slim index at the project root). Split out 2026-11-13; content unchanged. Read only what the task needs (grep / `sed -n`).

## 18. USB / OTG drives

### 18.1 Summary
A memory stick or disk on an OTG cable is mounted by Android as a storage volume (`/storage/XXXX-XXXX`). LANShare already has "All files access", so the drive is a second `LocalFs` rooted there (Option A, owner's choice 2026-10-29). Not done on purpose: raw USB host access (libaums, no exFAT) and a Storage Access Framework fallback for Android 10 and below (about 600 lines, `OtgSafFs : Endpoint`; only needed if a device refuses path access to the volume).

### 18.2 Files
- `core/Otg.kt` - drive list (1.5 s cache, `Otg.invalidate()` from plug / unplug broadcasts), `fs(id)`, `peers()`, `status()`, `set(key, value)`.
- `core/LocalFs.kt` - `id` / `name` / `readOnly` parameters, `checkWritable()`, `ix()`.
- `core/Jobs.kt` - `ep()` (`otg:`), `removeUser` (`=== Core.local`), direct-write guard. `core/ArchiveFs.kt` - guard before editing an archive.
- `core/Routes.kt` - peers entry, `/api/otg`. `core/Cfg.kt`, `core/settings/SettingsSchema.kt` - `otg_on`, `otg_auto`, `otg_ro`.
- `ui.html` - `usbWatch`, `openUsb`, Settings row, `usb` icon. `MainActivity.kt` - `openUsbSettings`.

### 18.3 Rules (do not break)
1. Anything that takes a virtual path and acts on `Core.local` (Bin, Albums, ImgSearch, PrintSpace, Storage Analyzer delete) is for the phone only. A USB drive must never reach it: the same virtual path would hit the phone's own file.
2. Every new write path on `LocalFs` calls `checkWritable()` first (the read-only switch).
3. A drive is never served to other devices: `/p/` routes use `Core.local` only.

### 18.4 Not device-tested - check list
1. Plug a stick: it appears under Devices within 2 s (kind USB). Unplug: it disappears; if it was open, the app goes back to This device.
2. Browse, open a photo / video / PDF, copy a file both ways, move, rename, new folder, delete (permanent, no bin entry).
3. Settings > USB / OTG drives: Read-only on, then copy to the drive must fail with "is read-only". Show USB drives off, then the drive vanishes. Open drive when plugged in on, then plugging opens it.
4. Android 11+ with "All files access" off: the drive shows the explanation instead of opening.
5. A phone with an SD card slot: the SD card must NOT be listed as USB (and a USB card reader should be).
6. Eject opens Android's storage screen.
