package com.cleanroom.stadiumdsp.live

import kotlinx.serialization.Serializable

@Serializable
data class ShowcaseCueMarker(
    val timestampMs: Long,
    val label: String,
    val snapshotIndex: Int? = null,
    val presetId: String? = null,
)

interface LiveTimingKernel {
    fun computeStopwatchElapsedMs(accumulatedMs: Long, startEpochMs: Long, isRunning: Boolean, nowMs: Long): Long
    fun formatStopwatchMmSs(elapsedMs: Long): String
    fun countTriggeredCues(cueTimestampsMs: List<Long>, nextCueIdx: Int, newPositionMs: Long): Int
    fun findNextCueRemainingMs(cueTimestampsMs: List<Long>, nextCueIdx: Int, currentPositionMs: Long): Long
}

object KotlinLiveTimingKernel : LiveTimingKernel {
    override fun computeStopwatchElapsedMs(accumulatedMs: Long, startEpochMs: Long, isRunning: Boolean, nowMs: Long): Long =
        com.cleanroom.stadiumdsp.live.computeStopwatchElapsedMs(accumulatedMs, startEpochMs, isRunning, nowMs)

    override fun formatStopwatchMmSs(elapsedMs: Long): String =
        com.cleanroom.stadiumdsp.live.formatStopwatchMmSs(elapsedMs)

    override fun countTriggeredCues(cueTimestampsMs: List<Long>, nextCueIdx: Int, newPositionMs: Long): Int =
        com.cleanroom.stadiumdsp.live.countTriggeredCues(cueTimestampsMs, nextCueIdx, newPositionMs)

    override fun findNextCueRemainingMs(cueTimestampsMs: List<Long>, nextCueIdx: Int, currentPositionMs: Long): Long =
        com.cleanroom.stadiumdsp.live.findNextCueRemainingMs(cueTimestampsMs, nextCueIdx, currentPositionMs)
}

/**
 * GAP-22b: Showcase automated cue scheduler firing Snapshot/Preset switches tightly locked to song timeline.
 */
class ShowcaseTrackPlayer(
    val cues: List<ShowcaseCueMarker> = defaultShowcaseCues(),
    private val kernel: LiveTimingKernel = KotlinLiveTimingKernel,
    private val onCueTriggered: (ShowcaseCueMarker) -> Unit = {},
) {
    private val sortedCues = cues.sortedBy { it.timestampMs }
    private val sortedTimestamps = sortedCues.map { it.timestampMs }
    private var nextCueIdx = 0
    var positionMs: Long = 0L
        private set
    var isPlaying: Boolean = false
        private set

    fun start() {
        isPlaying = true
    }

    fun pause() {
        isPlaying = false
    }

    fun reset() {
        isPlaying = false
        positionMs = 0L
        nextCueIdx = 0
    }

    fun advanceTo(newPositionMs: Long) {
        if (!isPlaying) return
        positionMs = newPositionMs.coerceAtLeast(0L)
        val triggeredCount = kernel.countTriggeredCues(sortedTimestamps, nextCueIdx, positionMs)
        repeat(triggeredCount) {
            onCueTriggered(sortedCues[nextCueIdx])
            nextCueIdx++
        }
    }

    fun nextUpcomingCue(): ShowcaseCueMarker? = sortedCues.getOrNull(nextCueIdx)

    fun nextCueRemainingMs(): Long = kernel.findNextCueRemainingMs(sortedTimestamps, nextCueIdx, positionMs)

    companion object {
        fun defaultShowcaseCues(): List<ShowcaseCueMarker> = listOf(
            ShowcaseCueMarker(0L, "Intro Clean", snapshotIndex = 0),
            ShowcaseCueMarker(8_000L, "Verse Klon Edge", snapshotIndex = 1),
            ShowcaseCueMarker(16_000L, "Chorus 2203 Rhythm", snapshotIndex = 2),
            ShowcaseCueMarker(24_000L, "Lead Guitar Solo", snapshotIndex = 3),
            ShowcaseCueMarker(34_000L, "Outro Ambient Swell", snapshotIndex = 4),
        )
    }
}

/**
 * Stage Gig Stopwatch tracking live set elapsed duration in `MM:SS`.
 */
class GigStopwatch(
    private val kernel: LiveTimingKernel = KotlinLiveTimingKernel,
) {
    private var startEpochMs: Long? = null
    private var accumulatedMs: Long = 0L

    val isRunning: Boolean get() = startEpochMs != null

    fun start(nowMs: Long = System.currentTimeMillis()) {
        if (startEpochMs == null) startEpochMs = nowMs
    }

    fun pause(nowMs: Long = System.currentTimeMillis()) {
        startEpochMs?.let {
            accumulatedMs = kernel.computeStopwatchElapsedMs(accumulatedMs, it, true, nowMs)
            startEpochMs = null
        }
    }

    fun reset() {
        startEpochMs = null
        accumulatedMs = 0L
    }

    fun elapsedMs(nowMs: Long = System.currentTimeMillis()): Long =
        kernel.computeStopwatchElapsedMs(accumulatedMs, startEpochMs ?: 0L, startEpochMs != null, nowMs)

    fun formatElapsed(nowMs: Long = System.currentTimeMillis()): String =
        kernel.formatStopwatchMmSs(elapsedMs(nowMs))
}
