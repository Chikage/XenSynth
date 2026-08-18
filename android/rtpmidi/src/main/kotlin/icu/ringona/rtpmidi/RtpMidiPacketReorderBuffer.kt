package icu.ringona.rtpmidi

import java.util.LinkedHashSet
import java.util.TreeMap

internal data class BufferedRtpMidiPacket(
    val packet: RtpMidiPacket,
    val extendedSequenceNumber: Long,
    val arrivalNanos: Long,
)

internal data class RtpMidiPacketLoss(
    val firstMissingExtendedSequence: Long,
    val missingPackets: Int,
    val recoveryPacket: BufferedRtpMidiPacket,
)

internal data class RtpMidiPacketRelease(
    val packets: List<BufferedRtpMidiPacket>,
    val loss: RtpMidiPacketLoss? = null,
    val duplicate: Boolean = false,
    val late: Boolean = false,
    val reordered: Boolean = false,
    val initialPacket: Boolean = false,
) {
    val duplicateOrLate: Boolean
        get() = duplicate || late
}

/**
 * Small packet reorder buffer that defers declaring RTP loss for one LAN jitter window.
 * Late packets that fill every missing sequence release the held packets without recovery.
 */
internal class RtpMidiPacketReorderBuffer(
    private val gapGraceNanos: Long = DEFAULT_GAP_GRACE_NANOS,
    private val maximumBufferedPackets: Int = DEFAULT_MAXIMUM_BUFFERED_PACKETS,
) {
    private val pending = TreeMap<Long, BufferedRtpMidiPacket>()
    private val recentlySeen = LinkedHashSet<Long>()
    private var expectedExtendedSequence: Long? = null
    private var highestExtendedSequence: Long? = null
    private var gapDeadlineNanos: Long? = null

    init {
        require(gapGraceNanos > 0) { "gapGraceNanos must be positive" }
        require(maximumBufferedPackets > 0) { "maximumBufferedPackets must be positive" }
    }

    fun offer(packet: RtpMidiPacket, arrivalNanos: Long): RtpMidiPacketRelease {
        val expected = expectedExtendedSequence
        if (expected == null) {
            val extended = packet.sequenceNumber.toLong()
            rememberSeen(extended)
            expectedExtendedSequence = extended + 1
            highestExtendedSequence = extended
            return RtpMidiPacketRelease(
                packets = listOf(BufferedRtpMidiPacket(packet, extended, arrivalNanos)),
                initialPacket = true,
            )
        }

        val reference = highestExtendedSequence ?: expected
        val extended = unwrapSequenceNear(packet.sequenceNumber, reference)
        if (pending.containsKey(extended) || extended < expected && extended in recentlySeen) {
            return RtpMidiPacketRelease(emptyList(), duplicate = true)
        }
        if (extended < expected) {
            return RtpMidiPacketRelease(emptyList(), late = true)
        }
        highestExtendedSequence = maxOf(reference, extended)
        rememberSeen(extended)
        pending[extended] = BufferedRtpMidiPacket(packet, extended, arrivalNanos)

        if (pending.size > maximumBufferedPackets) {
            return expireNextGap(arrivalNanos, force = true)
        }
        val released = drainContiguous()
        refreshGapDeadline()
        return RtpMidiPacketRelease(
            packets = released,
            reordered = extended > expected,
        )
    }

    fun expireGap(nowNanos: Long): RtpMidiPacketRelease = expireNextGap(nowNanos, force = false)

    fun reset() {
        pending.clear()
        recentlySeen.clear()
        expectedExtendedSequence = null
        highestExtendedSequence = null
        gapDeadlineNanos = null
    }

    val nextGapDeadlineNanos: Long?
        get() = gapDeadlineNanos

    val bufferedPacketCount: Int
        get() = pending.size

    private fun expireNextGap(nowNanos: Long, force: Boolean): RtpMidiPacketRelease {
        val deadline = gapDeadlineNanos
        if (!force && (deadline == null || nowNanos < deadline)) {
            return RtpMidiPacketRelease(emptyList())
        }
        val expected = expectedExtendedSequence ?: return RtpMidiPacketRelease(emptyList())
        val first = pending.firstEntry()?.value ?: run {
            gapDeadlineNanos = null
            return RtpMidiPacketRelease(emptyList())
        }
        if (first.extendedSequenceNumber <= expected) {
            val released = drainContiguous()
            refreshGapDeadline()
            return RtpMidiPacketRelease(released)
        }
        val distance = first.extendedSequenceNumber - expected
        expectedExtendedSequence = first.extendedSequenceNumber
        val released = drainContiguous()
        refreshGapDeadline()
        return RtpMidiPacketRelease(
            packets = released,
            loss = RtpMidiPacketLoss(
                firstMissingExtendedSequence = expected,
                missingPackets = distance.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                recoveryPacket = first,
            ),
        )
    }

    private fun drainContiguous(): List<BufferedRtpMidiPacket> {
        var expected = expectedExtendedSequence ?: return emptyList()
        val result = ArrayList<BufferedRtpMidiPacket>()
        while (true) {
            val packet = pending.remove(expected) ?: break
            result += packet
            expected++
        }
        expectedExtendedSequence = expected
        return result
    }

    private fun refreshGapDeadline() {
        val expected = expectedExtendedSequence
        val first = pending.firstEntry()?.value
        gapDeadlineNanos = if (expected != null && first != null && first.extendedSequenceNumber > expected) {
            first.arrivalNanos + gapGraceNanos
        } else {
            null
        }
    }

    private fun rememberSeen(extendedSequenceNumber: Long) {
        recentlySeen += extendedSequenceNumber
        while (recentlySeen.size > RECENT_SEQUENCE_HISTORY_SIZE) {
            val oldest = recentlySeen.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    companion object {
        const val DEFAULT_GAP_GRACE_NANOS = 12_000_000L
        const val DEFAULT_MAXIMUM_BUFFERED_PACKETS = 256
        private const val RECENT_SEQUENCE_HISTORY_SIZE = DEFAULT_MAXIMUM_BUFFERED_PACKETS * 2
    }
}
