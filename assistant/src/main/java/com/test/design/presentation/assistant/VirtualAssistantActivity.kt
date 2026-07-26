package com.test.design.presentation.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest

/**
 * Standalone assistant entry — separate from in-app home chrome.
 *
 * Prefers [ImmersiveAssistantOverlayService] (translucent over whatever is on screen).
 * Does **not** cold-start MainActivity: OsmDroid + overlay together spike GL memory
 * (~200MB+) and kill emulators when immersive/speaking starts. Open home first, or use
 * `.cursor/scripts/launch-assistant.sh`.
 *
 * ```
 * adb shell am start -a com.test.design.action.OPEN_ASSISTANT \
 *   -n com.test.design/.presentation.assistant.VirtualAssistantActivity
 * ```
 */
class VirtualAssistantActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)
    private var summonEpoch by mutableStateOf(0)

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        intent.getStringExtra(EXTRA_FACE)?.let { AssistantFaceConfig.setFromRaw(this, it) }

        if (Settings.canDrawOverlays(this)) {
            ImmersiveAssistantOverlayService.show(
                this,
                face = intent.getStringExtra(EXTRA_FACE),
            )
            finish()
            return
        }

        setContent {
            AssistantTheme(darkTheme = true) {
                LaunchedEffect(micGranted) {
                    if (!micGranted) return@LaunchedEffect
                    hotwordDetections(this@VirtualAssistantActivity).collectLatest {
                        notifyImmersiveAssistantHotword()
                    }
                }
                VirtualAssistantOverlay(
                    onDismiss = { finish() },
                    modifier = Modifier.fillMaxSize(),
                    awaitHotword = false,
                )
                LaunchedEffect(summonEpoch) {
                    if (summonEpoch > 0) notifyImmersiveAssistantHotword()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_FACE)?.let { AssistantFaceConfig.setFromRaw(this, it) }
        if (Settings.canDrawOverlays(this)) {
            ImmersiveAssistantOverlayService.show(
                this,
                face = intent.getStringExtra(EXTRA_FACE),
            )
            finish()
        } else {
            summonEpoch++
        }
    }

    companion object {
        const val ACTION_OPEN_ASSISTANT = "com.test.design.action.OPEN_ASSISTANT"
        const val EXTRA_FACE = AssistantFaceReceiver.EXTRA_FACE

        fun launch(context: Context, face: String? = null) {
            context.startActivity(
                Intent(context, VirtualAssistantActivity::class.java).apply {
                    action = ACTION_OPEN_ASSISTANT
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (face != null) putExtra(EXTRA_FACE, face)
                },
            )
        }
    }
}
