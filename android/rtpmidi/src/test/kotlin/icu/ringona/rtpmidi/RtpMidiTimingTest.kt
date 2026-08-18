package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RtpMidiTimingTest {
    @Test
    fun monotonicClockUsesTenKilohertzTicksAndWrapsRtpTimestamp() {
        var now = 1_000_000_000L
        val clock = RtpMidiClock(initialTimestampTicks = 0xFFFF_FFFEL) { now }

        now += 300_000

        assertEquals(0x1_0000_0001L, clock.nowTicks())
        assertEquals(1L, clock.nowTimestamp32())
        assertEquals(now, clock.monotonicNanosForTimestamp(clock.nowTicks()))
    }

    @Test
    fun ckExchangeComputesOffsetsForBothRoles() {
        val initiator = ClockSynchronizationSample.forInitiator(
            t1Local = 1_000,
            t2Remote = 4_000,
            t3Local = 1_020,
        )
        val responder = ClockSynchronizationSample.forResponder(
            t1Remote = 1_000,
            t2Local = 4_000,
            t3Remote = 1_020,
        )

        assertEquals(-2_990, initiator.remoteToLocalOffsetTicks)
        assertEquals(2_990, responder.remoteToLocalOffsetTicks)
        assertEquals(20, initiator.roundTripTicks)
        assertEquals(10, initiator.estimatedOneWayDelayTicks)
    }

    @Test
    fun synchronizedSessionMapsRemoteRtpTimestampOntoLocalNanos() {
        var now = 1_000_000_000L
        val localClock = RtpMidiClock(initialTimestampTicks = 1_000) { now }
        val sessionClock = RtpMidiSessionClock(localClock)

        sessionClock.updateFromInitiatorExchange(
            t1Local = 1_000,
            t2Remote = 2_000,
            t3Local = 1_020,
        )

        assertTrue(sessionClock.isSynchronized)
        assertEquals(-990L, sessionClock.remoteToLocalOffsetTicks)
        assertEquals(
            1_002_000_000L,
            sessionClock.localNanosForRemoteTimestamp(2_010, arrivalNanos = 1_050_000_000),
        )
        now += 1_000_000
        assertEquals(1_010L, localClock.nowTicks())
    }

    @Test
    fun unsynchronizedSessionAnchorsFirstPacketToArrival() {
        val localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)

        assertEquals(
            10_000_000L,
            sessionClock.localNanosForRemoteTimestamp(100, arrivalNanos = 10_000_000),
        )
        assertEquals(
            11_000_000L,
            sessionClock.localNanosForRemoteTimestamp(110, arrivalNanos = 30_000_000),
        )
        assertFalse(sessionClock.isSynchronized)
        assertNull(sessionClock.remoteToLocalOffsetTicks)
    }

    @Test
    fun timestampAndSequenceUnwrappersHandleWrapLateAndDuplicatePackets() {
        val timestamps = RtpTimestampUnwrapper()
        assertEquals(0xFFFF_FFFEL, timestamps.unwrap(0xFFFF_FFFE))
        assertEquals(0x1_0000_0001L, timestamps.unwrap(1))
        assertEquals(0xFFFF_FFFDL, timestamps.unwrap(0xFFFF_FFFD))

        val sequences = RtpSequenceTracker()
        assertEquals(RtpSequenceDisposition.FIRST, sequences.observe(0xFFFE).disposition)
        assertEquals(RtpSequenceDisposition.IN_ORDER, sequences.observe(0xFFFF).disposition)
        assertEquals(RtpSequenceDisposition.IN_ORDER, sequences.observe(0).disposition)
        val gap = sequences.observe(2)
        assertEquals(RtpSequenceDisposition.GAP, gap.disposition)
        assertEquals(1, gap.missingPackets)
        assertEquals(RtpSequenceDisposition.LATE, sequences.observe(1).disposition)
        assertEquals(RtpSequenceDisposition.DUPLICATE, sequences.observe(1).disposition)
    }

    @Test
    fun jitterBufferOrdersEqualSessionEventsByTargetTime() {
        val localClock = RtpMidiClock(initialTimestampTicks = 1_000) { 1_000_000_000L }
        val sessionClock = RtpMidiSessionClock(localClock)
        sessionClock.applySynchronization(
            ClockSynchronizationSample(
                remoteToLocalOffsetTicks = 0,
                roundTripTicks = 0,
                remoteReferenceTicks = 1_000,
            ),
        )
        val buffer = RtpMidiJitterBuffer<String>(
            sessionClock = sessionClock,
            initialDelayNanos = 20_000_000,
            minimumDelayNanos = 20_000_000,
            maximumDelayNanos = 20_000_000,
        )

        buffer.offer(remoteTimestamp = 1_020, arrivalNanos = 1_001_000_000, value = "later")
        buffer.offer(remoteTimestamp = 1_010, arrivalNanos = 1_001_000_000, value = "earlier")

        assertNull(buffer.pollReady(1_020_999_999))
        assertEquals(listOf("earlier"), buffer.drainReady(1_021_000_000).map { it.value })
        assertEquals(listOf("later"), buffer.drainReady(1_022_000_000).map { it.value })
        assertEquals(0, buffer.size)
    }

    @Test
    fun adaptiveDelayRisesForLargeTransitVariationAndStaysBounded() {
        val localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)
        val buffer = RtpMidiJitterBuffer<Unit>(sessionClock)

        buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = Unit)
        buffer.offer(remoteTimestamp = 110, arrivalNanos = 101_000_000, value = Unit)

        assertTrue(buffer.estimatedJitterNanos > 0)
        assertTrue(buffer.playoutDelayNanos > 60_000_000)
        assertTrue(buffer.playoutDelayNanos <= 120_000_000)
        buffer.clear()
        assertEquals(60_000_000L, buffer.playoutDelayNanos)
    }

    @Test
    fun eventsOfferedFromOneDatagramKeepTheirWireOrderDuringAdaptation() {
        val localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)
        val buffer = RtpMidiJitterBuffer<String>(sessionClock)
        buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = "warm-up")

        val first = buffer.offer(
            remoteTimestamp = 110,
            arrivalNanos = 101_000_000,
            value = "note-on",
        )
        val second = buffer.offer(
            remoteTimestamp = 110,
            arrivalNanos = 101_000_000,
            value = "pitch-bend",
        )

        assertEquals(first.targetTimeNanos, second.targetTimeNanos)
        assertEquals(
            listOf("warm-up", "note-on", "pitch-bend"),
            buffer.drainReady(Long.MAX_VALUE).map { it.value },
        )
    }

    @Test
    fun latePacketDoesNotMoveTheChronologicalPlayoutWatermarkBackwards() {
        val localClock = RtpMidiClock(initialTimestampTicks = 1_000) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)
        sessionClock.applySynchronization(
            ClockSynchronizationSample(
                remoteToLocalOffsetTicks = 0,
                roundTripTicks = 0,
                remoteReferenceTicks = 1_000,
            ),
        )
        val buffer = RtpMidiJitterBuffer<String>(
            sessionClock = sessionClock,
            initialDelayNanos = 40_000_000,
        )

        val first = buffer.offer(1_000, arrivalNanos = 1_000_000, value = "first")
        buffer.offer(999, arrivalNanos = 2_000_000, value = "late")
        val next = buffer.offer(1_001, arrivalNanos = 3_000_000, value = "next")

        assertTrue(next.targetTimeNanos >= first.targetTimeNanos)
    }

    @Test
    fun jitterBufferBoundsBacklogAndReportsDroppedTailEvents() {
        val localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)
        val buffer = RtpMidiJitterBuffer<String>(
            sessionClock = sessionClock,
            initialDelayNanos = 60_000_000,
            maximumQueueSize = 2,
        )

        buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = "first")
        buffer.offer(remoteTimestamp = 110, arrivalNanos = 0, value = "second")
        buffer.offer(remoteTimestamp = 120, arrivalNanos = 0, value = "third")

        assertEquals(2, buffer.size)
        assertEquals(1L, buffer.droppedEvents)
        assertEquals(
            RtpMidiJitterBufferStatistics(
                queuedValues = 2,
                peakQueueSize = 2,
                offeredValues = 3,
                playedValues = 0,
                droppedLateValues = 0,
                droppedOverflowValues = 1,
            ),
            buffer.statistics,
        )
        assertEquals(listOf("first", "second"), buffer.drainReady(Long.MAX_VALUE).map { it.value })
        assertEquals(2L, buffer.statistics.playedValues)
    }

    @Test
    fun protectedJitterValueEvictsLowerPriorityTail() {
        val sessionClock = RtpMidiSessionClock(RtpMidiClock(initialTimestampTicks = 0) { 0L })
        val buffer = RtpMidiJitterBuffer<String>(
            sessionClock = sessionClock,
            maximumQueueSize = 2,
            priorityOf = { if (it == "release") 2 else 1 },
            protectedPriority = 2,
        )

        buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = "first")
        buffer.offer(remoteTimestamp = 110, arrivalNanos = 0, value = "second")
        val release = buffer.offerWithResult(
            remoteTimestamp = 120,
            arrivalNanos = 0,
            value = "release",
        )

        assertTrue(release.accepted)
        assertEquals(
            listOf("first", "release"),
            buffer.drainReady(Long.MAX_VALUE).map { it.value },
        )
        assertEquals(1L, buffer.statistics.droppedOverflowValues)
    }

    @Test
    fun protectedLateValueRunsImmediatelyAfterPlayoutWatermark() {
        val sessionClock = RtpMidiSessionClock(RtpMidiClock(initialTimestampTicks = 0) { 0L })
        val buffer = RtpMidiJitterBuffer<String>(
            sessionClock = sessionClock,
            priorityOf = { if (it == "release") 2 else 1 },
            protectedPriority = 2,
        )
        val played = buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = "played")
        assertEquals(listOf("played"), buffer.drainReady(Long.MAX_VALUE).map { it.value })

        val release = buffer.offerWithResult(
            remoteTimestamp = 99,
            arrivalNanos = 0,
            value = "release",
        )

        assertTrue(release.accepted)
        assertTrue(release.scheduled.targetTimeNanos > played.targetTimeNanos)
        assertEquals(listOf("release"), buffer.drainReady(Long.MAX_VALUE).map { it.value })
        assertEquals(0L, buffer.statistics.droppedLateValues)
    }

    @Test
    fun droppedTailDoesNotPushLaterProtectedValueIntoTheFuture() {
        val sessionClock = RtpMidiSessionClock(RtpMidiClock(initialTimestampTicks = 0) { 0L })
        val buffer = RtpMidiJitterBuffer<String>(
            sessionClock = sessionClock,
            maximumQueueSize = 1,
            priorityOf = { if (it == "release") 2 else 1 },
            protectedPriority = 2,
        )
        buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = "first")
        val dropped = buffer.offerWithResult(
            remoteTimestamp = 10_000,
            arrivalNanos = 0,
            value = "tail",
        )

        val release = buffer.offerWithResult(
            remoteTimestamp = 101,
            arrivalNanos = 0,
            value = "release",
        )

        assertFalse(dropped.accepted)
        assertTrue(release.accepted)
        assertTrue(release.scheduled.targetTimeNanos < dropped.scheduled.targetTimeNanos)
        assertEquals(listOf("release"), buffer.drainReady(Long.MAX_VALUE).map { it.value })
    }

    @Test
    fun defaultJitterBufferCapacityIsSixThousandOneHundredFortyFourEvents() {
        val localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)
        val buffer = RtpMidiJitterBuffer<Int>(sessionClock)

        repeat(6_145) { index ->
            buffer.offer(
                remoteTimestamp = index.toLong(),
                arrivalNanos = 0L,
                value = index,
            )
        }

        assertEquals(6_144, buffer.size)
        assertEquals(1L, buffer.statistics.droppedOverflowValues)
    }

    @Test
    fun jitterBufferDiscardsEventsBehindThePlayoutWatermark() {
        val localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)
        val buffer = RtpMidiJitterBuffer<String>(sessionClock)

        buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = "played")
        assertEquals(listOf("played"), buffer.drainReady(Long.MAX_VALUE).map { it.value })

        buffer.offer(remoteTimestamp = 99, arrivalNanos = 0, value = "too-late")

        assertEquals(0, buffer.size)
        assertEquals(1L, buffer.statistics.droppedLateValues)
        assertEquals(0L, buffer.statistics.droppedOverflowValues)
        assertEquals(2L, buffer.statistics.offeredValues)
        assertEquals(1L, buffer.statistics.playedValues)
    }

    @Test
    fun jitterEstimateRisesQuicklyButDecaysOnlyAfterStableTransit() {
        val localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L }
        val sessionClock = RtpMidiSessionClock(localClock)
        val buffer = RtpMidiJitterBuffer<Unit>(sessionClock)

        buffer.offer(remoteTimestamp = 100, arrivalNanos = 0, value = Unit)
        buffer.offer(remoteTimestamp = 110, arrivalNanos = 101_000_000, value = Unit)
        val risenDelay = buffer.playoutDelayNanos

        // One clean packet must not immediately collapse the safety margin.
        buffer.offer(remoteTimestamp = 120, arrivalNanos = 2_000_000, value = Unit)
        assertEquals(risenDelay, buffer.playoutDelayNanos)

        // Repeated packets with the same transit time slowly bring the estimate down.
        repeat(128) { index ->
            buffer.offer(
                remoteTimestamp = 130L + index,
                arrivalNanos = 3_000_000L + index * 100_000L,
                value = Unit,
            )
        }
        assertTrue(buffer.playoutDelayNanos < risenDelay)
        assertTrue(buffer.playoutDelayNanos >= 24_000_000L)
    }

    @Test
    fun sessionQueuesOrderedNoteAndSustainRecoveryAndClearsActiveState() {
        val session = AppleMidiSession(
            id = "test-session",
            peerId = null,
            peerName = "loopback",
            advertisedAddress = java.net.InetAddress.getLoopbackAddress(),
            remoteControlPort = 5004,
            remoteDataPort = 5005,
            initiatorToken = 1L,
            initiatedLocally = false,
            localSsrc = 2L,
            remoteSsrc = 3L,
            state = AppleMidiSessionState.CONNECTED,
            createdAtNanos = 0L,
            lastActivityNanos = 0L,
            nextSequence = 0,
            localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L },
            jitterBufferMillis = 60L,
        )

        session.observeMidi(byteArrayOf(0x90.toByte(), 60, 100))
        session.observeMidi(byteArrayOf(0xB0.toByte(), 64, 127))
        val recovery = session.resetActiveStateForRecovery()
        assertEquals(2, recovery.size)

        session.enqueueRecovery(targetTimeNanos = 1_000_000L, messages = recovery)
        assertTrue(session.drainMidi(999_999L).isEmpty())
        val events = session.drainMidi(1_000_000L)

        assertEquals(2, events.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 60, 0), events[0].bytes)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 64, 0), events[1].bytes)
        assertTrue(session.resetActiveStateForRecovery().isEmpty())
    }

    @Test
    fun journalRepairInsertedAtGapTimestampStaysBetweenEarlierAndLaterCommands() {
        val session = AppleMidiSession(
            id = "ordered-recovery",
            peerId = null,
            peerName = "loopback",
            advertisedAddress = java.net.InetAddress.getLoopbackAddress(),
            remoteControlPort = 5004,
            remoteDataPort = 5005,
            initiatorToken = 1L,
            initiatedLocally = false,
            localSsrc = 2L,
            remoteSsrc = 3L,
            state = AppleMidiSessionState.CONNECTED,
            createdAtNanos = 0L,
            lastActivityNanos = 0L,
            nextSequence = 0,
            localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L },
            jitterBufferMillis = 60L,
        )

        session.offerMidi(100, 0L, byteArrayOf(0x90.toByte(), 60, 100))
        session.offerMidi(100, 1L, byteArrayOf(0x80.toByte(), 60, 0))
        session.offerMidi(100, 1L, byteArrayOf(0x90.toByte(), 61, 100))

        val messages = session.drainMidi(Long.MAX_VALUE).map(AppleMidiEvent::bytes)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 100), messages[0])
        assertArrayEquals(byteArrayOf(0x80.toByte(), 60, 0), messages[1])
        assertArrayEquals(byteArrayOf(0x90.toByte(), 61, 100), messages[2])
    }

    @Test
    fun criticalOnlyJitterOverflowQueuesCompletePanic() {
        val session = testSession("overflow-panic", jitterBufferMaximumQueueSize = 48)
        repeat(48) { note ->
            session.offerMidi(
                remoteTimestamp = 100L + note,
                arrivalNanos = 0L,
                message = byteArrayOf(0x80.toByte(), note.toByte(), 0),
            )
        }

        session.offerMidi(
            remoteTimestamp = 200,
            arrivalNanos = 0L,
            message = byteArrayOf(0xB0.toByte(), 64, 0),
        )
        val panic = session.drainMidi(Long.MAX_VALUE).map(AppleMidiEvent::bytes)

        assertEquals(48, panic.size)
        for (channel in 0 until 16) {
            assertTrue(panic.any { it.matchesControl(channel, 64) })
            assertTrue(panic.any { it.matchesControl(channel, 120) })
            assertTrue(panic.any { it.matchesControl(channel, 123) })
        }
    }

    @Test
    fun sessionCloseWaitsForInflightDeliveryAndRejectsLaterEvents() {
        val session = testSession("delivery-race")
        val order = Collections.synchronizedList(mutableListOf<String>())
        val deliveryEntered = CountDownLatch(1)
        val releaseDelivery = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)

        val deliveryThread = thread(name = "rtpmidi-test-delivery") {
            assertTrue(
                session.deliverIfOpen {
                    order += "event-start"
                    deliveryEntered.countDown()
                    assertTrue(releaseDelivery.await(5, TimeUnit.SECONDS))
                    order += "event-end"
                },
            )
        }
        assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS))
        val closeThread = thread(name = "rtpmidi-test-close") {
            closeAttempted.countDown()
            assertTrue(session.closeDelivery { order += "close" })
        }
        assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
        releaseDelivery.countDown()
        deliveryThread.join(5_000)
        closeThread.join(5_000)

        assertFalse(deliveryThread.isAlive)
        assertFalse(closeThread.isAlive)
        assertEquals(listOf("event-start", "event-end", "close"), order)
        assertFalse(session.deliverIfOpen { order += "late-event" })
        assertEquals(listOf("event-start", "event-end", "close"), order)
    }

    @Test
    fun sessionStatisticsSeparateLossReorderDuplicateAndLatePackets() {
        val session = testSession("statistics")
        val initial = testRtpPacket(sequence = 10)
        val held = testRtpPacket(sequence = 12)

        session.offerRtpPacket(initial, arrivalNanos = 0L)
        session.offerRtpPacket(held, arrivalNanos = 1_000_000L)
        session.offerRtpPacket(held, arrivalNanos = 2_000_000L)
        session.expireRtpGap(nowNanos = 13_000_000L)
        session.offerRtpPacket(testRtpPacket(sequence = 11), arrivalNanos = 14_000_000L)

        session.recoverFromPacketLoss(
            RtpMidiPacketLoss(
                firstMissingExtendedSequence = 11,
                missingPackets = 1,
                recoveryPacket = BufferedRtpMidiPacket(held, 12, 1_000_000L),
            ),
        )
        val outgoing = session.takeNextOutgoingSequence()
        session.recordOutgoingPacket(outgoing, emptyList(), sentAtNanos = 20_000_000L)
        session.recordOutgoingSendFailure()
        session.recordReceiverFeedbackSent()
        session.recordJournalHeartbeatSent(urgent = true)
        session.offerMidi(100, 0L, byteArrayOf(0x90.toByte(), 60, 100))

        val statistics = session.statistics
        assertEquals(4L, statistics.incomingPackets)
        assertEquals(2L, statistics.releasedPackets)
        assertEquals(1L, statistics.lostPackets)
        assertEquals(1L, statistics.reorderedPackets)
        assertEquals(1L, statistics.duplicatePackets)
        assertEquals(1L, statistics.latePackets)
        assertEquals(1L, statistics.recoveryAttempts)
        assertEquals(1L, statistics.recoveryFallbacks)
        assertEquals(1L, statistics.outgoingPackets)
        assertEquals(1L, statistics.outgoingSendFailures)
        assertEquals(1L, statistics.receiverFeedbackSent)
        assertEquals(1L, statistics.journalHeartbeatsSent)
        assertEquals(1L, statistics.urgentJournalHeartbeatsSent)
        assertEquals(1L, statistics.jitterBuffer.offeredValues)
        assertEquals(60_000_000L, statistics.jitterBufferDelayNanos)
    }

    @Test
    fun receiverFeedbackKeepsNewestSequenceWhileRateLimited() {
        val session = testSession("receiver-feedback")
        session.queueReceiverFeedback(10)
        assertEquals(10, session.takeReceiverFeedback(100, 25))

        session.queueReceiverFeedback(11)
        session.queueReceiverFeedback(12)
        assertNull(session.takeReceiverFeedback(124, 25))
        assertEquals(12, session.takeReceiverFeedback(125, 25))
    }

    private fun testSession(
        id: String,
        jitterBufferMaximumQueueSize: Int = 6_144,
    ) = AppleMidiSession(
        id = id,
        peerId = null,
        peerName = "loopback",
        advertisedAddress = java.net.InetAddress.getLoopbackAddress(),
        remoteControlPort = 5004,
        remoteDataPort = 5005,
        initiatorToken = 1L,
        initiatedLocally = false,
        localSsrc = 2L,
        remoteSsrc = 3L,
        state = AppleMidiSessionState.CONNECTED,
        createdAtNanos = 0L,
        lastActivityNanos = 0L,
        nextSequence = 0,
        localClock = RtpMidiClock(initialTimestampTicks = 0) { 0L },
        jitterBufferMillis = 60L,
        jitterBufferMaximumQueueSize = jitterBufferMaximumQueueSize,
    )

    private fun testRtpPacket(sequence: Int) = RtpMidiPacket(
        sequenceNumber = sequence,
        timestamp = sequence.toLong(),
        ssrc = 3L,
        commands = emptyList(),
        firstDeltaEncoded = false,
        journal = null,
    )

    private fun ByteArray.matchesControl(channel: Int, controller: Int): Boolean =
        (getOrNull(0)?.toInt()?.and(0xFF)) == 0xB0 + channel &&
            (getOrNull(1)?.toInt()?.and(0xFF)) == controller && getOrNull(2)?.toInt() == 0
}
