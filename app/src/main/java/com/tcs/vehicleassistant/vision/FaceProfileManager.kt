package com.tcs.vehicleassistant.vision

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persistent storage of FaceNet 512D embeddings for multiple users.
 * Uses SharedPreferences to store names mapped to a comma-separated string of floats.
 */
class FaceProfileManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("FaceProfiles", Context.MODE_PRIVATE)

    fun saveProfile(name: String, embedding: FloatArray) {
        val serialized = embedding.joinToString(",")
        prefs.edit().putString(name, serialized).apply()
    }

    fun getAllProfiles(): Map<String, FloatArray> {
        val profiles = mutableMapOf<String, FloatArray>()
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (value is String) {
                try {
                    val floats = value.split(",").map { it.toFloat() }.toFloatArray()
                    if (floats.size == 512) {
                        profiles[key] = floats
                    }
                } catch (e: Exception) {
                    // Ignore corrupted entries
                }
            }
        }
        return profiles
    }

    fun clearAllProfiles() {
        prefs.edit().clear().apply()
    }
}