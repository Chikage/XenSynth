package icu.ringona.xensynth.hexkeyboard.ui

import icu.ringona.xensynth.hexkeyboard.core.HexKey

internal fun hexKeyLabel(key: HexKey, period: Int): String {
    if (key.pitchClass != 0) return key.pitchClass.toString()

    val octave = MIDDLE_C_OCTAVE + Math.floorDiv(key.step, period.coerceAtLeast(1))
    return "C$octave"
}

private const val MIDDLE_C_OCTAVE = 4
