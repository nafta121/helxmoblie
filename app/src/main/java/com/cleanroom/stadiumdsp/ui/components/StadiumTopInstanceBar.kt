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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.cleanroom.stadiumdsp.audio.AudioDspSettings
import com.cleanroom.stadiumdsp.audio.OboeSessionController
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import kotlinx.coroutines.flow.StateFlow

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
                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = "Undo",
                        tint = if (state.canUndo) StadiumColors.TextPrimary else StadiumColors.TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(enabled = state.canUndo, onClick = onUndo)
                            .padding(2.dp),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Redo,
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
                        imageVector = Icons.Rounded.CameraAlt,
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
                        imageVector = if (state.isPresetClipPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
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
                        imageVector = if (state.stageLock) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
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
                        imageVector = if (state.isMasterMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
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
                        imageVector = Icons.Rounded.CameraAlt,
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
) {
    val t by telemetryFlow.collectAsStateWithLifecycle()
    var enableMic by rememberSaveable { mutableStateOf(state.audioSettings.enableMicrophoneInput) }
    var burstMult by rememberSaveable { mutableStateOf(state.audioSettings.burstMultiplier) }
    var keepAwake by rememberSaveable { mutableStateOf(state.audioSettings.keepScreenAwakeInLive) }

    val headroomPct = OboeSessionController.estimateDspCompatibilityPct(t.rtCpuLoadPct)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StadiumColors.SurfaceBar,
        title = {
            Text("Audio / DSP Engine Settings", color = StadiumColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Stream: ${if (state.isAudioRunning) "RUNNING" else "STOPPED"} • ${state.streamDiagnostics.sampleRate} Hz • " +
                        "Burst ${state.streamDiagnostics.framesPerBurst}f • ${String.format("%.1f ms", state.streamDiagnostics.roundTripLatencyMs)} • " +
                        "XRuns: ${state.streamDiagnostics.xRunCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StadiumColors.BadgeCyan,
                )
                Text(
                    text = "RT CPU Load: ${String.format("%.1f%%", t.rtCpuLoadPct)} • Hardware DSP Headroom: $headroomPct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (headroomPct >= 70) StadiumColors.DelayEmerald else StadiumColors.DistortionAmber,
                )
                Text(
                    text = "MIDI Controllers: ${if (state.connectedMidiDevices.isEmpty()) "Waiting for USB / BLE MIDI..." else state.connectedMidiDevices.joinToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StadiumColors.TextSecondary,
                )

                HorizontalDivider(color = StadiumColors.BorderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Full-Duplex Guitar/Mic Input", color = StadiumColors.TextPrimary, fontSize = 13.sp)
                    Switch(checked = enableMic, onCheckedChange = { enableMic = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keep Screen Awake in LIVE Mode", color = StadiumColors.TextPrimary, fontSize = 13.sp)
                    Switch(checked = keepAwake, onCheckedChange = { keepAwake = it })
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Oboe Buffer Burst Multiplier", color = StadiumColors.TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "1x Ultra", 2 to "2x Stage", 4 to "4x Safe").forEach { (mult, lbl) ->
                            val sel = burstMult == mult
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) StadiumColors.AmpRed else StadiumColors.SurfaceCard)
                                    .clickable { burstMult = mult }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(lbl, style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                    }
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
                    onClick = {
                        onApplySettings(
                            state.audioSettings.copy(
                                enableMicrophoneInput = enableMic,
                                burstMultiplier = burstMult,
                                keepScreenAwakeInLive = keepAwake,
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StadiumColors.AmpRed),
                ) {
                    Text("Apply")
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
