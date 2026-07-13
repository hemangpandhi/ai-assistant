package com.tcs.vehicleassistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

data class GestureFeedback(
    val gestureName: String,
    val score: Float,
    val feedbackMessage: String,
    val positionHint: String,
    val isNightMode: Boolean = false,
    val isSleeping: Boolean = false,
    val isDemoMode: Boolean = false,
    val mood: String = "Neutral",
    val driverName: String = "Guest",
    val passengerDetected: Boolean = false
)

class GestureProcessor(context: Context, private val listener: (GestureFeedback) -> Unit) {
    private var gestureRecognizer: GestureRecognizer? = null
    private var faceLandmarker: FaceLandmarker? = null
    
    // Phase 4: Demo Mode & Mood
    var isDemoMode = true // Default for Customer Demo
    private var currentMood = "Neutral"
    private var frameCount = 0
    
    // ID State
    private var driverName = "Guest"
    private var passengerPresent = false
    
    // Shared Data for HealthProcessor
    var lastFaceResult: FaceLandmarkerResult? = null

    
    // Phase 3 State Variables
    private var isSleeping = true 
    private var lastWakeTime = 0L
    private val WAKE_DURATION_MS = 10000L
    private var wakeHoldStartTime = 0L
    
    private val handHistory = ArrayDeque<Pair<Long, Float>>()
    private val HISTORY_SIZE = 10 

    init {
        setupGestureRecognizer(context)
        setupFaceLandmarker(context)
    }

