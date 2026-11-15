package com.multicamstudio.domain

import com.multicamstudio.domain.manifest.AppInfo
import com.multicamstudio.domain.manifest.CameraTrack
import com.multicamstudio.domain.manifest.DeviceInfo
import com.multicamstudio.domain.manifest.SessionManifest
import com.multicamstudio.domain.manifest.SyncInfo
import com.multicamstudio.domain.model.AssetType
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.QualityPreset
import com.multicamstudio.domain.model.ThermalStatus
import com.multicamstudio.domain.model.UploadState
import com.multicamstudio.domain.model.VideoCodecChoice
import com.multicamstudio.domain.model.VideoResolution
import com.multicamstudio.domain.session.BitratePolicy
import com.multicamstudio.domain.session.SegmentPlanner
import com.multicamstudio.domain.session.SessionNaming
import com.multicamstudio.domain.session.StoragePolicy
import com.multicamstudio.domain.thermal.CameraDecisionOption
import com.multicamstudio.domain.thermal.CameraPriority
import com.multicamstudio.domain.thermal.HealthSample
import com.multicamstudio.domain.thermal.ThermalAction
import com.multicamstudio.domain.thermal.ThermalPolicy
import com.multicamstudio.domain.timing.AudioDriftEstimator
import com.multicamstudio.domain.timing.ClockBase
import com.multicamstudio.domain.timing.ClockBaseDetector
import com.multicamstudio.domain.timing.ClockPair
import com.multicamstudio.domain.timing.FrameStatsTracker
import com.multicamstudio.domain.timing.PhaseAlignment
import com.multicamstudio.domain.timing.SessionTimeline
import com.multicamstudio.domain.upload.AssetCheck
import com.multicamstudio.domain.upload.AutoUploadInputs
import com.multicamstudio.domain.upload.AutoUploadPolicy
import com.multicamstudio.domain.upload.BackupVerifier
import com.multicamstudio.domain.upload.ResumableProtocol
import com.multicamstudio.domain.upload.UploadGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private const val SEC = 1_000_000_000L
private const val P30 = SEC / 30

class FrameStatsTrackerTest {
    @Test
    fun steadyStreamHasNoDrops() {
        val t = FrameStatsTracker(30)
        repeat(300) { t.onFrame(1_000 * SEC + it * P30) }
        val s = t.snapshot()
        assertEquals(300, s.frameCount)
        assertEquals(0, s.droppedFrames)
        assertEquals(30.0, s.currentFps!!, 0.2)
        assertEquals(30.0, s.averageFps!!, 0.01)
    }

    @Test
    fun gapCountsMissingFramesExactly() {
        val t = FrameStatsTracker(30)
        t.onFrame(0)
        t.onFrame(P30)
        t.onFrame(4 * P30) // frames 2 and 3 missing
        val s = t.snapshot()
        assertEquals(3, s.frameCount)
        assertEquals(2, s.droppedFrames)
        assertEquals(1, s.dropEvents)
    }

    @Test
    fun jitterBelowToleranceIsNotADrop() {
        val t = FrameStatsTracker(30)
        t.onFrame(0)
        t.onFrame(P30 + P30 / 3)
        assertEquals(0, t.snapshot().droppedFrames)
    }

    @Test
    fun discontinuityIsNotCountedAsDrops() {
        val t = FrameStatsTracker(30)
        t.onFrame(0)
        t.markDiscontinuity()
        t.onFrame(5 * SEC)
        assertEquals(0, t.snapshot().droppedFrames)
    }

    @Test
    fun duplicateTimestampsIgnored() {
        val t = FrameStatsTracker(30)
        t.onFrame(100)
        t.onFrame(100)
        assertEquals(1, t.snapshot().frameCount)
    }
}

class TimingTest {
    @Test
    fun detectsRealtimeAndMonotonicBases() {
        val now = ClockPair(realtimeNs = 10_000 * SEC, monotonicNs = 4_000 * SEC)
        assertEquals(ClockBase.REALTIME, ClockBaseDetector.detect(10_000 * SEC - SEC / 10, now).base)
        assertEquals(ClockBase.MONOTONIC, ClockBaseDetector.detect(4_000 * SEC - SEC / 10, now).base)
        assertFalse(ClockBaseDetector.detect(0, now).ambiguous)
    }

    @Test
    fun timelineMapsBothClocksToSameSessionTime() {
        val tl = SessionTimeline(originRealtimeNs = 10_000 * SEC, monotonicToRealtimeOffsetNs = 6_000 * SEC)
        assertEquals(2 * SEC, tl.toSessionNs(10_002 * SEC, ClockBase.REALTIME))
        assertEquals(2 * SEC, tl.toSessionNs(4_002 * SEC, ClockBase.MONOTONIC))
    }

