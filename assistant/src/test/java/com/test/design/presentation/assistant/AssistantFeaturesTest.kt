package com.test.design.presentation.assistant

import com.test.design.assistant.api.AssistantCabinContext
import com.test.design.assistant.api.AssistantContextGlyph
import com.test.design.presentation.assistant.backend.buildCabinBeats
import com.test.design.presentation.assistant.backend.shouldHandOffToCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFeaturesTest {

    @Test
    fun hotwordMatcherAcceptsCommonVariants() {
        assertTrue(matchesAssistantHotword("Hey assistant"))
        assertTrue(matchesAssistantHotword("okay assistant, find coffee"))
        assertTrue(matchesAssistantHotword("hi assistant"))
        assertFalse(matchesAssistantHotword("play jazz"))
    }

    @Test
    fun yesNoGesturesMapFromUtterances() {
        assertEquals(FaceGesture.Nod, faceGestureForText("Yes!"))
        assertEquals(FaceGesture.Nod, faceGestureForText("Sure thing"))
        assertEquals(FaceGesture.Shake, faceGestureForText("No thanks"))
        assertEquals(FaceGesture.None, faceGestureForText("Find a charger"))
    }

    @Test
    fun gazeLooksTowardDriverForUserSpeech() {
        val (x, y) = gazeForSpeaker(DialogueSpeaker.User)
        assertTrue(x < 0f)
        assertTrue(y in -0.2f..0.2f)
        val assistant = gazeForSpeaker(DialogueSpeaker.Assistant)
        assertEquals(0f, assistant.first, 0.001f)
    }

    @Test
    fun driveContextSuggestsChargerWhenBatteryLow() {
        val beats = buildCabinBeats(
            AssistantCabinContext(
                drivingUx = "Driving",
                speedMph = 54,
                gear = "D",
                batteryPercent = 18,
                rangeMiles = 40,
            ),
        )
        assertTrue(beats.any { it.text.contains("Battery", ignoreCase = true) })
        assertTrue(beats.any { it.mood == AssistantMood.Speaking })
    }

    @Test
    fun driveContextMarksRestrictedForClusterHandOff() {
        assertTrue(shouldHandOffToCluster("Restricted"))
        assertTrue(shouldHandOffToCluster("Driving"))
        assertFalse(shouldHandOffToCluster("Parked"))
        val beats = buildCabinBeats(
            AssistantCabinContext(
                drivingUx = "Restricted",
                speedMph = 32,
                gear = "D",
                batteryPercent = 80,
                rangeMiles = 200,
            ),
        )
        assertTrue(beats.any { it.text.contains("glanceable", ignoreCase = true) })
    }

    @Test
    fun highContrastEyeFillIsBrighter() {
        assertTrue(eyeFillForContrast(true).red >= eyeFillForContrast(false).red)
        assertTrue(auraAlphaForContrast(true, 0.2f) > auraAlphaForContrast(false, 0.2f))
    }

    @Test
    fun assistantLinesAreSpokenIncludingThinking() {
        assertTrue(
            shouldSpeakBeat(
                DialogueBeat(
                    speaker = DialogueSpeaker.Assistant,
                    text = "On it — thinking…",
                    mood = AssistantMood.Thinking,
                ),
            ),
        )
        assertTrue(
            shouldSpeakBeat(
                DialogueBeat(
                    speaker = DialogueSpeaker.Assistant,
                    text = "Bluebird is nearby.",
                    mood = AssistantMood.Speaking,
                ),
            ),
        )
        assertFalse(
            shouldSpeakBeat(
                DialogueBeat(
                    speaker = DialogueSpeaker.User,
                    text = "Hey assistant",
                    mood = AssistantMood.Listening,
                ),
            ),
        )
        assertFalse(
            shouldSpeakBeat(
                DialogueBeat(
                    speaker = DialogueSpeaker.System,
                    text = "Listening…",
                    mood = AssistantMood.Listening,
                ),
            ),
        )
    }

    @Test
    fun fatigueKeywordsMapToDrowsyOrTired() {
        assertEquals(AssistantMood.Drowsy, fatigueMoodForText("I'm getting drowsy"))
        assertEquals(AssistantMood.Tired, fatigueMoodForText("I'm feeling a bit tired"))
        assertEquals(null, fatigueMoodForText("Find a charger"))
    }

    @Test
    fun answerMoodsGateThumbsFeedback() {
        assertTrue(isAnswerMood(AssistantMood.Speaking))
        assertTrue(isAnswerMood(AssistantMood.Happy))
        assertFalse(isAnswerMood(AssistantMood.Thinking))
        assertFalse(isAnswerMood(AssistantMood.Reading))
        assertFalse(isAnswerMood(AssistantMood.Listening))
    }

    @Test
    fun immersiveScriptTagsWeatherAndClimateGlyphs() {
        val snow = ImmersiveDialogueScript.filter {
            it.text.contains("snow", ignoreCase = true)
        }
        assertTrue(snow.isNotEmpty())
        assertTrue(snow.any { it.contextGlyph == AssistantContextGlyph.WeatherSnow })

        val rain = ImmersiveDialogueScript.filter {
            it.text.contains("rain", ignoreCase = true) ||
                it.text.contains("drizzle", ignoreCase = true)
        }
        assertTrue(rain.any { it.contextGlyph == AssistantContextGlyph.WeatherLightRain })

        val climate = ImmersiveDialogueScript.filter {
            it.contextGlyph == AssistantContextGlyph.ClimateAc ||
                it.contextGlyph == AssistantContextGlyph.ClimateDefrost ||
                it.contextGlyph == AssistantContextGlyph.ClimateThermostat
        }
        assertTrue(climate.size >= 3)
    }
}
