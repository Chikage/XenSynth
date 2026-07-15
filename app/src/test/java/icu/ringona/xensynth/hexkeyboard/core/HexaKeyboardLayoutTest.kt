package icu.ringona.xensynth.hexkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

class HexaKeyboardLayoutTest {
    @Test
    fun requiredDefaultsBuildFixedSizeRotatedKeyboard() {
        val configuration = HexaKeyboardConfiguration.Default
        val layout = HexaKeyboardLayoutEngine.build(configuration)

        assertEquals(35, configuration.columns)
        assertEquals(8, configuration.rows)
        assertEquals(53, configuration.period)
        assertEquals(9, configuration.stepQ)
        assertEquals(4, configuration.stepR)
        assertEquals(24, configuration.radius)
        assertEquals(12, configuration.rotationDegrees)
        assertEquals(72.0, configuration.frameAcuteAngleDegrees, 0.0)
        assertEquals(280, layout.cells.size)
        assertEquals(280, layout.cells.map { it.coordinate }.toSet().size)
        assertEquals(RotationStats(generated = 56, omitted = 56), layout.stats)
    }

    @Test
    fun radiusIsAlwaysNormalizedToTwentyFour() {
        assertEquals(24, HexaKeyboardConfiguration(radius = 14).normalized().radius)
        assertEquals(24, HexaKeyboardConfiguration(radius = 34).normalized().radius)
    }

    @Test
    fun oneEdoIsPreservedAndLowerPeriodsNormalizeToOne() {
        assertEquals(1, HexaKeyboardConfiguration(period = 1).normalized().period)
        assertEquals(1, HexaKeyboardConfiguration(period = 0).normalized().period)

        val layout = HexaKeyboardLayoutEngine.build(HexaKeyboardConfiguration(period = 1))
        assertEquals(setOf(0), layout.cells.map { it.pitchClass }.toSet())
    }

    @Test
    fun originAnchorsC4AndOddQWindow() {
        val layout = HexaKeyboardLayoutEngine.build()
        val origin = layout.cellAt(AxialCoordinate.Origin)

        assertNotNull(origin)
        origin!!
        assertEquals(AxialCoordinate(q = 0, r = 0), origin.coordinate)
        assertEquals(0, origin.coordinate.s)
        assertEquals(0, origin.step)
        assertEquals(0, origin.pitchClass)
        assertEquals(HexPoint(x = 0.0, y = 0.0), origin.center)
        assertEquals(AxialCoordinate(q = -17, r = 5), layout.slots.first().coordinate)
        assertEquals(AxialCoordinate(q = 17, r = -5), layout.slots.last().coordinate)
        assertEquals(AxialCoordinate.Origin, layout.defaultSelection?.coordinate)
    }

    @Test
    fun defaultPeriodVectorsAreShortestIndependentSolutions() {
        val vectors = HexaKeyboardLayoutEngine.periodVectors()

        assertEquals(
            listOf(
                PeriodVector(dq = -5, dr = -2, distance = 7),
                PeriodVector(dq = -4, dr = 9, distance = 9),
            ),
            vectors,
        )
        vectors.forEach { vector ->
            assertEquals(0, Math.floorMod(vector.dq * 9 + vector.dr * 4, 53))
        }
        assertNotEquals(
            0,
            vectors[0].dq * vectors[1].dr - vectors[0].dr * vectors[1].dq,
        )
    }

    @Test
    fun defaultWindowParallelogramHasSeventyTwoDegreeTargetCorners() {
        val outline = HexaKeyboardLayoutEngine.build().windowOutline

        assertPoint(outline.topLeft, x = -694.4480407125614, y = -169.4922678357857)
        assertPoint(outline.topRight, x = 577.5519592874386, y = -169.4922678357857)
        assertPoint(outline.bottomRight, x = 694.4480407125614, y = 190.27687752661222)
        assertPoint(outline.bottomLeft, x = -577.5519592874386, y = 190.27687752661222)
        assertEquals(
            72.0,
            interiorAngle(outline.topLeft, outline.topRight, outline.bottomLeft),
            1e-12,
        )
        assertEquals(
            72.0,
            interiorAngle(outline.bottomRight, outline.bottomLeft, outline.topRight),
            1e-12,
        )
    }

