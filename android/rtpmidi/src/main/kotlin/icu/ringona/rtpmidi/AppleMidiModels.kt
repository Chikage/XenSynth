package icu.ringona.rtpmidi

/** Configuration for one foreground AppleMIDI participant. */
data class AppleMidiConfiguration @JvmOverloads constructor(
    val serviceName: String,
    val invitationTimeoutMillis: Long = 12_000,
    val clockSyncIntervalMillis: Long = 10_000,
    /** Initial playout delay for a normal Wi-Fi LAN; the adaptive buffer may move within 24-120 ms. */
    val jitterBufferMillis: Long = 60,
    val maximumSessions: Int = 16,
    /** Optional Bonjour TXT model. Android callers may leave this null to publish Build.MODEL. */
    val deviceModel: String? = null,
    /**
     * How early a scheduled listener may receive an event before [AppleMidiEvent.targetTimeNanos].
     * Ordinary [AppleMidiListener] implementations continue to receive events at playout time.
     */
    val eventDeliveryLookaheadMillis: Long = 8,
    /**
     * Keep Bonjour discovery and the RTP sockets on the Wi-Fi IPv4 LAN by default. Set this to
     * false only when an IPv6-only network must be supported.
     */
    val ipv4Only: Boolean = true,
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
        require(jitterBufferMillis in 24..120) {
            "jitterBufferMillis must be between 24 and 120"
        }
        require(maximumSessions > 0) { "maximumSessions must be positive" }
        require(eventDeliveryLookaheadMillis in 0..50) {
            "eventDeliveryLookaheadMillis must be between 0 and 50"
        }
    }
}

/** A Bonjour-advertised AppleMIDI participant, deduplicated across conflict-renamed aliases. */
data class AppleMidiPeer(
    val id: String,
    val name: String,
    val hostAddress: String,
    val controlPort: Int,
    val state: AppleMidiSessionState,
    /** Remote Bonjour model, or the participant name when the TXT record is unavailable. */
    val model: String,
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

/**
 * Opts a listener into early delivery while preserving each event's original monotonic target.
 * Use this only when the consumer can schedule work against [AppleMidiEvent.targetTimeNanos].
 */
interface AppleMidiScheduledListener : AppleMidiListener
