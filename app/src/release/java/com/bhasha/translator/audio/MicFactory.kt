package com.bhasha.translator.audio

object MicFactory {
    fun open(bufferSamples: Int): MicInput? = DeviceMic.open(bufferSamples)
}
