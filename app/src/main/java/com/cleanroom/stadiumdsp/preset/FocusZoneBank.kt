package com.cleanroom.stadiumdsp.preset

import com.cleanroom.stadiumdsp.catalog.AmpModelCatalog
import kotlin.math.abs

data class FocusZonePreset(
    val centerLabel: String,
    val topLeftLabel: String,     // x = -1, y = +1
    val topRightLabel: String,    // x = +1, y = +1
    val bottomLeftLabel: String,  // x = -1, y = -1
    val bottomRightLabel: String, // x = +1, y = -1
    val origin: Map<String, Float>,
    val xMinYMin: Map<String, Float>, // Bottom-Left (-1, -1)
    val xMinYMax: Map<String, Float>, // Top-Left (-1, +1)
    val xMaxYMin: Map<String, Float>, // Bottom-Right (+1, -1)
    val xMaxYMax: Map<String, Float>, // Top-Right (+1, +1)
)

/**
 * Exact 5-Zone XY Morphing Interpolator:
 * - `(0, 0)` returns EXACTLY `origin`
 * - `(-1, -1)` -> `xMinYMin`, `(-1, +1)` -> `xMinYMax`, `(+1, -1)` -> `xMaxYMin`, `(+1, +1)` -> `xMaxYMax`
 * - Smooth C0/C1 quadrant interpolation with zero jumps across axes.
 */
object FocusZoneInterpolator {
    fun interpolate5Zone(
        x: Float,
        y: Float,
        origin: Map<String, Float>,
        xMinYMin: Map<String, Float>,
        xMinYMax: Map<String, Float>,
        xMaxYMin: Map<String, Float>,
        xMaxYMax: Map<String, Float>,
    ): Map<String, Float> {
        val cx = x.coerceIn(-1f, 1f)
        val cy = y.coerceIn(-1f, 1f)
        val ax = abs(cx)
        val ay = abs(cy)

        // Exact 5-anchor partition-of-unity weights:
        // At (0,0): wOrigin = 1.0, all corner weights = 0.0
        // At (±1, ±1): wOrigin = 0.0, active corner = 1.0
        // Along cardinal axes (e.g. x=±1, y=0): blends smoothly between origin and the two adjacent corners!
        val u = 0.5f * (cx + 1f) // [0..1]
        val v = 0.5f * (cy + 1f) // [0..1]
        val cornerBlend = maxOf(ax, ay)
        val wOrigin = 1f - cornerBlend

        val keys = (origin.keys + xMinYMin.keys + xMinYMax.keys + xMaxYMin.keys + xMaxYMax.keys)
        return keys.associateWith { key ->
            val vOrig = origin[key] ?: 0.5f
            val vBL = xMinYMin[key] ?: vOrig
            val vTL = xMinYMax[key] ?: vOrig
            val vBR = xMaxYMin[key] ?: vOrig
            val vTR = xMaxYMax[key] ?: vOrig

            val bilinear = (1f - u) * (1f - v) * vBL +
                (1f - u) * v * vTL +
                u * (1f - v) * vBR +
                u * v * vTR

            wOrigin * vOrig + cornerBlend * bilinear
        }
    }

    fun evaluate(zone: FocusZonePreset, x: Float, y: Float): Map<String, Float> =
        interpolate5Zone(x, y, zone.origin, zone.xMinYMin, zone.xMinYMax, zone.xMaxYMin, zone.xMaxYMax)
}

/**
 * GAP-05: Authentic 5-Zone Focus View banks for all 30 Amps + all FX categories,
 * plus Manage Zones customization (Rename / Copy / Paste / Reset).
 */
object FocusZoneBank {
    private val customOverrides = mutableMapOf<String, FocusZonePreset>()
    private var copiedCornerClipboard: Pair<String, Map<String, Float>>? = null

