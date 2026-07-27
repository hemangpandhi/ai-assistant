package com.tcs.vehicleassistant.handlers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaToolHandlerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testPlayMusic() = runBlocking {
        val handler = MediaToolHandler("playMusic")
        var interceptedIntent: Intent? = null
        
        val result = handler.execute(context, "playMusic(Jazz)", "Jazz") { intent ->
            interceptedIntent = intent
        }
        
        assertNotNull(result.message)
        assertNotNull(result.message)
    }

    @Test
    fun testPauseMusic() = runBlocking {
        val handler = MediaToolHandler("pauseMusic")
        var interceptedIntent: Intent? = null
        
        val result = handler.execute(context, "pauseMusic()", "") { intent ->
            interceptedIntent = intent
        }
        
        assertNotNull(result.message)
        assertNotNull(result.message)
    }

    @Test
    fun testNextTrack() = runBlocking {
        val handler = MediaToolHandler("nextTrack")
        var interceptedIntent: Intent? = null
        
        val result = handler.execute(context, "nextTrack()", "") { intent ->
            interceptedIntent = intent
        }
        
        assertNotNull(result.message)
        assertNotNull(result.message)
    }
}
