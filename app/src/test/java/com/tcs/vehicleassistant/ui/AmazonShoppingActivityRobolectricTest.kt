package com.tcs.vehicleassistant.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tcs.vehicleassistant.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AmazonShoppingActivityRobolectricTest {

    @Test
    fun testActivityLaunchAndSearchFlow() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), AmazonShoppingActivity::class.java).apply {
            putExtra("ACTION", "SEARCH")
            putExtra("ITEM_NAME", "jewelry")
        }

        ActivityScenario.launch<AmazonShoppingActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Initial status
                val tvStatus = activity.findViewById<TextView>(R.id.tvStatus)
                // Transient state might be skipped by Robolectric; we will assert final state below

                // Let the background thread execute
                Thread.sleep(2000) // Give network thread time to complete
                shadowOf(Looper.getMainLooper()).idle()

                // Check that RecyclerView has items
                val rvProducts = activity.findViewById<RecyclerView>(R.id.rvProducts)
                assertNotNull(rvProducts.adapter)
                val itemCount = rvProducts.adapter?.itemCount ?: 0
                assertTrue("RecyclerView should have items after search", itemCount > 0)

                // Trigger biometric purchase broadcast
                val purchaseIntent = Intent("com.tcs.vehicleassistant.ACTION_PURCHASE")
                activity.sendBroadcast(purchaseIntent)

                // Advance main looper to process broadcast
                shadowOf(Looper.getMainLooper()).idle()

                val tvBiometricStatus = activity.findViewById<TextView>(R.id.tvBiometricStatus)
                assertEquals(View.VISIBLE, tvBiometricStatus.visibility)
                assertEquals("Authorizing purchase...", tvStatus.text.toString())
                
                // Simulate surface creation so that triggerBiometricService() is called
                val svBiometricPreview = activity.findViewById<android.view.SurfaceView>(R.id.svBiometricPreview)
                activity.surfaceCreated(svBiometricPreview.holder)

                // Fast-forward handler delay (3000ms)
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3100))
                
                assertTrue("Status should update on success", tvStatus.text.toString().contains("Order Placed!"))
            }
        }
    }
}
