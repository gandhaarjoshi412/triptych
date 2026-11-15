package com.multicamstudio.core.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.domain.timing.AudioDriftEstimator
import com.multicamstudio.domain.timing.SessionTimeline
import java.io.File

data class AudioRouteChange(val sessionTimeMs: Double, val deviceType: String, val deviceName: String)

data class AudioResult(
    val file: File,
    val finalized: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    /** Session time of the first captured sample, from AudioTimestamp (BOOTTIME) when available. */
    val firstSampleSessionNs: Long?,
    val timestampSource: String,
    val framesCaptured: Long,
    val driftPpm: Double?,
    val accumulatedDriftMs: Double?,
    val routes: List<AudioRouteChange>,
    val error: String?,
) {
    val durationNs: Long get() = framesCaptured * 1_000_000_000L / sampleRate
}

/**
 * One master audio recording for the whole session: AudioRecord (48 kHz mono PCM16) -> AAC-LC -> M4A.
 *
 * Synchronisation: AudioRecord.getTimestamp(TIMEBASE_BOOTTIME) maps a frame position to
 * elapsedRealtimeNanos – the session reference clock – so the first sample's session time is measured,
 * not assumed. Encoder PTS follow the sample count (gap-free); drift between sample count and the
 * hardware clock is measured and written to the manifest.
 */
class MasterAudioRecorder(
    private val finalFile: File,
    private val timeline: SessionTimeline,
    private val onError: (String) -> Unit,
    private val onRouteChanged: (AudioRouteChange) -> Unit,
    val sampleRate: Int = 48_000,
    val bitrate: Int = 160_000,
) {
    private val channels = 1
    private val tmpFile = File(finalFile.parentFile, finalFile.name + SessionStorage.IN_PROGRESS_SUFFIX)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var result: AudioResult? = null
    private val routes = mutableListOf<AudioRouteChange>()
    private val drift = AudioDriftEstimator(sampleRate)
    private var firstSampleSessionNs: Long? = null
    private var timestampSource = "none"
    private var framesCaptured = 0L
    private val routingHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked before recording starts
    fun start() {
        require(!finalFile.exists()) { "Refusing to overwrite ${finalFile.name}" }
        running = true
        thread = Thread({ runLoop() }, "master-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf * 4, sampleRate * 2 / 5) // >= 200 ms
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var muxerStarted = false
        var error: String? = null
        var finalized = false
        val routingListener = AudioRouting.OnRoutingChangedListener { router -> recordRoute(router.routedDevice) }
        try {
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(bufSize)
                .build()
            record = rec
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
            rec.addOnRoutingChangedListener(routingListener, routingHandler)

            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = enc
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mux = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            rec.startRecording()
            recordRoute(rec.routedDevice)
            val pcm = ByteArray(2048) // 1024 mono frames
            val ts = AudioTimestamp()
            var lastStampAt = 0L
            val info = MediaCodec.BufferInfo()

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        idx >= 0 -> {
                            val out = enc.getOutputBuffer(idx)
                            val cfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (out != null && info.size > 0 && !cfg && muxerStarted) {
                                out.position(info.offset); out.limit(info.offset + info.size)
                                mux.writeSampleData(track, out, info)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun queue(bytes: ByteArray, length: Int, ptsUs: Long, flags: Int) {
                var idx: Int
                var attempts = 0
                do {
                    idx = enc.dequeueInputBuffer(10_000)
                    if (idx < 0) drain(false)
                } while (idx < 0 && (running || flags != 0) && ++attempts < 300)
                if (idx < 0) return
                val inBuf = enc.getInputBuffer(idx)!!
                inBuf.clear()
                inBuf.put(bytes, 0, length)
                enc.queueInputBuffer(idx, 0, length, ptsUs, flags)
            }

            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) {
                    error = "AudioRecord.read error $n"
                    onError(error)
                    break
                }
                if (n == 0) continue
                val frames = (n / 2).toLong()
                val ptsUs = framesCaptured * 1_000_000L / sampleRate
                queue(pcm, n, ptsUs, 0)
                framesCaptured += frames
                drain(false)

                val now = SystemClock.elapsedRealtime()
                if (now - lastStampAt >= 1000 || firstSampleSessionNs == null) {
                    lastStampAt = now
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        drift.add(ts.framePosition, ts.nanoTime)
                        if (firstSampleSessionNs == null || timestampSource != "AudioTimestamp(BOOTTIME)") {
                            val frame0Ns = ts.nanoTime - ts.framePosition * 1_000_000_000L / sampleRate
                            firstSampleSessionNs = frame0Ns - timeline.originRealtimeNs
                            timestampSource = "AudioTimestamp(BOOTTIME)"
                        }
                    } else if (firstSampleSessionNs == null) {
                        // Estimate: the data just read ended "now"; buffer latency is unknown -> flagged as estimate.
                        val endNs = SystemClock.elapsedRealtimeNanos()
                        firstSampleSessionNs = endNs - framesCaptured * 1_000_000_000L / sampleRate - timeline.originRealtimeNs
                        timestampSource = "estimated(read-completion)"
                    }
                }
            }
            runCatching { rec.stop() }
            queue(ByteArray(0), 0, framesCaptured * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (muxerStarted) {
                mux.stop()
                finalized = true
            } else {
                error = error ?: "No audio data was encoded"
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Audio pipeline failure", e)
            error = (error?.let { "$it; " } ?: "") + (e.message ?: e.javaClass.simpleName)
            onError(error)
        } finally {
            runCatching { record?.removeOnRoutingChangedListener(routingListener) }
            runCatching { record?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
        val out = if (finalized && tmpFile.renameTo(finalFile)) finalFile else tmpFile
        result = AudioResult(
            out, finalized && out == finalFile, sampleRate, channels, bitrate, firstSampleSessionNs, timestampSource,
            framesCaptured, drift.driftPpm(), drift.accumulatedDriftMs(), synchronized(routes) { routes.toList() }, error,
        )
    }

    private fun recordRoute(device: AudioDeviceInfo?) {
        val change = AudioRouteChange(
            sessionTimeMs = (SystemClock.elapsedRealtimeNanos() - timeline.originRealtimeNs) / 1e6,
            deviceType = device?.let { typeName(it.type) } ?: "none",
            deviceName = device?.productName?.toString() ?: "unknown",
        )
        synchronized(routes) {
            if (routes.lastOrNull()?.let { it.deviceType == change.deviceType && it.deviceName == change.deviceName } == true) return
            routes += change
        }
        onRouteChanged(change)
    }

    /** Blocks until the audio thread finalized the file. Call off the main thread. */
    fun stop(timeoutMs: Long = 5_000): AudioResult? {
        running = false
        thread?.join(timeoutMs)
        return result
    }

    val isRunning: Boolean get() = running && thread?.isAlive == true

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE_$t"
    }

    companion object {
        private const val TAG = "MasterAudio"
    }
}
