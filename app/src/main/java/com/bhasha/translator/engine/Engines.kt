// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.engine

import android.content.Context
import com.bhasha.engine.asr.AsrEngine
import com.bhasha.engine.asr.MtEngine
import com.bhasha.engine.llm.LlmEngine
import com.bhasha.translator.core.Settings
import com.bhasha.translator.core.performanceThreads
import com.bhasha.translator.model.ModelCatalog
import com.bhasha.translator.model.ModelKind
import com.bhasha.translator.model.ModelSpec
import com.bhasha.translator.model.ModelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val model: String) : EngineState
    data class Ready(val model: ModelSpec) : EngineState
    data class Error(val message: String) : EngineState
}

/**
 * Owns the two native engines. Each has its own single thread, which both
 * serialises access (the engines are not re-entrant) and keeps model loading
 * and inference off the main thread.
 */
class Engines(
    private val context: Context,
    private val store: ModelStore,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private var release: Job? = null
    val asrDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "bhasha-asr") }.asCoroutineDispatcher()
    val llmDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "bhasha-llm") }.asCoroutineDispatcher()

    val mtDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "bhasha-mt") }.asCoroutineDispatcher()

    private val threads = performanceThreads()

    private val _asrState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val _llmState = MutableStateFlow<EngineState>(EngineState.Idle)
    val asrState: StateFlow<EngineState> = _asrState
    val llmState: StateFlow<EngineState> = _llmState
    private val _mtState = MutableStateFlow<EngineState>(EngineState.Idle)
    val mtState: StateFlow<EngineState> = _mtState
    @Volatile private var mt: MtEngine? = null

    @Volatile private var asr: AsrEngine? = null
    @Volatile private var asrKey: String? = null   // which models the loaded engine was opened with
    @Volatile private var llm: LlmEngine? = null
    @Volatile private var llmSpec: ModelSpec? = null

    /** The speech model to use: the user's choice if installed, else the first installed one. */
    fun selectedAsr(): ModelSpec? = pick(settings.asrModelId.value, ModelKind.ASR)

    fun selectedLlm(): ModelSpec? = pick(settings.llmModelId.value, ModelKind.LLM)

    private fun pick(preferred: String, kind: ModelKind): ModelSpec? {
        val installed = store.installed(kind)
        return installed.firstOrNull { it.id == preferred } ?: installed.firstOrNull()
    }

    /** Loads (or swaps to) the selected speech model. Returns null and sets [asrState] on failure. */
    suspend fun ensureAsr(): AsrEngine? = withContext(asrDispatcher) {
        val whisper = selectedAsr()
        val packs = store.installed(ModelKind.SPEECH_PACK)
        val tokens = if (packs.isEmpty()) null else store.packTokens()
        val usePacks = packs.isNotEmpty() && tokens != null
        val shown = if (usePacks) packs.first() else whisper
        if (shown == null) {
            _asrState.value = EngineState.Error("No speech model installed")
            return@withContext null
        }
        // Language packs are loaded by the engine when their language is first heard, and
        // Whisper only if something needs it, so both can be handed over together.
        val key = listOfNotNull(whisper?.id).plus(if (usePacks) packs.map { it.id } else emptyList()).joinToString("+")
        asr?.let { if (asrKey == key) return@withContext it }
        asr?.close()
        asr = null
        val title = if (usePacks) "speech models" else shown.title
        _asrState.value = EngineState.Loading(title)
        val vad = store.file(ModelCatalog.vad).takeIf { store.isInstalled(ModelCatalog.vad) }?.absolutePath
        val engine = AsrEngine.open(
            whisperPath = whisper?.let { store.file(it).absolutePath },
            vadModelPath = vad,
            threads = threads,
            langModelPattern = if (usePacks) store.packPattern else null,
            langTokens = tokens?.absolutePath,
        )
        if (engine == null) {
            _asrState.value = EngineState.Error("Could not load $title")
            return@withContext null
        }
        asr = engine
        asrKey = key
        _asrState.value = EngineState.Ready(shown)
        engine
    }

    /** True when the dedicated translation model is installed and handles [langCode]. */
    fun hasTranslator(langCode: String): Boolean =
        langCode in MtEngine.LANGUAGES && store.isInstalled(ModelCatalog.translator)

    /** Loads the translation model. Returns null (and sets [mtState]) when it is missing or broken. */
    suspend fun ensureMt(): MtEngine? = withContext(mtDispatcher) {
        mt?.let { return@withContext it }
        val spec = ModelCatalog.translator
        if (!store.isInstalled(spec)) return@withContext null
        _mtState.value = EngineState.Loading(spec.title)
        // Two threads: the decoder's matrices are small, and more threads mostly add heat.
        val engine = runCatching { MtEngine.open(store.file(spec).absolutePath, minOf(threads, MT_THREADS)) }.getOrNull()
        if (engine == null) {
            _mtState.value = EngineState.Error("Could not load ${spec.title}")
            return@withContext null
        }
        mt = engine
        _mtState.value = EngineState.Ready(spec)
        engine
    }

    suspend fun ensureLlm(): LlmEngine? = withContext(llmDispatcher) {
        val spec = selectedLlm()
        if (spec == null) {
            _llmState.value = EngineState.Error("No language model installed")
            return@withContext null
        }
        llm?.let { if (llmSpec?.id == spec.id) return@withContext it }
        llm?.close()
        llm = null
        _llmState.value = EngineState.Loading(spec.title)
        val engine = runCatching {
            LlmEngine.init(context.applicationInfo.nativeLibraryDir)
            LlmEngine.open(store.file(spec).absolutePath, CONTEXT_TOKENS, threads)
        }.getOrNull()
        if (engine == null) {
            _llmState.value = EngineState.Error("Could not load ${spec.title}")
            return@withContext null
        }
        llm = engine
        llmSpec = spec
        _llmState.value = EngineState.Ready(spec)
        engine
    }

    /** Listening started: the models must stay in memory. */
    fun keep() {
        release?.cancel()
        release = null
    }

    /**
     * Listening stopped. The models stay loaded for a short while, so starting again is
     * instant, and are then freed: an idle Bhasha should hold no model memory at all.
     */
    fun releaseWhenIdle(afterMillis: Long = IDLE_RELEASE_MS) {
        release?.cancel()
        release = scope.launch {
            delay(afterMillis)
            releaseNow()
        }
    }

    /** Frees all models. They are loaded again the next time they are needed. */
    suspend fun releaseNow() {
        withContext(asrDispatcher) {
            asr?.close()
            asr = null
            asrKey = null
            _asrState.value = EngineState.Idle
        }
        withContext(mtDispatcher) {
            mt?.close()
            mt = null
            _mtState.value = EngineState.Idle
        }
        withContext(llmDispatcher) {
            llm?.close()
            llm = null
            llmSpec = null
            _llmState.value = EngineState.Idle
        }
    }

    /** Interrupts a generation in progress (safe from any thread). */
    fun cancelLlm() {
        llm?.cancel()
        mt?.cancel()
    }

    companion object {
        const val CONTEXT_TOKENS = 4096
        private const val MT_THREADS = 2
        private const val IDLE_RELEASE_MS = 3 * 60 * 1000L
    }
}
