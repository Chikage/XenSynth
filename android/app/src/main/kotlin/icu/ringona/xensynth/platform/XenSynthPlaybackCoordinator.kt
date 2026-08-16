package icu.ringona.xensynth.platform

import icu.ringona.xensynth.audio.NativeAudioEngine
import icu.ringona.xensynth.midi.MidiOutputRouter

internal data class NativePlaybackSnapshot(
    val title: String,
    val durationSeconds: Double,
    val positionSeconds: Double,
    val playing: Boolean,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "title" to title,
        "duration" to durationSeconds,
        "position" to positionSeconds,
        "playing" to playing,
    )
}

/** Shared score player used by both Flutter and the Android media service. */
internal object XenSynthPlaybackCoordinator {
    private val scheduler = NativeScoreScheduler(NativeAudioEngine, MidiOutputRouter)
    private var title = DEFAULT_TITLE
    private var playbackSpeed = 1.0
    private var pitchOffsetCents = 0.0
    private var audioDelayOverrideSeconds: Double? = null

    fun loadScore(arguments: Map<*, *>): Map<String, Any> {
        val result = scheduler.loadScore(arguments)
        title = arguments["title"]?.toString()?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_TITLE
        return result
    }

    fun setLatency(milliseconds: Double) {
        scheduler.setLatency(milliseconds)
    }

    fun play(
        fromSeconds: Double?,
        speed: Double,
        offsetCents: Double,
        audioDelaySeconds: Double?,
    ): Boolean {
        playbackSpeed = speed.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        pitchOffsetCents = offsetCents.takeIf(Double::isFinite) ?: 0.0
        audioDelayOverrideSeconds = audioDelaySeconds?.takeIf(Double::isFinite)
        return scheduler.play(
            fromSeconds = fromSeconds,
            speed = playbackSpeed,
            offsetCents = pitchOffsetCents,
            audioDelayOverrideSeconds = audioDelayOverrideSeconds,
        )
    }

    fun resume(): Boolean = scheduler.play(
        fromSeconds = null,
        speed = playbackSpeed,
        offsetCents = pitchOffsetCents,
        audioDelayOverrideSeconds = audioDelayOverrideSeconds,
    )

    fun pause(): Double = scheduler.pause()

    fun seek(positionSeconds: Double): Double = scheduler.seek(positionSeconds)

    fun stop() = scheduler.stop()

    fun allNotesOff(sendToNetwork: Boolean = true) = scheduler.allNotesOff(sendToNetwork)

    fun snapshot(): NativePlaybackSnapshot = NativePlaybackSnapshot(
        title = title,
        durationSeconds = scheduler.duration(),
        positionSeconds = scheduler.currentPosition(),
        playing = scheduler.isPlaying(),
    )

    fun dispose() = scheduler.dispose()

    private const val DEFAULT_TITLE = "Xen Synth"
}
