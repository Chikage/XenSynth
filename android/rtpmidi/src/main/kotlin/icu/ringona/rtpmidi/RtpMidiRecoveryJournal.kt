package icu.ringona.rtpmidi

internal enum class RtpMidiControlRecoveryTool {
    VALUE,
    TOGGLE,
    COUNT,
}

internal data class RtpMidiControlJournalLog(
    val controller: Int,
    val tool: RtpMidiControlRecoveryTool,
    val value: Int,
    val singlePacketLoss: Boolean,
) {
    init {
        require(controller in 0..127) { "controller must be a 7-bit value" }
        require(value in 0..127) { "control recovery value must be a 7-bit value" }
        if (tool != RtpMidiControlRecoveryTool.VALUE) {
            require(value <= 63) { "toggle/count recovery values must fit six bits" }
        }
    }
}

internal data class RtpMidiNoteJournalLog(
    val note: Int,
    val velocity: Int,
    val playOnRecovery: Boolean,
    val singlePacketLoss: Boolean,
) {
    init {
        require(note in 0..127) { "note must be a 7-bit value" }
        require(velocity in 1..127) { "a Chapter N Note On velocity must be non-zero" }
    }
}

internal data class RtpMidiChannelRecoveryJournal(
    val channel: Int,
    val singlePacketLoss: Boolean,
    val controlLogs: List<RtpMidiControlJournalLog> = emptyList(),
    val noteLogs: List<RtpMidiNoteJournalLog> = emptyList(),
    val noteOffs: Set<Int> = emptySet(),
    val noteOffSinglePacketLoss: Boolean = true,
) {
    init {
        require(channel in 0..15) { "channel must fit the MIDI channel nibble" }
        require(controlLogs.size in 0..128) { "Chapter C supports at most 128 logs" }
        require(noteLogs.size in 0..128) { "Chapter N supports at most 128 note logs" }
        require(noteOffs.all { it in 0..127 }) { "Note Off values must be 7-bit notes" }
        require(controlLogs.isNotEmpty() || noteLogs.isNotEmpty() || noteOffs.isNotEmpty()) {
            "a channel recovery journal must contain Chapter C or Chapter N"
        }
    }
}

internal data class RtpMidiRecoveryJournal(
    val checkpointSequenceNumber: Int,
    val singlePacketLoss: Boolean,
    val channels: List<RtpMidiChannelRecoveryJournal>,
) {
    init {
        require(checkpointSequenceNumber in 0..0xFFFF) {
            "checkpointSequenceNumber must fit an unsigned 16-bit value"
        }
        require(channels.size in 0..16) { "a recovery journal supports at most 16 channels" }
        require(channels.map { it.channel }.distinct().size == channels.size) {
            "a recovery journal must not repeat a channel"
        }
    }
}

/** RFC 6295 recovery-journal codec for the interoperable Chapter C + Chapter N subset. */
internal object RtpMidiRecoveryJournalCodec {
    private const val TOP_SINGLE_PACKET = 0x80
    private const val TOP_SYSTEM_JOURNAL = 0x40
    private const val TOP_CHANNEL_JOURNALS = 0x20
    private const val TOP_ENHANCED_CONTROL = 0x10

    private const val CHANNEL_SINGLE_PACKET = 0x80
    private const val CHANNEL_ENHANCED_CONTROL = 0x04
    private const val CHANNEL_LENGTH_HIGH_MASK = 0x03
    private const val CHANNEL_TOC_CONTROL = 0x40
    private const val CHANNEL_TOC_NOTE = 0x08
    private const val CHANNEL_TOC_SUPPORTED = CHANNEL_TOC_CONTROL or CHANNEL_TOC_NOTE

