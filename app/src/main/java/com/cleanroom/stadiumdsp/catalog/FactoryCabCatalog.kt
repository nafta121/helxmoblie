package com.cleanroom.stadiumdsp.catalog

data class FactoryCabSpecUi(
    val nativeId: Int,
    val displayName: String,
    val shortLabel: String,
    val speakerConfig: String,
    val basedOn: String,
    val coneFsHz: Float,
    val isBass: Boolean,
)

data class StudioMicSpecUi(
    val nativeId: Int,
    val displayName: String,
    val badgeCode: String,
    val type: String,
    val basedOn: String,
)

/**
 * Clean-room catalog of 12 Factory Guitar/Bass Cabs & 12 Studio Microphones matching C++ enums (0..11).
 */
object FactoryCabCatalog {
    val allCabs: List<FactoryCabSpecUi> = listOf(
        FactoryCabSpecUi(0, "4x12 Brit V30", "4x12 V30", "4x12\"", "Marshall 1960BV Celestion Vintage 30", 96f, false),
        FactoryCabSpecUi(1, "4x12 Greenback 25", "4x12 GB25", "4x12\"", "Marshall 1960B Celestion G12M-25 Greenback", 88f, false),
        FactoryCabSpecUi(2, "4x12 Cali Oversized", "4x12 Cali", "4x12\"", "Mesa Rectifier Standard Oversized V30", 82f, false),
        FactoryCabSpecUi(3, "2x12 Essex Blue Bell", "2x12 Blue", "2x12\"", "Vox AC30 Celestion Alnico Blue", 92f, false),
        FactoryCabSpecUi(4, "2x12 US Twin Jensen", "2x12 Twin", "2x12\"", "Fender Twin Reverb Jensen C12N", 94f, false),
        FactoryCabSpecUi(5, "1x12 US Deluxe", "1x12 Dlx", "1x12\"", "Fender Deluxe Reverb Oxford 12K5-6", 104f, false),
        FactoryCabSpecUi(6, "1x12 Tweed Alnico", "1x12 Twd", "1x12\"", "Vintage 1950s Tweed Jensen P12Q", 110f, false),
        FactoryCabSpecUi(7, "1x8 Tweed Champ", "1x8 Chmp", "1x8\"", "1957 Fender Champ 5F1 Alnico 8\"", 124f, false),
        FactoryCabSpecUi(8, "8x10 SV Beast Fridge", "8x10 SVT", "8x10\"", "Ampeg SVT-810E Sealed Bass Enclosure", 66f, true),
        FactoryCabSpecUi(9, "4x10 Ampeg Pro", "4x10 Pro", "4x10\"", "Ampeg / Aguilar 4x10 Ported Bass Cab", 62f, true),
        FactoryCabSpecUi(10, "1x15 B-15 Portaflex", "1x15 B15", "1x15\"", "Ampeg B-15N Flip-Top 15\" CTS", 54f, true),
        FactoryCabSpecUi(11, "2x15 Bassman Dual", "2x15 Bass", "2x15\"", "Dual 15\" JBL D140F Vintage Bass Cab", 58f, true),
    )

    val allMics: List<StudioMicSpecUi> = listOf(
        StudioMicSpecUi(0, "57 Dynamic", "57", "Dynamic", "Shure SM57 Cardioid"),
        StudioMicSpecUi(1, "421 Dynamic", "421", "Dynamic", "Sennheiser MD 421-II"),
        StudioMicSpecUi(2, "7 Dynamic", "7B", "Dynamic", "Shure SM7B Broadcast Dynamic"),
        StudioMicSpecUi(3, "906 Dynamic", "906", "Dynamic", "Sennheiser e906 Flat-Side"),
        StudioMicSpecUi(4, "121 Ribbon", "121", "Ribbon", "Royer R-121 Figure-8 Ribbon"),
        StudioMicSpecUi(5, "160 Ribbon", "160", "Ribbon", "Beyerdynamic M160 Hypercardioid Ribbon"),
        StudioMicSpecUi(6, "4038 Ribbon", "4038", "Ribbon", "Coles 4038 Studio Ribbon"),
        StudioMicSpecUi(7, "84 Condenser", "84", "Condenser", "Neumann KM84 Small Diaphragm"),
        StudioMicSpecUi(8, "414 Condenser", "414", "Condenser", "AKG C414 XLS Multi-Pattern"),
        StudioMicSpecUi(9, "67 Condenser", "67", "Condenser", "Neumann U67 Tube Condenser"),
        StudioMicSpecUi(10, "47 FET Condenser", "47F", "Condenser", "Neumann U47 FET"),
        StudioMicSpecUi(11, "112 Bass Dynamic", "112", "Dynamic", "AKG D112 Low-End Dynamic"),
    )

    fun cabById(id: Int): FactoryCabSpecUi =
        allCabs.getOrElse(id.coerceIn(0, allCabs.lastIndex)) { allCabs[0] }

    fun micById(id: Int): StudioMicSpecUi =
        allMics.getOrElse(id.coerceIn(0, allMics.lastIndex)) { allMics[0] }

    fun defaultCabForAmp(ampNativeId: Int): FactoryCabSpecUi =
        cabById(AmpModelCatalog.byId(ampNativeId).defaultCabId)

    fun searchCabs(query: String): List<FactoryCabSpecUi> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return allCabs
        return allCabs.filter {
            it.displayName.lowercase().contains(q) ||
                it.basedOn.lowercase().contains(q) ||
                it.speakerConfig.lowercase().contains(q)
        }
    }
}
