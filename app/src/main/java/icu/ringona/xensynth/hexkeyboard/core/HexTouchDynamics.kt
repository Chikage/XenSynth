package icu.ringona.xensynth.hexkeyboard.core

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

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

internal data class CalibratedTouchArea(
    val change: Double,
    val confidence: Double,
)

private data class RobustSignalBounds(
    val minimum: Double,
    val maximum: Double,
)

private class RobustSignalWindow(
    capacity: Int,
) {
    private val values = FloatArray(capacity)
    private var nextIndex = 0
    var count: Int = 0
        private set

    fun add(value: Float) {
        values[nextIndex] = value
        nextIndex = (nextIndex + 1) % values.size
        count = minOf(count + 1, values.size)
    }

    fun bounds(trimSingleOutlier: Boolean): RobustSignalBounds {
        var minimum = Float.POSITIVE_INFINITY
        var secondMinimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var secondMaximum = Float.NEGATIVE_INFINITY
        repeat(count) { index ->
            val value = values[index]
            if (value <= minimum) {
                secondMinimum = minimum
                minimum = value
            } else if (value < secondMinimum) {
                secondMinimum = value
            }
            if (value >= maximum) {
                secondMaximum = maximum
                maximum = value
            } else if (value > secondMaximum) {
                secondMaximum = value
            }
        }
        return if (trimSingleOutlier) {
            RobustSignalBounds(secondMinimum.toDouble(), secondMaximum.toDouble())
        } else {
            RobustSignalBounds(minimum.toDouble(), maximum.toDouble())
        }
    }
}

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
    private val recentPressures = RobustSignalWindow(PRESSURE_WINDOW_SIZE)
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
        recentPressures.add(pressure)

        val bounds = recentPressures.bounds(
            trimSingleOutlier = recentPressures.count >= MIN_FINGER_PRESSURE_SAMPLES,
        )
        val observedRange = bounds.maximum - bounds.minimum
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
        val adaptiveFloor = bounds.minimum - ADAPTIVE_PRESSURE_MARGIN
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

/** Uses relative finger-pad growth as a location-independent fallback signal. */
internal class TouchAreaCalibrator {
    private var startTimeMillis: Long? = null
    private var referenceArea: Double? = null
    private var sampleCount = 0
    private val recentAreas = RobustSignalWindow(AREA_WINDOW_SIZE)
    private var confidence = 0.0

    fun sample(rawArea: Float, uptimeMillis: Long): CalibratedTouchArea {
        val area = rawArea
            .takeIf { it.isFinite() && it > MIN_VALID_CONTACT_AREA }
            ?.toDouble()
            ?: return CalibratedTouchArea(change = 0.0, confidence = 0.0)
        val startedAt = startTimeMillis ?: uptimeMillis.also { startTimeMillis = it }
        val reference = referenceArea ?: area.also { referenceArea = it }
        sampleCount += 1
        recentAreas.add(area.toFloat())

        val bounds = recentAreas.bounds(
            trimSingleOutlier = recentAreas.count >= MIN_AREA_SAMPLES,
        )
        val relativeRange = if (bounds.minimum > MIN_VALID_CONTACT_AREA) {
            bounds.maximum / bounds.minimum - 1.0
        } else {
            0.0
        }
        val elapsedMillis = (uptimeMillis - startedAt).coerceAtLeast(0L)
        val observedConfidence = if (
            sampleCount >= MIN_AREA_SAMPLES && elapsedMillis >= MIN_AREA_OBSERVATION_MILLIS
        ) {
            ((relativeRange - AREA_RELATIVE_RANGE_FLOOR) / AREA_CONFIDENCE_SPAN)
                .coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        confidence = max(confidence, observedConfidence)

        return CalibratedTouchArea(
            change = (ln(area / reference) / LOG_FULL_SCALE_AREA_RATIO).coerceIn(-1.0, 1.0),
            confidence = confidence,
        )
    }

    private companion object {
        const val MIN_VALID_CONTACT_AREA = 0.0001f
        const val AREA_WINDOW_SIZE = 12
        const val MIN_AREA_SAMPLES = 5
        const val MIN_AREA_OBSERVATION_MILLIS = 64L
        const val AREA_RELATIVE_RANGE_FLOOR = 0.08
        const val AREA_CONFIDENCE_SPAN = 0.22
        val LOG_FULL_SCALE_AREA_RATIO = ln(1.55)
    }
}

internal class TouchDynamicsCalibrator {
    val pressure = TouchPressureCalibrator()
    val contactArea = TouchAreaCalibrator()
}

class PseudoPressureTracker internal constructor(
    private val calibrator: TouchDynamicsCalibrator,
) {
    constructor() : this(TouchDynamicsCalibrator())

    private var lastTimeMillis: Long? = null
    private var filteredForce: Double? = null
    private var strikeForce: Double? = null

    fun sample(
        rawPressure: Float,
        rawContactArea: Float = Float.NaN,
        uptimeMillis: Long,
        hardwarePressureHint: Boolean = false,
    ): TouchForce {
        val previousTime = lastTimeMillis
        val deltaMillis = previousTime
            ?.let { (uptimeMillis - it).coerceIn(0L, MAX_FILTER_DELTA_MILLIS) }
            ?: 0L

        val calibratedPressure = calibrator.pressure.sample(
            rawPressure = rawPressure,
            uptimeMillis = uptimeMillis,
            hardwarePressureHint = hardwarePressureHint,
        )
        val calibratedArea = calibrator.contactArea.sample(
            rawArea = rawContactArea,
            uptimeMillis = uptimeMillis,
        )
        val areaForce = (FALLBACK_FORCE + CONTACT_AREA_FORCE_SPAN * calibratedArea.change)
            .coerceIn(0.0, 1.0)
        val fallbackForce = FALLBACK_FORCE +
            (areaForce - FALLBACK_FORCE) * calibratedArea.confidence
        val targetForce = fallbackForce +
            (calibratedPressure.normalized - fallbackForce) * calibratedPressure.confidence
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
        const val FALLBACK_FORCE = 0.64
        const val CONTACT_AREA_FORCE_SPAN = 0.36
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
