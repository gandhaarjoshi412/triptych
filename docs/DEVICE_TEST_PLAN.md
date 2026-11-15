# Device Test Plan — Nothing Phone (2a) Plus

Nothing counts as "working" until it passes here on the real phone. Record results in a copy of this file (for example `docs/results/YYYY-MM-DD.md`).

Useful commands while testing:
```bash
adb logcat | grep "MCS/"                                   # all app logs (tags MCS/Engine, MCS/VideoEncoder, …)
adb shell dumpsys thermalservice | head -40                # thermal status + temperatures
adb shell cmd thermalservice override-status 3             # SIMULATE thermal SEVERE (0..6); `reset` to undo
adb shell cmd thermalservice reset
adb shell dumpsys jobscheduler | grep -A3 multicamstudio   # WorkManager / user-initiated jobs
adb shell top -H -p $(adb shell pidof com.multicamstudio)  # per-thread CPU (enc-*, camera, preview, master-audio)
adb exec-out run-as com.multicamstudio tar c files/recordings > recordings.tar   # pull originals (debug build)
ffprobe -v error -select_streams v:0 -count_frames \
  -show_entries stream=codec_name,width,height,avg_frame_rate,nb_read_frames,duration -of default=nw=1 front_001.mp4
```

---

## 0. Device facts
Run the commands in HANDOFF §2.3 and record the Android release, API level, Nothing OS version, firmware build, model, RAM and free storage.

## 1. Camera feasibility (spec §4.2)
1. Settings → Diagnostics → **Capability report**. Save and share it.
   - Check the **role mapping**: is FRONT the selfie camera, REAR_MAIN the 50 MP main, REAR_WIDE the ultra-wide? If not, set Settings → Camera mapping.
   - Record `getConcurrentCameraIds`, the logical/physical camera layout, the sensor sync type, and the query results for architectures A and B.
2. **Report + live 3-camera test**. Record each candidate's measured fps.
3. Write down the verdict line: *Three-camera 1080p30: SUPPORTED / PARTIAL / NOT SUPPORTED*.

## 2. Encoder capacity (spec §4.3)
1. Run **3× HEVC encoder 60 s** and record per-stream output fps and errors.
2. Run **3× HEVC encoder 15 min** with the phone on a desk at room temperature. Record fps over time (from logcat progress) and the maximum thermal status.
3. If HEVC can't sustain the load, run **3× H.264 encoder 60 s** for comparison. The app never switches codec on its own.

## 3. Sanity recording (1 min)
1. On the Record screen, all three tiles show a preview. Press REC, wait 60 s, press STOP.
2. Sessions → open the session. Each camera should have 1 segment, about 1,800 frames, close to 0 drops, and an average fps of about 30.
3. Pull the files and run `ffprobe` on each MP4: HEVC, 1920×1080, ~30 fps, ~60 s. Play the M4A.
4. Check `metadata/session_manifest.json`:
   - `cameras.*.timestampClock` should be `REALTIME` (or `MONOTONIC`, mapped).
   - `firstFrameSessionMs` should be a few hundred ms at most.
   - `audio.firstSampleSessionMs` should be present and `timestampSource` should be `AudioTimestamp(BOOTTIME)`.
5. Orientation: holding the phone in portrait, the videos should play upright.

## 4. Sustained 15-minute recording, screen off (spec §4.3, §9.3)
1. Phone at room temperature, not charging. Press REC and **lock the screen immediately**.
2. After 15 min, unlock and press STOP.
3. Expect 3 segments per camera (5 + 5 + 5 min) with no `.inprogress` files left.
4. Record frame counts, dropped frames, avg fps per camera, `maxPairwisePhaseMs`, audio `driftPpm` / `accumulatedDriftMs`, any THERMAL or RESOLUTION events, and file sizes.
5. Repeat while switching apps (Chrome, camera, YouTube) during the recording.
6. If recording stopped while the screen was off: Settings → Apps → MultiCam Studio → Battery → *Unrestricted*, then retest. Document whether Nothing OS needs this.

## 5. Synchronisation (spec §6.2, §7) — target under 50 ms
1. Set up a slate: a mirror so the front camera sees the scene, and a sharp hand clap visible to main and wide.
2. Record 30 s, clap 3 times (start, middle, end). For the 15-minute drift check, clap at minutes 0, 7 and 15 as well.
3. In your editor, place each segment at its `segments[].startSessionMs` and the audio at `audio.firstSampleSessionMs`.
4. Measure the clap offset between each video and the audio waveform. **Pass:** under 50 ms (1.5 frames) at every clap, and no growth over 15 minutes.
5. If it fails, record the offsets: a constant offset points to a timestamp-base problem, a growing one to drift.

