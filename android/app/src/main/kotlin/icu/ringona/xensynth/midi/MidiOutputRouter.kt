package icu.ringona.xensynth.midi

import android.media.midi.MidiReceiver
import android.util.Log
import kotlin.math.roundToInt

/** Routes app note events to Android virtual, system, and AppleMIDI outputs. */
internal object MidiOutputRouter {
    private const val TAG = "XenSynthMidiOutput"
    private const val CHANNEL_COUNT = 16
    private const val MIDI_MIN = 0
    private const val MIDI_MAX = 127
    private const val PITCH_BEND_CENTER = 8192
    private const val PITCH_BEND_MAX = 16383
    private const val PITCH_BEND_RANGE_SEMITONES = 2.0

    private val lock = Any()
    private var virtualOutputReceivers: Array<MidiReceiver> = emptyArray()
    private var bluetoothOutputReceivers: Array<MidiReceiver> = emptyArray()
    private var outputEnabled = true
    private var networkOutputEnabled = false
    private var networkSender: ((List<ByteArray>, Long) -> Unit)? = null
    private val activeNotes = LinkedHashMap<Int, ActiveNote>()
    private val activeIds = HashMap<Int, Int>()
    private var nextToken = 1

    fun attach(receivers: Array<MidiReceiver>) {
        synchronized(lock) {
            virtualOutputReceivers = receivers.copyOf()
        }
    }

    fun detach() {
        synchronized(lock) {
            virtualOutputReceivers = emptyArray()
        }
    }

    fun setBluetoothReceivers(receivers: Array<MidiReceiver>) {
        synchronized(lock) {
            bluetoothOutputReceivers = receivers.copyOf()
        }
    }

    fun setOutputEnabled(enabled: Boolean) {
        if (!enabled) allNotesOff()
        synchronized(lock) {
            outputEnabled = enabled
        }
    }

    fun setNetworkOutputEnabled(enabled: Boolean) {
        val safetySender = synchronized(lock) {
            val sender = networkSender.takeIf { networkOutputEnabled && !enabled }
            networkOutputEnabled = enabled
            sender
        }
        safetySender?.invoke(
            (0 until CHANNEL_COUNT).flatMap { channel ->
                listOf(
                    controlChange(channel, 120, 0),
                    controlChange(channel, 123, 0),
                )
            },
            System.nanoTime(),
        )
    }

    fun setNetworkSender(sender: ((List<ByteArray>, Long) -> Unit)?) {
        synchronized(lock) {
            networkSender = sender
        }
    }

    fun close() {
        allNotesOff()
        synchronized(lock) {
            virtualOutputReceivers = emptyArray()
            bluetoothOutputReceivers = emptyArray()
        }
        synchronized(lock) {
            networkSender = null
            networkOutputEnabled = false
        }
    }

    fun noteOn(
        id: Int?,
        pitch: Double,
        velocity: Int,
        channel: Int,
        program: Int,
        bankMsb: Int,
        bankLsb: Int,
        sendToNetwork: Boolean = true,
    ): Int {
        require(pitch.isFinite()) { "MIDI pitch must be finite" }
        val key = pitch.roundToInt().coerceIn(MIDI_MIN, MIDI_MAX)
        val safeChannel = channel.coerceIn(0, CHANNEL_COUNT - 1)
        val safeVelocity = velocity.coerceIn(1, MIDI_MAX)
        val safeProgram = program.coerceIn(0, MIDI_MAX)
        val safeBankMsb = bankMsb.coerceIn(0, MIDI_MAX)
        val safeBankLsb = bankLsb.coerceIn(0, MIDI_MAX)

        val previous: ActiveNote?
        val stolen: ActiveNote?
        val active: ActiveNote
        synchronized(lock) {
            previous = id?.let { activeIds.remove(it) }?.let(activeNotes::remove)
            stolen = if (activeNotes.size >= CHANNEL_COUNT) {
                activeNotes.entries.firstOrNull()?.value?.also { old ->
                    activeNotes.remove(activeNotes.entries.first().key)
                    old.id?.let(activeIds::remove)
                }
            } else {
                null
            }
            val outputChannel = chooseChannel(safeChannel)
            val token = nextToken.also {
                nextToken = if (nextToken == Int.MAX_VALUE) 1 else nextToken + 1
            }
            active = ActiveNote(token, id, outputChannel, key, sendToNetwork)
            activeNotes[token] = active
            id?.let { activeIds[it] = token }
        }

        previous?.let(::sendNoteOff)
        stolen?.let(::sendNoteOff)
        val messages = buildList {
            add(controlChange(active.channel, 0, safeBankMsb))
            add(controlChange(active.channel, 32, safeBankLsb))
            add(byteArrayOf((0xC0 or active.channel).toByte(), safeProgram.toByte()))
            addAll(pitchBendRange(active.channel))
            add(pitchBend(active.channel, pitchBendValue(pitch, key)))
            add(byteArrayOf((0x90 or active.channel).toByte(), key.toByte(), safeVelocity.toByte()))
        }
        sendMessages(messages, sendToNetwork)
        return active.token
    }

