package icu.ringona.xensynth.pitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class RecognizedPitchNote(
    val startSeconds: Double,
    val endSeconds: Double,
    val pitch: Double,
    val velocity: Int,
) {
    companion object {
        fun fromMap(value: Map<*, *>): RecognizedPitchNote? {
            val start = number(value["start"]) ?: return null
            val end = number(value["end"]) ?: return null
            val pitch = number(value["pitch"]) ?: return null
            if (!start.isFinite() || !end.isFinite() || !pitch.isFinite()) return null
            val velocity = number(value["velocity"])?.roundToInt() ?: 96
            return RecognizedPitchNote(
                startSeconds = max(0.0, start),
                endSeconds = max(max(0.0, start), end),
                pitch = pitch.coerceIn(0.0, 127.0),
                velocity = velocity.coerceIn(1, 127),
            )
        }

        private fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}

internal object PitchRecordingAudio {
    fun encodeWave(samples: ShortArray, sampleRate: Int): ByteArray {
        require(sampleRate > 0) { "Sample rate must be positive" }
        val dataSize = samples.size.toLong() * Short.SIZE_BYTES
        require(dataSize <= Int.MAX_VALUE - WAVE_HEADER_SIZE) { "Recording is too large to save" }
        val output = ByteBuffer.allocate(WAVE_HEADER_SIZE + dataSize.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        output.putInt(36 + dataSize.toInt())
        output.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        output.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(1)
        output.putInt(sampleRate)
        output.putInt(sampleRate * Short.SIZE_BYTES)
        output.putShort(Short.SIZE_BYTES.toShort())
        output.putShort(16)
        output.put("data".toByteArray(StandardCharsets.US_ASCII))
        output.putInt(dataSize.toInt())
        samples.forEach { sample -> output.putShort(sample) }
        return output.array()
    }

    fun renderRecognizedPitch(
        notes: List<RecognizedPitchNote>,
        durationSeconds: Double,
        sampleRate: Int,
    ): ShortArray {
        require(sampleRate > 0) { "Sample rate must be positive" }
        val noteDuration = notes.maxOfOrNull { it.endSeconds } ?: 0.0
        val safeDuration = max(
            durationSeconds.takeIf { it.isFinite() } ?: 0.0,
            noteDuration,
        ).coerceAtLeast(0.0)
        val sampleCount = (safeDuration * sampleRate).roundToIntChecked()
        if (sampleCount == 0) return ShortArray(0)

        val mix = FloatArray(sampleCount)
        for (note in notes) {
            val start = (note.startSeconds * sampleRate).roundToInt()
                .coerceIn(0, sampleCount)
            val end = (note.endSeconds * sampleRate).roundToInt()
                .coerceIn(start, sampleCount)
            if (end <= start) continue
            val frequency = 440.0 * 2.0.pow((note.pitch - 69.0) / 12.0)
            if (frequency <= 0 || frequency >= sampleRate * 0.48) continue
            val length = end - start
            val attack = min((sampleRate * ATTACK_SECONDS).roundToInt(), max(1, length / 3))
            val release = min((sampleRate * RELEASE_SECONDS).roundToInt(), max(1, length / 2))
            val gain = BASE_GAIN * sqrt(note.velocity / 127.0)
            val phaseStep = 2.0 * PI * frequency / sampleRate
            for (index in 0 until length) {
                val attackEnvelope = min(1.0, index.toDouble() / attack)
                val releaseEnvelope = min(1.0, (length - index).toDouble() / release)
                val envelope = min(attackEnvelope, releaseEnvelope)
                val phase = phaseStep * index
                val tone = (
                    sin(phase) +
                        SECOND_HARMONIC_GAIN * sin(phase * 2) +
                        THIRD_HARMONIC_GAIN * sin(phase * 3)
                    ) / HARMONIC_NORMALIZATION
                mix[start + index] += (tone * envelope * gain).toFloat()
            }
        }

        var peak = 1.0f
        for (sample in mix) peak = max(peak, abs(sample))
        val normalization = OUTPUT_PEAK / peak
        return ShortArray(sampleCount) { index ->
            (mix[index] * normalization * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun Double.roundToIntChecked(): Int {
        require(this <= Int.MAX_VALUE) { "Recording is too long to save" }
        return roundToInt()
    }

    private const val WAVE_HEADER_SIZE = 44
    private const val ATTACK_SECONDS = 0.008
    private const val RELEASE_SECONDS = 0.065
    private const val BASE_GAIN = 0.32
    private const val SECOND_HARMONIC_GAIN = 0.28
    private const val THIRD_HARMONIC_GAIN = 0.09
    private const val HARMONIC_NORMALIZATION =
        1.0 + SECOND_HARMONIC_GAIN + THIRD_HARMONIC_GAIN
    private const val OUTPUT_PEAK = 0.92f
}
