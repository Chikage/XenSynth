package icu.ringona.xensynth.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.jtransforms.fft.FloatFFT_1D

internal data class SpectrumPeak(
    val midiPitch: Double,
    val magnitude: Float,
)

internal data class FftPitchEstimate(
    val frequencyHz: Double,
    val midiPitch: Double,
    val confidence: Double,
    val rms: Double,
)

internal data class FftSpectrumResult(
    val magnitudes: FloatArray,
    val peaks: List<SpectrumPeak>,
    val pitchEstimate: FftPitchEstimate?,
)

internal class FftSpectrumAnalyzer(
    private val sampleRate: Int,
    val frameSize: Int,
) {
    init {
        require(sampleRate > 0)
        require(frameSize > 1 && frameSize and (frameSize - 1) == 0) {
            "FFT frame size must be a power of two"
        }
    }

    private val fft = FloatFFT_1D(frameSize.toLong())
    private val window = FloatArray(frameSize) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (frameSize - 1))).toFloat()
    }
    private val windowSum = window.sum().coerceAtLeast(1f)

    fun analyze(samples: FloatArray): FloatArray = analyzeFrame(samples).magnitudes

    fun analyzeFrame(samples: FloatArray): FftSpectrumResult {
        require(samples.size == frameSize) { "FFT frame has an unexpected size" }
        val rawMagnitudes = transform(samples)
        val displayMagnitudes = displayMagnitudes(rawMagnitudes)
        val rms = acRms(samples)
        if (!rms.isFinite() || rms < MINIMUM_RMS) {
            return FftSpectrumResult(displayMagnitudes, emptyList(), null)
        }

        val minimumBin = frequencyToBin(MINIMUM_FREQUENCY_HZ).roundToInt().coerceAtLeast(2)
        val maximumBin = frequencyToBin(MAXIMUM_FREQUENCY_HZ)
            .roundToInt()
            .coerceAtMost(rawMagnitudes.lastIndex - 2)
        if (maximumBin <= minimumBin) {
            return FftSpectrumResult(displayMagnitudes, emptyList(), null)
        }

        val usefulMagnitudes = rawMagnitudes.copyOfRange(minimumBin, maximumBin + 1)
        val maximumMagnitude = usefulMagnitudes.maxOrNull()?.toDouble() ?: 0.0
        if (maximumMagnitude <= MINIMUM_MAGNITUDE) {
            return FftSpectrumResult(displayMagnitudes, emptyList(), null)
        }
        usefulMagnitudes.sort()
        val noiseFloor = usefulMagnitudes[usefulMagnitudes.size / 2].toDouble()
        val detectionFloor = maxOf(
            MINIMUM_PEAK_MAGNITUDE,
            noiseFloor * NOISE_FLOOR_MULTIPLIER,
            maximumMagnitude * RELATIVE_PEAK_FLOOR,
        )
        val rawPeaks = buildList {
            for (bin in minimumBin..maximumBin) {
                val magnitude = rawMagnitudes[bin].toDouble()
                if (
                    magnitude >= detectionFloor &&
                    magnitude >= rawMagnitudes[bin - 1] &&
                    magnitude > rawMagnitudes[bin + 1]
                ) {
                    add(RawPeak(refinedBin(bin, rawMagnitudes), magnitude))
                }
            }
        }
        if (rawPeaks.isEmpty()) {
            return FftSpectrumResult(displayMagnitudes, emptyList(), null)
        }

        val peaks = rawPeaks
            .sortedByDescending(RawPeak::magnitude)
            .take(MAXIMUM_REPORTED_PEAKS)
            .mapNotNull { peak ->
                val frequency = binToFrequency(peak.bin)
                val midiPitch = frequencyToMidi(frequency)
                if (!midiPitch.isFinite() || midiPitch !in 0.0..MAX_MIDI_PITCH) {
                    null
                } else {
                    SpectrumPeak(
                        midiPitch = midiPitch,
                        magnitude = normalizedMagnitude(peak.magnitude),
                    )
                }
            }
            .sortedBy(SpectrumPeak::midiPitch)

        val candidateScores = rawPeaks
            .sortedByDescending(RawPeak::magnitude)
            .take(MAXIMUM_CANDIDATE_PEAKS)
            .flatMap { peak ->
                (1..MAXIMUM_FUNDAMENTAL_DIVISOR).mapNotNull { divisor ->
                    val frequency = binToFrequency(peak.bin) / divisor
                    if (frequency !in MINIMUM_FREQUENCY_HZ..MAXIMUM_FREQUENCY_HZ) {
                        null
                    } else {
                        scoreCandidate(
                            frequencyHz = frequency,
                            rawMagnitudes = rawMagnitudes,
                            maximumMagnitude = maximumMagnitude,
                            detectionFloor = detectionFloor,
                        )
                    }
                }
            }
            .sortedByDescending(CandidateScore::score)
        val best = candidateScores.firstOrNull()
        val runnerUp = best?.let { selected ->
            candidateScores.firstOrNull {
                kotlin.math.abs(frequencyToMidi(it.frequencyHz) - frequencyToMidi(selected.frequencyHz)) >
                    DISTINCT_CANDIDATE_SEMITONES
            }
        }
        val pitchEstimate = best?.let { candidate ->
            val signalDecibels = 20.0 * log10(maximumMagnitude.coerceAtLeast(MINIMUM_MAGNITUDE))
            val signalStrength = ((signalDecibels - MINIMUM_SIGNAL_DECIBELS) /
                (MAXIMUM_SIGNAL_DECIBELS - MINIMUM_SIGNAL_DECIBELS)).coerceIn(0.0, 1.0)
            val separation = if (candidate.score <= 0.0) {
                0.0
            } else {
                ((candidate.score - (runnerUp?.score ?: 0.0)) / candidate.score).coerceIn(0.0, 1.0)
            }
            val coverage = (candidate.support / HARMONIC_WEIGHT_SUM).coerceIn(0.0, 1.0)
            val confidence = (
                SIGNAL_CONFIDENCE_WEIGHT * signalStrength +
                    SEPARATION_CONFIDENCE_WEIGHT * separation +
                    COVERAGE_CONFIDENCE_WEIGHT * coverage
                ).coerceIn(0.0, 1.0)
            val midiPitch = frequencyToMidi(candidate.frequencyHz)
            if (
                confidence < MINIMUM_ESTIMATE_CONFIDENCE ||
                !midiPitch.isFinite() ||
                midiPitch !in 0.0..MAX_MIDI_PITCH
            ) {
                null
            } else {
                FftPitchEstimate(
                    frequencyHz = candidate.frequencyHz,
                    midiPitch = midiPitch,
                    confidence = confidence,
                    rms = rms,
                )
            }
        }
        return FftSpectrumResult(displayMagnitudes, peaks, pitchEstimate)
    }

    private fun transform(samples: FloatArray): FloatArray {
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
        return magnitudes
    }

    private fun displayMagnitudes(rawMagnitudes: FloatArray): FloatArray {
        return FloatArray(SPECTRUM_POINT_COUNT) { point ->
            val midiPitch = point.toDouble() * MAX_MIDI_PITCH / (SPECTRUM_POINT_COUNT - 1)
            val exactBin = frequencyToBin(midiToFrequency(midiPitch))
            if (exactBin >= rawMagnitudes.lastIndex) {
                0f
            } else {
                val lower = exactBin.toInt().coerceAtLeast(0)
                val fraction = exactBin - lower
                val magnitude = rawMagnitudes[lower] * (1.0 - fraction) +
                    rawMagnitudes[lower + 1] * fraction
                normalizedMagnitude(magnitude)
            }
        }
    }

    private fun scoreCandidate(
        frequencyHz: Double,
        rawMagnitudes: FloatArray,
        maximumMagnitude: Double,
        detectionFloor: Double,
    ): CandidateScore {
        var support = 0.0
        var matches = 0
        for (harmonic in HARMONIC_WEIGHTS.indices) {
            val harmonicNumber = harmonic + 1
            val exactBin = frequencyToBin(frequencyHz * harmonicNumber)
            if (exactBin >= rawMagnitudes.lastIndex - 1) break
            val magnitude = peakMagnitude(exactBin, rawMagnitudes)
            support += HARMONIC_WEIGHTS[harmonic] *
                (magnitude / maximumMagnitude).coerceIn(0.0, 1.0)
            if (magnitude >= detectionFloor * HARMONIC_MATCH_FLOOR) matches++
        }
        val fundamentalRatio = (peakMagnitude(frequencyToBin(frequencyHz), rawMagnitudes) /
            maximumMagnitude).coerceIn(0.0, 1.0)
        val fundamentalWeight = MISSING_FUNDAMENTAL_BASE_WEIGHT +
            (1.0 - MISSING_FUNDAMENTAL_BASE_WEIGHT) * sqrt(fundamentalRatio)
        val matchWeight = 0.8 + 0.2 * matches / HARMONIC_WEIGHTS.size
        return CandidateScore(
            frequencyHz = frequencyHz,
            score = support * fundamentalWeight * matchWeight,
            support = support,
        )
    }

    private fun peakMagnitude(exactBin: Double, magnitudes: FloatArray): Double {
        val center = exactBin.roundToInt()
        if (center <= 0 || center >= magnitudes.lastIndex) return 0.0
        var result = 0.0
        for (bin in (center - 1).coerceAtLeast(1)..(center + 1).coerceAtMost(magnitudes.lastIndex)) {
            result = max(result, magnitudes[bin].toDouble())
        }
        return result
    }

    private fun refinedBin(bin: Int, magnitudes: FloatArray): Double {
        if (bin <= 0 || bin >= magnitudes.lastIndex) return bin.toDouble()
        val previous = ln(magnitudes[bin - 1].toDouble().coerceAtLeast(MINIMUM_MAGNITUDE))
        val current = ln(magnitudes[bin].toDouble().coerceAtLeast(MINIMUM_MAGNITUDE))
        val next = ln(magnitudes[bin + 1].toDouble().coerceAtLeast(MINIMUM_MAGNITUDE))
        val denominator = previous - 2.0 * current + next
        if (kotlin.math.abs(denominator) < 1e-12) return bin.toDouble()
        return bin + (0.5 * (previous - next) / denominator).coerceIn(-0.5, 0.5)
    }

    private fun normalizedMagnitude(magnitude: Number): Float {
        val decibels = 20.0 * log10(magnitude.toDouble().coerceAtLeast(MINIMUM_MAGNITUDE))
        return ((decibels - MINIMUM_DECIBELS) /
            (MAXIMUM_DECIBELS - MINIMUM_DECIBELS)).coerceIn(0.0, 1.0).toFloat()
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

    private fun frequencyToBin(frequencyHz: Double): Double = frequencyHz * frameSize / sampleRate

    private fun binToFrequency(bin: Double): Double = bin * sampleRate / frameSize

    private fun frequencyToMidi(frequencyHz: Double): Double =
        69.0 + 12.0 * ln(frequencyHz / 440.0) / LN_2

    private fun midiToFrequency(midiPitch: Double): Double =
        440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)

    private data class RawPeak(val bin: Double, val magnitude: Double)

    private data class CandidateScore(
        val frequencyHz: Double,
        val score: Double,
        val support: Double,
    )

    companion object {
        const val SPECTRUM_POINT_COUNT = 128
        const val MAX_MIDI_PITCH = 127.0
        private const val MINIMUM_FREQUENCY_HZ = 27.5
        private const val MAXIMUM_FREQUENCY_HZ = 2_000.0
        private const val MINIMUM_DECIBELS = -90.0
        private const val MAXIMUM_DECIBELS = -15.0
        private const val MINIMUM_SIGNAL_DECIBELS = -70.0
        private const val MAXIMUM_SIGNAL_DECIBELS = -25.0
        private const val MINIMUM_MAGNITUDE = 0.000_000_01
        private const val MINIMUM_PEAK_MAGNITUDE = 0.000_15
        private const val MINIMUM_RMS = 0.0035
        private const val NOISE_FLOOR_MULTIPLIER = 6.0
        private const val RELATIVE_PEAK_FLOOR = 0.035
        private const val HARMONIC_MATCH_FLOOR = 0.75
        private const val MISSING_FUNDAMENTAL_BASE_WEIGHT = 0.55
        private const val MAXIMUM_REPORTED_PEAKS = 16
        private const val MAXIMUM_CANDIDATE_PEAKS = 12
        private const val MAXIMUM_FUNDAMENTAL_DIVISOR = 5
        private const val DISTINCT_CANDIDATE_SEMITONES = 0.75
        private const val MINIMUM_ESTIMATE_CONFIDENCE = 0.42
        private const val SIGNAL_CONFIDENCE_WEIGHT = 0.30
        private const val SEPARATION_CONFIDENCE_WEIGHT = 0.35
        private const val COVERAGE_CONFIDENCE_WEIGHT = 0.35
        private val HARMONIC_WEIGHTS = doubleArrayOf(0.90, 0.45, 0.30, 0.22, 0.18, 0.14)
        private val HARMONIC_WEIGHT_SUM = HARMONIC_WEIGHTS.sum()
        private val LN_2 = ln(2.0)
    }
}
