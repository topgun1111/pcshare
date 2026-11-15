# handover-android.md — Takipli Oynat for Android

Created 2026-11-14, revised 2026-11-14 (body-part focus). Put this file at the repo root next to the PC `handover.md`.
New session: read this file first, then do the next ⬜ part. Update it at the end of every part.
The PC `handover.md` stays the source of truth for the PC app; this file covers only `android/`.

---

## 1. Goal and scope (confirmed by the user)

**Purpose:** track the **human body and body parts (leg, foot, head, …) while the person is in motion**, and show the tracked part **zoomed in**, like a camera operator following it. Not sport-specific. Use "person/target/part", not "player".

Turn the PC project into an Android app that lives **in the same repo**, in an `android/` folder.
GitHub Actions builds the Windows package and the Android APK **at the same time** (parallel workflows).

**In scope (first 15 parts): export mode with a live preview**
1. Open a video from the phone.
2. Pick a target on the first frame: tap a person, or tap a body part (or choose a preset: head, leg, foot, …).
3. Scan the video offline. **While scanning, show a live preview, even at low fps** (frame, skeleton, target part, the crop the camera would use).
4. Build the follow-camera views (zoom/pan keyframes) for the chosen person/part.
5. Preview the result at full fps, then **export the tracked, zoomed video as an MP4** (original audio kept).

**Out of scope for now (see Backlog):** live camera/playback tracking, whole-body (toes, fingers, face points), Re-ID, team colour, jersey number, multi-target, the PC menu system.

**Hardware target:** the user's phone, Snapdragon 8 Gen 3 (Hexagon NPU, HTP v75, Adreno 750). arm64-v8a only.

---

## 2. Status

| # | Part | Status |
|---|------|--------|
| 1 | Android skeleton + CI workflow | ✅ written 2026-11-14 (first CI run pending) |
| 2 | Core data, geometry, formats (incl. keypoints), One-Euro filter + golden tests | ✅ written 2026-11-14 (Kotlin never compiled here: first CI run pending) |
| 3 | ByteTrack + Kalman port | ✅ written 2026-11-14 (Kotlin never compiled here: CI run pending) |
| 4 | Pose model decoding (person box + 17 keypoints) | ✅ written 2026-11-14 (synthetic golden; real-tensor golden pending; CI run pending) |
| 5 | ONNX Runtime on device (CPU) + bench screen | ✅ written 2026-11-14 (never compiled here; CI run + first bench numbers pending) |
| 6 | NPU: model prep + QNN provider + CPU/NPU bench + licence decision | ✅ written 2026-11-14 (never compiled; NPU numbers + keypoint-jitter check pending; licence decision provisional) |
| 7 | Video decode + frame sampling (configurable fps) | ✅ written 2026-11-14 (never compiled; device check pending) |
| 8 | Camera-motion compensation + cut detection | ✅ written 2026-11-14 (never compiled; phone pan check pending) |
| 9 | Offline scan engine: pose + tracking + keypoints (resumable) | ✅ written 2026-11-14 (never compiled; Part 8 test fix needed first; device scan check pending) |
| 10a | Part presets, group boxes, view building (Python-first + Kotlin port, golden) | ✅ written 2026-11-14 (never compiled; CI run pending) |
| 10b | Per-keypoint One-Euro, off-screen propagation (camera-compensated), parent-joint inference, rectangle selection | ✅ written 2026-11-14 (Python tests green; Kotlin core compiled + golden-checked outside Gradle; Gradle/CI run pending) |
| 11 | Scan service + scan UI (choose person/part) | ✅ written 2026-11-14 (never compiled; CI run + phone check pending) |
| 12 | Live scan preview (low fps, skeleton + part box) | ✅ written 2026-11-14 (never compiled; CI run + phone check pending) |
| 13 | Result preview player + tuning sliders | ✅ written 2026-11-14 (never compiled; CI run + phone check pending) |
| 14 | Export (Media3 Transformer) | ✅ written 2026-11-14 (never compiled; CI run + phone export check pending) |
| 15 | Hardening + release build | ✅ written 2026-11-14 (never compiled; CI run + phone check pending) |

