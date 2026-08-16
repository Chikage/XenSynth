package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UdpPortPairTest {
    @Test
    fun reservesAConsecutiveExclusiveControlAndDataPair() {
        UdpPortPair.bind().use { pair ->
            assertEquals(pair.controlPort + 1, pair.dataPort)
            assertFalse(pair.control.reuseAddress)
            assertFalse(pair.data.reuseAddress)
        }
    }
}
