package com.assistant.ui.assistant.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFaceKindTest {

    @Test
    fun parsesCanonicalKeys() {
        assertEquals(AssistantFaceKind.None, AssistantFaceKind.parse("none"))
        assertEquals(AssistantFaceKind.ImmersiveEyes, AssistantFaceKind.parse("eyes"))
        assertEquals(AssistantFaceKind.ImmersiveGlow, AssistantFaceKind.parse("glow"))
        assertEquals(AssistantFaceKind.ImmersiveHybrid, AssistantFaceKind.parse("hybrid"))
        assertEquals(AssistantFaceKind.Eporo, AssistantFaceKind.parse("eporo"))
        assertEquals(AssistantFaceKind.Fusion, AssistantFaceKind.parse("fusion"))
        assertEquals(AssistantFaceKind.FusionGlow, AssistantFaceKind.parse("fusionglow"))
        assertEquals(AssistantFaceKind.FusionEyes, AssistantFaceKind.parse("fusioneyes"))
        assertEquals(AssistantFaceKind.Droid, AssistantFaceKind.parse("droid"))
        assertEquals(AssistantFaceKind.Glyph, AssistantFaceKind.parse("glyph"))
        assertEquals(AssistantFaceKind.NomiSphere, AssistantFaceKind.parse("nomi"))
    }

    @Test
    fun parsesAliases() {
        assertEquals(AssistantFaceKind.None, AssistantFaceKind.parse("off"))
        assertEquals(AssistantFaceKind.None, AssistantFaceKind.parse("noface"))
        assertEquals(AssistantFaceKind.ImmersiveEyes, AssistantFaceKind.parse("immersive"))
        assertEquals(AssistantFaceKind.ImmersiveGlow, AssistantFaceKind.parse("aura"))
        assertEquals(AssistantFaceKind.ImmersiveGlow, AssistantFaceKind.parse("purple_eyes"))
        assertEquals(AssistantFaceKind.ImmersiveHybrid, AssistantFaceKind.parse("immersive_hybrid"))
        assertEquals(AssistantFaceKind.ImmersiveHybrid, AssistantFaceKind.parse("glow_hybrid"))
        assertEquals(AssistantFaceKind.Eporo, AssistantFaceKind.parse("eporp"))
        assertEquals(AssistantFaceKind.Fusion, AssistantFaceKind.parse("express"))
        assertEquals(AssistantFaceKind.FusionGlow, AssistantFaceKind.parse("fusion_glow"))
        assertEquals(AssistantFaceKind.FusionGlow, AssistantFaceKind.parse("glow_fusion"))
        assertEquals(AssistantFaceKind.FusionEyes, AssistantFaceKind.parse("fusion_eyes"))
        assertEquals(AssistantFaceKind.FusionEyes, AssistantFaceKind.parse("fusion_black"))
        assertEquals(AssistantFaceKind.Droid, AssistantFaceKind.parse("bugdroid"))
        assertEquals(AssistantFaceKind.Glyph, AssistantFaceKind.parse("classic"))
        assertEquals(AssistantFaceKind.NomiSphere, AssistantFaceKind.parse("sphere"))
        assertEquals(AssistantFaceKind.NomiSphere, AssistantFaceKind.parse("glass"))
        assertEquals(AssistantFaceKind.NomiSphere, AssistantFaceKind.parse("nomi_sphere"))
    }

    @Test
    fun ignoresCaseAndWhitespace() {
        assertEquals(AssistantFaceKind.Eporo, AssistantFaceKind.parse("  EPORO "))
        assertEquals(AssistantFaceKind.ImmersiveEyes, AssistantFaceKind.parse("Eyes"))
        assertEquals(AssistantFaceKind.ImmersiveGlow, AssistantFaceKind.parse("Glow"))
        assertEquals(AssistantFaceKind.ImmersiveHybrid, AssistantFaceKind.parse("Hybrid"))
        assertEquals(AssistantFaceKind.FusionGlow, AssistantFaceKind.parse("FusionGlow"))
        assertEquals(AssistantFaceKind.FusionEyes, AssistantFaceKind.parse("FusionEyes"))
    }

    @Test
    fun unknownReturnsNull() {
        assertNull(AssistantFaceKind.parse(null))
        assertNull(AssistantFaceKind.parse(""))
        assertNull(AssistantFaceKind.parse("banana"))
    }

    @Test
    fun adbKeysAreUnique() {
        val keys = AssistantFaceKind.entries.map { it.adbKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains("none"))
        assertTrue(keys.contains("eyes"))
        assertTrue(keys.contains("glow"))
        assertTrue(keys.contains("hybrid"))
        assertTrue(keys.contains("eporo"))
        assertTrue(keys.contains("fusion"))
        assertTrue(keys.contains("fusionglow"))
        assertTrue(keys.contains("fusioneyes"))
        assertTrue(keys.contains("nomi"))
    }

    @Test
    fun defaultIsImmersiveEyes() {
        assertEquals(AssistantFaceKind.ImmersiveEyes, AssistantFaceKind.Default)
    }
}
