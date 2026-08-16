package icu.ringona.rtpmidi

/** Configuration for one foreground AppleMIDI participant. */
data class AppleMidiConfiguration @JvmOverloads constructor(
    val serviceName: String,
    val invitationTimeoutMillis: Long = 12_000,
    val clockSyncIntervalMillis: Long = 10_000,
    /** Initial playout delay for a normal Wi-Fi LAN; the adaptive buffer may move within 8-40 ms. */
    val jitterBufferMillis: Long = 20,
    val maximumSessions: Int = 16,
) {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require('\u0000' !in serviceName) { "serviceName must not contain NUL" }
        require(serviceName.encodeToByteArray().size <= AppleMidiControlCodec.MAX_SESSION_NAME_BYTES) {
            "serviceName is too long for AppleMIDI"
        }
        require(invitationTimeoutMillis > 0) { "invitationTimeoutMillis must be positive" }
        require(clockSyncIntervalMillis in 1_000..59_000) {
            "clockSyncIntervalMillis must remain below the AppleMIDI timeout"
        }
        require(jitterBufferMillis in 8..40) { "jitterBufferMillis must be between 8 and 40" }
        require(maximumSessions > 0) { "maximumSessions must be positive" }
    }
}

/** A Bonjour-advertised AppleMIDI participant. The id is independent of its current IP address. */
data class AppleMidiPeer(
    val id: String,
    val name: String,
    val hostAddress: String,
    val controlPort: Int,
    val state: AppleMidiSessionState,
)

enum class AppleMidiSessionState {
    DISCOVERED,
    INVITING,
    SYNCHRONIZING,
    CONNECTED,
    FAILED,
}

/** A complete MIDI 1.0 channel message scheduled on the receiver's monotonic clock. */
data class AppleMidiEvent(
    val bytes: ByteArray,
    val targetTimeNanos: Long,
    val sessionId: String,
)

interface AppleMidiListener {
    fun onPeersChanged(peers: List<AppleMidiPeer>) = Unit

    fun onMidiEvent(event: AppleMidiEvent)

    /** Called after a session disappears so consumers can release notes owned by that source. */
    fun onSessionClosed(sessionId: String) = Unit
}
