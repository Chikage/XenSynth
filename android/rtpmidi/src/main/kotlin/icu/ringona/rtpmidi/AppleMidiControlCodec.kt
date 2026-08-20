package icu.ringona.rtpmidi

import java.nio.charset.CharacterCodingException

/** A malformed or unsupported AppleMIDI/RTP-MIDI datagram. */
class AppleMidiProtocolException(message: String) : IllegalArgumentException(message)

/** AppleMIDI control-channel and synchronization messages. */
sealed interface AppleMidiControlPacket {
    data class Invitation(
        val command: AppleMidiInvitationCommand,
        val initiatorToken: Long,
        val ssrc: Long,
        val name: String,
        val protocolVersion: Long = AppleMidiControlCodec.PROTOCOL_VERSION,
    ) : AppleMidiControlPacket {
        init {
            requireUInt32("initiatorToken", initiatorToken)
            requireUInt32("ssrc", ssrc)
            requireUInt32("protocolVersion", protocolVersion)
        }
    }

    data class EndSession(
        val initiatorToken: Long,
        val ssrc: Long,
    ) : AppleMidiControlPacket {
        init {
            requireUInt32("initiatorToken", initiatorToken)
            requireUInt32("ssrc", ssrc)
        }
    }

    /**
     * One CK0, CK1, or CK2 exchange. Timestamps are unsigned 64-bit wire values stored as
     * [Long] bit patterns. Apple uses a 10 kHz clock, so ordinary values remain positive.
     */
    data class ClockSynchronization(
        val ssrc: Long,
        val count: Int,
        val timestamp1: Long,
        val timestamp2: Long,
        val timestamp3: Long,
    ) : AppleMidiControlPacket {
        init {
            requireUInt32("ssrc", ssrc)
            require(count in 0..2) { "count must be CK0, CK1, or CK2" }
        }
    }

    data class ReceiverFeedback(
        val ssrc: Long,
        val sequenceNumber: Int,
    ) : AppleMidiControlPacket {
        init {
            requireUInt32("ssrc", ssrc)
            require(sequenceNumber in 0..0xFFFF) { "sequenceNumber must fit an unsigned 16-bit value" }
        }
    }
}

enum class AppleMidiInvitationCommand(internal val wireCode: Int) {
    IN(0x494E),
    OK(0x4F4B),
    NO(0x4E4F),
}

/** Codec for the AppleMIDI IN, OK, NO, BY, CK, and RS datagrams. */
object AppleMidiControlCodec {
    const val PROTOCOL_VERSION: Long = 2L
    const val SIGNATURE: Int = 0xFFFF
    const val MAX_SESSION_NAME_BYTES: Int = 255

    private const val BY = 0x4259
    private const val CK = 0x434B
    private const val RS = 0x5253
    private const val INVITATION_FIXED_SIZE = 16
    private const val END_SESSION_SIZE = 12
    private const val CLOCK_SYNCHRONIZATION_SIZE = 36
    private const val RECEIVER_FEEDBACK_SIZE = 12

    fun isControlPacket(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        if (offset < 0 || length < 4 || offset > bytes.size - length) return false
        return (bytes[offset].toInt() and 0xFF) == 0xFF &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xFF
    }

    fun encode(packet: AppleMidiControlPacket): ByteArray {
        val writer = BigEndianPacketWriter()
        writer.writeUInt16(SIGNATURE)
        when (packet) {
            is AppleMidiControlPacket.Invitation -> encodeInvitation(writer, packet)
            is AppleMidiControlPacket.EndSession -> {
                writer.writeUInt16(BY)
                writer.writeUInt32(packet.initiatorToken)
                writer.writeUInt32(packet.ssrc)
            }
            is AppleMidiControlPacket.ClockSynchronization -> {
                writer.writeUInt16(CK)
                writer.writeUInt32(packet.ssrc)
                writer.writeUInt8(packet.count)
                writer.writeUInt8(0)
                writer.writeUInt8(0)
                writer.writeUInt8(0)
                writer.writeInt64Bits(packet.timestamp1)
                writer.writeInt64Bits(packet.timestamp2)
                writer.writeInt64Bits(packet.timestamp3)
            }
            is AppleMidiControlPacket.ReceiverFeedback -> {
                writer.writeUInt16(RS)
                writer.writeUInt32(packet.ssrc)
                writer.writeUInt16(packet.sequenceNumber)
                writer.writeUInt16(0)
            }
        }
        return writer.toByteArray()
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): AppleMidiControlPacket {
        val reader = BigEndianPacketReader(bytes, offset, length)
        if (reader.readUInt16() != SIGNATURE) {
            throw AppleMidiProtocolException("AppleMIDI signature is missing")
        }
        val command = reader.readUInt16()
        return when (command) {
            AppleMidiInvitationCommand.IN.wireCode,
            AppleMidiInvitationCommand.OK.wireCode,
            AppleMidiInvitationCommand.NO.wireCode,
            -> decodeInvitation(reader, command)
            BY -> decodeEndSession(reader)
            CK -> decodeClockSynchronization(reader)
            RS -> decodeReceiverFeedback(reader)
            else -> throw AppleMidiProtocolException(
                "Unsupported AppleMIDI command 0x${command.toString(16).padStart(4, '0')}",
            )
        }
    }

