package com.cleanroom.stadiumdsp.preset

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * GAP-15: Local JSON Preset & Setlist Repository separating read-only FACTORY presets
 * from editable USER presets, with global search across Name, Category, & Preset Info + SAF JSON I/O.
 */
class PresetRepository(
    private val storageDir: File? = null,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val factoryPresets: List<StadiumPreset> = buildCuratedFactoryBank()
    private val userPresets = linkedMapOf<String, StadiumPreset>()
    private val setlists = linkedMapOf<String, SetlistData>()

    init {
        setlists["live_default"] = SetlistData(
            id = "live_default",
            name = "Friday Night Live Set",
            entries = listOf(
                SetlistEntry("factory_01a", "Opening Anthem (Brit 2203)", 124f, "Snapshot 1 Clean -> 3 Rhythm -> 4 Solo"),
                SetlistEntry("factory_02a", "Shimmer Worship Swell", 74f, "Use EXP 2 for ambient volume swells"),
                SetlistEntry("factory_03a", "Thrash Tight Mark IIC+", 176f, "Horizon Gate ON, V-Scoop 75%"),
                SetlistEntry("factory_04a", "Texas Tweed Blues", 96f, "Teemah! + '63 Spring Drip"),
            ),
        )
        loadUserPresetsFromDisk()
    }

    fun allPresets(): List<StadiumPreset> = factoryPresets + userPresets.values.toList()
    fun factoryBank(): List<StadiumPreset> = factoryPresets
    fun userBank(): List<StadiumPreset> = userPresets.values.toList()

    fun byId(id: String): StadiumPreset? =
        factoryPresets.firstOrNull { it.id == id } ?: userPresets[id]

    /**
     * Saves a user preset. Rejects attempts to overwrite read-only FACTORY presets.
     */
    fun saveUserPreset(preset: StadiumPreset): Boolean {
        if (preset.isFactory || factoryPresets.any { it.id == preset.id }) {
            return false
        }
        val clean = preset.copy(isFactory = false)
        userPresets[clean.id] = clean
        persistUserPresetsToDisk()
        return true
    }

    fun deleteUserPreset(id: String): Boolean {
        if (factoryPresets.any { it.id == id }) return false
        val removed = userPresets.remove(id) != null
        if (removed) persistUserPresetsToDisk()
        return removed
    }

    fun search(query: String): List<StadiumPreset> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return allPresets()
        return allPresets().filter { p ->
            p.name.lowercase().contains(q) ||
                p.bankCode.lowercase().contains(q) ||
                p.categoryTag.lowercase().contains(q) ||
                p.presetInfo.lowercase().contains(q) ||
                p.signalFlow.blocks.any { it.displayName.lowercase().contains(q) }
        }
    }

    fun allSetlists(): List<SetlistData> = setlists.values.toList()

    fun saveSetlist(setlist: SetlistData) {
        setlists[setlist.id] = setlist
    }

    fun exportPresetJson(preset: StadiumPreset): String = json.encodeToString(preset.copy(isFactory = false))

    fun importPresetJson(rawJson: String): StadiumPreset {
        val decoded = json.decodeFromString<StadiumPreset>(rawJson)
        val safeId = if (decoded.id.isBlank() || factoryPresets.any { it.id == decoded.id }) {
            "user_imp_${System.currentTimeMillis()}"
        } else {
            decoded.id
        }
        return decoded.copy(id = safeId, isFactory = false)
    }

    private fun loadUserPresetsFromDisk() {
        val dir = storageDir ?: return
        val file = File(dir, "user_presets.json")
        if (!file.exists()) return
        runCatching {
            val loaded = json.decodeFromString<List<StadiumPreset>>(file.readText())
            loaded.forEach { userPresets[it.id] = it.copy(isFactory = false) }
        }
    }

    private fun persistUserPresetsToDisk() {
        val dir = storageDir ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, "user_presets.json").writeText(json.encodeToString(userPresets.values.toList()))
        }
    }

    private fun buildCuratedFactoryBank(): List<StadiumPreset> = listOf(
        StadiumPreset.createDefaultPreset(),
        StadiumPreset(
            id = "factory_01b", bankCode = "01B", name = "US Double Blackface Clean",
            categoryTag = "Clean", presetInfo = "Fender Twin Reverb AB763 + LA-2A Opto + 2x12 Jensen + Glitz ambient bloom.",
            isFactory = true, globalBpm = 92f,
            signalFlow = SignalFlowState(
                blocks = StadiumPreset.defaultBlocks(
                    ampModelId = 0, ampName = "US Double Nrm", ampShort = "US Double",
                    cabModelId = 4, cabName = "2x12 US Twin Jensen", cabShort = "2x12 Twin",
                    driveModelId = 4, driveName = "Teemah!", driveEnabled = false,
                    modModelId = 6, modName = "Optical Tremolo", modEnabled = true,
                    delayModelId = 3, delayName = "Transistor Tape", delayEnabled = true,
                    reverbModelId = 2, reverbName = "Glitz", reverbEnabled = true,
                )
            ),
            snapshots = StadiumPreset.default8Snapshots(),
        ),
        StadiumPreset(
            id = "factory_02a", bankCode = "02A", name = "Essex A30 Ambient Worship",
            categoryTag = "Ambient", presetInfo = "Vox AC30 Top Boost + Heir Apparent + 2x12 Alnico Blue + Cosmos Echo Dotted 1/8 + Searchlights Shimmer.",
            isFactory = true, globalBpm = 74f,
            signalFlow = SignalFlowState(
                blocks = StadiumPreset.defaultBlocks(
                    ampModelId = 6, ampName = "Essex A30", ampShort = "Essex A30",
                    cabModelId = 3, cabName = "2x12 Essex Blue Bell", cabShort = "2x12 Blue",
                    driveModelId = 5, driveName = "Heir Apparent", driveEnabled = true,
                    modModelId = 1, modName = "Trinity Chorus", modEnabled = true,
                    delayModelId = 1, delayName = "Cosmos Echo", delayEnabled = true,
                    reverbModelId = 3, reverbName = "Searchlights", reverbEnabled = true,
                )
            ),
            snapshots = StadiumPreset.default8Snapshots(),
        ),
        StadiumPreset(
            id = "factory_03a", bankCode = "03A", name = "Cali IIC+ Master Thrash",
            categoryTag = "High-Gain", presetInfo = "Mesa Boogie Mark IIC+ 5-Band GEQ V-Scoop + Scream 808 + 4x12 Cali Oversized V30.",
            isFactory = true, globalBpm = 176f,
            signalFlow = SignalFlowState(
                blocks = StadiumPreset.defaultBlocks(
                    ampModelId = 15, ampName = "Cali IIC+", ampShort = "Cali 2C+",
                    cabModelId = 2, cabName = "4x12 Cali Oversized", cabShort = "4x12 Cali",
                    driveModelId = 0, driveName = "Scream 808", driveEnabled = true,
                    modModelId = 5, modName = "Gray Flanger 117", modEnabled = false,
                    delayModelId = 6, delayName = "Ducked 2290", delayEnabled = false,
                    reverbModelId = 1, reverbName = "Dynamic Plate", reverbEnabled = false,
                )
            ),
            snapshots = StadiumPreset.default8Snapshots(),
        ),
        StadiumPreset(
            id = "factory_04a", bankCode = "04A", name = "Plexi 1959 & Uni-Vibe Fuzz",
            categoryTag = "Vintage", presetInfo = "Marshall Super Lead 1959 Jumped + 10k Auto-Z Fuzz Face + 22k Shin-Ei Uni-Vibe + 4x12 Greenback.",
            isFactory = true, globalBpm = 104f,
            signalFlow = SignalFlowState(
                blocks = StadiumPreset.defaultBlocks(
                    ampModelId = 9, ampName = "Brit Plexi Jump", ampShort = "Plexi Jump",
                    cabModelId = 1, cabName = "4x12 Greenback 25", cabShort = "4x12 GB25",
                    driveModelId = 2, driveName = "Arbitrator Fuzz", driveEnabled = true,
                    modModelId = 4, modName = "Ubiquitous Vibe", modEnabled = true,
                    delayModelId = 3, delayName = "Transistor Tape", delayEnabled = true,
                    reverbModelId = 4, reverbName = "'63 Spring", reverbEnabled = true,
                )
            ),
            snapshots = StadiumPreset.default8Snapshots(),
        ),
        StadiumPreset(
            id = "factory_05a", bankCode = "05A", name = "Boutique Litigator ODS Lead",
            categoryTag = "Blues / Fusion", presetInfo = "Dumble Overdrive Special + Kinky Boost + 1x12 US Deluxe + Adriatic BBD Delay.",
            isFactory = true, globalBpm = 98f,
            signalFlow = SignalFlowState(
                blocks = StadiumPreset.defaultBlocks(
                    ampModelId = 20, ampName = "Litigator ODS", ampShort = "Litigator",
                    cabModelId = 5, cabName = "1x12 US Deluxe", cabShort = "1x12 Dlx",
                    driveModelId = 9, driveName = "Kinky Boost", driveEnabled = true,
                    modModelId = 8, modName = "Harmonic Tremolo", modEnabled = false,
                    delayModelId = 2, delayName = "Adriatic Delay", delayEnabled = true,
                    reverbModelId = 0, reverbName = "Dynamic Hall", reverbEnabled = true,
                )
            ),
            snapshots = StadiumPreset.default8Snapshots(),
        ),
        StadiumPreset(
            id = "factory_06a", bankCode = "06A", name = "SV Beast 8x10 Bass Rig",
            categoryTag = "Bass", presetInfo = "Ampeg SVT Classic + Obsidian 7000 Darkglass B7K Parallel CMOS + 8x10 SVT Fridge.",
            isFactory = true, globalBpm = 112f,
            signalFlow = SignalFlowState(
                blocks = StadiumPreset.defaultBlocks(
                    ampModelId = 25, ampName = "SV Beast Nrm", ampShort = "SV Beast",
                    cabModelId = 8, cabName = "8x10 SV Beast Fridge", cabShort = "8x10 SVT",
                    driveModelId = 11, driveName = "Obsidian 7000", driveEnabled = true,
                    modModelId = 0, modName = "CE-1 Chorus", modEnabled = false,
                    delayModelId = 0, delayName = "Bucket Brigade", delayEnabled = false,
                    reverbModelId = 0, reverbName = "Dynamic Hall", reverbEnabled = false,
                )
            ),
            snapshots = StadiumPreset.default8Snapshots(),
        ),
        StadiumPreset(
            id = "factory_07a", bankCode = "07A", name = "Neural Clone PROXY Studio",
            categoryTag = "Neural Clone", presetInfo = "Batch-16 NEON .nam / .aidax Capture Platform + Dual 2048-Tap External IR Loader + Plateaux Shimmer.",
            isFactory = true, globalBpm = 120f,
            signalFlow = SignalFlowState(
                blocks = StadiumPreset.defaultBlocks(
                    ampModelId = 18, ampName = "Revv Gen Purple", ampShort = "Clone/Revv",
                    cabModelId = 2, cabName = "4x12 Cali Oversized", cabShort = "IR / Cali",
                    driveModelId = 3, driveName = "Minotaur", driveEnabled = false,
                    modModelId = 1, modName = "Trinity Chorus", modEnabled = false,
                    delayModelId = 5, delayName = "Vintage Digital", delayEnabled = true,
                    reverbModelId = 6, reverbName = "Plateaux", reverbEnabled = true,
                ),
                routingMode = 1,
            ),
            snapshots = StadiumPreset.default8Snapshots(),
        ),
    )
}
