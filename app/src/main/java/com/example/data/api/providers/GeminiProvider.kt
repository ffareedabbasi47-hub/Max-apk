package com.example.data.api.providers

import com.example.data.model.GeminiContent
import com.example.data.model.GeminiInlineData
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

        // Model list now lives in ONE place: GeminiModels.kt. See that file
        // for why (previously two different hardcoded lists here and in
        // GeminiDiagnosticService.kt silently drifted out of sync with
        // Google's actual model availability).
        val modelsToTry = com.example.data.api.GeminiModels.TEXT_AND_VISION_MODELS

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

    // PHASE 10 — real screen/image vision analysis. Sends a base64 JPEG
    // frame + an instruction to a vision-capable Gemini model. Returns null
    // (never a fabricated description) if every attempt fails, so the
    // caller reports an honest failure instead of pretending to have "seen"
    // the screen.
    suspend fun generateVisionResponse(
        instruction: String,
        imageBase64: String,
        apiKey: String
    ): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank() || cleanKey == "MY_GEMINI_API_KEY") {
            return@withContext null
        }

        val modelsToTry = com.example.data.api.GeminiModels.TEXT_AND_VISION_MODELS

        for (model in modelsToTry) {
            try {
                val geminiRequest = GeminiRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(text = instruction),
                                GeminiPart(inlineData = GeminiInlineData(mimeType = "image/jpeg", data = imageBase64))
                            )
                        )
                    )
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
                    android.util.Log.w(
                        "GeminiProvider",
                        "Vision call to '$model' failed: HTTP ${response.code} - ${responseStr?.take(300)}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("GeminiProvider", "Vision call to '$model' threw: ${e.message}")
            }
        }
        return@withContext null
    }
}

