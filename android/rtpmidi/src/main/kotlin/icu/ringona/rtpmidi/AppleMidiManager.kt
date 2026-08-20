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
    /** Keeps potentially blocking data-socket writes off the timing scheduler while preserving RTP order. */
    private val rtpSendExecutor = BoundedSerialExecutor(
        capacity = RTP_SEND_QUEUE_CAPACITY,
        threadName = "AppleMidiData",
        criticalOverloadFallback = ::sendOverloadPanicNow,
    )
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AppleMidiSession").apply { isDaemon = true }
        }
    private val eventDeliveryLookaheadNanos = appleMidiDeliveryLookaheadNanos(
        configuration,
        listener,
    )
    private val pendingOutputLock = Any()
    private val pendingOutput = MidiOutputAccumulator()
    private var pendingOutputAuthorizationEpoch: Long? = null
    private var outputFlushFuture: ScheduledFuture<*>? = null
    private var outputFlushDeadlineNanos: Long? = null
    private val discovered = AppleMidiServiceRegistry()
    private val sessions = LinkedHashMap<String, AppleMidiSession>()
    private val queuedJournalHeartbeats = LinkedHashSet<String>()
    /** Output half of the app's single per-peer LINK selection. */
    private val selectedPeerIds = LinkedHashSet<String>()
    /** Input half of the app's single per-peer LINK selection. */
    private val selectedInputPeerIds = LinkedHashSet<String>()
    private var inputSelectionConfigured = false
    private val scanWaiters = CopyOnWriteArrayList<CountDownLatch>()
    private var nextJitterWakeupNanos: Long? = null
    private var jitterWakeupGeneration = 0L
    private var jitterWakeupFuture: ScheduledFuture<*>? = null
    private var gapWakeupGeneration = 0L
    private var gapWakeupFuture: ScheduledFuture<*>? = null
    private var nextGapWakeupNanos: Long? = null
    /** Bounds retry traffic when a selected CoreMIDI peer replaces its session. */
    private var lastOutgoingSessionReconciliationNanos: Long? = null
    /** Invalidates outbound work captured before a LINK or session transition. */
    private var outputAuthorizationEpoch = 0L
    private var outputDiagnosticCount = 0
    private var inputDiagnosticCount = 0

    @Volatile
    private var running = false
    @Volatile
    private var closed = false
    @Volatile
    private var discoveryEnabled = true
    private var portPair: UdpPortPair? = null
    private var transportHealthy = false
    private var directory: NsdDirectory? = null

    val controlPort: Int?
        get() = synchronized(lock) { portPair?.controlPort }

    /** Whether the active transport is using the preferred 5004/5005 pair. */
    val isFixedPortCapable: Boolean
        get() = synchronized(lock) {
            running && transportHealthy && portPair?.isFixedPortCapable == true
        }

    fun sessionStatistics(): List<AppleMidiSessionStatistics> = synchronized(lock) {
        sessions.values.map(AppleMidiSession::statistics)
    }

    val transportStatistics: AppleMidiTransportStatistics
        get() {
            val send = rtpSendExecutor.statistics
            val output = synchronized(pendingOutputLock) { pendingOutput.statistics }
            return AppleMidiTransportStatistics(
                queuedSendTasks = send.queuedTasks,
                peakQueuedSendTasks = send.peakQueuedTasks,
                droppedSendTasks = send.droppedTasks,
                overloadPanicFallbacks = send.overloadFallbacks,
                pendingMidiMessages = output.queuedMessages,
                coalescedMidiMessages = output.coalescedMessages,
                evictedContinuousMidiMessages = output.evictedContinuousMessages,
                droppedContinuousMidiMessages = output.droppedContinuousMessages,
                evictedNonCriticalMidiMessages = output.evictedNonCriticalMessages,
                droppedNonCriticalMidiMessages = output.droppedNonCriticalMessages,
                accumulatorPanicFallbacks = output.panicCount,
            )
        }

    fun start(): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (running) return true
            val pair = runCatching {
                UdpPortPair.bind(ipv4Only = configuration.ipv4Only)
            }
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
                        "AppleMIDI active transport moved to UDP " +
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
                addressPolicy = if (configuration.ipv4Only) {
                    AppleMidiAddressPolicy.IPV4_ONLY
                } else {
                    AppleMidiAddressPolicy.IPV4_PREFERRED
                },
                onResolved = ::onServiceResolved,
                onLost = ::onServiceLost,
            ).also { it.start(discover = discoveryEnabled) }
            ioExecutor.execute { receiveLoop(pair, pair.control, dataChannel = false) }
            ioExecutor.execute { receiveLoop(pair, pair.data, dataChannel = true) }
            scheduler.scheduleWithFixedDelay(::tickSafely, 0, TICK_MILLIS, TimeUnit.MILLISECONDS)
            return true
        }
    }

    /**
     * Returns a snapshot after giving DNS-SD a complete discovery window.
     *
     * Do not return early just because the cache already contains another peer. A browser can
     * report services in separate multicast batches, so an early return made a second device
     * appear to be missing whenever the first device had already been discovered.
     */
    @JvmOverloads
    fun scan(timeoutMillis: Long = 2_500): List<AppleMidiPeer> {
        if (!discoveryEnabled) return emptyList()
        if (!running && !start()) return emptyList()
        if (!discoveryEnabled) return emptyList()
        if (timeoutMillis <= 0L) return peers()
        val waiter = CountDownLatch(1)
        synchronized(lock) {
            if (!discoveryEnabled) return emptyList()
            scanWaiters += waiter
        }
        try {
            // The latch is released when discovery is disabled or the manager closes. Otherwise
            // wait for the full window so peers announced after an already cached peer are seen.
            runCatching {
                waiter.await(timeoutMillis.coerceAtMost(5_000), TimeUnit.MILLISECONDS)
            }
        } finally {
            scanWaiters -= waiter
        }
        return peers()
    }

    fun peers(): List<AppleMidiPeer> = synchronized(lock) {
        if (!discoveryEnabled) {
            emptyList()
        } else {
            discovered.snapshots(connectedEndpointsLocked())
                .map(::peerSnapshot)
                .sortedBy { it.name.lowercase() }
        }
    }

    /**
     * Controls the local AppleMIDI link.
     *
     * Disabling the link must revoke both discovery and every existing session. Keeping a
     * passive session alive here would allow the remote device to continue sending after this
     * device's LINK switch had been turned off.
     */
    fun setDiscoveryEnabled(enabled: Boolean) {
        val activeDirectory: NsdDirectory?
        val waitersToRelease: List<CountDownLatch>
        val sessionsToClose: List<AppleMidiSession>
        synchronized(lock) {
            if (closed) return
            if (discoveryEnabled != enabled) outputAuthorizationEpoch++
            discoveryEnabled = enabled
            if (!enabled) discovered.clear()
            activeDirectory = directory
            waitersToRelease = if (enabled) {
                emptyList()
            } else {
                scanWaiters.toList().also { scanWaiters.clear() }
            }
            sessionsToClose = if (enabled) {
                emptyList()
            } else {
                sessions.values.toList()
            }
        }
        activeDirectory?.setDiscoveryEnabled(enabled)
        waitersToRelease.forEach(CountDownLatch::countDown)
        if (!enabled) clearPendingOutput()
        sessionsToClose.forEach { closeSession(it, notifyRemote = true) }
        publishPeers()
    }

    /**
     * Updates the output half of the duplex LINK selection. A session is authorized only after
     * [setInputIds] contains the same peer, which prevents a half-enabled UI state from carrying
     * MIDI while retaining one connection switch for the user.
     */
    fun setDestinationIds(ids: Collection<String>) {
        val nextPeerIds = ids.asSequence()
            .filter { it.startsWith(DESTINATION_PREFIX) }
            .take(1)
            .toCollection(LinkedHashSet())
        val plannedConnections = synchronized(lock) {
            val previous = selectedConnectionPeerIdsLocked()
            val next = appleMidiDuplexPeerIds(
                outputPeerIds = nextPeerIds,
                inputPeerIds = selectedInputPeerIds,
                inputSelectionConfigured = inputSelectionConfigured,
            )
            previous to next
        }
        releaseNetworkNotesBeforeAuthorizationChange(
            previousConnections = plannedConnections.first,
            nextConnections = plannedConnections.second,
        )

        val servicesToConnect: List<AppleMidiServiceSnapshot>
        val sessionsToClose: List<AppleMidiSession>
        var selectionChanged = false
        synchronized(lock) {
            val previousConnections = selectedConnectionPeerIdsLocked()
            selectedPeerIds.clear()
            selectedPeerIds += nextPeerIds
            val connectedEndpoints = connectedEndpointsLocked()
            val selectedConnections = selectedConnectionPeerIdsLocked()
            selectionChanged = previousConnections != selectedConnections
            if (selectionChanged) outputAuthorizationEpoch++
            servicesToConnect = if (activeMidiTransportAllowedLocked()) {
                selectedConnections
                    .mapNotNull { discovered.snapshot(it, connectedEndpoints) }
            } else {
                emptyList()
            }
            sessionsToClose = sessions.values.filter { session ->
                session.peerId == null || session.peerId !in selectedConnections
            }
        }
        if (selectionChanged) clearPendingOutput()
        sessionsToClose.forEach { closeSession(it, notifyRemote = true) }
        servicesToConnect.forEach(::ensureOutgoingSession)
        logOutputDiagnostic {
            "Selected ${ids.count { it.startsWith(DESTINATION_PREFIX) }} AppleMIDI output(s); " +
                "connectCandidates=${servicesToConnect.size}"
        }
        publishPeers()
    }

    /**
     * Updates the input half of the duplex LINK selection. An incoming AppleMIDI invitation never
     * grants local authorization by itself.
     */
    @JvmOverloads
    fun setInputIds(ids: Collection<String>, configured: Boolean = true) {
        val nextInputPeerIds = if (configured) {
            ids.asSequence()
                .filter { it.startsWith(DESTINATION_PREFIX) }
                .take(1)
                .toCollection(LinkedHashSet())
        } else {
            linkedSetOf()
        }
        val plannedConnections = synchronized(lock) {
            val previous = selectedConnectionPeerIdsLocked()
            val next = appleMidiDuplexPeerIds(
                outputPeerIds = selectedPeerIds,
                inputPeerIds = nextInputPeerIds,
                inputSelectionConfigured = configured,
            )
            previous to next
        }
        releaseNetworkNotesBeforeAuthorizationChange(
            previousConnections = plannedConnections.first,
            nextConnections = plannedConnections.second,
        )

        val servicesToConnect: List<AppleMidiServiceSnapshot>
        val sessionsToClose: List<AppleMidiSession>
        var selectionChanged = false
        synchronized(lock) {
            val previousConnections = selectedConnectionPeerIdsLocked()
            inputSelectionConfigured = configured
            selectedInputPeerIds.clear()
            selectedInputPeerIds += nextInputPeerIds
            val selectedConnections = selectedConnectionPeerIdsLocked()
            selectionChanged = previousConnections != selectedConnections
            if (selectionChanged) outputAuthorizationEpoch++
            val connectedEndpoints = connectedEndpointsLocked()
            servicesToConnect = if (activeMidiTransportAllowedLocked()) {
                selectedConnections.mapNotNull { discovered.snapshot(it, connectedEndpoints) }
            } else {
                emptyList()
            }
            sessionsToClose = sessions.values.filter { session ->
                session.peerId == null || session.peerId !in selectedConnections
            }
        }
        if (selectionChanged) clearPendingOutput()
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
        if (safeMessages.isEmpty()) return
        val authorizationEpoch = synchronized(lock) { outputAuthorizationEpochLocked() }
        if (authorizationEpoch == null) {
            logOutputDiagnostic {
                "Dropped outbound MIDI before queueing; supported=${safeMessages.size} authorized=false"
            }
            return
        }
        logOutputDiagnostic { "Queued ${safeMessages.size} outbound MIDI message(s)" }
        synchronized(pendingOutputLock) {
            // LINK can be revoked after the fast-path check above. Recheck while holding the
            // queue lock so setDiscoveryEnabled(false) either blocks this enqueue or clears it.
            if (!synchronized(lock) {
                    outputAuthorizationEpochLocked() == authorizationEpoch
                }
            ) return
            if (pendingOutput.size == 0) {
                pendingOutputAuthorizationEpoch = authorizationEpoch
            } else if (pendingOutputAuthorizationEpoch != authorizationEpoch) {
                pendingOutput.clear()
                pendingOutputAuthorizationEpoch = authorizationEpoch
            }
            val now = System.nanoTime()
            val result = pendingOutput.offer(safeMessages, timestampNanos, now)
            val desiredDeadline = if (result.flushImmediately) {
                now
            } else {
                pendingOutput.nextFlushDeadlineNanos() ?: return
            }
            val existing = outputFlushFuture
            val alreadyImmediate = result.flushImmediately &&
                outputFlushDeadlineNanos?.let { it <= now } == true &&
                existing != null && !existing.isDone
            if (!alreadyImmediate &&
                (existing == null || existing.isDone || outputFlushDeadlineNanos != desiredDeadline)
            ) {
                existing?.cancel(false)
                outputFlushDeadlineNanos = desiredDeadline
                runCatching {
                    outputFlushFuture = scheduler.schedule(
                        ::flushPendingOutput,
                        (desiredDeadline - now).coerceAtLeast(0L),
                        TimeUnit.NANOSECONDS,
                    )
                }.onFailure { error ->
                    outputFlushFuture = null
                    outputFlushDeadlineNanos = null
                    pendingOutput.clear()
                    pendingOutputAuthorizationEpoch = null
                    if (running) Log.w(TAG, "Could not queue RTP-MIDI output", error)
                }
            }
        }
    }

    /** Coalesces the burst of key events arriving within one audio-sized frame. */
    private fun flushPendingOutput() {
        val output: MidiOutputDrain
        val authorizationEpoch: Long
        synchronized(pendingOutputLock) {
            outputFlushFuture = null
            outputFlushDeadlineNanos = null
            if (pendingOutput.size == 0) return
            authorizationEpoch = pendingOutputAuthorizationEpoch ?: run {
                pendingOutput.clear()
                return
            }
            output = pendingOutput.drain()
            pendingOutputAuthorizationEpoch = null
        }
        sendRtpMidi(output, authorizationEpoch)
    }

    /** Revoking LINK invalidates queued packets before a session can be re-established. */
    private fun clearPendingOutput() {
        synchronized(pendingOutputLock) {
            outputFlushFuture?.cancel(false)
            outputFlushFuture = null
            outputFlushDeadlineNanos = null
            pendingOutput.clear()
            pendingOutputAuthorizationEpoch = null
        }
    }

    /**
     * Flushes the currently coalesced MIDI batch before a LINK is revoked.
     *
     * The platform router sends an all-notes-off batch immediately before it disables the
     * network.  A normal [send] call is intentionally asynchronous, so clearing the queue as
     * part of LINK shutdown could otherwise discard that release batch before it reaches UDP.
     */
    fun flushPendingOutputSynchronously(timeoutMillis: Long = CONTROL_FLUSH_TIMEOUT_MILLIS): Boolean {
        val output: MidiOutputDrain
        val authorizationEpoch: Long
        synchronized(pendingOutputLock) {
            outputFlushFuture?.cancel(false)
            outputFlushFuture = null
            outputFlushDeadlineNanos = null
            if (pendingOutput.size == 0) return true
            authorizationEpoch = pendingOutputAuthorizationEpoch ?: run {
                pendingOutput.clear()
                return true
            }
            output = pendingOutput.drain()
            pendingOutputAuthorizationEpoch = null
        }

        return sendOutputSynchronously(output, authorizationEpoch, timeoutMillis)
    }

    /** Sends a final release packet while the old duplex selection is still authorized. */
    private fun releaseNetworkNotesBeforeAuthorizationChange(
        previousConnections: Set<String>,
        nextConnections: Set<String>,
    ) {
        if (previousConnections.isEmpty() || previousConnections == nextConnections) return

        // Queued Note Ons no longer need delivery once their route is being revoked. Dropping
        // them before the panic also guarantees no future-timestamp Note On can follow it.
        clearPendingOutput()
        val authorizationEpoch = synchronized(lock) {
            outputAuthorizationEpochLocked()?.takeIf {
                selectedConnectionPeerIdsLocked() == previousConnections
            }
        } ?: return
        val now = System.nanoTime()
        val panic = MidiOutputDrain(
            messages = MidiOutputAccumulator.fullPanic(now),
            batchWindowNanos = MidiOutputAccumulator.NORMAL_BATCH_WINDOW_NANOS,
        )
        sendOutputSynchronously(panic, authorizationEpoch, CONTROL_FLUSH_TIMEOUT_MILLIS)
    }

    private fun sendOutputSynchronously(
        output: MidiOutputDrain,
        authorizationEpoch: Long,
        timeoutMillis: Long,
    ): Boolean {
        // Keep the same serial data writer used by normal output so an already submitted note
        // batch cannot overtake the final release controllers.
        val completed = CountDownLatch(1)
        val accepted = rtpSendExecutor.execute(
            critical = true,
            onDiscard = completed::countDown,
        ) {
            try {
                sendRtpMidiNow(output, authorizationEpoch)
            } finally {
                completed.countDown()
            }
        }
        if (!accepted) return false
        return runCatching {
            completed.await(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    private fun onServiceResolved(service: ResolvedAppleMidiService) {
        val snapshot: AppleMidiServiceSnapshot
        val shouldConnect: Boolean
        synchronized(lock) {
            if (!running || !discoveryEnabled) return
            val peerId = discovered.upsert(service)
            sessions.values.filter { it.connectsTo(service) }.forEach { it.peerId = peerId }
            snapshot = discovered.snapshot(peerId, connectedEndpointsLocked()) ?: return
            shouldConnect = peerId in selectedConnectionPeerIdsLocked() &&
                activeMidiTransportAllowedLocked()
        }
        publishPeers()
        if (shouldConnect) ensureOutgoingSession(snapshot)
    }

    private fun onServiceLost(instanceId: String) {
        val fallback: AppleMidiServiceSnapshot?
        val shouldConnect: Boolean
        val sessionsToClose: List<AppleMidiSession>
        synchronized(lock) {
            if (!running || !discoveryEnabled) return
            val peerId = discovered.peerIdForInstance(instanceId)
            discovered.remove(instanceId)
            fallback = peerId?.let { discovered.snapshot(it, connectedEndpointsLocked()) }
            sessionsToClose = if (peerId != null && fallback == null) {
                sessions.values.filter { it.peerId == peerId }
            } else {
                emptyList()
            }
            shouldConnect = fallback != null && peerId in selectedConnectionPeerIdsLocked() &&
                sessions.values.none {
                    it.peerId == peerId && it.state == AppleMidiSessionState.CONNECTED
                } && activeMidiTransportAllowedLocked()
        }
        // Treat removal of the last Bonjour alias as a remote LINK revocation. AppleMIDI BY is
        // UDP and can be lost, so waiting for the inactivity timeout would leave a stale route
        // writable after the peer has explicitly stopped publishing itself.
        sessionsToClose.forEach { closeSession(it, notifyRemote = false) }
        publishPeers()
        if (shouldConnect) fallback?.let(::ensureOutgoingSession)
    }

    private fun ensureOutgoingSession(snapshot: AppleMidiServiceSnapshot) {
        val service = snapshot.service
        val peerId = snapshot.id
        val obsolete = synchronized(lock) {
            if (!activeMidiTransportAllowedLocked() ||
                peerId !in selectedConnectionPeerIdsLocked()
            ) return
            sessions.values.filter {
                it.initiatedLocally &&
                    it.peerId == peerId && !it.connectsTo(service) &&
                    it.state != AppleMidiSessionState.CONNECTED
            }
        }
        obsolete.forEach { closeSession(it, notifyRemote = true) }
        val session = synchronized(lock) {
            if (!activeMidiTransportAllowedLocked() ||
                peerId !in selectedConnectionPeerIdsLocked() ||
                sessions.size >= configuration.maximumSessions
            ) {
                return
            }
            // A peer selected only as an input may already have invited us.
            // Do not create a competing local invitation while that passive
            // control/data exchange is still alive.
            if (sessions.values.any {
                    it.peerId == peerId && it.state in LIVE_SESSION_STATES
                }) {
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
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
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
        val affectedSessions = synchronized(lock) {
            if (!running || portPair !== pair || !transportHealthy) return
            transportHealthy = false
            sessions.values.toList()
        }
        synchronized(pendingOutputLock) {
            outputFlushFuture?.cancel(false)
            outputFlushFuture = null
            outputFlushDeadlineNanos = null
            pendingOutput.clear()
            pendingOutputAuthorizationEpoch = null
        }
        Log.e(TAG, "AppleMIDI UDP transport failed; all sessions are being closed", error)
        affectedSessions.forEach { closeSession(it, notifyRemote = false) }
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
                findEndSession(remote, packet.initiatorToken, packet.ssrc)
                    ?.let { closeSession(it, notifyRemote = false) }
            }
            is AppleMidiControlPacket.ClockSynchronization -> handleClockSynchronization(packet, remote)
            is AppleMidiControlPacket.ReceiverFeedback -> {
                synchronized(lock) {
                    selectReceiverFeedbackSession(
                        sessions.values,
                        remote,
                        packet.ssrc,
                    )?.takeIf(::activeSessionTransportControlAllowedLocked)?.let { session ->
                        session.lastActivityNanos = System.nanoTime()
                        session.acknowledgeOutgoingSequence(packet.sequenceNumber)
                    }
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
        var detachedSimultaneousSession: AppleMidiSession? = null
        val session = synchronized(lock) {
            if (!running || !discoveryEnabled || !activeMidiTransportAllowedLocked()) {
                rejected = true
                return@synchronized null
            }

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
                        return@synchronized null
                    }
                    // Resolve simultaneous invitations by fully retiring the losing local
                    // session. Removing it from the map alone leaves its delivery gate, active
                    // notes, and recovery/output state alive.
                    detachedSimultaneousSession = sessions.remove(simultaneous.id)?.also {
                        outputAuthorizationEpoch++
                    }
                }
                if (sessions.size >= configuration.maximumSessions) {
                    rejected = true
                    return@synchronized null
                }
                var peer = discovered.findByEndpoint(
                    host = remote.address,
                    controlPort = remote.port,
                    connectedEndpoints = connectedEndpointsLocked(),
                )
                val invitationService = if (peer == null) {
                    ResolvedAppleMidiService(
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
                } else {
                    null
                }
                val candidatePeerId = peer?.id
                    ?: invitationService?.let(discovered::peerIdFor)
                val authorizedPeerId = candidatePeerId
                    ?.takeIf(::isConnectionPeerSelectedLocked)
                    ?: selectedConnectionPeerIdForEndpointLocked(
                        remote = remote,
                        advertisedName = packet.name,
                    )
                if (authorizedPeerId == null) {
                    rejected = true
                    return@synchronized null
                }
                if (peer == null && invitationService != null) {
                    val insertedPeerId = discovered.upsert(invitationService)
                    if (insertedPeerId != authorizedPeerId) {
                        discovered.remove(invitationService.id)
                        rejected = true
                        return@synchronized null
                    }
                }
                peer = discovered.snapshot(authorizedPeerId, connectedEndpointsLocked()) ?: peer
                val authorizedPeer = peer
                if (authorizedPeer == null) {
                    rejected = true
                    return@synchronized null
                }
                match = AppleMidiSession(
                    id = "applemidi-session:${UUID.randomUUID()}",
                    peerId = authorizedPeerId,
                    peerName = packet.name.ifBlank { authorizedPeer.service.name },
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

            val authorizedSession = match
            val authorizedPeerId = authorizedSession?.peerId
                ?.takeIf(::isConnectionPeerSelectedLocked)
                ?: selectedConnectionPeerIdForEndpointLocked(
                    remote = remote,
                    advertisedName = packet.name,
                )
            if (authorizedSession == null || authorizedPeerId == null) {
                rejected = true
                return@synchronized null
            }
            authorizedSession.peerId = authorizedPeerId
            if (dataChannel) {
                authorizedSession.remoteDataPort = remote.port
                authorizedSession.dataAccepted = true
                authorizedSession.state = AppleMidiSessionState.CONNECTED
            } else {
                authorizedSession.remoteControlPort = remote.port
                authorizedSession.controlAccepted = true
            }
            authorizedSession.lastActivityNanos = System.nanoTime()
            authorizedSession
        }
        detachedSimultaneousSession?.let {
            closeSession(it, notifyRemote = true, sessionAlreadyRemoved = true)
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
        sendControl(
            response,
            remote,
            dataChannel,
            sendAllowedLocked = {
                if (session == null) {
                    activeMidiTransportAllowed(running, portPair, transportHealthy)
                } else {
                    activeSessionTransportControlAllowedLocked(session)
                }
            },
        )
        if (!rejected && session != null) publishPeers()
    }

    private fun acceptInvitationResponse(
        packet: AppleMidiControlPacket.Invitation,
        remote: InetSocketAddress,
        dataChannel: Boolean,
    ) {
        val session = synchronized(lock) {
            if (!activeMidiTransportAllowedLocked()) return@synchronized null
            val match = selectInvitationResponseSession(
                sessions = sessions.values,
                remote = remote,
                initiatorToken = packet.initiatorToken,
                responseSsrc = packet.ssrc,
                dataChannel = dataChannel,
            )?.takeIf(::activeSessionControlAllowedLocked) ?: run {
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
        } ?: return
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
            selectClockSynchronizationSession(sessions.values, remote, packet.ssrc)
                ?.takeIf(::activeSessionTransportControlAllowedLocked)
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
                    sendAllowedLocked = { activeSessionTransportControlAllowedLocked(session) },
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
                    if (!activeSessionTransportControlAllowedLocked(session)) return
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
        val journalHeartbeats = mutableListOf<Pair<AppleMidiSession, Boolean>>()
        val receiverFeedback = mutableListOf<Pair<AppleMidiSession, Int>>()
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
                if (session.state == AppleMidiSessionState.CONNECTED) {
                    val urgent = session.hasUnacknowledgedCriticalReleaseJournal
                    val interval = if (urgent) {
                        CRITICAL_RELEASE_HEARTBEAT_NANOS
                    } else {
                        JOURNAL_HEARTBEAT_NANOS
                    }
                    if (session.journalHeartbeatDue(now, interval)) {
                        journalHeartbeats += session to urgent
                    }
                }
                if (session.state == AppleMidiSessionState.CONNECTED) {
                    session.takeReceiverFeedback(now, RECEIVER_FEEDBACK_INTERVAL_NANOS)
                        ?.let { sequenceNumber ->
                        receiverFeedback += session to sequenceNumber
                    }
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
        reconcileSelectedOutgoingSessions(now)
        journalHeartbeats
            .filterNot { heartbeat -> expired.any { it.id == heartbeat.first.id } }
            .forEach { (session, urgent) -> queueJournalHeartbeat(session, urgent) }
        receiverFeedback
            .filterNot { feedback -> expired.any { it.id == feedback.first.id } }
            .forEach { (session, sequenceNumber) ->
                sendReceiverFeedback(session, sequenceNumber)
            }
        drainJitterBuffers(now)
    }

    /**
     * CoreMIDI may replace a network session while its DNS-SD record and the user's selection
     * remain unchanged. Discovery callbacks do not fire in that case, so periodically restore
     * only the selected connection(s) that have no live invitation or media session.
     */
    private fun reconcileSelectedOutgoingSessions(nowNanos: Long) {
        val servicesToConnect = synchronized(lock) {
            val lastAttempt = lastOutgoingSessionReconciliationNanos
            if (!activeMidiTransportAllowedLocked() ||
                (lastAttempt != null &&
                    nowNanos - lastAttempt < OUTGOING_SESSION_RECONCILIATION_NANOS)
            ) {
                return
            }
            lastOutgoingSessionReconciliationNanos = nowNanos
            val connectedEndpoints = connectedEndpointsLocked()
            selectedConnectionPeerIdsLocked().mapNotNull { peerId ->
                val hasLiveSession = sessions.values.any { session ->
                    session.peerId == peerId && session.state in LIVE_SESSION_STATES
                }
                if (hasLiveSession) {
                    null
                } else {
                    discovered.snapshot(peerId, connectedEndpoints)
                }
            }
        }
        if (servicesToConnect.isEmpty()) return
        logOutputDiagnostic {
            "Reconciling selected AppleMIDI peer(s): " +
                servicesToConnect.joinToString { service ->
                    "${service.service.name}@${service.service.host.hostAddress}:${service.service.controlPort}"
                }
        }
        servicesToConnect.forEach(::ensureOutgoingSession)
    }

    private fun sendControl(
        packet: AppleMidiControlPacket,
        destination: InetSocketAddress,
        dataChannel: Boolean,
        sendAllowedLocked: (() -> Boolean)? = null,
        onSentLocked: (() -> Unit)? = null,
    ) {
        val bytes = AppleMidiControlCodec.encode(packet)
        runCatching {
            controlExecutor.execute {
                val pair = synchronized(lock) {
                    if (sendAllowedLocked?.invoke() == false) return@execute
                    portPair
                } ?: return@execute
                val socket = if (dataChannel) pair.data else pair.control
                runCatching {
                    socket.send(DatagramPacket(bytes, bytes.size, destination))
                    synchronized(lock) { onSentLocked?.invoke() }
                }
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
        sessionAlreadyRemoved: Boolean = false,
    ) {
        val removed = if (sessionAlreadyRemoved) {
            true
        } else {
            synchronized(lock) {
                if (sessions.remove(session.id) == null) {
                    false
                } else {
                    outputAuthorizationEpoch++
                    true
                }
            }
        }
        if (!removed) return
        clearPendingOutput()
        if (notifyRemote) {
            val by = AppleMidiControlPacket.EndSession(session.initiatorToken, localSsrc)
            sendControl(by, session.controlAddress, dataChannel = false)
            sendControl(by, session.dataAddress, dataChannel = true)
        }
        session.closeDelivery {
            releaseSessionNotes(session)
            listener.onSessionClosed(session.id)
        }
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

    /**
     * BY packets are sent by both AppleMIDI participants, and CoreMIDI does not always use the
     * same SSRC field as the invitation that created the local session. Match the initiator token
     * first, then SSRC, and finally a unique host so a lost BY cannot leave a stale route writable.
     */
    private fun findEndSession(
        remote: InetSocketAddress,
        initiatorToken: Long,
        ssrc: Long,
    ): AppleMidiSession? = synchronized(lock) {
        val hostCandidates = sessions.values.filter {
            it.transportAddress.sameNetworkHost(remote.address)
        }
        hostCandidates.firstOrNull { it.initiatorToken == initiatorToken }
            ?: hostCandidates.firstOrNull { it.remoteSsrc == ssrc }
            ?: hostCandidates.singleOrNull()
    }

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
        discoveryEnabled && activeMidiTransportAllowed(running, portPair, transportHealthy)

    private fun outputAuthorizationEpochLocked(): Long? =
        outputAuthorizationEpoch.takeIf {
            activeMidiTransportAllowedLocked() && sessions.values.any(::midiOutputAllowedLocked)
        }

    private fun activeSessionControlAllowedLocked(session: AppleMidiSession): Boolean =
        activeMidiTransportAllowedLocked() &&
            sessions[session.id] === session &&
            session.initiatedLocally &&
            session.peerId?.let(::isConnectionPeerSelectedLocked) == true

    /** Allows CK replies from a responder while keeping them bound to an authorized LINK. */
    private fun activeSessionTransportControlAllowedLocked(session: AppleMidiSession): Boolean =
        activeMidiTransportAllowedLocked() &&
            sessions[session.id] === session &&
            session.state in LIVE_SESSION_STATES &&
            session.peerId?.let(::isConnectionPeerSelectedLocked) == true

    private fun midiOutputAllowedLocked(session: AppleMidiSession): Boolean =
        activeMidiTransportAllowedLocked() &&
            sessions[session.id] === session &&
            session.state == AppleMidiSessionState.CONNECTED &&
            session.peerId?.let(::isConnectionPeerSelectedLocked) == true

    private fun midiInputAllowedLocked(session: AppleMidiSession): Boolean =
        activeMidiTransportAllowedLocked() &&
            sessions[session.id] === session &&
            session.state == AppleMidiSessionState.CONNECTED &&
            session.peerId?.let(::isConnectionPeerSelectedLocked) == true

    private fun isConnectionPeerSelectedLocked(peerId: String): Boolean =
        peerId in selectedConnectionPeerIdsLocked()

    /**
     * Resolves a selected peer by its transport endpoint when a Bonjour callback has not yet
     * associated the incoming invitation with the same registry identity. Android and iOS can
     * deliver the invitation before DNS-SD finishes resolving the service, so requiring the
     * synthetic invitation identity here would reject an otherwise authorized connection.
     */
    private fun selectedConnectionPeerIdForEndpointLocked(
        remote: InetSocketAddress,
        advertisedName: String,
    ): String? {
        val normalizedName = advertisedName.trim()
        return selectedConnectionPeerIdsLocked().firstOrNull { peerId ->
            val snapshot = discovered.snapshot(peerId, connectedEndpointsLocked()) ?: return@firstOrNull false
            val service = snapshot.service
            if (!service.host.sameNetworkHost(remote.address)) return@firstOrNull false
            val controlPort = service.controlPort
            val dataPort = if (controlPort < 0xFFFF) controlPort + 1 else controlPort
            if (remote.port != controlPort && remote.port != dataPort) return@firstOrNull false
            if (normalizedName.isEmpty()) return@firstOrNull true
            AppleMidiServiceRegistry.logicalName(service.name)
                .equals(AppleMidiServiceRegistry.logicalName(normalizedName), ignoreCase = true)
        }
    }

    private fun selectedConnectionPeerIdsLocked(): LinkedHashSet<String> =
        LinkedHashSet<String>().apply {
            if (inputSelectionConfigured) {
                addAll(selectedPeerIds.intersect(selectedInputPeerIds))
            }
        }

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
            discoveryEnabled = false
            active = sessions.values.toList()
            pair = portPair
            running = false
            transportHealthy = false
            // Invalidate any already queued wakeup. The scheduler is shut down below.
            jitterWakeupFuture?.cancel(false)
            jitterWakeupFuture = null
            nextJitterWakeupNanos = null
            jitterWakeupGeneration += 1
            gapWakeupFuture?.cancel(false)
            gapWakeupFuture = null
            nextGapWakeupNanos = null
            gapWakeupGeneration += 1
        }
        synchronized(pendingOutputLock) {
            outputFlushFuture?.cancel(false)
            outputFlushFuture = null
            outputFlushDeadlineNanos = null
            pendingOutput.clear()
            pendingOutputAuthorizationEpoch = null
        }
        rtpSendExecutor.close()
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
            queuedJournalHeartbeats.clear()
            nextJitterWakeupNanos = null
            jitterWakeupGeneration += 1
            nextGapWakeupNanos = null
            gapWakeupGeneration += 1
        }
        pair?.close()
        ioExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        scheduler.shutdownNow()
        scanWaiters.forEach(CountDownLatch::countDown)
        scanWaiters.clear()
    }

    private data class PreparedRtpPacket(
        val extendedSequenceNumber: Long,
        val packet: RtpMidiPacket,
        val bytes: ByteArray,
    )

    private fun sendRtpMidi(output: MidiOutputDrain, authorizationEpoch: Long) {
        val critical = output.messages.any { isCriticalMidiRelease(it.bytes) }
        rtpSendExecutor.execute(critical = critical) {
            sendRtpMidiNow(output, authorizationEpoch)
        }
    }

    private fun sendRtpMidiNow(output: MidiOutputDrain, authorizationEpoch: Long) {
        val targets = synchronized(lock) {
            if (outputAuthorizationEpochLocked() != authorizationEpoch) return
            sessions.values
                .filter { midiOutputAllowedLocked(it) }
                .groupBy { it.peerId ?: it.id }
                .values
                .map { peerSessions -> peerSessions.maxBy(AppleMidiSession::lastActivityNanos) }
        }
        logOutputDiagnostic {
            val sessionSummary = synchronized(lock) {
                sessions.values.joinToString(separator = ",") { session ->
                    "${session.peerName}:${session.state}:selected=" +
                        (session.peerId?.let(selectedPeerIds::contains) == true) +
                        ":data=${session.dataAddress.address.hostAddress}:${session.dataAddress.port}"
                }
            }
            "Draining ${output.messages.size} MIDI message(s) to ${targets.size} session(s); " +
                "sessions=[$sessionSummary]"
        }
        targets.forEach { session ->
            sendRtpMidiToSession(session, output)
        }
    }

    private fun sendRtpMidiToSession(
        session: AppleMidiSession,
        output: MidiOutputDrain,
    ) {
        // RFC 6295 specifies M=1 for a non-empty command section, but Apple's CoreMIDI network
        // driver and widely deployed AppleMIDI peers discard command packets with that value.
        // Keep M=0 on this AppleMIDI transport for wire compatibility; the command LEN remains
        // the authoritative discriminator.
        var cursor = 0
        while (cursor < output.messages.size) {
            if (!synchronized(lock) { midiOutputAllowedLocked(session) }) return
            val firstTimestamp = output.messages[cursor].timestampNanos
            val endExclusive = largestFittingMidiOutputPrefix(
                messages = output.messages,
                startIndex = cursor,
                batchWindowNanos = output.batchWindowNanos,
                maximumDatagramBytes = MAX_RTP_DATAGRAM_BYTES,
                encodedSize = { candidate ->
                    prepareRtpPacket(session, candidate, marker = false)?.bytes?.size
                        ?: Int.MAX_VALUE
                },
            )
            if (endExclusive == cursor) {
                if (!sendRecoveryOverflowPanic(session, firstTimestamp)) return
                continue
            }
            val accepted = prepareRtpPacket(
                session,
                output.messages.subList(cursor, endExclusive),
                marker = false,
            ) ?: return
            if (!transmitPreparedRtpPacket(session, accepted)) return
            cursor = endExclusive
        }
    }

    private fun prepareRtpPacket(
        session: AppleMidiSession,
        messages: List<PendingMidiOutput>,
        marker: Boolean,
    ): PreparedRtpPacket? {
        val sequenceAndJournal = synchronized(lock) {
            if (!midiOutputAllowedLocked(session)) return null
            val extended = session.nextOutgoingExtendedSequence
            extended to session.recoveryJournalForPacket(extended)
        }
        val extendedSequence = sequenceAndJournal.first
        val packet = buildRtpPacket(
            extendedSequenceNumber = extendedSequence,
            messages = messages,
            marker = marker,
            journal = sequenceAndJournal.second,
        )
        return PreparedRtpPacket(
            extendedSequenceNumber = extendedSequence,
            packet = packet,
            bytes = RtpMidiCodec.encode(packet),
        )
    }

    private fun buildRtpPacket(
        extendedSequenceNumber: Long,
        messages: List<PendingMidiOutput>,
        marker: Boolean,
        journal: ByteArray?,
    ): RtpMidiPacket {
        val firstTimestampNanos = messages.firstOrNull()?.timestampNanos ?: System.nanoTime()
        var previousTimestampNanos = firstTimestampNanos
        return RtpMidiPacket(
            sequenceNumber = (extendedSequenceNumber and 0xFFFF).toInt(),
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
            marker = marker,
            firstDeltaEncoded = false,
            journal = journal,
        )
    }

    private fun transmitPreparedRtpPacket(
        session: AppleMidiSession,
        prepared: PreparedRtpPacket,
    ): Boolean {
        if (prepared.bytes.size > MAX_RTP_DATAGRAM_BYTES) return false
        val pair = synchronized(lock) {
            if (!midiOutputAllowedLocked(session) ||
                session.nextOutgoingExtendedSequence != prepared.extendedSequenceNumber
            ) {
                return false
            }
            check(session.takeNextOutgoingSequence() == prepared.extendedSequenceNumber)
            portPair
        } ?: return false
        val sent = runCatching {
            pair.data.send(
                DatagramPacket(prepared.bytes, prepared.bytes.size, session.dataAddress),
            )
            true
        }.getOrElse { error ->
            synchronized(lock) {
                if (sessions[session.id] === session) session.recordOutgoingSendFailure()
            }
            if (error is SocketException) {
                markTransportUnhealthy(pair, error)
            } else if (running) {
                Log.d(TAG, "Could not send RTP-MIDI", error)
            }
            false
        }
        if (sent) {
            synchronized(lock) {
                if (sessions[session.id] === session) {
                    session.recordOutgoingPacket(
                        packetExtendedSequence = prepared.extendedSequenceNumber,
                        messages = prepared.packet.commands.map(TimedMidiMessage::message),
                        sentAtNanos = System.nanoTime(),
                    )
                }
            }
            logOutputDiagnostic {
                val header = prepared.bytes.take(14).joinToString(separator = " ") {
                    (it.toInt() and 0xFF).toString(16).padStart(2, '0')
                }
                "Sent RTP-MIDI seq=${prepared.packet.sequenceNumber} " +
                    "messages=${prepared.packet.commands.size} bytes=${prepared.bytes.size} " +
                    "to=${session.dataAddress.address.hostAddress}:${session.dataAddress.port} " +
                    "header=$header"
            }
        }
        return sent
    }

    private fun logOutputDiagnostic(message: () -> String) {
        val shouldLog = synchronized(lock) {
            if (outputDiagnosticCount >= MAX_OUTPUT_DIAGNOSTICS) {
                false
            } else {
                outputDiagnosticCount++
                true
            }
        }
        if (shouldLog) Log.i(TAG, message())
    }

    private fun logInputDiagnostic(message: () -> String) {
        val shouldLog = synchronized(lock) {
            if (inputDiagnosticCount >= MAX_INPUT_DIAGNOSTICS) {
                false
            } else {
                inputDiagnosticCount++
                true
            }
        }
        if (shouldLog) Log.i(TAG, message())
    }

    private fun sendRecoveryOverflowPanic(
        session: AppleMidiSession,
        timestampNanos: Long,
    ): Boolean {
        synchronized(lock) {
            if (!midiOutputAllowedLocked(session)) return false
            session.resetOutgoingRecoveryForPanic(session.nextOutgoingExtendedSequence)
        }
        val panic = MidiOutputAccumulator.fullPanic(timestampNanos)
        val prepared = prepareRtpPacket(session, panic, marker = false) ?: return false
        if (prepared.bytes.size > MAX_RTP_DATAGRAM_BYTES) {
            Log.e(TAG, "A complete RTP-MIDI panic exceeds the datagram limit")
            return false
        }
        return transmitPreparedRtpPacket(session, prepared)
    }

    private fun queueJournalHeartbeat(session: AppleMidiSession, urgent: Boolean) {
        val queued = synchronized(lock) {
            if (!midiOutputAllowedLocked(session) || !queuedJournalHeartbeats.add(session.id)) {
                false
            } else {
                true
            }
        }
        if (!queued) return
        val accepted = rtpSendExecutor.execute(
            critical = urgent,
            onDiscard = { synchronized(lock) { queuedJournalHeartbeats.remove(session.id) } },
        ) {
            try {
                sendJournalHeartbeatNow(session)
            } finally {
                synchronized(lock) { queuedJournalHeartbeats.remove(session.id) }
            }
        }
        if (!accepted) synchronized(lock) { queuedJournalHeartbeats.remove(session.id) }
    }

    private fun sendJournalHeartbeatNow(session: AppleMidiSession) {
        val urgent = synchronized(lock) {
            if (!midiOutputAllowedLocked(session)) return
            val currentUrgent = session.hasUnacknowledgedCriticalReleaseJournal
            val interval = if (currentUrgent) {
                CRITICAL_RELEASE_HEARTBEAT_NANOS
            } else {
                JOURNAL_HEARTBEAT_NANOS
            }
            if (!session.journalHeartbeatDue(System.nanoTime(), interval)) return
            currentUrgent
        }
        val prepared = prepareRtpPacket(session, emptyList(), marker = false) ?: return
        if (prepared.bytes.size > MAX_RTP_DATAGRAM_BYTES) {
            sendRecoveryOverflowPanic(session, System.nanoTime())
            return
        }
        if (transmitPreparedRtpPacket(session, prepared)) {
            synchronized(lock) {
                if (sessions[session.id] === session) session.recordJournalHeartbeatSent(urgent)
            }
        }
    }

    private fun sendOverloadPanicNow() {
        val targets = synchronized(lock) {
            if (!activeMidiTransportAllowedLocked()) return
            sessions.values
                .filter(::midiOutputAllowedLocked)
                .groupBy { it.peerId ?: it.id }
                .values
                .map { peerSessions -> peerSessions.maxBy(AppleMidiSession::lastActivityNanos) }
        }
        val now = System.nanoTime()
        targets.forEach { sendRecoveryOverflowPanic(it, now) }
    }

    private fun handleRtpMidi(payload: ByteArray, remote: InetSocketAddress) {
        val packetSsrc = RtpMidiCodec.readSsrcOrNull(payload) ?: return
        val selection = synchronized(lock) {
            // Do not let a packet from a deselected/stale session mutate its negotiated data
            // port or reorder/recovery state. Authorization is part of session lookup, not only
            // a check after the packet has already been accepted.
            val authorizedSessions = sessions.values.filter(::midiInputAllowedLocked)
            val exact = selectRtpMidiSession(authorizedSessions, remote, packetSsrc)
            val session = exact ?: selectRtpMidiSessionByHostAndSsrc(
                sessions = authorizedSessions,
                remote = remote,
                remoteSsrc = packetSsrc,
            )
            if (exact == null && session != null && session.remoteDataPort != remote.port) {
                // Some CoreMIDI builds replace the data socket when a
                // connection resumes. SSRC keeps this fallback bound to one
                // already-negotiated participant.
                logInputDiagnostic {
                    "RTP-MIDI peer ${session.peerName} moved data port " +
                        "${session.remoteDataPort} -> ${remote.port}"
                }
                session.remoteDataPort = remote.port
            }
            session?.let { it to (exact != null) }
        }
        if (selection == null) {
            logInputDiagnostic {
                "Dropped RTP-MIDI from ${remote.address.hostAddress}:${remote.port}; " +
                    "no unique session for SSRC=$packetSsrc"
            }
            return
        }
        val (session, exactMatch) = selection
        logInputDiagnostic {
            "Received RTP-MIDI bytes=${payload.size} from " +
                "${remote.address.hostAddress}:${remote.port}; peer=${session.peerName}; " +
                "match=${if (exactMatch) "exact" else "host+ssrc"}"
        }
        val packet = RtpMidiCodec.decode(payload)
        val arrival = System.nanoTime()
        val release = synchronized(lock) {
            session.takeIf { current ->
                midiInputAllowedLocked(current) && current.remoteSsrc == packet.ssrc
            }?.let { current ->
                current.lastActivityNanos = arrival
                current.offerRtpPacket(packet, arrival)
            }
        } ?: return
        processRtpPacketRelease(session, release)
        scheduleGapWakeup()
    }

    private fun processRtpPacketRelease(
        session: AppleMidiSession,
        release: RtpMidiPacketRelease,
    ) {
        if (release.packets.isEmpty()) return
        var feedbackSequenceNumber: Int? = null
        synchronized(lock) {
            if (!midiInputAllowedLocked(session)) return
            session.queueReceiverFeedback(release.packets.last().extendedSequenceNumber)
            val recovery = when {
                release.loss != null -> session.recoverFromPacketLoss(release.loss)
                release.initialPacket && release.packets.first().packet.journal != null ->
                    session.recoverFromInitialPacket(release.packets.first())
                else -> null
            }
            if (recovery != null && recovery.messages.isNotEmpty()) {
                val recoveryPacket = release.loss?.recoveryPacket ?: release.packets.first()
                recovery.messages.forEach { message ->
                    session.offerMidi(
                        remoteTimestamp = recoveryPacket.packet.timestamp and UINT32_MASK,
                        arrivalNanos = recoveryPacket.arrivalNanos,
                        message = message,
                    )
                }
            }
            release.packets.forEach { buffered ->
                buffered.packet.commands
                    .zip(buffered.packet.commandTimestamps())
                    .forEach { (command, commandTimestamp) ->
                        session.offerMidi(
                            remoteTimestamp = commandTimestamp and UINT32_MASK,
                            arrivalNanos = buffered.arrivalNanos,
                            message = command.message.toByteArray(),
                        )
                }
                session.observeOrderedNetworkMidi(buffered.packet.commands.map(TimedMidiMessage::message))
            }
            if (release.loss != null) {
                feedbackSequenceNumber = session.takeReceiverFeedback()
            }
        }
        feedbackSequenceNumber?.let { sendReceiverFeedback(session, it) }
        scheduleJitterWakeup()
    }

    private fun sendReceiverFeedback(session: AppleMidiSession, sequenceNumber: Int) {
        sendControl(
            AppleMidiControlPacket.ReceiverFeedback(localSsrc, sequenceNumber),
            session.controlAddress,
            dataChannel = false,
            sendAllowedLocked = { activeSessionTransportControlAllowedLocked(session) },
            onSentLocked = {
                if (sessions[session.id] === session) session.recordReceiverFeedbackSent()
            },
        )
    }

    private fun drainJitterBuffers(nowNanos: Long) {
        val drainThroughNanos = appleMidiDeliveryDrainThroughNanos(
            nowNanos = nowNanos,
            lookaheadNanos = eventDeliveryLookaheadNanos,
        )
        val ready = synchronized(lock) {
            sessions.values
                .filter(::midiInputAllowedLocked)
                .flatMap { session -> session.drainMidi(drainThroughNanos) }
        }
        ready.sortedBy { it.targetTimeNanos }.forEach { event ->
            val session = synchronized(lock) {
                val candidate = sessions[event.sessionId] ?: return@synchronized null
                if (!midiInputAllowedLocked(candidate)) {
                    logInputDiagnostic {
                        "Dropped RTP-MIDI input from ${candidate.peerName}; peerId=${candidate.peerId}; " +
                            "selected=$selectedInputPeerIds"
                    }
                    return@synchronized null
                }
                candidate
            } ?: return@forEach
            session.deliverIfOpen {
                session.observeMidi(event.bytes)
                logInputDiagnostic {
                    "Delivered RTP-MIDI bytes=${event.bytes.size} from ${session.peerName}; " +
                        "session=${event.sessionId}"
                }
                listener.onMidiEvent(event)
            }
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
            val earliest = sessions.values.filter(::midiInputAllowedLocked)
                .mapNotNull(AppleMidiSession::nextMidiTargetTimeNanos)
                .minOrNull()
            if (earliest == null) {
                jitterWakeupFuture?.cancel(false)
                jitterWakeupFuture = null
                nextJitterWakeupNanos = null
                return
            }

            val deliveryWakeup = appleMidiDeliveryWakeupNanos(
                targetTimeNanos = earliest,
                lookaheadNanos = eventDeliveryLookaheadNanos,
            )
            val existing = nextJitterWakeupNanos
            val existingFuture = jitterWakeupFuture
            if (existing != null && existing <= deliveryWakeup &&
                existingFuture != null && !existingFuture.isDone
            ) {
                return
            }

            existingFuture?.cancel(false)
            nextJitterWakeupNanos = deliveryWakeup
            val generation = ++jitterWakeupGeneration
            val delay = (deliveryWakeup - System.nanoTime()).coerceAtLeast(0L)
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

    private fun scheduleGapWakeup() {
        var schedulingError: Throwable? = null
        synchronized(lock) {
            if (!running) return
            val earliest = sessions.values.filter(::midiInputAllowedLocked)
                .mapNotNull(AppleMidiSession::nextRtpGapDeadlineNanos)
                .minOrNull()
            if (earliest == null) {
                gapWakeupFuture?.cancel(false)
                gapWakeupFuture = null
                nextGapWakeupNanos = null
                return
            }
            val existing = nextGapWakeupNanos
            val existingFuture = gapWakeupFuture
            if (existing != null && existing <= earliest &&
                existingFuture != null && !existingFuture.isDone
            ) {
                return
            }
            existingFuture?.cancel(false)
            nextGapWakeupNanos = earliest
            val generation = ++gapWakeupGeneration
            val delay = (earliest - System.nanoTime()).coerceAtLeast(0L)
            try {
                gapWakeupFuture = scheduler.schedule(
                    { onGapWakeup(generation) },
                    delay,
                    TimeUnit.NANOSECONDS,
                )
            } catch (error: Throwable) {
                gapWakeupFuture = null
                nextGapWakeupNanos = null
                schedulingError = error
            }
        }
        schedulingError?.let { error ->
            if (running) Log.d(TAG, "Could not schedule RTP sequence-gap recovery", error)
        }
    }

    private fun onGapWakeup(generation: Long) {
        val now = System.nanoTime()
        val releases = synchronized(lock) {
            if (!running || generation != gapWakeupGeneration) return
            gapWakeupFuture = null
            nextGapWakeupNanos = null
            sessions.values.filter(::midiInputAllowedLocked).mapNotNull { session ->
                val release = session.expireRtpGap(now)
                release.takeIf { it.packets.isNotEmpty() }?.let { session to it }
            }
        }
        releases.forEach { (session, release) ->
            processRtpPacketRelease(session, release)
        }
        scheduleGapWakeup()
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
        private const val MAX_OUTPUT_DIAGNOSTICS = 160
        private const val MAX_INPUT_DIAGNOSTICS = 160
        private const val RECEIVE_BUFFER_BYTES = 1_500
        private const val MAX_RTP_DATAGRAM_BYTES = 1_200
        private const val TICK_MILLIS = 10L
        private const val RTP_SEND_QUEUE_CAPACITY = 128
        private const val CONTROL_FLUSH_TIMEOUT_MILLIS = 250L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val INVITATION_RETRY_NANOS = 1_000_000_000L
        private const val OUTGOING_SESSION_RECONCILIATION_NANOS = 750_000_000L
        private const val RECEIVER_FEEDBACK_INTERVAL_NANOS = 25_000_000L
        private const val CRITICAL_RELEASE_HEARTBEAT_NANOS = 35_000_000L
        private const val JOURNAL_HEARTBEAT_NANOS = 100_000_000L
        private const val SESSION_TIMEOUT_NANOS = 35_000_000_000L
        private const val UINT32_MASK = 0xFFFF_FFFFL
        private val LIVE_SESSION_STATES = setOf(
            AppleMidiSessionState.INVITING,
            AppleMidiSessionState.SYNCHRONIZING,
            AppleMidiSessionState.CONNECTED,
        )

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
): Boolean = running && transportHealthy && portPair != null

/** The UI's one LINK switch is mirrored into both native directions before it is authorized. */
internal fun appleMidiDuplexPeerIds(
    outputPeerIds: Set<String>,
    inputPeerIds: Set<String>,
    inputSelectionConfigured: Boolean,
): Set<String> = if (inputSelectionConfigured) outputPeerIds.intersect(inputPeerIds) else emptySet()

/** Directional gates retained for callers/tests; a remote invitation never grants authorization. */
internal fun appleMidiSessionOutputAllowed(
    @Suppress("UNUSED_PARAMETER") initiatedLocally: Boolean,
    selectedForOutput: Boolean,
): Boolean = selectedForOutput

internal fun appleMidiSessionInputAllowed(
    inputSelectionConfigured: Boolean,
    @Suppress("UNUSED_PARAMETER") initiatedLocally: Boolean,
    selectedForInput: Boolean,
): Boolean = inputSelectionConfigured && selectedForInput

internal fun appleMidiDeliveryLookaheadNanos(
    configuration: AppleMidiConfiguration,
    listener: AppleMidiListener,
): Long = if (listener is AppleMidiScheduledListener) {
    configuration.eventDeliveryLookaheadMillis * 1_000_000L
} else {
    0L
}

internal fun appleMidiDeliveryWakeupNanos(
    targetTimeNanos: Long,
    lookaheadNanos: Long,
): Long {
    require(lookaheadNanos >= 0) { "lookaheadNanos must not be negative" }
    return (targetTimeNanos - lookaheadNanos).coerceAtLeast(0L)
}

internal fun appleMidiDeliveryDrainThroughNanos(
    nowNanos: Long,
    lookaheadNanos: Long,
): Long {
    require(lookaheadNanos >= 0) { "lookaheadNanos must not be negative" }
    return if (nowNanos > Long.MAX_VALUE - lookaheadNanos) {
        Long.MAX_VALUE
    } else {
        nowNanos + lookaheadNanos
    }
}

internal fun selectRtpMidiSession(
    sessions: Collection<AppleMidiSession>,
    remote: InetSocketAddress,
    remoteSsrc: Long,
): AppleMidiSession? = sessions.asSequence()
    .filter { session ->
        session.transportAddress.sameNetworkHost(remote.address) &&
            session.remoteDataPort == remote.port &&
            session.remoteSsrc == remoteSsrc
    }
    .maxByOrNull(AppleMidiSession::lastActivityNanos)

/** AppleMIDI CK synchronization is carried on the RTP data (odd) port. */
internal fun selectClockSynchronizationSession(
    sessions: Collection<AppleMidiSession>,
    remote: InetSocketAddress,
    remoteSsrc: Long,
): AppleMidiSession? = sessions.asSequence()
    .filter { session ->
        session.transportAddress.sameNetworkHost(remote.address) &&
            session.remoteDataPort == remote.port &&
            session.remoteSsrc == remoteSsrc &&
            session.state in setOf(
                AppleMidiSessionState.INVITING,
                AppleMidiSessionState.SYNCHRONIZING,
                AppleMidiSessionState.CONNECTED,
            )
    }
    .maxByOrNull(AppleMidiSession::lastActivityNanos)

/**
 * CoreMIDI can replace its RTP data socket while retaining the negotiated
 * SSRC. Only use this fallback when one connected session owns that exact
 * host/SSRC pair, so a stale or unrelated packet cannot claim a session.
 */
internal fun selectRtpMidiSessionByHostAndSsrc(
    sessions: Collection<AppleMidiSession>,
    remote: InetSocketAddress,
    remoteSsrc: Long,
): AppleMidiSession? = sessions.asSequence()
    .filter { session ->
        session.state == AppleMidiSessionState.CONNECTED &&
            session.transportAddress.sameNetworkHost(remote.address) &&
            session.remoteSsrc == remoteSsrc
    }
    .toList()
    .singleOrNull()

internal fun selectReceiverFeedbackSession(
    sessions: Collection<AppleMidiSession>,
    remote: InetSocketAddress,
    remoteSsrc: Long,
): AppleMidiSession? = sessions.asSequence()
    .filter { session ->
        session.transportAddress.sameNetworkHost(remote.address) &&
            session.remoteControlPort == remote.port &&
            session.remoteSsrc == remoteSsrc
    }
    .maxByOrNull(AppleMidiSession::lastActivityNanos)

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
