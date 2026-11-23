package com.cleanroom.stadiumdsp.preset

import kotlinx.serialization.Serializable

/**
 * Official 11 category hex colors matching Helix Stadium Native studio/stage contrast palette.
 */
@Serializable
enum class BlockCategory(val displayName: String, val colorHex: Long) {
    AMP("Amp", 0xFFFF3C3CL),
    CAB("Cab / IR", 0xFFFF509AL),
    DISTORTION("Distortion", 0xFFFF9B2DL),
    DYNAMICS("Dynamics", 0xFFE8D62FL),
    EQ("EQ", 0xFFE8D62FL),
    MODULATION("Modulation", 0xFF00A5FFL),
    DELAY("Delay", 0xFF43FF40L),
    REVERB("Reverb", 0xFFFF6828L),
    PITCH_WAH("Pitch / Wah", 0xFFA25DFFL),
    VOL_PAN("Vol / Pan", 0xFF0FFFFFL),
    ROUTING("I/O & Routing", 0xFF949494L),
}

@Serializable
enum class SplitRoutingMode(val nativeCode: Int, val label: String) {
    SERIAL(0, "Serial"),
    SPLIT_Y(1, "Split Y"),
    SPLIT_AB(2, "Split A/B"),
    CROSSOVER(3, "Crossover"),
}

