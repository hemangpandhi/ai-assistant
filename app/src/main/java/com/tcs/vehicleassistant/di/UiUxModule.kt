package com.tcs.vehicleassistant.di

import com.tcs.vehicleassistant.controller.UiUxAssistantViewModel
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
import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.hardware.SessionAndroidAudioManager
import com.tcs.vehicleassistant.hardware.SessionAudioPort
import com.tcs.vehicleassistant.llm.LiteRtLlmEngine
import com.tcs.vehicleassistant.llm.LlmEngine
import com.tcs.vehicleassistant.repository.UiUxAgentOrchestrator
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
    single { SessionAndroidAudioManager(androidContext()) }
    single<SessionAudioPort> { get<SessionAndroidAudioManager>() }
    single<IAudioManager> { get<SessionAudioPort>() }

    single { SpeechPresenter(get()) }
    single { ExecuteToolUseCase(get()) }
    single { ToolLoop(get()) }
    single { QueryPipeline(get(), get(), get()) }
    single { FollowUpUseCase() }
    single { ProcessQueryUseCase(get()) }
    single {
        UiUxAgentOrchestrator(
            context = androidContext(),
            audioManager = get(),
            memory = get(),
            featureFlags = get(),
            queryPipeline = get(),
            toolLoop = get(),
            speechPresenter = get(),
        )
    }
    single { UiUxAssistantViewModel(androidContext(), get(), get(), get()) }
}
