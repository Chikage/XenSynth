package icu.ringona.rtpmidi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMidiControlCodecTest {
    @Test
    fun invitationMatchesAppleMidiGoldenVector() {
        val packet = AppleMidiControlPacket.Invitation(
            command = AppleMidiInvitationCommand.IN,
            initiatorToken = 0x1122_3344,
            ssrc = 0xAABB_CCDDL,
            name = "XenSynth",
        )

        val encoded = AppleMidiControlCodec.encode(packet)

        assertArrayEquals(
            hex("FF FF 49 4E 00 00 00 02 11 22 33 44 AA BB CC DD 58 65 6E 53 79 6E 74 68 00"),
            encoded,
        )
        assertEquals(packet, AppleMidiControlCodec.decode(encoded))
    }

    @Test
    fun acceptedAndRejectedInvitationsPreserveUtf8Names() {
        val accepted = AppleMidiControlPacket.Invitation(
            command = AppleMidiInvitationCommand.OK,
            initiatorToken = 7,
            ssrc = 9,
            name = "钢琴",
        )
        assertEquals(accepted, AppleMidiControlCodec.decode(AppleMidiControlCodec.encode(accepted)))

        val rejected = AppleMidiControlPacket.Invitation(
            command = AppleMidiInvitationCommand.NO,
            initiatorToken = 7,
            ssrc = 9,
            name = "钢琴",
        )
        val encodedRejected = AppleMidiControlCodec.encode(rejected)
        assertEquals(16, encodedRejected.size)
        assertEquals(
            rejected.copy(name = ""),
            AppleMidiControlCodec.decode(encodedRejected),
        )
    }

    @Test
    fun invitationWithoutSessionNameIsAccepted() {
        val encoded = hex("FF FF 4E 4F 00 00 00 02 00 00 00 07 00 00 00 09")

        assertEquals(
            AppleMidiControlPacket.Invitation(
                command = AppleMidiInvitationCommand.NO,
                initiatorToken = 7,
                ssrc = 9,
                name = "",
            ),
            AppleMidiControlCodec.decode(encoded),
        )
    }

    @Test
    fun byCkAndRsMatchGoldenVectors() {
        assertArrayEquals(
            hex("FF FF 42 59 01 02 03 04 A0 B0 C0 D0"),
            AppleMidiControlCodec.encode(
                AppleMidiControlPacket.EndSession(0x0102_0304, 0xA0B0_C0D0),
            ),
        )
        assertArrayEquals(
            hex(
                "FF FF 43 4B 01 02 03 04 01 00 00 00 " +
                    "00 00 00 00 00 00 00 11 " +
                    "00 00 00 00 00 00 00 22 " +
                    "00 00 00 00 00 00 00 33",
            ),
            AppleMidiControlCodec.encode(
                AppleMidiControlPacket.ClockSynchronization(
                    ssrc = 0x0102_0304,
                    count = 1,
                    timestamp1 = 0x11,
                    timestamp2 = 0x22,
                    timestamp3 = 0x33,
                ),
            ),
        )
        assertArrayEquals(
            hex("FF FF 52 53 10 20 30 40 FF EE 00 00"),
            AppleMidiControlCodec.encode(
                AppleMidiControlPacket.ReceiverFeedback(0x1020_3040, 0xFFEE),
            ),
        )
    }

    @Test
    fun everyNonInvitationPacketRoundTrips() {
        val packets = listOf(
            AppleMidiControlPacket.EndSession(0xFFFF_FFFF, 0),
            AppleMidiControlPacket.ClockSynchronization(
                ssrc = 0xFFFF_FFFF,
                count = 2,
                timestamp1 = Long.MAX_VALUE,
                timestamp2 = 0,
                timestamp3 = -1L,
            ),
            AppleMidiControlPacket.ReceiverFeedback(1, 65_535),
        )

        for (packet in packets) {
            assertEquals(packet, AppleMidiControlCodec.decode(AppleMidiControlCodec.encode(packet)))
        }
    }

    @Test
    fun malformedControlPacketsAreRejected() {
        val wrongSignature = hex("00 FF 42 59 00 00 00 00 00 00 00 00")
        val unsupportedVersion = hex("FF FF 49 4E 00 00 00 01 00 00 00 00 00 00 00 00 00")
        val missingNameTerminator = hex("FF FF 49 4E 00 00 00 02 00 00 00 00 00 00 00 00 58")
        val nonZeroCkPadding = hex(
            "FF FF 43 4B 00 00 00 01 00 01 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00",
        )
        val nonZeroRsReserved = hex("FF FF 52 53 00 00 00 01 00 02 00 01")

        for (bytes in listOf(
            wrongSignature,
            unsupportedVersion,
            missingNameTerminator,
            nonZeroCkPadding,
            nonZeroRsReserved,
        )) {
            assertThrows(AppleMidiProtocolException::class.java) {
                AppleMidiControlCodec.decode(bytes)
            }
            assertEquals(null, AppleMidiControlCodec.decodeOrNull(bytes))
        }
        assertFalse(AppleMidiControlCodec.isControlPacket(wrongSignature))
        assertTrue(AppleMidiControlCodec.isControlPacket(unsupportedVersion))
    }
}

internal fun hex(value: String): ByteArray {
    val compact = value.filterNot(Char::isWhitespace)
    require(compact.length % 2 == 0)
    return ByteArray(compact.length / 2) { index ->
        compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
