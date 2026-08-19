package com.example.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import java.util.Locale

class MaxWakeService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListeningServiceRunning = false
    private var isRestartScheduled = false
    // BUGFIX: this service's own background SpeechRecognizer kept running
    // and competing for the microphone even while the foreground UI's
    // separate SpeechRecognizer (MaxVoiceEngine) tried to listen for the
    // user's actual command after "Yes Boss?" — Android only supports one
    // active recognition session at a time, so the second one silently
    // failed. This flag lets the foreground conversation pause background
    // wake-word listening while it's active, then resume it afterward.
    private var isPausedForForegroundConversation = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        // BUGFIX (foreground service failure handling): the old code caught
        // the startForeground() exception with e.printStackTrace() and then
        // carried on regardless -- setting isListeningServiceRunning = true
        // and starting the wake-word listener even if the foreground
        // promotion actually failed (e.g. ForegroundServiceStartNotAllowedException
        // on Android 12+, or a missing/blocked notification channel). That
        // means the UI could show "wake service active" while Android was
        // about to kill the service for not truly being foreground, and any
        // microphone use without a genuine foreground service is also a
        // policy violation on Android 14+. Now: on failure, log the real
        // error, do NOT start listening, and stop the service cleanly so
        // state stays honest.
        val foregroundStarted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() failed -- wake service cannot run: ${e.message}", e)
            false
        }

        if (!foregroundStarted) {
            isListeningServiceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        isListeningServiceRunning = true
        startBackgroundWakeListening()
        return START_STICKY
    }

    private var voskManager: VoskWakeManager? = null

    private fun startBackgroundWakeListening() {
        val vosk = VoskWakeManager(this)
        if (vosk.isReady()) {
            // Preferred path: continuous, non-flickering, fully offline
            // wake-word detection. Model loading can take a couple of
            // seconds, so do it off the main thread.
            Thread {
                val error = vosk.start {
                    mainHandler.post { onWakeWordDetected() }
                }
                if (error == null) {
                    voskManager = vosk
                } else {
                    Log.e(TAG, "Vosk failed to start, falling back to SpeechRecognizer: $error")
                    mainHandler.post { startBackgroundWakeListeningLegacy() }
                }
            }.start()
            return
        }
        // Fallback: old SpeechRecognizer restart-loop approach. Works
        // everywhere with no setup, but the mic visibly toggles on/off every
        // 1-2 seconds since SpeechRecognizer can't listen continuously.
        startBackgroundWakeListeningLegacy()
    }

    private fun startBackgroundWakeListeningLegacy() {
        mainHandler.post {
            try {
                if (speechRecognizer == null && SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                scheduleRestartListening(500)
                            }

                            override fun onError(error: Int) {
                                Log.d(TAG, "Wake listening error code: $error")
                                scheduleRestartListening(1000)
                            }

                            override fun onResults(results: Bundle?) {
                                handleSpeechResults(results)
                                scheduleRestartListening(500)
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                handleSpeechResults(partialResults)
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }
                listenInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting wake recognizer", e)
                scheduleRestartListening(2000)
            }
        }
    }

    private fun listenInternal() {
        if (!isListeningServiceRunning) return
        if (isPausedForForegroundConversation) return
        // BUGFIX: without this check, if RECORD_AUDIO isn't granted yet the service
        // enters an infinite fail/retry loop while never telling anyone why.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted, stopping wake listening until permission is granted")
            stopSelf()
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // BUGFIX: EXTRA_LANGUAGE must be a BCP-47 language tag String, not a Locale
                // object. Passing a Locale silently breaks language selection because
                // recognizer implementations read it with getStringExtra().
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call startListening", e)
            scheduleRestartListening(2000)
        }
    }

    // BUGFIX (word-boundary + duplicate triggers): this had the same flaw as
    // VoskWakeManager's old check -- `text.contains("max")` also matches
    // "maximum", "maximal", "maximizing", etc. Also, onPartialResults and
    // onResults both call this for the same utterance, so a single spoken
    // wake phrase could fire onWakeWordDetected() more than once. Fixed with
    // word-boundary regex (mirrors VoskWakeManager) and the same 2s cooldown.
    private val wakeWordRegex = Regex(
        """\b(hey\s+max|ok\s+max|max|hey\s+jarvis|ok\s+jarvis|jarvis)\b""",
        RegexOption.IGNORE_CASE
    )
    @Volatile
    private var lastLegacyWakeTriggerAtMs: Long = 0L
    private val legacyWakeCooldownMs = 2000L

    private fun handleSpeechResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (match in matches) {
            val text = match.lowercase(Locale.getDefault())
            // Wake words: "max" / "hey max" AND "jarvis" / "hey jarvis", per
            // spec Phase 12. Both route through the same handler -- MAX
            // does not attempt to distinguish which name was used to wake
            // it, since the on-device recognizer here can reliably detect
            // either phrase but not meaningfully differentiate persona
            // based on wake phrase alone.
            if (wakeWordRegex.containsMatchIn(text)) {
                val now = System.currentTimeMillis()
                if (now - lastLegacyWakeTriggerAtMs < legacyWakeCooldownMs) {
                    break
                }
                lastLegacyWakeTriggerAtMs = now
                Log.i(TAG, "Wake word detected in background! Triggering app...")
                onWakeWordDetected()
                break
            }
        }
    }

    private fun onWakeWordDetected() {
        pauseForForegroundConversation()
        // BARGE-IN (Phase 9/18): if MAX is mid-sentence when woken again,
        // stop the current utterance immediately rather than letting it
        // finish -- a fresh wake means the user wants MAX's attention now.
        try {
            MaxWakeService.pendingBargeIn = true
        } catch (e: Exception) {
            // non-fatal; UI layer will just not get the barge-in hint
        }
        // Send Broadcast with explicit package name for RECEIVER_NOT_EXPORTED compatibility
        val broadcastIntent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)

        // NEW (Aug 2026 pivot): "Max" now directly triggers the Google Gemini
        // app, "Hey Google" style, instead of opening MAX's own conversation
        // screen. The background always-listening wake service itself is
        // unchanged -- it's the same VoskWakeManager/legacy-recognizer loop
        // that was already running continuously; only what happens on
        // detection has changed.
        launchGoogleGemini()
    }

    private val geminiPackageName = "com.google.android.apps.bard"

    private fun launchGoogleGemini() {
        // CORRECTED: user wants the "Hey Google"-style Gemini overlay
        // (voice already listening, floats over whatever app is open) --
        // not the full Gemini app opening to its home screen. That overlay
        // is triggered system-wide via Intent.ACTION_ASSIST, which Android
        // routes to whichever app is set as the device's default assistant
        // (Settings > Apps > Default apps > Digital assistant app). If the
        // user has set Gemini as that default, this brings up the same
        // overlay a long-press-home or "Hey Google" would.
        try {
            val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(assistIntent)
        } catch (e: Exception) {
            // No app is registered to handle ACTION_ASSIST (or the OEM
            // blocks it) -- fall back to opening the Gemini app directly
            // rather than doing nothing.
            Log.w(TAG, "ACTION_ASSIST failed, falling back to opening Gemini app directly", e)
            val geminiIntent = packageManager.getLaunchIntentForPackage(geminiPackageName)
            if (geminiIntent != null) {
                geminiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(geminiIntent) } catch (e2: Exception) { Log.e(TAG, "Gemini app launch also failed", e2) }
            } else {
                try {
                    val playStoreIntent = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=$geminiPackageName")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivity(playStoreIntent)
                } catch (e3: Exception) {
                    Log.e(TAG, "Could not open Play Store for Gemini either", e3)
                }
            }
        }
        // Immediately resume background wake listening rather than waiting
        // on any TTS/foreground-conversation signal -- MAX itself never
        // takes over the mic for this flow, so there's nothing to wait on.
        mainHandler.postDelayed({ resumeBackgroundListening() }, 300)
    }

    // PHASE 9 FIX (Battery Mode): previously there was no Low Power/Standard
    // distinction anywhere -- restart delays were fixed constants. Vosk's
    // continuous listener (the preferred path) is already low-power by
    // design either way; this only affects the legacy SpeechRecognizer
    // restart-loop fallback, which the code above already documents as
    // more battery-hungry ("mic visibly toggles on/off every 1-2 seconds").
    // Low Power stretches the restart gap so that loop cycles less often;
    // Standard keeps the original fast-restart behavior for max responsiveness.
    private fun scheduleRestartListening(delayMs: Long) {
        if (!isListeningServiceRunning || isRestartScheduled) return
        isRestartScheduled = true
        val effectiveDelay = if (com.example.data.settings.MaxPreferences.isLowPowerMode(this)) {
            delayMs * 2
        } else {
            delayMs
        }
        mainHandler.postDelayed({
            isRestartScheduled = false
            if (isListeningServiceRunning) {
                listenInternal()
            }
        }, effectiveDelay)
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAX JARVIS Voice Core Active")
            .setContentText("Listening for 'Max / Hey Max' wake word in background...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MAX JARVIS Background Wake Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps MAX JARVIS active in background for wake word listening"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isListeningServiceRunning = false
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up speechRecognizer in onDestroy: ${e.message}")
        }
        voskManager?.stop()
        voskManager = null
        super.onDestroy()
    }

    /** Stops the background recognizer immediately so the foreground
     * conversation (after wake-word ack) can use the microphone without
     * the two competing for it. */
    fun pauseForForegroundConversation() {
        isPausedForForegroundConversation = true
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        voskManager?.stop()
        voskManager = null
    }

    /** Resumes background wake-word listening once the foreground
     * conversation is done. */
    fun resumeBackgroundListening() {
        if (!isPausedForForegroundConversation) return
        isPausedForForegroundConversation = false
        mainHandler.postDelayed({ startBackgroundWakeListening() }, 300)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MaxWakeService"
        const val CHANNEL_ID = "max_jarvis_wake_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_WAKE_WORD_DETECTED = "com.example.MAX_WAKE_WORD_EVENT"
        var instance: MaxWakeService? = null

        // BARGE-IN flag: set true the instant a fresh wake word fires while
        // MAX may already be speaking. MaxViewModel checks/clears this when
        // it receives ACTION_WAKE_WORD_DETECTED and, if true, stops the
        // current TTS utterance before starting the new listening turn --
        // real interruption support per Phase 9/18, not a fixed delay.
        @Volatile
        var pendingBargeIn: Boolean = false
    }
}

