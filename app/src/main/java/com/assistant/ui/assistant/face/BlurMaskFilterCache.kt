package com.assistant.ui.assistant.face

import android.graphics.BlurMaskFilter
import android.util.SparseArray

/**
 * Reuses native Skia [BlurMaskFilter] instances across frames.
 *
 * Creating a new BlurMaskFilter every draw allocates on the C++ heap and triggers
 * GC pressure on automotive GPUs. Radii are quantized to 0.5px buckets so nearby
 * sizes share one filter.
 */
internal object BlurMaskFilterCache {
    private const val MaxKey = 512
    private val filters = SparseArray<BlurMaskFilter>(64)

    fun get(radiusPx: Float): BlurMaskFilter {
        val key = ((radiusPx.coerceAtLeast(0.5f) * 2f).toInt()).coerceIn(1, MaxKey)
        synchronized(this) {
            filters.get(key)?.let { return it }
            val filter = BlurMaskFilter(key * 0.5f, BlurMaskFilter.Blur.NORMAL)
            filters.put(key, filter)
            return filter
        }
    }
}
