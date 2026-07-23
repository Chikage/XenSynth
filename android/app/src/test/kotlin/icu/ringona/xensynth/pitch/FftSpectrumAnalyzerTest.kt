package icu.ringona.xensynth.pitch

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FftSpectrumAnalyzerTest {
    @Test
    fun locatesA440NearMidiPitch69() {
        val analyzer = FftSpectrumAnalyzer(sampleRate = 16_000, frameSize = 2_048)
        val samples = FloatArray(analyzer.frameSize) { index ->
            (0.8 * sin(2.0 * PI * 440.0 * index / 16_000)).toFloat()
        }

        val spectrum = analyzer.analyze(samples)
        val strongestPoint = spectrum.indices.maxBy { spectrum[it] }
        val detectedPitch = strongestPoint.toDouble() *
            FftSpectrumAnalyzer.MAX_MIDI_PITCH / (spectrum.size - 1)

        assertEquals(FftSpectrumAnalyzer.SPECTRUM_POINT_COUNT, spectrum.size)
        assertTrue(spectrum[strongestPoint] > 0.8f)
        assertEquals(69.0, detectedPitch, 1.1)
    }
}
