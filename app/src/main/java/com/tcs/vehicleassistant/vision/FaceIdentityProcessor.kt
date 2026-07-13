package com.tcs.vehicleassistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class FaceIdentityProcessor(private val context: Context) {
    private var interpreter: Interpreter? = null
    
    // MobileFaceNet typically takes 112x112 RGB images
    private val INPUT_IMAGE_SIZE = 112
    private val EMBEDDING_SIZE = 128

    init {
        try {
            val modelBuffer = loadModelFile(context, "mobilefacenet.tflite")
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d("FaceIdentity", "Successfully loaded FaceNet model")
        } catch (e: Exception) {
            Log.e("FaceIdentity", "Failed to load FaceNet model: ${e.message}")
            interpreter = null
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Extracts a 128D embedding from the cropped face bitmap.
     * The input bitmap MUST be cropped tightly around the face and sized to 112x112.
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        if (interpreter == null) {
            Log.w("FaceIdentity", "Interpreter not initialized. Returning dummy embedding.")
            return FloatArray(EMBEDDING_SIZE) { 0.1f } // Fallback if model missing
        }

        try {
            val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, true)
            val inputBuffer = convertBitmapToByteBuffer(scaledBitmap)
            val outputBuffer = Array(1) { FloatArray(EMBEDDING_SIZE) }

            interpreter?.run(inputBuffer, outputBuffer)
            
            return normalize(outputBuffer[0])
        } catch (e: Exception) {
            Log.e("FaceIdentity", "Error extracting embedding: ${e.message}")
            return FloatArray(EMBEDDING_SIZE) { 0.1f }
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var pixel = 0
        for (i in 0 until INPUT_IMAGE_SIZE) {
            for (j in 0 until INPUT_IMAGE_SIZE) {
                val value = intValues[pixel++]
                
                // MobileFaceNet preprocessing (normalize to -1 .. 1)
                byteBuffer.putFloat((((value shr 16) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat((((value shr 8) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat(((value and 0xFF) - 127.5f) / 128.0f)
            }
        }
        return byteBuffer
    }

    private fun normalize(embedding: FloatArray): FloatArray {
        var sum = 0f
        for (value in embedding) {
            sum += value * value
        }
        val norm = Math.sqrt(sum.toDouble()).toFloat()
        for (i in embedding.indices) {
            embedding[i] /= norm
        }
        return embedding
    }

    fun computeSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        var dotProduct = 0f
        for (i in emb1.indices) {
            dotProduct += emb1[i] * emb2[i]
        }
        return dotProduct // Cosine similarity since they are normalized
    }
}