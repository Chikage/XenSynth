package icu.ringona.rtpmidi

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Address selection policy for Bonjour AppleMIDI peers. */
enum class AppleMidiAddressPolicy {
    /** Use a private/link-local IPv4 address only. */
    IPV4_ONLY,

    /** Prefer a private/link-local IPv4 address, then allow a usable IPv6 address. */
    IPV4_PREFERRED,
}

/**
 * Chooses a LAN address from the complete address set returned by DNS-SD.
 *
 * Apple platforms commonly publish both A and AAAA records.  The old Android NSD API exposed
 * only the first record, which made the result depend on resolver ordering and frequently picked
 * an IPv6 link-local address.  Keep this function side-effect free so address-family behavior is
 * covered by JVM tests and can be reused by the resolver worker.
 */
internal fun selectAppleMidiAddress(
    addresses: Collection<InetAddress>,
    policy: AppleMidiAddressPolicy = AppleMidiAddressPolicy.IPV4_ONLY,
): InetAddress? {
    val usable = addresses.asSequence()
        .filterNot(InetAddress::isAnyLocalAddress)
        .filterNot(InetAddress::isLoopbackAddress)
        .filterNot(InetAddress::isMulticastAddress)
        .distinctBy { address -> address.address.contentToString() }
        .toList()

    // Restrict the default path to RFC1918 or link-local IPv4.  This prevents cellular/VPN or
    // public addresses from replacing the Wi-Fi LAN endpoint shown in Destination.
    val lanIpv4 = usable
        .filterIsInstance<Inet4Address>()
        .filter { address -> address.isSiteLocalAddress || address.isLinkLocalAddress }
        .sortedWith(compareBy<Inet4Address> { if (it.isSiteLocalAddress) 0 else 1 })
    lanIpv4.firstOrNull()?.let { return it }

    if (policy == AppleMidiAddressPolicy.IPV4_ONLY) return null

    // In fallback mode an otherwise usable IPv4 address still has a more predictable socket
    // family than IPv6, even when it is not RFC1918 (for example a routed lab VLAN).
    usable.filterIsInstance<Inet4Address>().firstOrNull()?.let { return it }

    // IPv6 is an explicit fallback only.  Scope information on an Inet6Address is retained by
    // returning the original object instead of rebuilding it from hostAddress text.
    return usable.firstOrNull { address ->
        address is Inet6Address && (address.isLinkLocalAddress || !address.isAnyLocalAddress)
    }
}
