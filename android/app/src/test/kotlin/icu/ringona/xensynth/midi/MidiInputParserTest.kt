package icu.ringona.xensynth.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiInputParserTest {
    @Test
    fun resetAllControllersReleasesSustain() {
        val events = mutableListOf<MidiInputEvent>()
        val parser = MidiInputParser(events::add)

        parser.send(byteArrayOf(0xB3.toByte(), 121, 0))

        assertEquals(listOf(MidiInputEvent.SustainPedal(down = false, channel = 3)), events)
    }

    @Test
    fun channelModeMessagesReleaseVisibleNotes() {
        val events = mutableListOf<MidiInputEvent>()
        val parser = MidiInputParser(events::add)

        (123..127).forEach { controller ->
            parser.send(byteArrayOf(0xB2.toByte(), controller.toByte(), 0))
        }

        assertEquals(List(5) { MidiInputEvent.AllNotesOff(channel = 2) }, events)
    }
}
