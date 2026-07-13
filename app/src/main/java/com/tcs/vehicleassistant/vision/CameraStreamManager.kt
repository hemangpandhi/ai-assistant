package com.tcs.vehicleassistant.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class CameraStreamManager(
    private val onFrame: (Bitmap) -> Unit,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var isRunning = false
    private var streamThread: Thread? = null

    fun startStream(urlStr: String) {
        stop() // Stop any existing stream
        isRunning = true
        
        streamThread = Thread {
            var connection: HttpURLConnection? = null
            var bis: BufferedInputStream? = null
            
            try {
                Log.d("CameraStream", "Attempting connection to $urlStr")
                val url = URL(urlStr)
                connection = url.openConnection() as HttpURLConnection
                connection.readTimeout = 5000
                connection.connectTimeout = 5000
                
                val responseCode = connection.responseCode
                Log.d("CameraStream", "Response Code: $responseCode")
                
                if (responseCode == 200) {
                    onConnected()
                    bis = BufferedInputStream(connection.inputStream)
                    
                    val buffer = ByteArray(1024 * 1024) // 1MB buffer
                    var readIdx = 0
                    var totalRead = 0L
                    
                    while (isRunning) {
                        val read = bis.read(buffer, readIdx, buffer.size - readIdx)
                        if (read == -1) {
                             Log.e("CameraStream", "End of stream")
                             break
                        }
                        readIdx += read
                        totalRead += read
                        
                        if (totalRead % 200000 == 0L) Log.d("CameraStream", "Streaming data... Total: $totalRead")
                    
                        // Search for JPEG SOI (Start of Image) FF D8 and EOI (End of Image) FF D9
                        var frameStart = -1
                        var frameEnd = -1
                        
                        for (i in 0 until readIdx - 1) {
                            if (buffer[i] == 0xFF.toByte() && buffer[i+1] == 0xD8.toByte()) {
                                frameStart = i
                                break
                            }
                        }
                        
                        if (frameStart != -1) {
                            for (i in frameStart until readIdx - 1) {
                                 if (buffer[i] == 0xFF.toByte() && buffer[i+1] == 0xD9.toByte()) {
                                    frameEnd = i + 2
                                    break
                                }
                            }
                        }
                        
                        if (frameStart != -1 && frameEnd != -1) {
                            // Full frame found
                            val length = frameEnd - frameStart
                            try {
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = 4 // Downsample by 4 (e.g. 1920x1080 -> 480x270). severe speedup.
                                }
                                val rawBitmap = BitmapFactory.decodeByteArray(buffer, frameStart, length, options)
                                if (rawBitmap != null) {
                                    val matrix = android.graphics.Matrix()
                                    matrix.postRotate(270f) // Rotation 3 (270 degrees)
                                    val bitmap = android.graphics.Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                    onFrame(bitmap)
                                }
                            } catch (e: Exception) {
                                Log.e("CameraStream", "Decode error: ${e.message}")
                            }
                            
                            // Shift remaining data to start
                            val remaining = readIdx - frameEnd
                            System.arraycopy(buffer, frameEnd, buffer, 0, remaining)
                            readIdx = remaining
                        } else if (readIdx == buffer.size) {
                            // Buffer full but no full frame, resetting
                            readIdx = 0
                            Log.w("CameraStream", "Buffer overflow")
                        }
                    }
                } else {
                    onError("Connection Failed: HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.e("CameraStream", "Stream error: ${e.message}")
                onError(e.message ?: "Connection Error")
            } finally {
                try { bis?.close() } catch (e: Exception) {}
                try { connection?.disconnect() } catch (e: Exception) {}
            }
        }
        streamThread?.start()
    }

    fun stop() {
        isRunning = false
        try {
            streamThread?.join(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        streamThread = null
    }
}
