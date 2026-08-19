package com.example.system

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles everything related to running a LOCAL, offline LLM on-device via
 * Google's MediaPipe LLM Inference engine. This is model-agnostic — the user
 * can import ANY compatible ".task" model file (Gemma, Phi-3-mini, Falcon-RW,
 * StableLM, or any model converted to MediaPipe's .task bundle format) and
 * MAX will run it fully offline, no internet or API key needed.
 *
 * Compatible model sources (all free):
 * - Kaggle Models -> search "LiteRT" / "MediaPipe" -> pre-converted .task files
 *   for Gemma 2B/7B, Gemma-2 2B, Phi-3-mini, Falcon-RW-1B, StableLM-3B.
 * - Or convert your own HuggingFace checkpoint using Google's
 *   "ai-edge-torch-generative" converter to produce a .task file.
 *
 * Model files are large (500MB-4GB). Pick a smaller quantized model
 * (e.g. Gemma 2B int4, ~1.3GB) for phones with 6-8GB RAM; larger models
 * need more RAM and will be noticeably slower per response.
 */
class LocalLLMManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "local_llm_models").apply { mkdirs() }

    private val prefs = context.getSharedPreferences("max_jarvis_prefs", Context.MODE_PRIVATE)

    private var loadedInference: LlmInference? = null
    private var loadedModelName: String? = null

    data class LocalModelInfo(
        val fileName: String,
        val sizeBytes: Long,
        val isActive: Boolean
    )

    fun listImportedModels(): List<LocalModelInfo> {
        val active = getActiveModelName()
        return modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "task" }
            ?.map { LocalModelInfo(it.name, it.length(), it.name == active) }
            ?.sortedBy { it.fileName }
            ?: emptyList()
    }

    fun getActiveModelName(): String? = prefs.getString("active_local_model", null)

    fun setActiveModel(fileName: String) {
        prefs.edit().putString("active_local_model", fileName).apply()
        // Force reload on next generation call since the model changed
        unloadCurrentModel()
    }

    fun deleteModel(fileName: String) {
        File(modelsDir, fileName).delete()
        if (getActiveModelName() == fileName) {
            prefs.edit().remove("active_local_model").apply()
            unloadCurrentModel()
        }
    }

    /**
     * Copies a model file the user picked (via the system file picker, as a
     * content:// Uri) into the app's private storage where MediaPipe can
     * open it by a real file path. Runs on IO thread — large files can take
     * a while to copy.
     */
    suspend fun importModel(sourceUri: Uri, displayName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safeName = if (displayName.endsWith(".task")) displayName else "$displayName.task"
            val destFile = File(modelsDir, safeName)

            // BUGFIX (bug #7/8 audit): copying a 500MB-4GB model with no
            // upfront space check meant a low-storage device would fail
            // mid-copy, leaving a truncated file that LOOKED present
            // (existed, non-zero size) but was actually corrupt. Check
            // available space against the source size where we can
            // determine it, before writing anything.
            val approxSourceSize = try {
                context.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            val usableSpace = modelsDir.usableSpace
            if (approxSourceSize > 0 && usableSpace in 1 until approxSourceSize) {
                return@withContext Result.failure(
                    Exception("Not enough free storage to import this model: needs ~${approxSourceSize / (1024 * 1024)}MB, only ${usableSpace / (1024 * 1024)}MB free.")
                )
            }

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                }
            } ?: return@withContext Result.failure(Exception("Could not open the selected file"))

            if (destFile.length() < 1024) {
                destFile.delete()
                return@withContext Result.failure(Exception("Copied file looks too small — import may have failed"))
            }

            // BUGFIX: the file used to be renamed to ".task" regardless of
            // what it actually was — a .gz/.tar.gz checkpoint would get
            // labeled .task, then fail later with a confusing "unable to
            // open zip archive" error. .task files are internally zip
            // archives (they start with the "PK" signature); gzip files
            // start with a different signature (0x1F 0x8B). Check this now
            // and reject immediately with a clear explanation.
            val header = ByteArray(4)
            destFile.inputStream().use { it.read(header) }
            val isZipBased = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() // "PK"
            val isGzip = header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()
            if (!isZipBased) {
                destFile.delete()
                return@withContext Result.failure(
                    Exception(
                        if (isGzip)
                            "This is a .gz/.tar.gz checkpoint file, not a MediaPipe .task bundle — it can't run on-device. Download the 'LiteRT'/'TFLite' .task variant instead (e.g. from Kaggle's tfLite tab for the model)."
                        else
                            "This doesn't look like a valid .task model file (wrong file type)."
                    )
                )
            }

            // BUGFIX (bug #7): a file can start with the correct "PK"
            // signature and STILL be a corrupt/truncated archive (e.g. a
            // copy that was interrupted partway). The old check stopped at
            // "starts with PK" = valid, which is exactly the bug this audit
            // called out. Actually open it as a zip and read every entry's
            // header -- a truncated/corrupt archive will throw here, and we
            // catch that below and reject the file instead of leaving a
            // broken model marked as "imported".
            try {
                java.util.zip.ZipFile(destFile).use { zf ->
                    val entries = zf.entries()
                    var count = 0
                    while (entries.hasMoreElements()) {
                        entries.nextElement()
                        count++
                    }
                    if (count == 0) {
                        destFile.delete()
                        return@withContext Result.failure(Exception("Model archive is empty or unreadable — the download may be incomplete."))
                    }
                }
            } catch (e: Exception) {
                destFile.delete()
                return@withContext Result.failure(Exception("Model file is corrupt or incomplete (zip integrity check failed: ${e.message}). Please re-download and try importing again."))
            }

            Result.success(safeName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private var lastLoadError: String? = null
    fun getLastError(): String? = lastLoadError

    fun isModelReady(): Boolean {
        val active = getActiveModelName() ?: return false
        return File(modelsDir, active).exists()
    }

    /**
     * Lazily loads (or reuses) the MediaPipe inference engine for the
     * currently active model, then generates a response fully offline.
     * Returns null if no model is imported/selected, or on any load/inference
     * error (caller falls back to cloud providers or canned responses).
     * Call getLastError() after a null result to see exactly why it failed —
     * previously failures were silently swallowed, making it look like
     * nothing was configured even when a model file was present.
     */
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        val activeName = getActiveModelName() ?: run {
            lastLoadError = "No local model selected as active."
            return@withContext null
        }
        val modelFile = File(modelsDir, activeName)
        if (!modelFile.exists()) {
            lastLoadError = "Active model file '$activeName' is missing from storage."
            return@withContext null
        }

        // BUGFIX (bug #8 — OOM safety): a large model (multi-GB) loaded on
        // a device without enough free RAM will either throw
        // OutOfMemoryError or get the process killed by the low-memory
        // killer with no exception at all. This is a best-effort guard,
        // not a guarantee (native/GPU memory isn't visible to
        // ActivityManager), but it avoids attempting a load that's almost
        // certainly doomed and gives the user an honest, specific reason
        // instead of a silent crash.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (am != null && loadedModelName != activeName) {
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            // Rule of thumb: a .task model roughly needs its file size again
            // in RAM/VRAM once loaded (quantized weights + runtime buffers).
            if (memInfo.availMem < modelFile.length()) {
                lastLoadError = "Not enough free memory to load this model (~${modelFile.length() / (1024 * 1024)}MB needed, ${memInfo.availMem / (1024 * 1024)}MB available). Try a smaller/more quantized model."
                android.util.Log.w("LocalLLMManager", lastLoadError!!)
                return@withContext null
            }
        }

        try {
            val inference = if (loadedInference != null && loadedModelName == activeName) {
                loadedInference!!
            } else {
                unloadCurrentModel()
                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024)
                    .build()
                val newInference = LlmInference.createFromOptions(context, options)
                loadedInference = newInference
                loadedModelName = activeName
                newInference
            }
            val result = inference.generateResponse(prompt)
            lastLoadError = null
            result
        } catch (e: OutOfMemoryError) {
            // BUGFIX: OutOfMemoryError is a Throwable/Error, NOT an
            // Exception -- the old `catch (e: Exception)` block never
            // caught this, so a too-large model could crash the whole app
            // process instead of failing gracefully back to cloud/canned
            // responses. Caught explicitly here, resources released, and
            // reported as an honest error instead of a crash.
            val reason = "Out of memory while loading/running the local model. It's too large for this device's available RAM."
            lastLoadError = reason
            android.util.Log.e("LocalLLMManager", reason, e)
            unloadCurrentModel()
            null
        } catch (e: Exception) {
            val reason = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            lastLoadError = reason
            android.util.Log.e("LocalLLMManager", "Local inference failed: $reason", e)
            // Model may be corrupted/incompatible/out of memory — unload so
            // the next attempt doesn't reuse a broken instance.
            unloadCurrentModel()
            null
        }
    }

    fun unloadCurrentModel() {
        try {
            loadedInference?.close()
        } catch (e: Exception) {
            // ignore
        }
        loadedInference = null
        loadedModelName = null
    }
}
