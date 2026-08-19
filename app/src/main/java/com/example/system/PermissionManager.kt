package com.example.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * PHASE 21 — CENTRALIZED PERMISSION MANAGER.
 *
 * Single source of truth for "what does MAX have access to, and why does it
 * need it". Two kinds of access exist on Android and they're tracked
 * differently:
 *
 *  - Runtime permissions (RECORD_AUDIO, CALL_PHONE, READ_CONTACTS,
 *    POST_NOTIFICATIONS): checked via checkSelfPermission, requested via
 *    the normal permission dialog.
 *  - "Special" access (Accessibility Service, Notification Listener):
 *    Android has no runtime-permission dialog for these -- the user must
 *    flip them on in a dedicated Settings screen. This manager only
 *    reports status + opens the correct Settings screen; it cannot
 *    request them directly (no API allows that, by design, since these
 *    grant powerful access).
 *
 * Only permissions MAX's code actually uses are listed here -- no
 * CAMERA/BLUETOOTH entries, since nothing in this app currently touches
 * either, and listing unused permissions would be exactly the kind of
 * "pretend capability" Phase 26 rules out.
 */
object PermissionManager {

    enum class Kind { RUNTIME, SPECIAL }

    data class MaxPermission(
        val id: String,
        val label: String,
        val rationale: String,
        val kind: Kind,
        val androidPermission: String? = null // only set for Kind.RUNTIME
    )

    val ALL: List<MaxPermission> = listOf(
        MaxPermission(
            id = "microphone",
            label = "Microphone",
            rationale = "Zaroori hai voice commands sunne ke liye aur wake-word (\"Jarvis\"/\"MAX\") detect karne ke liye.",
            kind = Kind.RUNTIME,
            androidPermission = Manifest.permission.RECORD_AUDIO
        ),
        MaxPermission(
            id = "phone",
            label = "Phone Calls",
            rationale = "Zaroori hai jab tum bolo \"Papa ko call karo\" — bina is permission ke MAX call place nahi kar sakta.",
            kind = Kind.RUNTIME,
            androidPermission = Manifest.permission.CALL_PHONE
        ),
        MaxPermission(
            id = "contacts",
            label = "Contacts",
            rationale = "Zaroori hai naam se contact dhoondne ke liye (\"Zoya ko call/message karo\").",
            kind = Kind.RUNTIME,
            androidPermission = Manifest.permission.READ_CONTACTS
        ),
        MaxPermission(
            id = "notifications",
            label = "Post Notifications",
            rationale = "Zaroori hai MAX ke background wake-word service ka status notification dikhane ke liye.",
            kind = Kind.RUNTIME,
            androidPermission = Manifest.permission.POST_NOTIFICATIONS
        ),
        MaxPermission(
            id = "accessibility",
            label = "Accessibility Service",
            rationale = "Zaroori hai screen padhne, buttons tap karne, aur UI automation ke liye. Ye ek special Android permission hai — Settings mein manually enable karna padega.",
            kind = Kind.SPECIAL
        ),
        MaxPermission(
            id = "notification_listener",
            label = "Notification Access",
            rationale = "Zaroori hai recent notifications padhne ke liye (\"MAX, meri last notification kya thi?\"). Special Android permission — Settings mein enable karo.",
            kind = Kind.SPECIAL
        )
    )

    fun isGranted(context: Context, permission: MaxPermission): Boolean {
        return when (permission.kind) {
            Kind.RUNTIME -> {
                val perm = permission.androidPermission ?: return true
                if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    return true // permission doesn't exist pre-API33; nothing to grant
                }
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
            Kind.SPECIAL -> when (permission.id) {
                "accessibility" -> MaxAccessibilityService.isEnabled()
                "notification_listener" -> isNotificationListenerEnabled(context)
                else -> false
            }
        }
    }

    fun missing(context: Context): List<MaxPermission> = ALL.filterNot { isGranted(context, it) }

    /** Intent to open the correct system screen for a SPECIAL permission. */
    fun settingsIntentFor(permission: MaxPermission): Intent = when (permission.id) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notification_listener" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabledListeners.contains(context.packageName)
    }
}
