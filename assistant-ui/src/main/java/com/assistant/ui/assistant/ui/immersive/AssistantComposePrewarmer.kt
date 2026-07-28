package com.assistant.ui.assistant.ui.immersive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.assistant.ui.assistant.face.AssistantMood
import com.assistant.ui.assistant.face.ImmersiveEyesFace
import com.assistant.ui.assistant.ui.theme.AssistantTheme
import com.assistant.ui.assistant.ui.theme.LocalAssistantIdleMotion
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Class-loads Compose runtime, theme, and the default face off the wake-word
 * critical path so the first real session inflate is warm.
 *
 * Safe to call multiple times; only the first attempt runs.
 */
object AssistantComposePrewarmer {
    private const val TAG = "AssistantUiLatency"
    @Volatile private var started = false

    fun warm(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            val t0 = SystemClockMs()
            runCatching {
                val view = ComposeView(app)
                view.setContent {
                    AssistantTheme(darkTheme = true) {
                        CompositionLocalProvider(LocalAssistantIdleMotion provides false) {
                            Box(Modifier.size(1.dp)) {
                                ImmersiveEyesFace(
                                    mood = AssistantMood.Listening,
                                    modifier = Modifier.size(1.dp),
                                )
                            }
                        }
                    }
                }
                val spec = View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY)
                view.measure(spec, spec)
                view.layout(0, 0, 1, 1)
                // Keep composition briefly so class init settles, then release.
                view.postDelayed({
                    runCatching { view.disposeComposition() }
                }, 400)
                Log.i(TAG, "compose prewarm done in ${SystemClockMs() - t0}ms")
            }.onFailure {
                Log.w(TAG, "compose prewarm failed: ${it.message}")
            }
        }
    }

    private fun SystemClockMs(): Long = android.os.SystemClock.elapsedRealtime()
}
