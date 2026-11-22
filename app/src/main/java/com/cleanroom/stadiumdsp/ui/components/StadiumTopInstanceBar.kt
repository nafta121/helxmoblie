package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import com.cleanroom.stadiumdsp.ui.theme.StadiumIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.audio.AudioBufferPlanner
import com.cleanroom.stadiumdsp.audio.AudioDeviceOption
import com.cleanroom.stadiumdsp.audio.AudioDspSettings
import com.cleanroom.stadiumdsp.audio.BufferSizeMode
import com.cleanroom.stadiumdsp.audio.OboeSessionController
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@Composable
fun StadiumTopInstanceBar(
    state: StadiumUiState,
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    compactMode: Boolean,
    onStepPreset: (Int) -> Unit,
    onOpenPresets: () -> Unit,
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
    onApplyAudioSettings: (AudioDspSettings) -> Unit,
    onStartAudio: () -> Unit,
    onLiveBufferChange: (Int, BufferSizeMode, Int) -> Unit = { _, _, _ -> },
    onResetBufferMin: () -> Unit = {},
    onRefreshAudioDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showSnapshotRibbon by rememberSaveable { mutableStateOf(!compactMode) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StadiumColors.SurfaceBar)
            .border(width = 1.dp, color = StadiumColors.BorderSubtle),
    ) {
        // Primary Instance Bar Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Left: Live Input Mini Meter + A/B + Undo/Redo (compact hides A/B & Undo into overflow)
            if (!compactMode) {
                HeaderInputMiniMeter(
                    telemetryFlow = telemetryFlow,
                    trimDb = state.activePreset.inputTrimDb,
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StadiumColors.SurfaceDeep)
                        .border(1.dp, StadiumColors.BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CompactPillToggle("A", !state.isCompareSlotB, onClick = { if (state.isCompareSlotB) onToggleCompareAB() })
                    CompactPillToggle("B", state.isCompareSlotB, onClick = { if (!state.isCompareSlotB) onToggleCompareAB() })
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onCopyAB)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text("CPY", style = MaterialTheme.typography.labelSmall, color = StadiumColors.TextMuted)
                    }
                    Box(Modifier.width(1.dp).height(14.dp).background(StadiumColors.BorderSubtle))
                    Icon(
                        imageVector = StadiumIcons.Undo,
                        contentDescription = "Undo",
                        tint = if (state.canUndo) StadiumColors.TextPrimary else StadiumColors.TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(enabled = state.canUndo, onClick = onUndo)
                            .padding(2.dp),
                    )
                    Icon(
                        imageVector = StadiumIcons.Redo,
                        contentDescription = "Redo",
                        tint = if (state.canRedo) StadiumColors.TextPrimary else StadiumColors.TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(enabled = state.canRedo, onClick = onRedo)
                            .padding(2.dp),
                    )
                }
            }

            // Center: Helix Stadium Preset Pill + White/Red Camera Snapshot + Preset Clip Transport
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StadiumColors.CanvasBlack)
                    .border(1.dp, StadiumColors.BorderStrong, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Previous Preset",
                        tint = StadiumColors.TextSecondary,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onStepPreset(-1) },
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Next Preset",
                        tint = StadiumColors.TextSecondary,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onStepPreset(+1) },
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenPresets),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(StadiumColors.SurfaceSliderTrack)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = state.activePreset.bankCode,
                            style = MaterialTheme.typography.labelMedium,
                            color = StadiumColors.BadgeCyan,
                        )
                    }
                    Text(
                        text = state.activePreset.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Light),
                        color = StadiumColors.TextPresetTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Snapshot Camera Indicator (White = Auto-Remember, Red = Discard Edits)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StadiumColors.SurfaceCard)
                        .border(
                            1.dp,
                            if (state.snapshotDiscardEdits) StadiumColors.AmpRed else StadiumColors.BorderStrong,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { showSnapshotRibbon = !showSnapshotRibbon }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = StadiumIcons.Camera,
                        contentDescription = "Snapshot Camera Mode",
                        tint = if (state.snapshotDiscardEdits) StadiumColors.AmpRed else Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "S${state.activeSnapshotIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }

                // Preset Clip Dry DI Audition Transport Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.isPresetClipPlaying) StadiumColors.DelayEmerald.copy(alpha = 0.2f) else StadiumColors.SurfaceCard)
                        .border(
                            1.dp,
                            if (state.isPresetClipPlaying) StadiumColors.DelayEmerald else StadiumColors.BorderSubtle,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable(onClick = onTogglePresetClip)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = if (state.isPresetClipPlaying) StadiumIcons.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Preset Clip Audition",
                        tint = if (state.isPresetClipPlaying) StadiumColors.DelayEmerald else StadiumColors.TextSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    if (!compactMode) {
                        Text(
                            text = "Clip",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isPresetClipPlaying) StadiumColors.DelayEmerald else StadiumColors.TextSecondary,
                        )
                    }
                }
            }

            // Right: TAP Tempo, Stage Lock, Settings, & Panic MUTE ALL
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // TAP Tempo button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StadiumColors.SurfaceDeep)
                        .border(1.dp, StadiumColors.BorderStrong, RoundedCornerShape(6.dp))
                        .clickable(onClick = onTapTempo)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(StadiumColors.DelayEmerald),
                    )
                    Text(
                        text = String.format("%.0f", state.activePreset.globalBpm),
                        style = MaterialTheme.typography.labelMedium,
                        color = StadiumColors.TextPrimary,
                    )
                    if (!compactMode) {
                        Text("TAP", style = MaterialTheme.typography.labelSmall, color = StadiumColors.TextMuted)
                    }
                }

                // Stage Lock button (also engages Red Camera Discard Edits)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.stageLock) StadiumColors.DistortionAmber.copy(alpha = 0.2f) else StadiumColors.SurfaceCard)
                        .border(
                            1.dp,
                            if (state.stageLock) StadiumColors.DistortionAmber else StadiumColors.BorderStrong,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable(onClick = onToggleStageLock)
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = if (state.stageLock) Icons.Rounded.Lock else StadiumIcons.LockOpen,
                        contentDescription = "Stage Lock",
                        tint = if (state.stageLock) StadiumColors.DistortionAmber else StadiumColors.TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    if (!compactMode) {
                        Text(
                            text = if (state.stageLock) "LOCKED" else "Studio",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.stageLock) StadiumColors.DistortionAmber else StadiumColors.TextSecondary,
                        )
                    }
                }

                // Settings Dialog Gear
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StadiumColors.SurfaceCard)
                        .border(1.dp, StadiumColors.BorderSubtle, RoundedCornerShape(6.dp))
                        .clickable { showSettingsDialog = true }
                        .padding(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Audio & DSP Settings",
                        tint = if (state.isAudioRunning) StadiumColors.DelayEmerald else StadiumColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Panic MUTE ALL Button (always responsive!)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.isMasterMuted) StadiumColors.AmpRed else Color(0xFF2D1212))
                        .border(1.dp, StadiumColors.AmpRed, RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleMasterMute)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = if (state.isMasterMuted) StadiumIcons.VolumeOff else StadiumIcons.VolumeUp,
                        contentDescription = "Master Mute All",
                        tint = if (state.isMasterMuted) Color.White else Color(0xFFFF7A7A),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (state.isMasterMuted) "MUTED" else "MUTE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.isMasterMuted) Color.White else Color(0xFFFF7A7A),
                    )
                }
            }
        }

        // Collapsible 8-Snapshot Quick Ribbon + Camera Mode Toggle + Clip Style Chips
        if (showSnapshotRibbon) {
            HorizontalDivider(color = StadiumColors.BorderSubtle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StadiumColors.SurfaceRail)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("SNAPS:", style = MaterialTheme.typography.labelSmall, color = StadiumColors.TextMuted)
                state.activePreset.snapshots.forEach { snap ->
                    val isActive = snap.index == state.activeSnapshotIndex
                    val ledColor = Color(snap.ledColorHex)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isActive) StadiumColors.SurfaceSelected else StadiumColors.SurfaceCard)
                            .border(
                                width = if (isActive) 1.5.dp else 1.dp,
                                color = if (isActive) Color.White else ledColor.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(5.dp),
                            )
                            .clickable { onSelectSnapshot(snap.index) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "${snap.index + 1}:${snap.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) Color.White else StadiumColors.TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))
                // White vs Red Camera Toggle
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(StadiumColors.SurfaceCard)
                        .border(1.dp, if (state.snapshotDiscardEdits) StadiumColors.AmpRed else StadiumColors.BorderSubtle, RoundedCornerShape(5.dp))
                        .clickable(onClick = onToggleDiscardEdits)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = StadiumIcons.Camera,
                        contentDescription = null,
                        tint = if (state.snapshotDiscardEdits) StadiumColors.AmpRed else Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = if (state.snapshotDiscardEdits) "Discard Edits" else "Auto-Remember",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.snapshotDiscardEdits) StadiumColors.AmpRed else StadiumColors.TextSecondary,
                    )
                }

                // Preset Clip Style Selector (Clean / Crunch / Metal / Bass)
                val styles = listOf("Clean", "Crunch", "Metal", "Bass")
                styles.forEachIndexed { idx, label ->
                    val sel = state.presetClipStyleIndex == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel) StadiumColors.BadgeCyan.copy(alpha = 0.2f) else StadiumColors.SurfaceCard)
                            .clickable { onSelectPresetClipStyle(idx) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "DI:$label",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sel) StadiumColors.BadgeCyan else StadiumColors.TextMuted,
                        )
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        AudioDspSettingsDialog(
            state = state,
            telemetryFlow = telemetryFlow,
            onDismiss = { showSettingsDialog = false },
            onApplySettings = { newSettings ->
                onApplyAudioSettings(newSettings)
                showSettingsDialog = false
            },
            onStartAudio = onStartAudio,
            onLiveBufferChange = onLiveBufferChange,
            onResetBufferMin = onResetBufferMin,
            onRefreshAudioDevices = onRefreshAudioDevices,
        )
    }
}

