package com.tcs.vehicleassistant.domain

/**
 * Coalesce streaming UI emits (~30fps) so Compose is not flooded per token.
 * UI/UX / TTFR extension extracted from AgentOrchestrator.
 */
class StreamingStateCoalescer(
    private val intervalMs: Long = 32L,
    private val emit: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var pending: String? = null
    private var lastEmitMs = 0L

    fun offer(text: String, force: Boolean = false) {
        synchronized(lock) {
            pending = text
            val now = clock()
            if (force || now - lastEmitMs >= intervalMs) {
                lastEmitMs = now
                pending = null
                emit(text)
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            pending?.let {
                pending = null
                lastEmitMs = clock()
                emit(it)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            pending = null
            lastEmitMs = 0L
        }
    }
}
