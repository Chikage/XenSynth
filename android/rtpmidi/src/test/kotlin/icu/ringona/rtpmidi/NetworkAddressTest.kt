package icu.ringona.rtpmidi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Inet6Address

class NetworkAddressTest {
    @Test
    fun ipv4AndIpv4MappedIpv6IdentifyTheSameHost() {
        val ipv4 = InetAddress.getByName("10.36.64.211")
        val mappedBytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(),
            10, 36, 64, 0xD3.toByte(),
        )
        val mapped: InetAddress = Inet6Address.getByAddress(null, mappedBytes, -1)

        assertTrue(mapped is Inet6Address)
        assertTrue(ipv4.sameNetworkHost(mapped))
        assertTrue(mapped.sameNetworkHost(ipv4))
        assertFalse(ipv4.sameNetworkHost(InetAddress.getByName("10.36.64.212")))
    }
}
