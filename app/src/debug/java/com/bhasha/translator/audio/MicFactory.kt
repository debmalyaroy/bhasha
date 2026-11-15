package com.bhasha.translator.audio

import android.util.Log
import com.bhasha.engine.asr.AsrEngine
import java.io.File

/**
 * Debug builds only. If the folder [TEST_DIR] exists, the app "hears" the raw
 * PCM files (16 kHz, mono, 16-bit, name ending .pcm) dropped into it, played at
 * real-time speed with silence in between, instead of the microphone. This is
 * for emulators, whose virtual microphone cannot be fed reliably.
 *
 *   adb shell mkdir /sdcard/Android/data/com.bhasha.translator/files/test-mic
 *   adb push clip.pcm /sdcard/Android/data/com.bhasha.translator/files/test-mic/clip.part
 *   adb shell mv .../test-mic/clip.part .../test-mic/clip.pcm
 */
object MicFactory {
    private val TEST_DIR = File("/storage/emulated/0/Android/data/com.bhasha.translator/files/test-mic")

    fun open(bufferSamples: Int): MicInput? =
        if (TEST_DIR.isDirectory) FileMic(TEST_DIR) else DeviceMic.open(bufferSamples)
}

private class FileMic(private val dir: File) : MicInput {
    private var clip = ShortArray(0)
    private var pos = 0
    private var startNanos = 0L
    private var delivered = 0L
    private var lastScan = 0L
    @Volatile private var closed = false

    override fun start() {
        startNanos = System.nanoTime()
        Log.d("Bhasha", "test microphone: reading clips from $dir")
    }

    override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
        if (closed) return -1
        // Pace like a real microphone: sample n is not available before n / rate seconds.
        val dueNanos = startNanos + (delivered + length) * 1_000_000_000L / AsrEngine.SAMPLE_RATE
        val waitMs = (dueNanos - System.nanoTime()) / 1_000_000
        if (waitMs > 0) Thread.sleep(waitMs)

        if (pos >= clip.size) nextClip()
        val n = minOf(length, clip.size - pos)
        if (n > 0) {
            System.arraycopy(clip, pos, buffer, offset, n)
            pos += n
        }
        buffer.fill(0, offset + maxOf(n, 0), offset + length)
        delivered += length
        return length
    }

    private fun nextClip() {
        val now = System.nanoTime()
        if (now - lastScan < 200_000_000L) return
        lastScan = now
        val file = dir.listFiles { f -> f.name.endsWith(".pcm") }?.minByOrNull { it.name } ?: return
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        file.delete()
        clip = ShortArray(bytes.size / 2) { i ->
            ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort()
        }
        pos = 0
        Log.d("Bhasha", "test microphone: playing ${file.name}, ${clip.size / AsrEngine.SAMPLE_RATE} s")
    }

    override fun close() {
        closed = true
    }
}
