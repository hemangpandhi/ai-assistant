package com.tcs.vehicleassistant

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tcs.vehicleassistant.core.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the foreground-service contract of [WakeWordService].
 *
 * `onRequestPermissionsResult` reaches this service through `startForegroundService` with a restart
 * action. That branch used to skip `startForeground` entirely, so the platform killed the app with
 * `ForegroundServiceDidNotStartInTimeException` on the first-run permission grant — the path every
 * fresh install takes. Every branch that keeps the service alive must post the notification.
 *
 * The listen branch is deliberately not exercised: it loads the 200 MB Vosk model from assets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeWordServiceLifecycleTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private fun grantMicrophone() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun startWith(action: String?): Pair<WakeWordService, Int> {
        val controller = Robolectric.buildService(
            WakeWordService::class.java,
            Intent(application, WakeWordService::class.java).apply { action?.let { setAction(it) } }
        ).create()
        val result = controller.get().onStartCommand(controller.getIntent(), 0, 1)
        return controller.get() to result
    }

    @Test
    fun `pause posts the foreground notification so a startForegroundService caller is satisfied`() {
        grantMicrophone()
        val (service, result) = startWith(AssistantConfig.WakeWordAction.PAUSE)

        assertNotNull(
            "pause must promote to the foreground; it keeps the service alive",
            shadowOf(service).lastForegroundNotification
        )
        assertEquals(Service.START_STICKY, result)
        assertFalse("pause must not stop the service", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `restart posts the foreground notification`() {
        grantMicrophone()
        val (service, result) = startWith(AssistantConfig.WakeWordAction.RESTART)

        assertNotNull(
            "restart is delivered via startForegroundService and must post the notification",
            shadowOf(service).lastForegroundNotification
        )
        assertEquals(Service.START_STICKY, result)
    }

    @Test
    fun `stop tears the service down without claiming the foreground`() {
        grantMicrophone()
        val (service, result) = startWith(AssistantConfig.WakeWordAction.STOP)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    @Test
    fun `a missing microphone permission stops the service instead of promoting it`() {
        // A microphone foreground service cannot start without RECORD_AUDIO on API 34, so
        // promoting here would throw rather than merely fail to record.
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val (service, result) = startWith(AssistantConfig.WakeWordAction.PAUSE)

        assertNull(
            "must not attempt a microphone foreground service without the permission",
            shadowOf(service).lastForegroundNotification
        )
        assertTrue("must stop itself so the pending start obligation is cleared", shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    @Test
    fun `the notification names the configured wake word`() {
        application.getSharedPreferences(AssistantConfig.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(AssistantConfig.Prefs.WAKE_WORD, "hey copilot")
            .commit()
        grantMicrophone()

        val (service, _) = startWith(AssistantConfig.WakeWordAction.PAUSE)
        val text = shadowOf(service).lastForegroundNotification!!.extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

        assertTrue("notification said '$text'", text?.contains("hey copilot") == true)
    }
}
