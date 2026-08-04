package com.tcs.vehicleassistant.assistant.agent

import java.util.concurrent.atomic.AtomicLong

/**
 * Owns turn identity only — no LLM, tools, audio, or UI.
 *
 * Independent hardening: abandon races are tested here without LiteRT.
 */
class TurnStateMachine {

    private val turnCounter = AtomicLong(0)

    @Volatile
    var activeTurnId: Long = 0L
        private set

    @Volatile
    var isQueryProcessed: Boolean = true
        private set

    /**
     * Opens a new turn and invalidates any in-flight one, so late callbacks from an abandoned
     * inference are dropped rather than overwriting the new turn's UI and TTS state.
     */
    fun beginTurn(): Long {
        val turnId = turnCounter.incrementAndGet()
        activeTurnId = turnId
        isQueryProcessed = false
        return turnId
    }

    fun isCurrentTurn(turnId: Long): Boolean = activeTurnId == turnId

    fun markProcessed() {
        isQueryProcessed = true
    }

    fun resetProcessedIdle() {
        isQueryProcessed = true
    }

    fun isProcessing(): Boolean = !isQueryProcessed
}
