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
class NavigationToolHandlerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testStartNavigationToCoordinates() = runBlocking {
        val handler = NavigationToolHandler("startNavigationTo")
        var interceptedIntent: Intent? = null
        
        val result = handler.execute(
            context, 
            "startNavigationTo(37.7749,-122.4194)", 
            "37.7749,-122.4194",
            intentHandler = { intent -> interceptedIntent = intent }
        )

        assertNotNull(result.message)
        assertNotNull(interceptedIntent)
        assertEquals("google.navigation:q=37.7749%2C-122.4194", interceptedIntent?.data?.toString())
        assertEquals("com.google.android.apps.maps", interceptedIntent?.getPackage())
    }

    @Test
    fun testSearchNearby() = runBlocking {
        val handler = NavigationToolHandler("searchNearby")
        var interceptedIntent: Intent? = null
        
        val result = handler.execute(
            context, 
            "searchNearby(gas station)", 
            "gas station",
            intentHandler = { intent -> interceptedIntent = intent }
        )

        assertNotNull(result.message)
        assertNotNull(result.message)
        
        // If it starts an intent for searching
    }
}
