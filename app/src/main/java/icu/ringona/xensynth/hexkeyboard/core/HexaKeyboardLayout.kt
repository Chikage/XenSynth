package icu.ringona.xensynth.hexkeyboard.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class HexaKeyboardConfiguration(
    val columns: Int = 35,
    val rows: Int = 8,
    val period: Int = 53,
    val stepQ: Int = 9,
    val stepR: Int = 4,
    val radius: Int = 24,
    val rotationDegrees: Int = 12,
    val frameAcuteAngleDegrees: Double = 72.0,
) {
    fun normalized(): HexaKeyboardConfiguration = copy(
        columns = columns.coerceIn(4, 64),
        rows = rows.coerceIn(3, 32),
        period = period.coerceIn(1, 200),
        stepQ = stepQ.coerceIn(-200, 200),
        stepR = stepR.coerceIn(-200, 200),
        radius = 24,
        rotationDegrees = rotationDegrees.coerceIn(-60, 60),
    )

    companion object {
        val Default = HexaKeyboardConfiguration()
        val DEFAULT = Default
    }
}

data class HexKey(
    val coordinate: AxialCoordinate,
    val step: Int,
    val pitchClass: Int,
    val center: HexPoint,
)

data class HexWindowSlot(
    val column: Int,
    val row: Int,
    val key: HexKey,
) {
    val coordinate: AxialCoordinate get() = key.coordinate
    val center: HexPoint get() = key.center
    val q: Int get() = coordinate.q
    val r: Int get() = coordinate.r
    val s: Int get() = coordinate.s
    val step: Int get() = key.step
    val pitchClass: Int get() = key.pitchClass
}

data class RotationStats(
    val generated: Int,
    val omitted: Int,
)

data class PeriodVector(
    val dq: Int,
    val dr: Int,
    val distance: Int,
) {
    val coordinate: AxialCoordinate get() = AxialCoordinate(q = dq, r = dr)
}

data class HexaKeyboardLayout(
    val configuration: HexaKeyboardConfiguration,
    val cells: List<HexKey>,
    val slots: List<HexWindowSlot>,
    val stats: RotationStats,
    val periodVectors: List<PeriodVector>,
    val slotCenterBounds: HexBounds,
    val windowBounds: HexBounds,
    val windowOutline: HexParallelogram,
    val keyBounds: HexBounds,
) {
    val cellsByCoordinate: Map<AxialCoordinate, HexKey> = buildMap(cells.size) {
        cells.forEach { key ->
            if (key.coordinate !in this) put(key.coordinate, key)
        }
    }
    private val spatialIndex = HexKeySpatialIndex(
        keys = cells,
        keyRadius = configuration.radius.toDouble(),
    )

    val defaultSelection: HexKey?
        get() = cellAt(AxialCoordinate.Origin) ?: cells.getOrNull(cells.size / 2)

    fun cellAt(coordinate: AxialCoordinate): HexKey? =
        cellsByCoordinate[coordinate]

    internal fun nearestCell(point: HexPoint, maximumDistance: Double): HexKey? =
        spatialIndex.nearest(point, maximumDistance)
}

object HexaKeyboardLayoutEngine {
    fun build(
        configuration: HexaKeyboardConfiguration = HexaKeyboardConfiguration.Default,
    ): HexaKeyboardLayout {
        val normalized = configuration.normalized()
        val slots = buildWindowSlots(normalized)
        val slotCenterBounds = HexBounds.from(slots.map { it.center })
        val selection = selectCells(
            slots = slots,
            centerBounds = slotCenterBounds,
            configuration = normalized,
        )
        val radius = normalized.radius.toDouble()
        val windowBounds = HexBounds.from(slots.map { it.center }, radius)

        return HexaKeyboardLayout(
            configuration = normalized,
            cells = selection.cells,
            slots = slots,
            stats = selection.stats,
            periodVectors = periodVectors(normalized),
            slotCenterBounds = slotCenterBounds,
            windowBounds = windowBounds,
            windowOutline = HexGeometry.parallelogram(
                bounds = windowBounds,
                acuteAngleDegrees = normalized.frameAcuteAngleDegrees,
            ),
            keyBounds = HexBounds.from(selection.cells.map { it.center }, radius),
        )
    }

    fun periodVectors(
        configuration: HexaKeyboardConfiguration = HexaKeyboardConfiguration.Default,
    ): List<PeriodVector> {
        val normalized = configuration.normalized()
        val limit = min(
            24,
            max(8, ceil(sqrt(normalized.period.toDouble())).toInt() + 4),
        )
        val vectors = ArrayList<PeriodVector>()

        for (dq in -limit..limit) {
            for (dr in -limit..limit) {
                if (dq == 0 && dr == 0) continue
                val step = dq * normalized.stepQ + dr * normalized.stepR
                if (positiveModulo(step, normalized.period) != 0) continue
                val coordinate = AxialCoordinate(q = dq, r = dr)
                vectors += PeriodVector(
                    dq = dq,
                    dr = dr,
                    distance = HexGeometry.distance(coordinate),
                )
            }
        }

        vectors.sortWith(periodVectorComparator)
        val chosen = ArrayList<PeriodVector>(2)
        for (vector in vectors) {
            if (chosen.isEmpty()) {
                chosen += vector
                continue
            }

            val independent = chosen.all {
                it.dq * vector.dr - it.dr * vector.dq != 0
            }
            val opposite = chosen.any {
                it.dq == -vector.dq && it.dr == -vector.dr
            }
            if (independent && !opposite) chosen += vector
            if (chosen.size == 2) break
        }

        return if (chosen.isEmpty()) vectors.take(2) else chosen
    }