    fun decodeOrNull(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): AppleMidiControlPacket? = try {
        decode(bytes, offset, length)
    } catch (_: AppleMidiProtocolException) {
        null
    }

    private fun encodeInvitation(
        writer: BigEndianPacketWriter,
        packet: AppleMidiControlPacket.Invitation,
    ) {
        if (packet.protocolVersion != PROTOCOL_VERSION) {
            throw AppleMidiProtocolException(
                "Only AppleMIDI protocol version $PROTOCOL_VERSION is supported",
            )
        }
        if ('\u0000' in packet.name) {
            throw AppleMidiProtocolException("Session name must not contain NUL")
        }
        val nameBytes = packet.name.encodeToByteArray()
        if (nameBytes.size > MAX_SESSION_NAME_BYTES) {
            throw AppleMidiProtocolException(
                "UTF-8 session name exceeds $MAX_SESSION_NAME_BYTES bytes",
            )
        }
        writer.writeUInt16(packet.command.wireCode)
        writer.writeUInt32(packet.protocolVersion)
        writer.writeUInt32(packet.initiatorToken)
        writer.writeUInt32(packet.ssrc)
        // Apple omits the session name from a rejection response. Accepting a name here is
        // harmless for legacy peers, but emitting the protocol form is required by CoreMIDI.
        if (packet.command == AppleMidiInvitationCommand.NO) return
        writer.writeBytes(nameBytes)
        writer.writeUInt8(0)
    }

    private fun decodeInvitation(
        reader: BigEndianPacketReader,
        wireCommand: Int,
    ): AppleMidiControlPacket.Invitation {
        if (reader.packetLength < INVITATION_FIXED_SIZE) {
            throw AppleMidiProtocolException("Truncated AppleMIDI invitation")
        }
        val protocolVersion = reader.readUInt32()
        if (protocolVersion != PROTOCOL_VERSION) {
            throw AppleMidiProtocolException(
                "Unsupported AppleMIDI protocol version $protocolVersion",
            )
        }
        val initiatorToken = reader.readUInt32()
        val ssrc = reader.readUInt32()
        val command = AppleMidiInvitationCommand.entries.first { it.wireCode == wireCommand }
        // The name is optional on the wire (in particular for NO responses).
        if (reader.remaining == 0) {
            return AppleMidiControlPacket.Invitation(
                command = command,
                initiatorToken = initiatorToken,
                ssrc = ssrc,
                name = "",
                protocolVersion = protocolVersion,
            )
        }
        val terminatorOffset = reader.indexOf(0)
        if (terminatorOffset < 0) {
            throw AppleMidiProtocolException("AppleMIDI session name is not NUL terminated")
        }
        if (terminatorOffset > MAX_SESSION_NAME_BYTES) {
            throw AppleMidiProtocolException(
                "UTF-8 session name exceeds $MAX_SESSION_NAME_BYTES bytes",
            )
        }
        val nameBytes = reader.readBytes(terminatorOffset)
        val name = try {
            nameBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw AppleMidiProtocolException("AppleMIDI session name is not valid UTF-8")
        }
        reader.readUInt8()
        reader.requireFinished("Unexpected bytes after AppleMIDI session name")
        return AppleMidiControlPacket.Invitation(
            command = command,
            initiatorToken = initiatorToken,
            ssrc = ssrc,
            name = name,
            protocolVersion = protocolVersion,
        )
    }

    private fun decodeEndSession(reader: BigEndianPacketReader): AppleMidiControlPacket.EndSession {
        reader.requirePacketLength(END_SESSION_SIZE, "BY")
        return AppleMidiControlPacket.EndSession(
            initiatorToken = reader.readUInt32(),
            ssrc = reader.readUInt32(),
        )
    }

