package icu.ringona.rtpmidi

/** A complete MIDI 1.0 channel voice message. */
data class MidiChannelMessage(
    val status: Int,
    val data1: Int,
    val data2: Int? = null,
) {
    val channel: Int
        get() = status and 0x0F

    val messageType: Int
        get() = status and 0xF0

    init {
        require(status in 0x80..0xEF) { "status must be a MIDI channel voice status" }
        require(data1 in 0..0x7F) { "data1 must be a 7-bit MIDI value" }
        when (channelMessageDataLength(status)) {
            1 -> require(data2 == null) { "This MIDI status has exactly one data octet" }
            2 -> require(data2 in 0..0x7F) { "data2 must be a 7-bit MIDI value" }
        }
    }

    fun toByteArray(): ByteArray = if (data2 == null) {
        byteArrayOf(status.toByte(), data1.toByte())
    } else {
        byteArrayOf(status.toByte(), data1.toByte(), data2.toByte())
    }

    companion object {
        fun fromBytes(bytes: ByteArray, offset: Int = 0): MidiChannelMessage {
            if (offset !in bytes.indices) throw AppleMidiProtocolException("MIDI status is missing")
            val status = bytes[offset].toInt() and 0xFF
            if (status !in 0x80..0xEF) {
                throw AppleMidiProtocolException("Only MIDI channel voice messages are supported")
            }
            val dataLength = channelMessageDataLength(status)
            if (bytes.size - offset != dataLength + 1) {
                throw AppleMidiProtocolException(
                    "MIDI status 0x${status.toString(16)} requires $dataLength data octets",
                )
            }
            val data1 = bytes[offset + 1].toInt() and 0xFF
            val data2 = if (dataLength == 2) bytes[offset + 2].toInt() and 0xFF else null
            return try {
                MidiChannelMessage(status, data1, data2)
            } catch (error: IllegalArgumentException) {
                throw AppleMidiProtocolException(error.message ?: "Invalid MIDI channel message")
            }
        }
    }
}

/** A MIDI message whose delta is relative to the preceding command in this RTP packet. */
data class TimedMidiMessage(
    val deltaTimeTicks: Int,
    val message: MidiChannelMessage,
) {
    init {
        require(deltaTimeTicks in 0..RtpMidiCodec.MAX_DELTA_TIME_TICKS) {
            "deltaTimeTicks must fit the RFC 6295 four-octet delta time"
        }
    }
}

/** A constrained RTP-MIDI packet carrying MIDI 1.0 channel voice messages. */
data class RtpMidiPacket(
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val commands: List<TimedMidiMessage>,
    val marker: Boolean = false,
    val firstDeltaEncoded: Boolean = commands.firstOrNull()?.deltaTimeTicks != 0,
    val phantomStatus: Boolean = false,
    /** Opaque RFC 6295 recovery-journal bytes, or null when the J flag is clear. */
    val journal: ByteArray? = null,
) {
    init {
        require(sequenceNumber in 0..0xFFFF) { "sequenceNumber must fit an unsigned 16-bit value" }
        requireUInt32Value("timestamp", timestamp)
        requireUInt32Value("ssrc", ssrc)
        require(commands.isNotEmpty() || !firstDeltaEncoded) {
            "An empty MIDI list cannot contain a first-command delta time"
        }
        require(commands.isNotEmpty() || !phantomStatus) {
            "An empty MIDI list cannot set the phantom-status flag"
        }
        require(commands.isEmpty() || firstDeltaEncoded || commands.first().deltaTimeTicks == 0) {
            "A non-zero first delta time must be encoded"
        }
        require(journal == null || journal.isNotEmpty()) {
            "A journal must contain at least one octet"
        }
    }

    /** Absolute 32-bit RTP timestamps for the commands, including cumulative delta times. */
    fun commandTimestamps(): List<Long> {
        var commandTimestamp = timestamp
        return commands.map { command ->
            commandTimestamp = (commandTimestamp + command.deltaTimeTicks) and 0xFFFF_FFFFL
            commandTimestamp
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtpMidiPacket) return false
        return sequenceNumber == other.sequenceNumber &&
            timestamp == other.timestamp &&
            ssrc == other.ssrc &&
            commands == other.commands &&
            marker == other.marker &&
            firstDeltaEncoded == other.firstDeltaEncoded &&
            phantomStatus == other.phantomStatus &&
            when {
                journal == null -> other.journal == null
                other.journal == null -> false
                else -> journal.contentEquals(other.journal)
            }
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + ssrc.hashCode()
        result = 31 * result + commands.hashCode()
        result = 31 * result + marker.hashCode()
        result = 31 * result + firstDeltaEncoded.hashCode()
        result = 31 * result + phantomStatus.hashCode()
        result = 31 * result + (journal?.contentHashCode() ?: 0)
        return result
    }
}

