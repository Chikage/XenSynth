package icu.ringona.xensynth.midi

import android.media.midi.MidiDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiOutputDestinationManagerTest {
    @Test
    fun bluetoothDestinationIdKeepsLegacyPrefix() {
        assertEquals(
            "bluetooth:11:3",
            MidiOutputDestinationManager.outputDestinationId(
                deviceId = 11,
                portNumber = 3,
                deviceType = MidiDeviceInfo.TYPE_BLUETOOTH,
            ),
        )
    }

    @Test
    fun nonBluetoothDestinationIdUsesUnifiedPrefix() {
        assertEquals(
            "android-midi-output:11:3",
            MidiOutputDestinationManager.outputDestinationId(
                deviceId = 11,
                portNumber = 3,
                deviceType = MidiDeviceInfo.TYPE_USB,
            ),
        )
    }
}
