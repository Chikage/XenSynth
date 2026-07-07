package icu.ringona.xensynth.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiInputParserTest {
    private val events = mutableListOf<MidiInputEvent>()
    private val parser = MidiInputParser { events += it }

    @Test
    fun parsesNoteOnAndNoteOff() {
        parser.send(byteArrayOf(0x90.toByte(), 60, 100, 0x80.toByte(), 60, 0))

        assertEquals(
            listOf(
                MidiInputEvent.NoteOn(pitch = 60, velocity = 100, channel = 0),
                MidiInputEvent.NoteOff(pitch = 60, channel = 0)
            ),
            events
        )
    }

    @Test
    fun treatsNoteOnWithZeroVelocityAsNoteOff() {
        parser.send(byteArrayOf(0x92.toByte(), 64, 0))

        assertEquals(listOf(MidiInputEvent.NoteOff(pitch = 64, channel = 2)), events)
    }

    @Test
    fun keepsRunningStatusAcrossBuffers() {
        parser.send(byteArrayOf(0x91.toByte(), 60, 96))
        parser.send(byteArrayOf(62, 80, 60, 0))

        assertEquals(
            listOf(
                MidiInputEvent.NoteOn(pitch = 60, velocity = 96, channel = 1),
                MidiInputEvent.NoteOn(pitch = 62, velocity = 80, channel = 1),
                MidiInputEvent.NoteOff(pitch = 60, channel = 1)
            ),
            events
        )
    }

    @Test
    fun parsesSustainPedalControlChange() {
        parser.send(byteArrayOf(0xB3.toByte(), 64, 127, 64, 0))

        assertEquals(
            listOf(
                MidiInputEvent.SustainPedal(down = true, channel = 3),
                MidiInputEvent.SustainPedal(down = false, channel = 3)
            ),
            events
        )
    }

    @Test
    fun ignoresRealtimeStatusWithoutBreakingRunningStatus() {
        parser.send(byteArrayOf(0x90.toByte(), 60, 100, 0xF8.toByte(), 62, 90))

        assertEquals(
            listOf(
                MidiInputEvent.NoteOn(pitch = 60, velocity = 100, channel = 0),
                MidiInputEvent.NoteOn(pitch = 62, velocity = 90, channel = 0)
            ),
            events
        )
    }
}
