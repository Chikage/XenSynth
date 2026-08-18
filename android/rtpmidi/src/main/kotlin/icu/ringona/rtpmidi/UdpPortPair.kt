package icu.ringona.rtpmidi

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
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

        /**
         * Prefers 5004/5005, then reserves a passive-receive fallback pair if they are occupied.
         *
         * [ipv4Only] deliberately defaults to true. Android's ordinary DatagramSocket wildcard
         * constructor can select an IPv6 socket, even when an IPv4 address is supplied to bind();
         * using an INET DatagramChannel makes the address family deterministic for both ports.
         */
        fun bind(
            random: SecureRandom = SecureRandom(),
            ipv4Only: Boolean = true,
        ): UdpPortPair {
            bindPair(FIXED_CONTROL_PORT, isFixedPortCapable = true, ipv4Only = ipv4Only)
                ?.let { return it }
            repeat(512) {
                val controlPort = FIRST_DYNAMIC_PORT +
                    random.nextInt(LAST_CONTROL_PORT - FIRST_DYNAMIC_PORT + 1)
                bindPair(controlPort, isFixedPortCapable = false, ipv4Only = ipv4Only)
                    ?.let { return it }
            }
            throw IllegalStateException("Could not reserve consecutive UDP ports for AppleMIDI")
        }

        private fun bindPair(
            controlPort: Int,
            isFixedPortCapable: Boolean,
            ipv4Only: Boolean,
        ): UdpPortPair? {
            val control = bindOne(controlPort, ipv4Only) ?: return null
            val data = bindOne(controlPort + 1, ipv4Only)
            if (data != null) return UdpPortPair(control, data, isFixedPortCapable)
            control.close()
            return null
        }

        private fun bindOne(port: Int, ipv4Only: Boolean): DatagramSocket? {
            if (!ipv4Only) {
                return runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = false
                        bind(InetSocketAddress(port))
                        receiveBufferSize = 64 * 1024
                        sendBufferSize = 64 * 1024
                    }
                }.getOrNull()
            }

            // DatagramChannel.open(INET) is available on the library's minSdk and guarantees an
            // AF_INET socket. Keep the channel owned by the returned adaptor; closing the socket
            // closes its channel as well.
            val channel = runCatching {
                DatagramChannel.open(StandardProtocolFamily.INET)
            }.getOrNull() ?: return null
            return runCatching {
                channel.socket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)), port))
                    receiveBufferSize = 64 * 1024
                    sendBufferSize = 64 * 1024
                }
            }.onFailure { runCatching { channel.close() } }.getOrNull()
        }
    }
}
