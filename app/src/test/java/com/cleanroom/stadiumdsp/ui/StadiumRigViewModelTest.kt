package com.cleanroom.stadiumdsp.ui

import com.cleanroom.stadiumdsp.CloneMetadataUi
import com.cleanroom.stadiumdsp.DspBridgeGateway
import com.cleanroom.stadiumdsp.DspCommandCode
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.StreamDiagnosticsUi
import com.cleanroom.stadiumdsp.ui.state.StadiumRigViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDspBridge : DspBridgeGateway {
    data class SentCmd(val type: Int, val targetId: Int, val paramId: Int, val v0: Float, val intVal: Int)
    val sent = mutableListOf<SentCmd>()
    var recalledSnapshots = mutableListOf<Int>()
    var running = true
    var currentDiagnostics = StreamDiagnosticsUi(
        sampleRate = 48000,
        framesPerBurst = 96,
        bufferSizeFrames = 192,
        bufferCapacityFrames = 768,
        isMmapUsed = true,
        xRunCount = 0,
    )
    var liveBufferCalls = mutableListOf<Int>()
    var autoXrunGuardEnabled = true

    override val isNativeAvailable: Boolean = true
    override fun startAudio(
        enableInput: Boolean,
        inputDeviceId: Int,
        outputDeviceId: Int,
        burstMultiplier: Int,
        requestedBufferFrames: Int,
        autoXrunGuard: Boolean,
    ): Boolean {
        running = true
        autoXrunGuardEnabled = autoXrunGuard
        return true
    }
    override fun stopAudio() { running = false }
    override fun isAudioRunning(): Boolean = running
    override fun setLiveBufferSizeFrames(targetFrames: Int): Int {
        val clamped = targetFrames.coerceIn(currentDiagnostics.framesPerBurst, currentDiagnostics.bufferCapacityFrames)
        liveBufferCalls.add(clamped)
        currentDiagnostics = currentDiagnostics.copy(bufferSizeFrames = clamped)
        return clamped
    }
    override fun setAutoXrunGuard(enabled: Boolean) { autoXrunGuardEnabled = enabled }
    override fun getStreamDiagnostics(): StreamDiagnosticsUi = currentDiagnostics
    override fun enqueueCommand(type: Int, targetId: Int, paramId: Int, flags: Int, value0: Float, value1: Float, value2: Float, intVal: Int): Boolean {
        sent.add(SentCmd(type, targetId, paramId, value0, intVal))
        return true
    }
    override fun readTelemetry(buffer16: FloatArray): LiveTelemetryUi = LiveTelemetryUi(inPeakDb = -12f, outPeakLDb = -6f, outPeakRDb = -6f)
    override fun computeEqCurveDb(outBuffer: FloatArray) { outBuffer.fill(0f) }
    override fun computeCabSpectrumDb(slot: Int, outBuffer: FloatArray) { outBuffer.fill(-12f) }
    override fun setDynamicSlotOrder(order12: IntArray) {}
    override fun setParallelSplitMerge(mode: Int, crossoverHz: Float, routeAb: Float, lvlADb: Float, lvlBDb: Float, panA: Float, panB: Float, invertB: Boolean) {}
    override fun storeSnapshot(idx: Int, bypassMask: Int, params11: FloatArray) {}
    override fun recallSnapshot(idx: Int) { recalledSnapshots.add(idx) }
    override fun registerTapTempoMs(timestampMs: Long): Float = 128f
    override fun loadExternalIr(slot: Int, wavBytes: ByteArray): String = "OK"
    override fun loadExternalNam(jsonBytes: ByteArray): String = "OK"
    override fun loadExternalAidax(jsonBytes: ByteArray): String = "OK"
    override fun getCloneMetadata(): CloneMetadataUi = CloneMetadataUi()
    override fun selectFactoryPresetClipRiff(styleIndex: Int) {}
    override fun loadCustomPresetClipWav(wavBytes: ByteArray): String = "OK"
}

class StadiumRigViewModelTest {

