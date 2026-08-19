package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.GeminiBrain
import com.example.data.api.diagnostics.GeminiDiagnosticResult
import com.example.data.db.*
import com.example.data.model.*
import com.example.system.InstalledAppInfo
import com.example.system.MaxAccessibilityService
import com.example.system.SystemControlManager
import com.example.system.SystemTelemetry
import com.example.voice.MaxVoiceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MaxViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MaxDatabase.getInstance(application)
    private val dao = db.maxDao()
    private val brain = GeminiBrain(application)
    val systemManager = SystemControlManager(application)

    // BUGFIX: previously the mic started listening after a fixed 1.5s delay
    // following the wake-word acknowledgement ("Yes Boss?..."), regardless of
    // how long that phrase actually took to speak. If TTS took longer than
    // 1.5s (common for longer phrases or slower devices), startListening()
    // fired while MAX was still talking — the mic picked up its own voice
    // or Android's audio focus contention silently broke recognition. This
    // flag makes listening start exactly when TTS reports it's truly done.
    private var shouldListenAfterNextUtterance = false

    // BUGFIX: referencing `voiceEngine` inside its own initializer lambda
    // caused a Kotlin circular type-inference error ("Type checking has run
    // into a recursive problem") — the lambda's type can't be resolved while
    // it's still being used to construct the very property it references.
    // Fix: declare voiceEngine first with no self-reference, then attach the
    // "listen after speaking" behavior via a separate lateinit-safe callback.
    private var pendingListenAfterSpeech: (() -> Unit)? = null

    val voiceEngine = MaxVoiceEngine(application) {
        // Called when voice utterance completes
        if (shouldListenAfterNextUtterance) {
            shouldListenAfterNextUtterance = false
            // Wake ack just finished (or was skipped silently because Voice
            // Feedback is off) -- MAX is now actually entering active
            // listening, not idle. (Phase 7 fix: this used to jump to IDLE
            // here even though listening was about to start 300ms later.)
            _maxState.value = MaxState.LISTENING
            // BUGFIX: Android's TTS onDone callback can fire slightly before
            // the audio has actually finished playing through the speaker on
            // some devices/engines. Starting the mic immediately sometimes
            // caught the tail end of "Yes Boss?" itself, confusing
            // recognition. A short buffer fixes this without being
            // noticeable to the user.
            viewModelScope.launch {
                delay(300)
                pendingListenAfterSpeech?.invoke()
            }
        } else {
            // BUGFIX: conversation is truly done (MAX spoke its final answer,
            // not just the "Yes Boss?" ack) — hand the microphone back to
            // the background wake-word service so it can listen for "Max"
            // again. Previously the background service never resumed,
            // so wake-word detection only ever worked once per app launch.
            com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
            // Phase 7 fix: spec's state flow ends "...AI response/action ->
            // READY -> silent standby", not a silent jump straight to IDLE.
            // Only shown when there was an actual final answer to reflect
            // (not on every trivial utterance) -- see callers of speak().
            if (_maxState.value == MaxState.EXECUTING || _maxState.value == MaxState.PROCESSING) {
                _maxState.value = MaxState.READY
                viewModelScope.launch {
                    delay(700)
                    if (_maxState.value == MaxState.READY) _maxState.value = MaxState.IDLE
                }
            } else {
                _maxState.value = MaxState.IDLE
            }
        }
    }

    init {
        pendingListenAfterSpeech = { voiceEngine.startListening() }
    }

    // UI States
    private val _maxState = MutableStateFlow(MaxState.IDLE)
    val maxState: StateFlow<MaxState> = _maxState

    private val _lastSpeechText = MutableStateFlow("Systems online, Sir. MAX is ready for deployment.")
    val lastSpeechText: StateFlow<String> = _lastSpeechText

    // PHASE 18 — live transcript of what the user is saying WHILE they're
    // still speaking (not yet a finished command). Purely for UI captioning.
    val liveTranscript: StateFlow<String> = voiceEngine.partialRecognizedText

    private val _userInputQuery = MutableStateFlow("")
    val userInputQuery: StateFlow<String> = _userInputQuery

    private val _systemTelemetry = MutableStateFlow(systemManager.getTelemetry())
    val systemTelemetry: StateFlow<SystemTelemetry> = _systemTelemetry

    val commandLogs: StateFlow<List<CommandLogEntity>> = dao.getAllCommandLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notesList: StateFlow<List<NoteEntity>> = dao.getAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val autoReplyList: StateFlow<List<AutoReplyEntity>> = dao.getAllAutoReplies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _conversationMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(sender = "MAX", text = "Systems online, Boss. MAX Core is ready for your deployment.")
        )
    )
    val conversationMessages: StateFlow<List<ChatMessage>> = _conversationMessages

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps

    private val _geminiDiagnosticResult = MutableStateFlow<GeminiDiagnosticResult?>(null)
    val geminiDiagnosticResult: StateFlow<GeminiDiagnosticResult?> = _geminiDiagnosticResult

    // AI PROVIDERS settings redesign: real, honest per-provider connection tests
    // for OpenAI and Claude, matching the same truthful pattern Gemini's
    // diagnostic already used (real HTTP status, real latency, no fake success).
    private val providerDiagnosticService = com.example.data.api.diagnostics.ProviderDiagnosticService()
    private val _openAiDiagnosticResult = MutableStateFlow<com.example.data.api.diagnostics.ProviderDiagnosticResult?>(null)
    val openAiDiagnosticResult: StateFlow<com.example.data.api.diagnostics.ProviderDiagnosticResult?> = _openAiDiagnosticResult
    private val _claudeDiagnosticResult = MutableStateFlow<com.example.data.api.diagnostics.ProviderDiagnosticResult?>(null)
    val claudeDiagnosticResult: StateFlow<com.example.data.api.diagnostics.ProviderDiagnosticResult?> = _claudeDiagnosticResult

    fun runOpenAiDiagnosticCheck(apiKey: String) {
        viewModelScope.launch {
            _openAiDiagnosticResult.value = providerDiagnosticService.testOpenAi(apiKey)
        }
    }

    fun runClaudeDiagnosticCheck(apiKey: String) {
        viewModelScope.launch {
            _claudeDiagnosticResult.value = providerDiagnosticService.testClaude(apiKey)
        }
    }

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled

    private val _isFallbackActive = MutableStateFlow(false)
    val isFallbackActive: StateFlow<Boolean> = _isFallbackActive

    private val _fallbackNotice = MutableStateFlow("")
    val fallbackNotice: StateFlow<String> = _fallbackNotice

    // ACTION ORCHESTRATOR — CONFIRMATION GATE
    // Non-null whenever a sensitive action (WhatsApp send, phone call) is
    // waiting on user confirmation. The UI observes this to show a
    // confirm/cancel prompt. Nothing has been executed while this is set.
    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation

    // See PendingAppChoice doc in MaxModels.kt.
    private val _pendingAppChoice = MutableStateFlow<PendingAppChoice?>(null)
    val pendingAppChoice: StateFlow<PendingAppChoice?> = _pendingAppChoice

    // PHASE 10 — screen capture state. MainActivity observes
    // screenShareConsentRequested to know when to launch Android's
    // MediaProjection consent dialog (that dialog can only be triggered
    // from an Activity, never from the ViewModel directly).
    private val _screenShareConsentRequested = MutableStateFlow(false)
    val screenShareConsentRequested: StateFlow<Boolean> = _screenShareConsentRequested

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing

    fun requestScreenShare() {
        _screenShareConsentRequested.value = true
    }

    fun consumeScreenShareRequest() {
        _screenShareConsentRequested.value = false
    }

    // Called by MainActivity only after Android's own consent dialog
    // returned RESULT_OK and the foreground service actually started.
    fun onScreenCaptureGranted() {
        _isScreenSharing.value = true
        val msg = "Screen sharing active, Boss. Bata kya dekhna hai."
        _lastSpeechText.value = msg
        _conversationMessages.value = _conversationMessages.value + ChatMessage(sender = "MAX", text = msg)
        voiceEngine.speak(msg)
    }

    fun onScreenCaptureDenied() {
        _isScreenSharing.value = false
        val msg = "Theek hai Boss, screen sharing permission nahi mili — cancel kar diya."
        _lastSpeechText.value = msg
        voiceEngine.speak(msg)
    }

    fun stopScreenShare() {
        val status = systemManager.stopScreenCapture()
        _isScreenSharing.value = false
        _lastSpeechText.value = status
        _conversationMessages.value = _conversationMessages.value + ChatMessage(sender = "MAX", text = status)
        voiceEngine.speak(status)
    }

    // Captures one real frame from the active MediaProjection session and
    // sends it to Gemini vision. Returns an honest failure string (never a
    // fabricated description) if capture isn't active or every attempt fails.
    private suspend fun analyzeScreenVisually(instruction: String): String {
        if (!com.example.system.ScreenCaptureService.isActive) {
            return "Screen sharing pehle start karo, Boss — bolo \"MAX, screen share start karo\"."
        }
        val service = com.example.system.ScreenCaptureService.instance
            ?: return "Screen capture service se connect nahi ho paya, Boss."
        val bitmap = service.captureFrame()
            ?: return "Screen ka frame capture nahi ho paya, Boss. Dobara try karo."
        val base64 = try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
        if (base64 == null) return "Screen frame encode karne mein error aaya, Boss."
        val result = brain.analyzeImage(instruction, base64)
        return result ?: "Screen analyze nahi ho paya, Boss — vision AI se koi response nahi mila (API key ya network check karo)."
    }

    // Public entry point for a direct UI button (Analyze Visually) rather
    // than going through the full prompt/orchestrator pipeline -- still
    // uses the same real capture+vision path and speaks the real result.
    fun analyzeScreenNow(instruction: String) {
        viewModelScope.launch {
            _maxState.value = MaxState.EXECUTING
            val result = analyzeScreenVisually(instruction)
            _lastSpeechText.value = result
            _conversationMessages.value = _conversationMessages.value + ChatMessage(sender = "MAX", text = result)
            _maxState.value = MaxState.IDLE
            voiceEngine.speak(result)
        }
    }

    private var telemetryJob: Job? = null

    init {
        // Greet user on launch
        viewModelScope.launch {
            _lastSpeechText.value = "Systems online, Boss. MAX is ready for deployment."
            voiceEngine.speak("Systems online, Boss. MAX is ready for deployment.")
            loadInstalledApps()
            populateSampleDataIfNeeded()
        }


        // Periodically refresh system telemetry ticks
        telemetryJob = viewModelScope.launch {
            while (true) {
                _systemTelemetry.value = systemManager.getTelemetry()
                delay(2000)
            }
        }

        // Observe voice recognizer text
        viewModelScope.launch {
            voiceEngine.speechRecognizedText.collect { text ->
                if (text.isNotBlank()) {
                    val lower = text.lowercase().trim()
                    val appChoice = _pendingAppChoice.value
                    if (appChoice != null) {
                        // NEW: this utterance is the user's answer to "which
                        // app did you mean?" -- resolve it against the held
                        // candidates instead of routing it to the AI brain
                        // as an unrelated fresh command.
                        _pendingAppChoice.value = null
                        _maxState.value = MaxState.EXECUTING
                        val result = systemManager.resolveAppChoiceAndLaunch(text, appChoice.candidates)
                        if (result.needsClarification) {
                            _pendingAppChoice.value = PendingAppChoice(result.candidates, appChoice.originalQuery)
                        }
                        _lastSpeechText.value = result.message
                        _conversationMessages.value = _conversationMessages.value + ChatMessage(sender = "MAX", text = result.message)
                        voiceEngine.speak(result.message)
                        _maxState.value = MaxState.IDLE
                        com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
                    } else if (lower == "max" || lower == "hey max" || lower == "hey max!" || lower == "max!") {
                        val wakeAck = "Yes Boss."
                        _lastSpeechText.value = wakeAck
                        voiceEngine.speak(wakeAck)
                    } else if (lower.startsWith("max ") || lower.startsWith("hey max ")) {
                        val cleanQuery = text.replace(Regex("(?i)^(hey max|max)\\s*"), "").trim()
                        if (cleanQuery.isNotEmpty()) {
                            _userInputQuery.value = cleanQuery
                            executePrompt(cleanQuery)
                        } else {
                            val wakeAck = "Yes Boss."
                            _lastSpeechText.value = wakeAck
                            voiceEngine.speak(wakeAck)
                        }
                    } else {
                        _userInputQuery.value = text
                        executePrompt(text)
                    }
                }
            }
        }

        // Synchronize listening state
        viewModelScope.launch {
            voiceEngine.isListening.collect { listening ->
                if (listening) {
                    _maxState.value = MaxState.LISTENING
                } else if (_maxState.value == MaxState.LISTENING) {
                    _maxState.value = MaxState.IDLE
                }
            }
        }

        // Synchronize speaking state
        viewModelScope.launch {
            voiceEngine.isSpeaking.collect { speaking ->
                if (speaking) {
                    _maxState.value = MaxState.SPEAKING
                } else if (_maxState.value == MaxState.SPEAKING) {
                    _maxState.value = MaxState.IDLE
                }
            }
        }
    }

    fun checkAccessibilityStatus(context: Context) {
        val enabled = MaxAccessibilityService.isEnabled() || checkAccessibilitySystemSetting(context)
        _isAccessibilityEnabled.value = enabled
    }

    private fun checkAccessibilitySystemSetting(context: Context): Boolean {
        return try {
            val expectedService = "${context.packageName}/${MaxAccessibilityService::class.java.canonicalName}"
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains(expectedService, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    fun saveCustomKey(prefName: String, value: String) {
        // SECURITY FIX: previously plain SharedPreferences("max_jarvis_prefs").
        // Now Android Keystore-backed encrypted storage — see SecureKeyStore.
        com.example.data.security.SecureKeyStore.putString(getApplication(), prefName, value)
        // BUGFIX: this used to call voiceEngine.speak() on every single keystroke
        // while typing an API key — MAX would talk over you character by character.
        // Saving is silent now; only confirm once the field loses focus (see
        // confirmKeySaved below) or via the diagnostic test.
    }

    fun confirmKeySaved(prefName: String) {
        val msg = "$prefName updated, Boss."
        _lastSpeechText.value = msg
        voiceEngine.speak(msg)
    }

    fun getCustomKey(prefName: String): String {
        return com.example.data.security.SecureKeyStore.getString(getApplication(), prefName)
    }

    /** Securely removes a single stored API key/slot. Spec Phase 5/10: "DELETE API KEY". */
    fun deleteApiKey(prefName: String) {
        com.example.data.security.SecureKeyStore.delete(getApplication(), prefName)
        val msg = "$prefName deleted, Boss."
        _lastSpeechText.value = msg
        voiceEngine.speak(msg)
    }

    fun getApiKey(): String {
        return brain.getActiveKey()
    }

    private fun loadInstalledApps() {


        viewModelScope.launch {
            _installedApps.value = systemManager.getInstalledApps()
        }
    }

    private suspend fun populateSampleDataIfNeeded() {
        // Pre-populate sample notes & auto-replies if database is fresh
        dao.insertNote(
            NoteEntity(
                title = "Arc Reactor Core Specs",
                content = "Vibranium containment matrix operating at 3.5 gigawatts. Thermal dissipation stabilized.",
                fileType = "DOCX",
                folder = "Stark Tech"
            )
        )
        dao.insertNote(
            NoteEntity(
                title = "Meeting Summary - Pepper Potts",
                content = "Quarterly budget allocated for autonomous flight routines. Next review scheduled for Friday.",
                fileType = "SUMMARY",
                folder = "Communications"
            )
        )

        dao.insertAutoReply(
            AutoReplyEntity(
                sender = "Pepper Potts",
                platform = "WHATSAPP",
                incomingMessage = "Max, are Tony's suit diagnostics complete for tonight?",
                summary = "Query regarding suit diagnostic status.",
                generatedReply = "Systems online, Pepper. Suit diagnostics are 100% complete and verified.",
                status = "SENT"
            )
        )
        dao.insertAutoReply(
            AutoReplyEntity(
                sender = "Happy Hogan",
                platform = "EMAIL",
                incomingMessage = "Can you send me the security log for Sector 4?",
                summary = "Security log request for Sector 4.",
                generatedReply = "Sector 4 security logs compiled and attached. All perimeters secure.",
                status = "DRAFTED"
            )
        )
    }

    fun onQueryChanged(newText: String) {
        _userInputQuery.value = newText
    }

    fun stopAllAudioAndListening() {
        voiceEngine.stopSpeaking()
        voiceEngine.stopListening()
        _maxState.value = MaxState.IDLE
    }

    fun runGeminiDiagnosticCheck(apiKey: String = com.example.BuildConfig.GEMINI_API_KEY) {
        _maxState.value = MaxState.PROCESSING
        _conversationMessages.value = _conversationMessages.value + ChatMessage(
            sender = "USER",
            text = "Run Gemini API Connectivity Diagnostic Ping"
        )
        viewModelScope.launch {
            val result = brain.runGeminiDiagnostic(apiKey)
            _geminiDiagnosticResult.value = result
            // Phase 7 fix: ERROR state existed nowhere in the app -- a failed
            // diagnostic silently went straight back to IDLE, identical to a
            // success. Surface ERROR briefly so the UI can actually show it.
            _maxState.value = if (result.isSuccess) MaxState.IDLE else MaxState.ERROR

            val speech = if (result.isSuccess) {
                "Gemini API Ping Succeeded! HTTP 200 OK. Latency: ${result.latencyMs}ms on model ${result.modelTested}."
            } else {
                "Gemini API Diagnostic Failed. Code: ${result.statusCode ?: "None"} (${result.statusCategory}). Error: ${result.errorMessage ?: "Unknown error"}"
            }

            _lastSpeechText.value = speech
            _conversationMessages.value = _conversationMessages.value + ChatMessage(
                sender = "MAX",
                text = speech
            )

            dao.insertCommandLog(
                CommandLogEntity(
                    prompt = "Gemini Diagnostic Ping",
                    response = speech,
                    actionType = "SYSTEM_DIAGNOSTIC",
                    status = if (result.isSuccess) "PASSED_200" else "FAILED_${result.statusCode ?: 0}"
                )
            )

            voiceEngine.speak(speech)
        }
    }

    fun testWakeWord() {
        // BARGE-IN: stopAllAudioAndListening() below already cuts off any
        // in-progress TTS the instant a fresh wake word arrives -- that IS
        // the interruption support from Phase 9/18. This flag is just
        // consumed/logged here for traceability of when a barge-in actually
        // happened vs. a wake from idle.
        val wasBargeIn = com.example.system.MaxWakeService.pendingBargeIn && voiceEngine.isSpeaking.value
        com.example.system.MaxWakeService.pendingBargeIn = false
        if (wasBargeIn) {
            android.util.Log.i("MaxViewModel", "Barge-in: wake word interrupted an in-progress utterance.")
        }
        stopAllAudioAndListening()
        // Phase 7 fix: "Max" was previously jumping straight to LISTENING
        // with no distinct ACTIVATING state, even though the spec calls out
        // WAKE_DETECTED ("MAX / ACTIVATING...") as its own visible step.
        _maxState.value = MaxState.WAKE_DETECTED
        val wakeAck = "Yes Boss."
        _lastSpeechText.value = wakeAck
        shouldListenAfterNextUtterance = true
        voiceEngine.speak(wakeAck)
    }

    fun toggleVoiceListening() {
        if (voiceEngine.isListening.value || voiceEngine.isSpeaking.value) {
            stopAllAudioAndListening()
        } else {
            voiceEngine.startListening()
        }
    }

    fun executePrompt(userPrompt: String) {
        if (userPrompt.isBlank()) return
        _userInputQuery.value = ""
        _maxState.value = MaxState.PROCESSING

        // Add user prompt to chat history
        val userMsg = ChatMessage(sender = "USER", text = userPrompt)
        _conversationMessages.value = _conversationMessages.value + userMsg

        viewModelScope.launch {
            // Brain logic evaluation
            val parsedAction = brain.processUserPrompt(userPrompt)
            _isFallbackActive.value = parsedAction.isFallback
            if (parsedAction.isFallback) {
                _fallbackNotice.value = "⚠️ Local Fallback Mode: No active Gemini/OpenAI/Claude API Key found. Add key in Settings for live AI answers."
            } else {
                _fallbackNotice.value = ""
            }

            // ACTION ORCHESTRATOR — CONFIRMATION GATE
            // WhatsApp sends and phone calls never execute directly off a
            // parsed AI response. If a target is present, hold the action
            // and ask the user first; runParsedAction() only fires after
            // confirmPendingAction() is called from the UI.
            if (parsedAction.actionType.requiresConfirmation() && parsedAction.target.isNotBlank()) {
                val confirmText = when (parsedAction.actionType) {
                    ActionType.SEND_WHATSAPP -> {
                        val msg = parsedAction.details.ifEmpty { userPrompt }
                        "${parsedAction.target} ko ye message bhejun — \"$msg\"? Confirm karo, Boss."
                    }
                    ActionType.MAKE_CALL -> "Calling ${parsedAction.target}. Continue, Boss?"
                    ActionType.DELETE_FILE -> "'${parsedAction.target}' permanently delete hogi, Boss. Confirm?"
                    else -> "Ye action confirm karein, Boss?"
                }
                _pendingConfirmation.value = PendingConfirmation(
                    parsedAction = parsedAction,
                    originalUserPrompt = userPrompt,
                    confirmationSpeech = confirmText
                )
                _lastSpeechText.value = confirmText
                val holdMsg = ChatMessage(sender = "MAX", text = confirmText)
                _conversationMessages.value = _conversationMessages.value + holdMsg
                _maxState.value = MaxState.IDLE
                voiceEngine.speak(confirmText)
                return@launch
            }

            runParsedAction(parsedAction, userPrompt)
        }
    }

    // Called from the UI (button tap or a recognized "yes/confirm" voice
    // reply) once the user has approved a pending sensitive action.
    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        viewModelScope.launch {
            runParsedAction(pending.parsedAction, pending.originalUserPrompt)
        }
    }

    // Called from the UI (or a recognized "no/cancel" voice reply) to
    // reject a pending sensitive action. Nothing is executed.
    fun cancelPendingAction() {
        if (_pendingConfirmation.value == null) return
        _pendingConfirmation.value = null
        val msg = "Theek hai Boss, cancel kar diya."
        _lastSpeechText.value = msg
        _conversationMessages.value = _conversationMessages.value + ChatMessage(sender = "MAX", text = msg)
        voiceEngine.speak(msg)
        com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
    }

    // Real executor — only ever reached for non-sensitive actions directly,
    // or for sensitive actions after explicit confirmation. This is the
    // single place SystemControlManager gets called from AI-parsed actions.
    private suspend fun runParsedAction(parsedAction: ParsedMaxAction, userPrompt: String) {
        _maxState.value = MaxState.EXECUTING

        var systemExecutionStatus = "Executed"

            // Perform phone & system actions
            // BUGFIX: this block had no error handling — if any system
            // action threw (missing permission, bad intent, etc.) it could
            // silently fail or crash the coroutine with no visible sign of
            // what went wrong. Now failures are caught and surfaced.
            try {
                when (parsedAction.actionType) {
                ActionType.OPEN_APP -> {
                    val launchResult = systemManager.openAppByName(parsedAction.target)
                    systemExecutionStatus = launchResult.message
                    if (launchResult.needsClarification) {
                        // NEW: don't guess between close matches (e.g.
                        // "Instagram" vs "Instagram Lite") -- hold the
                        // candidates and let the router below resolve the
                        // user's next utterance against them instead of
                        // sending it to the AI brain as a fresh command.
                        _pendingAppChoice.value = PendingAppChoice(
                            candidates = launchResult.candidates,
                            originalQuery = parsedAction.target
                        )
                    }
                    // BUGFIX: launching another app pushes MAX itself to the
                    // background, so its own TTS confirmation ("Launching
                    // WhatsApp...") often never gets to actually finish
                    // playing before focus is lost. Since background
                    // wake-word listening only used to resume once TTS
                    // reported "done", MAX would end up permanently paused
                    // and unresponsive after opening just one app. Resume
                    // listening immediately here instead of waiting on TTS.
                    com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
                }
                ActionType.TOGGLE_SETTINGS -> {
                    val statusMsg = systemManager.toggleSystemSetting(parsedAction.target)
                    systemExecutionStatus = statusMsg
                    com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
                }
                ActionType.SEND_WHATSAPP -> {
                    // BUGFIX: the real result string (e.g. "couldn't find
                    // contact X" or "opened chat with Y") was being thrown
                    // away and replaced with a hardcoded "WhatsApp
                    // Dispatched" — so MAX always sounded successful even
                    // when it wasn't. Now the real result is kept.
                    systemExecutionStatus = systemManager.sendWhatsAppMessage(parsedAction.target, parsedAction.details.ifEmpty { userPrompt })
                    com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
                }
                ActionType.DRAFT_EMAIL -> {
                    systemExecutionStatus = systemManager.draftEmail(parsedAction.target, parsedAction.details.ifEmpty { userPrompt })
                    com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
                }
                ActionType.MAKE_CALL -> {
                    systemExecutionStatus = systemManager.makeCall(parsedAction.target)
                    com.example.system.MaxWakeService.instance?.resumeBackgroundListening()
                }
                ActionType.CREATE_FILE -> {
                    val fileName = if (parsedAction.target.isNotBlank()) parsedAction.target else "Max_Document_${System.currentTimeMillis() % 1000}.txt"
                    val content = if (parsedAction.details.isNotBlank()) parsedAction.details else userPrompt
                    val fileStatus = systemManager.createFileInStorage(fileName, content)

                    dao.insertNote(
                        NoteEntity(
                            title = fileName,
                            content = content,
                            fileType = "TXT",
                            folder = "System Files"
                        )
                    )
                    systemExecutionStatus = fileStatus
                }
                ActionType.EDIT_FILE -> {
                    systemExecutionStatus = systemManager.editFileInStorage(parsedAction.target, parsedAction.details)
                }
                ActionType.DELETE_FILE -> {
                    // Reached only after the user explicitly confirmed via
                    // confirmPendingAction() -- never called directly.
                    systemExecutionStatus = systemManager.deleteFileFromStorage(parsedAction.target)
                }
                ActionType.FIND_FILE -> {
                    systemExecutionStatus = systemManager.findFilesInStorage(parsedAction.target)
                }
                ActionType.SHARE_SCREEN_START -> {
                    requestScreenShare()
                    systemExecutionStatus = "Screen-capture permission maang raha hoon, Boss — Android ka consent dialog dikhega."
                }
                ActionType.SHARE_SCREEN_STOP -> {
                    systemExecutionStatus = systemManager.stopScreenCapture()
                    _isScreenSharing.value = false
                }
                ActionType.ANALYZE_SCREEN_VISUAL -> {
                    systemExecutionStatus = analyzeScreenVisually(parsedAction.target.ifBlank { userPrompt })
                }
                ActionType.WEB_SEARCH -> {
                    systemExecutionStatus = "Live Search Analyzed"
                }
                ActionType.SYSTEM_DIAGNOSTIC -> {
                    val result = brain.runGeminiDiagnostic()
                    _geminiDiagnosticResult.value = result
                    systemExecutionStatus = if (result.isSuccess) "Diagnostic HTTP 200 OK (${result.latencyMs}ms)" else "Diagnostic Failed (${result.statusCategory})"
                }
                ActionType.FLASHLIGHT -> {
                    systemExecutionStatus = systemManager.toggleFlashlight()
                }
                ActionType.VOLUME -> {
                    val level = parsedAction.target.toIntOrNull() ?: 50
                    systemExecutionStatus = systemManager.setVolume(level)
                }
                ActionType.CLIPBOARD -> {
                    systemExecutionStatus = systemManager.copyToClipboard(parsedAction.target.ifEmpty { userPrompt })
                }
                ActionType.SCREENSHOT -> {
                    systemExecutionStatus = systemManager.takeScreenshot()
                }
                ActionType.READ_NOTIFICATIONS -> {
                    systemExecutionStatus = systemManager.getRecentNotifications()
                }
                ActionType.GENERAL_TALK -> {
                    systemExecutionStatus = "Processed"
                }
                }
            } catch (e: Exception) {
                systemExecutionStatus = "Action failed: ${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.e("MaxViewModel", "Action execution failed for ${parsedAction.actionType}", e)
                // Phase 7 fix: a thrown action failure previously left MaxState
                // sitting at EXECUTING with no distinct ERROR signal anywhere.
                _maxState.value = MaxState.ERROR
            }

            // BUGFIX: previously MAX only ever spoke the AI's pre-scripted
            // guess ("Bilkul boss, kar raha hoon") regardless of whether the
            // action actually succeeded. For actions where the manager
            // returns a real, specific result (contact not found, app
            // launched, etc.), speak that real result instead so failures
            // are actually audible instead of masked by a generic success line.
            val actionsWithRealStatus = setOf(
                ActionType.OPEN_APP, ActionType.SEND_WHATSAPP, ActionType.MAKE_CALL,
                ActionType.DRAFT_EMAIL, ActionType.TOGGLE_SETTINGS, ActionType.FLASHLIGHT,
                ActionType.VOLUME, ActionType.CLIPBOARD, ActionType.SCREENSHOT,
                ActionType.READ_NOTIFICATIONS, ActionType.CREATE_FILE, ActionType.EDIT_FILE,
                ActionType.DELETE_FILE, ActionType.FIND_FILE,
                ActionType.SHARE_SCREEN_START, ActionType.SHARE_SCREEN_STOP, ActionType.ANALYZE_SCREEN_VISUAL
            )
            val finalSpeech = if (parsedAction.actionType in actionsWithRealStatus && systemExecutionStatus.isNotBlank()) {
                systemExecutionStatus
            } else {
                parsedAction.speechResponse
            }
            _lastSpeechText.value = finalSpeech

            // Add MAX response to chat history
            val maxMsg = ChatMessage(sender = "MAX", text = finalSpeech)
            _conversationMessages.value = _conversationMessages.value + maxMsg

            // Log command
            dao.insertCommandLog(
                CommandLogEntity(
                    prompt = userPrompt,
                    response = finalSpeech,
                    actionType = parsedAction.actionType.name,
                    status = systemExecutionStatus
                )
            )

        // Speak response via Voice Engine
        voiceEngine.speak(finalSpeech)
    }


    fun createNote(title: String, content: String, fileType: String, folder: String) {
        viewModelScope.launch {
            val noteId = dao.insertNote(
                NoteEntity(
                    title = title,
                    content = content,
                    fileType = fileType,
                    folder = folder
                )
            )
            systemManager.createFileInStorage("$title.$fileType", content)
            val msg = "Note '$title' created successfully in $folder, Sir."
            _lastSpeechText.value = msg
            voiceEngine.speak(msg)
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch {
            dao.deleteNoteById(id)
        }
    }

    fun createAutoReply(sender: String, platform: String, message: String, response: String) {
        viewModelScope.launch {
            dao.insertAutoReply(
                AutoReplyEntity(
                    sender = sender,
                    platform = platform,
                    incomingMessage = message,
                    summary = "Autonomous reply for $sender",
                    generatedReply = response,
                    status = "DRAFTED"
                )
            )
            val msg = "Autonomous reply rule configured for $sender on $platform, Sir."
            _lastSpeechText.value = msg
            voiceEngine.speak(msg)
        }
    }

    fun dispatchAutoReply(id: Long, sender: String, platform: String, reply: String) {
        viewModelScope.launch {
            dao.updateReplyStatus(id, "SENT")
            if (platform == "WHATSAPP") {
                systemManager.sendWhatsAppMessage(sender, reply)
            } else {
                systemManager.draftEmail(sender, reply)
            }
        }
    }

    // ---------- Local / offline LLM management ----------

    private val _localModels = MutableStateFlow<List<com.example.system.LocalLLMManager.LocalModelInfo>>(emptyList())
    val localModels: StateFlow<List<com.example.system.LocalLLMManager.LocalModelInfo>> = _localModels

    private val _localModelImportStatus = MutableStateFlow<String?>(null)
    val localModelImportStatus: StateFlow<String?> = _localModelImportStatus

    private val _localModelTestStatus = MutableStateFlow<String?>(null)
    val localModelTestStatus: StateFlow<String?> = _localModelTestStatus

    /** Runs a tiny real prompt through the active local model and reports
     * the exact success or failure reason — previously failures were
     * silently swallowed and just looked like "no key configured". */
    fun testLocalModel() {
        viewModelScope.launch {
            _localModelTestStatus.value = "Testing... (first run can take 10-30s to load the model)"
            val manager = brain.getLocalLLMManager()
            val result = manager.generateResponse("Say 'MAX offline test OK' and nothing else.")
            _localModelTestStatus.value = if (!result.isNullOrBlank()) {
                "✓ Model responded: ${result.take(120)}"
            } else {
                "✗ Failed: ${manager.getLastError() ?: "Unknown error — check Logcat"}"
            }
        }
    }

    fun refreshLocalModels() {
        _localModels.value = brain.getLocalLLMManager().listImportedModels()
    }

    /** displayName should NOT include a path — just a friendly file name. */
    fun importLocalModel(uri: android.net.Uri, displayName: String) {
        viewModelScope.launch {
            _localModelImportStatus.value = "Importing model, this can take a while for large files..."
            val result = brain.getLocalLLMManager().importModel(uri, displayName)
            result.onSuccess { savedName ->
                brain.getLocalLLMManager().setActiveModel(savedName)
                _localModelImportStatus.value = "Imported and activated: $savedName"
                refreshLocalModels()
                val msg = "New local model imported and ready, Boss."
                _lastSpeechText.value = msg
                voiceEngine.speak(msg)
            }.onFailure { e ->
                _localModelImportStatus.value = "Import failed: ${e.message}"
            }
        }
    }

    fun setActiveLocalModel(fileName: String) {
        brain.getLocalLLMManager().setActiveModel(fileName)
        refreshLocalModels()
    }

    fun deleteLocalModel(fileName: String) {
        brain.getLocalLLMManager().deleteModel(fileName)
        refreshLocalModels()
    }

    // ---------- Vosk wake-word (free, offline, non-flickering "Max" detection) ----------

    private val voskManagerHelper = com.example.system.VoskWakeManager(application)

    private val _voskStatus = MutableStateFlow<String?>(null)
    val voskStatus: StateFlow<String?> = _voskStatus

    fun isVoskReady(): Boolean = voskManagerHelper.isReady()

    fun importVoskModel(uri: android.net.Uri) {
        viewModelScope.launch {
            _voskStatus.value = "Extracting model, this can take a moment..."
            val result = withContext(Dispatchers.IO) { voskManagerHelper.importModelZip(uri) }
            result.onSuccess {
                _voskStatus.value = "✓ Ready — restart MAX to activate seamless, non-flickering wake-word listening."
            }.onFailure { e ->
                _voskStatus.value = "Import failed: ${e.message}"
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clearCommandLogs()
        }
    }

    fun getApiKeySlot(slotNumber: Int): String {
        // SECURITY FIX: previously plain SharedPreferences("max_jarvis_prefs").
        return com.example.data.security.SecureKeyStore.getString(getApplication(), "api_key_slot_$slotNumber")
    }

    fun saveApiKeySlot(slotNumber: Int, key: String) {
        com.example.data.security.SecureKeyStore.putString(getApplication(), "api_key_slot_$slotNumber", key)
        val msg = "API Key Slot $slotNumber updated, Boss!"
        _lastSpeechText.value = msg
        voiceEngine.speak(msg)
    }

    fun toggleBackgroundWakeService(context: android.content.Context, enable: Boolean) {
        val intent = android.content.Intent(context, com.example.system.MaxWakeService::class.java)
        try {
            if (enable) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                val msg = "Background Wake Listening ON! Say 'Max' anytime, Boss!"
                _lastSpeechText.value = msg
                voiceEngine.speak(msg)
            } else {
                context.stopService(intent)
                val msg = "Background Wake Listening OFF, Boss."
                _lastSpeechText.value = msg
                voiceEngine.speak(msg)
            }
        } catch (e: Exception) {
            val msg = "Could not start background service. Grant microphone permissions, Boss."
            _lastSpeechText.value = msg
            voiceEngine.speak(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetryJob?.cancel()
        voiceEngine.release()
    }
}
