package com.cleanroom.stadiumdsp.midi

import com.cleanroom.stadiumdsp.live.GigStopwatch
import com.cleanroom.stadiumdsp.live.ShowcaseCueMarker
import com.cleanroom.stadiumdsp.live.ShowcaseTrackPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerAndFootswitchTest {

    @Test
    fun expressionControllerMapsNormalInvertedAndAutoEngageWith200msDelay() {
        val engine = ControllerAssignmentEngine()
        engine.assign(
            ControllerAssignment(
                pedalIndex = 1,
                targetBlockId = "wah",
                targetParamKey = "wah.position",
                minValue = 0.10f,
                maxValue = 0.95f,
                autoEngage = true,
                engageThreshold = 0.03f,
                disengageDelayMs = 200L,
            )
        )
        engine.assign(
            ControllerAssignment(
                pedalIndex = 2,
                targetBlockId = "delay",
                targetParamKey = "delay.mix",
                minValue = 0.80f, // Inverted range: heel=0.80, toe=0.20!
                maxValue = 0.20f,
                autoEngage = false,
            )
        )

        // Heel below threshold -> not engaged yet
        val r0 = engine.evaluatePedal(1, 0.01f, nowMs = 1000L).first()
        assertFalse(r0.blockEnabledChange == true)

        // Sweep above 3% -> Auto-Engage fires immediately!
        val r1 = engine.evaluatePedal(1, 0.50f, nowMs = 1050L).first()
        assertEquals(true, r1.blockEnabledChange)
        assertEquals(0.525f, r1.mappedValue, 1e-4f)

        // Return to heel at t=1200ms -> MUST stay engaged until 200ms elapsed
        val r2 = engine.evaluatePedal(1, 0.00f, nowMs = 1200L).first()
        assertFalse(r2.blockEnabledChange == false)
        val r3 = engine.evaluatePedal(1, 0.00f, nowMs = 1350L).first()
        assertFalse(r3.blockEnabledChange == false)

        // At t=1405ms (>= 200ms at heel) -> Auto-Disengages cleanly!
        val r4 = engine.evaluatePedal(1, 0.00f, nowMs = 1405L).first()
        assertEquals(false, r4.blockEnabledChange)

        // Inverted EXP 2 mapping check
        val invHeel = engine.evaluatePedal(2, 0.0f, nowMs = 1500L).first()
        val invToe = engine.evaluatePedal(2, 1.0f, nowMs = 1500L).first()
        assertEquals(0.80f, invHeel.mappedValue, 1e-4f)
        assertEquals(0.20f, invToe.mappedValue, 1e-4f)
    }

    @Test
    fun liveFootswitchMatrixSupportsAll6ModesMultipleAssignAndStageLock() {
        val matrix = LiveFootswitchMatrix.createDefault()
        assertEquals(6, LivePlayMode.entries.size)

        val comboPads = matrix.padsForMode(LivePlayMode.COMBO_4_PLUS_4)
        assertEquals(8, comboPads.size)
        assertEquals(4, comboPads.count { it.action is FootswitchAction.RecallSnapshot })
        assertEquals(4, comboPads.count { it.action is FootswitchAction.ToggleBlocks })

        // MULTIPLE (X): 1 pad toggles Drive + Delay + Reverb simultaneously
        matrix.assignMultipleBlocks(stompSlotIndex = 0, blockIds = listOf("drive", "delay", "reverb"), customLabel = "SOLO BOOST")
        val stompAPads = matrix.padsForMode(LivePlayMode.STOMP_A)
        val pad0 = stompAPads[0]
        assertEquals("SOLO BOOST", pad0.label)
        assertEquals("MULTIPLE (3)", pad0.subLabel)

        // Stage Lock rules
        assertFalse(StageLockPolicy.allowParameterEdit(stageLocked = true, fromLiveController = false))
        assertTrue(StageLockPolicy.allowParameterEdit(stageLocked = true, fromLiveController = true))
        assertTrue(StageLockPolicy.allowLiveFootswitch(stageLocked = true))
        assertTrue(StageLockPolicy.allowMasterMute(stageLocked = true))
    }

    @Test
    fun showcaseTrackPlayerFiresCuesAndGigStopwatchTracksElapsedTime() {
        val fired = mutableListOf<Int>()
        val player = ShowcaseTrackPlayer(
            cues = listOf(
                ShowcaseCueMarker(timestampMs = 5_000L, label = "Chorus", snapshotIndex = 2),
                ShowcaseCueMarker(timestampMs = 12_000L, label = "Solo", snapshotIndex = 3),
            ),
            onCueTriggered = { cue -> cue.snapshotIndex?.let { fired.add(it) } },
        )
        player.start()
        player.advanceTo(4_500L)
        assertTrue(fired.isEmpty())
        player.advanceTo(5_100L)
        assertEquals(listOf(2), fired)
        player.advanceTo(12_500L)
        assertEquals(listOf(2, 3), fired)

        val watch = GigStopwatch()
        watch.start(1000L)
        assertEquals("01:05", watch.formatElapsed(66_000L))
    }
}
