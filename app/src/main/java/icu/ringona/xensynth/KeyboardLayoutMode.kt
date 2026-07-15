package icu.ringona.xensynth

internal enum class KeyboardLayoutMode(
    val preferenceValue: String,
    val label: String
) {
    Linear("linear", "LINEAR"),
    Hexagonal("hexagonal", "HEX");

    companion object {
        fun fromPreference(value: String?): KeyboardLayoutMode {
            return entries.firstOrNull { it.preferenceValue == value } ?: Linear
        }
    }
}
