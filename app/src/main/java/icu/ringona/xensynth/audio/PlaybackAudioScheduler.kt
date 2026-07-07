package icu.ringona.xensynth.audio

import icu.ringona.xensynth.midi.ParsedScore

interface PlaybackAudioScheduler {
    fun reset(score: ParsedScore?, playheadSeconds: Double)
    fun stop()
    fun ensureStarted(): Boolean
    fun schedule(score: ParsedScore?, playheadSeconds: Double, speed: Double, playing: Boolean)
}
