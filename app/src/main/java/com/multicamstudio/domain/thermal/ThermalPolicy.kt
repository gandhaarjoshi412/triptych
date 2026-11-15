package com.multicamstudio.domain.thermal

import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.ThermalStatus
import com.multicamstudio.domain.model.VideoResolution

/** One health observation, taken by the recording engine about once per second. */
data class HealthSample(
    val timeNs: Long,
    /** PowerManager thermal status (0..6). */
    val thermalStatus: Int,
    /** PowerManager.getThermalHeadroom(…) if available. 1.0 = throttling imminent. */
    val thermalHeadroom: Float?,
    /** Worst drop ratio across active streams over the last sampling interval (0..1). */
    val recentDropRatio: Double,
    /** Count of non-fatal encoder/camera errors in the last interval. */
    val recentPipelineErrors: Int,
)

data class ThermalPolicyConfig(
    /** Thermal status at which the device is considered overloaded. */
    val overloadStatus: Int = ThermalStatus.SEVERE,
    /** Status at which we warn the user without changing anything. */
    val warnStatus: Int = ThermalStatus.MODERATE,
    /** Status at which recording must stop. */
    val criticalStatus: Int = ThermalStatus.CRITICAL,
    /** Headroom above which we treat the device as overloaded even if status lags. null disables. */
    val overloadHeadroom: Float? = 0.95f,
    /** Drop ratio considered "cannot sustain 30 fps". */
    val overloadDropRatio: Double = 0.05,
    /** Overload must persist this long before acting. */
    val sustainNs: Long = 20_000_000_000L,
    /** After any change, wait this long before escalating again. */
    val cooldownNs: Long = 60_000_000_000L,
)

enum class DegradationStage { NORMAL_1080, REDUCED_720, CAMERA_DECISION_REQUESTED }

sealed interface ThermalAction {
    data object None : ThermalAction
    data class Warn(val reason: String) : ThermalAction
    data class ReduceResolution(val to: VideoResolution, val reason: String) : ThermalAction
    data class RequestCameraDecision(val reason: String) : ThermalAction
    data class EmergencyStop(val reason: String) : ThermalAction
}

/**
 * Pure decision logic for the thermal / overload strategy:
 *  1. 1080p30 normally.
 *  2. Sustained overload -> all active cameras to 720p30 (never lower, never fewer fps).
 *  3. Still overloaded at 720p -> ask the user which cameras to keep (or apply configured priority if unattended).
 *  4. Critical thermal -> stop safely.
 */
class ThermalPolicy(private val config: ThermalPolicyConfig = ThermalPolicyConfig()) {
    private var overloadSinceNs: Long? = null
    private var lastActionNs: Long? = null
    var stage: DegradationStage = DegradationStage.NORMAL_1080
        private set

    fun reset(stage: DegradationStage = DegradationStage.NORMAL_1080) {
        this.stage = stage
        overloadSinceNs = null
        lastActionNs = null
    }

    /** Must be called after the engine applied a camera decision so we may escalate again later. */
    fun onCameraDecisionApplied(nowNs: Long) {
        stage = DegradationStage.REDUCED_720
        overloadSinceNs = null
        lastActionNs = nowNs
    }

    fun isOverloaded(s: HealthSample): Boolean =
        s.thermalStatus >= config.overloadStatus ||
            (config.overloadHeadroom != null && s.thermalHeadroom != null && s.thermalHeadroom >= config.overloadHeadroom) ||
            s.recentDropRatio >= config.overloadDropRatio

