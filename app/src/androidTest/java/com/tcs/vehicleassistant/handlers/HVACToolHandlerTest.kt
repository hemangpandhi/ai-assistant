package com.tcs.vehicleassistant.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HVACToolHandlerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testIncreaseTemperature() = runBlocking {
        val handler = HVACToolHandler("increaseTemperature")
        val result = handler.execute(context, "increaseTemperature(2)", "2", null)
        
        assertNotNull(result)
        assertNotNull(result.message)
    }

    @Test
    fun testDecreaseTemperature() = runBlocking {
        val handler = HVACToolHandler("decreaseTemperature")
        val result = handler.execute(context, "decreaseTemperature(2)", "2", null)
        
        assertNotNull(result)
        assertNotNull(result.message)
    }

    @Test
    fun testSetSeatHeater() = runBlocking {
        val handler = HVACToolHandler("setSeatHeater")
        val result = handler.execute(context, "setSeatHeater(driver, 2)", "driver, 2", null)
        
        assertNotNull(result)
        assertNotNull(result.message)
    }

    @Test
    fun testTurnOnDefroster() = runBlocking {
        val handler = HVACToolHandler("turnOnDefroster")
        val result = handler.execute(context, "turnOnDefroster()", "", null)
        
        assertNotNull(result)
        assertNotNull(result.message)
    }
}
