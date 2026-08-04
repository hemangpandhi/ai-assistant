package com.tcs.vehicleassistant.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object GeminiManager {
    private const val TAG = "GeminiManager"
    private const val CLOUD_MODEL = "gemini-2.5-flash"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    var apiKey: String = ""

    fun resetConversation() {
        // Cloud conversational state is usually handled per-request via history array
    }

    suspend fun sendMessageAsync(systemPrompt: String, userMessage: String, callback: CloudMessageCallback) {
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty() || apiKey == "Enter API Key") {
                callback.onError(IllegalStateException("No Gemini API key configured. Add one in settings."))
                return@withContext
            }

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray(CloudRequestBuilder.geminiContents(userMessage)))
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 1024)
                })
            }

            var connection: HttpURLConnection? = null
            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/$CLOUD_MODEL:streamGenerateContent?alt=sse"
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    // Header rather than a query parameter, so the key cannot leak into access
                    // logs, proxy traces, or crash reports that capture request URLs.
                    setRequestProperty("x-goog-api-key", apiKey)
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
                    // Reported as an error rather than assistant text; the previous version fed
                    // "API Error: 401 ..." into the response stream, so the assistant spoke the
                    // failure aloud as if it were an answer.
                    callback.onError(java.io.IOException("Gemini API returned $responseCode: $body"))
                    return@withContext
                }

                connection.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data: ")) continue
                        val dataStr = line.removePrefix("data: ").trim()
                        if (dataStr.isEmpty() || dataStr == "[DONE]") continue
                        try {
                            val text = JSONObject(dataStr)
                                .optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                .orEmpty()
                            if (text.isNotEmpty()) callback.onMessage(text)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Error parsing SSE chunk: $dataStr", e)
                        }
                    }
                }
                callback.onDone()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Gemini request failed", e)
                callback.onError(e)
            } finally {
                connection?.disconnect()
            }
        }
    }
}
