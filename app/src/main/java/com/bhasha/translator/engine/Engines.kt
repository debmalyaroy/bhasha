// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.engine

import android.content.Context
import com.bhasha.engine.asr.AsrEngine
import com.bhasha.engine.llm.LlmEngine
import com.bhasha.translator.core.Settings
import com.bhasha.translator.core.performanceThreads
import com.bhasha.translator.model.ModelCatalog
import com.bhasha.translator.model.ModelKind
import com.bhasha.translator.model.ModelSpec
import com.bhasha.translator.model.ModelStore
import kotlinx.coroutines.CoroutineDispatcher
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
class Engines(private val context: Context, private val store: ModelStore, private val settings: Settings) {
    val asrDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "bhasha-asr") }.asCoroutineDispatcher()
    val llmDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "bhasha-llm") }.asCoroutineDispatcher()

    private val threads = performanceThreads()

    private val _asrState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val _llmState = MutableStateFlow<EngineState>(EngineState.Idle)
    val asrState: StateFlow<EngineState> = _asrState
    val llmState: StateFlow<EngineState> = _llmState

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

    /** Interrupts a generation in progress (safe from any thread). */
    fun cancelLlm() {
        llm?.cancel()
    }

    companion object {
        const val CONTEXT_TOKENS = 4096
    }
}
