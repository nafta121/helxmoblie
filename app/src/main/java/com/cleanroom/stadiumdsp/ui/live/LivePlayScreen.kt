package com.cleanroom.stadiumdsp.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanroom.stadiumdsp.midi.FootswitchAction
import com.cleanroom.stadiumdsp.midi.FootswitchPad
import com.cleanroom.stadiumdsp.midi.LivePlayMode
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import kotlin.math.roundToInt

/**
 * Stage-Proof LIVE Performance Screen (`Home > Play`):
 * - 6 Modes: Combo 4+4, Snapshots 1-8, Stomp A, Stomp B (Looper/Multi), Preset/Setlist, Showcase Cue
 * - Zero-delay touch-down triggering (`PointerEventPass.Initial` via `awaitFirstDown`, NO double-tap lag!)
 * - Top 25% Capacitive Quick-Focus Header Strip + Bottom 75% LED Footswitch Ring
 * - Virtual EXP 1 (Violet) / EXP 2 (Cyan) Expression Ribbon with Auto-Engage
 * - Showcase Cue Timeline & Gig Stopwatch
 */
@Composable
fun LivePlayScreen(
    state: StadiumUiState,
    isPortraitPhone: Boolean,
    onSelectMode: (LivePlayMode) -> Unit,
    onTriggerPad: (FootswitchPad) -> Unit,
    onQuickFocusJump: (String) -> Unit,
    onSelectExpSlot: (Int) -> Unit,
    onExpMoved: (Int, Float) -> Unit,
    onTapTempo: () -> Unit,
    onOpenTuner: () -> Unit,
    onToggleShowcase: () -> Unit,
    onToggleStopwatch: () -> Unit,
    onResetStopwatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeBlocksById = state.activePreset.signalFlow.blocks.associateBy { it.id }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070707))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Mode Switcher Bar + Showcase Cue / Gig Stopwatch Telemetry
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LivePlayMode.entries.forEach { mode ->
                val sel = state.livePlayMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) StadiumColors.AmpRed else StadiumColors.SurfaceCard)
                        .border(1.dp, if (sel) Color.White else StadiumColors.BorderSubtle, RoundedCornerShape(6.dp))
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (sel) Color.White else StadiumColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Gig Stopwatch Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(StadiumColors.SurfaceCard)
                    .border(1.dp, StadiumColors.BorderStrong, RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleStopwatch)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "⏱ ${state.gigStopwatchDisplay}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.gigStopwatchRunning) StadiumColors.DelayEmerald else StadiumColors.TextSecondary,
                )
                Text(
                    text = "RST",
                    style = MaterialTheme.typography.labelSmall,
                    color = StadiumColors.TextMuted,
                    modifier = Modifier.clickable(onClick = onResetStopwatch),
                )
            }

            // Showcase Automated Cue Toggle
            if (state.livePlayMode == LivePlayMode.SHOWCASE) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.showcasePlaying) StadiumColors.DelayEmerald.copy(alpha = 0.25f) else StadiumColors.SurfaceCard)
                        .border(1.dp, StadiumColors.DelayEmerald, RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleShowcase)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = if (state.showcasePlaying) "⏸ CUE: ${state.showcaseNextCueLabel}" else "▶ Start Showcase Cues",
                        style = MaterialTheme.typography.labelSmall,
                        color = StadiumColors.DelayEmerald,
                    )
                }
            }

            // Connected USB/BLE MIDI Status Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(StadiumColors.SurfaceDeep)
                    .border(1.dp, StadiumColors.BorderSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (state.connectedMidiDevices.isEmpty()) "MIDI Ready" else "MIDI: ${state.connectedMidiDevices.first()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.connectedMidiDevices.isEmpty()) StadiumColors.TextMuted else StadiumColors.DelayEmerald,
                )
            }
        }

        // Main Stage Body: Footswitch Matrix + Virtual EXP 1/2 Touch Ribbon
        if (isPortraitPhone) {
            // Phone Portrait: 4x2 Vertical Grid + Bottom TAP/TUNER + Horizontal EXP Bar
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiveFootswitchGrid(
                    pads = state.livePads,
                    columns = 2,
                    activeSnapshotIndex = state.activeSnapshotIndex,
                    activeBlocksById = activeBlocksById,
                    onTriggerPad = onTriggerPad,
                    onQuickFocusJump = onQuickFocusJump,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                // Horizontal EXP + TAP / TUNER Strip
                Row(
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StageUtilityButton("TAP ${state.activePreset.globalBpm.roundToInt()}", StadiumColors.DelayEmerald, onTapTempo, Modifier.weight(1f).fillMaxHeight())
                    StageUtilityButton("STAGE TUNER", StadiumColors.BadgeCyan, onOpenTuner, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            // Landscape / Tablet: 2x4 Wide Footswitch Matrix + Right Vertical EXP 1/2 Ribbon
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiveFootswitchGrid(
                    pads = state.livePads,
                    columns = 4,
                    activeSnapshotIndex = state.activeSnapshotIndex,
                    activeBlocksById = activeBlocksById,
                    onTriggerPad = onTriggerPad,
                    onQuickFocusJump = onQuickFocusJump,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                VirtualExpVerticalRibbon(
                    activeSlot = state.activeExpSlot,
                    value = if (state.activeExpSlot == 1) state.exp1Value else state.exp2Value,
                    onToggleSlot = { onSelectExpSlot(if (state.activeExpSlot == 1) 2 else 1) },
                    onValueChanged = { v -> onExpMoved(state.activeExpSlot, v) },
                    modifier = Modifier.width(88.dp).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun LiveFootswitchGrid(
    pads: List<FootswitchPad>,
    columns: Int,
    activeSnapshotIndex: Int,
    activeBlocksById: Map<String, com.cleanroom.stadiumdsp.preset.RigBlockState>,
    onTriggerPad: (FootswitchPad) -> Unit,
    onQuickFocusJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = pads.chunked(columns)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { rowPads ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPads.forEach { pad ->
                    val isLit = when (val act = pad.action) {
                        is FootswitchAction.RecallSnapshot -> act.snapshotIndex == activeSnapshotIndex
                        is FootswitchAction.ToggleBlocks -> act.blockIds.any { activeBlocksById[it]?.enabled == true }
                        else -> false
                    }
                    LiveFootswitchTile(
                        pad = pad,
                        isLit = isLit,
                        onInstantTouchDown = { onTriggerPad(pad) },
                        onQuickFocusJump = { pad.focusBlockId?.let(onQuickFocusJump) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                repeat(columns - rowPads.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Dual-Zone Stage Footswitch Card:
 * - Top 25%: Capacitive Quick-Focus Header Strip (`◎ BLOCK`)
 * - Bottom 75%: Instant Touch-Down LED Footswitch (`PointerEventPass.Initial` -> `< 1 frame` trigger!)
 */
@Composable
private fun LiveFootswitchTile(
    pad: FootswitchPad,
    isLit: Boolean,
    onInstantTouchDown: () -> Unit,
    onQuickFocusJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ledColor = Color(pad.ledColorHex)
    val latestTrigger = rememberUpdatedState(onInstantTouchDown)
    val isSnapshot = pad.action is FootswitchAction.RecallSnapshot

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isLit) ledColor.copy(alpha = 0.16f) else StadiumColors.SurfaceBar)
            .border(
                width = if (isLit) 2.dp else 1.2.dp,
                color = if (isLit) ledColor else StadiumColors.BorderStrong,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        // Top 25%: Capacitive Quick-Focus Header Strip (separate from stomp trigger!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.28f)
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(enabled = pad.focusBlockId != null, onClick = onQuickFocusJump)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (pad.focusBlockId != null) "◎ ${pad.subLabel}" else pad.subLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (isLit) ledColor else StadiumColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isLit) ledColor else StadiumColors.BorderStrong),
            )
        }

        // Bottom 75%: Instant Touch-Down Footswitch Zone (NO 300ms double-tap delay!)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.72f)
                .pointerInput(pad.padId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        latestTrigger.value()
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                text = pad.label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            // Virtual OLED Scribble Strip / Glowing LED Stomp Ring
            if (isSnapshot) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (isLit) ledColor else StadiumColors.BorderStrong),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isLit) ledColor.copy(alpha = 0.25f) else StadiumColors.SurfaceDeep)
                        .border(3.dp, if (isLit) ledColor else StadiumColors.BorderStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isLit) "ON" else "OFF",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = if (isLit) Color.White else StadiumColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun VirtualExpVerticalRibbon(
    activeSlot: Int,
    value: Float,
    onToggleSlot: () -> Unit,
    onValueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (activeSlot == 1) StadiumColors.PitchWahViolet else StadiumColors.VolPanCyan
    val label = if (activeSlot == 1) "WAH" else "VOL"

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StadiumColors.SurfaceBar)
            .border(1.dp, StadiumColors.BorderStrong, RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = 0.2f))
                .border(1.dp, accent, RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleSlot)
                .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("EXP $activeSlot", style = MaterialTheme.typography.labelMedium, color = accent)
        }

        // Draggable Vertical Expression Track
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .width(34.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(StadiumColors.SurfaceSliderTrack)
                .pointerInput(activeSlot) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val norm = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                        onValueChanged(norm)
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(value.coerceIn(0.03f, 1f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent),
            )
        }

        Text(
            text = "$label ${(value * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun StageUtilityButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = rememberUpdatedState(onClick)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.15f))
            .border(1.5.dp, accent, RoundedCornerShape(10.dp))
            .pointerInput(label) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    d.consume()
                    latest.value()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
    }
}
