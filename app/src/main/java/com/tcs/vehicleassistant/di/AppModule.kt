package com.tcs.vehicleassistant.di


import com.tcs.vehicleassistant.llm.ILLMProvider
import com.tcs.vehicleassistant.llm.EdgeLLMProvider
import com.tcs.vehicleassistant.llm.CloudLLMProvider
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single { com.tcs.vehicleassistant.domain.tools.ToolRegistry() }
    single { com.tcs.vehicleassistant.domain.tools.ToolSchemaGenerator(get()) }
    single<com.tcs.vehicleassistant.handlers.IToolHandlerRegistry> { com.tcs.vehicleassistant.handlers.DefaultToolHandlerRegistry() }
    single<com.tcs.vehicleassistant.domain.tools.IToolExecutor> { com.tcs.vehicleassistant.executor.AppToolExecutor(get(), get()) }

    single<ILLMProvider>(named("edge")) { EdgeLLMProvider() }
    single<ILLMProvider>(named("cloud")) { CloudLLMProvider() }

    single<com.tcs.vehicleassistant.hardware.IAudioManager> { com.tcs.vehicleassistant.hardware.AndroidAudioManager(get()) }
    single { com.tcs.vehicleassistant.repository.AgentOrchestrator(get(), get(), get(), get(), get()) }
}
