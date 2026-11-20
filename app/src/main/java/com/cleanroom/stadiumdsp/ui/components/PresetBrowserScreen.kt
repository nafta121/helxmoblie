package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors

@Composable
fun PresetBrowserScreen(
    state: StadiumUiState,
    onSelectPreset: (String) -> Unit,
    onSaveUserPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("${state.activePreset.name} Custom") }
    val listState = rememberLazyListState()

    val q = query.trim().lowercase()
    val filtered = state.allPresets.filter { p ->
        q.isEmpty() ||
            p.name.lowercase().contains(q) ||
            p.categoryTag.lowercase().contains(q) ||
            p.presetInfo.lowercase().contains(q) ||
            p.bankCode.lowercase().contains(q)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StadiumColors.CanvasBlack)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StadiumColors.SurfaceCard)
                    .border(1.dp, StadiumColors.BorderStrong, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) {
                    Text("Search Presets, *Based On, or Preset Info...", style = MaterialTheme.typography.bodySmall, color = StadiumColors.TextMuted)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(StadiumColors.BadgeCyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    draftName = "${state.activePreset.name} User"
                    showSaveDialog = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = StadiumColors.AmpRed),
            ) {
                Text("Save User Preset")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(
                items = filtered,
                key = { it.id },
                contentType = { "preset_row" },
            ) { preset ->
                val isActive = preset.id == state.activePreset.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isActive) StadiumColors.SurfaceSelected else StadiumColors.SurfaceCard)
                        .border(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = if (isActive) StadiumColors.BadgeCyan else StadiumColors.BorderSubtle,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectPreset(preset.id) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(StadiumColors.SurfaceDeep)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(preset.bankCode, style = MaterialTheme.typography.labelMedium, color = StadiumColors.BadgeCyan)
                            }
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = StadiumColors.TextPresetTitle,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = preset.categoryTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = StadiumColors.DistortionAmber,
                            )
                            Text(
                                text = if (preset.isFactory) "FACTORY" else "USER",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (preset.isFactory) StadiumColors.TextMuted else StadiumColors.DelayEmerald,
                            )
                        }
                    }
                    if (preset.presetInfo.isNotBlank()) {
                        Text(
                            text = preset.presetInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = StadiumColors.TextSecondary,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = StadiumColors.SurfaceBar,
            title = { Text("Save as Editable User Preset", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveUserPreset(draftName)
                        showSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StadiumColors.AmpRed),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            },
        )
    }
}
