package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single source of truth for every API key MAX stores: Gemini (built-in and
 * custom), OpenAI, Claude, and the three general-purpose "API Key Slot"
 * fields in Settings. Backed by Android Keystore via EncryptedSharedPreferences
 * instead of plain SharedPreferences.
 *
 * SECURITY FIX (2026-08): previously all of these lived in plain
 * getSharedPreferences("max_jarvis_prefs", MODE_PRIVATE) across
 * MultiBrainManager.kt and MaxViewModel.kt — readable by anyone with
 * filesystem/backup access to the app's data. This class is a drop-in
 * replacement with the same get/put shape, plus a one-time migration that
 * moves any existing plaintext values here and wipes them from the old file.
 *
 * NOTE: a client-side key is never fully secret against a rooted/debuggable
 * device — this protects against casual disk/backup extraction, which is
 * the realistic threat model for a local app, not a substitute for a
 * server-side proxy.
 */
object SecureKeyStore {

    private const val SECURE_FILE_NAME = "max_jarvis_secure_prefs"
    private const val LEGACY_FILE_NAME = "max_jarvis_prefs"

    // Every key name that has ever held an API key/secret in the legacy
    // plaintext file. Keep this list in sync if a new key field is added.
    private val MIGRATED_KEYS = listOf(
        "custom_gemini_api_key",
        "openai_api_key",
        "claude_api_key",
        "api_key_slot_1",
        "api_key_slot_2",
        "api_key_slot_3"
    )

    @Volatile
    private var securePrefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: buildEncryptedPrefs(context.applicationContext).also {
                securePrefs = it
                migrateLegacyKeys(context.applicationContext, it)
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            SECURE_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * One-time migration: copies any existing plaintext key values into the
     * encrypted store, then removes them from the legacy plaintext file so
     * they don't linger on disk unencrypted. Safe to call every app start —
     * it's a no-op once the legacy values are gone.
     */
    private fun migrateLegacyKeys(context: Context, target: SharedPreferences) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_FILE_NAME, Context.MODE_PRIVATE)
        val legacyEditor = legacyPrefs.edit()
        val targetEditor = target.edit()
        var migratedAny = false

        for (keyName in MIGRATED_KEYS) {
            val legacyValue = legacyPrefs.getString(keyName, null)
            if (!legacyValue.isNullOrBlank() && target.getString(keyName, null).isNullOrBlank()) {
                targetEditor.putString(keyName, legacyValue)
                legacyEditor.remove(keyName)
                migratedAny = true
            } else if (legacyPrefs.contains(keyName)) {
                // Already migrated or blank — still scrub it from plaintext.
                legacyEditor.remove(keyName)
            }
        }

        if (migratedAny) targetEditor.apply()
        legacyEditor.apply()
    }

    fun getString(context: Context, key: String): String {
        return prefs(context).getString(key, "") ?: ""
    }

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value.trim()).apply()
    }

    fun contains(context: Context, key: String): Boolean {
        return !getString(context, key).isNullOrBlank()
    }

    fun delete(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }
}
