package com.tcs.vehicleassistant.di

import com.tcs.vehicleassistant.SemanticSearchManager
import com.tcs.vehicleassistant.ToolManager
import com.tcs.vehicleassistant.llm.CloudLLMProvider
import com.tcs.vehicleassistant.llm.EdgeLLMProvider
import com.tcs.vehicleassistant.llm.ILLMProvider
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Core DI aligned with `dev/refactor`.
 *
 * UI/UX / TTFR / ports bindings live in [uiUxModule] so this file stays rebase-friendly.
 */
val appModule = module {
    single { ToolManager() }
    single { SemanticSearchManager(get()) }

    single<ILLMProvider>(named("edge")) { EdgeLLMProvider() }
    single<ILLMProvider>(named("cloud")) { CloudLLMProvider(get()) }
}
