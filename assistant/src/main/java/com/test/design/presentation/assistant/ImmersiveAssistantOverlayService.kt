package com.test.design.presentation.assistant

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Translucent system overlay host for the standalone immersive assistant.
 *
 * AAOS multi-panel parks translucent *activities* under app_panel as invisible;
 * TYPE_APPLICATION_OVERLAY draws over Settings / any app with the same look.
 *
 * Opens directly in immersive fullscreen (no corner bubble).
 */
class ImmersiveAssistantOverlayService : LifecycleService(),
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = store

    private lateinit var windowManager: WindowManager
    private var overlayView: android.widget.FrameLayout? = null

    private val uiJob = SupervisorJob()
    private val uiScope = CoroutineScope(AndroidUiDispatcher.Main + uiJob)

    private var summonEpoch = 0

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        attachOverlay()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_FACE)?.let { raw ->
            AssistantFaceConfig.setFromRaw(this, raw)
        }
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SET_FACE -> {
                val raw = intent.getStringExtra(EXTRA_FACE)
                    ?: intent.getStringExtra(EXTRA_KIND)
                AssistantFaceConfig.setFromRaw(this, raw)
            }
            ACTION_SUMMON -> {
                summonEpoch++
                notifyImmersiveAssistantHotword()
            }
            else -> {
                if (overlayView == null && Settings.canDrawOverlays(this)) {
                    attachOverlay()
                }
                if (summonEpoch == 0) {
                    summonEpoch = 1
                    notifyImmersiveAssistantHotword()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        instance = null
        detachOverlay()
        store.clear()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun attachOverlay() {
        if (overlayView != null) return

        val recomposer = Recomposer(AndroidUiDispatcher.Main)
        uiScope.launch(AndroidUiDispatcher.Main) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val composeView = ComposeView(this).also { v ->
            v.setViewTreeLifecycleOwner(this)
            v.setViewTreeSavedStateRegistryOwner(this)
            v.setViewTreeViewModelStoreOwner(this)
            v.compositionContext = recomposer
            v.setContent {
                AssistantTheme(darkTheme = true) {
                    VirtualAssistantOverlay(
                        onDismiss = { stopSelf() },
                        modifier = Modifier.fillMaxSize(),
                        awaitHotword = false,
                        // Prefer silent lip-sync on AVDs — TTS init often fails (status=-1)
                        // and still stresses AudioFlinger around the immersive morph.
                        enableTts = false,
                    )
                }
            }
        }

        val view = android.widget.FrameLayout(this).also { frame ->
            frame.addView(
                composeView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Do not set FLAG_BLUR_BEHIND — it crashes SurfaceFlinger on many emulators
            // when immersive covers the screen. ImmersiveBackdrop provides the dim.
        }

        overlayView = view
        windowManager.addView(view, params)
    }

    private fun detachOverlay() {
        overlayView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        overlayView = null
    }

    companion object {
        const val ACTION_STOP = "com.test.design.assistant.IMMERSIVE_STOP"
        const val ACTION_SUMMON = "com.test.design.assistant.IMMERSIVE_SUMMON"
        const val ACTION_SET_FACE = "com.test.design.assistant.SET_FACE"
        const val EXTRA_FACE = AssistantFaceReceiver.EXTRA_FACE
        const val EXTRA_KIND = AssistantFaceReceiver.EXTRA_KIND

        @Volatile
        private var instance: ImmersiveAssistantOverlayService? = null

        fun show(context: Context, face: String? = null) {
            val app = context.applicationContext
            val intent = Intent(app, ImmersiveAssistantOverlayService::class.java)
            if (face != null) {
                intent.putExtra(EXTRA_FACE, face)
            }
            if (instance != null) {
                intent.action = ACTION_SUMMON
            }
            app.startService(intent)
        }

        fun stop(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, ImmersiveAssistantOverlayService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }
}
