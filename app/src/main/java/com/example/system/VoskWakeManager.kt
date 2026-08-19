package com.example.system

import android.content.Context
import android.net.Uri
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Continuous, free, fully offline "Max" wake-word detection using Vosk.
 * Unlike Android's SpeechRecognizer (short 1-2s sessions, must restart
 * between them — visible mic flicker), Vosk runs its own continuous
 * AudioRecord-based recognition loop with no restart gap.
 *
 * Needs a Vosk model the user downloads once (no account/signup — direct
 * download) from https://alphacephei.com/vosk/models — the small English
 * model ("vosk-model-small-en-us-0.15", ~40MB) works well for this. The
 * downloaded .zip is imported via Settings (same pattern as importing a
 * local AI model) and extracted here.
 */
class VoskWakeManager(private val context: Context) {

    private val modelsRootDir = File(context.filesDir, "vosk_model").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("max_jarvis_prefs", Context.MODE_PRIVATE)

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var lastError: String? = null

    // SECURITY FIX (Zip Slip / path traversal): a zip entry can be crafted
    // with a name like "../../../data/data/<pkg>/shared_prefs/evil.xml" or
    // an absolute path. The OLD code did `File(modelsRootDir, entry.name)`
    // directly, so such an entry would resolve OUTSIDE modelsRootDir and
    // let a malicious "model zip" overwrite arbitrary app-private files.
    // Fix: resolve the canonical path of every extraction target and
    // reject anything that doesn't stay strictly inside modelsRootDir.
    private fun safeExtractionTarget(entryName: String): File? {
        // Reject absolute paths outright -- a legitimate zip entry name is
        // always relative.
        if (entryName.startsWith("/") || entryName.startsWith("\\")) return null
        val target = File(modelsRootDir, entryName)
        val rootCanonical = modelsRootDir.canonicalFile
        val targetCanonical = target.canonicalFile
        return if (targetCanonical.path == rootCanonical.path ||
            targetCanonical.path.startsWith(rootCanonical.path + File.separator)
        ) {
            targetCanonical
        } else {
            Log.w("VoskWakeManager", "Rejected unsafe zip entry (path traversal attempt): $entryName")
            null
        }
    }

    // BUG FIX (duplicate wake callbacks): Vosk calls onPartialResult
    // repeatedly as recognition progresses, then onResult, then
    // onFinalResult -- a single spoken "hey max" could contain the wake
    // word in several of those callbacks, firing onWakeWordDetected()
    // multiple times for one utterance. This cooldown ensures one spoken
    // wake phrase produces exactly one activation.
    @Volatile
    private var lastWakeTriggerAtMs: Long = 0L
    private val wakeCooldownMs = 2000L

    // BUG FIX (false-positive wake word): the old check was
    // `text.contains("max")`, which also matches "maximum", "taxman",
    // "climax", etc. This uses word-boundary regex so only the standalone
    // word "max" (optionally preceded by "hey"/"ok") triggers a wake.
    private val wakeWordRegex = Regex("""\b(hey\s+max|ok\s+max|max)\b""", RegexOption.IGNORE_CASE)

    fun getLastError(): String? = lastError

    private fun findExtractedModelDir(): File? {
        // The zip usually contains one top-level folder (e.g.
        // "vosk-model-small-en-us-0.15/"); Vosk needs the path to that
        // folder specifically, not its parent.
        val marker = prefs.getString("vosk_model_folder_name", null) ?: return null
        val dir = File(modelsRootDir, marker)
        return if (dir.exists() && dir.isDirectory) dir else null
    }

    fun isReady(): Boolean = findExtractedModelDir() != null

    /** Extracts a downloaded Vosk model .zip into internal storage. Runs
     * synchronously — call from a background thread/coroutine, this can
     * take a few seconds for a ~40MB model. Every entry's destination is
     * validated to stay inside modelsRootDir (see safeExtractionTarget);
     * any entry that fails that check is skipped, not silently allowed. */
    fun importModelZip(uri: Uri): Result<Unit> {
        return try {
            // Clear any previous model first.
            modelsRootDir.listFiles()?.forEach { it.deleteRecursively() }

            var topLevelFolder: String? = null
            var skippedUnsafeEntries = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val outFile = safeExtractionTarget(name)
                        if (outFile == null) {
                            skippedUnsafeEntries++
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        if (topLevelFolder == null && name.contains("/")) {
                            topLevelFolder = name.substringBefore("/")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return Result.failure(Exception("Could not open the selected zip file"))

            if (skippedUnsafeEntries > 0) {
                Log.w("VoskWakeManager", "Skipped $skippedUnsafeEntries unsafe zip entries during import.")
            }

            val folderName = topLevelFolder
            if (folderName.isNullOrBlank()) {
                return Result.failure(Exception("Zip didn't contain the expected model folder structure"))
            }
            prefs.edit().putString("vosk_model_folder_name", folderName).apply()

            if (findExtractedModelDir() == null) {
                Result.failure(Exception("Extraction finished but model folder wasn't found — check it's a genuine Vosk model zip"))
            } else if (skippedUnsafeEntries > 0) {
                Result.failure(Exception("Zip contained $skippedUnsafeEntries entries with unsafe paths and was rejected for your safety. Please use a genuine Vosk model zip from alphacephei.com/vosk/models."))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Starts continuous listening. [onWakeWordDetected] fires (on a
     * background thread) whenever recognized speech contains the standalone
     * word "max" (or "hey max"/"ok max"), debounced to one activation per
     * spoken phrase. Returns an error message on failure, or null on success.
     */
    fun start(onWakeWordDetected: () -> Unit): String? {
        val modelDir = findExtractedModelDir() ?: return "No Vosk model imported yet."
        stop()
        return try {
            val loadedModel = Model(modelDir.absolutePath)
            model = loadedModel
            val recognizer = Recognizer(loadedModel, 16000.0f)
            val service = SpeechService(recognizer, 16000.0f)
            speechService = service
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    checkForWakeWord(hypothesis, onWakeWordDetected)
                }
                override fun onResult(hypothesis: String?) {
                    checkForWakeWord(hypothesis, onWakeWordDetected)
                }
                override fun onFinalResult(hypothesis: String?) {
                    checkForWakeWord(hypothesis, onWakeWordDetected)
                }
                override fun onError(exception: Exception?) {
                    lastError = exception?.message
                }
                override fun onTimeout() {}
            })
            lastError = null
            null
        } catch (e: Exception) {
            lastError = e.message
            "Vosk failed to start: ${e.message}"
        }
    }

    private fun checkForWakeWord(hypothesisJson: String?, onWakeWordDetected: () -> Unit) {
        val text = hypothesisJson ?: return
        if (!wakeWordRegex.containsMatchIn(text)) return

        val now = System.currentTimeMillis()
        if (now - lastWakeTriggerAtMs < wakeCooldownMs) {
            // Same spoken phrase already triggered a wake very recently --
            // this is Vosk re-reporting the same utterance across
            // partial/result/finalResult, not a new command.
            return
        }
        lastWakeTriggerAtMs = now
        onWakeWordDetected()
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.w("VoskWakeManager", "Error stopping speech service: ${e.message}")
        }
        speechService = null
        try {
            model?.close()
        } catch (e: Exception) {
            Log.w("VoskWakeManager", "Error closing model: ${e.message}")
        }
        model = null
    }
}
