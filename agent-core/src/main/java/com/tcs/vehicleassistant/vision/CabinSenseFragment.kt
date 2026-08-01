package com.tcs.vehicleassistant.vision

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tcs.vehicleassistant.R

class CabinSenseFragment : Fragment() {
    private var tvMood: TextView? = null
    private var tvGesture: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_cabin_sense, container, false)
        tvMood = view.findViewById(R.id.tvMood)
        tvGesture = view.findViewById(R.id.tvGesture)
        return view
    }

    fun updateStats(mood: String, gesture: String) {
        tvMood?.text = "Mood: $mood"
        tvGesture?.text = "Gesture: $gesture"
    }
}