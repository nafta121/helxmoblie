package com.cleanroom.stadiumdsp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanroom.stadiumdsp.CloneMetadataUi
import com.cleanroom.stadiumdsp.LiveTelemetryUi
import com.cleanroom.stadiumdsp.catalog.AmpModelCatalog
import com.cleanroom.stadiumdsp.catalog.FactoryCabCatalog
import com.cleanroom.stadiumdsp.catalog.FxModelCatalog
import com.cleanroom.stadiumdsp.preset.BlockCategory
import com.cleanroom.stadiumdsp.preset.FocusZoneBank
import com.cleanroom.stadiumdsp.preset.RigBlockState
import com.cleanroom.stadiumdsp.ui.state.StadiumUiState
import com.cleanroom.stadiumdsp.ui.theme.StadiumColors
import com.cleanroom.stadiumdsp.ui.theme.composeColor
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure JVM coordinate mapper for 3D/2D Speaker Cab Mic Pad.
 */
object CabMicCoordinateMapper {
    private const val MIN_DIST_INCH = 1.0f
    private const val MAX_DIST_INCH = 12.0f

    fun canvasToCabParams(u: Float, v: Float): Pair<Float, Float> {
        val cu = u.coerceIn(0f, 1f)
        val cv = v.coerceIn(0f, 1f)
        val position = (abs(cu - 0.5f) * 2.0f).coerceIn(0f, 1f)
        val distanceInch = MIN_DIST_INCH + cv * (MAX_DIST_INCH - MIN_DIST_INCH)
        return position to distanceInch
    }

    fun cabParamsToCanvas(position: Float, distanceInch: Float): Pair<Float, Float> {
        val u = (0.5f + 0.5f * position.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val v = ((distanceInch.coerceIn(MIN_DIST_INCH, MAX_DIST_INCH) - MIN_DIST_INCH) /
            (MAX_DIST_INCH - MIN_DIST_INCH)).coerceIn(0f, 1f)
        return u to v
    }
}

/**
 * Pure JVM logarithmic frequency & gain coordinate mapper for Interactive EQ Curve Focus View.
 */
object EqCurveCoordinateMapper {
    private const val MIN_FREQ = 20f
    private const val MAX_FREQ = 20_000f
    private const val MAX_GAIN_DB = 15f

    fun freqToNormX(freqHz: Float): Float {
        val f = freqHz.coerceIn(MIN_FREQ, MAX_FREQ)
        return (ln((f / MIN_FREQ).toDouble()) / ln((MAX_FREQ / MIN_FREQ).toDouble())).toFloat().coerceIn(0f, 1f)
    }

    fun normXToFreq(x: Float): Float {
        val cx = x.coerceIn(0f, 1f)
        return (MIN_FREQ * (MAX_FREQ / MIN_FREQ).toDouble().pow(cx.toDouble())).toFloat()
    }

    fun gainDbToNormY(gainDb: Float): Float {
        val g = gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        return (0.5f - (g / (2f * MAX_GAIN_DB))).coerceIn(0f, 1f)
    }

