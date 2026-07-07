package icu.ringona.xensynth.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiWaterfallParserTest {
    @Test
    fun parsesStandardMidiNote() {
        val parsed = MidiWaterfallParser.detectAndParse(
            smfTrack(
                byteArrayOf(
                    0x00, 0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20,
                    0x00, 0x90.toByte(), 0x3C, 0x40,
                    0x83.toByte(), 0x60, 0x80.toByte(), 0x3C, 0x00,
                    0x00, 0xFF.toByte(), 0x2F, 0x00
                )
            ),
            "plain.mid"
        )

        assertEquals("MIDX", parsed.format)
        assertEquals(1, parsed.notes.size)
        assertEquals(60, parsed.notes[0].midiPitch)
        assertEquals(0.0, parsed.notes[0].start, 0.000001)
        assertEquals(0.5, parsed.notes[0].end, 0.000001)
        assertEquals(120.0, 60_000_000.0 / parsed.tempos[0].usPerQuarter, 0.000001)
    }

    @Test
    fun parsesMidxInlinePitchOffset() {
        val parsed = MidiWaterfallParser.detectAndParse(
            smfTrack(
                byteArrayOf(
                    0x00, 0xFF.toByte(), 0x7F, 0x07, 0x7D, 0x58, 0x54, 0x03, 0x3D, 0x20, 0x00,
                    0x00, 0x90.toByte(), 0x3C, 0x7F,
                    0x83.toByte(), 0x60, 0x80.toByte(), 0x3C, 0x00,
                    0x00, 0xFF.toByte(), 0x2F, 0x00
                )
            ),
            "offset.midx"
        )

        assertEquals(1, parsed.notes.size)
        assertEquals(60, parsed.notes[0].midiPitch)
        assertEquals(61.0 + 16.0 / 100.0, parsed.notes[0].pitch, 0.000001)
        assertEquals(16.0, parsed.notes[0].cents, 0.000001)
    }

    @Test
    fun parsesMidi2ClipAttributePitch() {
        val parsed = MidiWaterfallParser.detectAndParse(
            byteArrayOf(
                0x53, 0x4D, 0x46, 0x32, 0x43, 0x4C, 0x49, 0x50,
                0x00, 0x30, 0x01, 0xE0.toByte(),
                0x00, 0x40, 0x01, 0xE0.toByte(),
                0x40, 0x90.toByte(), 0x3C, 0x03, 0x7F, 0xFF.toByte(), 0x78, 0x00,
                0x00, 0x40, 0x01, 0xE0.toByte(),
                0x40, 0x80.toByte(), 0x3C, 0x00, 0x00, 0x00, 0x00, 0x00
            ),
            "clip.midi2"
        )

        assertEquals("MIDI 2.0 Clip", parsed.format)
        assertEquals(480, parsed.ticksPerQuarter)
        assertEquals(1, parsed.notes.size)
        assertTrue(parsed.notes[0].velocity > 0)
        assertEquals(60.0, parsed.notes[0].pitch, 0.000001)
        assertEquals(0.5, parsed.notes[0].start, 0.000001)
        assertEquals(1.0, parsed.notes[0].end, 0.000001)
    }

    private fun smfTrack(trackData: ByteArray): ByteArray {
        return byteArrayOf(
            0x4D, 0x54, 0x68, 0x64,
            0x00, 0x00, 0x00, 0x06,
            0x00, 0x00,
            0x00, 0x01,
            0x01, 0xE0.toByte(),
            0x4D, 0x54, 0x72, 0x6B
        ) + u32(trackData.size) + trackData
    }

    private fun u32(value: Int): ByteArray {
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }
}
