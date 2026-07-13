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
        // Implementation can be added if fragments need direct access
    }
}