    @Test
    fun phaseDifferenceIsBoundedByHalfPeriod() {
        assertEquals(0, PhaseAlignment.phaseDifferenceNs(10 * P30, 3 * P30, P30))
        assertEquals(5_000_000L, PhaseAlignment.phaseDifferenceNs(5_000_000, 0, P30))
        assertEquals(5_000_000L, PhaseAlignment.phaseDifferenceNs(0, 5_000_000, P30))
        val half = PhaseAlignment.phaseDifferenceNs(P30 / 2, 0, P30)
        assertTrue(half <= P30 / 2)
    }

    @Test
    fun audioDriftMeasured() {
        val d = AudioDriftEstimator(48_000)
        d.add(0, 0)
        // Hardware delivered 48_000 frames in 1.0001 s -> sample-count timeline is 0.1 ms ahead per second.
        d.add(48_000 * 600L, 600 * 1_000_100_000L)
        assertEquals(-60.0, d.accumulatedDriftMs()!!, 0.01)
        assertEquals(-100.0, d.driftPpm()!!, 0.5)
    }
}

class ThermalPolicyTest {
    private fun sample(t: Long, status: Int = 0, drops: Double = 0.0) = HealthSample(t, status, null, drops, 0)

    @Test
    fun normalDoesNothing() {
        val p = ThermalPolicy()
        assertEquals(ThermalAction.None, p.evaluate(sample(0)))
    }

    @Test
    fun criticalStopsImmediately() {
        val p = ThermalPolicy()
        assertTrue(p.evaluate(sample(0, ThermalStatus.CRITICAL)) is ThermalAction.EmergencyStop)
    }

    @Test
    fun sustainedOverloadEscalates720ThenDecision() {
        val p = ThermalPolicy()
        assertTrue(p.evaluate(sample(0, ThermalStatus.SEVERE)) is ThermalAction.Warn)
        val a = p.evaluate(sample(21 * SEC, ThermalStatus.SEVERE))
        assertTrue(a is ThermalAction.ReduceResolution)
        assertEquals(VideoResolution.P720, (a as ThermalAction.ReduceResolution).to)
        // In cooldown: only warnings.
        assertTrue(p.evaluate(sample(50 * SEC, ThermalStatus.SEVERE)) is ThermalAction.Warn)
        assertTrue(p.evaluate(sample(85 * SEC, ThermalStatus.SEVERE)) is ThermalAction.RequestCameraDecision)
        // Never asks twice until the decision is applied.
        assertTrue(p.evaluate(sample(300 * SEC, ThermalStatus.SEVERE)) is ThermalAction.Warn)
    }

    @Test
    fun frameDropsAloneCountAsOverload() {
        val p = ThermalPolicy()
        p.evaluate(sample(0, drops = 0.2))
        assertTrue(p.evaluate(sample(25 * SEC, drops = 0.2)) is ThermalAction.ReduceResolution)
    }

    @Test
    fun recoveryResetsSustainTimer() {
        val p = ThermalPolicy()
        p.evaluate(sample(0, ThermalStatus.SEVERE))
        p.evaluate(sample(10 * SEC, ThermalStatus.NONE))
        assertTrue(p.evaluate(sample(21 * SEC, ThermalStatus.SEVERE)) is ThermalAction.Warn)
    }

    @Test
    fun priorityReductionDropsLowestPriority() {
        val all = CameraRole.entries.toSet()
        assertEquals(setOf(CameraRole.REAR_MAIN, CameraRole.FRONT), CameraPriority.reduceByOne(all, CameraPriority.DEFAULT))
        assertEquals(setOf(CameraRole.REAR_MAIN), CameraPriority.reduceByOne(setOf(CameraRole.REAR_MAIN, CameraRole.FRONT), CameraPriority.DEFAULT))
        assertEquals(setOf(CameraRole.FRONT), CameraPriority.reduceByOne(setOf(CameraRole.FRONT), CameraPriority.DEFAULT))
    }

    @Test
    fun priorityParseFillsMissing() {
        assertEquals(listOf(CameraRole.REAR_WIDE, CameraRole.REAR_MAIN, CameraRole.FRONT), CameraPriority.parse("REAR_WIDE,bogus"))
    }

