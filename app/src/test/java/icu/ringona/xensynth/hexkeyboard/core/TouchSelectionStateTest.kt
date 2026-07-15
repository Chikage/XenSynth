package icu.ringona.xensynth.hexkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchSelectionStateTest {
    private val first = AxialCoordinate(q = 0, r = 0)
    private val second = AxialCoordinate(q = 1, r = 0)
    private val third = AxialCoordinate(q = 0, r = 1)

    @Test
    fun overlappingPointersKeepTwoCoordinatesSelected() {
        val state = TouchSelectionState()
            .press(pointerId = 10L, coordinate = first, eventTimeMillis = 100L)
            .press(pointerId = 20L, coordinate = second, eventTimeMillis = 400L)

        assertEquals(setOf(first, second), state.selectedCoordinates)
        assertEquals(second, state.anchorCoordinate)
    }

    @Test
    fun slightlyAsynchronousPressJoinsRecentlyReleasedChord() {
        val state = TouchSelectionState()
            .press(pointerId = 10L, coordinate = first, eventTimeMillis = 100L)
            .release(
                pointerId = 10L,
                eventTimeMillis = 180L,
                retainForChord = true,
            )
            .press(pointerId = 20L, coordinate = second, eventTimeMillis = 300L)

        assertEquals(setOf(first, second), state.selectedCoordinates)
        assertEquals(mapOf(20L to second), state.coordinatesByPointer)
    }

    @Test
    fun pressOutsideGraceWindowStartsNewChord() {
        val state = TouchSelectionState()
            .press(pointerId = 10L, coordinate = first, eventTimeMillis = 100L)
            .release(
                pointerId = 10L,
                eventTimeMillis = 180L,
                retainForChord = true,
            )
            .press(pointerId = 20L, coordinate = second, eventTimeMillis = 400L)

        assertEquals(setOf(second), state.selectedCoordinates)
        assertEquals(second, state.anchorCoordinate)
    }

    @Test
    fun slidingPointerReplacesOldCoordinateWithoutLeavingTrail() {
        val state = TouchSelectionState()
            .press(pointerId = 10L, coordinate = first, eventTimeMillis = 100L)
            .press(pointerId = 20L, coordinate = second, eventTimeMillis = 110L)
            .release(
                pointerId = 10L,
                eventTimeMillis = 150L,
                retainForChord = false,
            )
            .press(pointerId = 10L, coordinate = third, eventTimeMillis = 150L)

        assertEquals(setOf(second, third), state.selectedCoordinates)
        assertEquals(third, state.anchorCoordinate)
    }

    @Test
    fun releasingOnePointerKeepsRemainingChordSelectionsLatched() {
        val state = TouchSelectionState()
            .press(pointerId = 10L, coordinate = first, eventTimeMillis = 100L)
            .press(pointerId = 20L, coordinate = second, eventTimeMillis = 110L)
            .release(
                pointerId = 20L,
                eventTimeMillis = 180L,
                retainForChord = true,
            )

        assertEquals(setOf(first, second), state.selectedCoordinates)
        assertEquals(first, state.anchorCoordinate)
    }

    @Test
    fun releasingOneOfTwoPointersOnSameKeyKeepsKeySelected() {
        val state = TouchSelectionState()
            .press(pointerId = 10L, coordinate = first, eventTimeMillis = 100L)
            .press(pointerId = 20L, coordinate = first, eventTimeMillis = 110L)
            .release(
                pointerId = 10L,
                eventTimeMillis = 150L,
                retainForChord = false,
            )

        assertEquals(setOf(first), state.selectedCoordinates)
        assertEquals(mapOf(20L to first), state.coordinatesByPointer)
    }

    @Test
    fun completedChordRemainsSelectedUntilNextSeparateChordStarts() {
        val completedChord = TouchSelectionState()
            .press(pointerId = 10L, coordinate = first, eventTimeMillis = 100L)
            .press(pointerId = 20L, coordinate = second, eventTimeMillis = 110L)
            .release(
                pointerId = 10L,
                eventTimeMillis = 180L,
                retainForChord = true,
            )
            .release(
                pointerId = 20L,
                eventTimeMillis = 190L,
                retainForChord = true,
            )

        assertEquals(setOf(first, second), completedChord.selectedCoordinates)

        val nextChord = completedChord.press(
            pointerId = 30L,
            coordinate = third,
            eventTimeMillis = 400L,
        )
        assertEquals(setOf(third), nextChord.selectedCoordinates)
    }
}


