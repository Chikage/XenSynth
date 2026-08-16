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
    fun activeTransportRequiresRunningManagerAndFixedPortPair() {
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
            assertFalse(
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
