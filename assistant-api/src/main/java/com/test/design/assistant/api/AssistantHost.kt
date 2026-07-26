package com.test.design.assistant.api

/**
 * Host-app capabilities the assistant module must not own.
 * Implemented by `:app` today; a future standalone assistant APK provides its own.
 */
interface AssistantHost {
    /** Latest cabin / vehicle snapshot for proactive prompts. */
    fun cabinContext(): AssistantCabinContext

    /** Mirror status to instrument cluster / glance surface. */
    fun openClusterHandOff()

    /** Optional high-contrast eyes for sunlight (settings). */
    fun highContrastEyes(): Boolean = false
}

/**
 * Process-wide wiring installed by the host Application.
 */
object AssistantRuntime {
    @Volatile
    var host: AssistantHost = NoOpAssistantHost
        private set

    @Volatile
    var backend: AssistantBackend? = null
        private set

    fun install(
        host: AssistantHost,
        backend: AssistantBackend,
    ) {
        this.host = host
        this.backend = backend
    }

    fun requireBackend(): AssistantBackend =
        backend ?: error("AssistantRuntime.install() was not called")

    fun requireHost(): AssistantHost = host
}

object NoOpAssistantHost : AssistantHost {
    override fun cabinContext(): AssistantCabinContext = AssistantCabinContext()
    override fun openClusterHandOff() = Unit
}
