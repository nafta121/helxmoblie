package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.RigBlockCode
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 100% Clean-Room Procedural Compose Canvas Vector Icons & Hardware Art
 * replicating Helix Stadium Native studio clarity without any copyrighted bitmaps.
 */

@Composable
fun CategoryBlockGlyph(
    nativeBlockCode: Int,
    category: BlockCategory,
    tint: Color,
    modelId: Int = 0,
    isPreamp: Boolean = false,
    modifier: Modifier = Modifier.size(26.dp),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = (w * 0.068f).coerceAtLeast(1.3f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val thin = Stroke(width = (w * 0.048f).coerceAtLeast(1.0f), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when {
            nativeBlockCode == RigBlockCode.AMP && isPreamp -> {
                // Preamp rack unit with knobs & tube grill
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.28f), Size(w * 0.80f, h * 0.44f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint.copy(alpha = 0.7f), Offset(w * 0.14f, h * 0.50f), Offset(w * 0.86f, h * 0.50f), thin.width)
                for (i in 0..3) {
                    drawCircle(tint, radius = w * 0.045f, center = Offset(w * (0.24f + i * 0.17f), h * 0.39f))
                }
                drawLine(tint.copy(alpha = 0.6f), Offset(w * 0.22f, h * 0.61f), Offset(w * 0.78f, h * 0.61f), thin.width)
            }
            nativeBlockCode == RigBlockCode.AMP || category == BlockCategory.AMP -> {
                // Guitar Amp Head silhouette (matches Helix Stadium Native red amp icon)
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.20f), Size(w * 0.80f, h * 0.60f), CornerRadius(w * 0.09f), style = stroke)
                // Top handle
                drawLine(tint, Offset(w * 0.36f, h * 0.14f), Offset(w * 0.64f, h * 0.14f), stroke.width)
                // Control strip divider
                drawLine(tint, Offset(w * 0.12f, h * 0.44f), Offset(w * 0.88f, h * 0.44f), thin.width)
                // Knobs row
                for (i in 0..4) {
                    drawCircle(tint, radius = w * 0.036f, center = Offset(w * (0.23f + i * 0.135f), h * 0.33f))
                }
                // Grille horizontal slats
                drawLine(tint.copy(alpha = 0.55f), Offset(w * 0.18f, h * 0.56f), Offset(w * 0.82f, h * 0.56f), thin.width)
                drawLine(tint.copy(alpha = 0.55f), Offset(w * 0.18f, h * 0.68f), Offset(w * 0.82f, h * 0.68f), thin.width)
            }
            nativeBlockCode == RigBlockCode.CAB || category == BlockCategory.CAB -> {
                // Speaker cone side profile (matches magenta Cab icon in reference)
                val cone = Path().apply {
                    moveTo(w * 0.22f, h * 0.36f)
                    lineTo(w * 0.38f, h * 0.36f)
                    lineTo(w * 0.68f, h * 0.16f)
                    lineTo(w * 0.68f, h * 0.84f)
                    lineTo(w * 0.38f, h * 0.64f)
                    lineTo(w * 0.22f, h * 0.64f)
                    close()
                }
                drawPath(cone, tint, style = stroke)
                // Sound cone rim arc
                drawOval(tint.copy(alpha = 0.75f), Offset(w * 0.62f, h * 0.20f), Size(w * 0.18f, h * 0.60f), style = thin)
            }
            nativeBlockCode == RigBlockCode.DRIVE || category == BlockCategory.DISTORTION -> {
                if (modelId == 9 || modelId == 10) {
                    // Stacked Boost Chevrons (matches first orange block in Space Cadet!)
                    for (i in 0..2) {
                        val y = h * (0.24f + i * 0.20f)
                        val chevron = Path().apply {
                            moveTo(w * 0.24f, y + h * 0.14f)
                            lineTo(w * 0.50f, y)
                            lineTo(w * 0.76f, y + h * 0.14f)
                        }
                        drawPath(chevron, tint, style = stroke)
                    }
                } else {
                    // Overdrive / Fuzz Dial Octagon (matches second orange block in Space Cadet!)
                    val rOuter = w * 0.36f
                    val cx = w * 0.50f
                    val cy = h * 0.50f
                    val oct = Path().apply {
                        for (i in 0 until 8) {
                            val ang = (i * 45.0 - 22.5) * PI / 180.0
                            val px = cx + (rOuter * cos(ang)).toFloat()
                            val py = cy + (rOuter * sin(ang)).toFloat()
                            if (i == 0) moveTo(px, py) else lineTo(px, py)
                        }
                        close()
                    }
                    drawPath(oct, tint, style = stroke)
                    drawCircle(tint.copy(alpha = 0.75f), w * 0.22f, Offset(cx, cy), style = thin)
                    drawLine(tint, Offset(cx, cy), Offset(cx + w * 0.15f, cy - h * 0.15f), stroke.width)
                }
            }
            nativeBlockCode == RigBlockCode.DELAY || category == BlockCategory.DELAY -> {
                // Concentric rounded-rectangle Delay icon (exact match to green blocks in reference!)
                drawRoundRect(tint.copy(alpha = 0.55f), Offset(w * 0.14f, h * 0.22f), Size(w * 0.54f, h * 0.56f), CornerRadius(w * 0.16f), style = thin)
                drawRoundRect(tint, Offset(w * 0.30f, h * 0.22f), Size(w * 0.56f, h * 0.56f), CornerRadius(w * 0.16f), style = stroke)
            }
            nativeBlockCode == RigBlockCode.REVERB || category == BlockCategory.REVERB -> {
                // 3D Isometric Reverb Room Cube (exact match to coral-orange blocks in reference!)
                val top = Path().apply {
                    moveTo(w * 0.50f, h * 0.14f)
                    lineTo(w * 0.84f, h * 0.32f)
                    lineTo(w * 0.50f, h * 0.50f)
                    lineTo(w * 0.16f, h * 0.32f)
                    close()
                }
                val outer = Path().apply {
                    moveTo(w * 0.16f, h * 0.32f)
                    lineTo(w * 0.16f, h * 0.70f)
                    lineTo(w * 0.50f, h * 0.88f)
                    lineTo(w * 0.84f, h * 0.70f)
                    lineTo(w * 0.84f, h * 0.32f)
                }
                drawPath(top, tint, style = stroke)
                drawPath(outer, tint, style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.50f, h * 0.88f), stroke.width)
            }
            nativeBlockCode == RigBlockCode.MOD_PRE || nativeBlockCode == RigBlockCode.MOD_POST || category == BlockCategory.MODULATION -> {
                // Modulation Sine LFO Wave
                val wave = Path().apply {
                    moveTo(w * 0.12f, h * 0.52f)
                    cubicTo(w * 0.26f, h * 0.12f, w * 0.38f, h * 0.12f, w * 0.50f, h * 0.52f)
                    cubicTo(w * 0.62f, h * 0.90f, w * 0.74f, h * 0.90f, w * 0.88f, h * 0.48f)
                }
                drawPath(wave, tint, style = stroke)
                drawLine(tint.copy(alpha = 0.35f), Offset(w * 0.12f, h * 0.50f), Offset(w * 0.88f, h * 0.50f), thin.width)
            }
            nativeBlockCode == RigBlockCode.EQ || category == BlockCategory.EQ -> {
                // Parametric / Tilt EQ Sweep Icon (matches yellow EQ blocks in reference!)
                drawLine(tint.copy(alpha = 0.35f), Offset(w * 0.14f, h * 0.78f), Offset(w * 0.86f, h * 0.78f), thin.width)
                drawLine(tint, Offset(w * 0.18f, h * 0.78f), Offset(w * 0.76f, h * 0.24f), stroke.width)
                val curve = Path().apply {
                    moveTo(w * 0.20f, h * 0.72f)
                    quadraticTo(w * 0.52f, h * 0.56f, w * 0.82f, h * 0.60f)
                }
                drawPath(curve, tint.copy(alpha = 0.80f), style = thin)
                drawCircle(tint, w * 0.055f, Offset(w * 0.48f, h * 0.50f))
            }
            nativeBlockCode == RigBlockCode.GATE || nativeBlockCode == RigBlockCode.COMP || category == BlockCategory.DYNAMICS -> {
                // Dynamics Compressor / Gate Knee Curve
                drawLine(tint.copy(alpha = 0.35f), Offset(w * 0.16f, h * 0.80f), Offset(w * 0.84f, h * 0.80f), thin.width)
                drawLine(tint.copy(alpha = 0.35f), Offset(w * 0.16f, h * 0.20f), Offset(w * 0.16f, h * 0.80f), thin.width)
                val knee = Path().apply {
                    moveTo(w * 0.18f, h * 0.78f)
                    lineTo(w * 0.52f, h * 0.44f)
                    lineTo(w * 0.84f, h * 0.32f)
                }
                drawPath(knee, tint, style = stroke)
                drawCircle(tint, w * 0.05f, Offset(w * 0.52f, h * 0.44f))
            }
            nativeBlockCode == RigBlockCode.PITCH -> {
                // Pitch/Synth mini piano keys
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.20f), Size(w * 0.72f, h * 0.60f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.38f, h * 0.22f), Offset(w * 0.38f, h * 0.78f), thin.width)
                drawLine(tint, Offset(w * 0.62f, h * 0.22f), Offset(w * 0.62f, h * 0.78f), thin.width)
                drawRect(tint, Offset(w * 0.31f, h * 0.20f), Size(w * 0.13f, h * 0.34f))
                drawRect(tint, Offset(w * 0.56f, h * 0.20f), Size(w * 0.13f, h * 0.34f))
            }
            nativeBlockCode == RigBlockCode.WAH || category == BlockCategory.PITCH_WAH -> {
                // Wah Treadle Pedal (angled rocker)
                val treadle = Path().apply {
                    moveTo(w * 0.16f, h * 0.44f)
                    lineTo(w * 0.72f, h * 0.22f)
                    lineTo(w * 0.84f, h * 0.42f)
                    lineTo(w * 0.28f, h * 0.66f)
                    close()
                }
                drawPath(treadle, tint, style = stroke)
                drawLine(tint.copy(alpha = 0.75f), Offset(w * 0.20f, h * 0.78f), Offset(w * 0.82f, h * 0.78f), stroke.width)
            }
            nativeBlockCode == RigBlockCode.VOL_PAN || category == BlockCategory.VOL_PAN -> {
                // Volume / Pan Expression Pedal (exact match to cyan angled pedal in reference!)
                val pedalTop = Path().apply {
                    moveTo(w * 0.28f, h * 0.20f)
                    lineTo(w * 0.82f, h * 0.56f)
                    lineTo(w * 0.68f, h * 0.76f)
                    lineTo(w * 0.16f, h * 0.38f)
                    close()
                }
                drawPath(pedalTop, tint, style = stroke)
                drawLine(tint.copy(alpha = 0.65f), Offset(w * 0.22f, h * 0.78f), Offset(w * 0.76f, h * 0.78f), thin.width)
                drawLine(tint.copy(alpha = 0.55f), Offset(w * 0.36f, h * 0.34f), Offset(w * 0.66f, h * 0.54f), thin.width)
            }
            else -> {
                drawCircle(tint, w * 0.32f, Offset(w * 0.5f, h * 0.5f), style = stroke)
            }
        }
    }
}

