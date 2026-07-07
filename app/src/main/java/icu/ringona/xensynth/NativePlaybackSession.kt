package icu.ringona.xensynth

class NativePlaybackSession(
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun hasScore(): Boolean
        fun isPlaying(): Boolean
        fun isLoading(): Boolean
        fun isFinished(): Boolean
        fun canUseNativeAudio(): Boolean
        fun needsDefaultSoundFont(): Boolean
        fun requestDefaultSoundFont()
        fun clearPendingDefaultSoundFontPlayback()
        fun setPendingDefaultSoundFontPlayback(pending: Boolean)
        fun setLoading(loading: Boolean)
        fun resetNativePlayback()
        fun setNativePlaying(playing: Boolean)
        fun setStatusText(text: String)
        fun startNativeAudioPlayback()
        fun updatePlayButtonIcon()
        fun updateStageControls()
    }

    fun togglePlayback() {
        if (!callbacks.hasScore()) {
            return
        }
        if (callbacks.isPlaying() || callbacks.isLoading()) {
            callbacks.clearPendingDefaultSoundFontPlayback()
            callbacks.setLoading(false)
            callbacks.setNativePlaying(false)
            return
        }
        if (callbacks.isFinished()) {
            callbacks.resetNativePlayback()
        }
        callbacks.setLoading(true)
        callbacks.updatePlayButtonIcon()
        callbacks.updateStageControls()
        if (callbacks.needsDefaultSoundFont()) {
            callbacks.setPendingDefaultSoundFontPlayback(true)
            callbacks.setStatusText("Loading built-in SoundFont")
            callbacks.requestDefaultSoundFont()
            return
        }
        if (callbacks.canUseNativeAudio()) {
            callbacks.startNativeAudioPlayback()
            return
        }
        callbacks.setLoading(false)
        callbacks.setStatusText("Built-in SoundFont unavailable")
        callbacks.updatePlayButtonIcon()
        callbacks.updateStageControls()
    }
}
