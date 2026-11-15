package com.multicamstudio.domain.upload

import com.multicamstudio.domain.model.AssetType
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.ThermalStatus
import com.multicamstudio.domain.model.UploadState

/** Inputs for the automatic-upload eligibility decision. */
data class AutoUploadInputs(
    val nowMs: Long,
    val autoUploadEnabled: Boolean,
    val isRecording: Boolean,
    val appInForeground: Boolean,
    /** Wall-clock time the app last went to the background; null if unknown. */
    val backgroundSinceMs: Long?,
    val networkAvailable: Boolean,
    val thermalStatus: Int,
    val systemLowMemory: Boolean,
    /** availMem / totalMem, 0..1. */
    val availableMemoryFraction: Double,
    val manualTransferRunning: Boolean,
)

data class AutoUploadThresholds(
    val backgroundDelayMs: Long = 5 * 60_000L,
    /** Pause at or above this thermal status. Default MODERATE so uploads never add heat to a warm phone. */
    val pauseThermalStatus: Int = ThermalStatus.MODERATE,
    val minAvailableMemoryFraction: Double = 0.10,
)

sealed interface UploadGate {
    data object Allowed : UploadGate
    /** Not allowed now; try again after [retryAfterMs]. */
    data class Wait(val reason: String, val retryAfterMs: Long) : UploadGate
    /** Not allowed until an external event re-triggers scheduling (e.g. app goes to background again). */
    data class Blocked(val reason: String) : UploadGate
}

object AutoUploadPolicy {
    fun evaluate(i: AutoUploadInputs, t: AutoUploadThresholds = AutoUploadThresholds()): UploadGate {
        if (!i.autoUploadEnabled) return UploadGate.Blocked("Automatic upload disabled")
        if (i.isRecording) return UploadGate.Blocked("Recording in progress")
        if (i.appInForeground) return UploadGate.Blocked("App is in the foreground")
        if (i.manualTransferRunning) return UploadGate.Blocked("Manual upload in progress")
        val since = i.backgroundSinceMs ?: return UploadGate.Wait("Background time unknown", t.backgroundDelayMs)
        val elapsed = i.nowMs - since
        if (elapsed < t.backgroundDelayMs) return UploadGate.Wait("App backgrounded ${elapsed / 1000}s ago", t.backgroundDelayMs - elapsed)
        val res = resourceGate(i.networkAvailable, i.thermalStatus, i.systemLowMemory, i.availableMemoryFraction, t)
        return res
    }

    /** Shared by manual uploads (which skip the background requirement but not safety). */
    fun resourceGate(
        networkAvailable: Boolean,
        thermalStatus: Int,
        systemLowMemory: Boolean,
        availableMemoryFraction: Double,
        t: AutoUploadThresholds,
    ): UploadGate {
        if (!networkAvailable) return UploadGate.Wait("No network", 60_000)
        if (thermalStatus >= t.pauseThermalStatus) return UploadGate.Wait("Device warm (${ThermalStatus.label(thermalStatus)})", 120_000)
        if (systemLowMemory) return UploadGate.Wait("System low on memory", 120_000)
        if (availableMemoryFraction < t.minAvailableMemoryFraction) return UploadGate.Wait("Memory pressure", 120_000)
        return UploadGate.Allowed
    }

    /** Manual uploads only stop for recording and critical thermal state. */
    fun manualGate(isRecording: Boolean, thermalStatus: Int, networkAvailable: Boolean): UploadGate {
        if (isRecording) return UploadGate.Blocked("Recording in progress")
        if (thermalStatus >= ThermalStatus.SEVERE) return UploadGate.Wait("Device too hot (${ThermalStatus.label(thermalStatus)})", 60_000)
        if (!networkAvailable) return UploadGate.Wait("No network", 30_000)
        return UploadGate.Allowed
    }
}

/** What we know about one required session asset for verification purposes. */
data class AssetCheck(
    val assetType: AssetType,
    val localName: String,
    val localSize: Long?,
    val localMd5: String?,
    val uploadState: UploadState,
    val driveFileId: String?,
    val remoteSize: Long?,
    val remoteMd5: String?,
    val remoteParents: List<String>,
    val expectedParentId: String?,
)

data class VerificationResult(val verified: Boolean, val problems: List<String>)

