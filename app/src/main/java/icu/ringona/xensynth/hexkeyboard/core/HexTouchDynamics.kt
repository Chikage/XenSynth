package icu.ringona.xensynth.hexkeyboard.core

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TouchForce(
    val normalized: Float,
    val velocity: Int,
    val expression: Int,
    val usesHardwarePressure: Boolean,
) {
    companion object {
        val Fixed = TouchForce(
            normalized = 0.76f,
            velocity = 104,
            expression = 127,
            usesHardwarePressure = false,
        )
    }
}

object HexTouchHitTester {
    fun keyAt(
        point: HexPoint,
        layout: HexaKeyboardLayout,
        previousCoordinate: AxialCoordinate? = null,
        sensitivity: Float = 1.20f,
    ): HexKey? {
        if (layout.cells.isEmpty()) return null

        val safeSensitivity = sensitivity.coerceIn(1f, 1.5f).toDouble()
        val radius = layout.configuration.radius.toDouble()
        val previous = previousCoordinate?.let(layout::cellAt)
        val captureRadius = radius * safeSensitivity
        val retentionRadius = radius * (safeSensitivity + RETENTION_EXTRA_SCALE)
        val searchRadius = if (previous == null) captureRadius else retentionRadius
        val nearest = layout.nearestCell(point, searchRadius) ?: return null
        val nearestDistance = distance(point, nearest.center)

        if (previous != null) {
            val previousDistance = distance(point, previous.center)
            if (previousDistance <= retentionRadius) {
                if (nearest.coordinate == previous.coordinate) return previous

                val switchMargin = radius * SWITCH_MARGIN_SCALE
                if (nearestDistance + switchMargin >= previousDistance) return previous
            }
        }

        return nearest.takeIf { nearestDistance <= captureRadius }
    }

    private fun distance(first: HexPoint, second: HexPoint): Double =
        hypot(first.x - second.x, first.y - second.y)

    private const val RETENTION_EXTRA_SCALE = 0.12
    private const val SWITCH_MARGIN_SCALE = 0.12
}

class PseudoPressureTracker {
    private var startTimeMillis: Long? = null
    private var lastTimeMillis: Long? = null
    private var lastPoint: HexPoint? = null
    private var filteredForce: Double? = null
    private var minimumPressure = Float.POSITIVE_INFINITY
    private var maximumPressure = Float.NEGATIVE_INFINITY
    private var hardwarePressureObserved = false

