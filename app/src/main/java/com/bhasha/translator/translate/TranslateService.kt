// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.translate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bhasha.engine.asr.AsrEngine
import com.bhasha.translator.BhashaApp
import com.bhasha.translator.MainActivity
import com.bhasha.translator.R
import com.bhasha.translator.audio.MicFactory
import com.bhasha.translator.core.Lang
import com.bhasha.translator.core.Settings
import com.bhasha.translator.core.TranslateMode
import com.bhasha.translator.learn.privateAudioOutput
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that keeps listening and translating while the app is in
 * the background or the screen is off.
 *
 *   microphone thread -> AsrEngine.push()   (VAD cuts the stream into utterances)
 *   worker coroutine  -> AsrEngine.next()   (whisper)  -> Translator (LLM) -> captions
 */
class TranslateService : LifecycleService() {
    private val app get() = application as BhashaApp
    private val session get() = app.session
    private var job: Job? = null
    private var micThread: Thread? = null
    @Volatile private var capturing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val ids = AtomicLong(System.currentTimeMillis())
    @Volatile private var privateOutput = false
    @Volatile private var outputCheckedAt = 0L

    // What the status line says, so the user can tell a sentence was heard and is being worked on.
    @Volatile private var label = "Listening"   // the idle text; always starts with "Listening"
    @Volatile private var working = false       // a sentence is being transcribed or translated
    @Volatile private var queued = 0            // finished sentences waiting for the recogniser

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            session.status.value = "Microphone permission is required"
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        job = lifecycleScope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val engines = app.engines
        val settings = app.settings
        session.running.value = true
        try {
            session.status.value = "Loading speech model…"
            val asr = engines.ensureAsr()
            if (asr == null) {
                session.status.value = "Speech model could not be loaded. Download one in Models."
                return
            }

            // Decide how to translate with what is actually installed.
            val asrSpec = engines.selectedAsr()
            var mode = settings.mode.value
            if (mode == TranslateMode.DIRECT && asrSpec?.canTranslate != true) mode = TranslateMode.LLM
            var useLlm = mode == TranslateMode.LLM
            if (useLlm) {
                session.status.value = "Loading language model…"
                if (engines.ensureLlm() == null) {
                    useLlm = false
                    if (asrSpec?.canTranslate == true) mode = TranslateMode.DIRECT
                }
            }
            val direct = mode == TranslateMode.DIRECT
            val transcriptOnly = !direct && !useLlm

            app.translator.reset()
            asr.reset()
            acquireWakeLock()
            if (!startMicrophone(asr)) {
                session.status.value = "Could not open the microphone"
                return
            }
            label = when {
                transcriptOnly -> "Listening - transcript only (download a language model to translate)"
                direct -> "Listening - direct speech translation"
                else -> "Listening"
            }
            working = false
            session.status.value = label

            while (currentCoroutineContext().isActive && capturing) {
                val lang = recogniserLanguage(settings.sourceLang.value)
                if (queued > 0) {
                    working = true
                    session.status.value = "$label · sentence heard, transcribing…"
                }
                val result = withContext(engines.asrDispatcher) { asr.next(lang, direct) }
                queued = 0   // refreshed by the microphone thread within 100 ms
                if (result == null) {
                    working = false
                    delay(40)
                    continue
                }
                val text = result.text.trim()
                trace { "heard [${result.language}] ${result.decodeMillis} ms: $text" }
                if (text.count { it.isLetter() } < 2) continue

                val id = ids.incrementAndGet()
                val asrMs = result.decodeMillis.toInt()
                if (direct || transcriptOnly) {
                    session.add(
                        Caption(id, if (direct) "" else text, if (direct) text else "", result.language, false, asrMs)
                    )
                    updateNotification(text)
                    if (direct) speak(text)
                    continue
                }

                session.add(Caption(id, text, "", result.language, true, asrMs))
                working = true
                session.status.value = "$label · translating…"
                val started = System.nanoTime()
                val english = app.translator.translate(text, result.language) { partial ->
                    session.update(id) { it.copy(english = partial) }
                }
                val ms = ((System.nanoTime() - started) / 1_000_000).toInt()
                trace { "translated $ms ms: $english" }
                session.update(id) {
                    it.copy(english = english ?: it.english, pending = false, translateMillis = ms)
                }
                if (!english.isNullOrBlank()) {
                    updateNotification(english)
                    speak(english)
                }
            }
        } finally {
            stopMicrophone()
            app.speaker.stop()
            releaseWakeLock()
            session.running.value = false
            session.level.value = 0f
            if (session.status.value.startsWith("Listening")) session.status.value = ""
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Reads a translation aloud through the current output (headphones, Bluetooth, or the phone speaker). */
    private fun speak(english: String) {
        if (app.settings.speakTranslations.value) app.speaker.speakEnglish(english)
    }

    /**
     * True while the microphone should be ignored because the app itself is
     * talking: always when the sound comes out of a loudspeaker, and on
     * headphones only if the user asked for it.
     */
    private fun shouldIgnoreMic(): Boolean {
        if (!app.settings.speakTranslations.value || !app.speaker.isAudible()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - outputCheckedAt > 2000) {
            privateOutput = privateAudioOutput(this)
            outputCheckedAt = now
        }
        return !privateOutput || app.settings.pauseMicWhileSpeaking.value
    }

    private fun showStatus(text: String) {
        if (capturing && session.status.value != text) session.status.value = text
    }

    private fun recogniserLanguage(setting: String): String =
        if (setting == Settings.AUTO) Lang.detectSet else setting

    @SuppressLint("MissingPermission")   // checked in onStartCommand
    private fun startMicrophone(asr: AsrEngine): Boolean {
        val rate = AsrEngine.SAMPLE_RATE
        val mic = MicFactory.open(bufferSamples = rate * 2) ?: return false   // one second of headroom
        capturing = true
        micThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ShortArray(rate / 10)   // 100 ms
            var ignoring = false
            var peak = 0
            var reported = SystemClock.elapsedRealtime()
            try {
                mic.start()
                while (capturing) {
                    val n = mic.read(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (n <= 0) continue
                    if (TRACE) {
                        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(chunk[i].toInt()))
                        val now = SystemClock.elapsedRealtime()
                        if (now - reported >= 1000) {
                            Log.d(TAG, "mic peak=$peak speech=${asr.speechProbability} ignoring=$ignoring")
                            peak = 0
                            reported = now
                        }
                    }
                    if (shouldIgnoreMic()) {
                        // Close whatever was being said so it is not glued to audio from after the pause.
                        if (!ignoring) asr.flush()
                        ignoring = true
                        session.level.value = 0f
                        if (!working) showStatus("$label · speaking the translation, mic paused")
                        continue
                    }
                    ignoring = false
                    queued = asr.push(chunk, n)
                    val speech = asr.speechProbability
                    session.level.value = speech
                    if (!working) showStatus(if (speech > 0.5f) "$label · hearing speech…" else label)
                }
            } finally {
                mic.close()
                capturing = false
            }
        }, "bhasha-mic").also { it.start() }
        return true
    }

    private fun stopMicrophone() {
        capturing = false
        micThread?.let { t -> runCatching { t.join(1500) } }
        micThread = null
    }

    private fun stopListening() {
        capturing = false
        app.engines.cancelLlm()
        val running = job
        if (running == null || !running.isActive) {
            stopSelf()
        } else {
            running.cancel()
        }
    }

    override fun onDestroy() {
        capturing = false
        job?.cancel()
        releaseWakeLock()
        session.running.value = false
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bhasha:listening").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)   // safety cap: 4 hours
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Live translation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Bhasha is listening"
                }
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Bhasha is listening")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private inline fun trace(message: () -> String) {
        if (TRACE) Log.d(TAG, message())
    }

    companion object {
        private const val TAG = "Bhasha"

        /** Diagnostics are off unless enabled with `adb shell setprop log.tag.Bhasha DEBUG`. */
        private val TRACE = Log.isLoggable(TAG, Log.DEBUG)
        private const val CHANNEL = "listening"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.bhasha.translator.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TranslateService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TranslateService::class.java).setAction(ACTION_STOP))
        }
    }
}
