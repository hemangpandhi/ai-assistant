package com.tcs.vehicleassistant

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantEndToEndUITest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        
        // Start from the home screen
        device.pressHome()
        
        // Launch the app
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        
        // Wait for the app to appear
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 5000)
    }

    @Test
    fun executeAllCommandsVisually() {
        val testCases = listOf(
            "Make it cooler in here.",
            "I can't see through the windshield.",
            "My feet are freezing."
        )

        // Wait for model initialization
        Thread.sleep(5000)

        for (query in testCases) {
            val inputField = device.wait(Until.findObject(By.res("com.tcs.vehicleassistant", "inputText")), 2000)
            inputField?.clear()
            inputField?.text = query
            
            // Close keyboard just in case
            device.pressBack()

            val generateButton = device.findObject(By.res("com.tcs.vehicleassistant", "generateButton"))
            generateButton?.click()

            // Wait 12 seconds for the LLM to stream the response and for the user to read it
            Thread.sleep(12000)
        }
    }
}