    private data class Candidate(
        val key: HexKey,
        val score: Double,
        val centerDistance: Double,
    )

    private data class Selection(
        val cells: List<HexKey>,
        val stats: RotationStats,
    )

    private fun buildWindowSlots(
        configuration: HexaKeyboardConfiguration,
    ): List<HexWindowSlot> {
        val originColumn = (configuration.columns - 1) / 2
        val originRow = (configuration.rows - 1) / 2
        val origin = HexGeometry.oddQToAxial(column = originColumn, row = originRow)
        val slots = ArrayList<HexWindowSlot>(configuration.columns * configuration.rows)

        for (column in 0 until configuration.columns) {
            for (row in 0 until configuration.rows) {
                val axial = HexGeometry.oddQToAxial(column = column, row = row)
                val coordinate = AxialCoordinate(
                    q = axial.q - origin.q,
                    r = axial.r - origin.r,
                )
                slots += HexWindowSlot(
                    column = column,
                    row = row,
                    key = makeKey(
                        coordinate = coordinate,
                        rotate = false,
                        configuration = configuration,
                    ),
                )
            }
        }

        return slots
    }

    private fun selectCells(
        slots: List<HexWindowSlot>,
        centerBounds: HexBounds,
        configuration: HexaKeyboardConfiguration,
    ): Selection {
        if (configuration.rotationDegrees == 0) {
            return Selection(
                cells = slots.map { it.key },
                stats = RotationStats(generated = 0, omitted = 0),
            )
        }

        val baseSet = slots.mapTo(HashSet(slots.size)) { it.coordinate }
        val center = centerBounds.center
        val radius = configuration.radius.toDouble()
        val range = ceil(hypot(centerBounds.width, centerBounds.height) / radius * 1.35).toInt() + 6
        val scoringGeometry = HexGeometry.parallelogram(
            bounds = centerBounds,
            acuteAngleDegrees = configuration.frameAcuteAngleDegrees,
        )
        val targetCellCount = configuration.columns * configuration.rows
        val candidates = PriorityQueue<Candidate>(
            targetCellCount,
            candidateComparator.reversed(),
        )

        for (q in -range..range) {
            for (r in -range..range) {
                val key = makeKey(
                    coordinate = AxialCoordinate(q = q, r = r),
                    rotate = true,
                    configuration = configuration,
                )
                val candidate = Candidate(
                    key = key,
                    score = HexGeometry.parallelogramScore(
                        point = key.center,
                        bounds = centerBounds,
                        geometry = scoringGeometry,
                    ),
                    centerDistance = HexGeometry.squaredDistance(key.center, center),
                )
                if (candidates.size < targetCellCount) {
                    candidates += candidate
                } else if (candidateComparator.compare(candidate, candidates.peek()) < 0) {
                    candidates.poll()
                    candidates += candidate
                }
            }
        }

        val cells = candidates
            .sortedWith(candidateComparator)
            .map { it.key }
            .sortedWith(visualCellComparator)
        val used = cells.mapTo(HashSet(cells.size)) { it.coordinate }

        return Selection(
            cells = cells,
            stats = RotationStats(
                generated = cells.count { it.coordinate !in baseSet },
                omitted = slots.count { it.coordinate !in used },
            ),
        )
    }

    private fun makeKey(
        coordinate: AxialCoordinate,
        rotate: Boolean,
        configuration: HexaKeyboardConfiguration,
    ): HexKey {
        val step = coordinate.q * configuration.stepQ + coordinate.r * configuration.stepR
        val point = HexGeometry.point(coordinate, configuration.radius.toDouble())
        val center = if (rotate) {
            HexGeometry.rotate(point, configuration.rotationDegrees.toDouble())
        } else {
            point
        }
        return HexKey(
            coordinate = coordinate,
            step = step,
            pitchClass = positiveModulo(step, configuration.period),
            center = center,
        )
    }

    private val candidateComparator = Comparator<Candidate> { first, second ->
        when {
            first.score != second.score -> first.score.compareTo(second.score)
            first.centerDistance != second.centerDistance ->
                first.centerDistance.compareTo(second.centerDistance)
            first.key.coordinate.q != second.key.coordinate.q -> first.key.coordinate.q.compareTo(second.key.coordinate.q)
            else -> first.key.coordinate.r.compareTo(second.key.coordinate.r)
        }
    }

    private val visualCellComparator = Comparator<HexKey> { first, second ->
        when {
            first.center.y != second.center.y -> first.center.y.compareTo(second.center.y)
            else -> first.center.x.compareTo(second.center.x)
        }
    }

    private val periodVectorComparator = Comparator<PeriodVector> { first, second ->
        val firstManhattan = abs(first.dq) + abs(first.dr)
        val secondManhattan = abs(second.dq) + abs(second.dr)
        when {
            first.distance != second.distance -> first.distance.compareTo(second.distance)
            firstManhattan != secondManhattan -> firstManhattan.compareTo(secondManhattan)
            first.dq != second.dq -> first.dq.compareTo(second.dq)
            else -> first.dr.compareTo(second.dr)
        }
    }
}

private fun positiveModulo(value: Int, modulus: Int): Int {
    val remainder = value % modulus
    return if (remainder >= 0) remainder else remainder + modulus
}
