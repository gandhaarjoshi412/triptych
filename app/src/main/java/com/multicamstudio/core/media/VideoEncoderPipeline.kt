package com.multicamstudio.core.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.common.LooperThread
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.VideoResolution
import com.multicamstudio.domain.timing.ClockBase
import com.multicamstudio.domain.timing.ClockBaseDetector
import com.multicamstudio.domain.timing.ClockPair
import com.multicamstudio.domain.timing.FrameStatsTracker
import com.multicamstudio.domain.timing.SessionTimeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class EncoderConfig(
    val role: CameraRole,
    val codecName: String,
    val mime: String,
    val resolution: VideoResolution,
    val fps: Int,
    val bitrate: Int,
    val useVbr: Boolean,
    val hevcMainProfile: Boolean,
    /** Degrees for MediaMuxer.setOrientationHint so players display the clip upright. */
    val orientationHint: Int,
)

data class SegmentResult(
    val role: CameraRole,
    val index: Int,
    val file: File,
    val resolution: VideoResolution,
    val startSessionNs: Long?,
    val endSessionNs: Long?,
    val frames: Long,
    val droppedFrames: Long,
    val sizeBytes: Long,
    val finalized: Boolean,
    val error: String?,
)

/**
 * One camera -> one hardware encoder -> segmented MP4 files.
 *
 * - Input is the encoder's input Surface written directly by the camera (zero copy).
 * - Output timestamps are the camera capture timestamps, mapped onto the session timeline.
 * - Segments roll on the first key frame after [requestSegmentRoll]; each segment is written as
 *   `<name>.mp4.inprogress` and renamed to `.mp4` only after MediaMuxer.stop() succeeds.
 * - Frames are never duplicated (KEY_REPEAT_PREVIOUS_FRAME_AFTER is deliberately not set).
 * - All codec and muxer work runs on this pipeline's own thread.
 */
