package com.multicamstudio.domain.timing

import kotlin.math.abs

/** The clock a capture timestamp was produced in. */
enum class ClockBase {
    /** CLOCK_BOOTTIME – same as SystemClock.elapsedRealtimeNanos(). This is the session reference clock. */
    REALTIME,

    /** CLOCK_MONOTONIC – same as System.nanoTime(); does not advance in deep sleep. */
    MONOTONIC,
}

/**
 * Snapshot of both clocks taken at one instant: used to convert MONOTONIC stamps to REALTIME.
 * offset = realtime - monotonic (>= 0, grows with total deep-sleep time since boot).
 */
data class ClockPair(val realtimeNs: Long, val monotonicNs: Long) {
    val offsetNs: Long get() = realtimeNs - monotonicNs
}

/**
 * Detects which clock a capture/encoder timestamp is in by comparing it to "now" in both clocks.
 * Frames arrive tens to a few hundred ms after capture, so the closer clock is the right one.
 *
 * If the two clocks are almost equal (device never slept since boot) the choice doesn't matter and
 * [REALTIME] is returned with ambiguous=true.
 */
object ClockBaseDetector {
    data class Result(val base: ClockBase, val ambiguous: Boolean)

    fun detect(timestampNs: Long, now: ClockPair, ambiguityThresholdNs: Long = 1_000_000_000L): Result {
        val dRealtime = abs(now.realtimeNs - timestampNs)
        val dMonotonic = abs(now.monotonicNs - timestampNs)
        val ambiguous = abs(now.offsetNs) < ambiguityThresholdNs
        return Result(if (dRealtime <= dMonotonic) ClockBase.REALTIME else ClockBase.MONOTONIC, ambiguous)
    }
}

/**
 * The session's single shared monotonic timeline.
 *
 * Origin: SystemClock.elapsedRealtimeNanos() captured when the user pressed Record.
 * All camera frames and audio blocks are expressed as nanoseconds relative to that origin.
 */
class SessionTimeline(
    val originRealtimeNs: Long,
    /** realtime - monotonic measured at session start. */
    val monotonicToRealtimeOffsetNs: Long,
) {
    fun toSessionNs(timestampNs: Long, base: ClockBase): Long = when (base) {
        ClockBase.REALTIME -> timestampNs - originRealtimeNs
        ClockBase.MONOTONIC -> timestampNs + monotonicToRealtimeOffsetNs - originRealtimeNs
    }
}

/**
 * Pairwise frame-phase alignment between two 30 fps streams.
 *
 * Two independently clocked sensors running at the same rate capture frames at different instants
 * within a frame period. When the editor aligns the files using the manifest start offsets, the
 * remaining worst-case error is this phase difference (bounded by half a frame period, 16.7 ms at 30 fps)
 * plus any timestamp inaccuracy.
 */
object PhaseAlignment {
    fun phaseDifferenceNs(aNs: Long, bNs: Long, framePeriodNs: Long): Long {
        val diff = Math.floorMod(aNs - bNs, framePeriodNs)
        return minOf(diff, framePeriodNs - diff)
    }
}

/**
 * Estimates audio clock drift: compares the sample-count timeline (frames / nominal rate) with the
 * hardware presentation timestamps reported by AudioTimestamp (BOOTTIME).
 *
 * Positive drift means the audio hardware delivers samples slower than nominal (sample-count timeline
 * runs ahead of real time).
 */
class AudioDriftEstimator(private val sampleRate: Int) {
    private var firstFrame: Long? = null
    private var firstTimeNs: Long? = null
    private var lastFrame: Long = 0
    private var lastTimeNs: Long = 0
    var samples = 0
        private set

    fun add(framePosition: Long, timeNs: Long) {
        if (firstFrame == null) {
            firstFrame = framePosition
            firstTimeNs = timeNs
        }
        lastFrame = framePosition
        lastTimeNs = timeNs
        samples++
    }

    /** Accumulated difference (ms) between sample-count time and hardware time across the observed span. */
    fun accumulatedDriftMs(): Double? {
        val f0 = firstFrame ?: return null
        val t0 = firstTimeNs ?: return null
        if (lastFrame <= f0) return null
        val nominalNs = (lastFrame - f0) * 1_000_000_000.0 / sampleRate
        val actualNs = (lastTimeNs - t0).toDouble()
        return (nominalNs - actualNs) / 1_000_000.0
    }

    /** Drift in parts per million. */
    fun driftPpm(): Double? {
        val f0 = firstFrame ?: return null
        val t0 = firstTimeNs ?: return null
        if (lastFrame <= f0 || lastTimeNs <= t0) return null
        val nominalNs = (lastFrame - f0) * 1_000_000_000.0 / sampleRate
        val actualNs = (lastTimeNs - t0).toDouble()
        return (nominalNs - actualNs) / actualNs * 1_000_000.0
    }

    /** Estimated real time (ns, BOOTTIME) of a given frame position using the latest hardware stamp. */
    fun estimateTimeNs(framePosition: Long): Long? {
        if (firstFrame == null) return null
        return lastTimeNs + (framePosition - lastFrame) * 1_000_000_000L / sampleRate
    }
}
