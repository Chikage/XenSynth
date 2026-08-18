package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress

class UdpPortPairTest {
    @Test
    fun reservesAConsecutiveExclusiveControlAndDataPair() {
        UdpPortPair.bind().use { pair ->
            assertEquals(UdpPortPair.FIXED_CONTROL_PORT, pair.controlPort)
            assertEquals(UdpPortPair.FIXED_DATA_PORT, pair.dataPort)
            assertTrue(pair.isFixedPortCapable)
            assertEquals(pair.controlPort + 1, pair.dataPort)
            assertFalse(pair.control.reuseAddress)
            assertFalse(pair.data.reuseAddress)
        }
    }

    @Test
    fun defaultPairUsesIpv4SocketsForBothAppleMidiChannels() {
        UdpPortPair.bind().use { pair ->
            assertTrue(pair.control.localAddress is Inet4Address)
            assertTrue(pair.data.localAddress is Inet4Address)
            assertEquals(4, pair.control.localAddress.address.size)
            assertEquals(4, pair.data.localAddress.address.size)
        }
    }

    @Test
    fun explicitIpv6FallbackKeepsLegacyDualStackBindingAvailable() {
        UdpPortPair.bind(ipv4Only = false).use { pair ->
            // The platform may expose a dual-stack IPv6 wildcard or an IPv4 wildcard. Either is
            // valid when callers explicitly opt out of the IPv4-only policy.
            assertTrue(pair.control.localAddress.address.size == 4 ||
                pair.control.localAddress.address.size == 16)
            assertTrue(pair.data.localAddress.address.size == 4 ||
                pair.data.localAddress.address.size == 16)
        }
    }

    @Test
    fun fallsBackToNextConsecutivePairWhenFixedPairIsOccupied() {
        DatagramSocket(null).use { occupied ->
            occupied.reuseAddress = false
            occupied.bind(InetSocketAddress(UdpPortPair.FIXED_CONTROL_PORT))

            UdpPortPair.bind().use { pair ->
                assertFalse(pair.isFixedPortCapable)
                assertEquals(5_006, pair.controlPort)
                assertEquals(5_007, pair.dataPort)
            }
        }
    }

    @Test
    fun keepsTryingPortPairsInOrder() {
        DatagramSocket(null).use { occupiedFixed ->
            occupiedFixed.reuseAddress = false
            occupiedFixed.bind(InetSocketAddress(UdpPortPair.FIXED_CONTROL_PORT))
            DatagramSocket(null).use { occupiedFirstFallback ->
                occupiedFirstFallback.reuseAddress = false
                occupiedFirstFallback.bind(InetSocketAddress(5_006))

                UdpPortPair.bind().use { pair ->
                    assertFalse(pair.isFixedPortCapable)
                    assertEquals(5_008, pair.controlPort)
                    assertEquals(5_009, pair.dataPort)
                }
            }
        }
    }

    @Test
    fun releasesFixedControlPortWhenOnlyFixedDataPortIsOccupied() {
        DatagramSocket(null).use { occupiedData ->
            occupiedData.reuseAddress = false
            occupiedData.bind(InetSocketAddress(UdpPortPair.FIXED_DATA_PORT))

            UdpPortPair.bind().use { fallback ->
                assertFalse(fallback.isFixedPortCapable)
                assertEquals(5_006, fallback.controlPort)
                assertEquals(5_007, fallback.dataPort)

                DatagramSocket(null).use { controlProbe ->
                    controlProbe.reuseAddress = false
                    controlProbe.bind(InetSocketAddress(UdpPortPair.FIXED_CONTROL_PORT))
                    assertEquals(UdpPortPair.FIXED_CONTROL_PORT, controlProbe.localPort)
                }
            }
        }
    }
}
