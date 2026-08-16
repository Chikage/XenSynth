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
) : AutoCloseable {
    private data class PendingResolution(
        val serviceInfo: NsdServiceInfo,
        val identity: String,
        val generation: Long,
    )

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
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

    fun start() {
        mainHandler.post {
            if (closed) return@post
            acquireMulticastLock()
            if (!registrationActive && !registrationRequested) {
                val info = NsdServiceInfo().apply {
                    serviceName = requestedName
                    serviceType = SERVICE_TYPE
                    port = controlPort
                    AppleMidiBonjourMetadata.modelForPublishing(deviceModel)?.let { model ->
                        setAttribute(AppleMidiBonjourMetadata.MODEL_KEY, model)
                    }
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
                        resolving = false
                        val host = serviceInfo.host
                        val port = serviceInfo.port
                        val localName = registeredName
                        val resolutionIsCurrent =
                            discoveryGenerations[pending.identity] == pending.generation
                        if (resolutionIsCurrent && host != null && port in 1 until 65_535 &&
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
