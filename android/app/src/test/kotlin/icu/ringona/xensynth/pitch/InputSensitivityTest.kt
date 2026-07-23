package icu.ringona.xensynth.pitch

import org.junit.Assert.assertEquals
import org.junit.Test

class InputSensitivityTest {
    @Test
    fun scalesAnalysisSamplesWithoutExceedingTheFloatPcmRange() {
        assertEquals(0.5f, scaleInputSample(Short.MAX_VALUE, 0.5f), 0.001f)
        assertEquals(1f, scaleInputSample(20_000, 2f), 0.001f)
        assertEquals(-1f, scaleInputSample(Short.MIN_VALUE, 2f), 0.001f)
    }
}
