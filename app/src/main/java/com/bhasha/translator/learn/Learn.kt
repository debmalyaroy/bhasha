// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.learn

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import com.bhasha.engine.asr.AsrEngine
import com.bhasha.translator.audio.MicFactory
import com.bhasha.translator.core.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- content

@Serializable
data class Course(
    val code: String,
    val name: String,
    val nativeName: String,
    val script: String,
    val ttsLocale: String,
    val letters: List<LetterGroup>,
    val units: List<LessonUnit>,
    /** How the script and the sounds work; shown above the letter chart. */
    val about: List<GuideSection> = emptyList(),
)

/** A piece of explanation: how something works, with examples to hear. */
@Serializable
data class GuideSection(val title: String, val text: String, val examples: List<Phrase> = emptyList())

@Serializable
data class LetterGroup(val group: String, val items: List<Letter>, val note: String = "")

@Serializable
data class Letter(
    val char: String,
    val roman: String,
    val example: String = "",
    val exampleRoman: String = "",
    val exampleMeaning: String = "",
    /** How to say it, or what it is easily confused with. */
    val note: String = "",
)

@Serializable
data class LessonUnit(
    val id: String,
    val title: String,
    val description: String = "",
    val phrases: List<Phrase>,
    /** The stage of the course this unit belongs to; units of one stage are listed together. */
    val level: String = "",
    /** The teaching part of the unit, read before the phrases. */
    val guide: List<GuideSection> = emptyList(),
)

@Serializable
data class Phrase(
    val native: String,
    val roman: String,
    val english: String,
    val note: String = "",
    /** Word-for-word gloss, in the order of the original. */
    val literal: String = "",
)

/** Lessons bundled in assets/learn/<code>.json. */
class LearnRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<Lang, Course>()

    suspend fun course(lang: Lang): Course? = cache[lang] ?: withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("learn/${lang.code}.json").use { input ->
                json.decodeFromString<Course>(input.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrNull()?.also { cache[lang] = it }
    }
}

// ---------------------------------------------------------------- progress

/** Best quiz and speaking scores (percent) per unit. */
class Progress(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val _scores = MutableStateFlow(
        prefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    )
    val scores: StateFlow<Map<String, Int>> = _scores

    fun record(kind: String, lang: Lang, unitId: String, percent: Int) {
        val key = key(kind, lang, unitId)
        if (percent <= (_scores.value[key] ?: -1)) return
        _scores.value = _scores.value + (key to percent)
        prefs.edit().putInt(key, percent).apply()
    }

    companion object {
        const val QUIZ = "quiz"
        const val SPEAK = "speak"
        fun key(kind: String, lang: Lang, unitId: String) = "$kind/${lang.code}/$unitId"
    }
}

// ---------------------------------------------------------------- speech out

/** Pronunciation playback through the phone's (offline-capable) text-to-speech voices. */
class Speaker(context: Context) {
    @Volatile private var ready = false
    private val pending = AtomicInteger(0)
    @Volatile private var lastActive = 0L
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { lastActive = SystemClock.elapsedRealtime() }
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
    }

    private fun finished() {
        lastActive = SystemClock.elapsedRealtime()
        if (pending.decrementAndGet() < 0) pending.set(0)
    }

    /**
     * True while speech is playing or queued, and for [tailMillis] afterwards
     * (room echo). The listening service uses this to avoid hearing itself.
     */
    fun isAudible(tailMillis: Long = 400): Boolean =
        pending.get() > 0 || SystemClock.elapsedRealtime() - lastActive < tailMillis

    /** Speaks a phrase in [lang], replacing anything already playing. Returns false when the phone has no voice for it. */
    fun speak(text: String, lang: Lang, slow: Boolean = false): Boolean =
        say(text, lang.locale, if (slow) 0.6f else 0.9f, TextToSpeech.QUEUE_FLUSH)

    /**
     * Queues an English translation behind what is already being spoken. If the
     * queue falls behind the conversation, older items are dropped so the voice
     * stays close to real time.
     */
    fun speakEnglish(text: String): Boolean {
        val mode = if (pending.get() >= MAX_QUEUED) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return say(text, Locale.US, 1.05f, mode)
    }

    private fun say(text: String, locale: Locale, rate: Float, queueMode: Int): Boolean {
        if (!ready || text.isBlank()) return false
        val available = tts.setLanguage(locale)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.setSpeechRate(rate)
        if (queueMode == TextToSpeech.QUEUE_FLUSH) pending.set(0)
        pending.incrementAndGet()
        lastActive = SystemClock.elapsedRealtime()
        if (tts.speak(text, queueMode, null, "bhasha-${ids.incrementAndGet()}") != TextToSpeech.SUCCESS) {
            pending.decrementAndGet()
            return false
        }
        return true
    }

    fun stop() {
        if (ready) tts.stop()
        pending.set(0)
    }

    private val ids = AtomicInteger(0)

    companion object {
        private const val MAX_QUEUED = 3
    }
}

