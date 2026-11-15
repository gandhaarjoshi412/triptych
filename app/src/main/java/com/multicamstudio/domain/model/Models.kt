package com.multicamstudio.domain.model

/**
 * Pure domain types. Nothing in `domain` may import android.* so that it can be unit tested on the JVM.
 */

enum class CameraRole(val dirName: String, val manifestKey: String, val label: String) {
    FRONT("front", "front", "Front"),
    REAR_MAIN("rear_main", "rearMain", "Main"),
    REAR_WIDE("rear_wide", "rearWide", "Wide");

    companion object {
        fun fromDirName(name: String): CameraRole? = entries.firstOrNull { it.dirName == name }
    }
}

data class VideoResolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"

    companion object {
        val P1080 = VideoResolution(1920, 1080)
        val P720 = VideoResolution(1280, 720)

        fun parse(text: String): VideoResolution? {
            val parts = text.lowercase().split("x")
            if (parts.size != 2) return null
            val w = parts[0].trim().toIntOrNull() ?: return null
            val h = parts[1].trim().toIntOrNull() ?: return null
            return VideoResolution(w, h)
        }
    }
}

enum class VideoCodecChoice(val mime: String, val manifestName: String) {
    HEVC("video/hevc", "HEVC"),
    AVC("video/avc", "H264"),
}

enum class QualityPreset(val bitsPerPixelFrame: Double) {
    /** Automatic: high quality, tuned for the device; see [com.multicamstudio.domain.session.BitratePolicy]. */
    AUTO(0.13),
    HIGH(0.17),
    BALANCED(0.10),
    STORAGE_SAVER(0.07),
}

/** Overall state of a recording session as shown in the recording monitor. */
enum class SessionPhase {
    IDLE,
    PREVIEWING,
    INITIALIZING,
    RECORDING,
    RECORDING_DEGRADED,
    CAMERA_DISCONNECTED,
    WAITING_FOR_THERMAL_DECISION,
    FINALIZING,
    COMPLETED,
    FAILED;

    val isActive: Boolean
        get() = this == INITIALIZING || this == RECORDING || this == RECORDING_DEGRADED ||
            this == CAMERA_DISCONNECTED || this == WAITING_FOR_THERMAL_DECISION || this == FINALIZING
}

/** Per-camera stream status. */
enum class StreamStatus { INITIALIZING, RECORDING, DEGRADED, DISCONNECTED, STOPPED_BY_USER, STOPPED_THERMAL, FAILED, UNAVAILABLE }

/** Persisted recording state of a session (Room). */
enum class RecordingState { RECORDING, FINALIZING, COMPLETED, INTERRUPTED, FAILED }

/** Session level backup state (Room). */
enum class BackupState {
    LOCAL_ONLY,
    QUEUED,
    UPLOADING,
    PARTIALLY_UPLOADED,
    BACKUP_VERIFICATION,
    BACKED_UP,
    UPLOAD_FAILED,
    CLOUD_ONLY,
}

/** Per-asset upload state (Room). */
enum class UploadState { NOT_QUEUED, PENDING, IN_PROGRESS, PAUSED, UPLOADED_UNVERIFIED, VERIFIED, FAILED }

enum class FinalizationState { IN_PROGRESS, FINALIZED, INTERRUPTED_UNRECOVERABLE }

enum class LocalDeletionState { PRESENT, DELETING, DELETED }

enum class AssetType { VIDEO_SEGMENT, AUDIO, MANIFEST }

/** Mirrors android.os.PowerManager.THERMAL_STATUS_* so the policy can stay platform-free. */
object ThermalStatus {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6

    fun label(status: Int): String = when (status) {
        NONE -> "Normal"
        LIGHT -> "Light"
        MODERATE -> "Moderate"
        SEVERE -> "Severe"
        CRITICAL -> "Critical"
        EMERGENCY -> "Emergency"
        SHUTDOWN -> "Shutdown"
        else -> "Unknown ($status)"
    }
}
