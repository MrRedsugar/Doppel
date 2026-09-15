package dev.doppel.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Prepare a quiet recording once before ASR; never change duration or retry the provider. */
internal object SpeechSignal {
    fun prepare(wav: ByteArray): ByteArray {
        DirectPayload.wavDuration(wav)
        val input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        var energy = 0L
        val count = (wav.size - 44) / 2
        for (index in 0 until count) {
            val sample = input.getShort(44 + index * 2).toInt()
            peak = maxOf(peak, abs(sample)); energy += sample.toLong() * sample
        }
        val rms = sqrt(energy.toDouble() / count)
        if (rms == 0.0 || rms >= 180 || peak >= 24000) return wav
        val gain = minOf(32.0, 1000.0 / rms, 24000.0 / peak)
        if (gain <= 1.0) return wav
        val result = wav.copyOf()
        val output = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) output.putShort(44 + index * 2,
            (input.getShort(44 + index * 2) * gain).roundToInt().coerceIn(-32768, 32767).toShort())
        return result
    }
}