/**
 * Circular Helix Stadium Focus Iris icon (seen on top-left of Inspector header in reference).
 */
@Composable
fun FocusIrisGlyph(
    tint: Color,
    modifier: Modifier = Modifier.size(24.dp),
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rOuter = size.minDimension * 0.44f
        val rInner = size.minDimension * 0.22f
        val strokeW = (size.minDimension * 0.10f).coerceAtLeast(1.8f)

        // Outer ring
        drawCircle(tint.copy(alpha = 0.28f), rOuter, Offset(cx, cy))
        drawCircle(tint, rOuter, Offset(cx, cy), style = Stroke(width = strokeW * 0.7f))

        // 6 shutter blades creating iconic camera/focus iris look
        for (i in 0 until 6) {
            val a1 = (i * 60.0) * PI / 180.0
            val a2 = (i * 60.0 + 48.0) * PI / 180.0
            val p1 = Offset(cx + (rInner * cos(a1)).toFloat(), cy + (rInner * sin(a1)).toFloat())
            val p2 = Offset(cx + (rOuter * 0.92f * cos(a2)).toFloat(), cy + (rOuter * 0.92f * sin(a2)).toFloat())
            drawLine(tint, p1, p2, strokeWidth = strokeW * 0.72f, cap = StrokeCap.Round)
        }
    }
}

