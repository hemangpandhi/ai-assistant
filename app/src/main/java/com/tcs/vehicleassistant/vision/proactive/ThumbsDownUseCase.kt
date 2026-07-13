package com.tcs.vehicleassistant.vision.proactive

import android.util.Log

class ThumbsDownUseCase(private val isMusicPlayingProvider: () -> Boolean, private val skipMusicAction: () -> Unit) : IProactiveUseCase {
    override val name = "Contextual Gesture"
    override val priority = PriorityLevel.COMFORT
    
    private var lastTrigger = 0L
    private val DEBOUNCE_MS = 10_000L // 10 seconds

    override fun evaluate(context: CabinContext): TriggerResult? {
        val gesture = context.gestureFeedback?.gestureName ?: return null
        val now = context.timestamp

        if (gesture.contains("Thumb_Down", ignoreCase = true)) {
            if (now - lastTrigger > DEBOUNCE_MS) {
                lastTrigger = now
                Log.w("VisionOrchestrator", "Thumbs Down gesture detected!")

                if (isMusicPlayingProvider()) {
                    val prompt = "[SYSTEM EVENT: The driver gave a 'Thumbs Down' gesture while music was playing. The system has ALREADY skipped to the next song. You DO NOT need to use any media tools. Immediately say EXACTLY: 'Not a fan? I skipped to the next song.']"
                    
                    // Trigger skip action
                    skipMusicAction()
                    
                    return TriggerResult(prompt, priority)
                } else {
                    val prompt = "[SYSTEM EVENT: The driver gave a 'Thumbs Down' gesture. Ask them what they are dissatisfied with, e.g., 'Not a fan of the route? Should I find an alternative?']"
                    return TriggerResult(prompt, priority)
                }
            }
        }
        return null
    }
}