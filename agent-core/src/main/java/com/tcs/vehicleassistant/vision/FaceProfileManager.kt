package com.tcs.vehicleassistant.vision

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Manages persistent storage of FaceNet embeddings and mapped user settings for multiple users.
 * Uses a Shared Master File (/sdcard/FaceProfiles.json) alongside SharedPreferences
 * to ensure face profiles are 100% synced across ALL Android OS User Profiles (User 0, 10, 13, etc.).
 */
class FaceProfileManager(context: Context) {

    private val deviceContext: Context = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else {
        context
    }

    private val prefs: SharedPreferences = deviceContext.getSharedPreferences("FaceProfiles", Context.MODE_PRIVATE)

    private val sharedFile: File
        get() {
            return try {
                val externalSd = File("/sdcard/FaceProfiles.json")
                if (!externalSd.exists()) {
                    externalSd.createNewFile()
                }
                externalSd
            } catch (e: Exception) {
                File(deviceContext.filesDir, "FaceProfiles.json")
            }
        }

    fun saveProfile(name: String, embedding: FloatArray, osUserId: Int = 10, targetTemp: Float = 20.0f) {
        val serialized = embedding.joinToString(",")
        
        // 1. Save to local SharedPreferences
        prefs.edit()
            .putString(name, serialized)
            .putInt("userid_$name", osUserId)
            .putFloat("temp_$name", targetTemp)
            .apply()

        // 2. Sync to Global Shared JSON file for cross-user profile access
        try {
            val json = readSharedJsonFile()
            val userObj = JSONObject().apply {
                put("embedding", serialized)
                put("osUserId", osUserId)
                put("targetTemp", targetTemp.toDouble())
            }
            json.put(name, userObj)
            writeSharedJsonFile(json)
            Log.d("FaceProfileManager", "Saved profile '$name' to Global Cross-User File (${sharedFile.absolutePath})")
        } catch (e: Exception) {
            Log.e("FaceProfileManager", "Failed to sync to shared file: ${e.message}")
        }
    }

    fun getOsUserId(name: String): Int {
        val fromPrefs = prefs.getInt("userid_$name", -1)
        if (fromPrefs != -1) return fromPrefs

        return try {
            val json = readSharedJsonFile()
            if (json.has(name)) {
                json.getJSONObject(name).optInt("osUserId", 10)
            } else 10
        } catch (e: Exception) { 10 }
    }

    fun getTargetTemp(name: String): Float {
        val fromPrefs = prefs.getFloat("temp_$name", -1f)
        if (fromPrefs != -1f) return fromPrefs

        return try {
            val json = readSharedJsonFile()
            if (json.has(name)) {
                json.getJSONObject(name).optDouble("targetTemp", 20.0).toFloat()
            } else 20.0f
        } catch (e: Exception) { 20.0f }
    }

    fun getAllProfiles(): Map<String, FloatArray> {
        val profiles = mutableMapOf<String, FloatArray>()

        // 1. Load from SharedPreferences
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (value is String && !key.startsWith("userid_") && !key.startsWith("temp_")) {
                try {
                    val floats = value.split(",").map { it.toFloat() }.toFloatArray()
                    if (floats.size == 128 || floats.size == 512) {
                        profiles[key] = floats
                    }
                } catch (e: Exception) {}
            }
        }

        // 2. Merge from Global Shared File across user boundaries
        try {
            val json = readSharedJsonFile()
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (!profiles.containsKey(name)) {
                    val userObj = json.getJSONObject(name)
                    val embStr = userObj.getString("embedding")
                    val floats = embStr.split(",").map { it.toFloat() }.toFloatArray()
                    if (floats.size == 128 || floats.size == 512) {
                        profiles[name] = floats
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("FaceProfileManager", "Could not read shared JSON file: ${e.message}")
        }

        return profiles
    }

    private fun readSharedJsonFile(): JSONObject {
        return try {
            val file = sharedFile
            if (file.exists() && file.length() > 0) {
                JSONObject(file.readText())
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeSharedJsonFile(json: JSONObject) {
        try {
            val file = sharedFile
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e("FaceProfileManager", "Error writing shared JSON: ${e.message}")
        }
    }

    fun clearAllProfiles() {
        prefs.edit().clear().apply()
        try { sharedFile.delete() } catch (e: Exception) {}
    }
}