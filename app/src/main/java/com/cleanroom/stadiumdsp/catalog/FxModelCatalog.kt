package com.cleanroom.stadiumdsp.catalog

import com.cleanroom.stadiumdsp.preset.BlockCategory

data class FxModelSpec(
    val nativeId: Int,
    val category: BlockCategory,
    val subcategory: String,
    val displayName: String,
    val shortLabel: String,
    val basedOn: String,
    val autoZOhms: Float = 1_000_000f,
    val supportsStereo: Boolean = false,
    val supportsTempoSync: Boolean = false,
    val voiceOptions: List<String> = emptyList(),
)

/**
 * Clean-room catalog covering all 52+ precision analog FX models mapped 1:1 to `dsp_core` enums.
 */
object FxModelCatalog {

    val drives: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.DISTORTION, "Overdrive", "Scream 808", "Scream 808", "Ibanez TS808 Tube Screamer (MA150 + W-Taper)", 230_000f),
        FxModelSpec(1, BlockCategory.DISTORTION, "Distortion", "Vermin Dist", "Vermin RAT", "Pro Co RAT (LM308 0.3V/us Slew + 1N914)", 1_000_000f),
        FxModelSpec(2, BlockCategory.DISTORTION, "Fuzz", "Arbitrator Fuzz", "Fuzz Face", "Dallas Arbiter Fuzz Face (AC128 Ge PNP)", 10_000f),
        FxModelSpec(3, BlockCategory.DISTORTION, "Overdrive", "Minotaur", "Minotaur", "Klon Centaur (+18V/-9V Charge-Pump + 1N34A Ge)", 1_000_000f),
        FxModelSpec(4, BlockCategory.DISTORTION, "Overdrive", "Teemah!", "Teemah!", "Paul Cochrane Timmy (Pre-Clip Bass Cut + 3-Way Clip)", 1_000_000f, voiceOptions = listOf("Sym", "Asym", "Comp")),
        FxModelSpec(5, BlockCategory.DISTORTION, "Overdrive", "Heir Apparent", "Heir App", "Analogman Prince of Tone (Boost / OD / Dist)", 1_000_000f, voiceOptions = listOf("Boost", "OD", "Dist")),
        FxModelSpec(6, BlockCategory.DISTORTION, "Distortion", "Compulsive Drive", "OCD Drive", "Fulltone OCD v1.4 (2N7000 MOSFET + 1N34A Ge)", 1_000_000f, voiceOptions = listOf("LP", "HP")),
        FxModelSpec(7, BlockCategory.DISTORTION, "Fuzz", "Bighorn Fuzz", "Ram's Head", "1973 Violet Ram's Head Big Muff Pi (4-Stage MNA)", 136_000f),
        FxModelSpec(8, BlockCategory.DISTORTION, "Fuzz", "Triangle Fuzz", "Tri Muff", "1969 V1 Triangle Big Muff Pi (4-Stage MNA)", 136_000f),
        FxModelSpec(9, BlockCategory.DISTORTION, "Boost", "Kinky Boost", "EP Boost", "Xotic EP Booster (Maestro EP-3 JFET +18V)", 1_000_000f, voiceOptions = listOf("Norm", "Bright", "Fat")),
        FxModelSpec(10, BlockCategory.DISTORTION, "Boost", "Deranged Master", "Rangemstr", "1966 Dallas Rangemaster (OC44 Ge Treble Booster)", 10_000f, voiceOptions = listOf("4.7n", "10n", "47n")),
        FxModelSpec(11, BlockCategory.DISTORTION, "Bass Drive", "Obsidian 7000", "Obsidian B7K", "Darkglass Microtubes B7K Ultra (Parallel CMOS)", 1_000_000f, voiceOptions = listOf("Flat", "Grunt", "Attack")),
    )

    val wahFilters: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.PITCH_WAH, "Wah", "Fassel 310 Wah", "Fassel Wah", "Dunlop Cry Baby Super Fasel Inductor", 90_000f),
        FxModelSpec(1, BlockCategory.PITCH_WAH, "Wah", "Throaty Clyde", "Clyde Wah", "Vox Clyde McCoy Vintage Inductor Wah", 70_000f),
        FxModelSpec(2, BlockCategory.PITCH_WAH, "Wah", "UK 846 Wah", "UK 846", "Vox V846 Vintage Chrome Wah", 90_000f),
        FxModelSpec(3, BlockCategory.PITCH_WAH, "Wah", "Teardrop Wah", "Teardrop", "Dunlop Cry Baby Teardrop Sweep", 90_000f),
        FxModelSpec(4, BlockCategory.PITCH_WAH, "Filter", "Mutant Filter Up", "Mutant Up", "Mu-Tron III Vactrol Envelope Filter (Up)", 1_000_000f),
        FxModelSpec(5, BlockCategory.PITCH_WAH, "Filter", "Tron Down", "Tron Down", "Mu-Tron III Vactrol Envelope Filter (Down)", 1_000_000f),
        FxModelSpec(6, BlockCategory.PITCH_WAH, "Filter", "Obi Wah S&H", "Obi Wah", "Oberheim Voltage Controlled Sample & Hold", 230_000f, supportsTempoSync = true),
        FxModelSpec(7, BlockCategory.PITCH_WAH, "Filter", "8-Band MuRF", "MuRF 8-Bnd", "Moog MF-105 MuRF 8-Band Resonant Step Filter", 1_000_000f, supportsTempoSync = true),
    )

    val modulations: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.MODULATION, "Chorus", "CE-1 Chorus", "CE-1 Chor", "BOSS CE-1 Chorus Ensemble (MN3002 BBD)", 230_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(1, BlockCategory.MODULATION, "Chorus", "Trinity Chorus", "Tri-Chorus", "DyTronics CS-5 Tri-Stereo 120° BBD Chorus", 1_000_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(2, BlockCategory.MODULATION, "Vibrato", "VB-2 Vibrato", "VB-2 Vib", "BOSS VB-2 True Pitch Vibrato", 1_000_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(3, BlockCategory.MODULATION, "Phaser", "Script Mod Phase", "Phase 90", "MXR Script Phase 90 (4-Stage JFET Allpass)", 1_000_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(4, BlockCategory.MODULATION, "Phaser", "Ubiquitous Vibe", "Uni-Vibe", "Shin-Ei Uni-Vibe (4-Stage Asymmetric Photocell)", 22_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(5, BlockCategory.MODULATION, "Flanger", "Gray Flanger 117", "Flang 117", "MXR M117 Analog BBD Flanger", 1_000_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(6, BlockCategory.MODULATION, "Tremolo", "Optical Tremolo", "Opto Trem", "Fender Blackface Opto-Isolator Tremolo", 1_000_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(7, BlockCategory.MODULATION, "Tremolo", "Bias Tremolo", "Bias Trem", "Vox / Tweed Power-Tube Bias Tremolo", 1_000_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(8, BlockCategory.MODULATION, "Tremolo", "Harmonic Tremolo", "Harm Trem", "Fender Brownface Anti-Phase Harmonic Tremolo", 1_000_000f, supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(9, BlockCategory.MODULATION, "Rotary", "122 Rotary", "122 Rotary", "Leslie 122 Dual-Rotor Horn/Drum Doppler", 1_000_000f, supportsStereo = true),
    )

    val pitchSynths: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.PITCH_WAH, "Wham", "Pitch Wham", "Whammy", "DigiTech Whammy Continuous EXP Pitch Shifter"),
        FxModelSpec(1, BlockCategory.PITCH_WAH, "Pitch", "Poly Capo", "Poly Capo", "Polyphonic Transposing Capo (-12..+12 st)"),
        FxModelSpec(2, BlockCategory.PITCH_WAH, "Pitch", "Twin Harmony", "Twin Harm", "Eventide H3000 Dual-Voice Detune / Harmony"),
        FxModelSpec(3, BlockCategory.PITCH_WAH, "Synth", "Boctaver OC-2", "Boctaver", "BOSS OC-2 Analog CMOS Flip-Flop Sub-Octaver"),
        FxModelSpec(4, BlockCategory.PITCH_WAH, "Pitch", "12-String", "12-String", "12-String High-Band Octave Shimmer Emulator"),
    )

    val delays: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.DELAY, "Analog", "Bucket Brigade", "BBD DM-2", "BOSS DM-2 (MN3005 BBD + NE570 Compander)", supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(1, BlockCategory.DELAY, "Tape", "Cosmos Echo", "Cosmos RE", "Roland RE-201 Space Echo (3-Head + Spring)", supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(2, BlockCategory.DELAY, "Analog", "Adriatic Delay", "Adriatic", "BOSS DM-2 w/ Overclocked MN3102 BBD Mod", supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(3, BlockCategory.DELAY, "Tape", "Transistor Tape", "EP-3 Tape", "Maestro Echoplex EP-3 (JFET + Chua Capstan)", supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(4, BlockCategory.DELAY, "Analog", "Elephant Man", "DMM Delay", "EHX Deluxe Memory Man (Dual MN3005 + Mod)", supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(5, BlockCategory.DELAY, "Digital", "Vintage Digital", "Vint Dig", "1980s 12-Bit u-Law Rack Digital Delay", supportsStereo = true, supportsTempoSync = true),
        FxModelSpec(6, BlockCategory.DELAY, "Digital", "Ducked 2290", "Ducked", "TC Electronic 2290 Dynamic Ducked Delay", supportsStereo = true, supportsTempoSync = true),
    )

    val reverbs: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.REVERB, "Spaces", "Dynamic Hall", "Dyn Hall", "8x8 Unitary Walsh-Hadamard Concert Hall", supportsStereo = true),
        FxModelSpec(1, BlockCategory.REVERB, "Plate", "Dynamic Plate", "Dyn Plate", "EMT 140 Dense Metallic Studio Plate", supportsStereo = true),
        FxModelSpec(2, BlockCategory.REVERB, "Special FX", "Glitz", "Glitz", "Modulated Bloom Diffuser + Multi-Voice Tank Chorus", supportsStereo = true),
        FxModelSpec(3, BlockCategory.REVERB, "Special FX", "Searchlights", "Searchlt", "Cathedral Prime FDN + Octave-Up Shimmer", supportsStereo = true),
        FxModelSpec(4, BlockCategory.REVERB, "Spring", "'63 Spring", "'63 Spring", "1963 Fender 6G15 3-Spring Tank (36-Stage Chirp)", supportsStereo = true),
        FxModelSpec(5, BlockCategory.REVERB, "Spaces", "Ganymede", "Ganymede", "Ambient Modulated Chorus Hall", supportsStereo = true),
        FxModelSpec(6, BlockCategory.REVERB, "Special FX", "Plateaux", "Plateaux", "Dual-Voice Pitch Shimmer (+12st & +7st) Plate", supportsStereo = true),
    )

    val dynamics: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.DYNAMICS, "Gate", "Horizon Gate", "Gate", "Horizon Precision Sidechain HPF Hysteresis Gate"),
        FxModelSpec(1, BlockCategory.DYNAMICS, "Compress", "LA Studio Comp", "LA-2A Opto", "Teletronix LA-2A T4B Dual-Release Optical Comp"),
        FxModelSpec(2, BlockCategory.DYNAMICS, "Compress", "Red Squeeze", "Red Sqz", "MXR Dyna Comp / Ross VCA Punch Compressor"),
    )

    val eqModes: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.EQ, "Parametric", "Studio 4-Band EQ", "Param EQ", "Anti-Cramped 4-Band Parametric Studio EQ", supportsStereo = true),
        FxModelSpec(1, BlockCategory.EQ, "Graphic", "10-Band Graphic", "10-Band EQ", "MXR 10-Band Constant-Q ISO Graphic EQ", supportsStereo = true),
        FxModelSpec(2, BlockCategory.EQ, "Tilt", "Studio Tilt EQ", "Tilt EQ", "Tonmeister Mastering Spectral Tilt EQ", supportsStereo = true),
    )

    val volumePan: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.VOL_PAN, "Volume/Pan", "Volume & Pan Pedal", "Vol / Pan", "Log/Linear Taper Volume Swell + Stereo Width", supportsStereo = true),
    )

    val loopers: List<FxModelSpec> = listOf(
        FxModelSpec(0, BlockCategory.ROUTING, "Looper", "Stereo 30s Looper", "Looper", "30s Stereo Performance Looper + Crossfade + Undo", supportsStereo = true),
    )

    val allFxModels: List<FxModelSpec> =
        drives + wahFilters + modulations + pitchSynths + delays + reverbs + dynamics + eqModes + volumePan + loopers

    fun byCategory(category: BlockCategory): List<FxModelSpec> = when (category) {
        BlockCategory.DISTORTION -> drives
        BlockCategory.DYNAMICS -> dynamics
        BlockCategory.EQ -> eqModes
        BlockCategory.MODULATION -> modulations
        BlockCategory.DELAY -> delays
        BlockCategory.REVERB -> reverbs
        BlockCategory.PITCH_WAH -> wahFilters + pitchSynths
        BlockCategory.VOL_PAN -> volumePan
        BlockCategory.ROUTING -> loopers
        else -> emptyList()
    }

    fun search(query: String, category: BlockCategory? = null): List<FxModelSpec> {
        val q = query.trim().lowercase()
        val pool = if (category != null) byCategory(category) else allFxModels
        if (q.isEmpty()) return pool
        return pool.filter {
            it.displayName.lowercase().contains(q) ||
                it.basedOn.lowercase().contains(q) ||
                it.subcategory.lowercase().contains(q)
        }
    }
}
