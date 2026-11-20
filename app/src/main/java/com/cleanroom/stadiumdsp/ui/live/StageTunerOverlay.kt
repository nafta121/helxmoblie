package com.cleanroom.stadiumdsp.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.ui.state.TunerConfigUi
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

private val NOTE_NAMES_SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val NOTE_NAMES_FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

/**
 * High-Contrast Stage Strobe & Needle Chromatic Tuner (GAP-10) readable from 3 meters.
 * Reads 60 Hz YIN pitch telemetry strictly inside this leaf composable.
 */
@Composable
fun StageTunerOverlay(
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    config: TunerConfigUi,
    onUpdateConfig: (TunerConfigUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Engage C++ YIN Tuner while screen is visible and restore when leaving
    DisposableEffect(Unit) {
        onUpdateConfig(config.copy(enabled = true))
        onDispose {
            onUpdateConfig(config.copy(enabled = false))
        }
    }

    val t by telemetryFlow.collectAsStateWithLifecycle()
    val hasPitch = t.tunerMidiNote >= 0 && t.tunerConfidence > 0.45f
    val names = if (config.useFlats) NOTE_NAMES_FLAT else NOTE_NAMES_SHARP
    val noteName = if (hasPitch) names[t.tunerMidiNote % 12] else "--"
    val octave = if (hasPitch) ((t.tunerMidiNote / 12) - 1).toString() else ""
    val cents = if (hasPitch) t.tunerCents.coerceIn(-50f, 50f) else 0f
    val inTune = hasPitch && abs(cents) <= 2.0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF060606))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Calibration & Mute Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TunerControlChip("A4 -") {
                    onUpdateConfig(config.copy(enabled = true, refPitchHz = (config.refPitchHz - 1f).coerceIn(425f, 455f)))
                }
                Text(
                    text = "REF ${config.refPitchHz.toInt()} Hz",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                TunerControlChip("A4 +") {
                    onUpdateConfig(config.copy(enabled = true, refPitchHz = (config.refPitchHz + 1f).coerceIn(425f, 455f)))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TunerControlChip(if (config.strobeMode) "Strobe + Needle" else "Needle Only") {
                    onUpdateConfig(config.copy(enabled = true, strobeMode = !config.strobeMode))
                }
                TunerControlChip(if (config.useFlats) "Flats (b)" else "Sharps (#)") {
                    onUpdateConfig(config.copy(enabled = true, useFlats = !config.useFlats))
                }
                TunerControlChip(
                    label = if (config.muteOutput) "OUT: MUTED" else "OUT: LIVE",
                    accent = if (config.muteOutput) StadiumColors.AmpRed else StadiumColors.DelayEmerald,
                ) {
                    onUpdateConfig(config.copy(enabled = true, muteOutput = !config.muteOutput))
                }
            }
        }

        // Giant 3-Meter Readable Note & Cent/Strobe Display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(StadiumColors.SurfaceBar)
                .border(2.dp, if (inTune) StadiumColors.DelayEmerald else StadiumColors.BorderStrong, RoundedCornerShape(16.dp))
                .padding(vertical = 24.dp, horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Animated Strobe Ring / Strip
            if (config.strobeMode) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(StadiumColors.CanvasBlack),
                ) {
                    val segments = 12
                    val segW = size.width / segments
                    val phaseShift = if (hasPitch) t.tunerStrobePhase * segW else 0f
                    for (i in -1..segments) {
                        val x = i * segW + phaseShift
                        drawRect(
                            color = if (inTune) StadiumColors.DelayEmerald else StadiumColors.DistortionAmber.copy(alpha = 0.75f),
                            topLeft = Offset(x, 3f),
                            size = Size(segW * 0.45f, size.height - 6f),
                        )
                    }
                }
            }

            // Huge Note Name + Octave
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = noteName,
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (inTune) StadiumColors.DelayEmerald else Color.White,
                )
                if (octave.isNotEmpty()) {
                    Text(
                        text = octave,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (inTune) StadiumColors.DelayEmerald else StadiumColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                    )
                }
            }

            // Cent Needle Gauge (-50c .. 0 .. +50c)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(StadiumColors.SurfaceSliderTrack),
            ) {
                val centerX = size.width * 0.5f
                drawLine(Color.White, Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = 3f)
                if (hasPitch) {
                    val norm = ((cents + 50f) / 100f).coerceIn(0.03f, 0.97f)
                    val needleX = norm * size.width
                    val col = when {
                        inTune -> StadiumColors.DelayEmerald
                        abs(cents) <= 10f -> StadiumColors.MeterYellow
                        else -> StadiumColors.AmpRed
                    }
                    drawCircle(col, radius = 12f, center = Offset(needleX, size.height * 0.5f))
                }
            }

            Text(
                text = if (hasPitch) {
                    String.format("%+.1f cents  •  %.2f Hz  •  %s", cents, t.tunerFreqHz, if (inTune) "IN TUNE" else if (cents < 0) "FLAT" else "SHARP")
                } else {
                    "Pluck an open string (30 Hz – 1,200 Hz YIN Parabolic Detector)"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (inTune) StadiumColors.DelayEmerald else StadiumColors.TextSecondary,
            )
        }

        Text(
            text = if (config.muteOutput) "Stage Auto-Mute ACTIVE (Clickless 5 ms Ramp)" else "Tuner Bypassed Output LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = if (config.muteOutput) StadiumColors.AmpRed else StadiumColors.TextMuted,
        )
    }
}

@Composable
private fun TunerControlChip(
    label: String,
    accent: Color = StadiumColors.BorderStrong,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StadiumColors.SurfaceCard)
            .border(1.dp, accent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
