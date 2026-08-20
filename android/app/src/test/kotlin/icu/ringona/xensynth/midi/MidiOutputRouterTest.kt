package icu.ringona.xensynth.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun disablingNetworkOutputSendsSafetyControllersBeforeDisconnectingTheRoute() {
        var sent = emptyList<ByteArray>()
        MidiOutputRouter.setNetworkSender { messages, _ -> sent = messages }
        try {
            MidiOutputRouter.setNetworkOutputEnabled(true)
            MidiOutputRouter.setNetworkOutputEnabled(false)

            assertEquals(48, sent.size)
            assertTrue(sent.all { message ->
                (message[0].toInt() and 0xF0) == 0xB0 &&
                    (message[1].toInt() and 0xFF) in setOf(64, 120, 123)
            })
        } finally {
            MidiOutputRouter.setNetworkSender(null)
            MidiOutputRouter.setNetworkOutputEnabled(false)
        }
    }
}
