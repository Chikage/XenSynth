package icu.ringona.xensynth.hexkeyboard.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
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

internal data class CalibratedTouchPressure(
    val normalized: Double,
    val confidence: Double,
)

/**
 * Learns whether an Android pointer's pressure axis carries a useful signal.
 *
 * Styluses are trusted immediately. Finger input must demonstrate a meaningful
 * range over multiple samples so devices that report a constant value such as
 * 0.5 are not mistaken for pressure-sensitive hardware.
 */
internal class TouchPressureCalibrator {
    private var startTimeMillis: Long? = null
    private var sampleCount = 0
    private val recentPressures = FloatArray(PRESSURE_WINDOW_SIZE)
    private var storedPressureCount = 0
    private var nextPressureIndex = 0
    private var confidence = 0.0

    fun sample(
        rawPressure: Float,
        uptimeMillis: Long,
        hardwarePressureHint: Boolean,
    ): CalibratedTouchPressure {
        val pressure = rawPressure
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, MAX_RAW_PRESSURE)
            ?: DEFAULT_RAW_PRESSURE
        val startedAt = startTimeMillis ?: uptimeMillis.also { startTimeMillis = it }
        sampleCount += 1
        recentPressures[nextPressureIndex] = pressure
        nextPressureIndex = (nextPressureIndex + 1) % recentPressures.size
        storedPressureCount = minOf(storedPressureCount + 1, recentPressures.size)

        val (pressureFloor, pressureCeiling) = robustPressureBounds()
        val observedRange = pressureCeiling - pressureFloor
        val elapsedMillis = (uptimeMillis - startedAt).coerceAtLeast(0L)
        val observedConfidence = if (
            sampleCount >= MIN_FINGER_PRESSURE_SAMPLES &&
            elapsedMillis >= MIN_FINGER_PRESSURE_OBSERVATION_MILLIS
        ) {
            ((observedRange - FINGER_PRESSURE_RANGE_FLOOR) /
                FINGER_PRESSURE_RANGE_CONFIDENCE_SPAN).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        confidence = if (hardwarePressureHint) {
            1.0
        } else {
            max(confidence, observedConfidence)
        }

        val fixedNormalized = ((pressure - HARDWARE_PRESSURE_FLOOR) / HARDWARE_PRESSURE_SPAN)
            .coerceIn(0f, 1f)
            .toDouble()
            .pow(HARDWARE_PRESSURE_GAMMA)
        val adaptiveFloor = pressureFloor - ADAPTIVE_PRESSURE_MARGIN
        val adaptiveSpan = max(
            observedRange + ADAPTIVE_PRESSURE_MARGIN * 2.0,
            MIN_ADAPTIVE_PRESSURE_SPAN,
        )
        val adaptiveNormalized = ((pressure - adaptiveFloor) / adaptiveSpan)
            .coerceIn(0.0, 1.0)
            .pow(HARDWARE_PRESSURE_GAMMA)

        return CalibratedTouchPressure(
            normalized = if (hardwarePressureHint) fixedNormalized else adaptiveNormalized,
            confidence = confidence,
        )
    }

    private fun robustPressureBounds(): Pair<Double, Double> {
        var minimum = Float.POSITIVE_INFINITY
        var secondMinimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var secondMaximum = Float.NEGATIVE_INFINITY
        repeat(storedPressureCount) { index ->
            val pressure = recentPressures[index]
            if (pressure <= minimum) {
                secondMinimum = minimum
                minimum = pressure
            } else if (pressure < secondMinimum) {
                secondMinimum = pressure
            }
            if (pressure >= maximum) {
                secondMaximum = maximum
                maximum = pressure
            } else if (pressure > secondMaximum) {
                secondMaximum = pressure
            }
        }
        return if (storedPressureCount >= MIN_FINGER_PRESSURE_SAMPLES) {
            secondMinimum.toDouble() to secondMaximum.toDouble()
        } else {
            minimum.toDouble() to maximum.toDouble()
        }
    }

    private companion object {
        const val DEFAULT_RAW_PRESSURE = 1f
        const val MAX_RAW_PRESSURE = 1.5f
        const val PRESSURE_WINDOW_SIZE = 12
        const val MIN_FINGER_PRESSURE_SAMPLES = 5
        const val MIN_FINGER_PRESSURE_OBSERVATION_MILLIS = 64L
        const val FINGER_PRESSURE_RANGE_FLOOR = 0.07
        const val FINGER_PRESSURE_RANGE_CONFIDENCE_SPAN = 0.18
        const val HARDWARE_PRESSURE_FLOOR = 0.03f
        const val HARDWARE_PRESSURE_SPAN = 0.82f
        const val HARDWARE_PRESSURE_GAMMA = 0.72
        const val ADAPTIVE_PRESSURE_MARGIN = 0.02
        const val MIN_ADAPTIVE_PRESSURE_SPAN = 0.30
    }
}