@Composable
private fun HeaderInputMiniMeter(
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    trimDb: Float,
) {
    val t by telemetryFlow.collectAsStateWithLifecycle()
    val norm = ((t.inPeakDb + 60f) / 60f).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StadiumColors.SurfaceDeep)
            .border(1.dp, if (t.inClipped) StadiumColors.ClipRed else StadiumColors.BorderSubtle, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("IN", style = MaterialTheme.typography.labelSmall, color = StadiumColors.TextMuted)
        Box(
            modifier = Modifier
                .width(7.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(StadiumColors.SurfaceSliderTrack),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(norm)
                    .background(if (t.inClipped) StadiumColors.ClipRed else StadiumColors.MeterGreen),
            )
        }
        Text(
            text = String.format("%+.1fdB", trimDb),
            style = MaterialTheme.typography.labelSmall,
            color = StadiumColors.TextSecondary,
        )
    }
}

@Composable
private fun CompactPillToggle(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) StadiumColors.SurfaceSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Color.White else StadiumColors.TextMuted,
        )
    }
}

@Composable
private fun AudioDspSettingsDialog(
    state: StadiumUiState,
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    onDismiss: () -> Unit,
    onApplySettings: (AudioDspSettings) -> Unit,
    onStartAudio: () -> Unit,
    onLiveBufferChange: (Int, BufferSizeMode, Int) -> Unit,
    onResetBufferMin: () -> Unit,
    onRefreshAudioDevices: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onRefreshAudioDevices()
    }

    val t by telemetryFlow.collectAsStateWithLifecycle()
    val diag = state.streamDiagnostics
    val burst = diag.framesPerBurst.coerceAtLeast(16)
    val capacity = diag.bufferCapacityFrames.coerceAtLeast(burst)

    var enableMic by rememberSaveable { mutableStateOf(state.audioSettings.enableMicrophoneInput) }
    var selectedInputId by rememberSaveable { mutableIntStateOf(state.audioSettings.inputDeviceId) }
    var selectedOutputId by rememberSaveable { mutableIntStateOf(state.audioSettings.outputDeviceId) }
    var bufferModeName by rememberSaveable { mutableStateOf(state.audioSettings.bufferSizeMode.name) }
    var burstMult by rememberSaveable { mutableIntStateOf(state.audioSettings.burstMultiplier) }
    var requestedFrames by rememberSaveable {
        mutableIntStateOf(
            if (state.audioSettings.requestedBufferFrames > 0) state.audioSettings.requestedBufferFrames
            else diag.bufferSizeFrames.coerceAtLeast(burst)
        )
    }
    var autoXrunGuard by rememberSaveable { mutableStateOf(state.audioSettings.autoXrunGuard) }
    var keepAwake by rememberSaveable { mutableStateOf(state.audioSettings.keepScreenAwakeInLive) }

    val bufferMode = runCatching { BufferSizeMode.valueOf(bufferModeName) }
        .getOrDefault(BufferSizeMode.HARDWARE_BURST)
    val headroomPct = OboeSessionController.estimateDspCompatibilityPct(t.rtCpuLoadPct)

    val hwOptions = AudioBufferPlanner.buildHardwareAlignedOptions(
        sampleRate = diag.sampleRate,
        framesPerBurst = burst,
        bufferCapacityFrames = capacity,
    )
    val dawPresets = AudioBufferPlanner.buildFixedFramePresets(
        sampleRate = diag.sampleRate,
        framesPerBurst = burst,
        bufferCapacityFrames = capacity,
    )

    val candidateSettings = state.audioSettings.copy(
        enableMicrophoneInput = enableMic,
        inputDeviceId = selectedInputId,
        outputDeviceId = selectedOutputId,
        bufferSizeMode = bufferMode,
        burstMultiplier = burstMult,
        requestedBufferFrames = if (bufferMode == BufferSizeMode.FIXED_FRAMES) requestedFrames else 0,
        autoXrunGuard = autoXrunGuard,
        keepScreenAwakeInLive = keepAwake,
    )
    val requiresRestart = AudioBufferPlanner.needsStreamRestart(state.audioSettings, candidateSettings)
    val selectedOutputDevice = state.availableOutputDevices.firstOrNull { it.id == selectedOutputId }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StadiumColors.SurfaceBar,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Audio / Buffer Settings", color = StadiumColors.TextPrimary, fontSize = 17.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (diag.isMmapUsed) StadiumColors.DelayEmerald.copy(alpha = 0.2f) else StadiumColors.DistortionAmber.copy(alpha = 0.2f))
                        .border(
                            1.dp,
                            if (diag.isMmapUsed) StadiumColors.DelayEmerald else StadiumColors.DistortionAmber,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (diag.isMmapUsed) "MMAP EXCL" else "AAUDIO",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diag.isMmapUsed) StadiumColors.DelayEmerald else StadiumColors.DistortionAmber,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Live Stream Telemetry Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(StadiumColors.SurfaceDeep)
                        .border(1.dp, StadiumColors.BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "${if (state.isAudioRunning) "RUNNING" else "STOPPED"} • ${diag.sampleRate} Hz • HW Burst ${burst}f • Buf ${diag.bufferSizeFrames}f / ${capacity}f",
                        style = MaterialTheme.typography.labelSmall,
                        color = StadiumColors.BadgeCyan,
                    )
                    Text(
                        text = String.format(
                            "Out Buf: %.2f ms • %s: %.2f ms • XRuns: %d",
                            diag.outputBufferMs,
                            if (diag.measuredLatencyMs != null) "HW RTL" else "Est Buf RTL",
                            diag.roundTripLatencyMs,
                            diag.xRunCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = StadiumColors.TextPrimary,
                    )
                    Text(
                        text = "RT CPU: ${String.format("%.1f%%", t.rtCpuLoadPct)} • DSP Headroom: $headroomPct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (headroomPct >= 70) StadiumColors.DelayEmerald else StadiumColors.DistortionAmber,
                    )
                    Text(
                        text = "MIDI: ${if (state.connectedMidiDevices.isEmpty()) "Waiting for USB / BLE MIDI..." else state.connectedMidiDevices.joinToString()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StadiumColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                HorizontalDivider(color = StadiumColors.BorderSubtle)

                // SECTION 1: Input & Output Audio Device Picker (Opsi C)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Audio I/O Devices (USB-C / Wired)",
                        style = MaterialTheme.typography.labelMedium,
                        color = StadiumColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(StadiumColors.SurfaceCard)
                            .border(1.dp, StadiumColors.BorderSubtle, RoundedCornerShape(5.dp))
                            .clickable(onClick = onRefreshAudioDevices)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("Rescan", style = MaterialTheme.typography.labelSmall, color = StadiumColors.BadgeCyan)
                    }
                }

                DevicePickerRow(
                    title = "Input",
                    devices = state.availableInputDevices,
                    selectedId = selectedInputId,
                    onSelect = { selectedInputId = it },
                )

                DevicePickerRow(
                    title = "Output",
                    devices = state.availableOutputDevices,
                    selectedId = selectedOutputId,
                    onSelect = { selectedOutputId = it },
                )

                if (selectedOutputDevice?.isBluetoothWarning == true) {
                    Text(
                        text = "Warning: Bluetooth audio adds 80–200 ms latency. Use USB-C or wired headphones for live guitar.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StadiumColors.DistortionAmber,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Full-Duplex Guitar/Mic Input", color = StadiumColors.TextPrimary, fontSize = 12.sp)
                    Switch(checked = enableMic, onCheckedChange = { enableMic = it })
                }

                HorizontalDivider(color = StadiumColors.BorderSubtle)

                // SECTION 2: Hybrid Buffer Size Control (Opsi A HW Burst + Opsi B Fixed Frames + Live Slider)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Buffer Size (Live 0-ms Glitch)",
                        style = MaterialTheme.typography.labelMedium,
                        color = StadiumColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(StadiumColors.SurfaceCard)
                            .border(1.dp, StadiumColors.DelayEmerald, RoundedCornerShape(5.dp))
                            .clickable {
                                bufferModeName = BufferSizeMode.HARDWARE_BURST.name
                                burstMult = 1
                                requestedFrames = burst
                                onResetBufferMin()
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("Reset 1x Min", style = MaterialTheme.typography.labelSmall, color = StadiumColors.DelayEmerald)
                    }
                }

                // Mode Switch: HW Burst (MMAP) vs Fixed Frames (DAW)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BufferSizeMode.entries.forEach { mode ->
                        val active = bufferMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) StadiumColors.AmpRed else StadiumColors.SurfaceCard)
                                .border(1.dp, if (active) StadiumColors.AmpRed else StadiumColors.BorderSubtle, RoundedCornerShape(6.dp))
                                .clickable { bufferModeName = mode.name }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(mode.label, style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }

                if (bufferMode == BufferSizeMode.HARDWARE_BURST) {
                    // Opsi A: Hardware-Aligned Burst Multiples
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        hwOptions.forEach { opt ->
                            val mult = opt.multiplier ?: 1
                            val selected = burstMult == mult
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) StadiumColors.AmpRed else StadiumColors.SurfaceCard)
                                    .border(
                                        1.dp,
                                        if (selected) Color.White.copy(alpha = 0.7f) else StadiumColors.BorderSubtle,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable {
                                        burstMult = mult
                                        requestedFrames = opt.frames
                                        onLiveBufferChange(opt.frames, BufferSizeMode.HARDWARE_BURST, mult)
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(opt.label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    text = String.format("%df • %.1fms", opt.frames, opt.latencyMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (selected) Color.White else StadiumColors.BadgeCyan,
                                )
                            }
                        }
                    }
                } else {
                    // Opsi B: Classic DAW / ASIO Fixed Frame Presets (★ = Native MMAP aligned to HW burst)
                    Text(
                        text = "★ = Native MMAP aligned (kelipatan burst ${burst}f). Nilai di luar [${burst}f..${capacity}f] di-clamp otomatis oleh HAL.",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = StadiumColors.TextMuted,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        dawPresets.forEach { opt ->
                            val selected = requestedFrames == opt.frames
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            selected -> StadiumColors.AmpRed
                                            opt.isWithinCapacity -> StadiumColors.SurfaceCard
                                            else -> StadiumColors.SurfaceDeep
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        when {
                                            selected -> Color.White.copy(alpha = 0.7f)
                                            opt.isNativeAligned -> StadiumColors.BadgeCyan.copy(alpha = 0.7f)
                                            else -> StadiumColors.BorderSubtle
                                        },
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable {
                                        requestedFrames = opt.frames
                                        val estMult = (opt.frames / burst).coerceIn(1, 16)
                                        burstMult = estMult
                                        onLiveBufferChange(opt.frames, BufferSizeMode.FIXED_FRAMES, estMult)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = if (opt.isNativeAligned) "★ ${opt.frames}f" else "${opt.frames}f",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (opt.isWithinCapacity || selected) Color.White else StadiumColors.TextMuted,
                                    fontWeight = if (opt.isNativeAligned) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    text = String.format("%.1fms", opt.latencyMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (selected) Color.White else StadiumColors.TextSecondary,
                                )
                            }
                        }
                    }
                }

                // Live Fine Buffer Slider (active when hardware capacity > 1 burst)
                if (capacity > burst) {
                    val sliderMin = burst.toFloat()
                    val sliderMax = capacity.toFloat()
                    val currentSliderFrames = diag.bufferSizeFrames.coerceIn(burst, capacity)
                    val stepCount = ((capacity - burst) / burst - 1).coerceAtLeast(0)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Fine Live Buffer", style = MaterialTheme.typography.labelSmall, color = StadiumColors.TextSecondary)
                            Text(
                                text = String.format(
                                    "%df / %df (%.2f ms)",
                                    currentSliderFrames,
                                    capacity,
                                    AudioBufferPlanner.framesToMs(currentSliderFrames, diag.sampleRate),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = StadiumColors.BadgeCyan,
                            )
                        }
                        Slider(
                            value = currentSliderFrames.toFloat(),
                            onValueChange = { raw ->
                                val snapped = ((raw / burst).roundToInt().coerceAtLeast(1) * burst).coerceIn(burst, capacity)
                                requestedFrames = snapped
                                val newMult = (snapped / burst).coerceIn(1, 16)
                                burstMult = newMult
                                onLiveBufferChange(snapped, bufferMode, newMult)
                            },
                            valueRange = sliderMin..sliderMax,
                            steps = stepCount,
                            colors = SliderDefaults.colors(
                                thumbColor = StadiumColors.AmpRed,
                                activeTrackColor = StadiumColors.AmpRed,
                                inactiveTrackColor = StadiumColors.SurfaceSliderTrack,
                            ),
                        )
                    }
                }

                // Auto XRun Guard & Keep Screen Awake
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto XRun Guard", color = StadiumColors.TextPrimary, fontSize = 12.sp)
                        Text(
                            "Otomatis naik +1 burst bila ada dropout/XRun",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = StadiumColors.TextMuted,
                        )
                    }
                    Switch(checked = autoXrunGuard, onCheckedChange = { autoXrunGuard = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keep Screen Awake in LIVE Mode", color = StadiumColors.TextPrimary, fontSize = 12.sp)
                    Switch(checked = keepAwake, onCheckedChange = { keepAwake = it })
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isAudioRunning) {
                    Button(
                        onClick = { onStartAudio(); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = StadiumColors.DelayEmerald),
                    ) {
                        Text("Start Audio", color = Color.Black)
                    }
                }
                Button(
                    onClick = { onApplySettings(candidateSettings) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (requiresRestart) StadiumColors.AmpRed else StadiumColors.DelayEmerald,
                    ),
                ) {
                    Text(
                        text = if (requiresRestart) "Apply & Restart" else "Save",
                        color = if (requiresRestart) Color.White else Color.Black,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = StadiumColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun DevicePickerRow(
    title: String,
    devices: List<AudioDeviceOption>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = StadiumColors.TextSecondary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            devices.forEach { dev ->
                val selected = dev.id == selectedId
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) StadiumColors.SurfaceSelected else StadiumColors.SurfaceCard)
                        .border(
                            1.dp,
                            when {
                                selected -> StadiumColors.AmpRed
                                dev.isUsbOrPro -> StadiumColors.BadgeCyan.copy(alpha = 0.6f)
                                dev.isBluetoothWarning -> StadiumColors.DistortionAmber.copy(alpha = 0.6f)
                                else -> StadiumColors.BorderSubtle
                            },
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelect(dev.id) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = dev.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Color.White else StadiumColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = dev.typeBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = when {
                            dev.isUsbOrPro -> StadiumColors.BadgeCyan
                            dev.isBluetoothWarning -> StadiumColors.DistortionAmber
                            else -> StadiumColors.TextMuted
                        },
                    )
                }
            }
        }
    }
}
