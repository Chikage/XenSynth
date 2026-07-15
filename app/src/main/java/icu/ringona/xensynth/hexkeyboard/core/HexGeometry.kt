package icu.ringona.xensynth.hexkeyboard.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class HexPoint(
    val x: Double,
    val y: Double,
)

data class HexBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val center: HexPoint
        get() = HexPoint(
            x = (minX + maxX) / 2.0,
            y = (minY + maxY) / 2.0,
        )

    companion object {
        fun from(points: Iterable<HexPoint>, radius: Double = 0.0): HexBounds {
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var hasPoint = false

            for (point in points) {
                hasPoint = true
                minX = min(minX, point.x - radius)
                maxX = max(maxX, point.x + radius)
                minY = min(minY, point.y - radius)
                maxY = max(maxY, point.y + radius)
            }

            require(hasPoint) { "At least one point is required" }
            return HexBounds(minX = minX, maxX = maxX, minY = minY, maxY = maxY)
        }

        fun merge(first: HexBounds, vararg rest: HexBounds): HexBounds =
            rest.fold(first) { result, next ->
                HexBounds(
                    minX = min(result.minX, next.minX),
                    maxX = max(result.maxX, next.maxX),
                    minY = min(result.minY, next.minY),
                    maxY = max(result.maxY, next.maxY),
                )
            }
    }
}

/** Corners are ordered top-left, top-right, bottom-right, bottom-left. */
data class HexParallelogram(
    val points: List<HexPoint>,
    val bounds: HexBounds,
    val horizontalShift: Double,
) {
    init {
        require(points.size == 4) { "A parallelogram must have four corners" }
    }

    val topLeft: HexPoint get() = points[0]
    val topRight: HexPoint get() = points[1]
    val bottomRight: HexPoint get() = points[2]
    val bottomLeft: HexPoint get() = points[3]
}

object HexGeometry {
    fun oddQToAxial(column: Int, row: Int): AxialCoordinate =
        AxialCoordinate(
            q = column,
            r = row - (column - column.and(1)) / 2,
        )

    fun distance(coordinate: AxialCoordinate): Int =
        (abs(coordinate.q) + abs(coordinate.r) + abs(coordinate.q + coordinate.r)) / 2

    fun distance(start: AxialCoordinate, end: AxialCoordinate): Int =
        distance(AxialCoordinate(q = end.q - start.q, r = end.r - start.r))

    /** Converts a flat-top axial coordinate to its key center. */
    fun point(coordinate: AxialCoordinate, radius: Double): HexPoint =
        HexPoint(
            x = radius * 1.5 * coordinate.q.toDouble(),
            y = radius * sqrt(3.0) * (coordinate.r.toDouble() + coordinate.q.toDouble() / 2.0),
        )

    fun rotate(point: HexPoint, degrees: Double): HexPoint {
        val angle = Math.toRadians(degrees)
        val cosine = cos(angle)
        val sine = sin(angle)
        return HexPoint(
            x = point.x * cosine - point.y * sine,
            y = point.x * sine + point.y * cosine,
        )
    }

    fun squaredDistance(first: HexPoint, second: HexPoint): Double {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return dx * dx + dy * dy
    }

    fun parallelogram(
        bounds: HexBounds,
        acuteAngleDegrees: Double = 72.0,
    ): HexParallelogram {
        val shift = bounds.height / tan(Math.toRadians(acuteAngleDegrees))
        val halfShift = shift / 2.0
        val points = listOf(
            HexPoint(x = bounds.minX - halfShift, y = bounds.minY),
            HexPoint(x = bounds.maxX - halfShift, y = bounds.minY),
            HexPoint(x = bounds.maxX + halfShift, y = bounds.maxY),
            HexPoint(x = bounds.minX + halfShift, y = bounds.maxY),
        )
        return HexParallelogram(
            points = points,
            bounds = HexBounds.from(points),
            horizontalShift = shift,
        )
    }

    /**
     * Returns a normalized Chebyshev-like distance in the target parallelogram's
     * (u, v) basis. The smallest scores are the cells retained by resampling.
     */
    fun parallelogramScore(
        point: HexPoint,
        bounds: HexBounds,
        acuteAngleDegrees: Double = 72.0,
    ): Double = parallelogramScore(
        point = point,
        bounds = bounds,
        geometry = parallelogram(bounds, acuteAngleDegrees),
    )

    internal fun parallelogramScore(
        point: HexPoint,
        bounds: HexBounds,
        geometry: HexParallelogram,
    ): Double {
        val width = max(1.0, bounds.width)
        val height = max(1.0, bounds.height)
        val v = (point.y - geometry.topLeft.y) / height
        val u = (point.x - geometry.topLeft.x - v * geometry.horizontalShift) / width
        return max(
            abs(u - 0.5) / 0.5,
            abs(v - 0.5) / 0.5,
        )
    }
}

