package com.cleanroom.stadiumdsp.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.RigBlockCode
import com.cleanroom.stadiumdsp.audio.AudioDspSettings
import com.cleanroom.stadiumdsp.audio.BufferSizeMode
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.preset.DualPathRoutingEngine
import com.cleanroom.stadiumdsp.preset.SplitRoutingMode
import com.cleanroom.stadiumdsp.ui.components.ModelBrowserAndInspector
import com.cleanroom.stadiumdsp.ui.components.ModelBrowserPane
import com.cleanroom.stadiumdsp.ui.components.PolymorphicFocusView
import com.cleanroom.stadiumdsp.ui.components.PresetBrowserScreen
import com.cleanroom.stadiumdsp.ui.components.SignalFlowCanvas
import com.cleanroom.stadiumdsp.ui.components.StadiumTopInstanceBar
import com.cleanroom.stadiumdsp.ui.components.StudioDragDropHost
import com.cleanroom.stadiumdsp.ui.components.StudioDragPayload
import com.cleanroom.stadiumdsp.ui.components.StudioDropTarget
import com.cleanroom.stadiumdsp.ui.live.LivePlayScreen
import com.cleanroom.stadiumdsp.ui.live.StageTunerOverlay
import com.cleanroom.stadiumdsp.ui.state.BrowserCategoryTab
import com.cleanroom.stadiumdsp.ui.state.PendingInsertSlot
import com.cleanroom.stadiumdsp.ui.state.StadiumRigViewModel
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.state.WorkspaceDestination
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import com.cleanroom.stadiumdsp.ui.theme.StadiumIcons
import kotlinx.coroutines.flow.StateFlow

/**
 * 5-Posture Adaptive Orchestrator for Hx Mobile (Stadium DSP) with 1:1 Helix Stadium Native Studio UI,
 * auto-closing collapsible Model Browser, and cross-panel Dual-Path Drag & Drop.
 */
