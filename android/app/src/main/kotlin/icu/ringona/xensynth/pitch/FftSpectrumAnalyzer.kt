package icu.ringona.xensynth.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import org.jtransforms.fft.FloatFFT_1D

internal class FftSpectrumAnalyzer(
    private val sampleRate: Int,
    val frameSize: Int,
) {
    init {
        require(frameSize > 1 && frameSize and (frameSize - 1) == 0) {
            "FFT frame size must be a power of two"
        }
    }

    private val fft = FloatFFT_1D(frameSize.toLong())
    private val window = FloatArray(frameSize) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (frameSize - 1))).toFloat()
    }
    private val windowSum = window.sum().coerceAtLeast(1f)

    fun analyze(samples: FloatArray): FloatArray {
        require(samples.size == frameSize) { "FFT frame has an unexpected size" }
        val transformed = FloatArray(frameSize) { index -> samples[index] * window[index] }
        fft.realForward(transformed)
        val magnitudes = FloatArray(frameSize / 2 + 1)
        magnitudes[0] = kotlin.math.abs(transformed[0]) / windowSum
        magnitudes[frameSize / 2] = kotlin.math.abs(transformed[1]) / windowSum
        for (bin in 1 until frameSize / 2) {
            val real = transformed[bin * 2]
            val imaginary = transformed[bin * 2 + 1]
            magnitudes[bin] = 2f * sqrt(real * real + imaginary * imaginary) / windowSum
        }

        return FloatArray(SPECTRUM_POINT_COUNT) { point ->
            val midiPitch = point.toDouble() * MAX_MIDI_PITCH / (SPECTRUM_POINT_COUNT - 1)
            val frequency = 440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)
            val exactBin = frequency * frameSize / sampleRate
            if (exactBin >= magnitudes.lastIndex) {
                0f
            } else {
                val lower = exactBin.toInt().coerceAtLeast(0)
                val fraction = exactBin - lower
                val magnitude = magnitudes[lower] * (1.0 - fraction) +
                    magnitudes[lower + 1] * fraction
                val decibels = 20.0 * log10(magnitude.coerceAtLeast(MINIMUM_MAGNITUDE))
                ((decibels - MINIMUM_DECIBELS) /
                    (MAXIMUM_DECIBELS - MINIMUM_DECIBELS)).coerceIn(0.0, 1.0).toFloat()
            }
        }
    }

    companion object {
        const val SPECTRUM_POINT_COUNT = 128
        const val MAX_MIDI_PITCH = 127.0
        private const val MINIMUM_DECIBELS = -90.0
        private const val MAXIMUM_DECIBELS = -15.0
        private const val MINIMUM_MAGNITUDE = 0.000_000_01
    }
}
