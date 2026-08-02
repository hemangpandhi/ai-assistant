package com.tcs.vehicleassistant.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.assistant.ui.assistant.ui.immersive.AssistantPlacement
import com.assistant.ui.assistant.ui.immersive.AssistantPlacementConfig
import com.tcs.vehicleassistant.LocalLLMActivity
import com.tcs.vehicleassistant.R

/**
 * Injects an "Assistant Placement" spinner into [LocalLLMActivity] without editing
 * master-owned Kotlin. Finds the existing UI Layout row and inserts a matching row after it.
 */
object LocalLlmPlacementSettingsHook {
    private const val ROW_TAG = "assistant_placement_settings_row"

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is LocalLLMActivity) {
                        activity.window.decorView.post {
                            injectIfNeeded(activity)
                        }
                    }
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun injectIfNeeded(activity: Activity) {
        val spinnerUiLayout = activity.findViewById<Spinner>(R.id.spinnerUILayout) ?: return
        val layoutRow = spinnerUiLayout.parent as? ViewGroup ?: return
        val container = layoutRow.parent as? ViewGroup ?: return
        if (container.findViewWithTag<View>(ROW_TAG) != null) return

        AssistantPlacementConfig.install(activity)

        val row = LinearLayout(activity).apply {
            tag = ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.rounded_bg)
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val bottom = (8 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = bottom }
        }

        val label = TextView(activity).apply {
            text = "Assistant Placement:"
            setTextColor(0xFFAAAAAA.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val end = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = end }
        }

        val options = AssistantPlacement.entries.map { it.label }.toTypedArray()
        val spinner = Spinner(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFBB86FC.toInt())
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                options,
            )
            val current = AssistantPlacementConfig.current()
            val index = AssistantPlacement.entries.indexOf(current).coerceAtLeast(0)
            setSelection(index, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val next = AssistantPlacement.entries.getOrNull(position) ?: return
                    if (next != AssistantPlacementConfig.current()) {
                        AssistantPlacementConfig.set(activity, next)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        row.addView(label)
        row.addView(spinner)

        val insertAt = container.indexOfChild(layoutRow).let { if (it >= 0) it + 1 else container.childCount }
        container.addView(row, insertAt)
    }
}
