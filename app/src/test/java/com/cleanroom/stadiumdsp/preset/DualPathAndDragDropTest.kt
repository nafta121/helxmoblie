package com.cleanroom.stadiumdsp.preset

import com.cleanroom.stadiumdsp.CloneMetadataUi
import com.cleanroom.stadiumdsp.DspBridgeGateway
import com.cleanroom.stadiumdsp.DspCommandCode
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.RigBlockCode
import com.cleanroom.stadiumdsp.StreamDiagnosticsUi
import com.cleanroom.stadiumdsp.ui.state.BrowserCategoryTab
import com.cleanroom.stadiumdsp.ui.state.PendingInsertSlot
import com.cleanroom.stadiumdsp.ui.state.StadiumRigViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingDualPathBridge : DspBridgeGateway {
    data class Cmd(val type: Int, val targetId: Int, val paramId: Int, val flags: Int, val v0: Float, val intVal: Int)
    data class RoutingSnapshot(
        val p1aTypes: List<Int>,
        val p1aInst: List<Int>,
        val p1bTypes: List<Int>,
        val p1bInst: List<Int>,
        val p2aTypes: List<Int>,
        val p2aInst: List<Int>,
        val p2bTypes: List<Int>,
        val p2bInst: List<Int>,
    )

    val cmds = mutableListOf<Cmd>()
    val routings = mutableListOf<RoutingSnapshot>()

    override val isNativeAvailable: Boolean = true
    override fun startAudio(enableInput: Boolean, inputDeviceId: Int, outputDeviceId: Int, burstMultiplier: Int, requestedBufferFrames: Int, autoXrunGuard: Boolean) = true
    override fun stopAudio() {}
    override fun isAudioRunning(): Boolean = true
    override fun setLiveBufferSizeFrames(targetFrames: Int): Int = targetFrames
    override fun setAutoXrunGuard(enabled: Boolean) {}
    override fun getStreamDiagnostics(): StreamDiagnosticsUi = StreamDiagnosticsUi()
    override fun enqueueCommand(type: Int, targetId: Int, paramId: Int, flags: Int, value0: Float, value1: Float, value2: Float, intVal: Int): Boolean {
        cmds.add(Cmd(type, targetId, paramId, flags, value0, intVal))
        return true
    }
    override fun readTelemetry(buffer16: FloatArray): LiveTelemetryUi = LiveTelemetryUi()
    override fun computeEqCurveDb(outBuffer: FloatArray) { outBuffer.fill(0f) }
    override fun computeCabSpectrumDb(slot: Int, outBuffer: FloatArray) { outBuffer.fill(-12f) }
    override fun setDynamicSlotOrder(order12: IntArray) {}
    override fun setDualPathRouting(
        p1aTypes: IntArray, p1aInst: IntArray,
        p1bTypes: IntArray, p1bInst: IntArray,
        p2aTypes: IntArray, p2aInst: IntArray,
        p2bTypes: IntArray, p2bInst: IntArray,
        split1After: Int, split2After: Int, merge2At: Int,
        merge1bInto1a: Boolean, path1aFeeds2a: Boolean,
    ) {
        routings.add(
            RoutingSnapshot(
                p1aTypes.toList(), p1aInst.toList(),
                p1bTypes.toList(), p1bInst.toList(),
                p2aTypes.toList(), p2aInst.toList(),
                p2bTypes.toList(), p2bInst.toList(),
            )
        )
    }
    override fun setParallelSplitMerge(mode: Int, crossoverHz: Float, routeAb: Float, lvlADb: Float, lvlBDb: Float, panA: Float, panB: Float, invertB: Boolean) {}
    override fun storeSnapshot(idx: Int, bypassMask: Int, params11: FloatArray) {}
    override fun recallSnapshot(idx: Int) {}
    override fun registerTapTempoMs(timestampMs: Long): Float = 165f
    override fun loadExternalIr(slot: Int, wavBytes: ByteArray): String = "OK"
    override fun loadExternalNam(jsonBytes: ByteArray): String = "OK"
    override fun loadExternalAidax(jsonBytes: ByteArray): String = "OK"
    override fun getCloneMetadata(): CloneMetadataUi = CloneMetadataUi()
    override fun selectFactoryPresetClipRiff(styleIndex: Int) {}
    override fun loadCustomPresetClipWav(wavBytes: ByteArray): String = "OK"
}

