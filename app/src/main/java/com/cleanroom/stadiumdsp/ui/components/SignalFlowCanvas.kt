package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.RigBlockCode
import com.cleanroom.stadiumdsp.catalog.AmpModelCatalog
import com.cleanroom.stadiumdsp.catalog.FactoryCabCatalog
import com.cleanroom.stadiumdsp.preset.DualPathRoutingEngine
import com.cleanroom.stadiumdsp.preset.RigBlockState
import com.cleanroom.stadiumdsp.preset.SignalFlowState
import com.cleanroom.stadiumdsp.preset.SplitRoutingMode
import com.cleanroom.stadiumdsp.ui.state.PendingInsertSlot
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import com.cleanroom.stadiumdsp.ui.theme.composeColor
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure JVM testable signal flow routing rules (preserved for backward compatibility):
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

private val WireColor = Color(0xFF7B7F86)

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
    inputTrimDb: Float = 0f,
    outputLevelDb: Float = 0f,
    pendingInsertSlot: PendingInsertSlot? = null,
    onDismissModelBrowser: () -> Unit = {},
    onRequestInsertAtSlot: (String, Int) -> Unit = { _, _ -> },
    onDropDragPayload: (StudioDragPayload, StudioDropTarget) -> Unit = { _, _ -> },
    onCycleSplitMode2: () -> Unit = {},
    onToggleMerge1bInto1a: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dragController = LocalStudioDragDropController.current
    val isDragging = dragController?.isDragging == true
    val isMovingExisting = dragController?.activePayload is StudioDragPayload.MoveExistingBlock

    val b1a = DualPathRoutingEngine.blocksOnBranch(flow, "1A")
    val b1b = DualPathRoutingEngine.blocksOnBranch(flow, "1B")
    val b2a = DualPathRoutingEngine.blocksOnBranch(flow, "2A")
    val b2b = DualPathRoutingEngine.blocksOnBranch(flow, "2B")

    val split1Idx = flow.split1AfterIndex.coerceIn(0, b1a.size)
    val pre1a = b1a.subList(0, split1Idx)
    val post1a = b1a.subList(split1Idx, b1a.size)

    val split2Idx = flow.split2AfterIndex.coerceIn(0, b2a.size)
    val merge2Idx = flow.merge2AtIndex.coerceIn(split2Idx, b2a.size)
    val pre2a = b2a.subList(0, split2Idx)
    val mid2a = b2a.subList(split2Idx, merge2Idx)
    val post2a = b2a.subList(merge2Idx, b2a.size)

    val canvasHeight = if (compactSingleRibbon) 142.dp else 212.dp
    val blockSize = if (compactSingleRibbon) 34.dp else 44.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(canvasHeight)
            .background(StadiumColors.CanvasBlack)
            .pointerInput(Unit) {
                // Clicking any empty black area in Signal Flow auto-closes the Model Selection Module!
                detectTapGestures(onTap = { onDismissModelBrowser() })
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Far Left: Precision Input dB Scale & Meter
            PrecisionDbMeterColumn(
                telemetryFlow = telemetryFlow,
                isInput = true,
                trimDb = inputTrimDb,
            )

            // Center Dual-Path Routing Field (Path 1A/1B top + Path 2A/2B bottom)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                val minWidth = maxWidth
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .widthIn(min = minWidth)
                        .padding(horizontal = 6.dp, vertical = if (compactSingleRibbon) 4.dp else 8.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // ==================== PATH 1 (1A + Parallel 1B) ====================
                    DualBranchPathRow(
                        mainBranch = "1A",
                        parallelBranch = "1B",
                        leftNodeKind = "IN",
                        rightMainNodeKind = if (flow.path1aFeeds2a) "DOWN" else "OUT",
                        rightParallelNodeKind = if (flow.merge1bInto1a) null else "OUT",
                        preBlocks = pre1a,
                        midBlocks = post1a,
                        postBlocks = emptyList(),
                        parallelBlocks = b1b,
                        hasMergeNode = flow.merge1bInto1a,
                        ampCabLink = flow.ampCabLink,
                        selectedBlockId = selectedBlockId,
                        pendingInsertSlot = pendingInsertSlot,
                        blockSize = blockSize,
                        stageLocked = stageLocked,
                        isDragging = isDragging,
                        onSelectBlock = onSelectBlock,
                        onToggleBypass = onToggleBypass,
                        onOpenFocus = onOpenFocus,
                        onRequestInsertAtSlot = onRequestInsertAtSlot,
                        onSplitClick = onCycleSplitMode,
                        onMergeOrOutClick = onToggleMerge1bInto1a,
                        onDropPayload = onDropDragPayload,
                    )

                    // ==================== PATH 2 (2A + Parallel 2B -> Merge 2) ====================
                    DualBranchPathRow(
                        mainBranch = "2A",
                        parallelBranch = "2B",
                        leftNodeKind = if (flow.path1aFeeds2a) "DOWN" else "IN",
                        rightMainNodeKind = "OUT",
                        rightParallelNodeKind = null,
                        preBlocks = pre2a,
                        midBlocks = mid2a,
                        postBlocks = post2a,
                        parallelBlocks = b2b,
                        hasMergeNode = true,
                        ampCabLink = flow.ampCabLink,
                        selectedBlockId = selectedBlockId,
                        pendingInsertSlot = pendingInsertSlot,
                        blockSize = blockSize,
                        stageLocked = stageLocked,
                        isDragging = isDragging,
                        onSelectBlock = onSelectBlock,
                        onToggleBypass = onToggleBypass,
                        onOpenFocus = onOpenFocus,
                        onRequestInsertAtSlot = onRequestInsertAtSlot,
                        onSplitClick = onCycleSplitMode2,
                        onMergeOrOutClick = onCycleSplitMode2,
                        onDropPayload = onDropDragPayload,
                    )
                }
            }

            // Far Right: Precision Output dB Scale & Meter
            PrecisionDbMeterColumn(
                telemetryFlow = telemetryFlow,
                isInput = false,
                trimDb = outputLevelDb,
            )
        }

        // Bottom Drag-to-Remove Trash Zone (visible ONLY while dragging an existing block)
        if (dragController != null && isMovingExisting) {
            val isTrashHovered = dragController.hoveredTarget == StudioDropTarget.RemoveTrash
            DisposableEffect("trash_zone") {
                onDispose { dragController.unregisterDropZone("trash_zone") }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .onGloballyPositioned { coords ->
                        dragController.registerDropZone(
                            key = "trash_zone",
                            target = StudioDropTarget.RemoveTrash,
                            boundsInRoot = coords.boundsInRoot(),
                        )
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isTrashHovered) StadiumColors.AmpRed else Color(0xDD2A1214))
                    .border(
                        width = if (isTrashHovered) 2.dp else 1.dp,
                        color = if (isTrashHovered) Color.White else StadiumColors.AmpRed,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (isTrashHovered) "Release to Remove Block" else "× Drop here to remove block",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Renders one dual-branch Helix Stadium Native path row (Main line + curved Parallel sub-branch)
 * with continuous background wires and rounded 90° Split / Merge elbows matching jpeg.jpeg.
 */
@Composable
private fun DualBranchPathRow(
    mainBranch: String,
    parallelBranch: String,
    leftNodeKind: String,
    rightMainNodeKind: String,
    rightParallelNodeKind: String?,
    preBlocks: List<RigBlockState>,
    midBlocks: List<RigBlockState>,
    postBlocks: List<RigBlockState>,
    parallelBlocks: List<RigBlockState>,
    hasMergeNode: Boolean,
    ampCabLink: Boolean,
    selectedBlockId: String,
    pendingInsertSlot: PendingInsertSlot?,
    blockSize: Dp,
    stageLocked: Boolean,
    isDragging: Boolean,
    onSelectBlock: (String) -> Unit,
    onToggleBypass: (String) -> Unit,
    onOpenFocus: (String) -> Unit,
    onRequestInsertAtSlot: (String, Int) -> Unit,
    onSplitClick: () -> Unit,
    onMergeOrOutClick: () -> Unit,
    onDropPayload: (StudioDragPayload, StudioDropTarget) -> Unit,
) {
    val nodeSize = (blockSize.value * 0.64f).dp
    val rowSpacing = if (blockSize < 38.dp) 4.dp else 8.dp
    val showParallelRow = parallelBlocks.isNotEmpty() || isDragging
    val split1Index = preBlocks.size
    val mergeIndex = preBlocks.size + midBlocks.size

    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    var splitCenter by remember { mutableStateOf<Offset?>(null) }
    var mergeCenter by remember { mutableStateOf<Offset?>(null) }
    var leftCenter by remember { mutableStateOf<Offset?>(null) }
    var rightMainCenter by remember { mutableStateOf<Offset?>(null) }
    var rightParallelCenter by remember { mutableStateOf<Offset?>(null) }
    var parallelRowY by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { containerOrigin = it.positionInRoot() },
    ) {
        // Procedural Crisp Signal Wires + Curved Parallel Elbows (drawn behind nodes)
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val lC = leftCenter
            val rC = rightMainCenter
            if (lC != null && rC != null) {
                val mainY = lC.y
                // Continuous Main horizontal signal wire (Path 1A or Path 2A)
                drawLine(WireColor, Offset(lC.x, mainY), Offset(rC.x, mainY), strokeWidth = stroke.width)

                // Parallel Branch Elbow Wire (Path 1B or Path 2B)
                val sC = splitCenter
                val pY = parallelRowY
                if (showParallelRow && sC != null && pY != null && pY > mainY + 6f) {
                    val cornerR = 10.dp.toPx()
                    val endX = if (hasMergeNode && mergeCenter != null) {
                        mergeCenter!!.x
                    } else if (rightParallelCenter != null) {
                        rightParallelCenter!!.x
                    } else {
                        rC.x
                    }
                    val path = Path().apply {
                        // Drop down from Split circle with smooth 90° elbow
                        moveTo(sC.x, mainY)
                        lineTo(sC.x, pY - cornerR)
                        quadraticTo(sC.x, pY, sC.x + cornerR, pY)
                        if (hasMergeNode && mergeCenter != null) {
                            lineTo(endX - cornerR, pY)
                            quadraticTo(endX, pY, endX, pY - cornerR)
                            lineTo(endX, mainY)
                        } else {
                            lineTo(endX, pY)
                        }
                    }
                    drawPath(path, WireColor, style = stroke)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            // ================= MAIN LINE (1A or 2A) =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left Input / Feed-Down Circular Node
                Box(
                    modifier = Modifier.onGloballyPositioned { coords ->
                        val b = coords.boundsInRoot()
                        leftCenter = Offset(b.center.x - containerOrigin.x, b.center.y - containerOrigin.y)
                    },
                ) {
                    SignalNodeCircleGlyph(kind = leftNodeKind, modifier = Modifier.size(nodeSize))
                }

                // Pre-Split Blocks
                WireInsertSlot(
                    branch = mainBranch,
                    indexInBranch = 0,
                    isDragging = isDragging,
                    isPending = pendingInsertSlot == PendingInsertSlot(mainBranch, 0),
                    stageLocked = stageLocked,
                    onClick = { onRequestInsertAtSlot(mainBranch, 0) },
                )
                preBlocks.forEachIndexed { idx, blk ->
                    SignalFlowSquareBlock(
                        block = blk,
                        selected = blk.id == selectedBlockId,
                        showGoldLink = ampCabLink && (blk.nativeBlockCode == RigBlockCode.AMP || blk.nativeBlockCode == RigBlockCode.CAB),
                        blockSize = blockSize,
                        stageLocked = stageLocked,
                        onSelect = { onSelectBlock(blk.id) },
                        onToggleBypass = { onToggleBypass(blk.id) },
                        onOpenFocus = { onOpenFocus(blk.id) },
                        onDropPayload = onDropPayload,
                    )
                    WireInsertSlot(
                        branch = mainBranch,
                        indexInBranch = idx + 1,
                        isDragging = isDragging,
                        isPending = pendingInsertSlot == PendingInsertSlot(mainBranch, idx + 1),
                        stageLocked = stageLocked,
                        onClick = { onRequestInsertAtSlot(mainBranch, idx + 1) },
                    )
                }

                // Split Circular Node (⊢)
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInRoot()
                            splitCenter = Offset(b.center.x - containerOrigin.x, b.center.y - containerOrigin.y)
                        }
                        .clip(CircleShape)
                        .clickable(enabled = !stageLocked, onClick = onSplitClick),
                ) {
                    SignalNodeCircleGlyph(kind = "SPLIT", modifier = Modifier.size(nodeSize))
                }

                // Mid / Post-Split Blocks (between Split & Merge/End)
                WireInsertSlot(
                    branch = mainBranch,
                    indexInBranch = split1Index,
                    isDragging = isDragging,
                    isPending = pendingInsertSlot == PendingInsertSlot(mainBranch, split1Index) && preBlocks.isEmpty(),
                    stageLocked = stageLocked,
                    slotKeySuffix = "post_split",
                    onClick = { onRequestInsertAtSlot(mainBranch, split1Index) },
                )
                midBlocks.forEachIndexed { localIdx, blk ->
                    val globalIdx = split1Index + localIdx
                    SignalFlowSquareBlock(
                        block = blk,
                        selected = blk.id == selectedBlockId,
                        showGoldLink = ampCabLink && (blk.nativeBlockCode == RigBlockCode.AMP || blk.nativeBlockCode == RigBlockCode.CAB),
                        blockSize = blockSize,
                        stageLocked = stageLocked,
                        onSelect = { onSelectBlock(blk.id) },
                        onToggleBypass = { onToggleBypass(blk.id) },
                        onOpenFocus = { onOpenFocus(blk.id) },
                        onDropPayload = onDropPayload,
                    )
                    WireInsertSlot(
                        branch = mainBranch,
                        indexInBranch = globalIdx + 1,
                        isDragging = isDragging,
                        isPending = pendingInsertSlot == PendingInsertSlot(mainBranch, globalIdx + 1),
                        stageLocked = stageLocked,
                        onClick = { onRequestInsertAtSlot(mainBranch, globalIdx + 1) },
                    )
                }

                // Optional Merge Circular Node (⊣) + Post-Merge Blocks (Path 2A style!)
                if (hasMergeNode) {
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInRoot()
                                mergeCenter = Offset(b.center.x - containerOrigin.x, b.center.y - containerOrigin.y)
                            }
                            .clip(CircleShape)
                            .clickable(enabled = !stageLocked, onClick = onMergeOrOutClick),
                    ) {
                        SignalNodeCircleGlyph(kind = "MERGE", modifier = Modifier.size(nodeSize))
                    }

                    postBlocks.forEachIndexed { localIdx, blk ->
                        val globalIdx = mergeIndex + localIdx
                        WireInsertSlot(
                            branch = mainBranch,
                            indexInBranch = globalIdx,
                            isDragging = isDragging,
                            isPending = pendingInsertSlot == PendingInsertSlot(mainBranch, globalIdx),
                            stageLocked = stageLocked,
                            slotKeySuffix = "post_merge_$localIdx",
                            onClick = { onRequestInsertAtSlot(mainBranch, globalIdx) },
                        )
                        SignalFlowSquareBlock(
                            block = blk,
                            selected = blk.id == selectedBlockId,
                            showGoldLink = ampCabLink && (blk.nativeBlockCode == RigBlockCode.AMP || blk.nativeBlockCode == RigBlockCode.CAB),
                            blockSize = blockSize,
                            stageLocked = stageLocked,
                            onSelect = { onSelectBlock(blk.id) },
                            onToggleBypass = { onToggleBypass(blk.id) },
                            onOpenFocus = { onOpenFocus(blk.id) },
                            onDropPayload = onDropPayload,
                        )
                    }
                    WireInsertSlot(
                        branch = mainBranch,
                        indexInBranch = preBlocks.size + midBlocks.size + postBlocks.size,
                        isDragging = isDragging,
                        isPending = pendingInsertSlot == PendingInsertSlot(mainBranch, preBlocks.size + midBlocks.size + postBlocks.size),
                        stageLocked = stageLocked,
                        slotKeySuffix = "end",
                        onClick = { onRequestInsertAtSlot(mainBranch, preBlocks.size + midBlocks.size + postBlocks.size) },
                    )
                }

                // Right Main Output / Down-Feed Circular Node
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInRoot()
                            rightMainCenter = Offset(b.center.x - containerOrigin.x, b.center.y - containerOrigin.y)
                        }
                        .clip(CircleShape)
                        .clickable(enabled = !stageLocked && mainBranch == "1A", onClick = onMergeOrOutClick),
                ) {
                    SignalNodeCircleGlyph(kind = rightMainNodeKind, modifier = Modifier.size(nodeSize))
                }
            }

            // ================= PARALLEL SUB-BRANCH (1B or 2B) =================
            if (showParallelRow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInRoot()
                            parallelRowY = b.center.y - containerOrigin.y
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Align parallel blocks neatly beneath the Split-to-Merge/Out section
                    Spacer(Modifier.weight(if (hasMergeNode) 0.56f else 0.58f))

                    Row(
                        modifier = Modifier.weight(if (hasMergeNode) 0.24f else 0.42f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        WireInsertSlot(
                            branch = parallelBranch,
                            indexInBranch = 0,
                            isDragging = isDragging,
                            isPending = pendingInsertSlot == PendingInsertSlot(parallelBranch, 0),
                            stageLocked = stageLocked,
                            showSubtlePlusWhenEmpty = parallelBlocks.isEmpty(),
                            onClick = { onRequestInsertAtSlot(parallelBranch, 0) },
                        )
                        parallelBlocks.forEachIndexed { idx, blk ->
                            SignalFlowSquareBlock(
                                block = blk,
                                selected = blk.id == selectedBlockId,
                                showGoldLink = false,
                                blockSize = blockSize,
                                stageLocked = stageLocked,
                                onSelect = { onSelectBlock(blk.id) },
                                onToggleBypass = { onToggleBypass(blk.id) },
                                onOpenFocus = { onOpenFocus(blk.id) },
                                onDropPayload = onDropPayload,
                            )
                            WireInsertSlot(
                                branch = parallelBranch,
                                indexInBranch = idx + 1,
                                isDragging = isDragging,
                                isPending = pendingInsertSlot == PendingInsertSlot(parallelBranch, idx + 1),
                                stageLocked = stageLocked,
                                onClick = { onRequestInsertAtSlot(parallelBranch, idx + 1) },
                            )
                        }

                        if (rightParallelNodeKind != null) {
                            Box(
                                modifier = Modifier
                                    .onGloballyPositioned { coords ->
                                        val b = coords.boundsInRoot()
                                        rightParallelCenter = Offset(b.center.x - containerOrigin.x, b.center.y - containerOrigin.y)
                                    },
                            ) {
                                SignalNodeCircleGlyph(kind = rightParallelNodeKind, modifier = Modifier.size(nodeSize))
                            }
                        }
                    }

                    if (hasMergeNode) {
                        Spacer(Modifier.weight(0.20f))
                    }
                }
            }
        }
    }
}

