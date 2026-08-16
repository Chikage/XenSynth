package icu.ringona.rtpmidi

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToLong

/** Monotonic 10 kHz clock used by AppleMIDI CK and RTP timestamps. */
class RtpMidiClock(
    initialTimestampTicks: Long = 0L,
    private val nanoTimeSource: () -> Long = System::nanoTime,
) {
    private val originNanos = nanoTimeSource()
    private val originTimestampTicks = initialTimestampTicks

    init {
        require(initialTimestampTicks >= 0) { "initialTimestampTicks must not be negative" }
    }

    fun nowNanos(): Long = nanoTimeSource()

    fun nowTicks(): Long = timestampTicksForNanos(nowNanos())

    fun nowTimestamp32(): Long = nowTicks() and UINT32_MASK

    fun timestampTicksForNanos(monotonicNanos: Long): Long =
        originTimestampTicks + nanosToTicks(monotonicNanos - originNanos)

    fun monotonicNanosForTimestamp(timestampTicks: Long): Long =
        originNanos + ticksToNanos(timestampTicks - originTimestampTicks)

    companion object {
        const val TICKS_PER_SECOND: Long = 10_000L
        const val NANOS_PER_TICK: Long = 100_000L

        fun nanosToTicks(nanos: Long): Long = nanos / NANOS_PER_TICK

        fun ticksToNanos(ticks: Long): Long = Math.multiplyExact(ticks, NANOS_PER_TICK)
    }
}

/** One NTP-style estimate derived from an AppleMIDI CK0/CK1/CK2 exchange. */
data class ClockSynchronizationSample(
    /** Add this value to a remote timestamp to express it on the local 10 kHz clock. */
    val remoteToLocalOffsetTicks: Long,
    val roundTripTicks: Long,
    val remoteReferenceTicks: Long,
) {
    val estimatedOneWayDelayTicks: Long
        get() = roundTripTicks / 2

    companion object {
        /** t1 and t3 are local initiator timestamps; t2 is the responder's remote timestamp. */
        fun forInitiator(t1Local: Long, t2Remote: Long, t3Local: Long): ClockSynchronizationSample {
            require(t3Local >= t1Local) { "CK3 local timestamp precedes CK1" }
            val localMidpoint = midpoint(t1Local, t3Local)
            return ClockSynchronizationSample(
                remoteToLocalOffsetTicks = localMidpoint - t2Remote,
                roundTripTicks = t3Local - t1Local,
                remoteReferenceTicks = t2Remote,
            )
        }

        /** t1 and t3 are remote initiator timestamps; t2 is this responder's local timestamp. */
        fun forResponder(t1Remote: Long, t2Local: Long, t3Remote: Long): ClockSynchronizationSample {
            require(t3Remote >= t1Remote) { "CK3 remote timestamp precedes CK1" }
            val remoteMidpoint = midpoint(t1Remote, t3Remote)
            return ClockSynchronizationSample(
                remoteToLocalOffsetTicks = t2Local - remoteMidpoint,
                roundTripTicks = t3Remote - t1Remote,
                remoteReferenceTicks = remoteMidpoint,
            )
        }

        private fun midpoint(first: Long, second: Long): Long = first + (second - first) / 2
    }
}

/** Expands wrapping 32-bit RTP timestamps onto a stable 64-bit timeline. */
class RtpTimestampUnwrapper {
    private var highestTimestamp: Long? = null

    @Synchronized
    fun unwrap(timestamp32: Long, referenceTimestamp: Long? = null): Long {
        requireUInt32Timestamp(timestamp32)
        val reference = highestTimestamp ?: referenceTimestamp
        val expanded = if (reference == null) {
            timestamp32
        } else {
            unwrapNear(timestamp32, reference)
        }
        val highest = highestTimestamp
        if (highest == null || expanded > highest) highestTimestamp = expanded
        return expanded
    }

    @Synchronized
    fun anchor(timestamp: Long) {
        highestTimestamp = timestamp
    }

    @Synchronized
    fun reset() {
        highestTimestamp = null
    }

    companion object {
        private const val MODULUS = 0x1_0000_0000L
        private const val HALF_MODULUS = 0x8000_0000L

        fun unwrapNear(timestamp32: Long, referenceTimestamp: Long): Long {
            requireUInt32Timestamp(timestamp32)
            val base = referenceTimestamp and UINT32_MASK.inv()
            var candidate = base or timestamp32
            val difference = candidate - referenceTimestamp
            if (difference > HALF_MODULUS) candidate -= MODULUS
            if (difference < -HALF_MODULUS) candidate += MODULUS
            return candidate
        }
    }
}

