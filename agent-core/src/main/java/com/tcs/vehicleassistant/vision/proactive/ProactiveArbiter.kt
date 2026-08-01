package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class ProactiveArbiter(
    private val onTriggerAction: (String) -> Unit
) {
    private val useCases = mutableListOf<IProactiveUseCase>()

    fun registerUseCase(useCase: IProactiveUseCase) {
        useCases.add(useCase)
    }

    fun onContextUpdate(context: CabinContext) {
        var highestPriorityResult: TriggerResult? = null
        var winningUseCaseName: String? = null

        for (useCase in useCases) {
            val result = useCase.evaluate(context)
            if (result != null) {
                if (highestPriorityResult == null || result.priority.weight > highestPriorityResult.priority.weight) {
                    highestPriorityResult = result
                    winningUseCaseName = useCase.name
                }
            }
        }

        highestPriorityResult?.let {
            Log.d("ProactiveArbiter", "Triggering use case [${winningUseCaseName}] with priority ${it.priority}")
            onTriggerAction(it.prompt)
        }
    }
}