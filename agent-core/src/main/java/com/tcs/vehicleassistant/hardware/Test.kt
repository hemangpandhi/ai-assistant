package com.tcs.vehicleassistant.hardware
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
fun test(result: FaceLandmarkerResult) {
    val b = result.faceBlendshapes()
}
