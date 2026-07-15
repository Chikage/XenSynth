package icu.ringona.xensynth

import icu.ringona.xensynth.view.ScaleGuide
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutModeTest {
    @Test
    fun persistedLayoutFallsBackToLinear() {
        assertEquals(KeyboardLayoutMode.Linear, KeyboardLayoutMode.fromPreference(null))
        assertEquals(KeyboardLayoutMode.Linear, KeyboardLayoutMode.fromPreference("unknown"))
        assertEquals(KeyboardLayoutMode.Hexagonal, KeyboardLayoutMode.fromPreference("hexagonal"))
    }

    @Test
    fun rawPitchUsesXenEdoAndFreeModeReference() {
        assertEquals(60.0, hexKeyboardRawPitch(step = 0, edo = 53), 0.000_001)
        assertEquals(72.0, hexKeyboardRawPitch(step = 53, edo = 53), 0.000_001)
        assertEquals(72.0, hexKeyboardRawPitch(step = 1, edo = 1), 0.000_001)
        assertEquals(67.0, hexKeyboardRawPitch(step = 7, edo = 0), 0.000_001)
    }

    @Test
    fun playbackPitchIsResolvedByXenScaleGuide() {
        val guide = ScaleGuide(markRatios = emptyMap(), scaleMarks = emptyMap())

        assertEquals(
            60.0,
            requireNotNull(hexKeyboardPlaybackPitch(step = 0, edo = 53, guide)),
            0.000_001
        )
        assertEquals(
            60.0 + 12.0 / 53.0,
            requireNotNull(hexKeyboardPlaybackPitch(step = 1, edo = 53, guide)),
            0.000_001
        )
    }

    @Test
    fun increasingOffsetLowersHexKeyboardPlaybackPitch() {
        val guide = ScaleGuide(markRatios = emptyMap(), scaleMarks = emptyMap())

        assertEquals(
            59.75,
            requireNotNull(
                hexKeyboardPlaybackPitch(
                    step = 0,
                    edo = 53,
                    scaleGuide = guide,
                    offsetCents = 25.0
                )
            ),
            0.000_001
        )
    }
}
