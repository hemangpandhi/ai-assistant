package com.tcs.vehicleassistant.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Discovers offline Piper/Sherpa TTS voices available to the cabin assistant.
 *
 * The APK ships one bundled voice (Amy low). Additional Piper models can be sideloaded under
 * `/data/local/tmp/tts/<voice-id>/` without rebuilding the APK — each folder needs a `.onnx` model
 * and `tokens.txt` (and may include a `*.onnx.json` with `num_speakers`).
 */
object TtsVoiceCatalog {

    const val BUNDLED_AMY_ID = "amy-low"
    const val SIDELOAD_ROOT = "/data/local/tmp/tts"

    data class VoiceOption(
        val id: String,
        val displayName: String,
        /** Asset-relative path when [fromAssets] is true; otherwise absolute filesystem path. */
        val modelPath: String,
        val tokensPath: String,
        val fromAssets: Boolean,
        val numSpeakers: Int,
        val sampleRateHint: Int = 16_000,
    ) {
        val isMultiSpeaker: Boolean get() = numSpeakers > 1
    }

    /**
     * Known Piper packs that OEMs commonly sideload. Shown in settings only when present on disk
     * (except [BUNDLED_AMY_ID], which is always available from assets).
     */
    private val KNOWN_SIDELOAD_LABELS = mapOf(
        "amy-low" to "Amy (US, low)",
        "amy-medium" to "Amy (US, medium)",
        "lessac-low" to "Lessac (US, low)",
        "lessac-medium" to "Lessac (US, medium)",
        "lessac-high" to "Lessac (US, high)",
        // Ids are normalized with '_' → '-', so libritts_r-medium becomes libritts-r-medium.
        "libritts-r-medium" to "LibriTTS-R (US, 904 speakers)",
        "libritts-high" to "LibriTTS (US, high)",
        "glados" to "GLaDOS (US)",
        "alan-low" to "Alan (GB, low)",
        "cori-medium" to "Cori (GB, medium)",
    )

    fun bundledAmy(): VoiceOption = VoiceOption(
        id = BUNDLED_AMY_ID,
        displayName = "Amy (US, low) — bundled",
        modelPath = "sherpa-onnx-tts/en_US-amy-low.onnx",
        tokensPath = "sherpa-onnx-tts/tokens.txt",
        fromAssets = true,
        numSpeakers = 1,
        sampleRateHint = 16_000,
    )

    /** All voices currently usable on this device (bundled + readable sideloads). */
    fun availableVoices(context: Context): List<VoiceOption> {
        val out = linkedMapOf<String, VoiceOption>()
        out[BUNDLED_AMY_ID] = bundledAmy()

        scanSideloadRoot(File(SIDELOAD_ROOT)).forEach { out[it.id] = it }
        context.getExternalFilesDir("tts")?.let { dir ->
            scanSideloadRoot(dir).forEach { out[it.id] = it }
        }
        scanSideloadRoot(File(context.filesDir, "tts")).forEach { out[it.id] = it }

        return out.values.toList()
    }

    fun findById(context: Context, id: String?): VoiceOption {
        val voices = availableVoices(context)
        if (id.isNullOrBlank()) return bundledAmy()
        val normalized = normalizeId(id)
        return voices.firstOrNull { it.id == id || it.id == normalized } ?: bundledAmy()
    }

