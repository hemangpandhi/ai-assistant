package com.tcs.vehicleassistant.di

import com.tcs.vehicleassistant.SemanticSearchManager
import com.tcs.vehicleassistant.ToolManager
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
import com.tcs.vehicleassistant.llm.CloudLLMProvider
import com.tcs.vehicleassistant.llm.EdgeLLMProvider
import com.tcs.vehicleassistant.llm.ILLMProvider
import com.tcs.vehicleassistant.llm.LiteRtLlmEngine
import com.tcs.vehicleassistant.llm.LlmEngine
import com.tcs.vehicleassistant.repository.AgentOrchestrator
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single { ToolManager() }
    single { SemanticSearchManager(get()) }

    single { AssistantFeatureFlags(androidContext()) }
    single<VhalGateway> { VehicleManagerGateway() }
    single<ConversationMemory> { MemoryManagerStore() }
    single<LlmEngine> { LiteRtLlmEngine() }

    single<ILLMProvider>(named("edge")) { EdgeLLMProvider() }
    single<ILLMProvider>(named("cloud")) { CloudLLMProvider(get()) }

    // Voice path: one shared audio + orchestrator + view-model for the agent service.
    single<IAudioManager> { AndroidAudioManager(androidContext()) }
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
