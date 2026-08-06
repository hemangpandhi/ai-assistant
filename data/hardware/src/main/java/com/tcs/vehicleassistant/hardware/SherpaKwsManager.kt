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
            // Phoneme-spaced keywords; WakeWordPhrasePolicy.normalizeKwsKeyword maps hits
            // back to "hey iris" / etc. before the allowlist gate.
            keywordsFile.writeText(
                """
                H EY I R I S : 3.0
                H I I R I S : 2.5
                H EH L OW I R I S : 2.5
                O K I R I S : 2.5
                H EY C A R : 3.0
                H I C A R : 2.5
                H EH L OW C A R : 2.5
                O K C A R : 2.5
                H EY S O R A : 3.0
                H I S O R A : 2.5
                H EH L OW S O R A : 2.5
                O K S O R A : 2.5
                """.trimIndent()
            )

            val externalDir = File("/data/local/tmp/kws")
            if (externalDir.exists() && File(externalDir, "encoder.onnx").exists()) {
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                            model = File(externalDir, "encoder.onnx").absolutePath
                        ),
                        tokens = File(externalDir, "tokens.txt").absolutePath,
                        numThreads = 2,
                        debug = false
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
