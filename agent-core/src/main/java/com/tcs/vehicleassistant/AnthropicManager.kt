package com.tcs.vehicleassistant

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
    private const val TAG = "AnthropicManager"
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val CLAUDE_MODEL = "claude-3-5-sonnet-20241022"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    var apiKey: String = ""

    fun resetConversation() {
        MemoryManager.clearMemory()
    }

    suspend fun sendMessageAsync(systemPrompt: String, userMessage: String, callback: CloudMessageCallback) {
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty() || apiKey == "sk-ant-...") {
                callback.onError(IllegalStateException("No Anthropic API key configured. Add one in settings."))
                return@withContext
            }

            val jsonBody = JSONObject().apply {
                put("model", CLAUDE_MODEL)
                put("max_tokens", 1024)
                put("system", systemPrompt)
                put("messages", JSONArray(CloudRequestBuilder.anthropicMessages(userMessage)))
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                    setRequestProperty("content-type", "application/json")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                }

                connection.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    // Surfaced as an error rather than assistant text, so a failed request is not
                    // spoken to the driver as though it were an answer.
                    callback.onError(java.io.IOException("Anthropic API returned $responseCode: $body"))
                    return@withContext
                }

                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val assistantText = JSONObject(responseString)
                    .optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()

                if (assistantText.isNotEmpty()) callback.onMessage(assistantText)
                callback.onDone()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Anthropic request failed", e)
                callback.onError(e)
            } finally {
                connection?.disconnect()
            }
        }
    }
}
