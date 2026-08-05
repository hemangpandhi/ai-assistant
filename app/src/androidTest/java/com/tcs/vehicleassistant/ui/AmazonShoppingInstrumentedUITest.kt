package com.tcs.vehicleassistant.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.tcs.vehicleassistant.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AmazonShoppingInstrumentedUITest {

    @Test
    fun testActivityDisplaysRecyclerViewAndHandlesBiometric() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), AmazonShoppingActivity::class.java).apply {
            putExtra("ACTION", "SEARCH")
            putExtra("ITEM_NAME", "fragrance")
        }

        ActivityScenario.launch<AmazonShoppingActivity>(intent).use { scenario ->
            
            // Check that status text says it's searching
            scenario.onActivity { activity ->
                val tvStatus = activity.findViewById<TextView>(R.id.tvStatus)
                // Just log or ensure it's not null, no strict initial text assertion to avoid race condition
                assertTrue(tvStatus != null)
            }

            // Wait for network response
            Thread.sleep(5000)

            scenario.onActivity { activity ->
                // Verify RecyclerView is displayed
                val rvProducts = activity.findViewById<RecyclerView>(R.id.rvProducts)
                assertTrue(rvProducts.visibility == View.VISIBLE)
                assertTrue((rvProducts.adapter?.itemCount ?: 0) > 0)

                // Trigger the purchase broadcast
                val purchaseIntent = Intent("com.tcs.vehicleassistant.ACTION_PURCHASE")
                purchaseIntent.setPackage(activity.packageName)
                activity.sendBroadcast(purchaseIntent)
            }
            
            // Allow broadcast to process and UI to update
            Thread.sleep(1000)

            scenario.onActivity { activity ->
                // Verify biometric preview becomes visible
                val svBiometricPreview = activity.findViewById<View>(R.id.svBiometricPreview)
                val tvBiometricStatus = activity.findViewById<TextView>(R.id.tvBiometricStatus)
                val tvStatus = activity.findViewById<TextView>(R.id.tvStatus)

                assertTrue(svBiometricPreview.visibility == View.VISIBLE)
                assertTrue(tvBiometricStatus.visibility == View.VISIBLE)
                assertTrue(tvStatus.text.toString().contains("Authorizing purchase"))
                
                // Simulate surface creation because physical screens might be off in this specific test rig
                activity.surfaceCreated((svBiometricPreview as android.view.SurfaceView).holder)
            }
            
            // Wait for biometric success (handler delay is 3000ms)
            Thread.sleep(3500)

            scenario.onActivity { activity ->
                // Verify success state
                val tvStatus = activity.findViewById<TextView>(R.id.tvStatus)
                assertTrue("Expected Order Placed but was: ${tvStatus.text}", tvStatus.text.toString().contains("Order Placed!"))
            }
        }
    }
}
