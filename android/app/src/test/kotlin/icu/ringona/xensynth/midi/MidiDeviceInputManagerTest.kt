package icu.ringona.xensynth.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiDeviceInputManagerTest {
    @Test
    fun inputSourceIdIsStableAndPortScoped() {
        assertEquals(
            "android-midi-input:17:2",
            MidiDeviceInputManager.inputSourceId(deviceId = 17, portNumber = 2),
        )
        assertTrue(
            MidiDeviceInputManager.inputSourceId(17, 2)
                .startsWith(MidiDeviceInputManager.INPUT_ID_PREFIX),
        )
    }
}
