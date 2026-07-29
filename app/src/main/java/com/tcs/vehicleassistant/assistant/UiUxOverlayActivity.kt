package com.tcs.vehicleassistant.assistant

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.assistant.ui.assistant.entry.VirtualAssistantOverlay
import com.assistant.ui.assistant.ui.theme.AssistantTheme

/**
 * App-owned Compose overlay entry point for VIS and explicit intent launches.
 */
class UiUxOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            AssistantTheme(darkTheme = true) {
                VirtualAssistantOverlay(
                    onDismiss = ::finish,
                    modifier = Modifier.fillMaxSize(),
                    awaitHotword = false,
                )
            }
        }
    }

    companion object {
        const val ACTION_OPEN_UIUX_OVERLAY =
            "com.tcs.vehicleassistant.action.OPEN_UIUX_OVERLAY"
    }
}
