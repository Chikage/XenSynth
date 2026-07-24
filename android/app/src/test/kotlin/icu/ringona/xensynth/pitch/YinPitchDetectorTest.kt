package icu.ringona.xensynth.pitch

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YinPitchDetectorTest {
    private val detector = YinPitchDetector()

    @Test
    fun detects440HzSine() {
        val estimate = requireNotNull(detector.detect(sineWave(440.0)))

        assertEquals(440.0, estimate.frequencyHz, 0.8)
        assertEquals(69.0, estimate.midiPitch, 0.04)
        assertTrue(estimate.confidence > 0.95)
    }

    @Test
    fun preservesMicrotonalOffset() {
        val frequency = 440.0 * 2.0.pow(37.0 / 1_200.0)
        val estimate = requireNotNull(detector.detect(sineWave(frequency)))

        assertEquals(frequency, estimate.frequencyHz, 0.8)
        assertEquals(69.37, estimate.midiPitch, 0.04)
    }

    @Test
    fun rejectsSilence() {
        assertNull(detector.detect(FloatArray(FRAME_SIZE)))
    }

    @Test
    fun smoothingIsStableAcrossDifferentFrameCadences() {
        val coarse = YinPitchSmoother()
        coarse.update(69.0, 0.0)
        val coarseResult = coarse.update(70.0, 0.032)

        val fine = YinPitchSmoother()
        fine.update(69.0, 0.0)
        fine.update(70.0, 0.016)
        val fineResult = fine.update(70.0, 0.032)

        assertEquals(coarseResult, fineResult, 0.000_001)
    }

    @Test
    fun smoothingDoesNotDelayLargePitchChanges() {
        val smoother = YinPitchSmoother()
        smoother.update(60.0, 0.0)

        assertEquals(64.0, smoother.update(64.0, 0.016), 0.000_001)
    }

    private fun sineWave(frequencyHz: Double): FloatArray {
        return FloatArray(FRAME_SIZE) { index ->
            (AMPLITUDE * sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE)).toFloat()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 2_048
        const val AMPLITUDE = 0.5
    }
}
