package com.example.data.api.diagnostics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real connection tests for OpenAI and Claude, mirroring GeminiDiagnosticService's
 * honest reporting (real HTTP status codes, real latency, real error text -- never
 * a fabricated "connected"). Used by the redesigned AI PROVIDERS settings section's
 * per-provider [TEST CONNECTION] buttons.
 */
data class ProviderDiagnosticResult(
    val providerName: String,
    val isSuccess: Boolean,
    val statusCode: Int?,
    val statusCategory: String,
    val latencyMs: Long,
    val modelTested: String,
    val errorMessage: String?
)

class ProviderDiagnosticService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "ProviderDiagnostic"
        // Current as of this codebase's last model audit (Aug 2026). If OpenAI or
        // Anthropic ship a new default, update ONLY here -- OpenAIProvider.kt and
        // ClaudeProvider.kt read the same constants, so there's one place to fix.
        const val OPENAI_MODEL = "gpt-5-mini"
        const val CLAUDE_MODEL = "claude-sonnet-5"
    }

    private fun categoryFor(code: Int?): String = when (code) {
        null -> "NETWORK_ERROR"
        200 -> "SUCCESS_200"
        400 -> "INVALID_REQUEST_400"
        401 -> "UNAUTHORIZED_401"
        403 -> "FORBIDDEN_403"
        404 -> "NOT_FOUND_404"
        429 -> "RATE_LIMITED_429"
        in 500..599 -> "SERVER_ERROR_$code"
        else -> "HTTP_ERROR_$code"
    }

    suspend fun testOpenAi(apiKey: String): ProviderDiagnosticResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ProviderDiagnosticResult(
                "OpenAI", false, 401, "UNAUTHORIZED_401", 0, OPENAI_MODEL, "No API key entered."
            )
        }
        val startTime = System.currentTimeMillis()
        try {
            val json = JSONObject().apply {
                put("model", OPENAI_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "ping") })
                })
                put("max_tokens", 5)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $cleanKey")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            val raw = response.body?.string()
            if (response.isSuccessful) {
                ProviderDiagnosticResult("OpenAI", true, response.code, "SUCCESS_200", latency, OPENAI_MODEL, null)
            } else {
                Log.w(TAG, "OpenAI diagnostic failed: HTTP ${response.code} - ${raw?.take(300)}")
                ProviderDiagnosticResult(
                    "OpenAI", false, response.code, categoryFor(response.code), latency, OPENAI_MODEL,
                    "HTTP ${response.code} - ${raw?.take(200)}"
                )
            }
        } catch (e: IOException) {
            ProviderDiagnosticResult("OpenAI", false, null, "NETWORK_ERROR", System.currentTimeMillis() - startTime, OPENAI_MODEL, e.localizedMessage ?: e.message)
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI diagnostic exception", e)
            ProviderDiagnosticResult("OpenAI", false, null, "UNKNOWN_EXCEPTION", System.currentTimeMillis() - startTime, OPENAI_MODEL, e.localizedMessage ?: e.message)
        }
    }

    suspend fun testClaude(apiKey: String): ProviderDiagnosticResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ProviderDiagnosticResult(
                "Claude", false, 401, "UNAUTHORIZED_401", 0, CLAUDE_MODEL, "No API key entered."
            )
        }
        val startTime = System.currentTimeMillis()
        try {
            val json = JSONObject().apply {
                put("model", CLAUDE_MODEL)
                put("max_tokens", 5)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "ping") })
                })
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", cleanKey)
                .header("anthropic-version", "2023-06-01")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            val raw = response.body?.string()
            if (response.isSuccessful) {
                ProviderDiagnosticResult("Claude", true, response.code, "SUCCESS_200", latency, CLAUDE_MODEL, null)
            } else {
                Log.w(TAG, "Claude diagnostic failed: HTTP ${response.code} - ${raw?.take(300)}")
                ProviderDiagnosticResult(
                    "Claude", false, response.code, categoryFor(response.code), latency, CLAUDE_MODEL,
                    "HTTP ${response.code} - ${raw?.take(200)}"
                )
            }
        } catch (e: IOException) {
            ProviderDiagnosticResult("Claude", false, null, "NETWORK_ERROR", System.currentTimeMillis() - startTime, CLAUDE_MODEL, e.localizedMessage ?: e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Claude diagnostic exception", e)
            ProviderDiagnosticResult("Claude", false, null, "UNKNOWN_EXCEPTION", System.currentTimeMillis() - startTime, CLAUDE_MODEL, e.localizedMessage ?: e.message)
        }
    }
}
