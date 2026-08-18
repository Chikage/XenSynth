package icu.ringona.xensynth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioEventTimingTest {
    @Test
    fun futureTargetConvertsToNativeDelaySeconds() {
        val timing = nativeAudioEventTiming(
            targetTimeNanos = 1_008_000_000L,
            nowNanos = 1_000_000_000L,
        )

        assertEquals(0.008, timing.delaySeconds, 0.000_000_001)
        assertEquals(0L, timing.lateByNanos)
        assertFalse(timing.isLate)
    }

    @Test
    fun expiredTargetRunsImmediatelyAndReportsLateness() {
        val timing = nativeAudioEventTiming(
            targetTimeNanos = 990_000_000L,
            nowNanos = 1_000_000_000L,
        )

        assertEquals(0.0, timing.delaySeconds, 0.0)
        assertEquals(10_000_000L, timing.lateByNanos)
        assertTrue(timing.isLate)
    }

    @Test
    fun eventWithoutNetworkTargetKeepsImmediatePlaybackCompatibility() {
        assertEquals(
            NativeAudioEventTiming(delaySeconds = 0.0, lateByNanos = 0L),
            nativeAudioEventTiming(targetTimeNanos = null, nowNanos = 42L),
        )
    }
}
