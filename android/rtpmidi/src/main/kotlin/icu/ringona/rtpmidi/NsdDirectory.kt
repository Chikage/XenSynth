package icu.ringona.rtpmidi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executors

internal data class ResolvedAppleMidiService(
    val id: String,
    val name: String,
    val type: String,
    val host: InetAddress,
    val controlPort: Int,
    val model: String?,
)

/** Android DNS-SD adapter. All NsdManager calls are serialized on the main looper. */
internal class NsdDirectory(
    context: Context,
    private val requestedName: String,
    private val controlPort: Int,
    private val deviceModel: String?,
    private val onResolved: (ResolvedAppleMidiService) -> Unit,
    private val onLost: (String) -> Unit,
    private val addressPolicy: AppleMidiAddressPolicy = AppleMidiAddressPolicy.IPV4_ONLY,
) : AutoCloseable {
    private data class PendingResolution(
        val serviceInfo: NsdServiceInfo,
        val identity: String,
        val generation: Long,
    )

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    /** DNS hostname expansion can block on pre-34 NSD, so keep it off the main looper. */
    private val addressResolver = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppleMidiAddressResolver").apply { isDaemon = true }
    }
    private val resolutionQueue = ArrayDeque<PendingResolution>()
    private val discoveryGenerations = HashMap<String, Long>()
    private var nextDiscoveryGeneration = 0L
    private var registeredName: String? = null
    private var registrationRequested = false
    private var registrationActive = false
    private var discoveryRequested = false
    private var discoveryActive = false
    private var resolving = false
    private var closed = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        @Suppress("DEPRECATION")
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registrationRequested = false
            if (closed) {
                runCatching { nsdManager.unregisterService(this) }
                return
            }
            registeredName = serviceInfo.serviceName
            registrationActive = true
            Log.i(
                TAG,
                "Published AppleMIDI service ${serviceInfo.serviceName} on $controlPort " +
                    "(${serviceInfo.host?.hostAddress ?: "unspecified"})",
            )
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            registrationRequested = false
            registrationActive = false
            Log.w(TAG, "Could not publish AppleMIDI service: NSD error $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            registrationRequested = false
            registrationActive = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Could not unpublish AppleMIDI service: NSD error $errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discoveryRequested = false
            if (closed) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
                return
            }
            discoveryActive = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.isAppleMidiServiceType()) return
            val identity = serviceIdentity(serviceInfo.serviceName, serviceInfo.serviceType)
            val generation = ++nextDiscoveryGeneration
            discoveryGenerations[identity] = generation
            resolutionQueue.removeAll { it.identity == identity }
            resolutionQueue.addLast(PendingResolution(serviceInfo, identity, generation))
            resolveNext()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val identity = serviceIdentity(serviceInfo.serviceName, serviceInfo.serviceType)
            discoveryGenerations.remove(identity)
            resolutionQueue.removeAll { it.identity == identity }
            onLost(identity)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discoveryRequested = false
            discoveryActive = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryRequested = false
            discoveryActive = false
            Log.w(TAG, "Could not browse AppleMIDI services: NSD error $errorCode")
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryActive = false
            Log.w(TAG, "Could not stop AppleMIDI browsing: NSD error $errorCode")
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        mainHandler.post {
            if (closed) return@post
            acquireMulticastLock()
            if (!registrationActive && !registrationRequested) {
                val registrationHost = registrationHostAddress()
                if (addressPolicy == AppleMidiAddressPolicy.IPV4_ONLY && registrationHost == null) {
                    Log.w(TAG, "Could not publish AppleMIDI service: no private IPv4 LAN address")
                } else {
                    val info = NsdServiceInfo().apply {
                        serviceName = requestedName
                        serviceType = SERVICE_TYPE
                        port = controlPort
                        // Pin the service target to the selected IPv4 address. The device hostname
                        // may still have an AAAA record; discovery applies the same IPv4 filter.
                        registrationHost?.let(::setHost)
                        AppleMidiBonjourMetadata.modelForPublishing(deviceModel)?.let { model ->
                            setAttribute(AppleMidiBonjourMetadata.MODEL_KEY, model)
                        }
                    }
                    registrationRequested = true
                    runCatching {
                        nsdManager.registerService(
                            info,
                            NsdManager.PROTOCOL_DNS_SD,
                            registrationListener,
                        )
                    }.onFailure { error ->
                        registrationRequested = false
                        Log.w(TAG, "Could not start AppleMIDI publishing", error)
                    }
                }
            }
            if (!discoveryActive && !discoveryRequested) {
                discoveryRequested = true
                runCatching {
                    nsdManager.discoverServices(
                        SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener,
                    )
                }.onFailure { error ->
                    discoveryRequested = false
                    Log.w(TAG, "Could not start AppleMIDI browsing", error)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (closed || resolving) return
        val pending = resolutionQueue.pollFirst() ?: return
        val service = pending.serviceInfo
        resolving = true
        runCatching {
            nsdManager.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        Log.d(TAG, "Could not resolve ${serviceInfo.serviceName}: NSD error $errorCode")
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val port = serviceInfo.port
                        val localName = registeredName
                        resolveAddressesOffMainThread(serviceInfo) { addresses ->
                            val host = selectAppleMidiAddress(addresses, addressPolicy)
                            val resolutionIsCurrent =
                                discoveryGenerations[pending.identity] == pending.generation
                            if (!closed && resolutionIsCurrent && host != null &&
                                port in 1 until 65_535 &&
                                !isThisParticipant(serviceInfo.serviceName, host, port, localName)
                            ) {
                                onResolved(
                                    ResolvedAppleMidiService(
                                        id = pending.identity,
                                        name = serviceInfo.serviceName,
                                        type = serviceInfo.serviceType,
                                        host = host,
                                        controlPort = port,
                                        model = runCatching {
                                            AppleMidiBonjourMetadata.parseModel(serviceInfo.attributes)
                                        }.getOrNull(),
                                    ),
                                )
                            } else if (!closed && resolutionIsCurrent && host == null) {
                                Log.d(
                                    TAG,
                                    "Ignoring ${serviceInfo.serviceName}: no private IPv4 address",
                                )
                            }
                            resolving = false
                            resolveNext()
                        }
                    }
                },
            )
        }.onFailure { error ->
            resolving = false
            Log.d(TAG, "Could not queue AppleMIDI resolution", error)
            resolveNext()
        }
    }

    /**
     * API 34 exposes every A/AAAA record through [NsdServiceInfo.getHostAddresses]. Older Android
     * releases expose only the first record, so expand its hostname on a worker and let the
     * selector choose the private IPv4 LAN endpoint. The callback always returns on the NSD
     * looper, preserving the existing serialization contract.
     */
    @Suppress("NewApi", "DEPRECATION")
    private fun resolveAddressesOffMainThread(
        serviceInfo: NsdServiceInfo,
        callback: (List<InetAddress>) -> Unit,
    ) {
        val advertised = if (Build.VERSION.SDK_INT >= 34) {
            serviceInfo.hostAddresses
        } else {
            listOfNotNull(serviceInfo.host)
        }
        val hostname = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostname else null
        runCatching {
            addressResolver.execute {
                val expanded = LinkedHashSet<InetAddress>()
                advertised.forEach { address ->
                    expanded += address
                    expandHostname(address.hostName).forEach(expanded::add)
                }
                hostname?.takeIf(String::isNotBlank)?.let { name ->
                    expandHostname(name).forEach(expanded::add)
                    if (!name.endsWith(".local", ignoreCase = true)) {
                        expandHostname("$name.local").forEach(expanded::add)
                    }
                }
                mainHandler.post { callback(expanded.toList()) }
            }
        }.onFailure { error ->
            Log.d(TAG, "Could not queue AppleMIDI hostname resolution", error)
            mainHandler.post { callback(advertised) }
        }
    }

    private fun expandHostname(hostname: String): List<InetAddress> {
        if (hostname.isBlank() || hostname.endsWith(".")) return emptyList()
        return runCatching { InetAddress.getAllByName(hostname).toList() }
            .onFailure { error -> Log.d(TAG, "Could not resolve AppleMIDI host $hostname", error) }
            .getOrDefault(emptyList())
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            wifiManager?.createMulticastLock("AppleMidiNsd")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { error -> Log.d(TAG, "Could not acquire multicast lock", error) }
            .getOrNull()
    }

    /** Select the Wi-Fi/LAN IPv4 address used in the Bonjour host record. */
    @Suppress("DEPRECATION")
    private fun registrationHostAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { network -> network.isUp && !network.isLoopback }
            .sortedWith(compareBy<NetworkInterface> { interfacePriority(it.name) }.thenBy { it.name })
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address ->
                !address.isLoopbackAddress &&
                    !address.isAnyLocalAddress &&
                    !address.isMulticastAddress &&
                    (address.isSiteLocalAddress || address.isLinkLocalAddress)
            }
    }.getOrNull()

    private fun interfacePriority(name: String): Int = when {
        name.startsWith("wlan", ignoreCase = true) ||
            name.startsWith("wifi", ignoreCase = true) ||
            name.startsWith("eth", ignoreCase = true) -> 0
        name.startsWith("p2p", ignoreCase = true) -> 1
        else -> 2
    }

    private fun isThisParticipant(
        name: String,
        host: InetAddress,
        port: Int,
        localName: String?,
    ): Boolean {
        val logicalNameMatches = localName?.let {
            AppleMidiServiceRegistry.logicalName(name) ==
                AppleMidiServiceRegistry.logicalName(it)
        } == true
        if (port != controlPort && !logicalNameMatches) return false
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { network -> network.inetAddresses.toList() }
                .any { localAddress -> localAddress.sameNetworkHost(host) }
        }.getOrDefault(false)
    }

    override fun close() {
        mainHandler.post {
            if (closed) return@post
            closed = true
            resolutionQueue.clear()
            discoveryGenerations.clear()
            if (discoveryActive) runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            if (registrationActive) runCatching { nsdManager.unregisterService(registrationListener) }
            discoveryActive = false
            registrationActive = false
            runCatching { multicastLock?.release() }
            multicastLock = null
            addressResolver.shutdownNow()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_apple-midi._udp."
        private const val TAG = "AppleMidiNsd"

        fun serviceIdentity(name: String, type: String): String {
            val canonical = "$name\u0000${type.trimEnd('.').lowercase()}"
            val encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(canonical.toByteArray(Charsets.UTF_8))
            return "applemidi:$encoded"
        }

        private fun String.isAppleMidiServiceType(): Boolean =
            trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)
    }
}
