package icu.ringona.xensynth.pitch

import java.nio.ByteBuffer
import kotlin.math.exp

internal class PianoOutputProcessor(
    private val onNote: (pitch: Int, velocity: Int, down: Boolean) -> Unit,
) {
    private val activeNotes = BooleanArray(PIANO_KEY_COUNT)

    fun processLatestFrame(onsetsBuffer: ByteBuffer, framesBuffer: ByteBuffer) {
        // TFLite advances output ByteBuffers to their limit after inference.
        onsetsBuffer.rewind()
        framesBuffer.rewind()
        val onsets = onsetsBuffer.asFloatBuffer()
        val frames = framesBuffer.asFloatBuffer()
        val frameOffset = (OUTPUT_FRAME_COUNT - 1) * PIANO_KEY_COUNT
        for (noteIndex in 0 until PIANO_KEY_COUNT) {
            val onsetProbability = sigmoid(onsets.get(frameOffset + noteIndex))
            val frameProbability = sigmoid(frames.get(frameOffset + noteIndex))
            val probability = maxOf(onsetProbability, frameProbability)
            val wasPressed = activeNotes[noteIndex]
            val midiPitch = MIN_PIANO_MIDI_PITCH + noteIndex
            when {
                !wasPressed && probability >= NOTE_ON_THRESHOLD -> {
                    activeNotes[noteIndex] = true
                    val velocity = (
                        VELOCITY_BASE + (onsetProbability - VELOCITY_ONSET_BASE) * VELOCITY_SCALE
                        ).toInt().coerceIn(1, 127)
                    onNote(midiPitch, velocity, true)
                }
                wasPressed && probability < NOTE_OFF_THRESHOLD -> {
                    activeNotes[noteIndex] = false
                    onNote(midiPitch, 0, false)
                }
            }
        }
    }

    fun releaseActiveNotes() {
        for (noteIndex in activeNotes.indices) {
            if (!activeNotes[noteIndex]) continue
            activeNotes[noteIndex] = false
            onNote(MIN_PIANO_MIDI_PITCH + noteIndex, 0, false)
        }
    }

    private fun sigmoid(value: Float): Float {
        return if (value >= 0f) {
            (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
        } else {
            val exponential = exp(value.toDouble())
            (exponential / (1.0 + exponential)).toFloat()
        }
    }

    companion object {
        const val OUTPUT_FRAME_COUNT = 32
        const val PIANO_KEY_COUNT = 88
        const val OUTPUT_VALUE_COUNT = OUTPUT_FRAME_COUNT * PIANO_KEY_COUNT
        const val MIN_PIANO_MIDI_PITCH = 21
        const val NOTE_ON_THRESHOLD = 0.94f
        const val NOTE_OFF_THRESHOLD = 0.5f
        const val VELOCITY_BASE = 27f
        const val VELOCITY_ONSET_BASE = 0.8f
        const val VELOCITY_SCALE = 500f
    }
}
