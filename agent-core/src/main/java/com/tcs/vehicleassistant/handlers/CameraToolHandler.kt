package com.tcs.vehicleassistant.handlers

import com.tcs.vehicleassistant.hardware.CabinCameraManager

class CameraToolHandler {
    companion object {
        fun handleAnalyzeCabinState(): ToolExecutionResult {
            val mood = CabinCameraManager.currentMood
            val count = CabinCameraManager.occupantCount
            
            val response = if (count == 0) {
                "I don't see anyone in the cabin right now."
            } else if (count == 1) {
                "I see you! You look $mood."
            } else {
                "I see $count people in the cabin. The driver looks $mood."
            }
            
            return ToolExecutionResult(true, response)
        }
    }
}
