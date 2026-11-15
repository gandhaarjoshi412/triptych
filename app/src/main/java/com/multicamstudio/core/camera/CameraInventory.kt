package com.multicamstudio.core.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.util.Range
import android.util.Size
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.VideoResolution
import kotlin.math.abs
import kotlin.math.atan

/** Static description of one camera sensor (a standalone camera id, a logical camera, or a hidden physical camera). */
data class SensorInfo(
    val id: String,
    /** Logical camera ids that expose this sensor as a physical sub-camera. */
    val logicalParents: List<String>,
    /** True when [id] is in CameraManager.getCameraIdList() and can be opened directly. */
    val openable: Boolean,
    val facing: Int?,
    val focalLengths: List<Float>,
    val sensorWidthMm: Float?,
    val pixelArray: Size?,
    val horizontalFovDeg: Double?,
    val isLogicalMultiCamera: Boolean,
    val physicalIds: Set<String>,
    /** LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE: 0 = APPROXIMATE, 1 = CALIBRATED. */
    val sensorSyncType: Int?,
    val timestampSource: Int?,
    val hardwareLevel: Int?,
    val sensorOrientation: Int,
    val capabilities: List<Int>,
    val fpsRanges: List<Range<Int>>,
    val encoderSizes: List<Size>,
    val yuvSizes: List<Size>,
    /** Min frame duration (ns) for MediaCodec output at 1080p / 720p, null if size unsupported. */
    val minFrameDuration1080: Long?,
    val minFrameDuration720: Long?,
) {
    val isFront get() = facing == CameraMetadata.LENS_FACING_FRONT
    val isBack get() = facing == CameraMetadata.LENS_FACING_BACK

    fun supportsEncoderSize(r: VideoResolution, fps: Int = 30): Boolean {
        val size = encoderSizes.firstOrNull { it.width == r.width && it.height == r.height } ?: return false
        val minDur = when (r) {
            VideoResolution.P1080 -> minFrameDuration1080
            VideoResolution.P720 -> minFrameDuration720
            else -> null
        }
        return size.width > 0 && (minDur == null || minDur <= 1_000_000_000L / fps)
    }

    fun fixedFpsRange(fps: Int = 30): Range<Int>? =
        fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }

    fun syncTypeLabel(): String? = when (sensorSyncType) {
        CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "APPROXIMATE"
        CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "CALIBRATED"
        null -> null
        else -> "UNKNOWN($sensorSyncType)"
    }

    fun timestampSourceLabel(): String = when (timestampSource) {
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN(monotonic)"
        else -> "n/a"
    }
}

/** Which camera id to open and, optionally, which physical sub-camera to route a stream from. */
data class StreamTarget(val role: CameraRole, val deviceId: String, val physicalId: String?, val sensorId: String) {
    override fun toString() = if (physicalId != null) "$role=$deviceId:$physicalId" else "$role=$deviceId"
}

enum class CaptureArchitecture(val label: String) {
    /** A: front, main and ultra-wide as independent CameraDevices in one concurrent combination. */
    INDEPENDENT_CONCURRENT("A: independent concurrent cameras"),
    /** B: logical rear multi-camera with physical outputs for main + ultra-wide, plus front camera concurrently. */
    LOGICAL_PHYSICAL_PLUS_FRONT("B: logical multi-camera (main+wide) + front"),
    /** Fallback combinations with fewer than three cameras. */
    PARTIAL("Partial (fewer than three cameras)"),
}

data class CameraTopology(
    val architecture: CaptureArchitecture,
    val targets: List<StreamTarget>,
    val description: String,
) {
    val roles: Set<CameraRole> get() = targets.map { it.role }.toSet()
    val deviceIds: List<String> get() = targets.map { it.deviceId }.distinct()
    fun targetsFor(deviceId: String) = targets.filter { it.deviceId == deviceId }
    fun target(role: CameraRole) = targets.firstOrNull { it.role == role }
    fun withRoles(keep: Set<CameraRole>) = copy(targets = targets.filter { it.role in keep })
}

data class RoleMapping(
    val front: SensorInfo?,
    val main: SensorInfo?,
    val wide: SensorInfo?,
    val notes: List<String>,
)

data class CameraOverrides(val front: String = "", val main: String = "", val wide: String = "")