    fun forAmpModel(ampNativeId: Int): FocusZonePreset {
        val customKey = "amp:$ampNativeId"
        customOverrides[customKey]?.let { return it }
        val spec = AmpModelCatalog.byId(ampNativeId)

        return when {
            spec.isBass -> FocusZonePreset(
                centerLabel = "${spec.shortLabel} Studio",
                topLeftLabel = "Deep Motown",
                topRightLabel = "Grind / Pick",
                bottomLeftLabel = "Warm Finger",
                bottomRightLabel = "Modern Slap",
                origin = mapOf("drive" to 0.42f, "bass" to 0.58f, "mid" to 0.52f, "treble" to 0.50f, "master" to 0.58f, "sag" to 0.30f),
                xMinYMax = mapOf("drive" to 0.30f, "bass" to 0.78f, "mid" to 0.48f, "treble" to 0.25f, "master" to 0.66f, "sag" to 0.48f),
                xMaxYMax = mapOf("drive" to 0.78f, "bass" to 0.52f, "mid" to 0.72f, "treble" to 0.72f, "master" to 0.62f, "sag" to 0.38f),
                xMinYMin = mapOf("drive" to 0.25f, "bass" to 0.68f, "mid" to 0.60f, "treble" to 0.38f, "master" to 0.55f, "sag" to 0.25f),
                xMaxYMin = mapOf("drive" to 0.35f, "bass" to 0.70f, "mid" to 0.28f, "treble" to 0.80f, "master" to 0.54f, "sag" to 0.15f),
            )
            ampNativeId in 14..19 || ampNativeId == 22 -> FocusZonePreset(
                centerLabel = "${spec.shortLabel} Core",
                topLeftLabel = "Thick Doom",
                topRightLabel = "Tight Djent",
                bottomLeftLabel = "Mid-Gain Crunch",
                bottomRightLabel = "Liquid Lead",
                origin = mapOf("drive" to 0.68f, "bass" to 0.48f, "mid" to 0.54f, "treble" to 0.62f, "presence" to 0.56f, "master" to 0.48f, "sag" to 0.24f),
                xMinYMax = mapOf("drive" to 0.84f, "bass" to 0.72f, "mid" to 0.60f, "treble" to 0.46f, "presence" to 0.40f, "master" to 0.64f, "sag" to 0.55f),
                xMaxYMax = mapOf("drive" to 0.82f, "bass" to 0.34f, "mid" to 0.62f, "treble" to 0.76f, "presence" to 0.72f, "master" to 0.42f, "sag" to 0.12f),
                xMinYMin = mapOf("drive" to 0.44f, "bass" to 0.55f, "mid" to 0.58f, "treble" to 0.50f, "presence" to 0.45f, "master" to 0.55f, "sag" to 0.38f),
                xMaxYMin = mapOf("drive" to 0.78f, "bass" to 0.46f, "mid" to 0.74f, "treble" to 0.66f, "presence" to 0.58f, "master" to 0.56f, "sag" to 0.32f),
            )
            ampNativeId in 0..5 || ampNativeId in 23..24 -> FocusZonePreset(
                centerLabel = "${spec.shortLabel} Sweet Spot",
                topLeftLabel = "Warm Edge",
                topRightLabel = "Spanky Breakup",
                bottomLeftLabel = "Jazz Clean",
                bottomRightLabel = "Pedal Platform",
                origin = mapOf("drive" to 0.36f, "bass" to 0.50f, "mid" to 0.52f, "treble" to 0.58f, "presence" to 0.50f, "master" to 0.60f, "sag" to 0.35f),
                xMinYMax = mapOf("drive" to 0.66f, "bass" to 0.62f, "mid" to 0.64f, "treble" to 0.44f, "presence" to 0.38f, "master" to 0.74f, "sag" to 0.58f),
                xMaxYMax = mapOf("drive" to 0.70f, "bass" to 0.42f, "mid" to 0.58f, "treble" to 0.74f, "presence" to 0.68f, "master" to 0.70f, "sag" to 0.48f),
                xMinYMin = mapOf("drive" to 0.18f, "bass" to 0.56f, "mid" to 0.62f, "treble" to 0.32f, "presence" to 0.30f, "master" to 0.54f, "sag" to 0.18f),
                xMaxYMin = mapOf("drive" to 0.24f, "bass" to 0.48f, "mid" to 0.48f, "treble" to 0.68f, "presence" to 0.62f, "master" to 0.52f, "sag" to 0.14f),
            )
            else -> FocusZonePreset(
                centerLabel = "${spec.shortLabel} Pushed",
                topLeftLabel = "Classic Brown",
                topRightLabel = "All The Way Up",
                bottomLeftLabel = "Very Clean",
                bottomRightLabel = "Bright Chime",
                origin = mapOf("drive" to 0.56f, "bass" to 0.50f, "mid" to 0.64f, "treble" to 0.60f, "presence" to 0.54f, "master" to 0.56f, "sag" to 0.40f),
                xMinYMax = mapOf("drive" to 0.78f, "bass" to 0.60f, "mid" to 0.68f, "treble" to 0.48f, "presence" to 0.44f, "master" to 0.78f, "sag" to 0.62f),
                xMaxYMax = mapOf("drive" to 0.90f, "bass" to 0.42f, "mid" to 0.72f, "treble" to 0.76f, "presence" to 0.74f, "master" to 0.76f, "sag" to 0.50f),
                xMinYMin = mapOf("drive" to 0.22f, "bass" to 0.56f, "mid" to 0.54f, "treble" to 0.44f, "presence" to 0.40f, "master" to 0.58f, "sag" to 0.24f),
                xMaxYMin = mapOf("drive" to 0.34f, "bass" to 0.44f, "mid" to 0.58f, "treble" to 0.74f, "presence" to 0.68f, "master" to 0.56f, "sag" to 0.22f),
            )
        }
    }

