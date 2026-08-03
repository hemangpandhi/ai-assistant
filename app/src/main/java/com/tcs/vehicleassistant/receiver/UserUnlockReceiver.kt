package com.tcs.vehicleassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.tcs.vehicleassistant.service.VehicleAgentService

class UserUnlockReceiver : BroadcastReceiver() {
    companion object {
        private var lastGreetTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        if (now - lastGreetTime < 5000) return

        if (intent.action == "com.tcs.vehicleassistant.FACE_UNLOCKED" || 
            intent.action == "com.android.car.biometrics.ACTION_FACE_MATCHED" ||
            intent.action == Intent.ACTION_USER_PRESENT) {
            
            lastGreetTime = now
            var userName = intent.getStringExtra("USER_NAME")
            if (userName.isNullOrEmpty()) {
                userName = try {
                    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
                    userManager.userName ?: "User"
                } catch (e: Exception) {
                    "User"
                }
            }

            val serviceIntent = Intent(context, VehicleAgentService::class.java).apply {
                action = "com.tcs.vehicleassistant.ACTION_GREET_USER"
                putExtra("USER_NAME", userName)
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
