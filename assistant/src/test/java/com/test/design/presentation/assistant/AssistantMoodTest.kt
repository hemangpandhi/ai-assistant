package com.test.design.presentation.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMoodTest {

    @Test
    fun moodsCoverRequestedPersonalityStates() {
        val labels = AssistantMood.entries.map { it.label }.toSet()
        assertTrue(
            labels.containsAll(
                listOf(
                    "Idle",
                    "Listening",
                    "Speaking",
                    "Thinking",
                    "Happy",
                    "Sad",
                    "Excited",
                    "Bored",
                    "Drowsy",
                    "Tired",
                    "Reading",
                    "Searching",
                ),
            ),
        )
    }

    @Test
    fun eporoListeningRingPulsesStrongerThanIdle() {
        assertTrue(
            AssistantMood.Listening.toEporoPose().ringPulse >
                AssistantMood.Idle.toEporoPose().ringPulse,
        )
        assertTrue(AssistantMood.Searching.toEporoPose().eyeOpen >= 1f)
    }

    @Test
    fun glowIntensityStaysInUnitRange() {
        AssistantMood.entries.forEach { mood ->
            assertTrue(mood.glowIntensity in 0f..1f)
        }
    }

    @Test
    fun facePoseKeepsOneCharacterBounds() {
        AssistantMood.entries.forEach { mood ->
            val pose = mood.toFacePose()
            assertTrue(pose.eyeOpen in 0.3f..1.35f)
            assertTrue(pose.eyeWidth in 0.5f..1.3f)
            assertTrue(pose.eyeHeight in 0.35f..1.3f)
            assertTrue(pose.borderGlow in 0f..1.2f)
            assertTrue(pose.blush in 0f..1f)
            assertTrue(pose.roundness in 0.5f..1f)
            assertTrue(pose.mouthCurve in -1.1f..1.1f)
            assertTrue(pose.mouthOpen in 0f..1f)
            assertTrue(pose.mouthWidth in 0.5f..1.3f)
            assertTrue(pose.eyeStyle in -1.1f..1.1f)
        }
        assertTrue(AssistantMood.Happy.toFacePose().mouthCurve > AssistantMood.Sad.toFacePose().mouthCurve)
        assertTrue(AssistantMood.Speaking.toFacePose().mouthOpen > AssistantMood.Idle.toFacePose().mouthOpen)
        assertTrue(AssistantMood.Excited.toFacePose().eyeOpen > AssistantMood.Drowsy.toFacePose().eyeOpen)
    }

    @Test
    fun fusionEyePoseMorphsShapeAndSpacingByMood() {
        AssistantMood.entries.forEach { mood ->
            val pose = mood.toFusionEyePose()
            assertTrue(pose.eyeOpen in 0.2f..1.4f)
            assertTrue(pose.eyeWidth in 0.7f..1.5f)
            assertTrue(pose.eyeHeight in 0.3f..1.4f)
            assertTrue(pose.eyeGap in 0.65f..1.45f)
            assertTrue(pose.mouthVisible in 0f..1f)
            assertTrue(pose.eyeStyle in -1.1f..1.1f)
        }
        // Closer when sad / thinking; farther when excited / searching.
        assertTrue(
            AssistantMood.Sad.toFusionEyePose().eyeGap <
                AssistantMood.Idle.toFusionEyePose().eyeGap,
        )
        assertTrue(
            AssistantMood.Thinking.toFusionEyePose().eyeGap <
                AssistantMood.Listening.toFusionEyePose().eyeGap,
        )
        assertTrue(
            AssistantMood.Excited.toFusionEyePose().eyeGap >
                AssistantMood.Idle.toFusionEyePose().eyeGap,
        )
        assertTrue(
            AssistantMood.Searching.toFusionEyePose().eyeGap >
                AssistantMood.Reading.toFusionEyePose().eyeGap,
        )
        // Soft squint happy vs big excited rings — stay in glow-ring style.
        assertTrue(
            AssistantMood.Happy.toFusionEyePose().eyeHeight <
                AssistantMood.Excited.toFusionEyePose().eyeHeight,
        )
        assertTrue(AssistantMood.Happy.toFusionEyePose().eyeStyle in -0.2f..0.3f)
        assertTrue(
            AssistantMood.Drowsy.toFusionEyePose().eyeOpen <
                AssistantMood.Idle.toFusionEyePose().eyeOpen,
        )
        assertTrue(
            AssistantMood.Tired.toFusionEyePose().eyeHeight <
                AssistantMood.Listening.toFusionEyePose().eyeHeight,
        )
    }

    @Test
    fun fusionMouthOnlyOnExpressiveMoods() {
        val withMouth = setOf(
            AssistantMood.Happy,
            AssistantMood.Sad,
            AssistantMood.Speaking,
            AssistantMood.Excited,
        )
        AssistantMood.entries.forEach { mood ->
            val visible = mood.toFusionEyePose().mouthVisible
            if (mood in withMouth) {
                assertTrue("$mood should show mouth", visible > 0.2f)
            } else {
                assertTrue("$mood should hide mouth", visible <= 0.2f)
            }
        }
        assertTrue(
            AssistantMood.Happy.toFusionEyePose().mouthCurve >
                AssistantMood.Sad.toFusionEyePose().mouthCurve,
        )
    }

    @Test
    fun immersiveEyePoseConveysEmotionShapes() {
        AssistantMood.entries.forEach { mood ->
            val pose = mood.toImmersiveEyePose()
            assertTrue(pose.eyeOpen in 0.2f..1.5f)
            assertTrue(pose.eyeWidth in 0.8f..1.5f)
            assertTrue(pose.eyeHeight in 0.3f..1.4f)
            assertTrue(pose.faceGlow in 0f..1.2f)
            assertTrue(pose.mouthVisible in 0f..1f)
            assertTrue(pose.blinkSpeed in 0.2f..1.6f)
        }
        // Same capsule eyes — emotions morph mouth / openness
        assertTrue(
            AssistantMood.Happy.toImmersiveEyePose().mouthCurve >
                AssistantMood.Sad.toImmersiveEyePose().mouthCurve,
        )
        assertTrue(
            AssistantMood.Excited.toImmersiveEyePose().eyeOpen >
                AssistantMood.Drowsy.toImmersiveEyePose().eyeOpen,
        )
        assertTrue(
            AssistantMood.Listening.toImmersiveEyePose().eyeOpen >
                AssistantMood.Tired.toImmersiveEyePose().eyeOpen,
        )
        assertTrue(AssistantMood.Speaking.toImmersiveEyePose().mouthVisible > 0.5f)
        assertTrue(
            AssistantMood.Bored.toImmersiveEyePose().eyeOpen <
                AssistantMood.Idle.toImmersiveEyePose().eyeOpen,
        )
    }

    @Test
    fun droidFacePoseMapsMoodToGlyph() {
        AssistantMood.entries.forEach { mood ->
            val pose = mood.toDroidFacePose()
            assertTrue(pose.glyph == mood.toDroidFaceGlyph())
        }
        assertTrue(AssistantMood.Happy.toDroidFaceGlyph() == DroidFaceGlyph.SquintSmile)
        assertTrue(AssistantMood.Sad.toDroidFaceGlyph() == DroidFaceGlyph.Sad)
        assertTrue(AssistantMood.Excited.toDroidFaceGlyph() == DroidFaceGlyph.StarEyes)
        assertTrue(AssistantMood.Drowsy.toDroidFaceGlyph() == DroidFaceGlyph.Sleeping)
        assertTrue(AssistantMood.Searching.toDroidFaceGlyph() == DroidFaceGlyph.Search)
        assertEquals(36, DroidFaceGlyph.entries.size)
    }

    @Test
    fun expressiveShellMapsMoodsToThreeFaceLikeShapes() {
        assertEquals(ExpressiveShellKind.Arch, AssistantMood.Idle.toShellKind())
        assertEquals(ExpressiveShellKind.Arch, AssistantMood.Bored.toShellKind())
        assertEquals(ExpressiveShellKind.Arch, AssistantMood.Drowsy.toShellKind())
        assertEquals(ExpressiveShellKind.Arch, AssistantMood.Tired.toShellKind())
        assertEquals(ExpressiveShellKind.Arch, AssistantMood.Sad.toShellKind())

        assertEquals(ExpressiveShellKind.SemiCircle, AssistantMood.Listening.toShellKind())
        assertEquals(ExpressiveShellKind.SemiCircle, AssistantMood.Thinking.toShellKind())
        assertEquals(ExpressiveShellKind.SemiCircle, AssistantMood.Reading.toShellKind())
        assertEquals(ExpressiveShellKind.SemiCircle, AssistantMood.Searching.toShellKind())

        assertEquals(ExpressiveShellKind.Oval, AssistantMood.Speaking.toShellKind())
        assertEquals(ExpressiveShellKind.Oval, AssistantMood.Happy.toShellKind())
        assertEquals(ExpressiveShellKind.Oval, AssistantMood.Excited.toShellKind())

        AssistantMood.entries.forEach { mood ->
            assertTrue(mood.toShellKind() in ExpressiveShellKind.entries.toSet())
        }
        assertTrue(ExpressiveShellKind.Gem in ExpressiveShellKind.entries)
    }

    @Test
    @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
    fun gemShellUsesMaterialGemShape() {
        assertEquals(
            androidx.compose.material3.MaterialShapes.Gem,
            ExpressiveShellKind.Gem.toRoundedPolygon(),
        )
    }

    @Test
    fun wavePoseSharesSameStyleWithStateEnergy() {
        AssistantMood.entries.forEach { mood ->
            val wave = mood.toWavePose()
            assertTrue(wave.amplitude in 0f..1.2f)
            assertTrue(wave.speed in 0f..1.5f)
            assertTrue(wave.thickness in 0f..1.5f)
            assertTrue(wave.bloom in 0f..1.2f)
        }
        assertTrue(AssistantMood.Speaking.toWavePose().amplitude > AssistantMood.Idle.toWavePose().amplitude)
        assertTrue(AssistantMood.Listening.toWavePose().amplitude > AssistantMood.Sad.toWavePose().amplitude)
        assertTrue(AssistantMood.Speaking.toWavePose().speed > AssistantMood.Thinking.toWavePose().speed)
    }

    @Test
    fun presencePoseMorphsEnergyByMood() {
        AssistantMood.entries.forEach { mood ->
            val pose = mood.toPresencePose()
            assertTrue(pose.energy in 0f..1.2f)
            assertTrue(pose.spread in 0f..1.2f)
            assertTrue(pose.sparkle in 0f..1.2f)
        }
        assertTrue(AssistantMood.Listening.toPresencePose().energy > AssistantMood.Idle.toPresencePose().energy)
        assertTrue(AssistantMood.Searching.toPresencePose().sparkle > AssistantMood.Sad.toPresencePose().sparkle)
    }

    @Test
    fun dialogueScriptCoversAllMoods() {
        val moods = DemoDialogueScript.map { it.mood }.toSet()
        assertTrue(moods.containsAll(AssistantMood.entries.toSet()))
        assertTrue(DemoDialogueScript.any { it.speaker == DialogueSpeaker.User })
        assertTrue(DemoDialogueScript.any { it.speaker == DialogueSpeaker.Assistant })
        assertTrue(
            DemoDialogueScript.any {
                it.speaker == DialogueSpeaker.User && it.text.isNotBlank()
            },
        )
    }

    @Test
    fun immersiveScriptShowsOneLinePhasesAndEmotions() {
        val moods = ImmersiveDialogueScript.map { it.mood }.toSet()
        assertTrue(
            moods.containsAll(
                listOf(
                    AssistantMood.Listening,
                    AssistantMood.Thinking,
                    AssistantMood.Reading,
                    AssistantMood.Searching,
                    AssistantMood.Speaking,
                    AssistantMood.Happy,
                    AssistantMood.Sad,
                    AssistantMood.Excited,
                    AssistantMood.Bored,
                    AssistantMood.Drowsy,
                    AssistantMood.Tired,
                ),
            ),
        )
        assertTrue(ImmersiveDialogueScript.all { it.text.isNotBlank() })
        assertTrue(ImmersiveDialogueScript.any { it.speaker == DialogueSpeaker.User })
        assertTrue(ImmersiveDialogueScript.any { it.speaker == DialogueSpeaker.Assistant })
    }

    @Test
    fun microStatusUsesGlanceableVerbs() {
        assertEquals("Thinking…", AssistantMood.Thinking.microStatus())
        assertEquals("Reading…", AssistantMood.Reading.microStatus())
        assertEquals("Listening…", AssistantMood.Listening.microStatus())
        assertEquals("Taking it easy…", AssistantMood.Drowsy.microStatus())
        assertEquals("Taking it easy…", AssistantMood.Tired.microStatus())
        assertEquals(null, AssistantMood.Speaking.microStatus())
        assertEquals(null, AssistantMood.Happy.microStatus())
    }
}
