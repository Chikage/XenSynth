package icu.ringona.rtpmidi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpMidiCodecTest {
    @Test
    fun runningStatusPacketMatchesGoldenVector() {
        val packet = RtpMidiPacket(
            sequenceNumber = 0x1234,
            timestamp = 0x0102_0304,
            ssrc = 0xAABB_CCDDL,
            commands = listOf(
                TimedMidiMessage(0, MidiChannelMessage(0x90, 60, 100)),
                TimedMidiMessage(10, MidiChannelMessage(0x90, 64, 110)),
            ),
        )

        val encoded = RtpMidiCodec.encode(packet)

        assertArrayEquals(
            hex("80 61 12 34 01 02 03 04 AA BB CC DD 06 90 3C 64 0A 40 6E"),
            encoded,
        )
        assertEquals(0xAABB_CCDDL, RtpMidiCodec.readSsrcOrNull(encoded))
        assertEquals(packet, RtpMidiCodec.decode(encoded))
    }

    @Test
    fun firstDeltaUsesRfcVariableLengthEncoding() {
        val packet = RtpMidiPacket(
            sequenceNumber = 0xFFFE,
            timestamp = 0xFFFF_FFF0,
            ssrc = 0x0102_0304,
            marker = true,
            commands = listOf(
                TimedMidiMessage(128, MidiChannelMessage(0xE0, 0, 64)),
            ),
        )

        val encoded = RtpMidiCodec.encode(packet)

        assertArrayEquals(
            hex("80 E1 FF FE FF FF FF F0 01 02 03 04 25 81 00 E0 00 40"),
            encoded,
        )
        assertEquals(packet, RtpMidiCodec.decode(encoded))
    }

    @Test
    fun allChannelVoiceMessageLengthsRoundTripThroughLongHeader() {
        val messages = listOf(
            MidiChannelMessage(0x80, 60, 1),
            MidiChannelMessage(0x91, 61, 2),
            MidiChannelMessage(0xA2, 62, 3),
            MidiChannelMessage(0xB3, 64, 127),
            MidiChannelMessage(0xC4, 10),
            MidiChannelMessage(0xD5, 11),
            MidiChannelMessage(0xE6, 0, 64),
        )
        val packet = RtpMidiPacket(
            sequenceNumber = 1,
            timestamp = 2,
            ssrc = 3,
            commands = messages.mapIndexed { index, message -> TimedMidiMessage(index, message) },
        )

        val encoded = RtpMidiCodec.encode(packet, useRunningStatus = false)
        val decoded = RtpMidiCodec.decode(encoded)

        assertTrue((encoded[12].toInt() and 0x80) != 0)
        assertEquals(packet, decoded)
        assertArrayEquals(byteArrayOf(0xC4.toByte(), 10), messages[4].toByteArray())
        assertEquals(messages[4], MidiChannelMessage.fromBytes(messages[4].toByteArray()))
    }

    @Test
    fun longHeaderCarriesCommandSectionsLargerThanOneOctet() {
        val commands = List(200) { index ->
            TimedMidiMessage(0, MidiChannelMessage(0x90, index and 0x7F, 100))
        }
        val packet = RtpMidiPacket(
            sequenceNumber = 2,
            timestamp = 3,
            ssrc = 4,
            commands = commands,
        )

        val encoded = RtpMidiCodec.encode(packet)
        val decoded = RtpMidiCodec.decode(encoded)

        assertTrue(encoded.size > 255)
        assertEquals(packet, decoded)
    }

    @Test
    fun decoderExpandsRunningStatusAfterEveryDelta() {
        val encoded = hex(
            "80 61 00 02 00 00 00 10 00 00 00 20 " +
                "0A 92 3C 64 00 3D 65 81 00 3E 66",
        )

        val decoded = RtpMidiCodec.decode(encoded)

        assertEquals(
            listOf(
                TimedMidiMessage(0, MidiChannelMessage(0x92, 60, 100)),
                TimedMidiMessage(0, MidiChannelMessage(0x92, 61, 101)),
                TimedMidiMessage(128, MidiChannelMessage(0x92, 62, 102)),
            ),
            decoded.commands,
        )
    }

    @Test
    fun phantomStatusStillRequiresTheFirstChannelStatus() {
        val missingStatus = hex(
            "80 61 00 03 00 00 00 10 00 00 00 20 12 3C 64",
        )

        assertThrows(AppleMidiProtocolException::class.java) {
            RtpMidiCodec.decode(missingStatus)
        }

        val explicitStatus = hex(
            "80 61 00 03 00 00 00 10 00 00 00 20 13 90 3C 64",
        )
        val decoded = RtpMidiCodec.decode(explicitStatus)

        assertTrue(decoded.phantomStatus)
        assertEquals(
            listOf(TimedMidiMessage(0, MidiChannelMessage(0x90, 60, 100))),
            decoded.commands,
        )
    }

    @Test
    fun opaqueRecoveryJournalAndCommandTimestampsArePreserved() {
        val packet = RtpMidiPacket(
            sequenceNumber = 8,
            timestamp = 0xFFFF_FFFE,
            ssrc = 9,
            commands = listOf(
                TimedMidiMessage(0, MidiChannelMessage(0x90, 60, 100)),
                TimedMidiMessage(4, MidiChannelMessage(0x80, 60, 0)),
            ),
            phantomStatus = true,
            journal = hex("20 00 07"),
        )

        val decoded = RtpMidiCodec.decode(RtpMidiCodec.encode(packet))

        assertEquals(packet, decoded)
        assertEquals(packet.hashCode(), decoded.hashCode())
        assertArrayEquals(packet.journal, decoded.journal)
        assertEquals(listOf(0xFFFF_FFFEL, 2L), decoded.commandTimestamps())
    }

    @Test
    fun journalHeartbeatHasNoCommandsAndKeepsMarkerClear() {
        val packet = RtpMidiPacket(
            sequenceNumber = 9,
            timestamp = 10,
            ssrc = 11,
            commands = emptyList(),
            marker = false,
            firstDeltaEncoded = false,
            journal = hex("80 00 08"),
        )

        val encoded = RtpMidiCodec.encode(packet)
        val decoded = RtpMidiCodec.decode(encoded)

        assertEquals(16, encoded.size)
        assertEquals(packet, decoded)
        assertTrue(decoded.commands.isEmpty())
        assertFalse(decoded.marker)
    }

    @Test
    fun malformedRtpAndMidiListsAreRejected() {
        val wrongPayload = hex("80 60 00 00 00 00 00 00 00 00 00 00 00")
        val truncatedCommands = hex("80 61 00 00 00 00 00 00 00 00 00 00 04 90 3C")
        val runningStatusFirst = hex("80 61 00 00 00 00 00 00 00 00 00 00 02 3C 64")
        val statusInData = hex("80 61 00 00 00 00 00 00 00 00 00 00 03 90 3C 80")
        val missingJournal = hex("80 61 00 00 00 00 00 00 00 00 00 00 40")
        val fiveOctetDelta = hex(
            "80 61 00 00 00 00 00 00 00 00 00 00 27 80 80 80 80 00 90 3C 64",
        )

        for (bytes in listOf(
            wrongPayload,
            truncatedCommands,
            runningStatusFirst,
            statusInData,
            missingJournal,
            fiveOctetDelta,
        )) {
            assertThrows(AppleMidiProtocolException::class.java) { RtpMidiCodec.decode(bytes) }
            assertNull(RtpMidiCodec.decodeOrNull(bytes))
        }
        assertFalse(RtpMidiCodec.isRtpMidiPacket(wrongPayload))
        assertNull(RtpMidiCodec.readSsrcOrNull(wrongPayload))
        assertTrue(RtpMidiCodec.isRtpMidiPacket(truncatedCommands))
    }
}
