package com.tcs.vehicleassistant

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantEndToEndUITest {

    @get:Rule
    val activityRule = ActivityScenarioRule(LocalLLMActivity::class.java)

    @Test
    fun executeAllCommandsVisually() {
        val testCases = listOf(
            "I'm feeling a bit cold.",
            "Can you play some jazz music?",
            "Navigate to the nearest coffee shop.",
            "Roll down the windows a bit.",
            "Actually, open the trunk.", // Should trigger safety constraint!
            "Turn up the heat and pause the music." // Multi-intent
        )

        // Wait for model initialization
        Thread.sleep(5000)

        for (query in testCases) {
            // Type the query
            onView(withId(R.id.inputText))
                .perform(clearText(), typeText(query), closeSoftKeyboard())

            // Click generate
            onView(withId(R.id.generateButton))
                .perform(click())

            // Wait 12 seconds for the LLM to stream the response and for the user to read it
            Thread.sleep(12000)
        }
    }
}