/**
 * Circular Signal Flow I/O and Routing glyphs (Audio Wave, Down Feed Arrow, Split Tee, Merge Tee).
 */
@Composable
fun SignalNodeCircleGlyph(
    kind: String, // "IN", "OUT", "DOWN", "SPLIT", "MERGE"
    tint: Color = Color(0xFFA8A8A8),
    modifier: Modifier = Modifier.size(28.dp),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = size.minDimension * 0.46f
        val strokeW = (size.minDimension * 0.06f).coerceAtLeast(1.2f)

        drawCircle(Color(0xFF0A0A0A), r, Offset(cx, cy))
        drawCircle(tint.copy(alpha = 0.75f), r, Offset(cx, cy), style = Stroke(width = strokeW))

        when (kind) {
            "IN", "OUT" -> {
                // Crisp vertical audio waveform bars inside circle
                val heights = floatArrayOf(0.14f, 0.30f, 0.46f, 0.26f, 0.40f, 0.16f)
                val step = w * 0.095f
                val startX = cx - step * 2.5f
                heights.forEachIndexed { idx, frac ->
                    val x = startX + idx * step
                    drawLine(tint, Offset(x, cy - h * frac * 0.5f), Offset(x, cy + h * frac * 0.5f), strokeW, StrokeCap.Round)
                }
            }
            "DOWN" -> {
                // Path 1A -> Path 2A feed-down arrow
                drawLine(tint, Offset(cx, cy - h * 0.22f), Offset(cx, cy + h * 0.22f), strokeW, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(cx - w * 0.15f, cy + h * 0.08f)
                    lineTo(cx, cy + h * 0.24f)
                    lineTo(cx + w * 0.15f, cy + h * 0.08f)
                }
                drawPath(head, tint, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "SPLIT" -> {
                // Horizontal line with curved branch descending right-down
                drawLine(tint, Offset(cx - r * 0.72f, cy), Offset(cx + r * 0.72f, cy), strokeW, StrokeCap.Round)
                val curve = Path().apply {
                    moveTo(cx - r * 0.15f, cy)
                    quadraticTo(cx + r * 0.12f, cy, cx + r * 0.12f, cy + r * 0.72f)
                }
                drawPath(curve, tint, style = Stroke(strokeW, cap = StrokeCap.Round))
            }
            "MERGE" -> {
                // Horizontal line with curved branch ascending from bottom into main path
                drawLine(tint, Offset(cx - r * 0.72f, cy), Offset(cx + r * 0.72f, cy), strokeW, StrokeCap.Round)
                val curve = Path().apply {
                    moveTo(cx - r * 0.12f, cy + r * 0.72f)
                    quadraticTo(cx - r * 0.12f, cy, cx + r * 0.18f, cy)
                }
                drawPath(curve, tint, style = Stroke(strokeW, cap = StrokeCap.Round))
            }
        }
    }
}

/**
 * Tiny gold chain-link indicator displayed below Amp & Cab when Amp > Cab Link is active.
 */
@Composable
fun GoldChainLinkGlyph(
    linked: Boolean,
    modifier: Modifier = Modifier.size(width = 18.dp, height = 8.dp),
) {
    val tint = if (linked) StadiumColors.GoldLink else StadiumColors.TextMuted.copy(alpha = 0.4f)
    Canvas(modifier = modifier) {
        val h = size.height
        val w = size.width
        val stroke = Stroke(width = 1.4f.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(tint, Offset(1f, 1f), Size(w * 0.48f, h - 2f), CornerRadius(h * 0.5f), style = stroke)
        drawRoundRect(tint, Offset(w * 0.52f - 1f, 1f), Size(w * 0.48f, h - 2f), CornerRadius(h * 0.5f), style = stroke)
        drawLine(tint, Offset(w * 0.36f, h * 0.5f), Offset(w * 0.64f, h * 0.5f), stroke.width)
    }
}

/**
 * 100% Clean-Room Procedural Hardware Illustration Thumbnails for the 2-Column Model Browser Grid.
 * Visually evokes real combo amps, heads, speaker cabs, and stompboxes with pure vector primitives.
 */
@Composable
fun ProceduralHardwareThumbnail(
    category: BlockCategory,
    nativeBlockCode: Int,
    modelId: Int,
    isPreamp: Boolean = false,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when {
            nativeBlockCode == RigBlockCode.AMP -> drawProceduralAmpArt(w, h, modelId, isPreamp)
            nativeBlockCode == RigBlockCode.CAB -> drawProceduralCabArt(w, h, modelId)
            nativeBlockCode == RigBlockCode.VOL_PAN || nativeBlockCode == RigBlockCode.WAH -> drawProceduralPedalRockerArt(w, h, accentColor)
            nativeBlockCode == RigBlockCode.EQ || nativeBlockCode == RigBlockCode.GATE || nativeBlockCode == RigBlockCode.COMP -> drawProceduralRackArt(w, h, accentColor, modelId)
            category == BlockCategory.EQ || category == BlockCategory.DYNAMICS -> drawProceduralRackArt(w, h, accentColor, modelId)
            else -> drawProceduralStompboxArt(w, h, accentColor, modelId, category)
        }
    }
}

private fun DrawScope.drawProceduralAmpArt(w: Float, h: Float, modelId: Int, isPreamp: Boolean) {
    if (isPreamp) {
        // Slim 1U Rack Preamp Faceplate
        val top = h * 0.28f
        val bh = h * 0.44f
        drawRoundRect(Color(0xFF1E1E22), Offset(w * 0.06f, top), Size(w * 0.88f, bh), CornerRadius(4f))
        drawRoundRect(Color(0xFF4A4A52), Offset(w * 0.06f, top), Size(w * 0.88f, bh), CornerRadius(4f), style = Stroke(1.5f))
        // Glowing warm vacuum tube window
        drawRoundRect(
            brush = Brush.radialGradient(listOf(Color(0xFFFF8833), Color(0xFF551A05)), center = Offset(w * 0.22f, top + bh * 0.5f), radius = w * 0.14f),
            topLeft = Offset(w * 0.13f, top + bh * 0.22f),
            size = Size(w * 0.18f, bh * 0.56f),
            cornerRadius = CornerRadius(3f),
        )
        // 5 metallic knobs
        for (i in 0..4) {
            val kx = w * (0.40f + i * 0.11f)
            drawCircle(Color(0xFFCCCCCC), w * 0.036f, Offset(kx, top + bh * 0.52f))
            drawCircle(Color(0xFF222222), w * 0.022f, Offset(kx, top + bh * 0.52f))
        }
        return
    }

    when (modelId) {
        // 0..3: Blackface / Silverface Fender Combos (vertical combo silhouette with bright silver grille)
        0, 1, 2, 3 -> {
            val bx = w * 0.20f; val bw = w * 0.60f; val by = h * 0.08f; val bh = h * 0.84f
            drawRoundRect(Color(0xFF151517), Offset(bx, by), Size(bw, bh), CornerRadius(6f))
            // Silverface / Blackface top control strip
            drawRect(Color(0xFF0A0A0C), Offset(bx + 4f, by + 5f), Size(bw - 8f, bh * 0.16f))
            for (i in 0..5) {
                drawCircle(Color(0xFFD5D5DC), bw * 0.038f, Offset(bx + bw * (0.14f + i * 0.14f), by + bh * 0.13f))
            }
            // Iconic bright silver-weave grille cloth
            drawRect(
                brush = Brush.verticalGradient(listOf(Color(0xFFACA798), Color(0xFF8C8778), Color(0xFF7A7669))),
                topLeft = Offset(bx + 5f, by + bh * 0.23f),
                size = Size(bw - 10f, bh * 0.70f),
            )
            // Subtle weave horizontal lines
            for (row in 0..8) {
                val ly = by + bh * (0.27f + row * 0.07f)
                drawLine(Color.Black.copy(alpha = 0.12f), Offset(bx + 5f, ly), Offset(bx + bw - 5f, ly), 1f)
            }
            drawRoundRect(Color(0xFF45454A), Offset(bx, by), Size(bw, bh), CornerRadius(6f), style = Stroke(1.2f))
        }
        // 4..5: Vintage Tweed Combos (warm amber-linen tolex + oxblood grille)
        4, 5 -> {
            val bx = w * 0.18f; val bw = w * 0.64f; val by = h * 0.10f; val bh = h * 0.80f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFFD6A856), Color(0xFFB38236))),
                topLeft = Offset(bx, by), size = Size(bw, bh), cornerRadius = CornerRadius(6f),
            )
            drawRect(Color(0xFF5A3722), Offset(bx + 6f, by + bh * 0.18f), Size(bw - 12f, bh * 0.70f))
            drawRect(Color(0xFFC8CDD4), Offset(bx + 10f, by + 4f), Size(bw - 20f, bh * 0.10f))
        }
        // 6..7: Essex AC30 / AC15 (dark/charcoal diamond grille + gold pinstripe — matches Essex TB30CC card in reference!)
        6, 7 -> {
            val bx = w * 0.14f; val bw = w * 0.72f; val by = h * 0.10f; val bh = h * 0.80f
            drawRoundRect(Color(0xFF18181A), Offset(bx, by), Size(bw, bh), CornerRadius(6f))
            // Grille body
            drawRect(Color(0xFF2A2623), Offset(bx + 5f, by + bh * 0.16f), Size(bw - 10f, bh * 0.74f))
            // Subtle diamond lattice lines
            for (i in 0..6) {
                val xOff = bx + 8f + (bw - 16f) * (i / 6f)
                drawLine(Color(0xFF564B41).copy(alpha = 0.55f), Offset(xOff, by + bh * 0.16f), Offset(xOff, by + bh * 0.90f), 1f)
            }
            for (j in 0..5) {
                val yOff = by + bh * (0.20f + j * 0.12f)
                drawLine(Color(0xFF564B41).copy(alpha = 0.50f), Offset(bx + 5f, yOff), Offset(bx + bw - 5f, yOff), 1f)
            }
            // Gold piping divider & badge
            drawLine(Color(0xFFCFA550), Offset(bx + 5f, by + bh * 0.16f), Offset(bx + bw - 5f, by + bh * 0.16f), 2f)
            drawRoundRect(Color(0xFFD8B058), Offset(bx + bw * 0.36f, by + bh * 0.24f), Size(bw * 0.28f, bh * 0.08f), CornerRadius(2f))
            // White vent slits on top panel
            drawRect(Color(0xFF333338), Offset(bx + bw * 0.22f, by + bh * 0.05f), Size(bw * 0.56f, bh * 0.07f))
        }
        // 21: Matchstick 30 (maroon/cream boutique combo with glowing logo strip)
        21 -> {
            val bx = w * 0.14f; val bw = w * 0.72f; val by = h * 0.12f; val bh = h * 0.76f
            drawRoundRect(Color(0xFF5A1E24), Offset(bx, by), Size(bw, bh), CornerRadius(6f))
            drawRect(Color(0xFFD9CFAF), Offset(bx + 6f, by + bh * 0.06f), Size(bw - 12f, bh * 0.22f))
            drawRect(Color(0xFF2B2320), Offset(bx + 6f, by + bh * 0.32f), Size(bw - 12f, bh * 0.58f))
            drawRect(Color(0xFFEFECE0), Offset(bx + bw * 0.30f, by + bh * 0.14f), Size(bw * 0.40f, bh * 0.08f))
        }
        // 28: Mandarin Orange Head (vivid orange chassis + crisp white control panel — matches Mandarin Rock card!)
        28 -> {
            val bx = w * 0.10f; val bw = w * 0.80f; val by = h * 0.16f; val bh = h * 0.68f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFFF39C12), Color(0xFFD35400))),
                topLeft = Offset(bx, by), size = Size(bw, bh), cornerRadius = CornerRadius(7f),
            )
            // White faceplate frame
            drawRoundRect(Color(0xFFF0EFEA), Offset(bx + 7f, by + bh * 0.36f), Size(bw - 14f, bh * 0.50f), CornerRadius(3f))
            drawRect(Color(0xFF1A1A1A), Offset(bx + 10f, by + bh * 0.10f), Size(bw - 20f, bh * 0.22f))
            for (i in 0..5) {
                drawCircle(Color(0xFF222222), bw * 0.040f, Offset(bx + bw * (0.18f + i * 0.125f), by + bh * 0.62f))
            }
        }
        // 9..13, 22: British Classic Heads (Plexi / JCM800 / JTM45 — wide head, black tolex + brushed brass/gold panel)
        9, 10, 11, 12, 13, 22 -> {
            val bx = w * 0.08f; val bw = w * 0.84f; val by = h * 0.22f; val bh = h * 0.58f
            drawRoundRect(Color(0xFF1A1A1D), Offset(bx, by), Size(bw, bh), CornerRadius(5f))
            // White piping border
            drawRoundRect(Color(0xFFD6D0B8).copy(alpha = 0.6f), Offset(bx + 3f, by + 3f), Size(bw - 6f, bh - 6f), CornerRadius(4f), style = Stroke(1.2f))
            // Brushed gold lower control faceplate
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color(0xFFB89548), Color(0xFFE0C16F), Color(0xFFA9863D))),
                topLeft = Offset(bx + 5f, by + bh * 0.62f),
                size = Size(bw - 10f, bh * 0.30f),
            )
            for (i in 0..6) {
                drawCircle(Color(0xFF1C1C1C), bw * 0.032f, Offset(bx + bw * (0.14f + i * 0.115f), by + bh * 0.77f))
            }
            // Classic script logo bar
            drawRoundRect(Color(0xFFE8E4D8), Offset(bx + bw * 0.36f, by + bh * 0.26f), Size(bw * 0.28f, bh * 0.10f), CornerRadius(2f))
        }
        // 14..19: Modern High-Gain Heads (Cali IIC+, Rectifire, SLO, 5150, Revv, VH4 — matches Cali 2C+ card!)
        14, 15, 16, 17, 18, 19 -> {
            val bx = w * 0.09f; val bw = w * 0.82f; val by = h * 0.18f; val bh = h * 0.64f
            drawRoundRect(Color(0xFF16181C), Offset(bx, by), Size(bw, bh), CornerRadius(5f))
            // Upper metallic silver control faceplate
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color(0xFF8C929B), Color(0xFFC2C8D0), Color(0xFF7B818A))),
                topLeft = Offset(bx + 5f, by + 5f),
                size = Size(bw - 10f, bh * 0.34f),
            )
            // Lower black woven grille + chrome logo badge
            drawRect(Color(0xFF23252A), Offset(bx + 5f, by + bh * 0.42f), Size(bw - 10f, bh * 0.50f))
            for (i in 0..6) {
                drawCircle(Color(0xFF19191D), bw * 0.032f, Offset(bx + bw * (0.14f + i * 0.115f), by + bh * 0.22f))
            }
            // Glowing red/blue high-gain jewel LED
            val led = if (modelId == 18) Color(0xFFA25DFF) else Color(0xFFFF3C3C)
            drawCircle(led, bw * 0.03f, Offset(bx + bw * 0.88f, by + bh * 0.22f))
            drawRoundRect(Color(0xFFD0D4DC), Offset(bx + bw * 0.33f, by + bh * 0.62f), Size(bw * 0.34f, bh * 0.11f), CornerRadius(2f))
        }
        // 25..29: Bass Heads & Boutique Clean
        else -> {
            val bx = w * 0.10f; val bw = w * 0.80f; val by = h * 0.20f; val bh = h * 0.60f
            drawRoundRect(Color(0xFF1D2026), Offset(bx, by), Size(bw, bh), CornerRadius(5f))
            drawRect(Color(0xFF333944), Offset(bx + 5f, by + bh * 0.12f), Size(bw - 10f, bh * 0.32f))
            drawLine(Color(0xFF38BDF8), Offset(bx + 6f, by + bh * 0.46f), Offset(bx + bw - 6f, by + bh * 0.46f), 1.8f)
            for (i in 0..5) {
                drawCircle(Color(0xFFE0E5EC), bw * 0.034f, Offset(bx + bw * (0.16f + i * 0.135f), by + bh * 0.28f))
            }
        }
    }
}