class DualPathAndDragDropTest {

    @Test
    fun spaceCadetPresetContains14BlocksAcrossAll4ParallelBranches() {
        val preset = StadiumPreset.createSpaceCadetPreset()
        assertEquals("Space Cadet", preset.name)
        assertEquals(165.0f, preset.globalBpm, 1e-4f)
        assertEquals(14, preset.signalFlow.blocks.size)

        val b1a = DualPathRoutingEngine.blocksOnBranch(preset.signalFlow, "1A")
        val b1b = DualPathRoutingEngine.blocksOnBranch(preset.signalFlow, "1B")
        val b2a = DualPathRoutingEngine.blocksOnBranch(preset.signalFlow, "2A")
        val b2b = DualPathRoutingEngine.blocksOnBranch(preset.signalFlow, "2B")

        assertEquals(5, b1a.size)
        assertEquals(3, b1b.size)
        assertEquals(5, b2a.size)
        assertEquals(1, b2b.size)

        // Verify stacked dual instances have distinct pool instanceIndex 0 & 1
        val drives = preset.signalFlow.blocks.filter { it.nativeBlockCode == RigBlockCode.DRIVE }
        assertEquals(listOf(0, 1), drives.map { it.instanceIndex }.sorted())
        val delays = preset.signalFlow.blocks.filter { it.nativeBlockCode == RigBlockCode.DELAY }
        assertEquals(listOf(0, 1), delays.map { it.instanceIndex }.sorted())
        val reverbs = preset.signalFlow.blocks.filter { it.nativeBlockCode == RigBlockCode.REVERB }
        assertEquals(listOf(0, 1), reverbs.map { it.instanceIndex }.sorted())
        val eqs = preset.signalFlow.blocks.filter { it.nativeBlockCode == RigBlockCode.EQ }
        assertEquals(listOf(0, 1), eqs.map { it.instanceIndex }.sorted())
        val vols = preset.signalFlow.blocks.filter { it.nativeBlockCode == RigBlockCode.VOL_PAN }
        assertEquals(listOf(0, 1), vols.map { it.instanceIndex }.sorted())
    }

    @Test
    fun dualPathRoutingEngineEnforcesPoolLimitsAndSupportsCrossBranchDragAndDrop() {
        val initial = StadiumPreset.createSpaceCadetPreset().signalFlow

        // Adding a 3rd Drive must be rejected gracefully (pool limit = 2)
        val thirdDrive = DualPathRoutingEngine.insertBlockAt(
            flow = initial,
            category = BlockCategory.DISTORTION,
            nativeBlockCode = RigBlockCode.DRIVE,
            modelId = 0,
            displayName = "Scream 808",
            shortLabel = "808",
            targetBranch = "1A",
            targetIndexInBranch = 0,
        )
        assertNotNull(thirdDrive.error)
        assertEquals(14, thirdDrive.flow.blocks.size)

        // Adding a 1st Wah into Path 1A slot 0 succeeds with instanceIndex = 0
        val addWah = DualPathRoutingEngine.insertBlockAt(
            flow = initial,
            category = BlockCategory.PITCH_WAH,
            nativeBlockCode = RigBlockCode.WAH,
            modelId = 0,
            displayName = "Fassel 310 Wah",
            shortLabel = "Wah",
            targetBranch = "1A",
            targetIndexInBranch = 0,
        )
        assertNull(addWah.error)
        assertNotNull(addWah.activeBlockId)
        val wahBlock = addWah.flow.blocks.first { it.id == addWah.activeBlockId }
        assertEquals(0, wahBlock.instanceIndex)
        assertEquals("1A", wahBlock.pathBranch)

        // Drag-and-drop move Wah from Path 1A to Path 2B index 1
        val moved = DualPathRoutingEngine.moveBlockTo(addWah.flow, wahBlock.id, "2B", 1)
        assertNull(moved.error)
        val b2b = DualPathRoutingEngine.blocksOnBranch(moved.flow, "2B")
        assertEquals(2, b2b.size)
        assertEquals(wahBlock.id, b2b[1].id)

        // Replace Wah in-place with Horizon Gate (cross-family replacement)
        val replaced = DualPathRoutingEngine.replaceBlockAt(
            flow = moved.flow,
            blockId = wahBlock.id,
            category = BlockCategory.DYNAMICS,
            nativeBlockCode = RigBlockCode.GATE,
            modelId = 0,
            displayName = "Horizon Gate",
            shortLabel = "Gate",
        )
        assertNull(replaced.error)
        val afterReplace = replaced.flow.blocks.first { it.id == wahBlock.id }
        assertEquals(RigBlockCode.GATE, afterReplace.nativeBlockCode)
        assertEquals("2B", afterReplace.pathBranch)

        // Remove block cleanly
        val removed = DualPathRoutingEngine.removeBlock(replaced.flow, wahBlock.id)
        assertFalse(removed.flow.blocks.any { it.id == wahBlock.id })
        assertEquals(14, removed.flow.blocks.size)
    }

