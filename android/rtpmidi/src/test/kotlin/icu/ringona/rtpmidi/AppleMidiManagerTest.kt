package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class AppleMidiManagerTest {
    @Test
    fun scheduledDeliveryWakesAheadButPreservesTheTargetDeadline() {
        val target = 2_000_000_000L
        val lookahead = 8_000_000L

        assertEquals(1_992_000_000L, appleMidiDeliveryWakeupNanos(target, lookahead))
        assertEquals(target, appleMidiDeliveryDrainThroughNanos(1_992_000_000L, lookahead))
    }

    @Test
    fun ordinaryDeliveryUsesNoLookaheadAndDeadlineMathSaturates() {
        assertEquals(123L, appleMidiDeliveryWakeupNanos(123L, 0L))
        assertEquals(123L, appleMidiDeliveryDrainThroughNanos(123L, 0L))
        assertEquals(0L, appleMidiDeliveryWakeupNanos(5L, 8L))
        assertEquals(
            Long.MAX_VALUE,
            appleMidiDeliveryDrainThroughNanos(Long.MAX_VALUE - 3L, 8L),
        )
    }

    @Test
    fun onlyScheduledListenersOptIntoTheConfiguredDefaultLookahead() {
        val configuration = AppleMidiConfiguration(serviceName = "Timing test")
        val ordinary = object : AppleMidiListener {
            override fun onMidiEvent(event: AppleMidiEvent) = Unit
        }
        val scheduled = object : AppleMidiScheduledListener {
            override fun onMidiEvent(event: AppleMidiEvent) = Unit
        }

        assertEquals(0L, appleMidiDeliveryLookaheadNanos(configuration, ordinary))
        assertEquals(8_000_000L, appleMidiDeliveryLookaheadNanos(configuration, scheduled))
    }

    @Test
    fun activeTransportRequiresRunningManagerAndHealthyPortPair() {
        val first = DatagramSocket()
        val second = DatagramSocket()
        val fixed = UdpPortPair(first, second, isFixedPortCapable = true)
        fixed.use {
            assertTrue(
                activeMidiTransportAllowed(
                    running = true,
                    portPair = fixed,
                    transportHealthy = true,
                ),
            )
            assertFalse(
                activeMidiTransportAllowed(
                    running = false,
                    portPair = fixed,
                    transportHealthy = true,
                ),
            )
            assertFalse(
                activeMidiTransportAllowed(
                    running = true,
                    portPair = fixed,
                    transportHealthy = false,
                ),
            )
        }

        val fallback = UdpPortPair(
            DatagramSocket(),
            DatagramSocket(),
            isFixedPortCapable = false,
        )
        fallback.use {
            assertTrue(
                activeMidiTransportAllowed(
                    running = true,
                    portPair = fallback,
                    transportHealthy = true,
                ),
            )
        }
        assertFalse(
            activeMidiTransportAllowed(
                running = true,
                portPair = null,
                transportHealthy = true,
            ),
        )
    }

    @Test
    fun incomingInvitationCreatesAnImplicitDuplexPeer() {
        assertTrue(
            appleMidiSessionInputAllowed(
                inputSelectionConfigured = true,
                initiatedLocally = false,
                selectedForInput = false,
            ),
        )
        assertTrue(
            appleMidiSessionOutputAllowed(
                initiatedLocally = false,
                selectedForOutput = false,
            ),
        )

        assertFalse(
            appleMidiSessionInputAllowed(
                inputSelectionConfigured = true,
                initiatedLocally = true,
                selectedForInput = false,
            ),
        )
        assertFalse(
            appleMidiSessionOutputAllowed(
                initiatedLocally = true,
                selectedForOutput = false,
            ),
        )
    }

   @Test
   fun invitationResponseCanMoveToAnotherAddressOnTheSameRemotePort() {
        val advertisedAddress = InetAddress.getByName("10.36.64.211")
        val responseAddress = InetAddress.getByName("10.36.64.107")
        val session = outgoingSession(advertisedAddress, initiatorToken = 0x1234)
        val remote = InetSocketAddress(responseAddress, 5_004)

        assertSame(
            session,
            selectInvitationResponseSession(
                sessions = listOf(session),
                remote = remote,
                initiatorToken = 0x1234,
                responseSsrc = 7,
                dataChannel = false,
            ),
        )

        session.applyInvitationResponse(
            packet = AppleMidiControlPacket.Invitation(
                command = AppleMidiInvitationCommand.OK,
                initiatorToken = 0x1234,
                ssrc = 7,
                name = "Mac probe",
            ),
            remote = remote,
            dataChannel = false,
            nowNanos = 42,
        )
        assertEquals(responseAddress, session.transportAddress)
        assertEquals(7L, session.remoteSsrc)
        assertEquals("Mac probe", session.peerName)
        assertEquals(42L, session.lastActivityNanos)
        assertTrue(session.controlAccepted)
        assertTrue(
            session.connectsTo(
                resolvedService(advertisedAddress, controlPort = 5_004),
            ),
        )
    }

    @Test
    fun invitationResponseRequiresExpectedPhasePortAddressAndSsrc() {
        val session = outgoingSession(
            address = InetAddress.getByName("10.36.64.211"),
            initiatorToken = 0x1234,
        )

        assertNull(
            selectInvitationResponseSession(
                sessions = listOf(session),
                remote = InetSocketAddress("10.36.64.107", 5_004),
                initiatorToken = 0x5678,
                responseSsrc = 7,
                dataChannel = false,
            ),
        )
        assertNull(
            selectInvitationResponseSession(
                sessions = listOf(session),
                remote = InetSocketAddress("10.36.64.107", 5_005),
                initiatorToken = 0x1234,
                responseSsrc = 7,
                dataChannel = false,
            ),
        )
        session.controlAccepted = true
        session.remoteSsrc = 7
        assertNull(
            selectInvitationResponseSession(
                sessions = listOf(session),
                remote = InetSocketAddress("10.36.64.211", 5_004),
                initiatorToken = 0x1234,
                responseSsrc = 7,
                dataChannel = false,
            ),
        )
        assertNull(
            selectInvitationResponseSession(
                sessions = listOf(session),
                remote = InetSocketAddress("10.36.64.107", 5_005),
                initiatorToken = 0x1234,
                responseSsrc = 7,
                dataChannel = true,
            ),
        )
        assertNull(
            selectInvitationResponseSession(
                sessions = listOf(session),
                remote = InetSocketAddress("10.36.64.211", 5_005),
                initiatorToken = 0x1234,
                responseSsrc = 8,
                dataChannel = true,
            ),
        )
        assertSame(
            session,
            selectInvitationResponseSession(
                sessions = listOf(session),
                remote = InetSocketAddress("10.36.64.211", 5_005),
                initiatorToken = 0x1234,
                responseSsrc = 7,
                dataChannel = true,
            ),
        )
    }

    @Test
    fun acceptedDataInvitationMakesMediaUsableBeforeClockSynchronization() {
        val address = InetAddress.getByName("10.36.64.211")
        val session = outgoingSession(address, initiatorToken = 0x1234)
        session.applyInvitationResponse(
            packet = AppleMidiControlPacket.Invitation(
                command = AppleMidiInvitationCommand.OK,
                initiatorToken = 0x1234,
                ssrc = 7,
                name = "CoreMIDI peer",
            ),
            remote = InetSocketAddress(address, 5_004),
            dataChannel = false,
            nowNanos = 10,
        )

        session.applyInvitationResponse(
            packet = AppleMidiControlPacket.Invitation(
                command = AppleMidiInvitationCommand.OK,
                initiatorToken = 0x1234,
                ssrc = 7,
                name = "CoreMIDI peer",
            ),
            remote = InetSocketAddress(address, 5_005),
            dataChannel = true,
            nowNanos = 20,
        )

        assertTrue(session.dataAccepted)
        assertEquals(AppleMidiSessionState.CONNECTED, session.state)
        assertFalse(session.sessionClock.isSynchronized)
    }

    @Test
    fun invitationResponseFallbackRejectsAmbiguousTokenAndPort() {
        val sessions = listOf(
            outgoingSession(InetAddress.getByName("10.36.64.211"), initiatorToken = 0x1234),
            outgoingSession(InetAddress.getByName("10.36.64.212"), initiatorToken = 0x1234),
        )

        assertNull(
            selectInvitationResponseSession(
                sessions = sessions,
                remote = InetSocketAddress("10.36.64.107", 5_004),
                initiatorToken = 0x1234,
                responseSsrc = 7,
                dataChannel = false,
            ),
        )
        assertSame(
            sessions[1],
            selectInvitationResponseSession(
                sessions = sessions,
                remote = InetSocketAddress("10.36.64.212", 5_004),
                initiatorToken = 0x1234,
                responseSsrc = 7,
                dataChannel = false,
            ),
        )
    }

    @Test
    fun rtpSessionSelectionUsesSsrcBeforeSameEndpointInsertionOrder() {
        val address = InetAddress.getByName("10.36.64.211")
        val oldSession = connectedSession("old", address, remoteSsrc = 11, lastActivityNanos = 100)
        val restartedSession = connectedSession(
            "restarted",
            address,
            remoteSsrc = 22,
            lastActivityNanos = 200,
        )
        val remote = InetSocketAddress(address, 5_005)

        assertSame(
            restartedSession,
            selectRtpMidiSession(listOf(oldSession, restartedSession), remote, remoteSsrc = 22),
        )
        assertSame(
            oldSession,
            selectRtpMidiSession(listOf(oldSession, restartedSession), remote, remoteSsrc = 11),
        )
        assertNull(selectRtpMidiSession(listOf(oldSession, restartedSession), remote, remoteSsrc = 33))
    }

    @Test
    fun rtpSessionSelectionPrefersNewestDuplicateForOneSsrc() {
        val address = InetAddress.getByName("10.36.64.211")
        val oldSession = connectedSession("old", address, remoteSsrc = 11, lastActivityNanos = 100)
        val newSession = connectedSession("new", address, remoteSsrc = 11, lastActivityNanos = 200)

        assertSame(
            newSession,
            selectRtpMidiSession(
                listOf(oldSession, newSession),
                InetSocketAddress(address, 5_005),
                remoteSsrc = 11,
            ),
        )
    }

    @Test
    fun rtpSessionFallbackAcceptsOneConnectedHostAndSsrcAcrossDataPortChange() {
        val address = InetAddress.getByName("10.36.64.211")
        val session = connectedSession("coremidi", address, remoteSsrc = 22, lastActivityNanos = 200)
        val resumedDataPort = InetSocketAddress(address, 51_234)

        assertNull(selectRtpMidiSession(listOf(session), resumedDataPort, remoteSsrc = 22))
        assertSame(
            session,
            selectRtpMidiSessionByHostAndSsrc(
                sessions = listOf(session),
                remote = resumedDataPort,
                remoteSsrc = 22,
            ),
        )
    }

    @Test
    fun rtpSessionFallbackRejectsAmbiguousSameHostAndSsrc() {
        val address = InetAddress.getByName("10.36.64.211")
        val oldSession = connectedSession("old", address, remoteSsrc = 22, lastActivityNanos = 100)
        val newSession = connectedSession("new", address, remoteSsrc = 22, lastActivityNanos = 200)

        assertNull(
            selectRtpMidiSessionByHostAndSsrc(
                sessions = listOf(oldSession, newSession),
                remote = InetSocketAddress(address, 51_234),
                remoteSsrc = 22,
            ),
        )
    }

    @Test
    fun receiverFeedbackSelectionUsesControlPortAndSsrc() {
        val address = InetAddress.getByName("10.36.64.211")
        val oldSession = connectedSession("old", address, remoteSsrc = 11, lastActivityNanos = 100)
        val newSession = connectedSession("new", address, remoteSsrc = 22, lastActivityNanos = 200)

        assertSame(
            newSession,
            selectReceiverFeedbackSession(
                listOf(oldSession, newSession),
                InetSocketAddress(address, 5_004),
                remoteSsrc = 22,
            ),
        )
        assertNull(
            selectReceiverFeedbackSession(
                listOf(oldSession, newSession),
                InetSocketAddress(address, 5_005),
                remoteSsrc = 22,
            ),
        )
    }

    @Test
    fun receiverFeedbackCoalescesWithoutRegressingAcrossSequenceWrap() {
        val session = connectedSession(
            id = "feedback",
            address = InetAddress.getByName("10.36.64.211"),
            remoteSsrc = 22,
            lastActivityNanos = 200,
        )

        session.queueReceiverFeedback(65_535)
        session.queueReceiverFeedback(65_534)
        session.queueReceiverFeedback(65_536)

        assertEquals(0, session.takeReceiverFeedback())
        assertNull(session.takeReceiverFeedback())
    }

    private fun outgoingSession(
        address: InetAddress,
        initiatorToken: Long,
    ) = AppleMidiSession(
        id = "session-${address.hostAddress}",
        peerId = "peer-${address.hostAddress}",
        peerName = "Test peer",
        advertisedAddress = address,
        remoteControlPort = 5_004,
        remoteDataPort = 5_005,
        initiatorToken = initiatorToken,
        initiatedLocally = true,
        localSsrc = 1,
        remoteSsrc = null,
        state = AppleMidiSessionState.INVITING,
        createdAtNanos = 0,
        lastActivityNanos = 0,
        nextSequence = 0,
        localClock = RtpMidiClock(initialTimestampTicks = 0) { 0 },
        jitterBufferMillis = 60,
    )

    private fun connectedSession(
        id: String,
        address: InetAddress,
        remoteSsrc: Long,
        lastActivityNanos: Long,
    ) = AppleMidiSession(
        id = id,
        peerId = "peer-$id",
        peerName = "Test peer",
        advertisedAddress = address,
        remoteControlPort = 5_004,
        remoteDataPort = 5_005,
        initiatorToken = id.hashCode().toLong() and 0xFFFF_FFFFL,
        initiatedLocally = false,
        localSsrc = 1,
        remoteSsrc = remoteSsrc,
        state = AppleMidiSessionState.CONNECTED,
        createdAtNanos = 0,
        lastActivityNanos = lastActivityNanos,
        nextSequence = 0,
        localClock = RtpMidiClock(initialTimestampTicks = 0) { 0 },
        jitterBufferMillis = 60,
    )

    private fun resolvedService(
        address: InetAddress,
        controlPort: Int,
    ) = ResolvedAppleMidiService(
        id = "service",
        name = "Test peer",
        type = NsdDirectory.SERVICE_TYPE,
        host = address,
        controlPort = controlPort,
        model = "Test model",
    )
}
