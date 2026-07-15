package icu.ringona.xensynth.hexkeyboard.core

/** Integer axial coordinate on the flat-top hexagonal lattice. */
data class AxialCoordinate(
    val q: Int,
    val r: Int,
) {
    val s: Int get() = -q - r

    companion object {
        val Origin = AxialCoordinate(q = 0, r = 0)
        val ORIGIN = Origin
    }
}


