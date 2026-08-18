package icu.ringona.rtpmidi

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.PriorityQueue

internal class AppleMidiSession(
    val id: String,
    var peerId: String?,
    var peerName: String,
    val advertisedAddress: InetAddress,
    var remoteControlPort: Int,
    var remoteDataPort: Int,
    val initiatorToken: Long,
    val initiatedLocally: Boolean,
    val localSsrc: Long,
    var remoteSsrc: Long?,
    var state: AppleMidiSessionState,
    var createdAtNanos: Long,
    var lastActivityNanos: Long,
    var nextSequence: Int,
    localClock: RtpMidiClock,
    jitterBufferMillis: Long,
    jitterBufferMaximumQueueSize: Int = 6_144,
    var transportAddress: InetAddress = advertisedAddress,
    val advertisedControlPort: Int = remoteControlPort,
) {
    var controlAccepted: Boolean = false
    var dataAccepted: Boolean = false
    var lastInvitationNanos: Long = 0L
    var invitationAttempts: Int = 0
    var lastClockSyncNanos: Long = 0L
    var clockRequestT1: Long? = null
    var remoteToLocalOffsetTicks: Long? = null
    private var nextExtendedSequence = nextSequence.toLong()
    private var pendingReceiverFeedbackSequence: Long? = null
    private var lastReceiverFeedbackNanos: Long? = null
    private val packetReorderBuffer = RtpMidiPacketReorderBuffer()
    private val recoveryJournalSender = RtpMidiRecoveryJournalSender()
    private val recoveryJournalReceiver = RtpMidiRecoveryJournalReceiver()
    val sessionClock = RtpMidiSessionClock(localClock)
    val activeNotes = Array(16) { BooleanArray(128) }
    val sustainDown = BooleanArray(16)
    private val jitterBuffer = RtpMidiJitterBuffer<ByteArray>(
        sessionClock = sessionClock,
        initialDelayNanos = jitterBufferMillis.coerceIn(24, 120) * NANOS_PER_MILLI,
        maximumQueueSize = jitterBufferMaximumQueueSize,
        priorityOf = ::midiDeliveryPriority,
        protectedPriority = MIDI_PRIORITY_CRITICAL_RELEASE,
    )
    private data class RecoveryEvent(
        val targetTimeNanos: Long,
        val insertionOrder: Long,
        val message: ByteArray,
    )

    private val recoveryQueue = PriorityQueue<RecoveryEvent>(
        compareBy<RecoveryEvent> { it.targetTimeNanos }.thenBy { it.insertionOrder },
    )
    private var nextRecoveryInsertionOrder = 0L
    private val deliveryLock = Any()
    private var deliveryClosed = false
    private var incomingPackets = 0L
    private var releasedPackets = 0L
    private var lostPackets = 0L
    private var reorderedPackets = 0L
    private var latePackets = 0L
    private var duplicatePackets = 0L
    private var recoveryAttempts = 0L
    private var recoveryFallbacks = 0L
    private var recoveredMessages = 0L
    private var outgoingPackets = 0L
    private var outgoingSendFailures = 0L
    private var receiverFeedbackSent = 0L
    private var journalHeartbeatsSent = 0L
    private var urgentJournalHeartbeatsSent = 0L

    init {
        require(jitterBufferMaximumQueueSize >= MidiOutputAccumulator.FULL_PANIC_MESSAGE_COUNT) {
            "jitterBufferMaximumQueueSize must hold one complete 16-channel panic"
        }
    }

    val controlAddress: InetSocketAddress
        get() = InetSocketAddress(transportAddress, remoteControlPort)

    val dataAddress: InetSocketAddress
        get() = InetSocketAddress(transportAddress, remoteDataPort)

    fun connectsTo(service: ResolvedAppleMidiService): Boolean =
        advertisedAddress.sameNetworkHost(service.host) &&
            advertisedControlPort == service.controlPort

    fun matchesInvitationResponse(
        remote: InetSocketAddress,
        token: Long,
        responseSsrc: Long,
        dataChannel: Boolean,
    ): Boolean {
        val expectedPort = if (dataChannel) remoteDataPort else remoteControlPort
        if (!initiatedLocally || initiatorToken != token || remote.port != expectedPort) {
            return false
        }
        return if (dataChannel) {
            controlAccepted && !dataAccepted && remoteSsrc == responseSsrc &&
                transportAddress.sameNetworkHost(remote.address)
        } else {
            !controlAccepted
        }
    }

    fun applyInvitationResponse(
        packet: AppleMidiControlPacket.Invitation,
        remote: InetSocketAddress,
        dataChannel: Boolean,
        nowNanos: Long,
    ) {
        require(packet.command == AppleMidiInvitationCommand.OK) {
            "Only accepted invitations can advance a session"
        }
        check(
            matchesInvitationResponse(
                remote = remote,
                token = packet.initiatorToken,
                responseSsrc = packet.ssrc,
                dataChannel = dataChannel,
            ),
        ) { "Invitation response no longer matches this session" }
        transportAddress = remote.address
        remoteSsrc = packet.ssrc
        peerName = packet.name.ifBlank { peerName }
        lastActivityNanos = nowNanos
        if (dataChannel) {
            remoteDataPort = remote.port
            dataAccepted = true
            state = AppleMidiSessionState.SYNCHRONIZING
        } else {
            remoteControlPort = remote.port
            controlAccepted = true
        }
    }

    fun observeMidi(message: ByteArray) {
        if (message.isEmpty()) return
        val status = message[0].toInt() and 0xFF
        val channel = status and 0x0F
        when (status and 0xF0) {
            0x80 -> message.getOrNull(1)?.unsignedMidi()?.let { activeNotes[channel][it] = false }
            0x90 -> {
                val note = message.getOrNull(1)?.unsignedMidi() ?: return
                val velocity = message.getOrNull(2)?.unsignedMidi() ?: return
                activeNotes[channel][note] = velocity != 0
            }
            0xB0 -> {
                val controller = message.getOrNull(1)?.unsignedMidi() ?: return
                val value = message.getOrNull(2)?.unsignedMidi() ?: return
                when (controller) {
                    64 -> sustainDown[channel] = value >= 64
                    120, 123 -> activeNotes[channel].fill(false)
                }
            }
        }
    }

    fun takeNextOutgoingSequence(): Long {
        val extended = nextExtendedSequence
        nextExtendedSequence++
        nextSequence = (nextExtendedSequence and 0xFFFF).toInt()
        recoveryJournalSender.reservePacketSequence(extended)
        return extended
    }

    val nextOutgoingExtendedSequence: Long
        get() = nextExtendedSequence

    fun recoveryJournalForPacket(packetExtendedSequence: Long): ByteArray? =
        recoveryJournalSender.journalForPacket(packetExtendedSequence)

    fun recordOutgoingPacket(
        packetExtendedSequence: Long,
        messages: List<MidiChannelMessage>,
        sentAtNanos: Long,
    ) {
        recoveryJournalSender.recordPacket(packetExtendedSequence, messages, sentAtNanos)
        outgoingPackets++
    }

    fun recordOutgoingSendFailure() {
        outgoingSendFailures++
    }

    fun acknowledgeOutgoingSequence(sequenceNumber: Int): Long? =
        recoveryJournalSender.acknowledge(sequenceNumber)

    fun resetOutgoingRecoveryForPanic(nextPacketExtendedSequence: Long) {
        recoveryJournalSender.resetHistoryForPanic(nextPacketExtendedSequence)
    }

    fun journalHeartbeatDue(nowNanos: Long, intervalNanos: Long): Boolean =
        recoveryJournalSender.heartbeatDue(nowNanos, intervalNanos)

    val hasUnacknowledgedJournal: Boolean
        get() = recoveryJournalSender.hasUnacknowledgedState

    val hasUnacknowledgedCriticalReleaseJournal: Boolean
        get() = recoveryJournalSender.hasUnacknowledgedCriticalRelease

    fun offerRtpPacket(packet: RtpMidiPacket, arrivalNanos: Long): RtpMidiPacketRelease {
        incomingPackets++
        return packetReorderBuffer.offer(packet, arrivalNanos).also(::recordPacketRelease)
    }

    fun queueReceiverFeedback(extendedSequenceNumber: Long) {
        require(extendedSequenceNumber >= 0) { "extendedSequenceNumber must not be negative" }
        val pending = pendingReceiverFeedbackSequence
        if (pending == null || extendedSequenceNumber > pending) {
            pendingReceiverFeedbackSequence = extendedSequenceNumber
        }
    }

    @JvmOverloads
    fun takeReceiverFeedback(
        nowNanos: Long = System.nanoTime(),
        minimumIntervalNanos: Long = 0L,
    ): Int? {
        require(minimumIntervalNanos >= 0L) { "minimumIntervalNanos must not be negative" }
        val pending = pendingReceiverFeedbackSequence ?: return null
        if (lastReceiverFeedbackNanos?.let { nowNanos - it < minimumIntervalNanos } == true) {
            return null
        }
        pendingReceiverFeedbackSequence = null
        lastReceiverFeedbackNanos = nowNanos
        return (pending and 0xFFFF).toInt()
    }

    fun expireRtpGap(nowNanos: Long): RtpMidiPacketRelease =
        packetReorderBuffer.expireGap(nowNanos).also(::recordPacketRelease)

    val nextRtpGapDeadlineNanos: Long?
        get() = packetReorderBuffer.nextGapDeadlineNanos

    fun recoverFromPacketLoss(loss: RtpMidiPacketLoss): RtpMidiRecoveryResult =
        recoveryJournalReceiver.recover(
            journalBytes = loss.recoveryPacket.packet.journal,
            missingExtendedSequence = loss.firstMissingExtendedSequence,
            journalPacketExtendedSequence = loss.recoveryPacket.extendedSequenceNumber,
        ).also(::recordRecovery)

    fun recoverFromInitialPacket(packet: BufferedRtpMidiPacket): RtpMidiRecoveryResult {
        val checkpoint = RtpMidiRecoveryJournalCodec.decodeOrNull(packet.packet.journal)
            ?.checkpointSequenceNumber
            ?.let { unwrapSequenceNear(it, packet.extendedSequenceNumber) }
            ?: packet.extendedSequenceNumber
        return recoveryJournalReceiver.recover(
            journalBytes = packet.packet.journal,
            missingExtendedSequence = checkpoint,
            journalPacketExtendedSequence = packet.extendedSequenceNumber,
        ).also(::recordRecovery)
    }

    fun observeOrderedNetworkMidi(messages: List<MidiChannelMessage>) {
        recoveryJournalReceiver.observe(messages)
    }

    fun offerMidi(
        remoteTimestamp: Long,
        arrivalNanos: Long,
        message: ByteArray,
    ): Long {
        val safeMessage = message.copyOf()
        val result = jitterBuffer.offerWithResult(
            remoteTimestamp = remoteTimestamp,
            arrivalNanos = arrivalNanos,
            value = safeMessage,
        )
        if (!result.accepted && isCriticalMidiRelease(safeMessage)) {
            queueJitterOverflowPanic(remoteTimestamp, arrivalNanos)
        }
        return result.scheduled.targetTimeNanos
    }

    private fun queueJitterOverflowPanic(remoteTimestamp: Long, arrivalNanos: Long) {
        jitterBuffer.clearQueuedValuesPreservingTiming()
        recoveryQueue.clear()
        recoveryJournalReceiver.forceReleasedState()
        MidiOutputAccumulator.fullPanic(arrivalNanos).forEach { panic ->
            check(
                jitterBuffer.offerWithResult(
                    remoteTimestamp = remoteTimestamp,
                    arrivalNanos = arrivalNanos,
                    value = panic.bytes,
                ).accepted,
            ) { "A cleared jitter buffer must hold one complete panic" }
        }
    }

    fun nextMidiTargetNanos(): Long? {
        val recoveryTarget = recoveryQueue.peek()?.targetTimeNanos
        val jitterTarget = jitterBuffer.nextTargetTimeNanos()
        return when {
            recoveryTarget == null -> jitterTarget
            jitterTarget == null -> recoveryTarget
            else -> minOf(recoveryTarget, jitterTarget)
        }
    }

    fun enqueueRecovery(targetTimeNanos: Long, messages: List<ByteArray>) {
        messages.forEach { message ->
            if (message.isNotEmpty()) {
                recoveryQueue += RecoveryEvent(
                    targetTimeNanos = targetTimeNanos,
                    insertionOrder = nextRecoveryInsertionOrder++,
                    message = message.copyOf(),
                )
            }
        }
    }

    /** Returns note-offs and sustain release messages after a detected RTP gap. */
    fun resetActiveStateForRecovery(): List<ByteArray> {
        val messages = ArrayList<ByteArray>()
        activeNotes.forEachIndexed { channel, notes ->
            notes.forEachIndexed { note, active ->
                if (active) {
                    messages += byteArrayOf((0x80 or channel).toByte(), note.toByte(), 0)
                }
            }
            if (sustainDown[channel]) {
                messages += byteArrayOf((0xB0 or channel).toByte(), 64, 0)
            }
            notes.fill(false)
            sustainDown[channel] = false
        }
        return messages
    }

    fun drainMidi(nowNanos: Long): List<AppleMidiEvent> {
        val events = ArrayList<AppleMidiEvent>()
        while (recoveryQueue.peek()?.targetTimeNanos?.let { it <= nowNanos } == true) {
            val recovery = recoveryQueue.remove()
            events += AppleMidiEvent(recovery.message, recovery.targetTimeNanos, id)
        }
        jitterBuffer.drainReady(nowNanos).mapTo(events) { queued ->
            AppleMidiEvent(queued.value, queued.targetTimeNanos, id)
        }
        return events.sortedBy { it.targetTimeNanos }
    }

    fun deliverIfOpen(delivery: () -> Unit): Boolean = synchronized(deliveryLock) {
        if (deliveryClosed) return@synchronized false
        delivery()
        true
    }

    fun closeDelivery(closeAction: () -> Unit): Boolean = synchronized(deliveryLock) {
        if (deliveryClosed) return@synchronized false
        deliveryClosed = true
        closeAction()
        true
    }

    val jitterBufferStatistics: RtpMidiJitterBufferStatistics
        get() = jitterBuffer.statistics

    fun recordReceiverFeedbackSent() {
        receiverFeedbackSent++
    }

    fun recordJournalHeartbeatSent(urgent: Boolean) {
        journalHeartbeatsSent++
        if (urgent) urgentJournalHeartbeatsSent++
    }

    val statistics: AppleMidiSessionStatistics
        get() = AppleMidiSessionStatistics(
            sessionId = id,
            peerId = peerId,
            peerName = peerName,
            incomingPackets = incomingPackets,
            releasedPackets = releasedPackets,
            lostPackets = lostPackets,
            reorderedPackets = reorderedPackets,
            latePackets = latePackets,
            duplicatePackets = duplicatePackets,
            recoveryAttempts = recoveryAttempts,
            recoveryFallbacks = recoveryFallbacks,
            recoveredMessages = recoveredMessages,
            outgoingPackets = outgoingPackets,
            outgoingSendFailures = outgoingSendFailures,
            receiverFeedbackSent = receiverFeedbackSent,
            journalHeartbeatsSent = journalHeartbeatsSent,
            urgentJournalHeartbeatsSent = urgentJournalHeartbeatsSent,
            jitterBuffer = jitterBuffer.statistics,
            jitterBufferDelayNanos = jitterBuffer.playoutDelayNanos,
            estimatedNetworkJitterNanos = jitterBuffer.estimatedJitterNanos,
        )

    /** Earliest playout deadline, including synthetic recovery releases. */
    val nextMidiTargetTimeNanos: Long?
        get() = nextMidiTargetNanos()

    private fun recordPacketRelease(release: RtpMidiPacketRelease) {
        releasedPackets += release.packets.size
        lostPackets += release.loss?.missingPackets ?: 0
        if (release.reordered) reorderedPackets++
        if (release.late) latePackets++
        if (release.duplicate) duplicatePackets++
    }

    private fun recordRecovery(result: RtpMidiRecoveryResult) {
        recoveryAttempts++
        if (!result.journalApplied) recoveryFallbacks++
        recoveredMessages += result.messages.size
    }

    private fun Byte.unsignedMidi(): Int = (toInt() and 0xFF).coerceIn(0, 127)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
