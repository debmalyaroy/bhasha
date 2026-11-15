// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.engine.asr

import java.io.Closeable

/**
 * Text translation from Hindi, Bengali or Kannada to English with IndicTrans2.
 * It shares this library with the recogniser because both run on ONNX Runtime.
 *
 * One translation at a time; [cancel] may be called from any thread.
 */
class MtEngine private constructor(private var handle: Long) : Closeable {

    /** English for [text] in [language] ("hi", "bn" or "kn"), or null on failure. Blocks while it works. */
    fun translate(language: String, text: String): String? =
        nativeTranslate(handle, language.toByteArray(), text.toByteArray())?.let { String(it, Charsets.UTF_8) }

    /** Stops a translation running on another thread; it returns what it has so far. */
    fun cancel() {
        val h = handle
        if (h != 0L) nativeCancel(h)
    }

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) nativeClose(h)
    }

    companion object {
        val LANGUAGES = setOf("hi", "bn", "kn")

        init {
            System.loadLibrary("bhasha_asr")
        }

        /** Loads the model file; takes a second or two. Returns null if it cannot be read. */
        fun open(path: String, threads: Int): MtEngine? {
            val h = nativeOpen(path.toByteArray(), threads)
            return if (h == 0L) null else MtEngine(h)
        }

        @JvmStatic private external fun nativeOpen(path: ByteArray, threads: Int): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeTranslate(handle: Long, lang: ByteArray, text: ByteArray): ByteArray?
        @JvmStatic private external fun nativeCancel(handle: Long)
    }
}
