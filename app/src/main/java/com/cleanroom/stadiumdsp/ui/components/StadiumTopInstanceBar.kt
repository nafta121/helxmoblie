package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
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
import com.cleanroom.stadiumdsp.ui.theme.StadiumIcons
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
    onInputTrimChange: (Float) -> Unit = {},
    onOutputLevelChange: (Float) -> Unit = {},
    onDismissModelBrowser: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showSnapshotRibbon by rememberSaveable { mutableStateOf(false) }
    var showPresetInfoDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismissModelBrowser() })
            },
    ) {
        // Primary Helix Stadium Native Top Bar (#141414 — 1:1 visual parity with reference)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Far Left: Metallic Rotary Input Trim Knob (drag vertically, double-tap resets 0.0 dB)
            MetallicRotaryKnob(
                valueDb = state.activePreset.inputTrimDb,
                sizeDp = if (compactMode) 28.dp else 32.dp,
                onValueChange = onInputTrimChange,
            )

            // Left Controls: A -> B Compare + Undo / Redo (hidden in very narrow compact mode)
            if (!compactMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "A",
                        color = if (!state.isCompareSlotB) Color.White else StadiumColors.TextMuted,
                        fontSize = 13.sp,
                        fontWeight = if (!state.isCompareSlotB) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { if (state.isCompareSlotB) onToggleCompareAB() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Text(
                        text = "→",
                        color = StadiumColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onCopyAB)
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                    Text(
                        text = "B",
                        color = if (state.isCompareSlotB) Color.White else StadiumColors.TextMuted,
                        fontSize = 13.sp,
                        fontWeight = if (state.isCompareSlotB) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { if (!state.isCompareSlotB) onToggleCompareAB() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )

                    Spacer(Modifier.width(4.dp))

                    Icon(
                        imageVector = StadiumIcons.Undo,
                        contentDescription = "Undo",
                        tint = if (state.canUndo) Color.White else StadiumColors.TextMuted.copy(alpha = 0.45f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(enabled = state.canUndo, onClick = onUndo),
                    )
                    Icon(
                        imageVector = StadiumIcons.Redo,
                        contentDescription = "Redo",
                        tint = if (state.canRedo) Color.White else StadiumColors.TextMuted.copy(alpha = 0.45f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(enabled = state.canRedo, onClick = onRedo),
                    )
                }
            }

            // Center Dark Inset Capsule: [••• | ⇕ Space Cadet       [Camera 1] [Clip] [(i)]]
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF0A0A0A))
                    .border(1.dp, Color(0xFF252525), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ••• Preset Menu button
                Text(
                    text = "•••",
                    color = StadiumColors.TextSecondary,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onOpenPresets)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )

                Box(Modifier.width(1.dp).height(18.dp).background(Color(0xFF242424)))

                // Up / Down Preset Stepper Chevrons (⇕)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy((-4).dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Previous Preset",
                        tint = StadiumColors.TextSecondary,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { onStepPreset(-1) },
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Next Preset",
                        tint = StadiumColors.TextSecondary,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { onStepPreset(+1) },
                    )
                }

                // Preset Title ("Space Cadet" — clean light white sans-serif)
                Text(
                    text = state.activePreset.name,
                    color = Color(0xFFEDF2F7),
                    fontSize = if (compactMode) 14.sp else 16.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenPresets),
                )

                // Right icons inside/beside preset bar: Camera [1], Guitar Pick DI Clip, Info (i)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (showSnapshotRibbon) Color(0xFF222226) else Color.Transparent)
                        .clickable { showSnapshotRibbon = !showSnapshotRibbon }
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = StadiumIcons.Camera,
                            contentDescription = "Snapshots",
                            tint = if (state.snapshotDiscardEdits) StadiumColors.AmpRed else Color(0xFFDCDCDC),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "${state.activeSnapshotIndex + 1}",
                            color = if (state.snapshotDiscardEdits) StadiumColors.AmpRed else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                // Preset Clip Audition (Guitar Pick icon matching reference!)
                Icon(
                    imageVector = if (state.isPresetClipPlaying) StadiumIcons.Pause else StadiumIcons.GuitarPick,
                    contentDescription = "Preset Clip Audition",
                    tint = if (state.isPresetClipPlaying) StadiumColors.DelayEmerald else Color(0xFF8C8C92),
                    modifier = Modifier
                        .size(17.dp)
                        .clickable(onClick = onTogglePresetClip),
                )

                if (!compactMode) {
                    // Info (i) button
                    Icon(
                        imageVector = StadiumIcons.Info,
                        contentDescription = "Preset Info & Stage Controls",
                        tint = if (state.stageLock) StadiumColors.DistortionAmber else Color(0xFFCFD4DA),
                        modifier = Modifier
                            .size(17.dp)
                            .clickable { showPresetInfoDialog = true },
                    )
                }
            }

            // Right: Dark Clock + BPM Pill (`165.0`) — Tap for Tap Tempo!
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF0A0A0A))
                    .border(1.dp, Color(0xFF252525), RoundedCornerShape(5.dp))
                    .clickable(onClick = onTapTempo)
                    .padding(horizontal = if (compactMode) 6.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = StadiumIcons.Clock,
                    contentDescription = "Tap Tempo",
                    tint = Color(0xFFD5D8DC),
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = String.format("%.1f", state.activePreset.globalBpm),
                    color = Color(0xFFE0E4E8),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Compact Audio/Buffer Settings Gear (Opsi C Dialog) + Mute Panic dot
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF1C1C20))
                    .border(
                        1.dp,
                        if (state.isAudioRunning) StadiumColors.DelayEmerald.copy(alpha = 0.55f) else Color(0xFF2E2E34),
                        RoundedCornerShape(5.dp),
                    )
                    .clickable { showSettingsDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Audio & Buffer Settings",
                    tint = if (state.isAudioRunning) StadiumColors.DelayEmerald else StadiumColors.TextSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }

            // Compact Panic MUTE icon (glows bright red when muted)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (state.isMasterMuted) StadiumColors.AmpRed else Color(0xFF1C1C20))
                    .border(
                        1.dp,
                        if (state.isMasterMuted) Color.White else Color(0xFF3A2020),
                        RoundedCornerShape(5.dp),
                    )
                    .clickable(onClick = onToggleMasterMute),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isMasterMuted) StadiumIcons.VolumeOff else StadiumIcons.VolumeUp,
                    contentDescription = "Master Mute All",
                    tint = if (state.isMasterMuted) Color.White else Color(0xFFE57373),
                    modifier = Modifier.size(14.dp),
                )
            }

            // Far Right: Metallic Rotary Output Level Knob (drag vertically, double-tap resets 0.0 dB)
            MetallicRotaryKnob(
                valueDb = state.activePreset.outputLevelDb,
                sizeDp = if (compactMode) 28.dp else 32.dp,
                onValueChange = onOutputLevelChange,
            )
        }

        HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)

        // Collapsible 8-Snapshot Quick Ribbon + Camera Mode Toggle + Clip Style Chips (toggled by Camera [1])
        if (showSnapshotRibbon) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E0E10))
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
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isActive) StadiumColors.SurfaceSelected else StadiumColors.SurfaceCard)
                            .border(
                                width = if (isActive) 1.5.dp else 1.dp,
                                color = if (isActive) Color.White else ledColor.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(4.dp),
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

                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(StadiumColors.SurfaceCard)
                        .border(1.dp, if (state.snapshotDiscardEdits) StadiumColors.AmpRed else StadiumColors.BorderSubtle, RoundedCornerShape(4.dp))
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

                // Stage Lock quick toggle inside ribbon
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (state.stageLock) StadiumColors.DistortionAmber.copy(alpha = 0.2f) else StadiumColors.SurfaceCard)
                        .border(1.dp, if (state.stageLock) StadiumColors.DistortionAmber else StadiumColors.BorderSubtle, RoundedCornerShape(4.dp))
                        .clickable(onClick = onToggleStageLock)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = if (state.stageLock) Icons.Rounded.Lock else StadiumIcons.LockOpen,
                        contentDescription = "Stage Lock",
                        tint = if (state.stageLock) StadiumColors.DistortionAmber else StadiumColors.TextSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = if (state.stageLock) "STAGE LOCKED" else "Studio Edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.stageLock) StadiumColors.DistortionAmber else StadiumColors.TextSecondary,
                    )
                }

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
            HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)
        }
    }

    if (showPresetInfoDialog) {
        AlertDialog(
            onDismissRequest = { showPresetInfoDialog = false },
            containerColor = StadiumColors.SurfaceBar,
            title = {
                Text("${state.activePreset.bankCode} • ${state.activePreset.name}", color = Color.White)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = state.activePreset.presetInfo.ifBlank { "Dual-Path Stadium DSP Rig." },
                        style = MaterialTheme.typography.bodySmall,
                        color = StadiumColors.TextSecondary,
                    )
                    Text(
                        text = "Input Trim: ${String.format("%+.1f dB", state.activePreset.inputTrimDb)} • Output Level: ${String.format("%+.1f dB", state.activePreset.outputLevelDb)} • BPM: ${String.format("%.1f", state.activePreset.globalBpm)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StadiumColors.BadgeCyan,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Stage Lock (Protect Sliders)", color = Color.White, fontSize = 13.sp)
                        Switch(checked = state.stageLock, onCheckedChange = { onToggleStageLock() })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Red Camera (Discard Edits)", color = Color.White, fontSize = 13.sp)
                        Switch(checked = state.snapshotDiscardEdits, onCheckedChange = { onToggleDiscardEdits() })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresetInfoDialog = false }) { Text("Close", color = StadiumColors.BadgeCyan) }
            },
        )
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
