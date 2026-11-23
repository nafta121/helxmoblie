package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanroom.stadiumdsp.RigBlockCode
import com.cleanroom.stadiumdsp.catalog.AmpEngineTier
import com.cleanroom.stadiumdsp.catalog.AmpModelCatalog
import com.cleanroom.stadiumdsp.catalog.FactoryCabCatalog
import com.cleanroom.stadiumdsp.catalog.FxModelCatalog
import com.cleanroom.stadiumdsp.catalog.ParameterSchema
import com.cleanroom.stadiumdsp.catalog.ParameterSchemaRegistry
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.ui.state.BrowserCategoryTab
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import com.cleanroom.stadiumdsp.ui.theme.StadiumIcons
import com.cleanroom.stadiumdsp.ui.theme.composeColor

private data class BrowserModelEntry(
    val id: String,
    val nativeId: Int,
    val category: BlockCategory,
    val nativeBlockCode: Int,
    val displayName: String,
    val shortLabel: String,
    val basedOn: String,
    val badge: String?,
    val isPreamp: Boolean = false,
)

@Composable
fun ModelBrowserAndInspector(
    state: StadiumUiState,
    showBrowserPane: Boolean,
    isInspectorExpanded: Boolean,
    onToggleInspectorExpanded: () -> Unit,
    onToggleFocusView: () -> Unit,
    onToggleBypass: (String) -> Unit,
    onToggleStereo: (String) -> Unit,
    onSelectAmpModel: (Int) -> Unit,
    onSelectCabModel: (Int) -> Unit,
    onSelectFxModel: (String, Int, String, String) -> Unit,
    onUpdateParam: (String, String, Float) -> Unit,
    onToggleSnapshotControl: (String) -> Unit,
    onArmMidiLearn: (String) -> Unit,
    onImportExternalAsset: () -> Unit,
    onSelectBrowserTab: (BrowserCategoryTab) -> Unit = {},
    onDismissModelBrowser: () -> Unit = {},
    onToggleModelBrowser: () -> Unit = onToggleInspectorExpanded,
    onRemoveSelectedBlock: () -> Unit = {},
    onApplyModelEntry: (BlockCategory, Int, Int, String, String, Boolean) -> Unit = { cat, _, nid, dn, sl, _ ->
        when (cat) {
            BlockCategory.AMP -> onSelectAmpModel(nid)
            BlockCategory.CAB -> onSelectCabModel(nid)
            else -> onSelectFxModel(state.selectedBlockId, nid, dn, sl)
        }
    },
    onDropDragPayload: (StudioDragPayload, StudioDropTarget) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val browserVisible = showBrowserPane && state.isModelBrowserOpen && !isInspectorExpanded

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF060608)),
    ) {
        AnimatedVisibility(
            visible = browserVisible,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            modifier = Modifier.fillMaxHeight(),
        ) {
            ModelBrowserPane(
                state = state,
                onSelectBrowserTab = onSelectBrowserTab,
                onRemoveSelectedBlock = onRemoveSelectedBlock,
                onApplyModelEntry = onApplyModelEntry,
                onImportExternalAsset = onImportExternalAsset,
                onDropDragPayload = onDropDragPayload,
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight(),
            )
        }

        ParameterInspectorPane(
            state = state,
            isExpanded = !browserVisible,
            onToggleBrowser = onToggleModelBrowser,
            onDismissModelBrowser = onDismissModelBrowser,
            onToggleFocusView = onToggleFocusView,
            onToggleBypass = onToggleBypass,
            onToggleStereo = onToggleStereo,
            onUpdateParam = onUpdateParam,
            onToggleSnapshotControl = onToggleSnapshotControl,
            onArmMidiLearn = onArmMidiLearn,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

/**
 * 1:1 Helix Stadium Native 2-Column Model Selection Module:
 * - Left Rail (~108dp): Search, × None, ★ Favorites, 12 tinted category rows (Amp, Preamp, Cab, Distortion, Delay, Reverb, Modulation, Dynamics, EQ, Pitch/Synth, Wah/Filter, Volume/Pan)
 * - Right Area: Filter & List/Grid toolbar, category-tinted header banner ("GUITAR AMP"), and 2-column procedural hardware cards with BOTH Tap-to-Apply and Long-Press Drag-to-Canvas!
 */
@Composable
fun ModelBrowserPane(
    state: StadiumUiState,
    onSelectAmpModel: (Int) -> Unit = {},
    onSelectCabModel: (Int) -> Unit = {},
    onSelectFxModel: (String, Int, String, String) -> Unit = { _, _, _, _ -> },
    onImportExternalAsset: () -> Unit,
    onSelectBrowserTab: (BrowserCategoryTab) -> Unit = {},
    onRemoveSelectedBlock: () -> Unit = {},
    onApplyModelEntry: (BlockCategory, Int, Int, String, String, Boolean) -> Unit = { cat, _, nid, dn, sl, _ ->
        when (cat) {
            BlockCategory.AMP -> onSelectAmpModel(nid)
            BlockCategory.CAB -> onSelectCabModel(nid)
            else -> onSelectFxModel(state.selectedBlockId, nid, dn, sl)
        }
    },
    onDropDragPayload: (StudioDragPayload, StudioDropTarget) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var bassFilter by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var isGridView by rememberSaveable { mutableStateOf(true) }

    val activeTab = state.activeBrowserTab
    val accentColor = Color(activeTab.colorHex)
    val selectedBlock = state.selectedBlockOrNull
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    val entries: List<BrowserModelEntry> = remember(activeTab, searchQuery, bassFilter) {
        buildBrowserEntries(activeTab, searchQuery, bassFilter)
    }

    Row(
        modifier = modifier
            .background(Color(0xFF0E0E11))
            .border(1.dp, Color(0xFF222226)),
    ) {
        // ================= LEFT NARROW CATEGORY RAIL (~106dp) =================
        Column(
            modifier = Modifier
                .width(106.dp)
                .fillMaxHeight()
                .background(Color(0xFF0A0A0D))
                .border(width = 1.dp, color = Color(0xFF1E1E22)),
        ) {
            // Search Box (top of category rail, matching jpeg.jpeg!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF050507))
                    .border(1.dp, Color(0xFF2B2B30), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF777A80), modifier = Modifier.size(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text("Search", color = Color(0xFF6B6E75), fontSize = 10.sp, maxLines = 1)
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
                        cursorBrush = SolidColor(accentColor),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Scrollable Category List: × None, ★ Favorites, 12 tinted categories
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 6.dp),
            ) {
                // × None (Remove currently selected block)
                item(key = "cat_none") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clickable(enabled = !state.stageLock && selectedBlock != null, onClick = onRemoveSelectedBlock)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            imageVector = StadiumIcons.CloseX,
                            contentDescription = "Remove Block",
                            tint = Color(0xFF9A9EA6),
                            modifier = Modifier.size(13.dp),
                        )
                        Text("None", color = Color(0xFFD0D4DA), fontSize = 11.sp, maxLines = 1)
                    }
                }

                items(
                    items = BrowserCategoryTab.entries,
                    key = { it.name },
                ) { tab ->
                    val isSelected = tab == activeTab
                    val rowColor = Color(tab.colorHex)
                    val rowBrush = if (isSelected) {
                        Brush.horizontalGradient(
                            listOf(rowColor.copy(alpha = 0.42f), rowColor.copy(alpha = 0.14f))
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(rowColor.copy(alpha = 0.08f), Color.Transparent)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(rowBrush)
                            .clickable { onSelectBrowserTab(tab) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (tab == BrowserCategoryTab.FAVORITES) {
                            Icon(
                                imageVector = StadiumIcons.Star,
                                contentDescription = "Favorites",
                                tint = if (isSelected) Color.White else Color(0xFFCCCCCC),
                                modifier = Modifier.size(13.dp),
                            )
                        } else {
                            val code = tabToNativeCode(tab)
                            CategoryBlockGlyph(
                                nativeBlockCode = code,
                                category = tab.blockCategory ?: BlockCategory.AMP,
                                tint = rowColor,
                                isPreamp = tab == BrowserCategoryTab.PREAMP,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = tab.label,
                            color = if (isSelected) Color.White else rowColor.copy(alpha = 0.90f),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ================= RIGHT MODEL GRID / LIST COLUMN =================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF121215)),
        ) {
            // Sub-filter & View Mode Icon Toolbar (matches jpeg.jpeg!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Color(0xFF0C0C0E))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left sub-category filters (All / Guitar / Bass for Amp/Preamp, or SAF badge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (activeTab == BrowserCategoryTab.AMP || activeTab == BrowserCategoryTab.PREAMP) {
                        MiniToolbarPill("All", bassFilter == null, accentColor) { bassFilter = null }
                        MiniToolbarPill("Gtr", bassFilter == false, accentColor) { bassFilter = false }
                        MiniToolbarPill("Bass", bassFilter == true, accentColor) { bassFilter = true }
                    } else {
                        MiniToolbarPill("${entries.size}", true, accentColor) {}
                    }

                    // ••• / SAF Import (.nam / .aidax / .wav IR)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .clickable(onClick = onImportExternalAsset)
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    ) {
                        Text("SAF", color = StadiumColors.CabPink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Right: List / Grid Toggle (exact match to jpeg.jpeg!)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (!isGridView) Color(0xFF2B2B32) else Color.Transparent)
                            .clickable { isGridView = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(StadiumIcons.ListView, contentDescription = "List View", tint = if (!isGridView) Color.White else Color(0xFF7D8088), modifier = Modifier.size(14.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isGridView) Color(0xFF2B2B32) else Color.Transparent)
                            .clickable { isGridView = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(StadiumIcons.GridView, contentDescription = "Grid View", tint = if (isGridView) Color.White else Color(0xFF7D8088), modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Category-Tinted Section Header Banner (`[AmpIcon] GUITAR AMP`, dark crimson tint matching jpeg.jpeg!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accentColor.copy(alpha = 0.30f), accentColor.copy(alpha = 0.14f))
                        )
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (activeTab != BrowserCategoryTab.FAVORITES) {
                        CategoryBlockGlyph(
                            nativeBlockCode = tabToNativeCode(activeTab),
                            category = activeTab.blockCategory ?: BlockCategory.AMP,
                            tint = Color.White.copy(alpha = 0.85f),
                            isPreamp = activeTab == BrowserCategoryTab.PREAMP,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Text(
                        text = state.pendingInsertSlot?.let { "+ ADD TO PATH ${it.branch}" } ?: activeTab.bannerTitle,
                        color = Color(0xFFE6E9EE),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                    )
                }

                Text(
                    text = "Hold & Drag",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 8.5.sp,
                )
            }

            // 2-Column Hardware Visual Grid OR Compact List
            if (isGridView) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(
                        items = entries,
                        key = { it.id },
                        contentType = { "hardware_model_card" },
                    ) { entry ->
                        val isLoaded = selectedBlock != null &&
                            selectedBlock.nativeBlockCode == entry.nativeBlockCode &&
                            selectedBlock.modelId == entry.nativeId &&
                            state.pendingInsertSlot == null

                        HardwareModelGridCard(
                            entry = entry,
                            accentColor = Color(entry.category.colorHex),
                            isLoaded = isLoaded,
                            stageLocked = state.stageLock,
                            onTap = {
                                onApplyModelEntry(
                                    entry.category,
                                    entry.nativeBlockCode,
                                    entry.nativeId,
                                    entry.displayName,
                                    entry.shortLabel,
                                    entry.isPreamp,
                                )
                            },
                            onDropPayload = onDropDragPayload,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(
                        items = entries,
                        key = { it.id },
                        contentType = { "hardware_model_row" },
                    ) { entry ->
                        val isLoaded = selectedBlock != null &&
                            selectedBlock.nativeBlockCode == entry.nativeBlockCode &&
                            selectedBlock.modelId == entry.nativeId &&
                            state.pendingInsertSlot == null
                        HardwareModelListRow(
                            entry = entry,
                            accentColor = Color(entry.category.colorHex),
                            isLoaded = isLoaded,
                            stageLocked = state.stageLock,
                            onTap = {
                                onApplyModelEntry(
                                    entry.category,
                                    entry.nativeBlockCode,
                                    entry.nativeId,
                                    entry.displayName,
                                    entry.shortLabel,
                                    entry.isPreamp,
                                )
                            },
                            onDropPayload = onDropDragPayload,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1:1 Helix Stadium Native Hardware Thumbnail Card with BOTH Tap-to-Apply and Long-Press Drag-to-Canvas!
 */
@Composable
private fun HardwareModelGridCard(
    entry: BrowserModelEntry,
    accentColor: Color,
    isLoaded: Boolean,
    stageLocked: Boolean,
    onTap: () -> Unit,
    onDropPayload: (StudioDragPayload, StudioDropTarget) -> Unit,
) {
    val dragController = LocalStudioDragDropController.current
    val cardOriginInRoot = remember { OffsetHolder() }
    val latestEntry by rememberUpdatedState(entry)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnDrop by rememberUpdatedState(onDropPayload)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { cardOriginInRoot.value = it.positionInRoot() }
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (isLoaded) accentColor.copy(alpha = 0.34f) else Color(0xFF19191D)
            )
            .border(
                width = if (isLoaded) 1.5.dp else 1.dp,
                color = if (isLoaded) accentColor.copy(alpha = 0.85f) else Color(0xFF25252B),
                shape = RoundedCornerShape(7.dp),
            )
            .pointerInput(entry.id, stageLocked) {
                if (stageLocked) return@pointerInput
                detectTapGestures(onTap = { latestOnTap() })
            }
            .pointerInput(entry.id, stageLocked) {
                if (stageLocked || dragController == null) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { startLocal ->
                        val e = latestEntry
                        dragController.startDrag(
                            payload = StudioDragPayload.AddNewModel(
                                category = e.category,
                                nativeBlockCode = e.nativeBlockCode,
                                modelId = e.nativeId,
                                displayName = e.displayName,
                                shortLabel = e.shortLabel,
                                isPreamp = e.isPreamp,
                            ),
                            startRootOffset = cardOriginInRoot.value + startLocal,
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragController.updateDrag(cardOriginInRoot.value + change.position)
                    },
                    onDragEnd = { dragController.endDrag(latestOnDrop) },
                    onDragCancel = { dragController.cancelDrag() },
                )
            }
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ProceduralHardwareThumbnail(
            category = entry.category,
            nativeBlockCode = entry.nativeBlockCode,
            modelId = entry.nativeId,
            isPreamp = entry.isPreamp,
            accentColor = accentColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )

        Text(
            text = entry.displayName,
            color = if (isLoaded) Color.White else Color(0xFFD8DCE2),
            fontSize = 10.5.sp,
            fontWeight = if (isLoaded) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HardwareModelListRow(
    entry: BrowserModelEntry,
    accentColor: Color,
    isLoaded: Boolean,
    stageLocked: Boolean,
    onTap: () -> Unit,
    onDropPayload: (StudioDragPayload, StudioDropTarget) -> Unit,
) {
    val dragController = LocalStudioDragDropController.current
    val rowOriginInRoot = remember { OffsetHolder() }
    val latestEntry by rememberUpdatedState(entry)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnDrop by rememberUpdatedState(onDropPayload)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowOriginInRoot.value = it.positionInRoot() }
            .clip(RoundedCornerShape(5.dp))
            .background(if (isLoaded) accentColor.copy(alpha = 0.28f) else Color(0xFF19191D))
            .border(1.dp, if (isLoaded) accentColor else Color(0xFF232328), RoundedCornerShape(5.dp))
            .pointerInput(entry.id, stageLocked) {
                if (!stageLocked) detectTapGestures(onTap = { latestOnTap() })
            }
            .pointerInput(entry.id, stageLocked) {
                if (stageLocked || dragController == null) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { startLocal ->
                        val e = latestEntry
                        dragController.startDrag(
                            payload = StudioDragPayload.AddNewModel(
                                category = e.category,
                                nativeBlockCode = e.nativeBlockCode,
                                modelId = e.nativeId,
                                displayName = e.displayName,
                                shortLabel = e.shortLabel,
                                isPreamp = e.isPreamp,
                            ),
                            startRootOffset = rowOriginInRoot.value + startLocal,
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragController.updateDrag(rowOriginInRoot.value + change.position)
                    },
                    onDragEnd = { dragController.endDrag(latestOnDrop) },
                    onDragCancel = { dragController.cancelDrag() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                color = if (isLoaded) Color.White else StadiumColors.TextPrimary,
                fontSize = 11.5.sp,
                fontWeight = if (isLoaded) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.basedOn,
                color = StadiumColors.TextMuted,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        entry.badge?.let { b ->
            Text(
                text = b,
                color = accentColor,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun MiniToolbarPill(label: String, selected: Boolean, accentColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accentColor.copy(alpha = 0.28f) else Color(0xFF1A1A1F))
            .border(1.dp, if (selected) accentColor.copy(alpha = 0.7f) else Color(0xFF28282E), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(label, color = if (selected) Color.White else StadiumColors.TextSecondary, fontSize = 9.sp)
    }
}

/**
 * 1:1 Helix Stadium Native Parameter Inspector:
 * - Category-tinted gradient header (`Focus Iris` left | `[Icon] Category ModelName` center | `Sliders / Stereo / Power ⏻` right)
 * - 2-column sleek flat tinted value bars with crisp vertical indicator needle & `▲▼` fine steppers
 * - Expands to 100% width smoothly whenever Model Browser auto-closes!
 */
@Composable
private fun ParameterInspectorPane(
    state: StadiumUiState,
    isExpanded: Boolean,
    onToggleBrowser: () -> Unit,
    onDismissModelBrowser: () -> Unit,
    onToggleFocusView: () -> Unit,
    onToggleBypass: (String) -> Unit,
    onToggleStereo: (String) -> Unit,
    onUpdateParam: (String, String, Float) -> Unit,
    onToggleSnapshotControl: (String) -> Unit,
    onArmMidiLearn: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val block = state.selectedBlockOrNull
    val catColor = block?.category?.composeColor() ?: StadiumColors.RoutingTitanium
    val gridState = rememberLazyGridState()
    var deepEditParamKey by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .background(Color(0xFF070709))
            .border(1.dp, Color(0xFF1F1F24))
            .pointerInput(Unit) {
                // Tapping empty non-control space in the Inspector auto-closes the Model Browser!
                detectTapGestures(onTap = { onDismissModelBrowser() })
            },
    ) {
        // Category-Tinted Gradient Header (exact match to crimson Amp header in jpeg.jpeg!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(catColor.copy(alpha = 0.36f), catColor.copy(alpha = 0.16f))
                    )
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: Focus Iris Icon (opens 2D Polymorphic Focus View!)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(enabled = block != null, onClick = onToggleFocusView),
                contentAlignment = Alignment.Center,
            ) {
                FocusIrisGlyph(tint = catColor, modifier = Modifier.size(23.dp))
            }

            // Center: `[Icon] Category  ModelName` (click toggles Model Browser!)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleBrowser)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (block != null) {
                    CategoryBlockGlyph(
                        nativeBlockCode = block.nativeBlockCode,
                        category = block.category,
                        tint = catColor,
                        modelId = block.modelId,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = block.category.displayName,
                        color = catColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = block.displayName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "Tap any '+' slot or drag a model to add",
                        color = StadiumColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            // Right: Sliders/Browser Toggle + Stereo/Mono Dial + Power ⏻ Bypass
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = StadiumIcons.Sliders,
                    contentDescription = "Toggle Model Browser",
                    tint = if (state.isModelBrowserOpen) Color.White else Color(0xFF9EA3AC),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onToggleBrowser),
                )

                if (block != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                            .clickable(enabled = !state.stageLock) { onToggleStereo(block.id) }
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (block.isStereo) "ST" else "MN",
                            color = Color(0xFFD0D4DA),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    // Power ⏻ Icon (glows bright red/accent when enabled, exact match to jpeg.jpeg!)
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (block.enabled) catColor.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.35f))
                            .clickable(enabled = !state.stageLock) { onToggleBypass(block.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = StadiumIcons.Power,
                            contentDescription = "Toggle Bypass",
                            tint = if (block.enabled) catColor else Color(0xFF666970),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = catColor.copy(alpha = 0.22f), thickness = 1.dp)

        if (block == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Signal Flow Empty — Open Model Browser or Drag a Block",
                    color = StadiumColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        } else {
            val paramEntries = block.params.entries
                .filter { it.key != "cab.mic_model" }
                .toList()

            // Optional Segmented Pill Row for Amp Channel / Hype Mode (exact match to `Channel: Normal | Top Boost` in jpeg.jpeg!)
            if (block.nativeBlockCode == RigBlockCode.AMP) {
                val hypeVal = block.params["amp.hype"] ?: 0f
                val isHypeActive = hypeVal >= 0.5f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentedParamRow(
                        label = "Voicing",
                        leftLabel = "Stock",
                        rightLabel = "Hype",
                        rightSelected = isHypeActive,
                        accentColor = catColor,
                        stageLocked = state.stageLock,
                        onSelectRight = { right -> onUpdateParam(block.id, "amp.hype", if (right) 0.75f else 0.0f) },
                        modifier = Modifier.weight(1f),
                    )
                    val isHighHeadroom = (block.params["amp.master"] ?: 0.55f) >= 0.65f
                    SegmentedParamRow(
                        label = "Channel",
                        leftLabel = "Normal",
                        rightLabel = "Top Boost",
                        rightSelected = isHighHeadroom,
                        accentColor = catColor,
                        stageLocked = state.stageLock,
                        onSelectRight = { right -> onUpdateParam(block.id, "amp.master", if (right) 0.90f else 0.52f) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 2-Column Helix Stadium Native Flat Value-Bar Grid (3 columns when expanded wide!)
            LazyVerticalGrid(
                state = gridState,
                columns = if (isExpanded) GridCells.Adaptive(minSize = 195.dp) else GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(
                    items = paramEntries,
                    key = { "${block.id}_${it.key}" },
                    contentType = { "helix_flat_bar" },
                ) { (key, value) ->
                    val schema = ParameterSchemaRegistry.get(key)
                        ?: ParameterSchema(key, key.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }, 0f, 1f, value, "", 0.01f, "Circuit parameter")
                    val isSnapControlled = key in state.activePreset.snapshotControlledParams
                    val midiCc = state.learnedMidiMap[key]

                    HelixFlatValueBar(
                        schema = schema,
                        value = value,
                        accentColor = catColor,
                        isSnapshotControlled = isSnapControlled,
                        midiCc = midiCc,
                        stageLocked = state.stageLock,
                        onValueChange = { next -> onUpdateParam(block.id, key, next) },
                        onOpenDeepEdit = { deepEditParamKey = key },
                    )
                }
            }
        }
    }

    // Parameter Deep-Edit Popover (Double-Tap on any value bar)
    val activeBlock = block
    if (activeBlock != null) {
        deepEditParamKey?.let { key ->
            val schema = ParameterSchemaRegistry.get(key)
            val curVal = activeBlock.params[key] ?: schema?.defaultValue ?: 0.5f
            if (schema != null) {
                ParameterDeepEditDialog(
                    schema = schema,
                    currentValue = curVal,
                    isSnapshotControlled = key in state.activePreset.snapshotControlledParams,
                    midiCc = state.learnedMidiMap[key],
                    isLearningThis = state.midiLearnTargetKey == key,
                    stageLocked = state.stageLock,
                    onStepValue = { delta ->
                        val next = (curVal + delta).coerceIn(schema.minValue, schema.maxValue)
                        onUpdateParam(activeBlock.id, key, next)
                    },
                    onResetDefault = { onUpdateParam(activeBlock.id, key, schema.defaultValue) },
                    onToggleSnapshotControl = { onToggleSnapshotControl(key) },
                    onArmMidiLearn = { onArmMidiLearn(key) },
                    onDismiss = { deepEditParamKey = null },
                )
            }
        }
    }
}

@Composable
private fun SegmentedParamRow(
    label: String,
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    accentColor: Color,
    stageLocked: Boolean,
    onSelectRight: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFFD5D8DE), fontSize = 11.5.sp)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1D1D21))
                .border(1.dp, Color(0xFF2D2D34), RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .background(if (!rightSelected) accentColor.copy(alpha = 0.85f) else Color.Transparent)
                    .clickable(enabled = !stageLocked) { onSelectRight(false) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = leftLabel,
                    color = if (!rightSelected) Color.White else accentColor.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = if (!rightSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Box(
                modifier = Modifier
                    .background(if (rightSelected) accentColor.copy(alpha = 0.85f) else Color.Transparent)
                    .clickable(enabled = !stageLocked) { onSelectRight(true) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = rightLabel,
                    color = if (rightSelected) Color.White else accentColor.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = if (rightSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Signature Helix Stadium Native Flat Value Bar (matching jpeg.jpeg right pane):
 * - Top line: parameter label left | accent value readout + micro `▲▼` steppers right
 * - Bottom track: sleek 16dp tinted horizontal bar with category fill & sharp vertical needle
 */
@Composable
private fun HelixFlatValueBar(
    schema: ParameterSchema,
    value: Float,
    accentColor: Color,
    isSnapshotControlled: Boolean,
    midiCc: Int?,
    stageLocked: Boolean,
    onValueChange: (Float) -> Unit,
    onOpenDeepEdit: () -> Unit,
) {
    val norm = ((value - schema.minValue) / (schema.maxValue - schema.minValue).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Top Header Row: Label (left) + Value + ▲▼ steppers (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = schema.displayName,
                color = if (stageLocked) StadiumColors.TextMuted else Color(0xFFDCE0E6),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isSnapshotControlled) {
                    Icon(
                        imageVector = StadiumIcons.Camera,
                        contentDescription = "Snapshot Controlled",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(10.dp),
                    )
                }
                if (midiCc != null) {
                    Text(
                        text = "CC$midiCc",
                        color = StadiumColors.BadgeCyan,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = schema.formatValue(value),
                    color = if (stageLocked) StadiumColors.TextMuted else accentColor.copy(alpha = 0.95f),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )

                // Micro stacked ▲▼ fine steppers (exact visual match to jpeg.jpeg!)
                Column(
                    verticalArrangement = Arrangement.spacedBy((-5).dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Increment",
                        tint = accentColor.copy(alpha = if (stageLocked) 0.3f else 0.85f),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable(enabled = !stageLocked) {
                                latestOnChange((latestValue + schema.step * 2f).coerceIn(schema.minValue, schema.maxValue))
                            },
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Decrement",
                        tint = accentColor.copy(alpha = if (stageLocked) 0.3f else 0.85f),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable(enabled = !stageLocked) {
                                latestOnChange((latestValue - schema.step * 2f).coerceIn(schema.minValue, schema.maxValue))
                            },
                    )
                }
            }
        }

        // Sleek 16dp Flat Tinted Track Bar + Bright Vertical Needle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(17.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accentColor.copy(alpha = 0.14f))
                .pointerInput(schema.key, stageLocked) {
                    detectTapGestures(
                        onDoubleTap = { onOpenDeepEdit() },
                        onTap = { pos ->
                            if (stageLocked) return@detectTapGestures
                            val u = (pos.x / size.width).coerceIn(0f, 1f)
                            latestOnChange(schema.minValue + u * (schema.maxValue - schema.minValue))
                        },
                    )
                }
                .pointerInput(schema.key, stageLocked) {
                    if (stageLocked) return@pointerInput
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val u = (change.position.x / size.width).coerceIn(0f, 1f)
                        latestOnChange(schema.minValue + u * (schema.maxValue - schema.minValue))
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(norm.coerceAtLeast(0.012f))
                    .background(accentColor.copy(alpha = if (stageLocked) 0.16f else 0.42f)),
            ) {
                // Sharp vertical indicator needle right at current value edge
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(2.5.dp)
                        .fillMaxHeight()
                        .background(if (stageLocked) StadiumColors.TextMuted else accentColor),
                )
            }
        }
    }
}

private fun tabToNativeCode(tab: BrowserCategoryTab): Int = when (tab) {
    BrowserCategoryTab.AMP, BrowserCategoryTab.PREAMP -> RigBlockCode.AMP
    BrowserCategoryTab.CAB -> RigBlockCode.CAB
    BrowserCategoryTab.DISTORTION -> RigBlockCode.DRIVE
    BrowserCategoryTab.DELAY -> RigBlockCode.DELAY
    BrowserCategoryTab.REVERB -> RigBlockCode.REVERB
    BrowserCategoryTab.MODULATION -> RigBlockCode.MOD_POST
    BrowserCategoryTab.DYNAMICS -> RigBlockCode.COMP
    BrowserCategoryTab.EQ -> RigBlockCode.EQ
    BrowserCategoryTab.PITCH_SYNTH -> RigBlockCode.PITCH
    BrowserCategoryTab.WAH_FILTER -> RigBlockCode.WAH
    BrowserCategoryTab.VOL_PAN -> RigBlockCode.VOL_PAN
    BrowserCategoryTab.FAVORITES -> RigBlockCode.AMP
}

private fun buildBrowserEntries(
    tab: BrowserCategoryTab,
    searchQuery: String,
    bassFilter: Boolean?,
): List<BrowserModelEntry> {
    val q = searchQuery.trim().lowercase()
    return when (tab) {
        BrowserCategoryTab.FAVORITES -> {
            // Curated quick favorites showcasing signature models across categories
            listOf(
                BrowserModelEntry("fav_amp_6", 6, BlockCategory.AMP, RigBlockCode.AMP, "Essex TB30CC", "Essex 30", "Vox AC30 Top Boost", "Agoura"),
                BrowserModelEntry("fav_amp_11", 11, BlockCategory.AMP, RigBlockCode.AMP, "Brit 2203", "Brit 2203", "Marshall JCM800 2203", "Agoura"),
                BrowserModelEntry("fav_amp_15", 15, BlockCategory.AMP, RigBlockCode.AMP, "Cali IIC+", "Cali 2C+", "Mesa Mark IIC+", "Agoura"),
                BrowserModelEntry("fav_drv_3", 3, BlockCategory.DISTORTION, RigBlockCode.DRIVE, "Minotaur", "Minotaur", "Klon Centaur", "OD"),
                BrowserModelEntry("fav_dly_1", 1, BlockCategory.DELAY, RigBlockCode.DELAY, "Cosmos Echo", "Cosmos", "Roland RE-201", "Tape"),
                BrowserModelEntry("fav_rev_3", 3, BlockCategory.REVERB, RigBlockCode.REVERB, "Searchlights", "Shimmer", "Cathedral Shimmer", "FX"),
            ).filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q) }
        }
        BrowserCategoryTab.AMP, BrowserCategoryTab.PREAMP -> {
            val isPre = tab == BrowserCategoryTab.PREAMP
            AmpModelCatalog.search(searchQuery, bassFilter).map {
                BrowserModelEntry(
                    id = "${if (isPre) "pre" else "amp"}_${it.nativeId}",
                    nativeId = it.nativeId,
                    category = BlockCategory.AMP,
                    nativeBlockCode = RigBlockCode.AMP,
                    displayName = if (isPre) "${it.displayName} Pre" else it.displayName,
                    shortLabel = it.shortLabel,
                    basedOn = it.basedOn,
                    badge = if (it.engineTier == AmpEngineTier.AGOURA) "Agoura" else "HX",
                    isPreamp = isPre,
                )
            }
        }
        BrowserCategoryTab.CAB -> FactoryCabCatalog.searchCabs(searchQuery).map {
            BrowserModelEntry(
                id = "cab_${it.nativeId}",
                nativeId = it.nativeId,
                category = BlockCategory.CAB,
                nativeBlockCode = RigBlockCode.CAB,
                displayName = it.displayName,
                shortLabel = it.shortLabel,
                basedOn = it.basedOn,
                badge = it.speakerConfig,
            )
        }
        BrowserCategoryTab.DISTORTION -> FxModelCatalog.drives.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("drv_${it.nativeId}", it.nativeId, BlockCategory.DISTORTION, RigBlockCode.DRIVE, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.DELAY -> FxModelCatalog.delays.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("dly_${it.nativeId}", it.nativeId, BlockCategory.DELAY, RigBlockCode.DELAY, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.REVERB -> FxModelCatalog.reverbs.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("rev_${it.nativeId}", it.nativeId, BlockCategory.REVERB, RigBlockCode.REVERB, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.MODULATION -> FxModelCatalog.modulations.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("mod_${it.nativeId}", it.nativeId, BlockCategory.MODULATION, RigBlockCode.MOD_POST, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.DYNAMICS -> FxModelCatalog.dynamics.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            val code = if (it.nativeId == 0) RigBlockCode.GATE else RigBlockCode.COMP
            BrowserModelEntry("dyn_${it.nativeId}", if (it.nativeId == 0) 0 else it.nativeId - 1, BlockCategory.DYNAMICS, code, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.EQ -> FxModelCatalog.eqModes.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("eq_${it.nativeId}", it.nativeId, BlockCategory.EQ, RigBlockCode.EQ, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.PITCH_SYNTH -> FxModelCatalog.pitchSynths.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("pit_${it.nativeId}", it.nativeId, BlockCategory.PITCH_WAH, RigBlockCode.PITCH, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.WAH_FILTER -> FxModelCatalog.wahFilters.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("wah_${it.nativeId}", it.nativeId, BlockCategory.PITCH_WAH, RigBlockCode.WAH, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
        BrowserCategoryTab.VOL_PAN -> FxModelCatalog.volumePan.filter {
            q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
        }.map {
            BrowserModelEntry("vol_${it.nativeId}", it.nativeId, BlockCategory.VOL_PAN, RigBlockCode.VOL_PAN, it.displayName, it.shortLabel, it.basedOn, it.subcategory)
        }
    }
}

@Composable
private fun ParameterDeepEditDialog(
    schema: ParameterSchema,
    currentValue: Float,
    isSnapshotControlled: Boolean,
    midiCc: Int?,
    isLearningThis: Boolean,
    stageLocked: Boolean,
    onStepValue: (Float) -> Unit,
    onResetDefault: () -> Unit,
    onToggleSnapshotControl: () -> Unit,
    onArmMidiLearn: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StadiumColors.SurfaceBar,
        title = { Text("${schema.displayName} — Deep Edit", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(schema.circuitDescription, style = MaterialTheme.typography.bodySmall, color = StadiumColors.TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = !stageLocked,
                        onClick = { onStepValue(-schema.step * 5f) },
                        colors = ButtonDefaults.buttonColors(containerColor = StadiumColors.SurfaceCard),
                    ) { Text("−", color = Color.White) }
                    Text(schema.formatValue(currentValue), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Button(
                        enabled = !stageLocked,
                        onClick = { onStepValue(+schema.step * 5f) },
                        colors = ButtonDefaults.buttonColors(containerColor = StadiumColors.SurfaceCard),
                    ) { Text("+", color = Color.White) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Snapshot Control (Camera)", color = Color.White, fontSize = 13.sp)
                    Switch(checked = isSnapshotControlled, onCheckedChange = { onToggleSnapshotControl() })
                }

                Button(
                    onClick = onArmMidiLearn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLearningThis) StadiumColors.DistortionAmber else StadiumColors.SurfaceCard
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            isLearningThis -> "Waiting for MIDI CC..."
                            midiCc != null -> "MIDI Bound: CC#$midiCc (Tap to Re-Learn)"
                            else -> "1-Tap MIDI Learn"
                        },
                        color = if (isLearningThis) Color.Black else Color.White,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = StadiumColors.BadgeCyan) }
        },
        dismissButton = {
            TextButton(enabled = !stageLocked, onClick = onResetDefault) { Text("Reset", color = StadiumColors.TextSecondary) }
        },
    )
}
