package icu.ringona.xensynth.audio

import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import kotlin.math.roundToInt

class NativeAudioScheduler(
    private val callbacks: Callbacks,
    private val nativeAudio: NativeAudio = NativeAudioEngine
) : PlaybackAudioScheduler {
    interface Callbacks {
        fun canUseNativeAudio(): Boolean
        fun onAudioReadyChanged(ready: Boolean)
    }

    private var audioCursor = 0
    private val activeNativeNotes = linkedMapOf<NoteKey, ScheduledNote>()

    override fun reset(score: ParsedScore?, playheadSeconds: Double) {
        releaseScheduledNotes()
        audioCursor = score
            ?.notes
            ?.indexOfFirst { it.end > playheadSeconds + AUDIO_EPSILON_SECONDS }
            ?.takeIf { it >= 0 }
            ?: 0
    }

    override fun stop() {
        releaseScheduledNotes()
    }

    private fun releaseScheduledNotes() {
        val scheduledNoteIds = activeNativeNotes.values.map { it.noteId }
        activeNativeNotes.clear()
        scheduledNoteIds.forEach { noteId ->
            runCatching { nativeAudio.noteOffImmediately(noteId) }
        }
    }

    override fun ensureStarted(): Boolean {
        if (runCatching { nativeAudio.isStarted() }.getOrDefault(false)) {
            return true
        }
        val ready = restartNativeAudio()
        callbacks.onAudioReadyChanged(ready)
        return ready
    }

    override fun schedule(
        score: ParsedScore?,
        playheadSeconds: Double,
        speed: Double,
        playing: Boolean
    ) {
        val parsed = score ?: return
        if (!playing || !callbacks.canUseNativeAudio()) {
            return
        }
        if (!runCatching { nativeAudio.isStarted() }.getOrDefault(false)) {
            val ready = restartNativeAudio()
            callbacks.onAudioReadyChanged(ready)
            if (!ready) {
                return
            }
        }
        val endedKeys = activeNativeNotes
            .filterValues { it.note.end <= playheadSeconds + AUDIO_EPSILON_SECONDS }
            .keys
            .toList()
        endedKeys.forEach { key ->
            activeNativeNotes.remove(key)?.let { active ->
                noteOffNativeMode(active.noteId)
            }
        }
        val horizon = playheadSeconds + AUDIO_LOOKAHEAD_SECONDS * speed.coerceAtLeast(MIN_PLAYBACK_SPEED)
        val notes = parsed.notes
        while (audioCursor < notes.size) {
            val note = notes[audioCursor]
            if (note.end <= playheadSeconds + AUDIO_EPSILON_SECONDS) {
                audioCursor++
                continue
            }
            if (note.start > horizon) {
                break
            }
            val playbackKey = playbackKeyForNativeMode(note)
            val key = NoteKey(playbackKey, note.channel, note.track, note.startTick, note.endTick)
            if (!activeNativeNotes.containsKey(key)) {
                val delaySeconds = ((note.start - playheadSeconds) / speed.coerceAtLeast(MIN_PLAYBACK_SPEED))
                    .coerceAtLeast(0.0)
                val noteId = noteOnNativeMode(note, playbackKey, delaySeconds)
                if (noteId == null) {
                    audioCursor++
                    continue
                }
                activeNativeNotes[key] = ScheduledNote(noteId, note)
            }
            audioCursor++
        }
    }

    private fun playbackKeyForNativeMode(note: WaterfallNote): Int {
        return note.pitch.roundToInt().coerceIn(0, 127)
    }

    private fun noteOnNativeMode(
        note: WaterfallNote,
        playbackPitch: Int,
        delaySeconds: Double
    ): Int? {
        return runCatching {
            nativeAudio.noteOn(
                key = playbackPitch,
                velocity = note.velocity,
                cents = ((note.pitch - playbackPitch) * 100.0).toFloat(),
                channel = note.channel,
                program = note.program,
                bankMsb = note.bankMsb,
                bankLsb = note.bankLsb,
                delaySeconds = delaySeconds
            )
        }.getOrNull()
    }

    private fun noteOffNativeMode(noteId: Int) {
        runCatching { nativeAudio.noteOff(noteId) }
    }

    private fun restartNativeAudio(): Boolean {
        return runCatching { nativeAudio.restart() }.getOrDefault(false)
    }

    private data class ScheduledNote(
        val noteId: Int,
        val note: WaterfallNote
    )

    private data class NoteKey(
        val playbackKey: Int,
        val channel: Int,
        val track: Int,
        val startTick: Long,
        val endTick: Long
    )

    private companion object {
        const val AUDIO_LOOKAHEAD_SECONDS = 0.18
        const val AUDIO_EPSILON_SECONDS = 0.002
        const val MIN_PLAYBACK_SPEED = 0.05
    }
}
