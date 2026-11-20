package com.cleanroom.stadiumdsp.catalog

import kotlin.math.roundToInt

data class ParameterSchema(
    val key: String,
    val displayName: String,
    val minValue: Float,
    val maxValue: Float,
    val defaultValue: Float,
    val unit: String = "",
    val step: Float = 0.01f,
    val circuitDescription: String,
) {
    fun formatValue(value: Float): String {
        val clamped = value.coerceIn(minValue, maxValue)
        return when (unit) {
            "dB" -> String.format("%+.1f dB", clamped)
            "Hz" -> if (clamped >= 1000f) String.format("%.2f kHz", clamped / 1000f) else "${clamped.roundToInt()} Hz"
            "ms" -> "${clamped.roundToInt()} ms"
            "\"" -> String.format("%.1f\"", clamped)
            "°" -> "${clamped.roundToInt()}°"
            "%" -> "${(clamped * 100f).roundToInt()}%"
            "st" -> String.format("%+.0f st", clamped)
            "BPM" -> String.format("%.1f BPM", clamped)
            else -> String.format("%.1f", clamped * 10f)
        }
    }
}

/**
 * Central schema dictionary powering Value-Bar Sliders and double-tap Deep-Edit Popover descriptions.
 */
object ParameterSchemaRegistry {
    private val schemas: Map<String, ParameterSchema> = listOf(
        // Amp Parameters
        ParameterSchema("amp.drive", "Drive", 0f, 1f, 0.50f, "", 0.01f, "Input preamp gain driving cascaded triode clipping stages and coupled bright-cap divider."),
        ParameterSchema("amp.bass", "Bass", 0f, 1f, 0.50f, "", 0.01f, "Exact 3rd-order FMV or 4th-order R.T. James low-frequency passive tone stack control."),
        ParameterSchema("amp.mid", "Mid", 0f, 1f, 0.50f, "", 0.01f, "Interactive midrange contour coupled directly to Bass and Treble passive impedance nodes."),
        ParameterSchema("amp.treble", "Treble", 0f, 1f, 0.50f, "", 0.01f, "High-frequency tone stack wiper governing upper harmonic attack and chime."),
        ParameterSchema("amp.presence", "Presence", 0f, 1f, 0.50f, "", 0.01f, "Negative feedback (NFB) high-frequency bypass shaping power-amp upper-mid brilliance."),
        ParameterSchema("amp.master", "Master", 0f, 1f, 0.55f, "", 0.01f, "Power-tube drive into phase inverter, output transformer core saturation, and reactive cab Z_cab."),
        ParameterSchema("amp.ch_vol", "Ch Vol", 0f, 1f, 0.70f, "", 0.01f, "Transparent post-amp makeup output level for balancing volume across snapshots and presets."),
        ParameterSchema("amp.sag", "Sag", 0f, 1f, 0.45f, "", 0.01f, "B+ tube rectifier rail droop under heavy pick transients for spongy bloom and compression."),
        ParameterSchema("amp.ripple", "Ripple", 0f, 1f, 0.25f, "", 0.01f, "Asymmetric 31%-duty full-wave rectifier 120 Hz power supply ripple intermodulation."),
        ParameterSchema("amp.bias", "Bias", 0f, 1f, 0.50f, "", 0.01f, "Power tube idle bias from cold AB crossover crunch to warm Class-A rich even harmonics."),
        ParameterSchema("amp.bias_x", "Bias X", 0f, 1f, 0.50f, "", 0.01f, "Dynamic grid-current blocking excursion shifting operating point on sustained high-gain chords."),
        ParameterSchema("amp.hype", "Hype", 0f, 1f, 0.00f, "%", 0.01f, "Morphs continuously from 0% Stock hardware calibration to 100% idealized studio-ready Hype polish."),
        ParameterSchema("amp.deep", "Deep", 0f, 1f, 0.50f, "", 0.01f, "Sub-100 Hz active negative-feedback low-end resonance contour."),
        ParameterSchema("amp.cut", "Cut", 0f, 1f, 0.25f, "", 0.01f, "Post-phase-inverter high-frequency attenuation (classic Vox Top Boost Cut control)."),
        ParameterSchema("amp.geq_v_scoop", "GEQ V-Scoop", 0f, 1f, 0.75f, "%", 0.01f, "5-Band Mesa Mark IIC+ inductor graphic EQ classic V-curve depth."),

        // Cab & IR Parameters
        ParameterSchema("cab.position", "Mic Position", 0f, 1f, 0.25f, "%", 0.01f, "Horizontal mic placement from Center Dust Cap (0%, bright) through Cap Edge to Cone Rim (100%, warm)."),
        ParameterSchema("cab.distance_inch", "Distance", 1f, 12f, 1.5f, "\"", 0.1f, "Acoustic air distance (1.0\"–12.0\") modeling proximity effect and speed-of-sound Auto Air Delay."),
        ParameterSchema("cab.angle_deg", "Mic Angle", 0f, 45f, 0f, "°", 45f, "On-axis (0°) vs off-axis (45°) capsule orientation smoothing harsh upper-mid sizzle."),
        ParameterSchema("cab.low_cut_hz", "Low Cut", 20f, 300f, 65f, "Hz", 5f, "High-pass filter tightening sub-bass rumble before FOH PA or bass mix."),
        ParameterSchema("cab.high_cut_hz", "High Cut", 2500f, 20000f, 9500f, "Hz", 100f, "Low-pass filter rolling off ultrasonic fizz above guitar speaker breakup."),
        ParameterSchema("cab.level_db", "Cab Level", -24f, 12f, 0f, "dB", 0.5f, "Cab slot output level for balancing Dual Cab Slot A and Slot B blend."),
        ParameterSchema("cab.pan", "Cab Pan", -1f, 1f, 0f, "", 0.05f, "Stereo panning position for Dual Cab stereo imaging."),

        // Neural Clone Parameters
        ParameterSchema("clone.input_gain_db", "Input Gain", -24f, 24f, 0f, "dB", 0.5f, "Pre-model trim for driving a captured .nam or .aidax neural profile harder or cleaner."),
        ParameterSchema("clone.output_gain_db", "Output Gain", -24f, 24f, 0f, "dB", 0.5f, "Post-model makeup gain."),
        ParameterSchema("clone.interface_max_dbu", "Interface dBu", 0f, 24f, 12f, "dB", 0.5f, "Audio interface 0 dBFS headroom calibration matching the capture reference input_level_dbu."),
        ParameterSchema("clone.low_cut_hz", "Low Cut", 20f, 250f, 20f, "Hz", 5f, "Post-clone high-pass filter."),
        ParameterSchema("clone.high_cut_hz", "High Cut", 2500f, 20000f, 20000f, "Hz", 100f, "Post-clone low-pass filter."),

        // Drive / Fuzz
        ParameterSchema("drive.drive", "Gain", 0f, 1f, 0.45f, "", 0.01f, "Overdrive / Fuzz clipping saturation depth."),
        ParameterSchema("drive.tone", "Tone", 0f, 1f, 0.55f, "", 0.01f, "Hardware-specific tone control (active treble, inverted RAT filter, or Big Muff MNA stack)."),
        ParameterSchema("drive.level", "Level", 0f, 1f, 0.65f, "", 0.01f, "Pedal output volume capable of pushing tube preamp stages."),
        ParameterSchema("drive.bass_cut", "Bass Cut", 0f, 1f, 0.25f, "", 0.01f, "Pre-clipping low-end tightness control (Timmy Bass Cut / Obsidian Grunt)."),

        // Dynamics
        ParameterSchema("gate.threshold_db", "Threshold", -90f, -10f, -52f, "dB", 1f, "Horizon sidechain 850 Hz HPF hysteresis noise gate trigger threshold."),
        ParameterSchema("gate.decay_ms", "Decay", 10f, 500f, 45f, "ms", 5f, "Gate release envelope speed."),
        ParameterSchema("comp.peak_reduction", "Peak Red.", 0f, 1f, 0.45f, "%", 0.01f, "Optical T4B or VCA compression depth driving yellow GR meter."),
        ParameterSchema("comp.gain_db", "Makeup", -6f, 24f, 4f, "dB", 0.5f, "Post-compression makeup gain."),
        ParameterSchema("comp.mix", "Mix", 0f, 1f, 0.80f, "%", 0.01f, "Parallel dry/compressed blend preserving natural pick attack."),

        // Wah / Filter
        ParameterSchema("wah.position", "Position", 0f, 1f, 0.50f, "%", 0.01f, "Treadle sweep position (maps directly to EXP 1 with Auto-Engage)."),
        ParameterSchema("wah.q_resonance", "Resonance", 0f, 1f, 0.55f, "%", 0.01f, "Inductor / Vactrol filter vocal peak resonance."),
        ParameterSchema("wah.mix", "Mix", 0f, 1f, 1.00f, "%", 0.01f, "Dry/Wet filter blend."),

        // Modulation
        ParameterSchema("mod.rate_hz", "Rate", 0.08f, 12f, 1.2f, "Hz", 0.05f, "LFO modulation speed (or Tempo-Synced note subdivision)."),
        ParameterSchema("mod.depth", "Depth", 0f, 1f, 0.55f, "%", 0.01f, "Modulation excursion intensity."),
        ParameterSchema("mod.mix", "Mix", 0f, 1f, 0.45f, "%", 0.01f, "Dry/Wet modulation mix."),
        ParameterSchema("mod.feedback", "Feedback", 0f, 0.92f, 0.35f, "%", 0.01f, "Resonant regeneration for Flanger and Phaser stages."),

        // Pitch
        ParameterSchema("pitch.semitones", "Interval", -12f, 12f, -2f, "st", 1f, "Pitch transposition interval in semitones."),
        ParameterSchema("pitch.pedal_pos", "EXP Sweep", 0f, 1f, 0.0f, "%", 0.01f, "Continuous Whammy sweep position."),
        ParameterSchema("pitch.mix", "Mix", 0f, 1f, 0.50f, "%", 0.01f, "Dry/Shifted voice balance."),

        // Delay
        ParameterSchema("delay.time_ms", "Time", 20f, 2000f, 380f, "ms", 5f, "Delay echo time (automatically locked to BPM when Note Sync is enabled)."),
        ParameterSchema("delay.feedback", "Feedback", 0f, 0.95f, 0.42f, "%", 0.01f, "Repeat regeneration into analog BBD / tape head saturation path."),
        ParameterSchema("delay.mix", "Mix", 0f, 1f, 0.30f, "%", 0.01f, "Wet echo level in stereo signal path (preserved across Snapshot spillover)."),

        // Reverb
        ParameterSchema("reverb.decay", "Decay", 0.05f, 0.98f, 0.58f, "%", 0.01f, "FDN reverberation tail RT60 length or Spring dwell."),
        ParameterSchema("reverb.damping_hz", "Tone / Damp", 800f, 12000f, 4500f, "Hz", 100f, "High-frequency air absorption inside reverberation chamber."),
        ParameterSchema("reverb.mix", "Mix", 0f, 1f, 0.28f, "%", 0.01f, "Wet reverb blend (preserved across Snapshot spillover)."),
        ParameterSchema("reverb.predelay_ms", "Pre-Delay", 0f, 150f, 22f, "ms", 1f, "Initial reflection gap separating dry pick attack from lush reverb wash."),
        ParameterSchema("reverb.shimmer_amount", "Shimmer", 0f, 1f, 0.35f, "%", 0.01f, "WSOLA octave/fifth pitch feedback intensity for Searchlights & Plateaux."),

        // Volume / Pan
        ParameterSchema("vol.position", "Pedal Pos", 0f, 1f, 1.0f, "%", 0.01f, "Volume pedal taper position (maps directly to EXP 2)."),
        ParameterSchema("vol.pan", "Pan", -1f, 1f, 0f, "", 0.05f, "Stereo left/right balance."),
        ParameterSchema("vol.stereo_width", "Width", 0f, 1.5f, 1.0f, "%", 0.05f, "Mid/Side stereo image width."),
    ).associateBy { it.key }

    fun get(key: String): ParameterSchema? = schemas[key]
    fun allForPrefix(prefix: String): List<ParameterSchema> = schemas.values.filter { it.key.startsWith(prefix) }
}
