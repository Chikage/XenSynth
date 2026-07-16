package icu.ringona.xensynth.hexkeyboard.ui

import icu.ringona.xensynth.hexkeyboard.core.AxialCoordinate
import icu.ringona.xensynth.hexkeyboard.core.HexKey
import icu.ringona.xensynth.hexkeyboard.core.HexPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class HexKeyboardLabelsTest {
    @Test
    fun zeroPitchClassUsesMiddleCAsC4() {
        assertEquals("C3", label(step = -53, pitchClass = 0, period = 53))
        assertEquals("C4", label(step = 0, pitchClass = 0, period = 53))
        assertEquals("C5", label(step = 53, pitchClass = 0, period = 53))
    }

    @Test
    fun nonzeroPitchClassesRemainNumeric() {
        assertEquals("17", label(step = 17, pitchClass = 17, period = 53))
    }

    private fun label(step: Int, pitchClass: Int, period: Int): String = hexKeyLabel(
        key = HexKey(
            coordinate = AxialCoordinate.Origin,
            step = step,
            pitchClass = pitchClass,
            center = HexPoint(0.0, 0.0),
        ),
        period = period,
    )
}
