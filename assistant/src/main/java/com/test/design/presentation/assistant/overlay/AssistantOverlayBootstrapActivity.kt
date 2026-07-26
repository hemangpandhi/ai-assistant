package com.test.design.presentation.assistant.overlay

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * Thin launcher that starts [AssistantOverlayService].
 * Overlay permission is granted at install time — no in-app prompt.
 *
 * ADB:
 * ```
 * adb shell am start -n com.test.design/.presentation.assistant.overlay.AssistantOverlayBootstrapActivity
 * adb shell am startservice -n com.test.design/.presentation.assistant.overlay.AssistantOverlayService
 * ```
 */
class AssistantOverlayBootstrapActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        enableEdgeToEdge()
        AssistantOverlayService.start(this)
        finish()
    }
}
