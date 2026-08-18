package icu.ringona.rtpmidi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiOutputAccumulatorTest {
    @Test
    fun continuousFloodCoalescesLatestValueAndUsesCongestedWindow() {
        val accumulator = MidiOutputAccumulator()

        repeat(1_000) { value ->
            accumulator.offer(
                incoming = listOf(byteArrayOf(0xB0.toByte(), 1, (value and 0x7F).toByte())),
                timestampNanos = value.toLong(),
                nowNanos = value.toLong(),
            )
        }

        val drained = accumulator.drain()
        assertEquals(1, drained.messages.size)
        assertArrayEquals(
            byteArrayOf(0xB0.toByte(), 1, (999 and 0x7F).toByte()),
            drained.messages.single().bytes,
        )
        assertEquals(MidiOutputAccumulator.CONGESTED_BATCH_WINDOW_NANOS, drained.batchWindowNanos)
        assertEquals(999L, accumulator.statistics.coalescedMessages)
    }

    @Test
    fun priorityEvictionDoesNotReorderNoteOnAndNoteOff() {
        val accumulator = MidiOutputAccumulator(
            maximumSize = MidiOutputAccumulator.FULL_PANIC_MESSAGE_COUNT,
            congestionThreshold = MidiOutputAccumulator.FULL_PANIC_MESSAGE_COUNT,
        )
        val timestamp = 1_000L
        accumulator.offer(
            incoming = List(47) { note ->
                byteArrayOf(0x90.toByte(), (note and 0x7F).toByte(), 100)
            } + byteArrayOf(0xB0.toByte(), 1, 10),
            timestampNanos = timestamp,
            nowNanos = 0L,
        )

        accumulator.offer(
            incoming = listOf(byteArrayOf(0x80.toByte(), 0, 0)),
            timestampNanos = timestamp,
            nowNanos = 1L,
        )

        val drained = accumulator.drain().messages
        assertEquals(MidiOutputAccumulator.FULL_PANIC_MESSAGE_COUNT, drained.size)
        assertFalse(drained.any { it.bytes.contentEquals(byteArrayOf(0xB0.toByte(), 1, 10)) })
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0, 100), drained.first().bytes)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, 0), drained.last().bytes)
        assertEquals(1L, accumulator.statistics.evictedContinuousMessages)
        assertEquals(0L, accumulator.statistics.panicCount)
    }

    @Test
    fun criticalReleaseEvictsOldestNonCriticalDiscreteMessage() {
        val capacity = MidiOutputAccumulator.FULL_PANIC_MESSAGE_COUNT
        val accumulator = MidiOutputAccumulator(
            maximumSize = capacity,
            congestionThreshold = capacity,
        )
        accumulator.offer(
            incoming = List(capacity) { index ->
                byteArrayOf(0x90.toByte(), (index and 0x7F).toByte(), 100)
            },
            timestampNanos = 10L,
            nowNanos = 0L,
        )

        val result = accumulator.offer(
            incoming = listOf(byteArrayOf(0x80.toByte(), 12, 0)),
            timestampNanos = 11L,
            nowNanos = 1L,
        )
        val retained = accumulator.drain().messages

        assertFalse(result.flushImmediately)
        assertEquals(capacity, retained.size)
        assertFalse(retained.any { it.bytes.contentEquals(byteArrayOf(0x90.toByte(), 0, 100)) })
        assertArrayEquals(byteArrayOf(0x80.toByte(), 12, 0), retained.last().bytes)
        assertEquals(1L, accumulator.statistics.evictedNonCriticalMessages)
        assertEquals(0L, accumulator.statistics.panicCount)
    }

    @Test
    fun nonCriticalDiscreteMessageIsDroppedWhenQueueIsFull() {
        val capacity = MidiOutputAccumulator.FULL_PANIC_MESSAGE_COUNT
        val accumulator = MidiOutputAccumulator(
            maximumSize = capacity,
            congestionThreshold = capacity,
        )
        accumulator.offer(
            incoming = List(capacity) { index ->
                byteArrayOf(0x90.toByte(), (index and 0x7F).toByte(), 100)
            },
            timestampNanos = 10L,
            nowNanos = 0L,
        )

        val result = accumulator.offer(
            incoming = listOf(byteArrayOf(0xC0.toByte(), 12)),
            timestampNanos = 11L,
            nowNanos = 1L,
        )
        val retained = accumulator.drain().messages

        assertFalse(result.flushImmediately)
        assertEquals(capacity, retained.size)
        assertFalse(retained.any { (it.bytes[0].toInt() and 0xF0) == 0xC0 })
        assertEquals(1L, accumulator.statistics.droppedNonCriticalMessages)
        assertEquals(0L, accumulator.statistics.panicCount)
    }

    @Test
    fun fullyCriticalQueueBecomesCompletePanic() {
        val capacity = MidiOutputAccumulator.FULL_PANIC_MESSAGE_COUNT
        val accumulator = MidiOutputAccumulator(
            maximumSize = capacity,
            congestionThreshold = capacity,
        )
        accumulator.offer(
            incoming = List(capacity) { index ->
                byteArrayOf(0x80.toByte(), (index and 0x7F).toByte(), 0)
            },
            timestampNanos = 10L,
            nowNanos = 0L,
        )

        val result = accumulator.offer(
            incoming = listOf(byteArrayOf(0xB0.toByte(), 64, 0)),
            timestampNanos = 11L,
            nowNanos = 1L,
        )
        val panic = accumulator.drain().messages

        assertTrue(result.flushImmediately)
        assertEquals(capacity, panic.size)
        assertEquals(1L, accumulator.statistics.panicCount)
        for (channel in 0 until 16) {
            assertTrue(panic.any { it.matchesControl(channel, 64) })
            assertTrue(panic.any { it.matchesControl(channel, 120) })
            assertTrue(panic.any { it.matchesControl(channel, 123) })
        }
    }

    @Test
    fun rpnPedalAndChannelModeControllersRemainDiscrete() {
        val discrete = listOf(0, 6, 32, 38, 64) + (96..101) + (120..127)
        val accumulator = MidiOutputAccumulator()
        discrete.forEach { controller ->
            accumulator.offer(
                incoming = listOf(
                    byteArrayOf(0xB0.toByte(), controller.toByte(), 1),
                    byteArrayOf(0xB0.toByte(), controller.toByte(), 2),
                ),
                timestampNanos = controller.toLong(),
                nowNanos = controller.toLong(),
            )
        }

        assertEquals(discrete.size * 2, accumulator.drain().messages.size)
        assertEquals(0L, accumulator.statistics.coalescedMessages)
    }

    @Test
    fun normalBurstUsesTwoMillisecondWindow() {
        val accumulator = MidiOutputAccumulator()
        accumulator.offer(
            incoming = listOf(
                byteArrayOf(0x90.toByte(), 60, 100),
                byteArrayOf(0x80.toByte(), 60, 0),
            ),
            timestampNanos = 1L,
            nowNanos = 50L,
        )

        assertEquals(
            50L + MidiOutputAccumulator.NORMAL_BATCH_WINDOW_NANOS,
            accumulator.nextFlushDeadlineNanos(),
        )
        assertEquals(
            MidiOutputAccumulator.NORMAL_BATCH_WINDOW_NANOS,
            accumulator.drain().batchWindowNanos,
        )
    }

    @Test
    fun actualCommandAndJournalEncodingIsSplitAtTwelveHundredBytes() {
        val messages = List(400) { index ->
            PendingMidiOutput(
                timestampNanos = index.toLong(),
                bytes = byteArrayOf(0x90.toByte(), (index and 0x7F).toByte(), 100),
                insertionOrder = index.toLong(),
            )
        }
        val journal = ByteArray(700) { 1 }
        var cursor = 0
        while (cursor < messages.size) {
            val end = largestFittingMidiOutputPrefix(
                messages = messages,
                startIndex = cursor,
                batchWindowNanos = 4_000_000L,
                maximumDatagramBytes = 1_200,
                encodedSize = { candidate -> encodedSize(candidate, journal) },
            )
            assertTrue(end > cursor)
            assertTrue(encodedSize(messages.subList(cursor, end), journal) <= 1_200)
            if (end < messages.size) {
                assertTrue(encodedSize(messages.subList(cursor, end + 1), journal) > 1_200)
            }
            cursor = end
        }
    }

    @Test
    fun journalThatLeavesNoRoomForOneCommandSignalsPanic() {
        val message = PendingMidiOutput(
            timestampNanos = 0L,
            bytes = byteArrayOf(0x80.toByte(), 60, 0),
            insertionOrder = 0L,
        )

        assertEquals(
            0,
            largestFittingMidiOutputPrefix(
                messages = listOf(message),
                startIndex = 0,
                batchWindowNanos = 2_000_000L,
                maximumDatagramBytes = 1_200,
                encodedSize = { candidate -> encodedSize(candidate, ByteArray(1_190) { 1 }) },
            ),
        )
    }

    private fun encodedSize(
        messages: List<PendingMidiOutput>,
        journal: ByteArray,
    ): Int = RtpMidiCodec.encode(
        RtpMidiPacket(
            sequenceNumber = 1,
            timestamp = 2,
            ssrc = 3,
            commands = messages.map { pending ->
                TimedMidiMessage(0, MidiChannelMessage.fromBytes(pending.bytes))
            },
            journal = journal,
        ),
    ).size

    private fun PendingMidiOutput.matchesControl(channel: Int, controller: Int): Boolean =
        (bytes[0].toInt() and 0xFF) == 0xB0 + channel &&
            (bytes[1].toInt() and 0xFF) == controller && bytes[2].toInt() == 0
}
