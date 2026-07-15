package icu.ringona.xensynth.hexkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexTouchDynamicsTest {
    private val layout = HexaKeyboardLayoutEngine.build()
    private val origin = requireNotNull(layout.cellAt(AxialCoordinate.Origin))
    private val neighbor = layout.cells
        .filter { it.coordinate != origin.coordinate }
        .minBy { squaredDistance(it.center, origin.center) }

    @Test
    fun nearestCellCaptureRemovesFormerKeySeamDeadZone() {
        val seam = interpolate(origin.center, neighbor.center, 0.5)

        val key = HexTouchHitTester.keyAt(seam, layout, sensitivity = 1.2f)

        assertNotNull(key)
        assertTrue(key == origin || key == neighbor)
    }

    @Test
    fun sensitivityExtendsCapturePastOuterKeyEdge() {
        val edge = layout.cells.maxBy { squaredDistance(it.center, layout.keyBounds.center) }
        val dx = edge.center.x - layout.keyBounds.center.x
        val dy = edge.center.y - layout.keyBounds.center.y
        val length = kotlin.math.hypot(dx, dy)
        val point = HexPoint(
            x = edge.center.x + dx / length * layout.configuration.radius * 1.12,
            y = edge.center.y + dy / length * layout.configuration.radius * 1.12,
        )

        assertEquals(
            edge.coordinate,
            HexTouchHitTester.keyAt(point, layout, sensitivity = 1.2f)?.coordinate,
        )
        assertEquals(
            null,
            HexTouchHitTester.keyAt(point, layout, sensitivity = 1.0f),
        )
    }

    @Test
    fun previousKeyIsRetainedUntilPointerClearlyCrossesBoundary() {
        val nearBoundary = interpolate(origin.center, neighbor.center, 0.53)
        val clearlyAcross = interpolate(origin.center, neighbor.center, 0.64)

        assertEquals(
            origin.coordinate,
            HexTouchHitTester.keyAt(
                point = nearBoundary,
                layout = layout,
                previousCoordinate = origin.coordinate,
            )?.coordinate,
        )
        assertEquals(
            neighbor.coordinate,
            HexTouchHitTester.keyAt(
                point = clearlyAcross,
                layout = layout,
                previousCoordinate = origin.coordinate,
            )?.coordinate,
        )
    }

    @Test
    fun defaultOnePressureUsesPseudoSignalInsteadOfForcingMaximum() {
        val force = PseudoPressureTracker().sample(
            rawPressure = 1f,
            uptimeMillis = 100L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )

        assertFalse(force.usesHardwarePressure)
        assertTrue(force.velocity in 90 until 127)
        assertTrue(force.expression in 90 until 127)
    }

    @Test
    fun realPressureProducesWiderForceResponse() {
        val soft = PseudoPressureTracker().sample(
            rawPressure = 0.12f,
            uptimeMillis = 100L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )
        val hard = PseudoPressureTracker().sample(
            rawPressure = 0.82f,
            uptimeMillis = 100L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )

        assertTrue(soft.usesHardwarePressure)
        assertTrue(hard.usesHardwarePressure)
        assertTrue(hard.velocity > soft.velocity)
        assertTrue(hard.expression > soft.expression)
    }

    @Test
    fun fallbackForceRespondsToPlacementAndHoldTime() {
        val centerTracker = PseudoPressureTracker()
        val edgeTracker = PseudoPressureTracker()
        val centerAtDown = centerTracker.sample(
            rawPressure = 1f,
            uptimeMillis = 100L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )
        val edgeAtDown = edgeTracker.sample(
            rawPressure = 1f,
            uptimeMillis = 100L,
            point = HexPoint(origin.center.x + layout.configuration.radius, origin.center.y),
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )
        val centerAfterHold = centerTracker.sample(
            rawPressure = 1f,
            uptimeMillis = 500L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )

        assertTrue(centerAtDown.velocity > edgeAtDown.velocity)
        assertTrue(centerAfterHold.expression > centerAtDown.expression)
    }

    @Test
    fun maximumRotatedLayoutCentersAreAllDirectlyHittable() {
        val maximumLayout = maximumLayout()

        maximumLayout.cells.forEach { key ->
            assertEquals(
                key.coordinate,
                HexTouchHitTester.keyAt(key.center, maximumLayout)?.coordinate,
            )
        }
    }

    @Test
    fun spatialIndexMatchesLinearReferenceAcrossRotatedResamplingBoundaries() {
        val maximumLayout = maximumLayout()
        val sampledCells = maximumLayout.cells.filterIndexed { index, _ -> index % 29 == 0 }
        val sensitivities = listOf(1.0f, 1.2f, 1.5f)

        sampledCells.forEach { key ->
            val neighbor = maximumLayout.cells
                .asSequence()
                .filter { it.coordinate != key.coordinate }
                .minBy { squaredDistance(it.center, key.center) }
            listOf(0.47, 0.53, 0.64, 1.08).forEach { amount ->
                val point = interpolate(key.center, neighbor.center, amount)
                sensitivities.forEach { sensitivity ->
                    assertIndexedHitMatchesLinearReference(
                        point = point,
                        layout = maximumLayout,
                        previousCoordinate = key.coordinate,
                        sensitivity = sensitivity,
                    )
                    assertIndexedHitMatchesLinearReference(
                        point = point,
                        layout = maximumLayout,
                        previousCoordinate = null,
                        sensitivity = sensitivity,
                    )
                }
            }
        }

        val radius = maximumLayout.configuration.radius.toDouble()
        listOf(
            HexPoint(maximumLayout.keyBounds.minX - radius * 2.0, maximumLayout.keyBounds.minY),
            HexPoint(maximumLayout.keyBounds.maxX + radius * 2.0, maximumLayout.keyBounds.maxY),
            HexPoint(maximumLayout.keyBounds.minX, maximumLayout.keyBounds.maxY + radius * 2.0),
        ).forEach { point ->
            assertIndexedHitMatchesLinearReference(
                point = point,
                layout = maximumLayout,
                previousCoordinate = null,
                sensitivity = 1.5f,
            )
        }
    }

    private fun assertIndexedHitMatchesLinearReference(
        point: HexPoint,
        layout: HexaKeyboardLayout,
        previousCoordinate: AxialCoordinate?,
        sensitivity: Float,
    ) {
        val expected = linearKeyAt(
            point = point,
            layout = layout,
            previousCoordinate = previousCoordinate,
            sensitivity = sensitivity,
        )
        val actual = HexTouchHitTester.keyAt(
            point = point,
            layout = layout,
            previousCoordinate = previousCoordinate,
            sensitivity = sensitivity,
        )
        assertEquals(
            "point=$point previous=$previousCoordinate sensitivity=$sensitivity",
            expected?.coordinate,
            actual?.coordinate,
        )
    }

    private fun linearKeyAt(
        point: HexPoint,
        layout: HexaKeyboardLayout,
        previousCoordinate: AxialCoordinate?,
        sensitivity: Float,
    ): HexKey? {
        if (layout.cells.isEmpty()) return null
        val safeSensitivity = sensitivity.coerceIn(1f, 1.5f).toDouble()
        val radius = layout.configuration.radius.toDouble()
        val nearest = layout.cells.minByOrNull { squaredDistance(point, it.center) } ?: return null
        val nearestDistance = kotlin.math.hypot(
            point.x - nearest.center.x,
            point.y - nearest.center.y,
        )
        val previous = previousCoordinate?.let(layout::cellAt)

        if (previous != null) {
            val previousDistance = kotlin.math.hypot(
                point.x - previous.center.x,
                point.y - previous.center.y,
            )
            val retentionRadius = radius * (safeSensitivity + 0.12)
            if (previousDistance <= retentionRadius) {
                if (nearest.coordinate == previous.coordinate) return previous
                if (nearestDistance + radius * 0.12 >= previousDistance) return previous
            }
        }

        return nearest.takeIf { nearestDistance <= radius * safeSensitivity }
    }

    private fun maximumLayout(): HexaKeyboardLayout = HexaKeyboardLayoutEngine.build(
        HexaKeyboardConfiguration(
            columns = 64,
            rows = 32,
            period = 53,
            stepQ = 9,
            stepR = 4,
            rotationDegrees = 12,
        ),
    )

    private fun interpolate(first: HexPoint, second: HexPoint, amount: Double): HexPoint = HexPoint(
        x = first.x + (second.x - first.x) * amount,
        y = first.y + (second.y - first.y) * amount,
    )

    private fun squaredDistance(first: HexPoint, second: HexPoint): Double {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return dx * dx + dy * dy
    }
}