/** Maps a peer's wrapping RTP timestamps to this process's monotonic clock. */
class RtpMidiSessionClock(
    val localClock: RtpMidiClock = RtpMidiClock(),
    private val synchronizationSmoothing: Double = 0.125,
) {
    private val timestampUnwrapper = RtpTimestampUnwrapper()
    private var synchronizedOffsetTicks: Long? = null
    private var fallbackOffsetTicks: Long? = null
    private var remoteReferenceTicks: Long? = null
    private var latestRoundTripTicks: Long? = null

    init {
        require(synchronizationSmoothing in 0.0..1.0 && synchronizationSmoothing > 0.0) {
            "synchronizationSmoothing must be in (0, 1]"
        }
    }

    val isSynchronized: Boolean
        @Synchronized get() = synchronizedOffsetTicks != null

    val remoteToLocalOffsetTicks: Long?
        @Synchronized get() = synchronizedOffsetTicks

    val roundTripTicks: Long?
        @Synchronized get() = latestRoundTripTicks

    @Synchronized
    fun updateFromInitiatorExchange(
        t1Local: Long,
        t2Remote: Long,
        t3Local: Long,
    ): ClockSynchronizationSample = applySynchronization(
        ClockSynchronizationSample.forInitiator(t1Local, t2Remote, t3Local),
    )

    @Synchronized
    fun updateFromResponderExchange(
        t1Remote: Long,
        t2Local: Long,
        t3Remote: Long,
    ): ClockSynchronizationSample = applySynchronization(
        ClockSynchronizationSample.forResponder(t1Remote, t2Local, t3Remote),
    )

    @Synchronized
    fun applySynchronization(sample: ClockSynchronizationSample): ClockSynchronizationSample {
        val previous = synchronizedOffsetTicks
        synchronizedOffsetTicks = if (previous == null) {
            sample.remoteToLocalOffsetTicks
        } else {
            previous +
                ((sample.remoteToLocalOffsetTicks - previous) * synchronizationSmoothing).roundToLong()
        }
        latestRoundTripTicks = sample.roundTripTicks
        remoteReferenceTicks = sample.remoteReferenceTicks
        // CK carries a full 64-bit timestamp, so it resolves ambiguity after a long RTP silence.
        timestampUnwrapper.anchor(sample.remoteReferenceTicks)
        return sample
    }

    /**
     * Converts a peer RTP timestamp into local monotonic nanoseconds. Before CK synchronization,
     * the first packet is conservatively anchored to its arrival time.
     */
    @Synchronized
    fun localNanosForRemoteTimestamp(timestamp32: Long, arrivalNanos: Long): Long {
        requireUInt32Timestamp(timestamp32)
        val arrivalTicks = localClock.timestampTicksForNanos(arrivalNanos)
        val offset = synchronizedOffsetTicks
        val reference = remoteReferenceTicks ?: offset?.let { arrivalTicks - it }
        val expandedRemote = timestampUnwrapper.unwrap(timestamp32, reference)
        val effectiveOffset = offset ?: fallbackOffsetTicks ?: (arrivalTicks - expandedRemote).also {
            fallbackOffsetTicks = it
            remoteReferenceTicks = expandedRemote
        }
        return localClock.monotonicNanosForTimestamp(expandedRemote + effectiveOffset)
    }

    @Synchronized
    fun reset() {
        synchronizedOffsetTicks = null
        fallbackOffsetTicks = null
        remoteReferenceTicks = null
        latestRoundTripTicks = null
        timestampUnwrapper.reset()
    }
}

enum class RtpSequenceDisposition {
    FIRST,
    IN_ORDER,
    GAP,
    LATE,
    DUPLICATE,
}

data class RtpSequenceObservation(
    val extendedSequenceNumber: Long,
    val disposition: RtpSequenceDisposition,
    val missingPackets: Int = 0,
)

/** Sequence-number wrap, gap, duplicate, and bounded reordering tracker. */
class RtpSequenceTracker(private val historyWindow: Int = 128) {
    private var highestSequence: Long? = null
    private val seen = HashSet<Long>()

    init {
        require(historyWindow > 0) { "historyWindow must be positive" }
    }

