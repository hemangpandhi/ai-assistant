package com.tcs.vehicleassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.tcs.vehicleassistant.vision.*

class CockpitAwarenessActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnClose: Button
    
    private var visionService: CockpitVisionService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as CockpitVisionService.LocalBinder
            visionService = binder.getService()
            isBound = true

            val ivCameraFeed = findViewById<android.widget.ImageView>(R.id.ivCameraFeed)
            visionService?.onFrameCallback = { bitmap ->
                runOnUiThread {
                    ivCameraFeed.setImageBitmap(bitmap)
                }
            }
            
            // Connect fragments to the service
            val adapter = viewPager.adapter as? CockpitPagerAdapter
            adapter?.setVisionService(visionService)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            visionService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cockpit_awareness)

        viewPager = findViewById(R.id.viewPager)
        btnClose = findViewById(R.id.btnClose)

        viewPager.adapter = CockpitPagerAdapter(this)

        btnClose.setOnClickListener {
            finish()
        }

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVisionService()
        }
    }

    private fun startVisionService() {
        val options = arrayOf("Native Camera", "IP Camera")
        var selectedIndex = 0

        android.app.AlertDialog.Builder(this)
            .setTitle("Select Camera Source")
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Proceed") { _, _ ->
                if (selectedIndex == 0) {
                    launchService("native")
                } else {
                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val lastUrl = prefs.getString("camera_url", "http://10.169.205.226:8080/video")
                    showIpCameraDialog(lastUrl)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
                finish()
            }
            .show()
    }

    private fun showIpCameraDialog(lastUrl: String?) {
        val input = android.widget.EditText(this)
        input.setText(lastUrl)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        android.app.AlertDialog.Builder(this)
            .setTitle("IP Camera Connection")
            .setMessage("Enter the MJPEG stream URL (e.g., from IP Webcam app):")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val url = input.text.toString()
                prefs.edit().putString("camera_url", url).apply()
                launchService(url)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
                finish()
            }
            .show()
    }

    private fun launchService(url: String) {
        // Explicit cockpit UI — enable vision for this session.
        com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags(this).proactiveVisionEnabled = true
        val intent = Intent(this, CockpitVisionService::class.java).apply {
            putExtra("CAMERA_URL", url)
        }
        
        // Start FGS and then bind to it
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!isBound) {
                startVisionService()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}