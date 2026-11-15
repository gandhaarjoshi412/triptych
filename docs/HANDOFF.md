# MultiCam Studio — Handoff

This is the document to pick up the project on your laptop. Read sections 1–3 before anything else.

---

## 1. Status in one minute

| Area | State |
|---|---|
| Source code for the full app (camera engine, audio, sync, thermal, storage, Room, Drive upload, UI, services, diagnostics) | **Written** – 51 Kotlin files, about 8,100 lines (including tests) |
| Pure domain logic (sync math, frame-drop counting, thermal policy, upload eligibility, backup verification, naming, manifest JSON) | **Compiled and unit-tested: 31/31 JVM tests pass** |
| Android-dependent code (Camera2, MediaCodec, Compose, Room, WorkManager, Play Services) | **NOT compiled yet.** The build container could not reach Google's Maven/SDK servers, so expect a round of small compile fixes in Android Studio (see §3.2). |
| On-device behaviour (3-camera feasibility, 30 fps, thermals, sync accuracy, uploads) | **NOT tested.** No device was available. Run `docs/DEVICE_TEST_PLAN.md`. |
| Instrumentation tests (Room DAO, Compose dialog) | Written, not run |

Nothing in this repo claims that three-camera 1080p30 works on the Nothing Phone (2a) Plus. The **Diagnostics** screen exists to find that out on the real device, and it states the answer explicitly.

---

## 2. Set up on your laptop

