package com.tcs.vehicleassistant.di

import com.tcs.vehicleassistant.controller.AssistantViewModel
import com.tcs.vehicleassistant.core.flags.AssistantFeatureFlags
import com.tcs.vehicleassistant.data.memory.ConversationMemory
import com.tcs.vehicleassistant.data.memory.MemoryManagerStore
import com.tcs.vehicleassistant.data.vehicle.VehicleManagerGateway
import com.tcs.vehicleassistant.data.vehicle.VhalGateway
import com.tcs.vehicleassistant.domain.ExecuteToolUseCase
import com.tcs.vehicleassistant.domain.FollowUpUseCase
import com.tcs.vehicleassistant.domain.ProcessQueryUseCase
import com.tcs.vehicleassistant.domain.QueryPipeline
import com.tcs.vehicleassistant.domain.SpeechPresenter
import com.tcs.vehicleassistant.domain.ToolLoop
import com.tcs.vehicleassistant.hardware.AndroidAudioManager
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.hardware.SessionAudioPort
import com.tcs.vehicleassistant.llm.LiteRtLlmEngine
import com.tcs.vehicleassistant.llm.LlmEngine
import com.tcs.vehicleassistant.repository.AgentOrchestrator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Additive UI/UX / TTFR / ports bindings.
 *
 * Keep [appModule] close to `dev/refactor`; put extension singles here so rebasing
 * refactor DI changes stays a one-line `modules(...)` merge in VehicleApplication.
 */
val uiUxModule = module {
    single { AssistantFeatureFlags(androidContext()) }
    single<VhalGateway> { VehicleManagerGateway() }
    single<ConversationMemory> { MemoryManagerStore() }
    single<LlmEngine> { LiteRtLlmEngine() }

    // Concrete audio impl registered as SessionAudioPort; also satisfies IAudioManager.
    single { AndroidAudioManager(androidContext()) }
    single<SessionAudioPort> { get<AndroidAudioManager>() }
    single<IAudioManager> { get<SessionAudioPort>() }

    single { SpeechPresenter(get()) }
    single { ExecuteToolUseCase(get()) }
    single { ToolLoop(get()) }
    single { QueryPipeline(get(), get(), get()) }
    single { FollowUpUseCase() }
    single { ProcessQueryUseCase(get()) }
    single {
        AgentOrchestrator(
            context = androidContext(),
            audioManager = get(),
            memory = get(),
            featureFlags = get(),
            queryPipeline = get(),
            toolLoop = get(),
            speechPresenter = get(),
        )
    }
    single { AssistantViewModel(androidContext(), get(), get(), get()) }
}