/** Enumerates every camera the framework exposes, including hidden physical sub-cameras. */
class CameraInventory(private val manager: CameraManager) {

    fun enumerate(): List<SensorInfo> {
        val ids = manager.cameraIdList.toList()
        val result = linkedMapOf<String, SensorInfo>()
        val parents = mutableMapOf<String, MutableList<String>>()
        ids.forEach { id ->
            runCatching { manager.getCameraCharacteristics(id) }.getOrNull()?.physicalCameraIds?.forEach { pid ->
                parents.getOrPut(pid) { mutableListOf() } += id
            }
        }
        (ids + parents.keys).distinct().forEach { id ->
            val c = runCatching { manager.getCameraCharacteristics(id) }
                .onFailure { AppLog.w(TAG, "No characteristics for $id", it) }
                .getOrNull() ?: return@forEach
            result[id] = describe(id, c, openable = id in ids, parents = parents[id].orEmpty())
        }
        return result.values.toList()
    }

    private fun describe(id: String, c: CameraCharacteristics, openable: Boolean, parents: List<String>): SensorInfo {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
        val physSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val fov = if (physSize != null && focal.isNotEmpty() && focal.min() > 0f) {
            Math.toDegrees(2 * atan(physSize.width / (2.0 * focal.min())))
        } else null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val encSizes = runCatching { map?.getOutputSizes(MediaCodec::class.java)?.toList() }.getOrNull().orEmpty()
        val yuvSizes = runCatching { map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() }.getOrNull().orEmpty()
        fun minDur(w: Int, h: Int): Long? {
            val s = encSizes.firstOrNull { it.width == w && it.height == h } ?: return null
            return runCatching { map?.getOutputMinFrameDuration(MediaCodec::class.java, s) }.getOrNull()
        }
        return SensorInfo(
            id = id,
            logicalParents = parents,
            openable = openable,
            facing = c.get(CameraCharacteristics.LENS_FACING),
            focalLengths = focal,
            sensorWidthMm = physSize?.width,
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            horizontalFovDeg = fov,
            isLogicalMultiCamera = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps,
            physicalIds = c.physicalCameraIds,
            sensorSyncType = c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE),
            timestampSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            capabilities = caps,
            fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
            encoderSizes = encSizes,
            yuvSizes = yuvSizes,
            minFrameDuration1080 = minDur(1920, 1080),
            minFrameDuration720 = minDur(1280, 720),
        )
    }

    /**
     * Identify front / main / ultra-wide sensors.
     *  - Ultra-wide: rear (non-logical) sensor with the widest horizontal field of view.
     *  - Main: of the remaining rear sensors, largest pixel array, tie-break FOV closest to ~78 degrees.
     *  - Front: first openable front camera.
     * Manual overrides (Settings) take precedence; values are camera ids (physical or standalone).
     */
    fun identifyRoles(sensors: List<SensorInfo>, overrides: CameraOverrides = CameraOverrides()): RoleMapping {
        val notes = mutableListOf<String>()
        val byId = sensors.associateBy { it.id }
        fun ov(v: String): SensorInfo? = v.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { byId[it] }

        val rearSensors = sensors.filter { it.isBack && !it.isLogicalMultiCamera }
        val wide = ov(overrides.wide) ?: rearSensors.filter { it.horizontalFovDeg != null }.maxByOrNull { it.horizontalFovDeg!! }
        val mainCandidates = rearSensors.filter { it.id != wide?.id }
        val main = ov(overrides.main) ?: mainCandidates.maxWithOrNull(
            compareBy<SensorInfo> { (it.pixelArray?.width ?: 0).toLong() * (it.pixelArray?.height ?: 0) }
                .thenByDescending { abs((it.horizontalFovDeg ?: 0.0) - 78.0) }
        )
        val front = ov(overrides.front) ?: sensors.firstOrNull { it.isFront && it.openable && !it.isLogicalMultiCamera }
            ?: sensors.firstOrNull { it.isFront && it.openable }

        if (wide != null && main != null && (wide.horizontalFovDeg ?: 0.0) <= (main.horizontalFovDeg ?: 0.0) + 5) {
            notes += "Ultra-wide (${wide.id}) FOV is not clearly wider than main (${main.id}); verify mapping in diagnostics."
        }
        if (wide == null) notes += "No separate rear ultra-wide sensor detected."
        if (main == null) notes += "No rear main sensor detected."
        if (front == null) notes += "No openable front camera detected."
        return RoleMapping(front, main, wide, notes)
    }

    /**
     * Candidate topologies in preference order. Each still needs a session-configuration query and a
     * live test; nothing here assumes the device can actually stream them simultaneously.
     */
    fun candidateTopologies(sensors: List<SensorInfo>, mapping: RoleMapping, concurrentSets: Set<Set<String>>): List<CameraTopology> {
        val out = mutableListOf<CameraTopology>()
        val front = mapping.front
        val main = mapping.main
        val wide = mapping.wide
        val logicalRear = sensors.filter { it.isBack && it.isLogicalMultiCamera && it.openable }

        fun concurrent(vararg ids: String) = concurrentSets.any { set -> ids.all { it in set } }

        // How to open the main camera as an independent device: its own id, or the default logical rear camera.
        val mainDeviceId = when {
            main == null -> null
            main.openable -> main.id
            else -> logicalRear.firstOrNull { main.id in it.physicalIds }?.id
        }
        val mainPhysical = if (main != null && mainDeviceId != null && mainDeviceId != main.id) main.id else null

        // Architecture A
        if (front != null && main != null && wide != null && wide.openable && mainDeviceId != null && mainDeviceId != wide.id) {
            val t = CameraTopology(
                CaptureArchitecture.INDEPENDENT_CONCURRENT,
                listOf(
                    StreamTarget(CameraRole.FRONT, front.id, null, front.id),
                    StreamTarget(CameraRole.REAR_MAIN, mainDeviceId, mainPhysical, main.id),
                    StreamTarget(CameraRole.REAR_WIDE, wide.id, null, wide.id),
                ),
                "Front ${front.id}, main $mainDeviceId${mainPhysical?.let { ":$it" } ?: ""}, wide ${wide.id} as three devices" +
                    if (concurrent(front.id, mainDeviceId, wide.id)) "" else " (NOT listed in getConcurrentCameraIds)",
            )
            out += t
        }
        // Architecture B
        if (main != null && wide != null) {
            logicalRear.filter { main.id in it.physicalIds && wide.id in it.physicalIds }.forEach { logical ->
                if (front != null) {
                    out += CameraTopology(
                        CaptureArchitecture.LOGICAL_PHYSICAL_PLUS_FRONT,
                        listOf(
                            StreamTarget(CameraRole.FRONT, front.id, null, front.id),
                            StreamTarget(CameraRole.REAR_MAIN, logical.id, main.id, main.id),
                            StreamTarget(CameraRole.REAR_WIDE, logical.id, wide.id, wide.id),
                        ),
                        "Front ${front.id} + logical ${logical.id} (physical main ${main.id}, wide ${wide.id})" +
                            if (concurrent(front.id, logical.id)) "" else " (front+logical NOT listed as concurrent)",
                    )
                }
                out += CameraTopology(
                    CaptureArchitecture.PARTIAL,
                    listOf(
                        StreamTarget(CameraRole.REAR_MAIN, logical.id, main.id, main.id),
                        StreamTarget(CameraRole.REAR_WIDE, logical.id, wide.id, wide.id),
                    ),
                    "Logical ${logical.id}: main + wide only (no front)",
                )
            }
        }
        // Two independent devices: front + main
        if (front != null && main != null && mainDeviceId != null) {
            out += CameraTopology(
                CaptureArchitecture.PARTIAL,
                listOf(
                    StreamTarget(CameraRole.FRONT, front.id, null, front.id),
                    StreamTarget(CameraRole.REAR_MAIN, mainDeviceId, mainPhysical, main.id),
                ),
                "Front ${front.id} + main $mainDeviceId (no ultra-wide)",
            )
        }
        if (main != null && mainDeviceId != null) {
            out += CameraTopology(
                CaptureArchitecture.PARTIAL,
                listOf(StreamTarget(CameraRole.REAR_MAIN, mainDeviceId, mainPhysical, main.id)),
                "Main camera only",
            )
        }
        return out
    }

    companion object {
        private const val TAG = "CameraInventory"
    }
}