private fun DrawScope.drawProceduralCabArt(w: Float, h: Float, modelId: Int) {
    val isTallBass = modelId == 8 || modelId == 11
    val bx = if (isTallBass) w * 0.24f else w * 0.16f
    val bw = w - 2 * bx
    val by = h * 0.08f
    val bh = h * 0.84f
    drawRoundRect(Color(0xFF17171A), Offset(bx, by), Size(bw, bh), CornerRadius(5f))
    drawRect(Color(0xFF2A282B), Offset(bx + 5f, by + 5f), Size(bw - 10f, bh - 10f))
    // Subtle speaker cone circles behind grille
    val cols = 2
    val rows = if (isTallBass) 4 else if (modelId <= 2) 2 else 1
    val cellW = (bw - 14f) / cols
    val cellH = (bh - 14f) / rows
    val coneR = minOf(cellW, cellH) * 0.38f
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val cx = bx + 7f + cellW * (c + 0.5f)
            val cy = by + 7f + cellH * (r + 0.5f)
            drawCircle(Color(0xFF141418), coneR, Offset(cx, cy))
            drawCircle(Color(0xFF3B383E), coneR * 0.38f, Offset(cx, cy))
        }
    }
    drawRoundRect(StadiumColors.CabPink.copy(alpha = 0.45f), Offset(bx, by), Size(bw, bh), CornerRadius(5f), style = Stroke(1.3f))
}

