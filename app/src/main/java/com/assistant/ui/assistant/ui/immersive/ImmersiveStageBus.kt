package com.assistant.ui.assistant.ui.immersive

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Session-scoped summon/dismiss bus (replaces process-wide mutable handler lists).
 */
object ImmersiveStageBus {
    private val _summon = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val _dismiss = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val summon: SharedFlow<Unit> = _summon.asSharedFlow()
    val dismiss: SharedFlow<Unit> = _dismiss.asSharedFlow()

    fun notifySummon() {
        _summon.tryEmit(Unit)
    }

    fun notifyDismiss() {
        _dismiss.tryEmit(Unit)
    }
}