@Serializable
data class RigBlockState(
    val id: String,
    val nativeBlockCode: Int,
    val category: BlockCategory,
    val modelId: Int = 0,
    val displayName: String,
    val shortLabel: String,
    val enabled: Boolean = true,
    val isStereo: Boolean = false,
    val pathBranch: String = "1A", // "1A", "1B", "2A", or "2B"
    val instanceIndex: Int = 0,    // 0 = primary DSP pool instance, 1 = secondary DSP pool instance
    val snapshotsSetBypass: Boolean = true,
    val focusX: Float = 0f,
    val focusY: Float = 0f,
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
data class SignalFlowState(
    val blocks: List<RigBlockState>,
    val splitMode: SplitRoutingMode = SplitRoutingMode.SPLIT_Y,
    val splitMode2: SplitRoutingMode = SplitRoutingMode.SPLIT_Y,
    val split1AfterIndex: Int = 4,
    val split2AfterIndex: Int = 2,
    val merge2AtIndex: Int = 3,
    val merge1bInto1a: Boolean = false,
    val path1aFeeds2a: Boolean = true,
    val crossoverHz: Float = 400f,
    val routeAb: Float = 0.5f,
    val mergeLevelADb: Float = 0f,
    val mergeLevelBDb: Float = 0f,
    val mergePanA: Float = 0f,
    val mergePanB: Float = 0f,
    val invertPhaseB: Boolean = false,
    val ampCabLink: Boolean = true,
    val cabCabLink: Boolean = false,
    val autoZEnabled: Boolean = true,
    val pickupType: Int = 0,
    val manualInputZOhms: Float = 1_000_000f,
    val routingMode: Int = 0, // 0=Agoura+Cab, 1=Clone+Cab, 2=Clone Full Rig
)

@Serializable
data class SnapshotData(
    val index: Int,
    val name: String,
    val ledColorHex: Long = 0xFFFF3C3CL,
    val blockEnabled: Map<String, Boolean> = emptyMap(),
    val paramOverrides: Map<String, Float> = emptyMap(),
)

@Serializable
data class SetlistEntry(
    val presetId: String,
    val songTitle: String,
    val bpm: Float = 120f,
    val notes: String = "",
)

@Serializable
data class SetlistData(
    val id: String,
    val name: String,
    val entries: List<SetlistEntry> = emptyList(),
)

@Serializable
data class StadiumPreset(
    val id: String,
    val bankCode: String,
    val name: String,
    val categoryTag: String = "Rock",
    val presetInfo: String = "",
    val isFactory: Boolean = false,
    val globalBpm: Float = 120f,
    val inputTrimDb: Float = 0f,
    val outputLevelDb: Float = 0f,
    val outputPan: Float = 0f,
    val signalFlow: SignalFlowState,
    val snapshots: List<SnapshotData>,
    val snapshotControlledParams: Set<String> = setOf(
        "amp.drive", "amp.master", "amp.mid", "drive.drive", "delay.mix", "reverb.mix",
    ),
) {
    companion object {
        fun defaultBlocks(
            ampModelId: Int = 11,
            ampName: String = "Brit 2203",
            ampShort: String = "Brit 2203",
            cabModelId: Int = 0,
            cabName: String = "4x12 Brit V30",
            cabShort: String = "4x12 V30",
            driveModelId: Int = 3,
            driveName: String = "Minotaur",
            driveEnabled: Boolean = true,
            modModelId: Int = 1,
            modName: String = "Trinity Chorus",
            modEnabled: Boolean = false,
            delayModelId: Int = 1,
            delayName: String = "Cosmos Echo",
            delayEnabled: Boolean = true,
            reverbModelId: Int = 4,
            reverbName: String = "'63 Spring",
            reverbEnabled: Boolean = true,
        ): List<RigBlockState> = listOf(
            RigBlockState(
                id = "gate", nativeBlockCode = 0, category = BlockCategory.DYNAMICS, modelId = 0,
                displayName = "Horizon Gate", shortLabel = "Gate", enabled = true, isStereo = false,
                pathBranch = "1A", instanceIndex = 0,
                params = mapOf("gate.threshold_db" to -52f, "gate.decay_ms" to 45f),
            ),
            RigBlockState(
                id = "pitch", nativeBlockCode = 1, category = BlockCategory.PITCH_WAH, modelId = 1,
                displayName = "Poly Capo", shortLabel = "Pitch", enabled = false, isStereo = false,
                pathBranch = "1A", instanceIndex = 0,
                params = mapOf("pitch.semitones" to 0f, "pitch.pedal_pos" to 0f, "pitch.mix" to 0.5f),
            ),
            RigBlockState(
                id = "comp", nativeBlockCode = 2, category = BlockCategory.DYNAMICS, modelId = 0,
                displayName = "LA Studio Comp", shortLabel = "LA-2A", enabled = false, isStereo = false,
                pathBranch = "1A", instanceIndex = 0,
                params = mapOf("comp.peak_reduction" to 0.45f, "comp.gain_db" to 4f, "comp.mix" to 0.85f),
            ),
            RigBlockState(
                id = "wah", nativeBlockCode = 3, category = BlockCategory.PITCH_WAH, modelId = 0,
                displayName = "Fassel 310 Wah", shortLabel = "Wah", enabled = false, isStereo = false,
                pathBranch = "1A", instanceIndex = 0,
                params = mapOf("wah.position" to 0.50f, "wah.q_resonance" to 0.55f, "wah.mix" to 1.0f),
            ),
            RigBlockState(
                id = "drive", nativeBlockCode = 4, category = BlockCategory.DISTORTION, modelId = driveModelId,
                displayName = driveName, shortLabel = driveName.take(9), enabled = driveEnabled, isStereo = false,
                pathBranch = "1A", instanceIndex = 0,
                params = mapOf("drive.drive" to 0.42f, "drive.tone" to 0.56f, "drive.level" to 0.68f, "drive.bass_cut" to 0.25f),
            ),
            RigBlockState(
                id = "amp", nativeBlockCode = 6, category = BlockCategory.AMP, modelId = ampModelId,
                displayName = ampName, shortLabel = ampShort, enabled = true, isStereo = false,
                pathBranch = "1A", instanceIndex = 0,
                params = mapOf(
                    "amp.drive" to 0.62f, "amp.bass" to 0.50f, "amp.mid" to 0.65f,
                    "amp.treble" to 0.62f, "amp.presence" to 0.55f, "amp.master" to 0.52f,
                    "amp.ch_vol" to 0.72f, "amp.sag" to 0.42f, "amp.ripple" to 0.22f,
                    "amp.bias" to 0.50f, "amp.bias_x" to 0.50f, "amp.hype" to 0.20f,
                ),
            ),
            RigBlockState(
                id = "cab", nativeBlockCode = 7, category = BlockCategory.CAB, modelId = cabModelId,
                displayName = cabName, shortLabel = cabShort, enabled = true, isStereo = true,
                pathBranch = "1A", instanceIndex = 0,
                params = mapOf(
                    "cab.position" to 0.24f, "cab.distance_inch" to 1.8f, "cab.angle_deg" to 0f,
                    "cab.low_cut_hz" to 65f, "cab.high_cut_hz" to 9200f, "cab.level_db" to 0f, "cab.pan" to 0f,
                    "cab.mic_model" to 0f,
                ),
            ),
            RigBlockState(
                id = "eq", nativeBlockCode = 8, category = BlockCategory.EQ, modelId = 0,
                displayName = "Studio 4-Band EQ", shortLabel = "Param EQ", enabled = false, isStereo = true,
                pathBranch = "2A", instanceIndex = 0,
                params = mapOf(
                    "eq.low_freq" to 120f, "eq.low_gain_db" to 0f, "eq.low_q" to 0.8f,
                    "eq.mid1_freq" to 650f, "eq.mid1_gain_db" to 0f, "eq.mid1_q" to 1.2f,
                    "eq.mid2_freq" to 2400f, "eq.mid2_gain_db" to 0f, "eq.mid2_q" to 1.2f,
                    "eq.high_freq" to 6500f, "eq.high_gain_db" to 0f, "eq.high_q" to 0.8f,
                    "eq.tilt_db" to 0f,
                ),
            ),
            RigBlockState(
                id = "vol_pan", nativeBlockCode = 9, category = BlockCategory.VOL_PAN, modelId = 0,
                displayName = "Volume & Pan Pedal", shortLabel = "Vol / Pan", enabled = false, isStereo = true,
                pathBranch = "2A", instanceIndex = 0,
                params = mapOf("vol.position" to 1.0f, "vol.pan" to 0f, "vol.stereo_width" to 1.0f),
            ),
            RigBlockState(
                id = "mod", nativeBlockCode = 10, category = BlockCategory.MODULATION, modelId = modModelId,
                displayName = modName, shortLabel = modName.take(9), enabled = modEnabled, isStereo = true,
                pathBranch = "2A", instanceIndex = 1,
                params = mapOf("mod.rate_hz" to 1.2f, "mod.depth" to 0.55f, "mod.mix" to 0.45f, "mod.feedback" to 0.30f),
            ),
            RigBlockState(
                id = "delay", nativeBlockCode = 11, category = BlockCategory.DELAY, modelId = delayModelId,
                displayName = delayName, shortLabel = delayName.take(9), enabled = delayEnabled, isStereo = true,
                pathBranch = "1B", instanceIndex = 0,
                params = mapOf("delay.time_ms" to 375f, "delay.feedback" to 0.42f, "delay.mix" to 0.30f),
            ),
            RigBlockState(
                id = "reverb", nativeBlockCode = 12, category = BlockCategory.REVERB, modelId = reverbModelId,
                displayName = reverbName, shortLabel = reverbName.take(9), enabled = reverbEnabled, isStereo = true,
                pathBranch = "1B", instanceIndex = 0,
                params = mapOf("reverb.decay" to 0.56f, "reverb.damping_hz" to 4600f, "reverb.mix" to 0.28f, "reverb.predelay_ms" to 22f, "reverb.shimmer_amount" to 0.35f),
            ),
        )

        fun default8Snapshots(): List<SnapshotData> = listOf(
            SnapshotData(0, "CLEAN", 0xFF00A5FFL, mapOf("drive" to false, "mod" to true, "delay" to false, "reverb" to true), mapOf("amp.drive" to 0.26f, "amp.master" to 0.62f, "delay.mix" to 0.18f, "reverb.mix" to 0.30f)),
            SnapshotData(1, "KLON EDGE", 0xFFFF9B2DL, mapOf("drive" to true, "mod" to false, "delay" to false, "reverb" to true), mapOf("amp.drive" to 0.46f, "amp.master" to 0.56f, "drive.drive" to 0.36f, "delay.mix" to 0.20f, "reverb.mix" to 0.24f)),
            SnapshotData(2, "2203 RHYTHM", 0xFFFF3C3CL, mapOf("drive" to true, "mod" to false, "delay" to true, "reverb" to false), mapOf("amp.drive" to 0.68f, "amp.master" to 0.50f, "drive.drive" to 0.48f, "delay.mix" to 0.22f, "reverb.mix" to 0.16f)),
            SnapshotData(3, "SOLO LEAD", 0xFF43FF40L, mapOf("drive" to true, "mod" to true, "delay" to true, "reverb" to true), mapOf("amp.drive" to 0.86f, "amp.master" to 0.60f, "drive.drive" to 0.65f, "delay.mix" to 0.36f, "reverb.mix" to 0.32f)),
            SnapshotData(4, "AMBIENT", 0xFFA25DFFL, mapOf("drive" to false, "mod" to true, "delay" to true, "reverb" to true), mapOf("amp.drive" to 0.32f, "amp.master" to 0.58f, "delay.mix" to 0.48f, "reverb.mix" to 0.54f)),
            SnapshotData(5, "SURF DRIP", 0xFFFF6828L, mapOf("drive" to false, "mod" to false, "delay" to true, "reverb" to true), mapOf("amp.drive" to 0.40f, "amp.master" to 0.60f, "delay.mix" to 0.26f, "reverb.mix" to 0.46f)),
            SnapshotData(6, "HEAVY RIFF", 0xFFE8D62FL, mapOf("drive" to true, "mod" to false, "delay" to false, "reverb" to false), mapOf("amp.drive" to 0.80f, "amp.master" to 0.52f, "drive.drive" to 0.55f, "delay.mix" to 0.12f, "reverb.mix" to 0.10f)),
            SnapshotData(7, "OUTRO CLN", 0xFF0FFFFFL, mapOf("drive" to false, "mod" to true, "delay" to true, "reverb" to true), mapOf("amp.drive" to 0.24f, "amp.master" to 0.64f, "delay.mix" to 0.34f, "reverb.mix" to 0.40f)),
        )

        /**
         * Flagship 4-branch Dual-Path preset replicating the Space Cadet Studio layout:
         * Path 1A: Drive[0] -> Drive[1] -> Amp (Essex TB30CC) -> Cab (2x12 Blue) -> [Split 1] -> Delay[0] -> (Path 2A)
         * Path 1B: Delay[1] -> Reverb[0] -> Vol/Pan[0] -> (Direct Out)
         * Path 2A: EQ[0] -> Mod[1] -> [Split 2] -> Reverb[1] -> [Merge 2] -> EQ[1] -> Vol/Pan[1] -> (Final Out)
         * Path 2B: Comp[0] (Parallel ambient sustain branch -> Merge 2)
         */
        fun createSpaceCadetPreset(): StadiumPreset = StadiumPreset(
            id = "factory_01a",
            bankCode = "01A",
            name = "Space Cadet",
            categoryTag = "Ambient",
            presetInfo = "Essex TB30CC dual-path rig: stacked drives, linked 2x12 Alnico Blue, parallel dual delays & dual reverbs across Path 1A/1B -> Path 2A/2B.",
            isFactory = true,
            globalBpm = 165.0f,
            signalFlow = SignalFlowState(
                blocks = listOf(
                    RigBlockState(
                        id = "drive", nativeBlockCode = 4, category = BlockCategory.DISTORTION, modelId = 9,
                        displayName = "Kinky Boost", shortLabel = "Boost", enabled = true, isStereo = false,
                        pathBranch = "1A", instanceIndex = 0,
                        params = mapOf("drive.drive" to 0.36f, "drive.tone" to 0.62f, "drive.level" to 0.72f, "drive.bass_cut" to 0.20f),
                    ),
                    RigBlockState(
                        id = "drive_2", nativeBlockCode = 4, category = BlockCategory.DISTORTION, modelId = 3,
                        displayName = "Minotaur", shortLabel = "Minotaur", enabled = true, isStereo = false,
                        pathBranch = "1A", instanceIndex = 1,
                        params = mapOf("drive.drive" to 0.44f, "drive.tone" to 0.58f, "drive.level" to 0.68f, "drive.bass_cut" to 0.25f),
                    ),
                    RigBlockState(
                        id = "amp", nativeBlockCode = 6, category = BlockCategory.AMP, modelId = 6,
                        displayName = "Essex TB30CC", shortLabel = "Essex 30", enabled = true, isStereo = false,
                        pathBranch = "1A", instanceIndex = 0,
                        params = mapOf(
                            "amp.drive" to 0.42f, "amp.bass" to 0.45f, "amp.mid" to 0.49f,
                            "amp.treble" to 0.76f, "amp.presence" to 0.50f, "amp.master" to 1.00f,
                            "amp.ch_vol" to 0.64f, "amp.sag" to 0.50f, "amp.ripple" to 0.50f,
                            "amp.cut" to 0.05f, "amp.hype" to 0.00f, "amp.bias" to 0.50f,
                        ),
                    ),
                    RigBlockState(
                        id = "cab", nativeBlockCode = 7, category = BlockCategory.CAB, modelId = 3,
                        displayName = "2x12 Essex Blue Bell", shortLabel = "2x12 Blue", enabled = true, isStereo = true,
                        pathBranch = "1A", instanceIndex = 0,
                        params = mapOf(
                            "cab.position" to 0.28f, "cab.distance_inch" to 2.0f, "cab.angle_deg" to 0f,
                            "cab.low_cut_hz" to 70f, "cab.high_cut_hz" to 9600f, "cab.level_db" to 0f, "cab.pan" to 0f,
                            "cab.mic_model" to 0f,
                        ),
                    ),
                    RigBlockState(
                        id = "delay", nativeBlockCode = 11, category = BlockCategory.DELAY, modelId = 1,
                        displayName = "Cosmos Echo", shortLabel = "Cosmos", enabled = true, isStereo = true,
                        pathBranch = "1A", instanceIndex = 0,
                        params = mapOf("delay.time_ms" to 272f, "delay.feedback" to 0.42f, "delay.mix" to 0.34f),
                    ),
                    // Parallel Path 1B (Direct Out)
                    RigBlockState(
                        id = "delay_2", nativeBlockCode = 11, category = BlockCategory.DELAY, modelId = 3,
                        displayName = "Transistor Tape", shortLabel = "Tape Dly", enabled = true, isStereo = true,
                        pathBranch = "1B", instanceIndex = 1,
                        params = mapOf("delay.time_ms" to 363f, "delay.feedback" to 0.48f, "delay.mix" to 0.52f),
                    ),
                    RigBlockState(
                        id = "reverb", nativeBlockCode = 12, category = BlockCategory.REVERB, modelId = 1,
                        displayName = "Dynamic Plate", shortLabel = "Plate", enabled = true, isStereo = true,
                        pathBranch = "1B", instanceIndex = 0,
                        params = mapOf("reverb.decay" to 0.62f, "reverb.damping_hz" to 5200f, "reverb.mix" to 0.45f, "reverb.predelay_ms" to 18f, "reverb.shimmer_amount" to 0.15f),
                    ),
                    RigBlockState(
                        id = "vol_pan", nativeBlockCode = 9, category = BlockCategory.VOL_PAN, modelId = 0,
                        displayName = "Volume Swell 1B", shortLabel = "Vol 1B", enabled = true, isStereo = true,
                        pathBranch = "1B", instanceIndex = 0,
                        params = mapOf("vol.position" to 0.85f, "vol.pan" to 0f, "vol.stereo_width" to 1.0f),
                    ),
                    // Path 2A (Fed by Path 1A arrow-down)
                    RigBlockState(
                        id = "eq", nativeBlockCode = 8, category = BlockCategory.EQ, modelId = 0,
                        displayName = "Studio 4-Band EQ", shortLabel = "Pre EQ", enabled = true, isStereo = true,
                        pathBranch = "2A", instanceIndex = 0,
                        params = mapOf(
                            "eq.low_freq" to 110f, "eq.low_gain_db" to -1.5f, "eq.low_q" to 0.8f,
                            "eq.mid1_freq" to 720f, "eq.mid1_gain_db" to 1.5f, "eq.mid1_q" to 1.1f,
                            "eq.mid2_freq" to 2800f, "eq.mid2_gain_db" to 2.0f, "eq.mid2_q" to 1.2f,
                            "eq.high_freq" to 6800f, "eq.high_gain_db" to 1.0f, "eq.high_q" to 0.8f,
                            "eq.tilt_db" to 0.5f,
                        ),
                    ),
                    RigBlockState(
                        id = "mod", nativeBlockCode = 10, category = BlockCategory.MODULATION, modelId = 1,
                        displayName = "Trinity Chorus", shortLabel = "Trinity", enabled = true, isStereo = true,
                        pathBranch = "2A", instanceIndex = 1,
                        params = mapOf("mod.rate_hz" to 0.85f, "mod.depth" to 0.52f, "mod.mix" to 0.38f, "mod.feedback" to 0.25f),
                    ),
                    RigBlockState(
                        id = "reverb_2", nativeBlockCode = 12, category = BlockCategory.REVERB, modelId = 3,
                        displayName = "Searchlights", shortLabel = "Shimmer", enabled = true, isStereo = true,
                        pathBranch = "2A", instanceIndex = 1,
                        params = mapOf("reverb.decay" to 0.78f, "reverb.damping_hz" to 5800f, "reverb.mix" to 0.42f, "reverb.predelay_ms" to 28f, "reverb.shimmer_amount" to 0.48f),
                    ),
                    RigBlockState(
                        id = "eq_2", nativeBlockCode = 8, category = BlockCategory.EQ, modelId = 2,
                        displayName = "Tilt Mastering EQ", shortLabel = "Tilt EQ", enabled = true, isStereo = true,
                        pathBranch = "2A", instanceIndex = 1,
                        params = mapOf(
                            "eq.low_freq" to 100f, "eq.low_gain_db" to 0f, "eq.low_q" to 0.7f,
                            "eq.mid1_freq" to 650f, "eq.mid1_gain_db" to 0f, "eq.mid1_q" to 1.0f,
                            "eq.mid2_freq" to 2400f, "eq.mid2_gain_db" to 0f, "eq.mid2_q" to 1.0f,
                            "eq.high_freq" to 6500f, "eq.high_gain_db" to 0.5f, "eq.high_q" to 0.8f,
                            "eq.tilt_db" to 1.0f,
                        ),
                    ),
                    RigBlockState(
                        id = "vol_pan_2", nativeBlockCode = 9, category = BlockCategory.VOL_PAN, modelId = 0,
                        displayName = "Master Volume Pedal", shortLabel = "Vol 2A", enabled = true, isStereo = true,
                        pathBranch = "2A", instanceIndex = 1,
                        params = mapOf("vol.position" to 0.95f, "vol.pan" to 0f, "vol.stereo_width" to 1.0f),
                    ),
                    // Parallel Path 2B (Merges into Path 2A before eq_2)
                    RigBlockState(
                        id = "comp", nativeBlockCode = 2, category = BlockCategory.DYNAMICS, modelId = 0,
                        displayName = "LA Studio Comp", shortLabel = "LA-2A 2B", enabled = true, isStereo = false,
                        pathBranch = "2B", instanceIndex = 0,
                        params = mapOf("comp.peak_reduction" to 0.52f, "comp.gain_db" to 3.5f, "comp.mix" to 0.85f),
                    ),
                ),
                splitMode = SplitRoutingMode.SPLIT_Y,
                splitMode2 = SplitRoutingMode.SPLIT_Y,
                split1AfterIndex = 4,
                split2AfterIndex = 2,
                merge2AtIndex = 3,
                merge1bInto1a = false,
                path1aFeeds2a = true,
                ampCabLink = true,
            ),
            snapshots = default8Snapshots(),
        )

        fun createDefaultPreset(): StadiumPreset = createSpaceCadetPreset()
    }
}
