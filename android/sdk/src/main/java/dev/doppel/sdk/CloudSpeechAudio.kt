package dev.doppel.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded mono PCM16; final eligibility means sustained nonzero audio, not speech recognition. */
internal class CloudSpeechAudio {
    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_DURATION_MS = 30000
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_MS / 1000
        private const val MIN_DURATION_MS = 300
        private const val MIN_VOICED_SAMPLES = SAMPLE_RATE / 10
        private const val MIN_RMS = 180L
        private const val PREVIEW_FRAME_SAMPLES = SAMPLE_RATE / 50
    }

    data class Clip(val wav: ByteArray, val durationMs: Int, val hasSpeech: Boolean,
                    val canPreview: Boolean, val sampleCount: Int, val peak: Int, val rms: Double)
    data class Statistics(val durationMs: Int, val hasSpeech: Boolean, val canPreview: Boolean,
                          val sampleCount: Int, val peak: Int, val rms: Double)
    private val samples = ShortArray(MAX_SAMPLES)
    private var size = 0
    private var nonzeroSamples = 0
    private var previewVoicedSamples = 0
    private var previewFrameSamples = 0
    private var previewFrameEnergy = 0L
    private var totalEnergy = 0L
    private var peak = 0
    val full: Boolean @Synchronized get() = size == MAX_SAMPLES

    @Synchronized fun append(input: ShortArray, count: Int): Int {
        require(count in 0..input.size)
        val accepted = minOf(count, MAX_SAMPLES - size)
        if (accepted == 0) return 0
        input.copyInto(samples, size, 0, accepted)
        size += accepted
        for (index in 0 until accepted) {
            val sample = input[index].toInt()
            val energy = sample.toLong() * sample
            if (sample != 0) nonzeroSamples++
            peak = maxOf(peak, abs(sample))
            totalEnergy += energy
            previewFrameEnergy += energy
            previewFrameSamples++
            if (previewFrameSamples == PREVIEW_FRAME_SAMPLES) {
                if (previewFrameEnergy >= MIN_RMS * MIN_RMS * previewFrameSamples) previewVoicedSamples += previewFrameSamples
                previewFrameSamples = 0; previewFrameEnergy = 0L
            }
        }
        return accepted
    }

    @Synchronized fun snapshot(): Clip {
        val pcmBytes = size * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII)); wav.putInt(36 + pcmBytes)
        wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); wav.putInt(16)
        wav.putShort(1); wav.putShort(1); wav.putInt(SAMPLE_RATE); wav.putInt(SAMPLE_RATE * 2)
        wav.putShort(2); wav.putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII)); wav.putInt(pcmBytes)
        for (index in 0 until size) wav.putShort(samples[index])
        val stats = statistics()
        return Clip(wav.array(), stats.durationMs, stats.hasSpeech, stats.canPreview, size, peak, stats.rms)
    }
    @Synchronized fun statistics(): Statistics {
        val duration = size * 1000 / SAMPLE_RATE
        val finalEligible = duration >= MIN_DURATION_MS && nonzeroSamples >= MIN_VOICED_SAMPLES
        val pendingPreviewSamples = if (previewFrameSamples > 0 && previewFrameEnergy >= MIN_RMS * MIN_RMS * previewFrameSamples) previewFrameSamples else 0
        val canPreview = finalEligible && previewVoicedSamples + pendingPreviewSamples >= MIN_VOICED_SAMPLES
        return Statistics(duration, finalEligible, canPreview, size, peak,
            if (size == 0) 0.0 else sqrt(totalEnergy.toDouble() / size))
    }
}

/** A preview never commits a command; only one result after release can be final. */
internal class CloudSpeechState {
    enum class Phase { RECORDING, FINALIZING, COMPLETED, CANCELLED }
    @Volatile var phase = Phase.RECORDING
        private set
    private var sequence = 0L
    private var preview: Long? = null

    @Synchronized fun beginPreview(): Long? {
        if (phase != Phase.RECORDING || preview != null) return null
        return (++sequence).also { preview = it }
    }

    @Synchronized fun completePreview(token: Long): Boolean {
        if (phase != Phase.RECORDING || preview != token) return false
        preview = null
        return true
    }

    @Synchronized fun finish(): Boolean {
        if (phase != Phase.RECORDING) return false
        phase = Phase.FINALIZING; preview = null
        return true
    }

    @Synchronized fun completeFinal(): Boolean {
        if (phase != Phase.FINALIZING) return false
        phase = Phase.COMPLETED
        return true
    }

    @Synchronized fun fail(): Boolean {
        if (phase == Phase.COMPLETED || phase == Phase.CANCELLED) return false
        phase = Phase.COMPLETED; preview = null
        return true
    }

    @Synchronized fun cancel() { phase = Phase.CANCELLED; preview = null }
}
