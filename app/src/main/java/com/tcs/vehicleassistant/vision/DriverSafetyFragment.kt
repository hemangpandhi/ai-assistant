package com.tcs.vehicleassistant.vision

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tcs.vehicleassistant.R

class DriverSafetyFragment : Fragment() {
    private lateinit var tvIdentity: TextView
    private lateinit var tvSimScore: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_driver_safety, container, false)
        tvIdentity = view.findViewById(R.id.tvIdentity)
        tvSimScore = view.findViewById(R.id.tvSimScore)
        return view
    }

    fun updateStats(similarity: Float) {
        tvSimScore.text = "Similarity: " + String.format("%.2f", similarity)
        if (similarity > 0.6f) {
            tvIdentity.text = "Identity: Verified Driver"
            tvIdentity.setTextColor(android.graphics.Color.GREEN)
        } else if (similarity > 0.0f) {
            tvIdentity.text = "Identity: Unknown"
            tvIdentity.setTextColor(android.graphics.Color.RED)
        } else {
            tvIdentity.text = "Identity: Analyzing..."
            tvIdentity.setTextColor(android.graphics.Color.WHITE)
        }
    }
}