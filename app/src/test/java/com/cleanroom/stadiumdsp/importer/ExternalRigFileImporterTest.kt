package com.cleanroom.stadiumdsp.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRigFileImporterTest {

    @Test
    fun detectsAssetKindFromFilenameExtensionCaseInsensitively() {
        assertEquals(ExternalRigAssetKind.NAM_CLONE, ExternalRigFileValidator.detectKind("Marshall_JCM800.NAM"))
        assertEquals(ExternalRigAssetKind.AIDAX_CLONE, ExternalRigFileValidator.detectKind("mesa_mark_iic.aidax"))
        assertEquals(ExternalRigAssetKind.WAV_IR, ExternalRigFileValidator.detectKind("V30_SM57_CapEdge.wav"))
        assertEquals(ExternalRigAssetKind.UNKNOWN, ExternalRigFileValidator.detectKind("readme.pdf"))
    }

    @Test
    fun validatesRiffWaveHeaderAndRejectsCorruptPayload() {
        val validWav = ByteArray(48)
        "RIFF".toByteArray().copyInto(validWav, 0)
        "WAVE".toByteArray().copyInto(validWav, 8)
        val ok = ExternalRigFileValidator.validatePayload("cab.wav", validWav)
        assertTrue(ok.isValid)
        assertEquals(ExternalRigAssetKind.WAV_IR, ok.kind)

        val bad = ExternalRigFileValidator.validatePayload("cab.wav", "NOT_A_WAV".toByteArray())
        assertFalse(bad.isValid)
    }

    @Test
    fun validatesNamAndAidaxJsonEnvelopeAndRejectsOversizedFiles() {
        val namJson = """{"architecture":"WaveNet","config":{"layers":[]},"weights":[0.1,0.2]}""".toByteArray()
        assertTrue(ExternalRigFileValidator.validatePayload("amp.nam", namJson).isValid)

        val aidaxJson = """{"model_data":{"model":"GRU","hidden_size":8},"state_dict":{}}""".toByteArray()
        assertTrue(ExternalRigFileValidator.validatePayload("amp.aidax", aidaxJson).isValid)

        val invalidJson = "plain text".toByteArray()
        assertFalse(ExternalRigFileValidator.validatePayload("broken.nam", invalidJson).isValid)

        val empty = ByteArray(0)
        assertFalse(ExternalRigFileValidator.validatePayload("empty.aidax", empty).isValid)
    }
}