    fun encode(journal: RtpMidiRecoveryJournal): ByteArray {
        val channels = journal.channels.sortedBy { it.channel }
        val writer = BigEndianPacketWriter()
        var header = if (journal.singlePacketLoss) TOP_SINGLE_PACKET else 0
        if (channels.isNotEmpty()) {
            header = header or TOP_CHANNEL_JOURNALS or (channels.size - 1)
        }
        writer.writeUInt8(header)
        writer.writeUInt16(journal.checkpointSequenceNumber)
        channels.forEach { writer.writeBytes(encodeChannel(it)) }
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): RtpMidiRecoveryJournal {
        val reader = BigEndianPacketReader(bytes, 0, bytes.size)
        if (reader.remaining < 3) throw AppleMidiProtocolException("Truncated recovery journal")
        val header = reader.readUInt8()
        val singlePacketLoss = (header and TOP_SINGLE_PACKET) != 0
        val hasSystemJournal = (header and TOP_SYSTEM_JOURNAL) != 0
        val hasChannels = (header and TOP_CHANNEL_JOURNALS) != 0
        if ((header and TOP_ENHANCED_CONTROL) != 0) {
            throw AppleMidiProtocolException("Enhanced Chapter C is not supported")
        }
        if (hasSystemJournal) {
            throw AppleMidiProtocolException("System recovery journals are not supported")
        }
        val channelCount = if (hasChannels) (header and 0x0F) + 1 else 0
        val checkpoint = reader.readUInt16()
        val channels = ArrayList<RtpMidiChannelRecoveryJournal>(channelCount)
        repeat(channelCount) { channels += decodeChannel(reader) }
        reader.requireFinished("Unexpected bytes after recovery journal")
        return RtpMidiRecoveryJournal(checkpoint, singlePacketLoss, channels)
    }

