package com.test.design.presentation.assistant

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Extra bottom inset so assistant chrome clears host floating docks.
 * `:app` provides this from its system-bar layout; standalone hosts leave 0.
 */
val LocalAssistantChromeBottomSpace = compositionLocalOf { 0.dp }

/** Pad content above host chrome without importing IVI layout helpers. */
@Composable
fun Modifier.assistantChromePadding(): Modifier {
    val bottom = LocalAssistantChromeBottomSpace.current
    return if (bottom > 0.dp) padding(bottom = bottom) else this
}
