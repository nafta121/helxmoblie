package com.cleanroom.stadiumdsp.live

import kotlinx.serialization.Serializable

@Serializable
data class ShowcaseCueMarker(
    val timestampMs: Long,
    val label: String,
    val snapshotIndex: Int? = null,
    val presetId: String? = null,
)

/**
 * GAP-22b: Showcase automated cue scheduler firing Snapshot/Preset switches tightly locked to song timeline.
 */
class ShowcaseTrackPlayer(
    val cues: List<ShowcaseCueMarker> = defaultShowcaseCues(),
    private val onCueTriggered: (ShowcaseCueMarker) -> Unit = {},
) {
    private val sortedCues = cues.sortedBy { it.timestampMs }
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
        while (nextCueIdx < sortedCues.size && sortedCues[nextCueIdx].timestampMs <= positionMs) {
            onCueTriggered(sortedCues[nextCueIdx])
            nextCueIdx++
        }
    }

    fun nextUpcomingCue(): ShowcaseCueMarker? = sortedCues.getOrNull(nextCueIdx)

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
class GigStopwatch {
    private var startEpochMs: Long? = null
    private var accumulatedMs: Long = 0L

    val isRunning: Boolean get() = startEpochMs != null

    fun start(nowMs: Long = System.currentTimeMillis()) {
        if (startEpochMs == null) startEpochMs = nowMs
    }

    fun pause(nowMs: Long = System.currentTimeMillis()) {
        startEpochMs?.let {
            accumulatedMs += (nowMs - it).coerceAtLeast(0L)
            startEpochMs = null
        }
    }

    fun reset() {
        startEpochMs = null
        accumulatedMs = 0L
    }

    fun elapsedMs(nowMs: Long = System.currentTimeMillis()): Long {
        val active = startEpochMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return accumulatedMs + active
    }

    fun formatElapsed(nowMs: Long = System.currentTimeMillis()): String {
        val totalSec = (elapsedMs(nowMs) / 1000L).coerceAtLeast(0L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return String.format("%02d:%02d", mins, secs)
    }
}
