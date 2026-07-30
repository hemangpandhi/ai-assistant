package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsVoiceCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun scanSideloadRoot_readsPackDirectoryWithJsonSpeakers() {
        val pack = tmp.newFolder("lessac-medium")
        File(pack, "en_US-lessac-medium.onnx").writeText("fake-onnx")
        File(pack, "tokens.txt").writeText("a")
        File(pack, "en_US-lessac-medium.onnx.json").writeText(
            """{"num_speakers":1,"audio":{"sample_rate":22050}}"""
        )

        val voices = TtsVoiceCatalog.scanSideloadRoot(tmp.root)
        assertEquals(1, voices.size)
        assertEquals("lessac-medium", voices[0].id)
        assertEquals(1, voices[0].numSpeakers)
        assertEquals(22_050, voices[0].sampleRateHint)
        assertTrue(voices[0].displayName.contains("Lessac", ignoreCase = true))
        assertTrue(!voices[0].fromAssets)
    }

    @Test
    fun scanSideloadRoot_readsLibriTtsSpeakerMap() {
        val pack = tmp.newFolder("libritts_r-medium")
        File(pack, "en_US-libritts_r-medium.onnx").writeText("fake-onnx")
        File(pack, "tokens.txt").writeText("a")
        File(pack, "en_US-libritts_r-medium.onnx.json").writeText(
            """{"speaker_id_map":{"0":0,"1":1,"2":2},"audio":{"sample_rate":22050}}"""
        )

        val voices = TtsVoiceCatalog.scanSideloadRoot(tmp.root)
        assertEquals(1, voices.size)
        // Folder libritts_r-medium normalizes to libritts-r-medium (underscore → hyphen).
        assertEquals("libritts-r-medium", voices[0].id)
        assertEquals(3, voices[0].numSpeakers)
        assertTrue(voices[0].isMultiSpeaker)
        assertTrue(voices[0].displayName.contains("LibriTTS-R", ignoreCase = true))
    }

    @Test
    fun normalizeId_stripsPiperPrefixesAndUnderscores() {
        // Mirror private normalizeId via scan preferId / folder names.
        val root = tmp.root
        val pack = File(root, "vits-piper-en_US-lessac-medium").also { it.mkdirs() }
        File(pack, "en_US-lessac-medium.onnx").writeText("x")
        File(pack, "tokens.txt").writeText("t")
        val voices = TtsVoiceCatalog.scanSideloadRoot(root)
        assertEquals("lessac-medium", voices.single().id)
    }

    @Test
    fun normalizeKnownLabels_forAmyAndGlados() {
        val root = tmp.root
        val amy = File(root, "amy-medium").also { it.mkdirs() }
        File(amy, "en_US-amy-medium.onnx").writeText("x")
        File(amy, "tokens.txt").writeText("t")

        val glados = File(root, "glados").also { it.mkdirs() }
        File(glados, "en_US-glados.onnx").writeText("x")
        File(glados, "tokens.txt").writeText("t")

        val byId = TtsVoiceCatalog.scanSideloadRoot(root).associateBy { it.id }
        assertTrue(byId["amy-medium"]!!.displayName.contains("Amy"))
        assertTrue(byId["glados"]!!.displayName.contains("GLaDOS"))
    }

    @Test
    fun bundledAmy_hasExpectedAssetPaths() {
        val amy = TtsVoiceCatalog.bundledAmy()
        assertEquals(TtsVoiceCatalog.BUNDLED_AMY_ID, amy.id)
        assertTrue(amy.fromAssets)
        assertEquals("sherpa-onnx-tts/en_US-amy-low.onnx", amy.modelPath)
        assertEquals(1, amy.numSpeakers)
    }
}