@Composable
fun StadiumRigScreen(
    viewModel: StadiumRigViewModel,
    onImportExternalAsset: () -> Unit,
    onApplyAudioSettings: (AudioDspSettings) -> Unit,
    onStartAudio: () -> Unit,
    onLiveBufferChange: (Int, BufferSizeMode, Int) -> Unit = { _, _, _ -> },
    onResetBufferMin: () -> Unit = {},
    onRefreshAudioDevices: () -> Unit = {},
    onDestinationChanged: (WorkspaceDestination) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StadiumRigAdaptiveContent(
        state = state,
        telemetryFlow = viewModel.telemetry,
        onImportExternalAsset = onImportExternalAsset,
        onApplyAudioSettings = onApplyAudioSettings,
        onStartAudio = onStartAudio,
        onLiveBufferChange = onLiveBufferChange,
        onResetBufferMin = onResetBufferMin,
        onRefreshAudioDevices = onRefreshAudioDevices,
        onStepPreset = viewModel::stepPreset,
        onSelectPreset = viewModel::selectPreset,
        onSaveUserPreset = viewModel::saveCurrentAsUserPreset,
        onSelectSnapshot = viewModel::selectSnapshot,
        onToggleDiscardEdits = { viewModel.setSnapshotDiscardEdits(!state.snapshotDiscardEdits) },
        onToggleStageLock = { viewModel.setStageLock(!state.stageLock) },
        onToggleMasterMute = viewModel::toggleMasterMute,
        onTogglePresetClip = viewModel::togglePresetClipPlayback,
        onSelectPresetClipStyle = viewModel::selectPresetClipStyle,
        onTapTempo = { viewModel.registerTapTempo() },
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onToggleCompareAB = viewModel::toggleCompareAB,
        onCopyAB = viewModel::copyActiveCompareSlot,
        onSelectBlock = { viewModel.selectBlock(it, openBrowser = true) },
        onToggleBypass = { viewModel.toggleBlockBypass(it, fromLiveController = false) },
        onToggleStereo = viewModel::toggleBlockStereo,
        onMoveBlock = viewModel::moveBlock,
        onCycleSplitMode = {
            val next = SplitRoutingMode.entries[(state.activePreset.signalFlow.splitMode.ordinal + 1) % SplitRoutingMode.entries.size]
            viewModel.setSplitMode(next)
        },
        onSelectAmpModel = { viewModel.selectAmpModel(it) },
        onSelectCabModel = viewModel::selectCabModel,
        onSelectMicModel = viewModel::selectMicModel,
        onSelectFxModel = viewModel::selectFxModel,
        onUpdateParam = { bid, key, v -> viewModel.updateParameter(bid, key, v) },
        onToggleSnapshotControl = viewModel::toggleSnapshotParamControl,
        onArmMidiLearn = viewModel::armMidiLearn,
        onUpdateFocusXY = viewModel::updateFocusXY,
        onResetCenter = viewModel::resetFocusToCenter,
        onFactoryDefault = viewModel::applyFactoryDefault,
        onUserDefault = viewModel::applyUserDefault,
        onSaveUserDefault = viewModel::saveUserDefault,
        onAddFavorite = viewModel::addBlockToFavorites,
        onToggleAmpCabLink = viewModel::toggleAmpCabLink,
        onToggleCabCabLink = viewModel::toggleCabCabLink,
        onSelectLiveMode = viewModel::setLivePlayMode,
        onTriggerPad = viewModel::triggerFootswitchPad,
        onSelectExpSlot = viewModel::setActiveExpSlot,
        onExpMoved = { slot, v -> viewModel.onExpressionPedalMoved(slot, v) },
        onToggleShowcase = viewModel::toggleShowcasePlayback,
        onToggleStopwatch = viewModel::toggleGigStopwatch,
        onResetStopwatch = viewModel::resetGigStopwatch,
        onUpdateTunerConfig = viewModel::setTunerConfig,
        onInputTrimChange = viewModel::setInputTrimDb,
        onOutputLevelChange = viewModel::setOutputLevelDb,
        onDismissModelBrowser = viewModel::closeModelBrowser,
        onToggleModelBrowser = viewModel::toggleModelBrowser,
        onSelectBrowserTab = viewModel::setBrowserTab,
        onRequestInsertAtSlot = { branch, idx ->
            viewModel.openModelBrowser(insertSlot = PendingInsertSlot(branch, idx))
        },
        onInsertBlockAt = { cat, code, mid, dn, sl, br, idx, pre ->
            viewModel.insertBlockAt(cat, code, mid, dn, sl, br, idx, isPreamp = pre)
        },
        onReplaceBlockAt = { bid, cat, code, mid, dn, sl, pre ->
            viewModel.replaceBlockAt(bid, cat, code, mid, dn, sl, isPreamp = pre)
        },
        onMoveBlockTo = { bid, br, idx -> viewModel.moveBlockTo(bid, br, idx) },
        onRemoveBlock = { bid -> viewModel.removeBlock(bid) },
        onCycleSplitMode2 = viewModel::cycleSplitMode2,
        onToggleMerge1bInto1a = viewModel::toggleMerge1bInto1a,
        onDestinationChanged = onDestinationChanged,
    )
}

