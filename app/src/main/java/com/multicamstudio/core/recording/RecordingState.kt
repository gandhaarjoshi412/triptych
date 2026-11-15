package com.multicamstudio.core.recording

import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.SessionPhase
import com.multicamstudio.domain.model.StreamStatus
import com.multicamstudio.domain.thermal.CameraDecisionOption

data class CameraTelemetry(
    val role: CameraRole,
    val status: StreamStatus,
    val resolution: String?,
    /** Measured from real encoder output timestamps; null until measured. Never estimated. */
    val fps: Double?,
    val frames: Long,
    val dropped: Long,
    val bufferLost: Long,
    val message: String?,
)

data class PendingDecision(
    val reason: String,
    val options: List<CameraDecisionOption>,
    val deadlineElapsedMs: Long,
)

/** Everything the recording monitor and notification show. Updated ~1 Hz while recording. */
data class RecordingUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val sessionId: String? = null,
    val displayName: String? = null,
    val elapsedMs: Long = 0,
    val startedWallMs: Long? = null,
    val cameras: Map<CameraRole, CameraTelemetry> = emptyMap(),
    val audioStatus: String = "Idle",
    val thermalStatus: Int = 0,
    val thermalHeadroom: Float? = null,
    val thermalWarning: String? = null,
    val freeBytes: Long = 0,
    val minutesLeft: Double? = null,
    val storageWarning: String? = null,
    val errors: List<String> = emptyList(),
    val decision: PendingDecision? = null,
    val architecture: String? = null,
    val topologyNote: String? = null,
    val maxPhaseMs: Double? = null,
    val lastStopReason: String? = null,
    val lastSessionId: String? = null,
    /** Incremented whenever a session ends or fails to start; lets observers detect the end even if phases are conflated. */
    val endedCount: Int = 0,
) {
    val activeCameraCount: Int get() = cameras.values.count { it.status == StreamStatus.RECORDING || it.status == StreamStatus.DEGRADED }
}
