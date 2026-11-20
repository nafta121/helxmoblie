package com.cleanroom.stadiumdsp.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StadiumMidiParserTest {

    @Test
    fun decodesStandardHelixCcAndProgramChangeMessages() {
        val events = mutableListOf<StadiumMidiAction>()
        val parser = StadiumMidiParser { events.add(it) }

        // CC#69 val=3 -> Snapshot 4 (0-indexed 3)
        parser.parseBytes(byteArrayOf(0xB0.toByte(), 69, 3), 0, 3)
        assertEquals(StadiumMidiAction.SelectSnapshot(3), events.last())

        // CC#64 val=127 -> Tap Tempo
        parser.parseBytes(byteArrayOf(0xB0.toByte(), 64, 127), 0, 3)
        assertEquals(StadiumMidiAction.TapTempo, events.last())

        // CC#68 val=127 -> Toggle Tuner
        parser.parseBytes(byteArrayOf(0xB0.toByte(), 68, 127), 0, 3)
        assertEquals(StadiumMidiAction.ToggleTuner(true), events.last())

        // CC#1 val=64 -> EXP 1 ~0.5039
        parser.parseBytes(byteArrayOf(0xB0.toByte(), 1, 64), 0, 3)
        val expAction = events.last() as StadiumMidiAction.ExpressionPedal
        assertEquals(1, expAction.pedalIndex)
        assertEquals(64f / 127f, expAction.normalizedValue, 1e-4f)

        // CC#50 val=127 -> Stomp 2 (CC#49..56 -> index 0..7)
        parser.parseBytes(byteArrayOf(0xB0.toByte(), 50, 127), 0, 3)
        assertEquals(StadiumMidiAction.ToggleStomp(1), events.last())

        // Program Change 5 -> Select Preset 5
        parser.parseBytes(byteArrayOf(0xC0.toByte(), 5), 0, 2)
        assertEquals(StadiumMidiAction.SelectPreset(5), events.last())
    }

    @Test
    fun midiLearnCapturesIncomingCcAndBindsCustomTarget() {
        val learned = mutableListOf<Pair<String, Int>>()
        val actions = mutableListOf<StadiumMidiAction>()
        val parser = StadiumMidiParser(
            onAction = { actions.add(it) },
            onLearnedCc = { target, cc -> learned.add(target to cc) },
        )

        parser.armMidiLearn("amp.drive")
        assertTrue(parser.isLearning)

        // Incoming CC#21 value 90 gets captured by MIDI Learn
        parser.parseBytes(byteArrayOf(0xB0.toByte(), 21, 90), 0, 3)
        assertEquals(1, learned.size)
        assertEquals("amp.drive" to 21, learned.first())
        assertEquals(false, parser.isLearning)

        // Subsequent CC#21 now emits LearnedCcParameter directly!
        parser.parseBytes(byteArrayOf(0xB0.toByte(), 21, 127), 0, 3)
        val paramEvt = actions.last() as StadiumMidiAction.LearnedCcParameter
        assertEquals("amp.drive", paramEvt.targetKey)
        assertEquals(1.0f, paramEvt.normalizedValue, 1e-4f)
    }
}
