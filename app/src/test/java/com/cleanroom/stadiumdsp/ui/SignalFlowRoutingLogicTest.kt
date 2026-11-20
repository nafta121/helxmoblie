package com.cleanroom.stadiumdsp.ui

import com.cleanroom.stadiumdsp.preset.SignalFlowState
import com.cleanroom.stadiumdsp.preset.SplitRoutingMode
import com.cleanroom.stadiumdsp.preset.StadiumPreset
import com.cleanroom.stadiumdsp.ui.components.SignalFlowRoutingLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SignalFlowRoutingLogicTest {

    @Test
    fun splitsBlocksIntoPath1AAndParallelPath1BCorrectly() {
        val flow = SignalFlowState(blocks = StadiumPreset.defaultBlocks(), splitMode = SplitRoutingMode.SPLIT_Y)
        val pathA = SignalFlowRoutingLogic.blocksOnBranch(flow, "1A")
        val pathB = SignalFlowRoutingLogic.blocksOnBranch(flow, "1B")

        assertTrueSafe(pathA.any { it.id == "amp" })
        assertTrueSafe(pathA.any { it.id == "cab" })
        assertTrueSafe(pathB.any { it.id == "delay" })
        assertTrueSafe(pathB.any { it.id == "reverb" })

        val serialFlow = flow.copy(splitMode = SplitRoutingMode.SERIAL)
        assertEquals(0, SignalFlowRoutingLogic.blocksOnBranch(serialFlow, "1B").size)
        assertEquals(flow.blocks.size, SignalFlowRoutingLogic.blocksOnBranch(serialFlow, "1A").size)
    }

    @Test
    fun ampCabLinkAutomaticallySelectsMatchedFactoryCab() {
        val flow = SignalFlowState(blocks = StadiumPreset.defaultBlocks(), ampCabLink = true)
        // Switch Amp to SV Beast Nrm (nativeId=25 -> defaultCabId=8 "8x10 SV Beast Fridge")
        val updated = SignalFlowRoutingLogic.selectAmpModel(flow, ampNativeId = 25)
        val cab = updated.blocks.first { it.id == "cab" }
        assertEquals(8, cab.modelId)

        // When unlinked, switching Amp leaves Cab unchanged
        val unlinked = updated.copy(ampCabLink = false)
        val afterUnlinkedAmp = SignalFlowRoutingLogic.selectAmpModel(unlinked, ampNativeId = 0)
        assertEquals(8, afterUnlinkedAmp.blocks.first { it.id == "cab" }.modelId)
    }

    @Test
    fun reorderBlockUpdatesSlotSequenceWithoutLosingBlocks() {
        val flow = SignalFlowState(blocks = StadiumPreset.defaultBlocks())
        val driveIdx = flow.blocks.indexOfFirst { it.id == "drive" }
        val reordered = SignalFlowRoutingLogic.moveBlock(flow, fromIndex = driveIdx, toIndex = driveIdx - 1)
        assertEquals(flow.blocks.size, reordered.blocks.size)
        assertEquals("drive", reordered.blocks[driveIdx - 1].id)
        assertNotEquals(flow.blocks[driveIdx - 1].id, reordered.blocks[driveIdx - 1].id)
    }

    private fun assertTrueSafe(cond: Boolean) {
        org.junit.Assert.assertTrue(cond)
    }
}