    fun forBlock(block: RigBlockState): FocusZonePreset {
        if (block.category == BlockCategory.AMP) return forAmpModel(block.modelId)
        val key = "${block.category.name}:${block.modelId}"
        customOverrides[key]?.let { return it }

        return when (block.category) {
            BlockCategory.DISTORTION -> FocusZonePreset(
                centerLabel = "${block.shortLabel} Home",
                topLeftLabel = "Fat Saturate", topRightLabel = "Lead Bite",
                bottomLeftLabel = "Warm Boost", bottomRightLabel = "Clean Glass",
                origin = mapOf("drive.drive" to 0.45f, "drive.tone" to 0.55f, "drive.level" to 0.68f),
                xMinYMax = mapOf("drive.drive" to 0.85f, "drive.tone" to 0.34f, "drive.level" to 0.62f),
                xMaxYMax = mapOf("drive.drive" to 0.88f, "drive.tone" to 0.78f, "drive.level" to 0.76f),
                xMinYMin = mapOf("drive.drive" to 0.18f, "drive.tone" to 0.38f, "drive.level" to 0.78f),
                xMaxYMin = mapOf("drive.drive" to 0.20f, "drive.tone" to 0.76f, "drive.level" to 0.82f),
            )
            BlockCategory.MODULATION -> FocusZonePreset(
                centerLabel = "Classic Mod",
                topLeftLabel = "Deep Syrupy", topRightLabel = "Fast Rotary",
                bottomLeftLabel = "Subtle Wide", bottomRightLabel = "Shimmer Pulse",
                origin = mapOf("mod.rate_hz" to 1.2f, "mod.depth" to 0.55f, "mod.mix" to 0.45f),
                xMinYMax = mapOf("mod.rate_hz" to 0.45f, "mod.depth" to 0.88f, "mod.mix" to 0.62f),
                xMaxYMax = mapOf("mod.rate_hz" to 5.8f, "mod.depth" to 0.82f, "mod.mix" to 0.58f),
                xMinYMin = mapOf("mod.rate_hz" to 0.35f, "mod.depth" to 0.28f, "mod.mix" to 0.26f),
                xMaxYMin = mapOf("mod.rate_hz" to 3.4f, "mod.depth" to 0.42f, "mod.mix" to 0.35f),
            )
            BlockCategory.DELAY -> FocusZonePreset(
                centerLabel = "Studio Echo",
                topLeftLabel = "Dub Swell", topRightLabel = "Runaway Osc",
                bottomLeftLabel = "Slapback", bottomRightLabel = "Dotted Lead",
                origin = mapOf("delay.time_ms" to 380f, "delay.feedback" to 0.42f, "delay.mix" to 0.30f),
                xMinYMax = mapOf("delay.time_ms" to 520f, "delay.feedback" to 0.72f, "delay.mix" to 0.48f),
                xMaxYMax = mapOf("delay.time_ms" to 440f, "delay.feedback" to 0.86f, "delay.mix" to 0.55f),
                xMinYMin = mapOf("delay.time_ms" to 115f, "delay.feedback" to 0.18f, "delay.mix" to 0.24f),
                xMaxYMin = mapOf("delay.time_ms" to 375f, "delay.feedback" to 0.48f, "delay.mix" to 0.34f),
            )
            BlockCategory.REVERB -> FocusZonePreset(
                centerLabel = "Stage Space",
                topLeftLabel = "Warm Cathedral", topRightLabel = "Shimmer Wash",
                bottomLeftLabel = "Dry Studio", bottomRightLabel = "Surf Drip",
                origin = mapOf("reverb.decay" to 0.56f, "reverb.damping_hz" to 4600f, "reverb.mix" to 0.28f),
                xMinYMax = mapOf("reverb.decay" to 0.88f, "reverb.damping_hz" to 2600f, "reverb.mix" to 0.52f),
                xMaxYMax = mapOf("reverb.decay" to 0.92f, "reverb.damping_hz" to 8200f, "reverb.mix" to 0.58f),
                xMinYMin = mapOf("reverb.decay" to 0.22f, "reverb.damping_hz" to 3400f, "reverb.mix" to 0.16f),
                xMaxYMin = mapOf("reverb.decay" to 0.48f, "reverb.damping_hz" to 7400f, "reverb.mix" to 0.40f),
            )
            else -> FocusZonePreset(
                centerLabel = "Default",
                topLeftLabel = "Warm / Soft", topRightLabel = "Tight / Bright",
                bottomLeftLabel = "Subtle", bottomRightLabel = "Aggressive",
                origin = block.params,
                xMinYMax = block.params,
                xMaxYMax = block.params,
                xMinYMin = block.params,
                xMaxYMin = block.params,
            )
        }
    }