    fun noteOff(token: Int, sendToNetwork: Boolean? = null) {
        val active = synchronized(lock) {
            val removed = activeNotes.remove(token)
            removed?.id?.let(activeIds::remove)
            removed
        } ?: return
        sendNoteOff(active, sendToNetwork ?: active.sendToNetwork)
    }

    fun allNotesOff(sendToNetwork: Boolean = true) {
        val notes = synchronized(lock) {
            val snapshot = activeNotes.values.toList()
            activeNotes.clear()
            activeIds.clear()
            snapshot
        }
        notes.forEach { active ->
            sendNoteOff(active, sendToNetwork && active.sendToNetwork)
        }
        sendMessages((0 until CHANNEL_COUNT).map { channel ->
            listOf(
                controlChange(channel, 120, 0),
                controlChange(channel, 123, 0),
            )
        }.flatten(), sendToNetwork)
    }

    private fun chooseChannel(preferred: Int): Int {
        val occupied = activeNotes.values.mapTo(HashSet()) { it.channel }
        if (preferred !in occupied) return preferred
        return (0 until CHANNEL_COUNT).firstOrNull { it !in occupied } ?: preferred
    }

    private fun sendNoteOff(
        active: ActiveNote,
        sendToNetwork: Boolean = active.sendToNetwork,
    ) {
        sendMessages(
            listOf(
                byteArrayOf((0x80 or active.channel).toByte(), active.key.toByte(), 0),
                pitchBend(active.channel, PITCH_BEND_CENTER),
            ),
            sendToNetwork,
        )
    }

    private fun sendMessages(messages: List<ByteArray>, sendToNetwork: Boolean = true) {
        if (messages.isEmpty()) return
        val targets = synchronized(lock) {
            if (!outputEnabled) null else OutputTargets(
                receivers = virtualOutputReceivers + bluetoothOutputReceivers,
                networkSender = networkSender.takeIf { networkOutputEnabled && sendToNetwork },
            )
        } ?: return
        val timestamp = System.nanoTime()
        messages.forEach { message ->
            targets.receivers.forEach { receiver ->
                runCatching { receiver.send(message, 0, message.size, timestamp) }
                    .onFailure { error ->
                        Log.w(TAG, "Could not send MIDI message", error)
                    }
            }
        }
        targets.networkSender?.invoke(messages.map { it.copyOf() }, timestamp)
    }

    private fun controlChange(channel: Int, controller: Int, value: Int): ByteArray = byteArrayOf(
        (0xB0 or channel).toByte(),
        controller.coerceIn(0, MIDI_MAX).toByte(),
        value.coerceIn(0, MIDI_MAX).toByte(),
    )

    private fun pitchBendRange(channel: Int): List<ByteArray> = listOf(
        controlChange(channel, 101, 0),
        controlChange(channel, 100, 0),
        controlChange(channel, 6, PITCH_BEND_RANGE_SEMITONES.roundToInt()),
        controlChange(channel, 38, 0),
        controlChange(channel, 101, 127),
        controlChange(channel, 100, 127),
    )

    private fun pitchBend(channel: Int, value: Int): ByteArray {
        val safeValue = value.coerceIn(0, PITCH_BEND_MAX)
        return byteArrayOf(
            (0xE0 or channel).toByte(),
            (safeValue and 0x7F).toByte(),
            ((safeValue shr 7) and 0x7F).toByte(),
        )
    }

    internal fun pitchBendValue(pitch: Double, key: Int): Int {
        val centsFromKey = (pitch - key) / PITCH_BEND_RANGE_SEMITONES
        return (PITCH_BEND_CENTER + centsFromKey * PITCH_BEND_CENTER)
            .roundToInt()
            .coerceIn(0, PITCH_BEND_MAX)
    }

    private data class ActiveNote(
        val token: Int,
        val id: Int?,
        val channel: Int,
        val key: Int,
        val sendToNetwork: Boolean,
    )

    private data class OutputTargets(
        val receivers: Array<MidiReceiver>,
        val networkSender: ((List<ByteArray>, Long) -> Unit)?,
    )
}
