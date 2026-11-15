// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.bhasha.engine.asr.AsrEngine

/** A source of 16 kHz mono PCM. The app's capture code reads from this rather than from AudioRecord directly. */
interface MicInput {
    fun start()

    /** Blocks until samples are available. Returns the number read, or a negative value on failure. */
    fun read(buffer: ShortArray, offset: Int, length: Int): Int

    fun close()
}

/** The device microphone, tuned for speech recognition. */
class DeviceMic private constructor(private val record: AudioRecord) : MicInput {
    override fun start() = record.startRecording()
    override fun read(buffer: ShortArray, offset: Int, length: Int): Int = record.read(buffer, offset, length)
    override fun close() {
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        /** Null when the microphone cannot be opened. Callers must hold RECORD_AUDIO. */
        @SuppressLint("MissingPermission")
        fun open(bufferSamples: Int): DeviceMic? {
            val rate = AsrEngine.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return null
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, bufferSamples),
                )
            }.getOrNull() ?: return null
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return DeviceMic(record)
        }
    }
}
