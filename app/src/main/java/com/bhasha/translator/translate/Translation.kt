// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.translate

import com.bhasha.engine.llm.ChatMessage
import com.bhasha.translator.core.Lang
import com.bhasha.translator.engine.Engines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One utterance heard by the microphone and its translation. */
data class Caption(
    val id: Long,
    /** Text in the spoken language; empty in direct mode. */
    val original: String,
    val english: String,
    val lang: String,
    /** True while the translation is still being generated. */
    val pending: Boolean,
    val asrMillis: Int,
    val translateMillis: Int = 0,
)

/** Live state of the listening session; survives the UI because the service outlives the activity. */
class TranslationSession {
    val captions = MutableStateFlow<List<Caption>>(emptyList())
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val level = MutableStateFlow(0f)

    fun add(caption: Caption) = captions.update { (it + caption).takeLast(MAX_CAPTIONS) }

    fun update(id: Long, transform: (Caption) -> Caption) =
        captions.update { list -> list.map { if (it.id == id) transform(it) else it } }

    fun clear() {
        captions.value = emptyList()
    }

    companion object {
        private const val MAX_CAPTIONS = 200
    }
}

/**
 * Text translation with the local LLM. A few previous utterances are passed
 * along as conversation so pronouns and topic carry over; the window is cut
 * back in blocks rather than slid one at a time, which keeps most of the
 * prompt identical between calls so the engine can reuse its KV cache.
 */
class Translator(private val engines: Engines) {
    private val history = ArrayList<Pair<String, String>>()
    private var historyLang: String? = null

    fun reset() {
        history.clear()
        historyLang = null
    }

    /** Returns the English text, or null if no LLM is available or generation failed. */
    suspend fun translate(text: String, langCode: String, onPartial: (String) -> Unit): String? {
        val llm = engines.ensureLlm() ?: return null
        return withContext(engines.llmDispatcher) {
            if (historyLang != langCode) {
                history.clear()
                historyLang = langCode
            }
            val messages = buildList {
                add(ChatMessage.system(systemPrompt(Lang.fromCode(langCode))))
                history.forEach { (src, en) ->
                    add(ChatMessage.user(src))
                    add(ChatMessage.assistant(en))
                }
                add(ChatMessage.user(text))
            }
            val partial = StringBuilder()
            val reply = llm.chat(messages, maxTokens = 200, temperature = 0f) { piece ->
                partial.append(piece)
                onPartial(partial.toString())
                true
            }?.trim()
            if (!reply.isNullOrEmpty()) {
                history.add(text to reply)
                if (history.size > MAX_HISTORY) history.subList(0, history.size - 1).clear()
            }
            reply
        }
    }

    private fun systemPrompt(lang: Lang?): String {
        val name = lang?.english ?: "Hindi, Bengali or Kannada"
        return "You are a simultaneous interpreter. Each user message is $name speech that was " +
            "transcribed by a speech recogniser, so it may contain recognition errors, missing " +
            "punctuation or English words mixed in. Reply with a faithful, natural English " +
            "translation of what was most likely said. Output only the translation: no notes, " +
            "no quotation marks, no original text."
    }

    companion object {
        private const val MAX_HISTORY = 4
    }
}
