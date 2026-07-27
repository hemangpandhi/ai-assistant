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

    fun startCamera(context: Context, lifecycleOwner: LifecycleOwner) {
        cacheDir = context.cacheDir
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Setup FaceLandmarker
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
            
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE) // Using IMAGE mode for simpler sync processing
            .setNumFaces(5) // Support up to 5 people in the cabin
            .setOutputFaceBlendshapes(true)
            .build()
            
        try {
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceLandmarker", e)
        }

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    context.display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val rotation = display?.rotation ?: android.view.Surface.ROTATION_0

                // Set up ImageAnalysis
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    processImageProxy(imageProxy)
                }

                // Use Front Camera for the driver
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
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
        
        // Hardcode rotation to 270 degrees because the tablet's sensor is mounted sideways
        // when placed in landscape mode on the dashboard.
        val rotation = 270f
        Log.d(TAG, "Raw Image: ${image.width}x${image.height}, applying 270 degree rotation")
        
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

    fun stopCamera() {
        cameraExecutor?.shutdown()
        faceLandmarker?.close()
    }
}
