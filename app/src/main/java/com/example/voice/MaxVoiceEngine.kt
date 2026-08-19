package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class MaxVoiceEngine(
    private val context: Context,
    private val onUtteranceFinished: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _speechRecognizedText = MutableStateFlow("")
    val speechRecognizedText: StateFlow<String> = _speechRecognizedText

    // PHASE 18 — LIVE CONVERSATION UI / bug fix.
    // BUGFIX: onPartialResults used to write into the SAME StateFlow as
    // onResults (_speechRecognizedText). The ViewModel treats every emission
    // of that flow as a finished command and calls executePrompt() on it --
    // so mid-sentence partial transcripts were firing off real AI calls on
    // incomplete text, repeatedly, while the user was still talking. Partial
    // (in-progress, not yet final) text now goes here instead -- purely for
    // live captioning -- and only a truly final result touches
    // _speechRecognizedText.
    private val _partialRecognizedText = MutableStateFlow("")
    val partialRecognizedText: StateFlow<String> = _partialRecognizedText

    private val _voicePitch = MutableStateFlow(0.95f) // Slightly deeper than default but still natural (0.88 sounded synthetic on some TTS engines)
    private val _voiceRate = MutableStateFlow(0.98f)  // Natural, slightly deliberate cadence

    private val _selectedLanguage = MutableStateFlow("AUTO") // "hi_IN", "en_IN", "en_US", "AUTO"
    val selectedLanguage: StateFlow<String> = _selectedLanguage

    init {
        tts = TextToSpeech(context, this)
        initSpeechRecognizer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.apply {
                applySelectedLanguage()
                setPitch(_voicePitch.value)
                setSpeechRate(_voiceRate.value)
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        onUtteranceFinished()
                    }

                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
            }
        }
    }

    fun setLanguagePreference(langCode: String) {
        _selectedLanguage.value = langCode
        applySelectedLanguage()
    }

    private fun applySelectedLanguage() {
        val ttsEngine = tts ?: return
        val targetLocale = when (_selectedLanguage.value) {
            "hi_IN" -> Locale.forLanguageTag("hi-IN")
            "en_IN" -> Locale.forLanguageTag("en-IN")
            "en_US" -> Locale.US
            else -> { // AUTO mode: Prefer hi_IN if installed, fallback to en_IN then Locale.US
                val hiLocale = Locale.forLanguageTag("hi-IN")
                val hiRes = try { ttsEngine.isLanguageAvailable(hiLocale) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                if (hiRes >= TextToSpeech.LANG_AVAILABLE) {
                    hiLocale
                } else {
                    val enInLocale = Locale.forLanguageTag("en-IN")
                    val enInRes = try { ttsEngine.isLanguageAvailable(enInLocale) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                    if (enInRes >= TextToSpeech.LANG_AVAILABLE) {
                        enInLocale
                    } else {
                        Locale.US
                    }
                }
            }
        }

        try {
            val result = ttsEngine.setLanguage(targetLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsEngine.language = Locale.US
            }
        } catch (e: Exception) {
            try { ttsEngine.language = Locale.US } catch (ex: Exception) {
                android.util.Log.w("MaxVoiceEngine", "TTS language fallback to US failed: ${ex.message}")
            }
        }

        // BUGFIX / QUALITY FIX: setLanguage() alone often keeps whatever low-quality
        // default voice was already selected. Actively pick the best-quality voice
        // available for the resolved locale (e.g. an HD/network voice on Google TTS)
        // so MAX doesn't sound robotic.
        try {
            val bestVoice = ttsEngine.voices
                ?.filter { it.locale.language == targetLocale.language && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }
            if (bestVoice != null) {
                ttsEngine.voice = bestVoice
            }
        } catch (e: Exception) {
            // Some OEM TTS engines don't support voice enumeration — safe to ignore,
            // the locale-based setLanguage() above still applies.
        }
    }

    fun setVoiceParams(pitch: Float, rate: Float) {
        _voicePitch.value = pitch
        _voiceRate.value = rate
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
    }

    fun speak(text: String) {
        if (text.isBlank()) return

        // PHASE 8 FIX (Silent Operation): Voice Feedback is OFF by default
        // and was previously not wired to anything -- MAX spoke every reply
        // unconditionally. When the user has it OFF, skip actual TTS output
        // but still resolve the same state/callback TTS would have produced
        // (isSpeaking -> false, onUtteranceFinished()) so downstream logic
        // that waits for "the utterance is done" (e.g. starting to listen
        // right after the silent wake acknowledgement) still proceeds
        // correctly -- just silently, per "activate silently... enter
        // active listening" in the spec.
        if (!com.example.data.settings.MaxPreferences.isVoiceFeedbackEnabled(context)) {
            stopListening()
            _isSpeaking.value = false
            onUtteranceFinished()
            return
        }

        stopListening()
        _isSpeaking.value = true
        tts?.stop() // Flush previous audio immediately
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MAX_UTTERANCE_${System.currentTimeMillis()}")
    }


    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            // ignore
        }
        _isSpeaking.value = false
    }


    private fun initSpeechRecognizer() {
        try {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                try {
                    if (SpeechRecognizer.isRecognitionAvailable(context)) {
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                            setRecognitionListener(object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) {
                                    _isListening.value = true
                                }

                                override fun onBeginningOfSpeech() {}
                                override fun onRmsChanged(rmsdB: Float) {}
                                override fun onBufferReceived(buffer: ByteArray?) {}

                                override fun onEndOfSpeech() {
                                    _isListening.value = false
                                }

                                override fun onError(error: Int) {
                                    _isListening.value = false
                                }

                                override fun onResults(results: Bundle?) {
                                    _isListening.value = false
                                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    if (!matches.isNullOrEmpty()) {
                                        _partialRecognizedText.value = ""
                                        _speechRecognizedText.value = matches[0]
                                    }
                                }

                                override fun onPartialResults(partialResults: Bundle?) {
                                    // Live captioning only -- NOT routed to command
                                    // execution. See _partialRecognizedText doc above.
                                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    if (!matches.isNullOrEmpty()) {
                                        _partialRecognizedText.value = matches[0]
                                    }
                                }

                                override fun onEvent(eventType: Int, params: Bundle?) {}
                            })
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MaxVoiceEngine", "Failed to create SpeechRecognizer: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MaxVoiceEngine", "initSpeechRecognizer failed: ${e.message}", e)
        }
    }

    fun startListening() {
        stopSpeaking()
        _speechRecognizedText.value = ""
        _partialRecognizedText.value = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // BUGFIX: must be a language tag String (e.g. "hi-IN"), not a Locale object,
            // or the recognizer silently ignores it.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "MAX is listening, Sir...")
        }
        try {
            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            _isListening.value = false
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            // ignore
        }
        _isListening.value = false
    }

    // BUGFIX (idempotent release): called with no guard, this crashed if
    // invoked twice (e.g. ViewModel.onCleared() racing a config change) --
    // TextToSpeech.shutdown() and SpeechRecognizer.destroy() both throw if
    // called on an already-released instance. Now safe to call any number
    // of times, and references are cleared so nothing after this point can
    // touch a destroyed engine.
    fun release() {
        try { tts?.stop() } catch (e: Exception) { /* already stopped/destroyed */ }
        try { tts?.shutdown() } catch (e: Exception) { /* already shut down */ }
        tts = null
        try { speechRecognizer?.destroy() } catch (e: Exception) { /* already destroyed */ }
        speechRecognizer = null
    }
}