### 2.1 Tools
- **Android Studio**, latest stable (any version that supports AGP 8.12 and compileSdk 36).
- JDK 17 or newer (Android Studio's bundled JBR is fine).
- In the SDK Manager, install **Android SDK Platform 36** and the latest **Build-Tools**.

### 2.2 Open and build
```bash
git clone <this repo> && cd triptych
./gradlew :app:assembleDebug        # or open the folder in Android Studio and Sync
```
The versions in `gradle/libs.versions.toml` are stable releases that I picked without being able to resolve them online:
AGP 8.12.0, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2025.07.00, Room 2.7.2, WorkManager 2.10.3, play-services-auth 21.3.0, OkHttp 4.12.0.
I did confirm Kotlin, KSP, coroutines and serialization against Maven Central. If any Google artifact fails to resolve, bump it in the TOML. Android Studio's *Upgrade Assistant* and the version-catalog lint quick-fixes do this in one click.

### 2.3 Phone
1. On the phone, enable developer options (Settings → About phone → tap *Build number* 7 times), then turn on *USB debugging*.
2. Record the real device facts the spec asks for:
   ```bash
   adb shell getprop ro.build.version.release      # Android release
   adb shell getprop ro.build.version.sdk          # API level
   adb shell getprop ro.build.display.id           # firmware build
   adb shell getprop | grep -i -E "nothing|nt\."   # Nothing OS version properties
   adb shell getprop ro.product.model
   adb shell cat /proc/meminfo | head -3
   adb shell df -h /data
   adb shell dumpsys media.camera | head -200      # camera ids and characteristics
   ```
   The in-app **Settings → Diagnostics → Capability report** collects the same information plus camera and encoder details, and you can share it as a text file.

### 2.4 Google Drive (one-time Google Cloud setup)
The app uses Google Identity Services `AuthorizationClient` with the **`drive.file`** scope only, which means it can see only files it created. There is **no client secret** in the app.

1. At https://console.cloud.google.com create a project, for example "MultiCam Studio".
2. Go to **APIs & Services → Library**, find **Google Drive API**, and enable it.
3. Go to **OAuth consent screen**: choose External, add your own Google account as a test user, and add the scope `.../auth/drive.file`.
   - `drive.file` is a *non-sensitive* scope, so Google verification is not required.
   - While the app's publishing status is **Testing**, Google expires the grant after 7 days, so you'd have to tap *Reconnect* weekly. Switching the consent screen to **In production** (unverified is fine for personal use) avoids that.
4. Go to **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `com.multicamstudio`
   - SHA-1: run `./gradlew signingReport` and copy the SHA-1 of the variant you install (debug keystore for debug builds).
5. In the app, open Settings → Google Drive → **Connect**.

If sign-in fails with error code 10 / `DEVELOPER_ERROR`, the package name or SHA-1 doesn't match the OAuth client.

---

## 3. What to do first (in order)

1. **Get it compiling** (§3.2), then install the debug build.
2. **Run Diagnostics** on the phone: *Capability report*, then *Report + live 3-camera test*, then *3× HEVC encoder 60 s*. Share the reports. This answers the hardware feasibility question from spec §4.2/§4.3.
3. Make a 1-minute recording and confirm that the files play and `metadata/session_manifest.json` looks right (DEVICE_TEST_PLAN §3).
4. Work through the rest of `docs/DEVICE_TEST_PLAN.md`, starting with the 15-minute screen-off run and the clap sync test.

### 3.2 Likely compile hot-spots
Everything was written against public APIs, but none of it has gone through `kotlinc` against the Android SDK yet. If something fails, these are the most likely places:
- `core/camera/TopologyResolver.kt` → `CameraManager.isCameraDeviceSetupSupported` / `getCameraDeviceSetup` (API 35). Already wrapped in try/catch; delete that branch if the signatures differ.
- `core/camera/CameraSessions.kt` → `OutputConfiguration.setTimestampBase` (API 33) and `SessionConfiguration.sessionParameters`.
- `core/media/EncoderCatalog.kt` → `MediaCodecInfo.VideoCapabilities.PerformancePoint(...)` constructor/`covers`.
- `services/upload/UploadWorkers.kt` → `JobService.setNotification(...)` (API 34).
- `core/cloud/GoogleAuthManager.kt` → `AuthorizationResult`, `GoogleAuthUtil.clearToken`.
- Compose: material3/foundation experimental opt-ins (`FlowRow`, `combinedClickable`) might need `@OptIn` adjustments depending on the BOM.
- Room/KSP: entity/DAO annotation errors appear at KSP time and the messages are clear.

---

## 4. Architecture

```
UI (Compose)  ──commands──▶  RecordingEngine  ◀── SystemHealthMonitor (thermal/memory)
   │   ▲                        │  ├─ CameraDeviceHandle ×N (Camera2, one per CameraDevice)
   │   └──── StateFlow ─────────┤  ├─ VideoEncoderPipeline ×3 (HEVC → segmented MP4, own thread each)
   │                            │  ├─ MasterAudioRecorder (AudioRecord → AAC → M4A, own thread)
   │                            │  ├─ PreviewHub (small YUV readers, 5 fps, self-throttling)
   │                            │  └─ ThermalPolicy / StoragePolicy / SegmentPlanner (pure domain)
   │                            ▼
   │                      Room (source of truth) + session_manifest.json
   │                            ▲
   └──▶ SessionRepository ──────┤
        UploadScheduler ──▶ AutoUploadWorker / ManualUploadJobService(UIDT) ──▶ UploadEngine ──▶ DriveClient (OkHttp)
```

Key files:

| Concern | File |
|---|---|
| Camera discovery, role mapping (front / main / ultra-wide by FOV and pixel count), candidate topologies | `core/camera/CameraInventory.kt` |
| Choose a topology using framework queries (`getConcurrentCameraIds`, `isConcurrentSessionConfigurationSupported`, CameraDeviceSetup) | `core/camera/TopologyResolver.kt` |
| Open devices, build `SessionConfiguration` + `OutputConfiguration` (physical ids, REALTIME timestamp base) | `core/camera/CameraSessions.kt` |
| Diagnostic report + live frame-rate test per candidate | `core/camera/CameraDiagnostics.kt` |
| HEVC encoder → segmented MP4 (key-frame-aligned rolls, `.inprogress` → `.mp4` rename) | `core/media/VideoEncoderPipeline.kt` |
| Three-encoder synthetic stress test | `core/media/EncoderStressTest.kt` |
| Master audio with AudioTimestamp-based sync + drift measurement | `core/audio/MasterAudioRecorder.kt` |
| Session orchestration, thermal stages, disconnect handling, manifest | `core/recording/RecordingEngine.kt` |
| Crash/force-stop recovery at next launch | `core/recording/RecoveryManager.kt` |
| Drive auth / REST / folder ids / resumable verified upload | `core/cloud/*` |
| Local deletion safeguards, rename | `data/repo/SessionRepository.kt` |
| Auto (WorkManager) / manual (UIDT) scheduling, app-visibility tracking | `services/upload/*` |
| Foreground recording service + notification | `services/RecordingForegroundService.kt`, `services/Notifications.kt` |

### 4.1 Decisions and reasons

- **Two capture architectures, chosen by the framework, not by trial and error.**
  - A: three independent `CameraDevice`s in one `getConcurrentCameraIds()` set.
  - B: the logical rear multi-camera, with physical outputs for main and ultra-wide (`OutputConfiguration.setPhysicalCameraId`), plus the front camera, which must be concurrent with the logical camera.

  The resolver asks `isConcurrentSessionConfigurationSupported` with encoder-class stand-in surfaces (PRIVATE + `USAGE_VIDEO_ENCODE` ImageReaders), with and without preview. It prefers more cameras first, then verified over unknown, then with preview. Partial fallbacks: main+wide only, front+main, main only.

  Realistically, B is the likeliest path to three cameras on a mid-range MediaTek phone, and neither is guaranteed. The live diagnostic test decides.
- **One session clock = `elapsedRealtimeNanos` (CLOCK_BOOTTIME).**
  - On API 33+, encoder surfaces request `TIMESTAMP_BASE_REALTIME`.
  - Each pipeline also detects the actual clock base of its first frame (`ClockBaseDetector`) and maps MONOTONIC stamps through a measured offset.
  - Audio uses `AudioRecord.getTimestamp(TIMEBASE_BOOTTIME)` to place sample 0 on the same timeline, and records the drift between sample count and hardware clock (ppm and accumulated ms).
  - All segment and audio start times are written to the manifest. You align on the editor timeline using `segments[].startSessionMs` and `audio.firstSampleSessionMs`.
  - The worst pairwise *frame phase* between independently clocked sensors is measured (`maxPairwisePhaseMs`, bounded by about 16.7 ms at 30 fps).
- **Segments roll on key frames**, without stopping the camera. Every 5 minutes (configurable) all encoders receive a sync-frame request and each one switches muxers on its next key frame, so boundaries line up within about 1 frame.
  - Incomplete files keep the `.inprogress` suffix and are never shown as finished, never uploaded, and never deleted.
- **No fake frames.** `KEY_REPEAT_PREVIOUS_FRAME_AFTER` is never set. FPS and drops come from real encoder-output timestamps. A gap is counted as dropped frames, and PTS gaps stay in the file as real gaps.
- **Thermal strategy:**
  1. Normal: 1080p30.
  2. Sustained (20 s) SEVERE status, headroom ≥ 0.95, or ≥ 5% drops: all cameras go to **720p30**. Pipelines are rebuilt, a new segment starts, and the change is recorded in the manifest.
  3. Still overloaded after a 60 s cooldown: **camera decision dialog**, or a high-priority notification if the app isn't visible. If no answer comes within the timeout (90 s by default), the configured camera priority drops the lowest-priority camera.
  4. CRITICAL or above: **safe stop**. Every file is finalized and the reason is recorded.

  Frame rate is never reduced, and resolution never goes below 720p.
- **The preview is decoupled.** Small YUV ImageReaders (about 320 px wide) are converted at 5 fps on a background-priority thread. If conversion slows, the rate halves; if it gets slow enough, preview pauses for 10 s. If a device can't add preview streams, recording runs without them.
- **Background recording** runs in a `camera|microphone` foreground service. It is started only from the visible Record screen (Android's while-in-use rules), holds no wake lock, does not restart itself (`START_NOT_STICKY`), and does not resume after force-stop or reboot.
- **Uploads:**
  - Sequential, with 8 MiB resumable chunks.
  - Progress and an **AES-GCM Android-Keystore-encrypted** session URI are saved after every chunk.
  - Access tokens are never stored; Play services is asked for one each run.
  - An asset is `VERIFIED` only when Drive metadata (re-fetched, not taken from the upload response) matches local size, MD5 and parent folder ID.
  - Folders are tracked by Drive ID and re-validated once per process.
- **Automatic upload** is a WorkManager unique job:
  - Enqueued with an initial delay of 5 minutes each time the app is backgrounded, and cancelled when it returns to the foreground. The gate is also re-checked before every chunk, so it pauses within one chunk.
  - Requires network (any type) only: no charging, no unmetered, no device-idle requirement.
  - Pauses at or above the configured thermal status (default MODERATE), on system low memory, or below the configured free-memory percentage.
  - Each run is time-boxed to 8 minutes, then retried with linear backoff, to stay within WorkManager limits.
- **Manual upload:**
  - API 34+: a **User-Initiated Data Transfer job**.
  - API 30–33: an expedited WorkManager worker running as a dataSync foreground worker.
  - Skips the background rule, but never runs while recording and pauses at SEVERE thermal status.
- **Delete local copies:**
  - Enabled only when every segment of every camera, the audio and the manifest are `VERIFIED`.
  - On confirmation it re-checks every asset against **live** Drive metadata, deletes only local media (it keeps the DB record and the small manifest), and marks the session `CLOUD_ONLY`.
  - No Drive delete endpoint exists anywhere in `DriveClient`.

---

## 5. Requirements traceability

Legend: ✅ implemented (pending device verification) · 🟡 partial / simplified · ❌ not done

| Spec § | Requirement | Status / notes |
|---|---|---|
| 1 | Discover OS/API/firmware/model/RAM/storage/cameras/encoders at runtime | ✅ Diagnostics report + ADB commands above |
| 2 | Native Kotlin, Camera2, MediaCodec/Muxer, AudioRecord, Compose/M3, Room, DataStore, FGS, WorkManager, UIDT, GIS auth, Drive v3, OkHttp | ✅ |
| 2 | Macrobenchmark | ❌ not added; profiling steps are in the test plan |
| 4.1 | 3 × 1080p30 HEVC MP4, hardware encoders, independent pipelines | ✅ never falls back to software silently; H.264 only if selected explicitly |
| 4.2 | Feasibility investigation and report, architectures A and B, explicit verdict | ✅ `CameraDiagnostics` (static + live) |
| 4.3 | Encoder inventory, performance points, 3-encoder stress test | ✅ 60 s and 15 min variants |
| 4.4 | Per-camera state, independent failure | ✅ device loss/encoder failure affects only that camera; one re-open attempt |
| 4.5 | Fixed 30 fps, no frame duplication, true drop stats | ✅ |
| 4.6 | Automatic bitrate (VBR), recorded in manifest | 🟡 starting estimates (HEVC 1080p30 ≈ 8.1 Mbps on AUTO); tune on device |
| 5 | Minimal previews, independent of recording, self-degrading | ✅ |
| 5.1 | Recording monitor with real telemetry and the listed states | ✅ |
| 6 | One master audio, 48 kHz mono AAC/M4A, routing changes logged | ✅ 160 kbps |
| 6.2/7 | Monotonic session clock, AudioTimestamp, sensor/encoder PTS, drift measurement | ✅ measured; under 50 ms must be **proven** with the clap test |
| 7.3 | Versioned `session_manifest.json` with real measurements | ✅ schemaVersion 1, unknown keys ignored |
| 8 | Storage layout, 5-min segments, never expose incomplete MP4 | ✅ |
| 8.2 | Recover incomplete segment "where possible" | 🟡 MediaMuxer writes the MP4 index only on `stop()`, so an interrupted segment is normally unrecoverable. It is probed with MediaExtractor, kept on disk, and never claimed recoverable. Future: fragmented-MP4 writer. |
| 8.3 | Timestamp naming, rename without changing ID | ✅ |
| 8.4 | No automatic deletion; low-storage warning and safe stop | ✅ warn at 5 GB, stop at 2 GB (configurable) |
| 9 | FGS camera/microphone, notification with stop action, screen-off, no wake lock/accessibility hacks, no auto-restart | ✅ Nothing OS battery behaviour must be tested |
| 10 | Thermal stages 1080 → 720 → camera choice → critical stop; priority setting | ✅ |
| 11.1 | One-time auth, drive.file, disconnect/reconnect, no secrets, no plaintext tokens | ✅ |
| 11.1 | Folder selection via an officially supported picker | 🟡 There is no first-party Android Drive folder picker that grants `drive.file` to an existing folder, so the app creates its own root folder (name configurable) and you can move or rename it in Drive afterwards; the ID keeps working. A broader scope was deliberately **not** requested. |
| 11.2 | Date → Session → Camera folders, tracked by ID | ✅ |
| 11.3 | Resumable upload, persisted progress, retries, MD5/size/parent verification | ✅ |
| 12 | Auto-upload policy (5 min background, any network, pause on foreground or resource pressure) | ✅ timing is "at least", not exact |
| 13 | Manual upload (all or selected, while using the app, UIDT on 34+) | ✅ |
| 14 | Sessions screen (search, date filter, multi-select, upload, delete-local), details, rename (including Drive folder) | ✅ |
| 14.2 | Thumbnails | ❌ the folder is created but no thumbnails are generated yet |
| 15 | Delete-local safeguards, bulk delete, CLOUD_ONLY | ✅ |
| 16 | Performance monitor (fps, drops, errors, thermal, memory, storage, own CPU time) | ✅ CPU of other apps is not observable (platform limit, documented in the UI) |
| 17 | Record / Sessions / Uploads / Settings tabs | ✅ |
| 18 | Room schema with relations, indexes, transactions | ✅ v1; exported schema in `app/schemas/` after the first build |
| 20 | Future AI editing: originals immutable, `derivedAssets` reserved in manifest | ✅ design only |
| 21 | Failure handling (process death, disconnects, encoder/audio failure, upload interruptions, auth expiry, low memory, thermal) | ✅ in code; needs the device test pass |

---

## 6. Known limitations and risks (read before trusting a shoot to it)

1. **Unverified hardware capability.** Three simultaneous cameras may simply not be exposed on this phone. If Diagnostics says NOT SUPPORTED, the app falls back automatically (main+wide, front+main, or main only) and shows which in the Record screen's "Capture mode".
2. **Single master audio file.** As the spec requires, it is one M4A for the whole session, so a crash during recording loses that session's audio (the video segments survive). A possible improvement is segmenting audio alongside video.
3. **A preview session may be refused** when the device can't add preview streams. Recording then continues without preview, which is intended.
4. **Release build** has R8 enabled and has never been built. Use **debug** builds until R8 rules are confirmed.
5. **Bitrate values** are estimates. Use the matrix in the test plan to choose final AUTO values.
6. **Duplicate Drive files** are possible if a completed upload fails verification. The bad remote file is left untouched (the app never deletes from Drive) and the asset is uploaded again.
7. **Auto-upload start time** is "at least 5 minutes"; Android decides the exact time.
8. **No ViewModels.** The screens read the `AppContainer` directly. That's fine at this size; introduce ViewModels if the screens grow.

## 7. Suggested next steps
1. Compile, install, run Diagnostics, and share the report. Adjust the role mapping with *Settings → Camera mapping* if the auto-detection picks the wrong lens.
2. Run the 15-minute screen-off recording and the clap sync test, then tune bitrates.
3. Set up OAuth, then test auto/manual uploads, interruption and resume, and delete-local.
4. Add thumbnails (`MediaMetadataRetriever.getScaledFrameAtTime` on the first segment, written to `thumbnails/`).
5. Optional: an FCPXML/EDL exporter that turns the manifest into a ready-made multicam clip for your editor.
