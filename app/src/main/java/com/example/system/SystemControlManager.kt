package com.example.system

import android.app.ActivityManager
import android.content.Context

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val appName: String,
    val packageName: String
)

data class SystemTelemetry(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val cpuUsagePct: Int,
    val wifiEnabled: Boolean,
    val bluetoothEnabled: Boolean,
    val ringerMode: String
)

class SystemControlManager(private val context: Context) {

    private val contactsHelper = ContactsHelper(context)

    fun toggleFlashlight(context: Context = this.context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight-capable camera found, Sir."
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
            if (flashlightOn) "Flashlight ON, Sir." else "Flashlight OFF, Sir."
        } catch (e: Exception) {
            "Couldn't control flashlight: ${e.message}"
        }
    }

    /** level 0-100. Controls the media volume stream. */
    fun setVolume(levelPercent: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (levelPercent.coerceIn(0, 100) * max / 100)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            "Volume set to $levelPercent%, Sir."
        } catch (e: Exception) {
            "Couldn't change volume: ${e.message}"
        }
    }

    fun copyToClipboard(text: String): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MAX", text))
            "Copied to clipboard, Sir."
        } catch (e: Exception) {
            "Couldn't copy to clipboard: ${e.message}"
        }
    }

    /** Requires the Accessibility Service to be enabled (Android 9+). */
    fun takeScreenshot(): String {
        val service = MaxAccessibilityService.instance
        return if (service != null) {
            val taken = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (taken) "Screenshot captured, Sir." else "Screenshot request failed, Sir."
        } else {
            "Accessibility Service isn't enabled — enable MAX in Settings > Accessibility first, Sir."
        }
    }

    // ============================================================
    // SCREEN CAPTURE — status/stop only. STARTING capture requires an
    // Activity to launch MediaProjectionManager's consent dialog (Android
    // requires that flow to originate from foreground UI), so that part
    // lives in MainActivity/MaxViewModel, not here.
    // ============================================================
    fun isScreenCaptureActive(): Boolean = com.example.system.ScreenCaptureService.isActive

    fun stopScreenCapture(): String {
        return if (com.example.system.ScreenCaptureService.isActive) {
            val stopIntent = Intent(context, com.example.system.ScreenCaptureService::class.java).apply {
                action = com.example.system.ScreenCaptureService.ACTION_STOP
            }
            context.startService(stopIntent)
            "Screen sharing stopped, Boss."
        } else {
            "Screen sharing already inactive, Boss."
        }
    }

    fun getRecentNotifications(): String {
        val recent = NotificationReaderService.recentNotifications.takeLast(10)
        return if (recent.isEmpty()) {
            "No recent notifications captured yet, Sir. (Enable MAX under Settings > Notification Access if you haven't.)"
        } else {
            recent.joinToString("\n") { "${it.appLabel}: ${it.title} - ${it.text}" }
        }
    }

    private var flashlightOn = false

    fun getInstalledApps(): List<InstalledAppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appList = mutableListOf<InstalledAppInfo>()

        for (pkg in packages) {
            // Filter user-facing apps
            if (pm.getLaunchIntentForPackage(pkg.packageName) != null) {
                val appName = pm.getApplicationLabel(pkg).toString()
                appList.add(InstalledAppInfo(appName, pkg.packageName))
            }
        }
        return appList.sortedBy { it.appName }
    }

    // NEW: return type carries enough for the caller to ask the user when
    // matches are genuinely ambiguous, instead of silently guessing.
    data class AppLaunchResult(
        val message: String,
        val launched: Boolean,
        val needsClarification: Boolean = false,
        val candidates: List<String> = emptyList()
    )

    fun openAppByName(queryName: String): AppLaunchResult {
        val pm = context.packageManager
        val apps = getInstalledApps()
        val lowerQuery = queryName.lowercase().trim()

        // BUGFIX: previously only exact substring matching was used, so any
        // mispronunciation/mishearing (e.g. "whatsup" instead of "whatsapp")
        // failed completely. Fuzzy (edit-distance) matching is the fallback,
        // same approach used for contacts.
        val substringMatches = apps.filter {
            it.appName.lowercase().contains(lowerQuery) || lowerQuery.contains(it.appName.lowercase())
        }

        // NEW: don't launch a random app because fuzzy matching was too
        // aggressive. If more than one installed app is a plausible match,
        // ask instead of guessing -- UNLESS one of them is an exact
        // case-insensitive name match, which needs no clarification.
        val candidateApps: List<InstalledAppInfo> = when {
            substringMatches.size == 1 -> substringMatches
            substringMatches.isNotEmpty() -> {
                val exact = substringMatches.firstOrNull { it.appName.equals(queryName, ignoreCase = true) }
                if (exact != null) listOf(exact) else substringMatches
            }
            else -> {
                val scored = apps
                    .map { it to fuzzyNameScore(lowerQuery, it.appName.lowercase()) }
                    .filter { it.second >= 0.55 }
                    .sortedByDescending { it.second }
                if (scored.isEmpty()) {
                    emptyList()
                } else {
                    // Anything within 0.12 of the top score is too close a
                    // call to pick automatically.
                    val topScore = scored.first().second
                    scored.filter { topScore - it.second <= 0.12 }.map { it.first }
                }
            }
        }

        return when {
            candidateApps.isEmpty() -> {
                // Fallback: open system settings so the user isn't left
                // with total silence, but be honest that nothing matched.
                try {
                    val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    AppLaunchResult(
                        "App '$queryName' not directly matched. Opening System Settings, Sir.",
                        launched = false
                    )
                } catch (e: Exception) {
                    AppLaunchResult("Could not open application '$queryName'.", launched = false)
                }
            }
            candidateApps.size == 1 -> {
                val matchedApp = candidateApps[0]
                val launchIntent = pm.getLaunchIntentForPackage(matchedApp.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    AppLaunchResult("Launching ${matchedApp.appName}, Sir.", launched = true)
                } else {
                    AppLaunchResult("Unable to launch ${matchedApp.appName}.", launched = false)
                }
            }
            else -> {
                val names = candidateApps.take(3).map { it.appName }
                AppLaunchResult(
                    message = "Boss, mujhe ${names.joinToString(" aur ")} mein confusion hai. Kaunsa open karun?",
                    launched = false,
                    needsClarification = true,
                    candidates = names
                )
            }
        }
    }

    // Called once the user answers MAX's "Boss, mujhe X aur Y mein confusion
    // hai. Kaunsa open karun?" with one of the offered names.
    fun resolveAppChoiceAndLaunch(spokenChoice: String, candidates: List<String>): AppLaunchResult {
        val lower = spokenChoice.lowercase().trim()
        val chosenName = candidates.firstOrNull { it.lowercase() == lower }
            ?: candidates
                .map { it to fuzzyNameScore(lower, it.lowercase()) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= 0.4 }
                ?.first
            ?: return AppLaunchResult(
                "Samajh nahi aaya Boss, kaunsa? Phir se boliye.",
                launched = false,
                needsClarification = true,
                candidates = candidates
            )

        val apps = getInstalledApps()
        val matchedApp = apps.firstOrNull { it.appName == chosenName } ?: return AppLaunchResult(
            "Could not find '$chosenName' anymore, Boss.", launched = false
        )
        val launchIntent = context.packageManager.getLaunchIntentForPackage(matchedApp.packageName)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            AppLaunchResult("Launching ${matchedApp.appName}, Sir.", launched = true)
        } else {
            AppLaunchResult("Unable to launch ${matchedApp.appName}.", launched = false)
        }
    }

    private fun fuzzyNameScore(query: String, target: String): Double {
        val words = target.split(" ", "-").filter { it.isNotBlank() }
        var best = 0.0
        for (word in words + target) {
            val dist = levenshteinDistance(query, word)
            val maxLen = maxOf(query.length, word.length)
            if (maxLen == 0) continue
            val score = 1.0 - (dist.toDouble() / maxLen)
            if (score > best) best = score
        }
        return best
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }

    fun toggleSystemSetting(setting: String): String {
        val lower = setting.lowercase()
        return when {
            lower.contains("wifi") || lower.contains("wi-fi") -> {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening Wi-Fi control panel, Sir."
            }
            lower.contains("bluetooth") -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening Bluetooth interface, Sir."
            }
            lower.contains("silent") || lower.contains("mute") || lower.contains("sound") || lower.contains("volume") -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val currentMode = audioManager.ringerMode
                if (currentMode == AudioManager.RINGER_MODE_SILENT || currentMode == AudioManager.RINGER_MODE_VIBRATE) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    "Ringer set to Normal sound mode, Sir."
                } else {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "System audio switched to Silent/Vibrate mode, Sir."
                }
            }
            else -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening System Settings HUD, Sir."
            }
        }
    }

    // BUGFIX (bug #16 — threading audit): findBestMatch() runs a synchronous
    // ContentResolver query plus per-contact fuzzy-matching, which used to
    // execute directly on whatever coroutine dispatcher called this
    // function -- in practice viewModelScope's default (Main) dispatcher,
    // i.e. the UI thread. On a phone with a large contacts list this is a
    // real jank/ANR risk. Now suspend + the blocking lookup runs on
    // Dispatchers.IO; startActivity() itself is left on the original
    // (Main) dispatcher since that's the conventional/safe thread for it.
    suspend fun makeCall(target: String): String {
        // BUGFIX: this used to just strip non-digit characters from whatever
        // name was spoken (e.g. "Ramesh" -> "" -> garbage fallback number),
        // never actually looking up the contact. Now it fuzzy-matches the
        // spoken name against real contacts first, tolerating mispronunciation.
        val looksLikeNumber = target.replace(Regex("[^0-9+]"), "").length >= 7
        val phoneNumber: String
        val displayTarget: String

        if (looksLikeNumber) {
            phoneNumber = target.replace(Regex("[^0-9+]"), "")
            displayTarget = target
        } else {
            val match = withContext(Dispatchers.IO) { contactsHelper.findBestMatch(target) }
            if (match == null) {
                return "Couldn't find a contact matching '$target', Sir. Check contacts permission or try the exact saved name."
            }
            phoneNumber = match.phoneNumber
            displayTarget = match.name
        }

        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return "Dialing $displayTarget ($phoneNumber), Sir."
        } catch (e: Exception) {
            return "Unable to place call to $target."
        }
    }

    suspend fun sendWhatsAppMessage(recipient: String, message: String): String {
        // BUGFIX: this previously ignored `recipient` entirely and just
        // triggered WhatsApp's generic share sheet, forcing the user to
        // manually pick a contact every time. Now it fuzzy-matches the
        // spoken name to a real contact and deep-links straight to that
        // person's chat with the message pre-filled.
        // Note: WhatsApp requires the final tap on Send yourself — no app
        // (including Google Assistant) is allowed to silently send WhatsApp
        // messages without that final user tap, by WhatsApp/Android design.
        val match = withContext(Dispatchers.IO) { contactsHelper.findBestMatch(recipient) }
        if (match == null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                "Couldn't find a contact matching '$recipient' — opening WhatsApp so you can pick manually, Sir."
            } catch (e: Exception) {
                "WhatsApp isn't installed, Sir."
            }
        }

        val cleanNumber = match.phoneNumber.replace(Regex("[^0-9+]"), "")
        val encodedMessage = Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/$cleanNumber?text=$encodedMessage")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opened WhatsApp chat with ${match.name}, message ready — just tap Send, Sir."
        } catch (e: Exception) {
            "Unable to open WhatsApp for ${match.name}."
        }
    }

    fun draftEmail(recipient: String, subjectAndBody: String): String {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(if (recipient.contains("@")) recipient else "stark@industries.com"))
            putExtra(Intent.EXTRA_SUBJECT, "MAX AI Transmission")
            putExtra(Intent.EXTRA_TEXT, subjectAndBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opening email client with compiled draft for $recipient, Sir."
        } catch (e: Exception) {
            "Unable to dispatch email client."
        }
    }

    // BUGFIX (bug #16 — threading audit): plain synchronous File I/O
    // (write/read/delete/list), same issue as makeCall/sendWhatsAppMessage
    // above — used to run on whatever dispatcher called it (Main, via
    // viewModelScope.launch). Now suspend + Dispatchers.IO.
    suspend fun createFileInStorage(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        try {
            val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val cleanName = if (fileName.contains(".")) fileName else "$fileName.txt"
            val file = File(documentsDir, cleanName)
            file.writeText(content)
            "File '$cleanName' saved in Documents directory (${file.length()} bytes), Sir."
        } catch (e: Exception) {
            "Failed to create file: ${e.message}"
        }
    }

    // ============================================================
    // FILE SYSTEM ACTIONS — EDIT / DELETE / FIND
    // ============================================================
    // Scope: MAX's own app-private Documents directory (same dir used by
    // createFileInStorage). This is real, scoped-storage-compliant access
    // that needs no extra runtime permission and works on every Android
    // version. It intentionally does NOT reach into arbitrary system
    // folders like Downloads or DCIM — that requires the user to grant a
    // folder via the Storage Access Framework picker (ACTION_OPEN_DOCUMENT_TREE),
    // which is a separate, explicit consent flow, not something MAX can
    // silently assume. Report that limitation honestly rather than
    // pretending broader access exists.
    private fun documentsDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir

    suspend fun editFileInStorage(fileName: String, newContent: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(documentsDir(), fileName)
            if (!file.exists()) {
                return@withContext "'$fileName' Documents mein nahi mili, Boss. Pehle usse banao ya sahi naam batao."
            }
            file.writeText(newContent)
            "'$fileName' update kar di, Boss (${file.length()} bytes)."
        } catch (e: Exception) {
            "File edit fail ho gaya: ${e.message}"
        }
    }

    // Only ever called AFTER the confirmation gate in the ViewModel has
    // gotten an explicit user "yes" — never call this directly off a raw
    // AI-parsed action.
    suspend fun deleteFileFromStorage(fileName: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(documentsDir(), fileName)
            if (!file.exists()) {
                return@withContext "'$fileName' pehle se hi Documents mein nahi hai, Boss."
            }
            val deleted = file.delete()
            if (deleted) "'$fileName' permanently delete kar di, Boss." else "Delete fail ho gaya, Boss — file locked ho sakti hai."
        } catch (e: Exception) {
            "Delete fail ho gaya: ${e.message}"
        }
    }

    suspend fun findFilesInStorage(query: String): String = withContext(Dispatchers.IO) {
        try {
            val dir = documentsDir()
            val allFiles = dir.listFiles()?.toList() ?: emptyList()
            val matches = if (query.isBlank()) {
                allFiles
            } else {
                allFiles.filter { it.name.contains(query, ignoreCase = true) }
            }
            if (matches.isEmpty()) {
                "'$query' se milti koi file nahi mili MAX ke Documents folder mein, Boss. (Note: MAX abhi sirf apne khud ke Documents folder mein search karta hai, poore phone storage mein nahi — us ke liye folder access permission chahiye hogi.)"
            } else {
                val list = matches.sortedByDescending { it.lastModified() }
                    .take(10)
                    .joinToString(", ") { it.name }
                "${matches.size} file mili, Boss: $list"
            }
        } catch (e: Exception) {
            "File search fail ho gaya: ${e.message}"
        }
    }

    fun getTelemetry(): SystemTelemetry {
        // Battery info
        val batteryStatus: Intent? = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        } catch (e: Exception) {
            null
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 85
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPct = (level * 100 / scale.toFloat()).toInt()

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        // Memory info using ActivityManager for real system RAM
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)
        val usedRamMb = (totalRamMb - availRamMb).coerceAtLeast(0L)


        // Audio Ringer info

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerStr = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Normal"
        }

        val pseudoCpuPct = (20..45).random()

        return SystemTelemetry(
            batteryLevel = batteryPct,
            isCharging = isCharging,
            ramUsedMb = usedRamMb,
            ramTotalMb = totalRamMb,
            cpuUsagePct = pseudoCpuPct,
            wifiEnabled = true,
            bluetoothEnabled = true,
            ringerMode = ringerStr
        )
    }
}
