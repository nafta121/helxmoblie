package com.cleanroom.stadiumdsp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import com.cleanroom.stadiumdsp.ui.theme.StadiumIcons
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.audio.AudioDspSettings
import com.cleanroom.stadiumdsp.preset.SplitRoutingMode
import com.cleanroom.stadiumdsp.ui.components.ModelBrowserAndInspector
import com.cleanroom.stadiumdsp.ui.components.ModelBrowserPane
import com.cleanroom.stadiumdsp.ui.components.PolymorphicFocusView
import com.cleanroom.stadiumdsp.ui.components.PresetBrowserScreen
import com.cleanroom.stadiumdsp.ui.components.SignalFlowCanvas
import com.cleanroom.stadiumdsp.ui.components.StadiumTopInstanceBar
import com.cleanroom.stadiumdsp.ui.live.LivePlayScreen
import com.cleanroom.stadiumdsp.ui.live.StageTunerOverlay
import com.cleanroom.stadiumdsp.ui.state.StadiumRigViewModel
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.state.WorkspaceDestination
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import kotlinx.coroutines.flow.StateFlow

/**
 * 5-Posture Adaptive Orchestrator for Hx Mobile (Stadium DSP):
 * 1. Tablet Landscape (`EXPANDED` width): 1:1 3-panel Studio + 2x4 Live + EXP Ribbon
 * 2. Tablet Portrait (`MEDIUM` width, Mic Stand): 4-layer vertical stack (Header -> Signal Flow -> Hero Focus -> Tabs)
 * 3. Phone Landscape (`COMPACT` height): Single-row header + collapsible signal ribbon + 2-pane Focus/Sliders
 * 4. Phone Portrait (`COMPACT` width): Bottom Nav + thumb-zone single ribbon + 4x2 Live Grid
 * 5. Foldable Tabletop (`windowPosture.isTabletop`): Local hinge coordinates via `coordinates.windowToLocal`
 *    separating Top Upright Visual Display from Bottom Flat Control Deck!
 */
