package com.cleanroom.stadiumdsp.ui.state

import android.content.Context
import androidx.lifecycle.ViewModel
import com.cleanroom.stadiumdsp.CloneMetadataUi
import com.cleanroom.stadiumdsp.DspBridgeGateway
import com.cleanroom.stadiumdsp.DspCommandCode
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.NativeDspBridge
import com.cleanroom.stadiumdsp.RigBlockCode
import com.cleanroom.stadiumdsp.StreamDiagnosticsUi
import com.cleanroom.stadiumdsp.audio.AudioBufferPlanner
import com.cleanroom.stadiumdsp.audio.AudioDeviceEnumerator
import com.cleanroom.stadiumdsp.audio.AudioDeviceOption
import com.cleanroom.stadiumdsp.audio.AudioDspSettings
import com.cleanroom.stadiumdsp.audio.AudioSettingsRepository
import com.cleanroom.stadiumdsp.audio.BufferSizeMode
import com.cleanroom.stadiumdsp.audio.OboeSessionController
import com.cleanroom.stadiumdsp.catalog.AmpModelCatalog
import com.cleanroom.stadiumdsp.catalog.FactoryCabCatalog
import com.cleanroom.stadiumdsp.importer.ExternalRigAssetKind
import com.cleanroom.stadiumdsp.importer.ImportedExternalFile
import com.cleanroom.stadiumdsp.live.GigStopwatch
import com.cleanroom.stadiumdsp.live.ShowcaseTrackPlayer
import com.cleanroom.stadiumdsp.midi.ControllerAssignmentEngine
import com.cleanroom.stadiumdsp.midi.FootswitchAction
import com.cleanroom.stadiumdsp.midi.FootswitchPad
import com.cleanroom.stadiumdsp.midi.LiveFootswitchMatrix
import com.cleanroom.stadiumdsp.midi.LivePlayMode
import com.cleanroom.stadiumdsp.midi.StadiumMidiAction
import com.cleanroom.stadiumdsp.midi.StadiumMidiParser
import com.cleanroom.stadiumdsp.midi.StageLockPolicy
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.preset.BlockFavoritesStore
import com.cleanroom.stadiumdsp.preset.DualPathRoutingEngine
import com.cleanroom.stadiumdsp.preset.FavoriteBlockEntry
import com.cleanroom.stadiumdsp.preset.FocusZoneBank
import com.cleanroom.stadiumdsp.preset.FocusZoneInterpolator
import com.cleanroom.stadiumdsp.preset.PresetRepository
import com.cleanroom.stadiumdsp.preset.RigBlockState
import com.cleanroom.stadiumdsp.preset.RigHistoryManager
import com.cleanroom.stadiumdsp.preset.SignalFlowState
import com.cleanroom.stadiumdsp.preset.SnapshotManager
import com.cleanroom.stadiumdsp.preset.SplitRoutingMode
import com.cleanroom.stadiumdsp.preset.StadiumPreset
import com.cleanroom.stadiumdsp.ui.components.SignalFlowRoutingLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WorkspaceDestination(val label: String) {
    EDIT("Edit"),
    FOCUS("Focus"),
    LIVE_PLAY("Live Play"),
    PRESETS("Presets"),
    TUNER("Tuner"),
}

/**
 * Exact 12-item Helix Stadium Native Model Browser category rail (+ Favorites).
 */
enum class BrowserCategoryTab(
    val label: String,
    val bannerTitle: String,
    val blockCategory: BlockCategory?,
    val colorHex: Long,
) {
    FAVORITES("Favorites", "FAVORITES", null, 0xFFD8D8D8L),
    AMP("Amp", "GUITAR AMP", BlockCategory.AMP, BlockCategory.AMP.colorHex),
    PREAMP("Preamp", "PREAMP", BlockCategory.AMP, BlockCategory.AMP.colorHex),
    CAB("Cab", "SPEAKER CAB / IR", BlockCategory.CAB, BlockCategory.CAB.colorHex),
    DISTORTION("Distortion", "DISTORTION & DRIVE", BlockCategory.DISTORTION, BlockCategory.DISTORTION.colorHex),
    DELAY("Delay", "DELAY", BlockCategory.DELAY, BlockCategory.DELAY.colorHex),
    REVERB("Reverb", "REVERB", BlockCategory.REVERB, BlockCategory.REVERB.colorHex),
    MODULATION("Modulation", "MODULATION", BlockCategory.MODULATION, BlockCategory.MODULATION.colorHex),
    DYNAMICS("Dynamics", "DYNAMICS", BlockCategory.DYNAMICS, BlockCategory.DYNAMICS.colorHex),
    EQ("EQ", "EQUALIZER", BlockCategory.EQ, BlockCategory.EQ.colorHex),
    PITCH_SYNTH("Pitch/Synth", "PITCH / SYNTH", BlockCategory.PITCH_WAH, BlockCategory.PITCH_WAH.colorHex),
    WAH_FILTER("Wah/Filter", "WAH / FILTER", BlockCategory.PITCH_WAH, BlockCategory.PITCH_WAH.colorHex),
    VOL_PAN("Volume/Pan", "VOLUME / PAN", BlockCategory.VOL_PAN, BlockCategory.VOL_PAN.colorHex);

    companion object {
        fun fromBlock(blk: RigBlockState): BrowserCategoryTab = when (blk.nativeBlockCode) {
            RigBlockCode.AMP -> AMP
            RigBlockCode.CAB -> CAB
            RigBlockCode.DRIVE -> DISTORTION
            RigBlockCode.DELAY -> DELAY
            RigBlockCode.REVERB -> REVERB
            RigBlockCode.MOD_PRE, RigBlockCode.MOD_POST -> MODULATION
            RigBlockCode.GATE, RigBlockCode.COMP -> DYNAMICS
            RigBlockCode.EQ -> EQ
            RigBlockCode.PITCH -> PITCH_SYNTH
            RigBlockCode.WAH -> WAH_FILTER
            RigBlockCode.VOL_PAN -> VOL_PAN
            else -> AMP
        }
    }
}

data class PendingInsertSlot(
    val branch: String,
    val indexInBranch: Int,
)

data class TunerConfigUi(
    val enabled: Boolean = false,
    val refPitchHz: Float = 440f,
    val muteOutput: Boolean = true,
    val strobeMode: Boolean = true,
    val useFlats: Boolean = false,
)

private val EMPTY_CANVAS_PLACEHOLDER = RigBlockState(
    id = "__empty__",
    nativeBlockCode = RigBlockCode.AMP,
    category = BlockCategory.ROUTING,
    modelId = 0,
    displayName = "Empty Signal Slot",
    shortLabel = "Empty",
    enabled = false,
    pathBranch = "1A",
)

data class StadiumUiState(
    val activePreset: StadiumPreset = StadiumPreset.createDefaultPreset(),
    val allPresets: List<StadiumPreset> = emptyList(),
    val selectedBlockId: String = "amp",
    val isModelBrowserOpen: Boolean = true,
    val pendingInsertSlot: PendingInsertSlot? = null,
    val activeBrowserTab: BrowserCategoryTab = BrowserCategoryTab.AMP,
    val activeSnapshotIndex: Int = 0,
    val snapshotDiscardEdits: Boolean = false, // false=White Camera (Auto-Remember), true=Red Camera (Discard Edits)
    val stageLock: Boolean = false,
    val isMasterMuted: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isCompareSlotB: Boolean = false,
    val isPresetClipPlaying: Boolean = false,
    val presetClipStyleIndex: Int = 1, // 0=Clean, 1=Crunch, 2=Metal, 3=Bass
    val tunerConfig: TunerConfigUi = TunerConfigUi(),
    val livePlayMode: LivePlayMode = LivePlayMode.COMBO_4_PLUS_4,
    val livePads: List<FootswitchPad> = emptyList(),
    val exp1Value: Float = 0.0f,
    val exp2Value: Float = 1.0f,
    val activeExpSlot: Int = 1,
    val connectedMidiDevices: List<String> = emptyList(),
    val midiLearnTargetKey: String? = null,
    val learnedMidiMap: Map<String, Int> = emptyMap(),
    val cloneMetadata: CloneMetadataUi = CloneMetadataUi(),
    val externalIrLabel: String? = null,
    val eqCurveDb: List<Float> = List(64) { 0f },
    val cabSpectrumDb: List<Float> = List(64) { -12f },
    val audioSettings: AudioDspSettings = AudioDspSettings(),
    val availableInputDevices: List<AudioDeviceOption> = listOf(
        AudioDeviceOption(id = 0, name = "System Default (Auto)", typeBadge = "AUTO", isInput = true),
    ),
    val availableOutputDevices: List<AudioDeviceOption> = listOf(
        AudioDeviceOption(id = 0, name = "System Default (Auto)", typeBadge = "AUTO", isInput = false),
    ),
    val streamDiagnostics: StreamDiagnosticsUi = StreamDiagnosticsUi(),
    val isAudioRunning: Boolean = false,
    val statusBanner: String = "Stadium DSP Ready • 48 kHz Zero-Latency",
    val showcasePlaying: Boolean = false,
    val showcaseNextCueLabel: String = "Intro Clean (0:00)",
    val gigStopwatchDisplay: String = "00:00",
    val gigStopwatchRunning: Boolean = false,
    val favoritesCount: Int = 0,
) {
    val selectedBlockOrNull: RigBlockState?
        get() = activePreset.signalFlow.blocks.firstOrNull { it.id == selectedBlockId }
            ?: activePreset.signalFlow.blocks.firstOrNull()

    val selectedBlock: RigBlockState
        get() = selectedBlockOrNull ?: EMPTY_CANVAS_PLACEHOLDER
}