@Composable
fun StadiumRigAdaptiveContent(
    state: StadiumUiState,
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    onImportExternalAsset: () -> Unit,
    onApplyAudioSettings: (AudioDspSettings) -> Unit,
    onStartAudio: () -> Unit,
    onLiveBufferChange: (Int, BufferSizeMode, Int) -> Unit = { _, _, _ -> },
    onResetBufferMin: () -> Unit = {},
    onRefreshAudioDevices: () -> Unit = {},
    onStepPreset: (Int) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSaveUserPreset: (String) -> Unit,
    onSelectSnapshot: (Int) -> Unit,
    onToggleDiscardEdits: () -> Unit,
    onToggleStageLock: () -> Unit,
    onToggleMasterMute: () -> Unit,
    onTogglePresetClip: () -> Unit,
    onSelectPresetClipStyle: (Int) -> Unit,
    onTapTempo: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleCompareAB: () -> Unit,
    onCopyAB: () -> Unit,
    onSelectBlock: (String) -> Unit,
    onToggleBypass: (String) -> Unit,
    onToggleStereo: (String) -> Unit,
    onMoveBlock: (Int, Int) -> Unit,
    onCycleSplitMode: () -> Unit,
    onSelectAmpModel: (Int) -> Unit,
    onSelectCabModel: (Int) -> Unit,
    onSelectMicModel: (Int) -> Unit,
    onSelectFxModel: (String, Int, String, String) -> Unit,
    onUpdateParam: (String, String, Float) -> Unit,
    onToggleSnapshotControl: (String) -> Unit,
    onArmMidiLearn: (String) -> Unit,
    onUpdateFocusXY: (String, Float, Float) -> Unit,
    onResetCenter: (String) -> Unit,
    onFactoryDefault: (String) -> Unit,
    onUserDefault: (String) -> Unit,
    onSaveUserDefault: (String) -> Unit,
    onAddFavorite: (String) -> Unit,
    onToggleAmpCabLink: () -> Unit,
    onToggleCabCabLink: () -> Unit,
    onSelectLiveMode: (com.cleanroom.stadiumdsp.midi.LivePlayMode) -> Unit,
    onTriggerPad: (com.cleanroom.stadiumdsp.midi.FootswitchPad) -> Unit,
    onSelectExpSlot: (Int) -> Unit,
    onExpMoved: (Int, Float) -> Unit,
    onToggleShowcase: () -> Unit,
    onToggleStopwatch: () -> Unit,
    onResetStopwatch: () -> Unit,
    onUpdateTunerConfig: (com.cleanroom.stadiumdsp.ui.state.TunerConfigUi) -> Unit,
    onInputTrimChange: (Float) -> Unit = {},
    onOutputLevelChange: (Float) -> Unit = {},
    onDismissModelBrowser: () -> Unit = {},
    onToggleModelBrowser: () -> Unit = {},
    onSelectBrowserTab: (BrowserCategoryTab) -> Unit = {},
    onRequestInsertAtSlot: (String, Int) -> Unit = { _, _ -> },
    onInsertBlockAt: (BlockCategory, Int, Int, String, String, String, Int, Boolean) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onReplaceBlockAt: (String, BlockCategory, Int, Int, String, String, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onMoveBlockTo: (String, String, Int) -> Unit = { _, _, _ -> },
    onRemoveBlock: (String) -> Unit = {},
    onCycleSplitMode2: () -> Unit = {},
    onToggleMerge1bInto1a: () -> Unit = {},
    onDestinationChanged: (WorkspaceDestination) -> Unit = {},
) {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val widthClass = adaptiveInfo.windowSizeClass.windowWidthSizeClass
    val heightClass = adaptiveInfo.windowSizeClass.windowHeightSizeClass
    val posture = adaptiveInfo.windowPosture

    val isTabletop = posture.isTabletop
    val isPhonePortrait = widthClass == WindowWidthSizeClass.COMPACT && heightClass != WindowHeightSizeClass.COMPACT
    val isPhoneLandscape = heightClass == WindowHeightSizeClass.COMPACT
    val isTabletPortrait = widthClass == WindowWidthSizeClass.MEDIUM && heightClass == WindowHeightSizeClass.EXPANDED
    val isTabletLandscape = widthClass == WindowWidthSizeClass.EXPANDED && !isPhoneLandscape

    var destination by rememberSaveable { mutableStateOf(WorkspaceDestination.EDIT) }

    LaunchedEffect(destination) {
        onDestinationChanged(destination)
    }

    val handleDropPayload: (StudioDragPayload, StudioDropTarget) -> Unit = { payload, target ->
        when (payload) {
            is StudioDragPayload.MoveExistingBlock -> {
                when (target) {
                    is StudioDropTarget.InsertAtSlot -> onMoveBlockTo(payload.blockId, target.branch, target.indexInBranch)
                    is StudioDropTarget.ReplaceBlock -> {
                        val destBlk = state.activePreset.signalFlow.blocks.firstOrNull { it.id == target.blockId }
                        if (destBlk != null) {
                            val idxInBranch = DualPathRoutingEngine.blocksOnBranch(state.activePreset.signalFlow, destBlk.pathBranch)
                                .indexOfFirst { it.id == destBlk.id }
                                .coerceAtLeast(0)
                            onMoveBlockTo(payload.blockId, destBlk.pathBranch, idxInBranch)
                        }
                    }
                    StudioDropTarget.RemoveTrash -> onRemoveBlock(payload.blockId)
                }
            }
            is StudioDragPayload.AddNewModel -> {
                when (target) {
                    is StudioDropTarget.InsertAtSlot -> onInsertBlockAt(
                        payload.category,
                        payload.nativeBlockCode,
                        payload.modelId,
                        payload.displayName,
                        payload.shortLabel,
                        target.branch,
                        target.indexInBranch,
                        payload.isPreamp,
                    )
                    is StudioDropTarget.ReplaceBlock -> onReplaceBlockAt(
                        target.blockId,
                        payload.category,
                        payload.nativeBlockCode,
                        payload.modelId,
                        payload.displayName,
                        payload.shortLabel,
                        payload.isPreamp,
                    )
                    StudioDropTarget.RemoveTrash -> Unit
                }
            }
        }
    }

    val handleApplyModelEntry: (BlockCategory, Int, Int, String, String, Boolean) -> Unit = { cat, code, nid, dn, sl, pre ->
        val pending = state.pendingInsertSlot
        val sel = state.selectedBlockOrNull
        when {
            pending != null -> onInsertBlockAt(cat, code, nid, dn, sl, pending.branch, pending.indexInBranch, pre)
            sel == null -> onInsertBlockAt(cat, code, nid, dn, sl, "1A", 0, pre)
            cat == BlockCategory.AMP && sel.nativeBlockCode == RigBlockCode.AMP && sel.instanceIndex == 0 && !pre -> onSelectAmpModel(nid)
            cat == BlockCategory.CAB && sel.nativeBlockCode == RigBlockCode.CAB -> onSelectCabModel(nid)
            else -> onReplaceBlockAt(sel.id, cat, code, nid, dn, sl, pre)
        }
    }

    val navLayoutType = when {
        isTabletop -> NavigationSuiteType.None
        isPhonePortrait -> NavigationSuiteType.NavigationBar
        else -> NavigationSuiteType.NavigationRail
    }

    NavigationSuiteScaffold(
        layoutType = navLayoutType,
        containerColor = StadiumColors.CanvasBlack,
        contentColor = StadiumColors.TextPrimary,
        navigationSuiteItems = {
            WorkspaceDestination.entries.forEach { dest ->
                val icon = when (dest) {
                    WorkspaceDestination.EDIT -> Icons.Rounded.Edit
                    WorkspaceDestination.FOCUS -> StadiumIcons.FocusEq
                    WorkspaceDestination.LIVE_PLAY -> Icons.Rounded.PlayArrow
                    WorkspaceDestination.PRESETS -> StadiumIcons.Presets
                    WorkspaceDestination.TUNER -> StadiumIcons.Tuner
                }
                item(
                    selected = destination == dest,
                    onClick = { destination = dest },
                    icon = { Icon(icon, contentDescription = dest.label) },
                    label = { Text(dest.label, fontSize = 10.sp) },
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StadiumColors.CanvasBlack)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            if (isTabletop) {
                val hingeBounds = posture.hingeList.firstOrNull { it.isFlat || it.isSeparating }?.bounds
                var localHingeTopPx by rememberSaveable { mutableFloatStateOf(0f) }
                var localHingeBottomPx by rememberSaveable { mutableFloatStateOf(0f) }
                val density = LocalDensity.current

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPlaced { coords ->
                            if (hingeBounds != null) {
                                localHingeTopPx = coords.windowToLocal(Offset(0f, hingeBounds.top.toFloat())).y.coerceAtLeast(0f)
                                localHingeBottomPx = coords.windowToLocal(Offset(0f, hingeBounds.bottom.toFloat())).y.coerceAtLeast(localHingeTopPx)
                            }
                        },
                ) {
                    val topMod = if (localHingeTopPx > 60f) {
                        Modifier.fillMaxWidth().height(with(density) { localHingeTopPx.toDp() })
                    } else {
                        Modifier.fillMaxWidth().weight(0.5f)
                    }
                    StudioDragDropHost(modifier = topMod) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            StadiumTopInstanceBar(
                                state = state, telemetryFlow = telemetryFlow, compactMode = true,
                                onStepPreset = onStepPreset, onOpenPresets = { destination = WorkspaceDestination.PRESETS },
                                onSelectSnapshot = onSelectSnapshot, onToggleDiscardEdits = onToggleDiscardEdits,
                                onToggleStageLock = onToggleStageLock, onToggleMasterMute = onToggleMasterMute,
                                onTogglePresetClip = onTogglePresetClip, onSelectPresetClipStyle = onSelectPresetClipStyle,
                                onTapTempo = onTapTempo, onUndo = onUndo, onRedo = onRedo,
                                onToggleCompareAB = onToggleCompareAB, onCopyAB = onCopyAB,
                                onApplyAudioSettings = onApplyAudioSettings, onStartAudio = onStartAudio,
                                onLiveBufferChange = onLiveBufferChange, onResetBufferMin = onResetBufferMin,
                                onRefreshAudioDevices = onRefreshAudioDevices,
                                onInputTrimChange = onInputTrimChange, onOutputLevelChange = onOutputLevelChange,
                                onDismissModelBrowser = onDismissModelBrowser,
                            )
                            SignalFlowCanvas(
                                flow = state.activePreset.signalFlow, selectedBlockId = state.selectedBlockId,
                                stageLocked = state.stageLock, compactSingleRibbon = true, telemetryFlow = telemetryFlow,
                                onSelectBlock = onSelectBlock, onToggleBypass = onToggleBypass,
                                onOpenFocus = { onSelectBlock(it); destination = WorkspaceDestination.FOCUS },
                                onMoveBlock = onMoveBlock, onCycleSplitMode = onCycleSplitMode,
                                inputTrimDb = state.activePreset.inputTrimDb,
                                outputLevelDb = state.activePreset.outputLevelDb,
                                pendingInsertSlot = state.pendingInsertSlot,
                                onDismissModelBrowser = onDismissModelBrowser,
                                onRequestInsertAtSlot = onRequestInsertAtSlot,
                                onDropDragPayload = handleDropPayload,
                                onCycleSplitMode2 = onCycleSplitMode2,
                                onToggleMerge1bInto1a = onToggleMerge1bInto1a,
                            )
                            PolymorphicFocusView(
                                state = state, telemetryFlow = telemetryFlow, compactMode = true,
                                onUpdateFocusXY = onUpdateFocusXY, onResetCenter = onResetCenter,
                                onFactoryDefault = onFactoryDefault, onUserDefault = onUserDefault,
                                onSaveUserDefault = onSaveUserDefault, onAddFavorite = onAddFavorite,
                                onUpdateParam = onUpdateParam, onSelectMic = onSelectMicModel,
                                onToggleAmpCabLink = onToggleAmpCabLink, onToggleCabCabLink = onToggleCabCabLink,
                                onImportExternalAsset = onImportExternalAsset,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    val hingeGapDp = with(density) { (localHingeBottomPx - localHingeTopPx).coerceAtLeast(10f).toDp() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(hingeGapDp)
                            .background(StadiumColors.SurfaceRail),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("TABLETOP HINGE • BOTTOM DECK ACTIVE", style = MaterialTheme.typography.labelSmall, color = StadiumColors.PitchWahViolet)
                    }

                    LivePlayScreen(
                        state = state, isPortraitPhone = false,
                        onSelectMode = onSelectLiveMode, onTriggerPad = onTriggerPad,
                        onQuickFocusJump = { onSelectBlock(it) },
                        onSelectExpSlot = onSelectExpSlot, onExpMoved = onExpMoved,
                        onTapTempo = onTapTempo, onOpenTuner = { destination = WorkspaceDestination.TUNER },
                        onToggleShowcase = onToggleShowcase, onToggleStopwatch = onToggleStopwatch,
                        onResetStopwatch = onResetStopwatch,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                StudioDragDropHost(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        StadiumTopInstanceBar(
                            state = state,
                            telemetryFlow = telemetryFlow,
                            compactMode = isPhonePortrait || isPhoneLandscape,
                            onStepPreset = onStepPreset,
                            onOpenPresets = { destination = WorkspaceDestination.PRESETS },
                            onSelectSnapshot = onSelectSnapshot,
                            onToggleDiscardEdits = onToggleDiscardEdits,
                            onToggleStageLock = onToggleStageLock,
                            onToggleMasterMute = onToggleMasterMute,
                            onTogglePresetClip = onTogglePresetClip,
                            onSelectPresetClipStyle = onSelectPresetClipStyle,
                            onTapTempo = onTapTempo,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onToggleCompareAB = onToggleCompareAB,
                            onCopyAB = onCopyAB,
                            onApplyAudioSettings = onApplyAudioSettings,
                            onStartAudio = onStartAudio,
                            onLiveBufferChange = onLiveBufferChange,
                            onResetBufferMin = onResetBufferMin,
                            onRefreshAudioDevices = onRefreshAudioDevices,
                            onInputTrimChange = onInputTrimChange,
                            onOutputLevelChange = onOutputLevelChange,
                            onDismissModelBrowser = onDismissModelBrowser,
                        )

                        when (destination) {
                            WorkspaceDestination.TUNER -> {
                                StageTunerOverlay(
                                    telemetryFlow = telemetryFlow,
                                    config = state.tunerConfig,
                                    onUpdateConfig = onUpdateTunerConfig,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            WorkspaceDestination.PRESETS -> {
                                PresetBrowserScreen(
                                    state = state,
                                    onSelectPreset = {
                                        onSelectPreset(it)
                                        destination = WorkspaceDestination.EDIT
                                    },
                                    onSaveUserPreset = onSaveUserPreset,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            WorkspaceDestination.LIVE_PLAY -> {
                                if (isTabletPortrait) {
                                    PolymorphicFocusView(
                                        state = state, telemetryFlow = telemetryFlow, compactMode = false,
                                        onUpdateFocusXY = onUpdateFocusXY, onResetCenter = onResetCenter,
                                        onFactoryDefault = onFactoryDefault, onUserDefault = onUserDefault,
                                        onSaveUserDefault = onSaveUserDefault, onAddFavorite = onAddFavorite,
                                        onUpdateParam = onUpdateParam, onSelectMic = onSelectMicModel,
                                        onToggleAmpCabLink = onToggleAmpCabLink, onToggleCabCabLink = onToggleCabCabLink,
                                        onImportExternalAsset = onImportExternalAsset,
                                    )
                                } else if (!isPhoneLandscape) {
                                    SignalFlowCanvas(
                                        flow = state.activePreset.signalFlow, selectedBlockId = state.selectedBlockId,
                                        stageLocked = state.stageLock, compactSingleRibbon = isPhonePortrait,
                                        telemetryFlow = telemetryFlow, onSelectBlock = onSelectBlock,
                                        onToggleBypass = onToggleBypass,
                                        onOpenFocus = { onSelectBlock(it); destination = WorkspaceDestination.FOCUS },
                                        onMoveBlock = onMoveBlock, onCycleSplitMode = onCycleSplitMode,
                                        inputTrimDb = state.activePreset.inputTrimDb,
                                        outputLevelDb = state.activePreset.outputLevelDb,
                                        pendingInsertSlot = state.pendingInsertSlot,
                                        onDismissModelBrowser = onDismissModelBrowser,
                                        onRequestInsertAtSlot = onRequestInsertAtSlot,
                                        onDropDragPayload = handleDropPayload,
                                        onCycleSplitMode2 = onCycleSplitMode2,
                                        onToggleMerge1bInto1a = onToggleMerge1bInto1a,
                                    )
                                }
                                LivePlayScreen(
                                    state = state,
                                    isPortraitPhone = isPhonePortrait,
                                    onSelectMode = onSelectLiveMode,
                                    onTriggerPad = onTriggerPad,
                                    onQuickFocusJump = {
                                        onSelectBlock(it)
                                        destination = WorkspaceDestination.FOCUS
                                    },
                                    onSelectExpSlot = onSelectExpSlot,
                                    onExpMoved = onExpMoved,
                                    onTapTempo = onTapTempo,
                                    onOpenTuner = { destination = WorkspaceDestination.TUNER },
                                    onToggleShowcase = onToggleShowcase,
                                    onToggleStopwatch = onToggleStopwatch,
                                    onResetStopwatch = onResetStopwatch,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            WorkspaceDestination.FOCUS -> {
                                SignalFlowCanvas(
                                    flow = state.activePreset.signalFlow, selectedBlockId = state.selectedBlockId,
                                    stageLocked = state.stageLock, compactSingleRibbon = isPhonePortrait || isPhoneLandscape,
                                    telemetryFlow = telemetryFlow, onSelectBlock = onSelectBlock,
                                    onToggleBypass = onToggleBypass, onOpenFocus = onSelectBlock,
                                    onMoveBlock = onMoveBlock, onCycleSplitMode = onCycleSplitMode,
                                    inputTrimDb = state.activePreset.inputTrimDb,
                                    outputLevelDb = state.activePreset.outputLevelDb,
                                    pendingInsertSlot = state.pendingInsertSlot,
                                    onDismissModelBrowser = onDismissModelBrowser,
                                    onRequestInsertAtSlot = onRequestInsertAtSlot,
                                    onDropDragPayload = handleDropPayload,
                                    onCycleSplitMode2 = onCycleSplitMode2,
                                    onToggleMerge1bInto1a = onToggleMerge1bInto1a,
                                )

                                if (isPhoneLandscape) {
                                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                        PolymorphicFocusView(
                                            state = state, telemetryFlow = telemetryFlow, compactMode = true,
                                            onUpdateFocusXY = onUpdateFocusXY, onResetCenter = onResetCenter,
                                            onFactoryDefault = onFactoryDefault, onUserDefault = onUserDefault,
                                            onSaveUserDefault = onSaveUserDefault, onAddFavorite = onAddFavorite,
                                            onUpdateParam = onUpdateParam, onSelectMic = onSelectMicModel,
                                            onToggleAmpCabLink = onToggleAmpCabLink, onToggleCabCabLink = onToggleCabCabLink,
                                            onImportExternalAsset = onImportExternalAsset,
                                            modifier = Modifier.weight(0.50f).fillMaxHeight(),
                                        )
                                        ModelBrowserAndInspector(
                                            state = state, showBrowserPane = false, isInspectorExpanded = true,
                                            onToggleInspectorExpanded = onToggleModelBrowser,
                                            onToggleFocusView = { destination = WorkspaceDestination.EDIT },
                                            onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                            onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                            onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                            onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                            onImportExternalAsset = onImportExternalAsset,
                                            onSelectBrowserTab = onSelectBrowserTab,
                                            onDismissModelBrowser = onDismissModelBrowser,
                                            onToggleModelBrowser = onToggleModelBrowser,
                                            onRemoveSelectedBlock = { state.selectedBlockOrNull?.let { onRemoveBlock(it.id) } },
                                            onApplyModelEntry = handleApplyModelEntry,
                                            onDropDragPayload = handleDropPayload,
                                            modifier = Modifier.weight(0.50f).fillMaxHeight(),
                                        )
                                    }
                                } else {
                                    PolymorphicFocusView(
                                        state = state, telemetryFlow = telemetryFlow, compactMode = isPhonePortrait,
                                        onUpdateFocusXY = onUpdateFocusXY, onResetCenter = onResetCenter,
                                        onFactoryDefault = onFactoryDefault, onUserDefault = onUserDefault,
                                        onSaveUserDefault = onSaveUserDefault, onAddFavorite = onAddFavorite,
                                        onUpdateParam = onUpdateParam, onSelectMic = onSelectMicModel,
                                        onToggleAmpCabLink = onToggleAmpCabLink, onToggleCabCabLink = onToggleCabCabLink,
                                        onImportExternalAsset = onImportExternalAsset,
                                    )
                                    ModelBrowserAndInspector(
                                        state = state,
                                        showBrowserPane = isTabletLandscape,
                                        isInspectorExpanded = false,
                                        onToggleInspectorExpanded = onToggleModelBrowser,
                                        onToggleFocusView = { destination = WorkspaceDestination.EDIT },
                                        onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                        onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                        onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                        onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                        onImportExternalAsset = onImportExternalAsset,
                                        onSelectBrowserTab = onSelectBrowserTab,
                                        onDismissModelBrowser = onDismissModelBrowser,
                                        onToggleModelBrowser = onToggleModelBrowser,
                                        onRemoveSelectedBlock = { state.selectedBlockOrNull?.let { onRemoveBlock(it.id) } },
                                        onApplyModelEntry = handleApplyModelEntry,
                                        onDropDragPayload = handleDropPayload,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            WorkspaceDestination.EDIT -> {
                                SignalFlowCanvas(
                                    flow = state.activePreset.signalFlow, selectedBlockId = state.selectedBlockId,
                                    stageLocked = state.stageLock, compactSingleRibbon = isPhoneLandscape,
                                    telemetryFlow = telemetryFlow, onSelectBlock = onSelectBlock,
                                    onToggleBypass = onToggleBypass,
                                    onOpenFocus = { onSelectBlock(it); destination = WorkspaceDestination.FOCUS },
                                    onMoveBlock = onMoveBlock, onCycleSplitMode = onCycleSplitMode,
                                    inputTrimDb = state.activePreset.inputTrimDb,
                                    outputLevelDb = state.activePreset.outputLevelDb,
                                    pendingInsertSlot = state.pendingInsertSlot,
                                    onDismissModelBrowser = onDismissModelBrowser,
                                    onRequestInsertAtSlot = onRequestInsertAtSlot,
                                    onDropDragPayload = handleDropPayload,
                                    onCycleSplitMode2 = onCycleSplitMode2,
                                    onToggleMerge1bInto1a = onToggleMerge1bInto1a,
                                )

                                if (isTabletPortrait) {
                                    PolymorphicFocusView(
                                        state = state, telemetryFlow = telemetryFlow, compactMode = false,
                                        onUpdateFocusXY = onUpdateFocusXY, onResetCenter = onResetCenter,
                                        onFactoryDefault = onFactoryDefault, onUserDefault = onUserDefault,
                                        onSaveUserDefault = onSaveUserDefault, onAddFavorite = onAddFavorite,
                                        onUpdateParam = onUpdateParam, onSelectMic = onSelectMicModel,
                                        onToggleAmpCabLink = onToggleAmpCabLink, onToggleCabCabLink = onToggleCabCabLink,
                                        onImportExternalAsset = onImportExternalAsset,
                                    )
                                }

                                if (isPhonePortrait) {
                                    // Phone Portrait: Full-width Inspector + auto-closing bottom Model Browser overlay sheet
                                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                        ModelBrowserAndInspector(
                                            state = state,
                                            showBrowserPane = false,
                                            isInspectorExpanded = true,
                                            onToggleInspectorExpanded = onToggleModelBrowser,
                                            onToggleFocusView = { destination = WorkspaceDestination.FOCUS },
                                            onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                            onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                            onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                            onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                            onImportExternalAsset = onImportExternalAsset,
                                            onSelectBrowserTab = onSelectBrowserTab,
                                            onDismissModelBrowser = onDismissModelBrowser,
                                            onToggleModelBrowser = onToggleModelBrowser,
                                            onRemoveSelectedBlock = { state.selectedBlockOrNull?.let { onRemoveBlock(it.id) } },
                                            onApplyModelEntry = handleApplyModelEntry,
                                            onDropDragPayload = handleDropPayload,
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        // Auto-closing bottom overlay Model Browser on Phone Portrait
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = state.isModelBrowserOpen,
                                            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                                            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                                            modifier = Modifier.fillMaxSize(),
                                        ) {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                // Top dim scrim auto-closes Model Browser when tapped
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.45f))
                                                        .pointerInput(Unit) {
                                                            detectTapGestures(onTap = { onDismissModelBrowser() })
                                                        },
                                                )
                                                Column(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .fillMaxWidth()
                                                        .fillMaxHeight(0.84f)
                                                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                                        .background(Color(0xFF0E0E11))
                                                        .border(1.dp, Color(0xFF2B2B32), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(Color(0xFF141418))
                                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            text = state.pendingInsertSlot?.let { "Add Block to Path ${it.branch}" } ?: "Select Amp / Effect Model",
                                                            color = Color.White,
                                                            fontSize = 11.sp,
                                                        )
                                                        Text(
                                                            text = "Close ✕",
                                                            color = StadiumColors.BadgeCyan,
                                                            fontSize = 11.sp,
                                                            modifier = Modifier
                                                                .clickable(onClick = onDismissModelBrowser)
                                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                                        )
                                                    }
                                                    ModelBrowserPane(
                                                        state = state,
                                                        onSelectBrowserTab = onSelectBrowserTab,
                                                        onRemoveSelectedBlock = {
                                                            state.selectedBlockOrNull?.let { onRemoveBlock(it.id) }
                                                            onDismissModelBrowser()
                                                        },
                                                        onApplyModelEntry = { cat, code, nid, dn, sl, pre ->
                                                            handleApplyModelEntry(cat, code, nid, dn, sl, pre)
                                                            onDismissModelBrowser()
                                                        },
                                                        onImportExternalAsset = onImportExternalAsset,
                                                        onDropDragPayload = handleDropPayload,
                                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Tablet Landscape / Tablet Portrait / Phone Landscape: 1:1 Helix Stadium Native split!
                                    // Clicking empty canvas / non-block area smoothly collapses left browser & expands Inspector to 100%!
                                    ModelBrowserAndInspector(
                                        state = state,
                                        showBrowserPane = true,
                                        isInspectorExpanded = false,
                                        onToggleInspectorExpanded = onToggleModelBrowser,
                                        onToggleFocusView = { destination = WorkspaceDestination.FOCUS },
                                        onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                        onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                        onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                        onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                        onImportExternalAsset = onImportExternalAsset,
                                        onSelectBrowserTab = onSelectBrowserTab,
                                        onDismissModelBrowser = onDismissModelBrowser,
                                        onToggleModelBrowser = onToggleModelBrowser,
                                        onRemoveSelectedBlock = { state.selectedBlockOrNull?.let { onRemoveBlock(it.id) } },
                                        onApplyModelEntry = handleApplyModelEntry,
                                        onDropDragPayload = handleDropPayload,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        // Bottom Contextual Status Bar (Helix Stadium Native subtle #18181A footer)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF18181A))
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { onDismissModelBrowser() })
                                }
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.statusBanner,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = StadiumColors.TextSecondary,
                                maxLines = 1,
                            )
                            StatusBarCpuBadge(telemetryFlow = telemetryFlow)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBarCpuBadge(telemetryFlow: StateFlow<LiveTelemetryUi>) {
    val t by telemetryFlow.collectAsStateWithLifecycle()
    Text(
        text = String.format("DSP %.1f%%", t.rtCpuLoadPct),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = if (t.rtCpuLoadPct < 45f) StadiumColors.DelayEmerald else StadiumColors.DistortionAmber,
    )
}
