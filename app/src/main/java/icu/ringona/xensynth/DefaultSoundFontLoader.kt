package icu.ringona.xensynth

import icu.ringona.xensynth.audio.NativeAudio
import icu.ringona.xensynth.audio.NativeAudioEngine
import java.io.File
import java.io.IOException

class DefaultSoundFontLoader(
    private val cacheDir: File? = null,
    private val nativeAudio: NativeAudio = NativeAudioEngine
) {
    fun load(): Result<Boolean> {
        return runCatching {
            removeLegacyPlaintextCache()
            nativeAudio.loadBuiltinSf2()
        }
    }

    private fun removeLegacyPlaintextCache() {
        val legacyDir = cacheDir?.resolve(LEGACY_CACHE_DIR) ?: return
        if (legacyDir.exists() && !legacyDir.deleteRecursively()) {
            throw IOException("Could not remove legacy SoundFont cache")
        }
    }

    private companion object {
        const val LEGACY_CACHE_DIR = "blob"
    }
}
