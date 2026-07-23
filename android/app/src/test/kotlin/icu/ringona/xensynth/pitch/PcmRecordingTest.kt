package icu.ringona.xensynth.pitch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRecordingTest {
    @Test
    fun appendsOnlyCapturedSamplesAndReportsDuration() {
        val recording = PcmRecording(sampleRate = 4)

        recording.append(shortArrayOf(1, 2, 3), count = 2)
        recording.append(shortArrayOf(4, 5), count = 2)

        assertArrayEquals(shortArrayOf(1, 2, 4, 5), recording.snapshot())
        assertEquals(1.0, recording.durationSeconds, 0.000_001)
    }
}
