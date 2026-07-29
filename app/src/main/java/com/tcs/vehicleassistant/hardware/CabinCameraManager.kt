package com.tcs.vehicleassistant.hardware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CabinCameraManager {
    private const val TAG = "CabinCameraManager"
    private var faceLandmarker: FaceLandmarker? = null
    private var cameraExecutor: ExecutorService? = null

    // Callbacks for live view
    var frameCallback: ((Bitmap) -> Unit)? = null

    // Exposed state
    var currentMood: String = "Neutral"
    var occupantCount: Int = 0
    
    private var cacheDir: java.io.File? = null

    /** Retained so [stopCamera] can unbind the CameraX use cases and actually free the camera. */
    private var boundProvider: ProcessCameraProvider? = null

    fun startCamera(context: Context, lifecycleOwner: LifecycleOwner) {
        cacheDir = context.cacheDir
        if (cameraExecutor == null || cameraExecutor!!.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                boundProvider = cameraProvider
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    processImageProxy(imageProxy)
                }

                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    Log.w(TAG, "No camera found on device")
                    return@addListener
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
                Log.d(TAG, "Native camera bound successfully for Cabin Sense.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind native camera: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                // Pass to callback for UI/Services
                frameCallback?.invoke(bitmap)

                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = faceLandmarker?.detect(mpImage)
                
                if (result != null) {
                    processLandmarks(result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun processLandmarks(result: FaceLandmarkerResult) {
        val blendshapesOptional = result.faceBlendshapes()
        if (blendshapesOptional.isPresent) {
            val faces = blendshapesOptional.get()
            occupantCount = faces.size
            
            if (occupantCount > 0) {
                // Analyze the first face (assumed driver)
                val driverBlendshapes = faces[0]
                
                var smileScore = 0f
                var yawnScore = 0f
                var frownScore = 0f
                
                for (shape in driverBlendshapes) {
                    when (shape.categoryName()) {
                        "mouthSmileLeft", "mouthSmileRight" -> smileScore += shape.score()
                        "jawOpen" -> yawnScore = shape.score()
                        "browDownLeft", "browDownRight" -> frownScore += shape.score()
                    }
                }
                
                val detectedMood = when {
                    yawnScore > 0.4f -> "Tired / Yawning"
                    smileScore > 0.6f -> "Happy / Smiling"
                    frownScore > 0.6f || frownScore > 0.6f -> "Frustrated / Frowning"
                    else -> "Neutral / Focused"
                }
                currentMood = detectedMood
                Log.d(TAG, "Detected $occupantCount occupants. Driver Mood: $currentMood")
            } else {
                currentMood = "No one detected"
            }
        } else {
            occupantCount = 0
            currentMood = "No one detected"
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        val rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        
        // Use dynamic rotation provided by CameraX
        val rotation = image.imageInfo.rotationDegrees.toFloat()
        Log.d(TAG, "Raw Image: ${image.width}x${image.height}, applying $rotation degree rotation")
        
        val matrix = android.graphics.Matrix()
        matrix.postRotate(rotation)
        
        val finalBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        
        if (frameCount++ % 100 == 0 && cacheDir != null) {
            try {
                val file = java.io.File(cacheDir, "camera_debug.jpg")
                val fos = java.io.FileOutputStream(file)
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                fos.close()
                Log.d(TAG, "Saved debug frame to ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save debug frame", e)
            }
        }
        
        return finalBitmap
    }

    private var frameCount = 0

    /**
     * Releases the camera and analysis pipeline.
     *
     * The previous version shut the executor down but never unbound the CameraX use cases, so the
     * camera stayed held by this process after the vision service was destroyed and a later
     * [startCamera] bound a second analyzer on a dead executor.
     */
    fun stopCamera() {
        try {
            boundProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind camera use cases", e)
        }
        boundProvider = null

        cameraExecutor?.shutdown()
        cameraExecutor = null

        try {
            faceLandmarker?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close face landmarker", e)
        }
        faceLandmarker = null

        frameCallback = null
        occupantCount = 0
        currentMood = "Neutral"
    }
}