object BackupVerifier {
    /** Verify a single asset using Drive's returned metadata. */
    fun verifyAsset(a: AssetCheck): VerificationResult {
        val problems = mutableListOf<String>()
        if (a.driveFileId.isNullOrBlank()) problems += "${a.localName}: no Drive file id"
        if (a.localSize == null || a.remoteSize == null || a.localSize != a.remoteSize) {
            problems += "${a.localName}: size mismatch (local=${a.localSize}, drive=${a.remoteSize})"
        }
        // Drive returns md5Checksum for binary content; treat its absence as unverifiable rather than verified.
        if (a.localMd5 == null || a.remoteMd5 == null) {
            problems += "${a.localName}: checksum unavailable"
        } else if (!a.localMd5.equals(a.remoteMd5, ignoreCase = true)) {
            problems += "${a.localName}: checksum mismatch"
        }
        if (a.expectedParentId == null || a.expectedParentId !in a.remoteParents) {
            problems += "${a.localName}: not in expected Drive folder"
        }
        return VerificationResult(problems.isEmpty(), problems)
    }

    /**
     * Local deletion is permitted only when every required asset (all segments of every camera,
     * the master audio and the manifest) is VERIFIED. One missing asset blocks deletion.
     */
    fun canDeleteLocal(assets: List<AssetCheck>, expectedVideoSegments: Int, expectAudio: Boolean): VerificationResult {
        val problems = mutableListOf<String>()
        val videos = assets.filter { it.assetType == AssetType.VIDEO_SEGMENT }
        val audio = assets.filter { it.assetType == AssetType.AUDIO }
        val manifests = assets.filter { it.assetType == AssetType.MANIFEST }
        if (videos.size < expectedVideoSegments) problems += "Only ${videos.size} of $expectedVideoSegments video segments tracked"
        if (expectAudio && audio.isEmpty()) problems += "Master audio not tracked"
        if (manifests.isEmpty()) problems += "Manifest not tracked"
        assets.filter { it.uploadState != UploadState.VERIFIED }.forEach { problems += "${it.localName}: ${it.uploadState}" }
        return VerificationResult(problems.isEmpty(), problems)
    }

    /** Derive the session-level backup state; a single failed asset is never hidden by successes. */
    fun sessionState(states: List<UploadState>, localDeleted: Boolean): BackupState {
        if (localDeleted) return BackupState.CLOUD_ONLY
        if (states.isEmpty() || states.all { it == UploadState.NOT_QUEUED }) return BackupState.LOCAL_ONLY
        if (states.all { it == UploadState.VERIFIED }) return BackupState.BACKED_UP
        if (states.any { it == UploadState.FAILED }) return BackupState.UPLOAD_FAILED
        if (states.any { it == UploadState.IN_PROGRESS }) return BackupState.UPLOADING
        if (states.any { it == UploadState.UPLOADED_UNVERIFIED }) return BackupState.BACKUP_VERIFICATION
        if (states.any { it == UploadState.VERIFIED || it == UploadState.PAUSED }) return BackupState.PARTIALLY_UPLOADED
        return BackupState.QUEUED
    }
}

/** Helpers for the Google Drive resumable upload protocol. */
object ResumableProtocol {
    /** Drive requires chunk sizes that are multiples of 256 KiB (except the final chunk). */
    const val CHUNK_GRANULARITY = 256 * 1024

    fun normalizeChunkSize(requested: Int): Int {
        val n = (requested / CHUNK_GRANULARITY).coerceAtLeast(1)
        return n * CHUNK_GRANULARITY
    }

    /**
     * Parses a `Range: bytes=0-12345` header from a 308 response and returns the next byte offset
     * to send. A missing header means nothing has been persisted yet (offset 0).
     */
    fun nextOffsetFromRange(rangeHeader: String?): Long {
        if (rangeHeader.isNullOrBlank()) return 0
        val m = Regex("""bytes=(\d+)-(\d+)""").find(rangeHeader) ?: return 0
        return m.groupValues[2].toLong() + 1
    }

    fun contentRange(start: Long, length: Long, total: Long): String {
        require(length > 0) { "empty chunk" }
        return "bytes $start-${start + length - 1}/$total"
    }

    fun statusQueryContentRange(total: Long): String = "bytes */$total"

    fun isTransientHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

    /** Exponential backoff with cap, deterministic jitter supplied by caller. */
    fun backoffMs(attempt: Int, baseMs: Long = 1_000, maxMs: Long = 64_000, jitterMs: Long = 0): Long {
        val exp = baseMs shl attempt.coerceIn(0, 16)
        return minOf(exp, maxMs) + jitterMs
    }
}
