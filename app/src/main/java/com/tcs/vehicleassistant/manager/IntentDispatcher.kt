package com.tcs.vehicleassistant.manager

import android.content.Context
import android.content.Intent
import android.util.Log

class IntentDispatcher(private val context: Context) {
    private val TAG = "IntentDispatcher"
    
    private val pendingIntentsToLaunch = java.util.Collections.synchronizedList(mutableListOf<Intent>())

    fun queueIntent(intent: Intent) {
        pendingIntentsToLaunch.add(intent)
    }

    /**
     * Called when the TTS utterance finishes and it's safe to take the screen.
     */
    fun dispatchPendingIntents(onLaunch: (Intent) -> Unit) {
        synchronized(pendingIntentsToLaunch) {
            for (intent in pendingIntentsToLaunch) {
                onLaunch(intent)
            }
            pendingIntentsToLaunch.clear()
        }
    }

    fun clear() {
        pendingIntentsToLaunch.clear()
    }
}
