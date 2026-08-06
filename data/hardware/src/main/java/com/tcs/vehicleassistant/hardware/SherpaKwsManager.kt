package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Manages Sherpa-ONNX Zipformer Neural Keyword Spotter (KWS).
 * Keywords are the fixed UiUx allowlist: (hey|hi|hello|ok) + (iris|car|sora).
 */
object SherpaKwsManager {
    private const val TAG = "SherpaKwsManager"
    private var spotter: KeywordSpotter? = null

    @Synchronized
    fun getKeywordSpotter(context: Context): KeywordSpotter? {
        if (spotter != null) return spotter

        return try {
            val kwsDir = File(context.filesDir, "kws")
            if (!kwsDir.exists()) kwsDir.mkdirs()

            val keywordsFile = File(kwsDir, "keywords.txt")
            // back to "hey iris" / etc. before the allowlist gate.
            keywordsFile.writeText(
                """
                ${"\u2581"}HE Y ${"\u2581"}I RI S : 2.5
                ${"\u2581"}HI ${"\u2581"}I RI S : 2.5
                ${"\u2581"}HE LL O ${"\u2581"}I RI S : 2.5
                ${"\u2581"}O K ${"\u2581"}I RI S : 2.5
                ${"\u2581"}HE Y ${"\u2581"}C AR : 2.5
                ${"\u2581"}HI ${"\u2581"}C AR : 2.5
                ${"\u2581"}HE LL O ${"\u2581"}C AR : 2.5
                ${"\u2581"}O K ${"\u2581"}C AR : 2.5
                """.trimIndent()
            )

            val externalDir = File("/data/local/tmp/kws")
            if (externalDir.exists() && File(externalDir, "encoder.onnx").exists()) {
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(externalDir, "encoder.onnx").absolutePath,
                            decoder = File(externalDir, "decoder.onnx").absolutePath,
                            joiner = File(externalDir, "joiner.onnx").absolutePath
                        ),
                        tokens = File(externalDir, "tokens.txt").absolutePath,
                        numThreads = 2,
                        debug = false,
                        modelType = "",
                        modelingUnit = "bpe",
                        bpeVocab = File(externalDir, "bpe.model").absolutePath
                    ),
                    keywordsFile = keywordsFile.absolutePath
                )
                spotter = KeywordSpotter(null, config)
                Log.d(TAG, "Sherpa-ONNX KeywordSpotter initialized successfully from external /data/local/tmp/kws/")
            } else {
                Log.i(TAG, "Sherpa ONNX KWS files not found in /data/local/tmp/kws/. Vosk KWS fallback active.")
            }
            spotter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX KeywordSpotter", e)
            null
        }
    }

    fun release() {
        try {
            spotter?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing KeywordSpotter", e)
        }
        spotter = null
    }
}
