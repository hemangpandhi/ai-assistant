package com.tcs.vehicleassistant.vision

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tcs.vehicleassistant.R

class HealthMonitorFragment : Fragment() {
    private lateinit var tvBpm: TextView
    private lateinit var tvStress: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_health_monitor, container, false)
        tvBpm = view.findViewById(R.id.tvBpm)
        tvStress = view.findViewById(R.id.tvStress)
        return view
    }

    fun updateStats(bpm: Int, stress: String) {
        tvBpm.text = "BPM: $bpm"
        tvStress.text = "Stress: $stress"
    }
}