package com.cleanroom.stadiumdsp.rustbridge;

import com.cleanroom.stadiumdsp.live.KotlinLiveTimingKernel;
import com.cleanroom.stadiumdsp.live.LiveTimingKernel;
import java.util.List;
import rustanything.LiveRsNative;

/**
 * Production Android JNI adapter routing {@code live/} timing and cue-scheduling calls
 * through the {@code rust-anything}-verified Rust library ({@code liblive_rs.so} for
 * {@code arm64-v8a} and {@code x86_64}), with automatic bit-exact Kotlin fallback when
 * running on a host JVM without the native library loaded.
 */
public final class LiveTimingRustBridge implements LiveTimingKernel {

    public static final LiveTimingRustBridge INSTANCE = new LiveTimingRustBridge();

    private final boolean rustNativeAvailable;

    private LiveTimingRustBridge() {
        boolean loaded;
        try {
            loaded = "00:00".equals(LiveRsNative.call_formatStopwatchMmSs(0L));
        } catch (Throwable ignored) {
            loaded = false;
        }
        this.rustNativeAvailable = loaded;
    }

    public boolean isRustNativeAvailable() {
        return rustNativeAvailable;
    }

    @Override
    public long computeStopwatchElapsedMs(
            long accumulatedMs,
            long startEpochMs,
            boolean isRunning,
            long nowMs) {
        if (rustNativeAvailable) {
            return LiveRsNative.call_computeStopwatchElapsedMs(
                    accumulatedMs, startEpochMs, isRunning, nowMs);
        }
        return KotlinLiveTimingKernel.INSTANCE.computeStopwatchElapsedMs(
                accumulatedMs, startEpochMs, isRunning, nowMs);
    }

    @Override
    public String formatStopwatchMmSs(long elapsedMs) {
        if (rustNativeAvailable) {
            return LiveRsNative.call_formatStopwatchMmSs(elapsedMs);
        }
        return KotlinLiveTimingKernel.INSTANCE.formatStopwatchMmSs(elapsedMs);
    }

    @Override
    public int countTriggeredCues(List<Long> cueTimestampsMs, int nextCueIdx, long newPositionMs) {
        if (rustNativeAvailable) {
            return LiveRsNative.call_countTriggeredCues(cueTimestampsMs, nextCueIdx, newPositionMs);
        }
        return KotlinLiveTimingKernel.INSTANCE.countTriggeredCues(
                cueTimestampsMs, nextCueIdx, newPositionMs);
    }

    @Override
    public long findNextCueRemainingMs(
            List<Long> cueTimestampsMs, int nextCueIdx, long currentPositionMs) {
        if (rustNativeAvailable) {
            return LiveRsNative.call_findNextCueRemainingMs(
                    cueTimestampsMs, nextCueIdx, currentPositionMs);
        }
        return KotlinLiveTimingKernel.INSTANCE.findNextCueRemainingMs(
                cueTimestampsMs, nextCueIdx, currentPositionMs);
    }
}