    fun decodeOrNull(bytes: ByteArray?): RtpMidiRecoveryJournal? {
        if (bytes == null) return null
        return try {
            decode(bytes)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodeChannel(channel: RtpMidiChannelRecoveryJournal): ByteArray {
        val chapters = BigEndianPacketWriter()
        var toc = 0
        if (channel.controlLogs.isNotEmpty()) {
            toc = toc or CHANNEL_TOC_CONTROL
            chapters.writeBytes(encodeChapterC(channel.controlLogs))
        }
        if (channel.noteLogs.isNotEmpty() || channel.noteOffs.isNotEmpty()) {
            toc = toc or CHANNEL_TOC_NOTE
            chapters.writeBytes(encodeChapterN(channel))
        }
        val chapterBytes = chapters.toByteArray()
        val length = 3 + chapterBytes.size
        if (length > 0x03FF) {
            throw AppleMidiProtocolException("Channel recovery journal exceeds 1023 bytes")
        }
        val writer = BigEndianPacketWriter(length)
        writer.writeUInt8(
            (if (channel.singlePacketLoss) CHANNEL_SINGLE_PACKET else 0) or
                (channel.channel shl 3) or
                ((length ushr 8) and CHANNEL_LENGTH_HIGH_MASK),
        )
        writer.writeUInt8(length and 0xFF)
        writer.writeUInt8(toc)
        writer.writeBytes(chapterBytes)
        return writer.toByteArray()
    }

    private fun encodeChapterC(logs: List<RtpMidiControlJournalLog>): ByteArray {
        val writer = BigEndianPacketWriter(1 + logs.size * 2)
        val chapterSingle = logs.all { it.singlePacketLoss }
        writer.writeUInt8((if (chapterSingle) 0x80 else 0) or (logs.size - 1))
        logs.forEach { log ->
            writer.writeUInt8((if (log.singlePacketLoss) 0x80 else 0) or log.controller)
            val value = when (log.tool) {
                RtpMidiControlRecoveryTool.VALUE -> log.value
                RtpMidiControlRecoveryTool.TOGGLE -> 0x80 or log.value
                RtpMidiControlRecoveryTool.COUNT -> 0xC0 or log.value
            }
            writer.writeUInt8(value)
        }
        return writer.toByteArray()
    }

    private fun encodeChapterN(channel: RtpMidiChannelRecoveryJournal): ByteArray {
        val noteLogs = channel.noteLogs
        val noteOffGroups = channel.noteOffs.map { it / 8 }
        val low: Int
        val high: Int
        if (noteOffGroups.isEmpty()) {
            low = 15
            high = if (noteLogs.size == 128) 0 else 1
        } else {
            low = noteOffGroups.min()
            high = noteOffGroups.max()
        }
        val encodedLength = if (noteLogs.size == 128) 127 else noteLogs.size
        val writer = BigEndianPacketWriter()
        writer.writeUInt8(
            (if (channel.noteOffSinglePacketLoss) 0x80 else 0) or encodedLength,
        )
        writer.writeUInt8((low shl 4) or high)
        noteLogs.forEach { log ->
            writer.writeUInt8((if (log.singlePacketLoss) 0x80 else 0) or log.note)
            writer.writeUInt8((if (log.playOnRecovery) 0x80 else 0) or log.velocity)
        }
        if (noteOffGroups.isNotEmpty()) {
            for (group in low..high) {
                var bits = 0
                for (offset in 0..7) {
                    if ((group * 8 + offset) in channel.noteOffs) {
                        bits = bits or (0x80 ushr offset)
                    }
                }
                writer.writeUInt8(bits)
            }
        }
        return writer.toByteArray()
    }

    private fun decodeChannel(reader: BigEndianPacketReader): RtpMidiChannelRecoveryJournal {
        if (reader.remaining < 3) throw AppleMidiProtocolException("Truncated channel journal")
        val first = reader.readUInt8()
        val lengthLow = reader.readUInt8()
        val toc = reader.readUInt8()
        val length = ((first and CHANNEL_LENGTH_HIGH_MASK) shl 8) or lengthLow
        if (length < 3 || reader.remaining < length - 3) {
            throw AppleMidiProtocolException("Invalid channel journal length")
        }
        val payload = reader.readBytes(length - 3)
        if ((first and CHANNEL_ENHANCED_CONTROL) != 0) {
            throw AppleMidiProtocolException("Enhanced Chapter C channel is not supported")
        }
        if ((toc and CHANNEL_TOC_SUPPORTED.inv()) != 0) {
            throw AppleMidiProtocolException("Unsupported recovery channel chapter")
        }
        val channelReader = BigEndianPacketReader(payload, 0, payload.size)
        val controls = if ((toc and CHANNEL_TOC_CONTROL) != 0) {
            decodeChapterC(channelReader)
        } else {
            emptyList()
        }
        val note = if ((toc and CHANNEL_TOC_NOTE) != 0) {
            decodeChapterN(channelReader)
        } else {
            DecodedChapterN(emptyList(), emptySet(), true)
        }
        channelReader.requireFinished("Unexpected bytes after supported recovery chapters")
        return RtpMidiChannelRecoveryJournal(
            channel = (first ushr 3) and 0x0F,
            singlePacketLoss = (first and CHANNEL_SINGLE_PACKET) != 0,
            controlLogs = controls,
            noteLogs = note.noteLogs,
            noteOffs = note.noteOffs,
            noteOffSinglePacketLoss = note.singlePacketLoss,
        )
    }

    private fun decodeChapterC(reader: BigEndianPacketReader): List<RtpMidiControlJournalLog> {
        if (reader.remaining == 0) throw AppleMidiProtocolException("Truncated Chapter C")
        val header = reader.readUInt8()
        val count = (header and 0x7F) + 1
        if (reader.remaining < count * 2) throw AppleMidiProtocolException("Truncated Chapter C logs")
        return List(count) {
            val controllerByte = reader.readUInt8()
            val valueByte = reader.readUInt8()
            val alternate = (valueByte and 0x80) != 0
            val tool = when {
                !alternate -> RtpMidiControlRecoveryTool.VALUE
                (valueByte and 0x40) == 0 -> RtpMidiControlRecoveryTool.TOGGLE
                else -> RtpMidiControlRecoveryTool.COUNT
            }
            RtpMidiControlJournalLog(
                controller = controllerByte and 0x7F,
                tool = tool,
                value = if (alternate) valueByte and 0x3F else valueByte and 0x7F,
                singlePacketLoss = (controllerByte and 0x80) != 0,
            )
        }
    }

    private data class DecodedChapterN(
        val noteLogs: List<RtpMidiNoteJournalLog>,
        val noteOffs: Set<Int>,
        val singlePacketLoss: Boolean,
    )

    private fun decodeChapterN(reader: BigEndianPacketReader): DecodedChapterN {
        if (reader.remaining < 2) throw AppleMidiProtocolException("Truncated Chapter N")
        val first = reader.readUInt8()
        val range = reader.readUInt8()
        val low = range ushr 4
        val high = range and 0x0F
        val emptyOffBits = low == 15 && (high == 0 || high == 1)
        if (low > high && !emptyOffBits) {
            throw AppleMidiProtocolException("Invalid Chapter N Note Off range")
        }
        val noteCount = if ((first and 0x7F) == 127 && low == 15 && high == 0) {
            128
        } else {
            first and 0x7F
        }
        val offBytes = if (emptyOffBits) 0 else high - low + 1
        if (reader.remaining < noteCount * 2 + offBytes) {
            throw AppleMidiProtocolException("Truncated Chapter N data")
        }
        val noteLogs = List(noteCount) {
            val note = reader.readUInt8()
            val velocity = reader.readUInt8()
            RtpMidiNoteJournalLog(
                note = note and 0x7F,
                velocity = velocity and 0x7F,
                playOnRecovery = (velocity and 0x80) != 0,
                singlePacketLoss = (note and 0x80) != 0,
            )
        }
        val noteOffs = LinkedHashSet<Int>()
        repeat(offBytes) { groupOffset ->
            val bits = reader.readUInt8()
            repeat(8) { bit ->
                if ((bits and (0x80 ushr bit)) != 0) {
                    noteOffs += (low + groupOffset) * 8 + bit
                }
            }
        }
        return DecodedChapterN(
            noteLogs = noteLogs,
            noteOffs = noteOffs,
            singlePacketLoss = (first and 0x80) != 0,
        )
    }

}

/** Closed-loop sender state for the critical Chapter N + Chapter C journal subset. */
internal class RtpMidiRecoveryJournalSender {
    private data class NoteState(
        val active: Boolean,
        val velocity: Int,
        val sequence: Long,
        val insertionOrder: Long,
    )

    private data class ControlState(
        val controller: Int,
        val tool: RtpMidiControlRecoveryTool,
        val value: Int,
        val criticalRelease: Boolean,
        val sequence: Long,
        val insertionOrder: Long,
    )

    private val notes = Array(16) { arrayOfNulls<NoteState>(128) }
    private val controls = Array(16) { LinkedHashMap<Int, ControlState>() }
    private val sustainDown = BooleanArray(16)
    private val sustainToggleCount = IntArray(16)
    private val allSoundOffCount = IntArray(16)
    private val resetAllControllersCount = IntArray(16)
    private val allNotesOffCount = IntArray(16)
    private val channelModeCounts = Array(4) { IntArray(16) }
    private val lastNoteOffPacket = LongArray(16) { Long.MIN_VALUE }
    private var nextInsertionOrder = 0L
    private var firstSentExtendedSequence: Long? = null
    private var highestSentExtendedSequence: Long? = null
    private var acknowledgedExtendedSequence: Long? = null
    private var lastPacketSentNanos: Long? = null

    fun journalForPacket(packetExtendedSequence: Long): ByteArray? {
        if (!hasUnacknowledgedState) return null
        val checkpoint = (acknowledgedExtendedSequence?.plus(1) ?: firstSentExtendedSequence)
            ?: return null
        val previousSequence = packetExtendedSequence - 1
        val channels = buildList {
            for (channel in 0 until 16) {
                val controlLogs = controls[channel].values
                    .sortedBy { it.insertionOrder }
                    .map { state ->
                        RtpMidiControlJournalLog(
                            controller = state.controller,
                            tool = state.tool,
                            value = state.value,
                            singlePacketLoss = state.sequence != previousSequence,
                        )
                    }
                val noteLogs = notes[channel]
                    .mapIndexedNotNull { note, state ->
                        state?.takeIf(NoteState::active)?.let { note to it }
                    }
                    .sortedBy { it.second.insertionOrder }
                    .map { (note, state) ->
                        RtpMidiNoteJournalLog(
                            note = note,
                            velocity = state.velocity,
                            playOnRecovery = false,
                            singlePacketLoss = state.sequence != previousSequence,
                        )
                    }
                val noteOffs = buildSet {
                    notes[channel].forEachIndexed { note, state ->
                        if (state != null && !state.active) add(note)
                    }
                }
                if (controlLogs.isNotEmpty() || noteLogs.isNotEmpty() || noteOffs.isNotEmpty()) {
                    val hasChapterN = noteLogs.isNotEmpty() || noteOffs.isNotEmpty()
                    val noteOffSingle = !hasChapterN ||
                        lastNoteOffPacket[channel] != previousSequence
                    val channelSingle = controlLogs.all { it.singlePacketLoss } &&
                        noteLogs.all { it.singlePacketLoss } && noteOffSingle
                    add(
                        RtpMidiChannelRecoveryJournal(
                            channel = channel,
                            singlePacketLoss = channelSingle,
                            controlLogs = controlLogs,
                            noteLogs = noteLogs,
                            noteOffs = noteOffs,
                            noteOffSinglePacketLoss = noteOffSingle,
                        ),
                    )
                }
            }
        }
        return RtpMidiRecoveryJournalCodec.encode(
            RtpMidiRecoveryJournal(
                checkpointSequenceNumber = (checkpoint and 0xFFFF).toInt(),
                singlePacketLoss = channels.all { it.singlePacketLoss },
                channels = channels,
            ),
        )
    }

    fun recordPacket(
        packetExtendedSequence: Long,
        messages: List<MidiChannelMessage>,
        sentAtNanos: Long,
    ) {
        reservePacketSequence(packetExtendedSequence)
        lastPacketSentNanos = sentAtNanos
        messages.forEach { message -> observe(message, packetExtendedSequence) }
        acknowledgedExtendedSequence?.let(::pruneThrough)
    }

    /** Makes an in-flight sequence visible so a loopback-fast RS cannot race packet recording. */
    fun reservePacketSequence(packetExtendedSequence: Long) {
        if (firstSentExtendedSequence == null) firstSentExtendedSequence = packetExtendedSequence
        highestSentExtendedSequence = maxOf(
            highestSentExtendedSequence ?: packetExtendedSequence,
            packetExtendedSequence,
        )
    }

    fun acknowledge(sequenceNumber: Int): Long? {
        val highest = highestSentExtendedSequence ?: return null
        val extended = unwrapSequenceNear(sequenceNumber, highest)
        val previous = acknowledgedExtendedSequence
        if (extended > highest || previous != null && extended <= previous) return previous
        acknowledgedExtendedSequence = extended
        pruneThrough(extended)
        return extended
    }

    private fun pruneThrough(extended: Long) {
        notes.forEach { channel ->
            channel.indices.forEach { note ->
                if (channel[note]?.sequence?.let { it <= extended } == true) channel[note] = null
            }
        }
        controls.forEach { channel ->
            channel.entries.removeAll { it.value.sequence <= extended }
        }
    }

    fun resetHistoryForPanic(nextPacketExtendedSequence: Long) {
        notes.forEach { channel -> channel.fill(null) }
        controls.forEach { channel -> channel.clear() }
        firstSentExtendedSequence = nextPacketExtendedSequence
        acknowledgedExtendedSequence = nextPacketExtendedSequence - 1
    }

    fun heartbeatDue(nowNanos: Long, intervalNanos: Long): Boolean {
        val last = lastPacketSentNanos ?: return false
        return hasUnacknowledgedState && nowNanos - last >= intervalNanos
    }

    val hasUnacknowledgedState: Boolean
        get() = notes.any { channel -> channel.any { it != null } } || controls.any { it.isNotEmpty() }

    /** Pending state that can stop or release sounding notes and merits an accelerated heartbeat. */
    val hasUnacknowledgedCriticalRelease: Boolean
        get() = notes.any { channel -> channel.any { state -> state?.active == false } } ||
            controls.any { channel -> channel.values.any(ControlState::criticalRelease) }

    val highestSentSequence: Long?
        get() = highestSentExtendedSequence

    private fun observe(message: MidiChannelMessage, sequence: Long) {
        val channel = message.channel
        when (message.messageType) {
            0x80 -> recordNote(channel, message.data1, false, 0, sequence)
            0x90 -> recordNote(
                channel,
                message.data1,
                active = message.data2 != 0,
                velocity = message.data2 ?: 0,
                sequence = sequence,
            )
            0xB0 -> observeControl(channel, message.data1, message.data2 ?: return, sequence)
        }
    }

    private fun recordNote(
        channel: Int,
        note: Int,
        active: Boolean,
        velocity: Int,
        sequence: Long,
    ) {
        notes[channel][note] = NoteState(active, velocity, sequence, nextInsertionOrder++)
        if (!active) lastNoteOffPacket[channel] = sequence
    }

    private fun observeControl(channel: Int, controller: Int, value: Int, sequence: Long) {
        if (controller == 120 || controller in 123..127) notes[channel].fill(null)
        if (controller == 121 && sustainDown[channel]) {
            sustainDown[channel] = false
            sustainToggleCount[channel] = (sustainToggleCount[channel] + 1) and 0x3F
        }
        when (controller) {
            64 -> {
                val nextDown = value >= 64
                if (nextDown != sustainDown[channel]) {
                    sustainDown[channel] = nextDown
                    sustainToggleCount[channel] = (sustainToggleCount[channel] + 1) and 0x3F
                }
                controls[channel][controller] = ControlState(
                    controller = controller,
                    tool = RtpMidiControlRecoveryTool.TOGGLE,
                    value = sustainToggleCount[channel],
                    criticalRelease = !nextDown,
                    sequence = sequence,
                    insertionOrder = nextInsertionOrder++,
                )
            }
            120 -> {
                allSoundOffCount[channel] = (allSoundOffCount[channel] + 1) and 0x3F
                controls[channel][controller] = ControlState(
                    controller = controller,
                    tool = RtpMidiControlRecoveryTool.COUNT,
                    value = allSoundOffCount[channel],
                    criticalRelease = true,
                    sequence = sequence,
                    insertionOrder = nextInsertionOrder++,
                )
            }
            121 -> {
                resetAllControllersCount[channel] =
                    (resetAllControllersCount[channel] + 1) and 0x3F
                controls[channel][controller] = ControlState(
                    controller = controller,
                    tool = RtpMidiControlRecoveryTool.COUNT,
                    value = resetAllControllersCount[channel],
                    criticalRelease = true,
                    sequence = sequence,
                    insertionOrder = nextInsertionOrder++,
                )
            }
            123 -> {
                allNotesOffCount[channel] = (allNotesOffCount[channel] + 1) and 0x3F
                controls[channel][controller] = ControlState(
                    controller = controller,
                    tool = RtpMidiControlRecoveryTool.COUNT,
                    value = allNotesOffCount[channel],
                    criticalRelease = true,
                    sequence = sequence,
                    insertionOrder = nextInsertionOrder++,
                )
            }
            in 124..127 -> {
                val counts = channelModeCounts[controller - 124]
                counts[channel] = (counts[channel] + 1) and 0x3F
                controls[channel][controller] = ControlState(
                    controller = controller,
                    tool = RtpMidiControlRecoveryTool.COUNT,
                    value = counts[channel],
                    criticalRelease = true,
                    sequence = sequence,
                    insertionOrder = nextInsertionOrder++,
                )
            }
        }
    }
}

internal data class RtpMidiRecoveryResult(
    val messages: List<ByteArray>,
    val journalApplied: Boolean,
)

/** Receiver-side comparison state used when a grace-period gap becomes a real packet loss. */
internal class RtpMidiRecoveryJournalReceiver {
    private val activeNotes = Array(16) { BooleanArray(128) }
    private val sustainDown = BooleanArray(16)
    private val outputSustainDown = BooleanArray(16)
    private val sustainToggleCount = IntArray(16)
    private val allSoundOffCount = IntArray(16)
    private val resetAllControllersCount = IntArray(16)
    private val allNotesOffCount = IntArray(16)
    private val channelModeCounts = Array(4) { IntArray(16) }

    fun observe(messages: List<MidiChannelMessage>) {
        messages.forEach(::observe)
    }

    fun forceReleasedState() {
        activeNotes.forEach { it.fill(false) }
        outputSustainDown.fill(false)
    }

    fun recover(
        journalBytes: ByteArray?,
        missingExtendedSequence: Long,
        journalPacketExtendedSequence: Long,
    ): RtpMidiRecoveryResult {
        val journal = RtpMidiRecoveryJournalCodec.decodeOrNull(journalBytes)
            ?: return panicResult()
        val checkpoint = unwrapSequenceNear(
            journal.checkpointSequenceNumber,
            journalPacketExtendedSequence,
        )
        if (checkpoint > missingExtendedSequence || checkpoint > journalPacketExtendedSequence) {
            return panicResult()
        }
        val repairs = ArrayList<ByteArray>()
        journal.channels.forEach { channelJournal ->
            val channel = channelJournal.channel
            channelJournal.controlLogs.forEach { log ->
                when (log.controller) {
                    64 -> recoverSustain(channel, log, repairs)
                    120 -> recoverCountedControl(
                        channel,
                        log,
                        allSoundOffCount,
                        repairs,
                    )
                    121 -> recoverResetAllControllers(channel, log, repairs)
                    123 -> recoverCountedControl(
                        channel,
                        log,
                        allNotesOffCount,
                        repairs,
                    )
                    in 124..127 -> recoverCountedControl(
                        channel,
                        log,
                        channelModeCounts[log.controller - 124],
                        repairs,
                    )
                }
            }
            channelJournal.noteOffs.sorted().forEach { note ->
                if (activeNotes[channel][note]) {
                    repairs += byteArrayOf((0x80 or channel).toByte(), note.toByte(), 0)
                }
                activeNotes[channel][note] = false
            }
            channelJournal.noteLogs.forEach { noteLog ->
                if (!activeNotes[channel][noteLog.note] && noteLog.playOnRecovery) {
                    repairs += byteArrayOf(
                        (0x90 or channel).toByte(),
                        noteLog.note.toByte(),
                        noteLog.velocity.toByte(),
                    )
                }
                activeNotes[channel][noteLog.note] = true
            }
        }
        return RtpMidiRecoveryResult(repairs, journalApplied = true)
    }

    private fun observe(message: MidiChannelMessage) {
        val channel = message.channel
        when (message.messageType) {
            0x80 -> activeNotes[channel][message.data1] = false
            0x90 -> activeNotes[channel][message.data1] = message.data2 != 0
            0xB0 -> {
                val value = message.data2 ?: return
                when (message.data1) {
                    64 -> {
                        val nextDown = value >= 64
                        if (nextDown != sustainDown[channel]) {
                            sustainDown[channel] = nextDown
                            sustainToggleCount[channel] =
                                (sustainToggleCount[channel] + 1) and 0x3F
                        }
                        outputSustainDown[channel] = nextDown
                    }
                    120 -> {
                        allSoundOffCount[channel] = (allSoundOffCount[channel] + 1) and 0x3F
                        activeNotes[channel].fill(false)
                    }
                    121 -> {
                        resetAllControllersCount[channel] =
                            (resetAllControllersCount[channel] + 1) and 0x3F
                        if (sustainDown[channel]) {
                            sustainDown[channel] = false
                            sustainToggleCount[channel] =
                                (sustainToggleCount[channel] + 1) and 0x3F
                        }
                        outputSustainDown[channel] = false
                    }
                    123 -> {
                        allNotesOffCount[channel] = (allNotesOffCount[channel] + 1) and 0x3F
                        activeNotes[channel].fill(false)
                    }
                    in 124..127 -> {
                        val counts = channelModeCounts[message.data1 - 124]
                        counts[channel] = (counts[channel] + 1) and 0x3F
                        activeNotes[channel].fill(false)
                    }
                }
            }
        }
    }

    private fun recoverSustain(
        channel: Int,
        log: RtpMidiControlJournalLog,
        repairs: MutableList<ByteArray>,
    ) {
        when (log.tool) {
            RtpMidiControlRecoveryTool.TOGGLE -> {
                val missingToggles = (log.value - sustainToggleCount[channel]) and 0x3F
                repeat(missingToggles) {
                    sustainDown[channel] = !sustainDown[channel]
                    syncOutputSustain(channel, sustainDown[channel], repairs)
                }
                val desiredDown = (log.value and 1) != 0
                sustainDown[channel] = desiredDown
                syncOutputSustain(channel, desiredDown, repairs)
                sustainToggleCount[channel] = log.value
            }
            RtpMidiControlRecoveryTool.VALUE -> {
                val desiredDown = log.value >= 64
                sustainDown[channel] = desiredDown
                syncOutputSustain(channel, desiredDown, repairs)
            }
            RtpMidiControlRecoveryTool.COUNT -> Unit
        }
    }

    private fun sustainMessage(channel: Int, down: Boolean): ByteArray = byteArrayOf(
        (0xB0 or channel).toByte(),
        64,
        if (down) 127.toByte() else 0,
    )

    private fun syncOutputSustain(
        channel: Int,
        down: Boolean,
        repairs: MutableList<ByteArray>,
    ) {
        if (outputSustainDown[channel] != down) {
            repairs += sustainMessage(channel, down)
            outputSustainDown[channel] = down
        }
    }

    private fun recoverResetAllControllers(
        channel: Int,
        log: RtpMidiControlJournalLog,
        repairs: MutableList<ByteArray>,
    ) {
        val differs = when (log.tool) {
            RtpMidiControlRecoveryTool.COUNT -> resetAllControllersCount[channel] != log.value
            else -> true
        }
        if (differs) {
            repairs += byteArrayOf((0xB0 or channel).toByte(), 121, 0)
            if (sustainDown[channel]) {
                sustainDown[channel] = false
                sustainToggleCount[channel] = (sustainToggleCount[channel] + 1) and 0x3F
            }
            outputSustainDown[channel] = false
        }
        if (log.tool == RtpMidiControlRecoveryTool.COUNT) {
            resetAllControllersCount[channel] = log.value
        }
    }

    private fun recoverCountedControl(
        channel: Int,
        log: RtpMidiControlJournalLog,
        counts: IntArray,
        repairs: MutableList<ByteArray>,
    ) {
        val differs = when (log.tool) {
            RtpMidiControlRecoveryTool.COUNT -> counts[channel] != log.value
            else -> true
        }
        if (differs) {
            repairs += byteArrayOf((0xB0 or channel).toByte(), log.controller.toByte(), 0)
            activeNotes[channel].fill(false)
        }
        if (log.tool == RtpMidiControlRecoveryTool.COUNT) counts[channel] = log.value
    }

    private fun panicResult(): RtpMidiRecoveryResult {
        val messages = ArrayList<ByteArray>()
        for (channel in 0 until 16) {
            if (outputSustainDown[channel]) {
                messages += byteArrayOf((0xB0 or channel).toByte(), 64, 0)
            }
            activeNotes[channel].forEachIndexed { note, active ->
                if (active) messages += byteArrayOf((0x80 or channel).toByte(), note.toByte(), 0)
            }
            activeNotes[channel].fill(false)
            outputSustainDown[channel] = false
        }
        return RtpMidiRecoveryResult(messages, journalApplied = false)
    }
}

internal fun unwrapSequenceNear(sequenceNumber: Int, reference: Long): Long {
    require(sequenceNumber in 0..0xFFFF) { "sequenceNumber must fit an unsigned 16-bit value" }
    val base = reference and 0xFFFFL.inv()
    var candidate = base or sequenceNumber.toLong()
    val difference = candidate - reference
    if (difference > 0x8000L) candidate -= 0x1_0000L
    if (difference < -0x8000L) candidate += 0x1_0000L
    return candidate
}
