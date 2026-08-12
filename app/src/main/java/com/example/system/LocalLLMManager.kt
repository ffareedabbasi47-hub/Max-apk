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
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                }
            } ?: return@withContext Result.failure(Exception("Could not open the selected file"))

            if (destFile.length() < 1024) {
                destFile.delete()
                return@withContext Result.failure(Exception("Copied file looks too small — import may have failed"))
            }
            Result.success(safeName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isModelReady(): Boolean {
        val active = getActiveModelName() ?: return false
        return File(modelsDir, active).exists()
    }

    /**
     * Lazily loads (or reuses) the MediaPipe inference engine for the
     * currently active model, then generates a response fully offline.
     * Returns null if no model is imported/selected, or on any load/inference
     * error (caller falls back to cloud providers or canned responses).
     */
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        val activeName = getActiveModelName() ?: return@withContext null
        val modelFile = File(modelsDir, activeName)
        if (!modelFile.exists()) return@withContext null

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
            inference.generateResponse(prompt)
        } catch (e: Exception) {
            android.util.Log.e("LocalLLMManager", "Local inference failed: ${e.message}", e)
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
