package com.tcs.vehicleassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSettingsPresetsTest {

    @Test
    fun tokyoPreset_hasExpectedCoordinates() {
        val preset = DemoSettingsPresets.TOKYO_OEM
        assertEquals("tokyo_oem", preset.id)
        assertTrue(preset.coordinatesString().contains("139.6917"))
        assertTrue(preset.coordinatesString().contains("35.6895"))
        assertEquals("Tokyo", preset.cityName)
    }

    @Test
    fun sagamiharaPreset_hasExpectedCoordinates() {
        val preset = DemoSettingsPresets.SAGAMIHARA
        assertEquals("sagamihara", preset.id)
        assertTrue(preset.coordinatesString().contains("139.37"))
        assertTrue(preset.coordinatesString().contains("35.57"))
    }
}
