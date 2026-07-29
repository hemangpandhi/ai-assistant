package com.tcs.vehicleassistant.assistant.session

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Lifecycle / SavedState / ViewModel host for Compose content inside a voice
 * interaction session (which is not itself a LifecycleOwner).
 *
 * SavedStateRegistry's Restarter may only be registered while the owner is still
 * [Lifecycle.State.INITIALIZED] — jumping straight to RESUMED before
 * [SavedStateRegistryController.performRestore] crashes with:
 * "Restarter must be created only during owner's initialization stage".
 */
internal class SessionComposeHost :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        // Attach + restore while still INITIALIZED (LifecycleRegistry default).
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED ||
            registry.currentState == Lifecycle.State.DESTROYED
        ) {
            store.clear()
            return
        }
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