## 6. Thermal management (spec §10)
1. **Simulated:** while recording, run `adb shell cmd thermalservice override-status 3`.
   - After about 20 s, all cameras should switch to **720p30**. Expect a new segment, a RESOLUTION event, and the "Recording (degraded)" chip.
   - Keep the override. After a further ~60–80 s the **camera decision dialog** should appear. Choose "Keep main + front": the ultra-wide stops and the others continue.
   - Repeat with the app in the background. Expect a high-priority notification, and after the timeout the priority policy applies automatically.
   - Run `override-status 4` (CRITICAL). Recording should **stop safely** with the files finalized and the stop reason "Thermal emergency".
   - Run `adb shell cmd thermalservice reset`.
2. **Real:** record in a warm environment or while fast-charging until degradation happens naturally. Record the time to each stage.

## 7. Failure and recovery (spec §21)
| Test | How | Expected |
|---|---|---|
| Force-stop mid-recording | `adb shell am force-stop com.multicamstudio` at about 7 min | On relaunch the session is `interrupted`; segments 1 (0–5 min) are playable; the in-progress segment is kept as `.inprogress`, marked not playable, and not uploaded; recording does **not** restart |
| Reboot mid-recording | hold power → restart | same as above |
| Camera taken by another app | start recording, then open the system camera app | the affected camera(s) show DISCONNECTED, the others continue, one re-open attempt is made |
| Low storage | Settings → Storage → "Stop below 3 GB" with a nearly full phone | warning, then a safe stop with the reason "Storage nearly full" |
| Mic route change | plug in or unplug a USB-C mic during recording | AUDIO_ROUTE events in the session; audio continues |
| Microphone denied | revoke the mic permission, record | video records; audio shows "Microphone permission not granted" |

## 8. Google Drive (spec §11–13)
1. Connect in Settings. The account email appears.
2. **Auto upload:** finish a session, go to the home screen, and wait at least 5 min.
   - The upload starts (notification or Uploads tab after reopening).
   - Return to the app mid-upload: it **pauses** within one chunk, and progress is kept.
   - Leave again: after 5 more minutes it resumes from the saved byte offset, not from zero.
3. **Network loss:** toggle airplane mode mid-file. It retries with backoff and resumes where it stopped.
4. **Kill mid-upload:** force-stop. On the next eligible run it resumes using the encrypted session URI.
5. **Manual upload:** select 2 sessions → Upload while the app is open, on mobile data. On API 34+ the system shows a user-initiated transfer notification.
6. **Verification:** in Drive, check the layout `MultiCam Studio/<date>/<session>/{front,rear_main,rear_wide,audio,metadata}` and that the file sizes match. In the app, every task should be `verified` and the session `Backed up`.
7. **Auth expiry:** revoke access at myaccount.google.com/permissions and trigger an upload. You get the "Google Drive needs reconnecting" notification; reconnect and it continues.
8. **Thermal pause:** `override-status 2` during an auto upload pauses it (default threshold MODERATE).
9. **During recording:** start recording while an upload runs. The upload pauses within one chunk.

## 9. Local deletion safeguards (spec §15)
1. With one asset still `pending`, "Delete local copies" is not offered (or the dialog shows "Not eligible").
2. With everything backed up: "Delete local copies" shows the file count and size; confirm. The local MP4/M4A files are gone, the manifest is kept, the session shows *Cloud only*, and the **Drive files are untouched**.
3. Bulk: select 3 backed-up sessions and delete. You get one summary and one confirmation.
4. Re-verification: delete one file from Drive by hand, then try to delete local copies. It is refused and that asset is re-queued.

## 10. Rename (spec §14.3)
Rename a backed-up session. The local name changes immediately, the session ID and folder stay the same, the Drive session folder is renamed on the next upload run, and the manifest is re-uploaded in place. Test with Drive disconnected: you get the error text "Drive rename failed … (local name kept)".

## 11. Bitrate tuning (spec §4.6)
For each preset (Storage saver, Balanced, Auto, High), record 2 min of a detailed, moving scene and capture:

| Preset | Mbps/stream (manifest) | MB/min/stream | Drops | Max thermal | Visual notes |
|---|---|---|---|---|---|

Then choose final AUTO values and update `QualityPreset.bitsPerPixelFrame` in `domain/model/Models.kt`.

## 12. Performance
- While recording, the UI should stay responsive. Check jank in Android Studio Profiler → System Trace.
- Per-thread CPU via `top -H`: `enc-*`, `camera`, `preview`, `master-audio`. Turn preview off and compare.
- Memory: Profiler heap stays flat over 15 min (preview bitmaps are small and short-lived).
