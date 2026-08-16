package icu.ringona.rtpmidi

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground AppleMIDI participant for Android.
 *
 * It owns one consecutive UDP control/data pair, advertises it with DNS-SD, accepts incoming
 * invitations, initiates selected peers, and delivers decoded MIDI without ever retransmitting it.
 */
class AppleMidiManager(
    context: Context,
    private val configuration: AppleMidiConfiguration,
    private val listener: AppleMidiListener,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private val random = SecureRandom()
    private val localSsrc = randomUInt32()
    private val rtpClock = RtpMidiClock(initialTimestampTicks = randomUInt32())
    private val ioExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "AppleMidiUdp").apply { isDaemon = true }
    }
    /** Serializes control datagrams away from the Android main thread and preserves wire order. */
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppleMidiControl").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AppleMidiSession").apply { isDaemon = true }
        }
    private data class PendingOutputMessage(
        val timestampNanos: Long,
        val bytes: ByteArray,
    )

    private val pendingOutputLock = Any()
    private val pendingOutputMessages = ArrayList<PendingOutputMessage>()
    private var outputFlushFuture: ScheduledFuture<*>? = null
    private val discovered = AppleMidiServiceRegistry()
    private val sessions = LinkedHashMap<String, AppleMidiSession>()
    private val selectedPeerIds = LinkedHashSet<String>()
    private val scanWaiters = CopyOnWriteArrayList<CountDownLatch>()
    private var nextJitterWakeupNanos: Long? = null
    private var jitterWakeupGeneration = 0L
    private var jitterWakeupFuture: ScheduledFuture<*>? = null

    @Volatile
    private var running = false
    @Volatile
    private var closed = false
    private var portPair: UdpPortPair? = null
    private var transportHealthy = false
    private var directory: NsdDirectory? = null

    val controlPort: Int?
        get() = synchronized(lock) { portPair?.controlPort }

    /** False when 5004/5005 were occupied and this instance is passive-receive only. */
    val isFixedPortCapable: Boolean
        get() = synchronized(lock) { activeMidiTransportAllowedLocked() }

    fun start(): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (running) return true
            val pair = runCatching { UdpPortPair.bind(random) }
                .onFailure { error -> Log.e(TAG, "Could not bind AppleMIDI UDP ports", error) }
                .getOrNull()
                ?: return false
            if (pair.isFixedPortCapable) {
                Log.i(
                    TAG,
                    "AppleMIDI active transport bound to UDP $FIXED_CONTROL_PORT/$FIXED_DATA_PORT",
                )
            } else {
                Log.w(
                    TAG,
                    "UDP $FIXED_CONTROL_PORT/$FIXED_DATA_PORT unavailable; " +
                        "AppleMIDI is passive-receive only on " +
                        "${pair.controlPort}/${pair.dataPort}",
                )
            }
            portPair = pair
            transportHealthy = true
            running = true
            directory = NsdDirectory(
                context = applicationContext,
                requestedName = configuration.serviceName,
                controlPort = pair.controlPort,
                deviceModel = configuration.deviceModel ?: Build.MODEL,
                onResolved = ::onServiceResolved,
                onLost = ::onServiceLost,
            ).also(NsdDirectory::start)
            ioExecutor.execute { receiveLoop(pair, pair.control, dataChannel = false) }
            ioExecutor.execute { receiveLoop(pair, pair.data, dataChannel = true) }
            scheduler.scheduleWithFixedDelay(::tickSafely, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
            return true
        }
    }

    /** Returns the current cache, waiting briefly for the first DNS-SD result when it is empty. */
    @JvmOverloads
    fun scan(timeoutMillis: Long = 1_500): List<AppleMidiPeer> {
        if (!running && !start()) return emptyList()
        val initial = peers()
        if (initial.isNotEmpty() || timeoutMillis <= 0L) return initial
        val waiter = CountDownLatch(1)
        scanWaiters += waiter
        if (peers().isNotEmpty()) waiter.countDown()
        runCatching { waiter.await(timeoutMillis.coerceAtMost(5_000), TimeUnit.MILLISECONDS) }
        scanWaiters -= waiter
        return peers()
    }

    fun peers(): List<AppleMidiPeer> = synchronized(lock) {
        discovered.snapshots(connectedEndpointsLocked())
            .map(::peerSnapshot)
            .sortedBy { it.name.lowercase() }
    }

    /** Selected IDs are deduplicated peer identities returned by [scan], never IP:port strings. */
    fun setDestinationIds(ids: Collection<String>) {
        val servicesToConnect: List<AppleMidiServiceSnapshot>
        val sessionsToClose: List<AppleMidiSession>
        synchronized(lock) {
            selectedPeerIds.clear()
            selectedPeerIds += ids.filter { it.startsWith(DESTINATION_PREFIX) }
            val connectedEndpoints = connectedEndpointsLocked()
            servicesToConnect = if (activeMidiTransportAllowedLocked()) {
                selectedPeerIds.mapNotNull { discovered.snapshot(it, connectedEndpoints) }
            } else {
                emptyList()
            }
            // Destination selection controls local output; inbound sessions stay available.
            sessionsToClose = sessions.values.filter { session ->
                session.initiatedLocally &&
                    session.peerId != null && session.peerId !in selectedPeerIds
            }
        }
        sessionsToClose.forEach { closeSession(it, notifyRemote = true) }
        servicesToConnect.forEach(::ensureOutgoingSession)
        publishPeers()
    }

    /** Sends one timestamped RTP-MIDI list to every connected duplex session. */
    @JvmOverloads
    fun send(messages: List<ByteArray>, timestampNanos: Long = System.nanoTime()) {
        val safeMessages = messages
            .filter(::isSupportedChannelMessage)
            .map { it.copyOf() }
        if (safeMessages.isEmpty() ||
            !synchronized(lock) { activeMidiTransportAllowedLocked() }
        ) {
            return
        }
        synchronized(pendingOutputLock) {
            if (!running) return
            safeMessages.forEach { message ->
                pendingOutputMessages += PendingOutputMessage(timestampNanos, message)
            }
            if (outputFlushFuture == null || outputFlushFuture?.isDone == true) {
                val delay = if (pendingOutputMessages.size >= MAX_PENDING_OUTPUT_MESSAGES) {
                    0L
                } else {
                    OUTPUT_BATCH_WINDOW_NANOS
                }
                runCatching {
                    outputFlushFuture = scheduler.schedule(
                        ::flushPendingOutput,
                        delay,
                        TimeUnit.NANOSECONDS,
                    )
                }.onFailure { error ->
                    outputFlushFuture = null
                    pendingOutputMessages.clear()
                    if (running) Log.w(TAG, "Could not queue RTP-MIDI output", error)
                }
            }
        }
    }

    /** Coalesces the burst of key events arriving within one audio-sized frame. */
    private fun flushPendingOutput() {
        val messages: List<PendingOutputMessage>
        synchronized(pendingOutputLock) {
            outputFlushFuture = null
            if (pendingOutputMessages.isEmpty()) return
            messages = pendingOutputMessages.toList()
            pendingOutputMessages.clear()
        }
        sendRtpMidi(messages)
    }

    private fun onServiceResolved(service: ResolvedAppleMidiService) {
        val snapshot: AppleMidiServiceSnapshot
        val shouldConnect: Boolean
        synchronized(lock) {
            if (!running) return
            val peerId = discovered.upsert(service)
            sessions.values.filter { it.connectsTo(service) }.forEach { it.peerId = peerId }
            snapshot = discovered.snapshot(peerId, connectedEndpointsLocked()) ?: return
            shouldConnect = peerId in selectedPeerIds && activeMidiTransportAllowedLocked()
        }
        scanWaiters.forEach(CountDownLatch::countDown)
        publishPeers()
        if (shouldConnect) ensureOutgoingSession(snapshot)
    }

    private fun onServiceLost(instanceId: String) {
        val fallback: AppleMidiServiceSnapshot?
        val shouldConnect: Boolean
        synchronized(lock) {
            val peerId = discovered.peerIdForInstance(instanceId)
            discovered.remove(instanceId)
            fallback = peerId?.let { discovered.snapshot(it, connectedEndpointsLocked()) }
            shouldConnect = fallback != null && peerId in selectedPeerIds &&
                sessions.values.none {
                    it.peerId == peerId && it.state == AppleMidiSessionState.CONNECTED
                } && activeMidiTransportAllowedLocked()
        }
        publishPeers()
        if (shouldConnect) fallback?.let(::ensureOutgoingSession)
    }

    private fun ensureOutgoingSession(snapshot: AppleMidiServiceSnapshot) {
        val service = snapshot.service
        val peerId = snapshot.id
        val obsolete = synchronized(lock) {
            if (!activeMidiTransportAllowedLocked() || peerId !in selectedPeerIds) return
            sessions.values.filter {
                it.peerId == peerId && !it.connectsTo(service) &&
                    it.state != AppleMidiSessionState.CONNECTED
            }
        }
        obsolete.forEach { closeSession(it, notifyRemote = true) }
        val session = synchronized(lock) {
            if (!activeMidiTransportAllowedLocked() || peerId !in selectedPeerIds ||
                sessions.size >= configuration.maximumSessions
            ) {
                return
            }
            if (sessions.values.any {
                    it.peerId == peerId && it.state == AppleMidiSessionState.CONNECTED
                }
            ) {
                return
            }
            sessions.values.firstOrNull { it.peerId == peerId && it.connectsTo(service) }
                ?.let { return }
            AppleMidiSession(
                id = "applemidi-session:${UUID.randomUUID()}",
                peerId = peerId,
                peerName = service.name,
                advertisedAddress = service.host,
                remoteControlPort = service.controlPort,
                remoteDataPort = service.controlPort + 1,
                initiatorToken = randomUInt32(),
                initiatedLocally = true,
                localSsrc = localSsrc,
                remoteSsrc = null,
                state = AppleMidiSessionState.INVITING,
                createdAtNanos = System.nanoTime(),
                lastActivityNanos = System.nanoTime(),
                nextSequence = random.nextInt(0x1_0000),
                localClock = rtpClock,
                jitterBufferMillis = configuration.jitterBufferMillis,
            ).also { sessions[it.id] = it }
        }
        sendInvitation(session, dataChannel = session.controlAccepted)
        publishPeers()
    }

    private fun receiveLoop(
        pair: UdpPortPair,
        socket: DatagramSocket,
        dataChannel: Boolean,
    ) {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (running && !socket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (error: SocketException) {
                if (running) markTransportUnhealthy(pair, error)
                break
            } catch (error: Exception) {
                if (running) Log.w(TAG, "Could not receive AppleMIDI datagram", error)
                continue
            }
            val payload = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
            val remote = InetSocketAddress(datagram.address, datagram.port)
            runCatching {
                if (AppleMidiControlCodec.isControlPacket(payload)) {
                    AppleMidiControlCodec.decodeOrNull(payload)?.let { packet ->
                        handleControl(packet, remote, dataChannel)
                    }
                } else if (dataChannel) {
                    handleRtpMidi(payload, remote)
                }
            }.onFailure { error -> Log.d(TAG, "Ignored malformed AppleMIDI datagram", error) }
        }
    }

    private fun markTransportUnhealthy(pair: UdpPortPair, error: SocketException) {
        val outgoingSessions = synchronized(lock) {
            if (!running || portPair !== pair || !transportHealthy) return
            transportHealthy = false
            sessions.values.filter(AppleMidiSession::initiatedLocally).toList()
        }
        synchronized(pendingOutputLock) {
            outputFlushFuture?.cancel(false)
            outputFlushFuture = null
            pendingOutputMessages.clear()
        }
        Log.e(TAG, "AppleMIDI UDP transport failed; active output is disabled", error)
        outgoingSessions.forEach { closeSession(it, notifyRemote = false) }
        publishPeers()
    }

    private fun handleControl(
        packet: AppleMidiControlPacket,
        remote: InetSocketAddress,
        dataChannel: Boolean,
    ) {
        when (packet) {
            is AppleMidiControlPacket.Invitation -> handleInvitation(packet, remote, dataChannel)
            is AppleMidiControlPacket.EndSession -> {
                findSession(remote.address, packet.initiatorToken, packet.ssrc)
                    ?.let { closeSession(it, notifyRemote = false) }
            }
            is AppleMidiControlPacket.ClockSynchronization ->
                handleClockSynchronization(packet, remote)
            is AppleMidiControlPacket.ReceiverFeedback -> {
                synchronized(lock) {
                    findSessionLocked(remote.address, ssrc = packet.ssrc)?.lastActivityNanos =
                        System.nanoTime()
                }
            }
        }
    }

    private fun handleInvitation(
        packet: AppleMidiControlPacket.Invitation,
        remote: InetSocketAddress,
        dataChannel: Boolean,
    ) {
        when (packet.command) {
            AppleMidiInvitationCommand.IN -> acceptInvitation(packet, remote, dataChannel)
            AppleMidiInvitationCommand.OK -> acceptInvitationResponse(packet, remote, dataChannel)
            AppleMidiInvitationCommand.NO -> {
                findInvitationResponseSession(
                    remote = remote,
                    initiatorToken = packet.initiatorToken,
                    responseSsrc = packet.ssrc,
                    dataChannel = dataChannel,
                )
                    ?.let { failSession(it) }
            }
        }
    }

    private fun acceptInvitation(
        packet: AppleMidiControlPacket.Invitation,
        remote: InetSocketAddress,
        dataChannel: Boolean,
    ) {
        var rejected = false
        val session = synchronized(lock) {
            if (!running) return
            var match = findSessionLocked(remote.address, packet.initiatorToken, packet.ssrc)
            if (!dataChannel && match == null) {
                val simultaneous = sessions.values.firstOrNull {
                    it.initiatedLocally &&
                        it.transportAddress.sameNetworkHost(remote.address) &&
                        it.state != AppleMidiSessionState.CONNECTED
                }
                if (simultaneous != null) {
                    if (localSsrc < packet.ssrc) {
                        rejected = true
                        return@synchronized simultaneous
                    }
                    sessions.remove(simultaneous.id)
                }
                if (sessions.size >= configuration.maximumSessions) {
                    rejected = true
                    return@synchronized null
                }
                val peer = discovered.findByEndpoint(
                    host = remote.address,
                    controlPort = remote.port,
                    connectedEndpoints = connectedEndpointsLocked(),
                ) ?: run {
                    val service = ResolvedAppleMidiService(
                        id = NsdDirectory.serviceIdentity(
                            packet.name.ifBlank { remote.address.hostAddress },
                            NsdDirectory.SERVICE_TYPE,
                        ),
                        name = packet.name.ifBlank { remote.address.hostAddress },
                        type = NsdDirectory.SERVICE_TYPE,
                        host = remote.address,
                        controlPort = remote.port,
                        model = null,
                    )
                    val peerId = discovered.upsert(service)
                    discovered.snapshot(peerId, connectedEndpointsLocked())
                        ?: return@synchronized null
                }
                match = AppleMidiSession(
                    id = "applemidi-session:${UUID.randomUUID()}",
                    peerId = peer.id,
                    peerName = packet.name.ifBlank { peer.service.name },
                    advertisedAddress = remote.address,
                    remoteControlPort = remote.port,
                    remoteDataPort = remote.port + 1,
                    initiatorToken = packet.initiatorToken,
                    initiatedLocally = false,
                    localSsrc = localSsrc,
                    remoteSsrc = packet.ssrc,
                    state = AppleMidiSessionState.INVITING,
                    createdAtNanos = System.nanoTime(),
                    lastActivityNanos = System.nanoTime(),
                    nextSequence = random.nextInt(0x1_0000),
                    localClock = rtpClock,
                    jitterBufferMillis = configuration.jitterBufferMillis,
                ).also { sessions[it.id] = it }
            }
            if (dataChannel && match != null) {
                match.remoteDataPort = remote.port
                match.dataAccepted = true
                match.state = AppleMidiSessionState.SYNCHRONIZING
            } else if (!dataChannel && match != null) {
                match.remoteControlPort = remote.port
                match.controlAccepted = true
            }
            match?.lastActivityNanos = System.nanoTime()
            match
        }
        val response = AppleMidiControlPacket.Invitation(
            command = if (rejected || session == null) {
                AppleMidiInvitationCommand.NO
            } else {
                AppleMidiInvitationCommand.OK
            },
            initiatorToken = packet.initiatorToken,
            ssrc = localSsrc,
            name = configuration.serviceName,
        )
        sendControl(response, remote, dataChannel)
        if (!rejected && session != null) publishPeers()
    }

    private fun acceptInvitationResponse(
        packet: AppleMidiControlPacket.Invitation,
        remote: InetSocketAddress,
        dataChannel: Boolean,
    ) {
        val session = synchronized(lock) {
            val match = selectInvitationResponseSession(
                sessions = sessions.values,
                remote = remote,
                initiatorToken = packet.initiatorToken,
                responseSsrc = packet.ssrc,
                dataChannel = dataChannel,
            ) ?: run {
                Log.d(
                    TAG,
                    "Ignored AppleMIDI invitation response from " +
                        "${remote.address.hostAddress}:${remote.port}; " +
                        "no unambiguous token and channel-port match",
                )
                return
            }
            if (!match.transportAddress.sameNetworkHost(remote.address)) {
                Log.i(
                    TAG,
                    "AppleMIDI peer ${match.peerName} replied from " +
                        "${remote.address.hostAddress}; using the response address for transport",
                    )
                }
            match.applyInvitationResponse(
                packet = packet,
                remote = remote,
                dataChannel = dataChannel,
                nowNanos = System.nanoTime(),
            )
            match
        }
        if (dataChannel) {
            sendClockRequest(session)
        } else {
            sendInvitation(session, dataChannel = true)
        }
        publishPeers()
    }

    private fun sendInvitation(session: AppleMidiSession, dataChannel: Boolean) {
        val packet = synchronized(lock) {
            if (!activeSessionControlAllowedLocked(session)) return
            session.lastInvitationNanos = System.nanoTime()
            session.invitationAttempts++
            AppleMidiControlPacket.Invitation(
                command = AppleMidiInvitationCommand.IN,
                initiatorToken = session.initiatorToken,
                ssrc = localSsrc,
                name = configuration.serviceName,
            )
        }
        sendControl(
            packet,
            if (dataChannel) session.dataAddress else session.controlAddress,
            dataChannel,
            sendAllowedLocked = { activeSessionControlAllowedLocked(session) },
        )
    }

    private fun handleClockSynchronization(
        packet: AppleMidiControlPacket.ClockSynchronization,
        remote: InetSocketAddress,
    ) {
        val session = synchronized(lock) {
            findSessionLocked(remote.address, ssrc = packet.ssrc)?.takeIf {
                it.remoteDataPort == remote.port
            }
        } ?: return
        val nowTicks = clockTicks()
        when (packet.count) {
            0 -> {
                sendControl(
                    AppleMidiControlPacket.ClockSynchronization(
                        ssrc = localSsrc,
                        count = 1,
                        timestamp1 = packet.timestamp1,
                        timestamp2 = nowTicks,
                        timestamp3 = 0L,
                    ),
                    session.dataAddress,
                    dataChannel = true,
                    sendAllowedLocked = { sessions[session.id] === session },
                )
            }
            1 -> {
                val t1 = synchronized(lock) {
                    if (!activeSessionControlAllowedLocked(session)) return
                    session.clockRequestT1
                } ?: return
                if (packet.timestamp1 != t1) return
                synchronized(lock) {
                    if (!activeSessionControlAllowedLocked(session)) return
                    val sample = session.sessionClock.updateFromInitiatorExchange(
                        t1Local = t1,
                        t2Remote = packet.timestamp2,
                        t3Local = nowTicks,
                    )
                    session.remoteToLocalOffsetTicks = sample.remoteToLocalOffsetTicks
                    session.state = AppleMidiSessionState.CONNECTED
                    session.lastActivityNanos = System.nanoTime()
                }
                sendControl(
                    AppleMidiControlPacket.ClockSynchronization(
                        ssrc = localSsrc,
                        count = 2,
                        timestamp1 = packet.timestamp1,
                        timestamp2 = packet.timestamp2,
                        timestamp3 = nowTicks,
                    ),
                    session.dataAddress,
                    dataChannel = true,
                    sendAllowedLocked = { activeSessionControlAllowedLocked(session) },
                )
                publishPeers()
            }
            2 -> {
                synchronized(lock) {
                    if (sessions[session.id] !== session) return
                    val sample = session.sessionClock.updateFromResponderExchange(
                        t1Remote = packet.timestamp1,
                        t2Local = packet.timestamp2,
                        t3Remote = packet.timestamp3,
                    )
                    session.remoteToLocalOffsetTicks = sample.remoteToLocalOffsetTicks
                    session.state = AppleMidiSessionState.CONNECTED
                    session.lastActivityNanos = System.nanoTime()
                }
                publishPeers()
            }
        }
    }

    private fun sendClockRequest(session: AppleMidiSession) {
        val now = clockTicks()
        val packet = synchronized(lock) {
            if (!activeSessionControlAllowedLocked(session)) return
            session.clockRequestT1 = now
            session.lastClockSyncNanos = System.nanoTime()
            AppleMidiControlPacket.ClockSynchronization(
                ssrc = localSsrc,
                count = 0,
                timestamp1 = now,
                timestamp2 = 0L,
                timestamp3 = 0L,
            )
        }
        sendControl(
            packet,
            session.dataAddress,
            dataChannel = true,
            sendAllowedLocked = { activeSessionControlAllowedLocked(session) },
        )
    }

    private fun tickSafely() {
        if (!running) return
        runCatching { tick() }.onFailure { error -> Log.w(TAG, "AppleMIDI session tick failed", error) }
    }

    private fun tick() {
        val now = System.nanoTime()
        val inviteAgain = mutableListOf<AppleMidiSession>()
        val synchronize = mutableListOf<AppleMidiSession>()
        val expired = mutableListOf<AppleMidiSession>()
        synchronized(lock) {
            sessions.values.forEach { session ->
                if (session.initiatedLocally &&
                    session.state == AppleMidiSessionState.INVITING &&
                    now - session.lastInvitationNanos >= INVITATION_RETRY_NANOS
                ) {
                    if (now - session.createdAtNanos >=
                        configuration.invitationTimeoutMillis * NANOS_PER_MILLI
                    ) {
                        expired += session
                    } else {
                        inviteAgain += session
                    }
                }
                if (session.initiatedLocally &&
                    session.dataAccepted &&
                    now - session.lastClockSyncNanos >=
                    configuration.clockSyncIntervalMillis * NANOS_PER_MILLI
                ) {
                    synchronize += session
                }
                if (session.state == AppleMidiSessionState.CONNECTED &&
                    now - session.lastActivityNanos >= SESSION_TIMEOUT_NANOS
                ) {
                    expired += session
                }
                if (session.state != AppleMidiSessionState.CONNECTED &&
                    now - session.createdAtNanos >=
                    configuration.invitationTimeoutMillis * 2L * NANOS_PER_MILLI
                ) {
                    expired += session
                }
            }
        }
        inviteAgain.forEach { sendInvitation(it, dataChannel = it.controlAccepted) }
        synchronize.forEach(::sendClockRequest)
        expired.distinctBy { it.id }.forEach(::failSession)
        drainJitterBuffers(now)
    }

    private fun sendControl(
        packet: AppleMidiControlPacket,
        destination: InetSocketAddress,
        dataChannel: Boolean,
        sendAllowedLocked: (() -> Boolean)? = null,
    ) {
        val bytes = AppleMidiControlCodec.encode(packet)
        runCatching {
            controlExecutor.execute {
                val pair = synchronized(lock) {
                    if (sendAllowedLocked?.invoke() == false) return@execute
                    portPair
                } ?: return@execute
                val socket = if (dataChannel) pair.data else pair.control
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, destination)) }
                    .onFailure { error ->
                        if (error is SocketException) {
                            markTransportUnhealthy(pair, error)
                        } else if (running) {
                            Log.d(TAG, "Could not send AppleMIDI control", error)
                        }
                    }
            }
        }.onFailure { error ->
            if (running) Log.d(TAG, "Could not queue AppleMIDI control", error)
        }
    }

    private fun failSession(session: AppleMidiSession) {
        synchronized(lock) { session.state = AppleMidiSessionState.FAILED }
        closeSession(session, notifyRemote = false, preserveFailedPeerState = true)
    }

    private fun closeSession(
        session: AppleMidiSession,
        notifyRemote: Boolean,
        preserveFailedPeerState: Boolean = false,
    ) {
        val removed = synchronized(lock) { sessions.remove(session.id) != null }
        if (!removed) return
        if (notifyRemote) {
            val by = AppleMidiControlPacket.EndSession(session.initiatorToken, localSsrc)
            sendControl(by, session.controlAddress, dataChannel = false)
            sendControl(by, session.dataAddress, dataChannel = true)
        }
        releaseSessionNotes(session)
        listener.onSessionClosed(session.id)
        if (!preserveFailedPeerState) publishPeers()
    }

    private fun releaseSessionNotes(session: AppleMidiSession) {
        val now = System.nanoTime()
        session.activeNotes.forEachIndexed { channel, notes ->
            notes.forEachIndexed { note, active ->
                if (active) {
                    listener.onMidiEvent(
                        AppleMidiEvent(
                            bytes = byteArrayOf((0x80 or channel).toByte(), note.toByte(), 0),
                            targetTimeNanos = now,
                            sessionId = session.id,
                        ),
                    )
                }
            }
            if (session.sustainDown[channel]) {
                listener.onMidiEvent(
                    AppleMidiEvent(
                        bytes = byteArrayOf((0xB0 or channel).toByte(), 64, 0),
                        targetTimeNanos = now,
                        sessionId = session.id,
                    ),
                )
            }
        }
    }

    private fun findSession(address: InetAddress, token: Long?, ssrc: Long?): AppleMidiSession? =
        synchronized(lock) { findSessionLocked(address, token, ssrc) }

    private fun findInvitationResponseSession(
        remote: InetSocketAddress,
        initiatorToken: Long,
        responseSsrc: Long,
        dataChannel: Boolean,
    ): AppleMidiSession? = synchronized(lock) {
        selectInvitationResponseSession(
            sessions = sessions.values,
            remote = remote,
            initiatorToken = initiatorToken,
            responseSsrc = responseSsrc,
            dataChannel = dataChannel,
        )
    }

    private fun findSessionLocked(
        address: InetAddress,
        token: Long? = null,
        ssrc: Long? = null,
    ): AppleMidiSession? = sessions.values.firstOrNull { session ->
        session.transportAddress.sameNetworkHost(address) &&
            (token == null || session.initiatorToken == token) &&
            (ssrc == null || session.remoteSsrc == ssrc)
    }

    private fun connectedEndpointsLocked(): Set<AppleMidiServiceEndpoint> = sessions.values
        .filter { it.state == AppleMidiSessionState.CONNECTED }
        .mapTo(LinkedHashSet()) {
            AppleMidiServiceEndpoint(
                it.advertisedAddress.hostAddress.orEmpty(),
                it.advertisedControlPort,
            )
        }

    private fun activeMidiTransportAllowedLocked(): Boolean =
        activeMidiTransportAllowed(running, portPair, transportHealthy)

    private fun activeSessionControlAllowedLocked(session: AppleMidiSession): Boolean =
        activeMidiTransportAllowedLocked() &&
            sessions[session.id] === session &&
            session.initiatedLocally &&
            session.peerId?.let(selectedPeerIds::contains) == true

    private fun midiOutputAllowedLocked(session: AppleMidiSession): Boolean =
        activeMidiTransportAllowedLocked() &&
            sessions[session.id] === session &&
            session.state == AppleMidiSessionState.CONNECTED

    private fun peerSnapshot(snapshot: AppleMidiServiceSnapshot): AppleMidiPeer {
        val service = snapshot.service
        val state = sessions.values
            .filter { it.peerId == snapshot.id || it.connectsTo(service) }
            .maxByOrNull { it.state.priority }
            ?.state
            ?: AppleMidiSessionState.DISCOVERED
        return AppleMidiPeer(
            id = snapshot.id,
            name = service.name,
            hostAddress = service.host.hostAddress.orEmpty(),
            controlPort = service.controlPort,
            state = state,
            model = service.model?.takeIf(String::isNotBlank) ?: service.name,
        )
    }

    private fun publishPeers() {
        val snapshot = peers()
        listener.onPeersChanged(snapshot)
    }

    override fun close() {
        val active: List<AppleMidiSession>
        val pair: UdpPortPair?
        synchronized(lock) {
            if (closed) return
            closed = true
            active = sessions.values.toList()
            pair = portPair
            running = false
            transportHealthy = false
            // Invalidate any already queued wakeup. The scheduler is shut down below.
            jitterWakeupFuture?.cancel(false)
            jitterWakeupFuture = null
            nextJitterWakeupNanos = null
            jitterWakeupGeneration += 1
        }
        synchronized(pendingOutputLock) {
            outputFlushFuture?.cancel(false)
            outputFlushFuture = null
            pendingOutputMessages.clear()
        }
        active.forEach { closeSession(it, notifyRemote = true) }
        controlExecutor.shutdown()
        if (Thread.currentThread().name != "AppleMidiControl") {
            runCatching {
                if (!controlExecutor.awaitTermination(CONTROL_FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    controlExecutor.shutdownNow()
                }
            }
        }
        synchronized(lock) {
            directory?.close()
            directory = null
            portPair = null
            discovered.clear()
            selectedPeerIds.clear()
            nextJitterWakeupNanos = null
            jitterWakeupGeneration += 1
        }
        pair?.close()
        ioExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        scheduler.shutdownNow()
        scanWaiters.forEach(CountDownLatch::countDown)
        scanWaiters.clear()
    }

    private fun sendRtpMidi(messages: List<PendingOutputMessage>) {
        val targets = synchronized(lock) {
            if (!activeMidiTransportAllowedLocked()) return
            sessions.values
                .filter { it.state == AppleMidiSessionState.CONNECTED }
                .groupBy { it.peerId ?: it.id }
                .values
                .map { peerSessions -> peerSessions.maxBy(AppleMidiSession::lastActivityNanos) }
        }
        val batches = buildList {
            var batch = mutableListOf<PendingOutputMessage>()
            var estimatedBytes = 0
            var batchTimestampNanos: Long? = null
            messages.sortedBy { it.timestampNanos }.forEach { message ->
                // Keep the coalescing window bounded. The delta timestamps still preserve the
                // ordering of Note On/Off pairs inside that window.
                val messageBytes = message.bytes.size + if (batch.isEmpty()) 0 else 5
                val exceedsTimeWindow = batchTimestampNanos?.let {
                    message.timestampNanos - it > OUTPUT_BATCH_WINDOW_NANOS
                } == true
                if (batch.isNotEmpty() &&
                    (estimatedBytes + messageBytes > MAX_COMMAND_BYTES || exceedsTimeWindow)
                ) {
                    add(batch)
                    batch = mutableListOf()
                    estimatedBytes = 0
                    batchTimestampNanos = null
                }
                batch += message
                batchTimestampNanos = batchTimestampNanos ?: message.timestampNanos
                estimatedBytes += messageBytes
            }
            if (batch.isNotEmpty()) add(batch)
        }
        targets.forEach { session ->
            batches.forEach { batch -> sendRtpMidiToSession(session, batch) }
        }
    }

    private fun sendRtpMidiToSession(
        session: AppleMidiSession,
        messages: List<PendingOutputMessage>,
    ) {
        if (!synchronized(lock) { midiOutputAllowedLocked(session) }) return
        val packet = buildRtpPacket(session, messages)
        val bytes = RtpMidiCodec.encode(packet)
        val pair = synchronized(lock) {
            if (!midiOutputAllowedLocked(session)) return
            portPair
        } ?: return
        runCatching {
            pair.data.send(DatagramPacket(bytes, bytes.size, session.dataAddress))
        }.onFailure { error ->
            if (error is SocketException) {
                markTransportUnhealthy(pair, error)
            } else if (running) {
                Log.d(TAG, "Could not send RTP-MIDI", error)
            }
        }
    }

    private fun buildRtpPacket(
        session: AppleMidiSession,
        messages: List<PendingOutputMessage>,
    ): RtpMidiPacket {
        val sequence = synchronized(lock) {
            session.nextSequence.also { session.nextSequence = (it + 1) and 0xFFFF }
        }
        val firstTimestampNanos = messages.first().timestampNanos
        var previousTimestampNanos = firstTimestampNanos
        return RtpMidiPacket(
            sequenceNumber = sequence,
            timestamp = rtpClock.timestampTicksForNanos(firstTimestampNanos) and UINT32_MASK,
            ssrc = localSsrc,
            commands = messages.map { pending ->
                val deltaTimeTicks = RtpMidiClock.nanosToTicks(
                    (pending.timestampNanos - previousTimestampNanos).coerceAtLeast(0L),
                ).coerceAtMost(RtpMidiCodec.MAX_DELTA_TIME_TICKS.toLong()).toInt()
                previousTimestampNanos = pending.timestampNanos
                TimedMidiMessage(
                    deltaTimeTicks = deltaTimeTicks,
                    message = MidiChannelMessage.fromBytes(pending.bytes),
                )
            },
            marker = true,
        )
    }

    private fun handleRtpMidi(payload: ByteArray, remote: InetSocketAddress) {
        val session = synchronized(lock) {
            sessions.values.firstOrNull {
                it.transportAddress.sameNetworkHost(remote.address) &&
                    it.remoteDataPort == remote.port
            }
        } ?: return
        val packet = RtpMidiCodec.decode(
            bytes = payload,
            initialRunningStatus = session.incomingRunningStatus,
        )
        val arrival = System.nanoTime()
        val accepted = synchronized(lock) {
            session.takeIf { current ->
                sessions[current.id] === current && current.remoteSsrc == packet.ssrc
            }?.let { current ->
                val observation = current.sequenceTracker.observe(packet.sequenceNumber)
                if (observation.disposition == RtpSequenceDisposition.DUPLICATE) {
                    null
                } else {
                    if (observation.disposition == RtpSequenceDisposition.GAP) {
                        current.enqueueRecovery(
                            targetTimeNanos = arrival,
                            messages = current.resetActiveStateForRecovery(),
                        )
                    }
                    current
                }
            }
        } ?: return
        synchronized(lock) {
            accepted.lastActivityNanos = arrival
            accepted.incomingRunningStatus = packet.commands.lastOrNull()?.message?.status
                ?: accepted.incomingRunningStatus
        }
        packet.commands.zip(packet.commandTimestamps()).forEach { (command, commandTimestamp) ->
            val message = command.message.toByteArray()
            offerJittered(accepted, commandTimestamp, arrival, message)
        }
        scheduleJitterWakeup()
        sendControl(
            AppleMidiControlPacket.ReceiverFeedback(localSsrc, packet.sequenceNumber),
            accepted.controlAddress,
            dataChannel = false,
        )
    }

    private fun offerJittered(
        session: AppleMidiSession,
        remoteTimestamp: Long,
        arrivalNanos: Long,
        message: ByteArray,
    ) {
        session.offerMidi(
            remoteTimestamp = remoteTimestamp and UINT32_MASK,
            arrivalNanos = arrivalNanos,
            message = message,
        )
    }

    private fun drainJitterBuffers(nowNanos: Long) {
        val ready = synchronized(lock) {
            sessions.values.flatMap { session -> session.drainMidi(nowNanos) }
        }
        ready.sortedBy { it.targetTimeNanos }.forEach { event ->
            synchronized(lock) { sessions[event.sessionId]?.observeMidi(event.bytes) }
            listener.onMidiEvent(event)
        }
        scheduleJitterWakeup()
    }

    /**
     * Coalesces all queued MIDI events behind one earliest-deadline wakeup. A dense chord can
     * otherwise create hundreds of ScheduledExecutor tasks and add avoidable scheduler/GC jitter.
     */
    private fun scheduleJitterWakeup() {
        var schedulingError: Throwable? = null
        synchronized(lock) {
            if (!running) return
            val earliest = sessions.values
                .mapNotNull(AppleMidiSession::nextMidiTargetTimeNanos)
                .minOrNull()
            if (earliest == null) {
                jitterWakeupFuture?.cancel(false)
                jitterWakeupFuture = null
                nextJitterWakeupNanos = null
                return
            }

            val existing = nextJitterWakeupNanos
            val existingFuture = jitterWakeupFuture
            if (existing != null && existing <= earliest &&
                existingFuture != null && !existingFuture.isDone
            ) {
                return
            }

            existingFuture?.cancel(false)
            nextJitterWakeupNanos = earliest
            val generation = ++jitterWakeupGeneration
            val delay = (earliest - System.nanoTime()).coerceAtLeast(0L)
            try {
                // Keep scheduling and publishing the future under lock. With a zero delay the
                // scheduler may run the callback before schedule() returns; holding the lock
                // makes that callback observe the fully initialized future state.
                jitterWakeupFuture = scheduler.schedule(
                    { onJitterWakeup(generation) },
                    delay,
                    TimeUnit.NANOSECONDS,
                )
            } catch (error: Throwable) {
                jitterWakeupFuture = null
                nextJitterWakeupNanos = null
                schedulingError = error
            }
        }
        schedulingError?.let { error ->
            if (running) Log.d(TAG, "Could not schedule RTP-MIDI playout", error)
        }
    }

    private fun onJitterWakeup(generation: Long) {
        val accepted = synchronized(lock) {
            if (!running || generation != jitterWakeupGeneration) {
                false
            } else {
                jitterWakeupFuture = null
                nextJitterWakeupNanos = null
                true
            }
        }
        if (accepted) drainJitterBuffers(System.nanoTime())
    }

    private fun randomUInt32(): Long = random.nextInt().toLong() and UINT32_MASK

    private fun clockTicks(): Long = rtpClock.nowTicks()

    private val AppleMidiSessionState.priority: Int
        get() = when (this) {
            AppleMidiSessionState.DISCOVERED -> 0
            AppleMidiSessionState.FAILED -> 1
            AppleMidiSessionState.INVITING -> 2
            AppleMidiSessionState.SYNCHRONIZING -> 3
            AppleMidiSessionState.CONNECTED -> 4
        }

    companion object {
        const val DESTINATION_PREFIX = "applemidi:"
        const val FIXED_CONTROL_PORT = UdpPortPair.FIXED_CONTROL_PORT
        const val FIXED_DATA_PORT = UdpPortPair.FIXED_DATA_PORT
        private const val TAG = "AppleMidiManager"
        private const val MAX_DATAGRAM_BYTES = 1_500
        private const val MAX_COMMAND_BYTES = 1_000
        private const val MAX_PENDING_OUTPUT_MESSAGES = 256
        private const val OUTPUT_BATCH_WINDOW_NANOS = 1_000_000L
        private const val TICK_MILLIS = 100L
        private const val CONTROL_FLUSH_TIMEOUT_MILLIS = 250L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val INVITATION_RETRY_NANOS = 1_000_000_000L
        private const val SESSION_TIMEOUT_NANOS = 35_000_000_000L
        private const val UINT32_MASK = 0xFFFF_FFFFL

        private fun isSupportedChannelMessage(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val status = bytes[0].toInt() and 0xFF
            if (status !in 0x80..0xEF) return false
            val expected = if ((status and 0xF0) == 0xC0 || (status and 0xF0) == 0xD0) 2 else 3
            return bytes.size == expected && bytes.drop(1).all { (it.toInt() and 0x80) == 0 }
        }
    }
}

internal fun activeMidiTransportAllowed(
    running: Boolean,
    portPair: UdpPortPair?,
    transportHealthy: Boolean,
): Boolean = running && transportHealthy && portPair?.isFixedPortCapable == true

internal fun selectInvitationResponseSession(
    sessions: Collection<AppleMidiSession>,
    remote: InetSocketAddress,
    initiatorToken: Long,
    responseSsrc: Long,
    dataChannel: Boolean,
): AppleMidiSession? {
    val candidates = sessions.filter { session ->
        session.matchesInvitationResponse(
            remote = remote,
            token = initiatorToken,
            responseSsrc = responseSsrc,
            dataChannel = dataChannel,
        )
    }
    val sameHostCandidates = candidates.filter { session ->
        session.transportAddress.sameNetworkHost(remote.address)
    }
    return when {
        sameHostCandidates.size == 1 -> sameHostCandidates.single()
        sameHostCandidates.isNotEmpty() -> null
        else -> candidates.singleOrNull()
    }
}
