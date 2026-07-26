package com.test.design.presentation.assistant.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.test.design.presentation.assistant.AssistantTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * AAOS system overlay host.
 *
 * - IDLE: 64dp bug at BottomEnd, no scrim; only the bug is touchable
 * - Active: MATCH_PARENT + scrim α0.25; only the 420×180 capsule (32dp from bottom)
 *   receives touches — everything else passes through to maps/apps
 */
class AssistantOverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: PassThroughFrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val uiJob = SupervisorJob()
    private val uiScope = CoroutineScope(AndroidUiDispatcher.Main + uiJob)

    private var state by mutableStateOf(AssistantState.IDLE)
    private var amplitude by mutableFloatStateOf(0f)
    private val capsuleHitRect = Rect()

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!canDrawOverlays()) {
            stopSelf()
            return
        }
        attachOverlay()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_STATE -> {
                val name = intent.getStringExtra(EXTRA_STATE) ?: return START_STICKY
                runCatching { AssistantState.valueOf(name) }.getOrNull()?.let { applyState(it) }
            }
            ACTION_UPDATE_AMPLITUDE -> {
                applyAmplitude(intent.getFloatExtra(EXTRA_AMPLITUDE, 0f))
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        instance = null
        detachOverlay()
        uiScope.cancel()
        super.onDestroy()
    }

    fun updateState(newState: AssistantState) {
        uiScope.launch { applyState(newState) }
    }

    fun updateAmplitude(value: Float) {
        uiScope.launch { applyAmplitude(value) }
    }

    private fun applyState(newState: AssistantState) {
        state = newState
        relayoutWindowForState(newState)
        overlayView?.invalidate()
    }

    private fun applyAmplitude(value: Float) {
        amplitude = value.coerceIn(0f, 1f)
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
            v.compositionContext = recomposer
            v.setContent {
                OverlayContent(
                    state = state,
                    amplitude = amplitude,
                    onBugClick = { updateState(AssistantState.LISTENING) },
                    onReturnIdle = { updateState(AssistantState.IDLE) },
                    onCapsuleBounds = { l, t, r, b -> capsuleHitRect.set(l, t, r, b) },
                )
            }
        }

        val view = PassThroughFrameLayout(this).also { frame ->
            frame.hitRectProvider = {
                if (state == AssistantState.IDLE) {
                    Rect(0, 0, frame.width.coerceAtLeast(1), frame.height.coerceAtLeast(1))
                } else {
                    capsuleHitRect
                }
            }
            frame.addView(
                composeView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val params = baseLayoutParams(AssistantState.IDLE)
        overlayView = view
        layoutParams = params
        windowManager.addView(view, params)
    }

    private fun detachOverlay() {
        overlayView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        overlayView = null
        layoutParams = null
    }

    private fun relayoutWindowForState(newState: AssistantState) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val next = baseLayoutParams(newState)
        params.width = next.width
        params.height = next.height
        params.gravity = next.gravity
        params.x = next.x
        params.y = next.y
        params.flags = next.flags
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun baseLayoutParams(forState: AssistantState): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val format = PixelFormat.TRANSLUCENT
        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )

        return if (forState == AssistantState.IDLE) {
            WindowManager.LayoutParams(
                dp(CarAssistantGeometry.BugSize.value),
                dp(CarAssistantGeometry.BugSize.value),
                type,
                flags,
                format,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = dp(16f)
                y = dp(16f)
            }
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                format,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(this)

    companion object {
        const val ACTION_UPDATE_STATE = "com.test.design.assistant.UPDATE_STATE"
        const val ACTION_UPDATE_AMPLITUDE = "com.test.design.assistant.UPDATE_AMPLITUDE"
        const val ACTION_STOP = "com.test.design.assistant.STOP"
        const val EXTRA_STATE = "state"
        const val EXTRA_AMPLITUDE = "amplitude"

        @Volatile
        private var instance: AssistantOverlayService? = null

        fun start(context: Context) {
            context.startService(Intent(context, AssistantOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AssistantOverlayService::class.java).setAction(ACTION_STOP),
            )
        }

        fun updateState(context: Context, state: AssistantState) {
            instance?.updateState(state) ?: context.startService(
                Intent(context, AssistantOverlayService::class.java)
                    .setAction(ACTION_UPDATE_STATE)
                    .putExtra(EXTRA_STATE, state.name),
            )
        }

        fun updateAmplitude(context: Context, value: Float) {
            instance?.updateAmplitude(value) ?: context.startService(
                Intent(context, AssistantOverlayService::class.java)
                    .setAction(ACTION_UPDATE_AMPLITUDE)
                    .putExtra(EXTRA_AMPLITUDE, value),
            )
        }
    }
}

@Composable
private fun OverlayContent(
    state: AssistantState,
    amplitude: Float,
    onBugClick: () -> Unit,
    onReturnIdle: () -> Unit,
    onCapsuleBounds: (left: Int, top: Int, right: Int, bottom: Int) -> Unit,
) {
    if (state == AssistantState.IDLE) {
        CarAssistantBug(
            state = state,
            modifier = Modifier
                .size(CarAssistantGeometry.BugSize)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBugClick,
                ),
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AssistantTokens.Scrim),
    ) {
        val density = LocalDensity.current
        val capsuleW = with(density) { CarAssistantGeometry.CapsuleWidth.toPx() }
        val capsuleH = with(density) { CarAssistantGeometry.CapsuleHeight.toPx() }
        val bottomInset = with(density) { CarAssistantGeometry.BottomInset.toPx() }
        val left = ((constraints.maxWidth - capsuleW) * 0.5f).roundToInt()
        val top = (constraints.maxHeight - capsuleH - bottomInset).roundToInt()
        val right = (left + capsuleW).roundToInt()
        val bottom = (top + capsuleH).roundToInt()
        SideEffect { onCapsuleBounds(left, top, right, bottom) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = CarAssistantGeometry.BottomInset),
            contentAlignment = Alignment.BottomCenter,
        ) {
            CarAssistantFace(
                state = state,
                audioAmplitude = amplitude,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onReturnIdle,
                ),
            )
        }
    }
}

/**
 * Claims touches only inside [hitRectProvider]; returns false elsewhere so the
 * window manager forwards events to underlying maps / apps.
 */
private class PassThroughFrameLayout(context: Context) : android.widget.FrameLayout(context) {
    var hitRectProvider: (() -> Rect)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val rect = hitRectProvider?.invoke() ?: return super.dispatchTouchEvent(ev)
        val x = ev.x.roundToInt()
        val y = ev.y.roundToInt()
        return if (rect.contains(x, y)) {
            super.dispatchTouchEvent(ev)
        } else {
            false
        }
    }
}
