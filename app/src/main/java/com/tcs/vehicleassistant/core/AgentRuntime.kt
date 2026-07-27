package com.tcs.vehicleassistant.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide structured concurrency for the agent harness.
 * Owned/reset by [com.tcs.vehicleassistant.service.VehicleAgentService].
 *
 * - [agentDispatcher]: single-thread for orchestrator / token parse (off Main)
 * - [ioScope]: disk / VHAL / model IO
 * - [mainScope]: UI-facing emits only
 *
 * Do **not** store [scope] / [ioScope] / [mainScope] in a field at construction time.
 * [resetForService] / [shutdown] replace the root job; cached scopes stay cancelled forever.
 */
object AgentRuntime {
    private val rootJob = AtomicReference(SupervisorJob())

    /** Single-threaded agent work — no Main contention with Compose/STT. */
    val agentDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    @Volatile
    private var scopes: Scopes = Scopes(rootJob.get())

    val scope: CoroutineScope
        get() = scopes.agent

    val ioScope: CoroutineScope
        get() = scopes.io

    val mainScope: CoroutineScope
        get() = scopes.main

    fun cancelChildren() {
        rootJob.get().cancelChildren()
    }

    /** Call from VehicleAgentService.onDestroy — tears down all agent coroutines. */
    fun shutdown() {
        val old = rootJob.getAndSet(SupervisorJob())
        old.cancel()
        scopes = Scopes(rootJob.get())
    }

    fun resetForService() {
        shutdown()
    }

    private class Scopes(job: Job) {
        val agent = CoroutineScope(job + agentDispatcher)
        val io = CoroutineScope(job + Dispatchers.IO)
        val main = CoroutineScope(job + Dispatchers.Main.immediate)
    }
}
