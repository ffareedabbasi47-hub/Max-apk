package com.example.data.api.providers

import com.example.system.LocalLLMManager

/**
 * Wraps the on-device LocalLLMManager so it can slot into MultiBrainManager's
 * provider chain exactly like Gemini/OpenAI/Claude — same interface, but
 * needs no API key and no internet connection.
 */
class LocalLLMProvider(
    private val localLLMManager: LocalLLMManager
) : AIProvider {

    override val type: ProviderType = ProviderType.OFFLINE
    override val name: String = "Local On-Device Model"

    override fun isConfigured(): Boolean = localLLMManager.isModelReady()

    override suspend fun generateResponse(prompt: String, systemPrompt: String, apiKey: String): String? {
        if (!localLLMManager.isModelReady()) return null
        // Local models generally do better with the instruction folded
        // directly into the prompt rather than a separate system message.
        val combinedPrompt = "$systemPrompt\n\nUser: $prompt\nMAX:"
        return localLLMManager.generateResponse(combinedPrompt)
    }
}