/** RTP v2, dynamic payload type 97, RFC 6295 MIDI command-section codec. */
object RtpMidiCodec {
    const val RTP_VERSION: Int = 2
    const val PAYLOAD_TYPE: Int = 97
    const val MAX_COMMAND_SECTION_BYTES: Int = 0x0FFF
    const val MAX_DELTA_TIME_TICKS: Int = 0x0FFF_FFFF

    private const val RTP_HEADER_BYTES = 12
    private const val RTP_VERSION_SHIFT = 6
    private const val RTP_PADDING = 0x20
    private const val RTP_EXTENSION = 0x10
    private const val RTP_CSRC_COUNT = 0x0F
    private const val RTP_MARKER = 0x80
    private const val RTP_PAYLOAD_TYPE_MASK = 0x7F

    private const val LONG_HEADER = 0x80
    private const val JOURNAL = 0x40
    private const val FIRST_DELTA = 0x20
    private const val PHANTOM_STATUS = 0x10
    private const val SHORT_LENGTH = 0x0F

    fun isRtpMidiPacket(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Boolean {
        if (offset < 0 || length < RTP_HEADER_BYTES + 1 || offset > bytes.size - length) return false
        val first = bytes[offset].toInt() and 0xFF
        val second = bytes[offset + 1].toInt() and 0xFF
        return (first ushr RTP_VERSION_SHIFT) == RTP_VERSION &&
            (second and RTP_PAYLOAD_TYPE_MASK) == PAYLOAD_TYPE
    }

    fun readSsrcOrNull(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Long? {
        if (!isRtpMidiPacket(bytes, offset, length) || length < RTP_HEADER_BYTES) return null
        val ssrcOffset = offset + 8
        return ((bytes[ssrcOffset].toLong() and 0xFF) shl 24) or
            ((bytes[ssrcOffset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[ssrcOffset + 2].toLong() and 0xFF) shl 8) or
            (bytes[ssrcOffset + 3].toLong() and 0xFF)
    }

    fun encode(packet: RtpMidiPacket, useRunningStatus: Boolean = true): ByteArray {
        val midiCommands = encodeCommandSection(packet, useRunningStatus)
        if (midiCommands.size > MAX_COMMAND_SECTION_BYTES) {
            throw AppleMidiProtocolException(
                "RTP-MIDI command section exceeds $MAX_COMMAND_SECTION_BYTES bytes",
            )
        }

        val writer = BigEndianPacketWriter(RTP_HEADER_BYTES + 2 + midiCommands.size)
        writer.writeUInt8(RTP_VERSION shl RTP_VERSION_SHIFT)
        writer.writeUInt8((if (packet.marker) RTP_MARKER else 0) or PAYLOAD_TYPE)
        writer.writeUInt16(packet.sequenceNumber)
        writer.writeUInt32(packet.timestamp)
        writer.writeUInt32(packet.ssrc)

        var commandHeader = 0
        if (midiCommands.size >= 16) commandHeader = commandHeader or LONG_HEADER
        if (packet.journal != null) commandHeader = commandHeader or JOURNAL
        if (packet.firstDeltaEncoded) commandHeader = commandHeader or FIRST_DELTA
        if (packet.phantomStatus) commandHeader = commandHeader or PHANTOM_STATUS
        val encodedLengthHighBits = if (midiCommands.size >= 16) {
            (midiCommands.size ushr 8) and SHORT_LENGTH
        } else {
            midiCommands.size
        }
        commandHeader = commandHeader or encodedLengthHighBits
        writer.writeUInt8(commandHeader)
        if ((commandHeader and LONG_HEADER) != 0) writer.writeUInt8(midiCommands.size and 0xFF)
        writer.writeBytes(midiCommands)
        packet.journal?.let(writer::writeBytes)
        return writer.toByteArray()
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): RtpMidiPacket {
        val reader = BigEndianPacketReader(bytes, offset, length)
        if (reader.remaining < RTP_HEADER_BYTES + 1) {
            throw AppleMidiProtocolException("Truncated RTP-MIDI datagram")
        }
        val first = reader.readUInt8()
        val version = first ushr RTP_VERSION_SHIFT
        if (version != RTP_VERSION) {
            throw AppleMidiProtocolException("Unsupported RTP version $version")
        }
        if ((first and RTP_PADDING) != 0) {
            throw AppleMidiProtocolException("RTP padding is not supported")
        }
        if ((first and RTP_EXTENSION) != 0) {
            throw AppleMidiProtocolException("RTP header extensions are not supported")
        }
        if ((first and RTP_CSRC_COUNT) != 0) {
            throw AppleMidiProtocolException("RTP CSRC identifiers are not supported")
        }

        val second = reader.readUInt8()
        val payloadType = second and RTP_PAYLOAD_TYPE_MASK
        if (payloadType != PAYLOAD_TYPE) {
            throw AppleMidiProtocolException("Expected RTP payload type $PAYLOAD_TYPE, got $payloadType")
        }
        val marker = (second and RTP_MARKER) != 0
        val sequenceNumber = reader.readUInt16()
        val timestamp = reader.readUInt32()
        val ssrc = reader.readUInt32()

        val commandHeader = reader.readUInt8()
        val longHeader = (commandHeader and LONG_HEADER) != 0
        val hasJournal = (commandHeader and JOURNAL) != 0
        val firstDeltaEncoded = (commandHeader and FIRST_DELTA) != 0
        val phantomStatus = (commandHeader and PHANTOM_STATUS) != 0
        var commandLength = commandHeader and SHORT_LENGTH
        if (longHeader) commandLength = (commandLength shl 8) or reader.readUInt8()
        if (reader.remaining < commandLength) {
            throw AppleMidiProtocolException("Truncated RTP-MIDI command section")
        }
        if (commandLength == 0 && (firstDeltaEncoded || phantomStatus)) {
            throw AppleMidiProtocolException("Empty MIDI list has command-only flags set")
        }

        val commandBytes = reader.readBytes(commandLength)
        val commands = decodeCommandSection(
            bytes = commandBytes,
            firstDeltaEncoded = firstDeltaEncoded,
        )
        val journal = if (hasJournal) {
            if (reader.remaining == 0) {
                throw AppleMidiProtocolException("RTP-MIDI J flag is set without a journal")
            }
            reader.readBytes(reader.remaining)
        } else {
            reader.requireFinished("Unexpected bytes after RTP-MIDI command section")
            null
        }
        return RtpMidiPacket(
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            ssrc = ssrc,
            commands = commands,
            marker = marker,
            firstDeltaEncoded = firstDeltaEncoded,
            phantomStatus = phantomStatus,
            journal = journal,
        )
    }

    fun decodeOrNull(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): RtpMidiPacket? = try {
        decode(bytes, offset, length)
    } catch (_: AppleMidiProtocolException) {
        null
    }

    private fun encodeCommandSection(
        packet: RtpMidiPacket,
        useRunningStatus: Boolean,
    ): ByteArray {
        if (packet.commands.isEmpty()) return ByteArray(0)
        val writer = BigEndianPacketWriter()
        var runningStatus = -1
        packet.commands.forEachIndexed { index, command ->
            if (index > 0 || packet.firstDeltaEncoded) {
                writeDeltaTime(writer, command.deltaTimeTicks)
            }
            val message = command.message
            val omitStatus = index > 0 && useRunningStatus && runningStatus == message.status
            if (!omitStatus) writer.writeUInt8(message.status)
            writer.writeUInt8(message.data1)
            message.data2?.let(writer::writeUInt8)
            runningStatus = message.status
        }
        return writer.toByteArray()
    }

    private fun decodeCommandSection(
        bytes: ByteArray,
        firstDeltaEncoded: Boolean,
    ): List<TimedMidiMessage> {
        if (bytes.isEmpty()) return emptyList()
        val reader = BigEndianPacketReader(bytes, 0, bytes.size)
        val result = ArrayList<TimedMidiMessage>()
        var runningStatus = -1
        var commandIndex = 0
        while (reader.remaining > 0) {
            val delta = if (commandIndex > 0 || firstDeltaEncoded) readDeltaTime(reader) else 0
            if (reader.remaining == 0) {
                throw AppleMidiProtocolException("Delta time is not followed by a MIDI command")
            }

            val firstMidiOctet = reader.readUInt8()
            val status: Int
            val data1: Int
            if ((firstMidiOctet and 0x80) != 0) {
                if (firstMidiOctet !in 0x80..0xEF) {
                    throw AppleMidiProtocolException(
                        "Only MIDI 1.0 channel voice commands are supported",
                    )
                }
                status = firstMidiOctet
                runningStatus = status
                if (reader.remaining == 0) {
                    throw AppleMidiProtocolException("MIDI command is missing data octets")
                }
                data1 = reader.readUInt8()
            } else {
                if (runningStatus < 0) {
                    throw AppleMidiProtocolException(
                        "The first MIDI command must include a channel status octet",
                    )
                }
                status = runningStatus
                data1 = firstMidiOctet
            }
            if (data1 > 0x7F) throw AppleMidiProtocolException("MIDI data octet has its status bit set")

            val data2 = if (channelMessageDataLength(status) == 2) {
                if (reader.remaining == 0) {
                    throw AppleMidiProtocolException("MIDI command is missing its second data octet")
                }
                reader.readUInt8().also {
                    if (it > 0x7F) {
                        throw AppleMidiProtocolException("MIDI data octet has its status bit set")
                    }
                }
            } else {
                null
            }
            result += TimedMidiMessage(delta, MidiChannelMessage(status, data1, data2))
            commandIndex++
        }
        return result
    }

    private fun writeDeltaTime(writer: BigEndianPacketWriter, deltaTimeTicks: Int) {
        if (deltaTimeTicks !in 0..MAX_DELTA_TIME_TICKS) {
            throw AppleMidiProtocolException("Delta time is outside the RFC 6295 range")
        }
        var value = deltaTimeTicks
        val groups = IntArray(4)
        var groupCount = 0
        do {
            groups[groupCount++] = value and 0x7F
            value = value ushr 7
        } while (value != 0)
        for (index in groupCount - 1 downTo 0) {
            val continuation = if (index != 0) 0x80 else 0
            writer.writeUInt8(groups[index] or continuation)
        }
    }

    private fun readDeltaTime(reader: BigEndianPacketReader): Int {
        var value = 0
        repeat(4) { index ->
            if (reader.remaining == 0) {
                throw AppleMidiProtocolException("Truncated RTP-MIDI delta time")
            }
            val octet = reader.readUInt8()
            value = (value shl 7) or (octet and 0x7F)
            if ((octet and 0x80) == 0) return value
            if (index == 3) {
                throw AppleMidiProtocolException("RTP-MIDI delta time exceeds four octets")
            }
        }
        throw AppleMidiProtocolException("Invalid RTP-MIDI delta time")
    }
}

private fun channelMessageDataLength(status: Int): Int = when (status and 0xF0) {
    0xC0, 0xD0 -> 1
    in 0x80..0xE0 -> 2
    else -> throw IllegalArgumentException("Not a MIDI channel voice status")
}

private fun requireUInt32Value(name: String, value: Long) {
    require(value in 0..0xFFFF_FFFFL) { "$name must fit an unsigned 32-bit value" }
}
