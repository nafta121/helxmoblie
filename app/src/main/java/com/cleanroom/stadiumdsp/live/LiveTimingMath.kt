package com.cleanroom.stadiumdsp.live

/**
 * Pure, stateless stage timing and cue-scheduling math exported for Rust JNI acceleration
 * via `rust-anything` while preserving exact Kotlin fallback semantics.
 */

fun computeStopwatchElapsedMs(
    accumulatedMs: Long,
    startEpochMs: Long,
    isRunning: Boolean,
    nowMs: Long,
): Long {
    val active = if (isRunning) (nowMs - startEpochMs).coerceAtLeast(0L) else 0L
    return accumulatedMs + active
}

fun formatStopwatchMmSs(elapsedMs: Long): String {
    val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val minStr = if (mins < 10L) "0$mins" else mins.toString()
    val secStr = if (secs < 10L) "0$secs" else secs.toString()
    return "$minStr:$secStr"
}

fun countTriggeredCues(
    cueTimestampsMs: List<Long>,
    nextCueIdx: Int,
    newPositionMs: Long,
): Int {
    val pos = newPositionMs.coerceAtLeast(0L)
    var idx = nextCueIdx.coerceAtLeast(0)
    var count = 0
    while (idx < cueTimestampsMs.size && cueTimestampsMs[idx] <= pos) {
        count++
        idx++
    }
    return count
}

fun findNextCueRemainingMs(
    cueTimestampsMs: List<Long>,
    nextCueIdx: Int,
    currentPositionMs: Long,
): Long {
    if (nextCueIdx < 0 || nextCueIdx >= cueTimestampsMs.size) return -1L
    val pos = currentPositionMs.coerceAtLeast(0L)
    return (cueTimestampsMs[nextCueIdx] - pos).coerceAtLeast(0L)
}
