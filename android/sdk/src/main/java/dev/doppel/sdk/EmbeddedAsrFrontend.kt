package dev.doppel.sdk

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** Paraformer: Kaldi log-mel, PCM16 scale, 25 ms Hamming, 10 ms shift, 80 bins.
 * The transform follows the published Kaldi feature equations; it is verified
 * against independent kaldi-native-fbank vectors, with no native frontend shipped.
 */
internal object EmbeddedAsrFrontend {
    private const val FRAME = 400
    private const val SHIFT = 160
    private const val FFT = 512
    private const val BINS = 80
    private val window = FloatArray(FRAME) { (0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
    private val melWeights = Array(BINS) { bin ->
        val low = 1127.0 * ln(1 + 20.0 / 700)
        val high = 1127.0 * ln(1 + 8000.0 / 700)
        val step = (high - low) / (BINS + 1)
        val left = low + bin * step
        val center = left + step
        val right = center + step
        FloatArray(FFT / 2) { fftBin ->
            val mel = 1127.0 * ln(1 + fftBin * 16000.0 / FFT / 700)
            when {
                mel <= left || mel >= right -> 0f
                mel <= center -> ((mel - left) / step).toFloat()
                else -> ((right - mel) / step).toFloat()
            }
        }
    }

    fun filterbank(samples: ShortArray): FloatArray {
        if (samples.size < FRAME) return FloatArray(0)
        val frames = 1 + (samples.size - FRAME) / SHIFT
        val output = FloatArray(frames * BINS)
        val real = DoubleArray(FFT)
        val imag = DoubleArray(FFT)
        val frame = FloatArray(FRAME)
        val power = FloatArray(FFT / 2)
        for (index in 0 until frames) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            var mean = 0f
            for (i in 0 until FRAME) { frame[i] = samples[index * SHIFT + i].toFloat(); mean += frame[i] }
            mean /= FRAME
            for (i in 0 until FRAME) frame[i] -= mean
            for (i in FRAME - 1 downTo 1) frame[i] -= 0.97f * frame[i - 1]
            frame[0] *= 1f - 0.97f
            real.fill(0.0); imag.fill(0.0)
            for (i in 0 until FRAME) real[i] = (frame[i] * window[i]).toDouble()
            fft(real, imag)
            for (i in power.indices) power[i] = (real[i] * real[i] + imag[i] * imag[i]).toFloat()
            for (bin in 0 until BINS) {
                var sum = 0f
                val weights = melWeights[bin]
                for (i in power.indices) sum += power[i] * weights[i]
                output[index * BINS + bin] = ln(max(sum, 1.1920929e-7f))
            }
        }
        return output
    }

    fun stackAndNormalize(features: FloatArray, negativeMean: FloatArray, inverseStdDev: FloatArray): FloatArray {
        require(negativeMean.size == 560 && inverseStdDev.size == 560 && features.size % BINS == 0)
        val sourceFrames = features.size / BINS
        if (sourceFrames < 7) return FloatArray(0)
        val frames = (sourceFrames - 7) / 6 + 1
        return FloatArray(frames * 560) { index ->
            val column = index % 560
            (features[index / 560 * 6 * BINS + column] + negativeMean[column]) * inverseStdDev[column]
        }
    }

    /** Paraformer output is non-autoregressive tokens, not CTC: repeats matter. */
    fun text(tokens: List<String>): String = buildString {
        var merge = false
        var previousAscii = false
        for (raw in tokens) {
            if (raw == "</s>") break
            if (raw == "<blank>" || raw == "<s>") continue
            if (raw.isEmpty()) continue
            val continued = raw.endsWith("@@")
            val token = if (continued) raw.dropLast(2) else raw
            val ascii = token.firstOrNull()?.code?.let { it < 128 } == true
            if (isNotEmpty() && !merge && (ascii || previousAscii)) append(' ')
            append(token)
            merge = continued
            previousAscii = ascii
        }
    }.trim()

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        var j = 0
        for (i in 1 until FFT) {
            var bit = FFT shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val value = real[i]; real[i] = real[j]; real[j] = value }
        }
        var length = 2
        while (length <= FFT) {
            val stepReal = cos(-2 * PI / length)
            val stepImag = sin(-2 * PI / length)
            var start = 0
            while (start < FFT) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val vr = real[odd] * wr - imag[odd] * wi
                    val vi = real[odd] * wi + imag[odd] * wr
                    real[odd] = real[even] - vr; imag[odd] = imag[even] - vi
                    real[even] += vr; imag[even] += vi
                    val next = wr * stepReal - wi * stepImag
                    wi = wr * stepImag + wi * stepReal; wr = next
                }
                start += length
            }
            length *= 2
        }
    }
}
