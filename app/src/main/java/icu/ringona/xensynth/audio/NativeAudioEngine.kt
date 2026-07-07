package icu.ringona.xensynth.audio

object NativeAudioEngine : NativeAudio {
    init {
        System.loadLibrary("xenaudio")
    }

    override fun setup(): Boolean = setupNative()

    override fun start(): Boolean = startNative()

    override fun teardown() = teardownNative()

    override fun restart(): Boolean = restartNative()

    override fun isStarted(): Boolean = isStartedNative()

    override fun loadSf2(path: String): Boolean = loadSf2Native(path)

    override fun soundFontKey(): ByteArray = soundFontKeyNative()

    override fun unloadSf2() = unloadSf2Native()

    override fun hasSoundFont(): Boolean = hasSoundFontNative()

    override fun noteOn(
        key: Int,
        velocity: Int,
        cents: Float,
        channel: Int,
        program: Int,
        bankMsb: Int,
        bankLsb: Int,
        delaySeconds: Double
    ): Int? {
        val noteId = noteOnNative(
            key.coerceIn(0, 127),
            velocity.coerceIn(1, 127),
            cents,
            channel.coerceIn(0, 15),
            program.coerceIn(0, 127),
            bankMsb.coerceIn(0, 127),
            bankLsb.coerceIn(0, 127),
            delaySeconds.coerceAtLeast(0.0)
        )
        return noteId.takeIf { it >= 0 }
    }

    override fun noteOff(noteId: Int) = noteOffNative(noteId)

    override fun allSoundOff() = allSoundOffNative()

    override fun setGain(gain: Float) = setGainNative(gain.coerceIn(0f, MAX_GAIN))

    override fun setReverb(value: Int) = setReverbNative(value.coerceIn(0, 100))

    override fun setPitchCalibration(cents: FloatArray) = setPitchCalibrationNative(cents)

    private external fun setupNative(): Boolean

    private external fun startNative(): Boolean

    private external fun teardownNative()

    private external fun restartNative(): Boolean

    private external fun isStartedNative(): Boolean

    private external fun loadSf2Native(filePath: String): Boolean

    private external fun soundFontKeyNative(): ByteArray

    private external fun unloadSf2Native()

    private external fun hasSoundFontNative(): Boolean

    private external fun noteOnNative(
        key: Int,
        velocity: Int,
        cents: Float,
        channel: Int,
        program: Int,
        bankMsb: Int,
        bankLsb: Int,
        delaySeconds: Double
    ): Int

    private external fun noteOffNative(noteId: Int)

    private external fun allSoundOffNative()

    private external fun setGainNative(gain: Float)

    private external fun setReverbNative(value: Int)

    private external fun setPitchCalibrationNative(cents: FloatArray)

    private const val MAX_GAIN = 6f
}