    @Test
    fun rotatedSelectionPreservesReferenceCornerCells() {
        val cells = HexaKeyboardLayoutEngine.build().cells

        assertEquals(AxialCoordinate(q = 15, r = -14), cells.first().coordinate)
        assertPoint(cells.first().center, x = 584.3774278657452, y = -152.0230962749274)
        assertEquals(AxialCoordinate(q = -7, r = 9), cells.last().coordinate)
        assertPoint(cells.last().center, x = -294.02819216679524, y = 171.24083102790098)
    }

    @Test
    fun maximumRotatedLayoutHasCompleteConstantTimeCoordinateIndexAndStableSelection() {
        val configuration = HexaKeyboardConfiguration(
            columns = 64,
            rows = 32,
            period = 53,
            stepQ = 9,
            stepR = 4,
            rotationDegrees = 12,
        )

        val first = HexaKeyboardLayoutEngine.build(configuration)
        val second = HexaKeyboardLayoutEngine.build(configuration)

        assertEquals(2_048, first.cells.size)
        assertEquals(2_048, first.cellsByCoordinate.size)
        first.cells.forEach { key ->
            assertSame(key, first.cellAt(key.coordinate))
        }
        assertEquals(
            first.cells.map(HexKey::coordinate),
            second.cells.map(HexKey::coordinate),
        )
    }

    @Test
    fun maximumBoundedSelectionMatchesFormerFullSortReference() {
        val configuration = HexaKeyboardConfiguration(
            columns = 64,
            rows = 32,
            period = 53,
            stepQ = 9,
            stepR = 4,
            rotationDegrees = 12,
        )
        val layout = HexaKeyboardLayoutEngine.build(configuration)
        val radius = configuration.radius.toDouble()
        val center = layout.slotCenterBounds.center
        val range = ceil(
            hypot(layout.slotCenterBounds.width, layout.slotCenterBounds.height) /
                radius * 1.35,
        ).toInt() + 6
        val referenceCandidates = ArrayList<ReferenceCandidate>(
            (range * 2 + 1) * (range * 2 + 1),
        )

        for (q in -range..range) {
            for (r in -range..range) {
                val coordinate = AxialCoordinate(q = q, r = r)
                val point = HexGeometry.rotate(
                    HexGeometry.point(coordinate, radius),
                    configuration.rotationDegrees.toDouble(),
                )
                referenceCandidates += ReferenceCandidate(
                    coordinate = coordinate,
                    score = HexGeometry.parallelogramScore(
                        point = point,
                        bounds = layout.slotCenterBounds,
                        acuteAngleDegrees = configuration.frameAcuteAngleDegrees,
                    ),
                    centerDistance = HexGeometry.squaredDistance(point, center),
                )
            }
        }

        val expectedCoordinates = referenceCandidates
            .sortedWith(referenceCandidateComparator)
            .take(configuration.columns * configuration.rows)
            .mapTo(HashSet()) { it.coordinate }
        assertEquals(expectedCoordinates, layout.cellsByCoordinate.keys)
    }

    private data class ReferenceCandidate(
        val coordinate: AxialCoordinate,
        val score: Double,
        val centerDistance: Double,
    )

    private val referenceCandidateComparator = Comparator<ReferenceCandidate> { first, second ->
        when {
            first.score != second.score -> first.score.compareTo(second.score)
            first.centerDistance != second.centerDistance ->
                first.centerDistance.compareTo(second.centerDistance)
            first.coordinate.q != second.coordinate.q ->
                first.coordinate.q.compareTo(second.coordinate.q)
            else -> first.coordinate.r.compareTo(second.coordinate.r)
        }
    }

    private fun assertPoint(point: HexPoint, x: Double, y: Double) {
        assertEquals(x, point.x, 1e-9)
        assertEquals(y, point.y, 1e-9)
    }

    private fun interiorAngle(
        vertex: HexPoint,
        firstEndpoint: HexPoint,
        secondEndpoint: HexPoint,
    ): Double {
        val first = HexPoint(
            x = firstEndpoint.x - vertex.x,
            y = firstEndpoint.y - vertex.y,
        )
        val second = HexPoint(
            x = secondEndpoint.x - vertex.x,
            y = secondEndpoint.y - vertex.y,
        )
        val cross = first.x * second.y - first.y * second.x
        val dot = first.x * second.x + first.y * second.y
        return atan2(abs(cross), dot) * 180.0 / Math.PI
    }
}