    @Test
    fun stageLockBlocksSliderEditsButAllowsSnapshotsStompsAndMasterMute() {
        val bridge = FakeDspBridge()
        val vm = StadiumRigViewModel(bridge = bridge)
        vm.setStageLock(true)

        val beforeDrive = vm.uiState.value.selectedAmpDrive()
        vm.updateParameter("amp", "amp.drive", 0.99f, fromLiveController = false)
        assertEquals(beforeDrive, vm.uiState.value.selectedAmpDrive(), 1e-5f)

        // Snapshots MUST still respond immediately when Stage Lock is active
        vm.selectSnapshot(3)
        assertEquals(3, vm.uiState.value.activeSnapshotIndex)
        assertTrue(bridge.recalledSnapshots.contains(3))

        // Live Stomp toggle MUST still work under Stage Lock
        val driveWasOn = vm.uiState.value.activePreset.signalFlow.blocks.first { it.id == "drive" }.enabled
        vm.toggleBlocksLive(listOf("drive"))
        val driveNowOn = vm.uiState.value.activePreset.signalFlow.blocks.first { it.id == "drive" }.enabled
        assertEquals(!driveWasOn, driveNowOn)

        // MUTE ALL panic MUST always respond immediately
        vm.toggleMasterMute()
        assertTrue(vm.uiState.value.isMasterMuted)
        assertTrue(bridge.sent.any { it.type == DspCommandCode.SET_MASTER_MUTE && it.intVal == 1 })
    }

    @Test
    fun discardEditsRedCameraRevertsUnsavedParameterOnSnapshotReturn() {
        val bridge = FakeDspBridge()
        val vm = StadiumRigViewModel(bridge = bridge)
        vm.selectSnapshot(1)
        val snap2OrigDrive = vm.uiState.value.selectedAmpDrive()

        vm.setSnapshotDiscardEdits(true)
        vm.updateParameter("amp", "amp.drive", 0.93f)
        assertEquals(0.93f, vm.uiState.value.selectedAmpDrive(), 1e-4f)

        vm.selectSnapshot(2)
        vm.selectSnapshot(1)
        assertEquals(snap2OrigDrive, vm.uiState.value.selectedAmpDrive(), 1e-4f)
    }

    @Test
    fun focusViewXYMorphsParametersAndSupportsUndoRedoAndABCompare() {
        val bridge = FakeDspBridge()
        val vm = StadiumRigViewModel(bridge = bridge)

        vm.updateFocusXY("amp", 0.8f, 0.9f)
        assertEquals(0.8f, vm.uiState.value.activePreset.signalFlow.blocks.first { it.id == "amp" }.focusX, 1e-4f)
        assertTrue(vm.uiState.value.canUndo)

        vm.undo()
        assertEquals(0.0f, vm.uiState.value.activePreset.signalFlow.blocks.first { it.id == "amp" }.focusX, 1e-4f)
        assertTrue(vm.uiState.value.canRedo)

        vm.toggleCompareAB()
        assertTrue(vm.uiState.value.isCompareSlotB)
        vm.toggleCompareAB()
        assertFalse(vm.uiState.value.isCompareSlotB)
    }

    @Test
    fun autoXrunGuardStepsUpLiveBufferAfterWarmupWithoutRestartingStream() {
        val bridge = FakeDspBridge()
        val vm = StadiumRigViewModel(bridge = bridge)

        // 3 warmup ticks (~3 seconds) should NOT bump even if cold-start XRuns exist
        bridge.currentDiagnostics = bridge.currentDiagnostics.copy(xRunCount = 2)
        repeat(181) { vm.pollTelemetryFrame() }
        assertTrue(bridge.liveBufferCalls.isEmpty())

        // After warmup, a new XRun triggers +1 HW burst (192f + 96f = 288f) LIVE!
        bridge.currentDiagnostics = bridge.currentDiagnostics.copy(xRunCount = 3)
        repeat(60) { vm.pollTelemetryFrame() }
        assertEquals(listOf(288), bridge.liveBufferCalls)
        assertEquals(288, vm.uiState.value.streamDiagnostics.bufferSizeFrames)
    }

    private fun com.cleanroom.stadiumdsp.ui.state.StadiumUiState.selectedAmpDrive(): Float =
        activePreset.signalFlow.blocks.first { it.id == "amp" }.params["amp.drive"] ?: 0.5f
}