/**
 * 1:1 Helix Stadium Native 44dp square icon-only Signal Flow block with:
 * - Crisp colored frame + thick white selection ring (matching Amp in jpeg.jpeg)
 * - Tiny Gold LINK chain beneath linked Amp & Cab
 * - Tap = Select & open Model Browser | Double-Tap = Toggle Bypass | Long-Press Drag = Move across paths!
 */
@Composable
private fun SignalFlowSquareBlock(
    block: RigBlockState,
    selected: Boolean,
    showGoldLink: Boolean,
    blockSize: Dp,
    stageLocked: Boolean,
    onSelect: () -> Unit,
    onToggleBypass: () -> Unit,
    onOpenFocus: () -> Unit,
    onDropPayload: (StudioDragPayload, StudioDropTarget) -> Unit,
) {
    val dragController = LocalStudioDragDropController.current
    val catColor = block.category.composeColor()
    val isBeingDragged = (dragController?.activePayload as? StudioDragPayload.MoveExistingBlock)?.blockId == block.id
    val isDropHovered = (dragController?.hoveredTarget as? StudioDropTarget.ReplaceBlock)?.blockId == block.id

    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    val latestBlock by rememberUpdatedState(block)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnToggleBypass by rememberUpdatedState(onToggleBypass)
    val latestOnDrop by rememberUpdatedState(onDropPayload)

    val zoneKey = "block_${block.id}"
    DisposableEffect(zoneKey) {
        onDispose { dragController?.unregisterDropZone(zoneKey) }
    }

    val borderColor = when {
        isDropHovered -> StadiumColors.BadgeCyan
        selected -> Color.White
        block.enabled -> catColor.copy(alpha = 0.72f)
        else -> Color(0xFF3A3D42)
    }
    val borderWidth = if (selected || isDropHovered) 2.2.dp else 1.2.dp
    val bgColor = when {
        isDropHovered -> StadiumColors.BadgeCyan.copy(alpha = 0.22f)
        selected -> catColor.copy(alpha = 0.16f)
        block.enabled -> Color(0xFF0E0F12)
        else -> Color(0xFF08080A)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(blockSize)
                .alpha(if (isBeingDragged) 0.35f else if (block.enabled) 1f else 0.45f)
                .onGloballyPositioned { coords ->
                    originInRoot = coords.positionInRoot()
                    dragController?.registerDropZone(
                        key = zoneKey,
                        target = StudioDropTarget.ReplaceBlock(block.id),
                        boundsInRoot = coords.boundsInRoot(),
                    )
                }
                .clip(RoundedCornerShape(6.dp))
                .background(bgColor)
                .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
                .pointerInput(block.id, stageLocked) {
                    detectTapGestures(
                        onTap = { latestOnSelect() },
                        onDoubleTap = { if (!stageLocked) latestOnToggleBypass() },
                    )
                }
                .pointerInput(block.id, stageLocked) {
                    if (stageLocked || dragController == null) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startLocal ->
                            val b = latestBlock
                            dragController.startDrag(
                                payload = StudioDragPayload.MoveExistingBlock(
                                    blockId = b.id,
                                    category = b.category,
                                    nativeBlockCode = b.nativeBlockCode,
                                    modelId = b.modelId,
                                    shortLabel = b.shortLabel,
                                ),
                                startRootOffset = originInRoot + startLocal,
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragController.updateDrag(originInRoot + change.position)
                        },
                        onDragEnd = { dragController.endDrag(latestOnDrop) },
                        onDragCancel = { dragController.cancelDrag() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            CategoryBlockGlyph(
                nativeBlockCode = block.nativeBlockCode,
                category = block.category,
                tint = if (block.enabled) catColor else Color(0xFF64686F),
                modelId = block.modelId,
                modifier = Modifier.size((blockSize.value * 0.62f).dp),
            )

            // Micro instance "2" indicator ONLY when a 2nd instance of the same family is stacked
            if (block.instanceIndex > 0) {
                Text(
                    text = "2",
                    color = catColor.copy(alpha = 0.85f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 3.dp, top = 1.dp),
                )
            }
        }

        if (showGoldLink) {
            GoldChainLinkGlyph(linked = true)
        }
    }
}

/**
 * Interactive insertion & drop target along the horizontal signal wire.
 * Ultra-subtle when idle so the signal flow stays 100% pure like jpeg.jpeg;
 * lights up vividly as a `+` target when dragging any block or model card, or when tapped!
 */
@Composable
private fun WireInsertSlot(
    branch: String,
    indexInBranch: Int,
    isDragging: Boolean,
    isPending: Boolean,
    stageLocked: Boolean,
    slotKeySuffix: String = "",
    showSubtlePlusWhenEmpty: Boolean = false,
    onClick: () -> Unit,
) {
    val dragController = LocalStudioDragDropController.current
    val target = StudioDropTarget.InsertAtSlot(branch, indexInBranch)
    val isHovered = dragController?.hoveredTarget == target
    val zoneKey = "slot_${branch}_${indexInBranch}_$slotKeySuffix"

    DisposableEffect(zoneKey) {
        onDispose { dragController?.unregisterDropZone(zoneKey) }
    }

    val visibleDot = isDragging || isPending || showSubtlePlusWhenEmpty
    val slotSize = if (isHovered) 24.dp else if (isDragging || isPending) 18.dp else 14.dp

    Box(
        modifier = Modifier
            .size(width = if (isDragging || isPending) 22.dp else 14.dp, height = 34.dp)
            .onGloballyPositioned { coords ->
                dragController?.registerDropZone(
                    key = zoneKey,
                    target = target,
                    boundsInRoot = coords.boundsInRoot(),
                )
            }
            .clickable(enabled = !stageLocked, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (visibleDot) {
            Box(
                modifier = Modifier
                    .size(slotSize)
                    .clip(CircleShape)
                    .background(
                        when {
                            isHovered -> StadiumColors.DelayEmerald
                            isPending -> StadiumColors.BadgeCyan.copy(alpha = 0.35f)
                            else -> Color(0xCC181B20)
                        }
                    )
                    .border(
                        width = if (isHovered || isPending) 1.6.dp else 1.dp,
                        color = when {
                            isHovered -> Color.White
                            isPending -> StadiumColors.BadgeCyan
                            else -> Color(0xFF666C75)
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    color = if (isHovered) Color.Black else Color.White,
                    fontSize = if (isHovered) 13.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}
