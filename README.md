# MultiCam Studio

A native Android app (Kotlin, Camera2, MediaCodec, Jetpack Compose) that records the **front**, **rear main** and **rear ultra-wide** cameras at the same time. Each camera goes to its own H.265 MP4 file, alongside one master AAC audio file, and everything shares one monotonic session clock. Recording continues when the app is in the background or the screen is off. Finished sessions are backed up to **Google Drive** with resumable uploads that are verified by checksum.

Primary target device: Nothing Phone (2a) Plus (Dimensity 7350 Pro, Android 16 / Nothing OS 4.x expected). Personal use.

> **Start here:** [`docs/HANDOFF.md`](docs/HANDOFF.md) covers project status, laptop setup, the Google Cloud OAuth setup, the architecture, what is verified and what isn't, and next steps.
> Device validation protocol: [`docs/DEVICE_TEST_PLAN.md`](docs/DEVICE_TEST_PLAN.md).

## Build

```bash
# JDK 17+ and Android SDK (platform 36) required; Android Studio recommended.
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest          # JVM unit tests (domain logic)
./gradlew :app:connectedDebugAndroidTest  # Room + Compose tests on a device
```

## Layout

```
app/src/main/java/com/multicamstudio/
  domain/      Pure Kotlin rules (no Android): timing/sync math, thermal policy, upload eligibility,
               backup verification, naming, manifest schema. Fully unit tested.
  core/        camera/ (inventory, topology, sessions, preview, diagnostics), media/ (HEVC pipeline,
               encoder catalog, stress test), audio/, recording/ (engine, recovery), thermal/, storage/, cloud/
  data/        Room database (source of truth), DataStore settings, session repository
  services/    Foreground recording service, upload workers / user-initiated transfer job, notifications
  features/    Compose screens: Record, Sessions (+detail), Uploads, Settings, Diagnostics
```
