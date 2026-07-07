package icu.ringona.xensynth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackSessionTest {
    @Test
    fun waitsForBuiltInSoundFontBeforePlayback() {
        val callbacks = FakeCallbacks(needsDefaultSoundFont = true)
        val session = NativePlaybackSession(callbacks)

        session.togglePlayback()

        assertTrue(callbacks.loadingState)
        assertTrue(callbacks.pendingDefaultSoundFont)
        assertTrue(callbacks.requestDefaultSoundFontCalled)
        assertFalse(callbacks.nativePlaybackStarted)
    }

    @Test
    fun startsNativePlaybackWhenBuiltInSoundFontIsReady() {
        val callbacks = FakeCallbacks(needsDefaultSoundFont = false)
        val session = NativePlaybackSession(callbacks)

        session.togglePlayback()

        assertFalse(callbacks.pendingDefaultSoundFont)
        assertTrue(callbacks.nativePlaybackStarted)
    }

    @Test
    fun reportsUnavailableWhenNativeAudioCannotUseBuiltInSoundFont() {
        val callbacks = FakeCallbacks(
            nativeAudioReady = false,
            needsDefaultSoundFont = false
        )
        val session = NativePlaybackSession(callbacks)

        session.togglePlayback()

        assertFalse(callbacks.loadingState)
        assertFalse(callbacks.nativePlaybackStarted)
        assertTrue(callbacks.lastStatusText == "Built-in SoundFont unavailable")
    }

    private class FakeCallbacks(
        private val nativeAudioReady: Boolean = true,
        private val needsDefaultSoundFont: Boolean = false
    ) : NativePlaybackSession.Callbacks {
        var loadingState = false
        var pendingDefaultSoundFont = false
        var requestDefaultSoundFontCalled = false
        var nativePlaybackStarted = false
        var lastStatusText = ""

        override fun hasScore(): Boolean = true
        override fun isPlaying(): Boolean = false
        override fun isLoading(): Boolean = loadingState
        override fun isFinished(): Boolean = false
        override fun canUseNativeAudio(): Boolean = nativeAudioReady
        override fun needsDefaultSoundFont(): Boolean = needsDefaultSoundFont

        override fun requestDefaultSoundFont() {
            requestDefaultSoundFontCalled = true
        }

        override fun clearPendingDefaultSoundFontPlayback() {
            pendingDefaultSoundFont = false
        }

        override fun setPendingDefaultSoundFontPlayback(pending: Boolean) {
            pendingDefaultSoundFont = pending
        }

        override fun setLoading(loading: Boolean) {
            loadingState = loading
        }

        override fun resetNativePlayback() = Unit
        override fun setNativePlaying(playing: Boolean) = Unit

        override fun startNativeAudioPlayback() {
            nativePlaybackStarted = true
        }

        override fun setStatusText(text: String) {
            lastStatusText = text
        }

        override fun updatePlayButtonIcon() = Unit
        override fun updateStageControls() = Unit
    }
}
