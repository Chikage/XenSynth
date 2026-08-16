package icu.ringona.xensynth.pitch

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridPitchFusionTest {
    @Test
    fun fallsBackToFftWhenYinHasNoEstimate() {
        val fft = fftEstimate(midiPitch = 69.0, confidence = 0.72)

        val result = requireNotNull(HybridPitchFusion.fuse(yin = null, fft = fft))

        assertEquals(69.0, result.midiPitch, 0.000_001)
        assertEquals(HybridPitchFusion.ALGORITHM_FFT, result.algorithm)
    }

    @Test
    fun usesConfidentFftToCorrectYinOctaveConflict() {
        val yin = YinPitchEstimate(
            frequencyHz = 880.0,
            midiPitch = 81.0,
            confidence = 0.78,
            rms = 0.2,
        )
        val fft = fftEstimate(midiPitch = 69.0, confidence = 0.74)

        val result = requireNotNull(HybridPitchFusion.fuse(yin = yin, fft = fft))

        assertEquals(69.0, result.midiPitch, 0.000_001)
        assertEquals(HybridPitchFusion.ALGORITHM_FUSED, result.algorithm)
    }

    private fun fftEstimate(midiPitch: Double, confidence: Double) = FftPitchEstimate(
        frequencyHz = 440.0,
        midiPitch = midiPitch,
        confidence = confidence,
        rms = 0.2,
    )
}
