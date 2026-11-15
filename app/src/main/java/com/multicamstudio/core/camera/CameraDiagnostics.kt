package com.multicamstudio.core.camera

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import com.multicamstudio.core.common.LooperThread
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.core.media.EncoderCatalog
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.VideoResolution
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class LiveTestResult(
    val topology: CameraTopology,
    val withPreview: Boolean,
    val ok: Boolean,
    val fpsByRole: Map<CameraRole, Double>,
    val error: String?,
) {
    val sustains30: Boolean get() = ok && fpsByRole.size == topology.targets.size && fpsByRole.values.all { it >= 29.0 }
}

/**
 * Hardware feasibility investigation (spec §4.2/4.3). Produces a plain-text report that states clearly
 * whether three-camera 1080p30 is supported on this device, and why.
 *
 * The live test streams into PRIVATE ImageReaders flagged VIDEO_ENCODE (same stream class as an encoder
 * surface) and counts real frames per stream for a few seconds. It does not encode; the encoder stress
 * test and a real 15-minute recording cover the rest.
 */
class CameraDiagnostics(private val context: Context, private val storage: SessionStorage) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val resolver = TopologyResolver(manager)
    private val inventory = CameraInventory(manager)

    suspend fun run(liveTest: Boolean, overrides: CameraOverrides, onProgress: (String) -> Unit): String {
        val sb = StringBuilder()
        fun line(s: String = "") = sb.appendLine(s)

        line("# MultiCam Studio – hardware diagnostic report")
        line("Generated: ${java.time.ZonedDateTime.now()}")
        line()
        line("## Device")
        line("Manufacturer/model: ${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT})")
        line("Android release: ${Build.VERSION.RELEASE}  SDK/API: ${Build.VERSION.SDK_INT}  security patch: ${Build.VERSION.SECURITY_PATCH}")
        line("Build display (Nothing OS / firmware): ${Build.DISPLAY}")
        line("Build id/incremental: ${Build.ID} / ${Build.VERSION.INCREMENTAL}")
        line("Fingerprint: ${Build.FINGERPRINT}")
        line("SoC: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "n/a"}")
        val mi = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        line("RAM: total ${mi.totalMem.formatBytes()}, available ${mi.availMem.formatBytes()}")
        line("Storage (app volume): free ${storage.freeBytes().formatBytes()} of ${storage.totalBytes().formatBytes()}")
        line()

        onProgress("Enumerating cameras…")
        val sensors = inventory.enumerate()
        line("## Cameras (getCameraIdList + hidden physical cameras)")
        line("getCameraIdList(): ${manager.cameraIdList.joinToString()}")
        sensors.forEach { s ->
            line("- id ${s.id}${if (!s.openable) " (physical only, via ${s.logicalParents.joinToString()})" else ""}: facing=${facing(s.facing)} " +
                "logicalMulti=${s.isLogicalMultiCamera} physicalIds=${s.physicalIds} syncType=${s.syncTypeLabel()} " +
                "hwLevel=${s.hardwareLevel} tsSource=${s.timestampSourceLabel()} orientation=${s.sensorOrientation}")
            line("    focal=${s.focalLengths} sensorWidth=${s.sensorWidthMm}mm pixelArray=${s.pixelArray} hFOV=${s.horizontalFovDeg?.let { "%.1f°".format(it) }}")
            line("    fpsRanges=${s.fpsRanges} fixed30=${s.fixedFpsRange(30)}")
            line("    MediaCodec 1080p30=${s.supportsEncoderSize(VideoResolution.P1080)} (minFrameDur=${s.minFrameDuration1080}) 720p30=${s.supportsEncoderSize(VideoResolution.P720)}")
            line("    encoder sizes: ${s.encoderSizes.take(12).joinToString()}${if (s.encoderSizes.size > 12) " …" else ""}")
            line("    capabilities: ${s.capabilities}")
        }
        line()

        line("## Concurrent camera combinations (getConcurrentCameraIds)")
        val concurrent = resolver.concurrentSets()
        if (concurrent.isEmpty()) line("(none reported – concurrent camera streaming is not advertised)") else concurrent.forEach { line("- $it") }
        line()
        line("## Mandatory concurrent stream combinations (SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS)")
        manager.cameraIdList.forEach { id ->
            val combos = runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS) }.getOrNull()
            line("- camera $id: ${combos?.size ?: 0} combination(s)")
            combos?.take(8)?.forEach { c ->
                val streams = c.streamsInformation.joinToString { si -> "fmt=${si.format} max=${si.availableSizes.maxByOrNull { it.width * it.height }}" }
                line("    • ${c.description}: $streams")
            }
        }
        line()

        val mapping = inventory.identifyRoles(sensors, overrides)
        line("## Role mapping")
        line("FRONT=${mapping.front?.id}  REAR_MAIN=${mapping.main?.id}  REAR_WIDE=${mapping.wide?.id}")
        mapping.notes.forEach { line("note: $it") }
        line()

        onProgress("Querying session configurations…")
        val candidates = inventory.candidateTopologies(sensors, mapping, concurrent)
        line("## Framework support queries (1080p encoder-class streams)")
        val queryResults = candidates.associateWith { c ->
            listOf(true, false).associateWith { p -> resolver.query(c, sensors, VideoResolution.P1080, p, 320) }
        }
        queryResults.forEach { (c, r) -> line("- ${c.architecture.label}: ${c.description}\n    with preview=${r[true]}  without preview=${r[false]}") }
        line()

        val live = mutableListOf<LiveTestResult>()
        if (liveTest) {
            line("## Live capture tests (4 s each, frames counted from real camera output)")
            val thread = LooperThread("diag-camera")
            try {
                for (c in candidates) {
                    val r = queryResults[c]!!
                    if (r[true] == QueryResult.UNSUPPORTED && r[false] == QueryResult.UNSUPPORTED) {
                        line("- SKIPPED (framework reports unsupported): ${c.description}")
                        continue
                    }
                    for (withPreview in listOf(true, false)) {
                        if (r[withPreview] == QueryResult.UNSUPPORTED) continue
                        onProgress("Live test: ${c.description} preview=$withPreview")
                        val res = liveTest(c, sensors, withPreview, thread)
                        live += res
                        line("- ${c.architecture.name} preview=$withPreview: ${if (res.ok) "STREAMED" else "FAILED"} " +
                            res.fpsByRole.entries.joinToString { "${it.key}=%.1f fps".format(it.value) } + (res.error?.let { " error=$it" } ?: ""))
                        if (res.ok) break
                        delay(500)
                    }
                }
            } finally {
                thread.quit()
            }
            line()
        }

        onProgress("Inspecting encoders…")
        line("## Video encoders")
        listOf("video/hevc", "video/avc").forEach { mime ->
            line("### $mime")
            EncoderCatalog.list(mime).forEach { e ->
                line("- ${e.name}: hw=${e.hardwareAccelerated} swOnly=${e.softwareOnly} vendor=${e.vendor} 1080p30=${e.supports1080p30} 720p30=${e.supports720p30} " +
                    "bitrate=${e.bitrateMin}-${e.bitrateMax} VBR=${e.vbr} CBR=${e.cbr} CQ=${e.cq} maxInstances=${e.maxInstances} hevcMain=${e.hevcMainProfile}")
                line("    perf points cover 1080p30=${e.perfCovers1080p30} 1080p90(≈3×1080p30, indication only)=${e.perfCovers1080p90Aggregate}")
                if (e.performancePoints.isNotEmpty()) line("    points: ${e.performancePoints.take(10).joinToString()}")
            }
            val hw = EncoderCatalog.selectHardware(mime, VideoResolution.P1080)
            line("Selected hardware encoder for 1080p30: ${hw?.name ?: "NONE (software fallback is never used silently)"}")
        }
        line("AAC encoders: ${EncoderCatalog.audioEncoders().joinToString()}")
        line("NOTE: performance points describe a single codec instance. Three simultaneous encoders must be verified with the encoder stress test and a real recording.")
        line()

        line("## Conclusion")
        val three = candidates.filter { it.targets.size == 3 }
        val threeQuery = three.any { c -> queryResults[c]!!.values.any { it == QueryResult.SUPPORTED } }
        val threeLive = live.filter { it.topology.targets.size == 3 }
        val verdict = when {
            threeLive.any { it.sustains30 } -> "SUPPORTED: three-camera 1080p30 streamed live at ≥29 fps per camera (${threeLive.first { it.sustains30 }.topology.description})."
            threeLive.any { it.ok } -> "PARTIAL: three cameras streamed but at least one stayed below 29 fps – 1080p30 is not sustained; expect 720p or fewer cameras."
            liveTest && three.isNotEmpty() -> "NOT SUPPORTED: no three-camera configuration could stream on this device/firmware."
            threeQuery -> "QUERY SAYS SUPPORTED, NOT YET VERIFIED LIVE – run the live test."
            three.isEmpty() -> "NOT SUPPORTED: the device does not expose front, main and ultra-wide in a combinable way."
            else -> "NOT SUPPORTED by framework queries (run the live test for confirmation)."
        }
        line("Three-camera 1080p30: $verdict")
        val best = resolver.resolve(overrides)
        line("App will use: ${best?.topology?.description ?: "no usable configuration"} (query=${best?.verification}, preview=${best?.previewAllowed})")
        line("Encoder throughput and thermal behaviour are NOT covered by this report – run the encoder stress test and a 15-minute recording.")
        return sb.toString()
    }

    private suspend fun liveTest(t: CameraTopology, sensors: List<SensorInfo>, withPreview: Boolean, thread: LooperThread): LiveTestResult {
        val readers = mutableListOf<ImageReader>()
        val counters = mutableMapOf<CameraRole, AtomicLong>()
        val handles = mutableListOf<CameraDeviceHandle>()
        val listener = object : CameraDeviceListener {
            override fun onDisconnected(deviceId: String) = Unit
            override fun onError(deviceId: String, error: Int) = Unit
        }
        return try {
            for (deviceId in t.deviceIds) {
                val h = CameraDeviceHandle.open(manager, deviceId, thread, listener)
                handles += h
                val outs = t.targetsFor(deviceId).flatMap { target ->
                    val counter = AtomicLong().also { counters[target.role] = it }
                    val enc = ProbeSurfaces.encoderLike(1920, 1080).also { readers += it }
                    enc.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.let { counter.incrementAndGet(); it.close() } }, thread.handler)
                    val list = mutableListOf(StreamOutput(target.role, target.physicalId, enc.surface, OutputKind.ENCODER))
                    if (withPreview) {
                        val sensor = sensors.firstOrNull { it.id == target.sensorId }
                        PreviewSizes.choose(sensor?.yuvSizes.orEmpty(), 320)?.let { size ->
                            val pr = ProbeSurfaces.previewLike(size.width, size.height).also { readers += it }
                            pr.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, thread.handler)
                            list += StreamOutput(target.role, target.physicalId, pr.surface, OutputKind.PREVIEW)
                        }
                    }
                    list
                }
                val sensor = sensors.firstOrNull { it.id == t.targetsFor(deviceId).first().sensorId } ?: sensors.firstOrNull { it.id == deviceId }
                h.configure(outs, sensor?.fixedFpsRange(30) ?: android.util.Range(30, 30), listener)
            }
            delay(1_000) // warm-up (AE convergence, pipeline fill)
            val start = counters.mapValues { it.value.get() }
            val t0 = SystemClock.elapsedRealtime()
            delay(4_000)
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val fps = counters.mapValues { (role, c) -> (c.get() - start.getValue(role)) / secs }
            LiveTestResult(t, withPreview, fps.values.all { it > 0 }, fps, null)
        } catch (e: Exception) {
            LiveTestResult(t, withPreview, false, counters.mapValues { 0.0 }, e.message ?: e.javaClass.simpleName)
        } finally {
            handles.forEach { it.close() }
            withContext(NonCancellable) { delay(300) }
            readers.forEach { runCatching { it.close() } }
        }
    }

    private fun facing(f: Int?) = when (f) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }
}
