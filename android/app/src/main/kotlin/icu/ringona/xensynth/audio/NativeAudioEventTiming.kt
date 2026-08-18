package icu.ringona.xensynth.audio

internal data class NativeAudioEventTiming(
    val delaySeconds: Double,
    val lateByNanos: Long,
) {
    val isLate: Boolean
        get() = lateByNanos > 0L
}

/** Converts an AppleMIDI monotonic target into a delay consumed by the native audio clock. */
internal fun nativeAudioEventTiming(
    targetTimeNanos: Long?,
    nowNanos: Long,
): NativeAudioEventTiming {
    if (targetTimeNanos == null || targetTimeNanos <= 0L) {
        return NativeAudioEventTiming(delaySeconds = 0.0, lateByNanos = 0L)
    }
    if (targetTimeNanos <= nowNanos) {
        return NativeAudioEventTiming(
            delaySeconds = 0.0,
            lateByNanos = nowNanos - targetTimeNanos,
        )
    }
    return NativeAudioEventTiming(
        delaySeconds = (targetTimeNanos - nowNanos) / NANOS_PER_SECOND,
        lateByNanos = 0L,
    )
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
