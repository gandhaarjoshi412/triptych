package com.multicamstudio.domain.manifest

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Machine-readable description of a session. Written atomically to metadata/session_manifest.json
 * periodically during recording and once more at finalization.
 *
 * Compatibility rules:
 *  - [schemaVersion] increments on breaking changes only.
 *  - New optional fields may be added with defaults; readers must ignore unknown keys.
 *  - The manifest describes the originals; it never instructs anything to modify them.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionManifest(
    @EncodeDefault val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val displayName: String,
    val startTimeUtc: String,
    val endTimeUtc: String? = null,
    val timezone: String,
    val targetFrameRate: Int,
    val videoCodec: String,
    val audioCodec: String,
    val app: AppInfo,
    val device: DeviceInfo,
    val captureArchitecture: String,
    val cameras: Map<String, CameraTrack>,
    val audio: AudioTrack?,
    val synchronization: SyncInfo,
    val segments: List<SegmentInfo>,
    val events: List<SessionEvent> = emptyList(),
    val stopReason: String? = null,
    val backup: BackupInfo = BackupInfo(),
    /** Reserved for future editing tools (EDLs, AI suggestions). They reference originals; never replace them. */
    val derivedAssets: List<DerivedAsset> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@Serializable
data class AppInfo(val versionName: String, val versionCode: Long)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildFingerprint: String,
)

@Serializable
data class CameraTrack(
    val enabled: Boolean,
    val cameraId: String?,
    val physicalCameraId: String? = null,
    /** Current/last resolution. Full history in [resolutionHistory]. */
    val resolution: String,
    val frameRate: Int,
    val codecName: String? = null,
    val bitrate: Int? = null,
    val bitrateMode: String? = null,
    val timestampClock: String? = null,
    val status: String,
    val frameCount: Long = 0,
    val droppedFrames: Long = 0,
    val averageFps: Double? = null,
    val maxFrameGapMs: Double? = null,
    /** Session time of this stream's first recorded frame. Place the first segment here on an editor timeline. */
    val firstFrameSessionMs: Double? = null,
    val resolutionHistory: List<ResolutionChange> = emptyList(),
)

@Serializable
data class ResolutionChange(val sessionTimeMs: Double, val resolution: String, val reason: String)

@Serializable
data class AudioTrack(
    val enabled: Boolean,
    val file: String,
    val sampleRate: Int,
    val channels: Int,
    val format: String,
    val bitrate: Int,
    val firstSampleSessionMs: Double?,
    val timestampSource: String,
    val durationMs: Double?,
    val driftPpm: Double? = null,
    val accumulatedDriftMs: Double? = null,
    val routing: List<AudioRoute> = emptyList(),
)

@Serializable
data class AudioRoute(val sessionTimeMs: Double, val deviceType: String, val deviceName: String)

@Serializable
data class SyncInfo(
    val clock: String = "monotonic",
    val reference: String = "SystemClock.elapsedRealtimeNanos (CLOCK_BOOTTIME)",
    val originElapsedRealtimeNs: Long,
    val targetErrorMs: Int = 50,
    val sensorSyncType: String? = null,
    /** Measured worst pairwise frame-phase difference between cameras (ms). */
    val maxPairwisePhaseMs: Double? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class SegmentInfo(
    val camera: String,
    val index: Int,
    val file: String,
    val resolution: String,
    val startSessionMs: Double?,
    val endSessionMs: Double?,
    val frameCount: Long,
    val droppedFrames: Long,
    val sizeBytes: Long?,
    val md5: String? = null,
    val finalization: String,
)

@Serializable
data class SessionEvent(val sessionTimeMs: Double, val type: String, val message: String)

@Serializable
data class BackupInfo(
    /** Snapshot when the manifest was written. The app database is the live source of truth. */
    val status: String = "LOCAL_ONLY",
    val note: String = "Live backup state is tracked in the app database",
)

@Serializable
data class DerivedAsset(val kind: String, val file: String, val createdUtc: String, val sourceFiles: List<String>)
