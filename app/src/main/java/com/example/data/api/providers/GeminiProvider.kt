package com.example.data.api.providers

import com.example.data.model.GeminiContent
import com.example.data.model.GeminiPart
import com.example.data.model.GeminiRequest
import com.example.data.model.GeminiResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiProvider(
    private val client: OkHttpClient
) : AIProvider {

    override val type: ProviderType = ProviderType.GEMINI
    override val name: String = "Google Gemini AI"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)
    private val responseAdapter = moshi.adapter(GeminiResponse::class.java)

    override fun isConfigured(): Boolean = true

    override suspend fun generateResponse(prompt: String, systemPrompt: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank() || cleanKey == "MY_GEMINI_API_KEY" || cleanKey == "FALLBACK_KEY_VALID" || cleanKey == "NON_EXISTENT_KEY") {
            return@withContext null
        }

        // BUGFIX: the previous list contained model IDs that don't exist on the
        // Gemini API ("gemini-3.5-flash", "gemini-2.5-flash-preview-12-2025").
        // Every call to those returned HTTP 404, the exception was swallowed below,
        // and MultiBrainManager silently fell back to canned responses — this was
        // the main reason Q&A looked "broken" even with a valid API key.
        // Only real, currently-available model IDs below, most capable first.
        val modelsToTry = listOf(
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-pro"
        )

        for (model in modelsToTry) {
            try {
                val geminiRequest = GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
                    systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt)))
                )

                val jsonBody = requestAdapter.toJson(geminiRequest)
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = jsonBody.toRequestBody(mediaType)

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                val request = Request.Builder().url(url).post(body).build()

                val response = client.newCall(request).execute()
                val responseStr = response.body?.string()

                if (response.isSuccessful && responseStr != null) {
                    val geminiResponse = responseAdapter.fromJson(responseStr)
                    val text = geminiResponse?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (!text.isNullOrBlank()) {
                        return@withContext text.trim()
                    }
                } else {
                    // BUGFIX: log failures instead of silently swallowing them, so a
                    // bad API key / quota error / model mismatch is actually visible
                    // in Logcat instead of just falling through to the fallback.
                    android.util.Log.w(
                        "GeminiProvider",
                        "Model '$model' failed: HTTP ${response.code} - ${responseStr?.take(300)}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("GeminiProvider", "Model '$model' threw: ${e.message}")
            }
        }
        return@withContext null
    }
}

