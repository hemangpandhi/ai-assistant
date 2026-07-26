package com.test.design.presentation.assistant.gallery

import com.test.design.assistant.api.AssistantContextGlyph
import com.test.design.presentation.assistant.AssistantMood
import com.test.design.presentation.assistant.usesImmersivePurpleGlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantUiStyleTest {

    @Test
    fun atLeastTwelveOpaqueGalleryStyles() {
        assertTrue(AssistantUiStyle.entries.size >= 12)
    }

    @Test
    fun requiredFamiliesPresent() {
        val titles = AssistantUiStyle.entries.map { it.title }.toSet()
        assertTrue(titles.contains("Voice plate"))
        assertTrue(titles.contains("Face only"))
        assertTrue(titles.contains("Waveform"))
        assertTrue(titles.contains("Orb"))
        assertTrue(titles.contains("Capsule face"))
        assertTrue(!titles.contains("Bar"))
        assertTrue(titles.contains("Side rail"))
        assertTrue(titles.contains("Equalizer"))
        assertTrue(titles.contains("Listening rings"))
        assertTrue(titles.contains("Corner bubble"))
        assertTrue(titles.contains("Wave + face"))
        assertTrue(titles.contains("Ambient pill"))
        assertTrue(titles.contains("Immersive eyes"))
        assertTrue(titles.contains("Immersive glow"))
        assertTrue(titles.contains("Immersive hybrid"))
        assertTrue(titles.contains("Droid face"))
        assertTrue(titles.contains("EPORO"))
        assertTrue(titles.contains("Fusion"))
        assertTrue(titles.contains("Fusion glow"))
        assertTrue(titles.contains("Fusion eyes"))
        assertTrue(titles.contains("Weather sink"))
    }

    @Test
    fun styleNamesAreUnique() {
        assertEquals(AssistantUiStyle.entries.size, AssistantUiStyle.entries.map { it.name }.toSet().size)
    }

    @Test
    fun weatherSinkScriptIsWeatherOnly() {
        assertTrue(WeatherSinkDialogueScript.isNotEmpty())
        assertTrue(
            WeatherSinkDialogueScript.all { beat ->
                beat.contextGlyph == null ||
                    beat.contextGlyph.name.startsWith("Weather")
            },
        )
        assertTrue(
            WeatherSinkDialogueScript.none { beat ->
                beat.contextGlyph?.name?.startsWith("Climate") == true
            },
        )
        assertTrue(
            WeatherSinkDialogueScript.any {
                it.contextGlyph == AssistantContextGlyph.WeatherSnow
            },
        )
        assertTrue(
            WeatherSinkDialogueScript.any {
                it.contextGlyph == AssistantContextGlyph.WeatherLightRain
            },
        )
    }

    @Test
    fun immersiveHybridUsesPurpleOnExpressiveMoods() {
        assertTrue(AssistantMood.Excited.usesImmersivePurpleGlow())
        assertTrue(AssistantMood.Happy.usesImmersivePurpleGlow())
        assertTrue(AssistantMood.Searching.usesImmersivePurpleGlow())
        assertTrue(AssistantMood.Thinking.usesImmersivePurpleGlow())
        assertFalse(AssistantMood.Idle.usesImmersivePurpleGlow())
        assertFalse(AssistantMood.Listening.usesImmersivePurpleGlow())
        assertFalse(AssistantMood.Speaking.usesImmersivePurpleGlow())
        assertFalse(AssistantMood.Sad.usesImmersivePurpleGlow())
    }
}