    fun normYToGainDb(y: Float): Float {
        val cy = y.coerceIn(0f, 1f)
        return ((0.5f - cy) * (2f * MAX_GAIN_DB)).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
    }
}

/**
 * All 5 Polymorphic Focus View Variants matching Helix Stadium Native v1.4:
 * 1. 5-Zone XY Morphing Pad (Amp, Drive, Mod, Delay, Reverb, Pitch/Wah, Vol)
 * 2. 3D/2D Speaker Cab & Virtual Mic Pad + 64-Bin IR Magnitude Spectrum (Cab)
 * 3. Dynamics Yellow Gain Reduction (0..42 dB) Meter Focus (Gate / Comp)
 * 4. Interactive Logarithmic EQ Curve Focus + 10-Band / Tilt modes (EQ)
 * 5. PROXY / Neural Clone Metadata Card Focus (.nam / .aidax)
 */
@Composable
fun PolymorphicFocusView(
    state: StadiumUiState,
    telemetryFlow: StateFlow<LiveTelemetryUi>,
    compactMode: Boolean,
    onUpdateFocusXY: (String, Float, Float) -> Unit,
    onResetCenter: (String) -> Unit,
    onFactoryDefault: (String) -> Unit,
    onUserDefault: (String) -> Unit,
    onSaveUserDefault: (String) -> Unit,
    onAddFavorite: (String) -> Unit,
    onUpdateParam: (String, String, Float) -> Unit,
    onSelectMic: (Int) -> Unit,
    onToggleAmpCabLink: () -> Unit,
    onToggleCabCabLink: () -> Unit,
    onImportExternalAsset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val block = state.selectedBlock
    val isCloneView = block.category == BlockCategory.AMP && state.activePreset.signalFlow.routingMode != 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF060606))
            .border(1.dp, StadiumColors.BorderSubtle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Main Polymorphic Focus Canvas
        Row(
            modifier = Modifier
                .weight(if (compactMode) 1f else 0.76f)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Optional Yellow GR Meter for Dynamics blocks (Variant 3)
            if (block.category == BlockCategory.DYNAMICS) {
                DynamicsGainReductionBar(
                    isGate = block.id == "gate",
                    telemetryFlow = telemetryFlow,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (compactMode) 185.dp else 215.dp),
            ) {
                when {
                    isCloneView -> NeuralCloneFocusCard(
                        meta = state.cloneMetadata,
                        onImportClick = onImportExternalAsset,
                    )
                    block.category == BlockCategory.CAB -> SpeakerCabMicFocusPad(
                        block = block,
                        spectrumDb = state.cabSpectrumDb,
                        externalIrLabel = state.externalIrLabel,
                        stageLocked = state.stageLock,
                        onDragMic = { pos, dist ->
                            onUpdateParam("cab", "cab.position", pos)
                            onUpdateParam("cab", "cab.distance_inch", dist)
                        },
                        onSelectMic = onSelectMic,
                        onToggleAngle = {
                            val curAngle = block.params["cab.angle_deg"] ?: 0f
                            onUpdateParam("cab", "cab.angle_deg", if (curAngle < 22f) 45f else 0f)
                        },
                        onImportIr = onImportExternalAsset,
                    )
                    block.category == BlockCategory.EQ -> InteractiveEqCurveFocusPad(
                        block = block,
                        curveDb = state.eqCurveDb,
                        stageLocked = state.stageLock,
                        onUpdateBand = { freqKey, gainKey, freqHz, gainDb ->
                            onUpdateParam("eq", freqKey, freqHz)
                            onUpdateParam("eq", gainKey, gainDb)
                        },
                    )
                    else -> FiveZoneXyMorphPad(
                        block = block,
                        stageLocked = state.stageLock,
                        onDragXY = { x, y -> onUpdateFocusXY(block.id, x, y) },
                    )
                }
            }
        }

        // Right Focus Sidebar (Hidden on very compact height/width — actions remain accessible)
        if (!compactMode) {
            FocusRightSidebar(
                block = block,
                ampCabLink = state.activePreset.signalFlow.ampCabLink,
                cabCabLink = state.activePreset.signalFlow.cabCabLink,
                favoritesCount = state.favoritesCount,
                onResetCenter = { onResetCenter(block.id) },
                onFactoryDefault = { onFactoryDefault(block.id) },
                onUserDefault = { onUserDefault(block.id) },
                onSaveUserDefault = { onSaveUserDefault(block.id) },
                onAddFavorite = { onAddFavorite(block.id) },
                onToggleAmpCabLink = onToggleAmpCabLink,
                onToggleCabCabLink = onToggleCabCabLink,
                modifier = Modifier
                    .weight(0.24f)
                    .height(231.dp),
            )
        }
    }
}

@Composable
private fun FiveZoneXyMorphPad(
    block: RigBlockState,
    stageLocked: Boolean,
    onDragXY: (Float, Float) -> Unit,
) {
    val zone = FocusZoneBank.forBlock(block)
    val accent = block.category.composeColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(StadiumColors.SurfaceDeep)
            .border(1.dp, if (stageLocked) StadiumColors.DistortionAmber.copy(alpha = 0.5f) else StadiumColors.BorderStrong, RoundedCornerShape(10.dp))
            .pointerInput(block.id, stageLocked) {
                if (stageLocked) return@pointerInput
                detectTapGestures { pos ->
                    val nx = ((pos.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
                    val ny = (1f - (pos.y / size.height) * 2f).coerceIn(-1f, 1f)
                    onDragXY(nx, ny)
                }
            }
            .pointerInput(block.id, stageLocked) {
                if (stageLocked) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val nx = ((change.position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
                    val ny = (1f - (change.position.y / size.height) * 2f).coerceIn(-1f, 1f)
                    onDragXY(nx, ny)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridColor = Color.White.copy(alpha = 0.06f)
            // Crosshair axes
            drawLine(gridColor, Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), strokeWidth = 1.5f)
            drawLine(gridColor, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), strokeWidth = 1.5f)
            // Subtle concentric tuning rings
            drawCircle(gridColor, radius = size.minDimension * 0.22f, style = Stroke(1f))
            drawCircle(gridColor, radius = size.minDimension * 0.42f, style = Stroke(1f))

            // Glowing Focus Lens Thumb
            val px = ((block.focusX + 1f) * 0.5f).coerceIn(0.06f, 0.94f) * size.width
            val py = ((1f - block.focusY) * 0.5f).coerceIn(0.08f, 0.92f) * size.height
            val center = Offset(px, py)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.15f), Color.Transparent),
                    center = center,
                    radius = 48f,
                ),
                radius = 48f,
                center = center,
            )
            drawCircle(color = Color.White, radius = 17f, center = center, style = Stroke(width = 2.5f))
            drawCircle(color = Color.White, radius = 4.5f, center = center)
        }

        // 5 Authentic Zone Chips
        ZoneChip(zone.topLeftLabel, Modifier.align(Alignment.TopStart).padding(8.dp))
        ZoneChip(zone.topRightLabel, Modifier.align(Alignment.TopEnd).padding(8.dp))
        ZoneChip(zone.bottomLeftLabel, Modifier.align(Alignment.BottomStart).padding(8.dp))
        ZoneChip(zone.bottomRightLabel, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        ZoneChip(
            text = "Home: ${zone.centerLabel}",
            modifier = Modifier.align(Alignment.Center),
            subtle = true,
        )
    }
}

