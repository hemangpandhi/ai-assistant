package com.tcs.vehicleassistant

import com.tcs.vehicleassistant.hardware.CabinCameraManager
import com.tcs.vehicleassistant.vision.GestureFeedback
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the driver-mood classifier the empathy rule in the system prompt depends on. The mood
 * string changes how the assistant answers, so the thresholds are behaviour, not an implementation
 * detail.
 */
class VisionGestureMoodTest {

    @Test
    fun `mood and occupant count start in a neutral state`() {
        assertEquals("Neutral", CabinCameraManager.currentMood)
        assertEquals(0, CabinCameraManager.occupantCount)
    }

    @Test
    fun `an open jaw classifies as tired`() {
        assertEquals(
            CabinCameraManager.MOOD_TIRED,
            CabinCameraManager.classifyMood(smileScore = 0.1f, yawnScore = 0.5f, frownScore = 0f)
        )
    }

    @Test
    fun `a strong smile classifies as happy`() {
        assertEquals(
            CabinCameraManager.MOOD_HAPPY,
            CabinCameraManager.classifyMood(smileScore = 0.8f, yawnScore = 0.1f, frownScore = 0f)
        )
    }

    @Test
    fun `lowered brows classify as frustrated`() {
        assertEquals(
            CabinCameraManager.MOOD_FRUSTRATED,
            CabinCameraManager.classifyMood(smileScore = 0f, yawnScore = 0.1f, frownScore = 0.7f)
        )
    }

    @Test
    fun `weak signals classify as neutral`() {
        assertEquals(
            CabinCameraManager.MOOD_NEUTRAL,
            CabinCameraManager.classifyMood(smileScore = 0.2f, yawnScore = 0.1f, frownScore = 0.2f)
        )
    }

    @Test
    fun `tiredness takes priority over a simultaneous smile`() {
        // A yawn while smiling is still a drowsiness signal, and drowsiness is the safety-relevant
        // one, so it must win.
        assertEquals(
            CabinCameraManager.MOOD_TIRED,
            CabinCameraManager.classifyMood(smileScore = 0.9f, yawnScore = 0.9f, frownScore = 0.9f)
        )
    }

    @Test
    fun `a smile outranks lowered brows`() {
        assertEquals(
            CabinCameraManager.MOOD_HAPPY,
            CabinCameraManager.classifyMood(smileScore = 0.7f, yawnScore = 0f, frownScore = 0.7f)
        )
    }

    @Test
    fun `scores exactly at the threshold do not trip a classification`() {
        assertEquals(
            CabinCameraManager.MOOD_NEUTRAL,
            CabinCameraManager.classifyMood(smileScore = 0.6f, yawnScore = 0.4f, frownScore = 0.6f)
        )
    }

    @Test
    fun `zeroed scores classify as neutral`() {
        assertEquals(CabinCameraManager.MOOD_NEUTRAL, CabinCameraManager.classifyMood(0f, 0f, 0f))
    }

    @Test
    fun `mood labels are distinct so the prompt can discriminate between them`() {
        val labels = listOf(
            CabinCameraManager.MOOD_TIRED,
            CabinCameraManager.MOOD_HAPPY,
            CabinCameraManager.MOOD_FRUSTRATED,
            CabinCameraManager.MOOD_NEUTRAL,
            CabinCameraManager.MOOD_NO_OCCUPANT
        )
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun `gesture feedback carries the fields the action mapper reads`() {
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
}
