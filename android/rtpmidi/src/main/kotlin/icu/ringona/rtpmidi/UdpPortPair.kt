package icu.ringona.rtpmidi

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

internal data class UdpPortPair(
    val control: DatagramSocket,
    val data: DatagramSocket,
) : AutoCloseable {
    val controlPort: Int get() = control.localPort
    val dataPort: Int get() = data.localPort

    override fun close() {
        control.close()
        data.close()
    }

    companion object {
        private const val FIRST_DYNAMIC_PORT = 49_152
        private const val LAST_CONTROL_PORT = 65_534

        /** Binds N and N+1 before returning, so the published SRV port always has its data mate. */
        fun bind(random: SecureRandom = SecureRandom()): UdpPortPair {
            repeat(512) {
                val controlPort = FIRST_DYNAMIC_PORT +
                    random.nextInt(LAST_CONTROL_PORT - FIRST_DYNAMIC_PORT + 1)
                val control = bindOne(controlPort) ?: return@repeat
                val data = bindOne(controlPort + 1)
                if (data != null) return UdpPortPair(control, data)
                control.close()
            }
            throw IllegalStateException("Could not reserve consecutive UDP ports for AppleMIDI")
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