private fun DrawScope.drawProceduralStompboxArt(w: Float, h: Float, accent: Color, modelId: Int, category: BlockCategory) {
    val isWideDouble = category == BlockCategory.DELAY || category == BlockCategory.REVERB
    val bx = if (isWideDouble) w * 0.12f else w * 0.24f
    val bw = w - 2 * bx
    val by = h * 0.10f
    val bh = h * 0.80f
    val bodyTint = accent.copy(alpha = 0.28f)
    drawRoundRect(Color(0xFF1A1B1E), Offset(bx, by), Size(bw, bh), CornerRadius(7f))
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(bodyTint, Color(0xFF121215))),
        topLeft = Offset(bx + 2f, by + 2f),
        size = Size(bw - 4f, bh - 4f),
        cornerRadius = CornerRadius(6f),
    )
    drawRoundRect(accent.copy(alpha = 0.65f), Offset(bx, by), Size(bw, bh), CornerRadius(7f), style = Stroke(1.4f))
    // Knobs row
    val knobCount = if (isWideDouble) 4 else 3
    for (i in 0 until knobCount) {
        val kx = bx + bw * ((i + 0.7f) / (knobCount + 0.4f))
        drawCircle(Color(0xFF0E0E10), bw * 0.085f, Offset(kx, by + bh * 0.26f))
        drawCircle(Color(0xFFE0E0E0), bw * 0.055f, Offset(kx, by + bh * 0.26f), style = Stroke(1.2f))
    }
    // Glowing LED
    drawCircle(accent, bw * 0.045f, Offset(bx + bw * 0.50f, by + bh * 0.48f))
    // Footswitch button(s)
    if (isWideDouble) {
        drawCircle(Color(0xFFB5BBC2), bw * 0.075f, Offset(bx + bw * 0.30f, by + bh * 0.76f))
        drawCircle(Color(0xFFB5BBC2), bw * 0.075f, Offset(bx + bw * 0.70f, by + bh * 0.76f))
    } else {
        drawCircle(Color(0xFFB5BBC2), bw * 0.11f, Offset(bx + bw * 0.50f, by + bh * 0.75f))
        drawCircle(Color(0xFF33363C), bw * 0.06f, Offset(bx + bw * 0.50f, by + bh * 0.75f))
    }
    // Tiny subtle variation stripe by modelId
    val stripeAlpha = 0.20f + ((modelId % 3) * 0.10f)
    drawLine(accent.copy(alpha = stripeAlpha), Offset(bx + 6f, by + bh * 0.58f), Offset(bx + bw - 6f, by + bh * 0.58f), 1.5f)
}

