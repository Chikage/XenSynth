package icu.ringona.rtpmidi

/** One MIDI message retained by [MidiOutputAccumulator] in musical arrival order. */
internal data class PendingMidiOutput(
    val timestampNanos: Long,
    val bytes: ByteArray,
    val insertionOrder: Long,
)

internal data class MidiOutputDrain(
    val messages: List<PendingMidiOutput>,
    val batchWindowNanos: Long,
)

internal data class MidiOutputOfferResult(
    val queuedMessages: Int,
    val batchWindowNanos: Long,
    val flushImmediately: Boolean,
)

internal data class MidiOutputAccumulatorStatistics(
    val queuedMessages: Int,
    val coalescedMessages: Long,
    val evictedContinuousMessages: Long,
    val droppedContinuousMessages: Long,
    val evictedNonCriticalMessages: Long,
    val droppedNonCriticalMessages: Long,
    val panicCount: Long,
)

/**
 * Bounded output queue for the short RTP-MIDI batching window.
 *
 * Priority only decides which values may be coalesced or evicted. Retained messages are emitted
 * in timestamp/insertion order, so a high-priority Note Off can never jump ahead of its Note On.
 */
internal class MidiOutputAccumulator(
    private val maximumSize: Int = DEFAULT_MAXIMUM_SIZE,
    private val congestionThreshold: Int = DEFAULT_CONGESTION_THRESHOLD,
    private val normalBatchWindowNanos: Long = NORMAL_BATCH_WINDOW_NANOS,
    private val congestedBatchWindowNanos: Long = CONGESTED_BATCH_WINDOW_NANOS,
) {
    private data class CoalescingKey(
        val messageType: Int,
        val channel: Int,
        val index: Int,
    )

    private val messages = ArrayList<PendingMidiOutput>(maximumSize)
    private var nextInsertionOrder = 0L
    private var firstOfferNanos: Long? = null
    private var offersSinceDrain = 0
    private var congested = false
    private var panicPending = false
    private var coalescedMessages = 0L
    private var evictedContinuousMessages = 0L
    private var droppedContinuousMessages = 0L
    private var evictedNonCriticalMessages = 0L
    private var droppedNonCriticalMessages = 0L
    private var panicCount = 0L

    init {
        require(maximumSize >= FULL_PANIC_MESSAGE_COUNT) {
            "maximumSize must hold one complete 16-channel panic"
        }
        require(congestionThreshold in 1..maximumSize) {
            "congestionThreshold must be within the queue capacity"
        }
        require(normalBatchWindowNanos > 0L) { "normalBatchWindowNanos must be positive" }
        require(congestedBatchWindowNanos >= normalBatchWindowNanos) {
            "congestedBatchWindowNanos must not be shorter than the normal window"
        }
    }

    fun offer(
        incoming: List<ByteArray>,
        timestampNanos: Long,
        nowNanos: Long,
    ): MidiOutputOfferResult {
        if (incoming.isEmpty()) {
            return MidiOutputOfferResult(size, currentBatchWindowNanos(), false)
        }
        if (messages.isEmpty()) firstOfferNanos = nowNanos
        var panicTriggered = panicPending
        incoming.forEach { source ->
            val bytes = source.copyOf()
            offersSinceDrain++
            val key = coalescingKey(bytes)
            if (key != null) {
                val existingIndex = messages.indexOfFirst { coalescingKey(it.bytes) == key }
                if (existingIndex >= 0) {
                    messages.removeAt(existingIndex)
                    coalescedMessages++
                }
            }

            if (messages.size >= maximumSize) {
                val evictableIndex = messages.indexOfFirst { coalescingKey(it.bytes) != null }
                if (evictableIndex >= 0) {
                    messages.removeAt(evictableIndex)
                    evictedContinuousMessages++
                    congested = true
                } else if (key != null) {
                    droppedContinuousMessages++
                    congested = true
                    return@forEach
                } else if (isCriticalMidiRelease(bytes)) {
                    val nonCriticalIndex = messages.indexOfFirst { !isCriticalMidiRelease(it.bytes) }
                    if (nonCriticalIndex >= 0) {
                        messages.removeAt(nonCriticalIndex)
                        evictedNonCriticalMessages++
                        congested = true
                    } else {
                        replaceWithFullPanic(timestampNanos, nowNanos)
                        panicTriggered = true
                        return@forEach
                    }
                } else {
                    droppedNonCriticalMessages++
                    congested = true
                    return@forEach
                }
            }

            messages += PendingMidiOutput(timestampNanos, bytes, nextInsertionOrder++)
            if (messages.size >= congestionThreshold || offersSinceDrain >= congestionThreshold) {
                congested = true
            }
        }
        return MidiOutputOfferResult(
            queuedMessages = messages.size,
            batchWindowNanos = currentBatchWindowNanos(),
            flushImmediately = panicTriggered,
        )
    }

    fun nextFlushDeadlineNanos(): Long? = firstOfferNanos?.let { first ->
        first + currentBatchWindowNanos()
    }

    fun drain(): MidiOutputDrain {
        val window = currentBatchWindowNanos()
        val drained = messages.sortedWith(
            compareBy<PendingMidiOutput> { it.timestampNanos }.thenBy { it.insertionOrder },
        )
        messages.clear()
        firstOfferNanos = null
        offersSinceDrain = 0
        congested = false
        panicPending = false
        return MidiOutputDrain(drained, window)
    }

    fun clear() {
        messages.clear()
        firstOfferNanos = null
        offersSinceDrain = 0
        congested = false
        panicPending = false
    }

    val size: Int
        get() = messages.size

    val statistics: MidiOutputAccumulatorStatistics
        get() = MidiOutputAccumulatorStatistics(
            queuedMessages = messages.size,
            coalescedMessages = coalescedMessages,
            evictedContinuousMessages = evictedContinuousMessages,
            droppedContinuousMessages = droppedContinuousMessages,
            evictedNonCriticalMessages = evictedNonCriticalMessages,
            droppedNonCriticalMessages = droppedNonCriticalMessages,
            panicCount = panicCount,
        )

    private fun currentBatchWindowNanos(): Long =
        if (congested) congestedBatchWindowNanos else normalBatchWindowNanos

    private fun replaceWithFullPanic(timestampNanos: Long, nowNanos: Long) {
        messages.clear()
        firstOfferNanos = nowNanos
        panicCount++
        congested = true
        panicPending = true
        for (channel in 0 until MIDI_CHANNEL_COUNT) {
            messages += PendingMidiOutput(
                timestampNanos,
                byteArrayOf((0xB0 or channel).toByte(), SUSTAIN_CONTROLLER.toByte(), 0),
                nextInsertionOrder++,
            )
            messages += PendingMidiOutput(
                timestampNanos,
                byteArrayOf((0xB0 or channel).toByte(), ALL_SOUND_OFF_CONTROLLER.toByte(), 0),
                nextInsertionOrder++,
            )
            messages += PendingMidiOutput(
                timestampNanos,
                byteArrayOf((0xB0 or channel).toByte(), ALL_NOTES_OFF_CONTROLLER.toByte(), 0),
                nextInsertionOrder++,
            )
        }
    }

    private fun coalescingKey(bytes: ByteArray): CoalescingKey? {
        if (bytes.isEmpty()) return null
        val status = bytes[0].toInt() and 0xFF
        val channel = status and 0x0F
        return when (val messageType = status and 0xF0) {
            0xA0 -> CoalescingKey(messageType, channel, bytes.getOrNull(1)?.unsigned() ?: return null)
            0xD0, 0xE0 -> CoalescingKey(messageType, channel, 0)
            0xB0 -> {
                val controller = bytes.getOrNull(1)?.unsigned() ?: return null
                if (controller in MIDI_DISCRETE_CONTROLLERS) {
                    null
                } else {
                    CoalescingKey(messageType, channel, controller)
                }
            }
            else -> null
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    companion object {
        const val DEFAULT_MAXIMUM_SIZE = 256
        const val DEFAULT_CONGESTION_THRESHOLD = 128
        const val NORMAL_BATCH_WINDOW_NANOS = 2_000_000L
        const val CONGESTED_BATCH_WINDOW_NANOS = 4_000_000L
        const val MIDI_CHANNEL_COUNT = 16
        const val SUSTAIN_CONTROLLER = 64
        const val ALL_SOUND_OFF_CONTROLLER = 120
        const val ALL_NOTES_OFF_CONTROLLER = 123
        const val FULL_PANIC_MESSAGE_COUNT = MIDI_CHANNEL_COUNT * 3

        fun fullPanic(timestampNanos: Long): List<PendingMidiOutput> = buildList {
            var order = 0L
            for (channel in 0 until MIDI_CHANNEL_COUNT) {
                add(
                    PendingMidiOutput(
                        timestampNanos,
                        byteArrayOf((0xB0 or channel).toByte(), SUSTAIN_CONTROLLER.toByte(), 0),
                        order++,
                    ),
                )
                add(
                    PendingMidiOutput(
                        timestampNanos,
                        byteArrayOf(
                            (0xB0 or channel).toByte(),
                            ALL_SOUND_OFF_CONTROLLER.toByte(),
                            0,
                        ),
                        order++,
                    ),
                )
                add(
                    PendingMidiOutput(
                        timestampNanos,
                        byteArrayOf(
                            (0xB0 or channel).toByte(),
                            ALL_NOTES_OFF_CONTROLLER.toByte(),
                            0,
                        ),
                        order++,
                    ),
                )
            }
        }
    }
}

/** Returns the exclusive end of the largest timestamp-bounded prefix that fits one datagram. */
internal fun largestFittingMidiOutputPrefix(
    messages: List<PendingMidiOutput>,
    startIndex: Int,
    batchWindowNanos: Long,
    maximumDatagramBytes: Int,
    encodedSize: (List<PendingMidiOutput>) -> Int,
): Int {
    require(startIndex in 0..messages.size) { "startIndex is outside the message list" }
    require(batchWindowNanos > 0L) { "batchWindowNanos must be positive" }
    require(maximumDatagramBytes > 0) { "maximumDatagramBytes must be positive" }
    if (startIndex == messages.size) return startIndex
    val firstTimestamp = messages[startIndex].timestampNanos
    var endExclusive = startIndex
    while (endExclusive < messages.size) {
        val next = messages[endExclusive]
        if (endExclusive > startIndex &&
            next.timestampNanos - firstTimestamp > batchWindowNanos
        ) {
            break
        }
        val candidate = messages.subList(startIndex, endExclusive + 1)
        if (encodedSize(candidate) > maximumDatagramBytes) break
        endExclusive++
    }
    return endExclusive
}
