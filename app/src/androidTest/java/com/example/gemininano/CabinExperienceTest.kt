package com.example.gemininano

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CabinExperienceTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO,
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CONTROL_CAR_SEATS",
        "android.car.permission.CONTROL_CAR_CLIMATE",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_POWERTRAIN"
    )

    @get:Rule
    val activityRule = ActivityScenarioRule(LocalLLMActivity::class.java)

    @Test
    fun testTabNavigation() {
        // Initially Inference tab is visible
        onView(withId(R.id.tabInference)).check(matches(isDisplayed()))
        
        // Click on the second tab "Automotive Experiences" (using text since it's a TabItem)
        onView(withText("Automotive Experiences")).perform(click())
        
        // Verify Use Cases tab is now displayed
        onView(withId(R.id.tabUseCases)).check(matches(isDisplayed()))
    }

    @Test
    fun testAllEightCabinExperiencesExist() {
        // Go to Use Cases tab
        onView(withText("Automotive Experiences")).perform(click())

        // Use scroll to verify elements exist
        onView(withId(R.id.btnPremiumUseCase1)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnPremiumUseCase2)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnPremiumUseCase3)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnPremiumUseCase4)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnPremiumUseCase5)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnPremiumUseCase6)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnPremiumUseCase7)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnPremiumUseCase8)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun testUseCaseExecutionSwitchesTab() {
        // Go to Use Cases tab
        onView(withText("Automotive Experiences")).perform(click())
        
        // Click first use case
        onView(withId(R.id.btnPremiumUseCase1)).perform(scrollTo(), click())
        
        // It should switch back to the Inference tab
        onView(withId(R.id.tabInference)).check(matches(isDisplayed()))
        
        // The input box should have text
        onView(withId(R.id.inputText)).check(matches(withText("I'm running low on gas and my kids are hungry. Find the nearest rest stop or family restaurant along my current route on I-5.")))
    }
}
