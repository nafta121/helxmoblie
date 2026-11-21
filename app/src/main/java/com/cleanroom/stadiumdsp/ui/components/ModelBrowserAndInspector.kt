package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import com.cleanroom.stadiumdsp.ui.theme.StadiumIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanroom.stadiumdsp.catalog.AmpEngineTier
import com.cleanroom.stadiumdsp.catalog.AmpModelCatalog
import com.cleanroom.stadiumdsp.catalog.FactoryCabCatalog
import com.cleanroom.stadiumdsp.catalog.FxModelCatalog
import com.cleanroom.stadiumdsp.catalog.ParameterSchema
import com.cleanroom.stadiumdsp.catalog.ParameterSchemaRegistry
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import com.cleanroom.stadiumdsp.ui.theme.composeColor

private data class BrowserModelItem(
    val id: String,
    val nativeId: Int,
    val displayName: String,
    val shortLabel: String,
    val basedOn: String,
    val badge: String?,
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(StadiumColors.SurfacePanel),
    ) {
        if (showBrowserPane && !isInspectorExpanded) {
            ModelBrowserPane(
                state = state,
                onSelectAmpModel = onSelectAmpModel,
                onSelectCabModel = onSelectCabModel,
                onSelectFxModel = onSelectFxModel,
                onImportExternalAsset = onImportExternalAsset,
                modifier = Modifier
                    .weight(0.40f)
                    .fillMaxHeight(),
            )
        }

        ParameterInspectorPane(
            state = state,
            isExpanded = isInspectorExpanded,
            onToggleExpanded = onToggleInspectorExpanded,
            onToggleFocusView = onToggleFocusView,
            onToggleBypass = onToggleBypass,
            onToggleStereo = onToggleStereo,
            onUpdateParam = onUpdateParam,
            onToggleSnapshotControl = onToggleSnapshotControl,
            onArmMidiLearn = onArmMidiLearn,
            modifier = Modifier
                .weight(if (showBrowserPane && !isInspectorExpanded) 0.60f else 1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
fun ModelBrowserPane(
    state: StadiumUiState,
    onSelectAmpModel: (Int) -> Unit,
    onSelectCabModel: (Int) -> Unit,
    onSelectFxModel: (String, Int, String, String) -> Unit,
    onImportExternalAsset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var bassFilter by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val selectedBlock = state.selectedBlock
    val listState = rememberLazyListState()

    val items: List<BrowserModelItem> = when (selectedBlock.category) {
        BlockCategory.AMP -> AmpModelCatalog.search(searchQuery, bassFilter).map {
            BrowserModelItem(
                id = "amp_${it.nativeId}",
                nativeId = it.nativeId,
                displayName = it.displayName,
                shortLabel = it.shortLabel,
                basedOn = it.basedOn,
                badge = if (it.engineTier == AmpEngineTier.AGOURA) "Agoura" else "HX",
            )
        }
        BlockCategory.CAB -> FactoryCabCatalog.searchCabs(searchQuery).map {
            BrowserModelItem(
                id = "cab_${it.nativeId}",
                nativeId = it.nativeId,
                displayName = it.displayName,
                shortLabel = it.shortLabel,
                basedOn = it.basedOn,
                badge = it.speakerConfig,
            )
        }
        else -> {
            val pool = when (selectedBlock.id) {
                "wah" -> FxModelCatalog.wahFilters
                "pitch" -> FxModelCatalog.pitchSynths
                else -> FxModelCatalog.byCategory(selectedBlock.category)
            }
            val q = searchQuery.trim().lowercase()
            pool.filter {
                q.isEmpty() || it.displayName.lowercase().contains(q) || it.basedOn.lowercase().contains(q)
            }.map {
                BrowserModelItem(
                    id = "${selectedBlock.id}_${it.nativeId}",
                    nativeId = it.nativeId,
                    displayName = it.displayName,
                    shortLabel = it.shortLabel,
                    basedOn = it.basedOn,
                    badge = if (it.autoZOhms < 999_000f) "${(it.autoZOhms / 1000f).toInt()}k" else it.subcategory,
                )
            }
        }
    }

    Column(
        modifier = modifier
            .background(StadiumColors.SurfacePanel)
            .border(1.dp, StadiumColors.BorderSubtle)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Search Input + SAF Import Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StadiumColors.CanvasBlack)
                    .border(1.dp, StadiumColors.BorderStrong, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = StadiumColors.TextMuted, modifier = Modifier.size(14.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text("Search model / *Based On...", style = MaterialTheme.typography.bodySmall, color = StadiumColors.TextMuted)
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                        cursorBrush = SolidColor(StadiumColors.DistortionAmber),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(StadiumColors.SurfaceCard)
                    .border(1.dp, StadiumColors.CabPink.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .clickable(onClick = onImportExternalAsset)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(StadiumIcons.FolderOpen, contentDescription = "Import .nam/.aidax/.wav", tint = StadiumColors.CabPink, modifier = Modifier.size(14.dp))
                    Text("SAF", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }

        // Guitar / Bass Filter Chips (for Amp category)
        if (selectedBlock.category == BlockCategory.AMP) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChipSmall("All (30)", bassFilter == null) { bassFilter = null }
                FilterChipSmall("Guitar (25)", bassFilter == false) { bassFilter = false }
                FilterChipSmall("Bass (5)", bassFilter == true) { bassFilter = true }
            }
        }

        // Model List with explicit primitive key & contentType
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(
                items = items,
                key = { it.id },
                contentType = { "browser_model_row" },
            ) { item ->
                val isLoaded = selectedBlock.modelId == item.nativeId
                val catColor = selectedBlock.category.composeColor()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isLoaded) StadiumColors.SurfaceSelected else StadiumColors.SurfaceCard)
                        .border(
                            1.dp,
                            if (isLoaded) catColor else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable(enabled = !state.stageLock) {
                            when (selectedBlock.category) {
                                BlockCategory.AMP -> onSelectAmpModel(item.nativeId)
                                BlockCategory.CAB -> onSelectCabModel(item.nativeId)
                                else -> onSelectFxModel(selectedBlock.id, item.nativeId, item.displayName, item.shortLabel)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isLoaded) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isLoaded) Color.White else StadiumColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.basedOn,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = StadiumColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.badge?.let { badge ->
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(catColor.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text(badge, style = MaterialTheme.typography.labelSmall, color = catColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipSmall(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) StadiumColors.AmpRed else StadiumColors.SurfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) Color.White else StadiumColors.TextSecondary)
    }
}

@Composable
private fun ParameterInspectorPane(
    state: StadiumUiState,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleFocusView: () -> Unit,
    onToggleBypass: (String) -> Unit,
    onToggleStereo: (String) -> Unit,
    onUpdateParam: (String, String, Float) -> Unit,
    onToggleSnapshotControl: (String) -> Unit,
    onArmMidiLearn: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val block = state.selectedBlock
    val catColor = block.category.composeColor()
    val gridState = rememberLazyGridState()
    var deepEditParamKey by rememberSaveable { mutableStateOf<String?>(null) }

    val paramEntries = block.params.entries
        .filter { it.key != "cab.mic_model" }
        .toList()

    Column(
        modifier = modifier
            .background(StadiumColors.SurfaceDeep)
            .border(1.dp, StadiumColors.BorderSubtle)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Inspector Header (#333333 — Tap title to expand full width!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(StadiumColors.SurfaceHeader)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClick = onToggleFocusView)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("◎ Focus", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }

                Text(
                    text = block.category.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = catColor,
                )
                Text("|", color = StadiumColors.TextMuted)
                Text(
                    text = block.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Light),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(enabled = !state.stageLock) { onToggleStereo(block.id) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (block.isStereo) "Stereo" else "Mono",
                        style = MaterialTheme.typography.labelSmall,
                        color = StadiumColors.TextSecondary,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (block.enabled) catColor.copy(alpha = 0.25f) else StadiumColors.SurfaceCard)
                        .border(1.dp, if (block.enabled) catColor else StadiumColors.BorderStrong, RoundedCornerShape(5.dp))
                        .clickable(enabled = !state.stageLock) { onToggleBypass(block.id) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (block.enabled) "ON" else "BYPASS",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (block.enabled) Color.White else StadiumColors.TextMuted,
                    )
                }
            }
        }

        // Adaptive Horizontal Value-Bar Sliders Grid (Auto-expands 1 / 2 / 3 columns!)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = if (isExpanded) 180.dp else 165.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(
                items = paramEntries,
                key = { it.key },
                contentType = { "value_bar_slider" },
            ) { (key, value) ->
                val schema = ParameterSchemaRegistry.get(key)
                    ?: ParameterSchema(key, key.substringAfter('.').replace('_', ' ').uppercase(), 0f, 1f, value, "", 0.01f, "Circuit parameter")
                val isSnapControlled = key in state.activePreset.snapshotControlledParams
                val midiCc = state.learnedMidiMap[key]

                ValueBarSliderCard(
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

    // Parameter Deep-Edit Popover (Double-Tap on any slider)
    deepEditParamKey?.let { key ->
        val schema = ParameterSchemaRegistry.get(key)
        val curVal = block.params[key] ?: schema?.defaultValue ?: 0.5f
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
                    onUpdateParam(block.id, key, next)
                },
                onResetDefault = { onUpdateParam(block.id, key, schema.defaultValue) },
                onToggleSnapshotControl = { onToggleSnapshotControl(key) },
                onArmMidiLearn = { onArmMidiLearn(key) },
                onDismiss = { deepEditParamKey = null },
            )
        }
    }
}

@Composable
private fun ValueBarSliderCard(
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(StadiumColors.SurfaceSliderTrack)
            .border(
                1.dp,
                if (stageLocked) StadiumColors.BorderSubtle else StadiumColors.BorderStrong,
                RoundedCornerShape(7.dp),
            )
            .pointerInput(schema.key, stageLocked) {
                detectTapGestures(
                    onDoubleTap = { onOpenDeepEdit() },
                    onTap = { pos ->
                        if (stageLocked) return@detectTapGestures
                        val u = (pos.x / size.width).coerceIn(0f, 1f)
                        onValueChange(schema.minValue + u * (schema.maxValue - schema.minValue))
                    },
                )
            }
            .pointerInput(schema.key, stageLocked) {
                if (stageLocked) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val u = (change.position.x / size.width).coerceIn(0f, 1f)
                    onValueChange(schema.minValue + u * (schema.maxValue - schema.minValue))
                }
            },
    ) {
        // Colored horizontal value fill + crisp bright indicator edge
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(norm)
                .background(accentColor.copy(alpha = if (stageLocked) 0.14f else 0.28f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (stageLocked) StadiumColors.TextMuted else accentColor),
            )
        }

        // Foreground labels & Snapshot/MIDI badges
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = schema.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (stageLocked) StadiumColors.TextMuted else StadiumColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (isSnapshotControlled) {
                    Icon(
                        imageVector = StadiumIcons.Camera,
                        contentDescription = "Snapshot Controlled",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (midiCc != null) {
                    Text(
                        text = "CC$midiCc",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = StadiumColors.BadgeCyan,
                    )
                }
                Text(
                    text = schema.formatValue(value),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSnapshotControlled || midiCc != null) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (isSnapshotControlled || midiCc != null) Color.White else StadiumColors.TextSecondary,
                )
            }
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
