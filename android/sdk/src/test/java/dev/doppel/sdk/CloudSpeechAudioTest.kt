package dev.doppel.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CloudSpeechAudioTest {
    @Test fun quietCommandRemainsEligibleForFinalRecognition() {
        val audio = CloudSpeechAudio()
        repeat(50) { audio.append(ShortArray(320) { if (it % 32 < 16) 150 else -150 }, 320) }
        val clip = audio.snapshot()
        assertTrue("A released non-silent command must not be discarded by the preview RMS threshold", clip.hasSpeech)
        assertFalse(clip.canPreview)
        assertEquals(16000, clip.sampleCount); assertEquals(150, clip.peak); assertEquals(150.0, clip.rms, 0.0)
    }

    @Test fun wavDescribesActualPcmAndPreservesSignedSamples() {
        val audio = CloudSpeechAudio()
        audio.append(shortArrayOf(0, 32767, -32768, -1), 4)
        val wav = audio.snapshot().wav
        val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(44, data.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, data.getInt(16))
        assertEquals(1, data.getShort(20).toInt())
        assertEquals(1, data.getShort(22).toInt())
        assertEquals(16000, data.getInt(24))
        assertEquals(32000, data.getInt(28))
        assertEquals(2, data.getShort(32).toInt())
        assertEquals(16, data.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(8, data.getInt(40))
        assertEquals(52, wav.size)
        assertEquals(32767, data.getShort(46).toInt())
        assertEquals(-32768, data.getShort(48).toInt())
        assertEquals(-1, data.getShort(50).toInt())
    }

    @Test fun bufferCapsAtThirtySecondsAndNeverMutatesPreviousSnapshot() {
        val audio = CloudSpeechAudio()
        audio.append(ShortArray(16000), 16000)
        val first = audio.snapshot()
        assertEquals(1000, first.durationMs)
        assertEquals(464000, audio.append(ShortArray(480000), 480000))
        assertTrue(audio.full)
        assertEquals(0, audio.append(shortArrayOf(1), 1))
        val last = audio.snapshot()
        assertEquals(30000, last.durationMs)
        assertEquals(960044, last.wav.size)
        assertEquals(32044, first.wav.size)
        assertEquals(32000, ByteBuffer.wrap(first.wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test fun silenceAndIsolatedClickAreNotSubmitted() {
        val silent = CloudSpeechAudio()
        repeat(25) { silent.append(ShortArray(320), 320) }
        assertFalse(silent.snapshot().hasSpeech)
        silent.append(ShortArray(320) { 12000 }, 320)
        assertFalse(silent.snapshot().hasSpeech)
    }

    @Test fun lowLevelAudioSkipsRepeatedPreviewButRemainsAvailableOnRelease() {
        val audio = CloudSpeechAudio()
        repeat(25) { audio.append(ShortArray(320) { if (it % 2 == 0) 80 else -80 }, 320) }
        assertFalse(audio.snapshot().canPreview)
        assertTrue("Final eligibility does not claim that the signal contains speech", audio.snapshot().hasSpeech)
        assertEquals(16044, audio.snapshot().wav.size)
    }

    @Test fun speechGatePreservesLeadingAndTrailingSilence() {
        val audio = CloudSpeechAudio()
        repeat(10) { audio.append(ShortArray(320), 320) }
        repeat(10) { audio.append(ShortArray(320) { if (it % 2 == 0) 1200 else -1200 }, 320) }
        repeat(10) { audio.append(ShortArray(320), 320) }
        val clip = audio.snapshot()
        assertTrue(clip.hasSpeech)
        assertTrue(clip.canPreview)
        assertEquals(600, clip.durationMs)
        assertEquals(19244, clip.wav.size)
        val samples = ByteBuffer.wrap(clip.wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, samples.getShort(44).toInt())
        assertEquals(1200, samples.getShort(44 + 6400).toInt())
        assertEquals(0, samples.getShort(clip.wav.lastIndex - 1).toInt())
    }

    @Test fun veryShortLoudInputIsNotSubmitted() {
        val audio = CloudSpeechAudio()
        repeat(5) { audio.append(ShortArray(320) { 2000 }, 320) }
        assertFalse(audio.snapshot().hasSpeech)
        assertFalse(audio.snapshot().canPreview)
    }

    private fun chunked(signal: ShortArray, chunkSize: Int): CloudSpeechAudio.Clip {
        val audio = CloudSpeechAudio()
        var offset = 0
        while (offset < signal.size) {
            val end = minOf(signal.size, offset + chunkSize)
            val part = signal.copyOfRange(offset, end)
            audio.append(part, part.size)
            offset = end
        }
        return audio.snapshot()
    }

    @Test fun finalAndPreviewDecisionsAreIndependentOfAppendChunkBoundaries() {
        val signal = ShortArray(16000) { if (it in 640 until 2240) 300 else 0 }
        val expected = chunked(signal, signal.size)
        assertTrue(expected.hasSpeech); assertTrue(expected.canPreview)
        for (size in listOf(1, 7, 127, 320, 777, 4096)) {
            val actual = chunked(signal, size)
            assertEquals(expected.hasSpeech, actual.hasSpeech); assertEquals(expected.canPreview, actual.canPreview)
            assertEquals(expected.sampleCount, actual.sampleCount); assertEquals(expected.durationMs, actual.durationMs)
            assertEquals(expected.peak, actual.peak); assertEquals(expected.rms, actual.rms, 0.0)
            assertArrayEquals(expected.wav, actual.wav)
        }
    }

    @Test fun anIsolatedFullScaleClickCannotQualifyAnEntireReadBuffer() {
        val signal = ShortArray(16000).apply { this[8123] = Short.MAX_VALUE }
        for (size in listOf(1, 320, signal.size)) {
            val clip = chunked(signal, size)
            assertFalse(clip.hasSpeech); assertFalse(clip.canPreview)
            assertEquals(32767, clip.peak); assertEquals(1000, clip.durationMs)
        }
    }

    @Test fun finalRequiresAtLeastOneHundredMillisecondsOfNonzeroSamples() {
        for (nonzero in listOf(1599, 1600)) {
            val signal = ShortArray(4800) { if (it < nonzero) 1 else 0 }
            val clip = chunked(signal, 777)
            assertEquals(300, clip.durationMs); assertEquals(nonzero == 1600, clip.hasSpeech)
            assertFalse(clip.canPreview)
        }
    }

    @Test fun emptyAndDigitalSilenceHaveFiniteZeroDiagnosticsAndAreNotSubmitted() {
        for (length in listOf(0, 4800, 16000)) {
            val clip = chunked(ShortArray(length), 320)
            assertFalse(clip.hasSpeech); assertFalse(clip.canPreview)
            assertEquals(length, clip.sampleCount); assertEquals(length / 16, clip.durationMs)
            assertEquals(0, clip.peak); assertEquals(0.0, clip.rms, 0.0)
        }
    }

    @Test fun numericDiagnosticsPreserveSignedFullScaleAndExactSampleCount() {
        val audio = CloudSpeechAudio()
        audio.append(shortArrayOf(-32768, 32767, 0, -1), 4)
        val clip = audio.snapshot()
        assertEquals(4, clip.sampleCount); assertEquals(32768, clip.peak)
        assertEquals(kotlin.math.sqrt((32768.0 * 32768 + 32767.0 * 32767 + 1) / 4), clip.rms, 0.0)
        assertFalse(clip.hasSpeech); assertFalse(clip.canPreview)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidReadCountIsRejected() { CloudSpeechAudio().append(ShortArray(4), 5) }

    @Test fun onlyOnePreviewCanBeInFlight() {
        val state = CloudSpeechState()
        val token = state.beginPreview()!!
        assertNull(state.beginPreview())
        assertTrue(state.completePreview(token))
        assertFalse(state.completePreview(token))
        assertNotNull(state.beginPreview())
    }

    @Test fun releaseInvalidatesPreviewAndAllowsOnlyOneFinalResult() {
        val state = CloudSpeechState()
        val token = state.beginPreview()!!
        assertFalse(state.completeFinal())
        assertTrue(state.finish())
        assertFalse(state.finish())
        assertFalse(state.completePreview(token))
        assertNull(state.beginPreview())
        assertTrue(state.completeFinal())
        assertFalse(state.completeFinal())
    }

    @Test fun cancellationRejectsBothPreviewAndLateFinal() {
        val state = CloudSpeechState()
        val token = state.beginPreview()!!
        state.finish()
        state.cancel()
        assertFalse(state.completePreview(token))
        assertFalse(state.completeFinal())
        assertFalse(state.finish())
        assertNull(state.beginPreview())
    }

    @Test fun terminalFailureCannotLaterExecuteRecognizedText() {
        val state = CloudSpeechState()
        state.finish()
        assertTrue(state.fail())
        assertFalse(state.fail())
        assertFalse(state.completeFinal())
    }
}
