package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.cleanroom.stadiumdsp.catalog.AmpModelCatalog
import com.cleanroom.stadiumdsp.catalog.FactoryCabCatalog
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.preset.RigBlockState
import com.cleanroom.stadiumdsp.preset.SignalFlowState
import com.cleanroom.stadiumdsp.preset.SplitRoutingMode
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import com.cleanroom.stadiumdsp.ui.theme.composeColor
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure JVM testable signal flow routing rules:
 * - Path 1A / Parallel Path 1B splitting
 * - Amp > Cab Link automatic matched factory cab sync
 * - Dynamic block slot reordering
 */
object SignalFlowRoutingLogic {
    fun blocksOnBranch(flow: SignalFlowState, branch: String): List<RigBlockState> {
        if (flow.splitMode == SplitRoutingMode.SERIAL) {
            return if (branch == "1A") flow.blocks else emptyList()
        }
        return flow.blocks.filter { it.pathBranch == branch }
    }

    fun selectAmpModel(flow: SignalFlowState, ampNativeId: Int): SignalFlowState {
        val ampSpec = AmpModelCatalog.byId(ampNativeId)
        val matchedCab = FactoryCabCatalog.cabById(ampSpec.defaultCabId)
        val updated = flow.blocks.map { blk ->
            when (blk.id) {
                "amp" -> blk.copy(
                    modelId = ampSpec.nativeId,
                    displayName = ampSpec.displayName,
                    shortLabel = ampSpec.shortLabel,
                )
                "cab" -> if (flow.ampCabLink) {
                    blk.copy(
                        modelId = matchedCab.nativeId,
                        displayName = matchedCab.displayName,
                        shortLabel = matchedCab.shortLabel,
                    )
                } else blk
                else -> blk
            }
        }
        return flow.copy(blocks = updated)
    }

    fun moveBlock(flow: SignalFlowState, fromIndex: Int, toIndex: Int): SignalFlowState {
        if (fromIndex !in flow.blocks.indices || toIndex !in flow.blocks.indices || fromIndex == toIndex) {
            return flow
        }
        val mutable = flow.blocks.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return flow.copy(blocks = mutable)
    }
}

@Composable
fun SignalFlowCanvas(
    flow: SignalFlowState,
    selectedBlockId: String,
    stageLocked: Boolean,
    compactSingleRibbon: Boolean,
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    onSelectBlock: (String) -> Unit,
    onToggleBypass: (String) -> Unit,
    onOpenFocus: (String) -> Unit,
    onMoveBlock: (Int, Int) -> Unit,
    onCycleSplitMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val path1A = SignalFlowRoutingLogic.blocksOnBranch(flow, "1A")
    val path1B = SignalFlowRoutingLogic.blocksOnBranch(flow, "1B")
    var actionMenuBlockId by rememberSaveable { mutableStateOf<String?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(StadiumColors.CanvasBlack)
            .border(1.dp, StadiumColors.BorderSubtle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Input Vertical Peak Meter + Auto-Z Badge
        VerticalIoMeterColumn(
            label = "IN",
            footer = if (flow.autoZEnabled) "AutoZ" else "ManZ",
            isOutput = false,
            telemetryFlow = telemetryFlow,
            compact = compactSingleRibbon,
        )

        // Dual Stereo Path Canvas (Scrollable horizontally with rememberScrollState!)
        Column(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = if (compactSingleRibbon) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Path 1A Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RoutingTerminalChip("1A In")
                WireSegment()

                val listA = if (compactSingleRibbon) flow.blocks else path1A
                listA.forEach { blk ->
                    val globalIdx = flow.blocks.indexOfFirst { it.id == blk.id }
                    Box {
                        SignalFlowBlockNode(
                            block = blk,
                            selected = blk.id == selectedBlockId,
                            showAmpCabLink = blk.id == "amp" && flow.ampCabLink,
                            compact = compactSingleRibbon,
                            onClick = {
                                if (blk.id == selectedBlockId) {
                                    actionMenuBlockId = blk.id
                                } else {
                                    onSelectBlock(blk.id)
                                }
                            },
                        )

                        // Second-tap Action Popover (Helix Stadium Native pattern)
                        DropdownMenu(
                            expanded = actionMenuBlockId == blk.id,
                            onDismissRequest = { actionMenuBlockId = null },
                            containerColor = StadiumColors.SurfaceCard,
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (blk.enabled) "Bypass Block" else "Engage Block", color = Color.White) },
                                enabled = !stageLocked,
                                onClick = {
                                    actionMenuBlockId = null
                                    onToggleBypass(blk.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open Focus View", color = StadiumColors.BadgeCyan) },
                                onClick = {
                                    actionMenuBlockId = null
                                    onOpenFocus(blk.id)
                                },
                            )
                            if (globalIdx > 0) {
                                DropdownMenuItem(
                                    text = { Text("Move Left ◀", color = StadiumColors.TextSecondary) },
                                    enabled = !stageLocked,
                                    onClick = {
                                        actionMenuBlockId = null
                                        onMoveBlock(globalIdx, globalIdx - 1)
                                    },
                                )
                            }
                            if (globalIdx in 0 until flow.blocks.lastIndex) {
                                DropdownMenuItem(
                                    text = { Text("Move Right ▶", color = StadiumColors.TextSecondary) },
                                    enabled = !stageLocked,
                                    onClick = {
                                        actionMenuBlockId = null
                                        onMoveBlock(globalIdx, globalIdx + 1)
                                    },
                                )
                            }
                        }
                    }
                    WireSegment(widthDp = 8)
                }

                RoutingTerminalChip("Out 1/2")
            }

            // Parallel Path 1B Row (Split Y / A-B / Crossover -> Path 1B -> Merge Mixer)
            if (!compactSingleRibbon) {
                Row(
                    modifier = Modifier.padding(start = 140.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(StadiumColors.SurfaceBar)
                            .border(1.dp, StadiumColors.RoutingTitanium.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                            .clickable(enabled = !stageLocked, onClick = onCycleSplitMode)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "${flow.splitMode.label} → 1B",
                            style = MaterialTheme.typography.labelSmall,
                            color = StadiumColors.TextSecondary,
                        )
                    }

                    if (path1B.isNotEmpty()) {
                        WireSegment(widthDp = 10)
                        path1B.forEach { blk ->
                            SignalFlowBlockNode(
                                block = blk,
                                selected = blk.id == selectedBlockId,
                                showAmpCabLink = false,
                                compact = false,
                                onClick = {
                                    if (blk.id == selectedBlockId) {
                                        onToggleBypass(blk.id)
                                    } else {
                                        onSelectBlock(blk.id)
                                    }
                                },
                            )
                            WireSegment(widthDp = 8)
                        }
                        RoutingTerminalChip("→ Merge Mixer")
                    } else {
                        Text(
                            text = "(Serial Path — Tap Split Y to enable Parallel 1B)",
                            style = MaterialTheme.typography.labelSmall,
                            color = StadiumColors.TextMuted,
                        )
                    }
                }
            }
        }

        // Right Stereo Output L/R Vertical Meter Column
        VerticalIoMeterColumn(
            label = "OUT",
            footer = "L/R",
            isOutput = true,
            telemetryFlow = telemetryFlow,
            compact = compactSingleRibbon,
        )
    }
}