    @Test
    fun optionsIncludeContinueAndStopAndRecommendedFirst() {
        val opts = CameraPriority.options(CameraRole.entries.toSet(), CameraPriority.DEFAULT)
        assertTrue(opts.first() is CameraDecisionOption.ContinueAll)
        assertTrue(opts.last() is CameraDecisionOption.StopAll)
        val firstKeep = opts[1] as CameraDecisionOption.Keep
        assertEquals(setOf(CameraRole.REAR_MAIN, CameraRole.FRONT), firstKeep.roles)
    }
}

class UploadRulesTest {
    private val base = AutoUploadInputs(
        nowMs = 1_000_000, autoUploadEnabled = true, isRecording = false, appInForeground = false,
        backgroundSinceMs = 1_000_000 - 6 * 60_000, networkAvailable = true, thermalStatus = 0,
        systemLowMemory = false, availableMemoryFraction = 0.5, manualTransferRunning = false,
    )

    @Test
    fun eligibleAfterFiveMinutesInBackground() = assertEquals(UploadGate.Allowed, AutoUploadPolicy.evaluate(base))

    @Test
    fun waitsUntilFiveMinutes() {
        val g = AutoUploadPolicy.evaluate(base.copy(backgroundSinceMs = base.nowMs - 60_000))
        assertTrue(g is UploadGate.Wait)
        assertEquals(4 * 60_000L, (g as UploadGate.Wait).retryAfterMs)
    }

    @Test
    fun foregroundAndRecordingBlock() {
        assertTrue(AutoUploadPolicy.evaluate(base.copy(appInForeground = true)) is UploadGate.Blocked)
        assertTrue(AutoUploadPolicy.evaluate(base.copy(isRecording = true)) is UploadGate.Blocked)
    }

    @Test
    fun resourcePressurePauses() {
        assertTrue(AutoUploadPolicy.evaluate(base.copy(thermalStatus = ThermalStatus.MODERATE)) is UploadGate.Wait)
        assertTrue(AutoUploadPolicy.evaluate(base.copy(systemLowMemory = true)) is UploadGate.Wait)
        assertTrue(AutoUploadPolicy.evaluate(base.copy(networkAvailable = false)) is UploadGate.Wait)
    }

    @Test
    fun manualIgnoresForegroundButNotRecording() {
        assertEquals(UploadGate.Allowed, AutoUploadPolicy.manualGate(false, ThermalStatus.MODERATE, true))
        assertTrue(AutoUploadPolicy.manualGate(true, 0, true) is UploadGate.Blocked)
        assertTrue(AutoUploadPolicy.manualGate(false, ThermalStatus.SEVERE, true) is UploadGate.Wait)
    }

    private fun asset(state: UploadState = UploadState.VERIFIED, type: AssetType = AssetType.VIDEO_SEGMENT, md5: String? = "abc", remoteMd5: String? = "ABC") =
        AssetCheck(type, "f", 10, md5, state, "id", 10, remoteMd5, listOf("p"), "p")

    @Test
    fun verifyAssetChecksSizeChecksumParent() {
        assertTrue(BackupVerifier.verifyAsset(asset()).verified)
        assertFalse(BackupVerifier.verifyAsset(asset(remoteMd5 = "zzz")).verified)
        assertFalse(BackupVerifier.verifyAsset(asset(remoteMd5 = null)).verified)
        assertFalse(BackupVerifier.verifyAsset(asset().copy(remoteSize = 9)).verified)
        assertFalse(BackupVerifier.verifyAsset(asset().copy(remoteParents = listOf("other"))).verified)
    }

    @Test
    fun deletionRequiresEveryAsset() {
        val ok = listOf(asset(), asset(), asset(), asset(type = AssetType.AUDIO), asset(type = AssetType.MANIFEST))
        assertTrue(BackupVerifier.canDeleteLocal(ok, expectedVideoSegments = 3, expectAudio = true).verified)
        assertFalse(BackupVerifier.canDeleteLocal(ok.dropLast(1), 3, true).verified)
        assertFalse(BackupVerifier.canDeleteLocal(ok, 4, true).verified)
        val oneFailed = ok.toMutableList().also { it[1] = asset(UploadState.FAILED) }
        assertFalse(BackupVerifier.canDeleteLocal(oneFailed, 3, true).verified)
    }

    @Test
    fun failureNotHiddenBySuccess() {
        assertEquals(BackupState.UPLOAD_FAILED, BackupVerifier.sessionState(listOf(UploadState.VERIFIED, UploadState.FAILED), false))
        assertEquals(BackupState.BACKED_UP, BackupVerifier.sessionState(listOf(UploadState.VERIFIED, UploadState.VERIFIED), false))
        assertEquals(BackupState.PARTIALLY_UPLOADED, BackupVerifier.sessionState(listOf(UploadState.VERIFIED, UploadState.PENDING), false))
        assertEquals(BackupState.LOCAL_ONLY, BackupVerifier.sessionState(emptyList(), false))
        assertEquals(BackupState.CLOUD_ONLY, BackupVerifier.sessionState(listOf(UploadState.VERIFIED), true))
    }

