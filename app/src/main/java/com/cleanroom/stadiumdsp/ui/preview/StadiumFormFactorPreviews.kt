package com.cleanroom.stadiumdsp.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.ui.StadiumRigAdaptiveContent
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumDspTheme
import kotlinx.coroutines.flow.MutableStateFlow

@Preview(name = "1. Phone Portrait", device = Devices.PHONE, showBackground = true)
@Preview(name = "2. Phone Landscape", device = "spec:width=891dp,height=411dp,dpi=420", showBackground = true)
@Preview(name = "3. Foldable", device = Devices.FOLDABLE, showBackground = true)
@Preview(name = "4. Tablet Portrait", device = "spec:width=800dp,height=1280dp,dpi=240", showBackground = true)
@Preview(name = "5. Tablet Landscape", device = Devices.TABLET, showBackground = true)
annotation class FormFactorPreviews

@FormFactorPreviews
@Composable
fun StadiumRigFormFactorPreview() {
    val previewState = StadiumUiState()
    val previewTelemetry = MutableStateFlow(
        LiveTelemetryUi(inPeakDb = -10.5f, outPeakLDb = -4.2f, outPeakRDb = -4.5f, rtCpuLoadPct = 7.6f)
    )
    StadiumDspTheme {
        StadiumRigAdaptiveContent(
            state = previewState,
            telemetryFlow = previewTelemetry,
            onImportExternalAsset = {},
            onApplyAudioSettings = {},
            onStartAudio = {},
            onStepPreset = {},
            onSelectPreset = {},
            onSaveUserPreset = {},
            onSelectSnapshot = {},
            onToggleDiscardEdits = {},
            onToggleStageLock = {},
            onToggleMasterMute = {},
            onTogglePresetClip = {},
            onSelectPresetClipStyle = {},
            onTapTempo = {},
            onUndo = {},
            onRedo = {},
            onToggleCompareAB = {},
            onCopyAB = {},
            onSelectBlock = {},
            onToggleBypass = {},
            onToggleStereo = {},
            onMoveBlock = { _, _ -> },
            onCycleSplitMode = {},
            onSelectAmpModel = {},
            onSelectCabModel = {},
            onSelectMicModel = {},
            onSelectFxModel = { _, _, _, _ -> },
            onUpdateParam = { _, _, _ -> },
            onToggleSnapshotControl = {},
            onArmMidiLearn = {},
            onUpdateFocusXY = { _, _, _ -> },
            onResetCenter = {},
            onFactoryDefault = {},
            onUserDefault = {},
            onSaveUserDefault = {},
            onAddFavorite = {},
            onToggleAmpCabLink = {},
            onToggleCabCabLink = {},
            onSelectLiveMode = {},
            onTriggerPad = {},
            onSelectExpSlot = {},
            onExpMoved = { _, _ -> },
            onToggleShowcase = {},
            onToggleStopwatch = {},
            onResetStopwatch = {},
            onUpdateTunerConfig = {},
        )
    }
}

@Preview(name = "6. Tablet Landscape (Browser Auto-Closed)", device = Devices.TABLET, showBackground = true)
@Composable
fun StadiumRigBrowserAutoClosedPreview() {
    val previewState = StadiumUiState(isModelBrowserOpen = false)
    val previewTelemetry = MutableStateFlow(
        LiveTelemetryUi(inPeakDb = -12.0f, outPeakLDb = -5.5f, outPeakRDb = -5.8f, rtCpuLoadPct = 8.4f)
    )
    StadiumDspTheme {
        StadiumRigAdaptiveContent(
            state = previewState,
            telemetryFlow = previewTelemetry,
            onImportExternalAsset = {},
            onApplyAudioSettings = {},
            onStartAudio = {},
            onStepPreset = {},
            onSelectPreset = {},
            onSaveUserPreset = {},
            onSelectSnapshot = {},
            onToggleDiscardEdits = {},
            onToggleStageLock = {},
            onToggleMasterMute = {},
            onTogglePresetClip = {},
            onSelectPresetClipStyle = {},
            onTapTempo = {},
            onUndo = {},
            onRedo = {},
            onToggleCompareAB = {},
            onCopyAB = {},
            onSelectBlock = {},
            onToggleBypass = {},
            onToggleStereo = {},
            onMoveBlock = { _, _ -> },
            onCycleSplitMode = {},
            onSelectAmpModel = {},
            onSelectCabModel = {},
            onSelectMicModel = {},
            onSelectFxModel = { _, _, _, _ -> },
            onUpdateParam = { _, _, _ -> },
            onToggleSnapshotControl = {},
            onArmMidiLearn = {},
            onUpdateFocusXY = { _, _, _ -> },
            onResetCenter = {},
            onFactoryDefault = {},
            onUserDefault = {},
            onSaveUserDefault = {},
            onAddFavorite = {},
            onToggleAmpCabLink = {},
            onToggleCabCabLink = {},
            onSelectLiveMode = {},
            onTriggerPad = {},
            onSelectExpSlot = {},
            onExpMoved = { _, _ -> },
            onToggleShowcase = {},
            onToggleStopwatch = {},
            onResetStopwatch = {},
            onUpdateTunerConfig = {},
        )
    }
}
