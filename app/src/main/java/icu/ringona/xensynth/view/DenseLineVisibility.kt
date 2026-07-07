package icu.ringona.xensynth.view

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal object DenseLineVisibility {
    private const val STEP_EPSILON = 0.0001
    private const val MIN_VISIBLE_RATIO = 0.02f

    fun ratioForStep(
        stepIndex: Int,
        step: Double,
        minPitchSpacing: Double,
        isAnchor: Boolean = false
    ): Float {
        if (isAnchor) {
            return 1f
        }
        if (step <= STEP_EPSILON || minPitchSpacing <= STEP_EPSILON) {
            return 1f
        }
        val desiredStride = minPitchSpacing / step
        if (!desiredStride.isFinite() || desiredStride <= 1.0) {
            return 1f
        }

        val fineStride = floor(desiredStride).toInt().coerceAtLeast(1)
        val coarseStride = ceil(desiredStride).toInt().coerceAtLeast(1)
        if (fineStride == coarseStride) {
            return if (positiveModulo(stepIndex, coarseStride) == 0) 1f else 0f
        }

        val fineWeight = smoothStep((coarseStride - desiredStride).coerceIn(0.0, 1.0)).toFloat()
        val coarseWeight = 1f - fineWeight
        var ratio = 0f
        if (positiveModulo(stepIndex, fineStride) == 0) {
            ratio = max(ratio, fineWeight)
        }
        if (positiveModulo(stepIndex, coarseStride) == 0) {
            ratio = max(ratio, coarseWeight)
        }
        return if (ratio >= MIN_VISIBLE_RATIO) ratio else 0f
    }

    private fun smoothStep(value: Double): Double {
        return value * value * (3.0 - 2.0 * value)
    }

    private fun positiveModulo(value: Int, mod: Int): Int {
        return if (mod == 0) 0 else ((value % mod) + mod) % mod
    }
}
