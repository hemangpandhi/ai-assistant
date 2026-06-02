package com.example.gemininano

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

interface CloudMessageCallback {
    fun onMessage(chunk: String)
    fun onDone()
    fun onError(throwable: Throwable)
}

object AnthropicManager {
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val CLAUDE_MODEL = "claude-3-5-sonnet-20241022"
    var apiKey: String = ""
    
    private val conversationHistory = mutableListOf<JSONObject>()
    
    fun resetConversation() {
        conversationHistory.clear()
    }

    suspend fun sendMessageAsync(systemPrompt: String, userMessage: String, callback: CloudMessageCallback) {
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty() || apiKey == "sk-ant-...") {
                callback.onMessage("Error: Please enter a valid Anthropic API Key in the settings.")
                callback.onDone()
                return@withContext
            }

            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })

            val jsonBody = JSONObject().apply {
                put("model", CLAUDE_MODEL)
                put("max_tokens", 1024)
                put("system", systemPrompt)
                put("messages", JSONArray(conversationHistory))
            }

            try {
                val url = URL(API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                connection.setRequestProperty("content-type", "application/json")
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
                    val contentArray = jsonResponse.getJSONArray("content")
                    val assistantText = contentArray.getJSONObject(0).getString("text")
                    
                    conversationHistory.add(JSONObject().apply {
                        put("role", "assistant")
                        put("content", assistantText)
                    })
                    
                    callback.onMessage(assistantText)
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