@Composable
fun StadiumRigScreen(
    viewModel: StadiumRigViewModel,
    onImportExternalAsset: () -> Unit,
    onApplyAudioSettings: (AudioDspSettings) -> Unit,
    onStartAudio: () -> Unit,
    onDestinationChanged: (WorkspaceDestination) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StadiumRigAdaptiveContent(
        state = state,
        telemetryFlow = viewModel.telemetry,
        onImportExternalAsset = onImportExternalAsset,
        onApplyAudioSettings = onApplyAudioSettings,
        onStartAudio = onStartAudio,
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
        onSelectBlock = viewModel::selectBlock,
        onToggleBypass = { viewModel.toggleBlockBypass(it, fromLiveController = false) },
        onToggleStereo = viewModel::toggleBlockStereo,
        onMoveBlock = viewModel::moveBlock,
        onCycleSplitMode = {
            val next = SplitRoutingMode.entries[(state.activePreset.signalFlow.splitMode.ordinal + 1) % SplitRoutingMode.entries.size]
            viewModel.setSplitMode(next)
        },
        onSelectAmpModel = viewModel::selectAmpModel,
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
    var isInspectorExpanded by rememberSaveable { mutableStateOf(false) }
    var showPhoneBrowserSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(destination) {
        onDestinationChanged(destination)
    }

    val navLayoutType = when {
        isTabletop -> NavigationSuiteType.None
        isPhonePortrait -> NavigationSuiteType.NavigationBar
        else -> NavigationSuiteType.NavigationRail
    }

    // Edge-to-Edge compliant: NO safeDrawingPadding on NavigationSuiteScaffold parent!
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
        // Apply safeDrawing + IME insets strictly inside inner screen content!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StadiumColors.CanvasBlack)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            if (isTabletop) {
                // Posture 5: Foldable Tabletop — exact local hinge bounds via coordinates.windowToLocal!
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
                    // Top Upright Display (above hingeTop)
                    val topMod = if (localHingeTopPx > 60f) {
                        Modifier.fillMaxWidth().height(with(density) { localHingeTopPx.toDp() })
                    } else {
                        Modifier.fillMaxWidth().weight(0.5f)
                    }
                    Column(modifier = topMod) {
                        StadiumTopInstanceBar(
                            state = state, telemetryFlow = telemetryFlow, compactMode = true,
                            onStepPreset = onStepPreset, onOpenPresets = { destination = WorkspaceDestination.PRESETS },
                            onSelectSnapshot = onSelectSnapshot, onToggleDiscardEdits = onToggleDiscardEdits,
                            onToggleStageLock = onToggleStageLock, onToggleMasterMute = onToggleMasterMute,
                            onTogglePresetClip = onTogglePresetClip, onSelectPresetClipStyle = onSelectPresetClipStyle,
                            onTapTempo = onTapTempo, onUndo = onUndo, onRedo = onRedo,
                            onToggleCompareAB = onToggleCompareAB, onCopyAB = onCopyAB,
                            onApplyAudioSettings = onApplyAudioSettings, onStartAudio = onStartAudio,
                        )
                        SignalFlowCanvas(
                            flow = state.activePreset.signalFlow, selectedBlockId = state.selectedBlockId,
                            stageLocked = state.stageLock, compactSingleRibbon = true, telemetryFlow = telemetryFlow,
                            onSelectBlock = onSelectBlock, onToggleBypass = onToggleBypass,
                            onOpenFocus = { onSelectBlock(it); destination = WorkspaceDestination.FOCUS },
                            onMoveBlock = onMoveBlock, onCycleSplitMode = onCycleSplitMode,
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

                    // Physical Hinge Gap Band
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

                    // Bottom Flat Control Surface (below hingeBottom)
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
                // Postures 1–4: Tablet Landscape, Tablet Portrait, Phone Landscape, Phone Portrait
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
                                // Tablet Portrait Mic-Stand: Top Focus View + Bottom 2x4 Pedalboard
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
                            )

                            if (isPhoneLandscape) {
                                // Posture 3: Phone Landscape 2-Pane Split (Left Focus | Right Sliders)
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
                                        onToggleInspectorExpanded = { isInspectorExpanded = !isInspectorExpanded },
                                        onToggleFocusView = { destination = WorkspaceDestination.EDIT },
                                        onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                        onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                        onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                        onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                        onImportExternalAsset = onImportExternalAsset,
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
                                    isInspectorExpanded = isInspectorExpanded,
                                    onToggleInspectorExpanded = { isInspectorExpanded = !isInspectorExpanded },
                                    onToggleFocusView = { destination = WorkspaceDestination.EDIT },
                                    onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                    onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                    onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                    onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                    onImportExternalAsset = onImportExternalAsset,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        WorkspaceDestination.EDIT -> {
                            SignalFlowCanvas(
                                flow = state.activePreset.signalFlow, selectedBlockId = state.selectedBlockId,
                                stageLocked = state.stageLock, compactSingleRibbon = isPhonePortrait || isPhoneLandscape,
                                telemetryFlow = telemetryFlow, onSelectBlock = onSelectBlock,
                                onToggleBypass = onToggleBypass,
                                onOpenFocus = { onSelectBlock(it); destination = WorkspaceDestination.FOCUS },
                                onMoveBlock = onMoveBlock, onCycleSplitMode = onCycleSplitMode,
                            )

                            if (isTabletPortrait) {
                                // Posture 2: Tablet Portrait Mic-Stand 4-layer stack (includes Hero Focus View!)
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
                                // Phone Portrait toggle between Thumb-Zone Sliders and Model Browser
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StadiumColors.SurfaceRail)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PhonePaneTab("Parameters", !showPhoneBrowserSheet, { showPhoneBrowserSheet = false }, Modifier.weight(1f))
                                    PhonePaneTab("Models / SAF", showPhoneBrowserSheet, { showPhoneBrowserSheet = true }, Modifier.weight(1f))
                                }
                                if (showPhoneBrowserSheet) {
                                    ModelBrowserPane(
                                        state = state,
                                        onSelectAmpModel = { onSelectAmpModel(it); showPhoneBrowserSheet = false },
                                        onSelectCabModel = { onSelectCabModel(it); showPhoneBrowserSheet = false },
                                        onSelectFxModel = { bid, nid, dn, sl -> onSelectFxModel(bid, nid, dn, sl); showPhoneBrowserSheet = false },
                                        onImportExternalAsset = onImportExternalAsset,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                } else {
                                    ModelBrowserAndInspector(
                                        state = state, showBrowserPane = false, isInspectorExpanded = true,
                                        onToggleInspectorExpanded = { isInspectorExpanded = !isInspectorExpanded },
                                        onToggleFocusView = { destination = WorkspaceDestination.FOCUS },
                                        onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                        onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                        onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                        onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                        onImportExternalAsset = onImportExternalAsset,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                // Tablet Landscape / Tablet Portrait / Phone Landscape simultaneous Browser + Inspector
                                ModelBrowserAndInspector(
                                    state = state,
                                    showBrowserPane = true,
                                    isInspectorExpanded = isInspectorExpanded,
                                    onToggleInspectorExpanded = { isInspectorExpanded = !isInspectorExpanded },
                                    onToggleFocusView = { destination = WorkspaceDestination.FOCUS },
                                    onToggleBypass = onToggleBypass, onToggleStereo = onToggleStereo,
                                    onSelectAmpModel = onSelectAmpModel, onSelectCabModel = onSelectCabModel,
                                    onSelectFxModel = onSelectFxModel, onUpdateParam = onUpdateParam,
                                    onToggleSnapshotControl = onToggleSnapshotControl, onArmMidiLearn = onArmMidiLearn,
                                    onImportExternalAsset = onImportExternalAsset,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // Bottom Contextual Status Bar (Helix Stadium Native #2A2A2A style)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF242424))
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

@Composable
private fun PhonePaneTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(if (selected) StadiumColors.SurfaceSelected else StadiumColors.SurfaceCard)
            .border(1.dp, if (selected) StadiumColors.AmpRed else StadiumColors.BorderSubtle)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) Color.White else StadiumColors.TextSecondary)
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