    fun evaluate(s: HealthSample): ThermalAction {
        if (s.thermalStatus >= config.criticalStatus) {
            return ThermalAction.EmergencyStop("Device reported ${ThermalStatus.label(s.thermalStatus)} thermal status")
        }
        val overloaded = isOverloaded(s)
        if (!overloaded) {
            overloadSinceNs = null
            return if (s.thermalStatus >= config.warnStatus) {
                ThermalAction.Warn("Device is warm (${ThermalStatus.label(s.thermalStatus)})")
            } else ThermalAction.None
        }
        val since = overloadSinceNs ?: s.timeNs.also { overloadSinceNs = it }
        val sustained = s.timeNs - since >= config.sustainNs
        val coolingDown = lastActionNs?.let { s.timeNs - it < config.cooldownNs } ?: false
        val reason = describe(s)
        if (!sustained || coolingDown) return ThermalAction.Warn(reason)

        return when (stage) {
            DegradationStage.NORMAL_1080 -> {
                stage = DegradationStage.REDUCED_720
                lastActionNs = s.timeNs
                overloadSinceNs = null
                ThermalAction.ReduceResolution(VideoResolution.P720, reason)
            }
            DegradationStage.REDUCED_720 -> {
                stage = DegradationStage.CAMERA_DECISION_REQUESTED
                lastActionNs = s.timeNs
                overloadSinceNs = null
                ThermalAction.RequestCameraDecision(reason)
            }
            DegradationStage.CAMERA_DECISION_REQUESTED -> ThermalAction.Warn(reason)
        }
    }

    private fun describe(s: HealthSample): String = buildList {
        if (s.thermalStatus >= config.overloadStatus) add("thermal status ${ThermalStatus.label(s.thermalStatus)}")
        if (config.overloadHeadroom != null && s.thermalHeadroom != null && s.thermalHeadroom >= config.overloadHeadroom) {
            add("thermal headroom %.2f".format(s.thermalHeadroom))
        }
        if (s.recentDropRatio >= config.overloadDropRatio) add("frame drops %.1f%%".format(s.recentDropRatio * 100))
    }.joinToString(", ").ifEmpty { "overload" }
}

/** Options shown in the camera management dialog (stage 2). */
sealed class CameraDecisionOption(val label: String) {
    data object ContinueAll : CameraDecisionOption("Continue with all cameras at 720p30")
    data class Keep(val roles: Set<CameraRole>, val text: String) : CameraDecisionOption(text)
    data object StopAll : CameraDecisionOption("Stop all recordings safely")
}

object CameraPriority {
    val DEFAULT: List<CameraRole> = listOf(CameraRole.REAR_MAIN, CameraRole.FRONT, CameraRole.REAR_WIDE)

    /** Parse a comma-separated persisted priority, filling in any missing role in default order. */
    fun parse(text: String?): List<CameraRole> {
        val parsed = text.orEmpty().split(",").mapNotNull { name -> CameraRole.entries.firstOrNull { it.name == name.trim() } }.distinct()
        return parsed + DEFAULT.filterNot { it in parsed }
    }

    fun serialize(order: List<CameraRole>): String = order.joinToString(",") { it.name }

    /** Unattended reduction: drop the lowest-priority active camera, but always keep at least one. */
    fun reduceByOne(active: Set<CameraRole>, priority: List<CameraRole>): Set<CameraRole> {
        if (active.size <= 1) return active
        val ordered = priority.filter { it in active }
        return ordered.dropLast(1).toSet()
    }

    fun options(active: Set<CameraRole>, priority: List<CameraRole>): List<CameraDecisionOption> {
        val out = mutableListOf<CameraDecisionOption>(CameraDecisionOption.ContinueAll)
        val candidates = listOf(
            setOf(CameraRole.REAR_MAIN, CameraRole.FRONT) to "Stop ultra-wide; keep main + front",
            setOf(CameraRole.REAR_MAIN, CameraRole.REAR_WIDE) to "Keep main + ultra-wide",
            setOf(CameraRole.FRONT, CameraRole.REAR_WIDE) to "Keep front + ultra-wide",
            setOf(CameraRole.REAR_MAIN) to "Keep only main camera",
            setOf(CameraRole.FRONT) to "Keep only front camera",
        )
        val recommended = reduceByOne(active, priority)
        val valid = candidates.filter { (set, _) -> set.all { it in active } && set != active }
        // Recommended (per configured priority) first.
        valid.sortedByDescending { it.first == recommended }.forEach { (set, text) ->
            out += CameraDecisionOption.Keep(set, if (set == recommended) "$text (recommended)" else text)
        }
        out += CameraDecisionOption.StopAll
        return out
    }
}
