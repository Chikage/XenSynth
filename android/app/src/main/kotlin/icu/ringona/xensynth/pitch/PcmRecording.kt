package icu.ringona.xensynth.pitch

internal class PcmRecording(val sampleRate: Int) {
    private val lock = Any()
    private val chunks = mutableListOf<ShortArray>()
    private var sampleCount = 0L

    val durationSeconds: Double
        get() = synchronized(lock) { sampleCount.toDouble() / sampleRate }

    fun append(samples: ShortArray, count: Int) {
        val safeCount = count.coerceIn(0, samples.size)
        if (safeCount == 0) return
        val copy = samples.copyOf(safeCount)
        synchronized(lock) {
            chunks += copy
            sampleCount += safeCount
        }
    }

    fun snapshot(): ShortArray = synchronized(lock) {
        check(sampleCount <= Int.MAX_VALUE) { "Microphone recording is too large to replay" }
        val result = ShortArray(sampleCount.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        result
    }
}

internal data class PitchRecordingSnapshot(
    val sampleRate: Int,
    val samples: ShortArray,
)
