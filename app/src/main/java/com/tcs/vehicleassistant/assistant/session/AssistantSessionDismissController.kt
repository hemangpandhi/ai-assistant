package com.tcs.vehicleassistant.assistant.session

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import com.assistant.ui.assistant.api.AssistantDebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Dismisses a visible assistant session when another system or app UI takes focus. */
internal class AssistantSessionDismissController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val overlayViewProvider: () -> View?,
    private val isVisible: () -> Boolean,
    private val onDismiss: (reason: String) -> Unit,
) {
    private var baselineResumedActivity: String? = null
    private var baselineTopPackage: String? = null
    private var focusListenerRegistered = false
    private var topTaskPollJob: Job? = null
    private var closeSystemDialogsReceiverRegistered = false
    private var protectUntilElapsedMs: Long = 0L

    private val activityWatcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            // Same-process apps (e.g. LocalLLMActivity) — dismiss as soon as they start.
            if (!isVisible()) return
            if (activity.javaClass.name.contains("AssistantSession")) return
            AssistantDebugLog.d(
                "Session",
                "activity started=${activity.javaClass.simpleName} — dismiss",
            )
            onDismiss("activity-started:${activity.javaClass.simpleName}")
        }

        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            if (!isVisible()) return
            val name = activity.javaClass.name
            if (baselineResumedActivity == null) {
                baselineResumedActivity = name
                AssistantDebugLog.d("Session", "baseline activity=$name")
                return
            }
            if (name != baselineResumedActivity) {
                onDismiss("activity-resumed:$name")
            }
        }
    }

    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (!isVisible()) return@OnWindowFocusChangeListener
        AssistantDebugLog.d("Session", "window focus=$hasFocus")
        if (!hasFocus) {
            if (isSummonProtected()) {
                AssistantDebugLog.d("Session", "focus-lost ignored (summon protect)")
                return@OnWindowFocusChangeListener
            }
            scope.launch {
                delay(80)
                val overlayView = overlayViewProvider()
                if (isVisible() &&
                    overlayView != null &&
                    !overlayView.hasWindowFocus() &&
                    !isSummonProtected()
                ) {
                    onDismiss("window-focus-lost")
                }
            }
        }
    }

    private val closeSystemDialogsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (!isVisible()) return
            val reason = intent?.getStringExtra("reason") ?: intent?.action ?: "unknown"
            AssistantDebugLog.d("Session", "CLOSE_SYSTEM_DIALOGS reason=$reason")
            if (isSummonProtected()) {
                AssistantDebugLog.d("Session", "close-system-dialogs ignored (summon protect)")
                return
            }
            // Home / recent / system bar often fires this when leaving the assistant.
            onDismiss("close-system-dialogs:$reason")
        }
    }

    fun start(protectionMs: Long = 2_000L) {
        protectUntilElapsedMs = SystemClock.elapsedRealtime() + protectionMs
        baselineResumedActivity = null
        baselineTopPackage = null

        val app = context.applicationContext as? Application ?: return
        runCatching { app.unregisterActivityLifecycleCallbacks(activityWatcher) }
        app.registerActivityLifecycleCallbacks(activityWatcher)
        val overlayView = overlayViewProvider()
        if (!focusListenerRegistered && overlayView != null) {
            overlayView.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
            focusListenerRegistered = true
        }
        if (!closeSystemDialogsReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            runCatching {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    closeSystemDialogsReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                closeSystemDialogsReceiverRegistered = true
            }.onFailure {
                AssistantDebugLog.w(
                    "Session",
                    "CLOSE_SYSTEM_DIALOGS register failed: ${it.message}",
                )
            }
        }
        startTopTaskPoller()
    }

    fun stop() {
        baselineResumedActivity = null
        baselineTopPackage = null
        topTaskPollJob?.cancel()
        topTaskPollJob = null
        val app = context.applicationContext as? Application
        runCatching { app?.unregisterActivityLifecycleCallbacks(activityWatcher) }
        val overlayView = overlayViewProvider()
        if (focusListenerRegistered && overlayView != null) {
            runCatching {
                overlayView.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
            }
            focusListenerRegistered = false
        }
        if (closeSystemDialogsReceiverRegistered) {
            runCatching {
                context.applicationContext.unregisterReceiver(closeSystemDialogsReceiver)
            }
            closeSystemDialogsReceiverRegistered = false
        }
    }

    fun destroy() {
        stop()
    }

    private fun isSummonProtected(): Boolean =
        SystemClock.elapsedRealtime() < protectUntilElapsedMs

    /**
     * Cross-process dismiss: ActivityLifecycleCallbacks only see this app's activities.
     * System-bar launches (Maps, phone, …) live in other processes — poll the foreground
     * task/package and hide when it changes.
     */
    private fun startTopTaskPoller() {
        topTaskPollJob?.cancel()
        topTaskPollJob = scope.launch {
            // Let the session settle before sampling the baseline top package.
            delay(350)
            while (isActive && isVisible()) {
                val top = foregroundPackage()
                if (top != null) {
                    if (baselineTopPackage == null) {
                        baselineTopPackage = top
                        AssistantDebugLog.d("Session", "baseline topPkg=$top")
                    } else if (top != baselineTopPackage && !isTransientSystemPackage(top)) {
                        // Confirm once — AAOS system UI can briefly report a different pkg.
                        delay(120)
                        val confirmed = foregroundPackage()
                        if (confirmed == top && confirmed != baselineTopPackage && isVisible()) {
                            if (isSummonProtected()) {
                                AssistantDebugLog.d(
                                    "Session",
                                    "topPkg change ignored (summon protect): $confirmed",
                                )
                            } else {
                                onDismiss("top-pkg $baselineTopPackage → $confirmed")
                                break
                            }
                        }
                    }
                }
                delay(250)
            }
        }
    }

    /** Packages that flicker without meaning a real app launch from the system bar. */
    private fun isTransientSystemPackage(pkg: String): Boolean {
        return pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg.endsWith(".systemui") ||
            pkg.contains("permissioncontroller")
    }

    private fun foregroundPackage(): String? {
        // 1) Running tasks (works for priv-apps with REAL_GET_TASKS on AAOS).
        runCatching {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val pkg = activityManager.getRunningTasks(1)
                ?.firstOrNull()
                ?.topActivity
                ?.packageName
            if (!pkg.isNullOrBlank()) return pkg
        }
        // 2) Importance-based process list.
        runCatching {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val process = activityManager.runningAppProcesses?.firstOrNull {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
            val pkg = process?.pkgList?.firstOrNull() ?: process?.processName
            if (!pkg.isNullOrBlank()) return pkg
        }
        // 3) Usage stats (if granted).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val usageStatsManager =
                    context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    end - 15_000,
                    end,
                )
                val top = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
                if (!top.isNullOrBlank()) return top
            }
        }
        return null
    }
}
