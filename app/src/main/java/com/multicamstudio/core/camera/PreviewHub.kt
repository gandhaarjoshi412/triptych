package com.multicamstudio.core.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.ImageReader
import android.os.Process
import android.os.SystemClock
import android.util.Size
import com.multicamstudio.core.common.LooperThread
import com.multicamstudio.domain.model.CameraRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class PreviewFrame(val bitmap: Bitmap, val sequence: Long)

/**
 * Low-resolution, low-rate previews that never block recording.
 *
 * - Each camera gets a small YUV ImageReader (maxImages=2). Every image is acquired and closed promptly,
 *   so the camera never stalls on the preview output even when rendering is off.
 * - Conversion happens on a background-priority thread at [targetFps] (default 5 fps), not at 30 fps.
 * - Self-protection: if conversion gets slow, rate is halved, then preview is suspended for 10 s.
 * - When the UI is not visible ([setRenderingEnabled] false) images are dropped without conversion.
 */
class PreviewHub {
    private val thread = LooperThread("preview", Process.THREAD_PRIORITY_BACKGROUND)
    private val readers = ConcurrentHashMap<CameraRole, ImageReader>()
    private val frames = CameraRole.entries.associateWith { MutableStateFlow<PreviewFrame?>(null) }
    private val lastRenderMs = mutableMapOf<CameraRole, Long>()
    private val orientation = ConcurrentHashMap<CameraRole, Pair<Int, Boolean>>()

    @Volatile private var renderingEnabled = true
    @Volatile private var userEnabled = true
    @Volatile var targetFps = 5
        private set
    @Volatile private var effectiveFps = 5
    @Volatile private var suspendedUntilMs = 0L
    private var avgConvertMs = 0.0
    private var seq = 0L

    private val _status = MutableStateFlow("Preview idle")
    val status: StateFlow<String> = _status.asStateFlow()

    fun frame(role: CameraRole): StateFlow<PreviewFrame?> = frames.getValue(role).asStateFlow()

    fun configure(enabled: Boolean, fps: Int) {
        userEnabled = enabled
        targetFps = fps.coerceIn(1, 15)
        effectiveFps = targetFps
        _status.value = if (enabled) "Preview ${targetFps} fps" else "Preview off"
    }

    fun setRenderingEnabled(enabled: Boolean) {
        renderingEnabled = enabled
    }

    /** Creates (or recreates) the preview surface for [role]. */
    fun createReader(role: CameraRole, size: Size, sensorOrientation: Int, mirror: Boolean, deviceRotationDeg: Int): ImageReader {
        releaseReader(role)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        orientation[role] = ((sensorOrientation - (if (mirror) -deviceRotationDeg else deviceRotationDeg) + 360) % 360) to mirror
        reader.setOnImageAvailableListener({ r -> onImage(role, r) }, thread.handler)
        readers[role] = reader
        return reader
    }

    fun releaseReader(role: CameraRole) {
        readers.remove(role)?.let { r ->
            thread.handler.post { runCatching { r.close() } }
        }
        frames.getValue(role).value = null
    }

    fun releaseAll() {
        CameraRole.entries.forEach { releaseReader(it) }
        _status.value = "Preview idle"
    }

    private fun onImage(role: CameraRole, reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (!userEnabled || !renderingEnabled || now < suspendedUntilMs) return
            val interval = 1000L / effectiveFps
            if (now - (lastRenderMs[role] ?: 0L) < interval) return
            lastRenderMs[role] = now
            val t0 = SystemClock.elapsedRealtimeNanos()
            val bmp = yuvToBitmap(image)
            val (rot, mirror) = orientation[role] ?: (0 to false)
            val out = if (rot != 0 || mirror) {
                val m = Matrix().apply {
                    postRotate(rot.toFloat())
                    if (mirror) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false).also { if (it !== bmp) bmp.recycle() }
            } else bmp
            frames.getValue(role).value = PreviewFrame(out, ++seq)
            adapt((SystemClock.elapsedRealtimeNanos() - t0) / 1e6, now)
        } finally {
            image.close()
        }
    }

    private fun adapt(convertMs: Double, now: Long) {
        avgConvertMs = avgConvertMs * 0.8 + convertMs * 0.2
        when {
            avgConvertMs > 60 -> {
                suspendedUntilMs = now + 10_000
                avgConvertMs = 0.0
                _status.value = "Preview paused (device busy)"
            }
            avgConvertMs > 25 && effectiveFps > 1 -> {
                effectiveFps = (effectiveFps / 2).coerceAtLeast(1)
                _status.value = "Preview reduced to $effectiveFps fps"
            }
            avgConvertMs < 8 && effectiveFps < targetFps -> {
                effectiveFps++
                _status.value = "Preview $effectiveFps fps"
            }
        }
    }

    /** Straightforward YUV_420_888 -> ARGB conversion at the (small) preview size, honouring row/pixel strides. */
    private fun yuvToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuf = y.buffer
        val uBuf = u.buffer
        val vBuf = v.buffer
        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            val yRow = row * y.rowStride
            val uvRow = (row shr 1)
            for (col in 0 until w) {
                val yy = (yBuf.get(yRow + col * y.pixelStride).toInt() and 0xff) - 16
                val uvCol = col shr 1
                val uu = (uBuf.get(uvRow * u.rowStride + uvCol * u.pixelStride).toInt() and 0xff) - 128
                val vv = (vBuf.get(uvRow * v.rowStride + uvCol * v.pixelStride).toInt() and 0xff) - 128
                val c = if (yy < 0) 0 else 1192 * yy
                var r = (c + 1634 * vv) shr 10
                var g = (c - 833 * vv - 400 * uu) shr 10
                var b = (c + 2066 * uu) shr 10
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                pixels[row * w + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