    private fun decodeClockSynchronization(
        reader: BigEndianPacketReader,
    ): AppleMidiControlPacket.ClockSynchronization {
        reader.requirePacketLength(CLOCK_SYNCHRONIZATION_SIZE, "CK")
        val ssrc = reader.readUInt32()
        val count = reader.readUInt8()
        if (count !in 0..2) {
            throw AppleMidiProtocolException("Invalid CK count $count")
        }
        repeat(3) {
            if (reader.readUInt8() != 0) {
                throw AppleMidiProtocolException("CK padding must be zero")
            }
        }
        return AppleMidiControlPacket.ClockSynchronization(
            ssrc = ssrc,
            count = count,
            timestamp1 = reader.readInt64Bits(),
            timestamp2 = reader.readInt64Bits(),
            timestamp3 = reader.readInt64Bits(),
        )
    }

    private fun decodeReceiverFeedback(
        reader: BigEndianPacketReader,
    ): AppleMidiControlPacket.ReceiverFeedback {
        reader.requirePacketLength(RECEIVER_FEEDBACK_SIZE, "RS")
        val ssrc = reader.readUInt32()
        val sequenceNumber = reader.readUInt16()
        if (reader.readUInt16() != 0) {
            throw AppleMidiProtocolException("RS reserved field must be zero")
        }
        return AppleMidiControlPacket.ReceiverFeedback(ssrc, sequenceNumber)
    }
}

internal class BigEndianPacketReader(
    private val bytes: ByteArray,
    offset: Int,
    length: Int,
) {
    private val limit: Int
    private var position: Int
    val packetLength: Int = length

    init {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw AppleMidiProtocolException("Invalid datagram range")
        }
        position = offset
        limit = offset + length
    }

    val remaining: Int
        get() = limit - position

    fun readUInt8(): Int {
        requireAvailable(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun peekUInt8(): Int {
        requireAvailable(1)
        return bytes[position].toInt() and 0xFF
    }

    fun readUInt16(): Int = (readUInt8() shl 8) or readUInt8()

    fun readUInt32(): Long =
        (readUInt8().toLong() shl 24) or
            (readUInt8().toLong() shl 16) or
            (readUInt8().toLong() shl 8) or
            readUInt8().toLong()

    fun readInt64Bits(): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) {
            value = (value shl 8) or readUInt8().toLong()
        }
        return value
    }

    fun readBytes(count: Int): ByteArray {
        requireAvailable(count)
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    fun indexOf(value: Int): Int {
        var index = position
        while (index < limit) {
            if ((bytes[index].toInt() and 0xFF) == value) return index - position
            index++
        }
        return -1
    }

    fun requirePacketLength(expected: Int, command: String) {
        if (packetLength != expected) {
            throw AppleMidiProtocolException(
                "$command packet must contain $expected bytes, got $packetLength",
            )
        }
    }

    fun requireFinished(message: String = "Unexpected trailing bytes") {
        if (remaining != 0) throw AppleMidiProtocolException(message)
    }

    private fun requireAvailable(count: Int) {
        if (count < 0 || remaining < count) {
            throw AppleMidiProtocolException("Truncated datagram")
        }
    }
}

internal class BigEndianPacketWriter(initialCapacity: Int = 64) {
    private var bytes = ByteArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun writeUInt8(value: Int) {
        if (value !in 0..0xFF) throw AppleMidiProtocolException("Value does not fit one octet")
        ensureCapacity(1)
        bytes[size++] = value.toByte()
    }

    fun writeUInt16(value: Int) {
        if (value !in 0..0xFFFF) throw AppleMidiProtocolException("Value does not fit two octets")
        writeUInt8((value ushr 8) and 0xFF)
        writeUInt8(value and 0xFF)
    }

    fun writeUInt32(value: Long) {
        requireUInt32("value", value)
        writeUInt8(((value ushr 24) and 0xFF).toInt())
        writeUInt8(((value ushr 16) and 0xFF).toInt())
        writeUInt8(((value ushr 8) and 0xFF).toInt())
        writeUInt8((value and 0xFF).toInt())
    }

    fun writeInt64Bits(value: Long) {
        for (shift in 56 downTo 0 step 8) {
            writeUInt8(((value ushr shift) and 0xFF).toInt())
        }
    }

    fun writeBytes(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(bytes, destinationOffset = size)
        size += value.size
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        if (required <= bytes.size) return
        var nextSize = bytes.size
        while (nextSize < required) nextSize = (nextSize * 2).coerceAtLeast(required)
        bytes = bytes.copyOf(nextSize)
    }
}

private fun requireUInt32(name: String, value: Long) {
    require(value in 0..0xFFFF_FFFFL) { "$name must fit an unsigned 32-bit value" }
}
