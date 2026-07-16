package icu.ringona.xensynth.platform

import android.view.Choreographer
import icu.ringona.xensynth.audio.NativeAudio
import icu.ringona.xensynth.audio.NativeAudioEngine
import kotlin.math.max
import kotlin.math.roundToInt

internal data class PlatformScoreNote(
    val start: Double,
    val end: Double,
    val pitch: Double,
    val velocity: Int,
    val channel: Int,
    val program: Int,
    val bankMsb: Int,
    val bankLsb: Int,
    val track: Int,
)

/**
 * Keeps score-time scheduling on Android's vsync clock while FluidSynth performs
 * the sample-accurate delayed note starts in the native audio callback.
 */
internal class NativeScoreScheduler(
    private val nativeAudio: NativeAudio = NativeAudioEngine,
) {
    private var notes: List<PlatformScoreNote> = emptyList()
    private var durationSeconds = 0.0
    private var positionSeconds = 0.0
    private var basePositionSeconds = 0.0
    private var baseTimeNanos = 0L
    private var playbackSpeed = 1.0
    private var pitchOffsetCents = 0.0
    private var audioStartDelaySeconds = 0.0
    private var latencyMilliseconds = 0.0
    private var audioCursor = 0
    private var playing = false
    private var framePosted = false
    private val activeNotes = linkedMapOf<Int, ActiveScheduledNote>()

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        handleFrame(frameTimeNanos)
    }

    fun loadScore(arguments: Map<*, *>): Map<String, Any> {
        stop()
        val rawNotes = arguments["notes"] as? List<*> ?: emptyList<Any?>()
        notes = rawNotes.mapNotNull(::parseNote)
            .sortedWith(compareBy<PlatformScoreNote> { it.start }.thenBy { it.pitch })
        val inferredDuration = notes.maxOfOrNull { it.end } ?: 0.0
        durationSeconds = number(arguments, "duration")
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: inferredDuration
        positionSeconds = 0.0
        basePositionSeconds = 0.0
        audioCursor = 0
        return mapOf(
            "noteCount" to notes.size,
            "duration" to durationSeconds,
        )
    }

    fun setLatency(milliseconds: Double) {
        latencyMilliseconds = milliseconds.takeIf(Double::isFinite) ?: 0.0
    }

    fun play(
        fromSeconds: Double?,
        speed: Double,
        offsetCents: Double,
        audioDelayOverrideSeconds: Double?,
    ): Boolean {
        if (!nativeAudio.hasSoundFont()) return false
        if (!nativeAudio.isStarted() && !nativeAudio.restart()) return false

        val now = System.nanoTime()
        releaseScheduledNotes(immediate = true)
        playbackSpeed = speed.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
            ?: 1.0
        pitchOffsetCents = offsetCents.takeIf(Double::isFinite) ?: 0.0
        audioStartDelaySeconds = audioDelayOverrideSeconds
            ?.takeIf { it.isFinite() }
            ?.coerceAtLeast(0.0)
            ?: (-latencyMilliseconds / 1_000.0).coerceAtLeast(0.0)
        basePositionSeconds = clampPosition(fromSeconds ?: currentPosition(now))
        positionSeconds = basePositionSeconds
        baseTimeNanos = now
        playing = true
        resetCursor(audioPosition(now))
        scheduleAt(audioPosition(now))
        postFrame()
        return true
    }

    fun pause(): Double {
        val now = System.nanoTime()
        positionSeconds = currentPosition(now)
        playing = false
        removeFrame()
        releaseScheduledNotes(immediate = true)
        return positionSeconds
    }

    fun seek(position: Double): Double {
        val next = clampPosition(position)
        val wasPlaying = playing
        val now = System.nanoTime()
        positionSeconds = next
        basePositionSeconds = next
        baseTimeNanos = now
        releaseScheduledNotes(immediate = true)
        resetCursor(audioPosition(now))
        if (wasPlaying) {
            scheduleAt(audioPosition(now))
            postFrame()
        }
        return next
    }

    fun stop() {
        playing = false
        positionSeconds = 0.0
        basePositionSeconds = 0.0
        baseTimeNanos = 0L
        removeFrame()
        releaseScheduledNotes(immediate = true)
        audioCursor = 0
    }

    fun currentPosition(): Double = currentPosition(System.nanoTime())

    fun allNotesOff() {
        activeNotes.clear()
        nativeAudio.allSoundOff()
    }

    fun dispose() {
        stop()
    }

    private fun handleFrame(frameTimeNanos: Long) {
        if (!playing) return

        positionSeconds = currentPosition(frameTimeNanos)
        val audioPosition = audioPosition(frameTimeNanos)
        scheduleAt(audioPosition)

        val logicalFinished = durationSeconds <= 0.0 || positionSeconds >= durationSeconds
        val audioFinished = durationSeconds <= 0.0 || audioPosition >= durationSeconds
        if (logicalFinished && audioFinished) {
            playing = false
            positionSeconds = durationSeconds.coerceAtLeast(0.0)
            releaseScheduledNotes(immediate = false)
            return
        }
        postFrame()
    }

    private fun scheduleAt(audioPositionSeconds: Double) {
        val ended = activeNotes
            .filterValues { it.note.end <= audioPositionSeconds + AUDIO_EPSILON_SECONDS }
            .keys
            .toList()
        ended.forEach { index ->
            activeNotes.remove(index)?.let { active -> nativeAudio.noteOff(active.noteId) }
        }

        val horizon = audioPositionSeconds + AUDIO_LOOKAHEAD_SECONDS * playbackSpeed
        while (audioCursor < notes.size) {
            val index = audioCursor
            val note = notes[index]
            if (note.end <= audioPositionSeconds + AUDIO_EPSILON_SECONDS) {
                audioCursor++
                continue
            }
            if (note.start > horizon) break

            if (!activeNotes.containsKey(index)) {
                val renderedPitch = note.pitch + pitchOffsetCents / 100.0
                val key = renderedPitch.roundToInt()
                if (renderedPitch.isFinite() && key in MIDI_KEY_MIN..MIDI_KEY_MAX) {
                    val delaySeconds = ((note.start - audioPositionSeconds) / playbackSpeed)
                        .coerceAtLeast(0.0)
                    nativeAudio.noteOn(
                        key = key,
                        velocity = note.velocity,
                        cents = ((renderedPitch - key) * 100.0).toFloat(),
                        channel = note.channel,
                        program = note.program,
                        bankMsb = note.bankMsb,
                        bankLsb = note.bankLsb,
                        delaySeconds = delaySeconds,
                    )?.let { noteId ->
                        activeNotes[index] = ActiveScheduledNote(noteId, note)
                    }
                }
            }
            audioCursor++
        }
    }

    private fun releaseScheduledNotes(immediate: Boolean) {
        val noteIds = activeNotes.values.map { it.noteId }
        activeNotes.clear()
        noteIds.forEach { noteId ->
            if (immediate) {
                nativeAudio.noteOffImmediately(noteId)
            } else {
                nativeAudio.noteOff(noteId)
            }
        }
    }

    private fun resetCursor(audioPositionSeconds: Double) {
        audioCursor = notes.indexOfFirst { it.end > audioPositionSeconds + AUDIO_EPSILON_SECONDS }
            .takeIf { it >= 0 }
            ?: notes.size
    }

    private fun currentPosition(nowNanos: Long): Double {
        if (!playing || baseTimeNanos == 0L) return clampPosition(positionSeconds)
        val elapsed = ((nowNanos - baseTimeNanos).coerceAtLeast(0L) / NANOS_PER_SECOND)
        return clampPosition(basePositionSeconds + elapsed * playbackSpeed)
    }

    private fun audioPosition(nowNanos: Long): Double {
        val elapsed = if (baseTimeNanos == 0L) {
            0.0
        } else {
            (nowNanos - baseTimeNanos).coerceAtLeast(0L) / NANOS_PER_SECOND
        }
        return basePositionSeconds - audioStartDelaySeconds * playbackSpeed + elapsed * playbackSpeed
    }

    private fun clampPosition(value: Double): Double {
        val safe = value.takeIf(Double::isFinite) ?: 0.0
        return if (durationSeconds > 0.0) {
            safe.coerceIn(MIN_PLAYHEAD_SECONDS, durationSeconds)
        } else {
            max(MIN_PLAYHEAD_SECONDS, safe)
        }
    }

    private fun postFrame() {
        if (!framePosted) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun removeFrame() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePosted = false
        }
    }

    private data class ActiveScheduledNote(
        val noteId: Int,
        val note: PlatformScoreNote,
    )

    private companion object {
        const val AUDIO_LOOKAHEAD_SECONDS = 0.18
        const val AUDIO_EPSILON_SECONDS = 0.002
        const val MIN_PLAYBACK_SPEED = 0.05
        const val MAX_PLAYBACK_SPEED = 8.0
        const val MIN_PLAYHEAD_SECONDS = -1.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MIDI_KEY_MIN = 0
        const val MIDI_KEY_MAX = 127

        fun parseNote(value: Any?): PlatformScoreNote? {
            val map = value as? Map<*, *> ?: return null
            val start = number(map, "start", "startSeconds")
                ?.takeIf(Double::isFinite)
                ?.coerceAtLeast(MIN_PLAYHEAD_SECONDS)
                ?: return null
            val end = number(map, "end", "endSeconds")
                ?.takeIf(Double::isFinite)
                ?.coerceAtLeast(start)
                ?: start
            val pitch = number(map, "pitch", "audioPitch")
                ?: number(map, "midiPitch")?.let { midiPitch ->
                    midiPitch + (number(map, "cents") ?: 0.0) / 100.0
                }
                ?: return null
            if (!pitch.isFinite()) return null
            return PlatformScoreNote(
                start = start,
                end = end,
                pitch = pitch,
                velocity = integer(map, "velocity", defaultValue = 80).coerceIn(1, 127),
                channel = integer(map, "channel", defaultValue = 0).coerceIn(0, 15),
                program = integer(map, "program", defaultValue = 0).coerceIn(0, 127),
                bankMsb = integer(map, "bankMsb", defaultValue = 0).coerceIn(0, 127),
                bankLsb = integer(map, "bankLsb", defaultValue = 0).coerceIn(0, 127),
                track = integer(map, "track", defaultValue = 0),
            )
        }

        fun number(map: Map<*, *>, vararg keys: String): Double? {
            keys.forEach { key ->
                val value = map[key]
                when (value) {
                    is Number -> return value.toDouble()
                    is String -> value.toDoubleOrNull()?.let { return it }
                }
            }
            return null
        }

        fun integer(map: Map<*, *>, key: String, defaultValue: Int): Int {
            val value = map[key]
            return when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: defaultValue
                else -> defaultValue
            }
        }
    }
}
