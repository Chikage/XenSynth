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
            hardwarePressureHint = true,
        )
        val hard = PseudoPressureTracker().sample(
            rawPressure = 0.82f,
            uptimeMillis = 100L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
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
    fun fallbackForceRespondsToPlacementAndDeliberateInwardMotion() {
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
            uptimeMillis = 600L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )
        val edgeAfterInwardMotion = edgeTracker.sample(
            rawPressure = 1f,
            uptimeMillis = 180L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )

        assertTrue(centerAtDown.velocity > edgeAtDown.velocity)
        assertTrue(centerAfterHold.expression > centerAtDown.expression)
        assertTrue(centerAfterHold.expression < 120)
        assertTrue(edgeAfterInwardMotion.expression > edgeAtDown.expression)
        assertEquals(edgeAtDown.velocity, edgeAfterInwardMotion.velocity)
    }

    @Test
    fun newKeyTrackerDoesNotInheritPreviousKeyHoldRamp() {
        val calibrator = TouchPressureCalibrator()
        val firstTracker = PseudoPressureTracker(calibrator)
        val firstDown = firstTracker.sample(
            rawPressure = 1f,
            uptimeMillis = 100L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )
        val firstHeld = firstTracker.sample(
            rawPressure = 1f,
            uptimeMillis = 600L,
            point = origin.center,
            keyCenter = origin.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )

        val nextKeyDown = PseudoPressureTracker(calibrator).sample(
            rawPressure = 1f,
            uptimeMillis = 600L,
            point = neighbor.center,
            keyCenter = neighbor.center,
            keyRadius = layout.configuration.radius.toDouble(),
        )

        assertEquals(firstDown.velocity, nextKeyDown.velocity)
        assertEquals(firstDown.expression, nextKeyDown.expression)
        assertTrue(firstHeld.expression > nextKeyDown.expression)
    }

    @Test
    fun rotatedHexVertexAndEdgeHaveEquivalentPlacementForce() {
        val radius = layout.configuration.radius.toDouble()
        val rotation = layout.configuration.rotationDegrees.toDouble()
        val vertex = radialPoint(origin.center, radius, rotation)
        val edgeMiddle = radialPoint(
            center = origin.center,
            radius = radius * kotlin.math.sqrt(3.0) / 2.0,
            degrees = rotation + 30.0,
        )

        val vertexForce = PseudoPressureTracker().sample(
            rawPressure = 1f,
            uptimeMillis = 100L,
            point = vertex,
            keyCenter = origin.center,
            keyRadius = radius,
            keyRotationDegrees = rotation,
        )
        val edgeForce = PseudoPressureTracker().sample(
            rawPressure = 1f,
            uptimeMillis = 100L,
            point = edgeMiddle,
            keyCenter = origin.center,
            keyRadius = radius,
            keyRotationDegrees = rotation,
        )

        assertEquals(vertexForce.velocity, edgeForce.velocity)
        assertEquals(vertexForce.expression, edgeForce.expression)
    }

    @Test
    fun pseudoForceFilterIsStableAcrossPointerSampleRates() {
        val denseExpression = expressionAfterInwardMove(sampleIntervalMillis = 16L)
        val sparseExpression = expressionAfterInwardMove(sampleIntervalMillis = 48L)

        assertTrue(kotlin.math.abs(denseExpression - sparseExpression) <= 1)
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

    private fun radialPoint(center: HexPoint, radius: Double, degrees: Double): HexPoint {
        val angle = Math.toRadians(degrees)
        return HexPoint(
            x = center.x + kotlin.math.cos(angle) * radius,
            y = center.y + kotlin.math.sin(angle) * radius,
        )
    }

    private fun expressionAfterInwardMove(sampleIntervalMillis: Long): Int {
        val tracker = PseudoPressureTracker()
        val radius = layout.configuration.radius.toDouble()
        tracker.sample(
            rawPressure = 1f,
            uptimeMillis = 100L,
            point = HexPoint(origin.center.x + radius, origin.center.y),
            keyCenter = origin.center,
            keyRadius = radius,
        )
        var uptimeMillis = 116L
        var expression = 0
        while (uptimeMillis <= 212L) {
            expression = tracker.sample(
                rawPressure = 1f,
                uptimeMillis = uptimeMillis,
                point = origin.center,
                keyCenter = origin.center,
                keyRadius = radius,
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
