package icu.ringona.xensynth

import icu.ringona.xensynth.audio.NativeAudioScheduler
import icu.ringona.xensynth.audio.PlaybackAudioScheduler
import icu.ringona.xensynth.midi.ParsedScore

class NativePlaybackCoordinator(
    private val callbacks: Callbacks,
    audioSchedulerFactory: (NativeAudioScheduler.Callbacks) -> PlaybackAudioScheduler = { NativeAudioScheduler(it) },
    frameScheduler: NativePlaybackController.FrameScheduler = NativePlaybackController.ChoreographerFrameScheduler
) {
    interface Callbacks {
        fun requestDefaultSoundFont()
        fun onPlayheadChanged(score: ParsedScore?, playheadSeconds: Double)
        fun onStateChanged()
        fun onPlaybackFinished()
        fun onStatusText(text: String)
    }

    private val audioScheduler = audioSchedulerFactory(
        object : NativeAudioScheduler.Callbacks {
            override fun canUseNativeAudio(): Boolean = this@NativePlaybackCoordinator.canUseNativeAudio()

            override fun onAudioReadyChanged(ready: Boolean) {
                audioReady = ready
            }
        }
    )
    private val playbackController = NativePlaybackController(
        audioScheduler,
        object : NativePlaybackController.Callbacks {
            override fun onLoadingCancelled() {
                pendingDefaultSoundFontPlayback = false
            }

            override fun onPlayheadChanged(score: ParsedScore?, playheadSeconds: Double) {
                callbacks.onPlayheadChanged(score, playheadSeconds)
            }

            override fun onStateChanged() {
                callbacks.onStateChanged()
            }

            override fun onPlaybackFinished() {
                callbacks.onPlaybackFinished()
            }
        },
        frameScheduler
    )
    private val playbackSession = NativePlaybackSession(
        object : NativePlaybackSession.Callbacks {
            override fun hasScore(): Boolean = score != null
            override fun isPlaying(): Boolean = playing
            override fun isLoading(): Boolean = loading
            override fun isFinished(): Boolean = finished
            override fun canUseNativeAudio(): Boolean = this@NativePlaybackCoordinator.canUseNativeAudio()
            override fun needsDefaultSoundFont(): Boolean = audioReady && !soundFontReady

            override fun requestDefaultSoundFont() {
                callbacks.requestDefaultSoundFont()
            }

            override fun clearPendingDefaultSoundFontPlayback() {
                pendingDefaultSoundFontPlayback = false
            }

            override fun setPendingDefaultSoundFontPlayback(pending: Boolean) {
                pendingDefaultSoundFontPlayback = pending
            }

            override fun setLoading(loading: Boolean) {
                this@NativePlaybackCoordinator.loading = loading
            }

            override fun resetNativePlayback() {
                reset()
            }

            override fun setNativePlaying(playing: Boolean) {
                playOrPause(playing)
            }

            override fun setStatusText(text: String) {
                callbacks.onStatusText(text)
            }

            override fun startNativeAudioPlayback() {
                this@NativePlaybackCoordinator.startNativeAudioPlayback()
            }

            override fun updatePlayButtonIcon() {
                callbacks.onStateChanged()
            }

            override fun updateStageControls() {
                callbacks.onStateChanged()
            }
        }
    )

    private var audioReady = false
    private var soundFontReady = false
    private var pendingDefaultSoundFontPlayback = false

    var score: ParsedScore?
        get() = playbackController.score
        set(value) {
            playbackController.score = value
        }
    var playheadSeconds: Double
        get() = playbackController.playheadSeconds
        set(value) {
            playbackController.playheadSeconds = value
        }
    var speed: Double
        get() = playbackController.speed
        set(value) {
            playbackController.speed = value
        }
    var audioScheduleOffsetSeconds: Double
        get() = playbackController.audioScheduleOffsetSeconds
        set(value) {
            playbackController.audioScheduleOffsetSeconds = value
        }
    val playing: Boolean
        get() = playbackController.playing
    var loading: Boolean
        get() = playbackController.loading
        set(value) {
            playbackController.loading = value
        }
    var finished: Boolean
        get() = playbackController.finished
        set(value) {
            playbackController.finished = value
        }

    fun setAudioReady(ready: Boolean) {
        audioReady = ready
    }

    fun canUseNativeAudio(): Boolean {
        return audioReady && soundFontReady
    }

    fun shouldLoadDefaultSoundFont(soundFontLoading: Boolean): Boolean {
        return audioReady && !soundFontReady && !soundFontLoading
    }

    fun onDefaultSoundFontLoadFinished(ready: Boolean) {
        soundFontReady = ready
        if (soundFontReady && pendingDefaultSoundFontPlayback && score != null) {
            pendingDefaultSoundFontPlayback = false
            startNativeAudioPlayback()
            return
        }
        callbacks.onStateChanged()
    }

    fun clearPendingDefaultSoundFontPlayback() {
        pendingDefaultSoundFontPlayback = false
    }

    fun togglePlayback() {
        playbackSession.togglePlayback()
    }

    fun playOrPause(playing: Boolean) {
        playbackController.playOrPause(playing)
    }

    fun reset() {
        playbackController.reset()
    }

    fun beginSeekGesture() {
        playbackController.beginSeekGesture()
    }

    fun endSeekGesture() {
        playbackController.endSeekGesture()
    }

    fun stopClock() {
        playbackController.stopClock()
    }

    fun isPaused(): Boolean {
        return playbackController.isPaused()
    }

    fun resetAudioScheduler(score: ParsedScore?, playheadSeconds: Double) {
        playbackController.resetAudioScheduler(score, playheadSeconds)
    }

    fun stopAudio() {
        audioScheduler.stop()
    }

    private fun startNativeAudioPlayback() {
        if (!audioScheduler.ensureStarted()) {
            loading = false
            pendingDefaultSoundFontPlayback = false
            callbacks.onStatusText("Native audio start failed")
            callbacks.onStateChanged()
            return
        }
        loading = false
        playOrPause(true)
    }
}