private fun DrawScope.drawProceduralRackArt(w: Float, h: Float, accent: Color, modelId: Int) {
    val bx = w * 0.08f; val bw = w * 0.84f; val by = h * 0.22f; val bh = h * 0.56f
    drawRoundRect(Color(0xFF1A1B1F), Offset(bx, by), Size(bw, bh), CornerRadius(5f))
    drawRoundRect(accent.copy(alpha = 0.65f), Offset(bx, by), Size(bw, bh), CornerRadius(5f), style = Stroke(1.3f))
    // Mini EQ bands or VU meter bars
    val bars = 7
    for (i in 0 until bars) {
        val x = bx + bw * (0.14f + i * 0.12f)
        val frac = 0.25f + (((i * 3 + modelId * 2) % 5) * 0.13f)
        drawLine(Color(0xFF33363E), Offset(x, by + bh * 0.22f), Offset(x, by + bh * 0.78f), 2.5f, StrokeCap.Round)
        drawCircle(accent, bw * 0.032f, Offset(x, by + bh * (0.78f - frac * 0.56f)))
    }
}

private fun DrawScope.drawProceduralPedalRockerArt(w: Float, h: Float, accent: Color) {
    val bx = w * 0.26f; val bw = w * 0.48f; val by = h * 0.08f; val bh = h * 0.84f
    drawRoundRect(Color(0xFF1D1F24), Offset(bx, by), Size(bw, bh), CornerRadius(8f))
    drawRoundRect(accent.copy(alpha = 0.75f), Offset(bx, by), Size(bw, bh), CornerRadius(8f), style = Stroke(1.5f))
    // Grip tread strips
    for (i in 0..5) {
        val y = by + bh * (0.18f + i * 0.12f)
        drawLine(Color(0xFF424650), Offset(bx + 6f, y), Offset(bx + bw - 6f, y), 2.2f, StrokeCap.Round)
    }
}

