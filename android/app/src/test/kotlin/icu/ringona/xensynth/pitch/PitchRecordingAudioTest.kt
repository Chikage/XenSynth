package icu.ringona.xensynth.pitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchRecordingAudioTest {
    @Test
    fun encodesMonoPcmAsStandardWave() {
        val samples = shortArrayOf(0, 1_024, -1_024, Short.MAX_VALUE)

        val wave = PitchRecordingAudio.encodeWave(samples, 16_000)

        assertEquals("RIFF", wave.ascii(0, 4))
        assertEquals("WAVE", wave.ascii(8, 4))
        assertEquals("fmt ", wave.ascii(12, 4))
        assertEquals("data", wave.ascii(36, 4))
        assertEquals(16_000, wave.intAt(24))
        assertEquals(samples.size * Short.SIZE_BYTES, wave.intAt(40))
        assertEquals(44 + samples.size * Short.SIZE_BYTES, wave.size)
    }

    @Test
    fun rendersRecognizedPitchAtItsRecordedTime() {
        val rendered = PitchRecordingAudio.renderRecognizedPitch(
            notes = listOf(
                RecognizedPitchNote(
                    startSeconds = 0.1,
                    endSeconds = 0.4,
                    pitch = 69.0,
                    velocity = 100,
                ),
            ),
            durationSeconds = 0.5,
            sampleRate = 16_000,
        )

        assertEquals(8_000, rendered.size)
        assertTrue(rendered.take(1_600).all { it == 0.toShort() })
        assertTrue(rendered.sliceArray(1_700 until 6_300).any { it != 0.toShort() })
        assertTrue(rendered.takeLast(1_500).all { it == 0.toShort() })
    }

    @Test
    fun ignoresMalformedRecognizedNotes() {
        assertEquals(
            null,
            RecognizedPitchNote.fromMap(mapOf("start" to 0.0, "pitch" to 60.0)),
        )
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        return String(this, offset, length, StandardCharsets.US_ASCII)
    }

    private fun ByteArray.intAt(offset: Int): Int {
        return ByteBuffer.wrap(this, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }
}
