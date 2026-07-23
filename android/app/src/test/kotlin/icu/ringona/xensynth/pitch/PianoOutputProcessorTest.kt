package icu.ringona.xensynth.pitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PianoOutputProcessorTest {
    @Test
    fun readsTfliteBuffersAfterTheirPositionsAdvanceToTheLimit() {
        val events = mutableListOf<TestNoteEvent>()
        val processor = PianoOutputProcessor { pitch, velocity, down ->
            events += TestNoteEvent(pitch, velocity, down)
        }
        val noteIndex = 48
        val outputIndex =
            (PianoOutputProcessor.OUTPUT_FRAME_COUNT - 1) *
                PianoOutputProcessor.PIANO_KEY_COUNT + noteIndex
        val onsets = outputBuffer(defaultLogit = -10f, outputIndex = outputIndex, outputLogit = 10f)
        val frames = outputBuffer(defaultLogit = -10f, outputIndex = outputIndex, outputLogit = 10f)

        processor.processLatestFrame(onsets, frames)

        assertEquals(1, events.size)
        assertEquals(PianoOutputProcessor.MIN_PIANO_MIDI_PITCH + noteIndex, events.single().pitch)
        assertTrue(events.single().down)
        assertTrue(events.single().velocity > 0)

        events.clear()
        processor.processLatestFrame(outputBuffer(), outputBuffer())

        assertEquals(1, events.size)
        assertFalse(events.single().down)
        assertEquals(0, events.single().velocity)
    }

    private fun outputBuffer(
        defaultLogit: Float = -10f,
        outputIndex: Int? = null,
        outputLogit: Float = defaultLogit,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(
            PianoOutputProcessor.OUTPUT_VALUE_COUNT * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder())
        val values = FloatArray(PianoOutputProcessor.OUTPUT_VALUE_COUNT) { defaultLogit }
        if (outputIndex != null) values[outputIndex] = outputLogit
        buffer.asFloatBuffer().put(values)
        buffer.position(buffer.limit())
        return buffer
    }

    private data class TestNoteEvent(
        val pitch: Int,
        val velocity: Int,
        val down: Boolean,
    )
}
