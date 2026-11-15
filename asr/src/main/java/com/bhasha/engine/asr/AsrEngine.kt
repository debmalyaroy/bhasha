// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.engine.asr

import androidx.annotation.Keep
import java.io.Closeable

/** One recognised utterance. Constructed from native code. */
@Keep
class AsrResult(utf8: ByteArray, val language: String, val audioSeconds: Float, val decodeMillis: Float) {
    val text: String = String(utf8, Charsets.UTF_8)
}

/**
 * Streaming speech recogniser: Silero VAD cuts the microphone stream into
 * utterances; a per-language model decodes them when one is installed for the
 * language, whisper.cpp otherwise.
 *
 * [push] may be called from the audio thread while another thread is blocked
 * in [next]; everything else must not race with [close].
 */
class AsrEngine private constructor(private var handle: Long) : Closeable {

    /** Feed 16 kHz mono PCM. Returns the number of utterances waiting for [next]. */
    fun push(pcm: ShortArray, count: Int = pcm.size): Int = nativePush(handle, pcm, count)

    /** Close the utterance in progress so [next] can return it. */
    fun flush(): Int = nativeFlush(handle)

    /** Drop all buffered audio. */
    fun reset() = nativeReset(handle)

    /** Latest speech probability, 0..1. */
    val speechProbability: Float get() = nativeSpeechProb(handle)

    /**
     * Decode the oldest queued utterance, or return null when none is waiting.
     * [language] is a whisper code ("hi"), "auto", or a comma separated set to pick from ("hi,bn,kn").
     * Blocks for the duration of the decode.
     */
    fun next(language: String, translateToEnglish: Boolean): AsrResult? =
        nativeNext(handle, language, translateToEnglish)

    /**
     * Live preview of the sentence still being spoken, or null when nobody is speaking or
     * [language] (a single code) has no model of its own. Does not consume the audio.
     */
    fun partial(language: String): AsrResult? = nativePartial(handle, language)

    /** Recognise one complete clip. */
    fun transcribe(pcm: ShortArray, count: Int, language: String, translateToEnglish: Boolean = false): AsrResult? =
        nativeTranscribe(handle, pcm, count, language, translateToEnglish)

    /** Interrupt a decode running on another thread. */
    fun abort() = nativeAbort(handle)

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) nativeClose(h)
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        init {
            System.loadLibrary("bhasha_asr")
        }

        /**
         * Loads the models; slow (hundreds of ms to seconds). Returns null if a model cannot be read.
         *
         * [whisperPath] may be null when [langModelPattern] is given: a path containing "{lang}"
         * that names the per-language models, with [langTokens] their shared token table.
         */
        fun open(
            whisperPath: String?,
            vadModelPath: String?,
            threads: Int,
            fastEncode: Boolean = false,
            langModelPattern: String? = null,
            langTokens: String? = null,
        ): AsrEngine? {
            val h = nativeOpen(whisperPath ?: "", vadModelPath ?: "", threads, fastEncode, langModelPattern ?: "", langTokens ?: "")
            return if (h == 0L) null else AsrEngine(h)
        }

        @JvmStatic private external fun nativeOpen(
            model: String, vad: String, threads: Int, fastEncode: Boolean, langModels: String, langTokens: String,
        ): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativePush(handle: Long, pcm: ShortArray, n: Int): Int
        @JvmStatic private external fun nativeFlush(handle: Long): Int
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeSpeechProb(handle: Long): Float
        @JvmStatic private external fun nativeNext(handle: Long, lang: String, translate: Boolean): AsrResult?
        @JvmStatic private external fun nativePartial(handle: Long, lang: String): AsrResult?
        @JvmStatic private external fun nativeTranscribe(handle: Long, pcm: ShortArray, n: Int, lang: String, translate: Boolean): AsrResult?
        @JvmStatic private external fun nativeAbort(handle: Long)
    }
}
