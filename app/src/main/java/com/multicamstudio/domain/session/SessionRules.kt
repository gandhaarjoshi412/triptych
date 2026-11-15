package com.multicamstudio.domain.session

import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.QualityPreset
import com.multicamstudio.domain.model.VideoCodecChoice
import com.multicamstudio.domain.model.VideoResolution
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object SessionNaming {
    private val ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH-mm-ss")

    /** Immutable session id, e.g. 2026-11-16_09-30-25. [taken] resolves collisions with _2, _3… */
    fun sessionId(local: LocalDateTime, taken: (String) -> Boolean = { false }): String {
        val base = local.format(ID_FORMAT)
        if (!taken(base)) return base
        var n = 2
        while (taken("${base}_$n")) n++
        return "${base}_$n"
    }

    fun dateFolder(local: LocalDateTime): String = local.format(DATE_FORMAT)

    /** Session folder name: HH-mm-ss or HH-mm-ss_<label>. */
    fun sessionFolder(local: LocalDateTime, label: String?, suffix: String? = null): String {
        val time = local.format(TIME_FORMAT)
        val clean = label?.let { sanitize(it) }?.takeIf { it.isNotEmpty() }
        return listOfNotNull(time, clean, suffix).joinToString("_")
    }

    /** File/folder-safe label: letters, digits, dash, underscore. Spaces become underscores. */
    fun sanitize(text: String, maxLength: Int = 48): String =
        text.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_\\-]"), "")
            .take(maxLength)

    fun segmentFileName(role: CameraRole, index: Int): String = "${role.dirName}_%03d.mp4".format(index)
}

/** Decides when to roll to a new segment so all cameras share boundaries on the session timeline. */
class SegmentPlanner(private val segmentDurationNs: Long) {
    init {
        require(segmentDurationNs > 0)
    }

    /** Segment index (1-based) that a session-relative time belongs to. */
    fun indexFor(sessionNs: Long): Int = (sessionNs.coerceAtLeast(0) / segmentDurationNs).toInt() + 1

    /** Session time at which the given (1-based) segment is planned to end. */
    fun boundaryAfter(index: Int): Long = index * segmentDurationNs
}

object BitratePolicy {
    /**
     * Initial bitrate estimate: width*height*fps*bitsPerPixel, HEVC ~ 60% of AVC for similar quality.
     * These are starting points that must be validated on device (see docs/DEVICE_TEST_PLAN.md);
     * the result is clamped to the encoder's supported range by the caller.
     */
    fun estimate(resolution: VideoResolution, fps: Int, codec: VideoCodecChoice, preset: QualityPreset): Int {
        val bpp = preset.bitsPerPixelFrame * if (codec == VideoCodecChoice.HEVC) 1.0 else 1.6
        val raw = resolution.width.toDouble() * resolution.height * fps * bpp
        // Round to 100 kbps.
        return ((raw / 100_000).roundToInt() * 100_000).coerceAtLeast(1_000_000)
    }

    fun clamp(bitrate: Int, min: Int?, max: Int?): Int {
        var b = bitrate
        if (min != null) b = maxOf(b, min)
        if (max != null) b = minOf(b, max)
        return b
    }
}

/** Storage guardrails while recording. */
object StoragePolicy {
    data class Decision(val warn: Boolean, val stop: Boolean, val estimatedMinutesLeft: Double?)

    fun evaluate(freeBytes: Long, bytesPerSecond: Double, warnBytes: Long, stopBytes: Long): Decision {
        val minutes = if (bytesPerSecond > 0) (freeBytes - stopBytes).coerceAtLeast(0) / bytesPerSecond / 60.0 else null
        return Decision(warn = freeBytes <= warnBytes, stop = freeBytes <= stopBytes, estimatedMinutesLeft = minutes)
    }
}
