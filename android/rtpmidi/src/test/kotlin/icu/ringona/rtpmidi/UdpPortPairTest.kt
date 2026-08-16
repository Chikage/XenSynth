package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
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
    fun fallsBackToPassiveConsecutivePortsWhenFixedPairIsOccupied() {
        DatagramSocket(null).use { occupied ->
            occupied.reuseAddress = false
            occupied.bind(InetSocketAddress(UdpPortPair.FIXED_CONTROL_PORT))

            UdpPortPair.bind().use { pair ->
                assertFalse(pair.isFixedPortCapable)
                assertEquals(pair.controlPort + 1, pair.dataPort)
                assertTrue(pair.controlPort >= 49_152)
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
                assertEquals(fallback.controlPort + 1, fallback.dataPort)

                DatagramSocket(null).use { controlProbe ->
                    controlProbe.reuseAddress = false
                    controlProbe.bind(InetSocketAddress(UdpPortPair.FIXED_CONTROL_PORT))
                    assertEquals(UdpPortPair.FIXED_CONTROL_PORT, controlProbe.localPort)
                }
            }
        }
    }
}
