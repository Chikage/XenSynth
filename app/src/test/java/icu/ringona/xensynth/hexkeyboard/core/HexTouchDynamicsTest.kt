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
            rawContactArea = 100f,
            uptimeMillis = 100L,
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
            hardwarePressureHint = true,
        )
        val hard = PseudoPressureTracker().sample(
            rawPressure = 0.82f,
            uptimeMillis = 100L,
            hardwarePressureHint = true,
        )

        assertTrue(soft.usesHardwarePressure)
        assertTrue(hard.usesHardwarePressure)
        assertTrue(hard.velocity > soft.velocity)
        assertTrue(hard.expression > soft.expression)
    }

    @Test
    fun constantFingerPressureAndSingleOutlierDoNotMasqueradeAsHardwarePressure() {
        val calibrator = TouchPressureCalibrator()
        val pressures = listOf(0.5f, 0.5f, 1.4f, 0.5f, 0.5f, 0.5f)

        val samples = pressures.mapIndexed { index, pressure ->
            calibrator.sample(
                rawPressure = pressure,
                uptimeMillis = 100L + index * 24L,
                hardwarePressureHint = false,
            )
        }

        assertTrue(samples.all { it.confidence == 0.0 })
    }

    @Test
    fun varyingFingerPressureBuildsHardwareConfidenceGradually() {
        val calibrator = TouchPressureCalibrator()
        val pressures = listOf(0.18f, 0.26f, 0.44f, 0.68f, 0.86f)

        val samples = pressures.mapIndexed { index, pressure ->
            calibrator.sample(
                rawPressure = pressure,
                uptimeMillis = 100L + index * 20L,
                hardwarePressureHint = false,
            )
        }

        assertEquals(0.0, samples.first().confidence, 0.0)
        assertTrue(samples.last().confidence >= 0.5)
        assertTrue(samples.last().normalized > samples.first().normalized)
    }

    @Test
    fun constantContactAreaAndSingleOutlierDoNotCreateFalseExpression() {
        val calibrator = TouchAreaCalibrator()
        val areas = listOf(100f, 100f, 420f, 100f, 100f, 100f)

        val samples = areas.mapIndexed { index, area ->
            calibrator.sample(
                rawArea = area,
                uptimeMillis = 100L + index * 24L,
            )
        }

        assertTrue(samples.all { it.confidence == 0.0 })
    }

    @Test
    fun growingContactAreaBuildsExpressionConfidence() {
        val calibrator = TouchAreaCalibrator()
        val areas = listOf(100f, 105f, 115f, 130f, 150f)

        val samples = areas.mapIndexed { index, area ->
            calibrator.sample(
                rawArea = area,
                uptimeMillis = 100L + index * 20L,
            )
        }

        assertEquals(0.0, samples.first().confidence, 0.0)
        assertTrue(samples.last().confidence >= 0.5)
        assertTrue(samples.last().change > 0.0)
    }

    @Test
    fun fallbackForceStaysStableWithoutUsefulPressureOrAreaSignals() {
        val tracker = PseudoPressureTracker()
        val initial = tracker.sample(
            rawPressure = 1f,
            rawContactArea = 100f,
            uptimeMillis = 100L,
        )
        val held = tracker.sample(
            rawPressure = 1f,
            rawContactArea = 100f,
            uptimeMillis = 600L,
        )

        assertEquals(initial.velocity, held.velocity)
        assertEquals(initial.expression, held.expression)
    }

    @Test
    fun contactAreaChangesExpressionWithoutChangingStrikeVelocity() {
        val tracker = PseudoPressureTracker()
        val areas = listOf(100f, 105f, 115f, 130f, 150f)
        val forces = areas.mapIndexed { index, area ->
            tracker.sample(
                rawPressure = 1f,
                rawContactArea = area,
                uptimeMillis = 100L + index * 20L,
            )
        }

        assertTrue(forces.last().expression > forces.first().expression)
        assertTrue(forces.all { it.velocity == forces.first().velocity })
    }

    @Test
    fun pseudoForceFilterIsStableAcrossPointerSampleRates() {
        val denseExpression = expressionAfterAreaGrowth(sampleIntervalMillis = 8L)
        val sparseExpression = expressionAfterAreaGrowth(sampleIntervalMillis = 16L)

        assertTrue(kotlin.math.abs(denseExpression - sparseExpression) <= 2)
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

    private fun expressionAfterAreaGrowth(sampleIntervalMillis: Long): Int {
        val tracker = PseudoPressureTracker()
        var uptimeMillis = 100L
        var expression = 0
        while (uptimeMillis <= 212L) {
            val area = 100f + (uptimeMillis - 100L) * 0.5f
            expression = tracker.sample(
                rawPressure = 1f,
                rawContactArea = area,
                uptimeMillis = uptimeMillis,
            ).expression
            uptimeMillis += sampleIntervalMillis
        }
        return expression
    }

    private fun squaredDistance(first: HexPoint, second: HexPoint): Double {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return dx * dx + dy * dy
    }
}
