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
    }

    override fun onStart() {
        super.onStart()
        Intent(this, CockpitVisionService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
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