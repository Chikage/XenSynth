package icu.ringona.xensynth.audio

interface NativeAudio {
    fun setup(): Boolean
    fun start(): Boolean
    fun teardown()
    fun restart(): Boolean
    fun isStarted(): Boolean
    fun loadSf2(path: String): Boolean
    fun loadBuiltinSf2(): Boolean
    fun unloadSf2()
    fun hasSoundFont(): Boolean
    fun noteOn(
        key: Int,
        velocity: Int,
        cents: Float,
        channel: Int = 0,
        program: Int = 0,
        bankMsb: Int = 0,
        bankLsb: Int = 0,
        delaySeconds: Double = 0.0,
        expression: Int = 127,
    ): Int?

    fun noteOnAt(
        key: Int,
        velocity: Int,
        cents: Float,
        channel: Int = 0,
        program: Int = 0,
        bankMsb: Int = 0,
        bankLsb: Int = 0,
        targetTimeNanos: Long,
        expression: Int = 127,
    ): Int? = noteOn(
        key = key,
        velocity = velocity,
        cents = cents,
        channel = channel,
        program = program,
        bankMsb = bankMsb,
        bankLsb = bankLsb,
        expression = expression,
    )

    fun noteOff(noteId: Int)
    fun noteOffImmediately(noteId: Int) = noteOff(noteId)
    fun scheduleNoteOff(noteId: Int, delaySeconds: Double, immediate: Boolean = false) {
        if (immediate) noteOffImmediately(noteId) else noteOff(noteId)
    }
    fun noteOffAt(noteId: Int, targetTimeNanos: Long, immediate: Boolean = false) {
        if (immediate) noteOffImmediately(noteId) else noteOff(noteId)
    }
    fun setNotePressure(noteId: Int, expression: Int) = Unit
    fun allSoundOff()
    fun setGain(gain: Float)
    fun setReverb(value: Int)
    fun setPitchCalibration(cents: FloatArray)
}