/**
 * Rotation-safe ViewModel orchestrating all 23 GAP Foundation engines and C++20 SPSC commands.
 * Isolates high-frequency 60 Hz telemetry into `telemetry` so the main UI tree never recomposes at 60 fps.
 */
class StadiumRigViewModel(
    private val bridge: DspBridgeGateway = NativeDspBridge,
    private val repository: PresetRepository = PresetRepository(),
) : ViewModel() {

    private val initialPreset = repository.allPresets().firstOrNull() ?: StadiumPreset.createDefaultPreset()
    private val history = RigHistoryManager(initialPreset)
    private val snapshotManager = SnapshotManager(initialPreset.snapshots, initialActiveIndex = 0)
    private val favoritesStore = BlockFavoritesStore()
    private val controllerEngine = ControllerAssignmentEngine.createDefault()
    private val footswitchMatrix = LiveFootswitchMatrix(initialPreset.snapshots)
    private val gigStopwatch = GigStopwatch(kernel = com.cleanroom.stadiumdsp.rustbridge.LiveTimingRustBridge.INSTANCE)
    private val showcasePlayer = ShowcaseTrackPlayer(
        kernel = com.cleanroom.stadiumdsp.rustbridge.LiveTimingRustBridge.INSTANCE,
        onCueTriggered = { cue ->
            cue.snapshotIndex?.let { selectSnapshot(it) }
            cue.presetId?.let { selectPreset(it) }
        }
    )

    val midiParser = StadiumMidiParser(
        onAction = { action -> handleMidiAction(action) },
        onLearnedCc = { target, cc ->
            _uiState.update { st ->
                st.copy(
                    midiLearnTargetKey = null,
                    learnedMidiMap = st.learnedMidiMap + (target to cc),
                    statusBanner = "MIDI Learned: $target -> CC#$cc",
                )
            }
        },
    )

    private val telemetryScratch = FloatArray(16)
    private val eqScratch = FloatArray(64)
    private val cabSpectrumScratch = FloatArray(64)
    private var telemetryPollCounter = 0
    private var lastObservedXRuns = 0
    private var xRunGuardWarmupTicks = 0

    private val _telemetry = MutableStateFlow(LiveTelemetryUi())
    val telemetry: StateFlow<LiveTelemetryUi> = _telemetry.asStateFlow()

    private val _uiState = MutableStateFlow(
        StadiumUiState(
            activePreset = initialPreset,
            allPresets = repository.allPresets(),
            livePads = footswitchMatrix.padsForMode(LivePlayMode.COMBO_4_PLUS_4, initialPreset.snapshots),
            isAudioRunning = bridge.isAudioRunning(),
            streamDiagnostics = bridge.getStreamDiagnostics(),
        )
    )
    val uiState: StateFlow<StadiumUiState> = _uiState.asStateFlow()

    init {
        syncFullPresetToNative(initialPreset)
        refreshEqAndCabPlots()
    }

    fun pollTelemetryFrame() {
        val snap = bridge.readTelemetry(telemetryScratch)
        _telemetry.value = snap
        telemetryPollCounter++

        if (telemetryPollCounter % 60 == 1) {
            val running = bridge.isAudioRunning()
            var diag = bridge.getStreamDiagnostics()

            // Off-RT Auto XRun Guard (never blocks audio thread!): bumps +1 HW burst on new XRun after warmup
            if (running && _uiState.value.audioSettings.autoXrunGuard) {
                if (xRunGuardWarmupTicks < 3) {
                    xRunGuardWarmupTicks++
                    lastObservedXRuns = diag.xRunCount
                } else if (diag.xRunCount > lastObservedXRuns) {
                    lastObservedXRuns = diag.xRunCount
                    val nextFrames = AudioBufferPlanner.computeNextXrunGuardStep(
                        currentBufferFrames = diag.bufferSizeFrames,
                        framesPerBurst = diag.framesPerBurst,
                        bufferCapacityFrames = diag.bufferCapacityFrames,
                    )
                    if (nextFrames != null) {
                        val applied = bridge.setLiveBufferSizeFrames(nextFrames)
                        diag = bridge.getStreamDiagnostics()
                        val ms = AudioBufferPlanner.framesToMs(applied, diag.sampleRate)
                        _uiState.update {
                            it.copy(
                                streamDiagnostics = diag,
                                statusBanner = String.format(
                                    "Auto XRun Guard -> %df (%.1f ms) • XRuns: %d",
                                    applied, ms, diag.xRunCount,
                                ),
                            )
                        }
                    }
                }
            } else {
                lastObservedXRuns = diag.xRunCount
            }

            _uiState.update { it.copy(streamDiagnostics = diag, isAudioRunning = running) }

            if (telemetryPollCounter % 120 == 1) {
                runCatching {
                    android.util.Log.i(
                        "StadiumGate4Telemetry",
                        String.format(
                            "running=%s sr=%d burst=%df buf=%df/%df outBufMs=%.2f rtlMs=%.2f mmapExcl=%s xruns=%d rtCpu=%.2f%% inPeak=%.1fdB outL=%.1fdB outR=%.1fdB snap=%d",
                            running, diag.sampleRate, diag.framesPerBurst, diag.bufferSizeFrames,
                            diag.bufferCapacityFrames, diag.outputBufferMs, diag.roundTripLatencyMs,
                            diag.isMmapUsed, diag.xRunCount, snap.rtCpuLoadPct,
                            snap.inPeakDb, snap.outPeakLDb, snap.outPeakRDb, snap.activeSnapshot,
                        ),
                    )
                }
            }
        }

        if (gigStopwatch.isRunning || showcasePlayer.isPlaying) {
            val nowMs = System.currentTimeMillis()
            if (showcasePlayer.isPlaying) {
                showcasePlayer.advanceTo(showcasePlayer.positionMs + 16L)
            }
            val nextCue = showcasePlayer.nextUpcomingCue()
            _uiState.update {
                it.copy(
                    gigStopwatchDisplay = gigStopwatch.formatElapsed(nowMs),
                    showcaseNextCueLabel = nextCue?.let { c -> "${c.label} (${c.timestampMs / 1000}s)" } ?: "End of Cues",
                )
            }
        }
    }

    /**
     * Loads persisted Audio/DSP preferences + enumerates currently connected AudioManager I/O devices.
     */
    fun initializeAudioEnvironment(context: Context): AudioDspSettings {
        val inputs = AudioDeviceEnumerator.listInputDevices(context)
        val outputs = AudioDeviceEnumerator.listOutputDevices(context)
        val loaded = AudioSettingsRepository.load(context)
        val resolved = loaded.copy(
            inputDeviceId = AudioBufferPlanner.resolveAvailableDeviceId(loaded.inputDeviceId, inputs),
            outputDeviceId = AudioBufferPlanner.resolveAvailableDeviceId(loaded.outputDeviceId, outputs),
        )
        _uiState.update {
            it.copy(
                audioSettings = resolved,
                availableInputDevices = inputs,
                availableOutputDevices = outputs,
            )
        }
        return resolved
    }

    fun refreshAudioDevices(context: Context) {
        val inputs = AudioDeviceEnumerator.listInputDevices(context)
        val outputs = AudioDeviceEnumerator.listOutputDevices(context)
        _uiState.update { st ->
            st.copy(
                availableInputDevices = inputs,
                availableOutputDevices = outputs,
                audioSettings = st.audioSettings.copy(
                    inputDeviceId = AudioBufferPlanner.resolveAvailableDeviceId(st.audioSettings.inputDeviceId, inputs),
                    outputDeviceId = AudioBufferPlanner.resolveAvailableDeviceId(st.audioSettings.outputDeviceId, outputs),
                ),
            )
        }
    }

    fun startAudioEngine(context: Context, settings: AudioDspSettings = _uiState.value.audioSettings) {
        OboeSessionController.bridge = bridge
        val ok = OboeSessionController.ensureStarted(context, settings)
        syncFullPresetToNative(_uiState.value.activePreset)
        telemetryPollCounter = 0
        xRunGuardWarmupTicks = 0
        val diag = bridge.getStreamDiagnostics()
        lastObservedXRuns = diag.xRunCount
        _uiState.update {
            it.copy(
                audioSettings = settings,
                isAudioRunning = ok || bridge.isAudioRunning(),
                streamDiagnostics = diag,
                statusBanner = if (ok) {
                    String.format(
                        "Oboe Active (%d Hz • %df / %.1f ms • %s)",
                        diag.sampleRate,
                        diag.bufferSizeFrames,
                        diag.outputBufferMs,
                        if (diag.isMmapUsed) "MMAP" else "AAudio",
                    )
                } else {
                    "Audio Fallback Ready"
                },
            )
        }
    }

    /**
     * Live zero-glitch buffer tuning directly from slider/chips without stopping audio.
     */
    fun setLiveBufferFrames(
        context: Context,
        targetFrames: Int,
        mode: BufferSizeMode,
        burstMultiplier: Int,
    ) {
        OboeSessionController.bridge = bridge
        val applied = OboeSessionController.setLiveBufferFrames(context, targetFrames, mode, burstMultiplier)
        val diag = bridge.getStreamDiagnostics()
        lastObservedXRuns = diag.xRunCount
        val ms = AudioBufferPlanner.framesToMs(applied, diag.sampleRate)
        _uiState.update {
            it.copy(
                audioSettings = OboeSessionController.currentSettings,
                streamDiagnostics = diag,
                statusBanner = String.format("Live Buffer: %df (%.2f ms) • 0 ms glitch", applied, ms),
            )
        }
    }

    fun resetBufferToMinimum(context: Context) {
        val diag = bridge.getStreamDiagnostics()
        setLiveBufferFrames(
            context = context,
            targetFrames = diag.framesPerBurst.coerceAtLeast(16),
            mode = BufferSizeMode.HARDWARE_BURST,
            burstMultiplier = 1,
        )
    }

    fun restartAudioWithSettings(context: Context, settings: AudioDspSettings) {
        OboeSessionController.bridge = bridge
        val prev = _uiState.value.audioSettings
        val willRestart = !bridge.isAudioRunning() || AudioBufferPlanner.needsStreamRestart(prev, settings)
        val ok = OboeSessionController.applySettings(context, settings)
        if (willRestart) {
            syncFullPresetToNative(_uiState.value.activePreset)
            xRunGuardWarmupTicks = 0
        }
        telemetryPollCounter = 0
        val diag = bridge.getStreamDiagnostics()
        lastObservedXRuns = diag.xRunCount
        _uiState.update {
            it.copy(
                audioSettings = settings,
                isAudioRunning = ok || bridge.isAudioRunning(),
                streamDiagnostics = diag,
                statusBanner = if (willRestart) {
                    String.format(
                        "Audio Applied (%df • %.1f ms • %s)",
                        diag.bufferSizeFrames,
                        diag.outputBufferMs,
                        if (diag.isMmapUsed) "MMAP" else "Shared",
                    )
                } else {
                    String.format(
                        "Live Buffer Applied (%df / %df • %.1f ms)",
                        diag.bufferSizeFrames,
                        diag.bufferCapacityFrames,
                        diag.outputBufferMs,
                    )
                },
            )
        }
    }

    fun updateMidiDevices(names: List<String>) {
        _uiState.update { it.copy(connectedMidiDevices = names) }
    }

    fun selectPreset(presetId: String) {
        val found = repository.byId(presetId) ?: return
        history.reset(found)
        snapshotManager.replaceAll(found.snapshots, active = 0)
        syncFullPresetToNative(found)
        _uiState.update {
            it.copy(
                activePreset = found,
                activeSnapshotIndex = 0,
                canUndo = false,
                canRedo = false,
                isCompareSlotB = false,
                livePads = footswitchMatrix.padsForMode(it.livePlayMode, found.snapshots),
                statusBanner = "Loaded Preset ${found.bankCode} — ${found.name}",
            )
        }
        refreshEqAndCabPlots()
    }

    fun stepPreset(delta: Int) {
        val list = repository.allPresets()
        if (list.isEmpty()) return
        val curIdx = list.indexOfFirst { it.id == _uiState.value.activePreset.id }.coerceAtLeast(0)
        val nextIdx = (curIdx + delta + list.size) % list.size
        selectPreset(list[nextIdx].id)
    }

    fun saveCurrentAsUserPreset(customName: String) {
        val base = _uiState.value.activePreset
        val id = if (base.isFactory) "user_${System.currentTimeMillis()}" else base.id
        val userPreset = base.copy(
            id = id,
            bankCode = "U${(repository.userBank().size + 1).toString().padStart(2, '0')}",
            name = customName.trim().ifEmpty { "${base.name} (User)" },
            isFactory = false,
            snapshots = snapshotManager.allSnapshots(),
        )
        if (repository.saveUserPreset(userPreset)) {
            _uiState.update {
                it.copy(
                    activePreset = userPreset,
                    allPresets = repository.allPresets(),
                    statusBanner = "Saved User Preset: ${userPreset.name}",
                )
            }
        }
    }

    fun undo() {
        if (!history.canUndo) return
        val prev = history.undo()
        syncFullPresetToNative(prev)
        _uiState.update {
            it.copy(activePreset = prev, canUndo = history.canUndo, canRedo = history.canRedo, statusBanner = "Undo")
        }
        refreshEqAndCabPlots()
    }

    fun redo() {
        if (!history.canRedo) return
        val next = history.redo()
        syncFullPresetToNative(next)
        _uiState.update {
            it.copy(activePreset = next, canUndo = history.canUndo, canRedo = history.canRedo, statusBanner = "Redo")
        }
        refreshEqAndCabPlots()
    }

    fun toggleCompareAB() {
        val swapped = history.toggleAB()
        syncFullPresetToNative(swapped)
        _uiState.update {
            it.copy(
                activePreset = swapped,
                isCompareSlotB = history.isSlotB,
                statusBanner = if (history.isSlotB) "Compare Slot B Active" else "Compare Slot A Active",
            )
        }
    }

    fun copyActiveCompareSlot() {
        history.copyActiveToOtherSlot()
        _uiState.update {
            it.copy(statusBanner = if (history.isSlotB) "Copied Slot B -> A" else "Copied Slot A -> B")
        }
    }

    fun selectSnapshot(index: Int) {
        val snap = snapshotManager.selectSnapshot(index)
        bridge.recallSnapshot(snap.index)

        val updatedBlocks = _uiState.value.activePreset.signalFlow.blocks.map { blk ->
            val newEnabled = if (blk.snapshotsSetBypass && snap.blockEnabled.containsKey(blk.id)) {
                snap.blockEnabled[blk.id] ?: blk.enabled
            } else {
                blk.enabled
            }
            val mergedParams = blk.params.toMutableMap()
            snap.paramOverrides.forEach { (k, v) ->
                if (mergedParams.containsKey(k)) {
                    mergedParams[k] = v
                }
            }
            blk.copy(enabled = newEnabled, params = mergedParams)
        }

        val updatedPreset = _uiState.value.activePreset.copy(
            signalFlow = _uiState.value.activePreset.signalFlow.copy(blocks = updatedBlocks),
            snapshots = snapshotManager.allSnapshots(),
        )
        _uiState.update {
            it.copy(
                activePreset = updatedPreset,
                activeSnapshotIndex = snap.index,
                livePads = footswitchMatrix.padsForMode(it.livePlayMode, updatedPreset.snapshots),
                statusBanner = "Snapshot ${snap.index + 1}: ${snap.name} (Zero-Gap Trails)",
            )
        }
    }

    fun setSnapshotDiscardEdits(discard: Boolean) {
        snapshotManager.setDiscardEdits(discard)
        _uiState.update {
            it.copy(
                snapshotDiscardEdits = discard,
                statusBanner = if (discard) "Red Camera: Snapshot Discard Edits (Stage Safe)" else "White Camera: Snapshot Auto-Remember",
            )
        }
    }

    fun setStageLock(locked: Boolean) {
        snapshotManager.setDiscardEdits(locked)
        _uiState.update {
            it.copy(
                stageLock = locked,
                snapshotDiscardEdits = locked,
                statusBanner = if (locked) "STAGE LOCK ON (Sliders Protected • Live Pads Active)" else "Stage Lock Released (Studio Edit)",
            )
        }
    }

    fun toggleMasterMute() {
        val nextMuted = !_uiState.value.isMasterMuted
        bridge.enqueueCommand(DspCommandCode.SET_MASTER_MUTE, intVal = if (nextMuted) 1 else 0)
        _uiState.update {
            it.copy(
                isMasterMuted = nextMuted,
                statusBanner = if (nextMuted) "MASTER OUTPUT MUTED (-200 dBFS)" else "Master Output Active",
            )
        }
    }

    fun toggleSnapshotParamControl(paramKey: String) {
        val curSet = _uiState.value.activePreset.snapshotControlledParams
        val nextSet = if (paramKey in curSet) curSet - paramKey else curSet + paramKey
        commitPresetMutation(_uiState.value.activePreset.copy(snapshotControlledParams = nextSet))
    }

    fun selectBlock(blockId: String, openBrowser: Boolean = true) {
        val blk = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == blockId }
        _uiState.update { st ->
            st.copy(
                selectedBlockId = blockId,
                isModelBrowserOpen = openBrowser,
                pendingInsertSlot = null,
                activeBrowserTab = blk?.let { BrowserCategoryTab.fromBlock(it) } ?: st.activeBrowserTab,
            )
        }
        refreshEqAndCabPlots()
    }

    fun openModelBrowser(
        forBlockId: String? = null,
        insertSlot: PendingInsertSlot? = null,
        tab: BrowserCategoryTab? = null,
    ) {
        val blocks = _uiState.value.activePreset.signalFlow.blocks
        val blk = forBlockId?.let { id -> blocks.firstOrNull { it.id == id } }
        _uiState.update { st ->
            st.copy(
                selectedBlockId = forBlockId ?: st.selectedBlockId,
                isModelBrowserOpen = true,
                pendingInsertSlot = insertSlot,
                activeBrowserTab = tab ?: blk?.let { BrowserCategoryTab.fromBlock(it) } ?: st.activeBrowserTab,
            )
        }
    }

    fun closeModelBrowser() {
        if (!_uiState.value.isModelBrowserOpen && _uiState.value.pendingInsertSlot == null) return
        _uiState.update { it.copy(isModelBrowserOpen = false, pendingInsertSlot = null) }
    }

    fun toggleModelBrowser() {
        _uiState.update { st ->
            val nextOpen = !st.isModelBrowserOpen
            st.copy(
                isModelBrowserOpen = nextOpen,
                pendingInsertSlot = if (nextOpen) st.pendingInsertSlot else null,
            )
        }
    }

    fun setBrowserTab(tab: BrowserCategoryTab) {
        _uiState.update { it.copy(activeBrowserTab = tab, isModelBrowserOpen = true) }
    }

    fun insertBlockAt(
        category: BlockCategory,
        nativeBlockCode: Int,
        modelId: Int,
        displayName: String,
        shortLabel: String,
        targetBranch: String,
        targetIndexInBranch: Int,
        isPreamp: Boolean = false,
    ): Boolean {
        if (!StageLockPolicy.allowBlockReorderOrDelete(_uiState.value.stageLock)) return false
        val currentFlow = _uiState.value.activePreset.signalFlow
        val res = DualPathRoutingEngine.insertBlockAt(
            flow = currentFlow,
            category = category,
            nativeBlockCode = nativeBlockCode,
            modelId = modelId,
            displayName = displayName,
            shortLabel = shortLabel,
            targetBranch = targetBranch,
            targetIndexInBranch = targetIndexInBranch,
            isPreamp = isPreamp,
        )
        if (res.error != null || res.activeBlockId == null) {
            _uiState.update { it.copy(statusBanner = res.error ?: "Cannot insert block") }
            return false
        }
        val newBlock = res.flow.blocks.firstOrNull { it.id == res.activeBlockId }
        if (newBlock != null) {
            syncSingleBlockToNative(newBlock)
        }
        syncDualPathRoutingToNative(res.flow)
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = res.flow),
            banner = "Added $displayName to Path $targetBranch",
        )
        _uiState.update { st ->
            st.copy(
                selectedBlockId = res.activeBlockId,
                pendingInsertSlot = null,
                activeBrowserTab = newBlock?.let { BrowserCategoryTab.fromBlock(it) } ?: st.activeBrowserTab,
            )
        }
        refreshEqAndCabPlots()
        return true
    }

    fun replaceBlockAt(
        blockId: String,
        category: BlockCategory,
        nativeBlockCode: Int,
        modelId: Int,
        displayName: String,
        shortLabel: String,
        isPreamp: Boolean = false,
    ): Boolean {
        if (!StageLockPolicy.allowBlockReorderOrDelete(_uiState.value.stageLock)) return false
        val currentFlow = _uiState.value.activePreset.signalFlow
        val oldBlock = currentFlow.blocks.firstOrNull { it.id == blockId } ?: return false
        val res = DualPathRoutingEngine.replaceBlockAt(
            flow = currentFlow,
            blockId = blockId,
            category = category,
            nativeBlockCode = nativeBlockCode,
            modelId = modelId,
            displayName = displayName,
            shortLabel = shortLabel,
            isPreamp = isPreamp,
        )
        if (res.error != null) {
            _uiState.update { it.copy(statusBanner = res.error) }
            return false
        }
        if (DualPathRoutingEngine.poolFamilyKey(oldBlock.nativeBlockCode) != DualPathRoutingEngine.poolFamilyKey(nativeBlockCode)) {
            dispatchBlockBypassToNative(oldBlock, enabled = false)
        }
        val replaced = res.flow.blocks.firstOrNull { it.id == blockId }
        if (replaced != null) {
            syncSingleBlockToNative(replaced)
        }
        syncDualPathRoutingToNative(res.flow)
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = res.flow),
            banner = "Loaded $displayName",
        )
        _uiState.update { st ->
            st.copy(
                selectedBlockId = blockId,
                pendingInsertSlot = null,
                activeBrowserTab = replaced?.let { BrowserCategoryTab.fromBlock(it) } ?: st.activeBrowserTab,
            )
        }
        refreshEqAndCabPlots()
        return true
    }

    fun moveBlockTo(blockId: String, targetBranch: String, targetIndexInBranch: Int): Boolean {
        if (!StageLockPolicy.allowBlockReorderOrDelete(_uiState.value.stageLock)) return false
        val currentFlow = _uiState.value.activePreset.signalFlow
        val res = DualPathRoutingEngine.moveBlockTo(currentFlow, blockId, targetBranch, targetIndexInBranch)
        if (res.error != null) {
            _uiState.update { it.copy(statusBanner = res.error) }
            return false
        }
        syncDualPathRoutingToNative(res.flow)
        val movedName = res.flow.blocks.firstOrNull { it.id == blockId }?.shortLabel ?: blockId
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = res.flow),
            banner = "Moved $movedName -> Path $targetBranch",
        )
        _uiState.update { it.copy(selectedBlockId = blockId) }
        return true
    }

    fun removeBlock(blockId: String): Boolean {
        if (!StageLockPolicy.allowBlockReorderOrDelete(_uiState.value.stageLock)) return false
        val currentFlow = _uiState.value.activePreset.signalFlow
        val target = currentFlow.blocks.firstOrNull { it.id == blockId } ?: return false
        dispatchBlockBypassToNative(target, enabled = false)
        val res = DualPathRoutingEngine.removeBlock(currentFlow, blockId)
        syncDualPathRoutingToNative(res.flow)
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = res.flow),
            banner = "Removed ${target.displayName}",
        )
        _uiState.update { st ->
            st.copy(
                selectedBlockId = res.activeBlockId ?: "",
                pendingInsertSlot = null,
            )
        }
        refreshEqAndCabPlots()
        return true
    }

    fun toggleBlockBypass(blockId: String, fromLiveController: Boolean = false) {
        if (!fromLiveController && !StageLockPolicy.allowParameterEdit(_uiState.value.stageLock, false)) return
        toggleBlocksInternal(listOf(blockId))
    }

    fun toggleBlocksLive(blockIds: List<String>) {
        toggleBlocksInternal(blockIds)
    }

    private fun toggleBlocksInternal(blockIds: List<String>) {
        val targetSet = blockIds.toSet()
        val updatedBlocks = _uiState.value.activePreset.signalFlow.blocks.map { blk ->
            if (blk.id in targetSet) {
                val next = !blk.enabled
                dispatchBlockBypassToNative(blk, next)
                snapshotManager.recordBypassEdit(blk.id, next)
                blk.copy(enabled = next)
            } else {
                blk
            }
        }
        commitPresetMutation(
            _uiState.value.activePreset.copy(
                signalFlow = _uiState.value.activePreset.signalFlow.copy(blocks = updatedBlocks),
                snapshots = snapshotManager.allSnapshots(),
            )
        )
    }

    fun toggleBlockStereo(blockId: String) {
        if (_uiState.value.stageLock) return
        val updatedBlocks = _uiState.value.activePreset.signalFlow.blocks.map { blk ->
            if (blk.id == blockId) {
                val nextStereo = !blk.isStereo
                bridge.enqueueCommand(
                    DspCommandCode.SET_BLOCK_STEREO,
                    targetId = blk.nativeBlockCode,
                    flags = blk.instanceIndex,
                    intVal = if (nextStereo) 1 else 0,
                )
                blk.copy(isStereo = nextStereo)
            } else blk
        }
        commitPresetMutation(_uiState.value.activePreset.copy(signalFlow = _uiState.value.activePreset.signalFlow.copy(blocks = updatedBlocks)))
    }

    fun moveBlock(fromIndex: Int, toIndex: Int) {
        if (!StageLockPolicy.allowBlockReorderOrDelete(_uiState.value.stageLock)) return
        val updatedFlow = SignalFlowRoutingLogic.moveBlock(_uiState.value.activePreset.signalFlow, fromIndex, toIndex)
        syncDualPathRoutingToNative(updatedFlow)
        commitPresetMutation(_uiState.value.activePreset.copy(signalFlow = updatedFlow))
    }

    fun setSplitMode(mode: SplitRoutingMode) {
        if (_uiState.value.stageLock) return
        val flow = _uiState.value.activePreset.signalFlow.copy(splitMode = mode)
        syncDualPathRoutingToNative(flow)
        commitPresetMutation(_uiState.value.activePreset.copy(signalFlow = flow))
    }

    fun cycleSplitMode2() {
        if (_uiState.value.stageLock) return
        val all = SplitRoutingMode.entries
        val cur = _uiState.value.activePreset.signalFlow.splitMode2
        val next = all[(cur.ordinal + 1) % all.size]
        val flow = _uiState.value.activePreset.signalFlow.copy(splitMode2 = next)
        syncDualPathRoutingToNative(flow)
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = flow),
            banner = "Path 2 Split: ${next.label}",
        )
    }

    fun toggleMerge1bInto1a() {
        if (_uiState.value.stageLock) return
        val cur = _uiState.value.activePreset.signalFlow
        val flow = cur.copy(merge1bInto1a = !cur.merge1bInto1a)
        syncDualPathRoutingToNative(flow)
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = flow),
            banner = if (flow.merge1bInto1a) "Path 1B Merged into 1A" else "Path 1B Direct Parallel Out",
        )
    }

    fun toggleAmpCabLink() {
        val next = !_uiState.value.activePreset.signalFlow.ampCabLink
        bridge.enqueueCommand(DspCommandCode.SET_CAB_SLOT_PARAM, targetId = 0, paramId = 13, intVal = if (next) 1 else 0)
        commitPresetMutation(_uiState.value.activePreset.copy(signalFlow = _uiState.value.activePreset.signalFlow.copy(ampCabLink = next)))
    }

    fun toggleCabCabLink() {
        val next = !_uiState.value.activePreset.signalFlow.cabCabLink
        bridge.enqueueCommand(DspCommandCode.SET_CAB_SLOT_PARAM, targetId = 0, paramId = 14, intVal = if (next) 1 else 0)
        commitPresetMutation(_uiState.value.activePreset.copy(signalFlow = _uiState.value.activePreset.signalFlow.copy(cabCabLink = next)))
    }

    fun selectAmpModel(ampNativeId: Int, isPreamp: Boolean = false) {
        if (_uiState.value.stageLock) return
        val spec = AmpModelCatalog.byId(ampNativeId)
        val pending = _uiState.value.pendingInsertSlot
        if (pending != null) {
            insertBlockAt(
                category = BlockCategory.AMP,
                nativeBlockCode = RigBlockCode.AMP,
                modelId = ampNativeId,
                displayName = if (isPreamp) "${spec.displayName} Pre" else spec.displayName,
                shortLabel = spec.shortLabel,
                targetBranch = pending.branch,
                targetIndexInBranch = pending.indexInBranch,
                isPreamp = isPreamp,
            )
            return
        }
        val sel = _uiState.value.selectedBlockOrNull
        if (sel != null && sel.nativeBlockCode == RigBlockCode.AMP && sel.instanceIndex > 0) {
            replaceBlockAt(
                blockId = sel.id,
                category = BlockCategory.AMP,
                nativeBlockCode = RigBlockCode.AMP,
                modelId = ampNativeId,
                displayName = if (isPreamp) "${spec.displayName} Pre" else spec.displayName,
                shortLabel = spec.shortLabel,
                isPreamp = isPreamp,
            )
            return
        }
        if (sel != null && sel.nativeBlockCode != RigBlockCode.AMP && _uiState.value.activePreset.signalFlow.blocks.none { it.id == "amp" }) {
            replaceBlockAt(
                blockId = sel.id,
                category = BlockCategory.AMP,
                nativeBlockCode = RigBlockCode.AMP,
                modelId = ampNativeId,
                displayName = if (isPreamp) "${spec.displayName} Pre" else spec.displayName,
                shortLabel = spec.shortLabel,
                isPreamp = isPreamp,
            )
            return
        }
        val updatedFlow = SignalFlowRoutingLogic.selectAmpModel(_uiState.value.activePreset.signalFlow, ampNativeId)
        val finalFlow = if (isPreamp) {
            updatedFlow.copy(
                blocks = updatedFlow.blocks.map { b ->
                    if (b.id == "amp") {
                        b.copy(
                            displayName = "${spec.displayName} Pre",
                            params = b.params + mapOf("amp.master" to 0.22f, "amp.sag" to 0.0f, "amp.ripple" to 0.0f),
                        )
                    } else b
                }
            )
        } else {
            updatedFlow
        }
        bridge.enqueueCommand(DspCommandCode.SET_RIG_ROUTING_MODE, intVal = 0)
        bridge.enqueueCommand(DspCommandCode.SET_AMP_MODEL, flags = 0, intVal = ampNativeId)
        if (finalFlow.ampCabLink) {
            bridge.enqueueCommand(DspCommandCode.SET_CAB_SLOT_PARAM, targetId = 0, paramId = 1, intVal = spec.defaultCabId)
        }
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = finalFlow.copy(routingMode = 0)),
            banner = "${if (isPreamp) "Preamp" else "Amp"}: ${spec.displayName} (*Based on ${spec.basedOn})",
        )
        refreshEqAndCabPlots()
    }

    fun selectCabModel(cabNativeId: Int) {
        if (_uiState.value.stageLock) return
        val spec = FactoryCabCatalog.cabById(cabNativeId)
        val pending = _uiState.value.pendingInsertSlot
        if (pending != null) {
            insertBlockAt(
                category = BlockCategory.CAB,
                nativeBlockCode = RigBlockCode.CAB,
                modelId = cabNativeId,
                displayName = spec.displayName,
                shortLabel = spec.shortLabel,
                targetBranch = pending.branch,
                targetIndexInBranch = pending.indexInBranch,
            )
            return
        }
        val targetCab = _uiState.value.selectedBlockOrNull?.takeIf { it.nativeBlockCode == RigBlockCode.CAB }
            ?: _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.nativeBlockCode == RigBlockCode.CAB }
        val targetId = targetCab?.id ?: return
        bridge.enqueueCommand(
            DspCommandCode.SET_CAB_SLOT_PARAM,
            targetId = 0,
            paramId = 1,
            flags = targetCab.instanceIndex,
            intVal = cabNativeId,
        )
        val updatedBlocks = _uiState.value.activePreset.signalFlow.blocks.map { blk ->
            if (blk.id == targetId) blk.copy(modelId = cabNativeId, displayName = spec.displayName, shortLabel = spec.shortLabel)
            else blk
        }
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = _uiState.value.activePreset.signalFlow.copy(blocks = updatedBlocks)),
            banner = "Cab: ${spec.displayName}",
        )
        _uiState.update { it.copy(externalIrLabel = null) }
        refreshEqAndCabPlots()
    }

    fun selectMicModel(micNativeId: Int) {
        if (_uiState.value.stageLock) return
        val mic = FactoryCabCatalog.micById(micNativeId)
        val cabBlk = _uiState.value.selectedBlockOrNull?.takeIf { it.nativeBlockCode == RigBlockCode.CAB }
            ?: _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.nativeBlockCode == RigBlockCode.CAB }
        val cabId = cabBlk?.id ?: "cab"
        bridge.enqueueCommand(
            DspCommandCode.SET_CAB_SLOT_PARAM,
            targetId = 0,
            paramId = 2,
            flags = cabBlk?.instanceIndex ?: 0,
            intVal = micNativeId,
        )
        updateParameter(cabId, "cab.mic_model", micNativeId.toFloat(), fromLiveController = false)
        _uiState.update { it.copy(statusBanner = "Cab Mic: ${mic.displayName} (${mic.basedOn})") }
        refreshEqAndCabPlots()
    }

    fun selectFxModel(blockId: String, modelNativeId: Int, displayName: String, shortLabel: String) {
        if (_uiState.value.stageLock) return
        val blk = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == blockId } ?: return
        val updatedBlk = blk.copy(modelId = modelNativeId, displayName = displayName, shortLabel = shortLabel)
        dispatchBlockModelToNative(updatedBlk)
        val updatedBlocks = _uiState.value.activePreset.signalFlow.blocks.map {
            if (it.id == blockId) updatedBlk else it
        }
        commitPresetMutation(
            _uiState.value.activePreset.copy(signalFlow = _uiState.value.activePreset.signalFlow.copy(blocks = updatedBlocks)),
            banner = "Model Loaded: $displayName",
        )
        refreshEqAndCabPlots()
    }

    fun updateParameter(blockId: String, paramKey: String, value: Float, fromLiveController: Boolean = false) {
        if (!StageLockPolicy.allowParameterEdit(_uiState.value.stageLock, fromLiveController)) return

        var needsPlotRefresh = false
        val updatedBlocks = _uiState.value.activePreset.signalFlow.blocks.map { blk ->
            if (blk.id == blockId) {
                val updated = blk.copy(params = blk.params + (paramKey to value))
                dispatchBlockParamsToNative(updated, paramKey, value)
                if (blk.nativeBlockCode == RigBlockCode.EQ || blk.nativeBlockCode == RigBlockCode.CAB) {
                    needsPlotRefresh = true
                }
                updated
            } else blk
        }
        snapshotManager.recordParameterEdit(paramKey, value)

        val nextPreset = _uiState.value.activePreset.copy(
            signalFlow = _uiState.value.activePreset.signalFlow.copy(blocks = updatedBlocks),
            snapshots = snapshotManager.allSnapshots(),
        )
        commitPresetMutation(nextPreset)
        if (needsPlotRefresh) {
            refreshEqAndCabPlots()
        }
    }

    fun updateFocusXY(blockId: String, x: Float, y: Float) {
        if (_uiState.value.stageLock) return
        val cx = x.coerceIn(-1f, 1f)
        val cy = y.coerceIn(-1f, 1f)
        val block = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == blockId } ?: return
        val zone = FocusZoneBank.forBlock(block)
        val interpolated = FocusZoneInterpolator.evaluate(zone, cx, cy)
        val keyPrefix = paramPrefixFor(block.nativeBlockCode)

        val prefixed = interpolated.mapKeys { (k, _) ->
            if (k.contains(".")) k else "$keyPrefix.$k"
        }

        if (block.category == BlockCategory.AMP) {
            bridge.enqueueCommand(DspCommandCode.SET_AMP_FOCUS_XY, flags = block.instanceIndex, value0 = cx, value1 = cy)
        }

        val updatedBlocks = _uiState.value.activePreset.signalFlow.blocks.map { blk ->
            if (blk.id == blockId) {
                val merged = blk.params + prefixed
                val nextBlk = blk.copy(focusX = cx, focusY = cy, params = merged)
                prefixed.forEach { (pk, pv) -> dispatchBlockParamsToNative(nextBlk, pk, pv) }
                nextBlk
            } else blk
        }
        commitPresetMutation(
            _uiState.value.activePreset.copy(
                signalFlow = _uiState.value.activePreset.signalFlow.copy(blocks = updatedBlocks)
            )
        )
    }

    fun resetFocusToCenter(blockId: String) {
        updateFocusXY(blockId, 0f, 0f)
    }

    fun saveUserDefault(blockId: String) {
        val blk = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == blockId } ?: return
        favoritesStore.saveUserDefault("${blk.category.name}:${blk.modelId}", blk.params)
        _uiState.update { it.copy(statusBanner = "Saved User Default for ${blk.displayName}") }
    }

    fun applyUserDefault(blockId: String) {
        if (_uiState.value.stageLock) return
        val blk = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == blockId } ?: return
        val saved = favoritesStore.getUserDefault("${blk.category.name}:${blk.modelId}") ?: return
        saved.forEach { (k, v) -> updateParameter(blockId, k, v) }
        _uiState.update { it.copy(statusBanner = "Applied User Default for ${blk.displayName}") }
    }

    fun applyFactoryDefault(blockId: String) {
        if (_uiState.value.stageLock) return
        val blk = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == blockId } ?: return
        val defaults = DualPathRoutingEngine.defaultParamsFor(blk.nativeBlockCode)
        defaults.forEach { (k, v) -> updateParameter(blockId, k, v) }
        updateFocusXY(blockId, 0f, 0f)
        _uiState.update { it.copy(statusBanner = "Restored Factory Default") }
    }

    fun addBlockToFavorites(blockId: String) {
        val blk = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == blockId } ?: return
        favoritesStore.addFavorite(
            FavoriteBlockEntry(
                id = "fav_${blk.id}_${blk.modelId}_${System.currentTimeMillis()}",
                label = "${blk.displayName} Favorite",
                category = blk.category,
                modelId = blk.modelId,
                params = blk.params,
            )
        )
        _uiState.update {
            it.copy(
                favoritesCount = favoritesStore.favorites.size,
                statusBanner = "Added ${blk.displayName} to Favorites (${favoritesStore.favorites.size})",
            )
        }
    }

    fun allFavorites(): List<FavoriteBlockEntry> = favoritesStore.favorites

    fun registerTapTempo(nowMs: Long = System.currentTimeMillis()) {
        val bpm = bridge.registerTapTempoMs(nowMs).coerceIn(40f, 240f)
        _uiState.update {
            it.copy(
                activePreset = it.activePreset.copy(globalBpm = bpm),
                statusBanner = String.format("TAP Tempo: %.1f BPM", bpm),
            )
        }
    }

    fun setGlobalBpm(bpm: Float) {
        val clamped = bpm.coerceIn(40f, 240f)
        bridge.enqueueCommand(DspCommandCode.SET_GLOBAL_BPM, value0 = clamped)
        _uiState.update { it.copy(activePreset = it.activePreset.copy(globalBpm = clamped)) }
    }

    fun setIoTrim(inputDb: Float, outputDb: Float) {
        val inC = inputDb.coerceIn(-36f, 18f)
        val outC = outputDb.coerceIn(-36f, 18f)
        bridge.enqueueCommand(DspCommandCode.SET_IO_TRIM_PAN, value0 = inC, value1 = outC, value2 = 0f)
        _uiState.update {
            it.copy(activePreset = it.activePreset.copy(inputTrimDb = inC, outputLevelDb = outC))
        }
    }

    fun setInputTrimDb(inputDb: Float) {
        setIoTrim(inputDb, _uiState.value.activePreset.outputLevelDb)
    }

    fun setOutputLevelDb(outputDb: Float) {
        setIoTrim(_uiState.value.activePreset.inputTrimDb, outputDb)
    }

    fun togglePresetClipPlayback() {
        val next = !_uiState.value.isPresetClipPlaying
        bridge.enqueueCommand(DspCommandCode.SET_PRESET_CLIP_COMMAND, paramId = 0, intVal = if (next) 1 else 0)
        _uiState.update {
            it.copy(
                isPresetClipPlaying = next,
                statusBanner = if (next) "Auditioning Dry DI Preset Clip" else "Preset Clip Paused",
            )
        }
    }

    fun selectPresetClipStyle(styleIdx: Int) {
        val s = styleIdx.coerceIn(0, 3)
        bridge.selectFactoryPresetClipRiff(s)
        _uiState.update { it.copy(presetClipStyleIndex = s) }
    }

    fun skipPresetClip(deltaSec: Float) {
        bridge.enqueueCommand(DspCommandCode.SET_PRESET_CLIP_COMMAND, paramId = 2, value0 = deltaSec)
    }

    fun setTunerConfig(config: TunerConfigUi) {
        bridge.enqueueCommand(
            DspCommandCode.SET_TUNER_CONFIG,
            flags = if (config.muteOutput) 1 else 0,
            value0 = config.refPitchHz,
            intVal = if (config.enabled) 1 else 0,
        )
        _uiState.update { it.copy(tunerConfig = config) }
    }

    fun setLivePlayMode(mode: LivePlayMode) {
        val pads = footswitchMatrix.padsForMode(mode, _uiState.value.activePreset.snapshots)
        _uiState.update { it.copy(livePlayMode = mode, livePads = pads) }
    }

    fun triggerFootswitchPad(pad: FootswitchPad) {
        when (val act = pad.action) {
            is FootswitchAction.RecallSnapshot -> selectSnapshot(act.snapshotIndex)
            is FootswitchAction.ToggleBlocks -> toggleBlocksLive(act.blockIds)
            is FootswitchAction.SelectPreset -> {
                val list = repository.allPresets()
                list.getOrNull(act.presetIndex)?.let { selectPreset(it.id) }
            }
            FootswitchAction.TriggerTapTempo -> registerTapTempo()
            FootswitchAction.ToggleTuner -> setTunerConfig(_uiState.value.tunerConfig.copy(enabled = !_uiState.value.tunerConfig.enabled))
            is FootswitchAction.TriggerLooper -> {
                bridge.enqueueCommand(DspCommandCode.SET_LOOPER_COMMAND, intVal = act.actionCode)
                _uiState.update { it.copy(statusBanner = "Looper: ${pad.label}") }
            }
        }
    }

    fun setActiveExpSlot(slot: Int) {
        _uiState.update { it.copy(activeExpSlot = if (slot == 2) 2 else 1) }
    }

    fun onExpressionPedalMoved(pedalIndex: Int, normalizedPos: Float, nowMs: Long = System.currentTimeMillis()) {
        val pos = normalizedPos.coerceIn(0f, 1f)
        val results = controllerEngine.evaluatePedal(pedalIndex, pos, nowMs)
        results.forEach { res ->
            res.blockEnabledChange?.let { shouldEnable ->
                val cur = _uiState.value.activePreset.signalFlow.blocks.firstOrNull { it.id == res.assignment.targetBlockId }
                if (cur != null && cur.enabled != shouldEnable) {
                    toggleBlocksLive(listOf(res.assignment.targetBlockId))
                }
            }
            updateParameter(
                blockId = res.assignment.targetBlockId,
                paramKey = res.assignment.targetParamKey,
                value = res.mappedValue,
                fromLiveController = true,
            )
        }
        _uiState.update {
            if (pedalIndex == 1) it.copy(exp1Value = pos) else it.copy(exp2Value = pos)
        }
    }

    fun handleMidiAction(action: StadiumMidiAction) {
        when (action) {
            is StadiumMidiAction.SelectSnapshot -> selectSnapshot(action.index)
            is StadiumMidiAction.ToggleStomp -> {
                val pads = footswitchMatrix.padsForMode(LivePlayMode.STOMP_A, _uiState.value.activePreset.snapshots)
                pads.getOrNull(action.stompIndex)?.let { triggerFootswitchPad(it) }
            }
            is StadiumMidiAction.ExpressionPedal -> onExpressionPedalMoved(action.pedalIndex, action.normalizedValue)
            StadiumMidiAction.TapTempo -> registerTapTempo()
            is StadiumMidiAction.ToggleTuner -> setTunerConfig(_uiState.value.tunerConfig.copy(enabled = action.engage))
            is StadiumMidiAction.SelectPreset -> {
                repository.allPresets().getOrNull(action.programNumber)?.let { selectPreset(it.id) }
            }
            is StadiumMidiAction.LearnedCcParameter -> {
                val blockPrefix = action.targetKey.substringBefore('.')
                updateParameter(blockPrefix, action.targetKey, action.normalizedValue, fromLiveController = true)
            }
        }
    }

    fun armMidiLearn(targetKey: String) {
        midiParser.armMidiLearn(targetKey)
        _uiState.update { it.copy(midiLearnTargetKey = targetKey, statusBanner = "MIDI Learn Armed: Move pedal/switch for $targetKey") }
    }

    fun cancelMidiLearn() {
        midiParser.cancelMidiLearn()
        _uiState.update { it.copy(midiLearnTargetKey = null) }
    }

    fun toggleShowcasePlayback() {
        if (showcasePlayer.isPlaying) showcasePlayer.pause() else showcasePlayer.start()
        _uiState.update { it.copy(showcasePlaying = showcasePlayer.isPlaying) }
    }

    fun toggleGigStopwatch() {
        if (gigStopwatch.isRunning) gigStopwatch.pause() else gigStopwatch.start()
        _uiState.update {
            it.copy(
                gigStopwatchRunning = gigStopwatch.isRunning,
                gigStopwatchDisplay = gigStopwatch.formatElapsed(),
            )
        }
    }

    fun resetGigStopwatch() {
        gigStopwatch.reset()
        _uiState.update { it.copy(gigStopwatchRunning = false, gigStopwatchDisplay = "00:00") }
    }

    fun importExternalFile(file: ImportedExternalFile) {
        when (file.kind) {
            ExternalRigAssetKind.WAV_IR -> {
                val res = bridge.loadExternalIr(0, file.bytes)
                if (res == "OK") {
                    _uiState.update {
                        it.copy(
                            externalIrLabel = file.fileName,
                            statusBanner = "Loaded 2048-Tap Cab IR: ${file.fileName}",
                        )
                    }
                    refreshEqAndCabPlots()
                } else {
                    _uiState.update { it.copy(statusBanner = "IR Import Error: $res") }
                }
            }
            ExternalRigAssetKind.NAM_CLONE -> {
                val res = bridge.loadExternalNam(file.bytes)
                if (res == "OK") {
                    val meta = bridge.getCloneMetadata()
                    commitPresetMutation(
                        _uiState.value.activePreset.copy(
                            signalFlow = _uiState.value.activePreset.signalFlow.copy(routingMode = 1)
                        ),
                        banner = "Loaded NAM Clone: ${file.fileName}",
                    )
                    _uiState.update { it.copy(cloneMetadata = meta) }
                } else {
                    _uiState.update { it.copy(statusBanner = "NAM Import Error: $res") }
                }
            }
            ExternalRigAssetKind.AIDAX_CLONE -> {
                val res = bridge.loadExternalAidax(file.bytes)
                if (res == "OK") {
                    val meta = bridge.getCloneMetadata()
                    commitPresetMutation(
                        _uiState.value.activePreset.copy(
                            signalFlow = _uiState.value.activePreset.signalFlow.copy(routingMode = 1)
                        ),
                        banner = "Loaded AIDA-X Clone: ${file.fileName}",
                    )
                    _uiState.update { it.copy(cloneMetadata = meta) }
                } else {
                    _uiState.update { it.copy(statusBanner = "AIDA-X Import Error: $res") }
                }
            }
            ExternalRigAssetKind.UNKNOWN -> {
                _uiState.update { it.copy(statusBanner = "Unsupported file format: ${file.fileName}") }
            }
        }
    }

    fun refreshEqAndCabPlots() {
        bridge.computeEqCurveDb(eqScratch)
        bridge.computeCabSpectrumDb(0, cabSpectrumScratch)
        _uiState.update {
            it.copy(
                eqCurveDb = eqScratch.toList(),
                cabSpectrumDb = cabSpectrumScratch.toList(),
            )
        }
    }

    private fun commitPresetMutation(next: StadiumPreset, banner: String? = null) {
        history.pushState(next)
        _uiState.update { st ->
            st.copy(
                activePreset = next,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                livePads = footswitchMatrix.padsForMode(st.livePlayMode, next.snapshots),
                statusBanner = banner ?: st.statusBanner,
            )
        }
    }

    private fun paramPrefixFor(nativeBlockCode: Int): String = when (nativeBlockCode) {
        RigBlockCode.GATE -> "gate"
        RigBlockCode.PITCH -> "pitch"
        RigBlockCode.COMP -> "comp"
        RigBlockCode.WAH -> "wah"
        RigBlockCode.DRIVE -> "drive"
        RigBlockCode.MOD_PRE, RigBlockCode.MOD_POST -> "mod"
        RigBlockCode.AMP -> "amp"
        RigBlockCode.CAB -> "cab"
        RigBlockCode.EQ -> "eq"
        RigBlockCode.VOL_PAN -> "vol"
        RigBlockCode.DELAY -> "delay"
        RigBlockCode.REVERB -> "reverb"
        else -> "fx"
    }

    private fun syncSingleBlockToNative(blk: RigBlockState) {
        dispatchBlockModelToNative(blk)
        dispatchBlockBypassToNative(blk, blk.enabled)
        bridge.enqueueCommand(
            DspCommandCode.SET_BLOCK_STEREO,
            targetId = blk.nativeBlockCode,
            flags = blk.instanceIndex,
            intVal = if (blk.isStereo) 1 else 0,
        )
        blk.params.forEach { (k, v) -> dispatchBlockParamsToNative(blk, k, v) }
    }

    private fun syncDualPathRoutingToNative(flow: SignalFlowState) {
        val b1a = DualPathRoutingEngine.blocksOnBranch(flow, "1A")
        val b1b = DualPathRoutingEngine.blocksOnBranch(flow, "1B")
        val b2a = DualPathRoutingEngine.blocksOnBranch(flow, "2A")
        val b2b = DualPathRoutingEngine.blocksOnBranch(flow, "2B")

        bridge.setParallelSplitMerge(
            flow.splitMode.nativeCode, flow.crossoverHz, flow.routeAb,
            flow.mergeLevelADb, flow.mergeLevelBDb, flow.mergePanA, flow.mergePanB, flow.invertPhaseB,
        )
        bridge.setSecondaryParallelSplitMerge(
            flow.splitMode2.nativeCode, flow.crossoverHz, flow.routeAb,
            flow.mergeLevelADb, flow.mergeLevelBDb, flow.mergePanA, flow.mergePanB, flow.invertPhaseB,
        )
        bridge.setDualPathRouting(
            p1aTypes = b1a.map { it.nativeBlockCode }.toIntArray(),
            p1aInst = b1a.map { it.instanceIndex }.toIntArray(),
            p1bTypes = b1b.map { it.nativeBlockCode }.toIntArray(),
            p1bInst = b1b.map { it.instanceIndex }.toIntArray(),
            p2aTypes = b2a.map { it.nativeBlockCode }.toIntArray(),
            p2aInst = b2a.map { it.instanceIndex }.toIntArray(),
            p2bTypes = b2b.map { it.nativeBlockCode }.toIntArray(),
            p2bInst = b2b.map { it.instanceIndex }.toIntArray(),
            split1After = flow.split1AfterIndex,
            split2After = flow.split2AfterIndex,
            merge2At = flow.merge2AtIndex,
            merge1bInto1a = flow.merge1bInto1a,
            path1aFeeds2a = flow.path1aFeeds2a,
        )
    }

    private fun syncFullPresetToNative(preset: StadiumPreset) {
        val flow = preset.signalFlow
        bridge.enqueueCommand(DspCommandCode.SET_RIG_ROUTING_MODE, intVal = flow.routingMode)
        bridge.enqueueCommand(DspCommandCode.SET_GLOBAL_BPM, value0 = preset.globalBpm)
        bridge.enqueueCommand(DspCommandCode.SET_IO_TRIM_PAN, value0 = preset.inputTrimDb, value1 = preset.outputLevelDb, value2 = preset.outputPan)
        flow.blocks.forEach { blk -> syncSingleBlockToNative(blk) }
        syncDualPathRoutingToNative(flow)
        preset.snapshots.forEachIndexed { idx, s ->
            var mask = 0
            if (s.blockEnabled["gate"] == true) mask = mask or (1 shl 0)
            if (s.blockEnabled["pitch"] == true) mask = mask or (1 shl 1)
            if (s.blockEnabled["comp"] == true) mask = mask or (1 shl 2)
            if (s.blockEnabled["wah"] == true) mask = mask or (1 shl 3)
            if (s.blockEnabled["drive"] == true) mask = mask or (1 shl 4)
            if (s.blockEnabled["mod"] == true) mask = mask or (1 shl 5)
            if (s.blockEnabled["eq"] == true) mask = mask or (1 shl 6)
            if (s.blockEnabled["vol_pan"] == true) mask = mask or (1 shl 7)
            if (s.blockEnabled["delay"] != false) mask = mask or (1 shl 8)
            if (s.blockEnabled["reverb"] != false) mask = mask or (1 shl 9)
            val p11 = floatArrayOf(
                s.paramOverrides["amp.drive"] ?: 0.50f,
                s.paramOverrides["amp.master"] ?: 0.55f,
                s.paramOverrides["amp.ch_vol"] ?: 0.70f,
                s.paramOverrides["amp.bass"] ?: 0.50f,
                s.paramOverrides["amp.mid"] ?: 0.50f,
                s.paramOverrides["amp.treble"] ?: 0.50f,
                s.paramOverrides["amp.presence"] ?: 0.50f,
                s.paramOverrides["drive.drive"] ?: 0.45f,
                s.paramOverrides["delay.mix"] ?: 0.30f,
                s.paramOverrides["reverb.mix"] ?: 0.28f,
                preset.outputLevelDb,
            )
            bridge.storeSnapshot(idx, mask, p11)
        }
    }

    private fun dispatchBlockModelToNative(blk: RigBlockState) {
        val inst = blk.instanceIndex and 1
        when (blk.nativeBlockCode) {
            RigBlockCode.AMP -> bridge.enqueueCommand(DspCommandCode.SET_AMP_MODEL, flags = inst, intVal = blk.modelId)
            RigBlockCode.CAB -> bridge.enqueueCommand(DspCommandCode.SET_CAB_SLOT_PARAM, targetId = 0, paramId = 1, flags = inst, intVal = blk.modelId)
            RigBlockCode.DRIVE -> bridge.enqueueCommand(DspCommandCode.SET_DRIVE_FUZZ, paramId = 1, flags = inst, intVal = blk.modelId)
            RigBlockCode.COMP -> bridge.enqueueCommand(DspCommandCode.SET_DYNAMICS, targetId = 1, paramId = 1, flags = inst, intVal = blk.modelId)
            RigBlockCode.WAH -> bridge.enqueueCommand(DspCommandCode.SET_WAH_FILTER, paramId = 1, flags = inst, intVal = blk.modelId)
            RigBlockCode.PITCH -> bridge.enqueueCommand(DspCommandCode.SET_PITCH_SYNTH, paramId = 1, flags = 0, intVal = blk.modelId)
            RigBlockCode.EQ -> bridge.enqueueCommand(DspCommandCode.SET_EQ_PARAM, paramId = 1, flags = inst, intVal = blk.modelId)
            RigBlockCode.MOD_PRE -> bridge.enqueueCommand(DspCommandCode.SET_MODULATION, targetId = 0, paramId = 1, flags = 0, intVal = blk.modelId)
            RigBlockCode.MOD_POST -> bridge.enqueueCommand(DspCommandCode.SET_MODULATION, targetId = 1, paramId = 1, flags = 1, intVal = blk.modelId)
            RigBlockCode.DELAY -> bridge.enqueueCommand(DspCommandCode.SET_DELAY_REVERB, targetId = 0, paramId = 1, flags = inst, intVal = blk.modelId)
            RigBlockCode.REVERB -> bridge.enqueueCommand(DspCommandCode.SET_DELAY_REVERB, targetId = 1, paramId = 1, flags = inst, intVal = blk.modelId)
        }
    }

    private fun dispatchBlockBypassToNative(blk: RigBlockState, enabled: Boolean) {
        val en = if (enabled) 1 else 0
        val inst = blk.instanceIndex and 1
        when (blk.nativeBlockCode) {
            RigBlockCode.GATE -> bridge.enqueueCommand(DspCommandCode.SET_DYNAMICS, targetId = 0, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.COMP -> bridge.enqueueCommand(DspCommandCode.SET_DYNAMICS, targetId = 1, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.PITCH -> bridge.enqueueCommand(DspCommandCode.SET_PITCH_SYNTH, paramId = 0, flags = 0, intVal = en)
            RigBlockCode.WAH -> bridge.enqueueCommand(DspCommandCode.SET_WAH_FILTER, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.DRIVE -> bridge.enqueueCommand(DspCommandCode.SET_DRIVE_FUZZ, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.CAB -> bridge.enqueueCommand(DspCommandCode.SET_CAB_SLOT_PARAM, targetId = 0, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.EQ -> bridge.enqueueCommand(DspCommandCode.SET_EQ_PARAM, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.VOL_PAN -> bridge.enqueueCommand(DspCommandCode.SET_VOLUME_PAN, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.MOD_PRE -> bridge.enqueueCommand(DspCommandCode.SET_MODULATION, targetId = 0, paramId = 0, flags = 0, intVal = en)
            RigBlockCode.MOD_POST -> bridge.enqueueCommand(DspCommandCode.SET_MODULATION, targetId = 1, paramId = 0, flags = 1, intVal = en)
            RigBlockCode.DELAY -> bridge.enqueueCommand(DspCommandCode.SET_DELAY_REVERB, targetId = 0, paramId = 0, flags = inst, intVal = en)
            RigBlockCode.REVERB -> bridge.enqueueCommand(DspCommandCode.SET_DELAY_REVERB, targetId = 1, paramId = 0, flags = inst, intVal = en)
        }
    }

    private fun dispatchBlockParamsToNative(blk: RigBlockState, key: String, value: Float) {
        val p = blk.params
        val inst = blk.instanceIndex and 1
        when (blk.nativeBlockCode) {
            RigBlockCode.AMP -> {
                val pid = when (key) {
                    "amp.drive" -> 0; "amp.bass" -> 1; "amp.mid" -> 2; "amp.treble" -> 3
                    "amp.presence" -> 4; "amp.master" -> 5; "amp.ch_vol" -> 6; "amp.sag" -> 7
                    "amp.ripple" -> 8; "amp.bias" -> 9; "amp.bias_x" -> 10; "amp.hype" -> 11
                    "amp.deep" -> 13; "amp.cut" -> 14; "amp.geq_v_scoop" -> 15
                    else -> -1
                }
                if (pid >= 0) bridge.enqueueCommand(DspCommandCode.SET_AMP_PARAM, paramId = pid, flags = inst, value0 = value)
            }
            RigBlockCode.CAB -> {
                val pid = when (key) {
                    "cab.position" -> 3; "cab.distance_inch" -> 4; "cab.angle_deg" -> 5
                    "cab.low_cut_hz" -> 6; "cab.high_cut_hz" -> 7; "cab.level_db" -> 8; "cab.pan" -> 9
                    else -> -1
                }
                if (pid >= 0) bridge.enqueueCommand(DspCommandCode.SET_CAB_SLOT_PARAM, targetId = 0, paramId = pid, flags = inst, value0 = value)
            }
            RigBlockCode.DRIVE -> bridge.enqueueCommand(
                DspCommandCode.SET_DRIVE_FUZZ, paramId = 2, flags = inst,
                value0 = p["drive.drive"] ?: 0.45f, value1 = p["drive.tone"] ?: 0.55f, value2 = p["drive.level"] ?: 0.65f,
            )
            RigBlockCode.GATE -> bridge.enqueueCommand(
                DspCommandCode.SET_DYNAMICS, targetId = 0, paramId = 1, flags = inst,
                value0 = p["gate.threshold_db"] ?: -52f, value1 = p["gate.decay_ms"] ?: 45f,
            )
            RigBlockCode.COMP -> bridge.enqueueCommand(
                DspCommandCode.SET_DYNAMICS, targetId = 1, paramId = 2, flags = inst,
                value0 = p["comp.peak_reduction"] ?: 0.45f, value1 = p["comp.gain_db"] ?: 4f, value2 = p["comp.mix"] ?: 0.85f,
            )
            RigBlockCode.WAH -> bridge.enqueueCommand(
                DspCommandCode.SET_WAH_FILTER, paramId = 2, flags = inst,
                value0 = p["wah.position"] ?: 0.5f, value1 = p["wah.q_resonance"] ?: 0.55f, value2 = p["wah.mix"] ?: 1f,
            )
            RigBlockCode.MOD_PRE, RigBlockCode.MOD_POST -> bridge.enqueueCommand(
                DspCommandCode.SET_MODULATION,
                targetId = if (blk.nativeBlockCode == RigBlockCode.MOD_PRE) 0 else 1,
                paramId = 2,
                flags = if (blk.nativeBlockCode == RigBlockCode.MOD_PRE) 0 else 1,
                value0 = p["mod.rate_hz"] ?: 1.2f, value1 = p["mod.depth"] ?: 0.55f, value2 = p["mod.mix"] ?: 0.45f,
            )
            RigBlockCode.PITCH -> bridge.enqueueCommand(
                DspCommandCode.SET_PITCH_SYNTH, paramId = 2, flags = 0,
                value0 = p["pitch.semitones"] ?: 0f, value1 = p["pitch.pedal_pos"] ?: 0f, value2 = p["pitch.mix"] ?: 0.5f,
            )
            RigBlockCode.DELAY -> bridge.enqueueCommand(
                DspCommandCode.SET_DELAY_REVERB, targetId = 0, paramId = 2, flags = inst,
                value0 = p["delay.time_ms"] ?: 380f, value1 = p["delay.feedback"] ?: 0.42f, value2 = p["delay.mix"] ?: 0.30f,
            )
            RigBlockCode.REVERB -> {
                bridge.enqueueCommand(
                    DspCommandCode.SET_DELAY_REVERB, targetId = 1, paramId = 2, flags = inst,
                    value0 = p["reverb.decay"] ?: 0.56f, value1 = p["reverb.damping_hz"] ?: 4600f, value2 = p["reverb.mix"] ?: 0.28f,
                )
                bridge.enqueueCommand(
                    DspCommandCode.SET_DELAY_REVERB, targetId = 1, paramId = 3, flags = inst,
                    value0 = p["reverb.predelay_ms"] ?: 22f, value1 = p["reverb.shimmer_amount"] ?: 0.25f, value2 = 0.25f,
                )
            }
            RigBlockCode.VOL_PAN -> bridge.enqueueCommand(
                DspCommandCode.SET_VOLUME_PAN, paramId = 1, flags = inst,
                value0 = p["vol.position"] ?: 1f, value1 = p["vol.pan"] ?: 0f, value2 = p["vol.stereo_width"] ?: 1f, intVal = 1,
            )
            RigBlockCode.EQ -> {
                bridge.enqueueCommand(DspCommandCode.SET_EQ_PARAM, paramId = 2, flags = inst, value0 = p["eq.low_freq"] ?: 120f, value1 = p["eq.low_gain_db"] ?: 0f, value2 = p["eq.low_q"] ?: 0.8f)
                bridge.enqueueCommand(DspCommandCode.SET_EQ_PARAM, paramId = 3, flags = inst, value0 = p["eq.mid1_freq"] ?: 650f, value1 = p["eq.mid1_gain_db"] ?: 0f, value2 = p["eq.mid1_q"] ?: 1.2f)
                bridge.enqueueCommand(DspCommandCode.SET_EQ_PARAM, paramId = 4, flags = inst, value0 = p["eq.mid2_freq"] ?: 2400f, value1 = p["eq.mid2_gain_db"] ?: 0f, value2 = p["eq.mid2_q"] ?: 1.2f)
                bridge.enqueueCommand(DspCommandCode.SET_EQ_PARAM, paramId = 5, flags = inst, value0 = p["eq.high_freq"] ?: 6500f, value1 = p["eq.high_gain_db"] ?: 0f, value2 = p["eq.high_q"] ?: 0.8f)
                bridge.enqueueCommand(DspCommandCode.SET_EQ_PARAM, paramId = 7, flags = inst, value0 = p["eq.tilt_db"] ?: 0f, value1 = 900f)
            }
        }
    }
}
