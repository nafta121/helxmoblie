package com.cleanroom.stadiumdsp.live

import com.cleanroom.stadiumdsp.rustbridge.LiveTimingRustBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Verifies 100% bit-exact parity between the Kotlin `live/` oracle and the compiled Rust JNI
 * library (`rust/live-rs/target/release/liblive_rs.so`) end-to-end inside Gradle JUnit!
 */
class LiveTimingRustJniTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun configureHostRustLibrary() {
            val candidates = listOf(
                File("rust/live-rs/target/release/liblive_rs.so"),
                File("../rust/live-rs/target/release/liblive_rs.so"),
            )
            val lib = candidates.firstOrNull { it.exists() }
            if (lib != null) {
                System.setProperty("rust-anything.lib.live_rs", lib.canonicalPath)
            }
        }
    }

    @Test
    fun rustJniLibraryLoadsAndMatchesKotlinOracleAcrossEdgeCases() {
        val bridge = LiveTimingRustBridge.INSTANCE
        val hostLibConfigured = System.getProperty("rust-anything.lib.live_rs") != null
        if (hostLibConfigured) {
            assertTrue(
                "Expected compiled host Rust JNI library liblive_rs.so to load cleanly",
                bridge.isRustNativeAvailable,
            )
        }

        val elapsedCases = listOf(
            Quad(0L, 1_000L, true, 5_250L),
            Quad(12_000L, 1_000L, false, 99_000L),
            Quad(5_000L, 10_000L, true, 4_000L), // clock skew backwards
            Quad(Long.MAX_VALUE - 5L, 0L, true, 10L), // wrapping overflow parity
            Quad(-1_000L, -500L, true, 250L),
        )
        for (c in elapsedCases) {
            assertEquals(
                KotlinLiveTimingKernel.computeStopwatchElapsedMs(c.a, c.b, c.c, c.d),
                bridge.computeStopwatchElapsedMs(c.a, c.b, c.c, c.d),
            )
        }

        val fmtCases = listOf(Long.MIN_VALUE, -5_000L, -1L, 0L, 999L, 1_000L, 65_999L, 3_599_000L, 6_120_000L)
        for (ms in fmtCases) {
            assertEquals(
                KotlinLiveTimingKernel.formatStopwatchMmSs(ms),
                bridge.formatStopwatchMmSs(ms),
            )
        }

        val cues = listOf(0L, 8_000L, 16_000L, 24_000L, 34_000L)
        for (idx in listOf(-2, 0, 1, 3, 5, 99)) {
            for (pos in listOf(-500L, 0L, 7_999L, 8_000L, 24_000L, 99_000L)) {
                assertEquals(
                    KotlinLiveTimingKernel.countTriggeredCues(cues, idx, pos),
                    bridge.countTriggeredCues(cues, idx, pos),
                )
                assertEquals(
                    KotlinLiveTimingKernel.findNextCueRemainingMs(cues, idx, pos),
                    bridge.findNextCueRemainingMs(cues, idx, pos),
                )
            }
        }
    }

    @Test
    fun gigStopwatchAndShowcaseTrackPlayerRunThroughRustJniKernel() {
        val bridge = LiveTimingRustBridge.INSTANCE
        val watch = GigStopwatch(kernel = bridge)
        watch.start(nowMs = 1_000L)
        assertEquals("01:05", watch.formatElapsed(nowMs = 66_500L))
        watch.pause(nowMs = 66_500L)
        assertEquals(65_500L, watch.elapsedMs(nowMs = 200_000L))

        val fired = mutableListOf<String>()
        val player = ShowcaseTrackPlayer(
            kernel = bridge,
            onCueTriggered = { fired.add(it.label) },
        )
        player.start()
        player.advanceTo(16_000L)
        assertEquals(listOf("Intro Clean", "Verse Klon Edge", "Chorus 2203 Rhythm"), fired)
        assertEquals(8_000L, player.nextCueRemainingMs())
    }

    private data class Quad(val a: Long, val b: Long, val c: Boolean, val d: Long)
}
