package icu.ringona.xensynth.pitch

import kotlin.math.abs
import kotlin.math.pow

internal data class HybridPitchEstimate(
    val frequencyHz: Double,
    val midiPitch: Double,
    val confidence: Double,
    val rms: Double,
    val algorithm: String,
)

internal object HybridPitchFusion {
    fun fuse(
        yin: YinPitchEstimate?,
        fft: FftPitchEstimate?,
    ): HybridPitchEstimate? {
        if (yin == null) {
            return fft
                ?.takeIf { it.confidence >= FFT_FALLBACK_CONFIDENCE }
                ?.toHybrid(ALGORITHM_FFT)
        }
        if (fft == null) return yin.toHybrid(ALGORITHM_YIN)

        val distance = abs(yin.midiPitch - fft.midiPitch)
        if (distance <= AGREEMENT_SEMITONES) {
            val yinWeight = yin.confidence.coerceAtLeast(0.01)
            val fftWeight = fft.confidence.coerceAtLeast(0.01) * FFT_BLEND_WEIGHT
            val pitch = (yin.midiPitch * yinWeight + fft.midiPitch * fftWeight) /
                (yinWeight + fftWeight)
            return HybridPitchEstimate(
                frequencyHz = midiToFrequency(pitch),
                midiPitch = pitch,
                confidence = (yin.confidence + fft.confidence * 0.5).coerceIn(0.0, 1.0),
                rms = maxOf(yin.rms, fft.rms),
                algorithm = ALGORITHM_FUSED,
            )
        }

        val octaveDistance = distance % 12.0
        val nearOctave = minOf(octaveDistance, 12.0 - octaveDistance) <=
            OCTAVE_TOLERANCE_SEMITONES
        if (nearOctave && fft.confidence >= FFT_OCTAVE_CORRECTION_CONFIDENCE) {
            return fft.toHybrid(ALGORITHM_FUSED)
        }
        if (yin.confidence >= STRONG_YIN_CONFIDENCE) return yin.toHybrid(ALGORITHM_YIN)
        if (fft.confidence >= FFT_DISAGREEMENT_CONFIDENCE) return fft.toHybrid(ALGORITHM_FFT)
        return yin.toHybrid(ALGORITHM_YIN)
    }

    private fun YinPitchEstimate.toHybrid(algorithm: String) = HybridPitchEstimate(
        frequencyHz = frequencyHz,
        midiPitch = midiPitch,
        confidence = confidence,
        rms = rms,
        algorithm = algorithm,
    )

    private fun FftPitchEstimate.toHybrid(algorithm: String) = HybridPitchEstimate(
        frequencyHz = frequencyHz,
        midiPitch = midiPitch,
        confidence = confidence,
        rms = rms,
        algorithm = algorithm,
    )

    private fun midiToFrequency(midiPitch: Double): Double =
        440.0 * 2.0.pow((midiPitch - 69.0) / 12.0)

    private const val FFT_FALLBACK_CONFIDENCE = 0.50
    private const val FFT_OCTAVE_CORRECTION_CONFIDENCE = 0.58
    private const val FFT_DISAGREEMENT_CONFIDENCE = 0.64
    private const val STRONG_YIN_CONFIDENCE = 0.84
    private const val AGREEMENT_SEMITONES = 1.5
    private const val OCTAVE_TOLERANCE_SEMITONES = 1.5
    private const val FFT_BLEND_WEIGHT = 0.55
    const val ALGORITHM_FUSED = "yin+fft"
    const val ALGORITHM_YIN = "yin"
    const val ALGORITHM_FFT = "fft"
}
