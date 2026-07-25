package com.tcs.vehicleassistant.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowToolHandlerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testSetAllWindowsPosition() = runBlocking {
        val handler = WindowToolHandler("setAllWindowsPosition")
        val result = handler.execute(context, "setAllWindowsPosition(50)", "50", null)
        
        assertNotNull(result)
        assertNotNull(result.message)
    }

    @Test
    fun testOpenWindowsSlightly() = runBlocking {
        val handler = WindowToolHandler("openWindowsSlightly")
        val result = handler.execute(context, "openWindowsSlightly()", "", null)
        
        assertNotNull(result)
        assertNotNull(result.message)
    }

    @Test
    fun testCheckAllWindowsClosed() = runBlocking {
        val handler = WindowToolHandler("checkAllWindowsClosed")
        val result = handler.execute(context, "checkAllWindowsClosed()", "", null)
        
        assertNotNull(result.message)
        assertNotNull(result.message)
    }
}