@Composable
private fun SignalFlowBlockNode(
    block: RigBlockState,
    selected: Boolean,
    showAmpCabLink: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val catColor = block.category.composeColor()
    val borderColor = when {
        selected -> Color.White
        block.enabled -> catColor
        else -> StadiumColors.BorderStrong
    }
    val bgColor = if (block.enabled) catColor.copy(alpha = 0.17f) else StadiumColors.SurfaceCard

    Column(
        modifier = Modifier
            .widthIn(min = if (compact) 58.dp else 68.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(width = if (selected) 2.dp else 1.2.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 5.dp else 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (block.enabled) catColor else StadiumColors.TextMuted.copy(alpha = 0.4f)),
            )
            if (block.isStereo) {
                Text("ST", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), color = StadiumColors.TextMuted)
            }
        }
        Text(
            text = block.shortLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (block.enabled) Color.White else StadiumColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showAmpCabLink && !compact) {
            Text(
                text = "LINK",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = StadiumColors.GoldLink,
            )
        }
    }
}

@Composable
private fun RoutingTerminalChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(StadiumColors.SurfaceBar)
            .border(1.dp, StadiumColors.BorderSubtle, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = StadiumColors.RoutingTitanium)
    }
}

@Composable
private fun WireSegment(widthDp: Int = 10) {
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .height(2.dp)
            .background(Color(0xFF4A4A4A)),
    )
}

/**
 * Isolated 60 Hz leaf meter composable: only this tiny column recomposes on telemetry ticks!
 */
@Composable
fun VerticalIoMeterColumn(
    label: String,
    footer: String,
    isOutput: Boolean,
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val t by telemetryFlow.collectAsStateWithLifecycle()
    val barHeight = if (compact) 40.dp else 72.dp

    Column(
        modifier = modifier
            .width(38.dp)
            .background(StadiumColors.SurfaceRail)
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = StadiumColors.TextMuted)

        if (!isOutput) {
            val norm = ((t.inPeakDb + 60f) / 60f).coerceIn(0f, 1f)
            MeterBarVertical(norm = norm, clipped = t.inClipped, heightDp = barHeight, widthDp = 10.dp)
        } else {
            val normL = if (t.isMasterMuted) 0f else ((t.outPeakLDb + 60f) / 60f).coerceIn(0f, 1f)
            val normR = if (t.isMasterMuted) 0f else ((t.outPeakRDb + 60f) / 60f).coerceIn(0f, 1f)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MeterBarVertical(norm = normL, clipped = t.outClipped, heightDp = barHeight, widthDp = 7.dp)
                MeterBarVertical(norm = normR, clipped = t.outClipped, heightDp = barHeight, widthDp = 7.dp)
            }
        }

        Text(
            text = if (isOutput && t.isMasterMuted) "MUTE" else footer,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = if (isOutput && t.isMasterMuted) StadiumColors.ClipRed else StadiumColors.DelayEmerald,
            maxLines = 1,
        )
    }
}

@Composable
private fun MeterBarVertical(
    norm: Float,
    clipped: Boolean,
    heightDp: androidx.compose.ui.unit.Dp,
    widthDp: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(heightDp)
            .clip(RoundedCornerShape(2.dp))
            .background(StadiumColors.SurfaceSliderTrack)
            .padding(1.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val barColor = when {
            clipped -> StadiumColors.ClipRed
            norm > 0.82f -> StadiumColors.MeterYellow
            else -> StadiumColors.MeterGreen
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(norm.coerceAtLeast(0.03f))
                .clip(RoundedCornerShape(1.dp))
                .background(barColor),
        )
    }
}
