package icu.ringona.xensynth.pitch

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

internal data class YinPitchEstimate(
    val frequencyHz: Double,
    val midiPitch: Double,
    val confidence: Double,
    val rms: Double,
)

internal class YinPitchSmoother(
    private val referenceIntervalSeconds: Double = 512.0 / 16_000.0,
    private val smoothingFactor: Double = 0.35,
    private val smoothingRangeSemitones: Double = 1.5,
) {
    private var value: Double? = null
    private var timeSeconds: Double? = null

    init {
        require(referenceIntervalSeconds > 0.0)
        require(smoothingFactor in 0.0..1.0)
        require(smoothingRangeSemitones > 0.0)
    }

    fun update(midiPitch: Double, atTimeSeconds: Double): Double {
        val previous = value
        val previousTime = timeSeconds
        val next = if (
            previous != null &&
            previousTime != null &&
            kotlin.math.abs(midiPitch - previous) < smoothingRangeSemitones
        ) {
            val elapsed = (atTimeSeconds - previousTime)
                .takeIf { it.isFinite() && it > 0.0 }
                ?: referenceIntervalSeconds
            val factor = 1.0 - (1.0 - smoothingFactor).pow(elapsed / referenceIntervalSeconds)
            previous + (midiPitch - previous) * factor.coerceIn(0.0, 1.0)
        } else {
            midiPitch
        }
        value = next
        timeSeconds = atTimeSeconds
        return next
    }

    fun reset() {
        value = null
        timeSeconds = null
    }
}

internal class YinPitchDetector(
    private val sampleRate: Int = 16_000,
    private val frameSize: Int = 2_048,
    minimumFrequencyHz: Double = 27.5,
    maximumFrequencyHz: Double = 2_000.0,
    private val threshold: Double = 0.15,
    private val minimumConfidence: Double = 0.70,
    private val minimumRms: Double = 0.006,
) {
    private val minimumTau = floor(sampleRate / maximumFrequencyHz).toInt().coerceAtLeast(2)
    private val maximumTau = ceil(sampleRate / minimumFrequencyHz)
        .toInt()
        .coerceAtMost(frameSize / 2 - 1)
    private val comparisonLength = frameSize - maximumTau
    private val difference = DoubleArray(maximumTau + 1)
    private val normalizedDifference = DoubleArray(maximumTau + 1)

    init {
        require(sampleRate > 0)
        require(frameSize >= 256)
        require(minimumFrequencyHz > 0 && maximumFrequencyHz > minimumFrequencyHz)
        require(minimumTau < maximumTau)
        require(threshold in 0.0..1.0)
        require(minimumConfidence in 0.0..1.0)
        require(comparisonLength > 0)
    }

    fun detect(samples: FloatArray): YinPitchEstimate? {
        require(samples.size == frameSize) {
            "YIN requires $frameSize samples, received ${samples.size}"
        }
        val rms = acRms(samples)
        if (!rms.isFinite() || rms < minimumRms) return null

        difference.fill(0.0)
        normalizedDifference.fill(1.0)
        for (tau in 1..maximumTau) {
            var sum = 0.0
            for (index in 0 until comparisonLength) {
                val delta = samples[index] - samples[index + tau]
                sum += delta * delta
            }
            difference[tau] = sum
        }

        var cumulative = 0.0
        for (tau in 1..maximumTau) {
            cumulative += difference[tau]
            normalizedDifference[tau] = if (cumulative <= 0.0) {
                1.0
            } else {
                difference[tau] * tau / cumulative
            }
        }

        var candidate = -1
        var tau = minimumTau
        while (tau <= maximumTau) {
            if (normalizedDifference[tau] < threshold) {
                while (
                    tau < maximumTau &&
                    normalizedDifference[tau + 1] < normalizedDifference[tau]
                ) {
                    tau++
                }
                candidate = tau
                break
            }
            tau++
        }
        if (candidate < 0) {
            candidate = (minimumTau..maximumTau).minBy { normalizedDifference[it] }
        }

        val confidence = (1.0 - normalizedDifference[candidate]).coerceIn(0.0, 1.0)
        if (confidence < minimumConfidence) return null
        val refinedTau = parabolicTau(candidate)
        if (!refinedTau.isFinite() || refinedTau <= 0.0) return null
        val frequency = sampleRate / refinedTau
        if (!frequency.isFinite()) return null
        val midiPitch = 69.0 + 12.0 * ln(frequency / 440.0) / LN_2
        if (!midiPitch.isFinite()) return null
        return YinPitchEstimate(
            frequencyHz = frequency,
            midiPitch = midiPitch,
            confidence = confidence,
            rms = rms,
        )
    }

    private fun parabolicTau(tau: Int): Double {
        if (tau <= minimumTau || tau >= maximumTau) return tau.toDouble()
        val previous = normalizedDifference[tau - 1]
        val current = normalizedDifference[tau]
        val next = normalizedDifference[tau + 1]
        val denominator = 2.0 * (2.0 * current - next - previous)
        if (denominator == 0.0) return tau.toDouble()
        return tau + (next - previous) / denominator
    }

    private fun acRms(samples: FloatArray): Double {
        var mean = 0.0
        for (sample in samples) mean += sample
        mean /= samples.size
        var energy = 0.0
        for (sample in samples) {
            val centered = sample - mean
            energy += centered * centered
        }
        return sqrt(energy / samples.size)
    }

    private companion object {
        val LN_2 = ln(2.0)
    }
}
