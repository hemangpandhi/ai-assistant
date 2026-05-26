package com.example.gemininano

import android.util.Log
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HVACTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(LocalLLMActivity::class.java)

    @Test
    fun testHVACIncrease() {
        var startTempText = ""
        activityRule.scenario.onActivity { activity ->
            val dashTemp = activity.findViewById<TextView>(R.id.dashTemp)
            startTempText = dashTemp.text.toString()
            Log.d("TEST_HVAC", "INITIAL TEMP: $startTempText")
        }
        
        Thread.sleep(3000)
        
        // Click Load Model
        Log.d("TEST_HVAC", "Clicking Load Model...")
        onView(withId(R.id.btnLoadModel)).perform(click())
        
        // Wait for model to load
        Thread.sleep(20000)
        
        // Type "I'm freezing"
        Log.d("TEST_HVAC", "Typing prompt...")
        onView(withId(R.id.inputText)).perform(typeText("I'm freezing"), closeSoftKeyboard())
        
        // Click generate
        Log.d("TEST_HVAC", "Clicking Generate...")
        onView(withId(R.id.generateButton)).perform(click())
        
        // Wait for generation
        Thread.sleep(25000)
        
        var endTempText = ""
        activityRule.scenario.onActivity { activity ->
            val dashTemp = activity.findViewById<TextView>(R.id.dashTemp)
            endTempText = dashTemp.text.toString()
            Log.d("TEST_HVAC", "FINAL TEMP: $endTempText")
        }
    }
}
