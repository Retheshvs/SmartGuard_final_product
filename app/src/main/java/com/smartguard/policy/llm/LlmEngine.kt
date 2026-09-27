package com.smartguard.policy.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the on-device LLM (Google AI Edge / MediaPipe LLM Inference API).
 *
 * - Loads a `.task` model bundle from local storage only. No network is used (the app has no
 *   INTERNET permission at all).
 * - One generation at a time; each request gets a fresh session with greedy decoding (top-k = 1)
 *   so the same request yields the same policy.
 * - Kept loaded only while the Policy Assistant is open ([release] frees ~1.6 GB).
 */
object LlmEngine {

    private const val TAG = "SG-LLM"
    private const val MAX_TOKENS = 1280 // matches the model's KV cache (…_ekv1280.task)

    sealed class Status {
        object NotLoaded : Status()
        data class NotFound(val searched: List<String>) : Status()
        data class Ready(val modelName: String, val loadMs: Long) : Status()
        data class Failed(val reason: String) : Status()
    }

    data class Generation(val text: String, val latencyMs: Long, val promptTokens: Int)

    private val mutex = Mutex()
    private var llm: LlmInference? = null
    private var modelFile: File? = null

    @Volatile
    var status: Status = Status.NotLoaded
        private set

    /** Directories searched for a `.task` bundle, in priority order. */
    fun searchDirs(context: Context): List<File> = listOfNotNull(
        context.getExternalFilesDir("models"),         // /sdcard/Android/data/com.smartguard/files/models
        File(context.filesDir, "models"),
        File("/data/local/tmp/llm")
    )

    fun findModel(context: Context): File? =
        searchDirs(context)
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".task") }?.toList().orEmpty() }
            .sortedWith(
                compareByDescending<File> { it.name.contains("qwen", true) || it.name.contains("gemma", true) }
                    .thenByDescending { it.length() }
            )
            .firstOrNull()

    /** Loads the model if needed. Safe to call repeatedly; returns the resulting status. */
    suspend fun ensureLoaded(context: Context): Status = mutex.withLock {
        if (llm != null) return@withLock status
        withContext(Dispatchers.IO) {
            val file = findModel(context)
            if (file == null) {
                status = Status.NotFound(searchDirs(context).map { it.absolutePath })
                return@withContext status
            }
            try {
                val t0 = SystemClock.elapsedRealtime()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setMaxTopK(40)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                llm = LlmInference.createFromOptions(context.applicationContext, options)
                modelFile = file
                val loadMs = SystemClock.elapsedRealtime() - t0
                status = Status.Ready(prettyName(file.name), loadMs)
                Log.i(TAG, "Loaded ${file.name} (${file.length() / 1_000_000} MB) in ${loadMs}ms")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load ${file.name}", e)
                llm = null
                status = Status.Failed(e.message ?: e.javaClass.simpleName)
            }
            status
        }
    }

    /** Runs the policy prompt. Returns null if no model is loaded or generation failed. */
    suspend fun generate(request: String): Generation? = mutex.withLock {
        val engine = llm ?: return@withLock null
        val template = modelFile?.let { LlmPrompt.templateFor(it.name) }

        withContext(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val text = try {
                runSession(engine, template, useRuntimeTemplates = template != null, request = request)
            } catch (e: Throwable) {
                Log.w(TAG, "Session with explicit templates failed (${e.message}); retrying with the model's built-in template")
                try {
                    runSession(engine, template, useRuntimeTemplates = false, request = request)
                } catch (e2: Throwable) {
                    Log.e(TAG, "Generation failed", e2)
                    return@withContext null
                }
            }
            val latency = SystemClock.elapsedRealtime() - t0
            val tokens = try {
                engine.sizeInTokens(LlmPrompt.wrapped(template, request))
            } catch (e: Throwable) {
                -1
            }
            Log.i(TAG, "Generated in ${latency}ms (prompt≈$tokens tokens): ${text.take(400)}")
            Generation(text, latency, tokens)
        }
    }

    private fun runSession(
        engine: LlmInference,
        template: LlmPrompt.Template?,
        useRuntimeTemplates: Boolean,
        request: String
    ): String {
        val builder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(1)            // greedy: deterministic JSON
            .setTemperature(0.1f)
            .setRandomSeed(0)
        if (useRuntimeTemplates && template != null) {
            builder.setPromptTemplates(
                PromptTemplates.builder()
                    .setUserPrefix(template.userPrefix)
                    .setUserSuffix(template.userSuffix)
                    .setModelPrefix(template.modelPrefix)
                    .setModelSuffix(template.modelSuffix)
                    .setSystemPrefix("")
                    .setSystemSuffix("")
                    .build()
            )
        }

        LlmInferenceSession.createFromOptions(engine, builder.build()).use { session ->
            // Always send the plain instruction: the runtime wraps it with either our explicit
            // templates or the chat template embedded in the .task bundle. Adding markers here too
            // would double-wrap the prompt.
            session.addQueryChunk(LlmPrompt.instruction(request))
            return session.generateResponse()
        }
    }

    /**
     * Frees the model (≈1.6 GB). Waits for any in-flight load/generation to finish first, so native
     * memory is never released under a running inference. The next [ensureLoaded] reloads it.
     */
    fun release() {
        releaseScope.launch {
            mutex.withLock {
                try {
                    llm?.close()
                } catch (e: Throwable) {
                    Log.w(TAG, "close failed", e)
                }
                llm = null
                modelFile = null
                status = Status.NotLoaded
            }
        }
    }

    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun prettyName(fileName: String): String = when {
        fileName.contains("qwen", true) -> "Qwen2.5-1.5B-Instruct"
        fileName.contains("gemma", true) -> "Gemma"
        else -> fileName.removeSuffix(".task")
    }
}
