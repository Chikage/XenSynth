package icu.ringona.rtpmidi

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

internal data class UdpPortPair(
    val control: DatagramSocket,
    val data: DatagramSocket,
    val isFixedPortCapable: Boolean,
) : AutoCloseable {
    val controlPort: Int get() = control.localPort
    val dataPort: Int get() = data.localPort

    override fun close() {
        control.close()
        data.close()
    }

    companion object {
        const val FIXED_CONTROL_PORT = 5_004
        const val FIXED_DATA_PORT = 5_005
        private const val FIRST_DYNAMIC_PORT = 49_152
        private const val LAST_CONTROL_PORT = 65_534

        /** Prefers 5004/5005, then reserves a passive-receive fallback pair if they are occupied. */
        fun bind(random: SecureRandom = SecureRandom()): UdpPortPair {
            bindPair(FIXED_CONTROL_PORT, isFixedPortCapable = true)?.let { return it }
            repeat(512) {
                val controlPort = FIRST_DYNAMIC_PORT +
                    random.nextInt(LAST_CONTROL_PORT - FIRST_DYNAMIC_PORT + 1)
                bindPair(controlPort, isFixedPortCapable = false)?.let { return it }
            }
            throw IllegalStateException("Could not reserve consecutive UDP ports for AppleMIDI")
        }

        private fun bindPair(controlPort: Int, isFixedPortCapable: Boolean): UdpPortPair? {
            val control = bindOne(controlPort) ?: return null
            val data = bindOne(controlPort + 1)
            if (data != null) return UdpPortPair(control, data, isFixedPortCapable)
            control.close()
            return null
        }

        private fun bindOne(port: Int): DatagramSocket? = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = false
                bind(InetSocketAddress(port))
                receiveBufferSize = 64 * 1024
                sendBufferSize = 64 * 1024
            }
        }.getOrNull()
    }
}
