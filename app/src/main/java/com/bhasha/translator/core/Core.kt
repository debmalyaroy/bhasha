// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/** Languages the app translates from and teaches. */
enum class Lang(val code: String, val english: String, val native: String, val localeTag: String) {
    HINDI("hi", "Hindi", "हिन्दी", "hi-IN"),
    BENGALI("bn", "Bengali", "বাংলা", "bn-IN"),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ", "kn-IN");

    val locale: Locale get() = Locale.forLanguageTag(localeTag)

    companion object {
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code == code }

        /** Value for the recogniser meaning "pick whichever of our languages is being spoken". */
        val detectSet: String = entries.joinToString(",") { it.code }
    }
}

enum class TranslateMode(val id: String) {
    /** Whisper transcribes in the spoken language, the LLM translates. Best quality; shows the original. */
    LLM("llm"),

    /** Whisper's built-in speech-to-English task. One model, lowest latency, rougher output. */
    DIRECT("direct");

    companion object {
        fun fromId(id: String?): TranslateMode = entries.firstOrNull { it.id == id } ?: LLM
    }
}

/** User preferences, observable and persisted. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _sourceLang = MutableStateFlow(prefs.getString(KEY_SOURCE, Lang.HINDI.code) ?: Lang.HINDI.code)
    private val _mode = MutableStateFlow(TranslateMode.fromId(prefs.getString(KEY_MODE, null)))
    private val _asrModel = MutableStateFlow(prefs.getString(KEY_ASR, "") ?: "")
    private val _llmModel = MutableStateFlow(prefs.getString(KEY_LLM, "") ?: "")

    /** A [Lang.code], or [AUTO] to detect among the supported languages. */
    val sourceLang: StateFlow<String> = _sourceLang
    val mode: StateFlow<TranslateMode> = _mode
    val asrModelId: StateFlow<String> = _asrModel
    val llmModelId: StateFlow<String> = _llmModel

    fun setSourceLang(code: String) = put(KEY_SOURCE, code, _sourceLang)
    fun setAsrModel(id: String) = put(KEY_ASR, id, _asrModel)
    fun setLlmModel(id: String) = put(KEY_LLM, id, _llmModel)
    private val _speak = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, true))
    private val _pauseMic = MutableStateFlow(prefs.getBoolean(KEY_PAUSE_MIC, false))

    /** Read English translations aloud through the current audio output. */
    val speakTranslations: StateFlow<Boolean> = _speak

    /**
     * Stop listening while a translation is being spoken even when headphones
     * are connected. (Without headphones the mic always pauses, otherwise the
     * app would hear and translate its own voice.) Meant for Bluetooth speakers.
     */
    val pauseMicWhileSpeaking: StateFlow<Boolean> = _pauseMic

    fun setSpeakTranslations(on: Boolean) {
        _speak.value = on
        prefs.edit().putBoolean(KEY_SPEAK, on).apply()
    }

    fun setPauseMicWhileSpeaking(on: Boolean) {
        _pauseMic.value = on
        prefs.edit().putBoolean(KEY_PAUSE_MIC, on).apply()
    }

    fun setMode(mode: TranslateMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.id).apply()
    }

    private fun put(key: String, value: String, flow: MutableStateFlow<String>) {
        flow.value = value
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        const val AUTO = "auto"
        private const val KEY_SOURCE = "source_lang"
        private const val KEY_MODE = "translate_mode"
        private const val KEY_ASR = "asr_model"
        private const val KEY_LLM = "llm_model"
        private const val KEY_SPEAK = "speak_translations"
        private const val KEY_PAUSE_MIC = "pause_mic_while_speaking"
    }
}

/**
 * Number of inference threads: one per "big" core. Efficiency cores slow a
 * matrix-multiply thread pool down (every step waits for the slowest worker),
 * so they are left out.
 */
fun performanceThreads(): Int {
    val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }.getOrNull()
    }
    if (freqs.isEmpty()) return 4
    val top = freqs.max()
    val big = freqs.count { it >= top * 0.75 }
    return big.coerceIn(2, 6)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}
