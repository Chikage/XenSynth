package icu.ringona.rtpmidi

import java.net.InetAddress

/** Treats IPv4 and its IPv4-mapped IPv6 representation as the same UDP peer. */
internal fun InetAddress.sameNetworkHost(other: InetAddress): Boolean {
    if (this == other) return true
    return normalizedAddressBytes().contentEquals(other.normalizedAddressBytes())
}

private fun InetAddress.normalizedAddressBytes(): ByteArray {
    val bytes = address
    val isIpv4Mapped = bytes.size == 16 &&
        bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    return if (isIpv4Mapped) bytes.copyOfRange(12, 16) else bytes
}
