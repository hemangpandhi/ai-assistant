package com.tcs.vehicleassistant

import android.content.Context
import android.util.Log
// import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.core.BaseOptions
import kotlin.math.sqrt

class SemanticSearchManager(private val toolManager: ToolManager) {
    private val TAG = "SemanticSearch"
    // private var embedder: TextEmbedder? = null
    private var isInitialized = false
    
    // Cache for tool embeddings: Map of CommandName -> FloatArray
    private val toolEmbeddings = mutableMapOf<String, FloatArray>()

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            // Disabled TextEmbedder to fix MediaPipe Vision JNI collision
            /*
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("universal_sentence_encoder.tflite")
                .build()
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()
            
            embedder = TextEmbedder.createFromOptions(context, options)
            */
            isInitialized = true
            // MediaPipe TextEmbedder disabled (JNI clash). Keyword routing is the production path.
            Log.i(TAG, "SemanticSearchManager initialized (keyword-only / no embedder).")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize SemanticSearchManager", e)
        }
    }

    fun embedText(text: String): FloatArray? {
        if (!isInitialized) return null
        return null // Fallback
        /*
        return try {
            val result = embedder?.embed(text)
            val embedding = result?.embeddingResult()?.embeddings()?.firstOrNull()
            embedding?.floatEmbedding()
        } catch (e: Throwable) {
            Log.e(TAG, "Error embedding text: $text", e)
            null
        }
        */
    }

    fun buildToolEmbeddingsCache() {
        if (!isInitialized) return
        val tools = toolManager.getAllTools()
        for ((cmd, def) in tools) {
            val keywordsText = def.keywords?.joinToString(" ") ?: ""
            val description = "${def.handlerKey} $keywordsText"
            val vector = embedText(description)
            if (vector != null) {
                toolEmbeddings[cmd] = vector
            }
        }
        Log.i(TAG, "Built tool embeddings cache. Total tools embedded: ${toolEmbeddings.size}")
    }

    fun search(query: String, topK: Int = 10): List<ToolManager.ToolDefinition> {
        if (!isInitialized) return emptyList()

        val queryVector = embedText(query.lowercase())
        // Embedder disabled / unavailable: never dump full registry (prompt bloat).
        if (queryVector == null) {
            Log.d(TAG, "search fallback empty (no embeddings) for: ${query.take(40)}")
            return emptyList()
        }
        
        val scoredTools = mutableListOf<Pair<ToolManager.ToolDefinition, Float>>()
        
        for ((cmd, toolVector) in toolEmbeddings) {
            val def = toolManager.getToolDefinition(cmd) ?: continue
            val similarity = cosineSimilarity(queryVector, toolVector)
            scoredTools.add(Pair(def, similarity))
        }
        
        // Also include generic tools that don't have embeddings
        val allTools = toolManager.getAllTools()
        for ((cmd, def) in allTools) {
            if (!toolEmbeddings.containsKey(cmd)) {
                // Base score for tools without keywords
                scoredTools.add(Pair(def, 0.1f))
            }
        }

        return scoredTools.sortedByDescending { it.second }.map { it.first }.take(topK)
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        if (norm1 == 0.0 || norm2 == 0.0) return 0f
        return (dotProduct / (sqrt(norm1) * sqrt(norm2))).toFloat()
    }
}
