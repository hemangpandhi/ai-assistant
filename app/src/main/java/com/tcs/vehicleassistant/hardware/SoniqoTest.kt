package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

object SoniqoTest {
    fun test(context: Context) {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val resolveInfos: List<ResolveInfo> = context.packageManager.queryIntentServices(intent, 0)
        for (info in resolveInfos) {
            Log.i("SoniqoTest", "Found STT Service: ${info.serviceInfo.packageName} / ${info.serviceInfo.name}")
        }
    }
}
