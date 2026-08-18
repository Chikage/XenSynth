package icu.ringona.xensynth.platform

import icu.ringona.rtpmidi.AppleMidiEvent
import icu.ringona.xensynth.audio.NativeAudio
import kotlin.math.roundToInt

/** Owns network-created voices so RTP events bypass Flutter's scheduling latency. */
internal class NetworkMidiAudioScheduler(
    private val nativeAudio: NativeAudio,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class NoteKey(
        val sessionId: String,
        val channel: Int,
        val pitch: Int,
    )

    private data class ReleasingNote(
        val noteId: Int,
        val sessionId: String,
        val channel: Int,
        val forgetAfterNanos: Long,
    )

    private val activeNotes = LinkedHashMap<NoteKey, Int>()
    private val deferredNoteOffs = LinkedHashSet<NoteKey>()
    private val sustainedChannels = LinkedHashSet<Pair<String, Int>>()
    private val releasingNotes = ArrayList<ReleasingNote>()
    private var pitchMap = DoubleArray(MIDI_KEY_COUNT) { it.toDouble() }
    private var program = 0

    @Synchronized
    fun configure(mappedPitches: DoubleArray, program: Int) {
        require(mappedPitches.size == MIDI_KEY_COUNT) { "A MIDI pitch map must contain 128 values" }
        pitchMap = DoubleArray(MIDI_KEY_COUNT) { key ->
            mappedPitches[key].takeIf(Double::isFinite)?.coerceIn(0.0, 127.0)
                ?: key.toDouble()
        }
        this.program = program.coerceIn(0, 127)
    }

    @Synchronized
    fun onMidiEvent(event: AppleMidiEvent): Boolean {
        releasingNotes.removeAll { it.forgetAfterNanos <= nanoTime() }
        val bytes = event.bytes
        if (bytes.isEmpty()) return false
        val status = bytes[0].toInt() and 0xFF
        if (status !in 0x80..0xEF) return false
        val channel = status and 0x0F
        return when (status and 0xF0) {
            NOTE_OFF -> releaseNote(event, channel, bytes.midiData(1))
            NOTE_ON -> {
                val velocity = bytes.midiData(2)
                if (velocity == 0) {
                    releaseNote(event, channel, bytes.midiData(1))
                } else {
                    startNote(event, channel, bytes.midiData(1), velocity)
                }
            }
            CONTROL_CHANGE -> handleControlChange(
                event = event,
                channel = channel,
                controller = bytes.midiData(1),
                value = bytes.midiData(2),
            )
            else -> false
        }
    }

    @Synchronized
    fun releaseSession(sessionId: String) {
        val now = nanoTime()
        activeNotes.keys
            .filter { it.sessionId == sessionId }
            .forEach { key -> releaseActiveNote(key, now, immediate = true) }
        releaseFinishingNotes(sessionId = sessionId, channel = null, targetTimeNanos = now)
        deferredNoteOffs.removeAll { it.sessionId == sessionId }
        sustainedChannels.removeAll { it.first == sessionId }
    }

    @Synchronized
    fun close() {
        val now = nanoTime()
        activeNotes.keys.toList().forEach { key ->
            releaseActiveNote(key, now, immediate = true)
        }
        releasingNotes.forEach { note ->
            nativeAudio.noteOffAt(note.noteId, now, immediate = true)
        }
        releasingNotes.clear()
        deferredNoteOffs.clear()
        sustainedChannels.clear()
    }

    private fun startNote(
        event: AppleMidiEvent,
        channel: Int,
        pitch: Int,
        velocity: Int,
    ): Boolean {
        val key = NoteKey(event.sessionId, channel, pitch)
        releaseActiveNote(key, event.targetTimeNanos, immediate = true)
        deferredNoteOffs.remove(key)

        val mappedPitch = pitchMap[pitch]
        val nativeKey = mappedPitch.roundToInt().coerceIn(0, MIDI_KEY_COUNT - 1)
        val noteId = nativeAudio.noteOnAt(
            key = nativeKey,
            velocity = velocity.coerceIn(1, 127),
            cents = ((mappedPitch - nativeKey) * 100.0).toFloat(),
            channel = 0,
            program = program,
            targetTimeNanos = event.targetTimeNanos,
        ) ?: return false
        activeNotes[key] = noteId
        return true
    }

    private fun releaseNote(event: AppleMidiEvent, channel: Int, pitch: Int): Boolean {
        val key = NoteKey(event.sessionId, channel, pitch)
        if (key !in activeNotes) return false
        return if ((event.sessionId to channel) in sustainedChannels) {
            deferredNoteOffs += key
            true
        } else {
            releaseActiveNote(key, event.targetTimeNanos, immediate = false)
            true
        }
    }

    private fun handleControlChange(
        event: AppleMidiEvent,
        channel: Int,
        controller: Int,
        value: Int,
    ): Boolean = when (controller) {
        SUSTAIN_CONTROLLER -> {
            val sessionChannel = event.sessionId to channel
            if (value >= SUSTAIN_ON_VALUE) {
                sustainedChannels += sessionChannel
            } else {
                sustainedChannels -= sessionChannel
                deferredNoteOffs
                    .filter { it.sessionId == event.sessionId && it.channel == channel }
                    .forEach { key ->
                        releaseActiveNote(key, event.targetTimeNanos, immediate = false)
                    }
            }
            true
        }
        RESET_ALL_CONTROLLERS -> {
            val sessionChannel = event.sessionId to channel
            sustainedChannels -= sessionChannel
            deferredNoteOffs
                .filter { it.sessionId == event.sessionId && it.channel == channel }
                .forEach { key ->
                    releaseActiveNote(key, event.targetTimeNanos, immediate = false)
                }
            true
        }
        ALL_SOUND_OFF_CONTROLLER,
        in ALL_NOTES_OFF_CONTROLLERS -> {
            activeNotes.keys
                .filter { it.sessionId == event.sessionId && it.channel == channel }
                .forEach { key ->
                    releaseActiveNote(key, event.targetTimeNanos, immediate = true)
                }
            releaseFinishingNotes(
                sessionId = event.sessionId,
                channel = channel,
                targetTimeNanos = event.targetTimeNanos,
            )
            deferredNoteOffs.removeAll {
                it.sessionId == event.sessionId && it.channel == channel
            }
            sustainedChannels -= event.sessionId to channel
            true
        }
        else -> false
    }

    private fun releaseActiveNote(key: NoteKey, targetTimeNanos: Long, immediate: Boolean) {
        val noteId = activeNotes.remove(key) ?: return
        deferredNoteOffs.remove(key)
        nativeAudio.noteOffAt(noteId, targetTimeNanos, immediate)
        if (!immediate) {
            releasingNotes += ReleasingNote(
                noteId = noteId,
                sessionId = key.sessionId,
                channel = key.channel,
                forgetAfterNanos = targetTimeNanos.saturatingAdd(RELEASE_TRACKING_NANOS),
            )
        }
    }

    private fun releaseFinishingNotes(
        sessionId: String,
        channel: Int?,
        targetTimeNanos: Long,
    ) {
        val matching = releasingNotes.filter { note ->
            note.sessionId == sessionId && (channel == null || note.channel == channel)
        }
        releasingNotes.removeAll(matching.toSet())
        matching.forEach { note ->
            nativeAudio.noteOffAt(note.noteId, targetTimeNanos, immediate = true)
        }
    }

    private fun Long.saturatingAdd(increment: Long): Long =
        if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

    private fun ByteArray.midiData(index: Int): Int =
        getOrNull(index)?.toInt()?.and(0x7F) ?: 0

    private companion object {
        const val MIDI_KEY_COUNT = 128
        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val CONTROL_CHANGE = 0xB0
        const val SUSTAIN_CONTROLLER = 64
        const val SUSTAIN_ON_VALUE = 64
        const val ALL_SOUND_OFF_CONTROLLER = 120
        const val RESET_ALL_CONTROLLERS = 121
        const val RELEASE_TRACKING_NANOS = 1_000_000_000L
        val ALL_NOTES_OFF_CONTROLLERS = 123..127
    }
}
