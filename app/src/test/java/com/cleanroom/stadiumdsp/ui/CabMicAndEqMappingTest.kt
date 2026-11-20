package com.cleanroom.stadiumdsp.ui

import com.cleanroom.stadiumdsp.ui.components.CabMicCoordinateMapper
import com.cleanroom.stadiumdsp.ui.components.EqCurveCoordinateMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class CabMicAndEqMappingTest {

    @Test
    fun cabMicMapperConvertsNormalizedCanvasCoordsToPositionAndDistance() {
        // Center of cone (u=0.5) -> Center Cap (position = 0.0), top edge (v=0.0) -> 1.0"
        val (posCenter, distNear) = CabMicCoordinateMapper.canvasToCabParams(u = 0.5f, v = 0.0f)
        assertEquals(0.0f, posCenter, 1e-4f)
        assertEquals(1.0f, distNear, 1e-4f)

        // Cone rim (u=1.0) -> position = 1.0, bottom edge (v=1.0) -> 12.0"
        val (posRim, distFar) = CabMicCoordinateMapper.canvasToCabParams(u = 1.0f, v = 1.0f)
        assertEquals(1.0f, posRim, 1e-4f)
        assertEquals(12.0f, distFar, 1e-4f)

        // Round-trip inverse check
        val (uOut, vOut) = CabMicCoordinateMapper.cabParamsToCanvas(position = 0.40f, distanceInch = 6.5f)
        val (posRt, distRt) = CabMicCoordinateMapper.canvasToCabParams(uOut, vOut)
        assertEquals(0.40f, posRt, 1e-3f)
        assertEquals(6.5f, distRt, 1e-2f)
    }

    @Test
    fun eqCurveMapperUsesExactLogarithmicFrequencyScale20HzTo20kHz() {
        assertEquals(0.0f, EqCurveCoordinateMapper.freqToNormX(20f), 1e-4f)
        assertEquals(1.0f, EqCurveCoordinateMapper.freqToNormX(20_000f), 1e-4f)
        // Geometric midpoint of 20 Hz & 20 kHz is sqrt(400_000) = 632.4555 Hz -> x = 0.5
        assertEquals(632.4555f, EqCurveCoordinateMapper.normXToFreq(0.5f), 0.5f)

        // Gain dB mapping [-15..+15 dB] -> y [1..0]
        assertEquals(0.5f, EqCurveCoordinateMapper.gainDbToNormY(0f), 1e-4f)
        assertEquals(0.0f, EqCurveCoordinateMapper.gainDbToNormY(15f), 1e-4f)
        assertEquals(1.0f, EqCurveCoordinateMapper.gainDbToNormY(-15f), 1e-4f)
        assertEquals(6.0f, EqCurveCoordinateMapper.normYToGainDb(EqCurveCoordinateMapper.gainDbToNormY(6.0f)), 1e-3f)
    }
}
