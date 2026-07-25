package com.tcs.vehicleassistant.vision

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.tcs.vehicleassistant.R

class DriverSafetyFragment : Fragment() {
    private var tvIdentity: TextView? = null
    private var tvSimScore: TextView? = null
    private var btnRegisterFace: Button? = null

    var visionService: CockpitVisionService? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_driver_safety, container, false)
        tvIdentity = view.findViewById(R.id.tvIdentity)
        tvSimScore = view.findViewById(R.id.tvSimScore)
        btnRegisterFace = view.findViewById(R.id.btnRegisterFace)

        btnRegisterFace?.setOnClickListener {
            showSaveFaceDialog()
        }

        return view
    }

    private fun showSaveFaceDialog() {
        val ctx = context ?: return
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etName = EditText(ctx).apply {
            hint = "Driver Name (e.g. Driver 1)"
            setText("Driver 1")
        }
        val etUserId = EditText(ctx).apply {
            hint = "AAOS User ID (e.g. 10)"
            setText("10")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etTemp = EditText(ctx).apply {
            hint = "Preferred Temp °C (e.g. 19.0)"
            setText("19.0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        layout.addView(etName)
        layout.addView(etUserId)
        layout.addView(etTemp)

        AlertDialog.Builder(ctx)
            .setTitle("Save Driver Face & AAOS Mapping")
            .setMessage("Configure AAOS User ID and cabin preference:")
            .setView(layout)
            .setPositiveButton("Save Profile") { _, _ ->
                val name = etName.text.toString().trim()
                val userId = etUserId.text.toString().toIntOrNull() ?: 10
                val temp = etTemp.text.toString().toFloatOrNull() ?: 20.0f

                if (name.isNotEmpty()) {
                    visionService?.saveCurrentFace(name, userId, temp)
                    Toast.makeText(ctx, "Face saved for $name (AAOS User $userId, Temp $temp°C)!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun updateStats(similarity: Float, userName: String = "Guest") {
        tvSimScore?.text = "Similarity: " + String.format("%.2f", similarity)
        if (similarity > 0.6f && userName != "Guest") {
            tvIdentity?.text = "Identity: Verified ($userName)"
            tvIdentity?.setTextColor(android.graphics.Color.GREEN)
        } else if (similarity > 0.0f) {
            tvIdentity?.text = "Identity: Unknown"
            tvIdentity?.setTextColor(android.graphics.Color.RED)
        } else {
            tvIdentity?.text = "Identity: Analyzing..."
            tvIdentity?.setTextColor(android.graphics.Color.WHITE)
        }
    }
}