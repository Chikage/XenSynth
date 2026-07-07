package icu.ringona.xensynth

import android.view.Choreographer
import icu.ringona.xensynth.audio.NativeAudioScheduler
import icu.ringona.xensynth.audio.PlaybackAudioScheduler
import icu.ringona.xensynth.midi.ParsedScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackCoordinatorTest {
    @Test
    fun requestsBuiltInSoundFontBeforePlayback() {
        val callbacks = FakeCallbacks()
        val audio = FakeAudioScheduler()
        val coordinator = coordinator(callbacks, audio).apply {
            score = score()
            setAudioReady(true)
        }

        coordinator.togglePlayback()

        assertTrue(coordinator.loading)
        assertTrue(callbacks.requestDefaultSoundFontCalled)
        assertTrue(callbacks.lastStatusText == "Loading built-in SoundFont")
        assertFalse(coordinator.playing)
        assertFalse(audio.ensureStartedCalled)
    }

    @Test
    fun resumesPendingPlaybackWhenBuiltInSoundFontBecomesReady() {
        val callbacks = FakeCallbacks()
        val audio = FakeAudioScheduler()
        val coordinator = coordinator(callbacks, audio).apply {
            score = score()
            setAudioReady(true)
        }

        coordinator.togglePlayback()
        coordinator.onDefaultSoundFontLoadFinished(true)

        assertFalse(coordinator.loading)
        assertTrue(coordinator.playing)
        assertTrue(audio.ensureStartedCalled)
        assertTrue(audio.resetCalls > 0)
        assertTrue(audio.scheduleCalls > 0)
    }

    @Test
    fun reportsStartFailureAfterPendingSoundFontLoad() {
        val callbacks = FakeCallbacks()
        val audio = FakeAudioScheduler(ensureStartedResult = false)
        val coordinator = coordinator(callbacks, audio).apply {
            score = score()
            setAudioReady(true)
        }

        coordinator.togglePlayback()
        coordinator.onDefaultSoundFontLoadFinished(true)

        assertFalse(coordinator.loading)
        assertFalse(coordinator.playing)
        assertTrue(audio.ensureStartedCalled)
        assertTrue(callbacks.lastStatusText == "Native audio start failed")
    }

    @Test
    fun skipsDefaultSoundFontLoadWhenAlreadyLoading() {
        val coordinator = coordinator(FakeCallbacks(), FakeAudioScheduler()).apply {
            setAudioReady(true)
        }

        assertFalse(coordinator.shouldLoadDefaultSoundFont(soundFontLoading = true))
    }

    @Test
    fun seekGestureSuspendsAudioSchedulingUntilRelease() {
        val callbacks = FakeCallbacks()
        val audio = FakeAudioScheduler()
        val frameScheduler = ManualFrameScheduler()
        val coordinator = coordinator(callbacks, audio, frameScheduler).apply {
            score = score()
        }

        coordinator.playOrPause(true)
        coordinator.beginSeekGesture()
        coordinator.beginSeekGesture()
        coordinator.playheadSeconds = 0.5
        frameScheduler.runFrame(1_000_000_000L)
        frameScheduler.runFrame(1_100_000_000L)

        assertEquals(1, audio.stopCalls)
        assertEquals(1, audio.scheduleCalls)
        assertEquals(0.5, coordinator.playheadSeconds, 0.0001)

        coordinator.endSeekGesture()

        assertEquals(2, audio.resetCalls)
        assertEquals(2, audio.scheduleCalls)
        assertEquals(0.5, audio.lastResetPlayheadSeconds, 0.0001)
        assertEquals(0.5, audio.lastSchedulePlayheadSeconds, 0.0001)
    }

    private fun coordinator(
        callbacks: FakeCallbacks,
        audio: FakeAudioScheduler,
        frameScheduler: NativePlaybackController.FrameScheduler = NoOpFrameScheduler
    ): NativePlaybackCoordinator {
        return NativePlaybackCoordinator(
            callbacks = callbacks,
            audioSchedulerFactory = { callback ->
                audio.callbacks = callback
                audio
            },
            frameScheduler = frameScheduler
        )
    }

    private class FakeCallbacks : NativePlaybackCoordinator.Callbacks {
        var requestDefaultSoundFontCalled = false
        var lastStatusText = ""

        override fun requestDefaultSoundFont() {
            requestDefaultSoundFontCalled = true
        }

        override fun onPlayheadChanged(score: ParsedScore?, playheadSeconds: Double) = Unit
        override fun onStateChanged() = Unit
        override fun onPlaybackFinished() = Unit

        override fun onStatusText(text: String) {
            lastStatusText = text
        }
    }

    private class FakeAudioScheduler(
        private val ensureStartedResult: Boolean = true
    ) : PlaybackAudioScheduler {
        lateinit var callbacks: NativeAudioScheduler.Callbacks
        var ensureStartedCalled = false
        var resetCalls = 0
        var scheduleCalls = 0
        var stopCalls = 0
        var lastResetPlayheadSeconds = 0.0
        var lastSchedulePlayheadSeconds = 0.0

        override fun reset(score: ParsedScore?, playheadSeconds: Double) {
            resetCalls++
            lastResetPlayheadSeconds = playheadSeconds
        }

        override fun stop() {
            stopCalls++
        }

        override fun ensureStarted(): Boolean {
            ensureStartedCalled = true
            callbacks.onAudioReadyChanged(ensureStartedResult)
            return ensureStartedResult
        }

        override fun schedule(
            score: ParsedScore?,
            playheadSeconds: Double,
            speed: Double,
            playing: Boolean
        ) {
            scheduleCalls++
            lastSchedulePlayheadSeconds = playheadSeconds
        }
    }

    private object NoOpFrameScheduler : NativePlaybackController.FrameScheduler {
        override fun postFrameCallback(callback: Choreographer.FrameCallback) = Unit
        override fun removeFrameCallback(callback: Choreographer.FrameCallback) = Unit
    }

    private class ManualFrameScheduler : NativePlaybackController.FrameScheduler {
        private var callback: Choreographer.FrameCallback? = null

        override fun postFrameCallback(callback: Choreographer.FrameCallback) {
            this.callback = callback
        }

        override fun removeFrameCallback(callback: Choreographer.FrameCallback) {
            if (this.callback == callback) {
                this.callback = null
            }
        }

        fun runFrame(frameTimeNanos: Long) {
            val postedCallback = callback ?: return
            callback = null
            postedCallback.doFrame(frameTimeNanos)
        }
    }

    private companion object {
        fun score(): ParsedScore {
            return ParsedScore(
                title = "test.mid",
                format = "MIDX",
                ticksPerQuarter = 480,
                tempos = emptyList(),
                meters = emptyList(),
                tempoMap = emptyList(),
                rawEvents = emptyList(),
                notes = emptyList(),
                longNotes = emptyList(),
                duration = 1.0
            )
        }
    }
}