class VideoEncoderPipeline(
    val config: EncoderConfig,
    private val outputDir: File,
    private val fileNameForIndex: (Int) -> String,
    firstSegmentIndex: Int,
    private val timeline: SessionTimeline,
    private val listener: Listener,
) {
    interface Listener {
        fun onSegmentOpened(role: CameraRole, index: Int, inProgressFile: File, finalFile: File, resolution: VideoResolution)
        fun onSegmentClosed(result: SegmentResult)
        fun onClockBaseDetected(role: CameraRole, base: ClockBase, ambiguous: Boolean)
        fun onEncoderError(role: CameraRole, message: String, fatal: Boolean)
    }

    val role get() = config.role
    val stats = FrameStatsTracker(config.fps)
    private val thread = LooperThread("enc-${config.role.dirName}", Process.THREAD_PRIORITY_VIDEO)
    private val codec: MediaCodec = MediaCodec.createByCodecName(config.codecName)
    lateinit var inputSurface: Surface
        private set

    private var outputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var segmentIndex = firstSegmentIndex
    private var segmentFile: File? = null
    private var segmentFinal: File? = null
    private var segStartNs: Long? = null
    private var segEndNs: Long? = null
    private var segFrames = 0L
    private var segDropsAtStart = 0L
    private var lastWrittenUs = -1L
    private var clockBase: ClockBase? = null

    @Volatile private var rollRequested = false
    @Volatile var released = false
        private set
    private val eos = CompletableDeferred<Unit>()
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Next segment index this pipeline would use (for continuing numbering after a reconfiguration). */
    val nextSegmentIndex: Int get() = segmentIndex + if (openedAny) 1 else 0
    private var openedAny = false

    fun start() {
        val format = MediaFormat.createVideoFormat(config.mime, config.resolution.width, config.resolution.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            if (config.useVbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (config.mime == MediaFormat.MIMETYPE_VIDEO_HEVC && config.hevcMainProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        codec.setCallback(callback, thread.handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        AppLog.i(TAG, "${config.role} encoder ${config.codecName} ${config.resolution}@${config.fps} ${config.bitrate}bps vbr=${config.useVbr}")
    }

    /** Thread-safe. The roll happens on the next key frame; a sync frame is requested to make that prompt. */
    fun requestSegmentRoll() {
        rollRequested = true
        thread.handler.post {
            if (!released) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                handleOutput(index, info)
            } catch (e: Exception) {
                AppLog.e(TAG, "${config.role} output handling failed", e)
                listener.onEncoderError(config.role, "Muxer/output error: ${e.message}", fatal = true)
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            val fatal = !e.isRecoverable && !e.isTransient
            AppLog.e(TAG, "${config.role} codec error fatal=$fatal: ${e.diagnosticInfo}", e)
            listener.onEncoderError(config.role, "Encoder error ${e.errorCode}: ${e.diagnosticInfo}", fatal)
            if (fatal) {
                closeSegment(error = "encoder error")
                eos.complete(Unit)
            }
        }
    }

    private fun handleOutput(index: Int, info: MediaCodec.BufferInfo) {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        if (!isConfig && info.size > 0) {
            val buf = codec.getOutputBuffer(index)
            if (buf != null) writeFrame(buf, info)
        }
        codec.releaseOutputBuffer(index, false)
        if (isEos) {
            closeSegment(error = null)
            eos.complete(Unit)
        }
    }

    private fun writeFrame(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val ptsNs = info.presentationTimeUs * 1000
        val base = clockBase ?: run {
            val r = ClockBaseDetector.detect(ptsNs, ClockPair(SystemClock.elapsedRealtimeNanos(), System.nanoTime()))
            clockBase = r.base
            listener.onClockBaseDetected(config.role, r.base, r.ambiguous)
            r.base
        }
        val sessionNs = timeline.toSessionNs(ptsNs, base)
        if (sessionNs < 0) return // captured before the session origin; never part of the recording
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

        if (rollRequested && isKey && muxer != null && segFrames > 0) {
            rollRequested = false
            closeSegment(error = null)
            segmentIndex++
        }
        if (muxer == null) {
            if (!isKey) return // every segment must start with a key frame
            if (!openSegment()) return
            rollRequested = false
        }
        val outUs = sessionNs / 1000
        if (outUs <= lastWrittenUs) return // MediaMuxer requires strictly increasing timestamps
        val outInfo = MediaCodec.BufferInfo().apply { set(info.offset, info.size, outUs, info.flags) }
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        muxer!!.writeSampleData(track, buf, outInfo)
        lastWrittenUs = outUs
        if (segStartNs == null) segStartNs = sessionNs
        segEndNs = sessionNs
        segFrames++
        stats.onFrame(sessionNs)
    }

    private fun openSegment(): Boolean {
        val fmt = outputFormat ?: run {
            listener.onEncoderError(config.role, "No output format before first frame", fatal = false)
            return false
        }
        // Never overwrite an existing recording: skip to the next free index.
        while (File(outputDir, fileNameForIndex(segmentIndex)).exists() ||
            File(outputDir, fileNameForIndex(segmentIndex) + SessionStorage.IN_PROGRESS_SUFFIX).exists()
        ) segmentIndex++
        val finalFile = File(outputDir, fileNameForIndex(segmentIndex))
        val tmp = File(outputDir, finalFile.name + SessionStorage.IN_PROGRESS_SUFFIX)
        val m = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        m.setOrientationHint(config.orientationHint)
        track = m.addTrack(fmt)
        m.start()
        muxer = m
        openedAny = true
        segmentFile = tmp
        segmentFinal = finalFile
        segStartNs = null
        segEndNs = null
        segFrames = 0
        segDropsAtStart = stats.snapshot().droppedFrames
        listener.onSegmentOpened(config.role, segmentIndex, tmp, finalFile, config.resolution)
        return true
    }

    private fun closeSegment(error: String?) {
        val m = muxer ?: return
        val tmp = segmentFile!!
        val finalFile = segmentFinal!!
        muxer = null
        var finalized = false
        var err = error
        try {
            m.stop()
            finalized = true
        } catch (e: Exception) {
            err = (err?.let { "$it; " } ?: "") + "muxer stop failed: ${e.message}"
            AppLog.e(TAG, "${config.role} segment $segmentIndex stop failed", e)
        } finally {
            runCatching { m.release() }
        }
        val out = if (finalized && tmp.renameTo(finalFile)) finalFile else tmp
        if (finalized && out != finalFile) err = (err?.let { "$it; " } ?: "") + "rename failed"
        val drops = stats.snapshot().droppedFrames - segDropsAtStart
        listener.onSegmentClosed(
            SegmentResult(
                config.role, segmentIndex, out, config.resolution, segStartNs, segEndNs, segFrames, drops,
                out.length(), finalized && out == finalFile, err,
            )
        )
        segmentFile = null
        segmentFinal = null
    }

    /**
     * Graceful stop: the camera must already have stopped writing to [inputSurface].
     * Signals end-of-stream, waits for the final buffers and finalizes the open segment.
     */
    suspend fun stop(timeoutMs: Long = 5_000) {
        if (released) return
        thread.handler.post { runCatching { codec.signalEndOfInputStream() } }
        val ok = withTimeoutOrNull(timeoutMs) { eos.await() } != null
        if (!ok) {
            AppLog.w(TAG, "${config.role} EOS timeout; finalizing what was written")
            val done = CompletableDeferred<Unit>()
            thread.handler.post { closeSegment(error = null); done.complete(Unit) }
            withTimeoutOrNull(3_000) { done.await() }
        }
        withContext(Dispatchers.IO) { release() }
    }

    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        thread.handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (this::inputSurface.isInitialized) runCatching { inputSurface.release() }
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quit()
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
