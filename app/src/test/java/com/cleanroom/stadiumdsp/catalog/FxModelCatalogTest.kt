package com.cleanroom.stadiumdsp.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxModelCatalogTest {

    @Test
    fun catalogContainsAll52PrecisionAnalogFxModels() {
        assertEquals(12, FxModelCatalog.drives.size)
        assertEquals(8, FxModelCatalog.wahFilters.size)
        assertEquals(10, FxModelCatalog.modulations.size)
        assertEquals(5, FxModelCatalog.pitchSynths.size)
        assertEquals(7, FxModelCatalog.delays.size)
        assertEquals(7, FxModelCatalog.reverbs.size)
        assertEquals(3, FxModelCatalog.dynamics.size) // Horizon Gate + LA Studio Opto + Red Squeeze
        assertEquals(3, FxModelCatalog.eqModes.size)  // 4-Band Parametric + 10-Band Graphic + Tilt
        assertEquals(1, FxModelCatalog.volumePan.size)
        assertEquals(1, FxModelCatalog.loopers.size)

        // Core analog FX count (12+8+10+5+7+7+2+1) >= 52 distinct DSP circuits
        assertTrue(FxModelCatalog.allFxModels.size >= 52)
    }

    @Test
    fun autoZImpedanceLabelsMatchCircuitHardwareValues() {
        val fuzzFace = FxModelCatalog.drives.first { it.nativeId == 2 }
        assertEquals(10_000f, fuzzFace.autoZOhms, 1f)

        val rangemaster = FxModelCatalog.drives.first { it.nativeId == 10 }
        assertEquals(10_000f, rangemaster.autoZOhms, 1f)

        val bigMuff = FxModelCatalog.drives.first { it.nativeId == 7 }
        assertEquals(136_000f, bigMuff.autoZOhms, 1f)

        val uniVibe = FxModelCatalog.modulations.first { it.nativeId == 4 }
        assertEquals(22_000f, uniVibe.autoZOhms, 1f)

        val clydeWah = FxModelCatalog.wahFilters.first { it.nativeId == 1 }
        assertEquals(70_000f, clydeWah.autoZOhms, 1f)

        val ts808 = FxModelCatalog.drives.first { it.nativeId == 0 }
        assertEquals(230_000f, ts808.autoZOhms, 1f)
    }

    @Test
    fun searchFindsFxByBasedOnClassicHardwareName() {
        assertTrue(FxModelCatalog.search("Klon").any { it.displayName == "Minotaur" })
        assertTrue(FxModelCatalog.search("Space Echo").any { it.displayName == "Cosmos Echo" })
        assertTrue(FxModelCatalog.search("6G15").any { it.displayName == "'63 Spring" })
        assertTrue(FxModelCatalog.search("Darkglass").any { it.displayName == "Obsidian 7000" })
    }
}
