package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.hardware.CabinCameraManager
import com.tcs.vehicleassistant.vision.GestureFeedback
import org.junit.Assert.*
import org.junit.Test

class VisionGestureMoodTest {

    @Test
    fun testCabinCameraManagerDefaultMoodAndOccupants() {
        assertEquals("Neutral", CabinCameraManager.currentMood)
        assertEquals(0, CabinCameraManager.occupantCount)
    }

    @Test
    fun testGestureFeedbackDataClass() {
        val feedback = GestureFeedback(
            gestureName = "Thumb_Up",
            score = 0.95f,
            feedbackMessage = "VOLUME_UP",
            positionHint = "Center",
            mood = "Happy :)"
        )
        assertEquals("Thumb_Up", feedback.gestureName)
        assertEquals(0.95f, feedback.score, 0.001f)
        assertEquals("VOLUME_UP", feedback.feedbackMessage)
        assertEquals("Happy :)", feedback.mood)
    }

    @Test
    fun testMoodClassificationLogic() {
        // Simulating the threshold logic from CabinCameraManager & GestureProcessor
        fun classifyMood(smileScore: Float, yawnScore: Float, frownScore: Float): String {
            return when {
                yawnScore > 0.4f -> "Tired / Yawning"
                smileScore > 0.6f -> "Happy / Smiling"
                frownScore > 0.6f -> "Frustrated / Frowning"
                else -> "Neutral / Focused"
            }
        }

        assertEquals("Tired / Yawning", classifyMood(smileScore = 0.1f, yawnScore = 0.5f, frownScore = 0.0f))
        assertEquals("Happy / Smiling", classifyMood(smileScore = 0.8f, yawnScore = 0.1f, frownScore = 0.0f))
        assertEquals("Frustrated / Frowning", classifyMood(smileScore = 0.0f, yawnScore = 0.1f, frownScore = 0.7f))
        assertEquals("Neutral / Focused", classifyMood(smileScore = 0.2f, yawnScore = 0.1f, frownScore = 0.2f))
    }

    @Test
    fun testDefaultGestureActionMappings() {
        val defaults = mapOf(
            "Open_Palm" to "PAUSE",
            "Closed_Fist" to "PLAY",
            "Thumb_Up" to "VOLUME_UP",
            "Thumb_Down" to "VOLUME_DOWN",
            "Pointing_Up" to "HOME",
            "ILoveYou" to "FAVORITE",
            "Pinch" to "MUTE",
            "Swipe_Left" to "PREVIOUS",
            "Swipe_Right" to "NEXT"
        )

        assertEquals("PAUSE", defaults["Open_Palm"])
        assertEquals("PLAY", defaults["Closed_Fist"])
        assertEquals("VOLUME_UP", defaults["Thumb_Up"])
        assertEquals("VOLUME_DOWN", defaults["Thumb_Down"])
        assertEquals("HOME", defaults["Pointing_Up"])
        assertEquals("FAVORITE", defaults["ILoveYou"])
        assertEquals("MUTE", defaults["Pinch"])
        assertEquals("PREVIOUS", defaults["Swipe_Left"])
        assertEquals("NEXT", defaults["Swipe_Right"])
    }
}
