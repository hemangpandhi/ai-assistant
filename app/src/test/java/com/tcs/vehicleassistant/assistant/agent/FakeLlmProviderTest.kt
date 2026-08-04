package com.tcs.vehicleassistant.assistant.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FakeLlmProviderTest {

    @Test
    fun `fake provider streams reply without LiteRT`() = runBlocking {
        val fake = FakeLlmProvider(reply = "Cabin is warming up.")
        val ctx = RuntimeEnvironment.getApplication()
        fake.initialize(ctx)
        val tokens = StringBuilder()
        var done = ""
        fake.generateStream(
            context = ctx,
            prompt = "prompt",
            userQuery = "warmer",
            onToken = { tokens.append(it) },
            onDone = { text, _ -> done = text },
            onError = { throw it },
        )
        assertEquals("Cabin is warming up.", done)
        assertEquals(done, tokens.toString())
        assertEquals(1, fake.generateCalls)
        assertTrue(fake.isReady())
    }

    @Test
    fun `fake provider surfaces not-ready errors`() = runBlocking {
        val fake = FakeLlmProvider()
        fake.ready = false
        val ctx = RuntimeEnvironment.getApplication()
        var err: Exception? = null
        fake.generateStream(
            context = ctx,
            prompt = "p",
            userQuery = "q",
            onToken = {},
            onDone = { _, _ -> },
            onError = { err = it },
        )
        assertTrue(err!!.message!!.contains("not ready"))
    }
}
