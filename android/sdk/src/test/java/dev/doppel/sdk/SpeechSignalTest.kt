package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechSignalTest {
    private fun wave(amplitude: Int): ByteArray = CloudSpeechAudio().apply {
        repeat(50) { append(ShortArray(320) { if (it % 32 < 16) amplitude.toShort() else (-amplitude).toShort() }, 320) }
    }.snapshot().wav
    @Test fun quietSpeechIsAmplifiedWithoutClippingOrChangingInput() {
        val original = wave(30)
        val before = original.copyOf()
        val result = SpeechSignal.prepare(original)
        assertArrayEquals(before, original)
        assertEquals(original.size, result.size)
        assertArrayEquals(original.copyOfRange(0, 44), result.copyOfRange(0, 44))
        assertEquals(960, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getShort(44).toInt())
        assertEquals(-960, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getShort(76).toInt())
    }
    @Test fun normalSpeechAndSilenceRemainUntouched() {
        for (amplitude in listOf(0, 800, 12000)) {
            val original = wave(amplitude)
            assertArrayEquals(original, SpeechSignal.prepare(original))
        }
    }
    @Test fun loudTransientIsNeverClippedByQuietSignalGain() {
        val wav = wave(10)
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putShort(60, 25000)
        val result = SpeechSignal.prepare(wav)
        assertEquals(25000, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getShort(60).toInt())
    }
}