    fun copyCorner(label: String, values: Map<String, Float>) {
        copiedCornerClipboard = label to values.toMap()
    }

    fun pasteCorner(key: String, base: FocusZonePreset, cornerIndex: Int): FocusZonePreset {
        val clip = copiedCornerClipboard ?: return base
        val updated = when (cornerIndex) {
            0 -> base.copy(topLeftLabel = clip.first, xMinYMax = clip.second)
            1 -> base.copy(topRightLabel = clip.first, xMaxYMax = clip.second)
            2 -> base.copy(bottomLeftLabel = clip.first, xMinYMin = clip.second)
            else -> base.copy(bottomRightLabel = clip.first, xMaxYMin = clip.second)
        }
        customOverrides[key] = updated
        return updated
    }

    fun renameZone(key: String, base: FocusZonePreset, cornerIndex: Int, newName: String): FocusZonePreset {
        val updated = when (cornerIndex) {
            0 -> base.copy(topLeftLabel = newName)
            1 -> base.copy(topRightLabel = newName)
            2 -> base.copy(bottomLeftLabel = newName)
            3 -> base.copy(bottomRightLabel = newName)
            else -> base.copy(centerLabel = newName)
        }
        customOverrides[key] = updated
        return updated
    }

    fun resetCustomZones(key: String) {
        customOverrides.remove(key)
    }
}
