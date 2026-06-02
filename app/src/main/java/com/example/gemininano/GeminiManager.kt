package com.example.gemininano

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object GeminiManager {
    private const val CLOUD_MODEL = "gemini-1.5-flash"
    var apiKey: String = ""
    
    private val conversationHistory = mutableListOf<JSONObject>()
    
    fun resetConversation() {
        conversationHistory.clear()
    }

    suspend fun sendMessageAsync(systemPrompt: String, userMessage: String, callback: CloudMessageCallback) {
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty() || apiKey == "Enter API Key") {
                callback.onMessage("Error: Please enter a valid Gemini API Key in the settings.")
                callback.onDone()
                return@withContext
            }

            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            })

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray(conversationHistory))
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 1024)
                })
            }

            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$CLOUD_MODEL:generateContent?key=$apiKey")
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
                
                val responseString = stream.bufferedReader().use { it.readText() }
                
                if (responseCode in 200..299) {
                    val jsonResponse = JSONObject(responseString)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).getJSONObject("content")
                        val parts = content.getJSONArray("parts")
                        val assistantText = parts.getJSONObject(0).getString("text")
                        
                        conversationHistory.add(JSONObject().apply {
                            put("role", "model")
                            put("parts", JSONArray().put(JSONObject().put("text", assistantText)))
                        })
                        
                        callback.onMessage(assistantText)
                    } else {
                        callback.onMessage("Error: Empty response from Gemini API.")
                    }
                    callback.onDone()
                } else {
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
