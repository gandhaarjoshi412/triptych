package com.multicamstudio.core.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.domain.model.VideoResolution
import java.util.concurrent.Executor

enum class QueryResult { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class ResolvedTopology(
    val topology: CameraTopology,
    val previewAllowed: Boolean,
    val verification: QueryResult,
    val attempts: List<String>,
    val mapping: RoleMapping,
    val sensors: List<SensorInfo>,
)

/**
 * Chooses a capture topology using the framework's own answers rather than trial-and-error opening:
 *  - getConcurrentCameraIds() for which devices may stream together,
 *  - isConcurrentSessionConfigurationSupported() for the exact multi-device stream set,
 *  - CameraDeviceSetup.isSessionConfigurationSupported() (API 35+) for single-device sets.
 * Preference: three cameras with preview > three cameras without preview > partial topologies.
 */
class TopologyResolver(private val manager: CameraManager) {
    private val inventory = CameraInventory(manager)
    private val directExecutor = Executor { it.run() }
    private val noopCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
    }

    fun concurrentSets(): Set<Set<String>> = runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet())

    fun resolve(overrides: CameraOverrides, resolution: VideoResolution = VideoResolution.P1080, previewWidth: Int = 320): ResolvedTopology? {
        val sensors = inventory.enumerate()
        val mapping = inventory.identifyRoles(sensors, overrides)
        val concurrent = concurrentSets()
        val candidates = inventory.candidateTopologies(sensors, mapping, concurrent)
        val attempts = mutableListOf<String>()
        val accepted = mutableListOf<Triple<CameraTopology, Boolean, QueryResult>>()
        for (c in candidates) {
            for (withPreview in listOf(true, false)) {
                val r = query(c, sensors, resolution, withPreview, previewWidth)
                attempts += "${c.architecture.name} [${c.description}] preview=$withPreview -> $r"
                if (r != QueryResult.UNSUPPORTED) {
                    accepted += Triple(c, withPreview, r)
                    break
                }
            }
        }
        // Prefer: most cameras, then verified over unknown, then with preview.
        val best = accepted.sortedWith(
            compareByDescending<Triple<CameraTopology, Boolean, QueryResult>> { it.first.targets.size }
                .thenByDescending { it.third == QueryResult.SUPPORTED }
                .thenByDescending { it.second }
        ).firstOrNull() ?: return null
        AppLog.i(TAG, "Selected ${best.first.description} preview=${best.second} (${best.third})")
        return ResolvedTopology(best.first, best.second, best.third, attempts, mapping, sensors)
    }

    /** Queries support for [topology] using stand-in encoder (+ optional preview) surfaces. */
    fun query(topology: CameraTopology, sensors: List<SensorInfo>, resolution: VideoResolution, withPreview: Boolean, previewWidth: Int): QueryResult {
        val readers = mutableListOf<ImageReader>()
        try {
            val byDevice = topology.deviceIds.associateWith { deviceId ->
                val outs = topology.targetsFor(deviceId).flatMap { t ->
                    val enc = ProbeSurfaces.encoderLike(resolution.width, resolution.height).also { readers += it }
                    val list = mutableListOf(StreamOutput(t.role, t.physicalId, enc.surface, OutputKind.ENCODER))
                    if (withPreview) {
                        val sensor = sensors.firstOrNull { it.id == t.sensorId }
                        val size = sensor?.yuvSizes?.let { PreviewSizes.choose(it, previewWidth) }
                        if (size != null) {
                            val pr = ProbeSurfaces.previewLike(size.width, size.height).also { readers += it }
                            list += StreamOutput(t.role, t.physicalId, pr.surface, OutputKind.PREVIEW)
                        }
                    }
                    list
                }
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outs.map { CameraDeviceHandle.outputConfiguration(it) },
                    directExecutor,
                    noopCallback,
                )
            }
            return if (byDevice.size > 1) {
                try {
                    if (manager.isConcurrentSessionConfigurationSupported(byDevice)) QueryResult.SUPPORTED else QueryResult.UNSUPPORTED
                } catch (e: IllegalArgumentException) {
                    // Thrown when the id combination is not one of getConcurrentCameraIds().
                    QueryResult.UNSUPPORTED
                }
            } else {
                singleDeviceQuery(byDevice.keys.first(), byDevice.values.first())
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Query failed for ${topology.description}", e)
            return QueryResult.UNKNOWN
        } finally {
            readers.forEach { runCatching { it.close() } }
        }
    }

    private fun singleDeviceQuery(deviceId: String, cfg: SessionConfiguration): QueryResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return QueryResult.UNKNOWN
        return try {
            if (!manager.isCameraDeviceSetupSupported(deviceId)) return QueryResult.UNKNOWN
            val setup = manager.getCameraDeviceSetup(deviceId)
            if (setup.isSessionConfigurationSupported(cfg)) QueryResult.SUPPORTED else QueryResult.UNSUPPORTED
        } catch (e: Exception) {
            QueryResult.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "TopologyResolver"
    }
}

object PreviewSizes {
    fun choose(supported: List<android.util.Size>, targetWidth: Int): android.util.Size? {
        val sixteenNine = supported.filter { kotlin.math.abs(it.width * 9 - it.height * 16) <= 16 && it.width >= 160 }
        val pool = sixteenNine.ifEmpty { supported }
        return pool.minByOrNull { kotlin.math.abs(it.width - targetWidth) }
    }
}
