package com.example.data.model

import com.squareup.moshi.JsonClass

enum class MaxState {
    IDLE,
    WAKE_DETECTED,
    LISTENING,
    PROCESSING,
    SPEAKING,
    EXECUTING,
    READY,
    ERROR
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String, // "USER" or "MAX"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ActionType {

    OPEN_APP,
    TOGGLE_SETTINGS,
    SEND_WHATSAPP,
    DRAFT_EMAIL,
    MAKE_CALL,
    CREATE_FILE,
    EDIT_FILE,
    DELETE_FILE,
    FIND_FILE,
    WEB_SEARCH,
    SYSTEM_DIAGNOSTIC,
    FLASHLIGHT,
    VOLUME,
    CLIPBOARD,
    SCREENSHOT,
    READ_NOTIFICATIONS,
    SHARE_SCREEN_START,
    SHARE_SCREEN_STOP,
    ANALYZE_SCREEN_VISUAL,
    GENERAL_TALK
}

data class ParsedMaxAction(
    val actionType: ActionType,
    val target: String = "",
    val details: String = "",
    val speechResponse: String,
    val isFallback: Boolean = false
)

// ============================================================
// ACTION ORCHESTRATOR — CONFIRMATION LAYER
// ============================================================
// Sensitive/irreversible actions (sending a real WhatsApp message,
// placing a real phone call, deleting a file) must never fire
// straight from a parsed AI response. They go through this gate
// first: MAX asks a yes/no question, holds the action, and only
// calls the real executor once the user (or an explicitly enabled
// trusted-automation setting) confirms. See ActionType.requiresConfirmation().
fun ActionType.requiresConfirmation(): Boolean = when (this) {
    ActionType.SEND_WHATSAPP,
    ActionType.MAKE_CALL,
    ActionType.DELETE_FILE -> true
    else -> false
}

// Holds an action that is waiting on user confirmation before it is
// allowed to reach SystemControlManager. Nothing in this object has
// been executed yet — it is purely a proposal.
data class PendingConfirmation(
    val parsedAction: ParsedMaxAction,
    val originalUserPrompt: String,
    val confirmationSpeech: String
)

// APP DISAMBIGUATION: when SystemControlManager.openAppByName() finds two or
// more installed apps that are too close a match to pick safely (e.g.
// "Instagram" vs "Instagram Lite"), it returns these candidates instead of
// guessing. MaxViewModel holds this until the user's next utterance names one.
data class PendingAppChoice(
    val candidates: List<String>,
    val originalQuery: String
)

// Gemini API REST Request & Response Models for Moshi
@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

// PHASE 10 — screen-vision support: an image frame encoded as base64,
// attached alongside a text prompt in the same GeminiContent.parts list.
// Field names (inlineData/mimeType/data) match Gemini's proto-JSON mapping.
@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    val temperature: Float? = 0.4f,
    val topP: Float? = 0.95f,
    val topK: Int? = 40
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent? = null
)
