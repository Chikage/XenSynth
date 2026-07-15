package icu.ringona.xensynth.hexkeyboard.core

import kotlin.math.floor

/**
 * Uniform-grid nearest-neighbor index. Hex centers have a fixed minimum spacing,
 * so a hit-test radius intersects only a constant number of small buckets.
 */
internal class HexKeySpatialIndex(
    keys: List<HexKey>,
    keyRadius: Double,
) {
    private data class IndexedKey(
        val key: HexKey,
        val visualIndex: Int,
    )

    private val bucketSize = keyRadius.coerceAtLeast(1.0) * BUCKET_RADIUS_SCALE
    private val buckets: Map<Long, List<IndexedKey>> = buildMap {
        keys.forEachIndexed { index, key ->
            val bucketX = bucketCoordinate(key.center.x) ?: return@forEachIndexed
            val bucketY = bucketCoordinate(key.center.y) ?: return@forEachIndexed
            val bucketKey = packedBucketKey(bucketX, bucketY)
            val entries = get(bucketKey)?.toMutableList() ?: ArrayList(BUCKET_INITIAL_CAPACITY)
            entries += IndexedKey(key = key, visualIndex = index)
            put(bucketKey, entries)
        }
    }

    fun nearest(point: HexPoint, maximumDistance: Double): HexKey? {
        if (!point.x.isFinite() || !point.y.isFinite() || !maximumDistance.isFinite()) return null
        val safeDistance = maximumDistance.coerceAtLeast(0.0)
        val minimumBucketX = bucketCoordinate(point.x - safeDistance) ?: return null
        val maximumBucketX = bucketCoordinate(point.x + safeDistance) ?: return null
        val minimumBucketY = bucketCoordinate(point.y - safeDistance) ?: return null
        val maximumBucketY = bucketCoordinate(point.y + safeDistance) ?: return null
        val maximumDistanceSquared = safeDistance * safeDistance
        var best: IndexedKey? = null
        var bestDistanceSquared = maximumDistanceSquared

        var bucketX = minimumBucketX.toLong()
        while (bucketX <= maximumBucketX.toLong()) {
            var bucketY = minimumBucketY.toLong()
            while (bucketY <= maximumBucketY.toLong()) {
                buckets[packedBucketKey(bucketX.toInt(), bucketY.toInt())]?.forEach { candidate ->
                    val distanceSquared = HexGeometry.squaredDistance(point, candidate.key.center)
                    val currentBest = best
                    if (
                        distanceSquared <= maximumDistanceSquared &&
                        (
                            currentBest == null ||
                                distanceSquared < bestDistanceSquared ||
                                (
                                    distanceSquared == bestDistanceSquared &&
                                        candidate.visualIndex < currentBest.visualIndex
                                    )
                            )
                    ) {
                        best = candidate
                        bestDistanceSquared = distanceSquared
                    }
                }
                bucketY++
            }
            bucketX++
        }

        return best?.key
    }

    private fun bucketCoordinate(value: Double): Int? {
        val coordinate = floor(value / bucketSize)
        if (!coordinate.isFinite() ||
            coordinate < Int.MIN_VALUE.toDouble() ||
            coordinate > Int.MAX_VALUE.toDouble()
        ) {
            return null
        }
        return coordinate.toInt()
    }

    private fun packedBucketKey(x: Int, y: Int): Long =
        (x.toLong() shl Int.SIZE_BITS) xor (y.toLong() and UNSIGNED_INT_MASK)

    private companion object {
        const val BUCKET_RADIUS_SCALE = 2.0
        const val BUCKET_INITIAL_CAPACITY = 4
        const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL
    }
}
