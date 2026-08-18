package icu.ringona.rtpmidi

/** Immutable runtime health snapshot for one connected AppleMIDI session. */
data class AppleMidiSessionStatistics(
    val sessionId: String,
    val peerId: String?,
    val peerName: String,
    val incomingPackets: Long,
    val releasedPackets: Long,
    val lostPackets: Long,
    val reorderedPackets: Long,
    val latePackets: Long,
    val duplicatePackets: Long,
    val recoveryAttempts: Long,
    val recoveryFallbacks: Long,
    val recoveredMessages: Long,
    val outgoingPackets: Long,
    val outgoingSendFailures: Long,
    val receiverFeedbackSent: Long,
    val journalHeartbeatsSent: Long,
    val urgentJournalHeartbeatsSent: Long,
    val jitterBuffer: RtpMidiJitterBufferStatistics,
    val jitterBufferDelayNanos: Long,
    val estimatedNetworkJitterNanos: Long,
)

/** Immutable process-wide snapshot of the bounded RTP output path. */
data class AppleMidiTransportStatistics(
    val queuedSendTasks: Int,
    val peakQueuedSendTasks: Int,
    val droppedSendTasks: Long,
    val overloadPanicFallbacks: Long,
    val pendingMidiMessages: Int,
    val coalescedMidiMessages: Long,
    val evictedContinuousMidiMessages: Long,
    val droppedContinuousMidiMessages: Long,
    val evictedNonCriticalMidiMessages: Long,
    val droppedNonCriticalMidiMessages: Long,
    val accumulatorPanicFallbacks: Long,
)
