package com.example.data.api.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAIProvider(
    private val client: OkHttpClient
) : AIProvider {

    override val type: ProviderType = ProviderType.OPENAI
    override val name: String = "OpenAI GPT-4o Mini"

    override fun isConfigured(): Boolean = true

    override suspend fun generateResponse(prompt: String, systemPrompt: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !apiKey.startsWith("sk-")) {
            return@withContext null
        }

        try {
            // BUGFIX: "gpt-4o-mini" is a retired-from-ChatGPT, legacy-tier model as
            // of this audit -- current low-cost default is gpt-5-mini. Centralized
            // in ProviderDiagnosticService so the diagnostic ping and real chat
            // calls can never drift apart the way Gemini's model IDs once did.
            val json = JSONObject().apply {
                put("model", com.example.data.api.diagnostics.ProviderDiagnosticService.OPENAI_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 300)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string()

            if (response.isSuccessful && responseStr != null) {
                val resObj = JSONObject(responseStr)
                val choices = resObj.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    return@withContext message?.optString("content")
                }
            }
        } catch (e: Exception) {
            // BUGFIX: silently swallowing this hid real failures (bad key, quota,
            // retired model) behind an unexplained fallback to the next provider.
            android.util.Log.w("OpenAIProvider", "generateResponse failed: ${e.message}")
        }
        return@withContext null
    }
}
