package icu.ringona.xensynth

internal fun hexStepRangeForEdo(edo: Int): IntRange {
    val upperBound = (edo - 1).coerceAtLeast(1)
    return 1..upperBound
}