    @Test
    fun resumableHelpers() {
        assertEquals(0L, ResumableProtocol.nextOffsetFromRange(null))
        assertEquals(1000L, ResumableProtocol.nextOffsetFromRange("bytes=0-999"))
        assertEquals("bytes 0-9/100", ResumableProtocol.contentRange(0, 10, 100))
        assertEquals(256 * 1024 * 32, ResumableProtocol.normalizeChunkSize(8 * 1024 * 1024 + 5))
        assertEquals(256 * 1024, ResumableProtocol.normalizeChunkSize(1))
        assertTrue(ResumableProtocol.isTransientHttp(503))
        assertFalse(ResumableProtocol.isTransientHttp(404))
        assertEquals(64_000L, ResumableProtocol.backoffMs(30))
    }
}

class SessionRulesTest {
    private val t = LocalDateTime.of(2026, 11, 16, 9, 30, 25)

    @Test
    fun naming() {
        assertEquals("2026-11-16_09-30-25", SessionNaming.sessionId(t))
        assertEquals("2026-11-16_09-30-25_3", SessionNaming.sessionId(t) { it == "2026-11-16_09-30-25" || it.endsWith("_2") })
        assertEquals("2026-11-16", SessionNaming.dateFolder(t))
        assertEquals("09-30-25_Business_Shoot", SessionNaming.sessionFolder(t, "Business Shoot!"))
        assertEquals("09-30-25", SessionNaming.sessionFolder(t, "  "))
        assertEquals("front_001.mp4", SessionNaming.segmentFileName(CameraRole.FRONT, 1))
        assertEquals("rear_wide_012.mp4", SessionNaming.segmentFileName(CameraRole.REAR_WIDE, 12))
    }

    @Test
    fun segmentPlanner() {
        val p = SegmentPlanner(300 * SEC)
        assertEquals(1, p.indexFor(0))
        assertEquals(1, p.indexFor(299 * SEC))
        assertEquals(2, p.indexFor(300 * SEC))
        assertEquals(600 * SEC, p.boundaryAfter(2))
    }

    @Test
    fun bitrateEstimates() {
        val hevc1080 = BitratePolicy.estimate(VideoResolution.P1080, 30, VideoCodecChoice.HEVC, QualityPreset.AUTO)
        val hevc720 = BitratePolicy.estimate(VideoResolution.P720, 30, VideoCodecChoice.HEVC, QualityPreset.AUTO)
        val avc1080 = BitratePolicy.estimate(VideoResolution.P1080, 30, VideoCodecChoice.AVC, QualityPreset.AUTO)
        assertEquals(8_100_000, hevc1080)
        assertTrue(hevc720 < hevc1080)
        assertTrue(avc1080 > hevc1080)
        assertEquals(5_000_000, BitratePolicy.clamp(8_000_000, 1_000_000, 5_000_000))
    }

    @Test
    fun storagePolicy() {
        val d = StoragePolicy.evaluate(freeBytes = 3_000_000_000, bytesPerSecond = 3_000_000.0, warnBytes = 5_000_000_000, stopBytes = 1_000_000_000)
        assertTrue(d.warn)
        assertFalse(d.stop)
        assertEquals(11.1, d.estimatedMinutesLeft!!, 0.1)
    }

    @Test
    fun manifestRoundTripsAndIgnoresUnknownKeys() {
        val m = SessionManifest(
            sessionId = "id", displayName = "n", startTimeUtc = "2026-11-16T04:00:25Z", timezone = "Asia/Kolkata",
            targetFrameRate = 30, videoCodec = "HEVC", audioCodec = "AAC", app = AppInfo("0.1", 1),
            device = DeviceInfo("Nothing", "A142P", "16", 36, "fp"), captureArchitecture = "B",
            cameras = mapOf("front" to CameraTrack(true, "1", null, "1920x1080", 30, status = "RECORDING")),
            audio = null, synchronization = SyncInfo(originElapsedRealtimeNs = 5), segments = emptyList(),
        )
        val text = SessionManifest.json.encodeToString(SessionManifest.serializer(), m)
        assertTrue(text.contains("\"schemaVersion\": 1"))
        val back = SessionManifest.json.decodeFromString(SessionManifest.serializer(), text.replaceFirst("{", "{\"futureField\": 7,"))
        assertEquals(m, back)
        assertNotNull(back.cameras["front"])
    }
}