    fun sample(
        rawPressure: Float,
        uptimeMillis: Long,
        point: HexPoint,
        keyCenter: HexPoint,
        keyRadius: Double,
        hardwarePressureHint: Boolean = false,
    ): TouchForce {
        val safeRadius = keyRadius.coerceAtLeast(1.0)
        val startedAt = startTimeMillis ?: uptimeMillis.also { startTimeMillis = it }
        val previousTime = lastTimeMillis
        val previousPoint = lastPoint
        val elapsedMillis = (uptimeMillis - startedAt).coerceAtLeast(0L)
        val deltaMillis = previousTime?.let { (uptimeMillis - it).coerceAtLeast(1L) } ?: 0L

        val centerDistance = hypot(point.x - keyCenter.x, point.y - keyCenter.y)
        val centerProximity =
            (1.0 - centerDistance / (safeRadius * CENTER_PROXIMITY_RADIUS_SCALE)).coerceIn(0.0, 1.0)
        val holdProgress = (elapsedMillis.toDouble() / HOLD_RAMP_MILLIS).coerceIn(0.0, 1.0)
        val speedInRadiiPerSecond = if (previousPoint != null && deltaMillis > 0L) {
            hypot(point.x - previousPoint.x, point.y - previousPoint.y) /
                safeRadius / (deltaMillis / 1_000.0)
        } else {
            0.0
        }
        val stability = (1.0 - speedInRadiiPerSecond / FAST_SLIDE_RADII_PER_SECOND)
            .coerceIn(0.0, 1.0)
        val pseudoForce = (
            BASE_FORCE +
                CENTER_WEIGHT * centerProximity +
                HOLD_WEIGHT * sqrt(holdProgress) +
                STABILITY_WEIGHT * stability
            ).coerceIn(0.0, 1.0)

        val pressure = rawPressure.takeIf(Float::isFinite)?.coerceIn(0f, MAX_RAW_PRESSURE) ?: 1f
        minimumPressure = minOf(minimumPressure, pressure)
        maximumPressure = maxOf(maximumPressure, pressure)
        if (
            hardwarePressureHint ||
            pressure in MIN_DIRECT_PRESSURE..MAX_DIRECT_PRESSURE ||
            maximumPressure - minimumPressure >= MIN_PRESSURE_RANGE
        ) {
            hardwarePressureObserved = true
        }

        val hardwareForce = ((pressure - HARDWARE_PRESSURE_FLOOR) / HARDWARE_PRESSURE_SPAN)
            .coerceIn(0f, 1f)
            .toDouble()
            .pow(HARDWARE_PRESSURE_GAMMA)
        val targetForce = if (hardwarePressureObserved) {
            HARDWARE_WEIGHT * hardwareForce + (1.0 - HARDWARE_WEIGHT) * pseudoForce
        } else {
            pseudoForce
        }
        val previousFilteredForce = filteredForce
        val smoothing = if (previousFilteredForce == null || deltaMillis <= 0L) {
            1.0
        } else {
            (1.0 - exp(-deltaMillis / FORCE_SMOOTHING_MILLIS)).coerceIn(0.12, 0.78)
        }
        val force = if (previousFilteredForce == null) {
            targetForce
        } else {
            previousFilteredForce + (targetForce - previousFilteredForce) * smoothing
        }.coerceIn(0.0, 1.0)

        filteredForce = force
        lastTimeMillis = uptimeMillis
        lastPoint = point

        return TouchForce(
            normalized = force.toFloat(),
            velocity = (MIN_VELOCITY + (MAX_VELOCITY - MIN_VELOCITY) * force.pow(VELOCITY_GAMMA))
                .roundToInt()
                .coerceIn(MIN_VELOCITY, MAX_VELOCITY),
            expression = (MIN_EXPRESSION +
                (MAX_EXPRESSION - MIN_EXPRESSION) * force.pow(EXPRESSION_GAMMA))
                .roundToInt()
                .coerceIn(MIN_EXPRESSION, MAX_EXPRESSION),
            usesHardwarePressure = hardwarePressureObserved,
        )
    }

    private companion object {
        const val CENTER_PROXIMITY_RADIUS_SCALE = 1.15
        const val HOLD_RAMP_MILLIS = 320.0
        const val FAST_SLIDE_RADII_PER_SECOND = 9.0
        const val BASE_FORCE = 0.34
        const val CENTER_WEIGHT = 0.30
        const val HOLD_WEIGHT = 0.20
        const val STABILITY_WEIGHT = 0.16

        const val MAX_RAW_PRESSURE = 1.5f
        const val MIN_DIRECT_PRESSURE = 0.02f
        const val MAX_DIRECT_PRESSURE = 0.98f
        const val MIN_PRESSURE_RANGE = 0.05f
        const val HARDWARE_PRESSURE_FLOOR = 0.03f
        const val HARDWARE_PRESSURE_SPAN = 0.82f
        const val HARDWARE_PRESSURE_GAMMA = 0.72
        const val HARDWARE_WEIGHT = 0.82
        const val FORCE_SMOOTHING_MILLIS = 42.0

        const val MIN_VELOCITY = 44
        const val MAX_VELOCITY = 127
        const val VELOCITY_GAMMA = 0.72
        const val MIN_EXPRESSION = 70
        const val MAX_EXPRESSION = 127
        const val EXPRESSION_GAMMA = 0.85
    }
}

