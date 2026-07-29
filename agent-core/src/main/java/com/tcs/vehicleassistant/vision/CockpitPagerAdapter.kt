package com.tcs.vehicleassistant.vision

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class CockpitPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    val cabinSenseFragment = CabinSenseFragment()
    val healthMonitorFragment = HealthMonitorFragment()
    val driverSafetyFragment = DriverSafetyFragment()

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> cabinSenseFragment
            1 -> healthMonitorFragment
            2 -> driverSafetyFragment
            else -> cabinSenseFragment
        }
    }

    fun setVisionService(service: CockpitVisionService?) {
        driverSafetyFragment.visionService = service
        service?.onStatsUpdateCallback = { healthState, gestureFeedback, similarity, userName ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                cabinSenseFragment.updateStats(
                    mood = gestureFeedback?.mood ?: "Neutral",
                    gesture = gestureFeedback?.gestureName ?: "NONE"
                )
                driverSafetyFragment.updateStats(similarity, userName)
                healthMonitorFragment.updateStats(healthState.heartRate, healthState.stressLevel)
            }
        }
    }
}
