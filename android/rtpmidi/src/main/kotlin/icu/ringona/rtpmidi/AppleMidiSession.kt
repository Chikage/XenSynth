package icu.ringona.rtpmidi

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.PriorityQueue

internal class AppleMidiSession(
    val id: String,
    var peerId: String?,
    var peerName: String,
    val address: InetAddress,
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
) {
    var controlAccepted: Boolean = false
    var dataAccepted: Boolean = false
    var lastInvitationNanos: Long = 0L
    var invitationAttempts: Int = 0
    var lastClockSyncNanos: Long = 0L
    var clockRequestT1: Long? = null
    var remoteToLocalOffsetTicks: Long? = null
    var incomingRunningStatus: Int? = null
    val sequenceTracker = RtpSequenceTracker()
    val sessionClock = RtpMidiSessionClock(localClock)
    val activeNotes = Array(16) { BooleanArray(128) }
    val sustainDown = BooleanArray(16)
    private val jitterBuffer = RtpMidiJitterBuffer<ByteArray>(
        sessionClock = sessionClock,
        initialDelayNanos = jitterBufferMillis.coerceIn(8, 40) * NANOS_PER_MILLI,
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

    val controlAddress: InetSocketAddress
        get() = InetSocketAddress(address, remoteControlPort)

    val dataAddress: InetSocketAddress
        get() = InetSocketAddress(address, remoteDataPort)

    fun connectsTo(service: ResolvedAppleMidiService): Boolean =
        address == service.host && remoteControlPort == service.controlPort

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

    fun offerMidi(
        remoteTimestamp: Long,
        arrivalNanos: Long,
        message: ByteArray,
    ): Long {
        return jitterBuffer.offer(
            remoteTimestamp = remoteTimestamp,
            arrivalNanos = arrivalNanos,
            value = message.copyOf(),
        ).targetTimeNanos
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

    val jitterBufferStatistics: RtpMidiJitterBufferStatistics
        get() = jitterBuffer.statistics

    /** Earliest playout deadline, including synthetic recovery releases. */
    val nextMidiTargetTimeNanos: Long?
        get() = nextMidiTargetNanos()

    private fun Byte.unsignedMidi(): Int = (toInt() and 0xFF).coerceIn(0, 127)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
