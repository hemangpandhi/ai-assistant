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
        service?.onStatsUpdateCallback = { healthState, gestureFeedback, similarity, activeFaces ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                cabinSenseFragment.updateStats(
                    mood = gestureFeedback?.mood ?: "Neutral",
                    gesture = gestureFeedback?.gestureName ?: "NONE"
                )
                
                val primaryFace = activeFaces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val userName = primaryFace?.name ?: "Guest"
                driverSafetyFragment.updateStats(similarity, userName)
                healthMonitorFragment.updateStats(healthState.heartRate, healthState.stressLevel)

                // Update the face overlay view (if activity is CockpitAwarenessActivity)
                val context = driverSafetyFragment.context ?: return@post
                if (context is com.tcs.vehicleassistant.CockpitAwarenessActivity) {
                    val faceOverlayView = context.findViewById<com.tcs.vehicleassistant.vision.FaceOverlayView>(com.tcs.vehicleassistant.R.id.faceOverlayView)
                    val ivCameraFeed = context.findViewById<android.widget.ImageView>(com.tcs.vehicleassistant.R.id.ivCameraFeed)
                    if (faceOverlayView != null && ivCameraFeed != null) {
                        val drawable = ivCameraFeed.drawable
                        if (drawable != null) {
                            faceOverlayView.setFaces(activeFaces, drawable.intrinsicWidth, drawable.intrinsicHeight)
                        }
                    }
                }
            }
        }
    }
}
