package com.tcs.vehicleassistant.vision

import android.content.Context
import android.content.SharedPreferences

class GestureMapper(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("GestureMappings", Context.MODE_PRIVATE)

    // Default Mappings
    private val defaults = mapOf(
        "Open_Palm" to "PAUSE",
        "Closed_Fist" to "PLAY",
        "Thumb_Up" to "VOLUME_UP",
        "Thumb_Down" to "VOLUME_DOWN",
        "Pointing_Up" to "HOME",
        "Pointing_Down" to "VOLUME_DOWN",
        "Victory" to "PLAY", // Changed to Play as alternate
        "ILoveYou" to "FAVORITE",
        "Pinch" to "MUTE",
        "Two_Fingers_Up" to "PLAY",
        "Two_Fingers_Left" to "PREVIOUS",
        "Two_Fingers_Right" to "NEXT",
        "Swipe_Left" to "PREVIOUS",
        "Swipe_Right" to "NEXT"
    )

    fun getAction(gesture: String): String {
        val action = prefs.getString(gesture, defaults[gesture] ?: "NONE") ?: "NONE"
        // android.util.Log.d("GestureMapper", "Lookup: $gesture -> $action") 
        return action
    }

    fun setAction(gesture: String, action: String) {
        android.util.Log.d("GestureMapper", "Saving: $gesture -> $action")
        prefs.edit().putString(gesture, action).apply()
    }

    fun getAllGestures(): List<String> {
        return defaults.keys.toList()
    }
    
    companion object {
        val AVAILABLE_ACTIONS = listOf(
            "NONE", "PLAY", "PAUSE", "NEXT", "PREVIOUS", 
            "VOLUME_UP", "VOLUME_DOWN", "MUTE", 
            "HOME", "FAVORITE"
        )
    }
}