    @Synchronized
    fun observe(sequenceNumber: Int): RtpSequenceObservation {
        require(sequenceNumber in 0..0xFFFF) { "sequenceNumber must fit an unsigned 16-bit value" }
        val highest = highestSequence
        if (highest == null) {
            val extended = sequenceNumber.toLong()
            highestSequence = extended
            seen += extended
            return RtpSequenceObservation(extended, RtpSequenceDisposition.FIRST)
        }

        val extended = unwrapSequenceNear(sequenceNumber, highest)
        if (!seen.add(extended)) {
            return RtpSequenceObservation(extended, RtpSequenceDisposition.DUPLICATE)
        }
        val result = if (extended > highest) {
            val distance = extended - highest
            highestSequence = extended
            if (distance == 1L) {
                RtpSequenceObservation(extended, RtpSequenceDisposition.IN_ORDER)
            } else {
                RtpSequenceObservation(
                    extended,
                    RtpSequenceDisposition.GAP,
                    (distance - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
        } else {
            RtpSequenceObservation(extended, RtpSequenceDisposition.LATE)
        }
        val cutoff = (highestSequence ?: highest) - historyWindow
        seen.removeAll { it < cutoff }
        return result
    }

    @Synchronized
    fun reset() {
        highestSequence = null
        seen.clear()
    }

    private fun unwrapSequenceNear(sequenceNumber: Int, reference: Long): Long {
        val base = reference and 0xFFFFL.inv()
        var candidate = base or sequenceNumber.toLong()
        val difference = candidate - reference
        if (difference > 0x8000L) candidate -= 0x1_0000L
        if (difference < -0x8000L) candidate += 0x1_0000L
        return candidate
    }
}

data class ScheduledRtpMidiValue<T>(
    val value: T,
    val targetTimeNanos: Long,
    val remoteTimestamp: Long,
)

/** Observable queue health for one session's adaptive jitter buffer. */
data class RtpMidiJitterBufferStatistics(
    val queuedValues: Int,
    val peakQueueSize: Int,
    val offeredValues: Long,
    val playedValues: Long,
    val droppedLateValues: Long,
    val droppedOverflowValues: Long,
)

/**
 * Timestamp-ordered adaptive jitter buffer. It starts at 60 ms by default and converges within
 * a 24-120 ms window using the RFC 3550 inter-arrival jitter estimator.
 */
class RtpMidiJitterBuffer<T>(
    private val sessionClock: RtpMidiSessionClock,
    initialDelayNanos: Long = 60_000_000L,
    private val minimumDelayNanos: Long = 24_000_000L,
    private val maximumDelayNanos: Long = 120_000_000L,
    private val maximumQueueSize: Int = 6_144,
) {
    private data class Queued<T>(
        val scheduled: ScheduledRtpMidiValue<T>,
        val insertionOrder: Long,
    )

    private val queue = PriorityQueue<Queued<T>>(
        compareBy<Queued<T>> { it.scheduled.targetTimeNanos }.thenBy { it.insertionOrder },
    )
    private val initialDelayNanos = initialDelayNanos
    private var currentDelayNanos = initialDelayNanos
    private var jitterEstimateNanos = 0.0
    private var previousTransitNanos: Long? = null
    private var previousArrivalNanos: Long? = null
    private var previousBaseTargetNanos: Long? = null
    private var previousScheduledTargetNanos: Long? = null
    private var lastPlayedTargetNanos: Long? = null
    private var nextInsertionOrder = 0L
    private var droppedEventCount = 0L
    private var peakQueueSize = 0
    private var offeredValues = 0L
    private var playedValues = 0L
    private var droppedLateValues = 0L
    private var droppedOverflowValues = 0L

    init {
        require(minimumDelayNanos >= 0) { "minimumDelayNanos must not be negative" }
        require(maximumDelayNanos >= minimumDelayNanos) {
            "maximumDelayNanos must be at least minimumDelayNanos"
        }
        require(initialDelayNanos in minimumDelayNanos..maximumDelayNanos) {
            "initialDelayNanos must be within the configured delay range"
        }
        require(maximumQueueSize > 0) { "maximumQueueSize must be positive" }
    }

    val size: Int
        @Synchronized get() = queue.size

    val playoutDelayNanos: Long
        @Synchronized get() = currentDelayNanos

    val estimatedJitterNanos: Long
        @Synchronized get() = jitterEstimateNanos.roundToLong()

    /** Number of events rejected after the bounded queue reached its safety limit. */
    val droppedEvents: Long
        @Synchronized get() = droppedEventCount

    val statistics: RtpMidiJitterBufferStatistics
        @Synchronized get() = RtpMidiJitterBufferStatistics(
            queuedValues = queue.size,
            peakQueueSize = peakQueueSize,
            offeredValues = offeredValues,
            playedValues = playedValues,
            droppedLateValues = droppedLateValues,
            droppedOverflowValues = droppedOverflowValues,
        )

    @Synchronized
    fun offer(
        remoteTimestamp: Long,
        arrivalNanos: Long,
        value: T,
    ): ScheduledRtpMidiValue<T> {
        offeredValues++
        requireUInt32Timestamp(remoteTimestamp)
        val baseTargetNanos = sessionClock.localNanosForRemoteTimestamp(
            remoteTimestamp,
            arrivalNanos,
        )
        val precedingBaseTarget = previousBaseTargetNanos
        val isChronological = precedingBaseTarget == null || baseTargetNanos >= precedingBaseTarget
        if (isChronological && arrivalNanos != previousArrivalNanos) {
            updateJitter(arrivalNanos - baseTargetNanos)
            previousArrivalNanos = arrivalNanos
        }
        var targetTimeNanos = baseTargetNanos + currentDelayNanos
        val precedingScheduledTarget = previousScheduledTargetNanos
        if (isChronological && precedingScheduledTarget != null) {
            targetTimeNanos = maxOf(targetTimeNanos, precedingScheduledTarget)
        }
        val scheduled = ScheduledRtpMidiValue(
            value = value,
            targetTimeNanos = targetTimeNanos,
            remoteTimestamp = remoteTimestamp,
        )
        if (isChronological) {
            previousBaseTargetNanos = baseTargetNanos
            previousScheduledTargetNanos = targetTimeNanos
        }
        if (lastPlayedTargetNanos?.let { targetTimeNanos <= it } == true) {
            droppedLateValues++
            droppedEventCount++
            return scheduled
        }
        if (queue.size >= maximumQueueSize) {
            val latest = queue.maxByOrNull { it.scheduled.targetTimeNanos }
            if (latest == null || latest.scheduled.targetTimeNanos <= targetTimeNanos) {
                droppedEventCount++
                droppedOverflowValues++
                return scheduled
            }
            queue.remove(latest)
            droppedEventCount++
            droppedOverflowValues++
        }
        queue += Queued(scheduled, nextInsertionOrder++)
        peakQueueSize = maxOf(peakQueueSize, queue.size)
        return scheduled
    }

    @Synchronized
    fun pollReady(nowNanos: Long): ScheduledRtpMidiValue<T>? {
        val first = queue.peek() ?: return null
        return if (first.scheduled.targetTimeNanos <= nowNanos) {
            queue.remove().scheduled.also {
                lastPlayedTargetNanos = maxOf(
                    lastPlayedTargetNanos ?: Long.MIN_VALUE,
                    it.targetTimeNanos,
                )
                playedValues++
            }
        } else {
            null
        }
    }

    @Synchronized
    fun drainReady(nowNanos: Long, limit: Int = Int.MAX_VALUE): List<ScheduledRtpMidiValue<T>> {
        require(limit >= 0) { "limit must not be negative" }
        if (limit == 0 || queue.isEmpty()) return emptyList()
        val result = ArrayList<ScheduledRtpMidiValue<T>>(minOf(queue.size, limit))
        while (result.size < limit) {
            val ready = pollReady(nowNanos) ?: break
            result += ready
        }
        return result
    }

    @Synchronized
    fun nextTargetTimeNanos(): Long? = queue.peek()?.scheduled?.targetTimeNanos

    @Synchronized
    fun clear(resetAdaptation: Boolean = true) {
        queue.clear()
        if (resetAdaptation) {
            currentDelayNanos = initialDelayNanos
            jitterEstimateNanos = 0.0
            previousTransitNanos = null
            previousArrivalNanos = null
            previousBaseTargetNanos = null
            previousScheduledTargetNanos = null
        }
        lastPlayedTargetNanos = null
    }

    private fun updateJitter(transitNanos: Long) {
        previousTransitNanos?.let { previous ->
            val variation = abs(transitNanos.toDouble() - previous.toDouble())
            // React quickly to worsening Wi-Fi, but decay slowly so a brief
            // quiet interval cannot make note timing oscillate.
            val smoothing = if (variation > jitterEstimateNanos) 0.25 else 1.0 / 32.0
            jitterEstimateNanos += (variation - jitterEstimateNanos) * smoothing
            val desired = (minimumDelayNanos + 4.0 * jitterEstimateNanos)
                .roundToLong()
                .coerceIn(minimumDelayNanos, maximumDelayNanos)
            currentDelayNanos = if (desired > currentDelayNanos) {
                desired
            } else {
                (currentDelayNanos + (desired - currentDelayNanos) / 16L)
                    .coerceIn(minimumDelayNanos, maximumDelayNanos)
            }
        }
        previousTransitNanos = transitNanos
    }
}

private const val UINT32_MASK = 0xFFFF_FFFFL

private fun requireUInt32Timestamp(timestamp: Long) {
    require(timestamp in 0..UINT32_MASK) { "RTP timestamp must fit an unsigned 32-bit value" }
}
