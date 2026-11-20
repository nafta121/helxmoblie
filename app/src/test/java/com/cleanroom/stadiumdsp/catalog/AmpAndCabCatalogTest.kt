package com.cleanroom.stadiumdsp.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmpAndCabCatalogTest {

    @Test
    fun catalogContainsExactly30AmpsWithContiguousNativeIds() {
        val amps = AmpModelCatalog.allAmps
        assertEquals(30, amps.size)
        amps.forEachIndexed { index, spec ->
            assertEquals("Amp index mismatch for ${spec.displayName}", index, spec.nativeId)
            assertTrue("Missing basedOn for ${spec.displayName}", spec.basedOn.isNotBlank())
        }
        assertEquals(20, amps.count { it.engineTier == AmpEngineTier.AGOURA && !it.isBass })
        assertEquals(5, amps.count { it.engineTier == AmpEngineTier.HX && !it.isBass })
        assertEquals(5, amps.count { it.isBass })
    }

    @Test
    fun catalogSearchMatchesBothCleanRoomNameAndBasedOnHardware() {
        val byHardware = AmpModelCatalog.search("JCM800")
        assertTrue(byHardware.any { it.nativeId == 11 && it.displayName == "Brit 2203" })

        val byBass = AmpModelCatalog.search("Ampeg SVT")
        assertTrue(byBass.any { it.nativeId == 25 && it.isBass })
    }

    @Test
    fun factoryCabAndMicCatalogHas12CabsAnd12MicsAndMatchesAmpLink() {
        assertEquals(12, FactoryCabCatalog.allCabs.size)
        assertEquals(12, FactoryCabCatalog.allMics.size)
        FactoryCabCatalog.allCabs.forEachIndexed { idx, cab -> assertEquals(idx, cab.nativeId) }
        FactoryCabCatalog.allMics.forEachIndexed { idx, mic -> assertEquals(idx, mic.nativeId) }

        val plexi = AmpModelCatalog.byId(9)
        val linkedCab = FactoryCabCatalog.defaultCabForAmp(plexi.nativeId)
        assertNotNull(linkedCab)
        assertEquals(plexi.defaultCabId, linkedCab.nativeId)
    }

    @Test
    fun parameterSchemaRegistryProvidesMetadataForCoreCircuitParams() {
        val hype = ParameterSchemaRegistry.get("amp.hype")
        assertNotNull(hype)
        assertEquals(0f, hype!!.minValue, 1e-5f)
        assertEquals(1f, hype.maxValue, 1e-5f)
        assertTrue(hype.circuitDescription.isNotBlank())

        val sag = ParameterSchemaRegistry.get("amp.sag")
        assertNotNull(sag)
        val dist = ParameterSchemaRegistry.get("cab.distance_inch")
        assertNotNull(dist)
        assertEquals(1f, dist!!.minValue, 1e-5f)
        assertEquals(12f, dist.maxValue, 1e-5f)
    }
}
