package icu.ringona.xensynth

import android.view.Choreographer
import icu.ringona.xensynth.audio.PlaybackAudioScheduler
import icu.ringona.xensynth.midi.ParsedScore

class NativePlaybackController(
    private val audioScheduler: PlaybackAudioScheduler,
    private val callbacks: Callbacks,
    private val frameScheduler: FrameScheduler = ChoreographerFrameScheduler
) {
    interface Callbacks {
        fun onLoadingCancelled()
        fun onPlayheadChanged(score: ParsedScore?, playheadSeconds: Double)
        fun onStateChanged()
        fun onPlaybackFinished()
    }

    interface FrameScheduler {
        fun postFrameCallback(callback: Choreographer.FrameCallback)
        fun removeFrameCallback(callback: Choreographer.FrameCallback)
    }

    object ChoreographerFrameScheduler : FrameScheduler {
        override fun postFrameCallback(callback: Choreographer.FrameCallback) {
            Choreographer.getInstance().postFrameCallback(callback)
        }

        override fun removeFrameCallback(callback: Choreographer.FrameCallback) {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }

    var score: ParsedScore? = null
    var playheadSeconds = 0.0
    var speed = 1.0
    var playing = false
    var loading = false
    var finished = false

    private var framePosted = false
    private var lastFrameNanos = 0L
    private var audioSchedulingSuspended = false

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        handleFrame(frameTimeNanos)
    }

    fun playOrPause(nextPlaying: Boolean) {
        if (playing == nextPlaying) {
            if (!nextPlaying && loading) {
                callbacks.onLoadingCancelled()
                loading = false
                stopClock()
                audioScheduler.stop()
            }
            if (nextPlaying) {
                postFrame()
            }
            callbacks.onStateChanged()
            return
        }
        playing = nextPlaying
        loading = false
        if (nextPlaying) {
            finished = false
            audioScheduler.reset(score, playheadSeconds)
            if (!audioSchedulingSuspended) {
                audioScheduler.schedule(score, playheadSeconds, speed, playing)
            }
            lastFrameNanos = 0L
            postFrame()
        } else {
            audioSchedulingSuspended = false
            stopClock()
            audioScheduler.stop()
        }
        callbacks.onStateChanged()
    }

    fun reset() {
        loading = false
        finished = false
        playheadSeconds = 0.0
        audioSchedulingSuspended = false
        audioScheduler.stop()
        audioScheduler.reset(score, playheadSeconds)
        playOrPause(false)
        callbacks.onPlayheadChanged(score, playheadSeconds)
        callbacks.onStateChanged()
    }

    fun beginSeekGesture() {
        if (audioSchedulingSuspended) {
            return
        }
        audioSchedulingSuspended = true
        lastFrameNanos = 0L
        audioScheduler.stop()
    }

    fun endSeekGesture() {
        if (!audioSchedulingSuspended) {
            return
        }
        audioSchedulingSuspended = false
        audioScheduler.reset(score, playheadSeconds)
        if (playing && !finished) {
            audioScheduler.schedule(score, playheadSeconds, speed, playing)
            lastFrameNanos = 0L
            postFrame()
        }
    }

    fun stopClock() {
        if (framePosted) {
            frameScheduler.removeFrameCallback(frameCallback)
            framePosted = false
        }
        lastFrameNanos = 0L
    }

    fun isPaused(): Boolean {
        return score != null &&
            !playing &&
            !loading &&
            !finished &&
            playheadSeconds > 0.001
    }

    private fun handleFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!playing) {
            lastFrameNanos = 0L
            return
        }
        val lastFrame = lastFrameNanos
        lastFrameNanos = if (audioSchedulingSuspended) {
            0L
        } else {
            frameTimeNanos
        }
        if (!audioSchedulingSuspended && lastFrame != 0L) {
            val deltaSeconds = ((frameTimeNanos - lastFrame) / 1_000_000_000.0)
                .coerceIn(0.0, MAX_NATIVE_FRAME_DELTA_SECONDS)
            playheadSeconds += deltaSeconds * speed
        }
        if (!audioSchedulingSuspended && !finished) {
            audioScheduler.schedule(score, playheadSeconds, speed, playing)
        }
        val duration = score?.duration ?: 0.0
        if (duration > 0.0 && playheadSeconds >= duration) {
            playheadSeconds = duration
            callbacks.onPlayheadChanged(score, playheadSeconds)
            finished = true
            playOrPause(false)
            callbacks.onPlaybackFinished()
            callbacks.onStateChanged()
            return
        }
        callbacks.onPlayheadChanged(score, playheadSeconds)
        postFrame()
    }

    private fun postFrame() {
        if (!framePosted) {
            framePosted = true
            frameScheduler.postFrameCallback(frameCallback)
        }
    }

    private companion object {
        const val MAX_NATIVE_FRAME_DELTA_SECONDS = 0.08
    }
}