/**
 * Interactive Metallic Rotary Knob for Top Bar Input Trim & Output Level (matches jpeg.jpeg far-left & far-right knobs).
 * Drag vertically to adjust dB, double-tap to reset to 0.0 dB.
 */
@Composable
fun MetallicRotaryKnob(
    valueDb: Float,
    minDb: Float = -36f,
    maxDb: Float = 18f,
    accentColor: Color = Color(0xFFE0E5EB),
    sizeDp: Dp = 32.dp,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDb by rememberUpdatedState(valueDb)
    val norm = ((valueDb - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    // Angle spans -135° (7:30) to +135° (4:30), 0 dB sits nicely around ~1:00
    val angleDeg = -135f + norm * 270f

    Canvas(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onValueChange(0f) })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaDb = -dragAmount * 0.18f
                    onValueChange((currentDb + deltaDb).coerceIn(minDb, maxDb))
                }
            },
    ) {
        val r = size.minDimension / 2f
        val center = Offset(r, r)

        // Outer metallic bezel shadow
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF4B4E54), Color(0xFF0E0F11)),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
            radius = r,
            center = center,
        )
        // Inner brushed gunmetal cap
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF3E4147), Color(0xFF1A1C20), Color(0xFF0D0E10)),
                center = Offset(r * 0.78f, r * 0.75f),
                radius = r * 1.05f,
            ),
            radius = r * 0.80f,
            center = center,
        )
        // Subtle specular rim
        drawCircle(Color(0xFF666A72).copy(alpha = 0.5f), r * 0.80f, center, style = Stroke(1f))

        // Rotating pointer indicator tick
        val rad = (angleDeg - 90f) * PI.toFloat() / 180f
        val pInner = Offset(center.x + cos(rad) * r * 0.30f, center.y + sin(rad) * r * 0.30f)
        val pOuter = Offset(center.x + cos(rad) * r * 0.74f, center.y + sin(rad) * r * 0.74f)
        drawLine(accentColor, pInner, pOuter, strokeWidth = (size.minDimension * 0.085f).coerceAtLeast(2f), cap = StrokeCap.Round)
    }
}

