package com.tcs.vehicleassistant.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.ArrayDeque
import kotlin.math.abs

data class HealthState(
    val heartRate: Int,
    val stressLevel: String, // "Low", "Moderate", "High"
    val isCalmModeActive: Boolean,
    val rawSignal: Float = 0f // For graphing if needed
)

class HealthProcessor(private val listener: (HealthState) -> Unit) {

    private val signalHistory = ArrayDeque<Float>()
    private val MAX_HISTORY = 150 // 5 seconds at ~30 FPS
    
    private var currentBpm = 72
    private var stressLevel = "Low"
    private var isCalmMode = false
    
    private var lastProcessTime = 0L
    private val PROCESS_INTERVAL_MS = 33L // ~30 FPS cap
    
    // Filter State
    private var lastFiltered = 0f
    
    fun processFrame(bitmap: Bitmap, faceResult: FaceLandmarkerResult?, currentMood: String?) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < PROCESS_INTERVAL_MS) return
        lastProcessTime = now

        var roiGreenAvg = 0f
        var validFace = false
        
        // 1. Get ROI (Forehead/Cheek)
        if (faceResult != null && faceResult.faceLandmarks().isNotEmpty() && faceResult.faceLandmarks()[0].isNotEmpty()) {
            val landmarks = faceResult.faceLandmarks()[0]
            // Forehead approximation (between eyes, slightly up)
            // MP Face Mesh: 10 (High Forehead), 151 (Mid Forehead), 9 (Mid between eyes)
            // Let's take a small patch around landmark 151 (Forehead center)
            // or 332, 297 (Right Cheek), 101, 67 (Left Cheek).
            // Cheeks are better for rPPG. 
            // Left Cheek Center ~ 123?
            // Let's use simple center of image fallback if landmarks fail, but tries landmarks[101] (Left Cheek)
            
            try {
                // Safeguard indices
                if (landmarks.size > 200) {
                    val cheek = landmarks[101] 
                    val cx = (cheek.x() * bitmap.width).toInt()
                    val cy = (cheek.y() * bitmap.height).toInt()
                    
                    roiGreenAvg = getAverageGreen(bitmap, cx, cy, 20)
                    validFace = true
                }
            } catch (e: Exception) {
                // Fallback
            }
        }
        
        if (!validFace) {
             // Fallback to center crop (assumes user is centered)
             roiGreenAvg = getAverageGreen(bitmap, bitmap.width / 2, bitmap.height / 2, 40)
        }
        
        // 2. Signal Processing
        // Normalize
        signalHistory.add(roiGreenAvg)
        if (signalHistory.size > MAX_HISTORY) signalHistory.removeFirst()
        
        // 3. BPM Calculation (Simple Zero-Crossing / Peak Det on AC component)
        if (signalHistory.size >= MAX_HISTORY) {
            calculateBpm()
        }
        
        // 4. Stress Logic
        // Mock Stress based on BPM and Mood (if available)
        stressLevel = if (currentBpm > 90 || currentMood == "Angry >:(" || currentMood == "Sad :(") {
            "High"
        } else if (currentBpm > 80) {
            "Moderate"
        } else {
            "Low"
        }
        
        if (stressLevel == "High" && !isCalmMode) {
             // Trigger? Logic outside will handle the actual command, we just report state
             // But we can flag it here.
             // We'll let the Service decide when to trigger the "Action" to avoid spam.
        }

        listener(HealthState(currentBpm, stressLevel, isCalmMode, roiGreenAvg))
    }
    
    private fun getAverageGreen(bitmap: Bitmap, cx: Int, cy: Int, size: Int): Float {
        var sum = 0L
        var count = 0
        
        val startX = (cx - size).coerceAtLeast(0)
        val endX = (cx + size).coerceAtMost(bitmap.width - 1)
        val startY = (cy - size).coerceAtLeast(0)
        val endY = (cy + size).coerceAtMost(bitmap.height - 1)
        
        for (x in startX..endX step 2) { // Skip pixels for speed
            for (y in startY..endY step 2) {
                val pixel = bitmap.getPixel(x, y)
                // Green channel
                sum += (pixel shr 8) and 0xFF
                count++
            }
        }
        return if (count > 0) sum.toFloat() / count else 0f
    }
    
    private fun calculateBpm() {
        // Simple heuristic for demo:
        // Real rPPG is complex. For a demo, we might want a "Simulated but Reactive" approach 
        // if the signal is too noisy (which it will be without rigorous filtering).
        // Let's try to extract a frequency.
        
        // Remove DC offset (Mean)
        val mean = signalHistory.average().toFloat()
        val acSignal = signalHistory.map { it - mean }
        
        // Count Zero Crossings (with hysteresis)
        var zeroCrossings = 0
        var isPositive = acSignal.first() > 0
        
        for (value in acSignal) {
            if (isPositive && value < -2.0) { // Threshold 2.0 to avoid noise
                zeroCrossings++
                isPositive = false
            } else if (!isPositive && value > 2.0) {
                zeroCrossings++
                isPositive = true
            }
        }
        
        // Crossings / 2 = Cycles
        // Cycles / Time = Frequency
        // Time = MAX_HISTORY * (PROCESS_INTERVAL_MS / 1000) ?? No, real fps varies.
        // Approx 5 seconds buffer.
        val cycles = zeroCrossings / 2.0
        val estimatedBpm = (cycles * (60.0 / 5.0)).toInt() // 5 seconds window
        
        // Smoothing
        if (estimatedBpm in 50..120) {
            currentBpm = (currentBpm * 0.7 + estimatedBpm * 0.3).toInt()
        } else {
             // Noise? Drift back to 72
             currentBpm = (currentBpm * 0.95 + 72 * 0.05).toInt()
        }
    }
    
    fun setCalmMode(active: Boolean) {
        isCalmMode = active
    }
}
