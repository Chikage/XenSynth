package icu.ringona.xensynth

import org.junit.Assert.assertEquals
import org.junit.Test

class HexStepRangeTest {
    @Test
    fun rangeRunsFromOneToOneBelowEdo() {
        assertEquals(1..11, hexStepRangeForEdo(12))
        assertEquals(1..71, hexStepRangeForEdo(72))
    }

    @Test
    fun lowEdoFallsBackToSingleSafeStep() {
        assertEquals(1..1, hexStepRangeForEdo(0))
        assertEquals(1..1, hexStepRangeForEdo(1))
        assertEquals(1..1, hexStepRangeForEdo(2))
    }
}
