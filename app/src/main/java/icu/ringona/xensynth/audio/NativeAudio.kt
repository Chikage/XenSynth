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
        delaySeconds: Double = 0.0
    ): Int?

    fun noteOff(noteId: Int)
    fun allSoundOff()
    fun setGain(gain: Float)
    fun setReverb(value: Int)
    fun setPitchCalibration(cents: FloatArray)
}