/**
 * Precision Edge dB Meter matching Helix Stadium Native's left Input & right Output scales
 * (`+0.0 dB`, `0`, `-6`, `-12`, `-18`, `-24`, `-36`, `-48`, `-60`).
 * Isolated 60 Hz StateFlow collector so SignalFlowCanvas never recomposes at 60 fps!
 */
@Composable
fun PrecisionDbMeterColumn(
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    isInput: Boolean,
    trimDb: Float,
    modifier: Modifier = Modifier,
) {
    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
    val peakDb = if (isInput) telemetry.inPeakDb else maxOf(telemetry.outPeakLDb, telemetry.outPeakRDb)
    val clipped = if (isInput) telemetry.inClipped else telemetry.outClipped
    val norm = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val scaleMarks = listOf("0", "-6", "-12", "-18", "-24", "-36", "-48", "-60")

    Column(
        modifier = modifier
            .width(34.dp)
            .fillMaxHeight()
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = String.format("%+.1f dB", trimDb),
            color = if (clipped) StadiumColors.ClipRed else Color(0xFF8C9096),
            fontSize = 7.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!isInput) {
                // Output bar on left of scale labels (mirrors jpeg.jpeg right edge!)
                VerticalMeterBarCanvas(norm = norm, clipped = clipped)
            }

            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = if (isInput) Alignment.End else Alignment.Start,
            ) {
                scaleMarks.forEach { mark ->
                    Text(
                        text = mark,
                        color = Color(0xFF6B6F75),
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 8.sp,
                    )
                }
            }

            if (isInput) {
                // Input bar on right of scale labels (mirrors jpeg.jpeg left edge!)
                VerticalMeterBarCanvas(norm = norm, clipped = clipped)
            }
        }
    }
}

@Composable
private fun VerticalMeterBarCanvas(
    norm: Float,
    clipped: Boolean,
) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .fillMaxHeight(),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color(0xFF1A1C1F),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(2f),
            )
            val fillH = (size.height * norm).coerceAtLeast(if (norm > 0.01f) 3f else 0f)
            if (fillH > 0f) {
                val topY = size.height - fillH
                val brush = Brush.verticalGradient(
                    0.0f to if (clipped) StadiumColors.ClipRed else Color(0xFFFF3B30),
                    0.18f to StadiumColors.MeterYellow,
                    0.45f to StadiumColors.MeterGreen,
                    1.0f to Color(0xFF198C26),
                    startY = 0f,
                    endY = size.height,
                )
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(0f, topY),
                    size = Size(size.width, fillH),
                    cornerRadius = CornerRadius(2f),
                )
            }
        }
    }
}
