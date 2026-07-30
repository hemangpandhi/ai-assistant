package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tcs.vehicleassistant.DemoSettingsPresets
import com.tcs.vehicleassistant.LocationManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EdgeLocalHardeningTest {

    private fun prefs(): android.content.SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    @Test
    fun vehicleUnits_mpsToMph_matchesAospConvention() {
        // 20 m/s ≈ 44.7 mph
        assertEquals(45, VehicleUnits.mpsToMph(20f))
        assertEquals(0, VehicleUnits.mpsToMph(0f))
        assertEquals(0, VehicleUnits.mpsToMph(-1f))
        assertEquals(67, VehicleUnits.mpsToMph(30f))
    }

    @Test
    fun vehicleUnits_mphRoundTrip_forTelemetryMock() {
        val mps = VehicleUnits.mphToMps(55f)
        assertEquals(55, VehicleUnits.mpsToMph(mps))
    }

    @Test
    fun vehicleUnits_fuelAbsoluteVolumeIsUnknown() {
        assertEquals(-1, VehicleUnits.normalizeFuelLevelPct(25000f))
        assertEquals(100, VehicleUnits.normalizeFuelLevelPct(1f))
        assertEquals(0, VehicleUnits.normalizeFuelLevelPct(0f))
    }

    @Test
    fun localModelResolver_prefersSavedPathWhenReadable() {
        val tmp = File.createTempFile("oem-model", ".litertlm").apply { writeText("x") }
        val fallback = File.createTempFile("gemma-4-E2B-it", ".litertlm").apply { writeText("y") }
        try {
            val resolved = LocalModelResolver.resolve(
                savedPath = tmp.absolutePath,
                defaultPath = fallback.absolutePath,
                defaultFilename = fallback.name,
                candidates = listOf(fallback),
            )
            assertEquals(tmp.absolutePath, resolved.absolutePath)
        } finally {
            tmp.delete()
            fallback.delete()
        }
    }

    @Test
    fun localModelResolver_fallsBackToDefaultFilename() {
        val missing = File("/tmp/does-not-exist-edge-model.litertlm")
        val gemma = File.createTempFile("gemma-4-E2B-it", ".litertlm").apply {
            // rename-style: ensure filename match via sibling with exact default name
            delete()
        }
        val exact = File(gemma.parentFile, AssistantConfig.Llm.DEFAULT_MODEL_FILENAME).apply {
            writeText("model")
        }
        try {
            val resolved = LocalModelResolver.resolve(
                savedPath = missing.absolutePath,
                defaultPath = missing.absolutePath,
                candidates = listOf(exact),
            )
            assertEquals(exact.absolutePath, resolved.absolutePath)
        } finally {
            exact.delete()
        }
    }

    @Test
    fun ensureDefaults_isOneShot_andDoesNotWipeLaterPrefs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs().edit().clear().commit()

        DemoSettingsPresets.ensureDefaults(context)
        assertTrue(prefs().getBoolean(DemoSettingsPresets.PREF_INITIALIZED, false))
        assertEquals(
            AssistantConfig.Llm.DEFAULT_MODEL_PATH,
            prefs().getString(AssistantConfig.Prefs.SELECTED_MODEL, null),
        )

        // User/OEM changes after first boot
        prefs().edit()
            .putString(AssistantConfig.Prefs.SELECTED_MODEL, "/data/local/tmp/llm/custom.litertlm")
            .putString(LocationManager.PREF_LOCATION_SOURCE, "override")
            .putBoolean("companion_mode_enabled", false)
            .putString(LocationManager.PREF_DEMO_PRESET, DemoSettingsPresets.SAGAMIHARA.id)
            .commit()

        DemoSettingsPresets.ensureDefaults(context)

        assertEquals(
            "/data/local/tmp/llm/custom.litertlm",
            prefs().getString(AssistantConfig.Prefs.SELECTED_MODEL, null),
        )
        assertEquals("override", prefs().getString(LocationManager.PREF_LOCATION_SOURCE, null))
        assertEquals(false, prefs().getBoolean("companion_mode_enabled", true))
        assertEquals(
            DemoSettingsPresets.SAGAMIHARA.id,
            prefs().getString(LocationManager.PREF_DEMO_PRESET, null),
        )
    }
}
