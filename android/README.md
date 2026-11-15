# Takipli Oynat — Android

Android version of the PC tracking follow-cam, in the same repo. Plan and status: `../handover-android.md`.

| Module | What |
|---|---|
| `core/` | Pure Kotlin (no Android imports): geometry, formats, ByteTrack, view building. Tested on plain JVM. |
| `app/` | Android app (Compose UI, decode, ONNX Runtime, export). |

## Build

CI (`.github/workflows/android.yml`) runs on every push and uses Gradle 8.14.3 directly, so no Gradle wrapper
is committed. Locally:

```
cd android
gradle :core:test :app:assemblePoseDebug     # APK: app/build/outputs/apk/pose/debug/app-pose-debug.apk
gradle :app:assembleSamDebug                # SAM build (applicationId com.takipli.oynat.sam): app-sam-debug.apk
```

Needs JDK 17+ and the Android SDK (platform 36). To get a local wrapper once: `gradle wrapper --gradle-version 8.14.3`
(then commit `gradlew`, `gradlew.bat`, `gradle/wrapper/*`).

`VERSION` at the repo root is shared with the PC app (`versionName`; `versionCode = major*10000 + minor*100 + patch`).
Only the phone ABI `arm64-v8a` is built.

## Pose benchmark (Parts 5-6, CPU and NPU)

Home → "Pose benchmark (CPU)". Models come from `app/src/<flavor>/assets/models/*.onnx` (`pose` or `sam`; CI exports them; the folders are git-ignored)
or from `Android/data/com.takipli.oynat/files/models/` on the phone. Local build without CI: put `.onnx` files in the flavor's assets folder first.

## Release build (Part 15)

CI builds a **signed release APK** on every `v*` tag when these repository secrets exist (otherwise the step is skipped with a
warning and only the debug APK is uploaded):

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_B64` | the keystore file, base64: `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | as used when the keystore was created |

Create a keystore once (keep it and the passwords safe; losing them means phones cannot update the app, only reinstall):

```
keytool -genkeypair -v -keystore release.jks -alias takipli -keyalg RSA -keysize 4096 -validity 10000
```

Local signed build: `KEYSTORE_PATH=/abs/path/release.jks KEYSTORE_PASSWORD=... KEY_ALIAS=takipli KEY_PASSWORD=... gradle :app:assemblePoseRelease` (or `assembleSamRelease`).
The APK is attached to the GitHub Release **only in a private repository** (it contains AGPL-3.0 pose models for personal use);
in a public repository it stays a workflow artifact (`TakipliOynat-Android-Release`).

## Error log

Home screen footer: **Share error log** sends a text with the app version, device and the latest errors (scan, export, player,
crashes); **Clear error log** empties it. The file is `files/logs/error.log` (capped at 100 KB).

## Versioning and in-app updates

- **Version:** `VERSION` (X.Y.Z, shared with the PC app) is the public number. `python scripts/bump_version.py patch|minor|major|X.Y.Z` changes it everywhere it is pinned.
  CI adds a build number (GitHub run number): `versionCode = (X*10000 + Y*100 + Z) * 1000 + build` (build capped at 999, 0 locally), so every CI build is newer than the last.
- **Releases (private repository only, signed):** every push to `main` publishes a prerelease `vX.Y.Z-b<run>` (newest 8 kept); a `v*` tag publishes a normal Release.
  Files: `TakipliOynat-Android-<pose|sam>-X.Y.Z-b<run>.apk`. Needs the four keystore secrets (see Release build); without them nothing is published.
- **App:** Home -> Updates: enter the repository (`owner/name`, filled by CI builds) and, for a private repository, a fine-grained read-only token
  (Contents: read). Check -> Download and install. Each build only offers its own flavor (`pose` or `sam`).
- **Rules the phone enforces:** the update must be signed with the same key as the installed app, and its versionCode must be higher.
  CI **debug** APKs get a random debug key each run, so they can never update each other: install the signed release once (uninstall a debug build first), then update in-app.
  Allow "install unknown apps" for the app when asked.