class PseudoPressureTracker internal constructor(
    private val pressureCalibrator: TouchPressureCalibrator,
) {
    constructor() : this(TouchPressureCalibrator())

    private var startTimeMillis: Long? = null
    private var lastTimeMillis: Long? = null
    private var lastPoint: HexPoint? = null
    private var filteredForce: Double? = null
    private var initialHexRadius: Double? = null
    private var initialPseudoForce: Double? = null
    private var strikeForce: Double? = null

    fun sample(
        rawPressure: Float,
        uptimeMillis: Long,
        point: HexPoint,
        keyCenter: HexPoint,
        keyRadius: Double,
        keyRotationDegrees: Double = 0.0,
        hardwarePressureHint: Boolean = false,
    ): TouchForce {
        val safeRadius = keyRadius.coerceAtLeast(1.0)
        val startedAt = startTimeMillis ?: uptimeMillis.also { startTimeMillis = it }
        val previousTime = lastTimeMillis
        val previousPoint = lastPoint
        val elapsedMillis = (uptimeMillis - startedAt).coerceAtLeast(0L)
        val deltaMillis = previousTime
            ?.let { (uptimeMillis - it).coerceIn(0L, MAX_FILTER_DELTA_MILLIS) }
            ?: 0L

        val hexRadius = normalizedHexRadius(
            point = point,
            center = keyCenter,
            radius = safeRadius,
            rotationDegrees = keyRotationDegrees,
        )
        val centerProximity = (1.0 - hexRadius).coerceIn(0.0, 1.0)
        val placementForce = initialPseudoForce ?: (
            BASE_FORCE + PLACEMENT_WEIGHT * centerProximity.pow(PLACEMENT_GAMMA)
            ).coerceIn(0.0, 1.0).also { initialPseudoForce = it }
        val startedHexRadius = initialHexRadius ?: hexRadius.also { initialHexRadius = it }
        val inwardTravel = ((startedHexRadius - hexRadius) / INWARD_TRAVEL_SCALE)
            .coerceIn(-1.0, 1.0)
        val holdProgress = (elapsedMillis.toDouble() / HOLD_RAMP_MILLIS).coerceIn(0.0, 1.0)
        val speedInRadiiPerSecond = if (previousPoint != null && deltaMillis > 0L) {
            hypot(point.x - previousPoint.x, point.y - previousPoint.y) /
                safeRadius / (deltaMillis / 1_000.0)
        } else {
            FAST_SLIDE_RADII_PER_SECOND
        }
        val stability = (1.0 - speedInRadiiPerSecond / FAST_SLIDE_RADII_PER_SECOND)
            .coerceIn(0.0, 1.0)
        val pseudoForce = (
            placementForce +
                INWARD_TRAVEL_WEIGHT * inwardTravel +
                HOLD_WEIGHT * sqrt(holdProgress) * stability
            ).coerceIn(0.0, 1.0)

        val calibratedPressure = pressureCalibrator.sample(
            rawPressure = rawPressure,
            uptimeMillis = uptimeMillis,
            hardwarePressureHint = hardwarePressureHint,
        )
        val targetForce = pseudoForce +
            (calibratedPressure.normalized - pseudoForce) * calibratedPressure.confidence
        val initialStrikeForce = strikeForce ?: targetForce.also { strikeForce = it }
        val previousFilteredForce = filteredForce
        val force = if (previousFilteredForce == null) {
            targetForce
        } else if (deltaMillis <= 0L) {
            previousFilteredForce
        } else {
            val timeConstant = if (targetForce >= previousFilteredForce) {
                FORCE_ATTACK_MILLIS
            } else {
                FORCE_RELEASE_MILLIS
            }
            val smoothing = 1.0 - exp(-deltaMillis / timeConstant)
            previousFilteredForce + (targetForce - previousFilteredForce) * smoothing
        }.coerceIn(0.0, 1.0)

        filteredForce = force
        lastTimeMillis = uptimeMillis
        lastPoint = point

        return TouchForce(
            normalized = force.toFloat(),
            velocity = (MIN_VELOCITY +
                (MAX_VELOCITY - MIN_VELOCITY) * initialStrikeForce.pow(VELOCITY_GAMMA))
                .roundToInt()
                .coerceIn(MIN_VELOCITY, MAX_VELOCITY),
            expression = (MIN_EXPRESSION +
                (MAX_EXPRESSION - MIN_EXPRESSION) * force.pow(EXPRESSION_GAMMA))
                .roundToInt()
                .coerceIn(MIN_EXPRESSION, MAX_EXPRESSION),
            usesHardwarePressure = calibratedPressure.confidence >= HARDWARE_CONFIDENCE_THRESHOLD,
        )
    }

    private companion object {
        const val HOLD_RAMP_MILLIS = 420.0
        const val FAST_SLIDE_RADII_PER_SECOND = 7.0
        const val BASE_FORCE = 0.24
        const val PLACEMENT_WEIGHT = 0.52
        const val PLACEMENT_GAMMA = 0.80
        const val INWARD_TRAVEL_SCALE = 0.55
        const val INWARD_TRAVEL_WEIGHT = 0.38
        const val HOLD_WEIGHT = 0.05
        const val HARDWARE_CONFIDENCE_THRESHOLD = 0.50
        const val FORCE_ATTACK_MILLIS = 24.0
        const val FORCE_RELEASE_MILLIS = 72.0
        const val MAX_FILTER_DELTA_MILLIS = 100L

        const val MIN_VELOCITY = 36
        const val MAX_VELOCITY = 127
        const val VELOCITY_GAMMA = 0.82
        const val MIN_EXPRESSION = 52
        const val MAX_EXPRESSION = 127
        const val EXPRESSION_GAMMA = 0.92
    }
}

private fun normalizedHexRadius(
    point: HexPoint,
    center: HexPoint,
    radius: Double,
    rotationDegrees: Double,
): Double {
    val angle = Math.toRadians(-rotationDegrees)
    val cosine = cos(angle)
    val sine = sin(angle)
    val deltaX = point.x - center.x
    val deltaY = point.y - center.y
    val localX = deltaX * cosine - deltaY * sine
    val localY = deltaX * sine + deltaY * cosine
    val apothem = radius * SQRT_THREE_OVER_TWO
    return maxOf(
        abs(localY),
        abs(SQRT_THREE_OVER_TWO * localX + 0.5 * localY),
        abs(-SQRT_THREE_OVER_TWO * localX + 0.5 * localY),
    ) / apothem
}

private val SQRT_THREE_OVER_TWO = sqrt(3.0) / 2.0
