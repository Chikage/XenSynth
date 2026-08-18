package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class AppleMidiAddressTest {
    @Test
    fun privateIpv4WinsWhenBonjourReturnsIpv6First() {
        val ipv6 = InetAddress.getByName("fe80::1234")
        val ipv4 = InetAddress.getByName("192.168.1.42")

        assertEquals(ipv4, selectAppleMidiAddress(listOf(ipv6, ipv4)))
    }

    @Test
    fun publicAndLoopbackIpv4AreNotPublishedAsLanPeers() {
        val publicAddress = InetAddress.getByName("8.8.8.8")
        val loopback = InetAddress.getByName("127.0.0.1")

        assertNull(selectAppleMidiAddress(listOf(publicAddress, loopback)))
    }

    @Test
    fun ipv4PreferredPolicyFallsBackToIpv6OnlyWhenNoLanIpv4Exists() {
        val ipv6 = InetAddress.getByName("2001:db8::42")
        val routedIpv4 = InetAddress.getByName("8.8.8.8")

        assertEquals(
            ipv6,
            selectAppleMidiAddress(listOf(ipv6), AppleMidiAddressPolicy.IPV4_PREFERRED),
        )
        assertEquals(
            routedIpv4,
            selectAppleMidiAddress(
                listOf(ipv6, routedIpv4),
                AppleMidiAddressPolicy.IPV4_PREFERRED,
            ),
        )
        assertNull(selectAppleMidiAddress(listOf(ipv6), AppleMidiAddressPolicy.IPV4_ONLY))
    }
}
