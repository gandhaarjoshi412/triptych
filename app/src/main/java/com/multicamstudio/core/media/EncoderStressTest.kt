package com.multicamstudio.core.media

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.PowerManager
import android.os.SystemClock
import com.multicamstudio.core.common.LooperThread
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong

data class StressStreamResult(val index: Int, val codecName: String, val framesIn: Long, val framesOut: Long, val outputFps: Double, val errors: List<String>)

data class StressTestResult(
    val mime: String,
    val streams: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val durationSec: Double,
    val results: List<StressStreamResult>,
    val thermalStatusStart: Int,
    val thermalStatusEnd: Int,
    val maxThermalStatus: Int,
    val setupError: String?,
) {
    fun summary(): String = buildString {
        appendLine("Encoder stress test: $streams x $mime ${width}x$height@30 bitrate=$bitrate for %.0fs".format(durationSec))
        setupError?.let { appendLine("SETUP FAILED: $it") }
        results.forEach { r ->
            appendLine("  stream ${r.index} ${r.codecName}: in=${r.framesIn} out=${r.framesOut} -> %.1f fps%s".format(r.outputFps, if (r.errors.isEmpty()) "" else " errors=${r.errors}"))
        }
        appendLine("  thermal: start=$thermalStatusStart end=$thermalStatusEnd max=$maxThermalStatus")
        appendLine("  NOTE: synthetic canvas input, no camera. Validates concurrent encoder throughput only; run a real recording to validate the full pipeline.")
    }
}

/**
 * Runs N hardware encoders concurrently with synthetic 30 fps input drawn via Surface.lockHardwareCanvas().
 * Measures real encoder output rates. Output is discarded (no muxing) to isolate encoder throughput.
 */
class EncoderStressTest(private val power: PowerManager) {

    suspend fun run(mime: String, streams: Int, width: Int, height: Int, bitrate: Int, durationSec: Int, onProgress: (String) -> Unit): StressTestResult {
        val codecInfo = EncoderCatalog.list(mime).filter { it.hardwareAccelerated && !it.softwareOnly }.sortedByDescending { it.vendor }.firstOrNull()
            ?: return StressTestResult(mime, streams, width, height, bitrate, 0.0, emptyList(), 0, 0, 0, "No hardware encoder for $mime")
        val thermalStart = power.currentThermalStatus
        var maxThermal = thermalStart
        val codecs = mutableListOf<MediaCodec>()
        val threads = mutableListOf<LooperThread>()
        val outCounts = List(streams) { AtomicLong() }
        val inCounts = List(streams) { AtomicLong() }
        val errors = List(streams) { mutableListOf<String>() }
        val surfaces = mutableListOf<android.view.Surface>()
        try {
            repeat(streams) { i ->
                val t = LooperThread("stress-$i")
                threads += t
                val codec = MediaCodec.createByCodecName(codecInfo.name)
                codecs += codec
                val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (codecInfo.vbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                codec.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) outCounts[i].incrementAndGet()
                        codec.releaseOutputBuffer(index, false)
                    }
                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        synchronized(errors[i]) { errors[i] += "err ${e.errorCode} ${e.diagnosticInfo}" }
                    }
                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
                }, t.handler)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surfaces += codec.createInputSurface()
                codec.start()
            }
        } catch (e: Exception) {
            cleanup(codecs, surfaces, threads)
            return StressTestResult(mime, streams, width, height, bitrate, 0.0, emptyList(), thermalStart, power.currentThermalStatus, maxThermal,
                "Could not start ${codecs.size + 1}th encoder instance: ${e.message}")
        }

        val paint = Paint().apply { color = Color.WHITE; textSize = 64f }
        val start = SystemClock.elapsedRealtime()
        val periodMs = 1000.0 / 30
        var frame = 0L
        while (coroutineContext.isActive && SystemClock.elapsedRealtime() - start < durationSec * 1000L) {
            surfaces.forEachIndexed { i, s ->
                runCatching {
                    val c = s.lockHardwareCanvas()
                    c.drawColor(Color.rgb((frame * 3 % 255).toInt(), (i * 80 % 255), 120))
                    c.drawText("stream $i frame $frame", 40f, 120f + (frame % 200), paint)
                    s.unlockCanvasAndPost(c)
                    inCounts[i].incrementAndGet()
                }.onFailure { e -> synchronized(errors[i]) { if (errors[i].size < 5) errors[i] += "draw: ${e.message}" } }
            }
            frame++
            if (frame % 30 == 0L) {
                maxThermal = maxOf(maxThermal, power.currentThermalStatus)
                onProgress("Encoding ${frame / 30}s / ${durationSec}s, outputs=${outCounts.map { it.get() }}")
            }
            val nextAt = start + ((frame) * periodMs).toLong()
            val wait = nextAt - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
        }
        val elapsed = (SystemClock.elapsedRealtime() - start) / 1000.0
        cleanup(codecs, surfaces, threads)
        return StressTestResult(
            mime, streams, width, height, bitrate, elapsed,
            List(streams) { i -> StressStreamResult(i, codecInfo.name, inCounts[i].get(), outCounts[i].get(), outCounts[i].get() / elapsed, errors[i].toList()) },
            thermalStart, power.currentThermalStatus, maxThermal, null,
        )
    }

    private fun cleanup(codecs: List<MediaCodec>, surfaces: List<android.view.Surface>, threads: List<LooperThread>) {
        codecs.forEach { runCatching { it.stop() }; runCatching { it.release() } }
        surfaces.forEach { runCatching { it.release() } }
        threads.forEach { it.quit() }
    }
}