    fun scanSideloadRoot(root: File): List<VoiceOption> {
        if (!root.isDirectory) return emptyList()
        val results = mutableListOf<VoiceOption>()

        // Flat .onnx files next to a shared tokens.txt
        val sharedTokens = File(root, "tokens.txt").takeIf { it.isFile }
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".onnx") && !it.name.contains("encoder") }
            ?.forEach { model ->
                val tokens = sharedTokens ?: File(root, model.nameWithoutExtension + ".tokens.txt")
                    .takeIf { it.isFile }
                    ?: return@forEach
                results += voiceFromFiles(model, tokens, preferId = model.nameWithoutExtension)
            }

        // One subdirectory per voice pack
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val model = dir.listFiles()?.firstOrNull {
                    it.isFile && it.name.endsWith(".onnx") && !it.name.contains("encoder")
                } ?: return@forEach
                val tokens = File(dir, "tokens.txt").takeIf { it.isFile }
                    ?: dir.listFiles()?.firstOrNull { it.name.endsWith("tokens.txt") }
                    ?: return@forEach
                results += voiceFromFiles(model, tokens, preferId = dir.name)
            }

        return results.distinctBy { it.id }
    }

    private fun voiceFromFiles(model: File, tokens: File, preferId: String): VoiceOption {
        val json = File(model.parentFile, model.name + ".json").takeIf { it.isFile }
            ?: File(model.parentFile, model.nameWithoutExtension + ".onnx.json").takeIf { it.isFile }
        val numSpeakers = readNumSpeakers(json) ?: 1
        val sampleRate = readSampleRate(json) ?: 22_050
        val id = normalizeId(preferId)
        val label = KNOWN_SIDELOAD_LABELS[id]
            ?: humanize(preferId)
        return VoiceOption(
            id = id,
            displayName = "$label — sideloaded",
            modelPath = model.absolutePath,
            tokensPath = tokens.absolutePath,
            fromAssets = false,
            numSpeakers = numSpeakers,
            sampleRateHint = sampleRate,
        )
    }

    private fun readNumSpeakers(jsonFile: File?): Int? {
        if (jsonFile == null) return null
        return try {
            val obj = JSONObject(jsonFile.readText())
            when {
                obj.has("num_speakers") -> obj.getInt("num_speakers")
                obj.optJSONObject("speaker_id_map")?.length()?.let { it > 0 } == true ->
                    obj.getJSONObject("speaker_id_map").length()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readSampleRate(jsonFile: File?): Int? {
        if (jsonFile == null) return null
        return try {
            JSONObject(jsonFile.readText()).optJSONObject("audio")?.optInt("sample_rate")
                ?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeId(raw: String): String =
        raw.lowercase()
            .removePrefix("vits-piper-")
            .removePrefix("en_us-")
            .removePrefix("en_gb-")
            .replace('_', '-')
            .trim('-')

    private fun humanize(raw: String): String =
        normalizeId(raw).split('-').joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    /**
     * Short UI hint for Settings. Full push recipe is in [SIDELOAD_ADB_HINT].
     */
    fun settingsHint(voices: List<VoiceOption>): String {
        val sideloaded = voices.count { !it.fromAssets }
        return when {
            sideloaded == 0 ->
                "Only Amy (bundled). Sideload more: $SIDELOAD_ADB_HINT"
            else ->
                "${voices.size} voices (${sideloaded} sideloaded). Swap packs under $SIDELOAD_ROOT/<id>/ (.onnx + tokens.txt + optional .onnx.json)."
        }
    }

    /**
     * Example adb push for a Piper pack from
     * https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
     *
     * ```
     * tar xf vits-piper-en_US-lessac-medium.tar.bz2
     * adb shell mkdir -p /data/local/tmp/tts/lessac-medium
     * adb push en_US-lessac-medium.onnx /data/local/tmp/tts/lessac-medium/
     * adb push tokens.txt /data/local/tmp/tts/lessac-medium/
     * adb push en_US-lessac-medium.onnx.json /data/local/tmp/tts/lessac-medium/
     * ```
     *
     * Also works for amy-medium → `/data/local/tmp/tts/amy-medium/` and
     * libritts_r-medium → `/data/local/tmp/tts/libritts-r-medium/` (folder id is normalized `_`→`-`).
     */
    const val SIDELOAD_ADB_HINT =
        "adb push <onnx+tokens> /data/local/tmp/tts/<id>/  (see TtsVoiceCatalog)"
}
