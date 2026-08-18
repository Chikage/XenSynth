package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpMidiPacketReorderBufferTest {
    @Test
    fun latePacketFillsGapBeforeGraceDeadlineWithoutRecovery() {
        val buffer = RtpMidiPacketReorderBuffer(gapGraceNanos = 12_000_000L)
        val initial = buffer.offer(packet(10), 0L)
        assertEquals(listOf(10), initial.sequenceNumbers())
        assertTrue(initial.initialPacket)

        val held = buffer.offer(packet(12), 1_000_000L)
        assertTrue(held.packets.isEmpty())
        assertEquals(13_000_000L, buffer.nextGapDeadlineNanos)

        val filled = buffer.offer(packet(11), 10_000_000L)
        assertEquals(listOf(11, 12), filled.sequenceNumbers())
        assertNull(filled.loss)
        assertNull(buffer.nextGapDeadlineNanos)
    }

    @Test
    fun unresolvedGapExpiresOnlyAfterTwelveMilliseconds() {
        val buffer = RtpMidiPacketReorderBuffer(gapGraceNanos = 12_000_000L)
        buffer.offer(packet(20), 0L)
        buffer.offer(packet(22, journal = byteArrayOf(0x20, 0, 20)), 5_000_000L)

        assertTrue(buffer.expireGap(16_999_999L).packets.isEmpty())
        val expired = buffer.expireGap(17_000_000L)

        assertEquals(listOf(22), expired.sequenceNumbers())
        assertEquals(21L, expired.loss?.firstMissingExtendedSequence)
        assertEquals(1, expired.loss?.missingPackets)
        assertEquals(22L, expired.loss?.recoveryPacket?.extendedSequenceNumber)
        val late = buffer.offer(packet(21), 18_000_000L)
        assertTrue(late.duplicateOrLate)
        assertTrue(late.late)
        assertFalse(late.duplicate)
    }

    @Test
    fun consecutiveGapsReceiveIndependentGraceWindows() {
        val buffer = RtpMidiPacketReorderBuffer(gapGraceNanos = 12L)
        buffer.offer(packet(1), 0L)
        buffer.offer(packet(3), 1L)
        buffer.offer(packet(5), 2L)

        val first = buffer.expireGap(13L)
        assertEquals(listOf(3), first.sequenceNumbers())
        assertEquals(2L, first.loss?.firstMissingExtendedSequence)
        assertEquals(14L, buffer.nextGapDeadlineNanos)

        val second = buffer.expireGap(14L)
        assertEquals(listOf(5), second.sequenceNumbers())
        assertEquals(4L, second.loss?.firstMissingExtendedSequence)
    }

    @Test
    fun sequenceWrapStillReleasesLatePacketInOrder() {
        val buffer = RtpMidiPacketReorderBuffer()
        buffer.offer(packet(0xFFFE), 0L)
        buffer.offer(packet(0), 1L)

        val release = buffer.offer(packet(0xFFFF), 2L)

        assertEquals(listOf(0xFFFF, 0), release.sequenceNumbers())
        assertFalse(release.duplicateOrLate)
    }

    @Test
    fun duplicateAndReorderedPacketsAreReportedSeparately() {
        val buffer = RtpMidiPacketReorderBuffer()
        buffer.offer(packet(100), 0L)

        val reordered = buffer.offer(packet(102), 1L)
        assertTrue(reordered.reordered)
        assertFalse(reordered.duplicateOrLate)

        val duplicate = buffer.offer(packet(102), 2L)
        assertTrue(duplicate.duplicate)
        assertFalse(duplicate.late)
        assertFalse(duplicate.reordered)

        val filled = buffer.offer(packet(101), 3L)
        assertEquals(listOf(101, 102), filled.sequenceNumbers())
        assertFalse(filled.reordered)
    }

    private fun packet(sequence: Int, journal: ByteArray? = null) = RtpMidiPacket(
        sequenceNumber = sequence,
        timestamp = sequence.toLong() and 0xFFFF,
        ssrc = 1,
        commands = emptyList(),
        firstDeltaEncoded = false,
        journal = journal,
    )

    private fun RtpMidiPacketRelease.sequenceNumbers(): List<Int> =
        packets.map { it.packet.sequenceNumber }
}
