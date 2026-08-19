package com.example.data.settings

import android.content.Context

/**
 * Single source of truth for the two toggles the spec calls out that had
 * no storage/wiring anywhere in the app:
 *   - Voice Feedback (ON/OFF, default OFF) — Phase 8 "Silent Operation".
 *   - Battery Mode (Low Power/Standard) — Phase 9 "Battery Optimization".
 *
 * These are NOT secrets, so plain SharedPreferences (same "max_jarvis_prefs"
 * file everything else non-sensitive already uses) is correct here — only
 * API keys go through SecureKeyStore.
 */
object MaxPreferences {

    private const val PREFS_NAME = "max_jarvis_prefs"
    private const val KEY_VOICE_FEEDBACK = "voice_feedback_enabled"
    private const val KEY_LOW_POWER_MODE = "battery_low_power_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Default OFF per spec Phase 8: "Voice Feedback: ON / OFF, Default: OFF". */
    fun isVoiceFeedbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_FEEDBACK, false)

    fun setVoiceFeedbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_FEEDBACK, enabled).apply()
    }

    /** Default true (Low Power) per spec Phase 9: "Normal state: LOW-POWER". */
    fun isLowPowerMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOW_POWER_MODE, true)

    fun setLowPowerMode(context: Context, lowPower: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOW_POWER_MODE, lowPower).apply()
    }
}
