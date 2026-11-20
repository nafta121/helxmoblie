package com.cleanroom.stadiumdsp.catalog

enum class AmpEngineTier(val label: String) {
    AGOURA("Agoura"),
    HX("HX"),
}

data class AmpModelSpec(
    val nativeId: Int,
    val displayName: String,
    val shortLabel: String,
    val basedOn: String,
    val engineTier: AmpEngineTier,
    val isBass: Boolean,
    val defaultCabId: Int,
    val autoZOhms: Float = 1_000_000f,
    val toneStackSummary: String,
    val specialCircuitNote: String = "",
)

/**
 * Clean-room structured catalog of all 30 Guitar & Bass Amp models matching C++ `AmpModelId` (0..29).
 */
object AmpModelCatalog {
    val allAmps: List<AmpModelSpec> = listOf(
        // 0..19: 20 Agoura Guitar Amps
        AmpModelSpec(0, "US Double Nrm", "US Double", "Fender Twin Reverb (AB763 Normal)", AmpEngineTier.AGOURA, false, 4, 1_000_000f, "FMV AB763 3rd-Order"),
        AmpModelSpec(1, "US Deluxe Nrm", "US Deluxe", "Fender Deluxe Reverb (AB763 Normal)", AmpEngineTier.AGOURA, false, 5, 1_000_000f, "FMV AB763 3rd-Order"),
        AmpModelSpec(2, "US Deluxe Vib", "Deluxe Vib", "Fender Deluxe Reverb (Vibrato + Bright Cap)", AmpEngineTier.AGOURA, false, 5, 1_000_000f, "FMV AB763 + 47pF Bright Cap"),
        AmpModelSpec(3, "US Princess", "Princess", "Fender Princeton Reverb (AA1164)", AmpEngineTier.AGOURA, false, 6, 1_000_000f, "FMV AB763 3rd-Order"),
        AmpModelSpec(4, "US Tweedman", "Tweedman", "Fender Bassman (5F6-A Cathode Follower)", AmpEngineTier.AGOURA, false, 6, 1_000_000f, "FMV 5F6-A CF Stack"),
        AmpModelSpec(5, "US Small Tweed", "Champ 5F1", "Fender Champ (5F1 Single-Ended Class-A)", AmpEngineTier.AGOURA, false, 7, 1_000_000f, "Single-Ended 6V6", "Pure 2nd-Harmonic SE Class-A"),
        AmpModelSpec(6, "Essex A30", "Essex A30", "Vox AC30 Top Boost (Zero-NFB EL84)", AmpEngineTier.AGOURA, false, 3, 1_000_000f, "Vox Top Boost + Cut", "Zero-NFB + Post-PI Cut Control"),
        AmpModelSpec(7, "Essex A15", "Essex A15", "Vox AC15 (EF86 / Top Boost)", AmpEngineTier.AGOURA, false, 3, 1_000_000f, "Vox Top Boost + Cut", "Zero-NFB + Post-PI Cut Control"),
        AmpModelSpec(8, "WhoWatt 103", "WhoWatt", "Hiwatt DR-103 (R.T. James 4th-Order)", AmpEngineTier.AGOURA, false, 1, 1_000_000f, "R.T. James 4th-Order MNA"),
        AmpModelSpec(9, "Brit Plexi Jump", "Plexi Jump", "Marshall Super Lead 1959 (Jumped)", AmpEngineTier.AGOURA, false, 1, 1_000_000f, "FMV Marshall + 5nF Bright Cap"),
        AmpModelSpec(10, "Brit Plexi Low", "Plexi Low", "Marshall Super Lead 1959 (Low-Jack 136k)", AmpEngineTier.AGOURA, false, 1, 136_000f, "FMV Marshall (136k Auto-Z)"),
        AmpModelSpec(11, "Brit 2203", "Brit 2203", "Marshall JCM800 2203 100W Master Volume", AmpEngineTier.AGOURA, false, 0, 1_000_000f, "FMV Marshall + 1nF Bright Cap"),
        AmpModelSpec(12, "Brit J45", "Brit J45", "Marshall JTM45 (KT66 + GZ34 Sag)", AmpEngineTier.AGOURA, false, 1, 1_000_000f, "FMV 5F6-A + GZ34 Rectifier"),
        AmpModelSpec(13, "Brit Trem", "Brit Trem", "Marshall JTM50 Tremolo", AmpEngineTier.AGOURA, false, 1, 1_000_000f, "FMV Marshall"),
        AmpModelSpec(14, "Cali Rectifire", "Rectifire", "Mesa/Boogie Dual Rectifier (Red Modern)", AmpEngineTier.AGOURA, false, 2, 1_000_000f, "FMV High-Gain Modern"),
        AmpModelSpec(15, "Cali IIC+", "Cali 2C+", "Mesa/Boogie Mark IIC+ (Pre-Gain + 5-Band GEQ)", AmpEngineTier.AGOURA, false, 2, 1_000_000f, "Pre-Gain FMV + 5-Band GEQ", "80/240/750/2.2k/6.6k Hz Classic V-Scoop"),
        AmpModelSpec(16, "Solo Lead OD", "SLO Lead", "Soldano SLO-100 Overdrive", AmpEngineTier.AGOURA, false, 0, 1_000_000f, "FMV 39k High-Gain"),
        AmpModelSpec(17, "PV Panama", "PV Panama", "Peavey 5150 Lead (Cold-Clipped 6L6)", AmpEngineTier.AGOURA, false, 0, 1_000_000f, "FMV High-Gain + Cold Clip"),
        AmpModelSpec(18, "Revv Gen Purple", "Revv Purp", "Revv Generator 120 Purple Channel", AmpEngineTier.AGOURA, false, 2, 1_000_000f, "FMV Modern High-Gain"),
        AmpModelSpec(19, "Das Benzin VH4", "Benzin VH4", "Diezel VH4 Channel 3 (92 Hz Deep)", AmpEngineTier.AGOURA, false, 2, 1_000_000f, "FMV High-Gain + Deep", "92 Hz Active Power-Amp Deep Contour"),

        // 20..24: 5 HX Boutique & Studio Clean Guitar Amps
        AmpModelSpec(20, "Litigator ODS", "Litigator", "Dumble Overdrive Special Boutique", AmpEngineTier.HX, false, 5, 1_000_000f, "FMV Boutique ODS"),
        AmpModelSpec(21, "Matchstick Ch1", "Match Ch1", "Matchless DC30 Channel 1", AmpEngineTier.HX, false, 3, 1_000_000f, "Vox Top Boost + Cut"),
        AmpModelSpec(22, "Placater Dirty", "Placater", "Friedman BE-100 Dirty (Hot-Rod Plexi)", AmpEngineTier.HX, false, 0, 1_000_000f, "FMV Hot-Rod Marshall"),
        AmpModelSpec(23, "Jazz Rivet 120", "Jazz 120", "Roland JC-120 Solid-State Studio Clean", AmpEngineTier.HX, false, 4, 1_000_000f, "Ultra-Clean Solid-State"),
        AmpModelSpec(24, "Archetype Clean", "Archetype", "Paul Reed Smith Archon Clean", AmpEngineTier.HX, false, 0, 1_000_000f, "FMV Modern Clean"),

        // 25..29: 5 Agoura & HX Bass Amps
        AmpModelSpec(25, "SV Beast Nrm", "SV Beast", "Ampeg SVT Classic 300W Tube Bass", AmpEngineTier.AGOURA, true, 8, 1_000_000f, "R.T. James + Ultra-Mid", "220/800/3k Hz Gyrator Ultra-Mid"),
        AmpModelSpec(26, "SV B-15 Portaflex", "B-15N", "Ampeg B-15NF Portaflex Flip-Top", AmpEngineTier.AGOURA, true, 10, 1_000_000f, "R.T. James 4th-Order"),
        AmpModelSpec(27, "Agua 51", "Agua 51", "Aguilar DB751 Hybrid Bass Head", AmpEngineTier.AGOURA, true, 9, 1_000_000f, "R.T. James + Active Mid"),
        AmpModelSpec(28, "Mandarin AD200", "AD200 Bass", "Orange AD200 Mk3 Tube Bass", AmpEngineTier.HX, true, 11, 1_000_000f, "R.T. James 4th-Order"),
        AmpModelSpec(29, "Cougar 800", "GK 800RB", "Gallien-Krueger 800RB Bi-Amp Bass", AmpEngineTier.HX, true, 9, 1_000_000f, "4-Band Active Contour"),
    )

    fun byId(nativeId: Int): AmpModelSpec =
        allAmps.getOrElse(nativeId.coerceIn(0, allAmps.lastIndex)) { allAmps[11] }

    fun guitarAmps(): List<AmpModelSpec> = allAmps.filter { !it.isBass }
    fun bassAmps(): List<AmpModelSpec> = allAmps.filter { it.isBass }

    fun search(query: String, bassFilter: Boolean? = null): List<AmpModelSpec> {
        val q = query.trim().lowercase()
        return allAmps.filter { spec ->
            (bassFilter == null || spec.isBass == bassFilter) &&
                (q.isEmpty() ||
                    spec.displayName.lowercase().contains(q) ||
                    spec.basedOn.lowercase().contains(q) ||
                    spec.engineTier.label.lowercase().contains(q))
        }
    }
}
