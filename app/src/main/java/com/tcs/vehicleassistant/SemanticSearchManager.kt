package com.tcs.vehicleassistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.core.BaseOptions
import kotlin.math.sqrt

object SemanticSearchManager {
    private const val TAG = "SemanticSearch"
    private var embedder: TextEmbedder? = null
    private var isInitialized = false
    
    // Cache for tool embeddings: Map of CommandName -> FloatArray
    private val toolEmbeddings = mutableMapOf<String, FloatArray>()

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("universal_sentence_encoder.tflite")
                .build()
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()
            
            embedder = TextEmbedder.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "SemanticSearchManager initialized successfully.")
            
            // Wait for ToolManager to be initialized to build cache, but we'll build it lazily or explicitly
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize SemanticSearchManager", e)
        }
    }

    fun embedText(text: String): FloatArray? {
        if (!isInitialized || embedder == null) return null
        return try {
            val result = embedder?.embed(text)
            val embedding = result?.embeddingResult()?.embeddings()?.firstOrNull()
            embedding?.floatEmbedding()
        } catch (e: Throwable) {
            Log.e(TAG, "Error embedding text: $text", e)
            null
        }
    }

    fun buildToolEmbeddingsCache() {
        if (!isInitialized) return
        val tools = ToolManager.getAllTools()
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
        if (!isInitialized) return ToolManager.getAllTools().values.take(topK).toList()
        
        val queryVector = embedText(query.lowercase()) ?: return ToolManager.getAllTools().values.take(topK).toList()
        
        val scoredTools = mutableListOf<Pair<ToolManager.ToolDefinition, Float>>()
        
        for ((cmd, toolVector) in toolEmbeddings) {
            val def = ToolManager.getToolDefinition(cmd) ?: continue
            val similarity = cosineSimilarity(queryVector, toolVector)
            scoredTools.add(Pair(def, similarity))
        }
        
        // Also include generic tools that don't have embeddings
        val allTools = ToolManager.getAllTools()
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
