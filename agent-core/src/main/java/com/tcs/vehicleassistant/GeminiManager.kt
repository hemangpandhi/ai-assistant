package com.tcs.vehicleassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object GeminiManager {
    private const val CLOUD_MODEL = "gemini-2.5-flash"
    var apiKey: String = ""
    

    
    fun resetConversation() {
        MemoryManager.clearMemory()
    }

    suspend fun sendMessageAsync(systemPrompt: String, userMessage: String, callback: CloudMessageCallback) {
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty() || apiKey == "Enter API Key") {
                callback.onMessage("Error: Please enter a valid Gemini API Key in the settings.")
                callback.onDone()
                return@withContext
            }



            val jsonBody = JSONObject().apply {
                put("contents", JSONArray(MemoryManager.getGeminiHistory()))
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 1024)
                })
            }

            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$CLOUD_MODEL:streamGenerateContent?alt=sse&key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                connection.outputStream.use { os ->
                    val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                
                if (responseCode in 200..299) {
                    val reader = stream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("data: ")) {
                            val dataStr = line!!.substring(6).trim()
                            if (dataStr.isNotEmpty() && dataStr != "[DONE]") {
                                try {
                                    val jsonResponse = JSONObject(dataStr)
                                    val candidates = jsonResponse.optJSONArray("candidates")
                                    if (candidates != null && candidates.length() > 0) {
                                        val content = candidates.getJSONObject(0).optJSONObject("content")
                                        if (content != null) {
                                            val parts = content.optJSONArray("parts")
                                            if (parts != null && parts.length() > 0) {
                                                val text = parts.getJSONObject(0).optString("text", "")
                                                if (text.isNotEmpty()) {
                                                    callback.onMessage(text)
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("GeminiManager", "Error parsing chunk: $dataStr", e)
                                }
                            }
                        }
                    }
                    callback.onDone()
                } else {
                    val responseString = stream.bufferedReader().use { it.readText() }
                    callback.onMessage("API Error: $responseCode - $responseString")
                    callback.onDone()
                }
            } catch (e: Exception) {
                callback.onMessage("Network Error: ${e.message}")
                callback.onDone()
            }
        }
    }
}