    private fun setupGestureRecognizer(context: Context) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task") 
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE) 
            .build()
        try {
            gestureRecognizer = GestureRecognizer.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e("GestureProcessor", "Error initializing recognizer: ${e.message}")
        }
    }
    
    private fun setupFaceLandmarker(context: Context) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(2)
            .setOutputFaceBlendshapes(true) // CRITICAL: Needed for mood (smile/frown) detection
            .build()
        try {
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
             Log.e("GestureProcessor", "Error initializing face landmarker: ${e.message}")
        }
    }

    private var lastInferenceTime = 0L

    fun processingFrame(bitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastInferenceTime < 100) return // Throttle to ~10 FPS
        lastInferenceTime = currentTime
        
        if (gestureRecognizer == null) return
        
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = gestureRecognizer?.recognize(mpImage)
            
            // Run Face Detection every 10 frames (Throttle)
            frameCount++
            if (frameCount % 10 == 0) {
                 val faceResult = faceLandmarker?.detect(mpImage)
                 lastFaceResult = faceResult
                 analyzeMood(faceResult)
            }
            
            // Calculate Luminance
            var lumSum = 0L
            val cx = bitmap.width / 2
            val cy = bitmap.height / 2
            var pixels = 0
            if (cx > 10 && cy > 10) {
                 for (x in -5 until 5) {
                    for (y in -5 until 5) {
                        val pixel = bitmap.getPixel(cx + x, cy + y)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        lumSum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                        pixels++
                    }
                }
            }
            val avgLum = if (pixels > 0) lumSum / pixels else 255
            
            processResult(result, avgLum.toInt())
        } catch (e: Exception) {
            Log.e("GestureProcessor", "Inference error: ${e.message}")
        }
    }
    
    // State Timers
    private var eyesClosedStartTime = 0L
    private var distractionStartTime = 0L
    
    private fun analyzeMood(result: FaceLandmarkerResult?) {
        result?.let {
            if (it.faceBlendshapes().isPresent && it.faceBlendshapes().get().isNotEmpty()) {
                val blendshapes = it.faceBlendshapes().get()[0]
                
                // --- DRIVER ID & PASSENGER LOGIC ---
                // Reset defaults for this frame logic, but we need to persist them for processResult
                var detectedDriver = "Guest"
                var detectedPassenger = false
                
                // 1. Passenger Detection (Check if 2nd face exists)
                if (it.faceLandmarks().size > 1) {
                    detectedPassenger = true
                }
                
                // 2. Driver Identification
                if (it.faceLandmarks().isNotEmpty()) {
                    val driverLandmarks = it.faceLandmarks()[0]
                    val currentSig = extractFaceSignature(driverLandmarks)
                    
                    if (currentSig.isNotEmpty()) {
                        if (registerNextFace) {
                            registeredDrivers[pendingRegistrationName] = currentSig
                            registerNextFace = false
                            Log.d("GestureProcessor", "Driver Registered: $pendingRegistrationName")
                        }
                        
                        // Check against all registered drivers
                        var bestMatchName = "Guest"
                        var bestMatchScore = 100f
                        
                        for ((name, savedSig) in registeredDrivers) {
                             val diff = compareSignatures(currentSig, savedSig)
                             Log.d("GestureProcessor", "Comparing with $name: diff=$diff")
                             if (diff < 0.25f && diff < bestMatchScore) {
                                 bestMatchScore = diff
                                 bestMatchName = name
                             }
                        }
                        
                        detectedDriver = bestMatchName
                    }
                }
                
                // Store in class vars for listener
                this.driverName = detectedDriver
                this.passengerPresent = detectedPassenger
                
                // Scores
                var smileScore = 0f
                var jawOpenScore = 0f
                var browDownScore = 0f
                var frownScore = 0f
                
                // Advanced States
                var eyeBlinkScore = 0f
                var gazeOutScore = 0f
                var browInnerUpScore = 0f
                var noseSneerScore = 0f
                var cheekPuffScore = 0f
                var eyeSquintScore = 0f
                var eyeBlinkLeftScore = 0f
                var eyeBlinkRightScore = 0f
                
                for (category in blendshapes) {
                    val name = category.categoryName()
                    val score = category.score()
                    
                    // Mood
                    if (name == "mouthSmileLeft" || name == "mouthSmileRight") smileScore += score
                    if (name == "jawOpen") jawOpenScore = score
                    if (name == "browDownLeft" || name == "browDownRight") browDownScore += score
                    if (name == "mouthFrownLeft" || name == "mouthFrownRight") frownScore += score
                    
                    // Advanced
                    if (name == "eyeBlinkLeft") eyeBlinkLeftScore = score
                    if (name == "eyeBlinkRight") eyeBlinkRightScore = score
                    
                    if (name == "eyeBlinkLeft" || name == "eyeBlinkRight") {
                         // Keep existing combined score for Drowsiness logic
                        if (score > 0.5) eyeBlinkScore += 0.5f 
                    }
                    if (name == "eyeLookOutLeft" || name == "eyeLookOutRight" || name == "eyeLookUp" || name == "eyeLookDown") {
                        if (score > 0.5) gazeOutScore = score
                    }
                    if (name == "browInnerUp") browInnerUpScore = score
                    if (name == "noseSneerLeft" || name == "noseSneerRight") {
                        if (score > 0.5) noseSneerScore = score // Max or single trigger
                    }
                    
                    // New Moods
                    if (name == "cheekPuff") cheekPuffScore = score
                    if (name == "eyeSquintLeft" || name == "eyeSquintRight") eyeSquintScore += score // Sum > 1.0 means both
                    // jawOpen is already tracked for "Surprised" but also for "Speaking" 
                    // To diff Surprised vs Speaking: Surprised is usually WIDE open (>0.3), Speaking is rhythmic/smaller.
                    // For demo, let's use a threshold.
                }
                
                // --- STATE LOGIC ---
                val now = System.currentTimeMillis()
                var newState = "Neutral"
                
                // 1. Drowsiness (Eyes Closed > 1s)
                if (eyeBlinkScore > 0.8) { // Both eyes closed
                    if (eyesClosedStartTime == 0L) eyesClosedStartTime = now
                    if (now - eyesClosedStartTime > 1000) {
                        newState = "DROWSY WARNING"
                        eyesClosedStartTime = now - 1000 // Keep triggering
                    }
                } else {
                    eyesClosedStartTime = 0L
                }
                
                // 2. Distraction (Gaze Away > 2s)
                if (gazeOutScore > 0.6) {
                    if (distractionStartTime == 0L) distractionStartTime = now
                    if (now - distractionStartTime > 2000) {
                        newState = "DISTRACTED"
                        distractionStartTime = now - 2000
                    }
                } else {
                    distractionStartTime = 0L
                }
                
                // 3. Expressions (Immediate)
                if (newState == "Neutral") {
                    if (browInnerUpScore > 0.5) {
                        newState = "Confused ?"
                    } else if (noseSneerScore > 0.5) {
                        newState = "Discomfort/Wince"
                    } else if (cheekPuffScore > 0.4) {
                        newState = "Frustrated (Cheek Puff)"
                    } else if (eyeSquintScore > 1.0 && smileScore < 0.2) { // Squinting without smiling
                        newState = "Skeptical -_-"
                    } else if (smileScore > 0.5) {
                        newState = "Happy :)"
                    } else if (browDownScore > 0.5) {
                        newState = "Angry >:("
                    } else if (frownScore > 0.5) {
                        newState = "Sad :("
                    } else if (jawOpenScore > 0.45) { // Raised threshold for Surprise
                        newState = "Surprised :O"
                    } else if (eyeBlinkLeftScore > 0.5 && eyeBlinkRightScore < 0.2) {
                        newState = "Winking (Left)"
                    } else if (eyeBlinkRightScore > 0.5 && eyeBlinkLeftScore < 0.2) {
                        newState = "Winking (Right)"
                    }
                }
                
                currentMood = newState
                if (newState == "DROWSY WARNING" || newState == "DISTRACTED") {
                    // Force immediate update if critical
                     Log.w("GestureProcessor", "Critical State: $newState")
                }
            }
        }
    }

    private fun processResult(result: GestureRecognizerResult?, luminance: Int) {
        val currentTime = System.currentTimeMillis()
        
        // Demo Mode Bypass: Always active if isDemoMode
        if (!isDemoMode && !isSleeping && (currentTime - lastWakeTime > WAKE_DURATION_MS)) {
            isSleeping = true
            wakeHoldStartTime = 0L 
        } else if (isDemoMode) {
            isSleeping = false // Force awake in demo mode
        }

        val isNightMode = luminance < 80
        val activeThreshold = if (isNightMode) 0.4f else 0.6f

        result?.let {
            if (it.gestures().isNotEmpty()) {
                val topGesture = it.gestures().first().first()
                val score = topGesture.score()
                var category = topGesture.categoryName() // Mutable for override
                val landmarks = it.landmarks()[0]
                var positionHint = "Centered"
                var inDriverZone = true
                var isStatic = false
                
                if (landmarks.isNotEmpty()) {
                    val wrist = landmarks[0]
                    val x = wrist.x()
                    
                    // Logic for Pointing Down
                    val indexTip = landmarks[8]
                    val indexMcp = landmarks[5]
                    val middleTip = landmarks[12]
                    val middleMcp = landmarks[9]
                    val ringTip = landmarks[16]
                    val ringMcp = landmarks[13]
                    val pinkyTip = landmarks[20]
                    val pinkyMcp = landmarks[17]
                    
                    // Define State Booleans (Restored)
                    val isMiddleUp = middleTip.y() < middleMcp.y()
                    val isRingUp = ringTip.y() < ringMcp.y()
                    val isPinkyUp = pinkyTip.y() < pinkyMcp.y()

                    // --- ADVANCED DIRECTIONAL LOGIC ---
                    // ROBUST SCALE: Calculate Palm Size (Wrist to Index MCP) to normalize distances
                    val palmDx = indexMcp.x() - wrist.x()
                    val palmDy = indexMcp.y() - wrist.y()
                    val palmSize = Math.sqrt((palmDx*palmDx + palmDy*palmDy).toDouble())
                    
                    // Extension Threshold: Finger must be nearly as long as the palm to be "Extended"
                    // STRICTURE: Increased to 1.0 * PalmSize to filter out loose fists (Thumb Up false positives)
                    val extensionThreshold = palmSize * 1.0 
                    
                    // Calculate vectors for Index and Middle fingers
                    val idxDx = indexTip.x() - indexMcp.x()
                    val idxDy = indexTip.y() - indexMcp.y()
                    val midDx = middleTip.x() - middleMcp.x()
                    val midDy = middleTip.y() - middleMcp.y()
                    
                    val idxLen = Math.sqrt((idxDx*idxDx + idxDy*idxDy).toDouble())
                    val midLen = Math.sqrt((midDx*midDx + midDy*midDy).toDouble())
                    
                    val isIdxExt = idxLen > extensionThreshold
                    val isMidExt = midLen > extensionThreshold
                    
                    // Thumb Check: Is Thumb Up? (Tip significantly above IP/MCP)
                    // If MediaPipe says "Thumb_Up", we should respect it unless Index/Middle are CLEARLY pointing.
                    // But in a Fist (Thumb Up), Index/Middle are folded (Short).
                    // So just the `isIdxExt` check with high threshold should fix the "Thumb Up" false positive.
                    
                    if (isIdxExt && isMidExt && !isRingUp && !isPinkyUp) {
                        // Both extended, others likely folded
                        
                        // Check Orientation
                        // Dynamic Threshold: 40% of palm size is a robust movement indicator
                        val directionThreshold = palmSize * 0.4
                        
                        if (Math.abs(idxDx) > Math.abs(idxDy)) {
                            // Horizontal
                            if (idxDx > directionThreshold) { 
                                // dx > 0 means pointing to Image Right (User's Left if mirrored)
                                Log.d("GestureProcessor", "Override: Two_Fingers_Left")
                                category = "Two_Fingers_Left"
                            } else if (idxDx < -directionThreshold) {
                                // dx < 0 means pointing to Image Left (User's Right if mirrored)
                                Log.d("GestureProcessor", "Override: Two_Fingers_Right")
                                category = "Two_Fingers_Right"
                            }
                        } else {
                            // Vertical
                            if (idxDy < -directionThreshold) { // Up (Y decreases upwards)
                                Log.d("GestureProcessor", "Override: Two_Fingers_Up")
                                category = "Two_Fingers_Up"
                            }
                             // Removed Pointing_Down from Two Fingers logic to avoid confusion. Single finger Down is handled below.
                        }
                    } else if (indexTip.y() > indexMcp.y() + 0.05 && !isMiddleUp && isIdxExt) {
                         // Single finger pointing down fallback (Only if extended!)
                         category = "Pointing_Down"
                    }
                    
                    handHistory.add(Pair(currentTime, x))
                    if (handHistory.size > HISTORY_SIZE) handHistory.removeFirst()
                    
                    // Static Filter Logic ... (existing) 
                    if (!isDemoMode && handHistory.size >= 5) { 
                        val first = handHistory.first()
                        val last = handHistory.last()
                        val dx = Math.abs(last.second - first.second)
                        if (dx < 0.02) isStatic = true 
                    }
                    
                    // SWIPE DETECTION
                    // Calculate velocity over the last few frames
                    if (handHistory.size >= 5) {
                        val first = handHistory.first()
                        val last = handHistory.last()
                        val duration = (last.first - first.first) / 1000.0 // seconds
                        val distance = last.second - first.second // positive = Left to Right (in MP coords, x increases Left->Right)
                        // Note: Camera is mirrored typically. 
                        // x: 0 (Left) -> 1 (Right). 
                        // Move Hand Right -> x increases -> Velocity > 0.
                        
                        val velocity = if (duration > 0) distance / duration else 0.0
                        
                        // Lowered threshold to 0.5 (from 1.5) to make swipes easier to detect
                        if (Math.abs(velocity) > 0.5) { // Threshold for Swipe
                            // If moving fast, override category to Swipe
                            // To prevent spamming, we might need a debounce or "consumed" flag, 
                            // but CommandDispatcher has debounce.
                            if (velocity > 0) category = "Swipe_Right"
                            else category = "Swipe_Left"
                        }
                    }

                    if (!isDemoMode && x > 0.6) { // Disable ROI in Demo
                         inDriverZone = false
                         positionHint = "passenger zone ignored"
                    } else {
                        if (x < 0.3) positionHint = "Move Right >"
                        else if (x > 0.5) positionHint = "< Move Left"
                    }
                    
                    val thumbTip = landmarks[4]
                    val distance = Math.sqrt(
                        Math.pow((thumbTip.x() - indexTip.x()).toDouble(), 2.0) +
                        Math.pow((thumbTip.y() - indexTip.y()).toDouble(), 2.0)
                    )
                     if (distance < 0.05 && inDriverZone) {
                         if (score > activeThreshold) {
                            listener(GestureFeedback("Pinch", score, "Privacy Toggled", positionHint, isNightMode, isSleeping, isDemoMode, currentMood, driverName, passengerPresent))
                            lastWakeTime = currentTime 
                            return@let 
                        }
                    }
                }
                
                // Sleep Logic (Skipped in Demo Mode)
                if (isSleeping) {
                    if (inDriverZone && category == "Open_Palm" && score > activeThreshold) {
                        if (wakeHoldStartTime == 0L) wakeHoldStartTime = currentTime
                        
                        if (currentTime - wakeHoldStartTime > 1000) { 
                            isSleeping = false
                            lastWakeTime = currentTime
                            listener(GestureFeedback("WAKE UP", score, "System Active", positionHint, isNightMode, false, isDemoMode, currentMood, driverName, passengerPresent))
                        } else {
                            listener(GestureFeedback("Possible Wake", score, "Hold to Wake...", positionHint, isNightMode, true, isDemoMode, currentMood, driverName, passengerPresent))
                        }
                    } else {
                        wakeHoldStartTime = 0L 
                        if (inDriverZone) {
                             listener(GestureFeedback("SLEEPING", score, "Show Palm to Wake", positionHint, isNightMode, true, isDemoMode, currentMood, driverName, passengerPresent))
                        }
                    }
                    return@let 
                }
                
                lastWakeTime = currentTime 
                
                if (!inDriverZone) {
                    listener(GestureFeedback("None", 0f, "Ignored (Passenger)", positionHint, isNightMode, isSleeping, isDemoMode, currentMood, driverName, passengerPresent))
                    return@let
                }
                
                if (isStatic) {
                     listener(GestureFeedback("None", score, "Ignored (Static/Wheel)", positionHint, isNightMode, isSleeping, isDemoMode, currentMood, driverName, passengerPresent))
                     return@let
                }

                val feedbackMsg = if (score > 0.8) "Excellent" else "Good"

                if (score > activeThreshold) {
                    listener(GestureFeedback(category, score, feedbackMsg, positionHint, isNightMode, isSleeping, isDemoMode, currentMood, driverName, passengerPresent))
                }
            } else {
                 wakeHoldStartTime = 0L
                 // Send update even if no hand, so Mood is updated!
                 listener(GestureFeedback("None", 0f, " ", "No Hand", isNightMode, isSleeping, isDemoMode, currentMood, driverName, passengerPresent))
            }
        }
    }
    
    // Driver ID Logic
    private val registeredDrivers = mutableMapOf<String, List<Float>>()
    private var pendingRegistrationName: String = "Guest"
    private var registerNextFace = false

    fun registerDriver(name: String) {
        pendingRegistrationName = name
        registerNextFace = true
    }

    private fun extractFaceSignature(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): List<Float> {
        // Simple Euclidean distances between key points
        fun dist(i1: Int, i2: Int): Float {
            if (i1 >= landmarks.size || i2 >= landmarks.size) return 0f
            val p1 = landmarks[i1]
            val p2 = landmarks[i2]
            return Math.sqrt(Math.pow((p1.x() - p2.x()).toDouble(), 2.0) + Math.pow((p1.y() - p2.y()).toDouble(), 2.0)).toFloat()
        }

        val eyeDist = dist(33, 263)
        if (eyeDist < 0.01f) return emptyList() // Avoid div by zero

        // Normalized Ratios - More features for better accuracy
        val v1 = dist(1, 152) / eyeDist   // Nose to chin
        val v2 = dist(1, 33) / eyeDist    // Nose to left eye
        val v3 = dist(1, 263) / eyeDist   // Nose to right eye
        val v4 = dist(61, 291) / eyeDist  // Mouth width
        val v5 = dist(10, 152) / eyeDist  // Forehead to chin
        val v6 = dist(234, 454) / eyeDist // Face width at cheeks
        
        return listOf(v1, v2, v3, v4, v5, v6)
    }

    private fun compareSignatures(sig1: List<Float>, sig2: List<Float>): Float {
        if (sig1.size != sig2.size) return 100f
        var diff = 0f
        for (i in sig1.indices) {
            diff += Math.abs(sig1[i] - sig2[i])
        }
        return diff
    }
    
    fun close() {
        gestureRecognizer?.close()
        faceLandmarker?.close()
    }
}
