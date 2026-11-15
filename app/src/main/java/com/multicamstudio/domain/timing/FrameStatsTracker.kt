package com.multicamstudio.domain.timing

import kotlin.math.roundToLong

/**
 * Tracks real encoded frames for one stream.
 *
 * Dropped frames are inferred from gaps in presentation timestamps: a gap of N nominal frame
 * periods means N-1 frames never reached the encoder. Nothing is ever synthesised – the counts are
 * exactly what was observed.
 *
 * Not thread-safe: call from the single encoder output thread, read via [snapshot] (which copies).
 */
class FrameStatsTracker(
    targetFps: Int,
    /** A gap larger than this multiple of the frame period counts as dropped frames. */
    private val gapToleranceFactor: Double = 1.5,
    private val fpsWindowNs: Long = 2_000_000_000L,
) {
    val framePeriodNs: Long = 1_000_000_000L / targetFps

    private var frameCount = 0L
    private var droppedFrames = 0L
    private var dropEvents = 0L
    private var firstPtsNs: Long? = null
    private var lastPtsNs: Long? = null
    private var maxGapNs = 0L

    // Ring buffer of recent timestamps for a rolling FPS.
    private val window = LongArray(256)
    private var windowStart = 0
    private var windowSize = 0

    @Synchronized
    fun onFrame(ptsNs: Long) {
        val last = lastPtsNs
        if (last != null) {
            val gap = ptsNs - last
            if (gap <= 0) return // non-monotonic: ignore, never count twice
            if (gap > maxGapNs) maxGapNs = gap
            if (gap > framePeriodNs * gapToleranceFactor) {
                val missing = (gap.toDouble() / framePeriodNs).roundToLong() - 1
                if (missing > 0) {
                    droppedFrames += missing
                    dropEvents++
                }
            }
        } else {
            firstPtsNs = ptsNs
        }
        lastPtsNs = ptsNs
        frameCount++
        push(ptsNs)
    }

    /** Called when a pipeline is intentionally restarted (e.g. resolution change) so the gap is not counted as drops. */
    @Synchronized
    fun markDiscontinuity() {
        lastPtsNs = null
        windowSize = 0
        windowStart = 0
    }

    private fun push(ts: Long) {
        val idx = (windowStart + windowSize) % window.size
        window[idx] = ts
        if (windowSize < window.size) windowSize++ else windowStart = (windowStart + 1) % window.size
        // Evict old samples.
        while (windowSize > 1 && ts - window[windowStart] > fpsWindowNs) {
            windowStart = (windowStart + 1) % window.size
            windowSize--
        }
    }

    @Synchronized
    fun snapshot(): FrameStats {
        val fps = if (windowSize >= 2) {
            val first = window[windowStart]
            val last = window[(windowStart + windowSize - 1) % window.size]
            val span = last - first
            if (span > 0) (windowSize - 1) * 1_000_000_000.0 / span else null
        } else null
        val avgFps = run {
            val f = firstPtsNs
            val l = lastPtsNs
            if (f != null && l != null && l > f && frameCount > 1) (frameCount - 1) * 1_000_000_000.0 / (l - f) else null
        }
        return FrameStats(
            frameCount = frameCount,
            droppedFrames = droppedFrames,
            dropEvents = dropEvents,
            currentFps = fps,
            averageFps = avgFps,
            maxGapMs = maxGapNs / 1_000_000.0,
            firstPtsNs = firstPtsNs,
            lastPtsNs = lastPtsNs,
        )
    }
}

data class FrameStats(
    val frameCount: Long,
    val droppedFrames: Long,
    val dropEvents: Long,
    /** Rolling FPS over the recent window, null until enough frames exist. */
    val currentFps: Double?,
    val averageFps: Double?,
    val maxGapMs: Double,
    val firstPtsNs: Long?,
    val lastPtsNs: Long?,
) {
    val dropRatio: Double
        get() = if (frameCount + droppedFrames == 0L) 0.0 else droppedFrames.toDouble() / (frameCount + droppedFrames)

    companion object {
        val EMPTY = FrameStats(0, 0, 0, null, null, 0.0, null, null)
    }
}