@Composable
private fun ZoneChip(text: String, modifier: Modifier = Modifier, subtle: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = if (subtle) 0.58f else 0.72f))
            .border(1.dp, Color.White.copy(alpha = if (subtle) 0.12f else 0.18f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (subtle) StadiumColors.TextSecondary else Color.White,
        )
    }
}

@Composable
private fun SpeakerCabMicFocusPad(
    block: RigBlockState,
    spectrumDb: List<Float>,
    externalIrLabel: String?,
    stageLocked: Boolean,
    onDragMic: (Float, Float) -> Unit,
    onSelectMic: (Int) -> Unit,
    onToggleAngle: () -> Unit,
    onImportIr: () -> Unit,
) {
    val pos = block.params["cab.position"] ?: 0.25f
    val dist = block.params["cab.distance_inch"] ?: 1.8f
    val angle = block.params["cab.angle_deg"] ?: 0f
    val micId = (block.params["cab.mic_model"] ?: 0f).toInt()
    val micSpec = FactoryCabCatalog.micById(micId)
    val (uMic, vMic) = CabMicCoordinateMapper.cabParamsToCanvas(pos, dist)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(StadiumColors.SurfaceDeep)
            .border(1.dp, StadiumColors.CabPink.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Left: Procedural 2D/3D Speaker Cone + Draggable Virtual Mic Node
            Box(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0E0E0E))
                    .pointerInput(stageLocked) {
                        if (stageLocked) return@pointerInput
                        detectDragGestures { change, _ ->
                            change.consume()
                            val u = (change.position.x / size.width).coerceIn(0f, 1f)
                            val v = (change.position.y / size.height).coerceIn(0f, 1f)
                            val (p, d) = CabMicCoordinateMapper.canvasToCabParams(u, v)
                            onDragMic(p, d)
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width * 0.5f, size.height * 0.5f)
                    val outerR = size.minDimension * 0.44f
                    // Speaker surround, cone ribs, and center dust cap
                    drawCircle(Color(0xFF262626), radius = outerR, center = c, style = Stroke(6f))
                    drawCircle(Color(0xFF1A1A1A), radius = outerR * 0.92f, center = c)
                    drawCircle(Color(0xFF2C2C2C), radius = outerR * 0.68f, center = c, style = Stroke(1.5f))
                    drawCircle(Color(0xFF202020), radius = outerR * 0.34f, center = c)
                    drawCircle(Color(0xFF424242), radius = outerR * 0.34f, center = c, style = Stroke(2f))

                    // Draggable Virtual Mic Capsule
                    val mx = uMic.coerceIn(0.10f, 0.90f) * size.width
                    val my = vMic.coerceIn(0.12f, 0.88f) * size.height
                    val micPos = Offset(mx, my)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(StadiumColors.CabPink.copy(alpha = 0.85f), Color.Transparent),
                            center = micPos,
                            radius = 36f,
                        ),
                        radius = 36f,
                        center = micPos,
                    )
                    drawCircle(StadiumColors.CabPink, radius = 14f, center = micPos)
                    drawCircle(Color.White, radius = 14f, center = micPos, style = Stroke(2f))
                }

                Text(
                    text = externalIrLabel ?: "${block.displayName} • ${micSpec.badgeCode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StadiumColors.CabPink,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
                Text(
                    text = String.format("Pos %.0f%% • %.1f\" • %.0f°", pos * 100f, dist, angle),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }

            // Right: 64-Bin IR Magnitude Spectrum Plot + Angle / Load External WAV IR
            Column(
                modifier = Modifier.weight(0.45f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0E0E0E))
                        .border(1.dp, StadiumColors.BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(6.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (spectrumDb.size >= 2) {
                            val path = Path()
                            spectrumDb.forEachIndexed { idx, db ->
                                val x = (idx.toFloat() / (spectrumDb.size - 1)) * size.width
                                val yNorm = (1f - ((db + 48f) / 60f)).coerceIn(0f, 1f)
                                val y = yNorm * size.height
                                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color = StadiumColors.CabPink, style = Stroke(width = 2.5f))
                        }
                    }
                    Text(
                        text = "IR 2048-Tap Spectrum",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = StadiumColors.TextMuted,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(StadiumColors.SurfaceCard)
                            .border(1.dp, StadiumColors.BorderStrong, RoundedCornerShape(5.dp))
                            .clickable(enabled = !stageLocked, onClick = onToggleAngle)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text("Angle ${angle.toInt()}°", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(StadiumColors.CabPink.copy(alpha = 0.22f))
                            .border(1.dp, StadiumColors.CabPink, RoundedCornerShape(5.dp))
                            .clickable(onClick = onImportIr)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Load .WAV IR", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }

        // 12 Studio Microphones Quick Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FactoryCabCatalog.allMics.forEach { mic ->
                val sel = mic.nativeId == micId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (sel) StadiumColors.CabPink else StadiumColors.SurfaceCard)
                        .clickable(enabled = !stageLocked) { onSelectMic(mic.nativeId) }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = mic.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) Color.Black else StadiumColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveEqCurveFocusPad(
    block: RigBlockState,
    curveDb: List<Float>,
    stageLocked: Boolean,
    onUpdateBand: (String, String, Float, Float) -> Unit,
) {
    val bands = listOf(
        Triple("L", "eq.low_freq", "eq.low_gain_db"),
        Triple("M1", "eq.mid1_freq", "eq.mid1_gain_db"),
        Triple("M2", "eq.mid2_freq", "eq.mid2_gain_db"),
        Triple("H", "eq.high_freq", "eq.high_gain_db"),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(StadiumColors.SurfaceDeep)
            .border(1.dp, StadiumColors.EqGold.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .pointerInput(stageLocked) {
                if (stageLocked) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                    val freq = EqCurveCoordinateMapper.normXToFreq(nx)
                    val gain = EqCurveCoordinateMapper.normYToGainDb(ny)
                    val target = when {
                        nx < 0.28f -> bands[0]
                        nx < 0.52f -> bands[1]
                        nx < 0.76f -> bands[2]
                        else -> bands[3]
                    }
                    onUpdateBand(target.second, target.third, freq, gain)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            // 0 dB center line
            drawLine(Color.White.copy(alpha = 0.16f), Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 1.5f)
            // Octave vertical guide lines (100, 500, 1k, 4k, 10k)
            listOf(100f, 500f, 1000f, 4000f, 10000f).forEach { f ->
                val x = EqCurveCoordinateMapper.freqToNormX(f) * size.width
                drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f)
            }

            // Composite EQ magnitude curve from C++20 biquad evaluator
            if (curveDb.size >= 2) {
                val path = Path()
                curveDb.forEachIndexed { idx, db ->
                    val x = (idx.toFloat() / (curveDb.size - 1)) * size.width
                    val y = EqCurveCoordinateMapper.gainDbToNormY(db) * size.height
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = StadiumColors.EqGold, style = Stroke(width = 3f))
            }

            // Draggable Band Handles
            bands.forEach { (_, fk, gk) ->
                val f = block.params[fk] ?: 1000f
                val g = block.params[gk] ?: 0f
                val cx = EqCurveCoordinateMapper.freqToNormX(f) * size.width
                val cy = EqCurveCoordinateMapper.gainDbToNormY(g) * size.height
                drawCircle(StadiumColors.EqGold, radius = 9f, center = Offset(cx, cy))
                drawCircle(Color.White, radius = 9f, center = Offset(cx, cy), style = Stroke(2f))
            }
        }

        Text(
            text = "Interactive Studio EQ • 20 Hz – 20 kHz (±15 dB) • Drag Node",
            style = MaterialTheme.typography.labelSmall,
            color = StadiumColors.EqGold,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
    }
}

@Composable
private fun DynamicsGainReductionBar(
    isGate: Boolean,
    telemetryFlow: StateFlow<LiveTelemetryUi>,
) {
    val t by telemetryFlow.collectAsStateWithLifecycle()
    val grDb = if (isGate) t.gateGrDb else t.compGrDb
    val norm = (grDb / 42f).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .width(34.dp)
            .height(215.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(StadiumColors.SurfaceCard)
            .border(1.dp, StadiumColors.DynamicsGold.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("GR", style = MaterialTheme.typography.labelSmall, color = StadiumColors.DynamicsGold)
        Box(
            modifier = Modifier
                .width(12.dp)
                .weight(1f)
                .clip(RoundedCornerShape(3.dp))
                .background(StadiumColors.SurfaceSliderTrack),
            contentAlignment = Alignment.TopCenter, // GR drops downward from 0 dB!
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(norm.coerceAtLeast(0.04f))
                    .background(StadiumColors.DynamicsGold),
            )
        }
        Text(
            text = String.format("-%.0f", grDb),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = StadiumColors.DynamicsGold,
        )
    }
}

@Composable
private fun NeuralCloneFocusCard(
    meta: CloneMetadataUi,
    onImportClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(StadiumColors.SurfaceDeep)
            .border(1.dp, StadiumColors.AmpRed.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PROXY / NEURAL CLONE ENGINE (BATCH-16 NEON)", style = MaterialTheme.typography.labelSmall, color = StadiumColors.AmpRed)
            Text(
                text = if (meta.loaded) "${meta.brand} — ${meta.product}" else "Built-In Reference WaveNet / GRU Slot",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = "Architecture: ${meta.architecture.ifBlank { "WaveNet / GRU" }} • Ref Input: ${String.format("%+.1f dBu", meta.refInputDbu)}",
                style = MaterialTheme.typography.labelMedium,
                color = StadiumColors.BadgeCyan,
            )
            Text(
                text = meta.notes.ifBlank { "Drop any .nam (Tone3000 WaveNet) or .aidax (GRU) profile via Android SAF." },
                style = MaterialTheme.typography.bodySmall,
                color = StadiumColors.TextSecondary,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(StadiumColors.AmpRed)
                .clickable(onClick = onImportClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("Import .NAM / .AIDAX File (SAF)", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

@Composable
private fun FocusRightSidebar(
    block: RigBlockState,
    ampCabLink: Boolean,
    cabCabLink: Boolean,
    favoritesCount: Int,
    onResetCenter: () -> Unit,
    onFactoryDefault: () -> Unit,
    onUserDefault: () -> Unit,
    onSaveUserDefault: () -> Unit,
    onAddFavorite: () -> Unit,
    onToggleAmpCabLink: () -> Unit,
    onToggleCabCabLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val basedOnText = when (block.category) {
        BlockCategory.AMP -> AmpModelCatalog.byId(block.modelId).basedOn
        BlockCategory.CAB -> FactoryCabCatalog.cabById(block.modelId).basedOn
        else -> FxModelCatalog.byCategory(block.category).firstOrNull { it.nativeId == block.modelId }?.basedOn
            ?: block.displayName
    }

    Column(
        modifier = modifier
            .background(StadiumColors.SurfaceDeep)
            .border(1.dp, StadiumColors.BorderSubtle)
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SidebarActionButton("Factory Default", "↺", onFactoryDefault)
            SidebarActionButton("User Default", "☆", onUserDefault)
            SidebarActionButton("Save User Default", "✓", onSaveUserDefault)
            SidebarActionButton("Add Favorite ($favoritesCount)", "★", onAddFavorite, accent = StadiumColors.GoldLink)
            SidebarActionButton("Reset Focus XY", "◎", onResetCenter)
            if (block.category == BlockCategory.AMP || block.category == BlockCategory.CAB) {
                SidebarActionButton(
                    label = if (block.category == BlockCategory.AMP) "Amp > Cab Link" else "Cab > Cab Link",
                    glyph = if (if (block.category == BlockCategory.AMP) ampCabLink else cabCabLink) "ON" else "OFF",
                    onClick = if (block.category == BlockCategory.AMP) onToggleAmpCabLink else onToggleCabCabLink,
                    accent = StadiumColors.GoldLink,
                )
            }
        }

        Text(
            text = "*Based on: $basedOnText",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = StadiumColors.TextMuted,
            maxLines = 3,
        )
    }
}

@Composable
private fun SidebarActionButton(
    label: String,
    glyph: String,
    onClick: () -> Unit,
    accent: Color = StadiumColors.TextSecondary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(StadiumColors.SurfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = StadiumColors.TextPrimary)
        Text(glyph, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}