Bench lines (fill after Part 5, 6, 9, 14): one line per real clip, device, model, provider, ms/frame, scan x-realtime, export x-realtime.
Format (the app's "Copy bench line" button produces it): `device | model | provider | input | image | mean ms/frame (median, p95, n) | fps`.
- (empty: run the Bench screen on the phone, paste lines here)

---

## 3. Architecture

```
video file ─► MediaCodec decode ─► sample at scan fps ─► pose model (ONNX Runtime, NPU/CPU)
                                                              │  person boxes + 17 keypoints per person
                       camera motion (OpenCV LK+RANSAC) ─► ByteTrack (+Kalman) ─► pose↔track association ─► samples (track doc v2, `kp`)
                                                              │
   pick target (person + part) ─► part box (joint or joint group) ─► per-keypoint One-Euro, gap fill, hold, zero-phase smoothing
                                                              │
                                                   views = [[t, zoom_log2, pan_x, pan_y], ...]
                                                              │
        preview (ExoPlayer + GL transform)                    export (Media3 Transformer, HW encoder, audio passthrough)
```

- **Language/UI:** Kotlin, Jetpack Compose. Strings in `values/` (English) and `values-tr/` (Turkish).
- **One model for person + parts:** a pose model gives the person box and keypoints in a single inference (faster and one thing to optimise on the NPU). The PC code already decodes YOLOv8-pose output (`tracker/pose.py`).
- **Inference:** ONNX Runtime Android. CPU first (Part 5), Hexagon NPU through the QNN provider (Part 6).
- **Pure-Kotlin `core` module** (no Android imports, JVM unit tests on Linux CI): geometry, formats, ByteTrack, Kalman/One-Euro, parts, view building, pose decoding.
- **Video decode:** MediaExtractor + MediaCodec. **Camera motion:** OpenCV Android SDK.
- **Export:** Media3 Transformer with a time-varying matrix effect from the views; hardware encoder; replaces the PC ffmpeg pipe.
- **Preview:** (a) *scan preview*: what the scanner already processes, drawn at low fps; (b) *result preview*: ExoPlayer with the same view transform at full fps.
- **No Python on the phone** (Chaquopy rejected: no confirmed onnxruntime build, CPU only, needs an ffmpeg replacement).

---

## 4. Repo layout and CI

```
repo/
├─ tracker/ mpv/ models/ scripts/ tests/ ...        # PC version
├─ .github/workflows/android.yml                    # Part 1: Android CI, parallel to build.yml
├─ android/
│  ├─ settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, README.md
│  ├─ core/                       # pure Kotlin
│  ├─ app/                        # Android: UI, service, decode, ONNX Runtime, OpenCV, Transformer
│  └─ calib/                      # small calibration JPEGs for int8 quantization (user's own frames, optional)
├─ scripts/
│  ├─ gen_golden.py               # NEW (Part 2): golden fixtures from the Python implementation
│  └─ quantize_android.py         # NEW (Part 6): fp16/int8 pose model for the NPU
├─ tracker/parts.py               # NEW (Part 10): body-part presets + group boxes (PC gets them too)
├─ VERSION                        # shared by PC and Android
└─ handover-android.md
```

**Models:** `models/` is not in git (CI fetches it; `*.onnx` is in `.gitignore`). The Android build copies only the models it needs into app assets with an explicit include list. Never add the whole `models/` folder as assets.

**Golden fixtures:** `scripts/gen_golden.py` runs the Python code on synthetic inputs and writes small JSON files to `android/core/src/test/resources/golden/`. Kotlin tests must match them. A pytest test checks the committed fixtures are not stale. Part presets and group boxes (new code) are written in Python first (`tracker/parts.py`) so both sides share one reference.

**CI**
- **`.github/workflows/android.yml` (Part 1, done):** separate workflow, same triggers as `build.yml` (push to main, `v*` tags, pull requests, manual), so it runs **in parallel** with the PC build. Steps: checkout, Java 17, Android SDK (`android-actions/setup-android@v3`), Gradle 8.14.3 (`gradle/actions/setup-gradle@v4`), `gradle :core:test :app:assembleDebug` in `android/`, upload artifact `TakipliOynat-Android` (`TakipliOynat-Android-<VERSION>-debug.apk`). It does not touch `build.yml`, `ci.yml` or their tests (several PC tests read `build.yml` and check step order).
- No path filters (kept simple). If added later, `VERSION` and `models/` changes must trigger both workflows.
- No Gradle wrapper is committed; CI installs the pinned Gradle. For local terminal use: `gradle wrapper --gradle-version 8.14.3` once, then commit the wrapper.
- `make_package.py` copies an explicit list, so `android/` is never in the Windows package (checked 2026-11-14).
- Part 6 adds the model-fetch/export step to `android.yml` (pose model export reuses `scripts/export_pose.py`, which needs `ultralytics`+`onnx` on the Ubuntu runner).
- Part 15 (done): on a `v*` tag, `android.yml` attaches the signed APK (private repositories only, see Part 15) to the same GitHub Release (`softprops/action-gh-release` can add files from both workflows). Alternative: merge the job into `build.yml` with a `release` job that `needs` both.

---

## 5. Shared formats (must stay identical to PC)

**views.json** (what the follow-camera uses; also what export reads)
```json
{"version": 1, "video": {"path": "..."}, "target": ..., "views": [[t, zoom, pan_x, pan_y], ...], "vsig": ...}
```
- `t` seconds; `zoom` is log2 (scale `s = 2^max(zoom, 0)`; never below 1x); `pan_x`, `pan_y` normalized.
- `view_at(views, t)`: linear interpolation between keyframes, clamped at both ends (`tracker/offline.py::view_at`).
- Crop rectangle (`tracker/export.py::crop_rect`): `cw = vw/s`, `ch = vh/s`; centre `cx = clamp((0.5 − pan_x)·vw, cw/2, vw − cw/2)`, same for y; round to ints, size ≥ 2, keep inside the frame.
- Export output size = source size, both dimensions made even.

**track.json** (scan result): **version 2** (pose). Keys: `video {path,w,h,fps,duration}`, `sample_fps`, `samples`, `target`, `views`, optional `sig`/`vsig`, `cuts`. Each sample has per-person detections `d` and keypoints `kp` per track ID: 17 × (x, y, conf), x/y normalized to the frame (`tracker/offline.py::_kp_row`), and (Part 10b) optional `cam` = [a, b, tx/h, ty/h], the background similarity previous sample -> this sample (not on cuts or failed estimates). Read the exact `samples` layout in `tracker/offline.py::run_detection` before Parts 2/9 and put it into the golden fixtures. The reader must accept version 1 (no keypoints) too.

**Body parts (COCO-17 joint indices):** 0 nose, 1/2 left/right eye, 3/4 left/right ear, 5/6 shoulder, 7/8 elbow, 9/10 wrist, 11/12 hip, 13/14 knee, 15/16 ankle. "Left/right" means the **subject's** left/right (COCO convention).

| Preset | Joints | Note |
|---|---|---|
| Head | 0–4 | union box |
| Torso | 5, 6, 11, 12 | |
| Left / right arm | 5,7,9 / 6,8,10 | |
| Left / right leg | 11,13,15 / 12,14,16 | hip-knee-ankle union |
| Both legs | 11–16 | |
| Left / right foot | 15 / 16 | **ankle point only** (COCO has no toes/heel); box sized from person height; whole-body model later gives real foot points |
| Feet | 15, 16 | |
| Hands | 9, 10 | wrist point |
| Whole person | person box | |

Group box = union of joints with `conf ≥ kp_min_conf`, plus `part_margin`, never smaller than `kp_box × person height` (so a single joint still gets a sensible box). Currently the PC code only supports a single joint (`offline.py::part_boxes`); groups are new (`tracker/parts.py`, Part 10; this is also the PC plan's B3).

**Compatibility rule:** a `views.json` made on the phone must export correctly on the PC and the reverse. One cross-test in Part 2, one in Part 14.

**Config keys reused (same names/defaults as `tracker/config.py`):** `det_conf` 0.25, `det_iou` 0.45, `det_size` 640, `live_det_conf` 0.1 (ByteTrack low-score stage), `offline_fps` 10, `offline_interp_s` 0.6, `offline_alpha` 0.4, `target_fill` 0.30, `min_zoom` 0, `max_zoom` 2.5, `alpha_pos` 0.15, `alpha_zoom` 0.08, `dead_zone_pos` 0.01, `dead_zone_zoom` 0.05, `lost_hold_s` 1.0, `lost_zoomout_s` 2.0, `kf_accel` 0.5, `kf_meas` 0.01, `gmc` true, `gmc_side` 480, `cut_detect` true, `cut_hist` 0.35, `cut_diff` 0.18, `pick_tol` 0.03, `pose_conf` 0.3, `kp_min_conf` 0.35, `kp_box` 0.10, `kp_hold_s` 1.5, `kp_filter` true (One-Euro), `kp_world` true (camera-compensated position when the part leaves the frame). New (Android/PC shared, values decided by measurement): `part_margin`, `scan_fps_part`.

---

## 6. Models and the NPU

- **Primary model: a pose model** (person box + 17 keypoints in one pass; output layout `[1,56,N]`).
  - Baseline: `yolov8s-pose.onnx` (what the PC CI already exports with `scripts/export_pose.py`).
  - Candidate: `yolo11m-pose.onnx` (+ fp16). The PC plan (G2) left this undecided; **decide it by measurement on the phone in Part 5/6.**
  - Export for the phone: static 640×640 input, simplified graph, then fp16 and/or int8.
- **Licence:** Ultralytics YOLOv8/YOLO11 weights are **AGPL-3.0**: fine for personal use; distributing the APK brings AGPL obligations. Decide in Part 6 (before any release): (a) personal-use builds only, or (b) a pose model with a licence that allows distribution (check each model's code **and** weights terms; do not assume).
- **YOLOX-S (Apache-2.0):** no longer needed on Android for the MVP (the pose model supplies person boxes). Keep as a fallback only if the pose model is too slow for person finding.
- **ONNX Runtime packages (verified on Maven Central, Nov 2026):** `com.microsoft.onnxruntime:onnxruntime-android-qnn` (depends on `com.qualcomm.qti:qnn-runtime`); a newer `com.qualcomm.qti:onnxruntime-android-qnn` also exists. Part 6 picks one, pins the version, records it here.
- **Hexagon target:** Snapdragon 8 Gen 3 = HTP **v75**.
- **NPU preparation order (Part 6):** (1) **fp16** static-shape model (no calibration); (2) if too slow or unsupported ops, **int8 QDQ** with calibration frames from `android/calib/` (frames with people in motion, ideally your own footage); (3) keep the **CPU fallback** working.
- QNN may compile a context binary on first run; cache it in app storage and show a one-time "preparing NPU" message.
- **Quantization can hurt keypoint accuracy** more than box accuracy; check keypoint jitter on a real clip, not just speed.
- No fps is promised. Parts 5 and 6 measure it. Do not plan later parts around a number until it is measured.

---

## 7. The 15 parts

Each part: ≤ 3–4 files changed, ≈ 300 changed lines, tests included, handover updated. Line counts are estimates (Kotlin + tests). Part 10 is the heaviest; if it exceeds ~400 lines, split it and move the least important polish from Part 15 to the backlog (keep 15 parts).

### Phase A — Foundation
**Part 1 — Skeleton + CI** (~150 lines) ✅ written, CI run pending
- `android/` Gradle project (Kotlin, Compose, minSdk 29, arm64-v8a, `core` + `app`), "home" screen, `values` + `values-tr`; `.github/workflows/android.yml` (parallel to the PC build); artifact `TakipliOynat-Android`.
- **Never executed** (no network when written, only XML/YAML syntax-checked). First action after pushing: open the Android workflow run; if it fails, paste the Gradle error into the next session.
- Files: `.github/workflows/android.yml`; `android/` modules `core` (pure Kotlin + `versionCode()` test) and `app` (Compose home screen with version and "Core module: ok", English + Turkish strings, adaptive icon). `versionName` from the repo `VERSION`; `versionCode = major*10000 + minor*100 + patch` (1.2.0 → 10200).
- Pinned stack: AGP 8.13.0, Kotlin 2.2.20, Gradle 8.14.3, Compose BOM 2025.09.00, compileSdk/targetSdk 36, minSdk 29, JDK 17. Newer AGP 9 / Kotlin 2.3+ exist (AGP 9 changes the plugin setup); upgrading is a separate optional step.
- Exit: one push produces the Windows artifacts **and** a debug APK; the APK installs and opens.

**Part 2 — Core data, geometry, formats, filters** (~350) ✅ written, CI run pending
- Files: `android/core/.../{Config,Geometry,OneEuro,Formats}.kt`, `GoldenTest.kt`, `golden/*.json` (7 fixtures), `scripts/gen_golden.py`, `tests/test_golden.py`, Gradle (serialization plugin + lib).
- **Never compiled** (no Gradle/Kotlin/network when written). First action: push, open the Android run, paste any Kotlin/Gradle error into the next session. Fixtures were generated and the pytest stale check logic verified by hand (pytest not installed there).
- Not yet done from the exit list: a *real* PC-made `track.json` v2 (from `offline --pose`) loading; only the synthetic `make_doc` fixture is covered. Do it when a real file exists.
- Kotlin `Config` (same keys/defaults), `viewAt`, `cropRect`, views.json and track.json v1+v2 read/write (kotlinx.serialization), **One-Euro filter** (for keypoints), `scripts/gen_golden.py`, golden tests, pytest stale check.
- Exit: Kotlin `viewAt`/`cropRect` match golden values; a PC-made `views.json` and a v2 `track.json` (with `kp`) load.

**Part 3 — ByteTrack + Kalman** (~350) ✅ written, CI run pending
- Files: `core/.../{Tracking,Kalman,ByteTrack}.kt` (BBox, Detection, Track, Hungarian, IoU, `KalmanBox`, `Kalman1D`, `BoxFilter`, `ByteTracker` incl. `motion` hook, `clear`, `setNextId`), `TrackingTest.kt`, 5 new fixtures (`assignment`, `kalman_box`, `kalman_1d`, `box_filter`, `bytetrack`; scenario has low-score stage, 4-frame loss, camera motion frames, `clear`, 2 param sets), `scripts/gen_golden.py`.
- Tolerance in tests: 1e-6 relative (matrix inverse differs from numpy at ~1e-12). Hungarian pairs compared exactly.
- `Pipeline.step` (cut → `clear()`, GMC) is NOT ported here; it belongs to Part 9 (scan engine) with GMC from Part 8.
- **Never compiled.** First action: push, open the Android run, paste any Kotlin error.
- Port `tracker/bytetrack.py` and the Kalman part of `smooth.py` (person IDs across frames). Camera compensation is a hook only.
- Exit: golden test on synthetic detections identical to Python.

**Part 4 — Pose decoding** (~300) ✅ written, CI run pending
- Files: `core/.../Pose.kt` (`letterboxLayout`, `preprocessChw` (RGB CHW float, gray 114), `decodeYoloPose` for `[56,N]` and `[N,56]`, `nms`, `nearestKeypoint`, `toDetections`/`withMinScore` = `PoseDetector.detect/estimate` filters), `PoseTest.kt`, fixtures `letterbox.json`, `pose.json`, `scripts/gen_golden.py`.
- Golden input is a **synthetic** `[56,60]` tensor (NMS pair, clipped box, degenerate box, 52 background rows); expected values come from the Python `decode_yolo_pose`. Tolerance: boxes/keypoints 1e-3 px (Python is float32), scores 1e-6.
- **Still open for this part's exit:** a golden from a *real* model output on one test image (needs `models/yolov8s-pose.onnx` + an image + onnxruntime; none were available). Do it when the model is at hand; the synthetic test already pins the decode logic.
- The pixel resize is done by the app (Part 5: `Bitmap.createScaledBitmap` to `layout.newW x newH`, then `preprocessChw`); cv2 bilinear vs Android bilinear differ slightly, which is fine for inference.
- Not ported yet: `PoseDetector` caching wrapper and `associate_poses` (Part 9).
- Letterbox/preprocess, decode `[1,56,N]` (4 box + 1 score + 17×3 keypoints), NMS, mapping keypoints back to frame coordinates (port of `tracker/pose.py::decode_yolo_pose`), person boxes derived from pose output.
- Golden: record a real pose-model output tensor for one test image in Python; Kotlin decode must match boxes and keypoints within a documented tolerance.

### Phase B — Inference on the phone
**Part 5 — ONNX Runtime on device (CPU) + bench screen** (~250) ✅ written, CI run + device numbers pending
- Files: `core/.../Bench.kt` (`benchStats`, `benchLine`, `COCO_SKELETON`) + `BenchTest.kt`; `app/.../PoseRunner.kt` (`ModelStore`, `PoseRunner.detect/bench`, ORT CPU session, threads option), `BenchScreen.kt` (model + thread chips, photo picker, boxes/skeleton overlay, 50-run bench with pre/infer/decode split, copy bench line), `MainActivity.kt` (home → bench), strings en/tr, manifest `configChanges`; `app/build.gradle.kts` (+ `onnxruntime-android` 1.22.0, coroutines 1.10.2, `.onnx` uncompressed); `android.yml` (python + `export_pose.py` for yolov8s-pose and yolo11m-pose into `android/app/src/main/assets/models/`, check the models are in the APK).
- **Never compiled** (no Maven access when written). First action: push, open the Android run, paste any Kotlin/Gradle/export error. Likeliest failures: ORT version string, `ultralytics` export step, Compose API names.
- Models: CI exports fp32 only (`export_pose.py`, opset 12, no simplify); fp16/int8 + simplified static graph belong to Part 6. APK grows by ~130 MB (debug). Side-load alternative: copy `.onnx` to `Android/data/com.takipli.oynat/files/models/` (listed first, no rebuild).
- Bench measures the full per-frame path: bitmap resize + letterbox/CHW + `session.run` + decode/NMS (3 warm-up runs, then 50). A gray synthetic frame is used if no photo is picked (cheap decode: pick a photo with people for real numbers). Result shows thermal status (0 = none … 6 = shutdown): rerun after cooling if > 1.
- Exit (open): run both models at 1/2/4/6/8 threads on the phone, paste the lines into "Bench lines" above, pick the default thread count.
- Load the pose model from assets, run on a still image, draw boxes + skeleton. Bench screen: ms/frame over 50 runs. Compare yolov8s-pose and yolo11m-pose on CPU.
- Exit: first real numbers on the phone; record in bench lines.

**Part 6 — NPU (QNN) + licence decision** (~300 Kotlin + ~120 Python) ✅ written, device numbers pending
- Files: `app/.../PoseRunner.kt` (`wantNpu`, `createNpuSession`, `createCpuSession`, `npu`/`npuFallback`/`provider`), `BenchScreen.kt` (CPU / NPU chips, provider + fallback reason shown, provider goes into the bench line), `app/build.gradle.kts` + `libs.versions.toml` (**`onnxruntime-android-qnn` 1.22.0 replaces `onnxruntime-android`**; `useLegacyPackaging = true`), manifest (`uses-native-library libcdsprpc.so`), strings en/tr, `scripts/quantize_android.py`, `android.yml` (optional int8 step), `android/calib/README.txt`.
- **Never compiled; QNN artifact version 1.22.0 NOT verified on Maven Central** (no network when written). If Gradle cannot resolve it, pick the version Maven lists for `com.microsoft.onnxruntime:onnxruntime-android-qnn` and change `onnxruntime` in `libs.versions.toml`.
- NPU path: `addQnn` with `backend_path=<nativeLibraryDir>/libQnnHtp.so`, `htp_arch=75`, `htp_performance_mode=burst`, `enable_htp_fp16_precision=1` (fp32 models run as fp16 on HTP: step 1 of §6 needs no calibration); `ADSP_LIBRARY_PATH` = nativeLibraryDir. First load compiles and dumps a QNN context model to `files/qnn_ctx/<model>_htp_v75.onnx` (embed mode 1); later loads open it directly. Any failure falls back to CPU and the reason is shown on the bench screen. Delete `qnn_ctx/` (clear app data) after changing the model file.
- Option names (`htp_arch`, context config keys) follow the ORT QNN docs from memory: if `addQnn` throws "unknown option", the fallback message shows which one.
- int8: `quantize_android.py SRC DST CALIB [--a16]` (QDQ, MinMax; 8-bit activations by default, `--a16` = 16a8w). CI builds `yolov8s-pose-int8` and `yolov8s-pose-a16w8` only when `android/calib/` has ≥10 frames. 8-bit activations quantize pixel-space keypoints coarsely (~2.5 px steps at 640); prefer a16w8 or fp16 if jitter shows.
- Exit (open, needs the phone): CPU vs NPU ms/frame per model (fp32→fp16-on-HTP, int8, a16w8) into "Bench lines"; keypoint jitter on a real clip (Part 9 gives a clip pipeline; until then compare overlays on a photo CPU vs NPU); pick the default model/backend and log it in §11. If the NPU is not faster, record why and keep CPU.
- `scripts/quantize_android.py` (fp16 first, int8 option), QNN provider options (HTP v75), context binary cache, automatic CPU fallback, provider shown on the bench screen, CI step to export the pose model.
- Exit: CPU vs NPU ms/frame recorded; keypoint jitter checked on a real clip; model choice (v8s vs 11m, fp16 vs int8) and the AGPL decision logged in §11. If the NPU is not faster, record why and keep CPU as default.

### Phase C — Offline scan
**Part 7 — Video decode + sampling** (~350) ✅ written, device check pending
- Files: `core/.../Sampling.kt` (`SampleSelector`, `normalizeRotation`, `displaySize`, `scaledSize`, `yuv709ToArgb`) + `SamplingTest.kt`; `app/.../VideoDecoder.kt` (`probe`, `sample`), `DecodeScreen.kt` (test screen), `MainActivity.kt` nav, strings en/tr.
- Sampling rule: grid `startS + k/fps`; the first decoded frame at/after each grid time is taken (≤ one source frame late); `t` = the frame's real PTS, so VFR is correct. Resume = new selector with `startS` (decoder seeks to the previous sync frame, earlier frames are dropped, grid stays aligned). Tests: CFR 30→10, scan fps ≥ source, VFR with a hole, resume = tail of the full run, end time, duplicate/backwards PTS.
- Decoder: MediaExtractor → MediaCodec → `ImageReader` (YUV_420_888, surface output); only chosen frames are rendered and converted. YUV→ARGB in Kotlin (BT.709 limited), **nearest-neighbour downscale** to `maxSide` (default 960), then rotation by `KEY_ROTATION`. Input via `setDataSource(ctx, uri)` (SAF `content://` works; picker = `OpenDocument`, no storage permission). If scan quality of small parts suffers, upgrade the downscale (area average) or use full-res crops in Part 9/crop-and-refine.
- Never compiled. Risks: `ImageReader` size vs codec output size mismatch on some devices; BT.601/full-range sources give slightly off colours; HDR/10-bit not handled.
- Exit (open, on the phone): "Video decode test": open a normal clip, a rotated (portrait) phone clip, and a VFR clip (screen recording); check displayed size/rotation, upright preview, `gap min/max` ≈ grid, first `t` ≥ start for "Resume from middle", record x-realtime per fps here.
- MediaExtractor/MediaCodec to frames at a **configurable scan fps** (`offline_fps`, and `scan_fps_part` for part mode), correct timestamps for variable frame rate, rotation metadata, seek/resume from a time, Storage Access Framework (file picker) input.
- Exit: scan-order frames from a real clip with correct `t`; resume from the middle works.

**Part 8 — Camera motion + cut detection** (~300) ✅ written, phone check pending
- Files: `core/.../Gmc.kt` (`estimateSimilarity` = seeded RANSAC + least squares like `cv2.estimateAffinePartial2D`, `CameraMotion` with the `gmc.py` step/W/MAX_SHIFT/reset logic, `FlowTracker` interface, 3x3 helpers, `camSize`), `Cuts.kt` (`CutDetector` port with OpenCV's integer HSV/gray maths, `cutThumbSize`, `areaResize`), `GmcTest.kt`, `CutsTest.kt`, new golden `cuts.json` (from `gen_golden.py::_cuts`: 6 synthetic frames incl. 2 cuts and a relaxed-threshold case); app: `FrameAnalyzer.kt` (`OpenCvFlow` = goodFeaturesToTrack + forward/backward LK on OpenCV, `FrameAnalyzer` = motion then cut then reset-on-cut like `Pipeline.step`), `DecodeScreen.kt` (chip "Camera motion + cut detection": ok steps, ms/frame, pan since last cut, cut times); `libs.versions.toml` + `app/build.gradle.kts` (`org.opencv:opencv` 4.11.0, `pickFirsts libc++_shared.so`).
- Split: only feature tracking is OpenCV (phone); everything else (RANSAC, matrices, cut logic) is pure Kotlin and unit-tested on the JVM with a fake tracker. `CameraMotion.step` returns the 3x3 prev→cur in full-res pixels: feed it to `ByteTracker.update(dets, motion)` (Part 9); on `FrameAnalysis.cut` call `ByteTracker.clear()` and pass no motion (the analyzer already returns identity then).
- Differences from Python: corners are integer pixels (Java `goodFeaturesToTrack` returns `MatOfPoint`); cut thumbnail is a box average (`areaResize`) of the 480-px gray-colour frame, not INTER_AREA from full res; gray differs ≤1/255 from OpenCV (test tolerance 3e-3). Thresholds (0.35 / 0.18) are still untuned on real footage (same open point as PC G4).
- Never compiled. **`org.opencv:opencv` 4.11.0 not verified on Maven Central** (no network): change `opencv` in `libs.versions.toml` if it does not resolve. `OpenCVLoader.initLocal()` is used; if it returns false the Decode screen says so.
- Exit (open, on the phone): Decode screen with "Camera motion + cut detection" on a clip with a camera pan: motion ok ≈ most steps, pan px plausible and grows in the pan direction; a clip with a hard cut lists the cut time; record ms/frame at 10 fps here.
- OpenCV Android dependency, port `gmc.py` (LK + RANSAC) and the cut detection (`cuts.py`); feed ByteTrack prediction and the world position used when a part leaves the frame.
- Exit: unit tests on synthetic shifts; one real clip with a camera pan checked on the phone.

**Part 9 — Scan engine (pose + tracking)** (~350) ✅ written, CI run + device check pending
- Files: `core/.../Scan.kt` (`associatePoses`, `kpRow`, `ScanPipeline` = `run_detection` + `Pipeline.step` without video, `buildScanDoc`, `scanSig`), `ScanTest.kt`, new golden `scan.json` (`gen_golden.py::_scan` runs the real Python `run_detection` + `ByteTracker` + `associate_poses` on 30 synthetic steps: 2 people, one vanishing 3 samples, flickering ankles, a sub-threshold pose, camera motion steps, one cut), `tests/test_scan_fixture.py` (PC `load_doc`/`list_tracks` accept the scan shape); app: `Scanner.kt` (decode -> pose -> `FrameAnalyzer` -> `ScanPipeline`, progress callback, cache, resume, cancel), `ScanScreen.kt` (test screen: pick video, model, fps 5/10/15, NPU chip, Scan/resume, Cancel, report line), `MainActivity.kt` (home button), strings en/tr.
- Behaviour: poses are decoded at `live_det_conf` (0.1; ByteTrack low stage); `pose_conf` (0.3) filters the poses that get `kp`. Cut -> `tracker.clear()`, no motion that sample, `"cut": 1` + `cuts` list. A failed camera estimate never stops tracking (same as `Pipeline.step`); without OpenCV the scan runs without motion/cuts. Frames are the Part 7 bitmaps (longer side 960); normalized coordinates make the size irrelevant.
- Cache/resume: signature = SHA-1 of `videoId|fps|model[:npu]`, 16 hex (`scanSig`; `videoId` = uri + size + modified time, so it does not match the PC's path-based id). Files in `files/scans/<id>.track.json` (finished, v2, `sig`) and `<id>.track.json.partial` (saved every 30 s and on cancel/error). A finished file with the same signature is returned without work. Resume continues at `last t + 0.5/fps`, keeps the old samples and starts new IDs after the highest used. **Tracker state is not stored**: people on screen at the resume point get new IDs (like after a cut); Part 10/11 must treat the resume point like a cut when linking targets.
- Tolerances: golden compares to 1e-9 after the same rounding (t 3 decimals, boxes 4, score 3, keypoints 4/4/2). The tracker is only ~1e-12 away from Python, so a value that lands exactly on a rounding boundary could differ by one last digit; if `scanMatchesPython` ever fails on 1e-4, look there first.
- Never compiled. Likeliest failures: Kotlin inference in `Scan.kt` (`ifEmpty { null }`), Compose `LinearProgressIndicator(progress = { })` (needs the BOM already pinned), `PoseRunner.use`.
- Exit (open, on the phone): scan a real clip with the Scan test screen, copy the report line into "Bench lines", pull `files/scans/*.track.json` (adb or Device Explorer) and run `python -m tracker` (`offline` loader / `list_tracks`) on it: it must load. Decide the default `scan_fps_part` from the measured x-realtime and keypoint quality (provisional 15).
- Pipeline: decode → pose model → GMC/cuts → ByteTrack → pose↔track association (`associate_poses`, IoU) → samples with `kp` → track doc v2. Progress as a Flow, resume from the last sample, cache signature (video path+size+mtime, fps, model), cancel. Scan fps configurable; default decided from the Part 6 bench.
- Exit: a real clip scans into a valid v2 `track.json` that the PC `offline` code can load (cross-test). Record scan x-realtime and the fps used.

**Part 10a — Presets + view building** (~400) ✅ written, CI run pending
- Files: `tracker/parts.py` (NEW, PC gets it too: `PRESETS`, `group_box`, `preset_boxes`), `tracker/offline.py` (`build_preset_views`, `apply_target(preset=...)`, target gets `"preset"` next to `"part"`; at most one of them), `scripts/gen_golden.py` (`kp_tol` added to shared keys; new fixture `target_views.json`: 20 cases = person / joint / 5 presets on the scan doc, a variant where person 2 vanishes for 12 samples (hold, zoom-out, zoom-in, cut), 2 configs, + 5 tap cases), `tests/test_parts.py`; Kotlin `core/.../Views.kt` (`PRESETS`, `zoomForBbox`, `computeView`, `easeView`, `smoothZeroPhase`, `viewsFromBoxes`, `personBoxes`, `jointBoxes`, `presetBoxes`, `buildTargetViews`, `pickTrack`, `resolveTap`), `ViewsTest.kt`; `Config.kpTol` (0.12); `Target.preset`.
- Presets: head 0-4, torso 5,6,11,12, left/right arm, left/right leg, legs, left/right foot (ankle only), feet, hands (wrists), person (box). Group box = union of joints with conf >= `kp_min_conf` + `part_margin` (0.02 x frame height each side), never smaller than `kp_box` x person height (>= 8 px). Chain: confident joints of the group -> person-box slice for that part (`REGION` table, e.g. legs = lower half) -> (<= `offline_interp_s` gap fill) -> hold `lost_hold_s` -> zoom-out. Joint-based candidates always beat slices when several linked IDs compete.
- Old single-joint targets (`part` = joint index) keep their exact PC behaviour (no margin). The preset format is an addition (`target.preset`); PC `views.json` readers ignore it.
- Tap: `resolveTap` = `scan.py::resolve_selection` point case (nearest joint within `kp_tol` x person height -> {id, part joint}; else person under/near the tap with `pick_tol`). Rectangle selection is NOT ported yet (10b). A tapped joint maps to a single-joint target; mapping it to a preset (e.g. ankle -> foot) is a UI decision for Part 11/13.
- Never compiled. Rounding: views are rounded to 4 decimals in both languages (`-0.0` normalized); a value exactly on a rounding boundary could differ by one digit (see Part 9 note).
- Exit still open: the same doc + target gives Python views (golden: done in tests, not run) and a real clip: a foot target that leaves and re-enters.

**Part 10b — Smoothing, off-screen propagation, rectangle selection** (~300) ✅ written, CI run pending
- Files: `tracker/offscreen.py` (NEW: `smooth_keypoints`, `propagate_boxes`), `tracker/parts.py` (`INFER`, `infer_joint`, `group_box(infer=)`, `preset_boxes(kps=)`), `tracker/offline.py` (`build_preset_views` = smooth -> boxes -> propagate -> views; `run_detection` writes `cam`), `tracker/pipeline.py` (`Pipeline.cam`, `cam_row`), `tracker/config.py` (`kp_infer`), `scripts/gen_golden.py`, `tests/test_offscreen.py`; Kotlin `core/.../Offscreen.kt` (NEW: `smoothKeypoints`, `propagateBoxes`), `Views.kt` (`inferJoint`, `presetBoxes(kps)`, `buildTargetViews`, `resolveRect`), `Scan.kt` (`camRow`, `ScanPipeline.step` writes `cam`), `Formats.kt` (`Sample.cam`), `Config.kt` (`kpInfer`), `ViewsTest.kt`, `ScanTest.kt`; goldens `scan.json`, `target_views.json`, `config.json` regenerated.
- **`cam` in samples:** `"cam": [a, b, tx/h, ty/h]` = background similarity previous sample -> this sample (`a` = scale*cos, `b` = scale*sin, translation in **frame-height units**, so the decode size does not matter; the earlier plan said full-res pixels). Written only when the camera estimate succeeded and the sample is not a cut. PC `Pipeline.cam` and Android `camRow(motion, h)` produce the same numbers (6 / 5 decimals).
- **Per-keypoint One-Euro (`kp_filter`):** target IDs only, confident joints (`kp_min_conf`) only, coordinates divided by the person height in px (like the live `KeypointTracker`), defaults 1.5 Hz / beta 2 / d-cutoff 1. The filter restarts on a cut and after a joint gap > `kp_hold_s`. The raw `kp` in `track.json` is never changed; smoothing happens when views are built.
- **Off-screen propagation (`kp_world` + `kp_hold_s`):** samples with no box (person lost or outside the frame) continue from the last box, forward only, <= `kp_hold_s` s. Position in frame-height units; per step: velocity x 0.9, position + velocity, then the sample's `cam` moves it into the new frame (a part standing still in the world slides with the background); velocity = EMA 0.3 of the world displacement over the consecutive visible samples before the gap. Stops at a cut. Gaps between two visible boxes that `_views_from_boxes` already fills (<= `offline_interp_s`, no cut) are left to the linear fill. Box size = last box; the centre may leave the frame (`compute_view` clamps the pan). After `kp_hold_s` the old chain (`lost_hold_s`, zoom-out, zoom-in before return) continues, so a long loss = `kp_hold_s` + `lost_hold_s` before zoom-out starts. Applies to presets other than `person`; single-joint targets (`part`) keep the 10a behaviour.
- **Parent-joint inference (`kp_infer`, new shared key, default true):** a missing ankle (15/16) = knee + (knee - hip) x 1.0, a missing wrist (9/10) = elbow + (elbow - shoulder) x 0.8, only when both upper joints are >= `kp_min_conf`; its confidence = min(upper confidences) x 0.5. It joins the group union (so `legs` reaches the ankle) and replaces the person slice when it is the only evidence (`left_foot` with a hidden ankle). Knees/elbows are not inferred.
- **Rectangle selection:** `resolveRect` = `resolve_selection` rect branch (person holding >= 50% of the rectangle, largest share then smaller box; fallback = person at the centre with `pick_tol`; rectangle <= 30% of the person height with a confident joint inside -> that joint). Golden: 6 cases.
- **Golden layout:** `target_views.json` cfgs `default`/`custom` pin the 10a behaviour (`kp_filter`/`kp_world`/`kp_infer` false, same expected values as before); `b`/`b_custom` use the 10b defaults. Docs: `base`, `gap` (id 2 gone 6..17), `exit` (id 1 gone 2..17 = 1.6 s: propagation then loss), `exit2` (id 1 gone 18..23: propagation across the camera-motion samples 20-23, stops at the cut). Extra fixtures `smooth` (rows) and `propagate` (boxes in/out) localise a failing view.
- **Checked here:** Python `pytest` (386 passed). Gradle could not run (no Maven access in that session), so the Kotlin core (`core/src/main`, serialization stubbed) was compiled with the Kotlin 2.0.21 compiler from Gradle's lib folder and a scratch harness ran the same checks as `ViewsTest`/`ScanTest` against the goldens: 9623 checks, 0 failures (38 view cases, smoothing, propagation, taps, rects, scan + `cam`). `ViewsTest.kt`/`ScanTest.kt` themselves were NOT compiled: first CI run decides. The Part 8 test fix noted in the Part 9 row is still open (not looked at).
- Exit still open: a real clip where a foot leaves and re-enters the frame (also with a panning camera): compare the views with and without `kp_world`; tune `kp_hold_s`, the limb ratios and `part_margin` by measurement.
- Rounding: views are rounded to 4 decimals in both languages; a value exactly on a rounding boundary could differ by one digit (see Part 9 note).

- **All 15 parts are written. Next: no new part. What is needed from you (in this order):** (1) push and open the Android run: paste the first Gradle/Kotlin error (nothing in Parts 2-15 has ever been compiled by Gradle, incl. the open Part 8 test fix; fix them one run at a time); (2) the phone checks listed in each part's Exit (Parts 5-7, 9, 11-14, 15); (3) for a signed APK: create the keystore and the 4 secrets (android/README.md). Then pick from the Backlog (section 8).
- (old note, answered by Part 11) **Part 11 needed:** the CI result of this push (paste the Gradle error if any, including the open Part 8 test fix), and a decision whether a tapped joint should map to a preset in the UI (e.g. ankle -> foot, wrist -> hands) or stay a single-joint target.

**Part 10 (original text, kept for reference)** (~400, may split)
- `tracker/parts.py` (presets and group boxes, also usable on the PC) + Kotlin port; **target = {person ID, part}**; pick by tap (nearest keypoint within tolerance → part, else person); `part_boxes` for joint groups; per-keypoint One-Euro; gap fill; occlusion fallback chain (neighbouring joints → person sub-box → person → zoom-out); hold `kp_hold_s` and camera-compensated propagation when the part leaves the frame; zero-phase smoothing; hold then zoom-out on long loss; `target_fill`, zoom limits; cut handling.
- Exit: golden test: same track doc + target + part → same views as Python (within tolerance); test of a part that exits and re-enters the frame.

### Phase D — Phone app: scan, preview, export
**Part 11 — Scan service + scan UI** (~300) ✅ written, CI run + phone check pending
- Files: `core/.../Pick.kt` (NEW: `TargetPick`, `presetForJoint`, `resolvePick`, `applyPick`) + `PickTest.kt` (9 tests on a synthetic 1-person doc); app: `ScanService.kt` (NEW, foreground service type `dataSync`), `PickScreen.kt` (NEW), `Scanner.kt` (`writeAtomic` now `internal`), `ScanScreen.kt` (`videoIdOf` now `internal`), `MainActivity.kt` (home button + `pick` route), manifest (FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK, `<service>`), strings en/tr.
- Flow: open video (`OpenDocument`, persistable read permission taken) -> first frame (decoded at t = first sample, 960 px) -> tap on it (red marker) -> optional part chip (Auto / person / head / ... / hands) -> model, fps 10/15/20, NPU chip -> Start. The pick is stored as `TargetPick(t, x, y, preset, exact)` (normalized point; rectangle fields exist in the core but the UI has no rectangle yet).
- The tap cannot be turned into a person ID before the scan (IDs come from ByteTrack), so it travels with the intent and is resolved **after** the scan with `resolveTap`/`resolveRect` on the finished doc (PC A1 does the same). Result: `files/scans/<id>.track.json` gets `target` + `views` (samples and `sig` untouched, so the Part 9 cache still matches). A scan where nobody is at the tap is kept without target (UI says so; pick again = new scan call, cached = instant).
- **Decision (provisional, you did not answer):** a tapped joint maps to its preset group: nose/eyes/ears -> head; shoulders/hips -> torso; elbow/wrist -> left/right arm; knee -> left/right leg; ankle -> left/right foot. A chosen preset chip always wins over the tapped joint; chip "Exact joint" keeps the single joint (`target.part`). Change `presetForJoint` (one `when`) if you prefer e.g. wrist -> hands.
- Service: `startForegroundService` + `startForeground(dataSync)`; PARTIAL wake lock (max 4 h); `START_REDELIVER_INTENT` (an interrupted scan restarts and resumes from `.partial`); notification with progress (updated ~1/s) and a Cancel action; UI state is a `StateFlow` (`ScanUi`) in the service's companion, so closing and reopening the activity shows the running scan. File per video: `files/scans/<SHA-1(videoId)>.track.json` (same naming as the Part 9 test screen).
- Limits to know: Android 15 limits `dataSync` foreground services to ~6 h per 24 h (fine for these scans). The UI state is in memory: if the whole process is killed and the service is redelivered, the UI shows Idle until the next state update (the notification still shows progress). Preset names on the chips are not translated (shown as `left foot`).
- Never compiled. Likeliest failures: `Notification.Action.Builder(int, ...)` deprecation, Compose imports in `PickScreen.kt`, `ScanUi` sealed `data object` (Kotlin 2.2 ok).
- Exit (open, on the phone): pick a clip, tap a foot, start, switch the screen off for a few minutes, come back: the notification showed progress, the scan finished, the UI says `Target: #n left foot`; then Cancel mid-scan and start again: it resumes (progress starts above 0). Check `files/scans/*.track.json` has `target` and `views`.
- (original plan:)
- Foreground service (notification, cancel, resume). Screens: open video → first frame → tap a person or part, or pick a preset (head / leg / foot / …) → start scan. Survives app kill/restart through the resumable scan.
- Exit: a long clip scans with the screen off and the app in the background.

**Part 12 — Live scan preview (low fps)** (~300) ✅ written, CI run + phone check pending
- Files: `core/.../Preview.kt` (NEW: `PersonOverlay`, `PreviewOverlay`, `previewOverlay`, `cropForBox`, `livePickTarget`) + `PreviewTest.kt` (5 tests, hand-computed expectations); app: `ScanPreview.kt` (NEW: `ScanPreview`, `previewBitmap`, `ScanPreviewView` Compose canvas), `Scanner.kt` (`pick` + `onPreview` params), `ScanService.kt` (`ScanUi.Running.pv`, `Done.xRealtime/preview`, `preview` extra), `PickScreen.kt` (chip "Live preview", preview view while scanning, speed line when done), strings en/tr.
- Drawn: the sampled frame (longer side 320), every tracked person box (white), skeletons (COCO lines, joints >= 0.35), the **target person in yellow**, the **part box in red**, the **camera crop in green** (`cropForBox` = `computeView` + the `cropRect` centre/size maths, unrounded, so it matches what export will cut).
- Cost control: a preview is built at most every 200 ms wall time (`PREVIEW_MIN_MS`): one small bitmap copy + a tiny overlay on the scanner thread, the rest is the UI thread. The "Live preview" chip turns it off; the Done line prints `Scan speed: X x realtime (preview on/off)` so the on/off overhead can be recorded (exit below).
- Live target: the pick is resolved on the samples so far (`livePickTarget`, retried at each preview until a person is at the pick). Part box and crop use the **raw** keypoints of the current sample only (no One-Euro, no propagation, no zero-phase), so the green crop is jumpier than the final views. After a **resume** the target ID comes from the old samples while people now have new IDs, so the red/green boxes may be missing until a fresh scan of that person; the final `applyPick` still works on the full doc.
- Not drawn: the camera-motion/cut state, history trail. Preview bitmaps are never recycled (GC), the scanner only keeps the last one.
- Never compiled. Likeliest failures: Compose `Canvas` draw helpers, `if (..) ({ v: ScanPreview -> .. }) else null` lambda, `ScanUi.Done` constructor arity.
- Exit (open, on the phone): scan the same clip twice with the chip on and off and paste both `Scan speed` lines under "Bench lines" (target: within a few percent); the preview must follow the person and show the red box on the tapped part.
- (original plan:)
- While scanning, draw the current sampled frame, the **skeleton**, the **selected part box**, and the crop rectangle the camera would use (from the views built so far). Redraw at most ~5 fps and never slow the scanner; use a downscaled preview bitmap.
- Exit: preview updates during a scan; scan speed with preview on is within a few percent of preview off (record both).

**Part 13 — Result preview + tuning** (~350) ✅ written, CI run + phone check pending
- Files: `core/.../Tuning.kt` (NEW: `Tuning`, `Config.withTuning`, `rebuildViews`, `viewTransform`) + `TuningTest.kt` (5 tests); app: `ResultScreen.kt` (NEW), `PickScreen.kt` ("Preview result" button after a scan with a target), `MainActivity.kt` (`result` route), `libs.versions.toml` + `app/build.gradle.kts` (**`androidx.media3:media3-exoplayer` 1.8.0, version NOT verified** (no network); change `media3` in the toml if it does not resolve), strings en/tr.
- Player: ExoPlayer on a `TextureView` (not a `SurfaceView`, which cannot be scaled), inside a box with the video's aspect ratio and `clipToBounds`; each frame `viewAt(views, position)` -> `viewTransform` -> `graphicsLayer` scale + translation. `viewTransform` uses the same centre clamp as `cropRect` (a unit test checks the centre matches), so the preview shows what the export will cut. Position is polled every 33 ms; the transform is read in the draw phase only. "Follow camera" chip = compare with the original.
- Sliders (each rebuilds the views from the saved scan on release, no rescan): target fill (`target_fill`), part box size (`kp_box`), max zoom (`max_zoom`, log2), follow speed (`offline_alpha`, EMA alpha: higher = tighter), hold when lost (`lost_hold_s`). Reset button. Body-part chips switch the preset of the same person ID (`Target(id, preset)`), also without rescanning. Not exposed yet: `part_margin`, `kp_hold_s`, min zoom, alpha_pos/zoom (they exist in `Config`; add sliders if needed).
- Save: writes `target` + `views` back to `files/scans/<id>.track.json` and a `<id>.views.json` (`Formats.viewsOf`, version 1) next to it: this is the input of Part 14 export and is PC-compatible. Slider values themselves are not stored (only their effect on the views); they reset when the screen is reopened.
- Limits: the player is the platform decoder at full fps; very heavy zoom on a low-resolution video looks soft (same as the export). Rebuilding on a long clip is a pure CPU pass over the samples (expected ms to tens of ms; unmeasured).
- Never compiled. Likeliest failures: media3 version, `AndroidView` + `graphicsLayer` imports, local `@Composable fun tune`.
- Exit (open, on the phone): preview a finished scan at normal speed; moving each slider changes the zoom/follow visibly on release; switching foot -> head works instantly; Save then reopen shows the saved views; record how long a rebuild takes on a long clip.
- (original plan:)
- Media3 ExoPlayer with the same view transform as export (one shared transform function), seek bar, play/pause. Sliders: `target_fill`, **part box size (`kp_box`/`part_margin`)**, `max_zoom`, smoothing, lost-hold. Changing a slider rebuilds views from the saved track doc without rescanning. Switching the part (e.g. foot → head) also works without rescanning.
- Exit: preview of the finished scan at normal speed; sliders and part switching are instant.

**Part 14 — Export** (~300) ✅ written, CI run + phone export check pending
- Files: `core/.../ExportMath.kt` (NEW: `exportNdc`, `exportBitrate`) + `ExportMathTest.kt` (**cross-test: the matrix crop equals `cropRect` within 1 px at 20 times** on a walking-person doc, plus known values and bitrate clamps); app: `Exporter.kt` (NEW: Transformer setup, progress, `saveToMovies`), `ResultScreen.kt` (Export section: H.264 / HEVC chips, Export, Cancel, progress), `libs.versions.toml` + `app/build.gradle.kts` (`media3-transformer`, `media3-effect`, same `media3` 1.8.0, **unverified**), strings en/tr.
- How: `EditedMediaItem` with `Effects(no audio processors, [MatrixTransformation])`; the matrix per frame = `viewAt(views, presentationTimeUs / 1e6)` -> `exportNdc` -> `setTranslate(tx, ty)` + `postScale(s, s)` (NDC, y up: crop centre to the middle, crop fills the frame). Frame size stays the source (display) size; Transformer applies the rotation metadata before effects, so the maths is in upright coordinates like `doc.video.w/h`. Audio: no audio effects -> passed through (original audio kept). Encoder: `DefaultEncoderFactory` with a bitrate from `exportBitrate` (w x h x fps x 0.11 bit/px, HEVC 0.07, clamped 2-100 Mbit/s); Transformer's default fallback may switch codec if the device has no encoder for the chosen one.
- Output: written to `cacheDir/export/out.mp4`, then copied into `Movies/TakipliOynat/Takipli_<date>.mp4` through MediaStore (`IS_PENDING`, no storage permission), cache file deleted. Exports the views **currently shown** (after sliders / part switch), not only the saved ones.
- Runs **in the app process, not in a service**: the screen is kept on (`keepScreenOn`) and the UI says to keep the app open. If the app is killed the export is lost (no resume). Part 15 can move it into a foreground service (same pattern as `ScanService`).
- Not done: output size/aspect options (backlog), a `.views.json` cross-test PC<->phone export (the plan's "Compatibility rule": do it on a real clip: export the same `.views.json` with PC `python -m tracker export` and compare), HDR/10-bit sources.
- Never compiled. Likeliest failures: media3 version/artifact names, `MatrixTransformation { }` SAM lambda, `Transformer.Listener` signatures, `ExportException.errorCodeName`.
- Exit (open, on the phone): export a real clip (H.264): it plays, has audio, the zoom follows the part like the preview; record the "x realtime" shown after export under "Bench lines"; try HEVC; try cancel.
- (original plan:)
- Media3 Transformer with a time-varying matrix effect from `viewAt`; hardware encoder; original audio passthrough; H.264 default, HEVC option; progress, cancel, output to the Movies folder through MediaStore; even output size (source size; zooming far into a low-resolution video looks soft, so 4K sources work best; an output-size option is in the backlog).
- Cross-test: the crop for 20 sample times matches `cropRect` (≤ 1 px). Check an exported file plays and has audio.
- Exit: a real clip exported on the phone; record export x-realtime.

**Part 15 — Hardening + release** (~250) ✅ written, CI run + phone check pending
- Files: `core/.../Hardening.kt` (NEW: `ErrKind`, `classifyError`, `exportBytesNeeded`, `enoughSpaceForExport`, `lowBattery`, `trimLog`, `logLine`) + `HardeningTest.kt` (6 tests) + `VersionTest.kt` (`repoVersionFileIsWellFormed`: `../../VERSION` must be X.Y.Z); app: `Safety.kt` (NEW: `App` (crash logger, deletes a half-written `cache/export`), `ErrorLog`, `Safety.friendly/videoReadable/freeBytes/batteryLow/shareLog`, composables `BatteryWarning`, `AboutSection`), `ResultScreen.kt`, `PickScreen.kt`, `ScanService.kt`, `MainActivity.kt` (home scrolls, footer), manifest (`android:name=".App"`), strings en/tr (+12 each), `app/build.gradle.kts` (release signing from env, `lint` does not fail the build); `.github/workflows/android.yml`; `THIRD_PARTY_NOTICES.md` (section 5, Android); `android/README.md` (release + error log); `tests/test_android_release.py` (VERSION shared, no secrets in gradle, workflow wiring, notices, en/tr string keys equal).
- **Messages:** every failure goes through `Safety.friendly`: one sentence for NO_SPACE / MODEL_MISSING / VIDEO_ACCESS / CODEC / OTHER + the short technical reason in brackets; the full line (time, place, kind, exception, 5 frames) is appended to `files/logs/error.log` (100 KB cap). Home footer: **Share error log** (text with version + device) and **Clear error log**. Uncaught crashes are logged too (`App`).
- **Preflight:** export checks free space first (cache file + the copy in Movies: on one volume 2 x the estimate, `enoughSpaceForExport`; estimate = bitrate x duration x 1.15 + 16 MB), the video must still be openable (`videoReadable`, SAF grant lost / file deleted: message + Export disabled), the player reports its own errors, a battery < 20 % and not charging shows a warning above Start (pick screen) and Export. `Exporter.start` exceptions and an error that arrives before `start()` returns are handled (no stuck "exporting" state).
- **Release:** `app/build.gradle.kts` signs `release` only when `KEYSTORE_PATH` (+ `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) exist; no R8 (ORT/OpenCV keep rules untested). `android.yml` on a `v*` tag: writes the keystore from secret `ANDROID_KEYSTORE_B64`, `assembleRelease`, deletes the keystore, artifact `TakipliOynat-Android-Release`; without the 4 secrets the step is skipped with a warning (debug APK still uploaded).
- **Licence rule kept (decision of 2026-11-14):** the APK contains the AGPL pose models, so it is attached to the GitHub Release **only if the repository is private** (`github.event.repository.private`); in a public repo it stays a workflow artifact. This is why the original exit ("a `v*` tag publishes Setup, Portable and the APK on the same release") holds for a private repo only. To publish for others: swap the pose model first (Part 6 note).
- **Not done (moved to Backlog):** export in a foreground service (still in-process, screen kept on; a killed app loses the export), notification-permission explanation dialog, translated preset chip names, R8/shrinking, a `.views.json` PC <-> phone cross-export on a real clip, the 3-clip hardening test (fast limb motion, part leaves and re-enters, several people) which needs the phone.
- Race to know: `build.yml` and `android.yml` both call `softprops/action-gh-release` on the same tag; if both try to create the release at the same moment one can fail: re-run that job (the other already created it).
- Never compiled. Likeliest failures: `signingConfigs.findByName` / `lint {}` DSL in AGP 8.13, `Thread.setDefaultUncaughtExceptionHandler` lambda, `registerReceiver(null, filter)`, `PlaybackException.errorCodeName`, the `try { Exporter.start(...) } catch` expression in `ResultScreen`.
- Exit (open, on the phone): break each thing on purpose: delete the picked video then open the result (video message, Export disabled); fill storage then Export (storage message with MB); remove the models folder build (model message); start an export at < 20 % battery (warning); Share error log shows the lines; a signed APK from a `v*` tag installs over a previous signed one (same key).
- (original plan:)
- Error log, clear messages (unsupported codec, out of space, model missing), permission/SAF edge cases, low-battery warning, tested on ≥ 3 clips (fast limb motion, part leaves and re-enters the frame, several people).
- Signed release build in CI (keystore from GitHub secrets), APK attached to the `v*` GitHub Release (see §4), `VERSION` sync test, licence notices for the Android build (ONNX Runtime MIT, QNN runtime terms, OpenCV Apache-2.0, Media3 Apache-2.0, pose model licence per the Part 6 decision).
- Exit: a `v*` tag publishes Setup, Portable and the Android APK on the same release.

---

## 8. Backlog (after Part 15; do not start earlier)

| Item | Note |
|---|---|
| Whole-body model (toes, heel, fingers, face points) | RTMPose-wholebody; gives real foot and hand tracking (the PC already has it for live mode; check weights licence) |
| Crop-and-refine (two-stage) | find the person at low fps, re-run pose on a crop around the person at a higher fps; better for small parts and fast limbs; decide from the Part 9 bench |
| Live tracking mode | Playback or camera with ~10 fps analysis and GPU zoom; reuse the scan engine in streaming form |
| Re-ID, team colour, jersey number | OSNet etc.; helps in crowds |
| Multi-target / group framing | PC plan T1/T2 |
| Export options | Output size/aspect (16:9, 9:16), upscale, picture-in-picture, batch queue (PC plan E2) |
| Other classes (ball, cars, animals) | PC plan O1 |
| Export in a foreground service | Same pattern as `ScanService`; survives the app being killed (Part 15 left it in-process) |
| Release polish | R8/shrinking with keep rules, notification-permission dialog, translated preset names, PC <-> phone `.views.json` cross-export on a real clip |

---

## 9. Risks and unknowns

- **Not measured yet:** NPU speed, first-run QNN compile time, quality loss from fp16/int8 (keypoints are more sensitive than boxes). Parts 5–6 settle this.
- **Fast limbs:** a foot can travel far between samples at 10 fps; motion blur lowers keypoint confidence. Mitigations: higher scan fps for part mode, One-Euro + zero-phase smoothing, gap filling. Real clips decide.
- **Small parts:** a foot/head is few pixels at a distance; COCO-17 gives only the ankle for the foot. Crop-and-refine and the whole-body model are the planned upgrades (backlog).
- **Zoom vs resolution:** the export keeps the source size, so heavy zoom on low-resolution video looks soft.
- **Left/right confusion:** the pose model can swap left/right limbs when the person turns; smoothing and ID binding reduce but do not remove it. Real clips decide.
- **Operator support:** some ops may fall back to CPU and split the model; may need model simplification.
- **Background limits:** manufacturers kill long background jobs. Mitigation: foreground service + resumable scan (Parts 9, 11); test with the screen off.
- **Variable frame rate / rotated videos:** easy to get timestamps wrong; Part 7 tests both.
- **Two implementations drift apart:** mitigated by golden fixtures and cross-tests; new part logic is written in Python first.
- **Licences:** pose models from Ultralytics are AGPL-3.0 (personal use; distribution needs a decision, Part 6).
- **First CI run (2026-11-14) failed in the `setup-android` step, before Gradle:** `Failed to find package 'tools'` (the action's default `packages: tools platform-tools`). Fixed with `packages: ""` in `android.yml`; AGP downloads the platform/build-tools itself. Parts 2-4 Kotlin has still not been compiled by CI: paste the next error.
- **Part 1 was never executed:** the first CI run may fail on a version or setup detail. Paste the Gradle error, fix, re-run.
- **PC app was never validated on real footage** (PC `handover.md`, V1). Android inherits the same tracking logic, so tracking quality is only proven by real clips on both sides.

---

## 10. Session rules (free Claude account, weekends)

1. One part per session. Never start a part you cannot finish; stop at the last green test and write that in this file.
2. **Use git, do not re-send the whole project.** Paste or upload only: this file, the files the part touches, and the failing CI log if there is one. (This replaces the PC rule of zipping the whole project every session, which wastes tokens.)
3. At the end of a session: update §2 status, add the bench line if any, list the changed files, list "next part + what it needs".
4. Pure-Kotlin parts first, each with a golden test; Android-only parts keep logic in `core` where possible.
5. CI is the debugger: if it fails, paste the Gradle error, not the whole log.
6. No new heavy dependency without checking the APK size.
7. Planning estimate: about 2 sessions per weekend, so 15 parts ≈ 8 weekends, plus 20–30% for device debugging (Part 10 may take two sessions).

**Prompt template for each session**
> Read `handover-android.md`. Do Part N only. Files I attach: …. Keep it ≤ 300 changed lines with tests. At the end give me: changed files, the updated status table, and what Part N+1 needs from me.

---

## 11. Decisions log

| Date | Decision |
|---|---|
| 2026-11-14 | Android project lives in `android/` in the same repo; CI builds PC and Android in parallel |
| 2026-11-14 | Native Kotlin app, no Python on the phone |
| 2026-11-14 | Live mode postponed; first goal is export mode with a low-fps live scan preview |
| 2026-11-14 | Inference: ONNX Runtime, QNN provider for the Hexagon NPU, CPU fallback |
| 2026-11-14 | Export through Media3 Transformer (hardware encoder), not ffmpeg |
| 2026-11-14 | Shared `views.json`/`track.json` formats with the PC version (track.json v2 with keypoints) |
| 2026-11-14 | Android CI is a separate workflow `android.yml` (parallel to `build.yml`): PC tests read `build.yml`, so it stays untouched |
| 2026-11-14 | Build stack pinned to a known-good older set (AGP 8.13 / Kotlin 2.2.20 / Gradle 8.14.3); no Gradle wrapper committed |
| 2026-11-14 | applicationId `com.takipli.oynat`; UI strings English + Turkish |
| 2026-11-14 | **Purpose clarified: track the human body and body parts (leg, foot, head) in motion, zoomed.** Body parts move from the backlog into the first 15 parts |
| 2026-11-14 | A pose model replaces YOLOX-S as the main detector (person box + keypoints in one pass); the target is {person, part}; part presets use joint groups |
| 2026-11-14 | Scan fps for part mode is configurable and set after measuring (10 fps is likely too low for fast limbs) |
| 2026-11-14 | Part 2: kotlinx-serialization-json 1.9.0 in `core`; JSON via `JsonNamingStrategy.SnakeCase` (camelCase Kotlin props = PC snake_case keys); `emb`/`team` kept as raw `JsonElement`; `ViewsDoc.version` always written; rounding in `cropRect` is half-to-even (`Math.rint`) like Python `round` |
| 2026-11-14 | Part 6: ORT artifact = `onnxruntime-android-qnn` (CPU + QNN in one AAR), 1.22.0, unverified; NPU first via fp16-on-HTP (no calibration), int8/a16w8 optional via calibration frames |
| 2026-11-14 | **Part 6 licence decision — CONFIRMED by user (2026-11-14): personal use only.** (was provisional:) personal-use builds only. Ultralytics weights/code are AGPL-3.0, so the APK is a CI artifact for the owner, not published/distributed. Revisit before Part 15's release step: either keep releases private or swap in a pose model whose code and weights licences allow distribution (check both). Also add the Qualcomm QNN runtime licence terms to the notices in Part 15 |
| 2026-11-14 | Part 9: scan files live in `files/scans/` (finished `.track.json` + `.partial`), signature = SHA-1(video id, fps, model[:npu]); resume restarts the tracker (new IDs), no tracker state on disk |
| 2026-11-14 | Part 10: split into 10a (presets, group boxes, view building, tap resolve) and 10b (filters, off-screen propagation, rect selection); `target.preset` added beside `target.part`; `kp_tol` is now a shared config key |
| 2026-11-14 | Part 10b: samples get optional `cam` = [a, b, tx/h, ty/h] (frame-height units, not full-res pixels); new shared key `kp_infer` (default true); off-screen propagation is forward-only, <= `kp_hold_s`, skips gaps the linear fill covers, stops at cuts; golden cfgs `default`/`custom` pin 10a, `b`/`b_custom` are 10b |
| 2026-11-14 | Part 11: scan runs in a `dataSync` foreground service; the tap is resolved to {person, part} after the scan (`applyPick`); tapped joint -> preset group by default (provisional), chip overrides, "exact joint" option |
| 2026-11-14 | Part 12: preview at most every 200 ms, 320 px, raw per-sample part box + crop (not the smoothed final views); toggle chip to measure overhead |
| 2026-11-14 | Part 13: preview = ExoPlayer on TextureView + `graphicsLayer` from `viewTransform` (same clamp as `cropRect`); sliders rebuild views from the saved scan on release; media3 1.8.0 unverified; Save writes track.json + .views.json |
| 2026-11-14 | Part 15: errors -> `classifyError` kinds + friendly sentence + `error.log`; export preflight (space, video access, battery); release signed from env/secrets, APK attached to the Release only in a PRIVATE repo (AGPL models), no R8 |
| 2026-11-14 | Part 14: export = Transformer + `MatrixTransformation` from `exportNdc` (cross-tested against `cropRect`), source size, audio passthrough, bitrate by formula, cache file -> MediaStore Movies; runs in-process (no service yet) |
| 2026-11-15 | EdgeTAM: five static graphs, NPU per graph with CPU fallback (also at run time), real memory tracker with static slot layout, encoder-only int8 behind a cosine gate (section 16) |
| 2026-11-14 | Part 2: `part_margin` = 0.02 and `scan_fps_part` = 15.0 are **provisional** placeholders, final values by measurement (Parts 9/10/13) |

**Open (decide in the named part)**
- ONNX Runtime QNN artifact version: 1.22.0 chosen, verify it resolves (CI) — Part 6
- Pose model: yolov8s-pose vs yolo11m-pose; fp16 vs int8 — Part 5/6
- Calibration frames for int8 (user's own 50–100 frames with people in motion in `android/calib/`) — Part 6
- Default scan fps for part mode — Part 9
- Default `part_margin` and per-preset box sizes — Part 10/13
- Default codec (H.264 vs HEVC) — Part 14


---

## 12. Part 16 — free-point target + stable export (started 2026-11-14)

**Why:** the user wants to follow *any tapped spot* (not only body parts), in close shots where points move fast and leave the frame, with stutter-free export and multiple passes.
**16a (core, written, never compiled):** `FreePoint.kt` (`FreePick`, `PointTrack{a,b}`, person-anchor, `fuseFreePoint`, `freePointBoxes`, `viewAtSmooth`), `Target.free`, `TrackDoc.pt`, `buildTargetViews` branch (reuses `viewsFromBoxes` + `propagateBoxes`), `Exporter`/`ResultScreen` use `viewAtSmooth`; `FreePointTest.kt`.
**16b (written 2026-11-14, never compiled; CI + phone check pending):** `app/PointTracker.kt` (`GrayStore` = gray frames in a temp file, 640 px; `PointTracker`: 5x5 LK cluster with forward-backward check and initial flow from scan `cam` + velocity, NCC verify, windowed template search, whole-frame re-acquisition after loss, slow template update; `twoPass` = A: from the tap forward and backward in time, B: reverse run from the last confident sample back to the tap), `app/PointScanner.kt` (decode once at the scan grid, run passes, return `PointTrack`), `ScanService` runs it after `applyPick` for a free pick (stale `pt` dropped; failure keeps anchor-only views), `TargetPick.free/zoom` + `resolvePick` (free target, id -1 when no person), PickScreen chip + zoom slider, strings EN/TR, `FreePickResolveTest`.
**Open points:** `cam` is assumed to be in frame-height units for x and y (as `Offscreen.kt`); check on a panning clip. Thresholds (NCC 0.3/0.55/0.7, gate 0.35 person heights) untuned. **Backlog:** refine pass at 2x fps on spans with fused conf < 0.4 (needs a second decode of those spans), live preview of the point during the scan, tracker scale change (template is fixed size).

## 13. Part 17 removed (2026-11-14)
The manual pin editor (`PointEditor.kt`, pins, Re-track with pins, ResultScreen wiring, `edit_*` strings) was removed at the user's request; `PointScanner` deletes its gray-frame cache after every run again.

## 14. Part 18 — freehand region for free tracking (2026-11-14, never compiled)
Draw one or more closed strokes on the pick frame (`TargetPick.shapes` -> `FreePick.shapes`, polygons simplified by `simplifyStroke`, x/y = centre of `shapeBounds`). `app/RegionTracker.kt`: corners inside the mask -> forward-backward LK (initial flow from scan `cam`) -> RANSAC similarity (`core.estimateSimilarity`) moves the polygons each step; lost -> camera-predicted polygons + whole-frame template search; passes A (both directions from the pick) and B (reverse run). Rows `[cx, cy, conf, w, h]`; `fuseFreePoint` keeps size (`w,h`) and falls back to the pick-time size scaled with the anchor person; `freePointBoxes` frames the region bounds x1.15, the `targetFill` tuning slider decides how tight. Pick UI: "Draw region" chip, undo/clear. Limits: one rigid similarity per frame (legs deform: framing is good, the mask drifts from the exact outline); two legs that move very differently are framed as one group; stroke drawing and the tracker never run on a device yet.

## 15. Part 19 — EIS-style stabilization of the export (2026-11-14, never compiled)
**Why:** the export cropped in frame coordinates from views sampled at the scan fps, so source shake/roll reached the output. The user does not care about processing time and wants native-resolution, stable, accurately tracked output.
**What:** `core/Stabilize.kt` (`CamPath` = per-frame frame->world similarity, tx/ty in frame-height units, shot id per cut; `CamPathBuilder`; `StabPlan` = view keys -> world (centre, ln width) -> C1 resample -> zero-phase smoothing -> back into every frame with that frame's own pose; roll = low-passed camera roll (cancels open-loop drift); zoom floor from `zoomToFit` (dilated then smoothed, no pumping); centre slides only as a last resort; `cropMatrixNdc` = full rotate+scale+shift matrix for Media3, equals `exportNdc` at zero roll). `app/DenseCamera.kt` (`RobustFlow`: 800 corners, LK 31 px, 5 levels, retry on 2x/4x smaller images; `DenseCamera.analyze`: every frame, 960 px analysis from a 1920 px decode, scan boxes masked out, plausibility check, last step held for <= 3 failed frames, cuts via `CutDetector`; cached as `<scan>.cam.json`, signature = video + scan sig). `Exporter.start(plan = ...)` uses `plan.ndcMatrix(us)`; `ResultScreen` has a "Stabilize" chip (default on) that runs the pass, then export uses it. `FrameAnalyzer` got a `flow` parameter (default unchanged). `PickScreen` fps chips now 10/15/20/30/60, default 30. `StabilizeTest.kt` (JVM). Strings in `values/strings_stab.xml` + `values-tr/strings_stab.xml`.
**Checked here:** a Python port of the crop math and of `StabPlan` on synthetic 4K shaky footage (target jitter 70 -> 14 px, 13.75 deg of simulated roll drift did not reach the output, 2 of 600 frames slid). Kotlin NOT compiled (no compiler in that session): first CI run decides.
**Open:** the result preview player does not show the stabilization (export only); rolling shutter is not corrected (needs gyro); a cut is detected on the exact frame, the scan's cut sample can be up to one scan step later, so keys in that gap are assigned to the new shot; HDR/10-bit clips use the slower retriever path; thresholds (`plausibleCameraStep`, `StabConfig`) untuned on real clips.

- 2026-11-15: **Two Android builds (flavors `pose` and `sam`).** `app/build.gradle.kts` has product flavors (dimension `engine`; `sam` = applicationId `.sam`, versionName `-sam`, `BuildConfig.ENGINE`). Models: `app/src/<flavor>/assets/models` (CI fills, git-ignored). `android.yml` runs a matrix; the sam job downloads optional SAM-type `.onnx` files from the repo variable `SAM_MODELS_URL` (otherwise builds without them + warning). **Not done:** the SAM tracker Kotlin code and the EdgeTAM ONNX export; the `sam` build currently behaves like `pose`. Never compiled in CI yet.

- 2026-11-15: **SAM 2.1 tracker for the `sam` build** (never compiled/run). `scripts/export_sam2.py` (CI, official checkpoint `sam2.1_hiera_base_plus`) -> `sam2-encoder.onnx` + `sam2-decoder.onnx`; `app/src/sam/.../SamEngine.kt` = track-by-prompting (box predicted from the last good step + point in the previous mask; best of 3 masks; 3 fps; forward + backward from the pick; mask centroid rows `[x,y,conf]`). Used only for free-point / region targets via `PointScanner` (falls back to LK on any failure); person/part presets still use pose. `src/pose/.../SamEngine.kt` is a no-op stub. Open: tensor names/shape of the export, speed and memory on the phone, quality vs LK on real clips; true SAM 2 memory tracking is NOT implemented.

- 2026-11-15: **`sam` build switched to EdgeTAM** (SAM 2.1 base+ was too big; `export_sam2.py OUT edgetam` is now the default, SAM 2.1 still selectable). Model files renamed `sam-encoder.onnx` / `sam-decoder.onnx`; `SAM_FPS` 3 -> 6. Same track-by-prompting design (EdgeTAM's memory modules are NOT exported). Assumes EdgeTAM keeps SAM 2's prompt encoder / mask decoder / `build_sam2` API, 1024 input and 256x256 mask logits (the app throws and falls back to LK if the mask size differs). Never run: checkpoint URLs and config name are guesses with fallbacks.

- 2026-11-15: **Versioning + in-app updates (never compiled/run).** `versionCode = (X*10000+Y*100+Z)*1000 + BUILD_NUMBER` (CI run number, cap 999; core `updateVersionCode`, `UpdateTest`). `BuildConfig.BUILD_NUMBER` / `UPDATE_REPO` (= `GITHUB_REPOSITORY` on CI). `android.yml`: signed release on every push (main + tags), published with `gh` to Releases (main = prerelease `vX.Y.Z-b<run>`, keep 8; `v*` tag = release), private repos only; asset `TakipliOynat-Android-<flavor>-X.Y.Z-b<run>.apk`. App: `Updater.kt` (GitHub API list, flavor filter, download with manual redirect so the token is not sent to object storage, size + SHA-256 check, FileProvider install) + `UpdateScreen.kt` (Home -> Updates; repo + optional read-only token). Manifest: INTERNET, REQUEST_INSTALL_PACKAGES, FileProvider `${applicationId}.updates`. `scripts/bump_version.py`. Needs the keystore secrets; CI debug APKs cannot update (random debug key). Open: first CI run; first real update test with two builds; whether the Release asset `url` download works with a fine-grained token.

## 16. EdgeTAM: NPU + real memory tracker (2026-11-15, never compiled/run: no Kotlin, torch or phone in that session)
Done from the two-stage recommendation (user: "do these").

**Stage 1 — QNN/HTP for EdgeTAM, CPU fallback, per-model benchmark**
- `app/.../QnnSessions.kt` (NEW, shared): `Qnn.npuSession/cpuSession/open`, class `OrtModel` (provider, `fallback` reason, `loadMs`; **`run` switches to CPU for good if the NPU session fails at run time**). The QNN context cache name now carries the model file's size+mtime (`<model>_<sig>_htp_v75.onnx`), stale contexts are deleted, so re-exported models never reuse an old compile (the old "clear app data after changing the model" rule is gone). `PoseRunner` delegates to it (same options as before).
- `scripts/export_sam2.py` rewritten: all graphs **static shape** (HTP needs it), float32 I/O. The old dynamic-N decoder is now `sam-decoder.onnx` with 3 prompt slots (box, box, point; unused = label -1) and the exact per-frame no-prompt decoder `sam-decoder-trk.onnx` (1 slot). Both also output `obj_ptrs` and `object_score`.
- `SamEngine` (sam flavor): every graph opens on the NPU unless the user turns it off (`SamEngine.npuEnabled`, switch on the bench screen); `lastRun` records provider + ms per graph of the last scan.
- Benchmark: bench screen -> "EdgeTAM benchmark" (`SamBenchSection.kt`, `SamEngine.bench`): every present graph on CPU and NPU (mean/p95, load/compile ms, NPU note), thermal status, and an accuracy check (encoder+decoder on CPU vs NPU: image-embed cosine and mask IoU). "Copy" gives the text for "Bench lines".

**Stage 2 — proper EdgeTAM**
- Five graphs: `sam-encoder` (+ raw `image_feat` output), `sam-decoder`, `sam-decoder-trk`, `sam-memenc`, `sam-memattn`. The two memory graphs wrap the model's OWN `_encode_new_memory` / `_prepare_memory_conditioned_features` (so EdgeTAM's perceiver/RoPE variants come along). RoPE is rewritten real-valued for ONNX; the script checks that rewrite against the original and every ONNX file against PyTorch (onnxruntime) and **stops on a mismatch**. If the memory export fails, CI re-runs with `--no-memory` (encoder+decoders ship, app tracks per frame as before).
- Slot layout (static, baked temporal codes): `mem_feats[K]`/`ptrs[P]` slot 0 = prompted frame, slot d = d tracked steps back; missing history is filled with the prompted entry. K = `num_maskmem` (7), P = 16. Logic: `core/MemoryBank.kt` (+ `MemoryBankTest`), Python reference `tracker/edgetam_bank.py` (+ `tests/test_edgetam_bank.py`, run green here).
- Tracker (`SamEngine.walkMemory`): prompted frame -> `sam-decoder` (best candidate by the old "smallest almost-as-confident mask" rule) -> `sam-memenc` -> bank; every later frame -> `sam-encoder` -> `sam-memattn` -> `sam-decoder-trk` (no prompt) -> accept if object score > 0 and conf >= 0.35 -> `sam-memenc` -> bank. Only tracked frames enter the bank. Forward and backward from the pick, 6 fps. **Masks are carried by memory** (not re-derived from a box guess); rows are `[cx, cy, conf, w, h]` from the mask (region targets use w,h; free-point targets ignore them as before). Fallback chain: memory tracker -> prompt-per-frame tracker (old design) -> optical flow.
- INT8: `scripts/quantize_edgetam.py` quantizes **only the image encoder** (QDQ, calibration = `android/calib`), with an accuracy gate (mean cosine per output over 12 frames >= 0.98, else exit 3 and the fp16 encoder stays). CI step is optional. Decoders/memory graphs stay fp16 (attention + pointers are accuracy-sensitive and small).
- Tests added: `tests/test_edgetam_{bank,quant,contract}.py` (contract = graph names, tensor names, decoder slot counts, pose-stub API, CI wiring).

**Known gaps / what needs the phone or CI (nothing here has run)**
1. First CI run: paste the export log. Likeliest failures: EdgeTAM's method signatures differ from SAM 2 (`_prepare_memory_conditioned_features`, `_encode_new_memory`), another RoPE function name than `apply_rotary_enc` (the RoPE parity check says so), `torch.onnx.export` on the memory graphs, checkpoint URLs.
2. HTP may not compile `sam-memattn` (7 x 4096 memory tokens, large attention): then only that graph falls back to CPU (shown in the bench). Measure before deciding.
3. Differences from SAM 2: pointer of the prompted frame is always used (SAM 2 drops it after 16 frames); padded slots repeat the prompted entry; a single tap at the prompted frame uses the 3-slot decoder with two unused slots (SAM 2 would have 1 point); lost frames add nothing to memory (SAM 2 stores a no-object memory).
4. Not done: persisting masks to disk (they live only inside the tracker), a UI preview of the mask, int8 for decoder/memory graphs, `htp_graph_finalization_optimization_mode` and VTCM options (only the four proven option names are used).
5. Bench lines to fill: per graph CPU vs NPU, fp16 vs int8 encoder, memory tracker x-realtime (`lastRun`) vs prompt tracker, NPU-vs-CPU mask IoU.


## CI düzeltmesi (2026-11-15)
- `android.yml` → android-sam işi EdgeTAM dışa aktarımında `ModuleNotFoundError: timm` ile düşüyordu (EdgeTAM `sam2/modeling/backbones/timm.py` `timm` ister; editable kurulum bağımlılığı çekmiyor). `pip install ... timm -e ...` ile giderildi. android-pose işi zaten yeşildi.
- `scripts/export_sam2.py`: ONNX dışa aktarımı sonrası sarmalayıcı train moduna dönüyor (torch.onnx.export orijinal modu geri yükler) → BatchNorm batch istatistiği kullanıp encoder kontrolünü bozuyordu (`sam-encoder.onnx output 0 differs from PyTorch (1.75)`). `export()` ve `ort_check()` içinde `model.eval(); sam.eval()` eklendi. Henüz CI ile doğrulanmadı.
- `export_sam2.py` ikinci tur: (1) RoPE gerçek-değerli yeniden yazımda `repeat_interleave` → döşeme (`repeat`), orijinal SAM2 `expand+flatten` ile aynı; yanlıştı, bellek grafiği sessizce `--no-memory` yedeğine düşecekti. (2) `ort_check` tüm çıktıları basıp sonra hata verir. (3) `use_obj_ptrs_in_encoder` kapalıysa `ptrs` girişi ONNX grafiğinden düşmesin diye `0*ptrs.sum()` bağlandı. (4) RoPE yaması 0 fonksiyon yamalarsa hata. Doğrulanmadı: CI çalışmadı; sam flavor Kotlin (SamEngine.kt) ve Gradle adımları (7+) hiç derlenmedi.
- `ScanScreen.kt`: poz modeli seçicisi `sam-*.onnx` dosyalarını da listeliyordu (telefona elle yüklenenler `files/models` içinden geliyor); seçilirse tarama bozulurdu. `sam-` önekli dosyalar süzüldü. (Ekran görüntüsü 2026-11-15)
- 2026-11-15 **Seçim ekranı sadeleştirildi (PickScreen.kt)**: üstte uygulama sürümü + "Akıllı nesne takibi / Vücut bölgesi algılama" durum satırları (düz dille); iki büyük düğme "Kişi / vücut bölgesi" ve "Herhangi bir nesne". Nesne modunda (sam build) kare bir kez kodlanır (`SamEngine.encodeFrame`, CPU), dokunuşta `SamEngine.segmentAt` nesneyi renkli alan + kutu ile gösterir; takip aynı istem/aday kuralını kullanır (görünen = izlenen). Model/fps/NPU/canlı önizleme/tam eklem/yakınlaştırma "Gelişmiş ayarlar" altına alındı; `sam-*` dosyaları poz modeli listesinden süzüldü (PickScreen + ScanScreen). Yeni: `SamPreviewTypes.kt`; `pose` stub aynı imzalar (null). Yeni dizeler en + tr. Derlenmedi/test edilmedi.
- 2026-11-15 **Çökme (Önizleme sonucu)**: `ResultScreen` açılınca `stab` varsayılan true olduğundan tüm karelerde 4K `DenseCamera.analyze` hemen başlıyordu; log yalnız ilk 4K kareyi gösterip bitiyor, Java çökme satırı yok (yerel çökme/öldürülme şüphesi, kanıtlanmadı). `stab` varsayılanı false (isteğe bağlı). `DenseCamera` iz kırıntıları (`dense frame N`, `dense-analyze` hatası) eklendi: bir sonraki çökmede trace nerede kaldığını gösterir. Ayrıca poz modeli olarak sam-* seçilmesi (log: `image_embed ... Expected: 256`) süzme ile giderildi.
