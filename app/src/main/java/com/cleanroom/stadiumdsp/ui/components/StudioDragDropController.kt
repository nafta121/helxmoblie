package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import com.cleanroom.stadiumdsp.ui.theme.composeColor
import kotlin.math.roundToInt

/**
 * Payload carried during a cross-panel Drag & Drop gesture:
 * - Moving an existing block across Path 1A / 1B / 2A / 2B (or onto Remove Trash)
 * - Adding a brand-new model directly from the left Model Selection Module onto any Signal Flow slot
 */
sealed class StudioDragPayload {
    abstract val category: BlockCategory
    abstract val nativeBlockCode: Int
    abstract val modelId: Int
    abstract val shortLabel: String

    data class MoveExistingBlock(
        val blockId: String,
        override val category: BlockCategory,
        override val nativeBlockCode: Int,
        override val modelId: Int,
        override val shortLabel: String,
    ) : StudioDragPayload()

    data class AddNewModel(
        override val category: BlockCategory,
        override val nativeBlockCode: Int,
        override val modelId: Int,
        val displayName: String,
        override val shortLabel: String,
        val isPreamp: Boolean = false,
    ) : StudioDragPayload()
}

sealed class StudioDropTarget {
    data class InsertAtSlot(val branch: String, val indexInBranch: Int) : StudioDropTarget()
    data class ReplaceBlock(val blockId: String) : StudioDropTarget()
    data object RemoveTrash : StudioDropTarget()
}

class StudioDragDropController {
    var activePayload by mutableStateOf<StudioDragPayload?>(null)
        private set
    var pointerInRoot by mutableStateOf(Offset.Zero)
        private set
    var hoveredTarget by mutableStateOf<StudioDropTarget?>(null)
        private set

    val isDragging: Boolean get() = activePayload != null

    private data class RegisteredZone(
        val target: StudioDropTarget,
        val boundsInRoot: Rect,
    )

    private val dropZones = linkedMapOf<String, RegisteredZone>()

    fun registerDropZone(key: String, target: StudioDropTarget, boundsInRoot: Rect) {
        dropZones[key] = RegisteredZone(target, boundsInRoot)
    }

    fun unregisterDropZone(key: String) {
        dropZones.remove(key)
    }

    fun startDrag(payload: StudioDragPayload, startRootOffset: Offset) {
        activePayload = payload
        pointerInRoot = startRootOffset
        hoveredTarget = resolveHitTarget(startRootOffset, payload)
    }

    fun updateDrag(rootOffset: Offset) {
        val payload = activePayload ?: return
        pointerInRoot = rootOffset
        hoveredTarget = resolveHitTarget(rootOffset, payload)
    }

    fun endDrag(onDrop: (StudioDragPayload, StudioDropTarget) -> Unit) {
        val payload = activePayload
        val target = hoveredTarget
        activePayload = null
        hoveredTarget = null
        if (payload != null && target != null) {
            onDrop(payload, target)
        }
    }

    fun cancelDrag() {
        activePayload = null
        hoveredTarget = null
    }

    private fun resolveHitTarget(pt: Offset, payload: StudioDragPayload): StudioDropTarget? {
        val margin = 16f
        // 1. Highest priority: Remove Trash (only when moving an existing block)
        if (payload is StudioDragPayload.MoveExistingBlock) {
            dropZones.values.firstOrNull {
                it.target is StudioDropTarget.RemoveTrash && it.boundsInRoot.inflate(margin).contains(pt)
            }?.let { return it.target }
        }
        // 2. Direct drop on an existing block (Replace when adding from browser, or swap/insert before when moving)
        dropZones.values.firstOrNull { zone ->
            val t = zone.target
            t is StudioDropTarget.ReplaceBlock &&
                !(payload is StudioDragPayload.MoveExistingBlock && payload.blockId == t.blockId) &&
                zone.boundsInRoot.inflate(8f).contains(pt)
        }?.let { return it.target }

        // 3. Nearest InsertAtSlot within generous hit radius
        var bestTarget: StudioDropTarget? = null
        var bestDistSq = Float.MAX_VALUE
        for (zone in dropZones.values) {
            if (zone.target !is StudioDropTarget.InsertAtSlot) continue
            val inflated = zone.boundsInRoot.inflate(28f)
            if (inflated.contains(pt)) {
                val c = zone.boundsInRoot.center
                val dx = pt.x - c.x
                val dy = pt.y - c.y
                val dSq = dx * dx + dy * dy
                if (dSq < bestDistSq) {
                    bestDistSq = dSq
                    bestTarget = zone.target
                }
            }
        }
        return bestTarget
    }
}

/**
 * Plain non-snapshot coordinate holder used inside onGloballyPositioned so layout passes
 * never trigger unnecessary Compose recompositions.
 */
class OffsetHolder(var value: Offset = Offset.Zero)

val LocalStudioDragDropController = compositionLocalOf<StudioDragDropController?> { null }

/**
 * Top-level drag & drop host wrapping the Signal Flow Canvas + bottom Model Browser & Inspector.
 * Renders the floating ghost badge right under the finger across both panels!
 */
@Composable
fun StudioDragDropHost(
    controller: StudioDragDropController = remember { StudioDragDropController() },
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hostOriginInRoot = remember { OffsetHolder() }
    val density = LocalDensity.current

    CompositionLocalProvider(LocalStudioDragDropController provides controller) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    hostOriginInRoot.value = coords.positionInRoot()
                },
        ) {
            content()

            val payload = controller.activePayload
            if (payload != null) {
                val catColor = payload.category.composeColor()
                val localOffset = controller.pointerInRoot - hostOriginInRoot.value
                val ghostWidthPx = with(density) { 104.dp.toPx() }
                val ghostHeightPx = with(density) { 54.dp.toPx() }
                val targetLabel = when (val t = controller.hoveredTarget) {
                    is StudioDropTarget.InsertAtSlot -> "Drop -> Path ${t.branch}"
                    is StudioDropTarget.ReplaceBlock -> if (payload is StudioDragPayload.AddNewModel) "Replace Block" else "Swap Position"
                    StudioDropTarget.RemoveTrash -> "Release to Remove"
                    null -> "Drag to Signal Path"
                }
                val borderCol = when (controller.hoveredTarget) {
                    StudioDropTarget.RemoveTrash -> StadiumColors.AmpRed
                    null -> catColor
                    else -> Color.White
                }

                Box(
                    modifier = Modifier
                        .zIndex(99f)
                        .offset {
                            IntOffset(
                                x = (localOffset.x - ghostWidthPx * 0.5f).roundToInt(),
                                y = (localOffset.y - ghostHeightPx * 1.15f).roundToInt(),
                            )
                        }
                        .shadow(14.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xEE141418))
                        .border(2.dp, borderCol, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CategoryBlockGlyph(
                            nativeBlockCode = payload.nativeBlockCode,
                            category = payload.category,
                            tint = catColor,
                            modelId = payload.modelId,
                            isPreamp = (payload as? StudioDragPayload.AddNewModel)?.isPreamp == true,
                            modifier = Modifier.size(22.dp),
                        )
                        Column {
                            Text(
                                text = payload.shortLabel,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                text = targetLabel,
                                color = if (controller.hoveredTarget == StudioDropTarget.RemoveTrash) StadiumColors.AmpRed else Color(0xFFB5BDC4),
                                fontSize = 9.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
