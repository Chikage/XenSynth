package icu.ringona.xensynth

import icu.ringona.xensynth.audio.NativeAudio

class FakeNativeAudio : NativeAudio {
    var setupResult = true
    var startResult = true
    var restartResult = true
    var started = false
    var throwOnIsStarted = false
    var throwOnRestart = false
    var nextNoteId: Int? = 1
    var setupCalls = 0
    var startCalls = 0
    var restartCalls = 0
    var teardownCalls = 0
    var allSoundOffCalls = 0
    var setGainCalls = 0
    var lastGain = 0f
    val noteOns = mutableListOf<NoteOnCall>()
    val noteOffs = mutableListOf<Int>()

    override fun setup(): Boolean {
        setupCalls++
        return setupResult
    }

    override fun start(): Boolean {
        startCalls++
        started = startResult
        return startResult
    }

    override fun teardown() {
        teardownCalls++
        started = false
    }

    override fun restart(): Boolean {
        restartCalls++
        if (throwOnRestart) {
            error("restart failed")
        }
        started = restartResult
        return restartResult
    }

    override fun isStarted(): Boolean {
        if (throwOnIsStarted) {
            error("state unavailable")
        }
        return started
    }

    override fun loadSf2(path: String): Boolean = true

    override fun soundFontKey(): ByteArray = ByteArray(32)

    override fun unloadSf2() = Unit

    override fun hasSoundFont(): Boolean = true

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
        noteOns += NoteOnCall(
            key = key,
            velocity = velocity,
            cents = cents,
            channel = channel,
            program = program,
            bankMsb = bankMsb,
            bankLsb = bankLsb,
            delaySeconds = delaySeconds
        )
        return nextNoteId
    }

    override fun noteOff(noteId: Int) {
        noteOffs += noteId
    }

    override fun allSoundOff() {
        allSoundOffCalls++
    }

    override fun setGain(gain: Float) {
        setGainCalls++
        lastGain = gain
    }

    override fun setReverb(value: Int) = Unit

    override fun setPitchCalibration(cents: FloatArray) = Unit

    data class NoteOnCall(
        val key: Int,
        val velocity: Int,
        val cents: Float,
        val channel: Int,
        val program: Int,
        val bankMsb: Int,
        val bankLsb: Int,
        val delaySeconds: Double
    )
}
