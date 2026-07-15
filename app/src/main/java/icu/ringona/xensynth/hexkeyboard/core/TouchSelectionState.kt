package icu.ringona.xensynth.hexkeyboard.core

data class TouchSelectionState(
    val coordinatesByPointer: Map<Long, AxialCoordinate> = emptyMap(),
    val latchedCoordinates: Set<AxialCoordinate> = emptySet(),
    val anchorCoordinate: AxialCoordinate? = null,
    val joinDeadlineMillis: Long? = null,
) {
    val selectedCoordinates: Set<AxialCoordinate>
        get() = latchedCoordinates.ifEmpty { anchorCoordinate?.let(::setOf).orEmpty() }

    fun press(
        pointerId: Long,
        coordinate: AxialCoordinate,
        eventTimeMillis: Long,
    ): TouchSelectionState {
        val canJoinCurrentChord = coordinatesByPointer.isNotEmpty() ||
            joinDeadlineMillis?.let { eventTimeMillis <= it } == true
        val previousCoordinate = coordinatesByPointer[pointerId]
        val heldByAnotherPointer = previousCoordinate != null && coordinatesByPointer.any {
            it.key != pointerId && it.value == previousCoordinate
        }
        val baseSelection = if (canJoinCurrentChord) latchedCoordinates else emptySet()
        val withoutPrevious = if (previousCoordinate != null && !heldByAnotherPointer) {
            baseSelection - previousCoordinate
        } else {
            baseSelection
        }
        return copy(
            coordinatesByPointer = coordinatesByPointer + (pointerId to coordinate),
            latchedCoordinates = withoutPrevious + coordinate,
            anchorCoordinate = coordinate,
            joinDeadlineMillis = null,
        )
    }

    fun release(
        pointerId: Long,
        eventTimeMillis: Long,
        retainForChord: Boolean,
        joinGraceMillis: Long = DEFAULT_JOIN_GRACE_MILLIS,
    ): TouchSelectionState {
        if (pointerId !in coordinatesByPointer) return this
        val releasedCoordinate = coordinatesByPointer.getValue(pointerId)
        val remaining = coordinatesByPointer - pointerId
        val stillHeld = releasedCoordinate in remaining.values
        val nextSelection = if (retainForChord || stillHeld) {
            latchedCoordinates
        } else {
            latchedCoordinates - releasedCoordinate
        }
        val nextAnchor = when {
            anchorCoordinate in remaining.values -> anchorCoordinate
            remaining.isNotEmpty() -> remaining.values.last()
            anchorCoordinate in nextSelection -> anchorCoordinate
            nextSelection.isNotEmpty() -> nextSelection.last()
            retainForChord -> releasedCoordinate
            else -> null
        }
        return copy(
            coordinatesByPointer = remaining,
            latchedCoordinates = nextSelection,
            anchorCoordinate = nextAnchor,
            joinDeadlineMillis = if (remaining.isEmpty()) {
                eventTimeMillis + joinGraceMillis.coerceAtLeast(0L)
            } else {
                null
            },
        )
    }

    fun releaseAll(): TouchSelectionState = copy(
        coordinatesByPointer = emptyMap(),
        joinDeadlineMillis = null,
    )

    fun retainCoordinates(
        validCoordinates: Set<AxialCoordinate>,
        fallbackCoordinate: AxialCoordinate?,
    ): TouchSelectionState {
        val retained = coordinatesByPointer.filterValues { it in validCoordinates }
        val retainedSelection = latchedCoordinates.filterTo(LinkedHashSet()) { it in validCoordinates }
        val retainedAnchor = anchorCoordinate?.takeIf { it in validCoordinates }
            ?: retained.values.lastOrNull()
            ?: retainedSelection.lastOrNull()
            ?: fallbackCoordinate?.takeIf { it in validCoordinates }
        return TouchSelectionState(
            coordinatesByPointer = retained,
            latchedCoordinates = retainedSelection.ifEmpty {
                retainedAnchor?.let(::setOf).orEmpty()
            },
            anchorCoordinate = retainedAnchor,
            joinDeadlineMillis = null,
        )
    }

    companion object {
        const val DEFAULT_JOIN_GRACE_MILLIS = 160L
    }
}