    @Test
    fun viewModelAutoClosesModelBrowserAndDispatchesInstanceFlagsToNative() {
        val bridge = RecordingDualPathBridge()
        val vm = StadiumRigViewModel(bridge = bridge)

        // Initial state opens with Space Cadet & browser open on Amp
        assertTrue(vm.uiState.value.isModelBrowserOpen)
        assertEquals(BrowserCategoryTab.AMP, vm.uiState.value.activeBrowserTab)
        assertTrue(bridge.routings.isNotEmpty())
        assertEquals(5, bridge.routings.last().p1aTypes.size)
        assertEquals(3, bridge.routings.last().p1bTypes.size)

        // Tapping empty black canvas auto-closes Model Browser
        vm.closeModelBrowser()
        assertFalse(vm.uiState.value.isModelBrowserOpen)

        // Tapping Delay[1] (`delay_2`) re-opens Model Browser on DELAY tab
        vm.selectBlock("delay_2")
        assertTrue(vm.uiState.value.isModelBrowserOpen)
        assertEquals(BrowserCategoryTab.DELAY, vm.uiState.value.activeBrowserTab)
        assertEquals("delay_2", vm.uiState.value.selectedBlockId)

        // Updating parameter on `delay_2` sends flags=1 while `delay` sends flags=0
        bridge.cmds.clear()
        vm.updateParameter("delay_2", "delay.mix", 0.61f)
        assertTrue(
            bridge.cmds.any {
                it.type == DspCommandCode.SET_DELAY_REVERB && it.targetId == 0 && it.paramId == 2 && it.flags == 1
            }
        )
        bridge.cmds.clear()
        vm.updateParameter("delay", "delay.mix", 0.25f)
        assertTrue(
            bridge.cmds.any {
                it.type == DspCommandCode.SET_DELAY_REVERB && it.targetId == 0 && it.paramId == 2 && it.flags == 0
            }
        )

        // Opening browser for a `+` slot on Path 2B and picking an Amp inserts a 2nd Amp (flags=1)
        vm.openModelBrowser(insertSlot = PendingInsertSlot("2B", 1), tab = BrowserCategoryTab.AMP)
        assertTrue(vm.uiState.value.isModelBrowserOpen)
        vm.selectAmpModel(ampNativeId = 11)
        val amps = vm.uiState.value.activePreset.signalFlow.blocks.filter { it.nativeBlockCode == RigBlockCode.AMP }
        assertEquals(2, amps.size)
        assertEquals(1, amps.last().instanceIndex)
        assertEquals("2B", amps.last().pathBranch)

        // Removing the 2nd amp disables native instance 1 and updates routing plan
        bridge.cmds.clear()
        val removedId = amps.last().id
        assertTrue(vm.removeBlock(removedId))
        assertEquals(1, vm.uiState.value.activePreset.signalFlow.blocks.count { it.nativeBlockCode == RigBlockCode.AMP })

        // Top Bar rotary Input/Output trim controls
        vm.setInputTrimDb(-3.5f)
        vm.setOutputLevelDb(2.0f)
        assertEquals(-3.5f, vm.uiState.value.activePreset.inputTrimDb, 1e-4f)
        assertEquals(2.0f, vm.uiState.value.activePreset.outputLevelDb, 1e-4f)
    }
}
