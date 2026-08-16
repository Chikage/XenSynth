package icu.ringona.rtpmidi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.Base64

internal data class ResolvedAppleMidiService(
    val id: String,
    val name: String,
    val type: String,
    val host: InetAddress,
    val controlPort: Int,
)

/** Android DNS-SD adapter. All NsdManager calls are serialized on the main looper. */
internal class NsdDirectory(
    context: Context,
    private val requestedName: String,
    private val controlPort: Int,
    private val onResolved: (ResolvedAppleMidiService) -> Unit,
    private val onLost: (String) -> Unit,
) : AutoCloseable {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolutionQueue = ArrayDeque<NsdServiceInfo>()
    private var registeredName: String? = null
    private var registrationRequested = false
    private var registrationActive = false
    private var discoveryRequested = false
    private var discoveryActive = false
    private var resolving = false
    private var closed = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registrationRequested = false
            if (closed) {
                runCatching { nsdManager.unregisterService(this) }
                return
            }
            registeredName = serviceInfo.serviceName
            registrationActive = true
            Log.i(TAG, "Published AppleMIDI service ${serviceInfo.serviceName} on $controlPort")
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
            resolutionQueue.removeAll { queued ->
                queued.serviceName == serviceInfo.serviceName && queued.serviceType == serviceInfo.serviceType
            }
            resolutionQueue.addLast(serviceInfo)
            resolveNext()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            onLost(serviceIdentity(serviceInfo.serviceName, serviceInfo.serviceType))
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

    fun start() {
        mainHandler.post {
            if (closed) return@post
            acquireMulticastLock()
            if (!registrationActive && !registrationRequested) {
                val info = NsdServiceInfo().apply {
                    serviceName = requestedName
                    serviceType = SERVICE_TYPE
                    port = controlPort
                }
                registrationRequested = true
                runCatching {
                    nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
                }.onFailure { error ->
                    registrationRequested = false
                    Log.w(TAG, "Could not start AppleMIDI publishing", error)
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
        val service = resolutionQueue.pollFirst() ?: return
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
                        resolving = false
                        val host = serviceInfo.host
                        val port = serviceInfo.port
                        val localName = registeredName
                        if (host != null && port in 1 until 65_535 &&
                            !isThisParticipant(serviceInfo.serviceName, host, port, localName)
                        ) {
                            onResolved(
                                ResolvedAppleMidiService(
                                    id = serviceIdentity(
                                        serviceInfo.serviceName,
                                        serviceInfo.serviceType,
                                    ),
                                    name = serviceInfo.serviceName,
                                    type = serviceInfo.serviceType,
                                    host = host,
                                    controlPort = port,
                                ),
                            )
                        }
                        resolveNext()
                    }
                },
            )
        }.onFailure { error ->
            resolving = false
            Log.d(TAG, "Could not queue AppleMIDI resolution", error)
            resolveNext()
        }
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

    private fun isThisParticipant(
        name: String,
        host: InetAddress,
        port: Int,
        localName: String?,
    ): Boolean {
        if (port != controlPort) return false
        if (name == localName) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { network -> network.inetAddresses.toList() }
                .any { localAddress -> localAddress == host }
        }.getOrDefault(false)
    }

    override fun close() {
        mainHandler.post {
            if (closed) return@post
            closed = true
            resolutionQueue.clear()
            if (discoveryActive) runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            if (registrationActive) runCatching { nsdManager.unregisterService(registrationListener) }
            discoveryActive = false
            registrationActive = false
            runCatching { multicastLock?.release() }
            multicastLock = null
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
