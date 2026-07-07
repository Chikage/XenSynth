package icu.ringona.xensynth.audio

class NativeAudioController(
    private val nativeAudio: NativeAudio = NativeAudioEngine
) {
    var ready: Boolean = false
        private set

    fun start(): Boolean {
        ready = runCatching {
            nativeAudio.setup() && nativeAudio.start()
        }.getOrDefault(false)
        return ready
    }

    fun recoverStartedStream(): Boolean {
        val alreadyStarted = runCatching { nativeAudio.isStarted() }.getOrDefault(false)
        ready = alreadyStarted || runCatching { nativeAudio.restart() }.getOrDefault(false)
        return ready
    }

    fun setGain(gain: Float): Boolean {
        return runCatching {
            nativeAudio.setGain(gain)
        }.isSuccess
    }

    fun noteOn(
        key: Int,
        velocity: Int,
        cents: Float = 0f,
        channel: Int = 0,
        program: Int = 0,
        bankMsb: Int = 0,
        bankLsb: Int = 0
    ): Int? {
        return runCatching {
            nativeAudio.noteOn(
                key = key,
                velocity = velocity,
                cents = cents,
                channel = channel,
                program = program,
                bankMsb = bankMsb,
                bankLsb = bankLsb
            )
        }.getOrNull()
    }

    fun noteOff(noteId: Int): Boolean {
        return runCatching {
            nativeAudio.noteOff(noteId)
        }.isSuccess
    }

    fun allSoundOff() {
        runCatching { nativeAudio.allSoundOff() }
    }

    fun teardown() {
        ready = false
        runCatching { nativeAudio.teardown() }
    }
}
