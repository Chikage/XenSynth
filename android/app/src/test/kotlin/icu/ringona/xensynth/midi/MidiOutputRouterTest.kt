package icu.ringona.xensynth.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiOutputRouterTest {
    @Test
    fun centersPitchBendForAnIntegerPitch() {
        assertEquals(8192, MidiOutputRouter.pitchBendValue(60.0, 60))
    }

    @Test
    fun mapsHalfASemitoneToQuarterOfTheTwoSemitoneRange() {
        assertEquals(10240, MidiOutputRouter.pitchBendValue(60.5, 60))
    }

    @Test
    fun clampsPitchBendToTheFourteenBitRange() {
        assertEquals(0, MidiOutputRouter.pitchBendValue(56.0, 60))
        assertEquals(16383, MidiOutputRouter.pitchBendValue(64.0, 60))
    }
}
