// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.engine.llm

import androidx.annotation.Keep
import java.io.Closeable

/** Receives generated text as it is produced. Called from native code on the generating thread. */
@Keep
fun interface TokenListener {
    /** [utf8] always holds whole characters. Return false to stop generating. */
    fun onToken(utf8: ByteArray): Boolean
}

data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
    }
}

data class LlmStats(
    val promptTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val promptMillis: Float,
    val outputMillis: Float,
) {
    val tokensPerSecond: Float get() = if (outputMillis > 0f) outputTokens * 1000f / outputMillis else 0f
}

/**
 * A GGUF chat model running through llama.cpp. [chat] is stateless from the
 * caller's point of view, but the engine keeps its KV cache and reuses the
 * common prefix of consecutive prompts, so a fixed system prompt costs nothing
 * after the first call.
 */
class LlmEngine private constructor(private var handle: Long) : Closeable {

    @Volatile
    var lastStats: LlmStats? = null
        private set

    /** Blocks until the reply is complete. Returns null on failure (e.g. prompt longer than the context). */
    fun chat(
        messages: List<ChatMessage>,
        maxTokens: Int = 256,
        temperature: Float = 0f,
        onText: ((String) -> Boolean)? = null,
    ): String? {
        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content.toByteArray(Charsets.UTF_8) }
        val listener = onText?.let { cb -> TokenListener { bytes -> cb(String(bytes, Charsets.UTF_8)) } }
        val stats = FloatArray(5)
        val out = nativeChat(handle, roles, contents, maxTokens, temperature, listener, stats) ?: return null
        lastStats = LlmStats(stats[0].toInt(), stats[1].toInt(), stats[2].toInt(), stats[3], stats[4])
        return String(out, Charsets.UTF_8)
    }

    /** Stop a [chat] call running on another thread. */
    fun cancel() = nativeCancel(handle)

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) nativeClose(h)
    }

    companion object {
        @Volatile
        private var initialised = false

        /**
         * Must be called once before [open]. [nativeLibraryDir] is
         * `context.applicationInfo.nativeLibraryDir`; the CPU backend variants are loaded from it,
         * which requires the app to extract native libraries at install time
         * (`packaging.jniLibs.useLegacyPackaging = true`).
         */
        @Synchronized
        fun init(nativeLibraryDir: String) {
            if (initialised) return
            System.loadLibrary("bhasha_llm")
            nativeInit(nativeLibraryDir)
            initialised = true
        }

        /** Loads the model; slow (seconds). Returns null if it cannot be loaded. */
        fun open(modelPath: String, contextTokens: Int, threads: Int): LlmEngine? {
            check(initialised) { "LlmEngine.init() was not called" }
            val h = nativeOpen(modelPath, contextTokens, threads)
            return if (h == 0L) null else LlmEngine(h)
        }

        @JvmStatic private external fun nativeInit(backendDir: String)
        @JvmStatic private external fun nativeOpen(model: String, nCtx: Int, threads: Int): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeChat(
            handle: Long, roles: Array<String>, contents: Array<ByteArray>,
            maxTokens: Int, temperature: Float, listener: TokenListener?, stats: FloatArray,
        ): ByteArray?
        @JvmStatic private external fun nativeCancel(handle: Long)
    }
}
