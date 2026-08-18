package icu.ringona.rtpmidi

internal const val MIDI_PRIORITY_CONTINUOUS = 0
internal const val MIDI_PRIORITY_ORDINARY = 1
internal const val MIDI_PRIORITY_CRITICAL_RELEASE = 2

internal val MIDI_DISCRETE_CONTROLLERS = buildSet {
    add(0)
    add(6)
    add(32)
    add(38)
    add(64)
    addAll(96..101)
    addAll(120..127)
}

internal fun midiDeliveryPriority(bytes: ByteArray): Int {
    if (isCriticalMidiRelease(bytes)) return MIDI_PRIORITY_CRITICAL_RELEASE
    if (bytes.isEmpty()) return MIDI_PRIORITY_ORDINARY
    return when (bytes[0].unsignedMidiByte() and 0xF0) {
        0xA0, 0xD0, 0xE0 -> MIDI_PRIORITY_CONTINUOUS
        0xB0 -> {
            val controller = bytes.getOrNull(1)?.unsignedMidiByte()
                ?: return MIDI_PRIORITY_ORDINARY
            if (controller in MIDI_DISCRETE_CONTROLLERS) {
                MIDI_PRIORITY_ORDINARY
            } else {
                MIDI_PRIORITY_CONTINUOUS
            }
        }
        else -> MIDI_PRIORITY_ORDINARY
    }
}

internal fun isCriticalMidiRelease(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    return when (bytes[0].unsignedMidiByte() and 0xF0) {
        0x80 -> true
        0x90 -> bytes.getOrNull(2)?.unsignedMidiByte() == 0
        0xB0 -> {
            val controller = bytes.getOrNull(1)?.unsignedMidiByte() ?: return false
            val value = bytes.getOrNull(2)?.unsignedMidiByte() ?: return false
            controller in 120..127 || controller == 64 && value < 64
        }
        else -> false
    }
}

private fun Byte.unsignedMidiByte(): Int = toInt() and 0xFF