/**
 * Whether sound is going to something worn on the ear (so the microphone will
 * not pick it up). Bluetooth audio counts: earbuds and speakers cannot be told
 * apart, which is why pausing the mic stays a user setting.
 */
fun privateAudioOutput(context: Context): Boolean {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        when (it.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> true
            else -> false
        }
    }
}

// ---------------------------------------------------------------- speech in

/** Records one short clip for pronunciation practice. */
class ClipRecorder {
    @Volatile private var recording = false

    fun stop() {
        recording = false
    }

    /** Records until [stop] is called or [maxSeconds] elapse. Returns 16 kHz mono PCM, or null on failure. */
    @SuppressLint("MissingPermission")   // callers check RECORD_AUDIO
    suspend fun record(maxSeconds: Int = 8): ShortArray? = withContext(Dispatchers.IO) {
        val rate = AsrEngine.SAMPLE_RATE
        val mic = MicFactory.open(bufferSamples = rate) ?: return@withContext null
        val out = ShortArray(rate * maxSeconds)
        var filled = 0
        recording = true
        try {
            mic.start()
            while (recording && filled < out.size) {
                val n = mic.read(out, filled, minOf(rate / 10, out.size - filled))
                if (n < 0) break
                filled += n
            }
        } finally {
            recording = false
            mic.close()
        }
        if (filled < rate / 4) null else out.copyOf(filled)
    }
}

/**
 * How closely what the recogniser heard matches the target phrase, 0..100.
 * Compared on letters only, so punctuation and spacing do not count.
 */
fun similarityPercent(target: String, heard: String): Int {
    val a = normalise(target)
    val b = normalise(heard)
    if (a.isEmpty() || b.isEmpty()) return 0
    var prev = IntArray(b.size + 1) { it }
    var cur = IntArray(b.size + 1)
    for (i in 1..a.size) {
        cur[0] = i
        for (j in 1..b.size) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    val distance = prev[b.size]
    return ((1f - distance.toFloat() / maxOf(a.size, b.size)) * 100).toInt().coerceIn(0, 100)
}

/**
 * Score for a spoken attempt at [phrase]. The recogniser sometimes writes what
 * it heard in Latin letters instead of the language's script; such a transcript
 * is compared with the phrase's romanisation rather than counted as wrong.
 */
fun speechMatchPercent(phrase: Phrase, heard: String): Int {
    val letters = heard.count { it.isLetter() }
    val latin = heard.count { it.isLetter() && it.code < 0x250 }
    return if (letters > 0 && latin * 2 > letters) similarityPercent(foldLatin(phrase.roman), foldLatin(heard))
    else similarityPercent(phrase.native, heard)
}

/** Lower-case, accents removed, doubled letters collapsed ("aa" and "á" both become "a"). */
private fun foldLatin(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .filter { it in 'a'..'z' }
        .replace(Regex("(.)\\1+"), "$1")

private fun normalise(s: String): IntArray =
    s.lowercase().codePoints().filter { Character.isLetter(it) || Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() }.toArray()

// ---------------------------------------------------------------- tutor

fun tutorSystemPrompt(lang: Lang): String =
    "You are a friendly, encouraging ${lang.english} tutor for an English speaker who is a beginner. " +
        "Teach the words and phrases ${lang.english} speakers really use, never English words spelled " +
        "in ${lang.english} script. Whenever you write ${lang.english}, use the native script and follow " +
        "it with a simple romanisation in parentheses and the English meaning, like this: " +
        "${lang.native} (romanisation) - meaning. Keep answers short: a few sentences " +
        "or a small list. Explain grammar in plain English with one example. If the learner writes " +
        "in ${lang.english}, gently correct mistakes first, then reply. Ask a short follow-up " +
        "question to keep them practising."
